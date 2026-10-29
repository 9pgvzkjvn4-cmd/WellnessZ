/**
 * Increment 3 – E06-F05 management advice requests (docs/increment-03/API-Contract-E06-E07-E14.md §3.5).
 *
 * The requester sees status, dates and – only once the employee has consented (RELEASED) – the functional OH advice
 * report. A WITHHELD request shows its status only. As defence in depth the report is dropped for every status other
 * than RELEASED and only the whitelisted report fields are copied. The request `context` is never read back.
 */
import { list, s } from './fitness';
import type { EmployeeLookup, FieldErrors } from './referrals';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;

/* ---------------- question library ---------------- */

export interface AdviceQuestion {
  code: string;
  text: string;
}

export function toAdviceQuestions(body: unknown): AdviceQuestion[] {
  const seen = new Set<string>();
  return list(body)
    .map((raw) => {
      const r = obj(raw);
      const code = s(r?.code);
      if (!r || !code || seen.has(code)) return undefined;
      seen.add(code);
      return { code, text: s(r.text) ?? code };
    })
    .filter(defined);
}

/* ---------------- request form ---------------- */

export const ADVICE_CONTEXT_MAX = 2000;
export const ADVICE_REQUEST_FIELDS = ['employeeId', 'questionCodes', 'context', 'episodeId'] as const;

export interface AdviceRequestForm {
  employee?: EmployeeLookup;
  questionCodes: string[];
  context: string;
}
export const emptyAdviceRequestForm = (): AdviceRequestForm => ({ questionCodes: [], context: '' });

export function validateAdviceRequest(f: AdviceRequestForm, questions: readonly AdviceQuestion[]): FieldErrors {
  const e: FieldErrors = {};
  if (!f.employee) e.employeeId = 'Please choose the employee.';
  const known = new Set(questions.map((q) => q.code));
  if (f.questionCodes.length === 0) e.questionCodes = 'Please choose at least one question for Occupational Health.';
  else if (f.questionCodes.some((c) => !known.has(c))) e.questionCodes = 'Please choose questions from the list only.';
  if (f.context.length > ADVICE_CONTEXT_MAX) e.context = `Please use at most ${ADVICE_CONTEXT_MAX} characters.`;
  return e;
}

/** `POST /workspace/advice-requests` body – questions in library order, context only when given. */
export function buildAdviceRequest(
  f: AdviceRequestForm,
  questions: readonly AdviceQuestion[],
): { employeeId: string; questionCodes: string[]; context?: string } {
  const selected = new Set(f.questionCodes);
  const body: { employeeId: string; questionCodes: string[]; context?: string } = {
    employeeId: f.employee!.employeeId,
    questionCodes: questions.filter((q) => selected.has(q.code)).map((q) => q.code),
  };
  if (f.context.trim()) body.context = f.context.trim();
  return body;
}

/* ---------------- requests & released reports ---------------- */

export const ADVICE_STATUSES = ['SUBMITTED', 'ACCEPTED', 'REPORT_DRAFTED', 'AWAITING_EMPLOYEE', 'RELEASED', 'WITHHELD', 'WITHDRAWN'] as const;
export const ADVICE_STATUS_LABELS: Record<(typeof ADVICE_STATUSES)[number], string> = {
  SUBMITTED: 'Submitted',
  ACCEPTED: 'Accepted by OH',
  REPORT_DRAFTED: 'Report in preparation',
  AWAITING_EMPLOYEE: 'Awaiting employee review',
  RELEASED: 'Released',
  WITHHELD: 'Withheld',
  WITHDRAWN: 'Withdrawn',
};
export const adviceStatusLabel = (st: string) => ADVICE_STATUS_LABELS[st as keyof typeof ADVICE_STATUS_LABELS] ?? st;

/** Statuses in which the requester may still withdraw (no outcome yet). */
const WITHDRAWABLE = new Set(['SUBMITTED', 'ACCEPTED', 'REPORT_DRAFTED', 'AWAITING_EMPLOYEE']);

export const FITNESS_LABELS: Record<string, string> = {
  FIT: 'Fit for work',
  FIT_WITH_ADJUSTMENTS: 'Fit with adjustments',
  TEMPORARILY_UNFIT: 'Temporarily unfit',
  UNFIT_LONG_TERM: 'Unfit long term',
};
export const PROGNOSIS_LABELS: Record<string, string> = {
  RTW_WITHIN_2_WEEKS: 'Return within 2 weeks expected',
  RTW_WITHIN_4_WEEKS: 'Return within 4 weeks expected',
  RTW_WITHIN_12_WEEKS: 'Return within 12 weeks expected',
  UNCERTAIN: 'Uncertain',
  RTW_UNLIKELY: 'Return unlikely',
};

export interface AdviceReport {
  fitness?: string;
  adjustments: string[];
  restrictions: string[];
  prognosis?: string;
  expectedReturn?: string;
  reviewDate?: string;
  /** In the order of the request's questions where possible. */
  answers: { code: string; text: string }[];
  employeeComment?: string;
  documentId?: string;
  releasedAt?: string;
}

export interface AdviceRequest {
  id: string;
  ref?: string;
  employeeId?: string;
  displayName: string;
  status: string;
  questionCodes: string[];
  requestedBy?: string;
  requestedAt?: string;
  slaDueAt?: string;
  caseRef?: string;
  report?: AdviceReport;
}

/** Text or a list of texts / `{label|text}` objects (the report contract leaves the shape open) → lines. */
function textLines(v: unknown): string[] {
  if (typeof v === 'string') return v.trim() ? [v.trim()] : [];
  if (!Array.isArray(v)) return [];
  return v
    .map((x) => {
      if (typeof x === 'string') return x.trim() || undefined;
      const o = obj(x);
      return s(o?.label) ?? s(o?.text);
    })
    .filter(defined);
}

export function toAdviceReport(v: unknown, questionCodes: readonly string[] = []): AdviceReport | undefined {
  const r = obj(v);
  if (!r) return undefined;
  const a = obj(r.answers) ?? {};
  const codes = [...questionCodes.filter((c) => c in a), ...Object.keys(a).filter((c) => !questionCodes.includes(c))];
  return {
    fitness: s(r.fitness),
    adjustments: textLines(r.adjustments),
    restrictions: textLines(r.restrictions),
    prognosis: s(r.prognosis),
    expectedReturn: s(r.expectedReturn),
    reviewDate: s(r.reviewDate),
    answers: codes.map((code) => ({ code, text: s(a[code]) ?? '' })).filter((x) => x.text),
    employeeComment: s(r.employeeComment),
    documentId: s(r.documentId),
    releasedAt: s(r.releasedAt),
  };
}

export function toAdviceRequests(body: unknown): AdviceRequest[] {
  return list(body)
    .map((raw): AdviceRequest | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const status = s(r?.status);
      if (!r || !id || !status) return undefined;
      const questionCodes = Array.isArray(r.questionCodes) ? r.questionCodes.map(s).filter(defined) : [];
      return {
        id,
        ref: s(r.ref),
        employeeId: s(r.employeeId),
        displayName: s(r.displayName) ?? s(r.employeeId) ?? '–',
        status,
        questionCodes,
        requestedBy: s(r.requestedBy),
        requestedAt: s(r.requestedAt),
        slaDueAt: s(r.slaDueAt),
        caseRef: s(r.caseRef),
        report: status === 'RELEASED' ? toAdviceReport(r.report, questionCodes) : undefined,
      };
    })
    .filter(defined)
    .sort((a, b) => (b.requestedAt ?? '').localeCompare(a.requestedAt ?? ''));
}

export const canWithdrawAdvice = (r: Pick<AdviceRequest, 'status'>) => WITHDRAWABLE.has(r.status);

/** SLA overdue: still open and the due time has passed. */
export function isSlaOverdue(r: Pick<AdviceRequest, 'status' | 'slaDueAt'>, now = new Date()): boolean {
  if (!r.slaDueAt || !WITHDRAWABLE.has(r.status)) return false;
  const due = new Date(r.slaDueAt);
  return !Number.isNaN(due.getTime()) && due.getTime() < now.getTime();
}

export const adviceWithdrawPath = (id: string) => `/workspace/advice-requests/${encodeURIComponent(id)}/withdraw`;

export const questionText = (questions: readonly AdviceQuestion[], code: string) => questions.find((q) => q.code === code)?.text ?? code;
