/**
 * Platform additions used by E03 and E05 (contract §3): master data, documents, break-glass, disclosures, retention.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

// ---------------------------------------------------------------- master data (§3.1)
export const DEMAND_DIMENSIONS = [
  'LIFT_KG', 'PUSH_PULL_KG', 'CLIMB', 'AIRSIDE_DRIVE', 'NIGHT', 'KNIFE', 'CHEMICAL', 'RESPIRATOR', 'NOISE', 'HEAT', 'HEIGHT',
  'FLIGHT_DUTY', 'FOOD_HANDLING', 'HOURS_PER_DAY', 'SCREEN_HOURS', 'SAFETY_CRITICAL',
] as const;
export type DemandDimension = (typeof DEMAND_DIMENSIONS)[number];

export interface FitnessStandard {
  code: string;
  title: string;
  roleCategory?: string;
  source?: string;
  referenceUrl?: string;
  validityMonths?: number | null;
  validityMonthsOver40?: number | null;
  requiresSignoffRole?: 'AME' | 'CMO' | null;
  limitationCodes: string[];
  version?: number;
  status?: string;
  updatedBy?: string;
  updatedAt?: string;
}
export interface RestrictionDef {
  code: string;
  label: string;
  dimension: DemandDimension | string;
  valueRequired: boolean;
  unit?: string | null;
  active: boolean;
}
export interface JobDemandProfile {
  jobCode: string;
  title: string;
  unit: string;
  demands: Partial<Record<DemandDimension, number | boolean>>;
  sitting?: 'LOW' | 'MEDIUM' | 'HIGH';
  validatedBy?: string | null;
  validatedAt?: string | null;
}

// ---------------------------------------------------------------- documents (§3.2)
export const DOCUMENT_TYPES = [
  'FITNESS_CERTIFICATE', 'RESTRICTION_NOTE', 'REFERRAL_LETTER', 'CABIN_CREW_MEDICAL_REPORT', 'CREW_MEDICAL_REPORT', 'FOOD_HANDLER_CLEARANCE',
  'RESULT_LETTER', 'RTW_PLAN', 'JOB_OFFER', 'IHR_DECISION_LETTER', 'EXTERNAL_REPORT', 'PHOTO', 'OTHER',
] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export type DocumentStatus = 'DRAFT' | 'SIGNED' | 'SUPERSEDED' | 'REVOKED';
export const SHARE_TARGETS = ['EMPLOYEE', 'MANAGER', 'HR', 'PARTNER'] as const;
export type ShareTarget = (typeof SHARE_TARGETS)[number];

export interface DocumentShare {
  id: string;
  target: ShareTarget;
  partnerOrg?: string | null;
  sharedAt?: string;
  sharedBy?: string;
  revokedAt?: string | null;
}
export interface DocumentMeta {
  id: string;
  documentNumber?: string;
  type: DocumentType;
  templateCode?: string | null;
  title: string;
  employeeId: string;
  caseId?: string | null;
  version?: number;
  status: DocumentStatus;
  contentType?: string;
  size?: number;
  sha256?: string | null;
  signedBy?: string | null;
  signedAt?: string | null;
  createdBy?: string;
  createdAt?: string;
  legalHold?: boolean;
  shares?: DocumentShare[];
  acknowledgedAt?: string | null;
}
/** `mergeFields` are field names; the contract shows `[…]` – objects `{key,label,required}` are accepted too. */
export type MergeField = string | { key: string; label?: string; required?: boolean };
export interface DocumentTemplate {
  code: string;
  type: DocumentType;
  title: string;
  mergeFields: MergeField[];
  audience?: string;
  containsClinical: boolean;
}

// ---------------------------------------------------------------- break-glass & disclosures (§3.3)
export const BREAK_GLASS_MIN_REASON = 20;
export interface BreakGlassGrant {
  id: string;
  employeeId: string;
  grantedTo: string;
  expiresAt: string;
  reason: string;
}
export type BreakGlassReviewOutcome = 'PENDING' | 'APPROPRIATE' | 'INAPPROPRIATE';
export interface BreakGlassEvent {
  id: string;
  employeeId: string;
  grantedTo: string;
  reason: string;
  grantedAt: string;
  expiresAt: string;
  review: BreakGlassReviewOutcome;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  reviewDueAt?: string;
}
export interface BreakGlassReport {
  month: string;
  total: number;
  appropriate: number;
  inappropriate: number;
  pending: number;
  overdueReviews: number;
  byUser: { username: string; count: number }[];
}
export interface Disclosure {
  at: string;
  recipientType: string;
  recipientName?: string;
  documentTitle?: string;
  purpose?: string;
  consentId?: string | null;
  status: 'ACTIVE' | 'REVOKED';
  disclosedBy?: string;
}

// ---------------------------------------------------------------- retention (§3.2)
export const RETENTION_TRIGGERS = ['LAST_ENCOUNTER', 'DOCUMENT_DATE', 'EMPLOYMENT_END'] as const;
export interface RetentionRule {
  recordClass: string;
  retentionYears: number;
  trigger: (typeof RETENTION_TRIGGERS)[number];
  legalBasis?: string;
  approvedBy?: string;
  approvedAt?: string;
}
/** Due rows: the contract says "records past retention" – the UI reads document-like fields defensively. */
export interface RetentionDueItem {
  id?: string;
  documentId?: string;
  documentNumber?: string;
  recordClass?: string;
  title?: string;
  employeeId?: string;
  dueSince?: string;
  retentionEndsAt?: string;
}

/** Typed platform calls. */
export function platformApi(c: AuthClient) {
  return {
    fitnessStandards: () => c.api<FitnessStandard[]>('/masterdata/fitness-standards'),
    saveFitnessStandard: (s: Omit<FitnessStandard, 'version'>) => c.api<FitnessStandard>(`/masterdata/fitness-standards/${seg(s.code)}`, { method: 'PUT', body: s }),
    restrictions: () => c.api<RestrictionDef[]>('/masterdata/restrictions'),
    saveRestriction: (r: RestrictionDef) => c.api<RestrictionDef>(`/masterdata/restrictions/${seg(r.code)}`, { method: 'PUT', body: r }),
    jobDemands: (unit?: string) => c.api<JobDemandProfile[]>(`/masterdata/job-demands${buildQuery({ unit })}`),
    saveJobDemand: (p: JobDemandProfile) => c.api<JobDemandProfile>(`/masterdata/job-demands/${seg(p.jobCode)}`, { method: 'PUT', body: p }),
    importJobDemands: (profiles: JobDemandProfile[]) => c.api<{ upserted: number }>('/masterdata/job-demands/import', { method: 'POST', body: profiles }),

    documentTemplates: () => c.api<DocumentTemplate[]>('/documents/templates'),
    generateDocument: (body: { templateCode: string; employeeId: string; caseId?: string; fields: Record<string, string> }) =>
      c.api<DocumentMeta>('/documents/generate', { method: 'POST', body }),
    signDocument: (id: string) => c.api<DocumentMeta>(`/documents/${seg(id)}/sign`, { method: 'POST', body: {} }),
    newVersion: (id: string, fields: Record<string, string>) => c.api<DocumentMeta>(`/documents/${seg(id)}/new-version`, { method: 'POST', body: { fields } }),
    share: (id: string, body: { target: ShareTarget; partnerOrg?: string; consentId?: string }) => c.api<DocumentShare>(`/documents/${seg(id)}/share`, { method: 'POST', body }),
    revokeShare: (id: string, shareId: string) => c.api<void>(`/documents/${seg(id)}/shares/${seg(shareId)}`, { method: 'DELETE' }),
    documents: (q: { employeeId?: string; caseId?: string; type?: string }) => c.api<DocumentMeta[]>(`/documents${buildQuery(q)}`),
    searchDocuments: (employeeId: string, q: string) => c.api<{ document: DocumentMeta; snippet?: string }[]>(`/documents/search${buildQuery({ employeeId, q })}`),
    scan: (file: Blob, fileName: string, employeeId: string, caseId?: string, documentType?: string) => {
      const fd = new FormData();
      fd.append('file', file, fileName);
      fd.append('employeeId', employeeId);
      if (caseId) fd.append('caseId', caseId);
      if (documentType) fd.append('documentType', documentType);
      return c.api<DocumentMeta>('/documents/scan', { method: 'POST', body: fd });
    },
    retention: () => c.api<RetentionRule[]>('/documents/retention'),
    saveRetention: (r: RetentionRule) => c.api<RetentionRule>(`/documents/retention/${seg(r.recordClass)}`, { method: 'PUT', body: r }),
    retentionDue: () => c.api<RetentionDueItem[]>('/documents/retention/due'),
    setLegalHold: (id: string, reason: string) => c.api<DocumentMeta>(`/documents/${seg(id)}/legal-hold`, { method: 'POST', body: { reason } }),
    releaseLegalHold: (id: string, reason: string) => c.api<DocumentMeta>(`/documents/${seg(id)}/legal-hold`, { method: 'DELETE', body: { reason } }),

    breakGlass: (employeeId: string, reason: string) => c.api<BreakGlassGrant>('/break-glass', { method: 'POST', body: { employeeId, reason } }),
    breakGlassEvents: (status?: BreakGlassReviewOutcome) => c.api<BreakGlassEvent[]>(`/break-glass${buildQuery({ status })}`),
    reviewBreakGlass: (id: string, review: 'APPROPRIATE' | 'INAPPROPRIATE', note: string) => c.api<BreakGlassEvent>(`/break-glass/${seg(id)}/review`, { method: 'POST', body: { review, note } }),
    breakGlassReport: (month: string) => c.api<BreakGlassReport>(`/break-glass/report${buildQuery({ month })}`),
    disclosures: (employeeId: string) => c.api<Disclosure[]>(`/disclosures${buildQuery({ employeeId })}`),
  };
}

export type PlatformApi = ReturnType<typeof platformApi>;

/** Audited PDF / image download through the BFF. */
export const documentContentUrl = (id: string): string => bffLink(`/documents/${seg(id)}/content`);

/** Normalise a template merge field to `{key,label,required}`. */
export function mergeFieldSpec(f: MergeField): { key: string; label: string; required: boolean } {
  if (typeof f === 'string') return { key: f, label: f, required: true };
  return { key: f.key, label: f.label ?? f.key, required: f.required ?? true };
}

/** Document types that may be shared with MANAGER / HR (contract §3.2: no clinical content). */
export const NON_CLINICAL_TYPES: readonly DocumentType[] = ['FITNESS_CERTIFICATE', 'RESTRICTION_NOTE', 'FOOD_HANDLER_CLEARANCE', 'RTW_PLAN', 'JOB_OFFER'];
