import { useState, type FormEvent } from 'react';
import { buildQuery } from '../../api/links';
import { humanise, isoDay } from '../../components/format';
import { ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { cellCount, cellText, isSuppressed, readK } from '../../domain/suppression';
import type { ScreenProps } from '../types';

type Cell = number | null | { count?: number; suppressed?: boolean };
interface DashboardBody {
  bySource?: { source: string; count: Cell; suppressed?: boolean }[];
  byUnit?: { unit: string; open: Cell; overdue: Cell; suppressed?: boolean }[];
  slaCompliance?: { met: Cell; breached: Cell; pct: number | null };
  backlog?: { unassigned: Cell; overdue: Cell; awaitingTriage: Cell };
  trend?: { date: string; count: Cell; suppressed?: boolean }[];
  minGroupSize?: number;
}

function Num({ v, k, row }: { v: unknown; k: number; row?: boolean }) {
  const sup = isSuppressed(v, k, row);
  return <span className={sup ? 'suppressed' : 'num'} title={sup ? `Suppressed – fewer than ${k}` : undefined}>{cellText(v, k, row)}</span>;
}

function Trend({ points, k }: { points: NonNullable<DashboardBody['trend']>; k: number }) {
  if (points.length === 0) return <p className="muted">No data.</p>;
  const values = points.map((p) => (isSuppressed(p.count, k, p.suppressed) ? 0 : (cellCount(p.count) ?? 0)));
  const max = Math.max(1, ...values);
  const w = 12;
  const gap = 4;
  const h = 100;
  return (
    <figure className="trend">
      <svg viewBox={`0 0 ${points.length * (w + gap)} ${h + 4}`} role="img" aria-label="New cases per day" preserveAspectRatio="none">
        {points.map((p, i) => {
          const sup = isSuppressed(p.count, k, p.suppressed);
          const bh = sup ? 6 : Math.max(2, ((values[i] ?? 0) / max) * h);
          return (
            <rect key={p.date} x={i * (w + gap)} y={h - bh + 2} width={w} height={bh} className={sup ? 'bar-suppressed' : 'bar'}>
              <title>{`${p.date}: ${cellText(p.count, k, p.suppressed)}`}</title>
            </rect>
          );
        })}
      </svg>
      <figcaption className="small muted">
        {points[0]?.date} – {points[points.length - 1]?.date}; hatched/short bars are suppressed (&lt;{k}).
      </figcaption>
      <details>
        <summary className="small">Show data table</summary>
        <table>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">New cases</th>
            </tr>
          </thead>
          <tbody>
            {points.map((p) => (
              <tr key={p.date}>
                <td>{p.date}</td>
                <td><Num v={p.count} k={k} row={p.suppressed} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}

/** E01-BL-026 intake dashboard. Small cells are suppressed by the server and rendered as "<k". */
export function Dashboard({ client, onSessionExpired }: ScreenProps) {
  const [draft, setDraft] = useState(() => {
    const to = new Date();
    return { from: isoDay(new Date(to.getTime() - 30 * 86_400_000)), to: isoDay(to), subsidiary: '' };
  });
  const [filters, setFilters] = useState(draft);
  const dash = useAsync(() => client.api<DashboardBody>(`/intake/dashboard${buildQuery(filters)}`), onSessionExpired, [filters]);
  const d = dash.data;
  const k = readK(d);

  function apply(e: FormEvent) {
    e.preventDefault();
    setFilters({ ...draft });
  }

  return (
    <section>
      <PageTitle sub={`Intake volumes, backlog and SLA compliance. Cells below ${k} are suppressed.`}>Intake dashboard</PageTitle>
      <form className="filters" onSubmit={apply}>
        <Field label="From">{(id) => <input id={id} type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />}</Field>
        <Field label="Subsidiary">{(id) => <input id={id} size={6} value={draft.subsidiary} onChange={(e) => setDraft({ ...draft, subsidiary: e.target.value.toUpperCase() })} />}</Field>
        <div className="filter-actions">
          <button type="submit">Apply</button>
        </div>
      </form>
      {dash.loading && <Loading />}
      {!!dash.error && <ErrorAlert error={dash.error} />}
      {d && (
        <>
          <div className="tiles">
            <div className="tile">
              <h2>Unassigned</h2>
              <p className="tile-value"><Num v={d.backlog?.unassigned} k={k} /></p>
            </div>
            <div className="tile">
              <h2>Overdue</h2>
              <p className="tile-value"><Num v={d.backlog?.overdue} k={k} /></p>
            </div>
            <div className="tile">
              <h2>Awaiting triage</h2>
              <p className="tile-value"><Num v={d.backlog?.awaitingTriage} k={k} /></p>
            </div>
            <div className="tile">
              <h2>SLA compliance</h2>
              <p className="tile-value">{typeof d.slaCompliance?.pct === 'number' ? `${Math.round(d.slaCompliance.pct * 10) / 10} %` : '–'}</p>
              <p className="small">
                Met <Num v={d.slaCompliance?.met} k={k} /> · breached <Num v={d.slaCompliance?.breached} k={k} />
              </p>
              {typeof d.slaCompliance?.pct === 'number' && <p className="small">{d.slaCompliance.pct >= 95 ? '✓ Meets 95 % target' : '✕ Below 95 % target'}</p>}
            </div>
          </div>
          <div className="grid-2">
            <div className="tile">
              <h2>New cases by source</h2>
              <table>
                <tbody>
                  {(d.bySource ?? []).map((s) => (
                    <tr key={s.source}>
                      <th scope="row">{humanise(s.source)}</th>
                      <td><Num v={s.count} k={k} row={s.suppressed} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="tile">
              <h2>Open / overdue by unit</h2>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Unit</th>
                    <th scope="col">Open</th>
                    <th scope="col">Overdue</th>
                  </tr>
                </thead>
                <tbody>
                  {(d.byUnit ?? []).map((u) => (
                    <tr key={u.unit}>
                      <th scope="row">{u.unit}</th>
                      <td><Num v={u.open} k={k} row={u.suppressed} /></td>
                      <td><Num v={u.overdue} k={k} row={u.suppressed} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <div className="tile">
            <h2>Trend – new cases per day</h2>
            <Trend points={d.trend ?? []} k={k} />
          </div>
        </>
      )}
    </section>
  );
}
