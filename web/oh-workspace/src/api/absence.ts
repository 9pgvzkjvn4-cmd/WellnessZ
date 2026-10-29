/**
 * E06 – absence & sickness management. Mirrors API-Contract-E06-E07-E14.md §3 (paths relative to `/api/v1`).
 * Only the OH-facing endpoints are used here; the `/workspace/...` manager / HR views live in the manager workspace.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

export const ABSENCE_TYPES = ['SICKNESS', 'INJURY_WORK', 'INJURY_NON_WORK', 'MATERNITY_RELATED', 'OTHER_AUTHORISED'] as const;
export type AbsenceType = (typeof ABSENCE_TYPES)[number];
export const REASON_CATEGORIES = ['MSK', 'MENTAL_HEALTH', 'RESPIRATORY', 'GASTRO', 'INJURY', 'SURGERY', 'INFECTION', 'OTHER', 'UNDISCLOSED'] as const;
export const RULE_TYPES = ['DURATION', 'FREQUENCY', 'PATTERN', 'POST_INCIDENT', 'SAFETY_CRITICAL', 'CREW_SICKNESS'] as const;
export type RuleType = (typeof RULE_TYPES)[number];
export const RULE_ACTIONS = ['EARLY_INTERVENTION', 'LONG_TERM', 'AME_REVIEW'] as const;
export type RuleAction = (typeof RULE_ACTIONS)[number];
export const QUEUE_STATUSES = ['NEW', 'ACCEPTED', 'CONTACTED', 'CLOSED'] as const;
export type QueueStatus = (typeof QUEUE_STATUSES)[number];
export const CERTIFICATE_STATUSES = ['RECEIVED', 'VALID', 'INVALID', 'SUSPICIOUS', 'VERIFIED_GENUINE', 'VERIFIED_FRAUDULENT'] as const;
export type CertificateStatus = (typeof CERTIFICATE_STATUSES)[number];
export type CertificateValidity = 'PENDING' | 'VALID' | 'INVALID';
export const MILESTONE_TYPES = ['CONTACT', 'ADVICE_REPORT', 'CASE_CONFERENCE', 'HR_REVIEW', 'RTW_REVIEW', 'IHR_CONSIDERATION'] as const;
export type MilestoneType = (typeof MILESTONE_TYPES)[number];
export type MilestoneStatus = 'PENDING' | 'DONE' | 'OVERDUE' | 'SKIPPED';
export type HrStage = 'NONE' | 'INFORMAL_REVIEW' | 'FORMAL_REVIEW_1' | 'FORMAL_REVIEW_2' | 'CAPABILITY_HEARING' | 'CLOSED';
export const ADVICE_STATUSES = ['SUBMITTED', 'ACCEPTED', 'REPORT_DRAFTED', 'AWAITING_EMPLOYEE', 'RELEASED', 'WITHHELD', 'WITHDRAWN'] as const;
export type AdviceStatus = (typeof ADVICE_STATUSES)[number];
export const ADVICE_FITNESS = ['FIT', 'FIT_WITH_ADJUSTMENTS', 'TEMPORARILY_UNFIT', 'UNFIT_LONG_TERM'] as const;
export type AdviceFitness = (typeof ADVICE_FITNESS)[number];
export const PROGNOSES = ['RTW_WITHIN_2_WEEKS', 'RTW_WITHIN_4_WEEKS', 'RTW_WITHIN_12_WEEKS', 'UNCERTAIN', 'RTW_UNLIKELY'] as const;
export type Prognosis = (typeof PROGNOSES)[number];
export const CONTACT_CHANNELS = ['PHONE', 'VIDEO', 'IN_PERSON', 'EMAIL'] as const;
export const CONTACT_OUTCOMES = ['REACHED', 'NO_ANSWER', 'DECLINED'] as const;
export const CALL_OUTCOMES = ['CONFIRMED', 'NOT_CONFIRMED', 'NO_ANSWER'] as const;
export const VALIDATION_OUTCOMES = ['VALID', 'INVALID', 'SUSPICIOUS'] as const;
export const CONFERENCE_PARTICIPANTS = ['EMPLOYEE', 'MANAGER', 'HR', 'OH'] as const;
export const ANALYTICS_GROUP_BY = ['SUBSIDIARY', 'DEPARTMENT', 'JOB_FAMILY'] as const;
export const ABSENCE_PATTERNS = ['MONDAY_FRIDAY', 'POST_HOLIDAY', 'FREQUENT_SHORT'] as const;

export interface Episode {
  id: string;
  sourceSystem: string;
  startDate: string;
  endDate?: string | null;
  days: number;
  absenceType: AbsenceType;
  reasonCategory?: string | null;
  certified: boolean;
  certificateValidity?: CertificateValidity;
  caseId?: string | null;
  open: boolean;
}
export interface AbsenceSummary {
  episodes12m: number;
  daysLost12m: number;
  bradfordFactor: number;
  longestDays: number;
  openEpisode?: Episode | null;
  patterns: string[];
  activeLtaCaseId?: string | null;
}
export interface FeedRun {
  id: string;
  sourceSystem: string;
  startedAt: string;
  received: number;
  matched: number;
  unmatched: number;
  matchRatePct: number;
  errors: number | string[];
}
export interface FeedMismatch {
  id: string;
  sourceSystem: string;
  sourceRef: string;
  employeeRef: string;
  startDate: string;
  endDate?: string | null;
  reason: string;
  status: 'OPEN' | 'RESOLVED' | 'IGNORED';
}

export type RuleParams = Record<string, number | string>;
export interface Rule {
  id: string;
  code: string;
  name: string;
  subsidiaryCode?: string | null;
  ruleType: RuleType;
  params: RuleParams;
  action: RuleAction;
  active: boolean;
  version: number;
  updatedBy?: string;
  updatedAt?: string;
}
export type RuleBody = Pick<Rule, 'code' | 'name' | 'ruleType' | 'params' | 'action' | 'active'> & { subsidiaryCode?: string };
export interface RuleTestResult {
  matches: { employeeId: string; displayName: string; subsidiaryCode?: string; detail?: string }[];
  count: number;
}

export interface QueueItem {
  id: string;
  employeeId: string;
  displayName: string;
  unit?: string;
  ruleCode: string;
  ruleName: string;
  action: RuleAction;
  raisedAt: string;
  slaDueAt?: string;
  status: QueueStatus;
  caseId?: string | null;
  caseRef?: string | null;
  firstContactAt?: string | null;
  history: Episode[];
}

export interface CertificateCall {
  id?: string;
  calledParty: string;
  outcome: (typeof CALL_OUTCOMES)[number];
  note?: string;
  at?: string;
  by?: string;
}
export interface CertificateChecklist {
  issuerIdentified: boolean;
  registrationValid: boolean;
  datesConsistent: boolean;
  signaturePresent: boolean;
  coversAbsence: boolean;
}
export interface Certificate {
  id: string;
  employeeId: string;
  displayName: string;
  source: 'EMPLOYEE_UPLOAD' | 'CLINIC_SCAN' | 'PARTNER_ELECTRONIC';
  documentId?: string;
  fromDate: string;
  toDate: string;
  issuerName?: string;
  issuerRegistration?: string;
  issueDate?: string;
  extracted?: { issuerName?: string; issueDate?: string; fromDate?: string; toDate?: string; registration?: string } | null;
  checklist?: Partial<CertificateChecklist> | null;
  status: CertificateStatus;
  duplicateOfId?: string | null;
  alterationFlags: string[];
  calls: CertificateCall[];
  receivedAt: string;
  verifiedBy?: string;
  verifiedAt?: string;
}

export interface Milestone {
  id: string;
  week: number;
  type: MilestoneType;
  dueDate: string;
  status: MilestoneStatus;
  completedAt?: string | null;
  completedBy?: string | null;
  note?: string | null;
}
export interface LtaContact {
  id: string;
  at: string;
  channel: string;
  outcome: string;
  note?: string;
  by: string;
  nextContactDue?: string | null;
}
export interface Pathway {
  caseId: string;
  caseRef: string;
  employeeId: string;
  displayName: string;
  unit?: string;
  absenceStart: string;
  weeksAbsent: number;
  status: 'ACTIVE' | 'CLOSED';
  hrStage: HrStage;
  nextMilestone?: { week: number; type: MilestoneType; dueDate: string } | Milestone | null;
  overdueCount: number;
  rtwPlanId?: string | null;
}
export interface PathwayDetail extends Pathway {
  milestones: Milestone[];
  contacts: LtaContact[];
  adviceReports: { id: string; status: AdviceStatus; releasedAt?: string | null }[];
  rtwPlan?: { id: string; status: string } | null;
}

export interface AdviceQuestion {
  code: string;
  text: string;
}
export interface AdviceReportDraft {
  fitness: AdviceFitness;
  adjustments?: string;
  restrictions?: string;
  prognosis: Prognosis;
  expectedReturn?: string;
  reviewDate?: string;
  answers: Record<string, string>;
}
export interface AdviceRequest {
  id: string;
  ref: string;
  employeeId: string;
  displayName: string;
  status: AdviceStatus;
  questionCodes: string[];
  context?: string;
  requestedBy: string;
  requestedByRole?: string;
  requestedAt: string;
  slaDueAt?: string;
  caseRef?: string | null;
  caseId?: string | null;
  episodeId?: string | null;
  report?: (Partial<AdviceReportDraft> & { employeeComment?: string; documentId?: string; releasedAt?: string; signedAt?: string }) | null;
}

export interface UnitRow {
  key: string;
  label: string;
  headcount?: number;
  episodes?: number;
  daysLost?: number;
  absenceRatePct?: number;
  frequencyRate?: number;
  avgDurationDays?: number;
  bradfordMean?: number;
  categories?: Record<string, number> | null;
  suppressed: boolean;
}
export interface AbsenceKpis {
  absenceRatePct: number;
  ltaWithCasePct: number;
  medianDaysTriggerToContact: number;
  certificatesVerifiedPct: number;
  openLta: number;
  overdueMilestones: number;
  trend: { month: string; absenceRatePct: number; medianDaysTriggerToContact: number; ltaWithCasePct: number }[];
}

const q = (id: string) => `/absence/queue/${seg(id)}`;
const cert = (id: string) => `/absence/certificates/${seg(id)}`;
const lta = (caseId: string) => `/absence/lta/${seg(caseId)}`;
const adv = (id: string) => `/absence/advice-requests/${seg(id)}`;

/** Monthly CSV pack (suppression applied server-side) – direct BFF link so the browser downloads it. */
export const absenceExportUrl = (month: string): string => bffLink(`/absence/analytics/export${buildQuery({ month })}`);

export function absenceApi(c: AuthClient) {
  return {
    // F01 feed & history
    feedRuns: (limit = 30) => c.api<FeedRun[]>(`/absence/feed/runs${buildQuery({ limit })}`),
    runFeed: () => c.api<FeedRun>('/absence/feed/runs', { method: 'POST' }),
    mismatches: (status = 'OPEN') => c.api<FeedMismatch[]>(`/absence/feed/mismatches${buildQuery({ status })}`),
    resolveMismatch: (id: string, body: { employeeId: string } | { ignore: true; reason: string }) =>
      c.api<FeedMismatch>(`/absence/feed/mismatches/${seg(id)}/resolve`, { method: 'POST', body }),
    episodes: (employeeId: string, range: { from?: string; to?: string } = {}) =>
      c.api<Episode[]>(`/absence/employees/${seg(employeeId)}/episodes${buildQuery(range)}`),
    summary: (employeeId: string) => c.api<AbsenceSummary>(`/absence/employees/${seg(employeeId)}/summary`),
    linkCase: (episodeId: string, caseId: string) => c.api<Episode>(`/absence/episodes/${seg(episodeId)}/link-case`, { method: 'POST', body: { caseId } }),

    // F02 rules & queue
    rules: () => c.api<Rule[]>('/absence/rules'),
    createRule: (r: RuleBody) => c.api<Rule>('/absence/rules', { method: 'POST', body: r }),
    updateRule: (id: string, r: RuleBody) => c.api<Rule>(`/absence/rules/${seg(id)}`, { method: 'PUT', body: r }),
    deactivateRule: (id: string) => c.api<void>(`/absence/rules/${seg(id)}`, { method: 'DELETE' }),
    testRule: (body: { rule: RuleBody; subsidiaryCode?: string } | { ruleId: string; subsidiaryCode?: string }) =>
      c.api<RuleTestResult>('/absence/rules/test', { method: 'POST', body }),
    queue: (f: { status?: string; action?: string } = {}) => c.api<QueueItem[]>(`/absence/queue${buildQuery(f)}`),
    accept: (id: string) => c.api<QueueItem>(`${q(id)}/accept`, { method: 'POST' }),
    contact: (id: string, body: { channel: string; outcome: string; note?: string }) => c.api<QueueItem>(`${q(id)}/contact`, { method: 'POST', body }),
    close: (id: string, reason: string) => c.api<QueueItem>(`${q(id)}/close`, { method: 'POST', body: { reason } }),

    // F03 certificates
    certificates: (f: { status?: string; employeeId?: string } = {}) => c.api<Certificate[]>(`/absence/certificates${buildQuery(f)}`),
    scanCertificate: (form: FormData) => c.api<Certificate>('/absence/certificates', { method: 'POST', body: form }),
    validateCertificate: (id: string, body: { checklist: CertificateChecklist; outcome: string; note?: string }) =>
      c.api<Certificate>(`${cert(id)}/validate`, { method: 'POST', body }),
    logCall: (id: string, body: { calledParty: string; outcome: string; note?: string }) => c.api<Certificate>(`${cert(id)}/calls`, { method: 'POST', body }),
    verifyCertificate: (id: string, body: { outcome: 'VERIFIED_GENUINE' | 'VERIFIED_FRAUDULENT'; escalateToHr: boolean }) =>
      c.api<Certificate>(`${cert(id)}/verify`, { method: 'POST', body }),

    // F04 long-term absence
    ltaList: (status = 'ACTIVE') => c.api<Pathway[]>(`/absence/lta${buildQuery({ status })}`),
    ltaDetail: (caseId: string) => c.api<PathwayDetail>(lta(caseId)),
    completeMilestone: (caseId: string, id: string, note?: string) =>
      c.api<PathwayDetail>(`${lta(caseId)}/milestones/${seg(id)}/complete`, { method: 'POST', body: note ? { note } : {} }),
    skipMilestone: (caseId: string, id: string, reason: string) => c.api<PathwayDetail>(`${lta(caseId)}/milestones/${seg(id)}/skip`, { method: 'POST', body: { reason } }),
    ltaContact: (caseId: string, body: { channel: string; outcome: string; note?: string; nextContactDue?: string }) =>
      c.api<PathwayDetail>(`${lta(caseId)}/contacts`, { method: 'POST', body }),
    conference: (caseId: string, body: { scheduledFor: string; participants: string[]; note?: string }) =>
      c.api<PathwayDetail>(`${lta(caseId)}/conference`, { method: 'POST', body }),

    // F05 advice
    questions: () => c.api<AdviceQuestion[]>('/absence/advice/questions'),
    adviceRequests: (status?: string) => c.api<AdviceRequest[]>(`/absence/advice-requests${buildQuery({ status })}`),
    acceptAdvice: (id: string) => c.api<AdviceRequest>(`${adv(id)}/accept`, { method: 'POST' }),
    saveReport: (id: string, r: AdviceReportDraft) => c.api<AdviceRequest>(`${adv(id)}/report`, { method: 'PUT', body: r }),
    signReport: (id: string) => c.api<AdviceRequest>(`${adv(id)}/report/sign`, { method: 'POST' }),

    // F06 analytics
    units: (f: { groupBy: string; from?: string; to?: string }) => c.api<UnitRow[]>(`/absence/analytics/units${buildQuery(f)}`),
    kpis: (f: { from?: string; to?: string }) => c.api<AbsenceKpis>(`/absence/analytics/kpis${buildQuery(f)}`),
  };
}

export type AbsenceApi = ReturnType<typeof absenceApi>;
