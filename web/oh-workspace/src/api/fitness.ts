/**
 * E05 – fitness for duty, restrictions, RTW, crew, food handlers, IHR. Mirrors API-Contract-E03-E05.md §5.
 */
import type { AuthClient } from '../auth/AuthClient';
import type { FitnessStatus } from './types';
import { buildQuery, seg } from './links';

export const FITNESS_OUTCOMES = ['FIT', 'FIT_WITH_LIMITATIONS', 'TEMPORARILY_UNFIT', 'PERMANENTLY_UNFIT', 'PENDING'] as const;
export type FitnessOutcome = (typeof FITNESS_OUTCOMES)[number];
export type DecisionStatus = 'FINAL' | 'AWAITING_SIGNOFF' | 'SUPERSEDED' | 'REJECTED';
export const RESTRICTION_STATUSES = ['ACTIVE', 'EXPIRING', 'LAPSED', 'CLOSED'] as const;
export type RestrictionInstanceStatus = (typeof RESTRICTION_STATUSES)[number];
export type ProposalStatus = 'PROPOSED' | 'ACCEPTED' | 'DECLINED' | 'ESCALATED_TO_HR' | 'WITHDRAWN';
export type RtwPlanStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';
export const REVIEW_OUTCOMES = ['ON_TRACK', 'ADJUST', 'EXTEND', 'COMPLETE'] as const;
export type ReviewOutcome = (typeof REVIEW_OUTCOMES)[number];
export const FOOD_EXCLUSION_REASONS = ['GI_ILLNESS', 'SKIN_INFECTION', 'TYPHOID_PARATYPHOID', 'HEPATITIS_A', 'STEC', 'OTHER'] as const;
export type FoodHandlerStatus = 'FIT_TO_HANDLE_FOOD' | 'EXCLUDED' | 'RETURN_PENDING_CLEARANCE';
export const CREW_STANDARDS = ['CAA_CLASS1', 'CAA_CLASS2', 'CABIN_CREW'] as const;
export const IHR_TYPES = ['ILL_HEALTH_RETIREMENT', 'REDEPLOYMENT'] as const;
export type IhrStatus = 'DRAFT' | 'AWAITING_CMO' | 'DECIDED' | 'APPEALED' | 'APPEAL_DECIDED' | 'CLOSED';

export interface RestrictionValue {
  code: string;
  value?: number | string | boolean | null;
}
export interface RestrictionLabelled extends RestrictionValue {
  label: string;
}

export interface FitnessDecisionRequest {
  standardCode: string;
  outcome: FitnessOutcome;
  limitationCodes: string[];
  restrictions: RestrictionValue[];
  validFrom: string;
  validUntil?: string;
  reviewDate?: string;
  rationale: string;
  durationOverrideReason?: string;
  encounterId?: string;
  generateCertificate?: boolean;
}
export interface FitnessDecision {
  id: string;
  caseId: string;
  caseRef?: string;
  employeeId: string;
  employeeName?: string;
  standardCode: string;
  standardTitle?: string;
  standardVersion?: number;
  outcome: FitnessOutcome;
  kernelStatus?: FitnessStatus;
  limitationCodes: string[];
  restrictions: RestrictionLabelled[];
  validFrom?: string;
  validUntil?: string | null;
  reviewDate?: string | null;
  rationale?: string;
  status: DecisionStatus;
  decidedBy?: string;
  decidedAt?: string;
  signoffBy?: string | null;
  signoffAt?: string | null;
  signoffNote?: string | null;
  certificateDocumentId?: string | null;
}
export interface DurationComparison {
  icdCode?: string | null;
  guidelineTitle?: string | null;
  expectedDays: number | null;
  actualDays?: number | null;
  varianceDays?: number | null;
  exceeded?: boolean;
  source?: string | null;
}

export interface JobMatch {
  jobCode: string;
  title: string;
  unit?: string;
  compatible: boolean;
  conflicts: { dimension: string; demand: unknown; restriction: unknown }[];
  score?: number;
  sameDepartment?: boolean;
}
export interface DutyProposal {
  id: string;
  caseId: string;
  caseRef?: string;
  employeeId?: string;
  employeeName?: string;
  jobCode: string;
  jobTitle?: string;
  duties: string;
  restrictions?: RestrictionLabelled[];
  hoursPerDay?: number | null;
  startDate: string;
  endDate?: string | null;
  status: ProposalStatus;
  proposedBy?: string;
  proposedAt?: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  reasonCode?: string | null;
  note?: string | null;
}

export interface RtwPhase {
  fromWeek: number;
  toWeek: number;
  hoursPerDay: number;
  duties: string;
  restrictions: RestrictionValue[];
}
export interface RtwPlanRequest {
  startDate: string;
  phases: RtwPhase[];
  reviewDates: string[];
  goal: string;
}
export interface RtwReview {
  id: string;
  dueDate: string;
  completedAt?: string | null;
  outcome?: ReviewOutcome | null;
  note?: string | null;
  completedBy?: string | null;
}
export interface RtwPlan {
  id: string;
  caseId: string;
  caseRef?: string;
  employeeId?: string;
  employeeName?: string;
  status: RtwPlanStatus;
  startDate: string;
  goal: string;
  phases: (Omit<RtwPhase, 'restrictions'> & { restrictions: RestrictionLabelled[] })[];
  reviews: RtwReview[];
  employeeAck?: { at: string; agree: boolean; comment?: string } | null;
  managerAck?: { at: string; by: string; comment?: string } | null;
  documentId?: string | null;
  createdBy?: string;
  createdAt?: string;
  nextReviewDate?: string | null;
}
export interface OverdueReview {
  planId: string;
  caseId?: string;
  caseRef: string;
  employeeName: string;
  reviewDate: string;
  daysOverdue: number;
  owner?: string;
}
export interface JobOffer {
  id: string;
  caseId: string;
  jobTitle: string;
  status: string;
  issuedAt?: string;
  respondedAt?: string | null;
}

export interface LimitationCode {
  code: string;
  label: string;
  appliesTo: string[];
  operational: boolean;
}
export interface CrewAssessmentRequest {
  standardCode: (typeof CREW_STANDARDS)[number];
  outcome: FitnessOutcome;
  limitationCodes: string[];
  validFrom: string;
  validUntil?: string;
  reviewDate?: string;
  examinationDate: string;
  rationale: string;
}
/** Dashboard cells may be numbers, `null` or `{suppressed:true}` (k-anonymity, < 5). */
export interface CrewDashboard {
  unfitByBase: { base: string; count: unknown; avgDays?: unknown }[];
  limitedByBase: { base: string; count: unknown; avgDays?: unknown }[];
  byFleet: { fleet: string; unfit?: unknown; limited?: unknown; count?: unknown }[];
  trend: { week: string; unfit: unknown; limited: unknown }[];
  minGroupSize?: number;
}
export interface RosterMessage {
  messageId: string;
  employeeId: string;
  rosterStatus: string;
  validFrom?: string;
  validUntil?: string;
  operationalLimitations?: string | string[];
  sentAt: string;
  ackAt?: string;
}
/** `GET /cases/{id}/food-handler` – the contract states "food-handler episodes of a case" without a shape; read defensively. */
export interface FoodHandlerEpisode {
  id?: string;
  status: FoodHandlerStatus;
  reasonCategory?: string;
  excludedFrom?: string;
  expectedReturn?: string;
  returnRequestedAt?: string;
  clearedAt?: string;
  clearedBy?: string;
  documentId?: string;
  /** backend name of the FOOD_HANDLER_CLEARANCE document */
  clearanceDocumentId?: string;
  caseId?: string;
  employeeId?: string;
  department?: string;
}
export interface RosterReconciliation {
  sent: number;
  acknowledged: number;
  unacknowledgedOver15Min: { messageId: string; employeeId?: string; rosterStatus?: string; sentAt?: string }[];
  p95LatencySeconds: number | null;
}

export interface RestrictionInstance {
  id: string;
  employeeId: string;
  employeeName?: string;
  caseId?: string;
  caseRef?: string;
  decisionId?: string;
  code: string;
  label: string;
  value?: unknown;
  validFrom?: string;
  validUntil?: string | null;
  status: RestrictionInstanceStatus;
  alertedAt?: string | null;
  closedAt?: string | null;
  closeReason?: string | null;
}

export interface IhrAssessment {
  id: string;
  caseId: string;
  type: (typeof IHR_TYPES)[number];
  status: IhrStatus;
  findings?: string;
  recommendation?: string;
  policyReference?: string;
  decisionText?: string | null;
  assessedBy?: string;
  assessedAt?: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  appealDeadline?: string | null;
  appealGrounds?: string | null;
  appealDueAt?: string | null;
  appealUpheld?: boolean | null;
}

const cs = (id: string) => `/cases/${seg(id)}`;

/** Typed E05 calls. */
export function fitnessApi(c: AuthClient) {
  return {
    decide: (caseId: string, r: FitnessDecisionRequest) => c.api<FitnessDecision>(`${cs(caseId)}/fitness-decisions`, { method: 'POST', body: r }),
    decisions: (caseId: string) => c.api<FitnessDecision[]>(`${cs(caseId)}/fitness-decisions`),
    signoff: (id: string, approve: boolean, note: string) => c.api<FitnessDecision>(`/fitness-decisions/${seg(id)}/signoff`, { method: 'POST', body: { approve, note } }),
    awaitingSignoff: () => c.api<FitnessDecision[]>('/fitness-decisions/awaiting-signoff'),
    durationComparison: (caseId: string) => c.api<DurationComparison | null>(`${cs(caseId)}/duration-comparison`),
    employeeFitness: (employeeId: string) => c.api<FitnessDecision[]>(`/employees/${seg(employeeId)}/fitness`),

    match: (employeeId: string, restrictions: RestrictionValue[], unit?: string) =>
      c.api<JobMatch[]>('/fitness/match', { method: 'POST', body: { employeeId, restrictions, ...(unit ? { unit } : {}) } }),
    propose: (caseId: string, body: { jobCode: string; duties: string; hoursPerDay?: number; startDate: string; endDate?: string }) =>
      c.api<DutyProposal>(`${cs(caseId)}/duty-proposals`, { method: 'POST', body }),
    proposals: (caseId: string) => c.api<DutyProposal[]>(`${cs(caseId)}/duty-proposals`),

    createPlan: (caseId: string, r: RtwPlanRequest) => c.api<RtwPlan>(`${cs(caseId)}/rtw-plans`, { method: 'POST', body: r }),
    updatePlan: (id: string, r: RtwPlanRequest) => c.api<RtwPlan>(`/rtw-plans/${seg(id)}`, { method: 'PUT', body: r }),
    activatePlan: (id: string) => c.api<RtwPlan>(`/rtw-plans/${seg(id)}/activate`, { method: 'POST' }),
    plans: (caseId: string) => c.api<RtwPlan[]>(`${cs(caseId)}/rtw-plans`),
    completeReview: (planId: string, reviewId: string, outcome: ReviewOutcome, note: string) =>
      c.api<RtwPlan>(`/rtw-plans/${seg(planId)}/reviews/${seg(reviewId)}/complete`, { method: 'POST', body: { outcome, note } }),
    overdueReviews: () => c.api<OverdueReview[]>('/rtw/reviews/overdue'),
    jobOffers: (caseId: string) => c.api<JobOffer[]>(`${cs(caseId)}/job-offers`),

    limitationCodes: () => c.api<LimitationCode[]>('/crew/limitation-codes'),
    crewAssessment: (caseId: string, r: CrewAssessmentRequest) => c.api<FitnessDecision>(`${cs(caseId)}/crew-assessment`, { method: 'POST', body: r }),
    crewDashboard: (from?: string, to?: string) => c.api<CrewDashboard>(`/crew/dashboard${buildQuery({ from, to })}`),
    /** People use the `/crew/...` alias; `/integration/**` is reserved for machine clients. */
    rosterReconciliation: () => c.api<RosterReconciliation>('/crew/roster-feed/reconciliation'),
    rosterFeed: (since?: string) => c.api<RosterMessage[]>(`/crew/roster-feed${buildQuery({ since })}`),

    foodExclude: (caseId: string, body: { reasonCategory: string; excludedFrom: string; expectedReturn?: string; checklistAnswers: Record<string, boolean> }) =>
      c.api<{ status: FoodHandlerStatus }>(`${cs(caseId)}/food-handler/exclude`, { method: 'POST', body }),
    foodReturnRequest: (caseId: string) => c.api<{ status: FoodHandlerStatus }>(`${cs(caseId)}/food-handler/return-request`, { method: 'POST' }),
    foodClear: (caseId: string, body: { criteria: { symptomFree48h: boolean; clearanceSamples?: string }; note: string }) =>
      c.api<FoodHandlerEpisode>(`${cs(caseId)}/food-handler/clear`, { method: 'POST', body }),
    foodEpisodes: (caseId: string) => c.api<FoodHandlerEpisode[]>(`${cs(caseId)}/food-handler`),
    foodHandlers: (department?: string) =>
      c.api<{ employeeId: string; displayName: string; department?: string; status: FoodHandlerStatus; excludedFrom?: string; expectedReturn?: string; clearedAt?: string }[]>(
        `/fitness/food-handlers${buildQuery({ department })}`,
      ),

    activeRestrictions: (q: { unit?: string; status?: string }) => c.api<RestrictionInstance[]>(`/restrictions/active${buildQuery(q)}`),
    extendRestriction: (id: string, validUntil: string, reason: string) => c.api<RestrictionInstance>(`/restrictions/${seg(id)}/extend`, { method: 'POST', body: { validUntil, reason } }),
    closeRestriction: (id: string, reason: string) => c.api<RestrictionInstance>(`/restrictions/${seg(id)}/close`, { method: 'POST', body: { reason } }),
    restrictionsDashboard: () => c.api<{ byUnit: { unit: string; active: number; expiring: number; lapsed: number }[] }>('/restrictions/dashboard'),

    ihrCreate: (caseId: string, body: { type: string; findings: string; recommendation: string; policyReference: string }) =>
      c.api<IhrAssessment>(`${cs(caseId)}/ihr-assessments`, { method: 'POST', body }),
    ihrList: (caseId: string) => c.api<IhrAssessment[]>(`${cs(caseId)}/ihr-assessments`),
    ihrCmoDecision: (id: string, body: { approve: boolean; decisionText: string; note: string }) =>
      c.api<IhrAssessment>(`/ihr-assessments/${seg(id)}/cmo-decision`, { method: 'POST', body }),
    ihrAppeal: (id: string, body: { grounds: string; secondOpinionBy?: string }) => c.api<IhrAssessment>(`/ihr-assessments/${seg(id)}/appeal`, { method: 'POST', body }),
    ihrAppealDecision: (id: string, body: { upheld: boolean; decisionText: string }) =>
      c.api<IhrAssessment>(`/ihr-assessments/${seg(id)}/appeal-decision`, { method: 'POST', body }),
  };
}

export type FitnessApi = ReturnType<typeof fitnessApi>;
