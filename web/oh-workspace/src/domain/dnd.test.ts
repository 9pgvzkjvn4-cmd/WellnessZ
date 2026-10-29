import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import { acceptsCaseDrag, CASE_DRAG_MIME, decodeDragPayload, encodeDragPayload, handleCaseDrop, reassign } from './dnd';

const dt = (data: Record<string, string>) => ({ getData: (t: string) => data[t] ?? '' });
const payload = { caseId: 'c-1', caseRef: 'CAT-2026-000042', fromUsername: 'nurse1' };

describe('drag-and-drop reassign handler', () => {
  it('round-trips the payload', () => {
    expect(decodeDragPayload(encodeDragPayload(payload))).toEqual(payload);
    expect(decodeDragPayload(encodeDragPayload({ caseId: 'c', caseRef: 'R' }))).toEqual({ caseId: 'c', caseRef: 'R', fromUsername: null });
  });

  it('rejects foreign or malformed payloads', () => {
    expect(decodeDragPayload('')).toBeUndefined();
    expect(decodeDragPayload('not json')).toBeUndefined();
    expect(decodeDragPayload('{"caseRef":"x"}')).toBeUndefined();
  });

  it('drop calls POST assign with target user and reason', async () => {
    const assign = vi.fn(async () => undefined);
    const o = await handleCaseDrop(dt({ [CASE_DRAG_MIME]: encodeDragPayload(payload) }), 'nurse2', '  Caseload rebalancing ', assign);
    expect(o).toEqual({ kind: 'reassigned', caseRef: 'CAT-2026-000042', toUsername: 'nurse2' });
    expect(assign).toHaveBeenCalledWith('c-1', { toUsername: 'nurse2', reason: 'Caseload rebalancing' });
  });

  it('ignores drops without our MIME type (e.g. dragged text or files)', async () => {
    const assign = vi.fn();
    expect(await handleCaseDrop(dt({ 'text/plain': 'hello' }), 'nurse2', 'r', assign)).toEqual({ kind: 'ignored' });
    expect(await handleCaseDrop(null, 'nurse2', 'r', assign)).toEqual({ kind: 'ignored' });
    expect(assign).not.toHaveBeenCalled();
  });

  it('does nothing when dropped on the current owner', async () => {
    const assign = vi.fn();
    expect(await handleCaseDrop(dt({ [CASE_DRAG_MIME]: encodeDragPayload(payload) }), 'nurse1', 'r', assign)).toEqual({ kind: 'same-owner', caseRef: 'CAT-2026-000042' });
    expect(assign).not.toHaveBeenCalled();
  });

  it('requires a reason', async () => {
    const assign = vi.fn();
    expect(await reassign(payload, 'nurse2', '   ', assign)).toEqual({ kind: 'missing-reason' });
    expect(assign).not.toHaveBeenCalled();
  });

  it('reports server rejections (e.g. 403 / 409)', async () => {
    const err = new ApiError(409, { detail: 'Case closed' });
    const o = await reassign(payload, 'nurse2', 'r', async () => Promise.reject(err));
    expect(o).toEqual({ kind: 'failed', caseRef: 'CAT-2026-000042', error: err });
  });

  it('keyboard "Move to…" uses the same handler (empty selection ignored)', async () => {
    const assign = vi.fn(async () => undefined);
    expect(await reassign(payload, '', 'r', assign)).toEqual({ kind: 'ignored' });
    expect((await reassign({ ...payload, fromUsername: null }, 'nurse1', 'r', assign)).kind).toBe('reassigned');
  });

  it('accepts dragover only for case payloads', () => {
    expect(acceptsCaseDrag([CASE_DRAG_MIME, 'text/plain'])).toBe(true);
    expect(acceptsCaseDrag(['Files'])).toBe(false);
    expect(acceptsCaseDrag(undefined)).toBe(false);
  });
});
