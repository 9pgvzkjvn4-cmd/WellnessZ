/** Roles allowed into the manager/HR workspace (matches the BFF /bff/api/workspace/** rule). */
export const WORKSPACE_ROLES = [
  'MANAGER',
  'HR',
  'OH_CLINICIAN',
  'OH_ADMIN',
  'CASE_MANAGER',
  'PSYCH_CLINICIAN',
  'PLATFORM_ADMIN',
] as const;

/** `roles` are expected normalised (upper-case, no ROLE_ prefix); normalises defensively anyway. */
export function hasWorkspaceAccess(roles: readonly string[] | undefined): boolean {
  if (!roles) return false;
  return roles.some((r) => (WORKSPACE_ROLES as readonly string[]).includes(r.trim().toUpperCase().replace(/^ROLE_/, '')));
}

const has = (roles: readonly string[] | undefined, role: string) =>
  !!roles?.some((r) => r.trim().toUpperCase().replace(/^ROLE_/, '') === role);

/** Manager referral (`POST /intake/referrals/manager`) is MANAGER-only. */
export const canManagerRefer = (roles: readonly string[] | undefined) => has(roles, 'MANAGER');
/** HR referral (`POST /intake/referrals/hr`) is HR-only. */
export const canHrRefer = (roles: readonly string[] | undefined) => has(roles, 'HR');

export const MANAGER_REFERRAL_ROUTE = '/refer';
export const HR_REFERRAL_ROUTE = '/refer/hr';

/** Navigation entries for the referral forms the session may use (UX guard; the API enforces the roles). */
export function referralNav(roles: readonly string[] | undefined): { path: string; label: string }[] {
  const nav: { path: string; label: string }[] = [];
  if (canManagerRefer(roles)) nav.push({ path: MANAGER_REFERRAL_ROUTE, label: 'Refer to OH' });
  if (canHrRefer(roles)) nav.push({ path: HR_REFERRAL_ROUTE, label: 'HR referral' });
  return nav;
}

/** Which referral screen a route shows for these roles; undefined if the route is not a referral route. */
export function resolveReferralRoute(route: string, roles: readonly string[] | undefined): 'manager' | 'hr' | 'forbidden' | undefined {
  if (route === MANAGER_REFERRAL_ROUTE) return canManagerRefer(roles) ? 'manager' : 'forbidden';
  if (route === HR_REFERRAL_ROUTE) return canHrRefer(roles) ? 'hr' : 'forbidden';
  return undefined;
}

/** Navigation for the session: referral entries only for the roles that may submit them. */
export function workspaceNav(roles: readonly string[] | undefined): { path: string; label: string }[] {
  return [{ path: '/', label: 'Team cases' }, ...referralNav(roles), { path: '/analytics', label: 'Analytics' }];
}
