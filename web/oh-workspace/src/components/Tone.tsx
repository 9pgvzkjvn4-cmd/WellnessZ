import type { ReactNode } from 'react';
import type { Tone } from '../domain/absence';

const TONE_CLASS: Record<Tone, string> = { red: 'badge-overdue', amber: 'badge-expiring', green: 'badge-complete', muted: 'badge-muted' };
const TONE_SYMBOL: Record<Tone, string> = { red: '■', amber: '▲', green: '●', muted: '○' };

/** Status badge driven by a tone – colour + shape symbol + text (never colour alone). */
export function ToneBadge({ tone, children, title }: { tone: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge ${TONE_CLASS[tone]}`} title={title}>
      <span aria-hidden="true">{TONE_SYMBOL[tone]} </span>
      {children}
    </span>
  );
}

/** KPI tile with an optional target line ("Target ≥ 95 %") and met / missed state. */
export function KpiTile({ label, value, target, status }: { label: string; value: ReactNode; target?: string; status?: 'MET' | 'MISSED' | 'UNKNOWN' }) {
  return (
    <div className={`tile kpi-tile${status === 'MISSED' ? ' kpi-missed' : ''}`}>
      <span className={`tile-value${status === 'MISSED' ? ' text-red' : ''}`}>{value}</span>
      <span className="kpi-label">{label}</span>
      {target && (
        <span className="small muted">
          {target}
          {status === 'MET' && <> · <ToneBadge tone="green">on target</ToneBadge></>}
          {status === 'MISSED' && <> · <ToneBadge tone="red">off target</ToneBadge></>}
        </span>
      )}
    </div>
  );
}

export interface TrendSeries {
  name: string;
  values: (number | null | undefined)[];
  format?: (v: number) => string;
}

/**
 * Small line chart (SVG, role="img") with an accessible data table underneath. Values may be null (gap).
 * `target` draws a dashed reference line for the first series.
 */
export function LineTrend({ labels, series, label, target, max: maxIn }: { labels: string[]; series: TrendSeries[]; label: string; target?: number; max?: number }) {
  if (labels.length === 0) return <p className="muted">No trend data.</p>;
  const w = 300;
  const h = 100;
  const all = series.flatMap((s) => s.values.filter((v): v is number => typeof v === 'number'));
  const max = Math.max(1, maxIn ?? 0, target ?? 0, ...all);
  const x = (i: number) => (labels.length === 1 ? w / 2 : (i / (labels.length - 1)) * w);
  const y = (v: number) => h - (v / max) * (h - 4) - 2;
  return (
    <figure className="trend line-trend">
      <svg viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label} preserveAspectRatio="none">
        {target !== undefined && <line x1={0} x2={w} y1={y(target)} y2={y(target)} className="target-line" />}
        {series.map((s, si) => {
          const pts = s.values.map((v, i) => (typeof v === 'number' ? `${x(i)},${y(v)}` : null)).filter(Boolean);
          return <polyline key={s.name} points={pts.join(' ')} className={`trend-line trend-line-${si}`} fill="none" />;
        })}
      </svg>
      <figcaption className="small muted">
        {labels[0]} – {labels[labels.length - 1]}
        {series.length > 1 && <> · {series.map((s, i) => <span key={s.name} className={`legend legend-${i}`}>{s.name} </span>)}</>}
        {target !== undefined && <> · dashed line = target</>}
      </figcaption>
      <details>
        <summary className="small">Show data table</summary>
        <table>
          <thead>
            <tr>
              <th scope="col">Period</th>
              {series.map((s) => (
                <th key={s.name} scope="col" className="num">{s.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {labels.map((l, i) => (
              <tr key={l}>
                <th scope="row">{l}</th>
                {series.map((s) => {
                  const v = s.values[i];
                  return <td key={s.name} className="num">{typeof v === 'number' ? (s.format ? s.format(v) : String(v)) : '–'}</td>;
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
