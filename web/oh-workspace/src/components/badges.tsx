import type { Intensity, SafetyCategory, Sla, Urgency } from '../api/types';
import { slaView } from '../domain/sla';
import { humanise } from './format';

/** SLA badge – colour + shape symbol + text label + time remaining (never colour alone). */
export function SlaBadge({ sla, now, compact }: { sla: Sla | null | undefined; now?: number; compact?: boolean }) {
  const v = slaView(sla, now);
  return (
    <span className={`sla sla-${v.tone}`} title={v.description}>
      <span className="sla-symbol" aria-hidden="true">
        {v.symbol}
      </span>
      <span className="sla-label">{v.label}</span>
      {v.remaining && !compact && <span className="sla-remaining">{v.remaining}</span>}
      {v.remaining && compact && <span className="visually-hidden">{v.remaining}</span>}
    </span>
  );
}

const URGENCY_SYMBOL: Record<Urgency, string> = { EMERGENCY: '!!', URGENT: '!', SEMI_URGENT: '◆', ROUTINE: '○', ADVICE: 'i' };

export function UrgencyBadge({ urgency }: { urgency: Urgency | null | undefined }) {
  if (!urgency) return <span className="badge badge-muted">Not triaged</span>;
  return (
    <span className={`badge urgency urgency-${urgency.toLowerCase().replace(/_/g, '-')}`}>
      <span aria-hidden="true" className="urgency-symbol">
        {URGENCY_SYMBOL[urgency] ?? ''}
      </span>{' '}
      {humanise(urgency)}
    </span>
  );
}

export function SafetyBadge({ safetyCritical, category }: { safetyCritical?: boolean; category?: SafetyCategory | null }) {
  if (!safetyCritical) return null;
  return (
    <span className="badge badge-safety" title="Safety-critical role – fitness decision required before closure">
      <span aria-hidden="true">⚠ </span>Safety-critical{category ? `: ${humanise(category)}` : ''}
    </span>
  );
}

export function IntensityBadge({ intensity }: { intensity?: Intensity | null }) {
  if (!intensity) return null;
  return <span className={`badge ${intensity === 'INTENSIVE' ? 'badge-intensive' : 'badge-muted'}`}>{humanise(intensity)}</span>;
}

export function DuplicateFlag({ suspect }: { suspect?: boolean }) {
  if (!suspect) return null;
  return (
    <span className="badge badge-duplicate" title="Possible duplicate case">
      <span aria-hidden="true">⧉ </span>Possible duplicate
    </span>
  );
}

export function RedFlagMark() {
  return (
    <span className="badge badge-redflag">
      <span aria-hidden="true">⚑ </span>Red flag
    </span>
  );
}
