import { lazy, Suspense, useCallback, useEffect, useState, type ReactNode } from 'react';
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

// Increment-2 areas are code-split per module (loaded on first use; same-origin chunks, CSP script-src 'self').
const FitnessAdmin = () => import('./screens/admin/FitnessAdmin');
const RecordAdmin = () => import('./screens/admin/RecordAdmin');
const Dpo = () => import('./screens/dpo/DpoScreens');
const Work = () => import('./screens/fitness/WorkScreens');
const FitnessStandardsAdmin = lazy(() => FitnessAdmin().then((m) => ({ default: m.FitnessStandardsAdmin })));
const RestrictionCatalogueAdmin = lazy(() => FitnessAdmin().then((m) => ({ default: m.RestrictionCatalogueAdmin })));
const JobDemandsAdmin = lazy(() => FitnessAdmin().then((m) => ({ default: m.JobDemandsAdmin })));
const EncounterTemplatesAdmin = lazy(() => RecordAdmin().then((m) => ({ default: m.EncounterTemplatesAdmin })));
const TerminologyAdmin = lazy(() => RecordAdmin().then((m) => ({ default: m.TerminologyAdmin })));
const CdsStatistics = lazy(() => import('./screens/CdsStatistics').then((m) => ({ default: m.CdsStatistics })));
const ChartScreen = lazy(() => import('./screens/chart/ChartScreen').then((m) => ({ default: m.ChartScreen })));
const EmployeeChartSearch = lazy(() => import('./screens/chart/EmployeeChartSearch').then((m) => ({ default: m.EmployeeChartSearch })));
const ScanDocuments = lazy(() => import('./screens/documents/ScanDocuments').then((m) => ({ default: m.ScanDocuments })));
const BreakGlassReviews = lazy(() => Dpo().then((m) => ({ default: m.BreakGlassReviews })));
const BreakGlassReport = lazy(() => Dpo().then((m) => ({ default: m.BreakGlassReport })));
const DisclosureLog = lazy(() => Dpo().then((m) => ({ default: m.DisclosureLog })));
const RetentionScreen = lazy(() => Dpo().then((m) => ({ default: m.RetentionScreen })));
const EncounterEditor = lazy(() => import('./screens/encounter/EncounterEditor').then((m) => ({ default: m.EncounterEditor })));
const UnsignedNotes = lazy(() => import('./screens/encounter/UnsignedNotes').then((m) => ({ default: m.UnsignedNotes })));
const SignoffQueue = lazy(() => Work().then((m) => ({ default: m.SignoffQueue })));
const ActiveRestrictions = lazy(() => Work().then((m) => ({ default: m.ActiveRestrictions })));
const RtwOverdue = lazy(() => Work().then((m) => ({ default: m.RtwOverdue })));
const CrewDashboard = lazy(() => Work().then((m) => ({ default: m.CrewDashboard })));
const RosterReconciliation = lazy(() => Work().then((m) => ({ default: m.RosterReconciliation })));
const ResultsWorklist = lazy(() => import('./screens/results/ResultsWorklist').then((m) => ({ default: m.ResultsWorklist })));

type Notice = 'idle' | 'expired' | undefined;

function renderRoute(route: string, props: ScreenProps): ReactNode {
  const caseMatch = /^\/cases\/([^/]+)(?:\/([a-z-]+))?$/.exec(route);
  if (caseMatch?.[1]) return <CaseView key={caseMatch[1]} {...props} caseId={decodeURIComponent(caseMatch[1])} tab={caseMatch[2] ?? 'overview'} />;
  const chartMatch = /^\/employees\/([^/]+)\/chart(?:\/([a-z-]+))?$/.exec(route);
  if (chartMatch?.[1]) return <ChartScreen key={chartMatch[1]} {...props} employeeId={decodeURIComponent(chartMatch[1])} tab={chartMatch[2]} />;
  const encMatch = /^\/encounters\/([^/]+)$/.exec(route);
  if (encMatch?.[1] && encMatch[1] !== 'unsigned') return <EncounterEditor key={encMatch[1]} {...props} encounterId={decodeURIComponent(encMatch[1])} />;
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
    case '/employees': return <EmployeeChartSearch {...props} />;
    case '/encounters/unsigned': return <UnsignedNotes {...props} />;
    case '/results/worklist': return <ResultsWorklist {...props} />;
    case '/documents/scan': return <ScanDocuments {...props} />;
    case '/cds/statistics': return <CdsStatistics {...props} />;
    case '/fitness/signoff': return <SignoffQueue {...props} />;
    case '/restrictions': return <ActiveRestrictions {...props} />;
    case '/rtw/overdue': return <RtwOverdue {...props} />;
    case '/crew/dashboard': return <CrewDashboard {...props} />;
    case '/crew/roster-feed': return <RosterReconciliation {...props} />;
    case '/admin/fitness-standards': return <FitnessStandardsAdmin {...props} />;
    case '/admin/restrictions': return <RestrictionCatalogueAdmin {...props} />;
    case '/admin/job-demands': return <JobDemandsAdmin {...props} />;
    case '/admin/encounter-templates': return <EncounterTemplatesAdmin {...props} />;
    case '/admin/terminology': return <TerminologyAdmin {...props} />;
    case '/dpo/break-glass': return <BreakGlassReviews {...props} />;
    case '/dpo/break-glass-report': return <BreakGlassReport {...props} />;
    case '/dpo/disclosures': return <DisclosureLog {...props} />;
    case '/dpo/retention': return <RetentionScreen {...props} />;
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
  const current = route.startsWith('/cases/')
    ? '/queues'
    : /^\/employees\/./.test(route) || (route.startsWith('/encounters/') && route !== '/encounters/unsigned')
      ? '/employees'
      : route.replace(/^(\/intake\/incidents)\/.*$/, '$1');

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
        <main id="main" tabIndex={-1} key={route.replace(/^(\/cases\/[^/]+)\/.*$/, '$1').replace(/^(\/employees\/[^/]+\/chart)\/.*$/, '$1')}>
          <Suspense fallback={<Loading />}>{screen}</Suspense>
        </main>
      </div>
    </div>
  );
}
