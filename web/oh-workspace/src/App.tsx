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
// Increment-3 E14 scheduling & clinic operations (one chunk per screen file).
const Diary = lazy(() => import('./screens/scheduling/Diary').then((m) => ({ default: m.Diary })));
const Campaigns = lazy(() => import('./screens/scheduling/Campaigns').then((m) => ({ default: m.Campaigns })));
const CampaignDetail = lazy(() => import('./screens/scheduling/Campaigns').then((m) => ({ default: m.CampaignDetail })));
const ReceptionKiosk = lazy(() => import('./screens/scheduling/ClinicFlow').then((m) => ({ default: m.ReceptionKiosk })));
const QueueBoardScreen = lazy(() => import('./screens/scheduling/ClinicFlow').then((m) => ({ default: m.QueueBoardScreen })));
const EquipmentScreen = lazy(() => import('./screens/scheduling/Equipment').then((m) => ({ default: m.EquipmentScreen })));
const Capacity = lazy(() => import('./screens/scheduling/Capacity').then((m) => ({ default: m.Capacity })));
const Catalogue = lazy(() => import('./screens/scheduling/Catalogue').then((m) => ({ default: m.Catalogue })));
// Increment 3 – E06 absence & E07 surveillance
const AbsenceQueue = lazy(() => import('./screens/absence/Queue').then((m) => ({ default: m.AbsenceQueue })));
const LtaList = lazy(() => import('./screens/absence/Lta').then((m) => ({ default: m.LtaList })));
const LtaDetail = lazy(() => import('./screens/absence/Lta').then((m) => ({ default: m.LtaDetail })));
const AbsenceCertificates = lazy(() => import('./screens/absence/Certificates').then((m) => ({ default: m.AbsenceCertificates })));
const AbsenceAdvice = lazy(() => import('./screens/absence/Advice').then((m) => ({ default: m.AbsenceAdvice })));
const AbsenceRules = lazy(() => import('./screens/absence/Rules').then((m) => ({ default: m.AbsenceRules })));
const AbsenceAnalytics = lazy(() => import('./screens/absence/Analytics').then((m) => ({ default: m.AbsenceAnalytics })));
const AbsenceFeed = lazy(() => import('./screens/absence/Analytics').then((m) => ({ default: m.AbsenceFeed })));
const ProgrammeList = lazy(() => import('./screens/surveillance/Programmes').then((m) => ({ default: m.ProgrammeList })));
const ProgrammeBuilder = lazy(() => import('./screens/surveillance/Programmes').then((m) => ({ default: m.ProgrammeBuilder })));
const SurveillanceDue = lazy(() => import('./screens/surveillance/Due').then((m) => ({ default: m.SurveillanceDue })));
const SurveillanceFollowUps = lazy(() => import('./screens/surveillance/FollowUps').then((m) => ({ default: m.SurveillanceFollowUps })));
const ReturnAfterIllness = lazy(() => import('./screens/surveillance/FollowUps').then((m) => ({ default: m.ReturnAfterIllness })));
const PreEmploymentScreen = lazy(() => import('./screens/surveillance/PreEmployment').then((m) => ({ default: m.PreEmploymentScreen })));
const CrewCertificates = lazy(() => import('./screens/surveillance/Crew').then((m) => ({ default: m.CrewCertificates })));
const ImmunisationScreen = lazy(() => import('./screens/surveillance/Immunisation').then((m) => ({ default: m.ImmunisationScreen })));
const ExposureScreen = lazy(() => import('./screens/surveillance/Exposure').then((m) => ({ default: m.ExposureScreen })));
const ComplianceScreen = lazy(() => import('./screens/surveillance/Compliance').then((m) => ({ default: m.ComplianceScreen })));
// Increment 4 – E04 treatment coordination & E08 claims (one chunk per screen file).
const ReferralWorklist = lazy(() => import('./screens/treatment/ReferralScreens').then((m) => ({ default: m.ReferralWorklist })));
const ReferralScreen = lazy(() => import('./screens/treatment/ReferralScreens').then((m) => ({ default: m.ReferralScreen })));
const VarianceAlerts = lazy(() => import('./screens/treatment/TreatmentWork').then((m) => ({ default: m.VarianceAlerts })));
const TreatmentKpis = lazy(() => import('./screens/treatment/TreatmentWork').then((m) => ({ default: m.TreatmentKpis })));
const ProviderDirectory = lazy(() => import('./screens/treatment/Providers').then((m) => ({ default: m.ProviderDirectory })));
const ProviderScreen = lazy(() => import('./screens/treatment/Providers').then((m) => ({ default: m.ProviderScreen })));
const ProviderScorecards = lazy(() => import('./screens/treatment/Providers').then((m) => ({ default: m.ProviderScorecards })));
const NetworkGaps = lazy(() => import('./screens/treatment/Providers').then((m) => ({ default: m.NetworkGaps })));
const MedicationsScreen = lazy(() => import('./screens/treatment/Medications').then((m) => ({ default: m.MedicationsScreen })));
const TelehealthScreen = lazy(() => import('./screens/treatment/Telehealth').then((m) => ({ default: m.TelehealthScreen })));
const ClaimList = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.ClaimList })));
const PreauthList = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.PreauthList })));
const ClaimReportsDashboard = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.ClaimReportsDashboard })));
const NurseAssignment = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.NurseAssignment })));
const LegalScreen = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.LegalScreen })));
const ClaimDisclosureReport = lazy(() => import('./screens/claims/ClaimScreens').then((m) => ({ default: m.ClaimDisclosureReport })));
const ClaimCostsScreen = lazy(() => import('./screens/claims/ClaimCosts').then((m) => ({ default: m.ClaimCostsScreen })));
const ClaimDetailScreen = lazy(() => import('./screens/claims/ClaimDetail').then((m) => ({ default: m.ClaimDetailScreen })));
const PhysicianLegalRequests = lazy(() => import('./screens/claims/LegalRequests').then((m) => ({ default: m.PhysicianLegalRequests })));
// Increment 5 – E09 support (restricted) & E10 drug and alcohol testing (one chunk per screen file).
const SupportCases = lazy(() => import('./screens/support/Cases').then((m) => ({ default: m.SupportCases })));
const SupportCaseScreen = lazy(() => import('./screens/support/Cases').then((m) => ({ default: m.SupportCaseScreen })));
const UrgentQueue = lazy(() => import('./screens/support/Urgent').then((m) => ({ default: m.UrgentQueue })));
const EapReferrals = lazy(() => import('./screens/support/Urgent').then((m) => ({ default: m.EapReferrals })));
const SupportFitness = lazy(() => import('./screens/support/Fitness').then((m) => ({ default: m.SupportFitness })));
const PeerSupport = lazy(() => import('./screens/support/PeerSupport').then((m) => ({ default: m.PeerSupport })));
const CismEvents = lazy(() => import('./screens/support/Cism').then((m) => ({ default: m.CismEvents })));
const CismEventScreen = lazy(() => import('./screens/support/Cism').then((m) => ({ default: m.CismEventScreen })));
const Psychosocial = lazy(() => import('./screens/support/Psychosocial').then((m) => ({ default: m.Psychosocial })));
const TestingPolicies = lazy(() => import('./screens/testing/Programme').then((m) => ({ default: m.TestingPolicies })));
const TestingPools = lazy(() => import('./screens/testing/Programme').then((m) => ({ default: m.TestingPools })));
const TestingSelections = lazy(() => import('./screens/testing/Programme').then((m) => ({ default: m.TestingSelections })));
const TestingRequirements = lazy(() => import('./screens/testing/Requirements').then((m) => ({ default: m.TestingRequirements })));
const TestingCollection = lazy(() => import('./screens/testing/Collection').then((m) => ({ default: m.TestingCollection })));
const MroQueue = lazy(() => import('./screens/testing/Mro').then((m) => ({ default: m.MroQueue })));
const RtdPlans = lazy(() => import('./screens/testing/Mro').then((m) => ({ default: m.RtdPlans })));
const TestingReports = lazy(() => import('./screens/testing/Reports').then((m) => ({ default: m.TestingReports })));
// Increment 5 – E11 travel health & public health, E12 health promotion (one chunk per screen file).
const TravelDestinations = lazy(() => import('./screens/travel/Destinations').then((m) => ({ default: m.TravelDestinations })));
const TravelConsultations = lazy(() => import('./screens/travel/Consultations').then((m) => ({ default: m.TravelConsultations })));
const CommunicableList = lazy(() => import('./screens/travel/Communicable').then((m) => ({ default: m.CommunicableList })));
const CommunicableCase = lazy(() => import('./screens/travel/Communicable').then((m) => ({ default: m.CommunicableCase })));
const ExposureList = lazy(() => import('./screens/travel/Exposures').then((m) => ({ default: m.ExposureList })));
const ExposureDetail = lazy(() => import('./screens/travel/Exposures').then((m) => ({ default: m.ExposureDetail })));
const VaccinationCampaigns = lazy(() => import('./screens/travel/VaccinationCampaigns').then((m) => ({ default: m.VaccinationCampaigns })));
const Advisories = lazy(() => import('./screens/travel/Advisories').then((m) => ({ default: m.Advisories })));
const PublicHealthReports = lazy(() => import('./screens/travel/PublicHealth').then((m) => ({ default: m.PublicHealthReports })));
const PromotionInsights = lazy(() => import('./screens/promotion/Insights').then((m) => ({ default: m.PromotionInsights })));
const PromotionCampaigns = lazy(() => import('./screens/promotion/Campaigns').then((m) => ({ default: m.PromotionCampaigns })));
const ScreeningEvents = lazy(() => import('./screens/promotion/Screening').then((m) => ({ default: m.ScreeningEvents })));
const Ergonomics = lazy(() => import('./screens/promotion/Ergonomics').then((m) => ({ default: m.Ergonomics })));
const PromotionReports = lazy(() => import('./screens/promotion/Reports').then((m) => ({ default: m.PromotionReports })));

/** Fixed second segments that are screens, not ids (`/claims/reports` is not claim "reports"). */
const CLAIM_SCREENS = new Set(['reports', 'costs', 'nurse-assignment', 'disclosures', 'preauthorisations']);
const PROVIDER_SCREENS = new Set(['scorecards', 'gaps']);

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
  const campaignMatch = /^\/scheduling\/campaigns\/([^/]+)$/.exec(route);
  if (campaignMatch?.[1]) return <CampaignDetail key={campaignMatch[1]} {...props} campaignId={decodeURIComponent(campaignMatch[1])} />;
  const ltaMatch = /^\/absence\/lta\/([^/]+)$/.exec(route);
  if (ltaMatch?.[1]) return <LtaDetail key={ltaMatch[1]} {...props} caseId={decodeURIComponent(ltaMatch[1])} />;
  const progMatch = /^\/surveillance\/programmes\/([^/]+)$/.exec(route);
  if (progMatch?.[1]) return <ProgrammeBuilder key={progMatch[1]} {...props} code={decodeURIComponent(progMatch[1])} />;
  const claimMatch = /^\/claims\/([^/]+)(?:\/([a-z-]+))?$/.exec(route);
  if (claimMatch?.[1] && !(CLAIM_SCREENS.has(claimMatch[1]) && !claimMatch[2])) return <ClaimDetailScreen key={claimMatch[1]} {...props} claimId={decodeURIComponent(claimMatch[1])} tab={claimMatch[2]} />;
  const referralMatch = /^\/referrals\/([^/]+)$/.exec(route);
  if (referralMatch?.[1]) return <ReferralScreen key={referralMatch[1]} {...props} referralId={decodeURIComponent(referralMatch[1])} />;
  const providerMatch = /^\/providers\/([^/]+)$/.exec(route);
  if (providerMatch?.[1] && !PROVIDER_SCREENS.has(providerMatch[1])) return <ProviderScreen key={providerMatch[1]} {...props} code={decodeURIComponent(providerMatch[1])} />;
  const supportMatch = /^\/support\/cases\/([^/]+)$/.exec(route);
  if (supportMatch?.[1]) return <SupportCaseScreen key={supportMatch[1]} {...props} caseId={decodeURIComponent(supportMatch[1])} />;
  const cismMatch = /^\/cism\/([^/]+)$/.exec(route);
  if (cismMatch?.[1]) return <CismEventScreen key={cismMatch[1]} {...props} eventId={decodeURIComponent(cismMatch[1])} />;
  const incMatch = /^\/intake\/incidents(?:\/([^/]+))?$/.exec(route);
  if (incMatch) return <IncidentCases {...props} incidentId={incMatch[1] ? decodeURIComponent(incMatch[1]) : undefined} />;
  const cdMatch = /^\/communicable\/([^/]+)$/.exec(route);
  if (cdMatch?.[1]) return <CommunicableCase key={cdMatch[1]} {...props} caseId={decodeURIComponent(cdMatch[1])} />;
  const exposureMatch = /^\/exposures\/([^/]+)$/.exec(route);
  if (exposureMatch?.[1]) return <ExposureDetail key={exposureMatch[1]} {...props} exposureId={decodeURIComponent(exposureMatch[1])} />;
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
    case '/absence/queue': return <AbsenceQueue {...props} />;
    case '/absence/lta': return <LtaList {...props} />;
    case '/absence/certificates': return <AbsenceCertificates {...props} />;
    case '/absence/advice': return <AbsenceAdvice {...props} />;
    case '/absence/rules': return <AbsenceRules {...props} />;
    case '/absence/analytics': return <AbsenceAnalytics {...props} />;
    case '/absence/feed': return <AbsenceFeed {...props} />;
    case '/surveillance/programmes': return <ProgrammeList {...props} />;
    case '/surveillance/due': return <SurveillanceDue {...props} />;
    case '/surveillance/follow-ups': return <SurveillanceFollowUps {...props} />;
    case '/surveillance/rai': return <ReturnAfterIllness {...props} />;
    case '/surveillance/pre-employment': return <PreEmploymentScreen {...props} />;
    case '/surveillance/crew': return <CrewCertificates {...props} />;
    case '/surveillance/immunisation': return <ImmunisationScreen {...props} />;
    case '/surveillance/exposure': return <ExposureScreen {...props} />;
    case '/surveillance/compliance': return <ComplianceScreen {...props} />;
    case '/scheduling/diary': return <Diary {...props} />;
    case '/scheduling/campaigns': return <Campaigns {...props} />;
    case '/scheduling/reception': return <ReceptionKiosk {...props} />;
    case '/scheduling/queue': return <QueueBoardScreen {...props} />;
    case '/scheduling/equipment': return <EquipmentScreen {...props} />;
    case '/scheduling/capacity': return <Capacity {...props} />;
    case '/scheduling/catalogue': return <Catalogue {...props} />;
    case '/referrals': return <ReferralWorklist {...props} />;
    case '/treatment/variance': return <VarianceAlerts {...props} />;
    case '/treatment/kpis': return <TreatmentKpis {...props} />;
    case '/treatment/medications': return <MedicationsScreen {...props} />;
    case '/telehealth': return <TelehealthScreen {...props} />;
    case '/providers': return <ProviderDirectory {...props} />;
    case '/providers/scorecards': return <ProviderScorecards {...props} />;
    case '/providers/gaps': return <NetworkGaps {...props} />;
    case '/claims': return <ClaimList {...props} />;
    case '/claims/reports': return <ClaimReportsDashboard {...props} />;
    case '/claims/preauthorisations': return <PreauthList {...props} />;
    case '/claims/costs': return <ClaimCostsScreen {...props} />;
    case '/claims/nurse-assignment': return <NurseAssignment {...props} />;
    case '/claims/disclosures': return <ClaimDisclosureReport {...props} />;
    case '/travel/destinations': return <TravelDestinations {...props} />;
    case '/travel/consultations': return <TravelConsultations {...props} />;
    case '/communicable': return <CommunicableList {...props} />;
    case '/exposures': return <ExposureList {...props} />;
    case '/vaccination-campaigns': return <VaccinationCampaigns {...props} />;
    case '/advisories': return <Advisories {...props} />;
    case '/public-health': return <PublicHealthReports {...props} />;
    case '/promotion/insights': return <PromotionInsights {...props} />;
    case '/promotion/campaigns': return <PromotionCampaigns {...props} />;
    case '/promotion/screening': return <ScreeningEvents {...props} />;
    case '/ergonomics': return <Ergonomics {...props} />;
    case '/promotion/reports': return <PromotionReports {...props} />;
    case '/legal': return <LegalScreen {...props} />;
    case '/legal/requests': return <PhysicianLegalRequests {...props} />;
    case '/support/cases': return <SupportCases {...props} />;
    case '/support/urgent': return <UrgentQueue {...props} />;
    case '/support/eap': return <EapReferrals {...props} />;
    case '/support/fitness': return <SupportFitness {...props} />;
    case '/peer-support': return <PeerSupport {...props} />;
    case '/cism': return <CismEvents {...props} />;
    case '/psychosocial': return <Psychosocial {...props} />;
    case '/testing/policies': return <TestingPolicies {...props} />;
    case '/testing/pools': return <TestingPools {...props} />;
    case '/testing/selections': return <TestingSelections {...props} />;
    case '/testing/requirements': return <TestingRequirements {...props} />;
    case '/testing/collection': return <TestingCollection {...props} />;
    case '/testing/mro': return <MroQueue {...props} />;
    case '/testing/rtd': return <RtdPlans {...props} />;
    case '/testing/reports': return <TestingReports {...props} />;
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
      : nav.some((n) => n.path === route)
        ? route
        : /^\/(claims|referrals|providers)\/./.test(route)
          ? `/${route.split('/')[1]}`
          : route.replace(/^(\/intake\/incidents)\/.*$/, '$1').replace(/^(\/scheduling\/campaigns)\/.*$/, '$1').replace(/^(\/absence\/lta|\/surveillance\/programmes)\/.*$/, '$1').replace(/^(\/support\/cases|\/cism)\/.*$/, '$1').replace(/^(\/communicable|\/exposures)\/.*$/, '$1');

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
        <main id="main" tabIndex={-1} key={route.replace(/^(\/cases\/[^/]+)\/.*$/, '$1').replace(/^(\/employees\/[^/]+\/chart)\/.*$/, '$1').replace(/^(\/claims\/[^/]+)\/.*$/, '$1')}>
          <Suspense fallback={<Loading />}>{screen}</Suspense>
        </main>
      </div>
    </div>
  );
}
