import { useCallback, useEffect, useState } from 'react';
import { SIGNED_OUT, type AuthClient, type Session } from './auth/AuthClient';
import type { RuntimeConfig } from './config/runtimeConfig';
import { Alert, Loading } from './components/ui';
import { useHashRoute } from './routing/useHashRoute';
import { Consents } from './screens/Consents';
import { FitnessDeclaration } from './screens/FitnessDeclaration';
import { Home } from './screens/Home';
import { JobOffers } from './screens/JobOffers';
import { MyAbsence } from './screens/MyAbsence';
import { MyAdvice } from './screens/MyAdvice';
import { MyAppointments } from './screens/MyAppointments';
import { MyDocuments } from './screens/MyDocuments';
import { MyReturnToWork } from './screens/MyReturnToWork';
import { MySurveillance } from './screens/MySurveillance';
import { MyTelehealth } from './screens/MyTelehealth';
import { MyTreatment } from './screens/MyTreatment';
import { PreEmployment } from './screens/PreEmployment';
import { Privacy } from './screens/Privacy';
import { Profile } from './screens/Profile';
import { SelfReferral } from './screens/SelfReferral';
import { Campaigns } from './screens/Campaigns';
import { CheckIns } from './screens/CheckIns';
import { PeerSupport } from './screens/PeerSupport';
import { Support } from './screens/Support';
import { Testing } from './screens/Testing';
import { Travel } from './screens/Travel';
import { startIdleTimer } from './security/idleTimeout';
import { errorName, log } from './security/log';
import { portalStrings, type PortalStrings } from './i18n/portal';
import { ohStrings, type OhStrings } from './i18n/occupational';
import { careStrings, type CareStrings } from './i18n/care';
import { wellbeingStrings, type WellbeingStrings } from './i18n/wellbeing';
import { navFor, resolveRoute } from './routing/navigation';
import { DEFAULT_LANGUAGES, dirFor, initialLanguage } from './i18n/strings';

type Notice = 'idle' | 'expired' | 'login-failed' | undefined;

export function App({ client, config }: { client: AuthClient; config: RuntimeConfig }) {
  const [session, setSession] = useState<Session>();
  const [notice, setNotice] = useState<Notice>();
  // App language for the portal screens (en/ar/fr); in memory only, initial value from the browser languages.
  const [lang, setLang] = useState(() => initialLanguage(DEFAULT_LANGUAGES, typeof navigator !== 'undefined' ? navigator.languages : []));
  const t = portalStrings(lang);
  const oh = ohStrings(lang);
  const care = careStrings(lang);
  const wb = wellbeingStrings(lang);
  const hashRoute = useHashRoute();

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

  // 15-minute idle auto-logout while signed in.
  useEffect(() => {
    if (!session?.authenticated) return;
    return startIdleTimer(() => void signOut('idle'));
  }, [session?.authenticated, signOut]);

  const signIn = async () => {
    setNotice(undefined);
    try {
      const s = await client.login();
      if (s) setSession(s);
    } catch (e) {
      log.warn('auth.login-failed', { errorName: errorName(e) });
      setNotice('login-failed');
    }
  };

  if (!session) {
    return (
      <main id="main" className="centered">
        <Loading label="Starting WellnessZ…" />
      </main>
    );
  }

  if (!session.authenticated) {
    return (
      <main id="main" className="centered signin">
        <div className="signin-card">
          <div className="brand-mark" aria-hidden="true">
            Z
          </div>
          <h1>WellnessZ</h1>
          <p className="lead">Occupational Health for employees and crew</p>
          {notice === 'idle' && <Alert kind="info">You were signed out after 15 minutes of inactivity.</Alert>}
          {notice === 'expired' && <Alert kind="info">Your session has ended. Please sign in again.</Alert>}
          {notice === 'login-failed' && <Alert kind="error">Sign-in was not completed. Please try again.</Alert>}
          <button type="button" onClick={() => void signIn()}>
            Sign in with your company account
          </button>
        </div>
      </main>
    );
  }

  const screenProps = { client, session, onSessionExpired, lang };
  // Candidates without employee features only ever see the pre-employment section.
  const route = resolveRoute(hashRoute, session.roles);
  const nav = navFor(session.roles);
  let screen;
  switch (route) {
    case '/appointments':
      screen = <MyAppointments {...screenProps} />;
      break;
    case '/treatment':
      screen = <MyTreatment {...screenProps} />;
      break;
    case '/telehealth':
      screen = <MyTelehealth {...screenProps} />;
      break;
    case '/support':
      screen = <Support {...screenProps} />;
      break;
    case '/peer-support':
      screen = <PeerSupport {...screenProps} />;
      break;
    case '/travel':
      screen = <Travel {...screenProps} />;
      break;
    case '/check-ins':
      screen = <CheckIns {...screenProps} />;
      break;
    case '/campaigns':
      screen = <Campaigns {...screenProps} />;
      break;
    case '/testing':
      screen = <Testing {...screenProps} />;
      break;
    case '/absence':
      screen = <MyAbsence {...screenProps} />;
      break;
    case '/surveillance':
      screen = <MySurveillance {...screenProps} />;
      break;
    case '/pre-employment':
      screen = <PreEmployment {...screenProps} />;
      break;
    case '/refer':
      screen = <SelfReferral {...screenProps} isNative={client.mode === 'native-oidc'} />;
      break;
    case '/advice':
      screen = <MyAdvice {...screenProps} />;
      break;
    case '/documents':
      screen = <MyDocuments {...screenProps} />;
      break;
    case '/rtw':
      screen = <MyReturnToWork {...screenProps} />;
      break;
    case '/job-offers':
      screen = <JobOffers {...screenProps} />;
      break;
    case '/privacy':
      screen = <Privacy {...screenProps} />;
      break;
    case '/fitness-declaration':
      screen = <FitnessDeclaration {...screenProps} />;
      break;
    case '/consents':
      screen = <Consents {...screenProps} />;
      break;
    case '/profile':
      screen = <Profile {...screenProps} config={config} />;
      break;
    case '/':
      screen = <Home {...screenProps} />;
      break;
    default:
      screen = (
        <section>
          <h1>Page not found</h1>
          <a href="#/">Go to home</a>
        </section>
      );
  }

  return (
    <>
      <a className="skip-link" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>
        Skip to content
      </a>
      <header className="app-header">
        <span className="brand">WellnessZ</span>
        <label className="header-lang">
          <span className="visually-hidden">{t.language}</span>
          <select value={lang} onChange={(e) => setLang(e.target.value)}>
            {DEFAULT_LANGUAGES.map((l) => (
              <option key={l.code} value={l.code} lang={l.code}>
                {l.label}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="link-button" onClick={() => void signOut('user')}>
          Sign out
        </button>
      </header>
      <nav className="app-nav" aria-label="Main" dir={dirFor(lang)} lang={lang}>
        <ul>
          {nav.map((n) => (
            <li key={n.path}>
              <a href={`#${n.path}`} aria-current={route === n.path ? 'page' : undefined}>
                {n.source === 'oh'
                  ? oh.nav[n.key as keyof OhStrings['nav']]
                  : n.source === 'care'
                    ? care.nav[n.key as keyof CareStrings['nav']]
                    : n.source === 'wellbeing'
                      ? wb.nav[n.key as keyof WellbeingStrings['nav']]
                      : t.nav[n.key as keyof PortalStrings['nav']]}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" tabIndex={-1} key={route}>
        {screen}
      </main>
    </>
  );
}
