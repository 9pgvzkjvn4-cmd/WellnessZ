/**
 * E34 – endpoint & integration security. Mirrors docs/increment-06/API-Contract-E31-E34.md §15 and the controllers in
 * modules/wz-e34-device-security (paths relative to `/api/v1`). Secrets are metadata only – values are write-only and
 * never returned; one-time enrolment and endpoint tokens live only in component state.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

export const ENDPOINT_KINDS = ['GATEWAY', 'DEVICE_HOST_AGENT', 'MOBILE_APP', 'KIOSK', 'DATA_MANAGER', 'VENDOR_CLOUD', 'DEVICE'] as const;
export type EndpointKind = (typeof ENDPOINT_KINDS)[number];
export const ENDPOINT_STATUSES = ['PENDING_ENROLMENT', 'ACTIVE', 'ISOLATED', 'REVOKED'] as const;
export type EndpointStatus = (typeof ENDPOINT_STATUSES)[number];
export const ZONES = ['DEVICE_VLAN', 'GATEWAY_ZONE', 'CLINIC_LAN', 'VENDOR_CLOUD', 'CORE'] as const;
export const SEVERITIES = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export const EVENT_TYPES = [
  'ENDPOINT_CREATED', 'ENROLMENT_SUCCEEDED', 'ENROLMENT_FAILED', 'ENDPOINT_AUTH_FAILED', 'TOKEN_ROTATED', 'CERTIFICATE_RENEWED', 'CERTIFICATE_EXPIRING',
  'ENDPOINT_ISOLATED', 'ENDPOINT_RELEASED', 'ENDPOINT_REVOKED', 'UNKNOWN_DEVICE_IDENTITY', 'MESSAGE_RATE_SPIKE', 'UNEXPECTED_PROTOCOL', 'ZONE_VIOLATION',
  'WEBHOOK_SIGNATURE_FAILED', 'PACKAGE_SIGNATURE_INVALID', 'PACKAGE_SIGNED', 'SIGNING_KEY_ROTATED', 'SECRET_STORED', 'SECRET_RESOLVED', 'SECRET_ROTATED',
  'CONFIG_SUBMITTED', 'CONFIG_APPROVED', 'CONFIG_REJECTED', 'CONFIG_PUBLISHED', 'CONFIG_EXPORTED', 'CONFIG_IMPORTED', 'DEVICE_DECOMMISSIONED',
  'POSTURE_EXCEPTION_APPROVED', 'HARDENING_DRIFT', 'DIAGNOSTIC_TRACE_VIEWED', 'DEVICE_DATA_ACCESSED', 'TEST_PACK_RUN',
] as const;
export const REASON_CODES = ['SUSPECTED_COMPROMISE', 'ANOMALY_CONFIRMED', 'MAINTENANCE', 'DEVICE_LOST', 'DECOMMISSIONED', 'RECERTIFIED', 'FALSE_POSITIVE', 'OTHER'] as const;
export const TEST_OUTCOMES = ['PASS', 'FAIL', 'NOT_RUN'] as const;

export interface CertificateView {
  sha256?: string;
  subject?: string;
  issuer?: string;
  serial?: string;
  notBefore?: string;
  notAfter?: string;
}
export interface Endpoint {
  endpointId: string;
  kind: EndpointKind;
  siteCode: string;
  zone?: string;
  tenantCode?: string;
  status: EndpointStatus;
  statusReason?: string | null;
  certificate?: CertificateView | null;
  certificateExpiresInDays?: number | null;
  tokenIssuedAt?: string | null;
  tokenExpiresAt?: string | null;
  enrolmentExpiresAt?: string | null;
  deviceInstanceIds: string[];
  allowedCidrs?: string[] | null;
  hostname?: string | null;
  platformOs?: string | null;
  platformOsVersion?: string | null;
  agentVersion?: string | null;
  hardeningBaseline?: string | null;
  hardeningStatus?: string | null;
  attested?: boolean;
  recertificationRequired: boolean;
  createdBy?: string;
  createdAt?: string;
  enrolledAt?: string | null;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  isolatedAt?: string | null;
  releasedAt?: string | null;
  revokedAt?: string | null;
}
export interface EndpointBody {
  kind: EndpointKind;
  siteCode: string;
  zone?: string;
  tenantCode?: string;
  deviceInstanceIds: string[];
  allowedCidrs?: string[];
}
/** `enrolmentToken` is shown once, never stored in plaintext. */
export interface Issued {
  endpointId: string;
  status: EndpointStatus;
  enrolmentToken: string;
  enrolmentExpiresAt: string;
}
export interface Rotated {
  endpointId: string;
  endpointToken: string;
  tokenExpiresAt: string;
  previousTokenValidUntil?: string | null;
}
export interface StatusChange {
  endpointId: string;
  status: EndpointStatus;
  reasonCode?: string;
  detectedAt?: string | null;
  changedAt?: string;
  minutesToIsolate?: number | null;
  incidentRef?: string | null;
}
export interface SecurityEvent {
  id: number;
  eventId: string;
  type: string;
  severity: string;
  endpointId?: string | null;
  deviceInstanceId?: string | null;
  siteCode?: string | null;
  zone?: string | null;
  actor?: string | null;
  sourceModule?: string;
  reasonCode?: string | null;
  remoteAddress?: string | null;
  attributes?: Record<string, string>;
  occurredAt: string;
  hash?: string;
}
export interface ChainVerification {
  intact: boolean;
  checked: number;
  firstBrokenId?: number | null;
}
export interface Zone {
  code: string;
  name: string;
  description?: string;
  defaultPolicy?: string;
}
export interface Conduit {
  id: string;
  fromZone: string;
  toZone: string;
  protocol: string;
  ports: string;
  direction: string;
  description?: string;
}
export interface ZoneCidr {
  id: string;
  siteCode: string;
  zone: string;
  cidr: string;
  createdBy?: string;
}
export interface SigningKey {
  keyId: string;
  algorithm: string;
  publicKey?: string;
  status: 'ACTIVE' | 'RETIRED' | 'REVOKED' | string;
  createdBy?: string;
  createdAt?: string;
  retiredAt?: string | null;
  revokedAt?: string | null;
}
export interface SecretMetadata {
  reference: string;
  scope: string;
  name: string;
  createdAt?: string;
  createdBy?: string;
  rotatedAt?: string | null;
  lastResolvedAt?: string | null;
  boundInstances?: string[];
  destroyed?: boolean;
}
export interface SecurityAlert {
  id: string;
  ruleCode: string;
  severity: string;
  endpointId?: string | null;
  subjectKey?: string | null;
  siteCode?: string | null;
  status: 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED' | string;
  detectedAt: string;
  lastSeenAt?: string;
  occurrences: number;
  autoAction?: string | null;
  isolationDueAt?: string | null;
  isolatedAt?: string | null;
  ticketRef?: string | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: string | null;
  closedBy?: string | null;
  closedAt?: string | null;
  resolution?: string | null;
}
export interface AnomalyRule {
  code: string;
  description?: string;
  severity: string;
  enabled: boolean;
  threshold: number;
  windowMinutes: number;
  action: string;
  isolateThreshold?: number | null;
  updatedBy?: string | null;
  updatedAt?: string | null;
}
export interface IncidentEvidence {
  type: string;
  sha256: string;
  archiveId?: string | null;
  description?: string | null;
  addedBy?: string;
  addedAt?: string;
}
export interface Incident {
  incidentRef: string;
  endpointId: string;
  status: 'OPEN' | 'CLOSED' | string;
  summary?: string | null;
  openedBy?: string;
  openedAt?: string;
  detectedAt?: string | null;
  isolationCompletedAt?: string | null;
  minutesToIsolate?: number | null;
  withinSla?: boolean | null;
  slaMinutes: number;
  alertIds?: string[];
  evidence?: IncidentEvidence[];
  timeline?: { at: string; entry: string; actor?: string }[];
  closedBy?: string | null;
  closedAt?: string | null;
  resolution?: string | null;
}
export interface TestPackResult {
  script: string;
  control: string;
  outcome: 'PASS' | 'FAIL' | 'NOT_RUN' | string;
  evidenceSha256?: string;
}
export interface TestPackRun {
  id: string;
  version: string;
  environment: string;
  runBy?: string;
  runAt: string;
  passed: number;
  failed: number;
  notRun: number;
  results: TestPackResult[];
}
export interface Sdlc {
  threatModels: { id: string; adapterOrOption: string; version: string; reviewedOn: string; reviewer?: string; documentRef?: string | null }[];
  sbom?: { release: string; documentId: string; format: string; sha256?: string | null; generatedOn?: string | null } | null;
  vulnerabilities: { id: string; component: string; severity: string; foundOn?: string; dueOn?: string; status: string; fixedOn?: string | null; note?: string | null; overdue: boolean }[];
  disclosureUrl?: string | null;
}
export interface PentestFinding {
  ref: string;
  severity: string;
  title: string;
  status: 'OPEN' | 'FIXED' | 'ACCEPTED' | string;
  engagement?: string | null;
  foundOn?: string | null;
  closedOn?: string | null;
}
export interface Readiness {
  ready: boolean;
  checks: { code: string; passed: boolean; detail?: string }[];
}
export interface Agreement {
  version?: string | null;
  parties?: { party: string; role: string; signatory?: string }[];
  signedOn?: string | null;
  reviewCadence?: string | null;
}

const q = buildQuery;
const post = <T,>(c: AuthClient, path: string, body: unknown = {}) => c.api<T>(path, { method: 'POST', body });
const S = '/device-security';

export function conduitExportUrl(format: 'csv' | 'json'): string {
  return bffLink(`${S}/conduits/export${q({ format })}`);
}

export function deviceSecurityApi(c: AuthClient) {
  const ep = (id: string) => `${S}/endpoints/${seg(id)}`;
  return {
    endpoints: (f: { siteCode?: string; status?: string; kind?: string } = {}) => c.api<Endpoint[]>(`${S}/endpoints${q(f)}`),
    endpoint: (id: string) => c.api<Endpoint>(ep(id)),
    createEndpoint: (body: EndpointBody) => post<Issued>(c, `${S}/endpoints`, body),
    reissueEnrolmentToken: (id: string) => post<Issued>(c, `${ep(id)}/enrolment-token`),
    rotateToken: (id: string) => post<Rotated>(c, `${ep(id)}/rotate-token`),
    isolate: (id: string, reasonCode: string, note: string) => post<StatusChange>(c, `${ep(id)}/isolate`, { reasonCode, note }),
    release: (id: string, reasonCode: string, note: string) => post<StatusChange>(c, `${ep(id)}/release`, { reasonCode, note }),
    revoke: (id: string, reasonCode: string, note: string) => post<StatusChange>(c, `${ep(id)}/revoke`, { reasonCode, note }),
    endpointEvents: (id: string) => c.api<SecurityEvent[]>(`${ep(id)}/events${q({ limit: 100 })}`),
    events: (f: { type?: string; endpointId?: string; from?: string; to?: string; limit?: number } = {}) => c.api<SecurityEvent[]>(`${S}/events${q(f)}`),
    verifyChain: () => c.api<ChainVerification>(`${S}/events/verify`),
    zones: () => c.api<Zone[]>(`${S}/zones`),
    conduits: () => c.api<Conduit[]>(`${S}/conduits`),
    zoneCidrs: () => c.api<ZoneCidr[]>(`${S}/zone-cidrs`),
    signingKeys: () => c.api<SigningKey[]>(`${S}/signing-keys`),
    rotateKey: (algorithm: string) => post<SigningKey>(c, `${S}/signing-keys`, { algorithm }),
    revokeKey: (keyId: string) => post<SigningKey>(c, `${S}/signing-keys/${seg(keyId)}/revoke`),
    secrets: (scope?: string) => c.api<SecretMetadata[]>(`${S}/secrets${q({ scope })}`),
    rotateSecret: (reference: string, value: string) => post<SecretMetadata>(c, `${S}/secrets/${seg(reference)}/rotate`, { value }),
    anomalyRules: () => c.api<AnomalyRule[]>(`${S}/anomaly-rules`),
    alerts: (status?: string) => c.api<SecurityAlert[]>(`${S}/alerts${q({ status })}`),
    acknowledgeAlert: (id: string) => post<SecurityAlert>(c, `${S}/alerts/${seg(id)}/acknowledge`),
    closeAlert: (id: string, resolution: string) => post<SecurityAlert>(c, `${S}/alerts/${seg(id)}/close`, { resolution }),
    incidents: (status?: string) => c.api<Incident[]>(`${S}/incidents${q({ status })}`),
    openIncident: (body: { endpointId: string; alertIds: string[]; summary: string }) => post<Incident>(c, `${S}/incidents`, body),
    closeIncident: (ref: string, resolution: string) => post<Incident>(c, `${S}/incidents/${seg(ref)}/close`, { resolution }),
    testPackRuns: () => c.api<TestPackRun[]>(`${S}/test-pack/runs`),
    recordTestPackRun: (body: { version: string; environment: string; results: TestPackResult[] }) => post<TestPackRun>(c, `${S}/test-pack/runs`, body),
    sdlc: () => c.api<Sdlc>(`${S}/sdlc`),
    pentestFindings: () => c.api<PentestFinding[]>(`${S}/pentest/findings`),
    agreement: () => c.api<Agreement>(`${S}/responsibility-agreement`),
    readiness: () => c.api<Readiness>(`${S}/go-live-readiness`),
  };
}
export type DeviceSecurityApi = ReturnType<typeof deviceSecurityApi>;
