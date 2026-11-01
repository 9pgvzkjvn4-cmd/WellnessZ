/**
 * E33 – module-embedded device capture. Mirrors docs/increment-06/API-Contract-E31-E34.md §14 and the controller
 * `modules/wz-e33-device-capture/…/web/CaptureController.java` (records in `CaptureViews`, `DeviceListService`,
 * `CaptureSessionService`, `BatchService`, `CaptureKpiService`, `Comparison`). Paths are relative to `/api/v1`.
 * Values are immutable once captured: a wrong value is rejected and re-measured, never edited.
 */
import type { AuthClient } from '../auth/AuthClient';
import { BFF_API_PREFIX } from '../auth/bffAuthClient';
import { buildQuery, seg } from './links';

export const CAPTURE_STATES = ['LISTENING', 'PREVIEW', 'FALLBACK_OFFERED', 'FALLBACK_ENTRY', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'] as const;
export type CaptureState = (typeof CAPTURE_STATES)[number];
export const FINAL_STATES: readonly CaptureState[] = ['ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'];

export const DEVICE_STATUSES = ['AVAILABLE', 'BUSY', 'LOCKED_OUT', 'OFFLINE'] as const;
export type DeviceStatus = (typeof DEVICE_STATUSES)[number];

export const CAPTURE_PATHS = ['DEVICE', 'ALTERNATE_DEVICE', 'OCR_VERIFIED', 'MANUAL_ATTESTED'] as const;
export type CapturePath = (typeof CAPTURE_PATHS)[number];
export type FallbackPath = Exclude<CapturePath, 'DEVICE'>;

export const FALLBACK_REASONS = ['DEVICE_UNAVAILABLE', 'DEVICE_LOCKED', 'CONNECTION_FAILED', 'DISPLAY_ONLY', 'OTHER'] as const;
export type FallbackReason = (typeof FALLBACK_REASONS)[number];
export const REJECT_REASONS = ['RETEST', 'WRONG_EMPLOYEE', 'ARTEFACT', 'DEVICE_FAULT', 'OTHER'] as const;
export type RejectReason = (typeof REJECT_REASONS)[number];

export type GateOutcome = 'PASS' | 'INFO' | 'WARN' | 'BLOCK';
export const SUMMARY_CATEGORIES = ['AUDIOMETRY', 'SPIROMETRY', 'VITALS', 'BODY_COMPOSITION'] as const;
export type SummaryCategory = (typeof SUMMARY_CATEGORIES)[number];

/** Session actions the server enables (`CaptureStateMachine.triggers` minus internal ones; ACCEPT removed when blocked). */
export type SessionAction = 'ACCEPT' | 'REJECT' | 'RETEST' | 'CANCEL' | 'CHOOSE_DEVICE' | 'CHOOSE_ENTRY';

// ---------------------------------------------------------------- form registry and device list (§14.2)
export interface FormField {
  path: string;
  label: string;
  type: string;
  unit?: string | null;
  required: boolean;
}
export interface FieldGroup {
  group: string;
  fields: FormField[];
}
export interface FormVerification {
  verifierRoles: string[];
  signOffRoles: string[];
  verifyAlways: boolean;
  releaseToEmployee: boolean;
  restricted: boolean;
}
export interface CaptureForm {
  formCode: string;
  formVersion: string;
  module: string;
  category: string;
  fieldGroups: FieldGroup[];
  target: string;
  verification?: FormVerification;
}

/** Station-aware device choice – no technical parameters (E33-HLR-01). `heldBy` = operator username of a BUSY device. */
export interface DeviceChoice {
  deviceInstanceId: string;
  displayName: string;
  model?: string | null;
  option?: string | null;
  status: DeviceStatus;
  lockReason?: string | null;
  nextAction?: string | null;
  heldBy?: string | null;
}

/** `GET /device-catalogue/stations` (E32, clinicians may read) – used by the station picker. */
export interface Station {
  stationCode: string;
  siteCode: string;
  roomCode?: string | null;
  name?: string | null;
  kind?: string | null;
  testSite?: boolean;
  active?: boolean;
}

// ---------------------------------------------------------------- session (§14.1)
export interface FlagView {
  gate: string;
  outcome: GateOutcome;
  code: string;
  message?: string | null;
}
export interface ValueView {
  observationId: string;
  key: string;
  display?: string | null;
  value?: number | null;
  text?: string | null;
  unit?: string | null;
  bodySite?: string | null;
  method?: string | null;
  interpretation?: string | null;
  loinc?: string | null;
  mdc?: string | null;
}
export interface AttachmentView {
  archiveId: string;
  kind: string;
  mediaType: string;
  sha256: string;
  sizeBytes: number;
  /** Engine endpoint relative to /api/v1 (authorised there). */
  link: string;
}
export interface ProvenanceView {
  captureMethod?: string | null;
  model?: string | null;
  manufacturer?: string | null;
  serial?: string | null;
  udi?: string | null;
  firmware?: string | null;
  driver?: string | null;
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
}
export interface SequenceItem {
  observationId: string;
  category: string;
  status: string;
  decision?: string | null;
  flags: number;
}
export interface ComparisonValues {
  effectiveAt: string;
  values: Record<string, number>;
  flag?: string | null;
}
export interface ComparisonSummary {
  previous: ComparisonValues[];
  baseline?: ComparisonValues | null;
  thresholds: { key: string; value: number; source: string }[];
  trend: { date: string; key: string; value: number }[];
}
export interface Preview {
  observationId: string;
  status: string;
  captureMethod?: string | null;
  effectiveAt?: string | null;
  values: ValueView[];
  flags: FlagView[];
  acceptEnabled: boolean;
  verifyBeforePost: boolean;
  attachments: AttachmentView[];
  fields?: Record<string, unknown>;
  provenance?: ProvenanceView | null;
  /** "device-sourced · {model} · {time}", "photo verified" or "entered manually – attested". */
  marking?: string | null;
  interpretationStatus?: string | null;
  sequence: SequenceItem[];
  comparison?: ComparisonSummary | null;
}
export interface FallbackOption {
  rank: number;
  path: FallbackPath;
  deviceInstanceId?: string | null;
  label: string;
}
export interface AnnotationView {
  id: string;
  author: string;
  createdAt: string;
  text: string;
}
export interface CaptureSession {
  sessionId: string;
  state: CaptureState;
  mode: 'FORM' | 'BATCH' | 'KIOSK';
  employeeId: string;
  employeeName?: string | null;
  caseId?: string | null;
  orderRef?: string | null;
  formCode: string;
  formVersion?: string | null;
  fieldGroup?: string | null;
  category: string;
  deviceInstanceId?: string | null;
  deviceName?: string | null;
  path?: CapturePath | null;
  operator: string;
  siteCode: string;
  stationCode: string;
  openedAt: string;
  expiresAt?: string | null;
  observationId?: string | null;
  observationStatus?: string | null;
  routedRecordType?: string | null;
  routedRecordId?: string | null;
  fallbackReason?: string | null;
  rejectReason?: string | null;
  retestCount: number;
  interpretationStatus?: string | null;
  signedBy?: string | null;
  signedAt?: string | null;
  contextPush?: string | null;
  restricted: boolean;
  /** true = restricted reading the caller may not open: values are absent. */
  restrictedReading: boolean;
  batchId?: string | null;
  queuePosition?: number | null;
  actions: SessionAction[];
  fallbackOptions: FallbackOption[];
  flags: FlagView[];
  preview?: Preview | null;
  annotations: AnnotationView[];
}

export interface OpenSessionRequest {
  formCode: string;
  formVersion?: string;
  fieldGroup?: string;
  employeeId: string;
  caseId?: string;
  orderRef?: string;
  /** Omitted for a capture without a device (photo / manual entry) – only when no device at the station is available. */
  deviceInstanceId?: string;
  siteCode: string;
  stationCode: string;
}
export interface AcceptResult {
  sessionId: string;
  state: CaptureState;
  routedRecordType?: string | null;
  routedRecordId?: string | null;
  routed: { observationId: string; recordType: string; recordId: string }[];
  interpretationStatus?: string | null;
}
export interface Attested {
  model: string;
  serial: string;
  calibrationId: string;
  calibrationDate: string;
}
export interface FallbackRequest {
  path: FallbackPath;
  reasonCode: FallbackReason;
  deviceInstanceId?: string;
  imageArchiveId?: string;
  values?: Record<string, number | string>;
  ocrConfidence?: number;
  attested?: Attested;
}
export interface OcrField {
  key: string;
  value?: number | null;
  confidence?: number | null;
}
export interface OcrResult {
  imageArchiveId: string;
  sha256: string;
  ocrEngine: boolean;
  fields: OcrField[];
}

// ---------------------------------------------------------------- batch mode (§14.5)
export interface QueueEntry {
  queueEntryId: string;
  sessionId: string;
  position: number;
  employeeId: string;
  displayName: string;
  state: CaptureState;
  readings: number;
  current: boolean;
}
export interface BatchView {
  batchId: string;
  siteCode: string;
  screeningEventId?: string | null;
  formCode: string;
  deviceInstanceIds: string[];
  status: string;
  operator: string;
  createdAt: string;
  sessions: QueueEntry[];
}
export interface BatchRequest {
  siteCode: string;
  screeningEventId?: string;
  formCode: string;
  deviceInstanceIds: string[];
  employeeIds?: string[];
  badgeIds?: string[];
}
export interface AcceptAllResult {
  batchId: string;
  accepted: string[];
  skipped: { sessionId: string; reason: string }[];
  status: string;
}

// ---------------------------------------------------------------- KPIs
export interface CaptureKpiResult {
  sessions: number;
  accepted: number;
  deviceCaptures: number;
  usagePct?: number | null;
  minutesSaved?: number | null;
  minutesSavedPerDeviceTest?: number | null;
  retests: number;
  retestRatePct?: number | null;
  observations: number;
  unmatched: number;
  unmatchedRatePct?: number | null;
  byPath: Record<string, number>;
  byState: Record<string, number>;
}
export interface CaptureKpiView {
  siteCode?: string | null;
  from: string;
  to: string;
  kpis: CaptureKpiResult;
  targets: Record<string, number>;
}

/** SSE stream URL (same origin, cookie auth). The BFF may buffer – callers fall back to polling. */
export function sessionEventsUrl(sessionId: string): string {
  return `${BFF_API_PREFIX}/device-capture/sessions/${seg(sessionId)}/events`;
}

/** Engine attachment link through the BFF (the session preview carries `/device-engine/...`). */
export function attachmentHref(link: string): string {
  return `${BFF_API_PREFIX}${link.startsWith('/') ? link : `/${link}`}`;
}

/** One capture awaiting physician sign-off (`GET /device-capture/sign-offs`) – no values; the detail reads the session. */
export interface SignOffItem {
  sessionId: string;
  category: string;
  formCode: string;
  employeeId: string;
  employeeName?: string | null;
  siteCode?: string | null;
  stationCode?: string | null;
  deviceName?: string | null;
  acceptedAt?: string | null;
  verifiedBy?: string | null;
}

export function captureApi(client: AuthClient) {
  const base = '/device-capture';
  return {
    forms: () => client.api<CaptureForm[]>(`${base}/forms`),
    form: (formCode: string) => client.api<CaptureForm>(`${base}/forms/${seg(formCode)}`),
    devices: (formCode: string, siteCode: string, stationCode: string) =>
      client.api<DeviceChoice[]>(`${base}/devices${buildQuery({ formCode, siteCode, stationCode })}`),
    stations: (siteCode?: string) => client.api<Station[]>(`/device-catalogue/stations${buildQuery({ siteCode })}`),
    open: (req: OpenSessionRequest) => client.api<CaptureSession>(`${base}/sessions`, { method: 'POST', body: req }),
    session: (id: string) => client.api<CaptureSession>(`${base}/sessions/${seg(id)}`),
    accept: (id: string, acknowledgedFlags: string[], note?: string) =>
      client.api<AcceptResult>(`${base}/sessions/${seg(id)}/accept`, { method: 'POST', body: { acknowledgedFlags, ...(note ? { note } : {}) } }),
    reject: (id: string, reasonCode: RejectReason, note?: string) =>
      client.api<CaptureSession>(`${base}/sessions/${seg(id)}/reject`, { method: 'POST', body: { reasonCode, ...(note ? { note } : {}) } }),
    cancel: (id: string) => client.api<CaptureSession>(`${base}/sessions/${seg(id)}/cancel`, { method: 'POST' }),
    fallback: (id: string, req: FallbackRequest) => client.api<CaptureSession>(`${base}/sessions/${seg(id)}/fallback`, { method: 'POST', body: req }),
    signOffs: (siteCode?: string) => client.api<SignOffItem[]>(`${base}/sign-offs${buildQuery({ siteCode })}`),
    signOff: (id: string, note?: string) => client.api<CaptureSession>(`${base}/sessions/${seg(id)}/sign-off`, { method: 'POST', body: note ? { note } : {} }),
    annotate: (id: string, text: string) => client.api<AnnotationView>(`${base}/sessions/${seg(id)}/annotations`, { method: 'POST', body: { text } }),
    ocr: (image: Blob, fileName: string, opts: { deviceInstanceId?: string; modelCode?: string; category?: string }) => {
      const fd = new FormData();
      fd.append('image', image, fileName);
      return client.api<OcrResult>(`${base}/ocr${buildQuery(opts)}`, { method: 'POST', body: fd });
    },
    summary: (employeeId: string, category: SummaryCategory) =>
      client.api<ComparisonSummary>(`${base}/employees/${seg(employeeId)}/summary${buildQuery({ category })}`),
    createBatch: (req: BatchRequest) => client.api<BatchView>(`${base}/batches`, { method: 'POST', body: req }),
    batch: (id: string) => client.api<BatchView>(`${base}/batches/${seg(id)}`),
    next: (id: string) => client.api<BatchView>(`${base}/batches/${seg(id)}/next`, { method: 'POST' }),
    acceptAll: (id: string, sessionIds: string[]) => client.api<AcceptAllResult>(`${base}/batches/${seg(id)}/accept-all`, { method: 'POST', body: { sessionIds, acknowledgedFlags: {} } }),
    kpis: (q: { siteCode?: string; from?: string; to?: string }) => client.api<CaptureKpiView>(`${base}/kpis${buildQuery(q)}`),
  };
}
export type CaptureApi = ReturnType<typeof captureApi>;
