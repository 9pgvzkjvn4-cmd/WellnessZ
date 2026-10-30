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
    // increment 5 adds the psychosocial risk register (E09-F05) and possibly other increment-5 areas – earlier areas unchanged
    expect(paths(['HSE_OFFICER']).filter((p) => /^\/(intake|surveillance|queues|cases|employees)/.test(p))).toEqual(['/intake/reconciliation', '/intake/incidents', '/surveillance/exposure', '/surveillance/compliance']);
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
    // increment 4 adds the claim disclosure report (still in the privacy area)
    expect(paths(['DPO'])).toEqual(['/dpo/break-glass', '/dpo/break-glass-report', '/dpo/disclosures', '/dpo/retention', '/claims/disclosures']);
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

  it.each([['HR'], ['MANAGER'], ['HYGIENE_MANAGER'], ['CLAIMS_HANDLER'], ['EMPLOYEE'], ['PARTNER_PROVIDER'], ['TPA_ADJUSTER'], ['INTEGRATION']])('%s never reaches this workspace', (r) => {
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

describe('increment 4 – E04 treatment & E08 claims gating (API-Contract-E04-E08 §1, §6)', () => {
  const CLINICAL = [
    '/queues', '/cases/c1', '/cases/c1/treatment', '/cases/c1/referrals', '/cases/c1/outcomes', '/cases/c1/claim', '/employees/E1/chart', '/employees/E1/chart/treatment',
    '/encounters/e1', '/referrals', '/referrals/r1', '/treatment/variance', '/treatment/medications', '/telehealth', '/absence/queue', '/surveillance/follow-ups',
  ];
  const area = (roles: string[]) => paths(roles).filter((p) => /^\/(referrals|treatment|telehealth|providers|claims|legal)/.test(p));

  it.each(['CLAIMS_COORDINATOR', 'FINANCE', 'LEGAL_COUNSEL', 'PROCUREMENT'])('%s gets a workspace but never a clinical screen', (r) => {
    expect(hasWorkspaceAccess([r])).toBe(true);
    for (const route of CLINICAL) expect(can([r], featureForRoute(route)!)).toBe(false);
    for (const f of ['caseView', 'employeeChart', 'encounters', 'documents', 'treatmentWrite', 'medications', 'telehealth', 'referrals', 'outcomeWrite', 'disclosureRedact'] as const) expect(can([r], f)).toBe(false);
  });

  it('clinicians: referrals, variance, telehealth, medications, providers (read) – no claims, no edit, no KPIs', () => {
    expect(area(['OH_CLINICIAN'])).toEqual(['/referrals', '/treatment/variance', '/telehealth', '/treatment/medications', '/providers']);
    for (const f of ['treatmentWrite', 'referralWrite', 'telehealthWrite', 'medicationAdminister', 'outcomeWrite', 'preauthCreate', 'stockReceive'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(true);
    for (const f of ['providerEdit', 'providerContracts', 'preauthDecide', 'claimCreate', 'claimDetail', 'formularyWrite', 'medicationUsage'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(false);
    expect(featureForRoute('/cases/c1/treatment')).toBe('caseView');
    expect(featureForRoute('/employees/E1/chart/treatment')).toBe('employeeChart');
    expect(featureForRoute('/referrals/r-1')).toBe('referrals');
    expect(homeRoute(['OH_CLINICIAN'])).toBe('/queues');
  });

  it('case manager reads treatment and opens claims / collaborates, but does not write clinical content', () => {
    expect(area(['CASE_MANAGER'])).toEqual(['/referrals', '/treatment/variance', '/telehealth', '/treatment/medications', '/providers']);
    for (const f of ['claimCreate', 'claimCollaboration', 'disclosureSetWrite'] as const) expect(can(['CASE_MANAGER'], f)).toBe(true);
    for (const f of ['treatmentWrite', 'referralWrite', 'medicationAdminister', 'telehealthWrite', 'outcomeWrite', 'preauthCreate'] as const) expect(can(['CASE_MANAGER'], f)).toBe(false);
  });

  it('PROCUREMENT: provider directory (edit + contracts), scorecards, network gaps only', () => {
    expect(paths(['PROCUREMENT'])).toEqual(['/providers', '/providers/scorecards', '/providers/gaps']);
    expect(homeRoute(['PROCUREMENT'])).toBe('/providers');
    for (const f of ['providerEdit', 'providerContracts', 'scorecardRecompute'] as const) expect(can(['PROCUREMENT'], f)).toBe(true);
    expect(featureForRoute('/providers/CITYHOSP')).toBe('providers');
    expect(can(['PROCUREMENT'], 'claims')).toBe(false);
  });

  it('CLAIMS_COORDINATOR: claims, report deadlines, pre-auth list, nurse assignment, providers (read)', () => {
    expect(paths(['CLAIMS_COORDINATOR'])).toEqual(['/claims', '/claims/reports', '/claims/preauthorisations', '/claims/nurse-assignment', '/providers']);
    expect(homeRoute(['CLAIMS_COORDINATOR'])).toBe('/claims');
    for (const f of ['claimDetail', 'claimWrite', 'claimCreate', 'preauthDecide', 'preauthCreate', 'invoiceWrite', 'disclosureSetWrite', 'disclosureRevoke', 'claimCostLedger', 'legalRequestCreate', 'claimCollaboration'] as const) {
      expect(can(['CLAIMS_COORDINATOR'], f)).toBe(true);
    }
    for (const f of ['providerEdit', 'legalWrite', 'disclosureRedact', 'erpExports', 'budgetWrite'] as const) expect(can(['CLAIMS_COORDINATOR'], f)).toBe(false);
    expect(featureForRoute('/claims/c1')).toBe('claimDetail');
    expect(featureForRoute('/claims/c1/disclosures')).toBe('claimDetail');
    expect(featureForRoute('/claims/reports')).toBe('claimReports');
  });

  it('FINANCE: claim & treatment costs only (budgets, ERP exports) – no claim records with names', () => {
    expect(paths(['FINANCE'])).toEqual(['/claims/costs']);
    expect(homeRoute(['FINANCE'])).toBe('/claims/costs');
    for (const f of ['budgetWrite', 'erpExports', 'invoiceWrite', 'claimReconciliation'] as const) expect(can(['FINANCE'], f)).toBe(true);
    for (const f of ['claims', 'claimDetail', 'claimCostLedger', 'providers'] as const) expect(can(['FINANCE'], f)).toBe(false);
  });

  it('LEGAL_COUNSEL: legal screen and claim detail (hold, privilege, requests) – no report writing', () => {
    expect(paths(['LEGAL_COUNSEL'])).toEqual(['/legal']);
    expect(homeRoute(['LEGAL_COUNSEL'])).toBe('/legal');
    for (const f of ['legal', 'legalWrite', 'legalRequestCreate', 'claimDetail'] as const) expect(can(['LEGAL_COUNSEL'], f)).toBe(true);
    for (const f of ['legalReportWrite', 'claimWrite', 'claims'] as const) expect(can(['LEGAL_COUNSEL'], f)).toBe(false);
  });

  it('NURSING_HEAD: nurse assignment, scorecards, variance, KPIs; CMO: scorecards, KPIs, costs, gaps; physicians write medico-legal reports', () => {
    expect(area(['NURSING_HEAD'])).toEqual(expect.arrayContaining(['/claims/nurse-assignment', '/providers/scorecards', '/treatment/variance', '/treatment/kpis']));
    expect(area(['NURSING_HEAD'])).not.toContain('/claims/costs');
    expect(can(['NURSING_HEAD'], 'formularyWrite')).toBe(true);
    expect(area(['CMO'])).toEqual(expect.arrayContaining(['/providers/scorecards', '/treatment/kpis', '/claims/costs', '/providers/gaps', '/legal/requests']));
    expect(area(['CMO'])).not.toContain('/claims');
    expect(can(['CMO'], 'erpExports')).toBe(false);
    for (const r of ['OH_PHYSICIAN', 'AME', 'CMO']) expect(can([r], 'legalReportWrite')).toBe(true);
    expect(can(['OH_CLINICIAN'], 'legalReportWrite')).toBe(false);
    expect(homeRoute(['NURSING_HEAD'])).toBe('/queues');
    expect(homeRoute(['CMO'])).toBe('/queues');
  });

  it('DPO: claim disclosure report and revoke only; OH_ADMIN: network gaps only', () => {
    expect(area(['DPO'])).toEqual(['/claims/disclosures']);
    expect(can(['DPO'], 'disclosureRevoke')).toBe(true);
    expect(can(['DPO'], 'claimDetail')).toBe(false);
    expect(area(['OH_ADMIN'])).toEqual(['/providers/gaps']);
    expect(homeRoute(['OH_ADMIN'])).toBe('/intake/dashboard');
  });

  it.each([['HR'], ['MANAGER'], ['EMPLOYEE'], ['HSE_OFFICER'], ['CLINIC_SCHEDULER'], ['CLINIC_RECEPTION'], ['AUDITOR'], ['BIOMEDICAL']])('%s gets no increment-4 screen', (r) => {
    expect(area([r])).toEqual([]);
  });
});

describe('increment 5 – E09 support & E10 testing gating (API-Contract-E09-E10-E11-E12 §1, confidentiality rules)', () => {
  const RESTRICTED = ['PEER_SUPPORT_VOLUNTEER', 'PEER_SUPPORT_COORDINATOR', 'SAFETY_MANAGER', 'DA_PROGRAMME_MANAGER', 'COLLECTOR', 'MRO'];
  const OTHER_CLINICAL = [
    '/queues', '/cases/c1', '/cases/c1/treatment', '/employees', '/employees/E1/chart', '/employees/E1/chart/results', '/employees/E1/timeline', '/encounters/e1', '/encounters/unsigned',
    '/results/worklist', '/fitness/signoff', '/restrictions', '/rtw/overdue', '/absence/queue', '/absence/lta/c1', '/surveillance/follow-ups', '/surveillance/immunisation',
    '/referrals', '/referrals/r1', '/treatment/medications', '/telehealth', '/caseload', '/reception', '/scheduling/diary', '/claims/c1',
  ];
  const SUPPORT_CONTENT = ['/support/cases', '/support/cases/s1', '/support/urgent', '/support/eap', '/cism/ev1'];
  const TESTING_RESULTS = ['/testing/mro', '/testing/rtd'];
  const GENERAL_OH = ['OH_CLINICIAN', 'NURSING_HEAD', 'CASE_MANAGER', 'CMO', 'OH_PHYSICIAN', 'AME'];
  const area = (roles: string[]) => paths(roles).filter((p) => /^\/(support|peer-support|cism|psychosocial|testing)/.test(p));

  it.each(RESTRICTED)('%s gets a workspace but cannot open any other clinical screen', (r) => {
    expect(hasWorkspaceAccess([r])).toBe(true);
    for (const route of OTHER_CLINICAL) expect(can([r], featureForRoute(route)!)).toBe(false);
    for (const f of ['caseView', 'employeeChart', 'encounters', 'documents', 'chartWrite', 'breakGlass', 'supportPlaceholder'] as const) expect(can([r], f)).toBe(false);
    expect(paths([r]).every((p) => /^\/(support|peer-support|cism|psychosocial|testing)/.test(p))).toBe(true);
  });

  it.each(GENERAL_OH)('general OH role %s never opens support case content or the MRO result queue', (r) => {
    for (const route of [...SUPPORT_CONTENT, '/testing/mro']) {
      if (route.startsWith('/cism/') && r === 'OH_CLINICIAN') continue; // CISM names: PSYCH_CLINICIAN + OH_CLINICIAN (contract §3.4)
      expect(can([r], featureForRoute(route)!)).toBe(false);
    }
    expect(can([r], 'supportPlaceholder')).toBe(true);
    expect(can([r], 'testingCollection')).toBe(false);
  });

  it('only MRO and OH_PHYSICIAN (acting as MRO) reach return-to-duty; other OH roles never see testing results', () => {
    for (const r of ['OH_CLINICIAN', 'NURSING_HEAD', 'CASE_MANAGER', 'CMO', 'AME', 'PSYCH_CLINICIAN']) for (const route of TESTING_RESULTS) expect(can([r], featureForRoute(route)!)).toBe(false);
    expect(can(['OH_PHYSICIAN'], featureForRoute('/testing/rtd')!)).toBe(true);
    expect(can(['OH_PHYSICIAN'], featureForRoute('/testing/mro')!)).toBe(false);
  });

  it('PSYCH_CLINICIAN (named clinician) gets the support screens; AME / OH_PHYSICIAN the fitness assessment only', () => {
    expect(area(['PSYCH_CLINICIAN'])).toEqual(['/support/cases', '/support/urgent', '/support/eap', '/support/fitness', '/cism']);
    expect(featureForRoute('/support/cases/abc')).toBe('supportCases');
    expect(can(['PSYCH_CLINICIAN'], 'supportFitnessWrite')).toBe(false);
    expect(can(['PSYCH_CLINICIAN'], 'supportRelief')).toBe(true);
    expect(area(['AME'])).toEqual(['/support/fitness']);
    expect(area(['OH_PHYSICIAN'])).toEqual(['/support/fitness', '/testing/rtd']);
    for (const r of ['AME', 'OH_PHYSICIAN']) expect(can([r], 'supportFitnessWrite')).toBe(true);
    expect(can(['AME'], 'supportReliefReturn')).toBe(true);
    expect(can(['OH_PHYSICIAN'], 'supportRelief')).toBe(false);
    expect(homeRoute(['PSYCH_CLINICIAN'])).toBe('/queues');
  });

  it('peer support: coordinator register + report, volunteer own log only', () => {
    expect(paths(['PEER_SUPPORT_COORDINATOR'])).toEqual(['/peer-support']);
    expect(paths(['PEER_SUPPORT_VOLUNTEER'])).toEqual(['/peer-support']);
    expect(homeRoute(['PEER_SUPPORT_VOLUNTEER'])).toBe('/peer-support');
    expect(can(['PEER_SUPPORT_COORDINATOR'], 'peerSupportCoordinate')).toBe(true);
    expect(can(['PEER_SUPPORT_COORDINATOR'], 'peerSupportReport')).toBe(true);
    expect(can(['PEER_SUPPORT_VOLUNTEER'], 'peerSupportCoordinate')).toBe(false);
    expect(can(['PEER_SUPPORT_VOLUNTEER'], 'peerSupportReport')).toBe(false);
    expect(can(['PEER_SUPPORT_VOLUNTEER'], 'peerSupportVolunteer')).toBe(true);
    for (const r of ['PEER_SUPPORT_COORDINATOR', 'PEER_SUPPORT_VOLUNTEER']) for (const route of [...SUPPORT_CONTENT, '/cism', '/testing/reports']) expect(can([r], featureForRoute(route)!)).toBe(false);
  });

  it('SAFETY_MANAGER: CISM counts, SMS report, testing statistics (and policies read) – no names, no results', () => {
    expect(area(['SAFETY_MANAGER'])).toEqual(['/peer-support', '/cism', '/testing/policies', '/testing/reports']);
    expect(can(['SAFETY_MANAGER'], 'cismNames')).toBe(false);
    expect(can(['SAFETY_MANAGER'], featureForRoute('/cism/ev1')!)).toBe(false);
    expect(can(['SAFETY_MANAGER'], 'peerSupportReport')).toBe(true);
    expect(can(['SAFETY_MANAGER'], 'peerSupportVolunteer')).toBe(false);
    for (const f of ['testingPolicyWrite', 'testingMro', 'testingRequirements', 'testingAnnualReport'] as const) expect(can(['SAFETY_MANAGER'], f)).toBe(false);
  });

  it('DA_PROGRAMME_MANAGER: policies, pools, selections, requirements, coverage, statistics – never results', () => {
    expect(area(['DA_PROGRAMME_MANAGER'])).toEqual(['/testing/policies', '/testing/pools', '/testing/selections', '/testing/requirements', '/testing/reports']);
    for (const f of ['testingPolicyWrite', 'testingCoverage', 'testingAnnualReport'] as const) expect(can(['DA_PROGRAMME_MANAGER'], f)).toBe(true);
    for (const route of [...TESTING_RESULTS, '/testing/collection']) expect(can(['DA_PROGRAMME_MANAGER'], featureForRoute(route)!)).toBe(false);
  });

  it('COLLECTOR: collection + requirements; MRO: queue, RTD, requirements; CMO: reports and coverage', () => {
    expect(paths(['COLLECTOR'])).toEqual(['/testing/requirements', '/testing/collection']);
    expect(homeRoute(['COLLECTOR'])).toBe('/testing/requirements');
    expect(can(['COLLECTOR'], 'testingMro')).toBe(false);
    expect(paths(['MRO'])).toEqual(['/testing/requirements', '/testing/mro', '/testing/rtd']);
    expect(can(['MRO'], 'testingSelections')).toBe(false);
    expect(area(['CMO'])).toEqual(['/peer-support', '/psychosocial', '/testing/policies', '/testing/reports']);
    for (const f of ['testingCoverage', 'testingAnnualReport', 'supportGovernance', 'peerSupportReport'] as const) expect(can(['CMO'], f)).toBe(true);
    expect(can(['CMO'], 'testingPolicyWrite')).toBe(false);
    expect(homeRoute(['CMO'])).toBe('/queues');
  });

  it('CISM names for PSYCH_CLINICIAN / OH_CLINICIAN; psychosocial register for HSE_OFFICER / CMO / NURSING_HEAD', () => {
    for (const r of ['PSYCH_CLINICIAN', 'OH_CLINICIAN']) expect(can([r], featureForRoute('/cism/ev1')!)).toBe(true);
    expect(featureForRoute('/cism/ev1')).toBe('cismNames');
    for (const r of ['HSE_OFFICER', 'CMO', 'NURSING_HEAD']) expect(paths([r])).toContain('/psychosocial');
    expect(paths(['OH_CLINICIAN'])).not.toContain('/psychosocial');
  });

  it.each([['HR'], ['MANAGER'], ['EMPLOYEE'], ['CREW_SCHEDULING'], ['PARTNER_PROVIDER'], ['DPO'], ['AUDITOR'], ['CLINIC_RECEPTION'], ['FINANCE']])('%s gets no E09 / E10 screen', (r) => {
    expect(area([r])).toEqual([]);
  });
});

describe('increment 5 – E11 travel health & public health, E12 health promotion gating (API-Contract-E09-E10-E11-E12 §5, §6)', () => {
  const E11 = ['/travel/consultations', '/travel/destinations', '/communicable', '/exposures', '/vaccination-campaigns', '/advisories', '/public-health'];
  const E12 = ['/promotion/insights', '/promotion/campaigns', '/promotion/screening', '/ergonomics', '/promotion/reports'];
  const area = (roles: string[]) => paths(roles).filter((p) => E11.includes(p) || E12.includes(p));
  const PERSON_LEVEL = ['/travel/consultations', '/communicable', '/communicable/cd1', '/exposures', '/exposures/x1', '/vaccination-campaigns', '/queues', '/employees/E1/chart'];

  it('clinicians: travel, communicable (incl. case detail), exposures, vaccination campaigns, advisories, public health, screening', () => {
    expect(area(['OH_CLINICIAN'])).toEqual([...E11, '/promotion/screening']);
    for (const r of ['/communicable/cd1', '/exposures/x1']) expect(can(['OH_CLINICIAN'], featureForRoute(r)!)).toBe(true);
    expect(featureForRoute('/communicable/cd1')).toBe('communicable');
    expect(featureForRoute('/exposures/x1')).toBe('exposurePathways');
    for (const f of ['travelDestinationWrite', 'advisoryWrite', 'publicHealthWrite', 'vaccinationCampaignInvite', 'vaccinationUptake'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(false);
    for (const f of ['vaccinationCampaignCreate', 'screeningEventWrite', 'screeningResultWrite'] as const) expect(can(['OH_CLINICIAN'], f)).toBe(true);
    expect(homeRoute(['OH_CLINICIAN'])).toBe('/queues');
  });

  it('physicians author advisories; OH_PHYSICIAN / CMO edit destinations and submit public-health reports (AME advisories only)', () => {
    for (const r of ['OH_PHYSICIAN', 'CMO', 'AME']) expect(can([r], 'advisoryWrite')).toBe(true);
    for (const r of ['OH_PHYSICIAN', 'CMO']) {
      expect(can([r], 'travelDestinationWrite')).toBe(true);
      expect(can([r], 'publicHealthWrite')).toBe(true);
    }
    expect(can(['AME'], 'publicHealthWrite')).toBe(false);
    expect(can(['AME'], 'travelDestinationWrite')).toBe(false);
  });

  it('case manager reads destinations, advisories and public-health reports – no person-level E11 work', () => {
    expect(area(['CASE_MANAGER'])).toEqual(['/travel/destinations', '/advisories', '/public-health']);
  });

  it('vaccination campaigns: NURSING_HEAD invites; NURSING_HEAD / CMO see uptake', () => {
    expect(can(['NURSING_HEAD'], 'vaccinationCampaignInvite')).toBe(true);
    for (const r of ['NURSING_HEAD', 'CMO']) expect(can([r], 'vaccinationUptake')).toBe(true);
    expect(can(['CMO'], 'vaccinationCampaignInvite')).toBe(false);
    expect(can(['OH_PHYSICIAN'], 'vaccinationUptake')).toBe(false);
  });

  it('HEALTH_PROMOTION_LEAD: insights, campaign planner, screening events (no result entry), reports – no person-level clinical screen', () => {
    expect(hasWorkspaceAccess(['HEALTH_PROMOTION_LEAD'])).toBe(true);
    expect(paths(['HEALTH_PROMOTION_LEAD'])).toEqual(['/promotion/insights', '/promotion/campaigns', '/promotion/screening', '/promotion/reports']);
    expect(homeRoute(['HEALTH_PROMOTION_LEAD'])).toBe('/promotion/insights');
    expect(can(['HEALTH_PROMOTION_LEAD'], 'screeningEventWrite')).toBe(true);
    expect(can(['HEALTH_PROMOTION_LEAD'], 'screeningResultWrite')).toBe(false);
    for (const r of PERSON_LEVEL) expect(can(['HEALTH_PROMOTION_LEAD'], featureForRoute(r)!)).toBe(false);
  });

  it('ERGONOMIST: ergonomics only (with photos); HSE_OFFICER: ergonomics without photo upload', () => {
    expect(paths(['ERGONOMIST'])).toEqual(['/ergonomics']);
    expect(homeRoute(['ERGONOMIST'])).toBe('/ergonomics');
    expect(can(['ERGONOMIST'], 'ergonomicsPhotos')).toBe(true);
    expect(paths(['HSE_OFFICER'])).toContain('/ergonomics');
    expect(can(['HSE_OFFICER'], 'ergonomicsPhotos')).toBe(false);
    for (const r of PERSON_LEVEL) expect(can(['ERGONOMIST'], featureForRoute(r)!)).toBe(false);
  });

  it('CMO / NURSING_HEAD read insights; CMO reads campaign reports; neither plans campaigns', () => {
    for (const r of ['CMO', 'NURSING_HEAD']) {
      expect(paths([r])).toContain('/promotion/insights');
      expect(can([r], 'promotionCampaigns')).toBe(false);
    }
    expect(paths(['CMO'])).toContain('/promotion/reports');
    expect(paths(['NURSING_HEAD'])).not.toContain('/promotion/reports');
    expect(homeRoute(['CMO'])).toBe('/queues');
    expect(homeRoute(['NURSING_HEAD'])).toBe('/queues');
  });

  it.each([['HR'], ['MANAGER'], ['EMPLOYEE'], ['CREW_SCHEDULING'], ['DPO'], ['SAFETY_MANAGER'], ['CLINIC_RECEPTION'], ['OH_ADMIN'], ['PSYCH_CLINICIAN_X']])('%s gets no E11 / E12 screen', (r) => {
    expect(area([r])).toEqual([]);
  });
});
