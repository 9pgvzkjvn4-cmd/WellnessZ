import { useCallback, useEffect, useMemo, useState } from 'react';
import { SessionExpiredError, SIGNED_OUT, type AuthClient, type Session } from './auth/AuthClient';
import { loadMe, type Me } from './auth/me';
import { currentArea, homeRoute, portalNav, resolveAccess, resolveRoute } from './auth/roles';
import { Alert, Loading, PageTitle } from './components/ui';
import { navigate, useHashRoute } from './routing/useHashRoute';
import { AccessDenied } from './screens/AccessDenied';
import { ClaimDetail } from './screens/ClaimDetail';
import { Claims } from './screens/Claims';
import { Documents } from './screens/Documents';
import { EapReferrals } from './screens/EapReferrals';
import { Preauthorisations } from './screens/Preauthorisations';
import { ReferralDetail } from './screens/ReferralDetail';
import { Referrals } from './screens/Referrals';
import { SignIn } from './screens/SignIn';
import { Telehealth } from './screens/Telehealth';
import { startIdleTimer } from './security/idleTimeout';
import { errorName, log } from './security/log';
import { S } from './strings';

type Notice = 'idle' | 'expired' | undefined;
type MeState = { status: 'loading' } | { status: 'ok'; me: Me } | { status: 'error' };

export function App({ client }: { client: AuthClient }) {
  const [session, setSession] = useState<Session>();
  const [me, setMe] = useState<MeState>({ status: 'loading' });
  const [meTick, setMeTick] = useState(0);
  const [notice, setNotice] = useState<Notice>();
  const route = useHashRoute();

  useEffect(() => {
    client.init().then(setSession, (e: unknown) => {
      log.warn('auth.init-failed', { errorName: errorName(e) });
      setSession(SIGNED_OUT);
    });
  }, [client]);

  const signOut = useCallback(
    async (reason: 'user' | 'idle' | 'expired') => {
      setSession(SIGNED_OUT);
      setMe({ status: 'loading' });
      setNotice(reason === 'user' ? undefined : reason);
      await client.logout(reason === 'idle' ? 'idle' : 'user');
    },
    [client],
  );
  const onSessionExpired = useCallback(() => void signOut('expired'), [signOut]);

  // `/me` is authoritative for the partner roles (granted by the core API only with MFA proof).
  useEffect(() => {
    if (!session?.authenticated) return;
    let cancelled = false;
    loadMe(client).then(
      (m) => !cancelled && setMe({ status: 'ok', me: m }),
      (e: unknown) => {
        if (cancelled) return;
        if (e instanceof SessionExpiredError) {
          onSessionExpired();
          return;
        }
        log.warn('auth.me-failed', { errorName: errorName(e) });
        setMe({ status: 'error' });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [client, session?.authenticated, meTick, onSessionExpired]);

  useEffect(() => {
    if (!session?.authenticated) return;
    return startIdleTimer(() => void signOut('idle'));
  }, [session?.authenticated, signOut]);

  const roles = useMemo(() => (me.status === 'ok' ? me.me.roles : []), [me]);
  const access = session?.authenticated && me.status === 'ok' ? resolveAccess(session.roles, me.me.roles) : undefined;
  const resolved = resolveRoute(route, roles);

  // `#/` → the first area of the session's role
  useEffect(() => {
    if (access === 'partner' && resolved.kind === 'home') navigate(homeRoute(roles));
  }, [access, resolved.kind, roles]);

  if (!session || (session.authenticated && me.status === 'loading')) {
    return (
      <main id="main" className="centered">
        <Loading label={S.app.loading} />
      </main>
    );
  }

  if (!session.authenticated) return <SignIn notice={notice} onSignIn={() => void client.login()} />;

  if (me.status === 'error') {
    return (
      <main id="main" className="centered">
        <div className="signin-card">
          <PageTitle>{S.app.name}</PageTitle>
          <Alert kind="error">{S.app.meFailed}</Alert>
          <div className="actions center">
            <button type="button" onClick={() => setMeTick((t) => t + 1)}>
              {S.app.retry}
            </button>
            <button type="button" className="secondary" onClick={() => void signOut('user')}>
              {S.app.signOut}
            </button>
          </div>
        </div>
      </main>
    );
  }

  if (me.status !== 'ok' || access !== 'partner') {
    return <AccessDenied kind={access === 'other' ? 'other' : 'mfa'} onSignOut={() => void signOut('user')} />;
  }

  const props = { client, session, me: me.me, onSessionExpired };
  let screen;
  switch (resolved.kind) {
    case 'home':
      screen = <Loading />;
      break;
    case 'area':
      if (!resolved.allowed) screen = <FeatureNotAvailable />;
      else if (resolved.area === 'referrals') screen = <Referrals {...props} />;
      else if (resolved.area === 'eapReferrals') screen = <EapReferrals {...props} />;
      else if (resolved.area === 'telehealth') screen = <Telehealth {...props} />;
      else if (resolved.area === 'documents') screen = <Documents {...props} />;
      else if (resolved.area === 'claims') screen = <Claims {...props} />;
      else screen = <Preauthorisations {...props} />;
      break;
    case 'referral':
      screen = resolved.allowed ? <ReferralDetail {...props} referralId={resolved.id} /> : <FeatureNotAvailable />;
      break;
    case 'claim':
      screen = resolved.allowed ? <ClaimDetail {...props} claimId={resolved.id} /> : <FeatureNotAvailable />;
      break;
    default:
      screen = (
        <section>
          <PageTitle>{S.app.notFoundTitle}</PageTitle>
          <a href={`#${homeRoute(roles)}`}>{S.app.notFoundLink}</a>
        </section>
      );
  }

  const active = currentArea(route);
  return (
    <>
      <a
        className="skip-link"
        href="#main"
        onClick={(e) => {
          e.preventDefault();
          document.getElementById('main')?.focus();
        }}
      >
        {S.app.skip}
      </a>
      <header className="app-header">
        <span className="brand">{S.app.name}</span>
        <span className="header-user">
          {me.me.orgCode && (
            <span className="org-badge" title={me.me.orgScope}>
              <span className="visually-hidden">{S.app.organisation}: </span>
              {me.me.orgCode}
            </span>
          )}
          {(me.me.displayName ?? session.name) && <span className="small header-name">{me.me.displayName ?? session.name}</span>}
          <button type="button" className="link-button" onClick={() => void signOut('user')}>
            {S.app.signOut}
          </button>
        </span>
      </header>
      <nav className="app-nav" aria-label={S.app.nav}>
        <ul>
          {portalNav(roles).map((n) => (
            <li key={n.path}>
              <a href={`#${n.path}`} aria-current={active === n.area ? 'page' : undefined}>
                {S.nav[n.area]}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" tabIndex={-1} key={route} className="wide">
        {screen}
      </main>
    </>
  );
}

function FeatureNotAvailable() {
  return (
    <section>
      <PageTitle>{S.access.featureTitle}</PageTitle>
      <p>{S.access.feature}</p>
    </section>
  );
}
