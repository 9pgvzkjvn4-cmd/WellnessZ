/**
 * E32 – device catalogue, administration & provisioning console. Mirrors docs/increment-06/API-Contract-E31-E34.md §5 and
 * §13 and the controllers in modules/wz-e32-device-console (paths relative to `/api/v1`). Console roles never see person
 * data: fleet views are counts and states, traces are PHI-masked server-side.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

export const DEVICE_FAMILIES = ['SPIROMETRY', 'AUDIOMETRY', 'VITALS', 'CARDIOLOGY', 'VISION', 'POC', 'DRUG_ALCOHOL', 'RESP_ERGONOMICS', 'EXPOSURE', 'WEARABLE', 'IMAGING'] as const;
export type DeviceFamily = (typeof DEVICE_FAMILIES)[number];
export const OPTION_CODES = ['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7', 'P8', 'P9', 'P10'] as const;
export type OptionCode = (typeof OPTION_CODES)[number];
export const MODEL_LIFECYCLES = ['CANDIDATE', 'CERTIFIED', 'DEPRECATED', 'RETIRED'] as const;
export const POSTURES = ['ACCEPTABLE', 'EXCEPTION_APPROVED', 'BLOCKED', 'NOT_ASSESSED'] as const;
export const CONFIG_STATUSES = ['DRAFT', 'AWAITING_APPROVAL', 'CERTIFIED', 'PUBLISHED', 'SUSPENDED', 'QUARANTINED', 'SUPERSEDED', 'DECOMMISSIONED'] as const;
export type ConfigStatus = (typeof CONFIG_STATUSES)[number];
export const STEP_STATUSES = ['NOT_STARTED', 'IN_PROGRESS', 'VALID', 'INVALID'] as const;
export type StepStatus = (typeof STEP_STATUSES)[number];
export const ASSOCIATION_METHODS = ['WORKLIST', 'SESSION', 'BADGE', 'VENDOR_ID_MATCH'] as const;
export type AssociationMethod = (typeof ASSOCIATION_METHODS)[number];
export const DISPOSALS = ['DISPOSED', 'TRANSFERRED', 'RETURNED_TO_VENDOR', 'STORED'] as const;
export const PREREQ_STATUSES = ['OPEN', 'DONE', 'NOT_APPLICABLE'] as const;
export type PrereqStatus = (typeof PREREQ_STATUSES)[number];
export const URGENCY_LEVELS = ['LOW', 'NORMAL', 'HIGH'] as const;
export const SCOPING_STATUSES = ['SCOPING', 'SCOPED', 'IN_DEVELOPMENT', 'CERTIFIED', 'REJECTED'] as const;
export const DRIVER_STATUSES = ['REGISTERED', 'CERTIFIED', 'DEPRECATED', 'REVOKED'] as const;
export const POSTURE_CONTROLS = ['GATEWAY_ISOLATION_DEVICE_VLAN', 'OUTBOUND_ONLY_DEVICE_HOST_AGENT', 'NO_DIRECT_REMOTE_ACCESS', 'VENDOR_BROKERED_REMOTE_ACCESS', 'APPLICATION_ALLOW_LIST', 'ENHANCED_MONITORING', 'REMOVABLE_MEDIA_BLOCKED', 'LOCAL_ACCOUNTS_DISABLED'] as const;

export type Json = Record<string, unknown>;

export interface DeviceFamilyCount {
  family: DeviceFamily;
  label: string;
  models: number;
  certifiedModels: number;
}

export interface ModelSummary {
  modelCode: string;
  family: DeviceFamily;
  manufacturer: string;
  model: string;
  options: string[];
  lifecycle: string;
  posture: string;
  certifiedDrivers: number;
  confidence?: string;
  sprint?: string | number;
}

export interface ModelOptionNote {
  option: string;
  supported: boolean;
  notes?: string | null;
}
export interface RegulatoryEntry {
  market: string;
  regime?: string;
  status: string;
  productCodes?: string[];
  reference?: string | null;
  verifiedOn?: string | null;
  source?: string;
}
export interface PostureFinding {
  rule: string;
  outcome: 'PASS' | 'WARN' | 'FAIL' | string;
  message?: string;
}
export interface ModelSecurity {
  mds2?: { received?: boolean; version?: string; documentId?: string };
  sbom?: { available?: boolean; format?: string; documentId?: string };
  supportStatus?: string;
  osName?: string;
  osSupported?: boolean;
  patchPath?: string;
  remoteSupport?: string;
  posture: string;
  evaluatedPosture?: string;
  findings?: PostureFinding[];
  assessedBy?: string;
  assessedAt?: string;
}
export interface ModelDriverRef {
  packageId: string;
  version: string;
  status: string;
  options?: string[];
  certifiedAt?: string | null;
}
export interface ModelRecord {
  modelCode: string;
  family: DeviceFamily;
  familyLabel?: string;
  manufacturer: string;
  model: string;
  variants?: string[];
  ohUse?: string;
  guidelineDrivers?: string;
  units?: string[];
  formsFed?: string[];
  options: ModelOptionNote[];
  connectivity?: string;
  protocol?: string;
  fdaCodes?: string[];
  vendorSoftware?: { name: string; versions: string[] }[];
  udi?: { deviceIdentifier?: string; issuingAgency?: string; source?: string; retrievedOn?: string } | null;
  regulatory: RegulatoryEntry[];
  security: ModelSecurity;
  validationListed?: boolean;
  drivers: ModelDriverRef[];
  lifecycle: string;
  sprint?: string | number;
  confidence?: string;
  inventoryId?: string;
  locallyModified?: boolean;
}

export interface PostureIntake {
  mds2: { received: boolean; version?: string; documentId?: string };
  sbom: { available: boolean; format?: string; documentId?: string };
  supportStatus: string;
  osName?: string;
  osSupported?: boolean;
  patchPath: string;
  remoteSupport: string;
  contacts?: { securityEmail?: string; disclosureUrl?: string };
}
export interface PostureResult {
  modelCode: string;
  posture: string;
  evaluatedPosture: string;
  findings: PostureFinding[];
}

export interface StepGuide {
  step: number;
  text: string;
}
export interface OptionPack {
  option: string;
  name: string;
  howItWorks?: string;
  typicalDevices?: string[];
  securityLevel?: string;
  reliability?: string;
  effort?: string;
  endpointKind?: string | null;
  prerequisites?: string[];
  stepGuide?: StepGuide[];
  help?: Record<string, string>;
  parameterSchema?: JsonSchema;
  commonSchema?: JsonSchema;
}

/** The subset of JSON Schema 2020-12 the option packs use (contract §13.3). */
export interface JsonSchema {
  type?: 'object' | 'string' | 'integer' | 'number' | 'boolean' | 'array';
  title?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: (string | number)[];
  const?: string | number | boolean;
  default?: unknown;
  pattern?: string;
  format?: string;
  minimum?: number;
  maximum?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  items?: JsonSchema;
  additionalProperties?: boolean;
}

export interface Station {
  stationCode: string;
  siteCode: string;
  roomCode?: string;
  name: string;
  kind?: string;
  testSite: boolean;
  active: boolean;
}

export interface OptionComparison {
  option: string;
  name: string;
  prerequisites?: string[];
  reliability?: string;
  securityLevel?: string;
  effort?: string;
  driverAvailable: boolean;
  certifiedDriver?: boolean;
  driver?: { packageId: string; version: string; status: string };
}

export interface FieldFinding {
  field: string;
  code: string;
  message: string;
}
export interface WizardStep {
  step: number;
  name: string;
  status: StepStatus;
  data?: Json;
  errors?: FieldFinding[];
  updatedBy?: string;
  updatedAt?: string;
}
export interface TestStepResult {
  name: string;
  outcome: string;
  detail?: string;
}
export interface TestRun {
  testRunId: string;
  mode: 'SIMULATOR' | 'LIVE';
  status: string;
  datasetId?: string | null;
  steps?: TestStepResult[];
  finishedAt?: string;
  by?: string;
  evidenceSha256?: string;
}
export interface NurseReview {
  outcome: 'ACCEPTED' | 'REJECTED';
  note?: string;
  by?: string;
  at?: string;
}
export interface ApprovalGiven {
  role: string;
  by: string;
  at: string;
  note?: string;
}
export interface DecommissionRequest {
  reason: string;
  disposal: string;
  transferTo?: string;
  requestedBy: string;
  requestedAt: string;
  approvalId?: string;
}
export interface ConfigurationSummary {
  configurationId: string;
  version: number;
  deviceInstanceId: string;
  status: ConfigStatus;
  paused: boolean;
  currentStep: number;
  modelCode: string;
  option?: string;
  siteCode?: string;
  stationCode?: string;
  endpointId?: string;
  driverPackageId?: string;
  driverVersion?: string;
  updatedAt?: string;
  submittedBy?: string;
  clonedFrom?: string;
}
export interface Configuration extends ConfigurationSummary {
  steps: WizardStep[];
  tests: TestRun[];
  nurseReview?: NurseReview;
  simulatorOnlyJustification?: string;
  approvals?: ApprovalGiven[];
  decommissionRequest?: DecommissionRequest;
  disposal?: Json;
  secretsToRebind?: string[];
  approvedBy?: string;
  coApprovedBy?: string;
  approvedAt?: string;
  publishedAt?: string;
  statusReason?: string;
  equipmentDeviceId?: string;
  serial?: string;
  firmware?: string;
  softwareVersion?: string;
  /** Present on the response of a step write (e.g. `model_deprecated`, `driver_not_certified`). */
  warnings?: string[];
}

export interface PrerequisiteItem {
  code: string;
  label: string;
  guidance?: string;
  kind: 'AUTO' | 'MANUAL';
  required?: boolean;
  status: PrereqStatus;
  autoResult?: string;
  evidence?: { text?: string; documentId?: string; reference?: string };
}

export interface StepDefaults {
  option: string;
  parameters: Json;
  common: Json;
  parameterSchema: JsonSchema;
  commonSchema: JsonSchema;
  secretFields: string[];
}

export interface DriverBinding {
  formCode: string;
  formVersion?: string;
  category?: string;
  target?: string;
  verifyBeforePost?: boolean;
  fields: Record<string, string>;
}
export interface DriverMapping {
  key: string;
  display?: string;
  loinc?: string;
  ucum?: string;
}
export interface DriverTestVector {
  name: string;
  input?: string;
  expect?: Json;
}
export interface DriverManifest {
  packageId?: string;
  version?: string;
  capabilities?: { worklistPush?: string; startFromForm?: boolean; historicRetrieval?: boolean };
  bindings?: DriverBinding[];
  mappings?: DriverMapping[];
  testVectors?: DriverTestVector[];
  qualityGates?: string[];
  vendorSoftware?: { name?: string; versions?: string[] };
}
export interface DriverEntry {
  packageId: string;
  version: string;
  family?: string;
  models: string[];
  adapter?: string;
  options: string[];
  status: string;
  sha256?: string;
  signatureKeyId?: string;
  conformanceRunId?: string;
  conformanceEvidenceSha256?: string;
  engineRange?: string;
  registeredBy?: string;
  registeredAt?: string;
  certifiedBy?: string | null;
  certifiedAt?: string | null;
  statusReason?: string;
  manifest?: DriverManifest;
}
export interface MatrixRow {
  modelCode: string;
  option: string;
  packageId: string;
  version: string;
  adapter?: string;
  status: string;
  engineRange?: string;
}
export interface DriverValidation {
  valid: boolean;
  sha256?: string;
  errors?: { field?: string; code?: string; message?: string }[];
  warnings?: { field?: string; code?: string; message?: string }[];
}

export interface PreviewField {
  key: string;
  formField: string;
  value?: unknown;
  unit?: string;
  loinc?: string;
  display?: string;
  filled: boolean;
}
export interface PreviewResult {
  datasetId: string;
  input?: string;
  expectedError?: string;
  expectedGates?: unknown;
  forms: { formCode: string; target?: string; verifyBeforePost?: boolean; fields: PreviewField[] }[];
}

export interface PublishResult {
  configurationId: string;
  deviceInstanceId: string;
  configVersion: number;
  status: string;
  endpointId?: string;
  /** One-time enrolment token – shown once, never stored by the console. */
  enrolmentToken?: string;
  enrolmentExpiresAt?: string;
}

export interface CertificationRecord {
  certificationId: string;
  configurationId: string;
  version: number;
  deviceInstanceId: string;
  model?: { modelCode: string; manufacturer: string; model: string; family: string; posture: string };
  option?: string;
  site?: string;
  station?: string;
  snapshotSha256?: string;
  driver?: { packageId: string; version: string; sha256?: string; signatureKeyId?: string };
  tests?: { testRunId: string; mode: string; status: string; evidenceSha256?: string; finishedAt?: string }[];
  nurseReview?: NurseReview;
  submittedBy?: string;
  submittedAt?: string;
  approvedBy?: string;
  coApprovedBy?: string;
  approvedAt?: string;
  approvalSignatureHash?: string;
}

export interface DataFlow {
  device?: Json;
  endpoint?: { endpointId?: string; kind?: string; zone?: string };
  adapter?: unknown;
  driver?: Json | null;
  gates?: string[];
  associationMethods?: string[];
  bindings?: { form: string; formVersion?: string; target?: string; fields?: unknown; verifyBeforePost?: boolean }[];
  retention?: Json;
}

export interface HistoryEntry {
  actor: string;
  action: string;
  step?: number | null;
  version?: number | null;
  diff?: { path: string; from?: unknown; to?: unknown }[] | null;
  detail?: Json | null;
  approvalRef?: string | null;
  at: string;
}
export interface ChangeEntry extends Omit<HistoryEntry, 'diff'> {
  entityType: string;
  entityId: string;
  diff?: unknown;
}

export interface Approval {
  approvalId: string;
  kind: 'CONFIGURATION' | 'DECOMMISSION' | 'IMPORT' | 'POSTURE_EXCEPTION' | 'SCHEDULE_CHANGE' | string;
  reference: string;
  version?: number | null;
  status: string;
  requestedBy: string;
  requestedAt: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  note?: string | null;
  detail?: Json | null;
}

export interface DiffEntry {
  path: string;
  from?: unknown;
  to?: unknown;
}
export interface ExportItem {
  configurationId: string;
  version: number;
  status: string;
  snapshot: Json;
  driver?: { packageId: string; version: string; sha256: string } | null;
}
export interface ExportPayload {
  exportId: string;
  sourceEnvironment: string;
  createdAt: string;
  items: ExportItem[];
  sha256: string;
}
export interface ImportItem {
  configurationId?: string;
  targetConfigurationId?: string | null;
  action: 'CREATE' | 'UPDATE';
  diff: DiffEntry[];
  secretsToRebind?: string[];
  problems?: string[];
}
export interface ImportResult {
  importId: string;
  status: string;
  targetEnvironment?: string;
  sourceEnvironment?: string;
  importedBy?: string;
  items: ImportItem[];
  createdConfigurations?: Json[] | null;
  decidedBy?: string | null;
}

export interface CalibrationSchedule {
  family: string;
  checkType: string;
  intervalHours: number;
  graceMinutes?: number;
  lockout: boolean;
  reminderBeforeHours?: number;
  perLot?: boolean;
  description?: string;
}
export interface QcCheckVerdict {
  checkType: string;
  verdict: string;
  lastCheckAt?: string | null;
  dueAt?: string | null;
  lockout?: boolean;
  lot?: string | null;
}
export interface CalibrationStatus {
  deviceInstanceId: string;
  modelCode: string;
  model: string;
  siteCode?: string;
  stationCode?: string;
  status: string;
  checks: QcCheckVerdict[];
  calibration?: { verdict?: string; dueOn?: string | null; reason?: string; equipmentDeviceId?: string };
  lockedOut: boolean;
  lockoutReason?: 'QC' | 'CALIBRATION' | null;
}
export interface CalibrationHistory extends CalibrationStatus {
  history: { checkType: string; performedAt: string; outcome: string; lot?: string | null; lotExpiry?: string | null; source?: string; recordedBy?: string }[];
  lockoutEvents: { checkType: string; event: string; reason?: string; actor?: string; at: string }[];
}
export interface QcCheckBody {
  deviceInstanceId: string;
  checkType: string;
  performedAt: string;
  outcome: 'PASS' | 'FAIL';
  lot?: string;
  lotExpiry?: string;
  evidenceDocumentId?: string;
}

export interface FleetHealth {
  state: string;
  lastSeenAt?: string | null;
  messages24h?: number | null;
  errors24h?: number | null;
  p95LatencyMillis?: number | null;
  unmatchedOpen?: number | null;
  deadLetters?: number | null;
  reason?: string;
}
export interface FleetAlertRef {
  alertId: string;
  type: string;
  level: string;
  raisedAt: string;
  status: string;
}
export interface FleetRow {
  deviceInstanceId: string;
  configurationId: string;
  configVersion: number;
  modelCode: string;
  model?: string | null;
  family?: string | null;
  siteCode?: string;
  stationCode?: string;
  status: string;
  health: FleetHealth;
  lastSeenAt?: string | null;
  firmware?: string | null;
  softwareVersion?: string | null;
  driverPackageId?: string;
  driverVersion?: string;
  endpointId?: string | null;
  endpointStatus?: string | null;
  calibration?: { verdict?: string; dueOn?: string | null } | null;
  qc?: QcCheckVerdict[];
  lockedOut?: boolean;
  alerts: FleetAlertRef[];
}
export interface FleetSite {
  siteCode: string;
  devices: number;
  healthy: number;
  degraded: number;
  silent: number;
  quarantined: number;
  calibrationDue: number;
  lockedOut: number;
}
export interface FleetAlert {
  alertId: string;
  deviceInstanceId: string;
  siteCode?: string;
  type: string;
  level: string;
  detail?: string;
  status: 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED' | string;
  raisedAt: string;
  acknowledgedBy?: string | null;
  acknowledgedAt?: string | null;
  ticketRef?: string | null;
}
export interface Consumable {
  item: string;
  lot?: string;
  expiry?: string | null;
  quantity: number;
  expired?: boolean;
}
export interface DiagnosticsHealth extends FleetHealth {
  deviceInstanceId: string;
  status: string;
  endpointId?: string;
}
export interface TraceEntry {
  messageId?: string;
  receivedAt?: string;
  stage?: string;
  outcome?: string;
  code?: string;
  maskedExcerpt?: string;
  mappingOutcome?: string;
  clockOffsetMillis?: number | null;
}
export interface Trace {
  deviceInstanceId: string;
  modelCode: string;
  option?: string;
  driver?: string;
  timeZone?: string;
  entries: TraceEntry[];
}

export interface ModelRequestBody {
  manufacturer: string;
  model: string;
  family: string;
  connectivity: string;
  interfaceDocumentIds: string[];
  sites: string[];
  urgency: string;
}
export interface ModelRequest extends ModelRequestBody {
  id: string;
  requestRef: string;
  status: string;
  scoping?: { existingFamilyAdapter?: boolean; adapter?: string; effortDays?: number; targetSprint?: string; note?: string } | null;
  requestedBy: string;
  requestedAt: string;
  scopedBy?: string | null;
  firstScopedAt?: string | null;
  scopingWorkingDays?: number | null;
}
export interface ScopingBody {
  existingFamilyAdapter: boolean;
  adapter?: string;
  effortDays?: number;
  targetSprint?: string;
  status: string;
  note?: string;
}
export interface ModelRequestKpi {
  scopedRequests: number;
  awaitingScoping: number;
  medianScopingWorkingDays?: number | null;
  targetWorkingDays: number;
  withinTargetPct?: number | null;
}

const q = buildQuery;
const post = <T,>(c: AuthClient, path: string, body: unknown = {}) => c.api<T>(path, { method: 'POST', body });
const put = <T,>(c: AuthClient, path: string, body: unknown) => c.api<T>(path, { method: 'PUT', body });

export function certificatePdfUrl(configurationId: string): string {
  return bffLink(`/device-configurations/${seg(configurationId)}/certificate.pdf`);
}
export function calibrationReportUrl(siteCode: string | undefined, format: 'csv' | 'pdf'): string {
  return bffLink(`/device-calibration/report${q({ siteCode, format })}`);
}

export function devicesApi(c: AuthClient) {
  const cfg = (id: string) => `/device-configurations/${seg(id)}`;
  return {
    // catalogue
    families: () => c.api<DeviceFamilyCount[]>('/device-catalogue/families'),
    models: (f: { family?: string; q?: string; option?: string; lifecycle?: string; posture?: string } = {}) =>
      c.api<ModelSummary[]>(`/device-catalogue/models${q(f)}`),
    model: (code: string) => c.api<ModelRecord>(`/device-catalogue/models/${seg(code)}`),
    compare: (codes: string[]) => c.api<ModelRecord[]>(`/device-catalogue/compare${q({ models: codes.join(',') })}`),
    putPosture: (code: string, intake: PostureIntake) => put<PostureResult>(c, `/device-catalogue/models/${seg(code)}/posture`, intake),
    requestPostureException: (code: string, body: { compensatingControls: string[]; expiresOn: string; riskOwner: string }) =>
      post<Json>(c, `/device-catalogue/models/${seg(code)}/posture/exceptions`, body),
    options: () => c.api<OptionPack[]>('/device-catalogue/options'),
    option: (code: string) => c.api<OptionPack>(`/device-catalogue/options/${seg(code)}`),
    stations: (siteCode?: string) => c.api<Station[]>(`/device-catalogue/stations${q({ siteCode })}`),
    // driver registry
    drivers: (f: { modelCode?: string; status?: string } = {}) => c.api<DriverEntry[]>(`/device-catalogue/drivers${q(f)}`),
    driver: (packageId: string, version: string) => c.api<DriverEntry>(`/device-catalogue/drivers/${seg(packageId)}/${seg(version)}`),
    matrix: (modelCode?: string) => c.api<MatrixRow[]>(`/device-catalogue/drivers/matrix${q({ modelCode })}`),
    validateDriver: (pkg: Json) => post<DriverValidation>(c, '/device-catalogue/drivers/validate', pkg),
    registerDriver: (body: { packageJson: Json; conformanceRunId: string; conformanceEvidenceSha256?: string }) =>
      post<DriverEntry>(c, '/device-catalogue/drivers', body),
    certifyDriver: (p: string, v: string) => post<DriverEntry>(c, `/device-catalogue/drivers/${seg(p)}/${seg(v)}/certify`),
    deprecateDriver: (p: string, v: string, reason: string) => post<DriverEntry>(c, `/device-catalogue/drivers/${seg(p)}/${seg(v)}/deprecate`, { reason }),
    revokeDriver: (p: string, v: string, reason: string) => post<DriverEntry>(c, `/device-catalogue/drivers/${seg(p)}/${seg(v)}/revoke`, { reason }),
    // provisioning wizard
    configurations: (f: { status?: string; siteCode?: string; modelCode?: string } = {}) =>
      c.api<ConfigurationSummary[]>(`/device-configurations${q(f)}`),
    configuration: (id: string) => c.api<Configuration>(cfg(id)),
    createConfiguration: (body: { modelCode: string; siteCode?: string }) => post<Configuration>(c, '/device-configurations', body),
    optionComparison: (id: string) => c.api<OptionComparison[]>(`${cfg(id)}/options`),
    putStep: (id: string, n: number, body: Json) => put<Configuration>(c, `${cfg(id)}/steps/${n}`, body),
    autoCheck: (id: string) => post<Configuration>(c, `${cfg(id)}/steps/3/auto-check`),
    defaults: (id: string) => c.api<StepDefaults>(`${cfg(id)}/steps/4/defaults`),
    preview: (id: string, datasetId: string) => post<PreviewResult>(c, `${cfg(id)}/steps/5/preview`, { datasetId }),
    runTest: (id: string, body: { mode: 'SIMULATOR' | 'LIVE'; datasetId?: string }) => post<TestRun>(c, `${cfg(id)}/steps/6/tests`, body),
    nurseReview: (id: string, body: { outcome: 'ACCEPTED' | 'REJECTED'; note: string }) => post<Configuration>(c, `${cfg(id)}/steps/6/nurse-review`, body),
    submit: (id: string) => post<Configuration>(c, `${cfg(id)}/submit`),
    approve: (id: string, note: string) => post<Configuration>(c, `${cfg(id)}/approve`, { note }),
    reject: (id: string, reason: string) => post<Configuration>(c, `${cfg(id)}/reject`, { reason }),
    publish: (id: string) => post<PublishResult>(c, `${cfg(id)}/publish`),
    clone: (id: string, body: { siteCode: string; stationCode: string }) => post<Configuration>(c, `${cfg(id)}/clone`, body),
    revise: (id: string) => post<Configuration>(c, `${cfg(id)}/revise`),
    pause: (id: string) => post<Configuration>(c, `${cfg(id)}/pause`),
    resume: (id: string) => post<Configuration>(c, `${cfg(id)}/resume`),
    suspend: (id: string, reason: string) => post<Configuration>(c, `${cfg(id)}/suspend`, { reason }),
    resumeService: (id: string) => post<Configuration>(c, `${cfg(id)}/resume-service`),
    decommission: (id: string, body: { reason: string; disposal: string; transferTo?: string }) => post<Configuration>(c, `${cfg(id)}/decommission`, body),
    decideDecommission: (id: string, approve: boolean, note: string) =>
      post<Configuration>(c, `${cfg(id)}/decommission/${approve ? 'approve' : 'reject'}`, { note }),
    history: (id: string) => c.api<HistoryEntry[]>(`${cfg(id)}/history`),
    dataFlow: (id: string) => c.api<DataFlow>(`${cfg(id)}/data-flow`),
    certification: (id: string) => c.api<CertificationRecord>(`${cfg(id)}/certification`),
    // governance
    approvals: (status = 'PENDING') => c.api<Approval[]>(`/device-governance/approvals${q({ status })}`),
    decideApproval: (approvalId: string, approve: boolean, note: string) =>
      post<Json>(c, `/device-governance/approvals/${seg(approvalId)}/${approve ? 'approve' : 'reject'}`, { note }),
    changes: (f: { from?: string; to?: string; configurationId?: string } = {}) => c.api<ChangeEntry[]>(`/device-governance/changes${q(f)}`),
    exportConfigurations: (ids: string[]) => c.api<ExportPayload>(`/device-governance/export${q({ configurationIds: ids.join(',') })}`),
    importExport: (payload: Json) => post<ImportResult>(c, '/device-governance/imports', payload),
    importView: (importId: string) => c.api<ImportResult>(`/device-governance/imports/${seg(importId)}`),
    // calibration, QC and lockout
    schedules: () => c.api<CalibrationSchedule[]>('/device-calibration/schedules'),
    putSchedule: (family: string, checkType: string, body: { intervalHours: number; graceMinutes: number; lockout: boolean; reminderBeforeHours: number }) =>
      put<Json>(c, `/device-calibration/schedules/${seg(family)}/${seg(checkType)}`, body),
    calibrationStatus: (f: { siteCode?: string; stationCode?: string } = {}) => c.api<CalibrationStatus[]>(`/device-calibration/status${q(f)}`),
    calibrationHistory: (instanceId: string) => c.api<CalibrationHistory>(`/device-calibration/instances/${seg(instanceId)}/history`),
    recordCheck: (body: QcCheckBody) => post<Json>(c, '/device-calibration/checks', body),
    releaseLockout: (instanceId: string) => post<CalibrationStatus>(c, `/device-calibration/instances/${seg(instanceId)}/release`),
    // fleet
    fleet: (f: { siteCode?: string; family?: string; state?: string } = {}) => c.api<FleetRow[]>(`/device-fleet${q(f)}`),
    fleetSites: () => c.api<FleetSite[]>('/device-fleet/sites'),
    fleetAlerts: (status?: string) => c.api<FleetAlert[]>(`/device-fleet/alerts${q({ status })}`),
    acknowledgeAlert: (id: string) => post<FleetAlert>(c, `/device-fleet/alerts/${seg(id)}/acknowledge`),
    ticketAlert: (id: string) => post<FleetAlert>(c, `/device-fleet/alerts/${seg(id)}/ticket`),
    putVersions: (instanceId: string, body: { firmware: string; softwareVersion: string }) =>
      put<{ compatible: boolean } & Json>(c, `/device-fleet/instances/${seg(instanceId)}/versions`, body),
    consumables: (instanceId: string) => c.api<Consumable[]>(`/device-fleet/instances/${seg(instanceId)}/consumables`),
    addConsumable: (instanceId: string, body: { item: string; lot: string; expiry: string; quantity: number }) =>
      post<Json>(c, `/device-fleet/instances/${seg(instanceId)}/consumables`, body),
    // diagnostics (masked)
    trace: (instanceId: string, limit = 50) => c.api<Trace>(`/device-diagnostics/${seg(instanceId)}/trace${q({ limit })}`),
    health: (instanceId: string) => c.api<DiagnosticsHealth>(`/device-diagnostics/${seg(instanceId)}/health`),
    resend: (instanceId: string) => post<Json>(c, `/device-diagnostics/${seg(instanceId)}/resend`),
    // request-a-new-model
    modelRequests: (status?: string) => c.api<ModelRequest[]>(`/device-model-requests${q({ status })}`),
    modelRequestKpi: () => c.api<ModelRequestKpi>('/device-model-requests/kpi'),
    requestModel: (body: ModelRequestBody) => post<ModelRequest>(c, '/device-model-requests', body),
    scopeRequest: (id: string, body: ScopingBody) => put<ModelRequest>(c, `/device-model-requests/${seg(id)}/scoping`, body),
  };
}
export type DevicesApi = ReturnType<typeof devicesApi>;
