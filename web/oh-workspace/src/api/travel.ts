/**
 * E11 – travel health, communicable disease, exposure pathways, vaccination campaigns, health advisories and
 * public-health reporting. Mirrors docs/increment-05/API-Contract-E09-E10-E11-E12.md §5 (paths relative to `/api/v1`).
 *
 * Where the contract names an endpoint but not every response field, the optional fields below are read defensively
 * (rendered only when present). Endpoints the UI needs but the contract does not list are marked "not in §5".
 */
import type { AuthClient } from '../auth/AuthClient';
import { buildQuery, seg } from './links';

// ---------------------------------------------------------------- §5.1 travel & posting consultation
export const TRIP_PURPOSES = ['LAYOVER', 'POSTING', 'BUSINESS'] as const;
export type TripPurpose = (typeof TRIP_PURPOSES)[number];
export const DESTINATION_SOURCES = ['IN_HOUSE', 'LICENSED'] as const;

export interface Destination {
  countryCode: string;
  name: string;
  risks: string[];
  recommendedVaccines: string[];
  prophylaxis: string[];
  advice: string;
  source: (typeof DESTINATION_SOURCES)[number];
  reviewedOn?: string | null;
}

export interface Trip {
  destinations: string[];
  departDate: string;
  returnDate: string;
  purpose: TripPurpose;
}
export interface VaccineGiven {
  vaccineCode: string;
  doseNumber: number;
  lotNumber: string;
  site: string;
  route: string;
}
export interface ProphylaxisGiven {
  drug: string;
  regimen: string;
  startDate: string;
}
export interface ConsultationBody {
  employeeId: string;
  trip: Trip;
  vaccinesGiven: VaccineGiven[];
  prophylaxis: ProphylaxisGiven[];
  advice?: string;
}
/** One recommended vaccine against the E07 immunisation status (`GET /travel/recommendations`). */
export interface TravelRecommendation {
  vaccineCode: string;
  vaccineName?: string;
  /** E07 status (COMPLETE / DUE / OVERDUE / TITRE_NEEDED) or NOT_RECORDED. */
  status?: string;
  covered?: boolean;
  lastDoseOn?: string | null;
  nextDueOn?: string | null;
  destinations?: string[];
  note?: string;
}
export interface TravelConsultation {
  id: string;
  ref?: string;
  employeeId: string;
  displayName?: string;
  trip: Trip;
  recommendations?: TravelRecommendation[];
  vaccinesGiven?: VaccineGiven[];
  prophylaxis?: ProphylaxisGiven[];
  advice?: string;
  certificateDocumentId?: string | null;
  createdAt?: string;
  clinician?: string;
}

// ---------------------------------------------------------------- §5.2 communicable disease & contact tracing
export interface CdCondition {
  code: string;
  name: string;
  notifiable: boolean;
  reportWithinHours?: number | null;
  exclusionDays?: number | null;
  foodHandlerExclusion: boolean;
  monitoringDays: number;
}
export const EXCLUSION_DECISIONS = ['EXCLUDED', 'ISOLATING', 'CLEARED'] as const;
export type ExclusionDecision = (typeof EXCLUSION_DECISIONS)[number];
export const TRACE_INCLUDE = ['SAME_DUTY', 'PROXIMITY_GROUP', 'FACILITY'] as const;
export type TraceInclude = (typeof TRACE_INCLUDE)[number];
export const RISK_LEVELS = ['HIGH', 'MEDIUM', 'LOW'] as const;
export type RiskLevel = (typeof RISK_LEVELS)[number];

export interface Exclusion {
  decision: ExclusionDecision;
  from: string;
  until?: string;
  returnCriteria: string[];
}
export interface CdCase {
  id: string;
  ref: string;
  status: string;
  notifiable: boolean;
  reportDueAt?: string | null;
  employeeId?: string;
  displayName?: string;
  conditionCode?: string;
  conditionName?: string;
  onsetDate?: string;
  confirmedOn?: string | null;
  exclusion?: Exclusion | null;
  /** Time the last contact list was generated and its hours since confirmation (KPI ≤ 4 h). */
  tracedAt?: string | null;
  hoursSinceConfirmation?: number | null;
  createdAt?: string;
}
export type CheckIn = string | { at?: string; symptoms?: string[]; temperatureC?: number | null };
export interface TracedContact {
  id: string;
  employeeId: string;
  displayName: string;
  contactType: string;
  dutyRef?: string | null;
  overlapMinutes: number;
  riskLevel: RiskLevel;
  notifiedAt?: string | null;
  monitoringUntil?: string | null;
  lastCheckIn?: CheckIn | null;
  symptomatic?: boolean;
}
export interface TraceBody {
  windowFrom: string;
  windowTo: string;
  include: TraceInclude[];
}
export interface TraceResult {
  generatedAt: string;
  hoursSinceConfirmation: number;
  contacts: TracedContact[];
}

// ---------------------------------------------------------------- §5.3 exposure pathways with protocol timers
export const PATHWAYS = ['NEEDLESTICK_BBP', 'CHEMICAL_DGR', 'CABIN_FUME', 'HEAT_ILLNESS', 'BITE_STING'] as const;
export type PathwayCode = (typeof PATHWAYS)[number];
export const TIMER_STATUSES = ['PENDING', 'DONE', 'OVERDUE'] as const;
export type TimerStatus = (typeof TIMER_STATUSES)[number];

export interface PathwayTemplate {
  code: string;
  name?: string;
  description?: string;
  tasks?: { code: string; label: string; dueWithinMinutes?: number }[];
}
export interface Chemical {
  code: string;
  name: string;
  unNumber?: string | null;
  sdsUrl: string;
  hazards: string[] | string;
}
export interface ExposureTimer {
  code: string;
  label: string;
  dueAt: string;
  status: TimerStatus;
  completedAt?: string | null;
  note?: string | null;
}
export interface SerologyPoint {
  dueOn: string;
  done: boolean;
  label?: string;
}
export interface Exposure {
  id: string;
  ref: string;
  caseId: string;
  employeeId?: string;
  displayName?: string;
  pathway: PathwayCode | string;
  occurredAt?: string;
  status?: string;
  chemicalCode?: string | null;
  flightRef?: string | null;
  sourceKnown?: boolean | null;
  timers: ExposureTimer[];
  serology: SerologyPoint[];
}
export interface ExposureBody {
  employeeId: string;
  pathway: string;
  occurredAt: string;
  details?: string;
  chemicalCode?: string;
  flightRef?: string;
  sourceKnown?: boolean;
}

// ---------------------------------------------------------------- §5.4 vaccination campaigns
export type CampaignEligibility = { all: true } | { jobCodes: string[] } | { subsidiaries: string[] };
export interface VaccinationCampaign {
  id: string;
  name: string;
  vaccineCode: string;
  eligibility: CampaignEligibility | { all?: boolean; jobCodes?: string[]; subsidiaries?: string[] };
  startDate: string;
  endDate: string;
  siteCodes: string[];
  targetUptakePct: number;
  status?: string;
  invitedAt?: string | null;
}
export type VaccinationCampaignBody = Omit<VaccinationCampaign, 'id' | 'status' | 'invitedAt'>;
export interface CampaignAdministration {
  employeeId?: string;
  badgeId?: string;
  lotNumber?: string;
  lotBarcode?: string;
  site: string;
  route: string;
  consentConfirmed: true;
}
export const AE_EVENTS = ['LOCAL_REACTION', 'FEVER', 'SYNCOPE', 'ANAPHYLAXIS', 'OTHER'] as const;
export const AE_SEVERITIES = ['MILD', 'MODERATE', 'SEVERE'] as const;
export interface AdverseEventBody {
  employeeId: string;
  event: (typeof AE_EVENTS)[number];
  severity: (typeof AE_SEVERITIES)[number];
  onsetAt: string;
  actions: string;
}
export const UPTAKE_GROUP_BY = ['SUBSIDIARY', 'DEPARTMENT'] as const;
export interface UptakeRow {
  key: string;
  eligible?: number | null;
  vaccinated?: number | null;
  declined?: number | null;
  uptakePct?: number | null;
  suppressed: boolean;
}

// ---------------------------------------------------------------- §5.5 health advisories
export const ADVISORY_SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const;
export type AdvisorySeverity = (typeof ADVISORY_SEVERITIES)[number];
export const ADVISORY_BODY_MAX = 4000;
export interface AdvisoryBody {
  title: string;
  destinationCode?: string;
  baseCode?: string;
  roles: string[];
  severity: AdvisorySeverity;
  body: string;
  validFrom: string;
  validUntil: string;
}
export interface Advisory extends AdvisoryBody {
  id: string;
  publishedAt?: string;
  author?: string;
  targeted?: number;
  acknowledged?: number;
  ratePct?: number;
}
export interface AdvisoryAcks {
  targeted: number;
  acknowledged: number;
  ratePct: number;
  pending: (string | { employeeId: string; displayName?: string })[];
}

// ---------------------------------------------------------------- §5.6 public-health reporting
export const SUBMIT_CHANNELS = ['EMAIL', 'PORTAL', 'PHONE'] as const;
export const DIRECTIONS = ['IN', 'OUT'] as const;
export interface Correspondence {
  id?: string;
  direction: (typeof DIRECTIONS)[number];
  summary: string;
  documentId?: string | null;
  at?: string;
  author?: string;
}
export interface PublicHealthReport {
  id: string;
  ref?: string;
  communicableCaseId?: string | null;
  exposureId?: string | null;
  sourceRef?: string;
  conditionCode?: string;
  authorityCode: string;
  status: string;
  dueAt?: string | null;
  hoursLeft?: number | null;
  documentId?: string | null;
  submittedAt?: string | null;
  channel?: string | null;
  reference?: string | null;
  correspondence?: Correspondence[];
}

const cd = (id: string) => `/communicable/cases/${seg(id)}`;
const exp = (id: string) => `/exposures/${seg(id)}`;
const vc = (id: string) => `/vaccination-campaigns/${seg(id)}`;
const ph = (id: string) => `/public-health/reports/${seg(id)}`;

export function travelApi(c: AuthClient) {
  return {
    // §5.1
    destinations: () => c.api<Destination[]>('/travel/destinations'),
    destination: (countryCode: string) => c.api<Destination>(`/travel/destinations/${seg(countryCode)}`),
    saveDestination: (d: Destination) => c.api<Destination>(`/travel/destinations/${seg(d.countryCode)}`, { method: 'PUT', body: d }),
    createConsultation: (b: ConsultationBody) => c.api<TravelConsultation>('/travel/consultations', { method: 'POST', body: b }),
    consultations: (employeeId: string) => c.api<TravelConsultation[]>(`/travel/consultations${buildQuery({ employeeId })}`),
    recommendations: (employeeId: string, destinations: readonly string[]) =>
      c.api<TravelRecommendation[]>(`/travel/recommendations${buildQuery({ employeeId, destinations: destinations.join(',') })}`),

    // §5.2
    conditions: () => c.api<CdCondition[]>('/communicable/conditions'),
    /** Not in §5 (the contract lists POST only) – list for the `#/communicable` worklist. */
    cases: (status?: string) => c.api<CdCase[]>(`/communicable/cases${buildQuery({ status })}`),
    /** Not in §5 – single case for `#/communicable/{id}`. */
    case: (id: string) => c.api<CdCase>(cd(id)),
    createCase: (b: { employeeId: string; conditionCode: string; onsetDate: string; confirmedOn?: string }) => c.api<CdCase>('/communicable/cases', { method: 'POST', body: b }),
    exclusion: (id: string, b: Exclusion) => c.api<CdCase>(`${cd(id)}/exclusion`, { method: 'POST', body: b }),
    trace: (id: string, b: TraceBody) => c.api<TraceResult>(`${cd(id)}/trace`, { method: 'POST', body: b }),
    contacts: (id: string) => c.api<TracedContact[]>(`${cd(id)}/contacts`),
    notifyContacts: (id: string, contactIds: string[]) => c.api<unknown>(`${cd(id)}/contacts/notify`, { method: 'POST', body: { contactIds } }),
    clear: (id: string, criteriaMet: string[]) => c.api<CdCase>(`${cd(id)}/clear`, { method: 'POST', body: { criteriaMet } }),

    // §5.3
    pathways: () => c.api<PathwayTemplate[]>('/exposures/pathways'),
    chemicals: () => c.api<Chemical[]>('/exposures/chemicals'),
    createExposure: (b: ExposureBody) => c.api<Exposure>('/exposures', { method: 'POST', body: b }),
    exposures: (status?: string) => c.api<Exposure[]>(`/exposures${buildQuery({ status })}`),
    exposure: (id: string) => c.api<Exposure>(exp(id)),
    completeTask: (id: string, code: string, note?: string) => c.api<Exposure>(`${exp(id)}/tasks/${seg(code)}/complete`, { method: 'POST', body: note ? { note } : {} }),

    // §5.4
    campaigns: () => c.api<VaccinationCampaign[]>('/vaccination-campaigns'),
    createCampaign: (b: VaccinationCampaignBody) => c.api<VaccinationCampaign>('/vaccination-campaigns', { method: 'POST', body: b }),
    invite: (id: string) => c.api<{ invited?: number } | undefined>(`${vc(id)}/invite`, { method: 'POST', body: {} }),
    administer: (id: string, b: CampaignAdministration) => c.api<unknown>(`${vc(id)}/administrations`, { method: 'POST', body: b }),
    decline: (id: string, b: { employeeId: string; reason: string }) => c.api<unknown>(`${vc(id)}/declines`, { method: 'POST', body: b }),
    adverseEvent: (id: string, b: AdverseEventBody) => c.api<unknown>(`${vc(id)}/adverse-events`, { method: 'POST', body: b }),
    uptake: (id: string, groupBy: string) => c.api<UptakeRow[]>(`${vc(id)}/uptake${buildQuery({ groupBy })}`),

    // §5.5
    advisories: () => c.api<Advisory[]>('/advisories'),
    createAdvisory: (b: AdvisoryBody) => c.api<Advisory>('/advisories', { method: 'POST', body: b }),
    acknowledgements: (id: string) => c.api<AdvisoryAcks>(`/advisories/${seg(id)}/acknowledgements`),

    // §5.6
    reports: (status?: string) => c.api<PublicHealthReport[]>(`/public-health/reports${buildQuery({ status })}`),
    createReport: (b: { communicableCaseId?: string; exposureId?: string; authorityCode: string }) => c.api<PublicHealthReport>('/public-health/reports', { method: 'POST', body: b }),
    submitReport: (id: string, b: { channel: string; submittedAt: string; reference?: string }) => c.api<PublicHealthReport>(`${ph(id)}/submit`, { method: 'POST', body: b }),
    correspondence: (id: string, b: { direction: string; summary: string; documentId?: string }) => c.api<PublicHealthReport | Correspondence>(`${ph(id)}/correspondence`, { method: 'POST', body: b }),
  };
}
export type TravelApi = ReturnType<typeof travelApi>;
