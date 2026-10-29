/**
 * Pure helpers for the E14 scheduling screens (diary layout, time-zone maths, calibration / capacity / queue views,
 * campaign inputs). No React and no I/O so they are unit-tested in isolation.
 */
import { ApiError } from '../auth/AuthClient';
import type { Appointment, CalibrationState, CampaignInput, CampaignType, CapacityRow, Diary, DiaryTemplate } from '../api/scheduling';
import { errorInfo } from '../components/format';

// ---------------------------------------------------------------- time-zone maths
const pad = (n: number) => String(n).padStart(2, '0');

function zonedParts(ms: number, timeZone?: string): { y: number; mo: number; d: number; h: number; mi: number; wd: number } {
  const f = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    weekday: 'short',
    hourCycle: 'h23',
  });
  const p: Record<string, string> = {};
  for (const part of f.formatToParts(new Date(ms))) p[part.type] = part.value;
  const wd = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(p.weekday ?? '') + 1;
  return { y: Number(p.year), mo: Number(p.month), d: Number(p.day), h: Number(p.hour) % 24, mi: Number(p.minute), wd };
}

/** Minutes after midnight in the site's time zone (browser zone when undefined). Accepts "HH:MM" too. */
export function minutesOfDay(value: string, timeZone?: string): number {
  const hm = /^(\d{1,2}):(\d{2})/.exec(value);
  if (hm && !value.includes('T')) return Number(hm[1]) * 60 + Number(hm[2]);
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) return 0;
  const p = zonedParts(ms, timeZone);
  return p.h * 60 + p.mi;
}

/** Calendar day (YYYY-MM-DD) of an instant in the site's time zone. */
export function zonedDay(iso: string, timeZone?: string): string {
  const p = zonedParts(Date.parse(iso), timeZone);
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}`;
}

/** "HH:MM" for an ISO instant (or pass-through for "HH:MM"). */
export function hhmm(value: string, timeZone?: string): string {
  const m = minutesOfDay(value, timeZone);
  return `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
}

/** Wall-clock `date` + `minutes` after midnight in `timeZone` → ISO-8601 UTC. */
export function zonedToUtcIso(date: string, minutes: number, timeZone?: string): string {
  const [y, mo, d] = date.split('-').map(Number) as [number, number, number];
  const wall = Date.UTC(y, mo - 1, d, Math.floor(minutes / 60), minutes % 60);
  if (!timeZone) {
    return new Date(y, mo - 1, d, Math.floor(minutes / 60), minutes % 60).toISOString();
  }
  // Offset of the zone at `guess`; iterate twice to settle across DST transitions.
  let guess = wall;
  for (let i = 0; i < 2; i++) {
    const p = zonedParts(guess, timeZone);
    const asUtc = Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi);
    guess = wall - (asUtc - guess);
  }
  return new Date(guess).toISOString();
}

/** YYYY-MM-DD ± n days (calendar arithmetic, zone-free). */
export function addDays(date: string, n: number): string {
  const [y, mo, d] = date.split('-').map(Number) as [number, number, number];
  const t = new Date(Date.UTC(y, mo - 1, d + n));
  return t.toISOString().slice(0, 10);
}

/** Monday of the ISO week containing `date`. */
export function weekStart(date: string): string {
  const [y, mo, d] = date.split('-').map(Number) as [number, number, number];
  const wd = new Date(Date.UTC(y, mo - 1, d)).getUTCDay(); // 0 = Sun
  return addDays(date, wd === 0 ? -6 : 1 - wd);
}

export function weekDays(date: string): string[] {
  const s = weekStart(date);
  return Array.from({ length: 7 }, (_, i) => addDays(s, i));
}

/** Today in the browser's zone as YYYY-MM-DD. */
export function todayLocal(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

// ---------------------------------------------------------------- diary layout
export interface Interval {
  id: string;
  start: number;
  end: number;
}

export interface LaidOut {
  id: string;
  lane: number;
  lanes: number;
}

/**
 * Side-by-side layout of overlapping intervals (minutes): each item gets the lowest free lane; every item in a cluster
 * of transitively-overlapping items shares that cluster's lane count so widths line up. Touching ends do not overlap.
 */
export function layoutLanes(items: readonly Interval[]): Map<string, LaidOut> {
  const sorted = [...items].sort((a, b) => a.start - b.start || b.end - a.end || a.id.localeCompare(b.id));
  const out = new Map<string, LaidOut>();
  let cluster: { id: string; lane: number }[] = [];
  let laneEnds: number[] = [];
  let clusterEnd = -Infinity;
  const flush = () => {
    const lanes = Math.max(1, laneEnds.length);
    for (const c of cluster) out.set(c.id, { id: c.id, lane: c.lane, lanes });
    cluster = [];
    laneEnds = [];
  };
  for (const it of sorted) {
    const end = Math.max(it.end, it.start + 1);
    if (it.start >= clusterEnd) flush();
    let lane = laneEnds.findIndex((e) => e <= it.start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(end);
    } else laneEnds[lane] = end;
    cluster.push({ id: it.id, lane });
    clusterEnd = Math.max(clusterEnd, end);
  }
  flush();
  return out;
}

/** Start / end minutes of an appointment in the site zone (end clamped to at least start + 5). */
export function appointmentMinutes(a: { startsAt: string; endsAt: string }, timeZone?: string): { start: number; end: number } {
  const start = minutesOfDay(a.startsAt, timeZone);
  let end = minutesOfDay(a.endsAt, timeZone);
  if (end <= start) end = zonedDay(a.endsAt, timeZone) > zonedDay(a.startsAt, timeZone) ? 24 * 60 : start + 5;
  return { start, end };
}

export function overlaps(a: { start: number; end: number }, b: { start: number; end: number }): boolean {
  return a.start < b.end && b.start < a.end;
}

/** Visible diary window: sessions and appointments, padded to whole hours, default 08:00–17:00. */
export function diaryWindow(ranges: readonly { start: number; end: number }[], fallback: [number, number] = [8 * 60, 17 * 60]): [number, number] {
  if (ranges.length === 0) return fallback;
  const lo = Math.min(...ranges.map((r) => r.start));
  const hi = Math.max(...ranges.map((r) => r.end));
  return [Math.max(0, Math.floor(Math.min(lo, fallback[0]) / 60) * 60), Math.min(24 * 60, Math.ceil(Math.max(hi, fallback[1]) / 60) * 60)];
}

/** Snap minutes to a step (default 5) within [min, max − step]. */
export function snapMinutes(m: number, step = 5, min = 0, max = 24 * 60): number {
  const s = Math.round(m / step) * step;
  return Math.min(Math.max(s, min), Math.max(min, max - step));
}

export interface DiaryColumn {
  key: string;
  label: string;
  sessions: { start: number; end: number; roomCode?: string | null; telehealth?: boolean }[];
  appointments: Appointment[];
}

/** Clinician columns for the day view: one per session clinician, plus clinicians with bookings only, plus "Unassigned". */
export function diaryColumns(d: Diary, timeZone?: string): DiaryColumn[] {
  const cols = new Map<string, DiaryColumn>();
  for (const s of d.sessions ?? []) {
    const c = cols.get(s.clinicianUsername) ?? { key: s.clinicianUsername, label: s.clinicianName ?? s.clinicianUsername, sessions: [], appointments: [] };
    c.sessions.push({ start: minutesOfDay(s.start, timeZone), end: minutesOfDay(s.end, timeZone), roomCode: s.roomCode, telehealth: s.telehealth });
    cols.set(s.clinicianUsername, c);
  }
  for (const a of d.appointments ?? []) {
    const key = a.clinicianUsername ?? '';
    const c = cols.get(key) ?? { key, label: key || 'Unassigned', sessions: [], appointments: [] };
    c.appointments.push(a);
    cols.set(key, c);
  }
  return [...cols.values()];
}

/** 540 → "09:00". */
export function formatMinutes(m: number): string {
  const v = ((Math.round(m) % 1440) + 1440) % 1440;
  return `${pad(Math.floor(v / 60))}:${pad(v % 60)}`;
}

// ---------------------------------------------------------------- drag & drop / move
export const APPOINTMENT_DRAG_MIME = 'application/x-wz-appointment';

export interface AppointmentDragPayload {
  id: string;
  version: number;
  /** minutes between the pointer and the card top when the drag started */
  grabOffset: number;
}

export function encodeAppointmentDrag(p: AppointmentDragPayload): string {
  return JSON.stringify(p);
}

export function decodeAppointmentDrag(raw: string | null | undefined): AppointmentDragPayload | undefined {
  if (!raw) return undefined;
  try {
    const v = JSON.parse(raw) as Record<string, unknown>;
    if (typeof v.id !== 'string' || !v.id || typeof v.version !== 'number') return undefined;
    return { id: v.id, version: v.version, grabOffset: typeof v.grabOffset === 'number' ? v.grabOffset : 0 };
  } catch {
    return undefined;
  }
}

export type MoveFailure = { kind: 'stale' | 'conflict' | 'rule' | 'other'; message: string };

/** 409 `stale` (optimistic lock) vs 409 `resource_conflict` (clinician / room / device / employee overlap). */
export function classifyMoveError(e: unknown): MoveFailure {
  const info = errorInfo(e);
  if (e instanceof ApiError && e.status === 409) {
    if (info.code === 'stale') {
      return { kind: 'stale', message: 'Someone else changed this appointment while you were moving it. The diary has been refreshed – please try again.' };
    }
    return { kind: 'conflict', message: `The new time clashes with another booking${info.message ? `: ${info.message}` : '.'}` };
  }
  if (e instanceof ApiError && e.status === 422) return { kind: 'rule', message: info.message };
  return { kind: 'other', message: info.message };
}

// ---------------------------------------------------------------- calibration
export interface CalibrationView {
  state: CalibrationState | 'UNKNOWN';
  label: string;
  symbol: string;
  tone: 'green' | 'amber' | 'red' | 'none';
}

const CAL: Record<CalibrationView['state'], Omit<CalibrationView, 'state'>> = {
  VALID: { label: 'Valid', symbol: '●', tone: 'green' },
  DUE_SOON: { label: 'Due soon', symbol: '▲', tone: 'amber' },
  OVERDUE: { label: 'Overdue', symbol: '■', tone: 'red' },
  UNKNOWN: { label: 'Not recorded', symbol: '–', tone: 'none' },
};

/** Derived state (contract §5: DUE_SOON ≤ 30 days) – the server value wins when present. */
export function calibrationState(dueOn: string | null | undefined, today: string, serverState?: CalibrationState | null, dueSoonDays = 30): CalibrationView['state'] {
  if (serverState) return serverState;
  if (!dueOn) return 'UNKNOWN';
  if (dueOn < today) return 'OVERDUE';
  return dueOn <= addDays(today, dueSoonDays) ? 'DUE_SOON' : 'VALID';
}

export function calibrationView(state: CalibrationView['state']): CalibrationView {
  return { state, ...CAL[state] };
}

// ---------------------------------------------------------------- cold chain
export const DEFAULT_FRIDGE_RANGE: [number, number] = [2, 8];

export function fridgeRange(min?: number | null, max?: number | null): [number, number] {
  return [min ?? DEFAULT_FRIDGE_RANGE[0], max ?? DEFAULT_FRIDGE_RANGE[1]];
}

export function outOfRange(t: number, min?: number | null, max?: number | null): boolean {
  const [lo, hi] = fridgeRange(min, max);
  return t < lo || t > hi;
}

/** Temperature input: number with at most one decimal in a plausible range. */
export function parseTemperature(v: string): number | undefined {
  const t = v.trim().replace(',', '.');
  if (!/^-?\d{1,2}(\.\d)?$/.test(t)) return undefined;
  const n = Number(t);
  return n >= -40 && n <= 60 ? n : undefined;
}

// ---------------------------------------------------------------- queue
/** "< 1 min", "7 min", "1 h 05 min", "–". */
export function formatWait(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes) || minutes < 0) return '–';
  const m = Math.round(minutes);
  if (m < 1) return '< 1 min';
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${pad(m % 60)} min`;
}

/** Hand-off link from call-next: trust only `#/employees/{id}/chart/encounters`, else build it from the id. */
export function encounterHandOff(link: string | undefined, employeeId: string): string {
  if (link && /^#\/employees\/[A-Za-z0-9_.-]+\/chart\/encounters$/.test(link)) return link;
  return `#/employees/${encodeURIComponent(employeeId)}/chart/encounters`;
}

export type CheckInMode = 'badge' | 'employee' | 'appointment';

export function checkInBody(siteCode: string, mode: CheckInMode, value: string): { siteCode: string; badgeId?: string; employeeId?: string; appointmentId?: string } | undefined {
  const v = value.trim();
  if (!siteCode || !v || v.length > 64 || !/^[A-Za-z0-9_.:-]+$/.test(v)) return undefined;
  if (mode === 'badge') return { siteCode, badgeId: v };
  if (mode === 'employee') return { siteCode, employeeId: v.toUpperCase() };
  return { siteCode, appointmentId: v };
}

// ---------------------------------------------------------------- campaigns
export const MAX_BULK_BOOK = 5000;

export interface ParsedIds {
  ids: string[];
  invalid: string[];
  duplicates: number;
  tooMany: boolean;
}

/** Pasted employee ids (newline / comma / semicolon / tab / space separated) → unique, upper-cased ids. */
export function parseEmployeeIds(text: string): ParsedIds {
  const seen = new Set<string>();
  const invalid: string[] = [];
  let duplicates = 0;
  for (const raw of text.split(/[\s,;]+/)) {
    const t = raw.trim().replace(/^"|"$/g, '');
    if (!t) continue;
    if (!/^[A-Za-z0-9_-]{2,40}$/.test(t)) {
      invalid.push(t);
      continue;
    }
    const id = t.toUpperCase();
    if (seen.has(id)) duplicates++;
    else seen.add(id);
  }
  const ids = [...seen];
  return { ids, invalid, duplicates, tooMany: ids.length > MAX_BULK_BOOK };
}

/** Expected slot count: days × stations × floor((dayEnd − dayStart) / slotMinutes). */
export function campaignSlotCount(days: number, dayStart: string, dayEnd: string, slotMinutes: number, stations: number): number {
  const span = minutesOfDay(dayEnd) - minutesOfDay(dayStart);
  if (span <= 0 || slotMinutes <= 0 || stations <= 0 || days <= 0) return 0;
  return days * stations * Math.floor(span / slotMinutes);
}

/** Inclusive list of dates between two YYYY-MM-DD values, optionally skipping weekends (max 92). */
export function dateRange(from: string, to: string, skipWeekends = false): string[] {
  if (!from || !to || to < from) return [];
  const out: string[] = [];
  for (let d = from; d <= to && out.length < 92; d = addDays(d, 1)) {
    const wd = new Date(`${d}T00:00:00Z`).getUTCDay();
    if (skipWeekends && (wd === 0 || wd === 6)) continue;
    out.push(d);
  }
  return out;
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

export interface CampaignForm {
  name: string;
  campaignType: CampaignType;
  siteCode: string;
  appointmentType: string;
  from: string;
  to: string;
  skipWeekends: boolean;
  dayStart: string;
  dayEnd: string;
  slotMinutes: string;
  stations: string;
  deviceKit: string[];
  staff: string[];
  targetCount: string;
}


/** Client-side checks for the campaign setup form (server re-validates; device kit calibration is enforced with 422). */
export function validateCampaignForm(f: CampaignForm): { field: string; message: string }[] {
  const e: { field: string; message: string }[] = [];
  if (!f.name.trim()) e.push({ field: 'cmp-name', message: 'Enter a campaign name.' });
  if (!f.siteCode) e.push({ field: 'cmp-site', message: 'Choose a site.' });
  if (!f.appointmentType) e.push({ field: 'cmp-type', message: 'Choose an appointment type.' });
  if (!f.from || !f.to || f.to < f.from) e.push({ field: 'cmp-from', message: 'Choose a valid date range (last day on or after the first).' });
  else if (dateRange(f.from, f.to, f.skipWeekends).length === 0) e.push({ field: 'cmp-from', message: 'The date range contains no campaign days.' });
  if (!HHMM.test(f.dayStart) || !HHMM.test(f.dayEnd) || f.dayEnd <= f.dayStart) e.push({ field: 'cmp-start', message: 'Day end must be after day start (HH:MM).' });
  const slot = Number(f.slotMinutes);
  if (!Number.isInteger(slot) || slot < 5 || slot > 240) e.push({ field: 'cmp-slot', message: 'Slot length must be 5–240 minutes.' });
  const st = Number(f.stations);
  if (!Number.isInteger(st) || st < 1 || st > 50) e.push({ field: 'cmp-stations', message: 'Stations must be 1–50.' });
  const t = Number(f.targetCount);
  if (f.targetCount !== '' && (!Number.isInteger(t) || t < 0)) e.push({ field: 'cmp-target', message: 'Target must be a whole number.' });
  return e;
}

export function toCampaignInput(f: CampaignForm): CampaignInput {
  return {
    name: f.name.trim(),
    campaignType: f.campaignType,
    siteCode: f.siteCode,
    appointmentType: f.appointmentType,
    days: dateRange(f.from, f.to, f.skipWeekends),
    dayStart: f.dayStart,
    dayEnd: f.dayEnd,
    slotMinutes: Number(f.slotMinutes),
    stations: Number(f.stations),
    deviceKit: f.deviceKit,
    staff: f.staff,
    targetCount: f.targetCount === '' ? 0 : Number(f.targetCount),
  };
}

export const pct = (v: number | null | undefined): string => (v === null || v === undefined || Number.isNaN(v) ? '–' : `${Math.round(v)}%`);

// ---------------------------------------------------------------- capacity
export interface GapView {
  /** capacity − forecast demand (negative = shortfall) */
  gap: number;
  tone: 'short' | 'tight' | 'ok';
  label: string;
  symbol: string;
  /** demand as % of capacity */
  loadPct: number;
}

/**
 * Gap colouring: shortfall (demand above capacity) red ■, tight (≥ 90 % of capacity) amber ▲, else green ●.
 * Computed from capacity and forecast so it does not depend on the sign convention of the server `gap` field.
 */
export function capacityGap(r: Pick<CapacityRow, 'capacityAppointments' | 'forecastDemand'>, tightPct = 90): GapView {
  const cap = r.capacityAppointments ?? 0;
  const demand = r.forecastDemand ?? 0;
  const gap = Math.round(cap - demand);
  const loadPct = cap > 0 ? (demand / cap) * 100 : demand > 0 ? Infinity : 0;
  if (gap < 0) return { gap, tone: 'short', label: `Short by ${-gap}`, symbol: '■', loadPct };
  if (loadPct >= tightPct) return { gap, tone: 'tight', label: `Tight (${gap} spare)`, symbol: '▲', loadPct };
  return { gap, tone: 'ok', label: `${gap} spare`, symbol: '●', loadPct };
}

export function utilisationTone(p: number): 'ok' | 'high' | 'over' {
  return p > 100 ? 'over' : p >= 85 ? 'high' : 'ok';
}

/** First and last day of a YYYY-MM month. */
export function monthBounds(month: string): { from: string; to: string } {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${pad(last)}` };
}

export function addMonths(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

// ---------------------------------------------------------------- templates
export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'] as const;

/** Problems with weekly diary template rows (index-prefixed messages); overlapping rows for one clinician on a day. */
export function validateTemplates(rows: readonly DiaryTemplate[]): string[] {
  const errors: string[] = [];
  const re = /^([01]\d|2[0-3]):[0-5]\d$/;
  rows.forEach((r, i) => {
    const n = `Row ${i + 1}`;
    if (!(r.weekday >= 1 && r.weekday <= 7)) errors.push(`${n}: choose a weekday.`);
    if (!r.clinicianUsername.trim()) errors.push(`${n}: clinician is required.`);
    if (!re.test(r.start) || !re.test(r.end)) errors.push(`${n}: times must be HH:MM.`);
    else if (r.end <= r.start) errors.push(`${n}: end must be after start.`);
  });
  for (let i = 0; i < rows.length; i++) {
    for (let j = i + 1; j < rows.length; j++) {
      const a = rows[i]!;
      const b = rows[j]!;
      if (a.weekday === b.weekday && a.clinicianUsername && a.clinicianUsername === b.clinicianUsername && a.start < b.end && b.start < a.end) {
        errors.push(`Rows ${i + 1} and ${j + 1}: ${a.clinicianUsername} has overlapping sessions on ${WEEKDAYS[a.weekday - 1] ?? 'the same day'}.`);
      }
    }
  }
  return errors;
}

/** CSV-safe money formatting for the charging table. */
export function formatAmount(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return '–';
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
