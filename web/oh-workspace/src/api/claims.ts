/**
 * E08 – workers' compensation, insurance & claims coordination. Mirrors API-Contract-E04-E08.md §4 (paths relative to
 * `/api/v1`). Adjuster (`/partner/...`) and HR (`/workspace/claims`) endpoints live in the other apps.
 */
import type { AuthClient } from '../auth/AuthClient';
import type { Money } from '../domain/money';
import { bffLink, buildQuery, seg } from './links';

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
  nurseAssignedAt?: string;
  reserves?: Partial<Record<CostCategory, Money | string>> | null;
  paidTotal?: Money | string | null;
  subrogation?: Subrogation | null;
  litigationHold: boolean;
  privileged: boolean;
  openTasks?: number;
  reportStatus?: ReportStatus | string;
}
export interface ClaimDetail extends Claim {
  history?: { status: ClaimStatus; at: string; by?: string; reason?: string }[];
  reports?: FirstReport[];
  disclosureSets?: DisclosureSet[];
  legalRequests?: LegalRequest[];
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
  title: string;
  deadlineHours: number;
  trigger: string;
  fields: { code: string; label: string; source?: string }[];
}
export interface FirstReport {
  id: string;
  ref: string;
  claimId?: string;
  claimRef?: string;
  formCode: string;
  recipientCode: string;
  status: ReportStatus;
  dueAt: string;
  hoursLeft?: number;
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
export interface DisclosureSet {
  id: string;
  recipientCode: string;
  purpose: string;
  status: (typeof DISCLOSURE_SET_STATUSES)[number];
  documents: DisclosureDoc[];
  consentId?: string;
  sentAt?: string;
  sentBy?: string;
  revokedAt?: string;
}
export interface DisclosureReportRow {
  claimRef?: string;
  claimId?: string;
  recipientCode?: string;
  recipient?: string;
  documents?: (DisclosureDoc | string)[];
  consentId?: string;
  by?: string;
  at?: string;
  purpose?: string;
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
  sourceType?: string;
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
  pct: number;
  unassigned: { claimRef: string; notifiedAt: string }[];
}
export interface LegalRequest {
  id: string;
  ref: string;
  claimId?: string;
  claimRef?: string;
  type: LegalRequestType;
  status: (typeof LEGAL_REQUEST_STATUSES)[number];
  questions: string;
  dueDate: string;
  providerCode?: string;
  referralId?: string;
  reports: { version: number; documentId: string; createdAt: string; author?: string }[];
}

const cl = (id: string) => `/claims/${seg(id)}`;

export const legalReportUrl = (requestId: string, version: number): string => bffLink(`/legal-requests/${seg(requestId)}/reports/${seg(version)}/content`);
export const erpExportContentUrl = (id: string): string => bffLink(`/claims/costs/exports/${seg(id)}/content`);

export function claimsApi(c: AuthClient) {
  return {
    // F01
    createClaim: (caseId: string, body: ClaimBody) => c.api<Claim>(`/cases/${seg(caseId)}/claims`, { method: 'POST', body }),
    claims: (f: ClaimFilter = {}) => c.api<Claim[]>(`/claims${buildQuery({ ...f, lostTime: f.lostTime === '' ? undefined : f.lostTime })}`),
    claim: (id: string) => c.api<ClaimDetail>(cl(id)),
    updateClaim: (id: string, body: ClaimUpdate) => c.api<ClaimDetail>(cl(id), { method: 'PUT', body }),
    setStatus: (id: string, status: ClaimStatus, reason?: string) => c.api<ClaimDetail>(`${cl(id)}/status`, { method: 'POST', body: reason ? { status, reason } : { status } }),
    /** `204` → undefined (no claim). */
    caseClaimStatus: (caseId: string) => c.api<ClaimStatusBadge | undefined>(`/cases/${seg(caseId)}/claim-status`),
    subrogation: (id: string, body: { thirdPartyName: string; thirdPartyType: string; thirdPartyInsurer?: string; reference?: string; description: string }) =>
      c.api<ClaimDetail>(`${cl(id)}/subrogation`, { method: 'POST', body }),
    reconciliation: (month: string) => c.api<ReconciliationRow[]>(`/claims/reconciliation${buildQuery({ month })}`),

    // F02
    reportForms: () => c.api<ReportForm[]>('/claims/report-forms'),
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
    setBudget: (year: number, unitCode: string, amount: Money) => c.api<unknown>(`/claims/budgets/${seg(year)}/${seg(unitCode)}`, { method: 'PUT', body: amount }),
    budgetVsActual: (f: { year: number; groupBy: string }) => c.api<BudgetRow[]>(`/claims/costs/budget-vs-actual${buildQuery(f)}`),
    createExport: (month: string) => c.api<ErpExport>('/claims/costs/exports', { method: 'POST', body: { month } }),
    exports: () => c.api<ErpExport[]>('/claims/costs/exports'),
    reconcileExport: (id: string, erpTotal: Money, erpReference: string) =>
      c.api<ErpExport>(`/claims/costs/exports/${seg(id)}/reconcile`, { method: 'POST', body: { erpTotal, erpReference } }),

    // F06
    legalHold: (id: string, reason: string) => c.api<ClaimDetail>(`${cl(id)}/legal-hold`, { method: 'POST', body: { reason } }),
    releaseLegalHold: (id: string, reason: string) => c.api<ClaimDetail>(`${cl(id)}/legal-hold`, { method: 'DELETE', body: { reason } }),
    privilege: (id: string, privileged: boolean, basis: string) => c.api<ClaimDetail>(`${cl(id)}/privilege`, { method: 'POST', body: { privileged, basis } }),
    createLegalRequest: (id: string, body: { type: LegalRequestType; questions: string; dueDate: string; providerCode?: string }) =>
      c.api<LegalRequest>(`${cl(id)}/legal-requests`, { method: 'POST', body }),
    legalRequests: (status?: string) => c.api<LegalRequest[]>(`/legal-requests${buildQuery({ status })}`),
    submitLegalReport: (requestId: string, opinion: string) => c.api<LegalRequest>(`/legal-requests/${seg(requestId)}/report`, { method: 'POST', body: { opinion } }),
    closeLegalRequest: (requestId: string) => c.api<LegalRequest>(`/legal-requests/${seg(requestId)}/close`, { method: 'POST' }),
  };
}

export type ClaimsApi = ReturnType<typeof claimsApi>;
