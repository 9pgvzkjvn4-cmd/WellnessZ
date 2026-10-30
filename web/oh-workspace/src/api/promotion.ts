/**
 * E12 – health promotion & prevention: anonymised insights, campaign planner, screening events and results,
 * ergonomic assessments and actions, campaign reports. Mirrors docs/increment-05/API-Contract-E09-E10-E11-E12.md §6
 * (paths relative to `/api/v1`). HEALTH_PROMOTION_LEAD screens only ever read aggregated, suppressed figures.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';
import type { JobDemandProfile } from './platform';

export const PROMOTION_RISKS = ['MSK', 'HEARING', 'METABOLIC', 'FATIGUE', 'MENTAL_WELLBEING'] as const;
export type PromotionRisk = (typeof PROMOTION_RISKS)[number];

export interface Insight {
  unitCode: string;
  risk: PromotionRisk;
  indicator: string;
  value?: number | null;
  period: string;
  suppressed: boolean;
}

export const PROMOTION_CAMPAIGN_STATUSES = ['PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED'] as const;
export type PromotionCampaignStatus = (typeof PROMOTION_CAMPAIGN_STATUSES)[number];
export interface Money {
  amount: string;
  currency: string;
}
export interface CampaignKpi {
  metric: string;
  target: number;
}
export interface PromotionCampaignBody {
  title: string;
  risk: PromotionRisk;
  objective: string;
  targetUnits: string[];
  insightRef?: string;
  budget: Money;
  startDate: string;
  endDate: string;
  owners: string[];
  kpis: CampaignKpi[];
  status: PromotionCampaignStatus;
}
export interface PromotionCampaign extends PromotionCampaignBody {
  id: string;
  publishedAt?: string | null;
}

export const SCREENING_TYPES = ['BIOMETRIC', 'MSK', 'HEARING', 'VISION', 'MENTAL_WELLBEING'] as const;
export type ScreeningType = (typeof SCREENING_TYPES)[number];
export interface ScreeningParticipant {
  employeeId: string;
  displayName?: string;
  resultRecorded?: boolean;
  abnormal?: boolean;
}
export interface ScreeningEvent {
  id: string;
  campaignId?: string | null;
  type: ScreeningType;
  siteCode: string;
  date: string;
  /** Person-level list – only returned to clinicians; the promotion lead sees counts. */
  participants?: ScreeningParticipant[];
  participantCount?: number;
  resultsRecorded?: number;
}
export const MEASURE_KEYS = ['systolic', 'diastolic', 'bmi', 'glucoseMmol', 'cholesterolMmol', 'waistCm', 'mskScore', 'wellbeingScore'] as const;
export type MeasureKey = (typeof MEASURE_KEYS)[number];
export type Measures = Partial<Record<MeasureKey, number>>;
export interface ScreeningResultResponse {
  id?: string;
  abnormal: boolean;
  flags?: (string | { measure: string; flag?: string; value?: number })[];
  followUpCaseId?: string | null;
  followUpCaseRef?: string | null;
  followUpDueAt?: string | null;
  letterDocumentId?: string | null;
}

export const ASSESSMENT_TYPES = ['DSE_WORKSTATION', 'MANUAL_HANDLING', 'TASK'] as const;
export type AssessmentType = (typeof ASSESSMENT_TYPES)[number];
export type Rag = 'RED' | 'AMBER' | 'GREEN';
export const ACTION_STATUSES = ['OPEN', 'DONE'] as const;
export interface ErgoAction {
  id: string;
  title: string;
  owner: string;
  dueDate: string;
  status: (typeof ACTION_STATUSES)[number];
  closedAt?: string | null;
}
export interface ErgoAssessmentBody {
  type: AssessmentType;
  unitCode: string;
  jobCode: string;
  employeeId?: string;
  location: string;
  checklist: Record<string, number>;
  findings?: string;
}
export interface ErgoAssessment extends ErgoAssessmentBody {
  id: string;
  ref?: string;
  riskScore: number;
  rag: Rag;
  jobDemandProfile?: JobDemandProfile | null;
  photos?: { id: string; fileName?: string; uploadedAt?: string }[];
  actions?: ErgoAction[];
  createdAt?: string;
  assessor?: string;
}

export const REPORT_GROUP_BY = ['SUBSIDIARY', 'DEPARTMENT'] as const;
export interface ParticipationRow {
  key: string;
  invited?: number | null;
  participated?: number | null;
  pct?: number | null;
  suppressed: boolean;
}
export interface OutcomeRow {
  metric: string;
  baseline?: number | null;
  current?: number | null;
  delta?: number | null;
}
export interface CampaignReport {
  participation: ParticipationRow[];
  outcomes: OutcomeRow[];
}

/** CSV of the campaign report (same grouping, suppression applied server-side) – plain BFF download. */
export const campaignReportCsvUrl = (id: string, groupBy: string): string => bffLink(`/promotion/campaigns/${seg(id)}/report${buildQuery({ groupBy, format: 'csv' })}`);

const pc = (id: string) => `/promotion/campaigns/${seg(id)}`;
const se = (id: string) => `/promotion/screening-events/${seg(id)}`;
const ea = (id: string) => `/ergonomics/assessments/${seg(id)}`;

export function promotionApi(c: AuthClient) {
  return {
    insights: () => c.api<Insight[]>('/promotion/insights'),

    campaigns: () => c.api<PromotionCampaign[]>('/promotion/campaigns'),
    campaign: (id: string) => c.api<PromotionCampaign>(pc(id)),
    createCampaign: (b: PromotionCampaignBody) => c.api<PromotionCampaign>('/promotion/campaigns', { method: 'POST', body: b }),
    updateCampaign: (id: string, b: PromotionCampaignBody) => c.api<PromotionCampaign>(pc(id), { method: 'PUT', body: b }),
    publish: (id: string) => c.api<PromotionCampaign | undefined>(`${pc(id)}/publish`, { method: 'POST', body: {} }),
    report: (id: string, groupBy: string) => c.api<CampaignReport>(`${pc(id)}/report${buildQuery({ groupBy })}`),

    screeningEvents: () => c.api<ScreeningEvent[]>('/promotion/screening-events'),
    screeningEvent: (id: string) => c.api<ScreeningEvent>(se(id)),
    createScreeningEvent: (b: { campaignId?: string; type: ScreeningType; siteCode: string; date: string }) => c.api<ScreeningEvent>('/promotion/screening-events', { method: 'POST', body: b }),
    addParticipants: (id: string, employeeIds: string[]) => c.api<ScreeningEvent | undefined>(`${se(id)}/participants`, { method: 'POST', body: { employeeIds } }),
    recordResult: (id: string, b: { employeeId: string; measures: Measures }) => c.api<ScreeningResultResponse>(`${se(id)}/results`, { method: 'POST', body: b }),

    assessments: () => c.api<ErgoAssessment[]>('/ergonomics/assessments'),
    assessment: (id: string) => c.api<ErgoAssessment>(ea(id)),
    createAssessment: (b: ErgoAssessmentBody) => c.api<ErgoAssessment>('/ergonomics/assessments', { method: 'POST', body: b }),
    uploadPhoto: (id: string, file: Blob, fileName: string) => {
      const fd = new FormData();
      fd.append('file', file, fileName);
      return c.api<unknown>(`${ea(id)}/photos`, { method: 'POST', body: fd });
    },
    createAction: (id: string, b: Omit<ErgoAction, 'id' | 'closedAt'>) => c.api<ErgoAction>(`${ea(id)}/actions`, { method: 'POST', body: b }),
    updateAction: (id: string, actionId: string, b: Omit<ErgoAction, 'id' | 'closedAt'>) => c.api<ErgoAction>(`${ea(id)}/actions/${seg(actionId)}`, { method: 'PUT', body: b }),
  };
}
export type PromotionApi = ReturnType<typeof promotionApi>;
