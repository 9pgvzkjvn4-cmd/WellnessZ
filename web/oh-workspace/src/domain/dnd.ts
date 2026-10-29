/**
 * Drag-and-drop reassignment for the caseload board (E02-F03 "re-balance via drag-and-drop").
 * HTML5 DnD carries a small JSON payload under a private MIME type; the keyboard alternative
 * ("Move to…" select) calls the same `reassign` handler.
 */

export const CASE_DRAG_MIME = 'application/x-wz-case';

export interface CaseDragPayload {
  caseId: string;
  caseRef: string;
  fromUsername?: string | null;
}

export function encodeDragPayload(p: CaseDragPayload): string {
  return JSON.stringify({ caseId: p.caseId, caseRef: p.caseRef, fromUsername: p.fromUsername ?? null });
}

export function decodeDragPayload(raw: string | undefined | null): CaseDragPayload | undefined {
  if (!raw) return undefined;
  try {
    const v = JSON.parse(raw) as Record<string, unknown>;
    if (typeof v.caseId !== 'string' || !v.caseId || typeof v.caseRef !== 'string') return undefined;
    return { caseId: v.caseId, caseRef: v.caseRef, fromUsername: typeof v.fromUsername === 'string' ? v.fromUsername : null };
  } catch {
    return undefined;
  }
}

export type ReassignOutcome =
  | { kind: 'ignored' }
  | { kind: 'same-owner'; caseRef: string }
  | { kind: 'missing-reason' }
  | { kind: 'reassigned'; caseRef: string; toUsername: string }
  | { kind: 'failed'; caseRef: string; error: unknown };

export type AssignFn = (caseId: string, body: { toUsername: string; reason: string }) => Promise<unknown>;

/** Shared by drop and keyboard "Move to…". */
export async function reassign(payload: CaseDragPayload | undefined, toUsername: string, reason: string, assign: AssignFn): Promise<ReassignOutcome> {
  if (!payload || !toUsername) return { kind: 'ignored' };
  if (payload.fromUsername && payload.fromUsername === toUsername) return { kind: 'same-owner', caseRef: payload.caseRef };
  if (!reason.trim()) return { kind: 'missing-reason' };
  try {
    await assign(payload.caseId, { toUsername, reason: reason.trim() });
    return { kind: 'reassigned', caseRef: payload.caseRef, toUsername };
  } catch (error) {
    return { kind: 'failed', caseRef: payload.caseRef, error };
  }
}

/** Drop handler body: reads the payload from a DataTransfer-like object. */
export function handleCaseDrop(dt: Pick<DataTransfer, 'getData'> | null, toUsername: string, reason: string, assign: AssignFn): Promise<ReassignOutcome> {
  return reassign(decodeDragPayload(dt?.getData(CASE_DRAG_MIME)), toUsername, reason, assign);
}

/** Whether a dragover should be accepted (only our own payload type). */
export function acceptsCaseDrag(types: readonly string[] | DOMStringList | undefined): boolean {
  if (!types) return false;
  return Array.from(types as ArrayLike<string>).includes(CASE_DRAG_MIME);
}
