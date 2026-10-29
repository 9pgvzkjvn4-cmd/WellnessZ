import { useCallback, useEffect, useState } from 'react';
import { SIGNED_OUT, type AuthClient, type Session } from './auth/AuthClient';
import type { RuntimeConfig } from './config/runtimeConfig';
import { Alert, Loading } from './components/ui';
import { useHashRoute } from './routing/useHashRoute';
import { Consents } from './screens/Consents';
import { FitnessDeclaration } from './screens/FitnessDeclaration';
import { Home } from './screens/Home';
import { JobOffers } from './screens/JobOffers';
import { MyAdvice } from './screens/MyAdvice';
import { MyDocuments } from './screens/MyDocuments';
import { MyReturnToWork } from './screens/MyReturnToWork';
import { Privacy } from './screens/Privacy';
import { Profile } from './screens/Profile';
import { SelfReferral } from './screens/SelfReferral';
import { startIdleTimer } from './security/idleTimeout';
import { errorName, log } from './security/log';
import { portalStrings, type PortalStrings } from './i18n/portal';
import { DEFAULT_LANGUAGES, dirFor, initialLanguage } from './i18n/strings';

const NAV: readonly { path: string; key: keyof PortalStrings['nav'] }[] = [
  { path: '/', key: 'home' },
  { path: '/refer', key: 'refer' },
  { path: '/advice', key: 'advice' },
  { path: '/documents', key: 'documents' },
  { path: '/rtw', key: 'rtw' },
  { path: '/job-offers', key: 'offers' },
  { path: '/fitness-declaration', key: 'declaration' },
  { path: '/privacy', key: 'privacy' },
  { path: '/consents', key: 'consents' },
  { path: '/profile', key: 'profile' },
];

type Notice = 'idle' | 'expired' | 'login-failed' | undefined;

export function App({ client, config }: { client: AuthClient; config: RuntimeConfig }) {
  const [session, setSession] = useState<Session>();
  const [notice, setNotice] = useState<Notice>();
  // App language for the portal screens (en/ar/fr); in memory only, initial value from the browser languages.
  const [lang, setLang] = useState(() => initialLanguage(DEFAULT_LANGUAGES, typeof navigator !== 'undefined' ? navigator.languages : []));
  const t = portalStrings(lang);
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
  let screen;
  switch (route) {
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
          {NAV.map((n) => (
            <li key={n.path}>
              <a href={`#${n.path}`} aria-current={route === n.path ? 'page' : undefined}>
                {t.nav[n.key]}
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
