/**
 * Partner referral inbox – API contract E04-E08 §3.2 (PARTNER_PROVIDER, own organisation only).
 *   GET  /partner/referrals?status=
 *   POST /partner/referrals/{id}/accept · /decline {reason}
 *   POST /partner/referrals/{id}/appointment {appointmentAt, location?, instructions? (screened)}
 *   POST /partner/referrals/{id}/seen
 *   POST /partner/referrals/{id}/report   multipart file (+ summary?)
 *   POST /partner/referrals/{id}/invoice  multipart file + invoiceNumber, amount, currency, serviceDate
 * There is no single-referral GET for partners: the detail view reads the list and picks the id (absent → 404 text).
 */
import type { AuthClient } from '../auth/AuthClient';
import { humanise, isValidDate, localDate, localInputToIso, parseAmount, type Money } from '../components/format';
import { S } from '../strings';
import { displayValue, mapList, rec, str, type Rec } from './read';
import { seg, submit, type ActionResult, type FieldErrors } from './errors';
import { validateUpload, type UploadCandidate } from './upload';

export const REFERRAL_STATUSES = ['SENT', 'ACCEPTED', 'SCHEDULED', 'SEEN', 'REPORT_RECEIVED', 'DECLINED', 'CLOSED', 'CANCELLED'] as const;
export const referralStatusLabel = (s: string) => S.labels.referralStatus[s] ?? humanise(s);

/** Statuses after which a report is no longer awaited. */
const REPORT_DONE = new Set(['REPORT_RECEIVED', 'CLOSED', 'CANCELLED', 'DECLINED', 'DRAFT']);

export interface ReferralDocument {
  documentId: string;
  title: string;
}

export interface HistoryEntry {
  status: string;
  at: string;
  by?: string;
}

export interface PartnerReferral {
  id: string;
  ref: string;
  serviceName: string;
  urgency: string;
  status: string;
  sentAt?: string;
  appointmentAt?: string;
  reportDueAt?: string;
  /** Server flag when provided; otherwise derived from `reportDueAt`. */
  overdueFlag?: boolean;
  patient: { displayName: string; employeeId?: string; phone?: string };
  reason?: string;
  question?: string;
  dataSet: { key: string; value: string }[];
  letterDocumentId?: string;
  attachments: ReferralDocument[];
  history: HistoryEntry[];
}

function toReferral(r: Rec): PartnerReferral | undefined {
  const id = str(r.id);
  if (!id) return undefined;
  const p = rec(r.patient) ?? {};
  const ds = rec(r.dataSet) ?? {};
  return {
    id,
    ref: str(r.ref) ?? id,
    serviceName: str(r.serviceName) ?? str(r.serviceCode) ?? '',
    urgency: str(r.urgency) ?? 'ROUTINE',
    status: str(r.status) ?? 'SENT',
    sentAt: str(r.sentAt),
    appointmentAt: str(r.appointmentAt),
    reportDueAt: str(r.reportDueAt),
    overdueFlag: typeof r.overdue === 'boolean' ? r.overdue : undefined,
    patient: { displayName: str(p.displayName) ?? str(p.employeeId) ?? '', employeeId: str(p.employeeId), phone: str(p.phone) },
    reason: str(r.reason),
    question: str(r.question),
    dataSet: Object.entries(ds)
      .map(([key, v]) => ({ key, value: displayValue(v) }))
      .filter((e): e is { key: string; value: string } => e.value !== undefined && e.value !== ''),
    letterDocumentId: str(r.letterDocumentId),
    attachments: mapList(r.attachments, (a) => {
      const documentId = str(a.documentId);
      return documentId ? { documentId, title: str(a.title) ?? documentId } : undefined;
    }),
    history: mapList(r.history, (h) => {
      const status = str(h.status);
      const at = str(h.at);
      return status && at ? { status, at, by: str(h.by) } : undefined;
    }).sort((a, b) => a.at.localeCompare(b.at)),
  };
}

/** `GET /partner/referrals` body → referrals (whitelisted fields only). */
export const toPartnerReferrals = (body: unknown): PartnerReferral[] => mapList(body, toReferral);

export function isOverdue(r: Pick<PartnerReferral, 'overdueFlag' | 'reportDueAt' | 'status'>, now: Date = new Date()): boolean {
  if (r.overdueFlag !== undefined) return r.overdueFlag && !REPORT_DONE.has(r.status);
  if (!r.reportDueAt || REPORT_DONE.has(r.status)) return false;
  const due = new Date(r.reportDueAt).getTime();
  return !Number.isNaN(due) && due < now.getTime();
}

/** Report deadline state for badges: overdue, due within 48 h, or neither. */
export function reportDueState(r: PartnerReferral, now: Date = new Date()): 'overdue' | 'due-soon' | 'none' {
  if (isOverdue(r, now)) return 'overdue';
  if (!r.reportDueAt || REPORT_DONE.has(r.status)) return 'none';
  const due = new Date(r.reportDueAt).getTime();
  return !Number.isNaN(due) && due - now.getTime() <= 48 * 3600_000 ? 'due-soon' : 'none';
}

/** Inbox order: urgent first, then overdue, then by report due date / sent date. */
export function sortInbox(list: readonly PartnerReferral[], now: Date = new Date()): PartnerReferral[] {
  const key = (r: PartnerReferral) => r.reportDueAt ?? r.sentAt ?? '';
  return [...list].sort(
    (a, b) =>
      Number(b.urgency === 'URGENT') - Number(a.urgency === 'URGENT') ||
      Number(isOverdue(b, now)) - Number(isOverdue(a, now)) ||
      key(a).localeCompare(key(b)),
  );
}

export interface ReferralActions {
  accept: boolean;
  decline: boolean;
  appointment: boolean;
  seen: boolean;
  report: boolean;
  invoice: boolean;
}

/** Which partner actions the referral's status allows (UX guard – the server enforces the transitions). */
export function referralActions(status: string): ReferralActions {
  return {
    accept: status === 'SENT',
    decline: status === 'SENT' || status === 'ACCEPTED',
    appointment: status === 'ACCEPTED' || status === 'SCHEDULED',
    seen: status === 'SCHEDULED',
    report: status === 'SCHEDULED' || status === 'SEEN',
    invoice: status === 'SEEN' || status === 'REPORT_RECEIVED' || status === 'CLOSED',
  };
}


/* ------------------------------------------------------------------ actions */

const base = (id: string) => `/partner/referrals/${seg(id)}`;

export const APPOINTMENT_FIELDS = ['appointmentAt', 'location', 'instructions'] as const;
export const DECLINE_FIELDS = ['reason'] as const;
export const REPORT_FIELDS = ['file', 'summary'] as const;
export const INVOICE_FIELDS = ['file', 'invoiceNumber', 'amount', 'currency', 'serviceDate'] as const;

type Client = Pick<AuthClient, 'api'>;

export const acceptReferral = (c: Client, id: string, failed: string) => submit(c, `${base(id)}/accept`, undefined, failed);
export const declineReferral = (c: Client, id: string, reason: string, failed: string) => submit(c, `${base(id)}/decline`, { reason }, failed, DECLINE_FIELDS);
export const markSeen = (c: Client, id: string, failed: string) => submit(c, `${base(id)}/seen`, undefined, failed);

export interface AppointmentRequest {
  appointmentAt: string;
  location?: string;
  instructions?: string;
}
export const setAppointment = (c: Client, id: string, body: AppointmentRequest, failed: string) =>
  submit(c, `${base(id)}/appointment`, body, failed, APPOINTMENT_FIELDS);

export function reportForm(file: Blob & { name?: string }, summary: string): FormData {
  const fd = new FormData();
  fd.append('file', file, file.name);
  if (summary.trim()) fd.append('summary', summary.trim());
  return fd;
}
export const uploadReport = (c: Client, id: string, form: FormData, failed: string): Promise<ActionResult> => submit(c, `${base(id)}/report`, form, failed, REPORT_FIELDS);

export interface InvoiceRequest {
  invoiceNumber: string;
  amount: Money;
  serviceDate: string;
}
export function invoiceForm(file: Blob & { name?: string }, inv: InvoiceRequest): FormData {
  const fd = new FormData();
  fd.append('file', file, file.name);
  fd.append('invoiceNumber', inv.invoiceNumber.trim());
  fd.append('amount', inv.amount.amount);
  fd.append('currency', inv.amount.currency);
  fd.append('serviceDate', inv.serviceDate);
  return fd;
}
export const uploadInvoice = (c: Client, id: string, form: FormData, failed: string): Promise<ActionResult> => submit(c, `${base(id)}/invoice`, form, failed, INVOICE_FIELDS);

/* ------------------------------------------------------------------ client-side validation */

export const DECLINE_REASON_MAX = 1000;
export const LOCATION_MAX = 200;
export const INSTRUCTIONS_MAX = 1000;
export const SUMMARY_MAX = 2000;
export const INVOICE_NUMBER_MAX = 64;

export interface AppointmentForm {
  appointmentAt: string;
  location: string;
  instructions: string;
}

/** `appointmentAt` is the datetime-local value (viewer's time) → sent as ISO-8601 UTC; must lie in the future. */
export function buildAppointment(f: AppointmentForm, now: Date = new Date()): { body?: AppointmentRequest; errors: FieldErrors } {
  const errors: FieldErrors = {};
  const iso = f.appointmentAt ? localInputToIso(f.appointmentAt) : undefined;
  if (!f.appointmentAt) errors.appointmentAt = S.validation.required;
  else if (!iso) errors.appointmentAt = S.validation.dateTime;
  else if (new Date(iso).getTime() <= now.getTime()) errors.appointmentAt = S.validation.dateTimePast;
  if (f.location.length > LOCATION_MAX) errors.location = S.validation.maxLength(LOCATION_MAX);
  if (f.instructions.length > INSTRUCTIONS_MAX) errors.instructions = S.validation.maxLength(INSTRUCTIONS_MAX);
  if (Object.keys(errors).length || !iso) return { errors };
  const body: AppointmentRequest = { appointmentAt: iso };
  if (f.location.trim()) body.location = f.location.trim();
  if (f.instructions.trim()) body.instructions = f.instructions.trim();
  return { body, errors };
}

export function validateDecline(reason: string): FieldErrors {
  if (!reason.trim()) return { reason: S.validation.required };
  if (reason.length > DECLINE_REASON_MAX) return { reason: S.validation.maxLength(DECLINE_REASON_MAX) };
  return {};
}

export function validateReport(file: UploadCandidate | undefined, summary: string): FieldErrors {
  const errors: FieldErrors = {};
  const fileError = validateUpload(file);
  if (fileError) errors.file = fileError;
  if (summary.length > SUMMARY_MAX) errors.summary = S.validation.maxLength(SUMMARY_MAX);
  return errors;
}

export interface InvoiceForm {
  invoiceNumber: string;
  amount: string;
  currency: string;
  serviceDate: string;
}

/** Invoice metadata: number, positive amount (≤ 2 decimals), ISO currency and a service date not in the future. */
export function buildInvoice(file: UploadCandidate | undefined, f: InvoiceForm, now: Date = new Date()): { request?: InvoiceRequest; errors: FieldErrors } {
  const errors: FieldErrors = {};
  const fileError = validateUpload(file);
  if (fileError) errors.file = fileError;
  if (!f.invoiceNumber.trim()) errors.invoiceNumber = S.validation.required;
  else if (f.invoiceNumber.length > INVOICE_NUMBER_MAX) errors.invoiceNumber = S.validation.maxLength(INVOICE_NUMBER_MAX);
  const amount = parseAmount(f.amount);
  if (!amount) errors.amount = S.validation.amount;
  if (!/^[A-Z]{3}$/.test(f.currency)) errors.currency = S.validation.currency;
  if (!f.serviceDate) errors.serviceDate = S.validation.required;
  else if (!isValidDate(f.serviceDate)) errors.serviceDate = S.validation.date;
  else if (f.serviceDate > localDate(now)) errors.serviceDate = S.validation.dateFuture;
  if (Object.keys(errors).length || !amount) return { errors };
  return { request: { invoiceNumber: f.invoiceNumber.trim(), amount: { amount, currency: f.currency }, serviceDate: f.serviceDate }, errors };
}

/** Detail read: the partner list (own organisation only) filtered to `id`; undefined when not listed. */
export async function loadReferral(c: Client, id: string): Promise<PartnerReferral | undefined> {
  return toPartnerReferrals(await c.api<unknown>('/partner/referrals')).find((r) => r.id === id);
}
