/**
 * Increment 4 – E08 claim status for HR (docs/increment-04/API-Contract-E04-E08.md §4.1 `GET /workspace/claims`).
 *
 * Status only: the mapper keeps claim reference, employee, claim type, status, lost-time flag and incident date. Medical,
 * cost (reserves, payments), payer, adjuster, nurse, litigation and case fields are never copied – even if a
 * misconfigured backend returned them.
 */
import { humanise } from '../components/format';
import { list, s } from './fitness';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;

export const CLAIMS_PATH = '/workspace/claims';

export const CLAIM_TYPES = ['WORKERS_COMP', 'EMPLOYER_LIABILITY', 'GROUP_MEDICAL', 'THIRD_PARTY'] as const;
export type ClaimType = (typeof CLAIM_TYPES)[number];
export const CLAIM_TYPE_LABELS: Record<ClaimType, string> = {
  WORKERS_COMP: "Workers' compensation",
  EMPLOYER_LIABILITY: 'Employer liability',
  GROUP_MEDICAL: 'Group medical',
  THIRD_PARTY: 'Third party',
};

export const CLAIM_STATUSES = ['NOTIFIED', 'OPEN', 'UNDER_REVIEW', 'SETTLED', 'LITIGATED', 'CLOSED'] as const;
export type ClaimStatus = (typeof CLAIM_STATUSES)[number];
export const CLAIM_STATUS_LABELS: Record<ClaimStatus, string> = {
  NOTIFIED: 'Notified',
  OPEN: 'Open',
  UNDER_REVIEW: 'Under review',
  SETTLED: 'Settled',
  LITIGATED: 'In litigation',
  CLOSED: 'Closed',
};

const isClaimType = (v: string): v is ClaimType => (CLAIM_TYPES as readonly string[]).includes(v);
const isClaimStatus = (v: string): v is ClaimStatus => (CLAIM_STATUSES as readonly string[]).includes(v);
export const claimTypeLabel = (t: string) => (isClaimType(t) ? CLAIM_TYPE_LABELS[t] : humanise(t));
export const claimStatusLabel = (st: string) => (isClaimStatus(st) ? CLAIM_STATUS_LABELS[st] : humanise(st));

/** The only fields HR sees (§4.1). */
export const CLAIM_ROW_FIELDS = ['claimRef', 'employeeId', 'displayName', 'claimType', 'status', 'lostTime', 'incidentDate'] as const;

export interface ClaimRow {
  claimRef: string;
  employeeId: string;
  displayName: string;
  claimType: string;
  status: string;
  lostTime: boolean;
  incidentDate?: string;
}

/** Not yet settled or closed. */
export const isOpenClaim = (r: Pick<ClaimRow, 'status'>) => r.status !== 'SETTLED' && r.status !== 'CLOSED';

/** Rows without a claim reference or employee are dropped. Open claims first, then the most recent incident. */
export function toClaimRows(body: unknown): ClaimRow[] {
  return list(body)
    .map((raw): ClaimRow | undefined => {
      const r = obj(raw);
      const claimRef = s(r?.claimRef);
      const employeeId = s(r?.employeeId);
      if (!r || !claimRef || !employeeId) return undefined;
      return {
        claimRef,
        employeeId,
        displayName: s(r.displayName) ?? employeeId,
        claimType: (s(r.claimType) ?? 'WORKERS_COMP').toUpperCase(),
        status: (s(r.status) ?? 'NOTIFIED').toUpperCase(),
        lostTime: r.lostTime === true,
        incidentDate: s(r.incidentDate),
      };
    })
    .filter(defined)
    .sort(
      (a, b) =>
        Number(isOpenClaim(b)) - Number(isOpenClaim(a)) ||
        (b.incidentDate ?? '').localeCompare(a.incidentDate ?? '') ||
        a.claimRef.localeCompare(b.claimRef),
    );
}

export interface ClaimFilter {
  q: string;
  status: '' | ClaimStatus;
  claimType: '' | ClaimType;
  lostTimeOnly: boolean;
  openOnly: boolean;
}
export const emptyClaimFilter = (): ClaimFilter => ({ q: '', status: '', claimType: '', lostTimeOnly: false, openOnly: false });

export function filterClaims(rows: readonly ClaimRow[], f: ClaimFilter): ClaimRow[] {
  const q = f.q.trim().toLowerCase();
  return rows.filter((r) => {
    if (q && ![r.displayName, r.employeeId, r.claimRef].some((x) => x.toLowerCase().includes(q))) return false;
    if (f.status && r.status !== f.status) return false;
    if (f.claimType && r.claimType !== f.claimType) return false;
    if (f.lostTimeOnly && !r.lostTime) return false;
    if (f.openOnly && !isOpenClaim(r)) return false;
    return true;
  });
}

export function summariseClaims(rows: readonly ClaimRow[]) {
  return {
    total: rows.length,
    open: rows.filter(isOpenClaim).length,
    lostTime: rows.filter((r) => r.lostTime && isOpenClaim(r)).length,
    closed: rows.filter((r) => !isOpenClaim(r)).length,
  };
}
