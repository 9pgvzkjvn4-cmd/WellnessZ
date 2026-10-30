import { ApiError, type AuthClient } from '../auth/AuthClient';
import { safeHttpUrl } from './occupational';
import { asList } from './types';

/*
 * Increment 5 – E09 mental health & peer support for the employee (docs/increment-05/API-Contract-E09-E10-E11-E12.md §3):
 * signposting + emergency guidance (§3.2 `/me/support/signposting`), confidential self-referral, PHQ-9 / GAD-7 screening,
 * the per-case sharing choice (§3.1 `/me/support/sharing`), CISM offers (§3.4) and the anonymous peer-support request (§3.3).
 * Every response is projected onto the fields the screens render; anything else (case ids of other systems, employee id,
 * clinician names, risk, notes …) is dropped and never kept in UI state.
 */

type Client = Pick<AuthClient, 'api'>;
type Rec = Record<string, unknown>;

const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : typeof v === 'number' ? String(v) : undefined);
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() && Number.isFinite(Number(v)) ? Number(v) : undefined);
const bool = (v: unknown) => v === true || v === 'true';
const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
const upper = (v: unknown) => str(v)?.toUpperCase();
const strings = (v: unknown) => asList<unknown>(v).map((x) => str(x) ?? str(rec(x)?.text) ?? str(rec(x)?.label)).filter((x): x is string => !!x);
const enc = encodeURIComponent;

async function list(client: Client, path: string): Promise<unknown> {
  try {
    return await client.api<unknown>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

/* ====================================================================== enums (§3) */

export const CONCERNS = ['WORK_STRESS', 'LOW_MOOD', 'ANXIETY', 'SLEEP', 'BEREAVEMENT', 'TRAUMA', 'SUBSTANCE', 'RELATIONSHIP', 'OTHER'] as const;
export type Concern = (typeof CONCERNS)[number];
export const SUPPORT_CONTACTS = ['PHONE', 'APP_CHAT', 'VIDEO'] as const;
export type SupportContact = (typeof SUPPORT_CONTACTS)[number];
export const BEST_TIMES = ['MORNING', 'AFTERNOON', 'EVENING', 'ANY'] as const;
export const SHARING_CHOICES = ['NOTHING', 'FITNESS_ONLY', 'FITNESS_AND_ADJUSTMENTS'] as const;
export type SharingChoice = (typeof SHARING_CHOICES)[number];
export const INSTRUMENTS = ['PHQ9', 'GAD7'] as const;
export type Instrument = (typeof INSTRUMENTS)[number];
export const BANDS = ['MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'] as const;
export const CISM_RESPONSES = ['ACCEPTED', 'DECLINED'] as const;
export type CismResponse = (typeof CISM_RESPONSES)[number];

/** Message limit of the self-referral (§3.2, encrypted at rest). */
export const MAX_MESSAGE = 2000;
/** Sharing adjustments are screened for clinical content by the server; kept short and work-related. */
export const MAX_ADJUSTMENTS = 500;
export const MAX_ALIAS = 40;

const oneOf = <T extends string>(all: readonly T[], v: unknown): T | undefined => {
  const u = upper(v);
  return u && (all as readonly string[]).includes(u) ? (u as T) : undefined;
};

/* ====================================================================== signposting (§3.2) */

export interface SignpostService {
  code: string;
  title: string;
  summary?: string;
  url?: string;
  phone?: string;
  available24h: boolean;
}

export interface Emergency {
  text?: string;
  phone?: string;
}

export interface Signposting {
  services: SignpostService[];
  emergency?: Emergency;
}

export function toService(raw: unknown, i = 0): SignpostService | undefined {
  const r = rec(raw);
  const title = r && str(r.title);
  if (!r || !title) return undefined;
  return { code: str(r.code) ?? `S${i}`, title, summary: str(r.summary), url: safeHttpUrl(str(r.url)), phone: str(r.phone), available24h: bool(r.available24h) };
}

/** Plain array, or `{items|services|signposting, emergency}`; 24-hour services first. */
export function toSignposting(body: unknown): Signposting {
  const o = rec(body);
  const raw = Array.isArray(body) ? body : o ? ([o.items, o.services, o.signposting, o.content].find(Array.isArray) as unknown[] | undefined) ?? [] : [];
  const e = rec(o?.emergency);
  const emergency = e && (str(e.text) || str(e.phone)) ? { text: str(e.text), phone: str(e.phone) } : undefined;
  const services = raw
    .map((x, i) => toService(x, i))
    .filter((s): s is SignpostService => !!s)
    .sort((a, b) => Number(b.available24h) - Number(a.available24h));
  return { services, emergency };
}

export async function fetchSignposting(client: Client): Promise<Signposting> {
  return toSignposting(await list(client, '/me/support/signposting'));
}

/* ====================================================================== self-referral (§3.2) */

export interface SelfReferralInput {
  preferredContact: SupportContact;
  phone?: string;
  bestTime?: string;
  concern: Concern;
  message?: string;
}

export interface SelfReferralErrors {
  phone?: 'required' | 'invalid';
  concern?: 'required';
  message?: 'tooLong';
}

export const PHONE_PATTERN = /^\+?[\d\s()-]{6,20}$/;

export function validateSelfReferral(v: Partial<SelfReferralInput>): SelfReferralErrors {
  const err: SelfReferralErrors = {};
  const phone = v.phone?.trim();
  if (v.preferredContact === 'PHONE' && !phone) err.phone = 'required';
  else if (phone && !PHONE_PATTERN.test(phone)) err.phone = 'invalid';
  if (!v.concern) err.concern = 'required';
  if ((v.message ?? '').length > MAX_MESSAGE) err.message = 'tooLong';
  return err;
}

/** Request body: only the contract fields, blanks omitted. */
export function selfReferralBody(v: SelfReferralInput): Record<string, string> {
  const body: Record<string, string> = { preferredContact: v.preferredContact, concern: v.concern };
  const phone = v.phone?.trim();
  if (phone) body.phone = phone;
  if (v.bestTime) body.bestTime = v.bestTime;
  const message = v.message?.trim().slice(0, MAX_MESSAGE);
  if (message) body.message = message;
  return body;
}

export interface SelfReferralReceipt {
  caseRef?: string;
  nextContactBy?: string;
}

export async function submitSelfReferral(client: Client, v: SelfReferralInput): Promise<SelfReferralReceipt> {
  const r = rec(await client.api<unknown>('/me/support/self-referral', { method: 'POST', body: selfReferralBody(v) }));
  return { caseRef: str(r?.caseRef), nextContactBy: str(r?.nextContactBy) };
}

/* ====================================================================== screening (§3.2) */

export interface ScreenQuestion {
  code: string;
  text: string;
}
export interface ScreenOption {
  value: number;
  label: string;
}
export interface ScreenForm {
  instrument: Instrument;
  title?: string;
  questions: ScreenQuestion[];
  options: ScreenOption[];
}

export function toScreenForm(raw: unknown, fallback: Instrument): ScreenForm | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  const questions = asList<unknown>(r.questions)
    .map((q, i) => {
      const o = rec(q);
      const text = str(o?.text) ?? str(q);
      return text ? { code: str(o?.code) ?? `Q${i + 1}`, text } : undefined;
    })
    .filter((q): q is ScreenQuestion => !!q);
  const options = asList<unknown>(r.options)
    .map((x) => {
      const o = rec(x);
      const value = num(o?.value);
      const label = str(o?.label);
      return value !== undefined && label ? { value, label } : undefined;
    })
    .filter((o): o is ScreenOption => !!o)
    .sort((a, b) => a.value - b.value);
  if (questions.length === 0 || options.length === 0) return undefined;
  return { instrument: oneOf(INSTRUMENTS, r.instrument) ?? fallback, title: str(r.title), questions, options };
}

export async function fetchScreen(client: Client, instrument: Instrument): Promise<ScreenForm | undefined> {
  return toScreenForm(await client.api<unknown>(`/me/support/screens/${enc(instrument)}`), instrument);
}

/** All questions answered with an offered value. */
export function screenComplete(form: ScreenForm, answers: readonly (number | undefined)[]): boolean {
  const allowed = new Set(form.options.map((o) => o.value));
  return form.questions.every((_, i) => answers[i] !== undefined && allowed.has(answers[i]!));
}

export interface ScreenOutcome {
  score?: number;
  maxScore?: number;
  band?: string;
  urgent: boolean;
  nextSteps: string[];
}

/** Words that must never reach the employee from a self-screen (it is not a diagnosis). */
const DIAGNOSTIC = /\bdiagnos\w*|\bdisorder\b|تشخيص|diagnostic/i;

/** `{score, band, urgent, nextSteps[]}` – next steps that read like a diagnosis are dropped. */
export function toScreenOutcome(raw: unknown, form?: ScreenForm): ScreenOutcome {
  const r = rec(raw) ?? {};
  const max = form && form.options.length ? form.questions.length * Math.max(...form.options.map((o) => o.value)) : undefined;
  return {
    score: num(r.score),
    maxScore: max,
    band: oneOf(BANDS, r.band),
    urgent: bool(r.urgent) || upper(r.risk) === 'URGENT',
    nextSteps: strings(r.nextSteps).filter((s) => !DIAGNOSTIC.test(s)),
  };
}

export async function submitScreen(client: Client, form: ScreenForm, answers: readonly number[]): Promise<ScreenOutcome> {
  const res = await client.api<unknown>('/me/support/screens', { method: 'POST', body: { instrument: form.instrument, answers: [...answers] } });
  return toScreenOutcome(res, form);
}

/* ====================================================================== sharing choice (§3.1) */

export interface SharingSetting {
  caseId: string;
  caseRef?: string;
  choice: SharingChoice;
  adjustments?: string;
  updatedAt?: string;
}

export function toSharing(raw: unknown): SharingSetting | undefined {
  const r = rec(raw);
  const caseId = r && str(r.caseId);
  if (!r || !caseId) return undefined;
  return { caseId, caseRef: str(r.caseRef) ?? str(r.ref), choice: oneOf(SHARING_CHOICES, r.choice) ?? 'NOTHING', adjustments: str(r.adjustments), updatedAt: str(r.updatedAt) ?? str(r.changedAt) };
}

/** One object per support case, or a list (several cases); `{items}` tolerated. */
export function toSharingList(body: unknown): SharingSetting[] {
  const o = rec(body);
  const raw = Array.isArray(body) ? body : o && Array.isArray(o.items) ? (o.items as unknown[]) : o ? [o] : [];
  return raw.map(toSharing).filter((s): s is SharingSetting => !!s);
}

export async function fetchSharing(client: Client): Promise<SharingSetting[]> {
  return toSharingList(await list(client, '/me/support/sharing'));
}

export function sharingBody(caseId: string, choice: SharingChoice, adjustments?: string): { caseId: string; choice: SharingChoice; adjustments?: string } {
  const a = choice === 'FITNESS_AND_ADJUSTMENTS' ? adjustments?.trim().slice(0, MAX_ADJUSTMENTS) : undefined;
  return a ? { caseId, choice, adjustments: a } : { caseId, choice };
}

export async function saveSharing(client: Client, caseId: string, choice: SharingChoice, adjustments?: string): Promise<void> {
  await client.api('/me/support/sharing', { method: 'PUT', body: sharingBody(caseId, choice, adjustments) });
}

/* ====================================================================== CISM offers (§3.4) */

export interface CismOffer {
  id: string;
  title?: string;
  occurredAt?: string;
  offeredAt?: string;
  respondBy?: string;
  response?: CismResponse;
  preferredContact?: SupportContact;
}

export function toCismOffer(raw: unknown): CismOffer | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  return {
    id,
    title: str(r.title) ?? str(r.eventTitle),
    occurredAt: str(r.occurredAt),
    offeredAt: str(r.offeredAt) ?? str(r.offerSentAt),
    respondBy: str(r.respondBy) ?? str(r.offerDueBy),
    response: oneOf(CISM_RESPONSES, r.response),
    preferredContact: oneOf(SUPPORT_CONTACTS, r.preferredContact),
  };
}

export async function fetchCismOffers(client: Client): Promise<CismOffer[]> {
  return asList<unknown>(await list(client, '/me/cism/offers'))
    .map(toCismOffer)
    .filter((o): o is CismOffer => !!o)
    .sort((a, b) => Number(!!a.response) - Number(!!b.response) || (b.offeredAt ?? '').localeCompare(a.offeredAt ?? ''));
}

export function cismResponseBody(response: CismResponse, preferredContact?: SupportContact): { response: CismResponse; preferredContact?: SupportContact } {
  return response === 'ACCEPTED' && preferredContact ? { response, preferredContact } : { response };
}

export async function respondToCism(client: Client, id: string, response: CismResponse, preferredContact?: SupportContact): Promise<void> {
  await client.api(`/me/cism/offers/${enc(id)}/response`, { method: 'POST', body: cismResponseBody(response, preferredContact) });
}

/* ====================================================================== peer support (§3.3) */

export interface Peer {
  alias: string;
  base?: string;
  languages: string[];
}

/** Only alias, base and languages are kept – never a surname, username or id. */
export function toPeer(raw: unknown): Peer | undefined {
  const r = rec(raw);
  const alias = r && str(r.alias);
  if (!r || !alias) return undefined;
  return { alias, base: str(r.base), languages: strings(r.languages) };
}

export async function fetchPeers(client: Client): Promise<Peer[]> {
  return asList<unknown>(await list(client, '/me/peer-support/peers'))
    .map(toPeer)
    .filter((p): p is Peer => !!p);
}

export interface PeerRequestInput {
  alias: string;
  callbackPhone: string;
  base?: string;
  language?: string;
}

export interface PeerRequestErrors {
  alias?: 'required' | 'tooLong';
  callbackPhone?: 'required' | 'invalid';
}

export function validatePeerRequest(v: Partial<PeerRequestInput>): PeerRequestErrors {
  const err: PeerRequestErrors = {};
  const alias = v.alias?.trim() ?? '';
  if (!alias) err.alias = 'required';
  else if (alias.length > MAX_ALIAS) err.alias = 'tooLong';
  const phone = v.callbackPhone?.trim() ?? '';
  if (!phone) err.callbackPhone = 'required';
  else if (!PHONE_PATTERN.test(phone)) err.callbackPhone = 'invalid';
  return err;
}

export function peerRequestBody(v: PeerRequestInput): Record<string, string> {
  const body: Record<string, string> = { alias: v.alias.trim(), callbackPhone: v.callbackPhone.trim() };
  if (v.base?.trim()) body.base = v.base.trim();
  if (v.language?.trim()) body.language = v.language.trim();
  return body;
}

export async function requestPeerContact(client: Client, v: PeerRequestInput): Promise<string | undefined> {
  return str(rec(await client.api<unknown>('/me/peer-support/contact-request', { method: 'POST', body: peerRequestBody(v) }))?.contactCode);
}

/** Bases and languages offered by the available peers (for the optional preferences). */
export function peerChoices(peers: readonly Peer[]): { bases: string[]; languages: string[] } {
  const bases = [...new Set(peers.map((p) => p.base).filter((b): b is string => !!b))].sort();
  const languages = [...new Set(peers.flatMap((p) => p.languages))].sort();
  return { bases, languages };
}
