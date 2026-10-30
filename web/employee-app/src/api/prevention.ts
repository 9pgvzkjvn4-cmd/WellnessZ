import { ApiError, type AuthClient } from '../auth/AuthClient';
import { fetchMyConsents, fetchMyDocuments, isWithdrawn } from './portal';
import { fetchCismOffers } from './support';
import { asList, type Consent } from './types';

/*
 * Increment 5 employee portal (docs/increment-05/API-Contract-E09-E10-E11-E12.md):
 * E10 my workplace-check notices (§4.3 `/me/testing` – where and by when to report, never a result),
 * E11 travel health (§5.1 `/me/travel`), contact check-ins (§5.2 `/me/check-ins`), vaccination campaigns (§5.4) and
 * health advisories (§5.5), E12 campaigns, screening bookings and the wearable programme (§6 `/me/campaigns`,
 * `/me/wearables/enrolments` with WEARABLE_DATA consent).
 * Responses are projected onto the fields the screens render; anything else is dropped and never kept in UI state.
 */

type Client = Pick<AuthClient, 'api'>;
type Rec = Record<string, unknown>;

const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : typeof v === 'number' ? String(v) : undefined);
const bool = (v: unknown) => v === true || v === 'true';
const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
const upper = (v: unknown) => str(v)?.toUpperCase();
const strings = (v: unknown) => asList<unknown>(v).map((x) => str(x) ?? str(rec(x)?.name) ?? str(rec(x)?.code) ?? str(rec(x)?.text)).filter((x): x is string => !!x);
const enc = encodeURIComponent;

async function get(client: Client, path: string): Promise<unknown> {
  try {
    return await client.api<unknown>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

const oneOf = <T extends string>(all: readonly T[], v: unknown): T | undefined => {
  const u = upper(v);
  return u && (all as readonly string[]).includes(u) ? (u as T) : undefined;
};

/** Local calendar date `YYYY-MM-DD` (no UTC shift). */
export function localDay(d: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/* ====================================================================== E10 workplace checks (§4.3) */

/**
 * The employee sees a neutral phase only. The raw test status (which could reveal an outcome, e.g. VERIFIED_POSITIVE or
 * REFUSAL) is mapped here and never kept.
 */
export const CHECK_PHASES = ['REPORT', 'DONE', 'MISSED', 'CANCELLED'] as const;
export type CheckPhase = (typeof CHECK_PHASES)[number];

export function checkPhase(status: string | undefined): CheckPhase {
  switch (upper(status)) {
    case 'REQUIRED':
    case 'NOTIFIED':
    case 'DISPATCHED':
    case undefined:
      return 'REPORT';
    case 'WINDOW_MISSED':
      return 'MISSED';
    case 'CANCELLED':
      return 'CANCELLED';
    default:
      return 'DONE';
  }
}

export interface TestingNotice {
  ref: string;
  phase: CheckPhase;
  reportBy?: string;
  siteCode?: string;
}

/** Only `{ref, phase, reportBy, siteCode}` – reason, panels, results and verdicts are dropped. */
export function toTestingNotice(raw: unknown): TestingNotice | undefined {
  const r = rec(raw);
  const ref = r && str(r.ref);
  if (!r || !ref) return undefined;
  return { ref, phase: checkPhase(str(r.status)), reportBy: str(r.reportBy) ?? str(r.dueAt), siteCode: str(r.siteCode) };
}

export type ReportWindow = { state: 'open'; minutesLeft: number } | { state: 'passed' } | { state: 'none' };

/** Time left to report (`reportBy`), for open notices only. */
export function reportWindow(n: TestingNotice, now: Date = new Date()): ReportWindow {
  if (n.phase !== 'REPORT' || !n.reportBy) return { state: 'none' };
  const left = Date.parse(n.reportBy) - now.getTime();
  if (Number.isNaN(left)) return { state: 'none' };
  return left <= 0 ? { state: 'passed' } : { state: 'open', minutesLeft: Math.ceil(left / 60_000) };
}

const PHASE_ORDER: Record<CheckPhase, number> = { REPORT: 0, MISSED: 1, DONE: 2, CANCELLED: 3 };

export function sortNotices(items: TestingNotice[]): TestingNotice[] {
  return [...items].sort((a, b) => PHASE_ORDER[a.phase] - PHASE_ORDER[b.phase] || (a.reportBy ?? '9').localeCompare(b.reportBy ?? '9'));
}

export async function fetchTestingNotices(client: Client): Promise<TestingNotice[]> {
  return sortNotices(asList<unknown>(await get(client, '/me/testing')).map(toTestingNotice).filter((n): n is TestingNotice => !!n));
}

/* ====================================================================== E11 travel (§5.1) */

export const TRIP_PURPOSES = ['LAYOVER', 'POSTING', 'BUSINESS'] as const;

export interface TravelConsultation {
  id: string;
  ref?: string;
  date?: string;
  destinations: string[];
  departDate?: string;
  returnDate?: string;
  purpose?: string;
  vaccines: string[];
  prophylaxis: string[];
  advice?: string;
  certificateId?: string;
}

export interface TravelCertificate {
  documentId: string;
  title?: string;
  issuedAt?: string;
}

export interface UpcomingDestination {
  key: string;
  dutyRef?: string;
  date?: string;
  code: string;
  name?: string;
  advice?: string;
  risks: string[];
  recommendedVaccines: string[];
  prophylaxis: string[];
}

export interface MyTravel {
  consultations: TravelConsultation[];
  certificates: TravelCertificate[];
  upcoming: UpcomingDestination[];
}

const destName = (v: unknown) => str(v) ?? str(rec(v)?.name) ?? str(rec(v)?.countryCode);

export function toConsultation(raw: unknown): TravelConsultation | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  const trip = rec(r.trip) ?? r;
  return {
    id,
    ref: str(r.ref),
    date: str(r.consultedAt) ?? str(r.createdAt) ?? str(r.date),
    destinations: asList<unknown>(trip.destinations).map(destName).filter((d): d is string => !!d),
    departDate: str(trip.departDate),
    returnDate: str(trip.returnDate),
    purpose: oneOf(TRIP_PURPOSES, trip.purpose),
    vaccines: asList<unknown>(r.vaccinesGiven).map((v) => str(rec(v)?.vaccineName) ?? str(rec(v)?.vaccineCode) ?? str(v)).filter((v): v is string => !!v),
    prophylaxis: asList<unknown>(r.prophylaxis).map((p) => { const o = rec(p); return o ? [str(o.drug), str(o.regimen)].filter(Boolean).join(' – ') || undefined : str(p); }).filter((v): v is string => !!v),
    advice: str(r.advice),
    certificateId: str(r.certificateDocumentId) ?? str(r.certificateId) ?? str(rec(r.certificate)?.documentId),
  };
}

export function toCertificate(raw: unknown): TravelCertificate | undefined {
  const r = rec(raw);
  const documentId = r && (str(r.documentId) ?? str(r.id));
  if (!r || !documentId) return undefined;
  const status = upper(r.status);
  if (status === 'REVOKED' || status === 'DRAFT') return undefined;
  return { documentId, title: str(r.title), issuedAt: str(r.issuedAt) ?? str(r.signedAt) ?? str(r.createdAt) };
}

export function toUpcoming(raw: unknown, i = 0): UpcomingDestination | undefined {
  const r = rec(raw);
  const dest = rec(r?.destination);
  const code = r && (str(r.destinationCode) ?? str(r.countryCode) ?? str(dest?.countryCode));
  if (!r || !code) return undefined;
  const src = dest ?? r;
  return {
    key: `${str(r.dutyRef) ?? code}-${str(r.dutyDate) ?? str(r.date) ?? i}`,
    dutyRef: str(r.dutyRef),
    date: str(r.dutyDate) ?? str(r.date) ?? str(r.startsAt),
    code,
    name: str(r.destinationName) ?? str(src.name),
    advice: str(src.advice) ?? str(r.advice),
    risks: strings(src.risks),
    recommendedVaccines: strings(src.recommendedVaccines),
    prophylaxis: strings(src.prophylaxis),
  };
}

/** `GET /me/travel` – `{consultations, certificates, upcoming|upcomingDestinations|destinations}` (tolerant). */
export function toMyTravel(body: unknown): MyTravel {
  const o = rec(body) ?? {};
  return {
    consultations: asList<unknown>(o.consultations)
      .map(toConsultation)
      .filter((c): c is TravelConsultation => !!c)
      .sort((a, b) => (b.departDate ?? b.date ?? '').localeCompare(a.departDate ?? a.date ?? '')),
    certificates: asList<unknown>(o.certificates).map(toCertificate).filter((c): c is TravelCertificate => !!c),
    upcoming: asList<unknown>(o.upcoming ?? o.upcomingDestinations ?? o.destinations ?? o.roster)
      .map((x, i) => toUpcoming(x, i))
      .filter((u): u is UpcomingDestination => !!u)
      .sort((a, b) => (a.date ?? '9').localeCompare(b.date ?? '9')),
  };
}

export const TRAVEL_CERTIFICATE_TYPE = 'TRAVEL_HEALTH_CERTIFICATE';

/** Certificates come from `/me/travel`; if it lists none, travel-health certificates shared in "My documents" are used. */
export async function fetchMyTravel(client: Client): Promise<MyTravel> {
  const travel = toMyTravel(await get(client, '/me/travel'));
  if (travel.certificates.length > 0) return travel;
  try {
    const docs = await fetchMyDocuments(client);
    travel.certificates = docs
      .filter((d) => d.type === TRAVEL_CERTIFICATE_TYPE && d.status !== 'SUPERSEDED')
      .map((d) => ({ documentId: d.id, title: d.title, issuedAt: d.issuedAt }));
  } catch {
    // documents are optional here; the travel data is still shown
  }
  return travel;
}

/* ====================================================================== E11 advisories (§5.5) */

export const ADVISORY_SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const;

export interface Advisory {
  id: string;
  title: string;
  severity: string;
  body?: string;
  destinationCode?: string;
  baseCode?: string;
  validFrom?: string;
  validUntil?: string;
  acknowledgedAt?: string;
}

export function toAdvisory(raw: unknown): Advisory | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const title = r && str(r.title);
  if (!r || !id || !title) return undefined;
  return {
    id,
    title,
    severity: oneOf(ADVISORY_SEVERITIES, r.severity) ?? 'INFO',
    body: str(r.body),
    destinationCode: str(r.destinationCode),
    baseCode: str(r.baseCode),
    validFrom: str(r.validFrom),
    validUntil: str(r.validUntil),
    acknowledgedAt: str(r.acknowledgedAt) ?? (bool(r.acknowledged) ? 'yes' : undefined),
  };
}

const SEVERITY_ORDER: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 };

/** Unacknowledged first, then by severity. Expired advisories (validUntil passed) are hidden. */
export function sortAdvisories(items: Advisory[], now: Date = new Date()): Advisory[] {
  const today = localDay(now);
  return items
    .filter((a) => !a.validUntil || a.validUntil.slice(0, 10) >= today)
    .sort((a, b) => Number(!!a.acknowledgedAt) - Number(!!b.acknowledgedAt) || (SEVERITY_ORDER[a.severity] ?? 3) - (SEVERITY_ORDER[b.severity] ?? 3));
}

export async function fetchAdvisories(client: Client, now?: Date): Promise<Advisory[]> {
  return sortAdvisories(asList<unknown>(await get(client, '/me/advisories')).map(toAdvisory).filter((a): a is Advisory => !!a), now);
}

export async function acknowledgeAdvisory(client: Client, id: string): Promise<void> {
  await client.api(`/me/advisories/${enc(id)}/acknowledge`, { method: 'POST', body: {} });
}

/* ====================================================================== E11 contact check-ins (§5.2) */

export const SYMPTOMS = ['FEVER', 'COUGH', 'VOMITING', 'DIARRHOEA', 'RASH', 'SORE_THROAT', 'NONE'] as const;
export type Symptom = (typeof SYMPTOMS)[number];
export const TEMP_MIN = 34;
export const TEMP_MAX = 43;

export interface CheckIn {
  id: string;
  dueOn?: string;
  monitoringUntil?: string;
  submittedAt?: string;
}

export function toCheckIn(raw: unknown): CheckIn | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  if (!r || !id) return undefined;
  // condition, contact type, index case and risk level are not kept – the screen does not need them.
  return {
    id,
    dueOn: str(r.dueOn) ?? str(r.date) ?? str(r.dueDate),
    monitoringUntil: str(r.monitoringUntil),
    submittedAt: str(r.submittedAt) ?? str(r.completedAt) ?? str(r.checkedInAt),
  };
}

export type CheckInWindow = 'today' | 'upcoming' | 'missed' | 'done';

/** A check-in can be sent on its day only (local date); earlier days not sent are missed. */
export function checkInWindow(c: CheckIn, now: Date = new Date()): CheckInWindow {
  if (c.submittedAt) return 'done';
  const today = localDay(now);
  const day = c.dueOn?.slice(0, 10);
  if (!day || day === today) return 'today';
  return day > today ? 'upcoming' : 'missed';
}

export function sortCheckIns(items: CheckIn[], now: Date = new Date()): CheckIn[] {
  const order: Record<CheckInWindow, number> = { today: 0, upcoming: 1, missed: 2, done: 3 };
  return [...items].sort((a, b) => order[checkInWindow(a, now)] - order[checkInWindow(b, now)] || (a.dueOn ?? '').localeCompare(b.dueOn ?? ''));
}

export async function fetchCheckIns(client: Client, now?: Date): Promise<CheckIn[]> {
  return sortCheckIns(asList<unknown>(await get(client, '/me/check-ins')).map(toCheckIn).filter((c): c is CheckIn => !!c), now);
}

export interface CheckInErrors {
  symptoms?: 'required';
  temperature?: 'invalid';
}

/** At least one box; "no symptoms" excludes the others; temperature optional, 34–43 °C. */
export function validateCheckIn(symptoms: readonly Symptom[], temperature: string): CheckInErrors {
  const err: CheckInErrors = {};
  if (symptoms.length === 0) err.symptoms = 'required';
  const t = temperature.trim().replace(',', '.');
  if (t) {
    const v = Number(t);
    if (!Number.isFinite(v) || v < TEMP_MIN || v > TEMP_MAX) err.temperature = 'invalid';
  }
  return err;
}

/** Toggle a symptom: NONE clears the others and vice versa. */
export function toggleSymptom(current: readonly Symptom[], s: Symptom): Symptom[] {
  if (current.includes(s)) return current.filter((x) => x !== s);
  if (s === 'NONE') return ['NONE'];
  return [...current.filter((x) => x !== 'NONE'), s];
}

export function checkInBody(symptoms: readonly Symptom[], temperature: string): { symptoms: Symptom[]; temperatureC?: number } {
  const t = temperature.trim().replace(',', '.');
  const v = t ? Math.round(Number(t) * 10) / 10 : undefined;
  const list = SYMPTOMS.filter((s) => symptoms.includes(s));
  return v !== undefined && Number.isFinite(v) ? { symptoms: list, temperatureC: v } : { symptoms: list };
}

/** `true` when the server says the answer counts as symptomatic (the nurse is alerted). */
export async function submitCheckIn(client: Client, id: string, symptoms: readonly Symptom[], temperature: string): Promise<boolean> {
  const body = checkInBody(symptoms, temperature);
  const res = rec(await client.api<unknown>(`/me/check-ins/${enc(id)}`, { method: 'POST', body }));
  return res && 'symptomatic' in res ? bool(res.symptomatic) : body.symptoms.some((s) => s !== 'NONE') || (body.temperatureC ?? 0) >= 38;
}

/* ====================================================================== E11 vaccination campaigns (§5.4) */

export type VaccinationResponse = 'ACCEPT' | 'DECLINE';

export interface VaccinationCampaign {
  id: string;
  name: string;
  vaccineCode?: string;
  startDate?: string;
  endDate?: string;
  siteCodes: string[];
  response?: VaccinationResponse;
}

function toVaccResponse(v: unknown): VaccinationResponse | undefined {
  const u = upper(v);
  if (u === 'ACCEPT' || u === 'ACCEPTED') return 'ACCEPT';
  if (u === 'DECLINE' || u === 'DECLINED') return 'DECLINE';
  return undefined;
}

export function toVaccinationCampaign(raw: unknown): VaccinationCampaign | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const name = r && str(r.name);
  if (!r || !id || !name) return undefined;
  // eligibility, uptake targets and other employees' data are never kept.
  return { id, name, vaccineCode: str(r.vaccineCode), startDate: str(r.startDate), endDate: str(r.endDate), siteCodes: strings(r.siteCodes), response: toVaccResponse(r.response ?? r.myResponse) };
}

/** Respond while the campaign has not ended (local date) and no answer was given yet. */
export function canRespondToCampaign(c: VaccinationCampaign, now: Date = new Date()): boolean {
  return !c.response && (!c.endDate || c.endDate.slice(0, 10) >= localDay(now));
}

export async function fetchVaccinationCampaigns(client: Client): Promise<VaccinationCampaign[]> {
  return asList<unknown>(await get(client, '/me/vaccination-campaigns'))
    .map(toVaccinationCampaign)
    .filter((c): c is VaccinationCampaign => !!c)
    .sort((a, b) => (a.endDate ?? '9').localeCompare(b.endDate ?? '9'));
}

export async function respondToVaccination(client: Client, id: string, response: VaccinationResponse): Promise<void> {
  await client.api(`/me/vaccination-campaigns/${enc(id)}/response`, { method: 'POST', body: { response } });
}

/* ====================================================================== E12 campaigns & wearables (§6) */

export const CAMPAIGN_RISKS = ['MSK', 'HEARING', 'METABOLIC', 'FATIGUE', 'MENTAL_WELLBEING'] as const;
export const SCREENING_TYPES = ['BIOMETRIC', 'MSK', 'HEARING', 'VISION', 'MENTAL_WELLBEING'] as const;
export const DEVICE_TYPES = ['PHONE', 'SMARTWATCH', 'FITNESS_TRACKER'] as const;
export type DeviceType = (typeof DEVICE_TYPES)[number];
export const WEARABLE_METRICS = ['STEPS', 'SLEEP_MINUTES', 'ACTIVE_MINUTES'] as const;
/** Risks where a wearable adds something (steps, sleep, active minutes) when the server does not flag invitations. */
const WEARABLE_RISKS = new Set(['METABOLIC', 'FATIGUE', 'MENTAL_WELLBEING']);

export interface CampaignInvitation {
  campaignId: string;
  title: string;
  risk?: string;
  objective?: string;
  startDate?: string;
  endDate?: string;
  wearable: boolean;
}

export interface ScreeningBooking {
  id: string;
  type?: string;
  siteCode?: string;
  date?: string;
  startsAt?: string;
}

export interface WearableEnrolment {
  id: string;
  campaignId: string;
  deviceType?: string;
  enrolledAt?: string;
}

export interface MyCampaigns {
  invitations: CampaignInvitation[];
  screenings: ScreeningBooking[];
  enrolments: WearableEnrolment[];
}

export function toInvitation(raw: unknown): CampaignInvitation | undefined {
  const r = rec(raw);
  const campaignId = r && (str(r.campaignId) ?? str(r.id));
  const title = r && str(r.title);
  if (!r || !campaignId || !title) return undefined;
  const risk = oneOf(CAMPAIGN_RISKS, r.risk);
  const flag = ['wearable', 'wearableProgramme', 'wearableEnabled'].find((k) => k in r);
  // budget, KPIs, owners and target units are programme data, not shown to the employee.
  return { campaignId, title, risk, objective: str(r.objective), startDate: str(r.startDate), endDate: str(r.endDate), wearable: flag ? bool(r[flag]) : !!risk && WEARABLE_RISKS.has(risk) };
}

export function toScreeningBooking(raw: unknown): ScreeningBooking | undefined {
  const r = rec(raw);
  const id = r && (str(r.id) ?? str(r.eventId));
  if (!r || !id) return undefined;
  return { id, type: oneOf(SCREENING_TYPES, r.type), siteCode: str(r.siteCode), date: str(r.date), startsAt: str(r.startsAt) ?? str(r.appointmentAt) };
}

export function toEnrolment(raw: unknown): WearableEnrolment | undefined {
  const r = rec(raw);
  const id = r && str(r.id);
  const campaignId = r && str(r.campaignId);
  if (!r || !id || !campaignId) return undefined;
  const status = upper(r.status);
  if (status === 'WITHDRAWN' || r.withdrawnAt) return undefined;
  return { id, campaignId, deviceType: str(r.deviceType), enrolledAt: str(r.enrolledAt) ?? str(r.createdAt) };
}

export function toMyCampaigns(body: unknown): MyCampaigns {
  const o = rec(body) ?? {};
  return {
    invitations: asList<unknown>(o.invitations ?? o.campaigns).map(toInvitation).filter((x): x is CampaignInvitation => !!x),
    screenings: asList<unknown>(o.screenings ?? o.screeningBookings ?? o.bookings)
      .map(toScreeningBooking)
      .filter((x): x is ScreeningBooking => !!x)
      .sort((a, b) => (a.startsAt ?? a.date ?? '').localeCompare(b.startsAt ?? b.date ?? '')),
    enrolments: asList<unknown>(o.enrolments ?? o.wearables).map(toEnrolment).filter((x): x is WearableEnrolment => !!x),
  };
}

export async function fetchMyCampaigns(client: Client): Promise<MyCampaigns> {
  return toMyCampaigns(await get(client, '/me/campaigns'));
}

export const WEARABLE_PURPOSE = 'WEARABLE_DATA';

/** An active WEARABLE_DATA consent, if the employee already gave one. */
export function activeWearableConsent(consents: readonly Consent[]): Consent | undefined {
  return consents.find((c) => c.purpose === WEARABLE_PURPOSE && !isWithdrawn(c));
}

/**
 * Enrol in the wearable programme of a campaign. Requires the explicit consent tick: an existing active WEARABLE_DATA
 * consent is reused, otherwise one is recorded first (`POST /consents`), then the enrolment references its id.
 */
export async function enrolWearable(client: Client, campaignId: string, deviceType: DeviceType, consentConfirmed: boolean): Promise<void> {
  if (!consentConfirmed) throw new Error('wearable consent not confirmed');
  let consentId = activeWearableConsent(await fetchMyConsents(client))?.id;
  if (!consentId) {
    const granted = rec(await client.api<unknown>('/consents', { method: 'POST', body: { purpose: WEARABLE_PURPOSE } }));
    consentId = str(granted?.id);
  }
  if (!consentId) throw new Error('wearable consent not recorded');
  await client.api('/me/wearables/enrolments', { method: 'POST', body: { campaignId, deviceType, consentId } });
}

/** Withdraw from the programme – the server stops accepting data and purges what was received. */
export async function withdrawWearable(client: Client, enrolmentId: string): Promise<void> {
  await client.api(`/me/wearables/enrolments/${enc(enrolmentId)}`, { method: 'DELETE' });
}

/* ====================================================================== home counts */

export interface WellbeingCounts {
  support: number;
  testing: number;
  checkIns: number;
  travel: number;
  campaigns: number;
}

/** Items needing an answer. Failures just hide a count. */
export async function loadWellbeingCounts(client: Client, now: Date = new Date()): Promise<Partial<WellbeingCounts>> {
  const [cism, testing, checkIns, advisories, vacc] = await Promise.allSettled([
    fetchCismOffers(client),
    fetchTestingNotices(client),
    fetchCheckIns(client, now),
    fetchAdvisories(client, now),
    fetchVaccinationCampaigns(client),
  ]);
  const counts: Partial<WellbeingCounts> = {};
  if (cism.status === 'fulfilled') counts.support = cism.value.filter((o) => !o.response).length;
  if (testing.status === 'fulfilled') counts.testing = testing.value.filter((n) => reportWindow(n, now).state === 'open').length;
  if (checkIns.status === 'fulfilled') counts.checkIns = checkIns.value.filter((c) => checkInWindow(c, now) === 'today').length;
  if (advisories.status === 'fulfilled') counts.travel = advisories.value.filter((a) => !a.acknowledgedAt).length;
  if (vacc.status === 'fulfilled') counts.campaigns = vacc.value.filter((c) => canRespondToCampaign(c, now)).length;
  return counts;
}
