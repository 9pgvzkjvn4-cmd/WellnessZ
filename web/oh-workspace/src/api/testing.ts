/**
 * E10 – drug & alcohol testing. Mirrors API-Contract-E09-E10-E11-E12.md §4 (paths relative to `/api/v1`).
 * Restricted class: only MRO (and OH_PHYSICIAN acting as MRO for return-to-duty) read results; DA_PROGRAMME_MANAGER and
 * COLLECTOR never see results; managers / HR / crew scheduling see duty status only (other apps).
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

export const ROLE_GROUPS = ['FLIGHT_CREW', 'CABIN_CREW', 'AIRSIDE_DRIVER', 'ENGINEER', 'FORKLIFT', 'SECURITY'] as const;
export type RoleGroup = (typeof ROLE_GROUPS)[number];
export const REASONS = ['PRE_EMPLOYMENT', 'RANDOM', 'POST_INCIDENT', 'FOR_CAUSE', 'RETURN_TO_DUTY', 'FOLLOW_UP'] as const;
export type Reason = (typeof REASONS)[number];
export const PANELS = ['DRUGS_5', 'DRUGS_10', 'ALCOHOL_BREATH'] as const;
export type Panel = (typeof PANELS)[number];
export const TEST_STATUSES = [
  'REQUIRED', 'NOTIFIED', 'DISPATCHED', 'COLLECTED', 'IN_LAB', 'MRO_REVIEW', 'VERIFIED_NEGATIVE', 'VERIFIED_POSITIVE', 'REFUSAL', 'CANCELLED', 'WINDOW_MISSED',
] as const;
export type TestStatus = (typeof TEST_STATUSES)[number];
export const VERDICTS = ['NEGATIVE', 'NEGATIVE_LEGITIMATE_PRESCRIPTION', 'POSITIVE', 'POSITIVE_DILUTE', 'REFUSAL_ADULTERATED', 'REFUSAL_SUBSTITUTED', 'CANCELLED'] as const;
export type Verdict = (typeof VERDICTS)[number];
export const DUTY_STATUSES = ['AVAILABLE', 'REMOVED', 'RETURN_PENDING'] as const;
export const TEST_TYPES = ['DRUGS', 'ALCOHOL'] as const;
export type TestType = (typeof TEST_TYPES)[number];
export const EXCLUSION_REASONS = ['LEAVE', 'LONG_TERM_ABSENCE', 'NOT_SAFETY_SENSITIVE'] as const;
export type ExclusionReason = (typeof EXCLUSION_REASONS)[number];
export const SPECIMEN_TYPES = ['URINE', 'ORAL_FLUID', 'BREATH'] as const;
export type SpecimenType = (typeof SPECIMEN_TYPES)[number];
export const CUSTODY_EVENTS = ['SEALED', 'COURIER_PICKUP', 'LAB_RECEIVED'] as const;
export type CustodyEvent = (typeof CUSTODY_EVENTS)[number];
export const REFUSAL_REASONS = ['NO_SHOW', 'REFUSED', 'ADULTERATION_ATTEMPT', 'SUBSTITUTION_SUSPECTED'] as const;
export type RefusalReason = (typeof REFUSAL_REASONS)[number];
export const VALIDITIES = ['VALID', 'DILUTE', 'ADULTERATED', 'SUBSTITUTED', 'INVALID'] as const;
export const RTD_OUTCOMES = ['READY', 'NOT_READY'] as const;

// ---------------------------------------------------------------- F01 policies, pools, selections
export interface TestingPolicy {
  roleGroup: RoleGroup;
  jobCodes: string[];
  panels: Panel[];
  randomDrugRatePct: number;
  randomAlcoholRatePct: number;
  alcoholWindowHours: number;
  drugWindowHours: number;
  leadTimeMinutes: number;
  jurisdiction: string;
  consentRequired: boolean;
  approvalStatus: 'PENDING' | 'APPROVED';
  approvedBy?: string;
  version: number;
}
export interface PoolMember {
  employeeId: string;
  displayName: string;
  jobCode?: string;
}
export interface PoolExclusion {
  employeeId: string;
  reason: ExclusionReason;
  until: string;
  displayName?: string;
}
export interface Pool {
  roleGroup: RoleGroup;
  size: number;
  members: PoolMember[];
  exclusions: PoolExclusion[];
}
export interface Selection {
  id: string;
  ref: string;
  roleGroup: RoleGroup;
  period: string;
  testType?: TestType;
  poolSize: number;
  poolHash: string;
  seedCommitment: string;
  selectedCount: number;
  selected: { employeeId: string; displayName: string }[];
  createdAt: string;
  notifiedAt?: string;
}
export interface SelectionBody {
  roleGroup: RoleGroup;
  period: string;
  testType: TestType;
}
export interface CoverageRow {
  roleGroup: RoleGroup;
  poolSize: number;
  targetTests: number;
  completed: number;
  coveragePct: number;
  behindPlan: boolean;
}

// ---------------------------------------------------------------- F02 requirements
export interface Requirement {
  id: string;
  ref: string;
  employeeId: string;
  displayName: string;
  reason: Reason;
  panels: Panel[];
  status: TestStatus;
  dueAt: string;
  hoursLeft?: number;
  siteCode?: string;
  dispatchedAt?: string;
}

// ---------------------------------------------------------------- F03 collection & MRO
export interface CollectionBody {
  specimenId: string;
  specimenType: SpecimenType;
  collectedAt: string;
  siteCode: string;
  temperatureInRange?: boolean;
  sealIntact: boolean;
  donorIdentityChecked: boolean;
  splitSpecimen: boolean;
  breathScreenResult?: number;
  remarks?: string;
}
export interface LabResult {
  analyte: string;
  cutoff: number | string;
  value: number | string;
  unit: string;
  outcome: 'NEGATIVE' | 'NON_NEGATIVE';
}
export interface MroQueueItem {
  testId: string;
  ref: string;
  employeeId: string;
  displayName: string;
  reason: Reason;
  specimenId: string;
  validity: (typeof VALIDITIES)[number];
  results: LabResult[];
  receivedAt: string;
}
export interface MroDecisionBody {
  verdict: Verdict;
  prescriptionVerified?: boolean;
  interviewNote?: string;
  splitSpecimenRequested?: boolean;
}

// ---------------------------------------------------------------- F04 return to duty
export interface RtdFollowUp {
  id?: string;
  dueOn?: string;
  scheduledFor?: string;
  status?: string;
}
export interface RtdPlan {
  id: string;
  employeeId: string;
  displayName?: string;
  caseId: string;
  evaluator: string;
  treatmentProvider?: string;
  followUpTests: number;
  followUpMonths: number;
  status?: string;
  createdAt?: string;
  evaluation?: { outcome: (typeof RTD_OUTCOMES)[number]; note?: string; at?: string };
  followUps?: RtdFollowUp[];
  dutyStatus?: (typeof DUTY_STATUSES)[number];
}
export interface RtdPlanBody {
  employeeId: string;
  caseId: string;
  evaluator: string;
  treatmentProvider?: string;
  followUpTests: number;
  followUpMonths: number;
}

// ---------------------------------------------------------------- F05 reporting
export interface StatisticsRow {
  reason: Reason;
  roleGroup: RoleGroup;
  tests?: number | null;
  verifiedPositive?: number | null;
  refusals?: number | null;
  positiveRatePct?: number | null;
  suppressed: boolean;
}

export const annualReportUrl = (year: number, format: 'pdf' | 'csv'): string => bffLink(`/testing/reports/annual${buildQuery({ year, format })}`);

export function testingApi(c: AuthClient) {
  return {
    policies: () => c.api<TestingPolicy[]>('/testing/policies'),
    savePolicy: (p: TestingPolicy) => c.api<TestingPolicy>(`/testing/policies/${seg(p.roleGroup)}`, { method: 'PUT', body: p }),
    pools: (roleGroup?: string) => c.api<Pool[]>(`/testing/pools${buildQuery({ roleGroup })}`),
    addExclusion: (roleGroup: RoleGroup, body: { employeeId: string; reason: ExclusionReason; until: string }) =>
      c.api<Pool>(`/testing/pools/${seg(roleGroup)}/exclusions`, { method: 'POST', body }),
    selections: (period?: string) => c.api<Selection[]>(`/testing/selections${buildQuery({ period })}`),
    createSelection: (body: SelectionBody) => c.api<Selection>('/testing/selections', { method: 'POST', body }),
    verifySelection: (id: string) => c.api<{ reproducible: boolean }>(`/testing/selections/${seg(id)}/verify`),
    notifySelection: (id: string) => c.api<Selection | undefined>(`/testing/selections/${seg(id)}/notify`, { method: 'POST' }),
    coverage: (year: number) => c.api<CoverageRow[]>(`/testing/coverage${buildQuery({ year })}`),
    requirements: (status?: string) => c.api<Requirement[]>(`/testing/requirements${buildQuery({ status })}`),
    recordCollection: (testId: string, body: CollectionBody) => c.api<Requirement | undefined>(`/testing/tests/${seg(testId)}/collection`, { method: 'POST', body }),
    custody: (specimenId: string, body: { event: CustodyEvent; at: string; by: string }) => c.api<unknown>(`/testing/specimens/${seg(specimenId)}/custody`, { method: 'POST', body }),
    refusal: (testId: string, body: { reason: RefusalReason; observations: string }) => c.api<Requirement | undefined>(`/testing/tests/${seg(testId)}/refusal`, { method: 'POST', body }),
    mroQueue: () => c.api<MroQueueItem[]>('/testing/mro/queue'),
    mroDecision: (testId: string, body: MroDecisionBody) => c.api<unknown>(`/testing/tests/${seg(testId)}/mro-decision`, { method: 'POST', body }),
    splitRequest: (testId: string) => c.api<unknown>(`/testing/tests/${seg(testId)}/split-request`, { method: 'POST' }),
    rtdPlans: (status?: string) => c.api<RtdPlan[]>(`/testing/rtd-plans${buildQuery({ status })}`),
    createRtdPlan: (body: RtdPlanBody) => c.api<RtdPlan>('/testing/rtd-plans', { method: 'POST', body }),
    evaluateRtd: (id: string, body: { outcome: (typeof RTD_OUTCOMES)[number]; note?: string }) => c.api<RtdPlan>(`/testing/rtd-plans/${seg(id)}/evaluation`, { method: 'POST', body }),
    statistics: (year: number) => c.api<StatisticsRow[]>(`/testing/statistics${buildQuery({ year })}`),
  };
}

export type TestingApi = ReturnType<typeof testingApi>;
