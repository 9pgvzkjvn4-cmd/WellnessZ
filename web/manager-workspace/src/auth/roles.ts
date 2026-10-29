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
