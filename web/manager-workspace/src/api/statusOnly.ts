/**
 * Status-only projections. Managers/HR must never see clinical information.
 * The core API already returns a status-only projection for these roles; as
 * defence in depth the UI copies ONLY the whitelisted fields below, so any
 * unexpected clinical field in a response is dropped before rendering.
 */
/** Case status values (backend enum). */
export const CASE_STATUSES = ['INTAKE', 'TRIAGE', 'ASSESSMENT', 'FITNESS_REVIEW', 'TREATMENT', 'CLAIMS', 'MONITORING', 'CLOSED'] as const;
/** Fitness-for-work values (backend enum). */
export const FITNESS_STATUSES = ['FIT', 'FIT_WITH_RESTRICTIONS', 'TEMPORARILY_UNFIT', 'PERMANENTLY_UNFIT', 'UNDER_REVIEW', 'NOT_ASSESSED'] as const;

export interface TeamCaseRow {
  id: string;
  caseRef: string;
  employeeDisplayName: string;
  status: string;
  fitnessStatus?: string;
  restrictionUntil?: string | null;
  updatedAt?: string;
  disclosure?: string;
}

export interface CaseStatusDetail {
  caseRef: string;
  employeeDisplayName?: string;
  status: string;
  fitnessStatus?: string;
  restrictionUntil?: string | null;
  updatedAt?: string;
  disclosure?: string;
  history: { status: string; at?: string }[];
}

const s = (v: unknown): string | undefined => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : undefined);

export function toTeamCaseRow(raw: unknown): TeamCaseRow | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const caseRef = s(r.caseRef);
  const id = s(r.id) ?? caseRef;
  const status = s(r.status);
  if (!id || !status) return undefined;
  return {
    id,
    caseRef: caseRef ?? id,
    employeeDisplayName: s(r.employeeDisplayName) ?? '–',
    status,
    fitnessStatus: s(r.fitnessStatus),
    restrictionUntil: s(r.restrictionUntil) ?? null,
    updatedAt: s(r.updatedAt),
    disclosure: s(r.disclosure),
  };
}

export function toTeamCaseRows(body: unknown): TeamCaseRow[] {
  const list = Array.isArray(body)
    ? body
    : body && typeof body === 'object' && Array.isArray((body as { content?: unknown }).content)
      ? (body as { content: unknown[] }).content
      : [];
  return list.map(toTeamCaseRow).filter((r): r is TeamCaseRow => !!r);
}

export function toCaseStatusDetail(raw: unknown): CaseStatusDetail | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const status = s(r.status);
  if (!status) return undefined;
  const history = Array.isArray(r.history)
    ? r.history
        .map((h) => {
          const e = (h ?? {}) as Record<string, unknown>;
          const st = s(e.status);
          return st ? { status: st, at: s(e.at) ?? s(e.changedAt) ?? s(e.timestamp) } : undefined;
        })
        .filter((h): h is { status: string; at: string | undefined } => !!h)
    : [];
  return {
    caseRef: s(r.caseRef) ?? s(r.id) ?? '–',
    employeeDisplayName: s(r.employeeDisplayName),
    status,
    fitnessStatus: s(r.fitnessStatus),
    restrictionUntil: s(r.restrictionUntil) ?? null,
    updatedAt: s(r.updatedAt),
    disclosure: s(r.disclosure),
    history,
  };
}
