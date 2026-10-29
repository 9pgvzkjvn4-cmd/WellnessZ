/**
 * Secure message threads (encrypted at rest, screened for clinical content on claims):
 *   telehealth chat  GET / POST /partner/telehealth/sessions/{id}/messages  {text ≤ 2000}
 *   claim messages   GET / POST /claims/{id}/messages                       {text ≤ 2000}
 */
import type { AuthClient } from '../auth/AuthClient';
import { toActionFailure, type ActionResult } from './errors';
import { mapList, str } from './read';

export const MESSAGE_MAX = 2000;

/* ------------------------------------------------------------------ secure messages (telehealth chat and claims) */

export interface Message {
  id: string;
  sender?: string;
  senderRole?: string;
  text: string;
  sentAt: string;
}

export const toMessages = (body: unknown): Message[] =>
  mapList(body, (r) => {
    const id = str(r.id);
    const text = typeof r.text === 'string' ? r.text : undefined;
    const sentAt = str(r.sentAt) ?? str(r.createdAt);
    return id && text !== undefined && sentAt ? { id, sender: str(r.sender) ?? str(r.senderName), senderRole: str(r.senderRole), text, sentAt } : undefined;
  }).sort((a, b) => a.sentAt.localeCompare(b.sentAt));

/** Read and post a message thread at `path` (`/partner/telehealth/sessions/{id}/messages`, `/claims/{id}/messages`). */
export const loadMessages = async (c: Pick<AuthClient, 'api'>, path: string) => toMessages(await c.api<unknown>(path));

export async function postMessage(c: Pick<AuthClient, 'api'>, path: string, text: string, failed: string): Promise<ActionResult> {
  try {
    return { ok: true, body: await c.api(path, { method: 'POST', body: { text } }) };
  } catch (e) {
    return toActionFailure(e, failed, ['text']);
  }
}
