import { useCallback, useEffect, useState } from 'react';
import { SIGNED_OUT, type AuthClient, type Session } from './auth/AuthClient';
import { hasWorkspaceAccess, resolveFeatureRoute, resolveReferralRoute, workspaceNav } from './auth/roles';
import { Alert, Loading } from './components/ui';
import { useHashRoute } from './routing/useHashRoute';
import { AccessRestricted } from './screens/AccessRestricted';
import { Analytics } from './screens/Analytics';
import { CaseStatus } from './screens/CaseStatus';
import { Documents } from './screens/Documents';
import { DutyProposals } from './screens/DutyProposals';
import { FeatureNotAvailable } from './screens/FeatureNotAvailable';
import { HrReferral } from './screens/HrReferral';
import { IhrAssessments } from './screens/IhrAssessments';
import { JobOffers } from './screens/JobOffers';
import { ManagerReferral } from './screens/ManagerReferral';
import { ReferralNotAvailable } from './screens/Referral';
import { RtwPlans } from './screens/RtwPlans';
import { TeamCases } from './screens/TeamCases';
import { TeamRestrictions } from './screens/TeamRestrictions';
import { startIdleTimer } from './security/idleTimeout';
import { errorName, log } from './security/log';

type Notice = 'idle' | 'expired' | undefined;

export function App({ client }: { client: AuthClient }) {
  const [session, setSession] = useState<Session>();
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
      setNotice(reason === 'user' ? undefined : reason);
      await client.logout(reason === 'idle' ? 'idle' : 'user');
    },
    [client],
  );
  const onSessionExpired = useCallback(() => void signOut('expired'), [signOut]);

  useEffect(() => {
    if (!session?.authenticated) return;
    return startIdleTimer(() => void signOut('idle'));
  }, [session?.authenticated, signOut]);

  if (!session) {
    return (
      <main id="main" className="centered">
        <Loading label="Loading workspace…" />
      </main>
    );
  }

  if (!session.authenticated) {
    return (
      <main id="main" className="centered">
        <div className="signin-card">
          <div className="brand-mark" aria-hidden="true">
            Z
          </div>
          <h1>WellnessZ Workspace</h1>
          <p className="lead">For managers, HR and Occupational Health</p>
          {notice === 'idle' && <Alert kind="info">You were signed out after 15 minutes of inactivity.</Alert>}
          {notice === 'expired' && <Alert kind="info">Your session has ended. Please sign in again.</Alert>}
          <button type="button" onClick={() => void client.login()}>
            Sign in with your company account
          </button>
        </div>
      </main>
    );
  }

  if (!hasWorkspaceAccess(session.roles)) {
    return <AccessRestricted onSignOut={() => void signOut('user')} />;
  }

  const props = { client, session, onSessionExpired };
  const caseMatch = /^\/cases\/([^/]+)$/.exec(route);
  const referral = resolveReferralRoute(route, session.roles);
  const feature = resolveFeatureRoute(route, session.roles);
  let screen;
  if (route === '/') screen = <TeamCases {...props} />;
  else if (feature && !feature.allowed) screen = <FeatureNotAvailable feature={feature.feature} />;
  else if (feature?.feature === 'restrictions') screen = <TeamRestrictions {...props} />;
  else if (feature?.feature === 'dutyProposals') screen = <DutyProposals {...props} />;
  else if (feature?.feature === 'rtwPlans') screen = <RtwPlans {...props} />;
  else if (feature?.feature === 'documents') screen = <Documents {...props} />;
  else if (feature?.feature === 'jobOffers' && feature.caseId) screen = <JobOffers {...props} caseId={feature.caseId} />;
  else if (feature?.feature === 'ihr' && feature.caseId) screen = <IhrAssessments {...props} caseId={feature.caseId} />;
  else if (caseMatch?.[1]) screen = <CaseStatus {...props} caseId={decodeURIComponent(caseMatch[1])} />;
  else if (referral === 'manager') screen = <ManagerReferral {...props} />;
  else if (referral === 'hr') screen = <HrReferral {...props} />;
  else if (referral === 'forbidden') screen = <ReferralNotAvailable kind={route === '/refer/hr' ? 'hr' : 'manager'} />;
  else if (route === '/analytics') screen = <Analytics {...props} />;
  else
    screen = (
      <section>
        <h1>Page not found</h1>
        <a href="#/">Go to team cases</a>
      </section>
    );

  const current = caseMatch || feature?.caseId ? '/' : route;
  return (
    <>
      <a className="skip-link" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>
        Skip to content
      </a>
      <header className="app-header">
        <span className="brand">WellnessZ Workspace</span>
        <span className="header-user">
          {session.name && <span className="small">{session.name}</span>}
          <button type="button" className="link-button" onClick={() => void signOut('user')}>
            Sign out
          </button>
        </span>
      </header>
      <nav className="app-nav" aria-label="Main">
        <ul>
          {workspaceNav(session.roles).map((n) => (
            <li key={n.path}>
              <a href={`#${n.path}`} aria-current={current === n.path ? 'page' : undefined}>
                {n.label}
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
