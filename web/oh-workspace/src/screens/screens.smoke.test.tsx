import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import type { CaseDetail } from '../api/types';
import type { AuthClient, Session } from '../auth/AuthClient';
import { AssignmentRules, IntakeRules, NotificationMatrix, RecordabilityMapping, SlaPolicies } from './admin/ConfigScreens';
import { Protocols } from './admin/Protocols';
import { AssignmentTab } from './case/AssignmentTab';
import { CaseView } from './case/CaseView';
import { ChecklistsTab } from './case/ChecklistsTab';
import { ComplexityTab } from './case/ComplexityTab';
import { DuplicatesTab } from './case/DuplicatesTab';
import { EscalationTab } from './case/EscalationTab';
import { OverviewTab } from './case/OverviewTab';
import { RecordabilityTab } from './case/RecordabilityTab';
import { FitnessTab, StatusTab } from './case/StatusTabs';
import { TasksTab } from './case/TasksTab';
import { TimelineTab } from './case/TimelineTab';
import { TriageTab } from './case/TriageTab';
import { Caseload } from './Caseload';
import { EmployeeTimelineScreen } from './EmployeeTimeline';
import { Escalations } from './Escalations';
import { Handovers } from './Handovers';
import { Dashboard } from './intake/Dashboard';
import { Dlq } from './intake/Dlq';
import { IncidentCases } from './intake/IncidentCases';
import { Reconciliation } from './intake/Reconciliation';
import { Suggestions } from './intake/Suggestions';
import { Queues } from './Queues';
import { Reception } from './Reception';
import { TriageAudit } from './TriageAudit';
import { FitnessStandardsAdmin, JobDemandsAdmin, RestrictionCatalogueAdmin } from './admin/FitnessAdmin';
import { EncounterTemplatesAdmin, TerminologyAdmin } from './admin/RecordAdmin';
import { FitnessDecisionTab } from './case/FitnessDecisionTab';
import { CaseDocumentsTab, CaseEncountersTab, SummaryTab } from './case/RecordTabs';
import { RestrictionsDutiesTab } from './case/RestrictionsDutiesTab';
import { RtwPlanTab } from './case/RtwPlanTab';
import { CrewTab, FoodHandlerTab, IhrTab } from './case/SpecialistTabs';
import { CdsStatistics } from './CdsStatistics';
import { ChartScreen } from './chart/ChartScreen';
import { EmployeeChartSearch } from './chart/EmployeeChartSearch';
import { ScanDocuments } from './documents/ScanDocuments';
import { BreakGlassReport, BreakGlassReviews, DisclosureLog, RetentionScreen } from './dpo/DpoScreens';
import { EncounterEditor } from './encounter/EncounterEditor';
import { UnsignedNotes } from './encounter/UnsignedNotes';
import { ActiveRestrictions, CrewDashboard, RosterReconciliation, RtwOverdue, SignoffQueue } from './fitness/WorkScreens';
import { ResultsWorklist } from './results/ResultsWorklist';

/** Initial render (SSR, no effects) of every screen – catches render-time errors without a browser. */
const client: AuthClient = {
  mode: 'bff',
  init: async () => session,
  login: async () => undefined,
  logout: async () => undefined,
  api: () => new Promise(() => {}),
};
const session: Session = { authenticated: true, name: 'Nina Nurse', roles: ['NURSING_HEAD', 'CMO'] };
const base = { client, session, onSessionExpired: () => {} };
const detail: CaseDetail = {
  id: 'c1', caseRef: 'CAT-2026-000042', employeeId: 'E1001', employeeName: 'Erin Employee', status: 'TRIAGE', caseType: 'INJURY',
  urgency: 'URGENT', safetyCritical: true, safetyCategory: 'FOOD_HANDLER', hrisOverride: true, sla: { state: 'AT_RISK', minutesRemaining: 12 },
  intakeDetails: { shift: 'EARLY', ppe: true, symptoms: ['PAIN'] }, attachments: [{ id: 'a1', fileName: 'hand.jpg', contentType: 'image/jpeg', size: 2048 }],
  links: [{ type: 'INCIDENT', ref: 'INC-2026-0042' }], recordability: 'NOT_CLASSIFIED',
};
const tab = { ...base, caseId: 'c1', detail, refresh: () => {} };

const screens: [string, ReactElement][] = [
  ['Queues', <Queues {...base} />],
  ['CaseView', <CaseView {...base} caseId="c1" tab="overview" />],
  ['Overview', <OverviewTab {...tab} />],
  ['Triage', <TriageTab {...tab} />],
  ['Recordability', <RecordabilityTab {...tab} />],
  ['Checklists', <ChecklistsTab {...tab} />],
  ['Assignment', <AssignmentTab {...tab} />],
  ['Escalation', <EscalationTab {...tab} />],
  ['Complexity', <ComplexityTab {...tab} />],
  ['Duplicates', <DuplicatesTab {...tab} />],
  ['Tasks', <TasksTab {...tab} />],
  ['Timeline', <TimelineTab {...tab} />],
  ['Fitness', <FitnessTab {...tab} />],
  ['Status', <StatusTab {...tab} />],
  ['EmployeeTimeline', <EmployeeTimelineScreen {...base} employeeId="E1001" />],
  ['Escalations', <Escalations {...base} />],
  ['Handovers', <Handovers {...base} />],
  ['TriageAudit', <TriageAudit {...base} />],
  ['Caseload', <Caseload {...base} />],
  ['Reception', <Reception {...base} />],
  ['Suggestions', <Suggestions {...base} />],
  ['Reconciliation', <Reconciliation {...base} />],
  ['IncidentCases', <IncidentCases {...base} incidentId="i1" />],
  ['Dlq', <Dlq {...base} />],
  ['Dashboard', <Dashboard {...base} />],
  ['Protocols', <Protocols {...base} />],
  ['AssignmentRules', <AssignmentRules {...base} />],
  ['SlaPolicies', <SlaPolicies {...base} />],
  ['IntakeRules', <IntakeRules {...base} />],
  ['NotificationMatrix', <NotificationMatrix {...base} />],
  ['RecordabilityMapping', <RecordabilityMapping {...base} />],
  // increment 2 (E03 / E05)
  ...(['problems', 'medications', 'allergies', 'immunisations', 'jobs', 'results', 'documents', 'encounters', 'timeline'].map((t) => [`Chart ${t}`, <ChartScreen {...base} employeeId="E1004" tab={t} />]) as [string, ReactElement][]),
  ['EmployeeChartSearch', <EmployeeChartSearch {...base} />],
  ['EncounterEditor', <EncounterEditor {...base} encounterId="enc1" />],
  ['UnsignedNotes', <UnsignedNotes {...base} />],
  ['ResultsWorklist', <ResultsWorklist {...base} />],
  ['ScanDocuments', <ScanDocuments {...base} />],
  ['CdsStatistics', <CdsStatistics {...base} />],
  ['FitnessDecisionTab', <FitnessDecisionTab {...tab} />],
  ['RestrictionsDutiesTab', <RestrictionsDutiesTab {...tab} />],
  ['RtwPlanTab', <RtwPlanTab {...tab} />],
  ['FoodHandlerTab', <FoodHandlerTab {...tab} />],
  ['CrewTab', <CrewTab {...tab} />],
  ['IhrTab', <IhrTab {...tab} />],
  ['CaseDocumentsTab', <CaseDocumentsTab {...tab} />],
  ['CaseEncountersTab', <CaseEncountersTab {...tab} />],
  ['SummaryTab', <SummaryTab {...tab} />],
  ['SignoffQueue', <SignoffQueue {...base} />],
  ['ActiveRestrictions', <ActiveRestrictions {...base} />],
  ['RtwOverdue', <RtwOverdue {...base} />],
  ['CrewDashboard', <CrewDashboard {...base} />],
  ['RosterReconciliation', <RosterReconciliation {...base} />],
  ['FitnessStandardsAdmin', <FitnessStandardsAdmin {...base} />],
  ['RestrictionCatalogueAdmin', <RestrictionCatalogueAdmin {...base} />],
  ['JobDemandsAdmin', <JobDemandsAdmin {...base} />],
  ['EncounterTemplatesAdmin', <EncounterTemplatesAdmin {...base} />],
  ['TerminologyAdmin', <TerminologyAdmin {...base} />],
  ['BreakGlassReviews', <BreakGlassReviews {...base} />],
  ['BreakGlassReport', <BreakGlassReport {...base} />],
  ['DisclosureLog', <DisclosureLog {...base} />],
  ['RetentionScreen', <RetentionScreen {...base} />],
];

describe('screen render smoke test', () => {
  it.each(screens)('%s renders', (_name, el) => {
    expect(renderToStaticMarkup(el).length).toBeGreaterThan(0);
  });

  it('overview shows the HRIS override warning and audited attachment link', () => {
    const html = renderToStaticMarkup(<OverviewTab {...tab} />);
    expect(html).toContain('HRIS data overridden');
    expect(html).toContain('href="/bff/api/cases/c1/attachments/a1"');
  });

  it('registration form (walk-in and emergency) has exactly 5 fields: employee, time, location, complaint, action', () => {
    const html = renderToStaticMarkup(<Reception {...base} />);
    const form = html.slice(html.indexOf('<form class="form"'), html.indexOf('</form>'));
    expect(form.match(/<input /g)).toHaveLength(5);
  });
});
