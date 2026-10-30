import type { Band, Instrument, Rag, Risk } from '../../api/support';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { bandLabel, bandTone, countdown, ragTone, riskTone } from '../../domain/support';

export function RiskBadge({ risk }: { risk: Risk }) {
  return <ToneBadge tone={riskTone(risk)}>{humanise(risk)} risk</ToneBadge>;
}

export function BandBadge({ instrument, band }: { instrument: Instrument; band: Band }) {
  return <ToneBadge tone={bandTone(band)}>{bandLabel(instrument, band)}</ToneBadge>;
}

export function RagBadge({ rag, score }: { rag?: Rag; score?: number }) {
  if (!rag) return <span className="muted">–</span>;
  return (
    <ToneBadge tone={ragTone(rag)}>
      {humanise(rag)}
      {score !== undefined ? ` (${score})` : ''}
    </ToneBadge>
  );
}

/** Live countdown badge (`now` comes from `useNow`). */
export function CountdownBadge({ dueAt, now, amberWithinMin, redWithinMin, prefix }: { dueAt?: string; now: number; amberWithinMin?: number; redWithinMin?: number; prefix?: string }) {
  const c = countdown(dueAt, now, amberWithinMin, redWithinMin);
  return (
    <ToneBadge tone={c.tone} title={dueAt}>
      {prefix ? `${prefix} ` : ''}
      {c.label}
    </ToneBadge>
  );
}

/** Restricted-class banner shown on every support screen (confidentiality rule 1). */
export function RestrictedBanner() {
  return (
    <p className="small muted">
      <strong>Restricted record</strong> – visible to named mental-health clinicians only. Every access is audited.
    </p>
  );
}
