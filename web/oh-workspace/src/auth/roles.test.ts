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
  it('CLINIC_RECEPTION and FIRST_AIDER see only the reception screen', () => {
    expect(paths(['FIRST_AIDER'])).toEqual(['/reception']);
    expect(paths(['CLINIC_RECEPTION'])).toEqual(['/reception']);
    expect(isReceptionOnly(['FIRST_AIDER'])).toBe(true);
    expect(homeRoute(['CLINIC_RECEPTION'])).toBe('/reception');
  });

  it('reception roles cannot open clinical screens or convert encounters', () => {
    for (const r of ['/queues', '/cases/abc', '/cases/abc/triage', '/employees/E1/timeline', '/caseload', '/admin/sla']) {
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
