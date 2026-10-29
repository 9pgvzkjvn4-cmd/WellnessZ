import { ApiError, type AuthClient } from '../auth/AuthClient';
import { asList, type Advice } from './types';

const str = (v: unknown) => (typeof v === 'string' ? v : undefined);

/** Keep only the advice fields the screen renders; newest first. */
export function normaliseAdvice(body: unknown): Advice[] {
  return asList<unknown>(body)
    .map((raw): Advice | undefined => {
      if (!raw || typeof raw !== 'object') return undefined;
      const r = raw as Record<string, unknown>;
      const id = str(r.id) ?? (typeof r.id === 'number' ? String(r.id) : undefined);
      if (!id) return undefined;
      return { id, caseRef: str(r.caseRef), title: str(r.title), text: str(r.text), sentAt: str(r.sentAt) };
    })
    .filter((a): a is Advice => !!a)
    .sort((a, b) => (b.sentAt ?? '').localeCompare(a.sentAt ?? ''));
}

/** `GET /me/advice`; a 404 (no advice yet / endpoint not deployed) is treated as an empty list. */
export async function fetchMyAdvice(client: Pick<AuthClient, 'api'>): Promise<Advice[]> {
  try {
    return normaliseAdvice(await client.api<unknown>('/me/advice'));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}
