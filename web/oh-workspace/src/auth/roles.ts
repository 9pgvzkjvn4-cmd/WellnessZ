/**
 * UI role gating for the OH workspace. Convenience only – wz-core-app enforces every rule server-side.
 * Role names follow API-Contract-E01-E02 §1 (normalised: upper-case, no ROLE_ prefix).
 */

/** "OH roles" as defined in the contract. */
export const OH_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'CASE_MANAGER', 'PSYCH_CLINICIAN'] as const;
export const RECEPTION_ROLES = ['CLINIC_RECEPTION', 'FIRST_AIDER'] as const;
export const ADMIN_ROLES = ['OH_ADMIN', 'NURSING_HEAD'] as const;
export const SENIOR_CLINICIAN_ROLES = ['OH_PHYSICIAN', 'AME', 'CMO'] as const;
export const TRIAGE_WRITE_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'] as const;
export const FITNESS_WRITE_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'PSYCH_CLINICIAN'] as const;

const OH = [...OH_ROLES];

/** Every UI capability and the roles that unlock it. */
export const FEATURES = {
  // E02-F04 queues & case
  queues: OH,
  caseView: OH,
  employeeTimeline: OH,
  triageWrite: [...TRIAGE_WRITE_ROLES],
  recordabilityWrite: ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'],
  fitnessWrite: [...FITNESS_WRITE_ROLES],
  bulkReassign: ['NURSING_HEAD'],
  escalationDecide: [...SENIOR_CLINICIAN_ROLES],
  conferenceSchedule: ['CMO'],
  escalations: OH,
  handovers: OH,
  // E01 intake
  suggestions: OH,
  reconciliation: [...OH, 'HSE_OFFICER'],
  reconciliationExceptions: ['HSE_OFFICER', 'NURSING_HEAD'],
  incidentCases: [...OH, 'HSE_OFFICER'],
  dlq: ['OH_ADMIN', 'PLATFORM_ADMIN'],
  intakeDashboard: ['NURSING_HEAD', 'OH_ADMIN', 'CMO'],
  // E01-F03 reception / first aid
  reception: [...RECEPTION_ROLES, ...OH],
  encounterList: [...OH, 'CLINIC_RECEPTION'],
  encounterConvert: OH,
  // E02-F03 caseload & administration
  caseload: OH,
  caseloadRebalance: ['NURSING_HEAD'],
  admin: [...ADMIN_ROLES],
  adminWrite: [...ADMIN_ROLES],
  adminOnlyWrite: ['OH_ADMIN'],
  protocols: [...OH, 'OH_ADMIN'],
  protocolApprove: ['CMO'],
  triageAudit: ['NURSING_HEAD', 'CMO', 'OH_PHYSICIAN'],
  triageAuditSample: ['NURSING_HEAD'],
} satisfies Record<string, readonly string[]>;

export type Feature = keyof typeof FEATURES;

function norm(r: string): string {
  return r.trim().toUpperCase().replace(/^ROLE_/, '');
}

export function hasAnyRole(roles: readonly string[] | undefined, allowed: readonly string[]): boolean {
  if (!roles) return false;
  return roles.some((r) => allowed.includes(norm(r)));
}

export function can(roles: readonly string[] | undefined, feature: Feature): boolean {
  return hasAnyRole(roles, FEATURES[feature]);
}

export function isOhRole(roles: readonly string[] | undefined): boolean {
  return hasAnyRole(roles, OH);
}

/** Reception / first-aid staff without any OH role see only the reception screen. */
export function isReceptionOnly(roles: readonly string[] | undefined): boolean {
  return hasAnyRole(roles, RECEPTION_ROLES) && !isOhRole(roles);
}

/** Anyone with at least one screen in this workspace. */
export function hasWorkspaceAccess(roles: readonly string[] | undefined): boolean {
  return (Object.keys(FEATURES) as Feature[]).some((f) => can(roles, f));
}

export interface NavItem {
  path: string;
  label: string;
  feature: Feature;
  group: 'Work' | 'Intake' | 'Reception' | 'Team' | 'Configuration';
}

export const NAV: readonly NavItem[] = [
  { path: '/queues', label: 'Work queues', feature: 'queues', group: 'Work' },
  { path: '/escalations', label: 'Escalations', feature: 'escalations', group: 'Work' },
  { path: '/handovers', label: 'Handovers', feature: 'handovers', group: 'Work' },
  { path: '/reception', label: 'Walk-in & first aid', feature: 'reception', group: 'Reception' },
  { path: '/intake/suggestions', label: 'Incident suggestions', feature: 'suggestions', group: 'Intake' },
  { path: '/intake/reconciliation', label: 'Reconciliation', feature: 'reconciliation', group: 'Intake' },
  { path: '/intake/incidents', label: 'Incident → cases', feature: 'incidentCases', group: 'Intake' },
  { path: '/intake/dashboard', label: 'Intake dashboard', feature: 'intakeDashboard', group: 'Intake' },
  { path: '/intake/dlq', label: 'Dead-letter queue', feature: 'dlq', group: 'Intake' },
  { path: '/caseload', label: 'Caseload', feature: 'caseload', group: 'Team' },
  { path: '/triage-audit', label: 'Triage audit', feature: 'triageAudit', group: 'Team' },
  { path: '/admin/protocols', label: 'Triage protocols', feature: 'protocols', group: 'Configuration' },
  { path: '/admin/assignment-rules', label: 'Assignment rules', feature: 'admin', group: 'Configuration' },
  { path: '/admin/sla', label: 'SLA policies', feature: 'admin', group: 'Configuration' },
  { path: '/admin/intake-rules', label: 'Intake rules', feature: 'admin', group: 'Configuration' },
  { path: '/admin/notifications', label: 'Notification matrix', feature: 'admin', group: 'Configuration' },
  { path: '/admin/recordability-mapping', label: 'Recordability mapping', feature: 'admin', group: 'Configuration' },
];

export function navFor(roles: readonly string[] | undefined): NavItem[] {
  return NAV.filter((n) => can(roles, n.feature));
}

/** Landing route: reception-only staff land on reception, everyone else on the first screen they may use. */
export function homeRoute(roles: readonly string[] | undefined): string {
  if (isReceptionOnly(roles)) return '/reception';
  return navFor(roles)[0]?.path ?? '/restricted';
}

/** Feature required to open a route (undefined = no such route). */
export function featureForRoute(route: string): Feature | undefined {
  if (/^\/cases\/[^/]+(\/[a-z-]+)?$/.test(route)) return 'caseView';
  if (/^\/employees\/[^/]+\/timeline$/.test(route)) return 'employeeTimeline';
  if (/^\/intake\/incidents(\/[^/]+)?$/.test(route)) return 'incidentCases';
  return NAV.find((n) => n.path === route)?.feature;
}
