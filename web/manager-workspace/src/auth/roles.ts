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

/* ---------------- increment 2: fitness for duty / RTW / documents ---------------- */

export const isManager = (roles: readonly string[] | undefined) => has(roles, 'MANAGER');
export const isHr = (roles: readonly string[] | undefined) => has(roles, 'HR');

/**
 * Increment-2 features and who may use them (UX guard; the API enforces scope and roles):
 * managers – team restrictions, modified duty, RTW plans (acknowledge), documents;
 * HR – team restrictions, RTW plans (read-only), documents, job offers, ill-health retirement / redeployment.
 */
export type Feature =
  | 'restrictions'
  | 'dutyProposals'
  | 'rtwPlans'
  | 'documents'
  | 'jobOffers'
  | 'ihr'
  | 'absence'
  | 'adviceRequests'
  | 'compliance'
  | 'preEmployment';

export function canUse(feature: Feature, roles: readonly string[] | undefined): boolean {
  switch (feature) {
    case 'restrictions':
    case 'rtwPlans':
    case 'documents':
      return isManager(roles) || isHr(roles);
    case 'dutyProposals':
      return isManager(roles);
    case 'jobOffers':
    case 'ihr':
      return isHr(roles);
    // increment 3 (docs/increment-03/API-Contract-E06-E07-E14.md §3.1, §3.5, §4.2, §4.3)
    case 'absence':
    case 'adviceRequests':
    case 'compliance':
      return isManager(roles) || isHr(roles);
    case 'preEmployment':
      return isHr(roles);
  }
}

/** `POST /rtw-plans/{id}/manager-acknowledge` is MANAGER-only; HR sees RTW plans read-only. */
export const canAcknowledgeRtw = (roles: readonly string[] | undefined) => isManager(roles);

export const FEATURE_ROUTES = {
  restrictions: '/restrictions',
  dutyProposals: '/duty-proposals',
  rtwPlans: '/rtw-plans',
  documents: '/documents',
  absence: '/absence',
  adviceRequests: '/advice-requests',
  compliance: '/compliance',
  preEmployment: '/pre-employment',
} as const;
const FEATURE_LABELS: Record<keyof typeof FEATURE_ROUTES, string> = {
  restrictions: 'Team restrictions',
  dutyProposals: 'Modified duty',
  rtwPlans: 'Return to work',
  documents: 'Documents',
  absence: 'Absence',
  adviceRequests: 'OH advice',
  compliance: 'Health surveillance',
  preEmployment: 'Pre-employment',
};

/**
 * Increment-3 absence screen sections (UX guard; the API enforces scope and roles). Managers see their team's absence
 * status; HR additionally sees the long-term absence pathway (and may set the HR stage), the certificate validity list
 * with escalations and unit-level absence analytics / the monthly export.
 */
export function absenceSections(roles: readonly string[] | undefined) {
  const hr = isHr(roles);
  return { team: hr || isManager(roles), lta: hr, hrStage: hr, certificates: hr, analytics: hr };
}

export const jobOffersRoute = (caseId: string) => `/cases/${encodeURIComponent(caseId)}/job-offers`;
export const ihrRoute = (caseId: string) => `/cases/${encodeURIComponent(caseId)}/ihr`;

/** Which increment-2 screen a route shows; `allowed=false` → "not available" page. Undefined for other routes. */
export function resolveFeatureRoute(
  route: string,
  roles: readonly string[] | undefined,
): { feature: Feature; caseId?: string; allowed: boolean } | undefined {
  const top = (Object.keys(FEATURE_ROUTES) as (keyof typeof FEATURE_ROUTES)[]).find((k) => FEATURE_ROUTES[k] === route);
  if (top) return { feature: top, allowed: canUse(top, roles) };
  const m = /^\/cases\/([^/]+)\/(job-offers|ihr)$/.exec(route);
  if (!m?.[1]) return undefined;
  const feature: Feature = m[2] === 'ihr' ? 'ihr' : 'jobOffers';
  let caseId: string;
  try {
    caseId = decodeURIComponent(m[1]);
  } catch {
    return undefined;
  }
  return { feature, caseId, allowed: canUse(feature, roles) };
}

/** Navigation for the session: referral and increment-2 entries only for the roles that may use them. */
export function workspaceNav(roles: readonly string[] | undefined): { path: string; label: string }[] {
  const features = (Object.keys(FEATURE_ROUTES) as (keyof typeof FEATURE_ROUTES)[])
    .filter((k) => canUse(k, roles))
    .map((k) => ({ path: FEATURE_ROUTES[k], label: FEATURE_LABELS[k] }));
  return [{ path: '/', label: 'Team cases' }, ...referralNav(roles), ...features, { path: '/analytics', label: 'Analytics' }];
}
