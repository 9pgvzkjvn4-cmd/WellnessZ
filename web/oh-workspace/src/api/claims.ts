/**
 * E08 – workers' compensation, insurance & claims coordination. Mirrors API-Contract-E04-E08.md §4 (paths relative to
 * `/api/v1`). Adjuster (`/partner/...`) and HR (`/workspace/claims`) endpoints live in the other apps.
 */
import type { AuthClient } from '../auth/AuthClient';
import type { Money } from '../domain/money';
import { bffLink, buildQuery, seg } from './links';
import { toConsent, type ActiveConsent } from './treatment';

export type { ActiveConsent };

export const CLAIM_TYPES = ['WORKERS_COMP', 'EMPLOYER_LIABILITY', 'GROUP_MEDICAL', 'THIRD_PARTY'] as const;
export type ClaimType = (typeof CLAIM_TYPES)[number];
export const CLAIM_STATUSES = ['NOTIFIED', 'OPEN', 'UNDER_REVIEW', 'SETTLED', 'CLOSED', 'LITIGATED'] as const;
export type ClaimStatus = (typeof CLAIM_STATUSES)[number];
export const COST_CATEGORIES = ['MEDICAL', 'REHAB', 'INDEMNITY', 'ADMIN', 'SETTLEMENT', 'LEGAL'] as const;
export type CostCategory = (typeof COST_CATEGORIES)[number];
export const COST_KINDS = ['ESTIMATE', 'INVOICE', 'PAYMENT', 'RESERVE'] as const;
export const REPORT_STATUSES = ['DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'LATE'] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];
export const SUBMISSION_CHANNELS = ['API', 'EMAIL', 'PORTAL'] as const;
export const DISCLOSURE_SET_STATUSES = ['DRAFT', 'SENT', 'REVOKED'] as const;
export const LEGAL_REQUEST_TYPES = ['MEDICO_LEGAL_REPORT', 'EXPERT_OPINION', 'IME'] as const;
export type LegalRequestType = (typeof LEGAL_REQUEST_TYPES)[number];
export const LEGAL_REQUEST_STATUSES = ['OPEN', 'IN_PROGRESS', 'REPORT_READY', 'CLOSED'] as const;
export const THIRD_PARTY_TYPES = ['CONTRACTOR', 'VEHICLE_OWNER', 'OTHER'] as const;
export const TASK_ASSIGNEE_ROLES = ['CLAIMS', 'NURSE_CM', 'ADJUSTER'] as const;

export interface Adjuster {
  name?: string;
  email?: string;
  username?: string;
}
export interface Subrogation {
  thirdPartyName?: string;
  thirdPartyType?: string;
  thirdPartyInsurer?: string;
  reference?: string;
  description?: string;
  flaggedAt?: string;
}
export interface Claim {
  id: string;
  claimRef: string;
  caseId: string;
  caseRef?: string;
  employeeId: string;
  displayName: string;
  unit?: string;
  claimType: ClaimType;
  status: ClaimStatus;
  payerCode?: string;
  payerName?: string;
  policyNumber?: string;
  adjuster?: Adjuster | null;
  lostTime: boolean;
  incidentDate?: string;
  notifiedAt?: string;
  openedAt?: string;
  settledAt?: string;
  closedAt?: string;
  nurseCaseManager?: string;
  nurseCaseManagerName?: string;
  nurseAssignedAt?: string;
  reserves?: Partial<Record<CostCategory, Money | string>> | null;
  paidTotal?: Money | string | null;
  subrogation?: Subrogation | null;
  litigationHold: boolean;
  privileged: boolean;
  openTasks?: number;
  reportStatus?: ReportStatus | string;
  notes?: string;
}
/** `ClaimViews.ReportSummary` – no fields / document; the full reports come from `GET /claims/{id}/reports`. */
export interface ReportSummary {
  id: string;
  ref: string;
  formCode: string;
  recipientCode: string;
  status: ReportStatus;
  dueAt: string;
  submittedAt?: string;
  acknowledgedAt?: string;
}
/** `ClaimViews.DisclosureSetSummary` – a document count only; the sets come from `GET /claims/{id}/disclosure-sets`. */
export interface DisclosureSetSummary {
  id: string;
  recipientCode: string;
  status: (typeof DISCLOSURE_SET_STATUSES)[number];
  documents: number;
  sentAt?: string;
}
/** `ClaimViews.LegalRequestSummary` – no questions / report versions. */
export interface LegalRequestSummary {
  id: string;
  ref: string;
  type: LegalRequestType;
  status: (typeof LEGAL_REQUEST_STATUSES)[number];
  dueDate: string;
}
export interface ClaimDetail extends Claim {
  holdReason?: string;
  privilegeBasis?: string;
  nextStatuses?: ClaimStatus[];
  history?: { type?: string; fromStatus?: string; status: ClaimStatus; at: string; by?: string; reason?: string }[];
  reports?: ReportSummary[];
  disclosureSets?: DisclosureSetSummary[];
  legalRequests?: LegalRequestSummary[];
}
/** `LegalService.HoldView` – answer of the legal-hold / privilege endpoints. */
export interface HoldView {
  claimId: string;
  claimRef: string;
  litigationHold: boolean;
  reason?: string;
  documentsAffected: number;
  privileged: boolean;
  privilegeBasis?: string;
}
export interface ClaimBody {
  claimType: ClaimType;
  payerCode: string;
  policyNumber?: string;
  adjusterName?: string;
  adjusterEmail?: string;
  adjusterUsername?: string;
  lostTime: boolean;
  incidentDate?: string;
  notes?: string;
}
export interface ClaimUpdate {
  payerCode?: string;
  policyNumber?: string;
  adjusterName?: string;
  adjusterEmail?: string;
  adjusterUsername?: string;
  lostTime?: boolean;
  reserves?: Partial<Record<CostCategory, Money>>;
}
export interface ClaimStatusBadge {
  claimId: string;
  claimRef: string;
  claimType: ClaimType;
  status: ClaimStatus;
  lostTime: boolean;
  litigationHold: boolean;
  paidTotal?: Money | string | null;
}
export interface ClaimFilter {
  status?: string;
  type?: string;
  lostTime?: boolean | '';
  payerCode?: string;
  q?: string;
}

export interface ReportForm {
  code: string;
  jurisdiction: string;
  recipientType: 'AUTHORITY' | 'INSURER';
  recipientCode: string;
  recipientName?: string;
  title: string;
  deadlineHours: number;
  specifiedDeadlineHours?: number | null;
  signOffStatus?: string;
  trigger: string;
  fields: { code: string; label: string; source?: string }[];
}
export interface FirstReport {
  id: string;
  ref: string;
  claimId?: string;
  claimRef?: string;
  formCode: string;
  recipientType?: 'AUTHORITY' | 'INSURER' | string;
  recipientCode: string;
  recipientName?: string;
  status: ReportStatus;
  dueAt: string;
  hoursLeft?: number;
  specifiedInjury?: boolean;
  rejectionReason?: string;
  fields?: Record<string, string>;
  submittedAt?: string;
  acknowledgedAt?: string;
  ackRef?: string;
  channel?: string;
  documentId?: string;
}

export interface DisclosureDoc {
  documentId: string;
  title?: string;
  type?: string;
  redactedFromId?: string;
}
/** `DisclosureSetService.CandidateView` – a document of the claim's employee and whether it may go into a set. */
export interface DisclosureCandidate {
  documentId: string;
  documentNumber?: string;
  title: string;
  type?: string;
  createdAt?: string;
  disclosable: boolean;
  /** Why the document cannot be disclosed (set when `disclosable` is false). */
  reason?: string;
}
export interface DisclosureSet {
  id: string;
  claimId?: string;
  claimRef?: string;
  recipientCode: string;
  recipientName?: string;
  purpose: string;
  status: (typeof DISCLOSURE_SET_STATUSES)[number];
  documents: DisclosureDoc[];
  consentId?: string;
  sentAt?: string;
  sentBy?: string;
  revokedAt?: string;
  revokedBy?: string;
  createdBy?: string;
  createdAt?: string;
}
/**
 * `DisclosureSetService.DisclosureRow` – one row per disclosed document from the platform disclosure log (purpose CLAIM,
 * CLAIM_STATUS, LEGAL_PROCEEDINGS or LEGAL_OBLIGATION…); `status` ACTIVE | REVOKED.
 */
export interface DisclosureReportRow {
  id: string;
  at: string;
  employeeId?: string;
  recipientType?: string;
  recipientName?: string;
  resourceType?: string;
  resourceId?: string;
  /** Document title. */
  document?: string;
  purpose?: string;
  consentId?: string | null;
  by?: string;
  status?: 'ACTIVE' | 'REVOKED' | string;
  revokedAt?: string | null;
}

export interface ClaimTask {
  id: string;
  title: string;
  dueDate: string;
  assigneeRole: (typeof TASK_ASSIGNEE_ROLES)[number];
  status: 'OPEN' | 'DONE';
  createdBy?: string;
  createdAt?: string;
  completedAt?: string;
}
export interface ClaimMessage {
  id: string;
  sender: string;
  senderRole?: string;
  text: string;
  sentAt: string;
}

export interface CostEntry {
  id: string;
  category: CostCategory;
  kind: (typeof COST_KINDS)[number];
  amount: Money | string;
  currency?: string;
  incurredOn?: string;
  description?: string;
  invoiceNumber?: string;
  providerCode?: string;
  sourceType?: string;
  sourceRef?: string;
  createdAt?: string;
  createdBy?: string;
}
export interface CostBody {
  category: CostCategory;
  kind: (typeof COST_KINDS)[number];
  amount: string;
  currency: string;
  incurredOn: string;
  description?: string;
  invoiceNumber?: string;
}
export type MoneyLike = Money | string | number | null | undefined;
export type RollupRow = { key: string; label: string; cases?: number; total?: MoneyLike; costPerEmployee?: MoneyLike; suppressed: boolean } & Partial<Record<CostCategory, MoneyLike>>;
export interface BudgetRow {
  unitCode: string;
  label: string;
  budget?: MoneyLike;
  actual?: MoneyLike;
  variance?: MoneyLike;
  variancePct?: number;
  trend: { month: string; actual: MoneyLike }[];
}
export interface ErpExport {
  id: string;
  month: string;
  batchId?: string;
  rows: number;
  total: MoneyLike;
  checksum: string;
  status: 'GENERATED' | 'RECONCILED' | 'MISMATCH' | string;
  erpReference?: string;
  erpTotal?: MoneyLike;
  createdAt?: string;
}
export interface ReconciliationRow {
  claimRef: string;
  paidTotal?: MoneyLike;
  ledgerTotal?: MoneyLike;
  difference?: MoneyLike;
  reconciled: boolean;
}
export interface NurseAssignmentKpi {
  lostTimeClaims: number;
  assignedWithin48h: number;
  /** null when there are no lost-time claims in the range. */
  pct: number | null;
  unassigned: { claimId?: string; claimRef: string; notifiedAt: string }[];
}
export interface LegalRequest {
  id: string;
  ref: string;
  claimId?: string;
  claimRef?: string;
  caseRef?: string;
  type: LegalRequestType;
  status: (typeof LEGAL_REQUEST_STATUSES)[number];
  questions: string;
  dueDate: string;
  providerCode?: string;
  providerName?: string;
  referralId?: string;
  referralRef?: string;
  referralStatus?: string;
  reports: { version: number; documentId: string; createdAt: string; author?: string }[];
}

/** Candidate row → UI shape (`documentId`, falling back to `id`). */
export function toCandidate(raw: Partial<DisclosureCandidate> & { id?: string }): DisclosureCandidate {
  return {
    documentId: String(raw.documentId ?? raw.id ?? ''),
    ...(raw.documentNumber ? { documentNumber: raw.documentNumber } : {}),
    title: raw.title ?? raw.documentNumber ?? String(raw.documentId ?? raw.id ?? ''),
    ...(raw.type ? { type: raw.type } : {}),
    ...(raw.createdAt ? { createdAt: raw.createdAt } : {}),
    disclosable: raw.disclosable === true,
    ...(raw.reason ? { reason: raw.reason } : {}),
  };
}

/** A claim summary legal request as a list row (claims coordinators cannot read the full request). */
export const legalSummaryRow = (s: LegalRequestSummary, claim: Pick<Claim, 'id' | 'claimRef'>): LegalRequest => ({ ...s, claimId: claim.id, claimRef: claim.claimRef, questions: '', reports: [] });

const cl = (id: string) => `/claims/${seg(id)}`;

export const legalReportUrl = (requestId: string, version: number): string => bffLink(`/legal-requests/${seg(requestId)}/reports/${seg(version)}/content`);
export const erpExportContentUrl = (id: string): string => bffLink(`/claims/costs/exports/${seg(id)}/content`);

export function claimsApi(c: AuthClient) {
  return {
    // F01
    createClaim: (caseId: string, body: ClaimBody) => c.api<Claim>(`/cases/${seg(caseId)}/claims`, { method: 'POST', body }),
    claims: (f: ClaimFilter = {}) => c.api<Claim[]>(`/claims${buildQuery({ ...f, lostTime: f.lostTime === '' ? undefined : f.lostTime })}`),
    claim: (id: string) => c.api<ClaimDetail>(cl(id)),
    updateClaim: (id: string, body: ClaimUpdate) => c.api<Claim>(cl(id), { method: 'PUT', body }),
    setStatus: (id: string, status: ClaimStatus, reason?: string) => c.api<Claim>(`${cl(id)}/status`, { method: 'POST', body: reason ? { status, reason } : { status } }),
    /** `204` → undefined (no claim). */
    caseClaimStatus: (caseId: string) => c.api<ClaimStatusBadge | undefined>(`/cases/${seg(caseId)}/claim-status`),
    subrogation: (id: string, body: { thirdPartyName: string; thirdPartyType: string; thirdPartyInsurer?: string; reference?: string; description: string }) =>
      c.api<Claim>(`${cl(id)}/subrogation`, { method: 'POST', body }),
    reconciliation: (month: string) => c.api<ReconciliationRow[]>(`/claims/reconciliation${buildQuery({ month })}`),

    // F02
    reportForms: () => c.api<ReportForm[]>('/claims/report-forms'),
    /** Full reports of a claim (the claim detail only carries summaries). */
    claimReports: (id: string) => c.api<FirstReport[]>(`${cl(id)}/reports`),
    createReport: (id: string, formCode: string) => c.api<FirstReport>(`${cl(id)}/reports`, { method: 'POST', body: { formCode } }),
    updateReport: (reportId: string, fields: Record<string, string>) => c.api<FirstReport>(`/claims/reports/${seg(reportId)}`, { method: 'PUT', body: { fields } }),
    submitReport: (reportId: string, channel: string) => c.api<FirstReport>(`/claims/reports/${seg(reportId)}/submit`, { method: 'POST', body: { channel } }),
    acknowledgeReport: (reportId: string, ackRef: string, receivedAt: string, file?: File) => {
      const fd = new FormData();
      fd.append('ackRef', ackRef);
      fd.append('receivedAt', receivedAt);
      if (file) fd.append('file', file);
      return c.api<FirstReport>(`/claims/reports/${seg(reportId)}/acknowledgement`, { method: 'POST', body: fd });
    },
    reports: (f: { status?: string; dueWithinHours?: number } = {}) => c.api<FirstReport[]>(`/claims/reports${buildQuery(f)}`),

    // F03
    /** Disclosure candidates (all documents of the claim's employee, each with `disclosable` / `reason`). */
    claimDocuments: (id: string) => c.api<(DisclosureCandidate & { id?: string })[]>(`${cl(id)}/documents`).then((l) => (l ?? []).map(toCandidate)),
    /** The claim employee's active INSURER_SHARING consents. */
    claimConsents: (id: string) => c.api<ActiveConsent[]>(`${cl(id)}/consents`).then((l) => (l ?? []).map(toConsent)),
    createDisclosureSet: (id: string, body: { recipientCode: string; purpose: string; documentIds: string[] }) =>
      c.api<DisclosureSet>(`${cl(id)}/disclosure-sets`, { method: 'POST', body }),
    disclosureSets: (id: string) => c.api<DisclosureSet[]>(`${cl(id)}/disclosure-sets`),
    redact: (setId: string, documentId: string, phrases: string[]) =>
      c.api<DisclosureSet>(`/claims/disclosure-sets/${seg(setId)}/redactions`, { method: 'POST', body: { documentId, phrases } }),
    sendSet: (setId: string, consentId: string) => c.api<DisclosureSet>(`/claims/disclosure-sets/${seg(setId)}/send`, { method: 'POST', body: { consentId } }),
    revokeSet: (setId: string) => c.api<DisclosureSet>(`/claims/disclosure-sets/${seg(setId)}/revoke`, { method: 'POST' }),
    disclosureReport: (f: { from?: string; to?: string }) => c.api<DisclosureReportRow[]>(`/claims/disclosure-report${buildQuery(f)}`),

    // F04
    nurseAssignment: (f: { from?: string; to?: string }) => c.api<NurseAssignmentKpi>(`/claims/nurse-assignment${buildQuery(f)}`),
    tasks: (id: string) => c.api<ClaimTask[]>(`${cl(id)}/tasks`),
    createTask: (id: string, body: { title: string; dueDate: string; assigneeRole: string }) => c.api<ClaimTask>(`${cl(id)}/tasks`, { method: 'POST', body }),
    completeTask: (taskId: string) => c.api<ClaimTask>(`/claims/tasks/${seg(taskId)}/complete`, { method: 'POST' }),
    messages: (id: string) => c.api<ClaimMessage[]>(`${cl(id)}/messages`),
    sendMessage: (id: string, text: string) => c.api<ClaimMessage>(`${cl(id)}/messages`, { method: 'POST', body: { text } }),

    // F05
    costs: (id: string) => c.api<CostEntry[]>(`${cl(id)}/costs`),
    addCost: (id: string, body: CostBody) => c.api<CostEntry>(`${cl(id)}/costs`, { method: 'POST', body }),
    rollup: (f: { groupBy: string; from?: string; to?: string }) => c.api<RollupRow[]>(`/claims/costs/rollup${buildQuery(f)}`),
    setBudget: (year: number, unitCode: string, amount: Money) => c.api<{ year: number; unitCode: string; amount: Money }>(`/claims/budgets/${seg(year)}/${seg(unitCode)}`, { method: 'PUT', body: amount }),
    budgetVsActual: (f: { year: number; groupBy: string }) => c.api<BudgetRow[]>(`/claims/costs/budget-vs-actual${buildQuery(f)}`),
    createExport: (month: string) => c.api<ErpExport>('/claims/costs/exports', { method: 'POST', body: { month } }),
    exports: () => c.api<ErpExport[]>('/claims/costs/exports'),
    reconcileExport: (id: string, erpTotal: Money, erpReference: string) =>
      c.api<ErpExport>(`/claims/costs/exports/${seg(id)}/reconcile`, { method: 'POST', body: { erpTotal, erpReference } }),

    // F06
    legalHold: (id: string, reason: string) => c.api<HoldView>(`${cl(id)}/legal-hold`, { method: 'POST', body: { reason } }),
    releaseLegalHold: (id: string, reason: string) => c.api<HoldView>(`${cl(id)}/legal-hold`, { method: 'DELETE', body: { reason } }),
    privilege: (id: string, privileged: boolean, basis: string) => c.api<HoldView>(`${cl(id)}/privilege`, { method: 'POST', body: { privileged, basis } }),
    createLegalRequest: (id: string, body: { type: LegalRequestType; questions: string; dueDate: string; providerCode?: string }) =>
      c.api<LegalRequest>(`${cl(id)}/legal-requests`, { method: 'POST', body }),
    legalRequests: (status?: string) => c.api<LegalRequest[]>(`/legal-requests${buildQuery({ status })}`),
    legalRequest: (requestId: string) => c.api<LegalRequest>(`/legal-requests/${seg(requestId)}`),
    submitLegalReport: (requestId: string, opinion: string) => c.api<LegalRequest>(`/legal-requests/${seg(requestId)}/report`, { method: 'POST', body: { opinion } }),
    closeLegalRequest: (requestId: string) => c.api<LegalRequest>(`/legal-requests/${seg(requestId)}/close`, { method: 'POST' }),
  };
}

export type ClaimsApi = ReturnType<typeof claimsApi>;
