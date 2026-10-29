/**
 * Partner portal role gating (UX guard only – the BFF and core API enforce roles, organisation scope and MFA).
 * Contract E04-E08 §1 / §6: PARTNER_PROVIDER → referrals, telehealth, documents; TPA_ADJUSTER → claims,
 * pre-authorisations (and shared documents, §2.3). Any other role → "not available".
 */
export const PARTNER_PROVIDER = 'PARTNER_PROVIDER';
export const TPA_ADJUSTER = 'TPA_ADJUSTER';
export const PARTNER_ROLES = [PARTNER_PROVIDER, TPA_ADJUSTER] as const;

const norm = (r: string) => r.trim().toUpperCase().replace(/^ROLE_/, '');
const has = (roles: readonly string[] | undefined, role: string) => !!roles?.some((r) => norm(r) === role);

export const isProvider = (roles: readonly string[] | undefined) => has(roles, PARTNER_PROVIDER);
export const isAdjuster = (roles: readonly string[] | undefined) => has(roles, TPA_ADJUSTER);
export const hasPartnerRole = (roles: readonly string[] | undefined) => isProvider(roles) || isAdjuster(roles);

/**
 * Access decision after sign-in. The core API grants the partner roles only with MFA proof, so `/me` is
 * authoritative: a partner role there → `partner`. Without it: if the BFF session (ID token) carries a partner role,
 * or `/me` shows no role at all, the account most likely signed in without a second factor → `mfa`; any other role
 * (employee, OH staff, manager…) → `other`.
 */
export function resolveAccess(sessionRoles: readonly string[] | undefined, meRoles: readonly string[] | undefined): 'partner' | 'mfa' | 'other' {
  if (hasPartnerRole(meRoles)) return 'partner';
  if (hasPartnerRole(sessionRoles)) return 'mfa';
  const known = [...(meRoles ?? []), ...(sessionRoles ?? [])].map(norm).filter(Boolean);
  return known.length === 0 ? 'mfa' : 'other';
}

export type Area = 'referrals' | 'telehealth' | 'documents' | 'claims' | 'preauthorisations';

export function canUse(area: Area, roles: readonly string[] | undefined): boolean {
  switch (area) {
    case 'referrals':
    case 'telehealth':
      return isProvider(roles);
    case 'claims':
    case 'preauthorisations':
      return isAdjuster(roles);
    case 'documents':
      return hasPartnerRole(roles);
  }
}

export const AREA_ROUTES: Record<Area, string> = {
  referrals: '/referrals',
  telehealth: '/telehealth',
  claims: '/claims',
  preauthorisations: '/preauthorisations',
  documents: '/documents',
};
const NAV_ORDER: Area[] = ['referrals', 'telehealth', 'claims', 'preauthorisations', 'documents'];

export interface NavItem {
  area: Area;
  path: string;
}

/** Navigation entries for the session's roles (provider areas first, then adjuster areas, then documents). */
export function portalNav(roles: readonly string[] | undefined): NavItem[] {
  return NAV_ORDER.filter((a) => canUse(a, roles)).map((area) => ({ area, path: AREA_ROUTES[area] }));
}

/** Start page: the first navigation entry. */
export const homeRoute = (roles: readonly string[] | undefined) => portalNav(roles)[0]?.path ?? '/documents';

export type ResolvedRoute =
  | { kind: 'home' }
  | { kind: 'area'; area: Area; allowed: boolean }
  | { kind: 'referral'; id: string; allowed: boolean }
  | { kind: 'claim'; id: string; allowed: boolean }
  | { kind: 'notFound' };

const decode = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return undefined;
  }
};

export function resolveRoute(route: string, roles: readonly string[] | undefined): ResolvedRoute {
  if (route === '/' || route === '') return { kind: 'home' };
  const area = (Object.keys(AREA_ROUTES) as Area[]).find((a) => AREA_ROUTES[a] === route);
  if (area) return { kind: 'area', area, allowed: canUse(area, roles) };
  const m = /^\/(referrals|claims)\/([^/]+)$/.exec(route);
  const id = m?.[2] ? decode(m[2]) : undefined;
  if (m && id) {
    return m[1] === 'referrals' ? { kind: 'referral', id, allowed: canUse('referrals', roles) } : { kind: 'claim', id, allowed: canUse('claims', roles) };
  }
  return { kind: 'notFound' };
}

/** Navigation entry to highlight for a route (detail pages highlight their list). */
export function currentArea(route: string): Area | undefined {
  const top = `/${route.split('/')[1] ?? ''}`;
  return (Object.keys(AREA_ROUTES) as Area[]).find((a) => AREA_ROUTES[a] === top);
}

export const referralRoute = (id: string) => `/referrals/${encodeURIComponent(id)}`;
export const claimRoute = (id: string) => `/claims/${encodeURIComponent(id)}`;
