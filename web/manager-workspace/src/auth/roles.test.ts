import { describe, expect, it } from 'vitest';
import { hasWorkspaceAccess } from './roles';

describe('workspace role gating', () => {
  it.each([
    ['MANAGER'],
    ['HR'],
    ['OH_CLINICIAN'],
    ['OH_ADMIN'],
    ['CASE_MANAGER'],
    ['PSYCH_CLINICIAN'],
    ['PLATFORM_ADMIN'],
    ['ROLE_manager'],
    ['hr'],
  ])('allows %s', (role) => {
    expect(hasWorkspaceAccess([role])).toBe(true);
  });

  it.each([[[]], [['EMPLOYEE']], [['AUDITOR']], [['PARTNER_PROVIDER']], [['INTEGRATION']], [['MANAGERX']], [['OH_UNKNOWN']]])(
    'denies %j',
    (roles) => {
      expect(hasWorkspaceAccess(roles)).toBe(false);
    },
  );

  it('denies an undefined role list', () => {
    expect(hasWorkspaceAccess(undefined)).toBe(false);
  });

  it('allows if any role qualifies', () => {
    expect(hasWorkspaceAccess(['EMPLOYEE', 'MANAGER'])).toBe(true);
  });
});

describe('increment 2 role gating (E05 / E03)', () => {
  it('gives managers restrictions, modified duty, RTW plans and documents', async () => {
    const { canUse, workspaceNav } = await import('./roles');
    expect((['restrictions', 'dutyProposals', 'rtwPlans', 'documents'] as const).every((f) => canUse(f, ['MANAGER']))).toBe(true);
    expect(canUse('jobOffers', ['MANAGER'])).toBe(false);
    expect(canUse('ihr', ['ROLE_manager'])).toBe(false);
    expect(workspaceNav(['MANAGER']).map((n) => n.path)).toEqual(['/', '/refer', '/restrictions', '/duty-proposals', '/rtw-plans', '/documents', '/analytics']);
  });

  it('gives HR restrictions, RTW plans (read-only), documents, job offers and IHR – not modified duty', async () => {
    const { canAcknowledgeRtw, canUse, workspaceNav } = await import('./roles');
    expect((['restrictions', 'rtwPlans', 'documents', 'jobOffers', 'ihr'] as const).every((f) => canUse(f, ['HR']))).toBe(true);
    expect(canUse('dutyProposals', ['HR'])).toBe(false);
    expect(canAcknowledgeRtw(['HR'])).toBe(false);
    expect(canAcknowledgeRtw(['MANAGER'])).toBe(true);
    expect(workspaceNav(['HR']).map((n) => n.path)).toEqual(['/', '/refer/hr', '/restrictions', '/rtw-plans', '/documents', '/analytics']);
  });

  it('shows no increment-2 entries to OH or admin roles', async () => {
    const { workspaceNav } = await import('./roles');
    expect(workspaceNav(['OH_CLINICIAN', 'PLATFORM_ADMIN']).map((n) => n.path)).toEqual(['/', '/analytics']);
  });

  it('resolves feature routes and blocks direct navigation without the role', async () => {
    const { resolveFeatureRoute } = await import('./roles');
    expect(resolveFeatureRoute('/duty-proposals', ['MANAGER'])).toEqual({ feature: 'dutyProposals', allowed: true });
    expect(resolveFeatureRoute('/duty-proposals', ['HR'])).toEqual({ feature: 'dutyProposals', allowed: false });
    expect(resolveFeatureRoute('/restrictions', ['OH_CLINICIAN'])).toEqual({ feature: 'restrictions', allowed: false });
    expect(resolveFeatureRoute('/cases/c%2F1/job-offers', ['HR'])).toEqual({ feature: 'jobOffers', caseId: 'c/1', allowed: true });
    expect(resolveFeatureRoute('/cases/c1/job-offers', ['MANAGER'])).toEqual({ feature: 'jobOffers', caseId: 'c1', allowed: false });
    expect(resolveFeatureRoute('/cases/c1/ihr', ['HR'])).toEqual({ feature: 'ihr', caseId: 'c1', allowed: true });
    expect(resolveFeatureRoute('/cases/c1/ihr', ['MANAGER'])).toMatchObject({ allowed: false });
    expect(resolveFeatureRoute('/cases/c1', ['HR'])).toBeUndefined();
    expect(resolveFeatureRoute('/analytics', ['HR'])).toBeUndefined();
  });
});
