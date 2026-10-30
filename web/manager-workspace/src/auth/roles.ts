/** Roles with the team-case view (matches the increment 1–4 BFF /bff/api/workspace/** rule). */
export const CASE_ROLES = ['MANAGER', 'HR', 'OH_CLINICIAN', 'OH_ADMIN', 'CASE_MANAGER', 'PSYCH_CLINICIAN', 'PLATFORM_ADMIN'] as const;

/**
 * Roles allowed into the workspace. Increment 5 adds SAFETY_MANAGER (for-cause test requests) and CREW_SCHEDULING
 * (duty-status list) – both see the testing screen only (docs/increment-05/API-Contract-E09-E10-E11-E12.md §4.2, §4.4).
 */
export const WORKSPACE_ROLES = [...CASE_ROLES, 'SAFETY_MANAGER', 'CREW_SCHEDULING'] as const;

/** `roles` are expected normalised (upper-case, no ROLE_ prefix); normalises defensively anyway. */
export function hasWorkspaceAccess(roles: readonly string[] | undefined): boolean {
  if (!roles) return false;
  return roles.some((r) => (WORKSPACE_ROLES as readonly string[]).includes(r.trim().toUpperCase().replace(/^ROLE_/, '')));
}

const has = (roles: readonly string[] | undefined, role: string) =>
  !!roles?.some((r) => r.trim().toUpperCase().replace(/^ROLE_/, '') === role);

/** Team cases, referrals and analytics need one of the increment 1–4 workspace roles. */
export const hasCaseAccess = (roles: readonly string[] | undefined) => CASE_ROLES.some((r) => has(roles, r));

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
  | 'preEmployment'
  | 'claims'
  | 'testing'
  | 'psychosocial'
  | 'campaigns'
  | 'ergonomics';

export const isSafetyManager = (roles: readonly string[] | undefined) => has(roles, 'SAFETY_MANAGER');
export const isCrewScheduling = (roles: readonly string[] | undefined) => has(roles, 'CREW_SCHEDULING');

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
    // increment 4 (docs/increment-04/API-Contract-E04-E08.md §4.1, §6): HR claim status only – not for line managers
    case 'claims':
      return isHr(roles);
    // increment 5 (docs/increment-05/API-Contract-E09-E10-E11-E12.md §3.1, §3.5, §4.2, §4.4, §6)
    case 'testing': {
      const t = testingSections(roles);
      return t.forCause || t.dutyStatus;
    }
    case 'psychosocial':
    case 'campaigns':
    case 'ergonomics':
      return isManager(roles);
  }
}

/**
 * Testing screen sections: MANAGER / SAFETY_MANAGER raise for-cause requests (and see their own requests, status only);
 * HR / CREW_SCHEDULING see the duty-status list (AVAILABLE / REMOVED / RETURN_PENDING – never reasons or results).
 */
export function testingSections(roles: readonly string[] | undefined) {
  return { forCause: isManager(roles) || isSafetyManager(roles), dutyStatus: isHr(roles) || isCrewScheduling(roles) };
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
  claims: '/claims',
  testing: '/testing',
  psychosocial: '/psychosocial',
  campaigns: '/campaigns',
  ergonomics: '/ergonomics',
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
  claims: 'Claims',
  testing: 'Testing',
  psychosocial: 'Psychosocial risk',
  campaigns: 'Health campaigns',
  ergonomics: 'Ergonomics',
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
  // Safety managers / crew scheduling without a case role only get their increment-5 screen.
  if (!hasCaseAccess(roles)) return features;
  return [{ path: '/', label: 'Team cases' }, ...referralNav(roles), ...features, { path: '/analytics', label: 'Analytics' }];
}

/** Start page: team cases, or the testing screen for sessions without a case role. */
export const homeRoute = (roles: readonly string[] | undefined) => (hasCaseAccess(roles) ? '/' : FEATURE_ROUTES.testing);
