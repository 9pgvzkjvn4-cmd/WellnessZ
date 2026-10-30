/**
 * Increment 5 – E10 drug & alcohol testing in the workspace (docs/increment-05/API-Contract-E09-E10-E11-E12.md §4.2, §4.4).
 *
 * - MANAGER / SAFETY_MANAGER: for-cause request (`POST /workspace/testing/for-cause`) and own requests, **status only**
 *   (`GET /workspace/testing/for-cause`). The test status is mapped to a neutral request phase here so that no outcome
 *   (verified positive / negative, refusal) can ever be rendered; observations, description and results are never kept.
 * - HR / CREW_SCHEDULING: duty-status list (`GET /workspace/testing/status`) – employee, AVAILABLE / REMOVED /
 *   RETURN_PENDING and since when. Reasons, test types and results are never copied.
 */
import { list, s } from './fitness';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;

export const FOR_CAUSE_PATH = '/workspace/testing/for-cause';
export const DUTY_STATUS_PATH = '/workspace/testing/status';

export const OBSERVATIONS = ['ODOUR_OF_ALCOHOL', 'SLURRED_SPEECH', 'UNSTEADY_GAIT', 'ERRATIC_BEHAVIOUR', 'DROWSINESS', 'DILATED_PUPILS', 'ADMISSION', 'OTHER'] as const;
export type Observation = (typeof OBSERVATIONS)[number];
export const OBSERVATION_LABELS: Record<Observation, string> = {
  ODOUR_OF_ALCOHOL: 'Odour of alcohol',
  SLURRED_SPEECH: 'Slurred speech',
  UNSTEADY_GAIT: 'Unsteady walking or balance',
  ERRATIC_BEHAVIOUR: 'Erratic or unusual behaviour',
  DROWSINESS: 'Drowsiness',
  DILATED_PUPILS: 'Dilated or pinpoint pupils',
  ADMISSION: 'The employee said they had used alcohol or drugs',
  OTHER: 'Other observed behaviour',
};

export const FOR_CAUSE_FIELDS = ['employeeId', 'observedAt', 'location', 'observations', 'description', 'secondObserverName'] as const;
export const DESCRIPTION_MAX = 2000;
export const LOCATION_MAX = 200;
export const OBSERVER_MAX = 120;

export interface ForCauseForm {
  employeeId: string;
  employeeName?: string;
  /** `datetime-local` value (local time, no zone). */
  observedAt: string;
  location: string;
  observations: Observation[];
  description: string;
  secondObserverName: string;
}

export const emptyForCauseForm = (): ForCauseForm => ({ employeeId: '', observedAt: '', location: '', observations: [], description: '', secondObserverName: '' });

/** Local `datetime-local` string → ISO-8601 UTC, or undefined. */
export function localToIso(v: string): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(v)) return undefined;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** `datetime-local` value for a date (used as the input `max`). */
export function toLocalInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function toggleObservation(current: readonly Observation[], o: Observation): Observation[] {
  return current.includes(o) ? current.filter((x) => x !== o) : [...current, o];
}

export function validateForCause(f: ForCauseForm, now: Date = new Date()): Record<string, string> {
  const e: Record<string, string> = {};
  if (!f.employeeId) e.employeeId = 'Choose the employee.';
  const at = localToIso(f.observedAt);
  if (!at) e.observedAt = 'Enter the date and time of the observation.';
  else if (Date.parse(at) > now.getTime() + 60_000) e.observedAt = 'The observation cannot be in the future.';
  if (!f.location.trim()) e.location = 'Enter where you made the observation.';
  if (f.observations.length === 0) e.observations = 'Tick at least one observation.';
  if (!f.description.trim()) e.description = 'Describe what you observed.';
  else if (f.description.length > DESCRIPTION_MAX) e.description = `Use at most ${DESCRIPTION_MAX} characters.`;
  if (!f.secondObserverName.trim()) e.secondObserverName = 'Enter the name of the second person who observed the behaviour.';
  return e;
}

export interface ForCauseRequest {
  employeeId: string;
  observedAt: string;
  location: string;
  observations: Observation[];
  description: string;
  secondObserverName: string;
}

export function buildForCause(f: ForCauseForm): ForCauseRequest {
  return {
    employeeId: f.employeeId,
    observedAt: localToIso(f.observedAt) ?? f.observedAt,
    location: f.location.trim().slice(0, LOCATION_MAX),
    observations: OBSERVATIONS.filter((o) => f.observations.includes(o)),
    description: f.description.trim().slice(0, DESCRIPTION_MAX),
    secondObserverName: f.secondObserverName.trim().slice(0, OBSERVER_MAX),
  };
}

/** Neutral phase of a request as the requester sees it. */
export const REQUEST_PHASES = ['REQUESTED', 'COLLECTOR_DISPATCHED', 'WITH_PROGRAMME', 'CANCELLED'] as const;
export type RequestPhase = (typeof REQUEST_PHASES)[number];
export const REQUEST_PHASE_LABELS: Record<RequestPhase, string> = {
  REQUESTED: 'Requested – collection being arranged',
  COLLECTOR_DISPATCHED: 'Collector on the way',
  WITH_PROGRAMME: 'With the testing programme',
  CANCELLED: 'Cancelled',
};

/** Every test status after collection (including any outcome) collapses to WITH_PROGRAMME. */
export function requestPhase(status: string | undefined): RequestPhase {
  switch ((status ?? '').toUpperCase()) {
    case '':
    case 'REQUIRED':
    case 'NOTIFIED':
      return 'REQUESTED';
    case 'DISPATCHED':
      return 'COLLECTOR_DISPATCHED';
    case 'CANCELLED':
      return 'CANCELLED';
    default:
      return 'WITH_PROGRAMME';
  }
}

export interface ForCauseRow {
  key: string;
  ref?: string;
  displayName: string;
  phase: RequestPhase;
  requestedAt?: string;
}

/** Own requests: reference, employee, neutral phase, date. Nothing else is kept. */
export function toForCauseRows(body: unknown): ForCauseRow[] {
  return list(body)
    .map((raw, i): ForCauseRow | undefined => {
      const r = obj(raw);
      if (!r) return undefined;
      const ref = s(r.ref);
      const displayName = s(r.displayName) ?? s(r.employeeName) ?? s(r.employeeId);
      if (!ref && !displayName) return undefined;
      return { key: s(r.id) ?? ref ?? `row-${i}`, ref, displayName: displayName ?? '–', phase: requestPhase(s(r.status)), requestedAt: s(r.requestedAt) ?? s(r.createdAt) };
    })
    .filter(defined)
    .sort((a, b) => (b.requestedAt ?? '').localeCompare(a.requestedAt ?? ''));
}

/** Reference of a created requirement (response may be the requirement or `{ref}`). */
export const toRequestRef = (body: unknown): string | undefined => s(obj(body)?.ref);

/* ---------------------------------------------------------------- duty status (HR / crew scheduling) */

export const DUTY_STATUSES = ['AVAILABLE', 'REMOVED', 'RETURN_PENDING'] as const;
export type DutyStatus = (typeof DUTY_STATUSES)[number];
export const DUTY_STATUS_LABELS: Record<DutyStatus, string> = {
  AVAILABLE: 'Available',
  REMOVED: 'Removed from safety-sensitive duty',
  RETURN_PENDING: 'Return pending',
};
const isDutyStatus = (v: string): v is DutyStatus => (DUTY_STATUSES as readonly string[]).includes(v);

export const DUTY_STATUS_FIELDS = ['employeeId', 'displayName', 'dutyStatus', 'since'] as const;

export interface DutyStatusRow {
  employeeId: string;
  displayName: string;
  dutyStatus: DutyStatus;
  since?: string;
}

/** Rows with an unknown status are dropped (never guessed); REMOVED first, then RETURN_PENDING, then by name. */
export function toDutyStatusRows(body: unknown): DutyStatusRow[] {
  const order: Record<DutyStatus, number> = { REMOVED: 0, RETURN_PENDING: 1, AVAILABLE: 2 };
  return list(body)
    .map((raw): DutyStatusRow | undefined => {
      const r = obj(raw);
      const employeeId = s(r?.employeeId);
      const st = (s(r?.dutyStatus) ?? '').toUpperCase();
      if (!r || !employeeId || !isDutyStatus(st)) return undefined;
      return { employeeId, displayName: s(r.displayName) ?? employeeId, dutyStatus: st, since: s(r.since) };
    })
    .filter(defined)
    .sort((a, b) => order[a.dutyStatus] - order[b.dutyStatus] || a.displayName.localeCompare(b.displayName));
}

export function filterDutyStatus(rows: readonly DutyStatusRow[], q: string, status: '' | DutyStatus): DutyStatusRow[] {
  const needle = q.trim().toLowerCase();
  return rows.filter((r) => (!status || r.dutyStatus === status) && (!needle || r.displayName.toLowerCase().includes(needle) || r.employeeId.toLowerCase().includes(needle)));
}

export function countDutyStatus(rows: readonly DutyStatusRow[]): Record<DutyStatus, number> {
  const c: Record<DutyStatus, number> = { AVAILABLE: 0, REMOVED: 0, RETURN_PENDING: 0 };
  for (const r of rows) c[r.dutyStatus] += 1;
  return c;
}
