import { renderToStaticMarkup } from 'react-dom/server';
import { createElement } from 'react';
import { describe, expect, it } from 'vitest';
import { Restricted } from '../components/ui';
import { can, featureForRoute, hasWorkspaceAccess, homeRoute, isReceptionOnly, navFor, OH_ROLES } from './roles';

const paths = (roles: string[]) => navFor(roles).map((n) => n.path);

describe('workspace access', () => {
  it.each([...OH_ROLES, 'OH_ADMIN', 'CLINIC_RECEPTION', 'FIRST_AIDER', 'HSE_OFFICER', 'PLATFORM_ADMIN', 'ROLE_oh_clinician'])('allows %s', (r) => {
    expect(hasWorkspaceAccess([r])).toBe(true);
  });

  it.each([[[]], [['EMPLOYEE']], [['MANAGER']], [['HR']], [['AUDITOR']], [['CREW_SCHEDULING']], [['HYGIENE_MANAGER']], [['OH_CLINICIANX']]])('denies %j', (roles) => {
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
    expect(paths(['CLINIC_RECEPTION'])).toEqual(['/reception', '/documents/scan']);
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

  it('HSE officer sees reconciliation and incident → cases only', () => {
    expect(paths(['HSE_OFFICER'])).toEqual(['/intake/reconciliation', '/intake/incidents']);
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

  it.each([['HR'], ['MANAGER'], ['CREW_SCHEDULING'], ['HYGIENE_MANAGER'], ['CLAIMS_HANDLER'], ['EMPLOYEE'], ['PARTNER_PROVIDER'], ['INTEGRATION']])('%s never reaches this workspace', (r) => {
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
