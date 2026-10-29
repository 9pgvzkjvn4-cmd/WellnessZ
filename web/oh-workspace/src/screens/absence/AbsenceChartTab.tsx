import { useState } from 'react';
import { absenceApi, ABSENCE_TYPES } from '../../api/absence';
import { formatDay, humanise } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { bradfordBand, filterEpisodes } from '../../domain/absence';
import type { ChartTabProps } from '../chart/chartTabProps';

const TYPE_TONE: Record<string, 'red' | 'amber' | 'muted'> = { INJURY_WORK: 'red', SICKNESS: 'amber' };

/** Chart tab `#/employees/{id}/chart/absence` – absence summary (Bradford factor, patterns) and episode timeline with filters. */
export function AbsenceChartTab({ client, onSessionExpired, employeeId }: ChartTabProps) {
  const api = absenceApi(client);
  const summary = useAsync(() => api.summary(employeeId), onSessionExpired, [employeeId]);
  const episodes = useAsync(() => api.episodes(employeeId), onSessionExpired, [employeeId]);
  const [types, setTypes] = useState<string[]>([]);
  const [openOnly, setOpenOnly] = useState(false);
  const [range, setRange] = useState({ from: '', to: '' });
  const s = summary.data;
  const list = filterEpisodes(episodes.data ?? [], { types, openOnly, from: range.from || undefined, to: range.to || undefined });
  const band = s ? bradfordBand(s.bradfordFactor) : undefined;

  return (
    <section aria-labelledby="abs-tab-title">
      <h2 id="abs-tab-title">Absence</h2>
      {summary.loading && !s && <Loading />}
      {!!summary.error && <ErrorAlert error={summary.error} />}
      {s && band && (
        <>
          <div className="tiles" role="group" aria-label="Absence summary, last 12 months">
            <KpiTile label="Episodes (12 months)" value={s.episodes12m} />
            <KpiTile label="Days lost (12 months)" value={s.daysLost12m} />
            <div className="tile kpi-tile">
              <span className="tile-value">{s.bradfordFactor}</span>
              <span className="kpi-label">Bradford factor</span>
              <ToneBadge tone={band.tone}>{band.label}</ToneBadge>
            </div>
            <KpiTile label="Longest episode (days)" value={s.longestDays} />
          </div>
          <p>
            {s.openEpisode ? (
              <ToneBadge tone="amber">Currently absent since {formatDay(s.openEpisode.startDate)} ({humanise(s.openEpisode.absenceType)})</ToneBadge>
            ) : (
              <span className="small muted">No open episode.</span>
            )}{' '}
            {s.patterns.map((p) => (
              <ToneBadge key={p} tone="amber" title="Pattern detected by the trigger engine">{humanise(p)}</ToneBadge>
            ))}{' '}
            {s.activeLtaCaseId && <a href={`#/absence/lta/${encodeURIComponent(s.activeLtaCaseId)}`}>Long-term absence pathway</a>}
          </p>
        </>
      )}

      <form className="filters inline-form" onSubmit={(e) => e.preventDefault()} aria-label="Filter episodes">
        <fieldset>
          <legend>Episode type</legend>
          {ABSENCE_TYPES.map((t) => (
            <label key={t} className="inline-row">
              <input type="checkbox" checked={types.includes(t)} onChange={(e) => setTypes(e.target.checked ? [...types, t] : types.filter((x) => x !== t))} /> {humanise(t)}
            </label>
          ))}
        </fieldset>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <label className="inline-row">
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} /> Open only
        </label>
      </form>
      {episodes.loading && !episodes.data && <Loading />}
      {!!episodes.error && <ErrorAlert error={episodes.error} />}
      {episodes.data && list.length === 0 && <Empty>No episodes match.</Empty>}
      {list.length > 0 && (
        <ol className="timeline" aria-label="Absence episodes, newest first">
          {list.map((e) => (
            <li key={e.id}>
              <strong>{formatDay(e.startDate)} – {e.open ? 'ongoing' : formatDay(e.endDate)}</strong> · {e.days} day{e.days === 1 ? '' : 's'}{' '}
              <ToneBadge tone={TYPE_TONE[e.absenceType] ?? 'muted'}>{humanise(e.absenceType)}</ToneBadge>
              {e.reasonCategory && <span className="small"> · {humanise(e.reasonCategory)}</span>}
              <span className="small muted">
                {' '}· {e.certified ? `certificate ${humanise(e.certificateValidity ?? 'PENDING').toLowerCase()}` : 'self-certified'} · {e.sourceSystem}
              </span>
              {e.caseId && <> · <a href={`#/cases/${encodeURIComponent(e.caseId)}`}>Case</a></>}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
