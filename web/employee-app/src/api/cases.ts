import { asList, type MyCase } from './types';

const str = (v: unknown) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : undefined);

/**
 * Project a case from `GET /cases` onto the fields the home screen shows. Anything else in the response
 * (e.g. a description on the employee's own clinical view) is dropped and never kept in UI state.
 */
export function toMyCase(raw: unknown): MyCase | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const id = str(r.id) ?? str(r.caseId);
  const status = str(r.status);
  if (!id || !status) return undefined;
  return {
    id,
    caseRef: str(r.caseRef),
    category: str(r.category),
    status,
    fitnessStatus: str(r.fitnessStatus) ?? null,
    restrictionUntil: str(r.restrictionUntil) ?? null,
    createdAt: str(r.createdAt),
    updatedAt: str(r.updatedAt),
  };
}

/** Newest activity first. */
export function toMyCases(body: unknown): MyCase[] {
  return asList<unknown>(body)
    .map(toMyCase)
    .filter((c): c is MyCase => !!c)
    .sort((a, b) => (b.updatedAt ?? b.createdAt ?? '').localeCompare(a.updatedAt ?? a.createdAt ?? ''));
}
