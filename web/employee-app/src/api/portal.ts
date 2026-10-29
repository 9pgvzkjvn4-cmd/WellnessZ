import { ApiError, type AuthClient } from '../auth/AuthClient';
import { humanise } from '../components/format';
import { asList, type Consent } from './types';

/*
 * Increment 2 employee portal (docs/increment-02/API-Contract-E03-E05.md §3.2, §3.3, §5.3, §5.4).
 * Every response is projected onto the fields the screens render; anything else is dropped and never kept in UI state.
 */

type Client = Pick<AuthClient, 'api'>;
type Rec = Record<string, unknown>;

const str = (v: unknown) => (typeof v === 'string' && v ? v : typeof v === 'number' ? String(v) : undefined);
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() && Number.isFinite(Number(v)) ? Number(v) : undefined);
const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
const id = (r: Rec) => str(r.id);
const enc = encodeURIComponent;

/** Max length of the optional comments (RTW acknowledgement, job-offer reply). Not pinned by the contract. */
export const MAX_COMMENT = 1000;

/** List endpoints: a 404 (nothing yet / endpoint not deployed) is an empty list, like `GET /me/advice`. */
async function list(client: Client, path: string): Promise<unknown[]> {
  try {
    return asList<unknown>(await client.api<unknown>(path));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

const trimmed = (comment: string | undefined) => {
  const c = comment?.trim();
  return c ? c.slice(0, MAX_COMMENT) : undefined;
};

/* ---------- restrictions (functional wording only) ---------- */

export interface FunctionalRestriction {
  code?: string;
  /** Display text, e.g. "No lifting above 10 kg" (catalogue label with `{value}` substituted). */
  text: string;
}

export function toRestriction(raw: unknown): FunctionalRestriction | undefined {
  const r = rec(raw);
  if (!r) return typeof raw === 'string' && raw ? { text: raw } : undefined;
  const code = str(r.code);
  const value = str(r.value);
  const label = str(r.label);
  let text: string | undefined;
  if (label) text = label.includes('{value}') ? label.replace(/\{value\}/g, value ?? '…') : value && !label.includes(value) ? `${label} (${value})` : label;
  else if (code) text = value ? `${humanise(code)}: ${value}` : humanise(code);
  return text ? { code, text } : undefined;
}

const restrictions = (v: unknown) => (Array.isArray(v) ? v.map(toRestriction).filter((x): x is FunctionalRestriction => !!x) : []);

/* ---------- My documents (§3.2) ---------- */

/** Certificates and notes the employee confirms with `POST /me/documents/{id}/acknowledge` (E05-BL-003). */
export const ACKNOWLEDGEABLE_TYPES: ReadonlySet<string> = new Set([
  'FITNESS_CERTIFICATE',
  'RESTRICTION_NOTE',
  'FOOD_HANDLER_CLEARANCE',
  'CREW_MEDICAL_REPORT',
  'CABIN_CREW_MEDICAL_REPORT',
]);

export interface MyDocument {
  id: string;
  documentNumber?: string;
  type: string;
  title?: string;
  status?: string;
  version?: number;
  issuedAt?: string;
  acknowledgedAt?: string;
  contentType?: string;
  /** Server-side read marker if the backend provides one (not in the contract). */
  readAt?: string;
}

export function toMyDocument(raw: unknown): MyDocument | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  const docId = id(r);
  if (!docId) return undefined;
  const status = str(r.status)?.toUpperCase();
  if (status === 'REVOKED' || status === 'DRAFT') return undefined; // never shown to the employee
  return {
    id: docId,
    documentNumber: str(r.documentNumber),
    type: str(r.type)?.toUpperCase() ?? 'OTHER',
    title: str(r.title),
    status,
    version: num(r.version),
    issuedAt: str(r.signedAt) ?? str(r.sharedAt) ?? str(r.createdAt),
    acknowledgedAt: str(r.acknowledgedAt),
    contentType: str(r.contentType),
    readAt: str(r.readAt) ?? str(r.viewedAt),
  };
}

/** What the employee can do with a document in the list. RTW plans and job offers are answered on their own screens. */
export type DocumentAction = 'ACKNOWLEDGE' | 'RTW' | 'OFFER' | undefined;

export function documentAction(d: MyDocument): DocumentAction {
  if (d.status === 'SUPERSEDED') return undefined;
  if (d.type === 'RTW_PLAN') return 'RTW';
  if (d.type === 'JOB_OFFER') return 'OFFER';
  return ACKNOWLEDGEABLE_TYPES.has(d.type) && !d.acknowledgedAt ? 'ACKNOWLEDGE' : undefined;
}

export type DocumentState = 'ACKNOWLEDGED' | 'ACTION' | 'NEW' | 'READ' | 'SUPERSEDED';

/**
 * Badge state. "New" vs "read" uses the server marker when present, else the documents opened in this session
 * (kept in memory only – nothing is persisted on the device).
 */
export function documentState(d: MyDocument, opened: ReadonlySet<string> = new Set()): DocumentState {
  if (d.status === 'SUPERSEDED') return 'SUPERSEDED';
  if (d.acknowledgedAt) return 'ACKNOWLEDGED';
  if (documentAction(d) === 'ACKNOWLEDGE') return 'ACTION';
  return d.readAt || opened.has(d.id) ? 'READ' : 'NEW';
}

const STATE_ORDER: Record<DocumentState, number> = { ACTION: 0, NEW: 1, READ: 2, ACKNOWLEDGED: 2, SUPERSEDED: 3 };

/** Projection + order: documents needing acknowledgement first, then newest first. */
export function normaliseDocuments(body: unknown): MyDocument[] {
  return asList<unknown>(body)
    .map(toMyDocument)
    .filter((d): d is MyDocument => !!d)
    .sort((a, b) => STATE_ORDER[documentState(a)] - STATE_ORDER[documentState(b)] || (b.issuedAt ?? '').localeCompare(a.issuedAt ?? ''));
}

export async function fetchMyDocuments(client: Client): Promise<MyDocument[]> {
  return normaliseDocuments(await list(client, '/me/documents'));
}

/** `POST /me/documents/{id}/acknowledge` → acknowledgement timestamp (server value if returned, else now). */
export async function acknowledgeDocument(client: Client, docId: string, now: () => Date = () => new Date()): Promise<string> {
  const res = await client.api<unknown>(`/me/documents/${enc(docId)}/acknowledge`, { method: 'POST', body: {} });
  return str(rec(res)?.acknowledgedAt) ?? now().toISOString();
}

export const documentContentPath = (docId: string) => `/documents/${enc(docId)}/content`;

export interface OpenDeps {
  open: (url: string, target: string) => unknown;
  createObjectURL: (b: Blob) => string;
  revokeObjectURL: (url: string) => void;
  defer: (fn: () => void, ms: number) => unknown;
}

const browserDeps = (): OpenDeps => ({
  open: (u, t) => window.open(u, t, 'noopener'),
  createObjectURL: (b) => URL.createObjectURL(b),
  revokeObjectURL: (u) => URL.revokeObjectURL(u),
  defer: (fn, ms) => setTimeout(fn, ms),
});

const VIEWABLE = /^(application\/pdf|image\/(jpeg|png))\b/i;

/**
 * Native app: the content endpoint needs the bearer token, so the PDF is fetched as a blob and handed to the system
 * viewer through a short-lived object URL (revoked after a minute; nothing is written to the device).
 * Browser / PWA use the BFF link directly (`AuthClient.directUrl`).
 */
export async function openDocumentNative(client: Pick<AuthClient, 'download'>, docId: string, deps: OpenDeps = browserDeps()): Promise<void> {
  const blob = await client.download(documentContentPath(docId));
  const typed = blob.type && VIEWABLE.test(blob.type) ? blob : new Blob([blob], { type: 'application/pdf' });
  const url = deps.createObjectURL(typed);
  deps.open(url, '_blank');
  deps.defer(() => deps.revokeObjectURL(url), 60_000);
}

/* ---------- RTW plans (§5.3, E05-BL-007) ---------- */

export interface RtwPhase {
  fromWeek: number;
  toWeek: number;
  hoursPerDay?: number;
  duties?: string;
  restrictions: FunctionalRestriction[];
}

export interface RtwReview {
  id?: string;
  dueDate?: string;
  completedAt?: string;
}

export interface Contact {
  name?: string;
  email?: string;
  phone?: string;
}

export interface MyRtwPlan {
  id: string;
  caseRef?: string;
  status: string;
  startDate?: string;
  goal?: string;
  phases: RtwPhase[];
  reviews: RtwReview[];
  nextReviewDate?: string;
  employeeAck?: { at?: string; agree?: boolean; comment?: string };
  managerAckAt?: string;
  documentId?: string;
  contact?: Contact;
}

/**
 * The contract's `RtwPlan` has no contact field; a `contact{name,email,phone}` object or `contactName/contactEmail/
 * contactPhone` are used when the backend provides them (fallback in the UI: "Contact Occupational Health").
 */
export function toContact(r: Rec): Contact | undefined {
  const c = rec(r.contact) ?? rec(r.ameContact);
  const contact: Contact = {
    name: str(c?.name) ?? str(r.contactName),
    email: str(c?.email) ?? str(r.contactEmail),
    phone: str(c?.phone) ?? str(r.contactPhone),
  };
  return contact.name || contact.email || contact.phone ? contact : undefined;
}

export function toRtwPlan(raw: unknown): MyRtwPlan | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  const planId = id(r);
  if (!planId) return undefined;
  const phases = (Array.isArray(r.phases) ? r.phases : [])
    .map((p): RtwPhase | undefined => {
      const ph = rec(p);
      if (!ph) return undefined;
      const fromWeek = num(ph.fromWeek) ?? 1;
      return { fromWeek, toWeek: num(ph.toWeek) ?? fromWeek, hoursPerDay: num(ph.hoursPerDay), duties: str(ph.duties), restrictions: restrictions(ph.restrictions) };
    })
    .filter((p): p is RtwPhase => !!p)
    .sort((a, b) => a.fromWeek - b.fromWeek);
  const reviews = (Array.isArray(r.reviews) ? r.reviews : [])
    .map(rec)
    .filter((x): x is Rec => !!x)
    .map((x) => ({ id: str(x.id), dueDate: str(x.dueDate), completedAt: str(x.completedAt) }))
    .sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));
  // Older payloads may send reviewDates[] instead of reviews[].
  if (!reviews.length && Array.isArray(r.reviewDates)) {
    for (const d of r.reviewDates) if (str(d)) reviews.push({ id: undefined, dueDate: str(d), completedAt: undefined });
  }
  const ack = rec(r.employeeAck);
  const mgr = rec(r.managerAck);
  return {
    id: planId,
    caseRef: str(r.caseRef),
    status: str(r.status)?.toUpperCase() ?? 'ACTIVE',
    startDate: str(r.startDate),
    goal: str(r.goal),
    phases,
    reviews,
    nextReviewDate: str(r.nextReviewDate) ?? reviews.find((x) => !x.completedAt)?.dueDate,
    employeeAck: ack && (str(ack.at) || ack.agree !== undefined) ? { at: str(ack.at), agree: ack.agree === true, comment: str(ack.comment) } : undefined,
    managerAckAt: str(mgr?.at),
    documentId: str(r.documentId),
    contact: toContact(r),
  };
}

export async function fetchMyRtwPlans(client: Client): Promise<MyRtwPlan[]> {
  return (await list(client, '/me/rtw-plans'))
    .map(toRtwPlan)
    .filter((p): p is MyRtwPlan => !!p)
    .sort((a, b) => Number(!!a.employeeAck) - Number(!!b.employeeAck) || (b.startDate ?? '').localeCompare(a.startDate ?? ''));
}

/** `POST /me/rtw-plans/{id}/acknowledge {agree:true, comment?}` – the contract only allows agreement (see README). */
export async function acknowledgeRtwPlan(client: Client, planId: string, comment?: string): Promise<void> {
  const body: { agree: true; comment?: string } = { agree: true };
  const c = trimmed(comment);
  if (c) body.comment = c;
  await client.api(`/me/rtw-plans/${enc(planId)}/acknowledge`, { method: 'POST', body });
}

/* ---------- Job offers (§5.3, E05-BL-008) ---------- */

export interface MyJobOffer {
  id: string;
  jobTitle?: string;
  jobCode?: string;
  duties?: string;
  location?: string;
  shift?: string;
  hoursPerWeek?: number;
  payBasis?: string;
  payNote?: string;
  restrictions: FunctionalRestriction[];
  startDate?: string;
  endDate?: string;
  respondBy?: string;
  contact?: Contact;
  status: string;
  issuedAt?: string;
  respondedAt?: string;
  comment?: string;
  documentId?: string;
}

export function toJobOffer(raw: unknown): MyJobOffer | undefined {
  const r = rec(raw);
  if (!r) return undefined;
  const offerId = id(r);
  if (!offerId) return undefined;
  return {
    id: offerId,
    jobTitle: str(r.jobTitle),
    jobCode: str(r.jobCode),
    duties: str(r.duties),
    location: str(r.location),
    shift: str(r.shift),
    hoursPerWeek: num(r.hoursPerWeek),
    payBasis: str(r.payBasis)?.toUpperCase(),
    payNote: str(r.payNote),
    restrictions: restrictions(r.restrictions),
    startDate: str(r.startDate),
    endDate: str(r.endDate),
    respondBy: str(r.respondBy),
    contact: toContact(r),
    status: str(r.status)?.toUpperCase() ?? 'ISSUED',
    issuedAt: str(r.issuedAt),
    respondedAt: str(r.respondedAt),
    comment: str(r.comment),
    documentId: str(r.documentId),
  };
}

export const canRespond = (o: MyJobOffer) => o.status === 'ISSUED' && !o.respondedAt;

/** `respondBy` may be a date or a timestamp; a date counts until the end of that day (local time). */
export function respondByPassed(respondBy: string | undefined, now: Date = new Date()): boolean {
  if (!respondBy) return false;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(respondBy);
  const end = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59) : new Date(respondBy);
  return !Number.isNaN(end.getTime()) && end.getTime() < now.getTime();
}

export async function fetchMyJobOffers(client: Client): Promise<MyJobOffer[]> {
  return (await list(client, '/me/job-offers'))
    .map(toJobOffer)
    .filter((o): o is MyJobOffer => !!o)
    .sort((a, b) => Number(canRespond(b)) - Number(canRespond(a)) || (b.issuedAt ?? '').localeCompare(a.issuedAt ?? ''));
}

/** `POST /me/job-offers/{id}/respond {accept, comment?}` → updated offer when returned. */
export async function respondToOffer(client: Client, offerId: string, accept: boolean, comment?: string): Promise<MyJobOffer | undefined> {
  const body: { accept: boolean; comment?: string } = { accept };
  const c = trimmed(comment);
  if (c) body.comment = c;
  return toJobOffer(await client.api<unknown>(`/me/job-offers/${enc(offerId)}/respond`, { method: 'POST', body }));
}

/* ---------- Home: pending items ---------- */

export interface PortalCounts {
  documents: number;
  rtw: number;
  offers: number;
}

/**
 * Items waiting for the employee: certificates/notes to acknowledge, active RTW plans not yet agreed, open job offers.
 * Each list is optional – a failing endpoint just shows no count.
 */
export async function loadPortalCounts(client: Client): Promise<Partial<PortalCounts>> {
  const [docs, plans, offers] = await Promise.allSettled([fetchMyDocuments(client), fetchMyRtwPlans(client), fetchMyJobOffers(client)]);
  const counts: Partial<PortalCounts> = {};
  if (docs.status === 'fulfilled') counts.documents = docs.value.filter((d) => documentState(d) === 'ACTION').length;
  if (plans.status === 'fulfilled') counts.rtw = plans.value.filter((p) => p.status === 'ACTIVE' && !p.employeeAck).length;
  if (offers.status === 'fulfilled') counts.offers = offers.value.filter(canRespond).length;
  return counts;
}

/* ---------- Disclosure log & consent withdrawal (§3.3, E03-BL-022) ---------- */

export interface Disclosure {
  key: string;
  at?: string;
  recipientType?: string;
  recipientName?: string;
  documentTitle?: string;
  purpose?: string;
  consentId?: string;
  status: 'ACTIVE' | 'REVOKED' | string;
}

export function normaliseDisclosures(body: unknown): Disclosure[] {
  return asList<unknown>(body)
    .map((raw, i): Disclosure | undefined => {
      const r = rec(raw);
      if (!r) return undefined;
      return {
        // Rows have no id in the contract: build a stable key from the row content.
        key: str(r.id) ?? `${str(r.at) ?? ''}|${str(r.recipientName) ?? ''}|${str(r.documentTitle) ?? ''}|${i}`,
        at: str(r.at),
        recipientType: str(r.recipientType)?.toUpperCase(),
        recipientName: str(r.recipientName),
        documentTitle: str(r.documentTitle),
        purpose: str(r.purpose),
        consentId: str(r.consentId),
        status: str(r.status)?.toUpperCase() ?? 'ACTIVE',
      };
    })
    .filter((d): d is Disclosure => !!d)
    .sort((a, b) => (b.at ?? '').localeCompare(a.at ?? ''));
}

export async function fetchMyDisclosures(client: Client): Promise<Disclosure[]> {
  return normaliseDisclosures(await list(client, '/me/disclosures'));
}

export const isWithdrawn = (c: Consent) => c.status.toUpperCase() === 'WITHDRAWN' || !!c.withdrawnAt;

export async function fetchMyConsents(client: Client): Promise<Consent[]> {
  return asList<Consent>(await client.api<unknown>('/consents/me')).filter((c) => !!c && typeof c.id === 'string' && typeof c.status === 'string');
}

/** `DELETE /consents/{id}` – also revokes partner shares made under that consent (written to the disclosure log). */
export async function withdrawConsent(client: Client, consentId: string): Promise<void> {
  await client.api(`/consents/${enc(consentId)}`, { method: 'DELETE' });
}

/* ---------- Crew declaration outcome (§5.4, E05-BL-010) ---------- */

export interface DeclarationOutcome {
  caseRef?: string;
  nextSteps: string[];
  ameContact?: Contact & { hours?: string; text?: string };
  acknowledgementDueBy?: string;
}

/**
 * `nextSteps` / `ameContact` types are not pinned: steps may be strings or `{text|title|description}` objects;
 * the contact may be a string or `{name, phone, email, hours}`.
 */
export function toDeclarationOutcome(raw: unknown): DeclarationOutcome {
  const r = rec(raw) ?? {};
  const nextSteps = (Array.isArray(r.nextSteps) ? r.nextSteps : [])
    .map((s) => {
      const o = rec(s);
      return o ? (str(o.text) ?? str(o.title) ?? str(o.description) ?? str(o.label)) : str(s);
    })
    .filter((s): s is string => !!s);
  let ameContact: DeclarationOutcome['ameContact'];
  const c = rec(r.ameContact);
  if (c) {
    ameContact = { name: str(c.name), phone: str(c.phone), email: str(c.email), hours: str(c.hours) ?? str(c.availability) };
    if (!ameContact.name && !ameContact.phone && !ameContact.email) ameContact = ameContact.hours ? ameContact : undefined;
  } else if (str(r.ameContact)) {
    ameContact = { text: str(r.ameContact) };
  }
  return { caseRef: str(r.caseRef), nextSteps, ameContact, acknowledgementDueBy: str(r.acknowledgementDueBy) };
}

/** Phone link target: digits and a leading + only. */
export const telHref = (phone: string) => `tel:${phone.replace(/[^\d+]/g, '')}`;
