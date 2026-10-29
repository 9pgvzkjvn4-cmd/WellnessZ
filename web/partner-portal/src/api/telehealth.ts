/**
 * Partner telehealth – contract §3.7:
 *   GET  /partner/telehealth/sessions                    own organisation's sessions
 *   POST /partner/telehealth/sessions/{id}/join          → {joinUrl, expiresAt} (single-use, from 15 min before start to the end)
 *   GET / POST /partner/telehealth/sessions/{id}/messages  secure chat {text ≤ 2000} → [{id, sender, senderRole, text, sentAt}]
 */
import type { AuthClient } from '../auth/AuthClient';
import { bool, mapList, num, rec, str, type Rec } from './read';
import { seg, submit, type ActionResult } from './errors';

export const JOIN_LEAD_MINUTES = 15;

export interface TelehealthSession {
  id: string;
  ref: string;
  displayName: string;
  scheduledAt: string;
  durationMinutes: number;
  mode: string;
  status: string;
  crewLayover: boolean;
  layoverCity?: string;
  consentGiven: boolean;
}

function toSession(r: Rec): TelehealthSession | undefined {
  const id = str(r.id);
  const scheduledAt = str(r.scheduledAt);
  if (!id || !scheduledAt) return undefined;
  return {
    id,
    ref: str(r.ref) ?? id,
    displayName: str(r.displayName) ?? '',
    scheduledAt,
    durationMinutes: num(r.durationMinutes) ?? 30,
    mode: str(r.mode) ?? 'VIDEO',
    status: str(r.status) ?? 'SCHEDULED',
    crewLayover: bool(r.crewLayover),
    layoverCity: str(r.layoverCity),
    consentGiven: bool(r.consentGiven),
  };
}

/** Sessions, soonest first (whitelisted fields only – no encounter / case ids). */
export const toTelehealthSessions = (body: unknown): TelehealthSession[] =>
  mapList(body, toSession).sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt));

/** Whether the session can be joined now (mirrors the server's token window; the server stays authoritative). */
export function joinState(s: TelehealthSession, now: Date = new Date()): 'open' | 'early' | 'ended' | 'closed' | 'no-consent' {
  if (s.status !== 'SCHEDULED' && s.status !== 'IN_PROGRESS') return s.status === 'COMPLETED' ? 'ended' : 'closed';
  const start = new Date(s.scheduledAt).getTime();
  if (Number.isNaN(start)) return 'closed';
  const t = now.getTime();
  if (t > start + s.durationMinutes * 60_000) return 'ended';
  if (t < start - JOIN_LEAD_MINUTES * 60_000) return 'early';
  if (!s.consentGiven) return 'no-consent';
  return 'open';
}

/** Only absolute http(s) links are opened (never `javascript:`, `data:` or relative URLs). */
export function safeJoinUrl(url: unknown): string | undefined {
  if (typeof url !== 'string') return undefined;
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : undefined;
  } catch {
    return undefined;
  }
}

export interface JoinResult {
  joinUrl?: string;
  expiresAt?: string;
}

export async function joinSession(c: Pick<AuthClient, 'api'>, id: string, failed: string): Promise<ActionResult<JoinResult>> {
  const res = await submit<unknown>(c, `/partner/telehealth/sessions/${seg(id)}/join`, undefined, failed);
  if (!res.ok) return res;
  const r = rec(res.body) ?? {};
  return { ok: true, body: { joinUrl: safeJoinUrl(r.joinUrl), expiresAt: str(r.expiresAt) } };
}

export const telehealthMessagesPath = (id: string) => `/partner/telehealth/sessions/${seg(id)}/messages`;

/**
 * Opens a vetted join link in a new tab without giving the room a handle on this window (`noopener`, no referrer).
 * Browsers return null for `noopener` windows, so the screen always offers the link as a fallback (popup blockers).
 */
export function openInNewTab(url: string, open: (u: string, target: string, features: string) => unknown = (u, t, f) => window.open(u, t, f)): void {
  const safe = safeJoinUrl(url);
  if (safe) open(safe, '_blank', 'noopener,noreferrer');
}
