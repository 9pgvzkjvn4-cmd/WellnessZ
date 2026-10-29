/**
 * E07 – medical surveillance, periodic & statutory medicals. Mirrors API-Contract-E06-E07-E14.md §4 (paths relative to `/api/v1`).
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

export const PROGRAMME_STATUSES = ['DRAFT', 'ACTIVE', 'RETIRED'] as const;
export type ProgrammeStatus = (typeof PROGRAMME_STATUSES)[number];
export const PANEL_TESTS = ['AUDIOMETRY', 'SPIROMETRY', 'VISION', 'ECG', 'QUESTIONNAIRE', 'PHYSICAL_EXAM'] as const;
/** Parameterised panel tests: `LAB:{loinc}`, `BIOMONITORING:{code}`, `IMMUNISATION:{vaccine}`. */
export const PANEL_TEST_PREFIXES = ['LAB', 'BIOMONITORING', 'IMMUNISATION'] as const;
export const COMPLIANCE_STATUSES = ['CURRENT', 'DUE', 'OVERDUE', 'SCHEDULED', 'EXEMPT'] as const;
export type ComplianceStatus = (typeof COMPLIANCE_STATUSES)[number];
export type InvitationStatus = 'INVITED' | 'BOOKED' | 'ATTENDED' | 'NO_SHOW' | 'CANCELLED' | 'ESCALATED';
export const PRE_EMPLOYMENT_STATUSES = ['INVITED', 'QUESTIONNAIRE_DONE', 'BOOKED', 'EXAMINED', 'OUTCOME_RECORDED', 'WITHDRAWN'] as const;
export type HrClearance = 'PENDING' | 'FIT' | 'UNFIT';
export const CREW_CERT_TYPES = ['CLASS1', 'CLASS2', 'LAPL', 'CABIN_CREW'] as const;
export const CREW_EXAM_TYPES = ['CLASS1', 'CLASS2', 'CABIN_CREW'] as const;
export const CREW_CERT_STATUSES = ['VALID', 'EXPIRING', 'EXPIRED', 'SUSPENDED'] as const;
export type CrewCertStatus = (typeof CREW_CERT_STATUSES)[number];
export const CREW_EXAM_OUTCOMES = ['FIT', 'FIT_WITH_LIMITATIONS', 'UNFIT', 'REFERRED_TO_AUTHORITY'] as const;
export const FOLLOW_UP_STATUSES = ['OPEN', 'REVIEWED', 'CLOSED'] as const;
export const FOLLOW_UP_DECISIONS = ['CONFIRMED', 'NOT_CONFIRMED', 'WORK_RELATED'] as const;
export const RAI_REASONS = ['LONG_TERM_ABSENCE', 'SURGERY', 'CARDIAC_EVENT', 'OTHER_SERIOUS_ILLNESS'] as const;
export const RAI_STATUSES = ['REQUIRED', 'COMPLETED', 'WAIVED'] as const;
export const ENROLMENT_OUTCOMES = ['FIT', 'FIT_WITH_LIMITATIONS', 'UNFIT', 'REFER'] as const;
export const PRE_EMPLOYMENT_OUTCOMES = ['FIT', 'FIT_WITH_ADJUSTMENTS', 'UNFIT'] as const;
export const GOV_MEDICAL_TYPES = ['VISA', 'GOVERNMENT', 'RESIDENCY'] as const;
export const GOV_MEDICAL_STATUSES = ['PENDING', 'PASSED', 'FAILED'] as const;
export const INJECTION_SITES = ['LEFT_DELTOID', 'RIGHT_DELTOID', 'LEFT_THIGH', 'RIGHT_THIGH', 'ORAL'] as const;
export const ROUTES = ['IM', 'SC', 'ORAL', 'ID'] as const;
export const IMMUNISATION_STATUSES = ['COMPLETE', 'DUE', 'OVERDUE', 'TITRE_NEEDED'] as const;
export const MEASUREMENT_TYPES = ['NOISE_DOSIMETRY', 'AIR_SAMPLING', 'BIOLOGICAL', 'VIBRATION'] as const;
export const COMPLIANCE_GROUP_BY = ['PROGRAMME', 'SUBSIDIARY', 'DEPARTMENT'] as const;
export const AUDIT_TEMPLATES = ['IOSA', 'CAA', 'FOOD_SAFETY', 'LABOUR_INSPECTORATE'] as const;

export interface Eligibility {
  jobCodes: string[];
  departmentCodes: string[];
  subsidiaryCodes: string[];
  hazardCodes: string[];
  exposureGroups: string[];
  licenceTypes: string[];
  minAge?: number | null;
}
export interface PanelItem {
  test: string;
  standard?: string | null;
}
export interface Programme {
  code: string;
  version: number;
  title: string;
  status: ProgrammeStatus;
  effectiveFrom?: string | null;
  eligibility: Eligibility;
  panel: PanelItem[];
  frequencyMonths: number;
  frequencyMonthsOver40?: number | null;
  standardCode?: string | null;
  responsibleRole: string;
  serviceCode: string;
  regulatoryBasis?: string;
  eligibleHeadcount?: number;
}
export interface ProgrammeVersion {
  version: number;
  status: ProgrammeStatus;
  effectiveFrom?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
}
export type ProgrammeBody = Omit<Programme, 'version' | 'status' | 'eligibleHeadcount' | 'effectiveFrom'>;
export interface SimulationResult {
  eligibleHeadcount: number;
  byUnit: { subsidiaryCode: string; departmentCode: string; count: number }[];
  newlyEnrolled: number;
  wouldBeOverdue: number;
}

export interface Enrolment {
  id: string;
  employeeId: string;
  displayName: string;
  subsidiaryCode?: string;
  departmentCode?: string;
  programmeCode: string;
  programmeTitle: string;
  reason: string;
  lastCompletedOn?: string | null;
  nextDueOn: string;
  status: ComplianceStatus;
  invitations: number;
  lastInvitedAt?: string | null;
  appointment?: { id: string; startsAt: string; siteCode: string } | null;
  escalatedAt?: string | null;
}
export interface Invitation {
  id: string;
  sentAt: string;
  channel: string;
  status: InvitationStatus;
  appointmentId?: string | null;
}
export interface InviteResult {
  invited: number;
  booked: number;
  notBooked: number;
  invitationIds: string[];
}

export interface PreEmployment {
  id: string;
  offerRef: string;
  candidateId: string;
  candidateName: string;
  jobCode: string;
  jobTitle?: string;
  proposedStart: string;
  panel: string[];
  status: string;
  hrClearance: HrClearance;
  questionnaireCompleted: boolean;
  consentGiven: boolean;
  appointment?: { id?: string; startsAt: string; siteCode?: string } | null;
  governmentMedical?: { type: string; reference: string; status: string; expiresOn?: string | null } | null;
  documents: { id: string; title: string }[];
  slaDueAt?: string;
  retentionUntil?: string | null;
  /** detail only */
  questionnaire?: unknown;
  answers?: unknown;
}

export interface ExamTemplateSection {
  section: string;
  items: { code: string; label: string; type: string }[];
}
export interface CrewCertificate {
  id: string;
  employeeId: string;
  displayName: string;
  certificateType: string;
  certificateNumber?: string;
  issuingAuthority?: string;
  examinedOn?: string;
  validUntil: string;
  limitationCodes: string[];
  status: CrewCertStatus;
  source: 'INTERNAL' | 'EXTERNAL_AME';
  aemcName?: string | null;
  ameName?: string | null;
  alertsSent?: number[];
}
export interface WithoutValidMedical {
  employeeId: string;
  displayName: string;
  jobCode?: string;
  certificateType?: string | null;
  validUntil?: string | null;
  status: 'EXPIRED' | 'NONE' | 'SUSPENDED';
}
export interface CrewExamination {
  employeeId: string;
  certificateType: string;
  examinedOn: string;
  answers: Record<string, string | boolean>;
  outcome: string;
  limitationCodes: string[];
  validUntil?: string;
}
export interface CrewImport {
  employeeId: string;
  certificateType: string;
  certificateNumber: string;
  issuingAuthority: string;
  aemcName: string;
  ameName?: string;
  examinedOn: string;
  validUntil: string;
  limitationCodes: string[];
  pdfBase64?: string;
}

export interface ImmunisationRequirement {
  id: string;
  jobCode?: string | null;
  hazardCode?: string | null;
  vaccineCode: string;
  vaccineName: string;
  doses: number;
  boosterMonths?: number | null;
  titreRequired: boolean;
  mandatory: boolean;
}
export interface ImmunisationDue {
  employeeId: string;
  displayName: string;
  vaccineCode: string;
  vaccineName: string;
  dosesGiven: number;
  dosesRequired: number;
  nextDueOn?: string | null;
  status: (typeof IMMUNISATION_STATUSES)[number];
}
export interface EmployeeImmunisation {
  vaccineCode: string;
  vaccineName: string;
  status: string;
  lastDoseOn?: string | null;
  nextDueOn?: string | null;
  titre?: { value: number | string; protective: boolean; testedOn: string } | null;
}
export interface Administration {
  employeeId?: string;
  badgeId?: string;
  vaccineCode: string;
  doseNumber: number;
  lotNumber?: string;
  lotBarcode?: string;
  lotExpiry?: string;
  site: string;
  route: string;
  consentConfirmed: true;
  campaignId?: string;
  administeredAt?: string;
}
export interface BatchAdministration {
  vaccineCode: string;
  lotNumber?: string;
  lotBarcode?: string;
  site: string;
  route: string;
  campaignId?: string;
  entries: { employeeId?: string; badgeId?: string; consentConfirmed: boolean; doseNumber?: number }[];
}

export interface Seg {
  code: string;
  name: string;
  locationCode: string;
  jobCodes: string[];
  hazardCode: string;
  unit: string;
  actionLevel: number;
  limitValue: number;
  latestValue?: number | null;
  latestMeasuredOn?: string | null;
  aboveActionLevel: boolean;
  enrolledProgrammes: string[];
}
export interface IhMeasurement {
  sourceRef: string;
  segCode: string;
  hazardCode: string;
  measurementType: string;
  value: number;
  unit: string;
  measuredOn: string;
}
export interface IhImportResult {
  imported: number;
  duplicates: number;
  segsAboveActionLevel: number | string[];
  employeesEnrolled: number;
}
export interface CosmicDoseRow {
  employeeId: string;
  displayName?: string;
  year: number;
  doseMsv: number;
  aboveThreshold?: boolean;
  pregnancyDeclared?: boolean;
  source?: string;
}
export interface ExposureHistoryItem {
  segCode: string;
  hazardCode: string;
  from?: string | null;
  to?: string | null;
  latestValue?: number | null;
  unit?: string;
}

export interface FollowUp {
  id: string;
  employeeId: string;
  displayName: string;
  programmeCode: string;
  ruleCode: string;
  resultId: string;
  caseId?: string;
  caseRef?: string;
  recheckDueOn?: string | null;
  regulatoryNotificationRequired: boolean;
  status: (typeof FOLLOW_UP_STATUSES)[number];
  createdAt: string;
  reviewedBy?: string | null;
  reviewDecision?: string | null;
}
export interface Rai {
  id: string;
  employeeId: string;
  displayName: string;
  reason: string;
  source: 'E06' | 'MANUAL';
  caseId?: string | null;
  status: (typeof RAI_STATUSES)[number];
  createdAt: string;
  completedAt?: string | null;
}

export interface ComplianceRow {
  key: string;
  label: string;
  enrolled?: number;
  current?: number;
  due?: number;
  overdue?: number;
  compliancePct?: number;
  suppressed: boolean;
}
/** The contract lists rows "+ refreshedAt": accept either a bare array or `{rows|items, refreshedAt}`. */
export interface ComplianceView {
  rows: ComplianceRow[];
  refreshedAt?: string;
}
export interface AuditTemplate {
  code: string;
  title: string;
  sections: string[];
}
export interface AuditPack {
  id: string;
  generatedAt: string;
  programmeCode: string;
  template: string;
  anonymised: boolean;
  files: { format: 'pdf' | 'csv'; path: string }[];
}

const prog = (code: string) => `/surveillance/programmes/${seg(code)}`;

export function normaliseCompliance(body: unknown): ComplianceView {
  if (Array.isArray(body)) return { rows: body as ComplianceRow[] };
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  const rows = (Array.isArray(b.rows) ? b.rows : Array.isArray(b.items) ? b.items : Array.isArray(b.groups) ? b.groups : []) as ComplianceRow[];
  return { rows, refreshedAt: typeof b.refreshedAt === 'string' ? b.refreshedAt : undefined };
}

/** Exposure history: "[…] + cosmic dose by year" – accept an array or `{exposures|history|items, cosmicDose|cosmic}`. */
export function normaliseExposureHistory(body: unknown): { exposures: ExposureHistoryItem[]; cosmicDose: CosmicDoseRow[] } {
  if (Array.isArray(body)) return { exposures: body as ExposureHistoryItem[], cosmicDose: [] };
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  const list = (k: string[]) => (k.map((x) => b[x]).find(Array.isArray) ?? []) as unknown[];
  return { exposures: list(['exposures', 'history', 'items', 'segments']) as ExposureHistoryItem[], cosmicDose: list(['cosmicDose', 'cosmic', 'cosmicDoseByYear']) as CosmicDoseRow[] };
}

export const auditPackUrl = (id: string, format: 'pdf' | 'csv'): string => bffLink(`/surveillance/audit-packs/${seg(id)}/content${buildQuery({ format })}`);
export const authorityReportUrl = (from: string, to: string): string => bffLink(`/surveillance/crew/authority-report${buildQuery({ from, to })}`);

export function surveillanceApi(c: AuthClient) {
  return {
    // F01 programmes
    programmes: () => c.api<Programme[]>('/surveillance/programmes'),
    programme: (code: string) => c.api<Programme & { versions?: ProgrammeVersion[] }>(prog(code)),
    createProgramme: (p: ProgrammeBody) => c.api<Programme>('/surveillance/programmes', { method: 'POST', body: p }),
    updateProgramme: (code: string, p: ProgrammeBody) => c.api<Programme>(prog(code), { method: 'PUT', body: p }),
    simulate: (code: string, version?: number) => c.api<SimulationResult>(`${prog(code)}/simulate`, { method: 'POST', body: version ? { version } : {} }),
    activate: (code: string, effectiveFrom?: string) => c.api<Programme>(`${prog(code)}/activate`, { method: 'POST', body: effectiveFrom ? { effectiveFrom } : {} }),
    retire: (code: string) => c.api<Programme>(`${prog(code)}/retire`, { method: 'POST' }),

    // F02 due list
    /** `employeeId` is not in the contract's filter list (used by the chart tab; callers also filter client-side). */
    due: (f: { programme?: string; subsidiary?: string; department?: string; status?: string; dueBefore?: string; employeeId?: string }) =>
      c.api<Enrolment[]>(`/surveillance/due${buildQuery(f)}`),
    recalculate: () => c.api<{ enrolled: number; updated: number; removed: number }>('/surveillance/due/recalculate', { method: 'POST' }),
    invite: (body: { enrolmentIds: string[]; book: boolean; siteCode?: string; campaignId?: string }) =>
      c.api<InviteResult>('/surveillance/invitations', { method: 'POST', body }),
    invitations: (enrolmentId: string) => c.api<Invitation[]>(`/surveillance/invitations${buildQuery({ enrolmentId })}`),
    completeEnrolment: (id: string, body: { performedOn: string; outcome: string; encounterId?: string; resultIds?: string[] }) =>
      c.api<Enrolment>(`/surveillance/enrolments/${seg(id)}/complete`, { method: 'POST', body }),
    exemptEnrolment: (id: string, body: { reason: string; until?: string }) => c.api<Enrolment>(`/surveillance/enrolments/${seg(id)}/exempt`, { method: 'POST', body }),

    // F03 pre-employment
    preEmployments: (status?: string) => c.api<PreEmployment[]>(`/surveillance/pre-employment${buildQuery({ status })}`),
    preEmployment: (id: string) => c.api<PreEmployment>(`/surveillance/pre-employment/${seg(id)}`),
    preEmploymentOutcome: (id: string, body: { outcome: string; conditions?: string }) =>
      c.api<PreEmployment>(`/surveillance/pre-employment/${seg(id)}/outcome`, { method: 'POST', body }),
    governmentMedical: (id: string, body: { type: string; reference: string; status: string; expiresOn?: string }) =>
      c.api<PreEmployment>(`/surveillance/pre-employment/${seg(id)}/government-medical`, { method: 'POST', body }),
    withdrawPreEmployment: (id: string) => c.api<PreEmployment>(`/surveillance/pre-employment/${seg(id)}/withdraw`, { method: 'POST' }),

    // F04 crew
    examTemplate: (type: string) => c.api<ExamTemplateSection[]>(`/surveillance/crew/exam-template${buildQuery({ type })}`),
    crewExamination: (body: CrewExamination) => c.api<CrewCertificate>('/surveillance/crew/examinations', { method: 'POST', body }),
    crewCertificates: (f: { status?: string; expiringWithinDays?: number | string } = {}) => c.api<CrewCertificate[]>(`/surveillance/crew/certificates${buildQuery(f)}`),
    withoutValidMedical: () => c.api<WithoutValidMedical[]>('/surveillance/crew/without-valid-medical'),
    importCrewCertificate: (body: CrewImport) => c.api<CrewCertificate>('/surveillance/crew/certificates/import', { method: 'POST', body }),

    // F05 immunisation
    requirements: () => c.api<ImmunisationRequirement[]>('/surveillance/immunisation/requirements'),
    saveRequirement: (r: ImmunisationRequirement) => c.api<ImmunisationRequirement>(`/surveillance/immunisation/requirements/${seg(r.id)}`, { method: 'PUT', body: r }),
    immunisationDue: (f: { vaccine?: string; subsidiary?: string; department?: string }) => c.api<ImmunisationDue[]>(`/surveillance/immunisation/due${buildQuery(f)}`),
    employeeImmunisations: (employeeId: string) => c.api<EmployeeImmunisation[]>(`/surveillance/immunisation/employees/${seg(employeeId)}`),
    administer: (a: Administration) => c.api<unknown>('/surveillance/immunisation/administrations', { method: 'POST', body: a }),
    administerBatch: (b: BatchAdministration) =>
      c.api<{ recorded: number; rejected: { ref: string; reason: string }[] }>('/surveillance/immunisation/administrations/batch', { method: 'POST', body: b }),
    titre: (body: { employeeId: string; antigen: string; value: number; unit: string; protective: boolean; testedOn: string }) =>
      c.api<unknown>('/surveillance/immunisation/titres', { method: 'POST', body }),

    // F06 exposure
    segs: () => c.api<Seg[]>('/surveillance/exposure-groups'),
    saveSeg: (s: Omit<Seg, 'latestValue' | 'latestMeasuredOn' | 'aboveActionLevel' | 'enrolledProgrammes'>) => c.api<Seg>('/surveillance/exposure-groups', { method: 'POST', body: s }),
    importIh: (rows: IhMeasurement[]) => c.api<IhImportResult>('/surveillance/ih-measurements/import', { method: 'POST', body: rows }),
    ihMeasurements: (segCode?: string) => c.api<IhMeasurement[]>(`/surveillance/ih-measurements${buildQuery({ segCode })}`),
    importCosmic: (rows: { employeeId: string; year: number; doseMsv: number; source: string }[]) => c.api<unknown>('/surveillance/cosmic-dose/import', { method: 'POST', body: rows }),
    cosmicDose: (f: { year?: number | string; aboveThreshold?: boolean }) => c.api<CosmicDoseRow[]>(`/surveillance/cosmic-dose${buildQuery(f)}`),
    exposureHistory: async (employeeId: string) => normaliseExposureHistory(await c.api<unknown>(`/surveillance/employees/${seg(employeeId)}/exposure-history`)),

    // F07 follow-up & RAI
    followUps: (status?: string) => c.api<FollowUp[]>(`/surveillance/follow-ups${buildQuery({ status })}`),
    reviewFollowUp: (id: string, body: { decision: string; note?: string; regulatoryNotificationRequired: boolean }) =>
      c.api<FollowUp>(`/surveillance/follow-ups/${seg(id)}/review`, { method: 'POST', body }),
    rai: (status?: string) => c.api<Rai[]>(`/surveillance/return-after-illness${buildQuery({ status })}`),
    createRai: (body: { employeeId: string; reason: string; caseId?: string }) => c.api<Rai>('/surveillance/return-after-illness', { method: 'POST', body }),
    completeRai: (id: string, body: { encounterId?: string; note?: string }) => c.api<Rai>(`/surveillance/return-after-illness/${seg(id)}/complete`, { method: 'POST', body }),
    waiveRai: (id: string, reason: string) => c.api<Rai>(`/surveillance/return-after-illness/${seg(id)}/waive`, { method: 'POST', body: { reason } }),

    // F08 compliance
    compliance: async (f: { groupBy: string; programme?: string }) => normaliseCompliance(await c.api<unknown>(`/surveillance/compliance${buildQuery(f)}`)),
    complianceTrend: (programme?: string) => c.api<{ date: string; compliancePct: number }[]>(`/surveillance/compliance/trend${buildQuery({ programme })}`),
    complianceOverdue: (f: { programme?: string; department?: string }) => c.api<Enrolment[]>(`/surveillance/compliance/overdue${buildQuery(f)}`),
    auditTemplates: () => c.api<AuditTemplate[]>('/surveillance/audit-packs/templates'),
    generateAuditPack: (body: { template: string; programmeCode: string; from: string; to: string; anonymise: boolean }) =>
      c.api<AuditPack>('/surveillance/audit-packs', { method: 'POST', body }),
  };
}

export type SurveillanceApi = ReturnType<typeof surveillanceApi>;
