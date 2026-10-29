import type { Sla, SlaStateCode } from '../api/types';

export interface SlaView {
  state: SlaStateCode;
  /** Text label – SLA is never conveyed by colour alone. */
  label: string;
  /** Shape symbol shown next to the label (decorative, aria-hidden). */
  symbol: string;
  /** CSS modifier: sla-green / sla-amber / sla-red / sla-met / sla-none. */
  tone: 'green' | 'amber' | 'red' | 'met' | 'none';
  /** "12 min left", "Overdue by 1 h 5 min", or '' */
  remaining: string;
  /** Full accessible description. */
  description: string;
}

const STATES: Record<SlaStateCode, Pick<SlaView, 'label' | 'symbol' | 'tone'>> = {
  ON_TRACK: { label: 'On track', symbol: '●', tone: 'green' },
  AT_RISK: { label: 'At risk', symbol: '▲', tone: 'amber' },
  BREACHED: { label: 'Breached', symbol: '■', tone: 'red' },
  MET: { label: 'Met', symbol: '✓', tone: 'met' },
  NONE: { label: 'No SLA', symbol: '–', tone: 'none' },
};

/** "45 min", "2 h 5 min", "1 d 3 h". */
export function formatDuration(minutes: number): string {
  const m = Math.round(Math.abs(minutes));
  if (m < 60) return `${m} min`;
  if (m < 24 * 60) {
    const h = Math.floor(m / 60);
    const r = m % 60;
    return r ? `${h} h ${r} min` : `${h} h`;
  }
  const d = Math.floor(m / (24 * 60));
  const h = Math.floor((m % (24 * 60)) / 60);
  return h ? `${d} d ${h} h` : `${d} d`;
}

/** Minutes remaining: server value if present, else derived from dueAt and `now`. */
export function minutesRemaining(sla: Sla, now: number = Date.now()): number | undefined {
  if (typeof sla.minutesRemaining === 'number' && Number.isFinite(sla.minutesRemaining)) return sla.minutesRemaining;
  if (sla.dueAt) {
    const due = new Date(sla.dueAt).getTime();
    if (!Number.isNaN(due)) return Math.round((due - now) / 60_000);
  }
  return undefined;
}

export function slaView(sla: Sla | null | undefined, now: number = Date.now()): SlaView {
  const state: SlaStateCode = sla && sla.state in STATES ? sla.state : 'NONE';
  const base = STATES[state];
  let remaining = '';
  if (sla && (state === 'ON_TRACK' || state === 'AT_RISK' || state === 'BREACHED')) {
    const mins = minutesRemaining(sla, now);
    if (mins !== undefined) remaining = mins < 0 ? `Overdue by ${formatDuration(mins)}` : state === 'BREACHED' ? 'Overdue' : `${formatDuration(mins)} left`;
    else if (state === 'BREACHED') remaining = 'Overdue';
  }
  const milestone = sla?.milestone ? `${sla.milestone.replace(/_/g, ' ').toLowerCase()} ` : '';
  const description = `SLA ${milestone}${base.label.toLowerCase()}${remaining ? `, ${remaining.toLowerCase()}` : ''}`;
  return { state, ...base, remaining, description };
}

/** Derive an SLA from a due time only (e.g. escalation response SLA when the server sends just `dueAt`). */
export function slaFromDue(dueAt: string | null | undefined, now: number = Date.now()): Sla | null {
  if (!dueAt) return null;
  const due = new Date(dueAt).getTime();
  if (Number.isNaN(due)) return null;
  const mins = Math.round((due - now) / 60_000);
  return { state: mins < 0 ? 'BREACHED' : 'ON_TRACK', dueAt, minutesRemaining: mins };
}
