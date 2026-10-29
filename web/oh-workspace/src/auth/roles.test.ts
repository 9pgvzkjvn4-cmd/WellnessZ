import { renderToStaticMarkup } from 'react-dom/server';
import { createElement } from 'react';
import { describe, expect, it } from 'vitest';
import { Restricted } from '../components/ui';
import { can, featureForRoute, hasWorkspaceAccess, homeRoute, isReceptionOnly, navFor, OH_ROLES } from './roles';

const paths = (roles: string[]) => navFor(roles).map((n) => n.path);

describe('workspace access', () => {
  // increment 3: AUDITOR (compliance / audit packs) and CREW_LICENSING (crew certificate status) gain a screen
  it.each([...OH_ROLES, 'OH_ADMIN', 'CLINIC_RECEPTION', 'FIRST_AIDER', 'HSE_OFFICER', 'PLATFORM_ADMIN', 'AUDITOR', 'CREW_LICENSING', 'ROLE_oh_clinician'])('allows %s', (r) => {
    expect(hasWorkspaceAccess([r])).toBe(true);
  });

  it.each([[[]], [['EMPLOYEE']], [['MANAGER']], [['HR']], [['HYGIENE_MANAGER']], [['CANDIDATE']], [['OH_CLINICIANX']]])('denies %j', (roles) => {
    expect(hasWorkspaceAccess(roles)).toBe(false);
  });

  it('denies undefined roles', () => {
    expect(hasWorkspaceAccess(undefined)).toBe(false);
    expect(can(undefined, 'queues')).toBe(false);
  });
});

describe('reception / first aid gating', () => {
  it('CLINIC_RECEPTION and FIRST_AIDER see only reception (reception also scans documents, E03-BL-016)', () => {
    expect(paths(['FIRST_AIDER'])).toEqual(['/reception']);
    // increment 3 adds clinic screens for reception (E14 kiosk / queue board, E06 certificates) – see the increment-3 blocks
    expect(paths(['CLINIC_RECEPTION'])).toEqual(expect.arrayContaining(['/reception', '/documents/scan']));
    expect(paths(['CLINIC_RECEPTION']).every((p) => ['/reception', '/documents/scan'].includes(p) || /^\/(scheduling|absence)\//.test(p))).toBe(true);
    expect(isReceptionOnly(['FIRST_AIDER'])).toBe(true);
    expect(homeRoute(['CLINIC_RECEPTION'])).toBe('/reception');
  });

  it('reception roles cannot open clinical screens or convert encounters', () => {
    for (const r of ['/queues', '/cases/abc', '/cases/abc/triage', '/employees/E1/timeline', '/employees/E1/chart', '/encounters/e1', '/results/worklist', '/caseload', '/admin/sla']) {
      expect(can(['FIRST_AIDER'], featureForRoute(r)!)).toBe(false);
    }
    expect(can(['CLINIC_RECEPTION'], 'encounterConvert')).toBe(false);
    expect(can(['CLINIC_RECEPTION'], 'encounterList')).toBe(true);
    expect(can(['FIRST_AIDER'], 'encounterList')).toBe(false);
  });

  it('a nurse who is also a first aider is not reception-only', () => {
    expect(isReceptionOnly(['FIRST_AIDER', 'OH_CLINICIAN'])).toBe(false);
    expect(homeRoute(['FIRST_AIDER', 'OH_CLINICIAN'])).toBe('/queues');
  });
});

describe('clinical and admin gating', () => {
  it('OH clinicians see clinical screens but not admin configuration', () => {
    const p = paths(['OH_CLINICIAN']);
    expect(p).toContain('/queues');
    expect(p).toContain('/reception');
    expect(p).toContain('/intake/suggestions');
    expect(p).toContain('/admin/protocols');
    expect(p).not.toContain('/admin/assignment-rules');
    expect(p).not.toContain('/intake/dlq');
    expect(p).not.toContain('/intake/dashboard');
    expect(can(['OH_CLINICIAN'], 'bulkReassign')).toBe(false);
    expect(can(['OH_CLINICIAN'], 'escalationDecide')).toBe(false);
  });

  it('NURSING_HEAD gets admin, rebalancing, bulk reassign and audit sampling', () => {
    for (const f of ['admin', 'adminWrite', 'bulkReassign', 'caseloadRebalance', 'triageAuditSample', 'intakeDashboard'] as const) {
      expect(can(['NURSING_HEAD'], f)).toBe(true);
    }
    expect(can(['NURSING_HEAD'], 'adminOnlyWrite')).toBe(false);
  });

  it('OH_ADMIN (not an OH role) sees configuration, DLQ and dashboard but no clinical case screens', () => {
    const p = paths(['OH_ADMIN']);
    expect(p).toEqual(expect.arrayContaining(['/admin/assignment-rules', '/admin/sla', '/admin/intake-rules', '/admin/notifications', '/admin/recordability-mapping', '/intake/dlq', '/intake/dashboard']));
    expect(p).not.toContain('/queues');
    expect(can(['OH_ADMIN'], featureForRoute('/cases/x')!)).toBe(false);
    expect(can(['OH_ADMIN'], 'adminOnlyWrite')).toBe(true);
  });

  it('physician / AME / CMO decide escalations; only CMO approves protocols and schedules conferences', () => {
    for (const r of ['OH_PHYSICIAN', 'AME', 'CMO']) expect(can([r], 'escalationDecide')).toBe(true);
    expect(can(['CMO'], 'protocolApprove')).toBe(true);
    expect(can(['OH_PHYSICIAN'], 'protocolApprove')).toBe(false);
    expect(can(['CMO'], 'conferenceSchedule')).toBe(true);
  });

  it('HSE officer sees reconciliation and incident → cases, plus (increment 3) exposure registers and compliance', () => {
    expect(paths(['HSE_OFFICER'])).toEqual(['/intake/reconciliation', '/intake/incidents', '/surveillance/exposure', '/surveillance/compliance']);
    expect(can(['HSE_OFFICER'], 'reconciliationExceptions')).toBe(true);
  });

  it('maps routes to features', () => {
    expect(featureForRoute('/cases/123/recordability')).toBe('caseView');
    expect(featureForRoute('/intake/incidents/i-1')).toBe('incidentCases');
    expect(featureForRoute('/admin/notifications')).toBe('admin');
    expect(featureForRoute('/nope')).toBeUndefined();
  });

  it('renders an "Access restricted" panel', () => {
    expect(renderToStaticMarkup(createElement(Restricted))).toContain('Access restricted');
  });
});

describe('increment 2 gating (API-Contract-E03-E05 §1)', () => {
  const CLINICAL_ROUTES = [
    '/queues', '/cases/c1', '/cases/c1/fitness', '/employees', '/employees/E1/chart', '/employees/E1/chart/results', '/encounters/e1', '/encounters/unsigned',
    '/results/worklist', '/fitness/signoff', '/restrictions', '/rtw/overdue', '/crew/dashboard', '/cds/statistics',
  ];

  it('DPO sees only the privacy area – no clinical screen', () => {
    expect(hasWorkspaceAccess(['DPO'])).toBe(true);
    expect(paths(['DPO'])).toEqual(['/dpo/break-glass', '/dpo/break-glass-report', '/dpo/disclosures', '/dpo/retention']);
    expect(navFor(['DPO']).every((n) => n.group === 'Privacy')).toBe(true);
    expect(homeRoute(['DPO'])).toBe('/dpo/break-glass');
    for (const r of CLINICAL_ROUTES) expect(can(['DPO'], featureForRoute(r)!)).toBe(false);
    for (const f of ['chartWrite', 'breakGlass', 'encounters', 'documents', 'documentScan', 'deviceResults'] as const) expect(can(['DPO'], f)).toBe(false);
  });

  it('CREW_SCHEDULING sees only the status-only roster feed', () => {
    expect(paths(['CREW_SCHEDULING'])).toEqual(['/crew/roster-feed']);
    expect(homeRoute(['CREW_SCHEDULING'])).toBe('/crew/roster-feed');
    for (const route of CLINICAL_ROUTES) expect(can(['CREW_SCHEDULING'], featureForRoute(route)!)).toBe(false);
  });

  it.each([['HR'], ['MANAGER'], ['HYGIENE_MANAGER'], ['CLAIMS_HANDLER'], ['EMPLOYEE'], ['PARTNER_PROVIDER'], ['INTEGRATION']])('%s never reaches this workspace', (r) => {
    expect(hasWorkspaceAccess([r])).toBe(false);
    for (const route of CLINICAL_ROUTES) expect(can([r], featureForRoute(route)!)).toBe(false);
  });

  it('clinicians vs case manager vs physicians', () => {
    expect(can(['CASE_MANAGER'], 'employeeChart')).toBe(true);
    expect(can(['CASE_MANAGER'], 'chartWrite')).toBe(false);
    expect(can(['CASE_MANAGER'], 'breakGlass')).toBe(false);
    expect(can(['CASE_MANAGER'], 'unsignedNotes')).toBe(false);
    expect(can(['OH_CLINICIAN'], 'breakGlass')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'orderWrite')).toBe(true);
    expect(can(['NURSING_HEAD'], 'orderWrite')).toBe(false);
    expect(can(['PSYCH_CLINICIAN'], 'mentalHealthEncounter')).toBe(true);
    expect(can(['OH_PHYSICIAN'], 'mentalHealthEncounter')).toBe(false);
    expect(can(['OH_CLINICIAN'], 'ihrAssess')).toBe(false);
    expect(can(['OH_PHYSICIAN'], 'ihrAssess')).toBe(true);
    expect(can(['CMO'], 'ihrCmo')).toBe(true);
  });

  it('E05 work screens: sign-off AME/CMO, crew dashboard CMO, roster feed CMO/OH_ADMIN, restrictions dashboard NH/CMO', () => {
    expect(paths(['AME'])).toContain('/fitness/signoff');
    expect(paths(['OH_PHYSICIAN'])).not.toContain('/fitness/signoff');
    expect(paths(['CMO'])).toEqual(expect.arrayContaining(['/crew/dashboard', '/crew/roster-feed', '/dpo/break-glass-report']));
    expect(paths(['CMO'])).not.toContain('/dpo/break-glass');
    expect(paths(['AME'])).not.toContain('/crew/dashboard');
    expect(paths(['OH_ADMIN'])).toContain('/crew/roster-feed');
    expect(can(['NURSING_HEAD'], 'restrictionsDashboard')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'restrictionsDashboard')).toBe(false);
  });

  it('admin: standards OH_ADMIN/CMO, catalogue & job demands OH_ADMIN/NH, import & terminology OH_ADMIN, template approval CMO', () => {
    expect(paths(['OH_ADMIN'])).toEqual(expect.arrayContaining(['/admin/fitness-standards', '/admin/restrictions', '/admin/job-demands', '/admin/terminology', '/dpo/retention']));
    expect(paths(['OH_ADMIN'])).not.toContain('/admin/encounter-templates');
    expect(paths(['OH_ADMIN'])).not.toContain('/employees');
    expect(homeRoute(['OH_ADMIN'])).toBe('/intake/dashboard'); // unchanged from increment 1
    expect(can(['NURSING_HEAD'], 'jobDemandsImport')).toBe(false);
    expect(can(['NURSING_HEAD'], 'fitnessStandardsAdmin')).toBe(false);
    expect(can(['CMO'], 'templateApprove')).toBe(true);
    expect(can(['OH_ADMIN'], 'retentionWrite')).toBe(false);
  });

  it('maps the new routes to features and keeps increment-1 homes', () => {
    expect(featureForRoute('/employees/E1/chart')).toBe('employeeChart');
    expect(featureForRoute('/employees/E1/chart/jobs')).toBe('employeeChart');
    expect(featureForRoute('/encounters/unsigned')).toBe('unsignedNotes');
    expect(featureForRoute('/encounters/abc')).toBe('encounters');
    expect(featureForRoute('/dpo/retention')).toBe('retention');
    expect(homeRoute(['OH_CLINICIAN'])).toBe('/queues');
    expect(homeRoute(['CLINIC_RECEPTION'])).toBe('/reception');
  });
});

describe('increment 3 – E14 scheduling & clinic operations gating (API-Contract-E06-E07-E14 §1, §8)', () => {
  const sched = (roles: string[]) => paths(roles).filter((p) => p.startsWith('/scheduling/'));

  it('CLINIC_SCHEDULER: diary, campaigns, catalogue – no clinical screens', () => {
    expect(hasWorkspaceAccess(['CLINIC_SCHEDULER'])).toBe(true);
    expect(sched(['CLINIC_SCHEDULER'])).toEqual(['/scheduling/diary', '/scheduling/campaigns', '/scheduling/catalogue']);
    for (const r of ['/queues', '/cases/c1', '/employees/E1/chart', '/employees/E1/chart/encounters', '/encounters/e1', '/results/worklist', '/reception']) {
      expect(can(['CLINIC_SCHEDULER'], featureForRoute(r)!)).toBe(false);
    }
    expect(can(['CLINIC_SCHEDULER'], 'schedulingBook')).toBe(true);
    expect(can(['CLINIC_SCHEDULER'], 'campaignManage')).toBe(true);
    expect(can(['CLINIC_SCHEDULER'], 'siteTemplatesWrite')).toBe(true);
    expect(can(['CLINIC_SCHEDULER'], 'appointmentTypesWrite')).toBe(false);
    expect(can(['CLINIC_SCHEDULER'], 'chargingUsage')).toBe(false);
    expect(can(['CLINIC_SCHEDULER'], 'queueCallNext')).toBe(false);
    expect(can(['CLINIC_SCHEDULER'], 'telehealthLaunch')).toBe(false);
    expect(homeRoute(['CLINIC_SCHEDULER'])).toMatch(/^\/(scheduling|surveillance)\//);
  });

  it('CLINIC_RECEPTION: check-in kiosk and queue board; still lands on reception', () => {
    expect(sched(['CLINIC_RECEPTION'])).toEqual(['/scheduling/reception', '/scheduling/queue']);
    expect(homeRoute(['CLINIC_RECEPTION'])).toBe('/reception');
    expect(can(['CLINIC_RECEPTION'], 'schedulingAttendance')).toBe(true);
    expect(can(['CLINIC_RECEPTION'], 'schedulingBook')).toBe(false);
    expect(can(['CLINIC_RECEPTION'], 'queueCallNext')).toBe(false);
    expect(paths(['FIRST_AIDER'])).toEqual(['/reception']);
  });

  it('BIOMEDICAL: equipment only (register, calibration, status)', () => {
    expect(hasWorkspaceAccess(['BIOMEDICAL'])).toBe(true);
    expect(paths(['BIOMEDICAL'])).toEqual(['/scheduling/equipment']);
    expect(homeRoute(['BIOMEDICAL'])).toBe('/scheduling/equipment');
    for (const f of ['equipmentWrite', 'calibrationWrite', 'calibrationDue', 'coldChain'] as const) expect(can(['BIOMEDICAL'], f)).toBe(true);
    expect(can(['BIOMEDICAL'], 'excursionAcknowledge')).toBe(false);
    expect(can(['BIOMEDICAL'], featureForRoute('/employees/E1/chart')!)).toBe(false);
  });

  it('clinicians: queue (call next), diary, equipment / cold chain – not capacity, campaigns or catalogue', () => {
    expect(sched(['OH_CLINICIAN'])).toEqual(['/scheduling/diary', '/scheduling/queue', '/scheduling/equipment']);
    expect(can(['OH_CLINICIAN'], 'queueCallNext')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'excursionAcknowledge')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'telehealthLaunch')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'calibrationWrite')).toBe(false);
    expect(can(['CASE_MANAGER'], 'queueCallNext')).toBe(false);
    expect(homeRoute(['OH_CLINICIAN'])).toBe('/queues');
  });

  it('NURSING_HEAD / CMO / OH_ADMIN: capacity; OH_ADMIN and CMO see charging; increment-1 homes unchanged', () => {
    for (const r of ['NURSING_HEAD', 'CMO', 'OH_ADMIN']) expect(paths([r])).toContain('/scheduling/capacity');
    expect(paths(['OH_CLINICIAN'])).not.toContain('/scheduling/capacity');
    expect(paths(['CLINIC_SCHEDULER'])).not.toContain('/scheduling/capacity');
    expect(can(['OH_ADMIN'], 'chargingUsage')).toBe(true);
    expect(can(['CMO'], 'chargingUsage')).toBe(true);
    expect(can(['NURSING_HEAD'], 'chargingUsage')).toBe(false);
    expect(can(['NURSING_HEAD'], 'appointmentTypesWrite')).toBe(true);
    expect(homeRoute(['OH_ADMIN'])).toBe('/intake/dashboard');
    expect(homeRoute(['NURSING_HEAD'])).toBe('/queues');
  });

  it('maps scheduling routes (incl. campaign detail) to features', () => {
    expect(featureForRoute('/scheduling/campaigns/c-1')).toBe('campaigns');
    expect(featureForRoute('/scheduling/queue')).toBe('queueBoard');
    expect(featureForRoute('/scheduling/reception')).toBe('checkInKiosk');
    expect(featureForRoute('/scheduling/campaigns/c-1/x')).toBeUndefined();
    expect(can(['OH_CLINICIAN'], featureForRoute('/scheduling/campaigns/c-1')!)).toBe(false);
  });

  it.each([['HR'], ['MANAGER'], ['EMPLOYEE'], ['CANDIDATE'], ['CREW_SCHEDULING']])('%s gets no scheduling screen', (r) => {
    expect(sched([r])).toEqual([]);
  });
});

describe('increment 3 – E06 absence & E07 surveillance gating (API-Contract-E06-E07-E14 §3, §4, §8)', () => {
  const area = (roles: string[]) => paths(roles).filter((p) => /^\/(absence|surveillance)\//.test(p));
  const PERSON_ROUTES = ['/absence/queue', '/absence/lta', '/absence/lta/c1', '/absence/advice', '/surveillance/follow-ups', '/surveillance/pre-employment', '/surveillance/rai', '/surveillance/immunisation', '/employees/E1/chart/absence', '/employees/E1/chart/surveillance'];

  it('OH clinicians get the absence and surveillance work screens, not analytics, feed or rule editing', () => {
    const p = area(['OH_CLINICIAN']);
    expect(p).toEqual([
      '/absence/queue', '/absence/lta', '/absence/certificates', '/absence/advice', '/absence/rules',
      '/surveillance/due', '/surveillance/follow-ups', '/surveillance/rai', '/surveillance/pre-employment', '/surveillance/immunisation', '/surveillance/exposure', '/surveillance/compliance', '/surveillance/programmes',
    ]);
    for (const f of ['adviceReportWrite', 'certificateVerify', 'enrolmentComplete', 'immunisationAdminister', 'raiComplete', 'preEmploymentOutcome'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(true);
    for (const f of ['absenceRulesWrite', 'enrolmentExempt', 'followUpReview', 'raiWaive', 'programmeWrite', 'programmeActivate', 'auditPacks', 'crewExam'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(false);
    expect(featureForRoute('/employees/E1/chart/absence')).toBe('employeeChart');
    expect(featureForRoute('/absence/lta/c-1')).toBe('absenceLta');
    expect(featureForRoute('/surveillance/programmes/HEARING_CONSERVATION')).toBe('surveillanceProgrammes');
  });

  it('case managers read but do not write clinical content', () => {
    expect(area(['CASE_MANAGER'])).toContain('/absence/advice');
    for (const f of ['adviceReportWrite', 'enrolmentComplete', 'immunisationAdminister', 'raiComplete'] as const) expect(can(['CASE_MANAGER'], f)).toBe(false);
  });

  it('physicians review follow-ups, exempt, waive; CMO / OH_PHYSICIAN activate; CMO retires; AME examines crew', () => {
    for (const r of ['OH_PHYSICIAN', 'AME', 'CMO']) for (const f of ['followUpReview', 'enrolmentExempt', 'raiWaive'] as const) expect(can([r], f)).toBe(true);
    expect(can(['OH_PHYSICIAN'], 'programmeActivate')).toBe(true);
    expect(can(['AME'], 'programmeActivate')).toBe(false);
    expect(can(['CMO'], 'programmeRetire')).toBe(true);
    expect(can(['AME'], 'crewExam')).toBe(true);
    expect(can(['CMO'], 'crewExam')).toBe(false);
    expect(area(['AME'])).toContain('/surveillance/crew');
    expect(area(['OH_PHYSICIAN'])).not.toContain('/surveillance/crew');
    expect(can(['AME'], 'cosmicDoseImport')).toBe(true);
  });

  it('CMO / NURSING_HEAD get analytics (+ export) and audit packs; NURSING_HEAD the feed; CMO no feed', () => {
    for (const r of ['CMO', 'NURSING_HEAD']) {
      expect(area([r])).toContain('/absence/analytics');
      expect(can([r], 'absenceAnalyticsExport')).toBe(true);
      expect(can([r], 'auditPacks')).toBe(true);
    }
    expect(area(['NURSING_HEAD'])).toContain('/absence/feed');
    expect(area(['CMO'])).not.toContain('/absence/feed');
  });

  it('OH_ADMIN: rules (write), analytics (no export), feed (run + mismatches), programmes, immunisation matrix – no person-level work lists', () => {
    expect(area(['OH_ADMIN'])).toEqual(['/absence/analytics', '/absence/rules', '/absence/feed', '/surveillance/immunisation', '/surveillance/exposure', '/surveillance/compliance', '/surveillance/programmes']);
    for (const f of ['absenceRulesWrite', 'absenceFeedRun', 'absenceMismatches', 'programmeWrite', 'dueRecalculate', 'immunisationRequirementWrite', 'segWrite'] as const) expect(can(['OH_ADMIN'], f)).toBe(true);
    for (const f of ['absenceAnalyticsExport', 'immunisationAdminister', 'immunisationDue', 'complianceOverdue', 'auditPacks'] as const) expect(can(['OH_ADMIN'], f)).toBe(false);
    expect(homeRoute(['OH_ADMIN'])).toBe('/intake/dashboard');
  });

  it('HSE_OFFICER: exposure (SEG upsert, IH import) and compliance with audit packs – no drill-down to people, no cosmic dose', () => {
    expect(area(['HSE_OFFICER'])).toEqual(['/surveillance/exposure', '/surveillance/compliance']);
    for (const f of ['segWrite', 'ihImport', 'auditPacks'] as const) expect(can(['HSE_OFFICER'], f)).toBe(true);
    for (const f of ['complianceOverdue', 'cosmicDose'] as const) expect(can(['HSE_OFFICER'], f)).toBe(false);
    for (const r of PERSON_ROUTES) expect(can(['HSE_OFFICER'], featureForRoute(r)!)).toBe(false);
  });

  it('AUDITOR: compliance & audit packs only', () => {
    expect(paths(['AUDITOR'])).toEqual(['/surveillance/compliance']);
    expect(homeRoute(['AUDITOR'])).toBe('/surveillance/compliance');
    expect(can(['AUDITOR'], 'auditPacks')).toBe(true);
    expect(can(['AUDITOR'], 'complianceOverdue')).toBe(false);
    for (const r of [...PERSON_ROUTES, '/queues', '/employees/E1/chart']) expect(can(['AUDITOR'], featureForRoute(r)!)).toBe(false);
  });

  it('CREW_LICENSING: crew certificates only (status), no exam / import / authority report', () => {
    expect(paths(['CREW_LICENSING'])).toEqual(['/surveillance/crew']);
    expect(homeRoute(['CREW_LICENSING'])).toBe('/surveillance/crew');
    for (const f of ['crewExam', 'crewImport', 'crewAuthorityReport'] as const) expect(can(['CREW_LICENSING'], f)).toBe(false);
    for (const r of PERSON_ROUTES) expect(can(['CREW_LICENSING'], featureForRoute(r)!)).toBe(false);
  });

  it('CLINIC_RECEPTION: certificates (scan / validate, no verify); CLINIC_SCHEDULER: the surveillance due list', () => {
    expect(area(['CLINIC_RECEPTION'])).toEqual(['/absence/certificates']);
    expect(can(['CLINIC_RECEPTION'], 'certificateVerify')).toBe(false);
    expect(area(['CLINIC_SCHEDULER'])).toEqual(['/surveillance/due']);
    for (const f of ['enrolmentComplete', 'enrolmentExempt'] as const) expect(can(['CLINIC_SCHEDULER'], f)).toBe(false);
    for (const r of PERSON_ROUTES) {
      expect(can(['CLINIC_RECEPTION'], featureForRoute(r)!)).toBe(false);
      expect(can(['CLINIC_SCHEDULER'], featureForRoute(r)!)).toBe(false);
    }
  });

  it.each([['HR'], ['MANAGER'], ['EMPLOYEE'], ['CANDIDATE'], ['CREW_SCHEDULING'], ['HYGIENE_MANAGER'], ['BIOMEDICAL'], ['DPO']])('%s gets no absence / surveillance screen', (r) => {
    expect(area([r])).toEqual([]);
  });
});
