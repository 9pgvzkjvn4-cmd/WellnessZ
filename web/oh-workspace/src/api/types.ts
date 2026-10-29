/** Types mirroring docs/increment-01/API-Contract-E01-E02.md (source of truth). */

export const URGENCIES = ['EMERGENCY', 'URGENT', 'SEMI_URGENT', 'ROUTINE', 'ADVICE'] as const;
export type Urgency = (typeof URGENCIES)[number];

export const CASE_STATUSES = ['INTAKE', 'TRIAGE', 'ASSESSMENT', 'FITNESS_REVIEW', 'TREATMENT', 'CLAIMS', 'MONITORING', 'CLOSED', 'MERGED'] as const;
export type CaseStatus = (typeof CASE_STATUSES)[number];

export const CASE_TYPES = ['INJURY', 'ILLNESS', 'EXPOSURE', 'FITNESS', 'SURVEILLANCE_FOLLOW_UP', 'MENTAL_HEALTH', 'ABSENCE', 'CLAIM', 'SUBSTANCE', 'OTHER'] as const;
export type CaseType = (typeof CASE_TYPES)[number];

export const SAFETY_CATEGORIES = ['FLIGHT_CREW', 'CABIN_CREW', 'CERTIFYING_ENGINEER', 'ATC', 'AIRSIDE_DRIVER', 'FORKLIFT', 'FOOD_HANDLER', 'WORKING_AT_HEIGHT'] as const;
export type SafetyCategory = (typeof SAFETY_CATEGORIES)[number];

export const WAITING_STATES = ['NONE', 'AWAITING_EXTERNAL', 'AWAITING_EMPLOYEE'] as const;
export type WaitingState = (typeof WAITING_STATES)[number];

export const RECORDABILITY = ['NOT_CLASSIFIED', 'FIRST_AID', 'MEDICAL_TREATMENT', 'RESTRICTED_DUTY', 'LOST_TIME', 'FATALITY'] as const;
export type Recordability = (typeof RECORDABILITY)[number];

export const WORK_RELATED = ['YES', 'NO', 'UNDER_REVIEW'] as const;
export type WorkRelated = (typeof WORK_RELATED)[number];

export type SlaStateCode = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'NONE';
export type Intensity = 'STANDARD' | 'INTENSIVE';

export const FITNESS_STATUSES = ['FIT', 'FIT_WITH_RESTRICTIONS', 'TEMPORARILY_UNFIT', 'PERMANENTLY_UNFIT', 'UNDER_REVIEW', 'NOT_ASSESSED', 'UNFIT_PENDING'] as const;
export type FitnessStatus = (typeof FITNESS_STATUSES)[number];

export const QUEUES = ['MY', 'TEAM', 'UNASSIGNED', 'OVERDUE', 'AWAITING_EXTERNAL', 'AWAITING_EMPLOYEE', 'ESCALATED'] as const;
export type QueueCode = (typeof QUEUES)[number];
export type QueueCounts = Partial<Record<QueueCode, number>>;

export interface Sla {
  milestone?: string;
  state: SlaStateCode;
  dueAt?: string | null;
  minutesRemaining?: number | null;
}

export interface CaseSummary {
  id: string;
  caseRef: string;
  employeeId: string;
  employeeName: string;
  subsidiaryCode?: string;
  departmentCode?: string;
  jobCode?: string;
  locationCode?: string;
  category?: string;
  caseType?: CaseType;
  source?: string;
  status: CaseStatus;
  urgency?: Urgency | null;
  safetyCritical?: boolean;
  safetyCategory?: SafetyCategory | null;
  ownerUsername?: string | null;
  ownerName?: string | null;
  queueCode?: string | null;
  waitingState?: WaitingState;
  sla?: Sla | null;
  intensity?: Intensity;
  complexityScore?: number | null;
  duplicateSuspect?: boolean;
  earlyIntervention?: boolean;
  recordability?: Recordability;
  openTasks?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface CaseLink {
  type: 'INCIDENT' | 'CASE' | 'CLAIM' | 'SURVEILLANCE' | 'ENCOUNTER';
  ref: string;
  createdAt?: string;
}

export interface Attachment {
  id: string;
  fileName: string;
  contentType: string;
  size: number;
  uploadedAt?: string;
}

export interface CaseDetail extends CaseSummary {
  costCentre?: string;
  shift?: string;
  incidentRef?: string | null;
  bodyPart?: string | null;
  occurredOn?: string | null;
  language?: string;
  templateCode?: string | null;
  hrisOverride?: boolean;
  clinicalSummary?: string | null;
  intakeDetails?: Record<string, unknown> | null;
  workRelated?: WorkRelated | null;
  fitnessStatus?: FitnessStatus | null;
  restrictionUntil?: string | null;
  mergedIntoCaseId?: string | null;
  links?: CaseLink[];
  attachments?: Attachment[];
  disclosure?: string;
}

export type TaskType = 'CALL_BACK' | 'FOLLOW_UP' | 'HANDOVER' | 'REMINDER' | 'CHECKLIST';
export interface Task {
  id: string;
  caseId: string;
  type: TaskType;
  title: string;
  dueAt?: string | null;
  assignee?: string | null;
  status: 'OPEN' | 'DONE' | 'CANCELLED';
}

export interface CaseHeader {
  caseRef: string;
  status: CaseStatus;
  urgency?: Urgency | null;
  sla?: Sla | null;
  safetyCritical?: boolean;
  safetyCategory?: SafetyCategory | null;
  badges?: string[];
  openTasks?: Task[];
  nextAction?: string | null;
  owner?: string | null;
  queueCode?: string | null;
  intensity?: Intensity;
  complexityScore?: number | null;
}

export interface TimelineEvent {
  at: string;
  type: string;
  summary: string;
  actor?: string;
}

export interface EmployeeTimeline {
  employee: { employeeId?: string; displayName?: string; departmentCode?: string; jobCode?: string; [k: string]: unknown };
  items: { at: string; kind: 'CASE' | 'ENCOUNTER' | 'ABSENCE' | 'TRIGGER' | 'INCIDENT'; ref: string; title: string; status?: string }[];
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface SavedView {
  id: string | number;
  name: string;
  queue: QueueCode;
  filters: QueueFilters;
}

export interface QueueFilters {
  urgency?: string;
  subsidiary?: string;
  caseType?: string;
  safetyCritical?: string;
  q?: string;
}

export type BulkAction = 'REASSIGN' | 'REMIND' | 'CLOSE_DUPLICATES';
export interface BulkResult {
  succeeded: string[];
  failed: { caseId: string; reason: string }[];
}

// ---- Triage (E02-F01) ----
export interface ProtocolBranch {
  outcome?: Urgency;
  next?: string;
}
export interface ProtocolRule extends ProtocolBranch {
  if?: string;
  else?: boolean;
}
export interface ProtocolQuestion {
  id: string;
  text: string;
  type: 'BOOLEAN' | 'NUMBER' | 'SELECT';
  help?: string;
  redFlag?: boolean;
  min?: number;
  max?: number;
  options?: { value: string; label: string }[];
  onYes?: ProtocolBranch;
  onNo?: ProtocolBranch;
  rules?: ProtocolRule[];
  next?: string;
}
export interface Protocol {
  code: string;
  version: number;
  title: string;
  presentation?: string;
  status: string;
  start: string;
  questions: ProtocolQuestion[];
  outcomes: Partial<Record<Urgency, { disposition: string; adviceKey?: string }>>;
}
export interface ProtocolSummary {
  code: string;
  version: number;
  title: string;
  presentation?: string;
  status: string;
}
export type TriageMode = 'IN_PERSON' | 'TELEPHONE' | 'DIGITAL';
export type AnswerValue = boolean | number | string;
export interface CallLog {
  caller: string;
  phone: string;
  startedAt: string;
  endedAt: string;
  consentToRemoteAssessment: boolean;
  callBackAt?: string;
}
export interface TriageRequest {
  protocolCode: string;
  protocolVersion: number;
  mode: TriageMode;
  answers: Record<string, AnswerValue>;
  overrideUrgency?: Urgency;
  overrideReason?: string;
  call?: CallLog;
}
export interface TriageResult {
  assessmentId: string;
  computedUrgency: Urgency;
  finalUrgency: Urgency;
  redFlagsTriggered: string[];
  disposition: string;
  adviceKey?: string | null;
  protocolVersion: number;
  caseType?: CaseType;
  queueCode?: string;
  ownerUsername?: string | null;
  sla?: Sla | null;
  callBackTaskId?: string | null;
  assessedAt?: string;
  assessedBy?: string;
  protocolCode?: string;
}

// ---- Recordability (E01-F07) ----
export interface RecordabilityWizard {
  questions: { key: string; text: string; help?: string; type: 'BOOLEAN' | 'NUMBER' }[];
  definitions: Partial<Record<Recordability, string>>;
  reasonCodes: string[];
}
export interface RecordabilityResult {
  recordability: Recordability;
  computed: Recordability;
  workRelated: WorkRelated;
  localCategory?: string | null;
  caaReportable?: boolean;
  version: number;
  reasonCode?: string | null;
  rationale?: string | null;
  decidedBy?: string;
  decidedAt?: string;
}

// ---- Duplicates / complexity / escalation ----
export interface DuplicateCandidate {
  caseId: string;
  caseRef: string;
  status: CaseStatus;
  createdAt: string;
  score: number;
  reasons: string[];
}
export interface Complexity {
  score: number;
  intensity: Intensity;
  drivers: { code: string; label: string; points: number }[];
  calculatedAt?: string;
  expectedDurationDays?: number | null;
  actualAbsenceDays?: number | null;
}
export type EscalationLevel = 'PHYSICIAN' | 'CMO' | 'EXTERNAL_SPECIALIST' | 'EMERGENCY_SERVICES';
export const ESCALATION_LEVELS: EscalationLevel[] = ['PHYSICIAN', 'CMO', 'EXTERNAL_SPECIALIST', 'EMERGENCY_SERVICES'];
export const ESCALATION_REASONS = ['COMPLEX_CLINICAL', 'MEDICO_LEGAL', 'CREW_FITNESS', 'SAFETY_CRITICAL', 'MENTAL_HEALTH_RISK', 'SECOND_OPINION', 'OTHER'] as const;
export interface Escalation {
  id: string;
  caseId: string;
  caseRef?: string;
  level: EscalationLevel;
  reasonCode: string;
  note?: string;
  status: string;
  raisedBy?: string;
  raisedAt?: string;
  dueAt?: string | null;
  sla?: Sla | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: string | null;
  decision?: string | null;
  decidedBy?: string | null;
  decidedAt?: string | null;
}
export interface Conference {
  id: string;
  caseId?: string;
  scheduledAt: string;
  attendees: { name: string; role: string }[];
  agenda: string;
  minutes?: string | null;
  decisions?: string | null;
  visibility?: 'CLINICAL_ONLY' | 'ALL_ATTENDEES' | null;
}

// ---- Assignment / staff ----
export interface StaffMember {
  username: string;
  displayName: string;
  roles?: string[];
  sites?: string[];
  languages?: string[];
  specialties?: string[];
  caseloadCap?: number;
  active?: boolean;
}
export interface CaseloadRow {
  username: string;
  displayName: string;
  roles?: string[];
  caseloadCap: number;
  openCases: number;
  urgentCases: number;
  outOfOfficeUntil?: string | null;
  coverUsername?: string | null;
  utilisationPct: number;
}
export interface AssignmentHistory {
  at?: string;
  fromUsername?: string | null;
  toUsername?: string | null;
  queueCode?: string | null;
  reason?: string;
  actor?: string;
}
export interface Handover {
  id: string;
  caseId: string;
  caseRef?: string;
  fromUsername?: string;
  toUsername?: string;
  summary: string;
  outstandingActions?: string;
  nextContactAt?: string | null;
  createdAt?: string;
  acknowledgedAt?: string | null;
}

// ---- Intake (E01) ----
export const RECON_OUTCOMES = ['AUTO_CREATED', 'SUGGESTED', 'ACCEPTED', 'DISMISSED', 'IGNORED', 'NO_MATCH', 'PENDING'] as const;
export interface ReconciliationRow {
  incidentId: string;
  externalRef: string;
  occurredAt: string;
  incidentClass: string;
  severity?: string;
  lostTime?: boolean;
  unitCode?: string;
  location?: string;
  outcome: (typeof RECON_OUTCOMES)[number];
  ruleName?: string | null;
  caseId?: string | null;
  caseRef?: string | null;
  caseStatus?: string | null;
  dismissReason?: string | null;
  decidedBy?: string | null;
}
export const DISMISS_REASONS = ['NOT_WORK_RELATED', 'NO_HEALTH_CONSEQUENCE', 'DUPLICATE', 'INSUFFICIENT_DATA', 'OTHER'] as const;
export interface IncidentCase {
  caseRef: string;
  employeeDisplayName: string;
  status: string;
  fitnessStatus?: string | null;
}
export interface DlqItem {
  id: string;
  receivedAt: string;
  source: string;
  reason: string;
  status: string;
}
export const INCIDENT_CLASSES = [
  'INJURY_FIRST_AID', 'INJURY_FIRST_AID_PLUS', 'INJURY_MEDICAL_TREATMENT', 'LOST_TIME_INJURY', 'OCCUPATIONAL_ILLNESS',
  'EXPOSURE', 'BODY_FLUID_EXPOSURE', 'FUME_EVENT', 'HEAT_ILLNESS', 'NEAR_MISS', 'PROPERTY_DAMAGE', 'OTHER',
] as const;

export interface EmployeeRef {
  employeeId: string;
  displayName: string;
  departmentCode?: string;
  jobCode?: string;
  safetyCritical?: boolean;
}
export type EncounterMode = 'WALK_IN' | 'EMERGENCY';
export interface EncounterRequest {
  clientRef: string;
  mode: EncounterMode;
  employeeId: string;
  occurredAt: string;
  siteCode: string;
  location: string;
  chiefComplaint: string;
  actionTaken: string;
}
export interface Encounter extends EncounterRequest {
  id: string;
  employeeName?: string;
  status: 'OPEN' | 'ENRICHED' | 'CONVERTED' | 'CLOSED';
  enrichmentDue?: boolean;
  caseId?: string | null;
  caseRef?: string | null;
  createdBy?: string;
}
