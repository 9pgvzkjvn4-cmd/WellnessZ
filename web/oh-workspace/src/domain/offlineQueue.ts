import type { Encounter, EncounterRequest } from '../api/types';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';

/**
 * In-memory retry queue for walk-in / emergency registrations (E01-BL-011, airside posts are
 * offline-tolerant). PHI is NEVER written to localStorage / sessionStorage / IndexedDB / Cache:
 * pending items live only in this object and are lost when the tab closes (the UI warns about it).
 * Each request carries a client-generated `clientRef` (UUID) so replays are idempotent server-side.
 */

export type OutboxStatus = 'PENDING' | 'SENDING' | 'SENT' | 'FAILED';

export interface OutboxItem {
  clientRef: string;
  request: EncounterRequest;
  status: OutboxStatus;
  attempts: number;
  queuedAt: number;
  encounter?: Encounter;
  /** Non-retryable server rejection (4xx) shown to the user. */
  error?: unknown;
}

export interface OutboxDeps {
  send: (req: EncounterRequest) => Promise<Encounter>;
  isOnline: () => boolean;
  now?: () => number;
}

export type SubmitResult = { kind: 'sent'; encounter: Encounter } | { kind: 'queued'; clientRef: string } | { kind: 'rejected'; error: unknown };

/** Network failures (fetch TypeError), gateway/5xx and 408/429 are retryable; other 4xx are not. */
export function isRetryable(e: unknown): boolean {
  if (e instanceof SessionExpiredError) return false;
  if (e instanceof ApiError) return e.status >= 500 || e.status === 408 || e.status === 429;
  return true; // TypeError: Failed to fetch, AbortError, …
}

export function newClientRef(): string {
  return globalThis.crypto.randomUUID();
}

export class EncounterOutbox {
  readonly #deps: OutboxDeps;
  #items: OutboxItem[] = [];
  #listeners = new Set<() => void>();
  #flushing = false;

  constructor(deps: OutboxDeps) {
    this.#deps = deps;
  }

  /** Snapshot for rendering (new array on every change). */
  get items(): readonly OutboxItem[] {
    return this.#items;
  }

  get pendingCount(): number {
    return this.#items.filter((i) => i.status === 'PENDING' || i.status === 'SENDING').length;
  }

  subscribe(fn: () => void): () => void {
    this.#listeners.add(fn);
    return () => this.#listeners.delete(fn);
  }

  #update(clientRef: string, patch: Partial<OutboxItem>) {
    this.#items = this.#items.map((i) => (i.clientRef === clientRef ? { ...i, ...patch } : i));
    this.#listeners.forEach((l) => l());
  }

  async submit(request: EncounterRequest): Promise<SubmitResult> {
    const item: OutboxItem = { clientRef: request.clientRef, request, status: 'PENDING', attempts: 0, queuedAt: (this.#deps.now ?? Date.now)() };
    this.#items = [...this.#items.filter((i) => i.clientRef !== request.clientRef), item];
    this.#listeners.forEach((l) => l());
    if (!this.#deps.isOnline()) return { kind: 'queued', clientRef: request.clientRef };
    return this.#attempt(item);
  }

  async #attempt(item: OutboxItem): Promise<SubmitResult> {
    this.#update(item.clientRef, { status: 'SENDING', attempts: item.attempts + 1 });
    try {
      const encounter = await this.#deps.send(item.request);
      this.#update(item.clientRef, { status: 'SENT', encounter });
      return { kind: 'sent', encounter };
    } catch (e) {
      if (isRetryable(e)) {
        this.#update(item.clientRef, { status: 'PENDING' });
        return { kind: 'queued', clientRef: item.clientRef };
      }
      this.#update(item.clientRef, { status: 'FAILED', error: e });
      return { kind: 'rejected', error: e };
    }
  }

  /** Retry every pending item (call on `online` and periodically). Stops at the first network failure. */
  async flush(): Promise<number> {
    if (this.#flushing || !this.#deps.isOnline()) return 0;
    this.#flushing = true;
    let sent = 0;
    try {
      for (const item of this.#items.filter((i) => i.status === 'PENDING')) {
        const r = await this.#attempt(this.#items.find((i) => i.clientRef === item.clientRef) ?? item);
        if (r.kind === 'sent') sent++;
        else if (r.kind === 'queued') break;
      }
    } finally {
      this.#flushing = false;
    }
    return sent;
  }

  /** Remove sent/failed items from the list (pending ones stay). */
  clearSettled(): void {
    this.#items = this.#items.filter((i) => i.status === 'PENDING' || i.status === 'SENDING');
    this.#listeners.forEach((l) => l());
  }

  discard(clientRef: string): void {
    this.#items = this.#items.filter((i) => i.clientRef !== clientRef);
    this.#listeners.forEach((l) => l());
  }
}
