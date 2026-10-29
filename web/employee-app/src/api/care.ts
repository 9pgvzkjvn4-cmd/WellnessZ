import { ApiError, type AuthClient } from '../auth/AuthClient';
import { safeHttpUrl } from './occupational';
import { asList } from './types';

/*
 * Increment 4 employee portal (docs/increment-04/API-Contract-E04-E08.md):
 * E04 treatment plan & follow-ups (§3.1 `/me/treatment-plans`), partner appointments from e-referrals (§3.2,
 * `referral.appointment`) and telehealth consultations (§3.7 `/me/telehealth`).
 * Every response is projected onto the fields the screens render. OH-internal fields (variance, guideline, responsible
 * clinician, encounter id, case id, employee id, clinical notes …) are dropped and never kept in UI state.
 */

type Client = Pick<AuthClient, 'api'>;
type Rec = Record<string, unknown>;

const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v : typeof v === 'number' ? String(v) : undefined);
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() && Number.isFinite(Number(v)) ? Number(v) : undefined);
const bool = (v: unknown) => v === true || v === 'true';
const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
const upper = (v: unknown) => str(v)?.toUpperCase();
const enc = encodeURIComponent;
const MIN = 60_000;

async function list(client: Client, path: string): Promise<unknown[]> {
  try {
    return asList<unknown>(await client.api<unknown>(path));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

/** Max length of a decline reason (the server screens it for clinical content). */
export const MAX_REASON = 500;
/** Secure chat message limit (§3.7). */
export const MAX_CHAT = 2000;

/* ====================================================================== treatment plan (E04 §3.1, §3.2) */

export const PLAN_STATUSES = ['DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED'] as const;
export const INTERVENTION_TYPES = ['PHYSIOTHERAPY', 'MEDICATION', 'SPECIALIST_REVIEW', 'IMAGING', 'SELF_CARE', 'PSYCHOLOGICAL_THERAPY', 'OH_REVIEW', 'OTHER'] as const;
export const FOLLOW_UP_TYPES = ['OH_REVIEW', 'PHONE', 'PHYSIOTHERAPY', 'SPECIALIST', 'TELEHEALTH'] as const;
export const FOLLOW_UP_STATUSES = ['PLANNED', 'BOOKED', 'CONFIRMED', 'DECLINED', 'DONE', 'MISSED', 'CANCELLED'] as const;
export type FollowUpResponse = 'CONFIRM' | 'DECLINE';

export interface PlanIntervention {
  type: string;
  description?: string;
  frequency?: string;
  startDate?: string;
  endDate?: string;
  provider?: string;
}

export interface PlanAppointment {
  startsAt: string;
  endsAt?: string;
  siteCode?: string;
  status?: string;
}

export interface PlanFollowUp {
  id: string;
  dueDate?: string;
  type: string;
  status: string;
  appointment?: PlanAppointment;
  /** CONFIRM / DECLINE once the employee answered. */
  response?: FollowUpResponse;
}

export interface ExternalAppointment {
  key: string;
  ref?: string;
  service?: string;
  provider?: string;
  appointmentAt: string;
  location?: string;
  instructions?: string;
  status?: string;
}

export interface MyTreatmentPlan {
  id: string;
  caseRef?: string;
  status: string;
  goals: string[];
  interventions: PlanIntervention[];
  followUps: PlanFollowUp[];
  externalAppointments: ExternalAppointment[];
  expectedRecoveryDate?: string;
}

const textOf = (v: unknown) => str(v) ?? (rec(v) ? str(rec(v)!.text) ?? str(rec(v)!.description) ?? str(rec(v)!.title) : undefined);

function toResponse(v: unknown): FollowUpResponse | undefined {
  const r = upper(v) ?? upper(rec(v)?.response);
  if (r === 'CONFIRM' || r === 'CONFIRMED') return 'CONFIRM';
  if (r === 'DECLINE' || r === 'DECLINED') return 'DECLINE';
  return undefined;
}

export function toIntervention(raw: unknown): PlanIntervention | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  const type = upper(r.type) ?? 'OTHER';
  return {
    type,
    description: str(r.description),
    frequency: str(r.frequency),
    startDate: str(r.startDate),
    endDate: str(r.endDate),
    provider: str(r.providerName) ?? str(r.providerCode),
  };
}

function toPlanAppointment(raw: unknown, fallbackAt?: unknown): PlanAppointment | undefined {
  const a = rec(raw);
  const startsAt = str(a?.startsAt) ?? str(a?.appointmentAt) ?? str(fallbackAt);
  if (!startsAt) return undefined;
  return { startsAt, endsAt: str(a?.endsAt), siteCode: str(a?.siteCode), status: upper(a?.status) };
}

export function toFollowUp(raw: unknown): PlanFollowUp | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  return {
    id,
    dueDate: str(r.dueDate),
    type: upper(r.type) ?? 'OH_REVIEW',
    status: upper(r.status) ?? 'PLANNED',
    appointment: toPlanAppointment(r.appointment, r.appointmentAt),
    response: toResponse(r.employeeResponse),
  };
}

/** External (partner) appointment – from `externalAppointments[]` or a scheduled referral (`appointmentAt`). */
export function toExternalAppointment(raw: unknown, index = 0): ExternalAppointment | undefined {
  const r = rec(raw);
  const appointmentAt = r && (str(r.appointmentAt) ?? str(r.startsAt));
  if (!r || !appointmentAt) return undefined;
  const ref = str(r.ref) ?? str(r.referralRef);
  return {
    key: str(r.id) ?? str(r.referralId) ?? ref ?? `${appointmentAt}-${index}`,
    ref,
    service: str(r.serviceName) ?? str(r.service),
    provider: str(r.providerName) ?? str(r.provider) ?? str(r.providerCode),
    appointmentAt,
    location: str(r.location),
    instructions: str(r.instructions),
    status: upper(r.status),
  };
}

export function toTreatmentPlan(raw: unknown): MyTreatmentPlan | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  const ext = [...asList<unknown>(r.externalAppointments), ...asList<unknown>(r.appointments), ...asList<unknown>(r.referrals)];
  return {
    id,
    caseRef: str(r.caseRef),
    status: upper(r.status) ?? 'ACTIVE',
    goals: asList<unknown>(r.goals).map(textOf).filter((g): g is string => !!g),
    interventions: asList<unknown>(r.interventions).map(toIntervention).filter((i): i is PlanIntervention => !!i),
    followUps: asList<unknown>(r.followUps)
      .map(toFollowUp)
      .filter((f): f is PlanFollowUp => !!f)
      .sort((a, b) => (a.dueDate ?? '9').localeCompare(b.dueDate ?? '9')),
    externalAppointments: ext
      .map((x, i) => toExternalAppointment(x, i))
      .filter((x): x is ExternalAppointment => !!x)
      .sort((a, b) => a.appointmentAt.localeCompare(b.appointmentAt)),
    expectedRecoveryDate: str(r.expectedRecoveryDate),
  };
}

/** `/me/treatment-plans` is a plain array (a single object or `{plans|items}` is tolerated). Drafts are not shown. */
export function toTreatmentPlans(body: unknown): MyTreatmentPlan[] {
  const o = rec(body);
  const wrapped = o && [o.plans, o.items, o.content].find(Array.isArray);
  const raw: unknown[] = Array.isArray(body) ? body : wrapped ? (wrapped as unknown[]) : o ? [o] : [];
  const order: Record<string, number> = { ACTIVE: 0, COMPLETED: 1, CANCELLED: 2 };
  return raw
    .map(toTreatmentPlan)
    .filter((p): p is MyTreatmentPlan => !!p && p.status !== 'DRAFT')
    .sort((a, b) => (order[a.status] ?? 3) - (order[b.status] ?? 3));
}

/** A follow-up the employee can still confirm or decline: planned / booked, not answered, plan active. */
export function canRespond(f: PlanFollowUp, plan?: Pick<MyTreatmentPlan, 'status'>): boolean {
  if (plan && plan.status !== 'ACTIVE') return false;
  return (f.status === 'PLANNED' || f.status === 'BOOKED') && !f.response;
}

export function followUpResponseBody(response: FollowUpResponse, reason?: string): { response: FollowUpResponse; reason?: string } {
  const r = response === 'DECLINE' ? reason?.trim().slice(0, MAX_REASON) : undefined;
  return r ? { response, reason: r } : { response };
}

export async function fetchMyTreatmentPlans(client: Client): Promise<MyTreatmentPlan[]> {
  return toTreatmentPlans(await list(client, '/me/treatment-plans'));
}

export async function respondToFollowUp(client: Client, planId: string, followUpId: string, response: FollowUpResponse, reason?: string): Promise<void> {
  await client.api(`/me/treatment-plans/${enc(planId)}/follow-ups/${enc(followUpId)}/response`, { method: 'POST', body: followUpResponseBody(response, reason) });
}

/* ====================================================================== telehealth (E04 §3.7) */

export const TELEHEALTH_STATUSES = ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW'] as const;
/** Participant tokens are valid from 15 minutes before the start until the end (§3.7). */
export const JOIN_MINUTES_BEFORE = 15;

export interface MySession {
  id: string;
  ref?: string;
  scheduledAt: string;
  durationMinutes: number;
  mode: 'VIDEO' | 'CHAT' | string;
  status: string;
  clinician?: string;
  partner?: string;
  crewLayover: boolean;
  layoverCity?: string;
  consentGiven: boolean;
}

export function toSession(raw: unknown): MySession | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const scheduledAt = r && str(r.scheduledAt);
  if (!r || !id || !scheduledAt) return undefined;
  // employeeId, displayName, caseId and encounterId are never kept.
  return {
    id,
    ref: str(r.ref),
    scheduledAt,
    durationMinutes: num(r.durationMinutes) ?? 30,
    mode: upper(r.mode) ?? 'VIDEO',
    status: upper(r.status) ?? 'SCHEDULED',
    clinician: str(r.clinicianName) ?? str(r.clinicianUsername),
    partner: str(r.providerName) ?? str(r.providerCode),
    crewLayover: bool(r.crewLayover),
    layoverCity: str(r.layoverCity),
    consentGiven: bool(r.consentGiven),
  };
}

export const isOpenSession = (s: MySession) => s.status === 'SCHEDULED' || s.status === 'IN_PROGRESS';
export const sessionEnd = (s: MySession) => Date.parse(s.scheduledAt) + s.durationMinutes * MIN;

export type SessionWindow = 'before' | 'open' | 'ended';

/** Where `now` is relative to the join window: 15 minutes before the start until the end. */
export function sessionWindow(s: MySession, now: Date = new Date()): SessionWindow {
  const t = now.getTime();
  const start = Date.parse(s.scheduledAt);
  if (t < start - JOIN_MINUTES_BEFORE * MIN) return 'before';
  if (t > sessionEnd(s)) return 'ended';
  return 'open';
}

/** The join button is enabled only for a consented, open session inside its window. */
export function canJoinSession(s: MySession, now: Date = new Date()): boolean {
  return isOpenSession(s) && s.consentGiven && sessionWindow(s, now) === 'open';
}

/** Consent is asked for open sessions that have not ended. */
export function needsConsent(s: MySession, now: Date = new Date()): boolean {
  return isOpenSession(s) && !s.consentGiven && sessionWindow(s, now) !== 'ended';
}

/** Chat history is readable once consented; sending only while the session is open. */
export const canChat = (s: MySession) => s.consentGiven && s.status !== 'CANCELLED';
export const canSendChat = (s: MySession) => s.consentGiven && isOpenSession(s);

/** Upcoming (soonest first), then past (most recent first). */
export function sortSessions(items: MySession[], now: Date = new Date()): MySession[] {
  const upcoming = items.filter((s) => isOpenSession(s) && sessionEnd(s) >= now.getTime()).sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt));
  const past = items.filter((s) => !upcoming.includes(s)).sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt));
  return [...upcoming, ...past];
}

export interface JoinLink {
  url: string;
  expiresAt?: string;
}

/** Only http(s) links are accepted; anything else is dropped. */
export function toJoinLink(raw: unknown): JoinLink | undefined {
  const r = rec(raw);
  const url = safeHttpUrl(r ? str(r.joinUrl) : undefined);
  return url ? { url, expiresAt: str(r!.expiresAt) } : undefined;
}

/** A join link is offered only while the session window is open and the link has not expired. */
export function usableLink(s: MySession, link: JoinLink | undefined, now: Date = new Date()): JoinLink | undefined {
  if (!link || !canJoinSession(s, now)) return undefined;
  if (link.expiresAt && Date.parse(link.expiresAt) < now.getTime()) return undefined;
  return link;
}

export interface ChatMessage {
  id: string;
  mine: boolean;
  sender?: string;
  senderRole?: string;
  text: string;
  sentAt?: string;
}

export function toChatMessage(raw: unknown): ChatMessage | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const text = r && str(r.text);
  if (!r || !id || !text) return undefined;
  const role = upper(r.senderRole);
  return { id, mine: role === 'EMPLOYEE', sender: str(r.senderName) ?? str(r.sender), senderRole: role, text, sentAt: str(r.sentAt) };
}

export function toChat(body: unknown): ChatMessage[] {
  return asList<unknown>(body)
    .map(toChatMessage)
    .filter((m): m is ChatMessage => !!m)
    .sort((a, b) => (a.sentAt ?? '').localeCompare(b.sentAt ?? ''));
}

export async function fetchMySessions(client: Client): Promise<MySession[]> {
  return (await list(client, '/me/telehealth')).map(toSession).filter((s): s is MySession => !!s);
}
export async function giveSessionConsent(client: Client, id: string): Promise<void> {
  await client.api(`/me/telehealth/${enc(id)}/consent`, { method: 'POST', body: {} });
}
export async function joinSession(client: Client, id: string): Promise<JoinLink | undefined> {
  return toJoinLink(await client.api<unknown>(`/me/telehealth/${enc(id)}/join`, { method: 'POST', body: {} }));
}
export async function fetchChat(client: Client, id: string): Promise<ChatMessage[]> {
  return toChat(await list(client, `/me/telehealth/${enc(id)}/messages`));
}
export async function sendChat(client: Client, id: string, text: string): Promise<void> {
  const t = text.trim().slice(0, MAX_CHAT);
  if (!t) return;
  await client.api(`/me/telehealth/${enc(id)}/messages`, { method: 'POST', body: { text: t } });
}

/* ====================================================================== home counts */

export interface CareCounts {
  treatment: number;
  telehealth: number;
}

/** Follow-ups awaiting an answer; open sessions that need consent or can be joined now. Failures just hide a count. */
export async function loadCareCounts(client: Client, now: Date = new Date()): Promise<Partial<CareCounts>> {
  const [plans, sessions] = await Promise.allSettled([fetchMyTreatmentPlans(client), fetchMySessions(client)]);
  const counts: Partial<CareCounts> = {};
  if (plans.status === 'fulfilled') counts.treatment = plans.value.reduce((n, p) => n + p.followUps.filter((f) => canRespond(f, p)).length, 0);
  if (sessions.status === 'fulfilled') counts.telehealth = sessions.value.filter((s) => needsConsent(s, now) || canJoinSession(s, now)).length;
  return counts;
}
