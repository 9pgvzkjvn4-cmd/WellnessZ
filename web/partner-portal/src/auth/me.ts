import { ApiError, normaliseRoles, type AuthClient } from './AuthClient';

/**
 * `GET /me` (via `/bff/api/me`) → `{subject, username, displayName, employeeId, orgScope, roles, maxDisclosure}`.
 * The core API only includes PARTNER_PROVIDER / TPA_ADJUSTER when the token proves MFA (contract, confidentiality rule 5),
 * so the portal decides access on these roles, not on the BFF session's.
 */
export interface Me {
  displayName?: string;
  orgScope?: string;
  /** Last segment of `orgScope` (`/PARTNERS/CITYHOSP` → `CITYHOSP`). */
  orgCode?: string;
  roles: string[];
}

export function orgCodeOf(orgScope: string | undefined): string | undefined {
  if (!orgScope) return undefined;
  const parts = orgScope.split('/').map((p) => p.trim()).filter(Boolean);
  return parts[parts.length - 1];
}

export function toMe(body: unknown): Me {
  const r = body && typeof body === 'object' ? (body as Record<string, unknown>) : {};
  const orgScope = typeof r.orgScope === 'string' && r.orgScope ? r.orgScope : undefined;
  const displayName = typeof r.displayName === 'string' && r.displayName ? r.displayName : typeof r.username === 'string' ? r.username : undefined;
  return { displayName, orgScope, orgCode: orgCodeOf(orgScope), roles: normaliseRoles(r.roles) };
}

/** Load `/me`; a 403 (partner role withheld without MFA) is treated as "no roles". Other errors propagate. */
export async function loadMe(client: Pick<AuthClient, 'api'>): Promise<Me> {
  try {
    return toMe(await client.api<unknown>('/me'));
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) return { roles: [] };
    throw e;
  }
}
