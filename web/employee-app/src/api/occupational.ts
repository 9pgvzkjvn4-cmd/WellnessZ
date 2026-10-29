import { ApiError, type AuthClient } from '../auth/AuthClient';
import { toRestriction, type FunctionalRestriction } from './portal';
import { asList } from './types';

/*
 * Increment 3 employee / candidate portal (docs/increment-03/API-Contract-E06-E07-E14.md):
 * E14 appointments (§5.2), E06 absence & certificates (§3.1, §3.3), E06 OH advice reports (§3.5),
 * E07 surveillance, immunisations & pregnancy declaration (§4.2, §4.5, §4.6), E07 pre-employment (§4.3).
 * Every response is projected onto the fields the screens render; anything else (reason categories, OCR fields,
 * questionnaire answers …) is dropped and never kept in UI state.
 */

type Client = Pick<AuthClient, 'api'>;
type Rec = Record<string, unknown>;

const str = (v: unknown) => (typeof v === 'string' && v ? v : typeof v === 'number' ? String(v) : undefined);
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() && Number.isFinite(Number(v)) ? Number(v) : undefined);
const bool = (v: unknown) => v === true || v === 'true';
const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
const enc = encodeURIComponent;
const MIN = 60_000;

/** List endpoints: a 404 (nothing yet / endpoint not deployed) is an empty list. */
async function list(client: Client, path: string): Promise<unknown[]> {
  try {
    return asList<unknown>(await client.api<unknown>(path));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

/** Single-object endpoints: 404 → undefined (e.g. no pre-employment record). */
async function one(client: Client, path: string): Promise<unknown> {
  try {
    return await client.api<unknown>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return undefined;
    throw e;
  }
}

export const MAX_COMMENT = 1000;
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const UPLOAD_TYPES = ['application/pdf', 'image/jpeg', 'image/png'] as const;
export const UPLOAD_ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';

/* ====================================================================== appointments (E14 §5.2) */

export const OPEN_APPOINTMENT_STATUSES = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS'] as const;
/** Self-booking rules (§5.2): ≥ 24 h notice, ≤ 60 days ahead; app check-in within 60 min of the start. */
export const SELF_BOOKING = { minNoticeHours: 24, maxDaysAhead: 60, checkInMinutesBefore: 60, telehealthMinutesBefore: 15 } as const;

export interface MyAppointment {
  id: string;
  type: string;
  typeName?: string;
  siteCode?: string;
  startsAt: string;
  endsAt?: string;
  status: string;
  telehealth: boolean;
  ticket?: string;
  checkedInAt?: string;
}

export function toAppointment(raw: unknown): MyAppointment | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const startsAt = r && str(r.startsAt);
  if (!r || !id || !startsAt) return undefined;
  return {
    id,
    type: str(r.appointmentType) ?? '',
    typeName: str(r.appointmentTypeName),
    siteCode: str(r.siteCode),
    startsAt,
    endsAt: str(r.endsAt),
    status: (str(r.status) ?? 'BOOKED').toUpperCase(),
    telehealth: bool(r.telehealth),
    ticket: str(r.ticket),
    checkedInAt: str(r.checkedInAt),
  };
}

/** Upcoming first (soonest), then past (most recent first). */
export function sortAppointments(items: MyAppointment[], now: Date = new Date()): MyAppointment[] {
  const t = now.getTime();
  const end = (a: MyAppointment) => Date.parse(a.endsAt ?? a.startsAt);
  const upcoming = items.filter((a) => end(a) >= t && a.status !== 'CANCELLED').sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  const past = items.filter((a) => !upcoming.includes(a)).sort((a, b) => b.startsAt.localeCompare(a.startsAt));
  return [...upcoming, ...past];
}

export const isOpenAppointment = (a: MyAppointment) => (OPEN_APPOINTMENT_STATUSES as readonly string[]).includes(a.status);

/** Reschedule / cancel: a booked appointment that has not started yet. */
export function canChange(a: MyAppointment, now: Date = new Date()): boolean {
  return a.status === 'BOOKED' && Date.parse(a.startsAt) > now.getTime();
}

/** App check-in on the day: from 60 minutes before the start until the end. */
export function canCheckIn(a: MyAppointment, now: Date = new Date()): boolean {
  if (a.status !== 'BOOKED') return false;
  const t = now.getTime();
  const start = Date.parse(a.startsAt);
  const end = Date.parse(a.endsAt ?? a.startsAt) || start;
  return t >= start - SELF_BOOKING.checkInMinutesBefore * MIN && t <= Math.max(end, start);
}

/** Telehealth join: 15 minutes before the start until the end. */
export function canJoinTelehealth(a: MyAppointment, now: Date = new Date()): boolean {
  if (!a.telehealth || !isOpenAppointment(a)) return false;
  const t = now.getTime();
  const start = Date.parse(a.startsAt);
  const end = Date.parse(a.endsAt ?? a.startsAt) || start;
  return t >= start - SELF_BOOKING.telehealthMinutesBefore * MIN && t <= Math.max(end, start);
}

export interface BookableType {
  code: string;
  name: string;
  durationMinutes?: number;
  telehealth: boolean;
}

export function toBookableTypes(body: unknown): BookableType[] {
  return asList<unknown>(body)
    .map(rec)
    .filter((r): r is Rec => !!r && bool(r.selfBookable) && r.active !== false && !!str(r.code))
    .map((r) => ({ code: str(r.code)!, name: str(r.name) ?? str(r.code)!, durationMinutes: num(r.durationMinutes), telehealth: bool(r.telehealth) }));
}

export interface ClinicSite {
  code: string;
  name: string;
  timeZone?: string;
}

export function toSites(body: unknown): ClinicSite[] {
  return asList<unknown>(body)
    .map(rec)
    .filter((r): r is Rec => !!r && r.active !== false && !!str(r.code))
    .map((r) => ({ code: str(r.code)!, name: str(r.name) ?? str(r.code)!, timeZone: str(r.timeZone) }));
}

export interface Slot {
  startsAt: string;
  endsAt?: string;
}

/** Free slots within the self-booking window, de-duplicated by start time (clinician / room are not shown). */
export function toSlots(body: unknown, now: Date = new Date()): Slot[] {
  const min = now.getTime() + SELF_BOOKING.minNoticeHours * 60 * MIN;
  const max = now.getTime() + SELF_BOOKING.maxDaysAhead * 24 * 60 * MIN;
  const seen = new Set<string>();
  const out: Slot[] = [];
  for (const raw of asList<unknown>(body)) {
    const r = rec(raw);
    const s = r && str(r.startsAt);
    if (!r || !s || seen.has(s)) continue;
    const t = Date.parse(s);
    if (!(t >= min && t <= max)) continue;
    seen.add(s);
    out.push({ startsAt: s, endsAt: str(r.endsAt) });
  }
  return out.sort((a, b) => a.startsAt.localeCompare(b.startsAt));
}

/** Group slots by local calendar day (`YYYY-MM-DD` in the device zone). */
export function slotsByDay(slots: readonly Slot[]): [string, Slot[]][] {
  const m = new Map<string, Slot[]>();
  for (const s of slots) {
    const d = new Date(s.startsAt);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    m.set(key, [...(m.get(key) ?? []), s]);
  }
  return [...m.entries()];
}

export async function fetchMyAppointments(client: Client): Promise<MyAppointment[]> {
  return (await list(client, '/me/appointments')).map(toAppointment).filter((a): a is MyAppointment => !!a);
}
export async function fetchBookableTypes(client: Client): Promise<BookableType[]> {
  return toBookableTypes(await list(client, '/scheduling/appointment-types'));
}
export async function fetchSites(client: Client): Promise<ClinicSite[]> {
  return toSites(await list(client, '/masterdata/clinic-sites'));
}
export function slotsPath(type: string, siteCode: string, from: string, to: string): string {
  return `/me/appointments/slots?type=${enc(type)}&siteCode=${enc(siteCode)}&from=${enc(from)}&to=${enc(to)}`;
}
export async function fetchSlots(client: Client, type: string, siteCode: string, from: string, to: string, now?: Date): Promise<Slot[]> {
  return toSlots(await list(client, slotsPath(type, siteCode, from, to)), now);
}
export async function bookAppointment(client: Client, appointmentType: string, siteCode: string, startsAt: string): Promise<MyAppointment | undefined> {
  return toAppointment(await client.api<unknown>('/me/appointments', { method: 'POST', body: { appointmentType, siteCode, startsAt } }));
}
export async function rescheduleAppointment(client: Client, id: string, startsAt: string): Promise<MyAppointment | undefined> {
  return toAppointment(await client.api<unknown>(`/me/appointments/${enc(id)}/reschedule`, { method: 'POST', body: { startsAt } }));
}
export async function cancelAppointment(client: Client, id: string, reason?: string): Promise<void> {
  const r = reason?.trim();
  await client.api(`/me/appointments/${enc(id)}/cancel`, { method: 'POST', body: r ? { reason: r.slice(0, 500) } : {} });
}
/** Returns the queue ticket when the server provides one. */
export async function checkInAppointment(client: Client, id: string): Promise<string | undefined> {
  const r = rec(await client.api<unknown>(`/me/appointments/${enc(id)}/check-in`, { method: 'POST', body: {} }));
  return r ? str(r.ticket) : undefined;
}
/** Telehealth join link – only http(s) URLs are accepted. */
export async function telehealthLink(client: Client, id: string): Promise<string | undefined> {
  const r = rec(await client.api<unknown>(`/scheduling/appointments/${enc(id)}/telehealth`, { method: 'POST', body: {} }));
  return safeHttpUrl(r && str(r.joinUrl));
}

export function safeHttpUrl(u: string | undefined): string | undefined {
  if (!u) return undefined;
  try {
    const p = new URL(u);
    return p.protocol === 'https:' || p.protocol === 'http:' ? p.href : undefined;
  } catch {
    return undefined;
  }
}

/* ====================================================================== absence (E06 §3.1, §3.3) */

export interface MyEpisode {
  id: string;
  startDate: string;
  endDate?: string;
  days?: number;
  absenceType?: string;
  certified: boolean;
  certificateValidity?: string;
  open: boolean;
}

export function toEpisode(raw: unknown): MyEpisode | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const startDate = r && str(r.startDate);
  if (!r || !id || !startDate) return undefined;
  // reasonCategory is OH-only and never read here.
  return {
    id,
    startDate,
    endDate: str(r.endDate),
    days: num(r.days),
    absenceType: str(r.absenceType),
    certified: bool(r.certified),
    certificateValidity: str(r.certificateValidity),
    open: r.open === undefined ? !str(r.endDate) : bool(r.open),
  };
}

export interface MyCertificate {
  id: string;
  fromDate?: string;
  toDate?: string;
  status: string;
  receivedAt?: string;
}

/** Employees see validity only (RECEIVED / VALID / INVALID); finer OH states are folded. */
export function certificateStatus(s: string | undefined): 'RECEIVED' | 'VALID' | 'INVALID' {
  const v = (s ?? '').toUpperCase();
  if (v === 'VALID' || v === 'VERIFIED_GENUINE') return 'VALID';
  if (v === 'INVALID' || v === 'VERIFIED_FRAUDULENT') return 'INVALID';
  return 'RECEIVED';
}

export function toCertificate(raw: unknown): MyCertificate | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  return { id, fromDate: str(r.fromDate), toDate: str(r.toDate), status: certificateStatus(str(r.status)), receivedAt: str(r.receivedAt) };
}

export async function fetchMyEpisodes(client: Client): Promise<MyEpisode[]> {
  return (await list(client, '/me/absence/episodes'))
    .map(toEpisode)
    .filter((e): e is MyEpisode => !!e)
    .sort((a, b) => b.startDate.localeCompare(a.startDate));
}
export async function fetchMyCertificates(client: Client): Promise<MyCertificate[]> {
  return (await list(client, '/me/absence/certificates'))
    .map(toCertificate)
    .filter((c): c is MyCertificate => !!c)
    .sort((a, b) => (b.receivedAt ?? b.fromDate ?? '').localeCompare(a.receivedAt ?? a.fromDate ?? ''));
}
/** Multipart `file`, `fromDate`, `toDate`, `episodeId?`. */
export async function uploadCertificate(client: Client, file: Blob, fileName: string, fromDate: string, toDate: string, episodeId?: string): Promise<MyCertificate | undefined> {
  const form = new FormData();
  form.append('file', file, fileName);
  form.append('fromDate', fromDate);
  form.append('toDate', toDate);
  if (episodeId) form.append('episodeId', episodeId);
  return toCertificate(await client.api<unknown>('/me/absence/certificates', { method: 'POST', body: form }));
}

/* ====================================================================== OH advice reports (E06 §3.5) */

export interface AdviceReportContent {
  fitness?: string;
  adjustments: FunctionalRestriction[];
  restrictions: FunctionalRestriction[];
  prognosis?: string;
  expectedReturn?: string;
  reviewDate?: string;
  answers: { code: string; text: string }[];
  employeeComment?: string;
  releasedAt?: string;
}

export interface MyAdviceReport {
  id: string;
  requestRef?: string;
  requestedByRole?: string;
  status: string;
  createdAt?: string;
  report?: AdviceReportContent;
}

const textList = (v: unknown): FunctionalRestriction[] => {
  if (typeof v === 'string') return v.trim() ? [{ text: v.trim() }] : [];
  return Array.isArray(v) ? v.map(toRestriction).filter((x): x is FunctionalRestriction => !!x) : [];
};

export function toAdviceReport(raw: unknown): MyAdviceReport | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  const rep = rec(r.report);
  const answers = rec(rep?.answers);
  return {
    id,
    requestRef: str(r.requestRef),
    requestedByRole: str(r.requestedByRole),
    status: (str(r.status) ?? '').toUpperCase(),
    createdAt: str(r.createdAt),
    report: rep && {
      fitness: str(rep.fitness),
      adjustments: textList(rep.adjustments),
      restrictions: textList(rep.restrictions),
      prognosis: str(rep.prognosis),
      expectedReturn: str(rep.expectedReturn),
      reviewDate: str(rep.reviewDate),
      answers: answers ? Object.entries(answers).flatMap(([code, text]) => (typeof text === 'string' && text.trim() ? [{ code, text }] : [])) : [],
      employeeComment: str(rep.employeeComment),
      releasedAt: str(rep.releasedAt),
    },
  };
}

export const awaitingDecision = (a: MyAdviceReport) => a.status === 'AWAITING_EMPLOYEE';

export async function fetchMyAdviceReports(client: Client): Promise<MyAdviceReport[]> {
  return (await list(client, '/me/advice-reports'))
    .map(toAdviceReport)
    .filter((a): a is MyAdviceReport => !!a)
    .sort((a, b) => Number(awaitingDecision(b)) - Number(awaitingDecision(a)) || (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
}

/** Question library `[{code, text}]` → map (authenticated, used to label the answers). */
export async function fetchAdviceQuestions(client: Client): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const raw of await list(client, '/absence/advice/questions')) {
    const r = rec(raw);
    const code = r && str(r.code);
    const text = r && str(r.text);
    if (code && text) out[code] = text;
  }
  return out;
}

export interface AdviceDecision {
  decision: 'CONSENT' | 'DECLINE';
  comment?: string;
  declineReason?: string;
}

export function adviceDecisionBody(d: AdviceDecision): AdviceDecision {
  const comment = d.comment?.trim().slice(0, MAX_COMMENT) || undefined;
  const declineReason = d.decision === 'DECLINE' ? d.declineReason?.trim().slice(0, MAX_COMMENT) || undefined : undefined;
  return { decision: d.decision, ...(comment ? { comment } : {}), ...(declineReason ? { declineReason } : {}) };
}

export async function decideAdviceReport(client: Client, id: string, d: AdviceDecision): Promise<MyAdviceReport | undefined> {
  return toAdviceReport(await client.api<unknown>(`/me/advice-reports/${enc(id)}/decision`, { method: 'POST', body: adviceDecisionBody(d) }));
}

/* ====================================================================== surveillance & immunisations (E07 §4.2, §4.5, §4.6) */

export interface MyProgramme {
  title: string;
  nextDueOn?: string;
  status: string;
  appointment?: { startsAt?: string; siteCode?: string };
}

export interface MyImmunisation {
  vaccineCode?: string;
  vaccineName: string;
  status: string;
  lastDoseOn?: string;
  nextDueOn?: string;
  dosesGiven?: number;
  dosesRequired?: number;
}

export interface CrewCertificate {
  certificateType?: string;
  validUntil?: string;
  status?: string;
}

export interface MySurveillance {
  programmes: MyProgramme[];
  immunisations: MyImmunisation[];
  crewCertificate?: CrewCertificate;
}

export function toProgramme(raw: unknown): MyProgramme | undefined {
  const r = rec(raw);
  const title = r && (str(r.programmeTitle) ?? str(r.title));
  if (!r || !title) return undefined;
  const a = rec(r.appointment);
  return { title, nextDueOn: str(r.nextDueOn), status: (str(r.status) ?? 'CURRENT').toUpperCase(), appointment: a ? { startsAt: str(a.startsAt), siteCode: str(a.siteCode) } : undefined };
}

export function toImmunisation(raw: unknown): MyImmunisation | undefined {
  const r = rec(raw);
  const name = r && (str(r.vaccineName) ?? str(r.vaccineCode) ?? str(r.vaccine));
  if (!r || !name) return undefined;
  return {
    vaccineCode: str(r.vaccineCode),
    vaccineName: name,
    status: (str(r.status) ?? '').toUpperCase(),
    lastDoseOn: str(r.lastDoseOn),
    nextDueOn: str(r.nextDueOn),
    dosesGiven: num(r.dosesGiven),
    dosesRequired: num(r.dosesRequired),
  };
}

const PROGRAMME_ORDER: Record<string, number> = { OVERDUE: 0, DUE: 1, SCHEDULED: 2, CURRENT: 3, EXEMPT: 4 };

/** `/me/surveillance` is either a plain array of programmes or an object `{programmes|items, crewCertificate?, immunisations?}`. */
export function toMySurveillance(body: unknown): MySurveillance {
  const o = rec(body);
  const rawProgrammes = Array.isArray(body) ? body : asList<unknown>(o?.programmes ?? o?.items ?? o?.enrolments ?? o);
  const c = rec(o?.crewCertificate);
  return {
    programmes: rawProgrammes
      .map(toProgramme)
      .filter((p): p is MyProgramme => !!p)
      .sort((a, b) => (PROGRAMME_ORDER[a.status] ?? 9) - (PROGRAMME_ORDER[b.status] ?? 9) || (a.nextDueOn ?? '9').localeCompare(b.nextDueOn ?? '9')),
    immunisations: asList<unknown>(o?.immunisations).map(toImmunisation).filter((i): i is MyImmunisation => !!i),
    crewCertificate: c ? { certificateType: str(c.certificateType), validUntil: str(c.validUntil), status: str(c.status)?.toUpperCase() } : undefined,
  };
}

export async function fetchMySurveillance(client: Client): Promise<MySurveillance> {
  const body = await one(client, '/me/surveillance');
  return body === undefined ? { programmes: [], immunisations: [] } : toMySurveillance(body);
}
export async function fetchMyImmunisations(client: Client): Promise<MyImmunisation[]> {
  return (await list(client, '/me/immunisations')).map(toImmunisation).filter((i): i is MyImmunisation => !!i);
}
/** Generates the signed vaccination certificate; it is then listed in "My documents". Returns the document id. */
export async function requestVaccinationCertificate(client: Client): Promise<string | undefined> {
  const r = rec(await client.api<unknown>('/me/immunisations/certificate', { method: 'POST', body: {} }));
  return r ? str(r.id) : undefined;
}
export async function declarePregnancy(client: Client, declaredOn: string): Promise<void> {
  await client.api('/me/pregnancy-declaration', { method: 'POST', body: { declaredOn } });
}

/* ====================================================================== pre-employment (E07 §4.3, CANDIDATE) */

export const PRE_EMPLOYMENT_PURPOSES = ['PRE_EMPLOYMENT_ASSESSMENT', 'HR_STATUS_FEED'] as const;

export interface MyPreEmployment {
  offerRef?: string;
  jobTitle?: string;
  proposedStart?: string;
  status: string;
  questionnaireCompleted: boolean;
  consentGiven: boolean;
  appointment?: MyAppointment;
  documents: { id: string; title: string }[];
}

export function toPreEmployment(raw: unknown): MyPreEmployment | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  return {
    offerRef: str(r.offerRef),
    jobTitle: str(r.jobTitle),
    proposedStart: str(r.proposedStart),
    status: (str(r.status) ?? 'INVITED').toUpperCase(),
    questionnaireCompleted: bool(r.questionnaireCompleted),
    consentGiven: bool(r.consentGiven),
    appointment: toAppointment(r.appointment),
    documents: asList<unknown>(r.documents)
      .map(rec)
      .filter((d): d is Rec => !!d && !!str(d.id))
      .map((d) => ({ id: str(d.id)!, title: str(d.title) ?? '' })),
  };
}

export type StepId = 'questionnaire' | 'consent' | 'documents' | 'book';

/** Which steps are complete (documents are optional – done once at least one is uploaded). */
export function preEmploymentSteps(r: MyPreEmployment): Record<StepId, boolean> {
  return {
    questionnaire: r.questionnaireCompleted,
    consent: r.consentGiven,
    documents: r.documents.length > 0,
    book: !!r.appointment && r.appointment.status !== 'CANCELLED',
  };
}

export type QuestionType = 'YES_NO' | 'TEXT' | 'DATE';
export interface Question {
  code: string;
  text: string;
  type: QuestionType;
  required: boolean;
}

export function toQuestions(body: unknown): Question[] {
  return asList<unknown>(body)
    .map(rec)
    .filter((r): r is Rec => !!r && !!str(r.code) && !!str(r.text))
    .map((r) => {
      const t = (str(r.type) ?? 'TEXT').toUpperCase();
      return { code: str(r.code)!, text: str(r.text)!, type: (t === 'YES_NO' || t === 'DATE' ? t : 'TEXT') as QuestionType, required: bool(r.required) };
    });
}

export async function fetchMyPreEmployment(client: Client): Promise<MyPreEmployment | undefined> {
  return toPreEmployment(await one(client, '/me/pre-employment'));
}
export async function fetchQuestionnaire(client: Client): Promise<Question[]> {
  return toQuestions(await list(client, '/me/pre-employment/questionnaire'));
}
/** Answers: YES_NO → "YES" | "NO", DATE → "YYYY-MM-DD", TEXT → trimmed text. Empty optional answers are omitted. */
export async function submitQuestionnaire(client: Client, answers: Record<string, string>): Promise<void> {
  const clean: Record<string, string> = {};
  for (const [k, v] of Object.entries(answers)) if (v.trim()) clean[k] = v.trim().slice(0, 2000);
  await client.api('/me/pre-employment/questionnaire', { method: 'PUT', body: { answers: clean } });
}
export async function givePreEmploymentConsent(client: Client): Promise<void> {
  await client.api('/me/pre-employment/consent', { method: 'POST', body: { consentGiven: true, purposes: [...PRE_EMPLOYMENT_PURPOSES] } });
}
export async function uploadPreEmploymentDocument(client: Client, file: Blob, fileName: string, title: string): Promise<void> {
  const form = new FormData();
  form.append('file', file, fileName);
  form.append('title', title.trim().slice(0, 200));
  await client.api('/me/pre-employment/documents', { method: 'POST', body: form });
}
export async function bookPreEmploymentExam(client: Client, siteCode?: string, earliest?: string): Promise<MyAppointment | undefined> {
  const body: Rec = {};
  if (siteCode) body.siteCode = siteCode;
  if (earliest) body.earliest = earliest;
  return toAppointment(await client.api<unknown>('/me/pre-employment/book', { method: 'POST', body }));
}

/* ====================================================================== home counts */

export interface OhCounts {
  appointments: number;
  advice: number;
  surveillance: number;
}

/** Upcoming appointments, advice reports awaiting a decision, programmes due / overdue. Failures just hide a count. */
export async function loadOhCounts(client: Client, now: Date = new Date()): Promise<Partial<OhCounts>> {
  const [appts, advice, surv] = await Promise.allSettled([fetchMyAppointments(client), fetchMyAdviceReports(client), fetchMySurveillance(client)]);
  const counts: Partial<OhCounts> = {};
  if (appts.status === 'fulfilled') counts.appointments = appts.value.filter((a) => isOpenAppointment(a) && Date.parse(a.endsAt ?? a.startsAt) >= now.getTime()).length;
  if (advice.status === 'fulfilled') counts.advice = advice.value.filter(awaitingDecision).length;
  if (surv.status === 'fulfilled') counts.surveillance = surv.value.programmes.filter((p) => p.status === 'DUE' || p.status === 'OVERDUE').length;
  return counts;
}
