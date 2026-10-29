/**
 * Navigation of the employee app by role. Candidates (role CANDIDATE, pre-employment, not in the HRIS master) who have
 * no employee features see only the pre-employment section – every route resolves there.
 */

export type NavSource = 'portal' | 'oh';
export interface NavItem {
  path: string;
  /** Key in `PortalStrings['nav']` (source portal) or `OhStrings['nav']` (source oh). */
  key: string;
  source: NavSource;
}

const norm = (r: string) => r.trim().toUpperCase().replace(/^ROLE_/, '');
const has = (roles: readonly string[] | undefined, role: string) => !!roles?.some((r) => norm(r) === role);

export const PRE_EMPLOYMENT_ROUTE = '/pre-employment';

/** CANDIDATE without EMPLOYEE: only the pre-employment section. */
export function isCandidateOnly(roles: readonly string[] | undefined): boolean {
  return has(roles, 'CANDIDATE') && !has(roles, 'EMPLOYEE');
}

const EMPLOYEE_NAV: readonly NavItem[] = [
  { path: '/', key: 'home', source: 'portal' },
  { path: '/refer', key: 'refer', source: 'portal' },
  { path: '/appointments', key: 'appointments', source: 'oh' },
  { path: '/advice', key: 'advice', source: 'portal' },
  { path: '/absence', key: 'absence', source: 'oh' },
  { path: '/surveillance', key: 'surveillance', source: 'oh' },
  { path: '/documents', key: 'documents', source: 'portal' },
  { path: '/rtw', key: 'rtw', source: 'portal' },
  { path: '/job-offers', key: 'offers', source: 'portal' },
  { path: '/fitness-declaration', key: 'declaration', source: 'portal' },
  { path: '/privacy', key: 'privacy', source: 'portal' },
  { path: '/consents', key: 'consents', source: 'portal' },
  { path: '/profile', key: 'profile', source: 'portal' },
];

const PRE_EMPLOYMENT_ITEM: NavItem = { path: PRE_EMPLOYMENT_ROUTE, key: 'preEmployment', source: 'oh' };

export function navFor(roles: readonly string[] | undefined): NavItem[] {
  if (isCandidateOnly(roles)) return [PRE_EMPLOYMENT_ITEM];
  const items = [...EMPLOYEE_NAV];
  // An employee who is also a candidate (internal move) keeps every section plus pre-employment.
  if (has(roles, 'CANDIDATE')) items.splice(1, 0, PRE_EMPLOYMENT_ITEM);
  return items;
}

/** Route actually rendered: candidates are always taken to pre-employment; employees without CANDIDATE never see it. */
export function resolveRoute(route: string, roles: readonly string[] | undefined): string {
  if (isCandidateOnly(roles)) return PRE_EMPLOYMENT_ROUTE;
  if (route === PRE_EMPLOYMENT_ROUTE && !has(roles, 'CANDIDATE')) return '/';
  return route;
}
