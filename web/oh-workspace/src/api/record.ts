/**
 * E03 – OH record & clinical encounters. Types and calls mirror docs/increment-02/API-Contract-E03-E05.md §4.
 * Paths are relative to `/api/v1`; the client sends them through the BFF as `/bff/api/...`.
 */
import type { AuthClient } from '../auth/AuthClient';
import type { CaseStatus, CaseType, FitnessStatus, WorkRelated } from './types';
import { bffLink, buildQuery, seg } from './links';
import type { DocumentMeta } from './platform';

export const CODE_SYSTEMS = ['SNOMED_CT', 'ICD_10', 'ICD_11', 'LOINC', 'ODH'] as const;
export type CodeSystem = (typeof CODE_SYSTEMS)[number];

export interface Coding {
  system: CodeSystem | string;
  code: string;
  display: string;
}

export const ENCOUNTER_TYPES = ['INJURY', 'ILLNESS', 'FITNESS_ASSESSMENT', 'SURVEILLANCE_EXAM', 'MENTAL_HEALTH', 'TRAVEL', 'CREW_AEROMEDICAL', 'FOOD_HANDLER'] as const;
export type EncounterType = (typeof ENCOUNTER_TYPES)[number];
export type EncounterStatus = 'DRAFT' | 'SIGNED' | 'ENTERED_IN_ERROR';

export const PROBLEM_STATUSES = ['ACTIVE', 'RESOLVED', 'INACTIVE'] as const;
export type ProblemStatus = (typeof PROBLEM_STATUSES)[number];
export const ALLERGY_CRITICALITIES = ['LOW', 'HIGH', 'UNABLE_TO_ASSESS'] as const;
export type AllergyCriticality = (typeof ALLERGY_CRITICALITIES)[number];
export const ALLERGY_CATEGORIES = ['MEDICATION', 'FOOD', 'ENVIRONMENT', 'BIOLOGIC'] as const;
export type AllergyCategory = (typeof ALLERGY_CATEGORIES)[number];
export const MEDICATION_STATUSES = ['ACTIVE', 'COMPLETED', 'STOPPED'] as const;
export type MedicationStatus = (typeof MEDICATION_STATUSES)[number];
export const MEDICATION_SOURCES = ['REPORTED', 'ADMINISTERED'] as const;
export type MedicationSource = (typeof MEDICATION_SOURCES)[number];
export const IMMUNISATION_STATUSES = ['COMPLETE', 'PARTIAL', 'DUE'] as const;
export type ImmunisationStatus = (typeof IMMUNISATION_STATUSES)[number];

export const ORDER_CATEGORIES = ['LAB', 'IMAGING', 'AUDIOMETRY', 'SPIROMETRY', 'VISION', 'ECG'] as const;
export type OrderCategory = (typeof ORDER_CATEGORIES)[number];
export type OrderStatus = 'DRAFT' | 'QUEUED' | 'SENT' | 'PARTIAL' | 'RESULTED' | 'CANCELLED';
export const RESULT_FLAGS = ['NORMAL', 'LOW', 'HIGH', 'ABNORMAL', 'CRITICAL'] as const;
export type ResultFlag = (typeof RESULT_FLAGS)[number];
export type ResultStatus = 'PRELIMINARY' | 'FINAL' | 'AMENDED';
export const ACK_ACTIONS = ['NO_ACTION', 'REPEAT_TEST', 'REFER', 'FOLLOW_UP_APPOINTMENT', 'FITNESS_REVIEW'] as const;
export type AckAction = (typeof ACK_ACTIONS)[number];
export const DEVICE_CATEGORIES = ['AUDIOMETRY', 'SPIROMETRY', 'VISION', 'ECG'] as const;
export type DeviceCategory = (typeof DEVICE_CATEGORIES)[number];

// ---------------------------------------------------------------- chart (§4.1)
export interface ChartAlert {
  kind: string;
  severity: string;
  text: string;
}
export interface ChartSummary {
  employee: {
    employeeId: string;
    displayName: string;
    dateOfBirth?: string | null;
    subsidiaryCode?: string;
    departmentCode?: string;
    jobCode?: string;
    jobTitle?: string;
    locationCode?: string;
    safetyCategory?: string | null;
  };
  alerts: ChartAlert[];
  fitness?: { status: FitnessStatus; standardCode?: string; validUntil?: string; reviewDate?: string; decidedBy?: string } | null;
  restrictions: { code: string; label: string; validUntil?: string; status: string }[];
  openCases: { id: string; caseRef: string; status: CaseStatus; caseType: CaseType }[];
  surveillanceDue: { hazardCode: string; programme: string; dueDate: string; overdue: boolean }[];
  immunisations: { vaccine: string; date?: string; status: ImmunisationStatus }[];
  counts: { problems: number; medications: number; allergies: number; results: number; unacknowledgedAbnormal: number; documents: number; unsignedEncounters: number };
  restrictedItemsHidden: number;
}

export interface Problem {
  id: string;
  employeeId: string;
  code: Coding;
  status: ProblemStatus;
  onsetDate?: string | null;
  resolvedDate?: string | null;
  workRelated?: WorkRelated;
  caseId?: string | null;
  note?: string | null;
  recordedBy?: string;
  recordedAt?: string;
}
export type ProblemInput = Omit<Problem, 'id' | 'employeeId' | 'recordedBy' | 'recordedAt'>;

export interface Allergy {
  id: string;
  employeeId: string;
  substance: Coding;
  category: AllergyCategory;
  criticality: AllergyCriticality;
  reaction?: string | null;
  recordedBy?: string;
  recordedAt?: string;
}
export type AllergyInput = Pick<Allergy, 'substance' | 'category' | 'criticality' | 'reaction'>;

export interface Override {
  alertCode: string;
  reason: string;
}
export interface Medication {
  id: string;
  employeeId: string;
  name: string;
  code?: Coding | null;
  dose?: string;
  route?: string;
  frequency?: string;
  source: MedicationSource;
  status: MedicationStatus;
  startDate?: string | null;
  endDate?: string | null;
  recordedBy?: string;
  recordedAt?: string;
  overrides?: Override[];
}
export type MedicationInput = Omit<Medication, 'id' | 'employeeId' | 'recordedBy' | 'recordedAt'>;

export interface Immunisation {
  id: string;
  employeeId: string;
  vaccine: string;
  code?: Coding | null;
  date?: string | null;
  doseNumber?: number | null;
  lotNumber?: string | null;
  status: ImmunisationStatus;
  nextDueDate?: string | null;
}
export type ImmunisationInput = Omit<Immunisation, 'id' | 'employeeId'>;

export interface Hazard {
  code: string;
  name: string;
  category?: string;
}
export interface JobEntry {
  id: string;
  employeeId: string;
  jobCode?: string;
  jobTitle: string;
  employer?: string;
  industry?: string;
  startDate?: string | null;
  endDate?: string | null;
  current: boolean;
  source: 'HRIS' | 'MANUAL';
  hazards: Hazard[];
  duties?: string;
  weeklyHours?: number | null;
}
export type JobEntryInput = Omit<JobEntry, 'id' | 'employeeId' | 'source'>;

export interface RecordTimelineItem {
  at: string;
  kind: 'ENCOUNTER' | 'RESULT' | 'DOCUMENT' | 'PROBLEM' | 'FITNESS' | 'CASE';
  ref: string;
  title: string;
}

// ---------------------------------------------------------------- encounters (§4.2)
export type TemplateFieldType = 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'SELECT' | 'MULTISELECT' | 'BOOLEAN' | 'DATE' | 'CODE';
export interface TemplateField {
  key: string;
  label: string;
  type: TemplateFieldType;
  required?: boolean;
  options?: { value: string; label: string }[];
  codeSystem?: CodeSystem;
  unit?: string;
}
export interface EncounterTemplate {
  type: EncounterType;
  version: number;
  title: string;
  sections: { key: string; title: string; fields: TemplateField[] }[];
  bodyMap: boolean;
  requiresDiagnosis: boolean;
  fitnessStandardPrompt: boolean;
  approvedBy?: string | null;
  status: 'DRAFT' | 'APPROVED';
}

export interface EncounterSummary {
  id: string;
  employeeId: string;
  caseId?: string | null;
  type: EncounterType;
  status: EncounterStatus;
  title?: string;
  author?: string;
  createdAt?: string;
  signedAt?: string | null;
  primaryDiagnosis?: Coding | null;
  aiAssisted?: boolean;
}

export type AnswerValue = string | number | boolean | string[] | Coding | null;
export interface Soap {
  subjective?: string;
  objective?: string;
  assessment?: string;
  plan?: string;
}
export type BodySide = 'FRONT' | 'BACK';
/** Body-map entry as stored by the API: anatomical region, laterality, body view and mechanism. */
export interface BodyMapEntry {
  region: string;
  side: 'LEFT' | 'RIGHT' | 'BILATERAL' | 'MIDLINE';
  view: BodySide;
  mechanism?: string | null;
}
/** UI body-map mark: view + region code with laterality (e.g. LEFT_KNEE). */
export interface BodyMapMark {
  region: string;
  side: BodySide;
  mechanism?: string | null;
}
export interface Diagnosis extends Coding {
  primary: boolean;
}
export const CDS_SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const;
export interface CdsAlert {
  code: string;
  severity?: string;
  message?: string;
  reference?: string;
}
export interface ClinicalEncounter extends EncounterSummary {
  answers: Record<string, AnswerValue>;
  soap: Soap;
  bodyMap: (BodyMapEntry | BodyMapMark)[];
  diagnoses: Diagnosis[];
  addenda: { id: string; text: string; author: string; signedAt: string }[];
  photos: { documentId: string; bodyRegion?: string | null; takenAt?: string }[];
  signature?: { signedBy: string; signedAt: string; sha256: string } | null;
  classification?: string;
  templateVersion?: number;
  fitnessStandardCode?: string | null;
  fitnessStandard?: { code: string; title: string; referenceUrl?: string } | null;
  cdsAlerts: CdsAlert[];
}
export interface EncounterUpdate {
  answers: Record<string, AnswerValue>;
  soap: Soap;
  bodyMap: BodyMapEntry[];
  diagnoses: Diagnosis[];
  fitnessStandardCode?: string | null;
  /** Set when any part of an AI draft was accepted into the note (E03-F07). */
  aiAssisted?: boolean;
}
export interface AiDraft {
  draft: { soap?: Soap; answers?: Record<string, AnswerValue> };
  provider?: string;
  generatedAt?: string;
}

// ---------------------------------------------------------------- terminology (§4.3)
export interface TerminologyRelease {
  system: string;
  version: string;
  loadedAt?: string;
  loadedBy?: string;
  licence?: string;
}
export interface ConceptImport extends Coding {
  active: boolean;
}

// ---------------------------------------------------------------- orders & results (§4.4)
export interface OrderSet {
  code: string;
  title: string;
  items: { category: OrderCategory; code: Coding }[];
}
export interface OrderRequest {
  caseId?: string;
  category: OrderCategory;
  items?: Coding[];
  orderSetCode?: string;
  priority: 'ROUTINE' | 'URGENT';
  clinicalNote?: string;
  overrides?: Override[];
}
export interface Order {
  id: string;
  placerOrderNumber: string;
  employeeId: string;
  caseId?: string | null;
  category: OrderCategory;
  items: Coding[];
  priority: 'ROUTINE' | 'URGENT';
  status: OrderStatus;
  orderedBy?: string;
  orderedAt?: string;
  resultCount?: number;
}
export interface Result {
  id: string;
  orderId?: string | null;
  employeeId: string;
  employeeName?: string;
  category: OrderCategory;
  code: Coding;
  value?: string | number | null;
  unit?: string | null;
  referenceRange?: string | null;
  flag?: ResultFlag | null;
  status: ResultStatus;
  effectiveAt?: string;
  receivedAt?: string;
  source: 'LIS' | 'DEVICE' | 'MANUAL';
  deviceId?: string | null;
  calibrationId?: string | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: string | null;
  ackAction?: AckAction | null;
  ageHours?: number;
  overdue?: boolean;
  detail?: Record<string, unknown> | null;
}
export type AudiogramEar = Record<string, number | null>;
export interface DeviceResult {
  category: DeviceCategory;
  deviceId: string | null;
  deviceModel?: string;
  calibrationId?: string;
  calibrationDate?: string;
  performedAt: string;
  caseId?: string;
  measurements: Record<string, unknown>;
}

// ---------------------------------------------------------------- case summary (§4.6)
export interface CaseHandoverSummary {
  caseRef?: string;
  keyDates?: { label: string; date: string }[] | Record<string, string>;
  diagnoses?: Coding[];
  fitness?: { status?: string; validUntil?: string; reviewDate?: string } | null;
  restrictions?: { label: string; validUntil?: string }[];
  openTasks?: { title: string; dueAt?: string }[];
  lastEncounter?: { id?: string; type?: string; signedAt?: string; title?: string } | null;
}

const emp = (id: string) => `/employees/${seg(id)}`;

/** Typed E03 calls. */
export function recordApi(c: AuthClient) {
  return {
    chart: (employeeId: string) => c.api<ChartSummary>(`${emp(employeeId)}/chart`),
    problems: (employeeId: string) => c.api<Problem[]>(`${emp(employeeId)}/problems`),
    addProblem: (employeeId: string, p: ProblemInput) => c.api<Problem>(`${emp(employeeId)}/problems`, { method: 'POST', body: p }),
    updateProblem: (id: string, p: ProblemInput) => c.api<Problem>(`/problems/${seg(id)}`, { method: 'PUT', body: p }),
    allergies: (employeeId: string) => c.api<Allergy[]>(`${emp(employeeId)}/allergies`),
    addAllergy: (employeeId: string, a: AllergyInput) => c.api<Allergy>(`${emp(employeeId)}/allergies`, { method: 'POST', body: a }),
    updateAllergy: (id: string, a: AllergyInput) => c.api<Allergy>(`/allergies/${seg(id)}`, { method: 'PUT', body: a }),
    medications: (employeeId: string) => c.api<Medication[]>(`${emp(employeeId)}/medications`),
    /** Runs CDS: `409 code=cds_alert` unless `overrides` covers every alert. */
    addMedication: (employeeId: string, m: MedicationInput) => c.api<Medication>(`${emp(employeeId)}/medications`, { method: 'POST', body: m }),
    updateMedication: (id: string, m: MedicationInput) => c.api<Medication>(`/medications/${seg(id)}`, { method: 'PUT', body: m }),
    immunisations: (employeeId: string) => c.api<Immunisation[]>(`${emp(employeeId)}/immunisations`),
    addImmunisation: (employeeId: string, i: ImmunisationInput) => c.api<Immunisation>(`${emp(employeeId)}/immunisations`, { method: 'POST', body: i }),
    jobs: (employeeId: string) => c.api<JobEntry[]>(`${emp(employeeId)}/jobs`),
    addJob: (employeeId: string, j: JobEntryInput) => c.api<JobEntry>(`${emp(employeeId)}/jobs`, { method: 'POST', body: j }),
    updateJob: (id: string, j: JobEntryInput) => c.api<JobEntry>(`/jobs/${seg(id)}`, { method: 'PUT', body: j }),
    timeline: (employeeId: string) => c.api<RecordTimelineItem[]>(`${emp(employeeId)}/record-timeline`),

    templates: () => c.api<EncounterTemplate[]>('/encounter-templates'),
    approveTemplate: (type: EncounterType) => c.api<EncounterTemplate>(`/encounter-templates/${seg(type)}/approve`, { method: 'POST' }),
    encounters: (employeeId: string) => c.api<EncounterSummary[]>(`${emp(employeeId)}/encounters`),
    createEncounter: (employeeId: string, body: { type: EncounterType; caseId?: string; templateVersion?: number }) =>
      c.api<ClinicalEncounter>(`${emp(employeeId)}/encounters`, { method: 'POST', body }),
    encounter: (id: string) => c.api<ClinicalEncounter>(`/encounters/${seg(id)}`),
    saveEncounter: (id: string, body: EncounterUpdate) => c.api<ClinicalEncounter>(`/encounters/${seg(id)}`, { method: 'PUT', body }),
    signEncounter: (id: string) => c.api<ClinicalEncounter>(`/encounters/${seg(id)}/sign`, { method: 'POST' }),
    addAddendum: (id: string, text: string) => c.api<ClinicalEncounter>(`/encounters/${seg(id)}/addenda`, { method: 'POST', body: { text } }),
    enteredInError: (id: string, reason: string) => c.api<ClinicalEncounter>(`/encounters/${seg(id)}/entered-in-error`, { method: 'POST', body: { reason } }),
    uploadPhoto: (id: string, file: Blob, fileName: string, bodyRegion?: string) => {
      const fd = new FormData();
      fd.append('file', file, fileName);
      fd.append('consent', 'true');
      if (bodyRegion) fd.append('bodyRegion', bodyRegion);
      return c.api<{ documentId: string }>(`/encounters/${seg(id)}/photos`, { method: 'POST', body: fd });
    },
    unsigned: (mine: boolean) => c.api<EncounterSummary[]>(`/encounters/unsigned${buildQuery({ mine })}`),
    mechanisms: () => c.api<{ code: string; label: string }[]>('/body-map/mechanisms'),
    aiConsent: (id: string, statement: string) => c.api<unknown>(`/encounters/${seg(id)}/ai-consent`, { method: 'POST', body: { verbal: true, statement } }),
    aiDraft: (id: string, transcript: string) => c.api<AiDraft>(`/encounters/${seg(id)}/ai-draft`, { method: 'POST', body: { transcript } }),
    caseSummary: (caseId: string) => c.api<CaseHandoverSummary>(`/cases/${seg(caseId)}/summary`),

    searchTerms: (system: string, q: string, limit = 20) => c.api<Coding[]>(`/terminology/search${buildQuery({ system, q, limit })}`),
    favourites: () => c.api<Coding[]>('/terminology/favourites'),
    addFavourite: (cd: Pick<Coding, 'system' | 'code'>) => c.api<unknown>('/terminology/favourites', { method: 'POST', body: { system: cd.system, code: cd.code } }),
    /** Contract shows `{system, code}` for GET·POST·DELETE; DELETE sends it as a JSON body (see README "contract notes"). */
    removeFavourite: (cd: Pick<Coding, 'system' | 'code'>) => c.api<unknown>('/terminology/favourites', { method: 'DELETE', body: { system: cd.system, code: cd.code } }),
    recentTerms: () => c.api<Coding[]>('/terminology/recent'),
    releases: () => c.api<TerminologyRelease[]>('/terminology/releases'),
    importConcepts: (concepts: ConceptImport[]) => c.api<{ upserted: number; deactivated: number }>('/terminology/concepts/import', { method: 'POST', body: concepts }),
    cdsStatistics: (from: string, to: string) =>
      c.api<{ fired: number; overridden: number; overriddenWithoutReason: number; overrideRate: number; byCode: { code: string; fired?: number; overridden?: number }[] }>(
        `/cds/statistics${buildQuery({ from, to })}`,
      ),

    orderSets: () => c.api<OrderSet[]>('/order-sets'),
    orders: (employeeId: string) => c.api<Order[]>(`${emp(employeeId)}/orders`),
    placeOrder: (employeeId: string, o: OrderRequest) => c.api<Order>(`${emp(employeeId)}/orders`, { method: 'POST', body: o }),
    cancelOrder: (id: string) => c.api<Order>(`/orders/${seg(id)}/cancel`, { method: 'POST' }),
    results: (employeeId: string, category?: string) => c.api<Result[]>(`${emp(employeeId)}/results${buildQuery({ category })}`),
    deviceResult: (employeeId: string, r: DeviceResult) => c.api<Result>(`${emp(employeeId)}/results/device`, { method: 'POST', body: r }),
    worklist: (overdue?: boolean) => c.api<Result[]>(`/results/worklist${buildQuery({ overdue })}`),
    acknowledge: (id: string, action: AckAction, note: string) => c.api<Result>(`/results/${seg(id)}/acknowledge`, { method: 'POST', body: { action, note } }),
    resultLetter: (id: string) => c.api<DocumentMeta>(`/results/${seg(id)}/letter`, { method: 'POST' }),
  };
}

export type RecordApi = ReturnType<typeof recordApi>;

/** FHIR R4 Bundle export of the OH record (audited download through the BFF). */
export const fhirExportUrl = (employeeId: string): string => bffLink(`${emp(employeeId)}/fhir`);
/** ODH Bundle export (job history & exposures). */
export const odhExportUrl = (employeeId: string): string => bffLink(`${emp(employeeId)}/odh`);

export const codingText = (c?: Coding | null): string => (c ? `${c.display} (${c.system.replace(/_/g, ' ')} ${c.code})` : '–');
