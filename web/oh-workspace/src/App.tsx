import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { SIGNED_OUT, type AuthClient, type Session } from './auth/AuthClient';
import { can, featureForRoute, hasWorkspaceAccess, homeRoute, navFor, type NavItem } from './auth/roles';
import { Alert, Loading, Restricted } from './components/ui';
import { navigate, useHashRoute } from './routing/useHashRoute';
import { AccessRestricted } from './screens/AccessRestricted';
import { AssignmentRules, IntakeRules, NotificationMatrix, RecordabilityMapping, SlaPolicies } from './screens/admin/ConfigScreens';
import { Protocols } from './screens/admin/Protocols';
import { CaseView } from './screens/case/CaseView';
import { Caseload } from './screens/Caseload';
import { EmployeeTimelineScreen } from './screens/EmployeeTimeline';
import { Escalations } from './screens/Escalations';
import { Handovers } from './screens/Handovers';
import { Dashboard } from './screens/intake/Dashboard';
import { Dlq } from './screens/intake/Dlq';
import { IncidentCases } from './screens/intake/IncidentCases';
import { Reconciliation } from './screens/intake/Reconciliation';
import { Suggestions } from './screens/intake/Suggestions';
import { Queues } from './screens/Queues';
import { Reception } from './screens/Reception';
import { TriageAudit } from './screens/TriageAudit';
import type { ScreenProps } from './screens/types';
import { startIdleTimer } from './security/idleTimeout';
import { errorName, log } from './security/log';

type Notice = 'idle' | 'expired' | undefined;

function renderRoute(route: string, props: ScreenProps): ReactNode {
  const caseMatch = /^\/cases\/([^/]+)(?:\/([a-z-]+))?$/.exec(route);
  if (caseMatch?.[1]) return <CaseView key={caseMatch[1]} {...props} caseId={decodeURIComponent(caseMatch[1])} tab={caseMatch[2] ?? 'overview'} />;
  const empMatch = /^\/employees\/([^/]+)\/timeline$/.exec(route);
  if (empMatch?.[1]) return <EmployeeTimelineScreen {...props} employeeId={decodeURIComponent(empMatch[1])} />;
  const incMatch = /^\/intake\/incidents(?:\/([^/]+))?$/.exec(route);
  if (incMatch) return <IncidentCases {...props} incidentId={incMatch[1] ? decodeURIComponent(incMatch[1]) : undefined} />;
  switch (route) {
    case '/queues': return <Queues {...props} />;
    case '/escalations': return <Escalations {...props} />;
    case '/handovers': return <Handovers {...props} />;
    case '/reception': return <Reception {...props} />;
    case '/intake/suggestions': return <Suggestions {...props} />;
    case '/intake/reconciliation': return <Reconciliation {...props} />;
    case '/intake/dashboard': return <Dashboard {...props} />;
    case '/intake/dlq': return <Dlq {...props} />;
    case '/caseload': return <Caseload {...props} />;
    case '/triage-audit': return <TriageAudit {...props} />;
    case '/admin/protocols': return <Protocols {...props} />;
    case '/admin/assignment-rules': return <AssignmentRules {...props} />;
    case '/admin/sla': return <SlaPolicies {...props} />;
    case '/admin/intake-rules': return <IntakeRules {...props} />;
    case '/admin/notifications': return <NotificationMatrix {...props} />;
    case '/admin/recordability-mapping': return <RecordabilityMapping {...props} />;
    default:
      return (
        <section>
          <h1>Page not found</h1>
          <a href="#/">Go to start</a>
        </section>
      );
  }
}

function groupNav(items: NavItem[]): [string, NavItem[]][] {
  const groups = new Map<string, NavItem[]>();
  for (const i of items) groups.set(i.group, [...(groups.get(i.group) ?? []), i]);
  return [...groups.entries()];
}

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

  // Redirect "/" to the role's landing screen.
  const roles = session?.roles;
  useEffect(() => {
    if (session?.authenticated && route === '/') navigate(homeRoute(roles));
  }, [session?.authenticated, route, roles]);

  if (!session) {
    return (
      <main id="main" className="centered">
        <Loading label="Loading OH workspace…" />
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
          <h1>WellnessZ OH Workspace</h1>
          <p className="lead">Clinical console for Occupational Health, clinic reception and first aiders</p>
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

  const props: ScreenProps = { client, session, onSessionExpired };
  const feature = featureForRoute(route);
  const screen = route === '/' ? <Loading /> : feature && !can(session.roles, feature) ? <Restricted /> : renderRoute(route, props);
  const nav = navFor(session.roles);
  const current = route.startsWith('/cases/') ? '/queues' : route.replace(/^(\/intake\/incidents)\/.*$/, '$1');

  return (
    <div className="shell">
      <a className="skip-link" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>
        Skip to content
      </a>
      <header className="app-header">
        <span className="brand">
          <span className="brand-dot" aria-hidden="true">Z</span> WellnessZ <span className="brand-sub">OH Workspace</span>
        </span>
        <span className="header-user">
          {session.name && <span className="small">{session.name}</span>}
          <span className="small role-list">{session.roles.filter((r) => r !== 'EMPLOYEE').map((r) => r.replace(/_/g, ' ').toLowerCase()).join(' · ')}</span>
          <button type="button" className="link-button" onClick={() => void signOut('user')}>
            Sign out
          </button>
        </span>
      </header>
      <div className="layout">
        <nav className="side-nav" aria-label="Main">
          {groupNav(nav).map(([group, items]) => (
            <div key={group} className="nav-group">
              <h2 className="nav-heading">{group}</h2>
              <ul>
                {items.map((n) => (
                  <li key={n.path}>
                    <a href={`#${n.path}`} aria-current={current === n.path ? 'page' : undefined}>
                      {n.label}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <main id="main" tabIndex={-1} key={route.replace(/^(\/cases\/[^/]+)\/.*$/, '$1')}>
          {screen}
        </main>
      </div>
    </div>
  );
}
