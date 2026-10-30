/**
 * E09 – mental health, psychosocial risk & peer support. Mirrors API-Contract-E09-E10-E11-E12.md §3 (paths relative to
 * `/api/v1`). Restricted class: only named PSYCH_CLINICIAN users read support-case content; every other OH role only gets the
 * `{restrictedCases: n}` placeholder. Employee (`/me/...`), manager (`/workspace/...`) and partner (`/partner/...`) endpoints
 * live in the other apps.
 */
import type { AuthClient } from '../auth/AuthClient';
import { buildQuery, seg } from './links';
import type { ActiveConsent } from './treatment';

export const SUPPORT_STATUSES = ['OPEN', 'SCREENED', 'ACTIVE', 'REFERRED', 'RELIEF', 'CLOSED'] as const;
export type SupportStatus = (typeof SUPPORT_STATUSES)[number];
export const RISKS = ['LOW', 'MODERATE', 'HIGH', 'URGENT'] as const;
export type Risk = (typeof RISKS)[number];
export const SHARING_CHOICES = ['NOTHING', 'FITNESS_ONLY', 'FITNESS_AND_ADJUSTMENTS'] as const;
export type Sharing = (typeof SHARING_CHOICES)[number];
export const INSTRUMENTS = ['PHQ9', 'GAD7'] as const;
export type Instrument = (typeof INSTRUMENTS)[number];
export const BANDS = ['MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'] as const;
export type Band = (typeof BANDS)[number];
export const CONCERNS = ['WORK_STRESS', 'LOW_MOOD', 'ANXIETY', 'SLEEP', 'BEREAVEMENT', 'TRAUMA', 'SUBSTANCE', 'RELATIONSHIP', 'OTHER'] as const;
export type Concern = (typeof CONCERNS)[number];
export const SUPPORT_SOURCES = ['SELF', 'CLINICIAN', 'PEER', 'CISM', 'PROGRAMME_REFERRAL'] as const;
export type SupportSource = (typeof SUPPORT_SOURCES)[number];
export const URGENT_OUTCOMES = ['REACHED_SAFE', 'REACHED_ESCALATED_EMERGENCY', 'NOT_REACHED_RETRY', 'NOT_REACHED_WELFARE_CHECK'] as const;
export type UrgentOutcome = (typeof URGENT_OUTCOMES)[number];
export const EAP_URGENCIES = ['ROUTINE', 'PRIORITY'] as const;
export type EapUrgency = (typeof EAP_URGENCIES)[number];
export const EAP_PARTNERS = ['EAP_WELLBEING'] as const;
export const RELIEF_DECISIONS = ['FIT', 'EXTEND'] as const;
export const FITNESS_DECISIONS = ['FIT', 'FIT_WITH_LIMITATIONS', 'TEMPORARILY_UNFIT', 'REFER_TO_AUTHORITY'] as const;
export type MhFitnessDecision = (typeof FITNESS_DECISIONS)[number];
export const PEER_CATEGORIES = ['STRESS', 'FATIGUE', 'RELATIONSHIP', 'BEREAVEMENT', 'SUBSTANCE', 'TRAUMA', 'OTHER'] as const;
export type PeerCategory = (typeof PEER_CATEGORIES)[number];
export const PEER_OUTCOMES = ['SUPPORTED', 'SIGNPOSTED', 'REFERRED_TO_TRUSTED_PROFESSIONAL', 'EMERGENCY'] as const;
export type PeerOutcome = (typeof PEER_OUTCOMES)[number];
export const CISM_CATEGORIES = ['FATALITY', 'ASSAULT', 'FUME_EVENT', 'AIRCRAFT_ACCIDENT', 'SERIOUS_INJURY', 'EMERGENCY_LANDING', 'OTHER'] as const;
export const CISM_RESPONSES = ['ACCEPTED', 'DECLINED'] as const;
export const CISM_SESSION_TYPES = ['GROUP', 'INDIVIDUAL'] as const;
export const PSYCHOSOCIAL_FACTORS = ['WORKLOAD', 'SHIFT_FATIGUE', 'VIOLENCE_AGGRESSION', 'ISOLATION', 'CHANGE', 'ROLE_CLARITY', 'SUPPORT', 'RECOGNITION'] as const;
export type PsychosocialFactor = (typeof PSYCHOSOCIAL_FACTORS)[number];
export const ACTION_STATUSES = ['OPEN', 'DONE'] as const;
export type Rag = 'RED' | 'AMBER' | 'GREEN';

// ---------------------------------------------------------------- F01 / F02 support cases
export interface EapReferral {
  id: string;
  ref: string;
  status: string;
  firstAppointmentDueBy?: string;
  partnerCode?: string;
  sessionsAuthorised?: number;
  urgency?: EapUrgency;
  sentAt?: string;
}
export interface Relief {
  id: string;
  employeeId?: string;
  from: string;
  expectedUntil?: string;
  status?: string;
  until?: string;
}
export interface SupportCase {
  id: string;
  ref: string;
  employeeId: string;
  displayName: string;
  status: SupportStatus;
  risk: Risk;
  concern: Concern;
  source: SupportSource;
  openedAt: string;
  lastContactAt?: string;
  sharing: Sharing;
  eapReferral?: EapReferral;
  relief?: Relief;
  /** Restricted kernel case behind the support case, when the server exposes it (consents are read per kernel case). */
  caseId?: string;
}
export interface SupportNote {
  id?: string;
  text: string;
  author?: string;
  createdAt?: string;
}
export interface ScreenResult {
  id?: string;
  instrument: Instrument;
  score: number;
  band: Band;
  urgent: boolean;
  nextSteps?: string[];
  administeredBy?: string;
  source?: string;
  completedAt?: string;
}
export interface UrgentContact {
  id?: string;
  outcome: UrgentOutcome;
  note?: string;
  at?: string;
  by?: string;
}
export interface SupportCaseDetail extends SupportCase {
  notes?: SupportNote[];
  screens?: ScreenResult[];
  contacts?: UrgentContact[];
  timeline?: { at: string; type?: string; summary: string; actor?: string }[];
  closedAt?: string;
  closeReason?: string;
}
export interface UrgentItem {
  id?: string;
  caseId: string;
  ref?: string;
  employeeId?: string;
  displayName?: string;
  reason?: string;
  source?: string;
  band?: Band;
  raisedAt?: string;
  callBackBy: string;
}
export type ProtocolStep = string | { order?: number; title?: string; text?: string };
export interface UrgentProtocol {
  version: string | number;
  steps: ProtocolStep[];
}
export interface Governance {
  instruments?: { code: string; title?: string; licence?: string; licenceStatus?: string }[];
  protocolVersion?: string | number;
  signOff?: { status?: string; pendingWith?: string[]; signedBy?: string; signedAt?: string } | string;
  [k: string]: unknown;
}

// ---------------------------------------------------------------- F03 peer support
export interface TrainingRecord {
  course: string;
  completedOn: string;
  expiresOn?: string;
}
export interface Volunteer {
  id: string;
  username: string;
  displayName: string;
  base: string;
  languages: string[];
  available: boolean;
  trainingRecords: TrainingRecord[];
}
export type VolunteerBody = Omit<Volunteer, 'id'>;
export interface RoutedRequest {
  contactCode: string;
  alias: string;
  callbackPhone?: string;
  language?: string;
  requestedAt: string;
}
export interface PeerContactBody {
  contactCode: string;
  date: string;
  durationMinutes: number;
  category: PeerCategory;
  outcome: PeerOutcome;
}
export interface PeerContact extends PeerContactBody {
  id: string;
  referredAt?: string;
}
export interface SmsRow {
  base: string;
  category: string;
  contacts?: number | null;
  suppressed: boolean;
}
export interface SmsReport {
  quarter: string;
  rows: SmsRow[];
  totals?: { contacts?: number | null; suppressed?: boolean } | number | null;
  sentAt?: string;
  reportRef?: string;
  minGroupSize?: number;
}

// ---------------------------------------------------------------- F04 CISM
export interface CismEvent {
  id: string;
  ref: string;
  title: string;
  category: string;
  occurredAt: string;
  affected: number;
  offered: number;
  accepted: number;
  offerDueBy: string;
  slaMet?: boolean | null;
}
export interface FollowUpState {
  dueOn: string;
  done: boolean;
}
export interface CismAffected {
  employeeId: string;
  displayName: string;
  dutyRole?: string;
  offerSentAt?: string;
  response?: string;
  sessionType?: string;
  attendedAt?: string;
  followUp1w?: FollowUpState;
  followUp4w?: FollowUpState;
  referred?: boolean;
}
/** `GET /cism/events/{id}` – `affected` is the person list here (a count in the event list). */
export interface CismEventDetail extends Omit<CismEvent, 'affected'> {
  location?: string;
  dutyRefs?: string[];
  affected: CismAffected[];
}
export interface CismEventBody {
  title: string;
  category: string;
  occurredAt: string;
  location: string;
  dutyRefs: string[];
}
export interface AffectedUpdate {
  response?: string;
  sessionType?: string;
  attendedAt?: string;
  followUp1wDone?: boolean;
  followUp4wDone?: boolean;
  referToSupport?: boolean;
}

// ---------------------------------------------------------------- F05 psychosocial
export interface FactorRating {
  factor: PsychosocialFactor;
  likelihood: number;
  severity: number;
  controls: string;
  score?: number;
  rag?: Rag;
}
export interface PsychosocialAction {
  id: string;
  title: string;
  owner: string;
  dueDate: string;
  status: (typeof ACTION_STATUSES)[number];
}
export interface PsychosocialAssessment {
  id: string;
  unitCode: string;
  assessedOn: string;
  factors: FactorRating[];
  reviewDate: string;
  overallScore?: number;
  rag?: Rag;
  actions?: PsychosocialAction[];
  assessedBy?: string;
}
export interface AssessmentBody {
  unitCode: string;
  assessedOn: string;
  factors: { factor: PsychosocialFactor; likelihood: number; severity: number; controls: string }[];
  reviewDate: string;
}
export interface PsychosocialDashboardRow {
  unitCode: string;
  assessmentId?: string;
  assessedOn?: string;
  overallScore?: number;
  rag?: Rag;
  reviewDate?: string;
  overdueReview?: boolean;
  openActions?: number;
  overdueActions?: number;
  supportCaseRatePer100?: number | null;
  suppressed?: boolean;
}

// ---------------------------------------------------------------- F06 fitness
export interface Medication {
  name: string;
  drugClass: string;
  dose: string;
  startDate: string;
}
export interface FitnessAssessmentBody {
  employeeId: string;
  supportCaseId?: string;
  standardCode: string;
  medications: Medication[];
  findings: string;
  decision: MhFitnessDecision;
  limitations?: string;
  reviewDate: string;
  authorityReportRequired: boolean;
}
export interface FitnessAssessment extends Omit<FitnessAssessmentBody, 'findings'> {
  id: string;
  findings?: string;
  assessedBy?: string;
  assessedAt?: string;
  documentId?: string;
}

const sc = (id: string) => `/support/cases/${seg(id)}`;

export function supportApi(c: AuthClient) {
  return {
    // F01
    cases: (f: { status?: string; risk?: string } = {}) => c.api<SupportCase[]>(`/support/cases${buildQuery(f)}`),
    supportCase: (id: string) => c.api<SupportCaseDetail>(sc(id)),
    openCase: (body: { employeeId: string; concern: Concern; risk: Risk; note?: string }) => c.api<SupportCase>('/support/cases', { method: 'POST', body }),
    addNote: (id: string, text: string) => c.api<SupportNote>(`${sc(id)}/notes`, { method: 'POST', body: { text } }),
    closeCase: (id: string, reason: string) => c.api<SupportCase>(`${sc(id)}/close`, { method: 'POST', body: { reason } }),
    placeholder: (employeeId: string) => c.api<{ restrictedCases: number }>(`/support/placeholder${buildQuery({ employeeId })}`),
    // F02
    administerScreen: (id: string, instrument: Instrument, answers: number[]) => c.api<ScreenResult>(`${sc(id)}/screens`, { method: 'POST', body: { instrument, answers } }),
    urgent: () => c.api<UrgentItem[]>('/support/urgent'),
    urgentProtocol: () => c.api<UrgentProtocol>('/support/protocols/urgent-risk'),
    urgentContact: (id: string, outcome: UrgentOutcome, note: string) => c.api<UrgentContact>(`${sc(id)}/urgent-contact`, { method: 'POST', body: { outcome, note } }),
    /** Active TREATMENT_SHARING_PARTNER consents of the case employee (existing kernel endpoint on the restricted case). */
    consents: (caseId: string) => c.api<ActiveConsent[]>(`/cases/${seg(caseId)}/consents${buildQuery({ purpose: 'TREATMENT_SHARING_PARTNER' })}`),
    eapReferral: (id: string, body: { partnerCode: string; sessionsAuthorised: number; urgency: EapUrgency; consentId: string }) =>
      c.api<EapReferral>(`${sc(id)}/eap-referrals`, { method: 'POST', body }),
    governance: () => c.api<Governance>('/support/governance'),
    // F03
    volunteers: () => c.api<Volunteer[]>('/peer-support/volunteers'),
    createVolunteer: (body: VolunteerBody) => c.api<Volunteer>('/peer-support/volunteers', { method: 'POST', body }),
    updateVolunteer: (id: string, body: VolunteerBody) => c.api<Volunteer>(`/peer-support/volunteers/${seg(id)}`, { method: 'PUT', body }),
    myRequests: () => c.api<RoutedRequest[]>('/peer-support/my-requests'),
    logContact: (body: PeerContactBody) => c.api<PeerContact>('/peer-support/contacts', { method: 'POST', body }),
    referContact: (id: string) => c.api<PeerContact>(`/peer-support/contacts/${seg(id)}/refer`, { method: 'POST' }),
    startRelief: (body: { employeeId: string; from: string; expectedUntil?: string }) => c.api<Relief>('/support/relief', { method: 'POST', body }),
    returnFromRelief: (id: string, body: { decision: (typeof RELIEF_DECISIONS)[number]; until?: string }) => c.api<Relief>(`/support/relief/${seg(id)}/return`, { method: 'POST', body }),
    quarterly: (quarter: string) => c.api<SmsReport>(`/peer-support/reports/quarterly${buildQuery({ quarter })}`),
    sendQuarterly: (quarter: string) => c.api<SmsReport>(`/peer-support/reports/quarterly/send${buildQuery({ quarter })}`, { method: 'POST', body: { quarter } }),
    // F04
    cismEvents: () => c.api<CismEvent[]>('/cism/events'),
    cismEvent: (id: string) => c.api<CismEventDetail>(`/cism/events/${seg(id)}`),
    createCismEvent: (body: CismEventBody) => c.api<CismEvent>('/cism/events', { method: 'POST', body }),
    sendOffers: (id: string) => c.api<CismEvent>(`/cism/events/${seg(id)}/offers`, { method: 'POST' }),
    updateAffected: (id: string, employeeId: string, body: AffectedUpdate) => c.api<CismAffected>(`/cism/events/${seg(id)}/affected/${seg(employeeId)}`, { method: 'PUT', body }),
    // F05
    assessments: (unitCode?: string) => c.api<PsychosocialAssessment[]>(`/psychosocial/assessments${buildQuery({ unitCode })}`),
    createAssessment: (body: AssessmentBody) => c.api<PsychosocialAssessment>('/psychosocial/assessments', { method: 'POST', body }),
    addAction: (id: string, body: Omit<PsychosocialAction, 'id'>) => c.api<PsychosocialAction>(`/psychosocial/assessments/${seg(id)}/actions`, { method: 'POST', body }),
    updateAction: (id: string, actionId: string, body: Omit<PsychosocialAction, 'id'>) =>
      c.api<PsychosocialAction>(`/psychosocial/assessments/${seg(id)}/actions/${seg(actionId)}`, { method: 'PUT', body }),
    psychosocialDashboard: () => c.api<PsychosocialDashboardRow[]>('/psychosocial/dashboard'),
    // F06
    fitnessAssessments: (employeeId: string) => c.api<FitnessAssessment[]>(`/support/fitness-assessments${buildQuery({ employeeId })}`),
    createFitnessAssessment: (body: FitnessAssessmentBody) => c.api<FitnessAssessment>('/support/fitness-assessments', { method: 'POST', body }),
  };
}

export type SupportApi = ReturnType<typeof supportApi>;
