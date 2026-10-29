/**
 * E04 – injury & illness management, treatment coordination and partner referrals.
 * Mirrors API-Contract-E04-E08.md §3 (paths relative to `/api/v1`). Partner (`/partner/...`), employee (`/me/...`) and
 * integration endpoints live in the other apps.
 */
import type { AuthClient } from '../auth/AuthClient';
import type { Money } from '../domain/money';
import { bffLink, buildQuery, seg } from './links';

export type { Money };

export const PLAN_STATUSES = ['DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED'] as const;
export type PlanStatus = (typeof PLAN_STATUSES)[number] | 'SUPERSEDED';
export const INTERVENTION_TYPES = ['PHYSIOTHERAPY', 'MEDICATION', 'SPECIALIST_REVIEW', 'IMAGING', 'SELF_CARE', 'PSYCHOLOGICAL_THERAPY', 'OH_REVIEW', 'OTHER'] as const;
export type InterventionType = (typeof INTERVENTION_TYPES)[number];
export const FOLLOW_UP_TYPES = ['OH_REVIEW', 'PHONE', 'PHYSIOTHERAPY', 'SPECIALIST', 'TELEHEALTH'] as const;
export type FollowUpType = (typeof FOLLOW_UP_TYPES)[number];
export const FOLLOW_UP_STATUSES = ['PLANNED', 'BOOKED', 'CONFIRMED', 'DECLINED', 'DONE', 'MISSED', 'CANCELLED'] as const;
export type FollowUpStatus = (typeof FOLLOW_UP_STATUSES)[number];
export const REFERRAL_STATUSES = ['DRAFT', 'SENT', 'ACCEPTED', 'DECLINED', 'SCHEDULED', 'SEEN', 'REPORT_RECEIVED', 'CLOSED', 'CANCELLED'] as const;
export type ReferralStatus = (typeof REFERRAL_STATUSES)[number];
export const URGENCIES = ['ROUTINE', 'URGENT'] as const;
export type Urgency = (typeof URGENCIES)[number];
export const CHANNELS = ['PORTAL', 'FHIR', 'SECURE_EMAIL'] as const;
export type Channel = (typeof CHANNELS)[number];
export const PREAUTH_STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_APPROVED', 'DECLINED', 'EXPIRED', 'CANCELLED'] as const;
export type PreauthStatus = (typeof PREAUTH_STATUSES)[number];
export const PREAUTH_DECISIONS = ['APPROVED', 'PARTIALLY_APPROVED', 'DECLINED'] as const;
export const INVOICE_STATUSES = ['RECEIVED', 'MATCHED', 'DISPUTED', 'APPROVED', 'PAID'] as const;
export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];
export const OUTCOME_MILESTONES = ['BASELINE', 'REVIEW', 'CLOSURE'] as const;
export type OutcomeMilestone = (typeof OUTCOME_MILESTONES)[number];
export const FUNCTION_SCALES = ['NONE', 'ODI', 'QUICKDASH', 'PSFS', 'LEFS'] as const;
export type FunctionScale = (typeof FUNCTION_SCALES)[number];
export const TELEHEALTH_STATUSES = ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW'] as const;
export type TelehealthStatus = (typeof TELEHEALTH_STATUSES)[number];
export const TELEHEALTH_MODES = ['VIDEO', 'CHAT'] as const;
export const COVERAGES = ['HUB', 'OUTSTATION', 'LAYOVER'] as const;
export type Coverage = (typeof COVERAGES)[number];
export const CONTRACT_STATUSES = ['ACTIVE', 'EXPIRING', 'EXPIRED', 'NONE'] as const;
export type ContractStatus = (typeof CONTRACT_STATUSES)[number];
export const COST_GROUP_BY = ['SUBSIDIARY', 'DEPARTMENT', 'COST_CENTRE'] as const;
export const VARIANCE_ALERT_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'ESCALATED'] as const;

// ---------------------------------------------------------------- F01 treatment plans
export interface PlanTemplate {
  code: string;
  injuryType: string;
  title: string;
  goals: string[];
  interventions: { type: InterventionType; description: string; frequency?: string; weeks?: number }[];
  followUpDays: number[];
  guidelineCode?: string;
  expectedDays?: number;
}
export interface Intervention {
  type: InterventionType;
  description: string;
  frequency?: string;
  startDate?: string;
  endDate?: string;
  providerCode?: string;
}
export interface FollowUp {
  id: string;
  dueDate: string;
  type: FollowUpType;
  status: FollowUpStatus;
  appointmentId?: string;
  employeeResponse?: string;
}
export interface PlanVariance {
  actualDays: number;
  expectedDays: number;
  pctOver: number;
  alert: boolean;
}
export interface TreatmentPlan {
  id: string;
  caseId: string;
  caseRef?: string;
  employeeId: string;
  status: PlanStatus;
  version: number;
  goals: string[];
  interventions: Intervention[];
  followUps: FollowUp[];
  expectedRecoveryDate?: string;
  guideline?: { code: string; title: string; expectedDays: number } | null;
  variance?: PlanVariance | null;
  responsibleClinician?: string;
  documentId?: string;
  createdAt?: string;
}
export interface PlanBody {
  templateCode?: string;
  goals: string[];
  interventions: Intervention[];
  followUps: { dueDate: string; type: FollowUpType; siteCode?: string; book?: boolean }[];
  expectedRecoveryDays?: number;
  guidelineCode?: string;
  varianceThresholdPct?: number;
}
export interface VarianceAlert {
  id: string;
  caseId: string;
  caseRef: string;
  employeeId: string;
  displayName: string;
  planId: string;
  expectedDays: number;
  actualDays: number;
  pctOver: number;
  status: (typeof VARIANCE_ALERT_STATUSES)[number];
  raisedAt: string;
}

// ---------------------------------------------------------------- F02 referrals
export type DataFieldType = 'TEXT' | 'DATE' | 'CODE' | 'LIST';
export interface RequiredField {
  code: string;
  label: string;
  type: DataFieldType;
}
export interface ReferralService {
  code: string;
  name: string;
  providerTypes: string[];
  requiredFields: RequiredField[];
  reportDueDays: number;
  urgentAppointmentHours: number;
  routineAppointmentDays: number;
}
export interface Referral {
  id: string;
  ref: string;
  caseId: string;
  caseRef: string;
  employeeId: string;
  displayName: string;
  serviceCode: string;
  serviceName: string;
  providerCode: string;
  providerName: string;
  contracted: boolean;
  preferred: boolean;
  urgency: Urgency;
  status: ReferralStatus;
  channel: Channel;
  createdAt: string;
  sentAt?: string;
  acceptedAt?: string;
  appointmentAt?: string;
  seenAt?: string;
  reportDueAt?: string;
  reportReceivedAt?: string;
  reportDocumentId?: string;
  overdue: boolean;
  chaseCount: number;
  escalated: boolean;
  rating?: number;
}
export interface ReferralDetail extends Referral {
  reason: string;
  question: string;
  dataSet: Record<string, string>;
  letterDocumentId?: string;
  attachments: ({ documentId: string; title?: string } | string)[];
  history: { status: ReferralStatus; at: string; by?: string }[];
  fhir?: unknown;
}
export interface ReferralBody {
  serviceCode: string;
  urgency: Urgency;
  providerCode: string;
  reason: string;
  question: string;
  dataSet: Record<string, string>;
  consentId?: string;
  channel: Channel;
  attachDocumentIds: string[];
}
export interface ReferralFilter {
  status?: string;
  overdue?: boolean;
  mine?: boolean;
  serviceCode?: string;
  providerCode?: string;
}

// ---------------------------------------------------------------- F03 providers
export interface Contract {
  ref: string;
  status: ContractStatus;
  start?: string;
  end?: string;
  rates?: { serviceCode: string; price: Money }[];
  sla?: Sla | null;
  version?: number;
  createdAt?: string;
  createdBy?: string;
}
export interface Sla {
  appointmentDays: number;
  urgentHours: number;
  reportDays: number;
}
export interface ProviderScore {
  month: string;
  overall: number;
  timeliness?: number;
  reportQuality?: number;
  outcome?: number;
  cost?: number;
}
export interface Provider {
  code: string;
  name: string;
  orgType: string;
  city?: string;
  countryCode?: string;
  specialties: string[];
  services: string[];
  languages: string[];
  accreditation: { body: string; validUntil?: string }[];
  coverage?: Coverage;
  latitude?: number;
  longitude?: number;
  preferred: boolean;
  portalEnabled?: boolean;
  contract?: Contract | null;
  rates?: { serviceCode: string; price: Money }[];
  sla?: Sla | null;
  scorecard?: ProviderScore | null;
}
export interface ProviderDetail extends Provider {
  contractHistory?: Contract[];
  contracts?: Contract[];
}
export interface ProviderProfile {
  specialties: string[];
  services: string[];
  languages: string[];
  accreditation: { body: string; validUntil?: string }[];
  coverage?: Coverage;
  preferred: boolean;
  latitude?: number;
  longitude?: number;
}
export interface ContractBody {
  ref: string;
  start: string;
  end: string;
  rates: { serviceCode: string; price: Money }[];
  sla: Sla;
}
export interface ProviderFilter {
  serviceCode?: string;
  specialty?: string;
  city?: string;
  countryCode?: string;
  contractStatus?: string;
  q?: string;
  preferredOnly?: boolean;
}
export interface ScorecardRow {
  providerCode: string;
  providerName: string;
  month: string;
  referrals: number;
  medianDaysToAppointment?: number;
  reportWithin7DaysPct?: number;
  avgReportQuality?: number;
  outcomeImprovedPct?: number;
  avgInvoice?: Money | string | number | null;
  overall?: number;
}
export interface NetworkGap {
  serviceCode: string;
  city: string;
  reason: string;
}

// ---------------------------------------------------------------- F04 pre-auth & invoices
export interface Preauth {
  id: string;
  ref: string;
  caseId: string;
  caseRef?: string;
  employeeId?: string;
  displayName?: string;
  payerCode?: string;
  payerName?: string;
  serviceCode: string;
  providerCode: string;
  estimate: Money;
  status: PreauthStatus;
  submittedAt?: string;
  slaDueAt?: string;
  decidedAt?: string;
  approvalRef?: string;
  approvedAmount?: Money | null;
  validUntil?: string;
  documentId?: string;
}
export interface PreauthBody {
  payerCode?: string;
  serviceCode: string;
  providerCode: string;
  referralId?: string;
  estimate: Money;
  justification?: string;
  consentId: string;
}
export interface PreauthDecision {
  status: (typeof PREAUTH_DECISIONS)[number];
  approvalRef?: string;
  approvedAmount?: Money;
  validUntil?: string;
  note?: string;
}
export interface Invoice {
  id: string;
  caseId?: string;
  providerCode: string;
  providerName?: string;
  invoiceNumber: string;
  amount: Money | string;
  currency?: string;
  serviceDate?: string;
  referralId?: string;
  preauthId?: string;
  documentId?: string;
  status: InvoiceStatus;
  matchedPreauthId?: string;
  variance?: Money | string | number | null;
  createdAt?: string;
}
export interface InvoiceBody {
  providerCode: string;
  invoiceNumber: string;
  amount: string;
  currency: string;
  serviceDate: string;
  referralId?: string;
  preauthId?: string;
  documentId?: string;
}
export interface TreatmentCostRow {
  key: string;
  label: string;
  cases?: number;
  estimated?: Money | string | number | null;
  invoiced?: Money | string | number | null;
  avgPerCase?: Money | string | number | null;
  suppressed: boolean;
}

// ---------------------------------------------------------------- F05 medications
export interface FormularyItem {
  code: string;
  name: string;
  form?: string;
  strength?: string;
  unit?: string;
  route: string[];
  snomedCode?: string;
  stockTracked: boolean;
  reorderLevel?: number;
  active: boolean;
}
export interface StockRow {
  itemCode: string;
  name?: string;
  batchNumber: string;
  expiry: string;
  quantity: number;
  siteCode: string;
  belowReorder: boolean;
  expired: boolean;
}
export interface StockBody {
  itemCode: string;
  batchNumber: string;
  expiry: string;
  quantity: number;
  siteCode: string;
}
export interface AdministerBody {
  itemCode: string;
  dose: string;
  route: string;
  batchNumber: string;
  siteCode: string;
  overrideAllergyReason?: string;
}
export interface Administration {
  id: string;
  itemCode: string;
  name?: string;
  dose: string;
  route: string;
  batchNumber: string;
  expiry?: string;
  administeredBy?: string;
  administeredAt: string;
  allergyOverride?: string | boolean | null;
  siteCode?: string;
}
export interface UsageRow {
  itemCode: string;
  name?: string;
  siteCode?: string;
  quantity?: number;
  administrations?: number;
}
export interface EPrescribingStatus {
  enabled: boolean;
  route?: string;
  decisionMemo?: string;
}

// ---------------------------------------------------------------- F06 outcomes
export interface Outcome {
  id?: string;
  milestone: OutcomeMilestone;
  painScore: number;
  functionScale: FunctionScale;
  functionScore?: number;
  rtwDate?: string;
  residualImpairmentPct?: number;
  note?: string;
  recordedAt?: string;
  recordedBy?: string;
}
export type OutcomeBody = Omit<Outcome, 'id' | 'recordedAt' | 'recordedBy'>;
/** The contract reads "[Outcome] + {improvement}" – accept a plain array or `{outcomes|items, improvement}`. */
export type OutcomesResponse = Outcome[] | { outcomes?: Outcome[]; items?: Outcome[]; improvement?: { painDelta?: number; functionDelta?: number } };

// ---------------------------------------------------------------- F07 telehealth
export interface TelehealthSession {
  id: string;
  ref: string;
  employeeId: string;
  displayName?: string;
  caseId?: string;
  scheduledAt: string;
  durationMinutes: number;
  mode: (typeof TELEHEALTH_MODES)[number];
  status: TelehealthStatus;
  clinicianUsername?: string;
  providerCode?: string;
  crewLayover: boolean;
  layoverCity?: string;
  consentGiven: boolean;
  encounterId?: string;
}
export interface TelehealthBody {
  employeeId: string;
  caseId?: string;
  scheduledAt: string;
  durationMinutes: number;
  mode: (typeof TELEHEALTH_MODES)[number];
  clinicianUsername?: string;
  providerCode?: string;
  crewLayover: boolean;
  layoverCity?: string;
}
export interface ChatMessage {
  id: string;
  sender: string;
  senderRole?: string;
  text: string;
  sentAt: string;
}

export interface TreatmentKpis {
  medianWorkingDaysReferralToAppointment?: number;
  urgentWithin48hPct?: number;
  reportWithin7DaysPct?: number;
  missedAppointmentPct?: number;
  avgMedicalCostPerCase?: Money | string | number | null;
}

const cs = (caseId: string) => `/cases/${seg(caseId)}`;
const plan = (id: string) => `/treatment-plans/${seg(id)}`;
const ref = (id: string) => `/referrals/${seg(id)}`;
const tele = (id: string) => `/telehealth/sessions/${seg(id)}`;

export const scorecardExportUrl = (month: string): string => bffLink(`/providers/scorecards/export${buildQuery({ month })}`);
export const treatmentCostsExportUrl = (f: { groupBy: string; from?: string; to?: string }): string => bffLink(`/treatment/costs/export${buildQuery(f)}`);

export function treatmentApi(c: AuthClient) {
  return {
    // F01
    templates: () => c.api<PlanTemplate[]>('/treatment/plan-templates'),
    plans: (caseId: string) => c.api<TreatmentPlan[]>(`${cs(caseId)}/treatment-plans`),
    createPlan: (caseId: string, body: PlanBody, activate: boolean) =>
      c.api<TreatmentPlan>(`${cs(caseId)}/treatment-plans${activate ? '?activate=true' : ''}`, { method: 'POST', body }),
    updatePlan: (id: string, body: PlanBody) => c.api<TreatmentPlan>(plan(id), { method: 'PUT', body }),
    planStatus: (id: string, status: 'ACTIVE' | 'COMPLETED' | 'CANCELLED', note?: string) =>
      c.api<TreatmentPlan>(`${plan(id)}/status`, { method: 'POST', body: note ? { status, note } : { status } }),
    followUpStatus: (id: string, followUpId: string, status: 'DONE' | 'MISSED' | 'CANCELLED', note?: string) =>
      c.api<TreatmentPlan>(`${plan(id)}/follow-ups/${seg(followUpId)}`, { method: 'POST', body: note ? { status, note } : { status } }),
    printPlan: (id: string) => c.api<{ id: string; title?: string }>(`${plan(id)}/print`, { method: 'POST' }),
    varianceAlerts: (status = 'OPEN') => c.api<VarianceAlert[]>(`/treatment/variance-alerts${buildQuery({ status })}`),
    acknowledgeVariance: (id: string, note?: string) => c.api<VarianceAlert>(`/treatment/variance-alerts/${seg(id)}/acknowledge`, { method: 'POST', body: note ? { note } : {} }),
    escalateVariance: (id: string, note?: string) => c.api<VarianceAlert>(`/treatment/variance-alerts/${seg(id)}/escalate`, { method: 'POST', body: note ? { note } : {} }),

    // F02
    services: () => c.api<ReferralService[]>('/referrals/services'),
    createReferral: (caseId: string, body: ReferralBody) => c.api<ReferralDetail>(`${cs(caseId)}/referrals`, { method: 'POST', body }),
    updateReferral: (id: string, body: ReferralBody) => c.api<ReferralDetail>(ref(id), { method: 'PUT', body }),
    sendReferral: (id: string) => c.api<ReferralDetail>(`${ref(id)}/send`, { method: 'POST' }),
    referrals: (f: ReferralFilter = {}) => c.api<Referral[]>(`/referrals${buildQuery({ ...f, overdue: f.overdue || undefined, mine: f.mine || undefined })}`),
    referral: (id: string) => c.api<ReferralDetail>(ref(id)),
    remind: (referralIds: string[]) => c.api<{ reminded: number }>('/referrals/remind', { method: 'POST', body: { referralIds } }),
    cancelReferral: (id: string, reason: string) => c.api<ReferralDetail>(`${ref(id)}/cancel`, { method: 'POST', body: { reason } }),
    rateReferral: (id: string, rating: number, comment?: string) => c.api<ReferralDetail>(`${ref(id)}/rating`, { method: 'POST', body: comment ? { rating, comment } : { rating } }),
    caseReferrals: (caseId: string) => c.api<Referral[]>(`${cs(caseId)}/referrals`),
    kpis: (f: { from?: string; to?: string }) => c.api<TreatmentKpis>(`/treatment/kpis${buildQuery(f)}`),

    // F03
    providers: (f: ProviderFilter = {}) => c.api<Provider[]>(`/providers${buildQuery({ ...f, preferredOnly: f.preferredOnly || undefined })}`),
    provider: (code: string) => c.api<ProviderDetail>(`/providers/${seg(code)}`),
    saveProvider: (code: string, body: ProviderProfile) => c.api<ProviderDetail>(`/providers/${seg(code)}`, { method: 'PUT', body }),
    addContract: (code: string, body: ContractBody) => c.api<ProviderDetail>(`/providers/${seg(code)}/contracts`, { method: 'POST', body }),
    networkGaps: () => c.api<NetworkGap[]>('/providers/network-gaps'),
    scorecards: (month: string) => c.api<ScorecardRow[]>(`/providers/scorecards${buildQuery({ month })}`),
    recomputeScorecards: (month: string) => c.api<unknown>(`/providers/scorecards/recompute${buildQuery({ month })}`, { method: 'POST' }),

    // F04
    createPreauth: (caseId: string, body: PreauthBody) => c.api<Preauth>(`${cs(caseId)}/preauthorisations`, { method: 'POST', body }),
    submitPreauth: (id: string) => c.api<Preauth>(`/preauthorisations/${seg(id)}/submit`, { method: 'POST' }),
    decidePreauth: (id: string, body: PreauthDecision) => c.api<Preauth>(`/preauthorisations/${seg(id)}/decision`, { method: 'POST', body }),
    preauths: (status?: string) => c.api<Preauth[]>(`/preauthorisations${buildQuery({ status })}`),
    casePreauths: (caseId: string) => c.api<Preauth[]>(`${cs(caseId)}/preauthorisations`),
    caseInvoices: (caseId: string) => c.api<Invoice[]>(`${cs(caseId)}/invoices`),
    createInvoice: (caseId: string, body: InvoiceBody) => c.api<Invoice>(`${cs(caseId)}/invoices`, { method: 'POST', body }),
    invoiceStatus: (id: string, status: 'APPROVED' | 'DISPUTED' | 'PAID', note?: string) =>
      c.api<Invoice>(`/invoices/${seg(id)}/status`, { method: 'POST', body: note ? { status, note } : { status } }),
    costs: (f: { groupBy: string; from?: string; to?: string }) => c.api<TreatmentCostRow[]>(`/treatment/costs${buildQuery(f)}`),

    // F05
    formulary: () => c.api<FormularyItem[]>('/treatment/formulary'),
    saveFormularyItem: (item: FormularyItem) => c.api<FormularyItem>(`/treatment/formulary/${seg(item.code)}`, { method: 'PUT', body: item }),
    stock: (siteCode?: string) => c.api<StockRow[]>(`/treatment/stock${buildQuery({ siteCode })}`),
    receiveStock: (body: StockBody) => c.api<StockRow>('/treatment/stock', { method: 'POST', body }),
    administer: (caseId: string, body: AdministerBody) => c.api<Administration>(`${cs(caseId)}/medications/administer`, { method: 'POST', body }),
    administrations: (caseId: string) => c.api<Administration[]>(`${cs(caseId)}/medications/administrations`),
    usage: (month: string) => c.api<UsageRow[]>(`/treatment/medication-usage${buildQuery({ month })}`),
    eprescribing: () => c.api<EPrescribingStatus>('/treatment/eprescribing/status'),

    // F06
    outcomes: (caseId: string) => c.api<OutcomesResponse>(`${cs(caseId)}/outcomes`),
    recordOutcome: (caseId: string, body: OutcomeBody) => c.api<Outcome>(`${cs(caseId)}/outcomes`, { method: 'POST', body }),

    // F07
    createSession: (body: TelehealthBody) => c.api<TelehealthSession>('/telehealth/sessions', { method: 'POST', body }),
    sessions: (f: { from?: string; to?: string; status?: string } = {}) => c.api<TelehealthSession[]>(`/telehealth/sessions${buildQuery(f)}`),
    join: (id: string) => c.api<{ joinUrl: string; expiresAt: string }>(`${tele(id)}/join`, { method: 'POST' }),
    messages: (id: string) => c.api<ChatMessage[]>(`${tele(id)}/messages`),
    sendMessage: (id: string, text: string) => c.api<ChatMessage>(`${tele(id)}/messages`, { method: 'POST', body: { text } }),
    completeSession: (id: string, note: string, followUpDate?: string) =>
      c.api<TelehealthSession>(`${tele(id)}/complete`, { method: 'POST', body: followUpDate ? { note, followUpDate } : { note } }),
    cancelSession: (id: string, reason: string) => c.api<TelehealthSession>(`${tele(id)}/cancel`, { method: 'POST', body: { reason } }),

    // master data (§2.2)
    partnerOrganisations: (types?: string) => c.api<PartnerOrganisation[]>(`/masterdata/partner-organisations${buildQuery({ types })}`),
  };
}

export interface PartnerOrganisation {
  code: string;
  name: string;
  orgType: string;
  countryCode?: string;
  city?: string;
  contactEmail?: string;
  phone?: string;
  portalEnabled?: boolean;
  active?: boolean;
}

export type TreatmentApi = ReturnType<typeof treatmentApi>;
