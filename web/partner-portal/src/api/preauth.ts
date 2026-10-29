/**
 * Pre-authorisation decisions by the payer's adjuster – contract §3.4:
 *   GET  /partner/preauthorisations?status=          own payer only; `justification` only via the shared document
 *   POST /partner/preauthorisations/{id}/decision    {status APPROVED|PARTIALLY_APPROVED|DECLINED, approvalRef?, approvedAmount?, validUntil?, note?}
 * `approvedAmount` uses the contract's money shape `{amount, currency}` (currency of the estimate).
 */
import type { AuthClient } from '../auth/AuthClient';
import { compareAmounts, isValidDate, localDate, parseAmount, toMoney, type Money } from '../components/format';
import { S } from '../strings';
import { mapList, str } from './read';
import { seg, submit, type ActionResult, type FieldErrors } from './errors';

export const PREAUTH_STATUSES = ['SUBMITTED', 'APPROVED', 'PARTIALLY_APPROVED', 'DECLINED', 'EXPIRED', 'CANCELLED'] as const;
export const DECISIONS = ['APPROVED', 'PARTIALLY_APPROVED', 'DECLINED'] as const;
export type Decision = (typeof DECISIONS)[number];
export const DECISION_FIELDS = ['status', 'approvalRef', 'approvedAmount', 'validUntil', 'note'] as const;
export const NOTE_MAX = 1000;
export const APPROVAL_REF_MAX = 64;

export interface Preauth {
  id: string;
  ref: string;
  caseRef?: string;
  displayName?: string;
  serviceCode?: string;
  providerCode?: string;
  providerName?: string;
  estimate?: Money;
  status: string;
  submittedAt?: string;
  slaDueAt?: string;
  decidedAt?: string;
  approvalRef?: string;
  approvedAmount?: Money;
  validUntil?: string;
  documentId?: string;
}

export const toPreauths = (body: unknown): Preauth[] =>
  mapList(body, (r) => {
    const id = str(r.id);
    if (!id) return undefined;
    return {
      id,
      ref: str(r.ref) ?? id,
      caseRef: str(r.caseRef),
      displayName: str(r.displayName),
      serviceCode: str(r.serviceCode),
      providerCode: str(r.providerCode),
      providerName: str(r.providerName),
      estimate: toMoney(r.estimate),
      status: str(r.status) ?? 'SUBMITTED',
      submittedAt: str(r.submittedAt),
      slaDueAt: str(r.slaDueAt),
      decidedAt: str(r.decidedAt),
      approvalRef: str(r.approvalRef),
      approvedAmount: toMoney(r.approvedAmount),
      validUntil: str(r.validUntil),
      documentId: str(r.documentId),
    };
  }).sort((a, b) => (a.slaDueAt ?? a.submittedAt ?? '').localeCompare(b.slaDueAt ?? b.submittedAt ?? ''));

export const isDecidable = (p: Preauth) => p.status === 'SUBMITTED';
export const isSlaOverdue = (p: Preauth, now: Date = new Date()) =>
  isDecidable(p) && !!p.slaDueAt && !Number.isNaN(new Date(p.slaDueAt).getTime()) && new Date(p.slaDueAt).getTime() < now.getTime();

export interface DecisionForm {
  status: Decision | '';
  approvalRef: string;
  amount: string;
  validUntil: string;
  note: string;
}

export interface DecisionBody {
  status: Decision;
  approvalRef?: string;
  approvedAmount?: Money;
  validUntil?: string;
  note?: string;
}

/** Client-side validation + request body. Partial approval needs an amount below the estimate. */
export function buildDecision(p: Preauth, f: DecisionForm, now: Date = new Date()): { body?: DecisionBody; errors: FieldErrors } {
  const errors: FieldErrors = {};
  if (!f.status) errors.status = S.validation.required;
  let approvedAmount: Money | undefined;
  if (f.status === 'PARTIALLY_APPROVED') {
    const amount = parseAmount(f.amount);
    if (!amount) errors.approvedAmount = S.validation.amount;
    else if (p.estimate && compareAmounts(amount, p.estimate.amount) >= 0) errors.approvedAmount = S.validation.amountAbove;
    else approvedAmount = { amount, currency: p.estimate?.currency ?? 'GBP' };
  }
  if (f.validUntil && f.status !== 'DECLINED') {
    if (!isValidDate(f.validUntil)) errors.validUntil = S.validation.date;
    else if (f.validUntil < localDate(now)) errors.validUntil = S.validation.datePast;
  }
  if (f.approvalRef.length > APPROVAL_REF_MAX) errors.approvalRef = S.validation.maxLength(APPROVAL_REF_MAX);
  if (f.note.length > NOTE_MAX) errors.note = S.validation.maxLength(NOTE_MAX);
  if (Object.keys(errors).length || !f.status) return { errors };
  const approved = f.status !== 'DECLINED';
  const body: DecisionBody = { status: f.status };
  if (approved && f.approvalRef.trim()) body.approvalRef = f.approvalRef.trim();
  if (approvedAmount) body.approvedAmount = approvedAmount;
  if (approved && f.validUntil) body.validUntil = f.validUntil;
  if (f.note.trim()) body.note = f.note.trim();
  return { body, errors };
}

export async function loadPreauths(c: Pick<AuthClient, 'api'>, status: string): Promise<Preauth[]> {
  return toPreauths(await c.api<unknown>(`/partner/preauthorisations${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

export const decide = (c: Pick<AuthClient, 'api'>, id: string, body: DecisionBody, failed: string): Promise<ActionResult> =>
  submit(c, `/partner/preauthorisations/${seg(id)}/decision`, body, failed, DECISION_FIELDS);
