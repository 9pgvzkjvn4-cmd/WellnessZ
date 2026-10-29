import { useState, type FormEvent } from 'react';
import { absenceApi, absenceExportUrl, ANALYTICS_GROUP_BY, type FeedMismatch, type UnitRow } from '../../api/absence';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise } from '../../components/format';
import { KpiTile, LineTrend, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { formatPct, kpiStatus, previousMonth, todayIso } from '../../domain/absence';
import type { ScreenProps } from '../types';

const num = (v: number | undefined, d = 1) => (typeof v === 'number' && Number.isFinite(v) ? v.toFixed(d) : '–');

function UnitsTable({ rows, groupBy }: { rows: UnitRow[]; groupBy: string }) {
  const categories = [...new Set(rows.flatMap((r) => (r.suppressed || !r.categories ? [] : Object.keys(r.categories))))].sort();
  return (
    <div className="table-wrap">
      <table>
        <caption>Absence by {humanise(groupBy).toLowerCase()}</caption>
        <thead>
          <tr>
            <th scope="col">Unit</th>
            <th scope="col" className="num">Headcount</th>
            <th scope="col" className="num">Episodes</th>
            <th scope="col" className="num">Days lost</th>
            <th scope="col" className="num">Absence rate</th>
            <th scope="col" className="num">Frequency rate</th>
            <th scope="col" className="num">Avg duration (days)</th>
            <th scope="col" className="num">Bradford mean</th>
            {categories.map((c) => (
              <th key={c} scope="col" className="num">{humanise(c)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) =>
            r.suppressed ? (
              <tr key={r.key} className="row-suppressed">
                <th scope="row">{r.label}</th>
                <td colSpan={7 + categories.length} className="suppressed">Suppressed – group too small to report</td>
              </tr>
            ) : (
              <tr key={r.key}>
                <th scope="row">{r.label}</th>
                <td className="num">{r.headcount ?? '–'}</td>
                <td className="num">{r.episodes ?? '–'}</td>
                <td className="num">{r.daysLost ?? '–'}</td>
                <td className="num">{formatPct(r.absenceRatePct)}</td>
                <td className="num">{num(r.frequencyRate, 2)}</td>
                <td className="num">{num(r.avgDurationDays)}</td>
                <td className="num">{num(r.bradfordMean, 0)}</td>
                {categories.map((c) => (
                  <td key={c} className="num">{r.categories?.[c] ?? 0}</td>
                ))}
              </tr>
            ),
          )}
        </tbody>
      </table>
    </div>
  );
}

const yearAgo = () => {
  const d = new Date();
  d.setFullYear(d.getFullYear() - 1);
  return todayIso(d);
};

/** `#/absence/analytics` – unit absence rates (small groups suppressed), KPI tiles against targets, trend, monthly CSV pack (E06-F06). */
export function AbsenceAnalytics({ client, session, onSessionExpired }: ScreenProps) {
  const api = absenceApi(client);
  const [form, setForm] = useState({ groupBy: 'SUBSIDIARY', from: yearAgo(), to: todayIso() });
  const [applied, setApplied] = useState(form);
  const units = useAsync(() => api.units(applied), onSessionExpired, [applied]);
  const kpis = useAsync(() => api.kpis({ from: applied.from, to: applied.to }), onSessionExpired, [applied.from, applied.to]);
  const [month, setMonth] = useState(previousMonth());
  const k = kpis.data;
  const months = (k?.trend ?? []).map((t) => t.month);

  return (
    <section>
      <PageTitle sub="Unit-level only – no drill-down to individuals. Groups below the minimum size are suppressed.">Absence analytics</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(form); }}>
        <Field label="Group by">
          {(id) => (
            <select id={id} value={form.groupBy} onChange={(e) => setForm({ ...form, groupBy: e.target.value })}>
              <SelectOptions values={ANALYTICS_GROUP_BY} />
            </select>
          )}
        </Field>
        <Field label="From">{(id) => <input id={id} type="date" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>

      {kpis.loading && !k && <Loading label="Loading KPIs…" />}
      {!!kpis.error && <ErrorAlert error={kpis.error} />}
      {k && (
        <>
          <div className="tiles" role="group" aria-label="Key performance indicators">
            <KpiTile label="Absence rate" value={formatPct(k.absenceRatePct)} />
            <KpiTile label="LTA cases with an OH case" value={formatPct(k.ltaWithCasePct)} target="Target ≥ 95 %" status={kpiStatus(k.ltaWithCasePct, 95, 'atLeast')} />
            <KpiTile label="Median days trigger → contact" value={num(k.medianDaysTriggerToContact)} target="Target ≤ 5 days" status={kpiStatus(k.medianDaysTriggerToContact, 5, 'atMost')} />
            <KpiTile label="Certificates verified" value={formatPct(k.certificatesVerifiedPct)} />
            <KpiTile label="Open long-term absences" value={k.openLta} />
            <KpiTile label="Overdue milestones" value={k.overdueMilestones} status={k.overdueMilestones > 0 ? 'MISSED' : undefined} />
          </div>
          <div className="grid-2">
            <div>
              <h2>Absence rate trend</h2>
              <LineTrend labels={months} label="Absence rate per month" series={[{ name: 'Absence rate %', values: k.trend.map((t) => t.absenceRatePct), format: (v) => `${v.toFixed(1)} %` }]} />
            </div>
            <div>
              <h2>Trigger → contact (median days)</h2>
              <LineTrend labels={months} label="Median days from trigger to first contact per month, target 5" target={5} series={[{ name: 'Median days', values: k.trend.map((t) => t.medianDaysTriggerToContact), format: (v) => v.toFixed(1) }]} />
            </div>
            <div>
              <h2>LTA with OH case</h2>
              <LineTrend labels={months} label="Long-term absences with an OH case per month, target 95 percent" target={95} max={100} series={[{ name: 'LTA with case %', values: k.trend.map((t) => t.ltaWithCasePct), format: (v) => `${v.toFixed(1)} %` }]} />
            </div>
          </div>
        </>
      )}

      {units.loading && <Loading label="Loading units…" />}
      {!!units.error && <ErrorAlert error={units.error} />}
      {units.data && units.data.length === 0 && <Empty>No absence data for this period.</Empty>}
      {units.data && units.data.length > 0 && <UnitsTable rows={units.data} groupBy={applied.groupBy} />}

      {can(session.roles, 'absenceAnalyticsExport') && (
        <section className="tile" aria-labelledby="abs-export">
          <h2 id="abs-export">Monthly pack (CSV)</h2>
          <div className="inline-form">
            <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
            {/^\d{4}-\d{2}$/.test(month) && (
              <a className="button secondary" href={absenceExportUrl(month)} download={`absence-${month}.csv`} rel="noopener">
                Download CSV
              </a>
            )}
          </div>
          <p className="small muted">Suppression is applied to the export as well.</p>
        </section>
      )}
    </section>
  );
}

/** `#/absence/feed` – HRIS / T&A / crew feed runs: match rate, errors, unmatched episodes (E06-F01). */
export function AbsenceFeed({ client, session, onSessionExpired }: ScreenProps) {
  const api = absenceApi(client);
  const runs = useAsync(() => api.feedRuns(30), onSessionExpired);
  const canResolve = can(session.roles, 'absenceMismatches');
  const mismatches = useAsync(() => (canResolve ? api.mismatches('OPEN') : Promise.resolve<FeedMismatch[]>([])), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [resolving, setResolving] = useState<{ m: FeedMismatch; ignore: boolean }>();
  const [value, setValue] = useState('');

  async function runNow() {
    const ok = await action.run(async () => {
      const r = await api.runFeed();
      return `Feed run started at ${formatDate(r.startedAt)}: ${r.received} received, ${r.matched} matched.`;
    });
    if (ok) {
      runs.reload();
      mismatches.reload();
    }
  }

  async function resolve(e: FormEvent) {
    e.preventDefault();
    if (!resolving || !value.trim()) return;
    const { m, ignore } = resolving;
    const ok = await action.run(async () => {
      await api.resolveMismatch(m.id, ignore ? { ignore: true, reason: value.trim() } : { employeeId: value.trim().toUpperCase() });
      return ignore ? `${m.sourceRef} ignored.` : `${m.sourceRef} matched to ${value.trim().toUpperCase()} and re-ingested.`;
    });
    if (ok) {
      setResolving(undefined);
      mismatches.reload();
      runs.reload();
    }
  }

  const list = runs.data ?? [];
  const latest = list[0];
  return (
    <section>
      <PageTitle sub="Daily pull from HRIS, time & attendance and crew systems. Unmatched rows wait here until they are matched to an employee or ignored.">Absence feed</PageTitle>
      {can(session.roles, 'absenceFeedRun') && (
        <button type="button" disabled={action.busy} onClick={() => void runNow()}>Run feed now</button>
      )}
      <ActionFeedback error={resolving ? undefined : action.error} message={action.message} />
      {latest && (
        <div className="tiles">
          <KpiTile label={`Match rate – last run (${latest.sourceSystem})`} value={formatPct(latest.matchRatePct)} />
          <KpiTile label="Received" value={latest.received} />
          <KpiTile label="Unmatched" value={latest.unmatched} status={latest.unmatched > 0 ? 'MISSED' : undefined} />
        </div>
      )}
      {runs.loading && <Loading />}
      {!!runs.error && <ErrorAlert error={runs.error} />}
      {runs.data && list.length === 0 && <Empty>No feed runs yet.</Empty>}
      {list.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Feed runs</caption>
            <thead>
              <tr>
                <th scope="col">Started</th>
                <th scope="col">Source</th>
                <th scope="col" className="num">Received</th>
                <th scope="col" className="num">Matched</th>
                <th scope="col" className="num">Unmatched</th>
                <th scope="col" className="num">Match rate</th>
                <th scope="col" className="num">Errors</th>
              </tr>
            </thead>
            <tbody>
              {list.map((r) => {
                const errors = Array.isArray(r.errors) ? r.errors.length : r.errors;
                return (
                  <tr key={r.id}>
                    <th scope="row">{formatDate(r.startedAt)}</th>
                    <td>{r.sourceSystem}</td>
                    <td className="num">{r.received}</td>
                    <td className="num">{r.matched}</td>
                    <td className="num">{r.unmatched}</td>
                    <td className="num">{formatPct(r.matchRatePct)}</td>
                    <td className="num">{errors > 0 ? <ToneBadge tone="red">{errors}</ToneBadge> : 0}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {canResolve && (
        <>
          <h2>Unmatched episodes</h2>
          {!!mismatches.error && <ErrorAlert error={mismatches.error} />}
          {mismatches.data && mismatches.data.length === 0 && <Empty>No open mismatches.</Empty>}
          {(mismatches.data ?? []).length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Open mismatches</caption>
                <thead>
                  <tr>
                    <th scope="col">Source ref</th>
                    <th scope="col">Source</th>
                    <th scope="col">Employee ref</th>
                    <th scope="col">Period</th>
                    <th scope="col">Reason</th>
                    <th scope="col"><span className="visually-hidden">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {(mismatches.data ?? []).map((m) => (
                    <tr key={m.id}>
                      <th scope="row" className="mono">{m.sourceRef}</th>
                      <td>{m.sourceSystem}</td>
                      <td className="mono">{m.employeeRef}</td>
                      <td className="small">{formatDay(m.startDate)} – {formatDay(m.endDate)}</td>
                      <td className="small">{humanise(m.reason)}</td>
                      <td>
                        <div className="card-actions">
                          <button type="button" className="small-button" onClick={() => { action.reset(); setValue(''); setResolving({ m, ignore: false }); }} aria-label={`Match ${m.sourceRef}`}>Match</button>
                          <button type="button" className="secondary small-button" onClick={() => { action.reset(); setValue(''); setResolving({ m, ignore: true }); }} aria-label={`Ignore ${m.sourceRef}`}>Ignore</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      {resolving && (
        <Dialog title={resolving.ignore ? 'Ignore mismatch' : 'Match to employee'} onClose={() => setResolving(undefined)}>
          <p>
            {resolving.m.sourceSystem} <span className="mono">{resolving.m.sourceRef}</span> – employee ref <span className="mono">{resolving.m.employeeRef}</span>
          </p>
          <form className="form" onSubmit={resolve}>
            <Field label={resolving.ignore ? 'Reason' : 'Employee ID'} required>
              {(id) => <input id={id} required maxLength={resolving.ignore ? 500 : 20} value={value} onChange={(e) => setValue(e.target.value)} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || !value.trim()}>{resolving.ignore ? 'Ignore' : 'Match and re-ingest'}</button>
              <button type="button" className="secondary" onClick={() => setResolving(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
