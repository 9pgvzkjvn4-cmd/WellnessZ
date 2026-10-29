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
    expect(workspaceNav(['MANAGER']).map((n) => n.path)).toEqual([
      '/',
      '/refer',
      '/restrictions',
      '/duty-proposals',
      '/rtw-plans',
      '/documents',
      '/absence',
      '/advice-requests',
      '/compliance',
      '/analytics',
    ]);
  });

  it('gives HR restrictions, RTW plans (read-only), documents, job offers and IHR – not modified duty', async () => {
    const { canAcknowledgeRtw, canUse, workspaceNav } = await import('./roles');
    expect((['restrictions', 'rtwPlans', 'documents', 'jobOffers', 'ihr'] as const).every((f) => canUse(f, ['HR']))).toBe(true);
    expect(canUse('dutyProposals', ['HR'])).toBe(false);
    expect(canAcknowledgeRtw(['HR'])).toBe(false);
    expect(canAcknowledgeRtw(['MANAGER'])).toBe(true);
    expect(workspaceNav(['HR']).map((n) => n.path)).toEqual([
      '/',
      '/refer/hr',
      '/restrictions',
      '/rtw-plans',
      '/documents',
      '/absence',
      '/advice-requests',
      '/compliance',
      '/pre-employment',
      '/claims',
      '/analytics',
    ]);
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

describe('increment 3 role gating (E06 / E07)', () => {
  it('gives managers absence, OH advice and compliance – not pre-employment', async () => {
    const { canUse, workspaceNav } = await import('./roles');
    expect((['absence', 'adviceRequests', 'compliance'] as const).every((f) => canUse(f, ['MANAGER']))).toBe(true);
    expect(canUse('preEmployment', ['MANAGER'])).toBe(false);
    const nav = workspaceNav(['ROLE_manager']);
    expect(nav).toContainEqual({ path: '/absence', label: 'Absence' });
    expect(nav).toContainEqual({ path: '/advice-requests', label: 'OH advice' });
    expect(nav).toContainEqual({ path: '/compliance', label: 'Health surveillance' });
    expect(nav.map((n) => n.path)).not.toContain('/pre-employment');
  });

  it('gives HR all four increment-3 screens', async () => {
    const { canUse } = await import('./roles');
    expect((['absence', 'adviceRequests', 'compliance', 'preEmployment'] as const).every((f) => canUse(f, ['hr']))).toBe(true);
  });

  it('a manager who is also HR gets the union, each entry once', async () => {
    const { workspaceNav } = await import('./roles');
    const paths = workspaceNav(['MANAGER', 'HR']).map((n) => n.path);
    expect(paths.filter((p) => p === '/absence')).toHaveLength(1);
    expect(paths).toEqual(expect.arrayContaining(['/refer', '/refer/hr', '/duty-proposals', '/pre-employment', '/compliance']));
  });

  it('hides increment-3 entries from OH, admin and other roles', async () => {
    const { canUse } = await import('./roles');
    for (const roles of [['OH_CLINICIAN'], ['PLATFORM_ADMIN'], ['CASE_MANAGER'], [] as string[]]) {
      expect((['absence', 'adviceRequests', 'compliance', 'preEmployment'] as const).some((f) => canUse(f, roles))).toBe(false);
    }
  });

  it('resolves the routes and blocks direct navigation without the role', async () => {
    const { resolveFeatureRoute } = await import('./roles');
    expect(resolveFeatureRoute('/absence', ['MANAGER'])).toEqual({ feature: 'absence', allowed: true });
    expect(resolveFeatureRoute('/advice-requests', ['HR'])).toEqual({ feature: 'adviceRequests', allowed: true });
    expect(resolveFeatureRoute('/compliance', ['MANAGER'])).toEqual({ feature: 'compliance', allowed: true });
    expect(resolveFeatureRoute('/pre-employment', ['HR'])).toEqual({ feature: 'preEmployment', allowed: true });
    expect(resolveFeatureRoute('/pre-employment', ['MANAGER'])).toEqual({ feature: 'preEmployment', allowed: false });
    expect(resolveFeatureRoute('/absence', ['OH_CLINICIAN'])).toEqual({ feature: 'absence', allowed: false });
    expect(resolveFeatureRoute('/absence/lta', ['HR'])).toBeUndefined();
  });

  it('shows the HR-only absence sections to HR only', async () => {
    const { absenceSections } = await import('./roles');
    expect(absenceSections(['MANAGER'])).toEqual({ team: true, lta: false, hrStage: false, certificates: false, analytics: false });
    expect(absenceSections(['HR'])).toEqual({ team: true, lta: true, hrStage: true, certificates: true, analytics: true });
    expect(absenceSections(['OH_ADMIN'])).toMatchObject({ team: false, lta: false });
  });

  it('increment 4: claim status (/claims) is HR-only', async () => {
    const { canUse, resolveFeatureRoute, workspaceNav } = await import('./roles');
    expect(canUse('claims', ['HR'])).toBe(true);
    expect(canUse('claims', ['ROLE_hr'])).toBe(true);
    expect(canUse('claims', ['MANAGER'])).toBe(false);
    for (const roles of [['OH_CLINICIAN'], ['CASE_MANAGER'], ['PLATFORM_ADMIN'], ['CLAIMS_COORDINATOR'], [] as string[]]) expect(canUse('claims', roles)).toBe(false);
    expect(workspaceNav(['HR'])).toContainEqual({ path: '/claims', label: 'Claims' });
    expect(workspaceNav(['MANAGER']).map((n) => n.path)).not.toContain('/claims');
    expect(workspaceNav(['MANAGER', 'HR']).filter((n) => n.path === '/claims')).toHaveLength(1);
    expect(resolveFeatureRoute('/claims', ['HR'])).toEqual({ feature: 'claims', allowed: true });
    expect(resolveFeatureRoute('/claims', ['MANAGER'])).toEqual({ feature: 'claims', allowed: false });
    expect(resolveFeatureRoute('/claims/CLM-2026-000001', ['HR'])).toBeUndefined();
  });
});
