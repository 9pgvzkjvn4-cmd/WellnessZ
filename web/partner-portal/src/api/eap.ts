/**
 * EAP referrals for the Employee Assistance Programme partner – API contract increment 5 §3.2
 * (docs/increment-05/API-Contract-E09-E10-E11-E12.md, PARTNER_PROVIDER, own organisation only, disclosure-logged):
 *   GET  /partner/eap-referrals?status=
 *        → [{id, ref, displayName, contactPhone?, preferredContact, sessionsAuthorised, urgency, status, sentAt}]
 *   POST /partner/eap-referrals/{id}/feedback
 *        {status ACCEPTED|IN_PROGRESS|CLOSED, attendance[{date, attended}], closureReason? COMPLETED|DISENGAGED|REFERRED_ON}
 *
 * Mental-health records are a restricted class: the partner sees the minimal data set above and returns structured
 * feedback only. There are deliberately no free-text fields – counselling content stays between the employee and the
 * counsellor, and free text is where clinical detail would leak into the employer's system.
 */
import type { AuthClient } from '../auth/AuthClient';
import { humanise, isValidDate, localDate } from '../components/format';
import { S } from '../strings';
import { seg, submit, type ActionResult, type FieldErrors } from './errors';
import { mapList, num, str } from './read';

export const EAP_PATH = '/partner/eap-referrals';

export const EAP_STATUSES = ['SENT', 'ACCEPTED', 'IN_PROGRESS', 'CLOSED'] as const;
export const FEEDBACK_STATUSES = ['ACCEPTED', 'IN_PROGRESS', 'CLOSED'] as const;
export type FeedbackStatus = (typeof FEEDBACK_STATUSES)[number];
export const CLOSURE_REASONS = ['COMPLETED', 'DISENGAGED', 'REFERRED_ON'] as const;
export type ClosureReason = (typeof CLOSURE_REASONS)[number];
export const PREFERRED_CONTACTS = ['PHONE', 'APP_CHAT', 'VIDEO'] as const;
/** Upper bound of authorised sessions (contract: 1..12). */
export const MAX_SESSIONS = 12;

export const eapStatusLabel = (s: string) => S.eap.statuses[s] ?? humanise(s);
export const contactLabel = (c: string) => S.eap.contacts[c] ?? humanise(c);

/** The only fields kept from the list response. */
export const EAP_FIELDS = ['id', 'ref', 'displayName', 'contactPhone', 'preferredContact', 'sessionsAuthorised', 'urgency', 'status', 'sentAt'] as const;

export interface EapReferral {
  id: string;
  ref: string;
  displayName: string;
  contactPhone?: string;
  preferredContact?: string;
  sessionsAuthorised: number;
  urgency: string;
  status: string;
  sentAt?: string;
}

/** Whitelisting projection: no case, concern, risk, screening or clinician field is ever copied. */
export function toEapReferrals(body: unknown): EapReferral[] {
  const raw = Array.isArray(body) ? body : body && typeof body === 'object' && Array.isArray((body as { content?: unknown }).content) ? (body as { content: unknown[] }).content : [];
  return mapList(raw, (r) => {
    const id = str(r.id);
    if (!id) return undefined;
    const sessions = num(r.sessionsAuthorised);
    return {
      id,
      ref: str(r.ref) ?? id,
      displayName: str(r.displayName) ?? '',
      contactPhone: str(r.contactPhone),
      preferredContact: str(r.preferredContact)?.toUpperCase(),
      sessionsAuthorised: sessions !== undefined ? Math.max(0, Math.min(MAX_SESSIONS, Math.floor(sessions))) : 0,
      urgency: (str(r.urgency) ?? 'ROUTINE').toUpperCase(),
      status: (str(r.status) ?? 'SENT').toUpperCase(),
      sentAt: str(r.sentAt),
    };
  });
}

/** Open referrals first (priority first), then newest. */
export function sortEap(items: readonly EapReferral[]): EapReferral[] {
  const open = (r: EapReferral) => (r.status === 'CLOSED' ? 1 : 0);
  return [...items].sort((a, b) => open(a) - open(b) || Number(b.urgency === 'PRIORITY') - Number(a.urgency === 'PRIORITY') || (b.sentAt ?? '').localeCompare(a.sentAt ?? ''));
}

/** Statuses the partner may report next: SENT → any, ACCEPTED → IN_PROGRESS / CLOSED, IN_PROGRESS → IN_PROGRESS / CLOSED. */
export function nextStatuses(current: string): FeedbackStatus[] {
  switch (current) {
    case 'SENT':
      return ['ACCEPTED', 'IN_PROGRESS', 'CLOSED'];
    case 'ACCEPTED':
      return ['ACCEPTED', 'IN_PROGRESS', 'CLOSED'];
    case 'IN_PROGRESS':
      return ['IN_PROGRESS', 'CLOSED'];
    default:
      return [];
  }
}
export const canGiveFeedback = (r: EapReferral) => nextStatuses(r.status).length > 0;

export interface AttendanceRow {
  date: string;
  /** '' until answered. */
  attended: '' | 'YES' | 'NO';
}

export interface FeedbackForm {
  status: '' | FeedbackStatus;
  attendance: AttendanceRow[];
  closureReason: '' | ClosureReason;
}

export const emptyFeedback = (r?: EapReferral): FeedbackForm => ({ status: r?.status === 'SENT' ? 'ACCEPTED' : r?.status === 'IN_PROGRESS' || r?.status === 'ACCEPTED' ? 'IN_PROGRESS' : '', attendance: [], closureReason: '' });

export const FEEDBACK_FIELDS = ['status', 'attendance', 'closureReason'] as const;

/** Field-level checks; attendance errors are keyed `attendance.<i>.date` / `attendance.<i>.attended` plus `attendance`. */
export function validateFeedback(f: FeedbackForm, r: EapReferral, now: Date = new Date()): FieldErrors {
  const e: FieldErrors = {};
  const allowed = nextStatuses(r.status);
  if (!f.status) e.status = S.validation.required;
  else if (!allowed.includes(f.status)) e.status = S.eap.statusNotAllowed;
  if (f.status === 'CLOSED' && !f.closureReason) e.closureReason = S.eap.closureRequired;
  const today = localDate(now);
  const sentDay = r.sentAt ? localDate(new Date(r.sentAt)) : undefined;
  const seen = new Set<string>();
  f.attendance.forEach((a, i) => {
    if (!isValidDate(a.date)) e[`attendance.${i}.date`] = S.validation.date;
    else if (a.date > today) e[`attendance.${i}.date`] = S.eap.dateFuture;
    else if (sentDay && a.date < sentDay) e[`attendance.${i}.date`] = S.eap.dateBeforeReferral;
    else if (seen.has(a.date)) e[`attendance.${i}.date`] = S.eap.dateDuplicate;
    seen.add(a.date);
    if (!a.attended) e[`attendance.${i}.attended`] = S.eap.attendedRequired;
  });
  const attendedCount = f.attendance.filter((a) => a.attended === 'YES').length;
  if (r.sessionsAuthorised > 0 && attendedCount > r.sessionsAuthorised) e.attendance = S.eap.tooManySessions(r.sessionsAuthorised);
  if (f.status === 'CLOSED' && f.closureReason === 'COMPLETED' && attendedCount === 0) e.closureReason = S.eap.completedNeedsSession;
  return e;
}

export interface FeedbackRequest {
  status: FeedbackStatus;
  attendance: { date: string; attended: boolean }[];
  closureReason?: ClosureReason;
}

/** Exactly the contract body – structured values only, sorted by date, closure reason only when closing. */
export function buildFeedback(f: FeedbackForm): FeedbackRequest {
  const attendance = f.attendance
    .filter((a) => a.date && a.attended)
    .map((a) => ({ date: a.date, attended: a.attended === 'YES' }))
    .sort((x, y) => x.date.localeCompare(y.date));
  const body: FeedbackRequest = { status: f.status as FeedbackStatus, attendance };
  if (f.status === 'CLOSED' && f.closureReason) body.closureReason = f.closureReason;
  return body;
}

export async function fetchEapReferrals(client: Pick<AuthClient, 'api'>, status = ''): Promise<EapReferral[]> {
  return sortEap(toEapReferrals(await client.api<unknown>(`${EAP_PATH}${status ? `?status=${encodeURIComponent(status)}` : ''}`)));
}

export function sendFeedback(client: Pick<AuthClient, 'api'>, id: string, f: FeedbackForm): Promise<ActionResult> {
  return submit(client, `${EAP_PATH}/${seg(id)}/feedback`, buildFeedback(f), S.eap.failed, FEEDBACK_FIELDS);
}
