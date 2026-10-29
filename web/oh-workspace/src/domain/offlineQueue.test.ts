import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Encounter, EncounterRequest } from '../api/types';
import { ApiError } from '../auth/AuthClient';
import { EncounterOutbox, isRetryable, newClientRef } from './offlineQueue';

const req = (clientRef: string, extra: Partial<EncounterRequest> = {}): EncounterRequest => ({
  clientRef,
  mode: 'EMERGENCY',
  employeeId: 'E1001',
  occurredAt: '2026-10-29T10:00:00Z',
  siteCode: 'LHR-FA1',
  location: 'Stand 12',
  chiefComplaint: 'Hand laceration',
  actionTaken: 'Pressure dressing',
  ...extra,
});
const enc = (r: EncounterRequest): Encounter => ({ ...r, id: `id-${r.clientRef}`, status: 'OPEN' });

/** Spy storage objects: the outbox must never touch them (PHI must not be persisted). */
function storageSpy() {
  return { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn(), clear: vi.fn(), key: vi.fn(), length: 0 };
}

describe('EncounterOutbox (in-memory offline retry queue)', () => {
  const g = globalThis as Record<string, unknown>;
  let local: ReturnType<typeof storageSpy>;
  let session: ReturnType<typeof storageSpy>;
  let idb: { open: ReturnType<typeof vi.fn> };
  let caches: { open: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    local = storageSpy();
    session = storageSpy();
    idb = { open: vi.fn() };
    caches = { open: vi.fn() };
    g.localStorage = local;
    g.sessionStorage = session;
    g.indexedDB = idb;
    g.caches = caches;
  });
  afterEach(() => {
    delete g.localStorage;
    delete g.sessionStorage;
    delete g.indexedDB;
    delete g.caches;
  });

  function expectNoStorage() {
    for (const s of [local, session]) for (const fn of [s.getItem, s.setItem, s.removeItem, s.clear, s.key]) expect(fn).not.toHaveBeenCalled();
    expect(idb.open).not.toHaveBeenCalled();
    expect(caches.open).not.toHaveBeenCalled();
  }

  it('sends immediately when online', async () => {
    const send = vi.fn(async (r: EncounterRequest) => enc(r));
    const box = new EncounterOutbox({ send, isOnline: () => true });
    const r = await box.submit(req('a'));
    expect(r).toEqual({ kind: 'sent', encounter: expect.objectContaining({ id: 'id-a' }) });
    expect(box.items[0]?.status).toBe('SENT');
    expect(box.pendingCount).toBe(0);
    expectNoStorage();
  });

  it('queues without calling the API while navigator is offline, then flushes with the same clientRef', async () => {
    let online = false;
    const send = vi.fn(async (r: EncounterRequest) => enc(r));
    const box = new EncounterOutbox({ send, isOnline: () => online });
    expect(await box.submit(req('b'))).toEqual({ kind: 'queued', clientRef: 'b' });
    expect(send).not.toHaveBeenCalled();
    expect(box.pendingCount).toBe(1);
    expect(await box.flush()).toBe(0); // still offline → no attempt
    online = true;
    expect(await box.flush()).toBe(1);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[0].clientRef).toBe('b');
    expect(box.items[0]?.status).toBe('SENT');
    expectNoStorage();
  });

  it('queues on fetch failure (TypeError) and retries idempotently', async () => {
    const send = vi
      .fn<(r: EncounterRequest) => Promise<Encounter>>()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockRejectedValueOnce(new ApiError(503))
      .mockImplementation(async (r) => enc(r));
    const box = new EncounterOutbox({ send, isOnline: () => true });
    expect((await box.submit(req('c'))).kind).toBe('queued');
    expect(await box.flush()).toBe(0); // 503 → still pending
    expect(box.items[0]).toMatchObject({ status: 'PENDING', attempts: 2 });
    expect(await box.flush()).toBe(1);
    expect(send.mock.calls.map((c) => c[0].clientRef)).toEqual(['c', 'c', 'c']);
    expectNoStorage();
  });

  it('does not retry validation errors (4xx) and marks them FAILED', async () => {
    const box = new EncounterOutbox({ send: vi.fn(async () => Promise.reject(new ApiError(422, { detail: 'Unknown employee' }))), isOnline: () => true });
    const r = await box.submit(req('d'));
    expect(r.kind).toBe('rejected');
    expect(box.items[0]?.status).toBe('FAILED');
    expect(await box.flush()).toBe(0);
    box.discard('d');
    expect(box.items).toHaveLength(0);
  });

  it('flushes in order and stops at the first network failure', async () => {
    let online = false;
    const sent: string[] = [];
    let failNext = false;
    const box = new EncounterOutbox({
      send: async (r) => {
        if (failNext) throw new TypeError('offline again');
        sent.push(r.clientRef);
        if (r.clientRef === 'e1') failNext = true;
        return enc(r);
      },
      isOnline: () => online,
    });
    await box.submit(req('e1'));
    await box.submit(req('e2'));
    await box.submit(req('e3'));
    online = true;
    expect(await box.flush()).toBe(1);
    expect(sent).toEqual(['e1']);
    expect(box.pendingCount).toBe(2);
  });

  it('notifies subscribers and clears settled items', async () => {
    const box = new EncounterOutbox({ send: async (r) => enc(r), isOnline: () => true });
    const listener = vi.fn();
    const off = box.subscribe(listener);
    await box.submit(req('f'));
    expect(listener).toHaveBeenCalled();
    box.clearSettled();
    expect(box.items).toHaveLength(0);
    off();
  });

  it('classifies retryable errors', () => {
    expect(isRetryable(new TypeError('x'))).toBe(true);
    expect(isRetryable(new ApiError(502))).toBe(true);
    expect(isRetryable(new ApiError(429))).toBe(true);
    expect(isRetryable(new ApiError(400))).toBe(false);
    expect(isRetryable(new ApiError(409))).toBe(false);
  });

  it('generates RFC 4122 v4 client references', () => {
    expect(newClientRef()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});
