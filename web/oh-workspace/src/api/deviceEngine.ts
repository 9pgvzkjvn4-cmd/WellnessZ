/**
 * E31 – Medical Device Integration Engine, user and operations API (`/device-engine/**`). Mirrors
 * docs/increment-06/API-Contract-E31-E34.md §8, §9, §12 and `modules/wz-e31-device-engine/…/web/EngineController.java`
 * (records in `ClinicalReadService`, `OperationsService`, `MessageStore`, `DriverValidator`, `DriverService`,
 * `SimulatorService`, `Terminology`, `RawPayloadStore`). Operations views are PHI-free (counts, masked dead letters);
 * observations are person-level clinical reads (ABAC, audited) and restricted categories show no values to other OH users.
 */
import type { AuthClient } from '../auth/AuthClient';
import { buildQuery, seg } from './links';

export const OBSERVATION_CATEGORIES = [
  'AUDIOMETRY', 'SPIROMETRY', 'VISION', 'ECG', 'VITALS', 'BODY_COMPOSITION', 'ABPM_HOLTER', 'BREATH_ALCOHOL', 'DRUG_SCREEN', 'POC', 'FIT_TEST',
  'ERGONOMICS', 'EXPOSURE', 'WEARABLE', 'IMAGING',
] as const;
export type ObservationCategory = (typeof OBSERVATION_CATEGORIES)[number];
export const RESTRICTED_CATEGORIES: readonly string[] = ['BREATH_ALCOHOL', 'DRUG_SCREEN'];

export const UNMATCHED_STATUSES = ['OPEN', 'RESOLVED', 'DISCARDED'] as const;
export const RESOLUTION_CODES = ['CONFIRMED_IDENTITY', 'WORKLIST_ERROR', 'BADGE_ERROR', 'TEST_TRAFFIC', 'DUPLICATE', 'OTHER'] as const;
export type ResolutionCode = (typeof RESOLUTION_CODES)[number];
export const DLQ_STATUSES = ['OPEN', 'REPLAYED', 'DISCARDED'] as const;
export const PIPELINE_STAGES = ['ACQUIRE', 'PARSE', 'NORMALISE', 'ASSOCIATE', 'VALIDATE', 'ROUTE', 'PERSIST', 'ACKNOWLEDGE'] as const;
export const CONNECTOR_STATES = ['HEALTHY', 'DEGRADED', 'STALE', 'SILENT', 'ISOLATED'] as const;
export const TERMINOLOGY_SYSTEMS = ['MDC', 'LOINC', 'LOCAL'] as const;

export interface QualityFlag {
  gate: string;
  outcome: 'PASS' | 'INFO' | 'WARN' | 'BLOCK';
  code: string;
  message?: string | null;
}
export interface Component {
  key: string;
  mdcCode?: string | null;
  loincCode?: string | null;
  display?: string | null;
  valueType?: string | null;
  value?: number | null;
  text?: string | null;
  ucum?: string | null;
  bodySite?: string | null;
  method?: string | null;
  interpretation?: string | null;
}
export interface Provenance {
  modelCode?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  udi?: string | null;
  serial?: string | null;
  firmware?: string | null;
  softwareVersion?: string | null;
  driverPackageId?: string | null;
  driverVersion?: string | null;
  calibrationId?: string | null;
  calibrationDate?: string | null;
  calibrationVerdict?: string | null;
  operator?: string | null;
  verifiedBy?: string | null;
  verifiedAt?: string | null;
  associationMethod?: string | null;
  associationConfidence?: number | null;
  clockSkewMillis?: number;
  rawPayloadSha256?: string | null;
  /** Test traffic from a simulator run (contract §12) – never a real measurement. */
  simulated?: boolean;
}
export interface Attachment {
  archiveId: string;
  kind: string;
  mediaType: string;
  sha256: string;
  sizeBytes: number;
}

export interface ObservationSummary {
  observationId: string;
  category: string;
  status: string;
  effectiveAt?: string | null;
  deviceInstanceId?: string | null;
  model?: string | null;
  captureMethod?: string | null;
  flags: string[];
  routedRecordId?: string | null;
  restricted: boolean;
  restrictedNote?: string | null;
}
export interface FhirStored {
  resourceType: string;
  resourceId: string;
  json: string;
  version: number;
}
export interface ObservationView {
  observationId: string;
  reportId?: string | null;
  category: string;
  status: string;
  employeeId?: string | null;
  effectiveAt?: string | null;
  deviceInstanceId?: string | null;
  captureMethod?: string | null;
  /** null for a restricted reading the viewer may not open. */
  components?: Component[] | null;
  flags: QualityFlag[];
  provenance?: Provenance | null;
  attachments: Attachment[];
  formCode?: string | null;
  boundFields?: Record<string, unknown> | null;
  routedRecordType?: string | null;
  routedRecordId?: string | null;
  restricted: boolean;
  restrictedNote?: string | null;
  fhir?: FhirStored[] | null;
  /** Raw payload archive of the observation's message (absent for a restricted reading the viewer may not open). */
  rawArchiveId?: string | null;
}
/** Provenance-only view for DEVICE_ADMIN / AUDITOR – no employee, values or filed record. */
export interface ProvenanceView {
  observationId: string;
  category: string;
  status: string;
  effectiveAt?: string | null;
  deviceInstanceId?: string | null;
  captureMethod?: string | null;
  provenance?: Provenance | null;
  rawArchiveId?: string | null;
  rawPayloadSha256?: string | null;
  attachments: Attachment[];
  restricted: boolean;
}

export interface Suggestion {
  employeeId: string;
  displayName: string;
  method: string;
  confidence: number;
}
export interface UnmatchedItem {
  id: string;
  observationId: string;
  category: string;
  receivedAt: string;
  ageHours: number;
  slaDueAt?: string | null;
  deviceInstanceId?: string | null;
  reasonCode?: string | null;
  suggestions: Suggestion[];
  restricted: boolean;
}
export interface ResolveResult {
  id: string;
  status: 'RESOLVED' | 'DISCARDED';
  observationId?: string;
  observationStatus?: string;
}

export interface DeadLetter {
  id: string;
  tenantCode?: string;
  messageId: string;
  observationId?: string | null;
  deviceInstanceId?: string | null;
  stage: string;
  code: string;
  /** PHI-masked detail («masked»). */
  detailMasked?: string | null;
  attempts: number;
  firstFailedAt: string;
  lastFailedAt: string;
  status: 'OPEN' | 'REPLAYED' | 'DISCARDED';
  resolvedBy?: string | null;
  resolvedAt?: string | null;
  reason?: string | null;
}
export interface ReplayResult {
  deadLetterId: string;
  messageId: string;
  status: string;
  observationIds?: string[];
  errorCode?: string;
}

export interface ConnectorHealth {
  deviceInstanceId: string;
  endpointId?: string | null;
  siteCode?: string | null;
  option?: string | null;
  state: string;
  lastSeenAt?: string | null;
  messages24h: number;
  errors24h: number;
  p95LatencyMillis?: number | null;
  unmatchedOpen: number;
  deadLetters: number;
}
export interface EngineMetrics {
  window: string;
  messages: number;
  perSecondPeak?: number;
  latencyP50Ms?: number | null;
  latencyP95Ms?: number | null;
  parseErrorRate: number;
  validationBlockRate: number;
  unmatchedRate: number;
  unmatchedOpen: number;
  slaBreaches: number;
  deadLettersOpen: number;
}
export interface EngineAlert {
  type: string;
  deviceInstanceId?: string | null;
  siteCode?: string | null;
  since?: string | null;
  level: string;
}

export interface DriverSample {
  packageId: string;
  version: string;
  family: string;
  models: string[];
  adapter: string;
  options: string[];
  sha256: string;
  status: string;
}
export interface DriverReport {
  valid: boolean;
  packageId?: string | null;
  version?: string | null;
  sha256?: string | null;
  errors: string[];
  warnings: string[];
  coverage?: { fields: number; mapped: number; coded: number; quantities: number; quantitiesWithUcum: number } | null;
  secretFindings: string[];
}
export interface Conformance {
  passed: boolean;
  packageId?: string | null;
  version?: string | null;
  results: { name: string; outcome: string; diff: string[] }[];
  evidenceSha256?: string | null;
}
export interface GoldenDataset {
  datasetId: string;
  family: string;
  adapter: string;
  packageId?: string;
  kind: string;
  description: string;
}
export interface SimulatorRun {
  runId: string;
  adapter: string;
  datasetId: string;
  messages: number;
  accepted: number;
  duplicates: number;
  failed: number;
  messageIds: string[];
  observationIds: string[];
  statuses: string[];
  evidenceSha256: string;
  /** The published device the run was fed as (or the virtual SIM-<adapter> endpoint). */
  deviceInstanceId?: string | null;
  endpointId?: string | null;
  simulated?: boolean;
}
export interface TerminologyEntry {
  system: string;
  code: string;
  mdc?: string | null;
  loinc?: string | null;
  snomed?: string | null;
  display?: string | null;
  ucum?: string | null;
}
export interface TerminologyVersion {
  version: string;
  description?: string | null;
  active?: boolean;
  loadedAt?: string | null;
}
export interface PredictedValue {
  predicted: number;
  lln: number;
  l: number;
  s: number;
}
export interface PredictedResult {
  equation: string;
  FEV1: PredictedValue;
  FVC: PredictedValue;
  FEV1FVC: PredictedValue;
  release: string;
}
export interface Gate {
  code: string;
  description: string;
  families: string[];
  parameters?: unknown;
  signOff?: { status: string; by?: string; at?: string };
}
export interface ArchiveVerification {
  sha256Stored: string;
  sha256Computed: string;
  intact: boolean;
}

/** P6 vendor-cloud connector status (contract §12 `/device-engine/ops/cloud-connectors`) – PHI-free. */
export interface CloudConnectorStatus {
  deviceInstanceId: string;
  siteCode?: string | null;
  tenantCode: string;
  driverPackageId?: string | null;
  driverStatus?: string;
  pollEnabled: boolean;
  intervalSeconds?: number | null;
  rateLimitPerMinute?: number | null;
  apiHost?: string | null;
  hostAllowed: boolean;
  consentPurpose?: string | null;
  residencyRegion?: string | null;
  state: string;
  lastError?: string | null;
  lastPollAt?: string | null;
  nextPollAt?: string | null;
  consecutiveFailures: number;
  recordsTotal: number;
  recordsLastRun: number;
  duplicatesTotal: number;
  skippedNoConsent: number;
  highWaterMarkSet: boolean;
}
export interface CloudPollRun {
  deviceInstanceId: string;
  status: string;
  error?: string | null;
  records: number;
  accepted: number;
  duplicates: number;
  skippedNoConsent: number;
  nextPollAt?: string | null;
}
export const HISTORIC_STATUSES = ['REQUESTED', 'SENT', 'RECEIVING', 'COMPLETED', 'FAILED'] as const;
/** Historic retrieval (contract §12 `/device-engine/historic-retrievals`). */
export interface HistoricRetrieval {
  id: string;
  deviceInstanceId: string;
  from: string;
  to: string;
  method: 'HL7_QUERY' | 'VENDOR_DB';
  status: (typeof HISTORIC_STATUSES)[number];
  requestedBy: string;
  requestedAt: string;
  completedAt?: string | null;
  received: number;
  accepted: number;
  duplicates: number;
  failed: number;
  detail?: string | null;
}
/** Outbound order / worklist / start message of a capture session or device – status only, never content. */
export interface OutboundMessage {
  id: string;
  commandType: 'PUSH_WORKLIST' | 'START_ACQUISITION';
  deviceInstanceId: string;
  orderRef?: string | null;
  transport: 'MLLP' | 'SERIAL' | 'GDT';
  messageType: string;
  controlId: string;
  status: 'SENT' | 'ACKED' | 'REJECTED' | 'FAILED';
  ackCode?: string | null;
  detail?: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface OutboundStatus {
  messages: OutboundMessage[];
  pending: { commandId: string; commandType: string; status: string; createdAt: string }[];
}

export function engineApi(client: AuthClient) {
  const base = '/device-engine';
  return {
    observations: (q: { employeeId: string; category?: string; from?: string; to?: string }) =>
      client.api<ObservationSummary[]>(`${base}/observations${buildQuery(q)}`),
    observation: (id: string, fhir = false) => client.api<ObservationView>(`${base}/observations/${seg(id)}${fhir ? '?format=fhir' : ''}`),
    provenance: (id: string) => client.api<ProvenanceView>(`${base}/observations/${seg(id)}/provenance`),
    /** Hash check of the observation's raw payload (or one of its attachments) by whoever may read the observation. */
    verifyObservationArchive: (id: string, archiveId?: string) =>
      client.api<ArchiveVerification>(`${base}/observations/${seg(id)}/archive/verify${buildQuery({ archiveId })}`),
    unmatched: (q: { siteCode?: string; status?: string }) => client.api<UnmatchedItem[]>(`${base}/unmatched${buildQuery(q)}`),
    unmatchedCount: (siteCode?: string) => client.api<{ open: number; overdue: number }>(`${base}/unmatched/count${buildQuery({ siteCode })}`),
    resolve: (id: string, body: { employeeId?: string; reasonCode: ResolutionCode; note?: string; discard?: boolean }) =>
      client.api<ResolveResult>(`${base}/unmatched/${seg(id)}/resolve`, { method: 'POST', body }),
    deadLetters: (q: { stage?: string; status?: string }) => client.api<DeadLetter[]>(`${base}/dead-letters${buildQuery(q)}`),
    replay: (id: string, driverVersion?: string) => client.api<ReplayResult>(`${base}/dead-letters/${seg(id)}/replay`, { method: 'POST', body: driverVersion ? { driverVersion } : {} }),
    discard: (id: string, reason: string) => client.api<Record<string, unknown>>(`${base}/dead-letters/${seg(id)}/discard`, { method: 'POST', body: { reason } }),
    connectors: (q: { siteCode?: string; state?: string }) => client.api<ConnectorHealth[]>(`${base}/ops/connectors${buildQuery(q)}`),
    metrics: (window: 'PT1H' | 'P1D') => client.api<EngineMetrics>(`${base}/ops/metrics${buildQuery({ window })}`),
    alerts: () => client.api<EngineAlert[]>(`${base}/ops/alerts`),
    cloudConnectors: () => client.api<CloudConnectorStatus[]>(`${base}/ops/cloud-connectors`),
    pollCloud: (deviceInstanceId: string) => client.api<CloudPollRun>(`${base}/ops/cloud-connectors/${seg(deviceInstanceId)}/poll`, { method: 'POST' }),
    historicRetrievals: (deviceInstanceId?: string) => client.api<HistoricRetrieval[]>(`${base}/historic-retrievals${buildQuery({ deviceInstanceId })}`),
    requestHistoric: (body: { deviceInstanceId: string; from: string; to: string }) =>
      client.api<HistoricRetrieval>(`${base}/historic-retrievals`, { method: 'POST', body }),
    outbound: (q: { sessionId?: string; deviceInstanceId?: string }) => client.api<OutboundStatus>(`${base}/outbound${buildQuery(q)}`),
    drivers: () => client.api<DriverSample[]>(`${base}/drivers`),
    driverSource: (packageId: string, version: string) => client.api<unknown>(`${base}/drivers/${seg(packageId)}/${seg(version)}`),
    validate: (packageJson: unknown) => client.api<DriverReport>(`${base}/drivers/validate`, { method: 'POST', body: packageJson }),
    conformance: (packageJson: unknown) => client.api<Conformance>(`${base}/drivers/conformance`, { method: 'POST', body: { packageJson } }),
    golden: (family?: string) => client.api<GoldenDataset[]>(`${base}/golden-datasets${buildQuery({ family })}`),
    simulate: (adapter: string, body: { datasetId: string; count?: number; deviceInstanceId?: string }) =>
      client.api<SimulatorRun>(`${base}/simulators/${seg(adapter)}/run`, { method: 'POST', body }),
    terminology: (q: { system?: string; code?: string }) => client.api<TerminologyEntry[]>(`${base}/terminology/maps${buildQuery(q)}`),
    terminologyVersions: () => client.api<TerminologyVersion[]>(`${base}/terminology/versions`),
    predicted: (q: { age: number; sex: 'M' | 'F'; heightCm: number }) => client.api<PredictedResult>(`${base}/terminology/predicted${buildQuery({ equation: 'GLI_2012', ...q })}`),
    gates: () => client.api<Gate[]>(`${base}/gates`),
    verifyArchive: (archiveId: string) => client.api<ArchiveVerification>(`${base}/archive/${seg(archiveId)}/verify`),
  };
}
export type EngineApi = ReturnType<typeof engineApi>;
