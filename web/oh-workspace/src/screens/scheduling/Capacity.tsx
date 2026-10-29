import { useState, type FormEvent } from 'react';
import { schedulingApi, type CapacityRow } from '../../api/scheduling';
import { humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { addMonths, capacityGap, formatWait, monthBounds, pct, utilisationTone } from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { LocalTabs, SiteSelect } from './common';
import { useSites } from './useSchedulingData';

const thisMonth = () => new Date().toISOString().slice(0, 7);

/** `#/scheduling/capacity` – forecast demand vs capacity per site / month, utilisation, what-if (E14-F05). */
export function Capacity({ client, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites } = useSites(client, onSessionExpired);
  const [form, setForm] = useState(() => ({ from: thisMonth(), to: addMonths(thisMonth(), 5), siteCode: '' }));
  const [applied, setApplied] = useState(form);
  const range = { from: monthBounds(applied.from).from, to: monthBounds(applied.to).to };
  const cap = useAsync(() => api.capacity({ ...range, siteCode: applied.siteCode || undefined }), onSessionExpired, [applied]);
  const [groupBy, setGroupBy] = useState<'SITE' | 'CLINICIAN'>('SITE');
  const util = useAsync(() => api.utilisation({ ...range, groupBy }), onSessionExpired, [applied, groupBy]);

  return (
    <section>
      <PageTitle sub="Capacity comes from diary templates; demand from surveillance due lists, absence reviews and the 3-month run rate.">Capacity & utilisation</PageTitle>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          if (form.from && form.to && form.to >= form.from) setApplied(form);
        }}
      >
        <Field label="From month">{(id) => <input id={id} type="month" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} />}</Field>
        <Field label="To month">{(id) => <input id={id} type="month" value={form.to} min={form.from} onChange={(e) => setForm({ ...form, to: e.target.value })} />}</Field>
        <SiteSelect sites={sites.data} value={form.siteCode} onChange={(siteCode) => setForm({ ...form, siteCode })} allowAll />
        <button type="submit">Show</button>
      </form>

      <h2>Forecast vs capacity</h2>
      {cap.loading && <Loading />}
      {!!cap.error && <ErrorAlert error={cap.error} />}
      {cap.data && cap.data.length === 0 && <Empty>No capacity data for this period.</Empty>}
      {cap.data && cap.data.length > 0 && <CapacityTable rows={cap.data} caption="Forecast demand against diary capacity" />}

      <h2>Utilisation</h2>
      <LocalTabs label="Group utilisation by" tabs={[{ id: 'SITE', label: 'By site' }, { id: 'CLINICIAN', label: 'By clinician' }] as const} current={groupBy} onChange={setGroupBy} />
      {util.loading && <Loading />}
      {!!util.error && <ErrorAlert error={util.error} />}
      {util.data && util.data.length === 0 && <Empty>No utilisation data.</Empty>}
      {util.data && util.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Utilisation {groupBy === 'SITE' ? 'by site' : 'by clinician'}</caption>
            <thead>
              <tr>
                <th scope="col">{groupBy === 'SITE' ? 'Site' : 'Clinician'}</th>
                <th scope="col">Session hours</th>
                <th scope="col">Booked hours</th>
                <th scope="col">Utilisation</th>
                <th scope="col">No-show rate</th>
                <th scope="col">Average wait</th>
              </tr>
            </thead>
            <tbody>
              {util.data.map((u) => {
                const tone = utilisationTone(u.utilisationPct);
                return (
                  <tr key={u.key}>
                    <th scope="row">{u.label || u.key}</th>
                    <td className="num">{(u.sessionsMinutes / 60).toFixed(1)}</td>
                    <td className="num">{(u.bookedMinutes / 60).toFixed(1)}</td>
                    <td>
                      <span className="num">{pct(u.utilisationPct)}</span>
                      <span className="util-bar" aria-hidden="true">
                        <span className={`util-fill util-${tone}`} style={{ width: `${Math.min(100, Math.max(0, u.utilisationPct))}%` }} />
                      </span>
                    </td>
                    <td className="num">{pct(u.noShowRatePct)}</td>
                    <td className="num">{formatWait(u.averageWaitMinutes)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <WhatIf client={client} onSessionExpired={onSessionExpired} sites={sites.data ?? []} range={range} baseline={cap.data} />
    </section>
  );
}

function CapacityTable({ rows, caption, baseline }: { rows: CapacityRow[]; caption: string; baseline?: CapacityRow[] }) {
  const base = (r: CapacityRow) => baseline?.find((b) => b.siteCode === r.siteCode && b.month === r.month);
  const sorted = [...rows].sort((a, b) => a.siteCode.localeCompare(b.siteCode) || a.month.localeCompare(b.month));
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Site</th>
            <th scope="col">Month</th>
            <th scope="col">Capacity</th>
            <th scope="col">Booked</th>
            <th scope="col">Forecast demand</th>
            <th scope="col">Demand sources</th>
            <th scope="col">Gap</th>
            <th scope="col">Utilisation</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => {
            const g = capacityGap(r);
            const b = base(r);
            const bg = b ? capacityGap(b) : undefined;
            return (
              <tr key={`${r.siteCode}-${r.month}`}>
                <th scope="row">{r.siteName ?? r.siteCode}</th>
                <td>{r.month}</td>
                <td className="num">
                  {r.capacityAppointments}
                  {b && b.capacityAppointments !== r.capacityAppointments && <span className="small muted"> (was {b.capacityAppointments})</span>}
                </td>
                <td className="num">{r.bookedAppointments}</td>
                <td className="num">{r.forecastDemand}</td>
                <td className="small">
                  {Object.entries(r.demandBySource ?? {})
                    .map(([k, v]) => `${k === 'RUN_RATE' ? 'Run rate' : humanise(k)} ${v}`)
                    .join(' · ') || '–'}
                </td>
                <td className={`gap-${g.tone}`}>
                  <span aria-hidden="true">{g.symbol} </span>
                  {g.label}
                  {bg && bg.gap !== g.gap && <span className="small muted"> (was {bg.label.toLowerCase()})</span>}
                </td>
                <td className="num">{pct(r.utilisationPct)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function WhatIf({ client, onSessionExpired, sites, range, baseline }: { client: ScreenProps['client']; onSessionExpired: () => void; sites: { code: string; name: string }[]; range: { from: string; to: string }; baseline?: CapacityRow[] }) {
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({ siteCode: '', sessions: '2', campaignDays: '0' });
  const [result, setResult] = useState<CapacityRow[]>();
  const siteCode = f.siteCode || sites[0]?.code || '';
  const sessions = Number(f.sessions);
  const days = Number(f.campaignDays);
  const valid = !!siteCode && Number.isInteger(sessions) && sessions >= -50 && sessions <= 50 && Number.isInteger(days) && days >= 0 && days <= 100;

  async function run(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    await action.run(async () => {
      setResult(await schedulingApi(client).whatIf({ siteCode, extraClinicianSessionsPerWeek: sessions, extraCampaignDays: days, from: range.from, to: range.to }));
    });
  }

  return (
    <section aria-labelledby="whatif-h">
      <h2 id="whatif-h">What if…</h2>
      <form className="filters" onSubmit={run}>
        <SiteSelect sites={sites} value={siteCode} onChange={(s) => setF({ ...f, siteCode: s })} />
        <Field label="Extra clinician sessions / week" hint="Negative to model fewer sessions">
          {(id) => <input id={id} type="number" min={-50} max={50} value={f.sessions} onChange={(e) => setF({ ...f, sessions: e.target.value })} />}
        </Field>
        <Field label="Extra campaign days">{(id) => <input id={id} type="number" min={0} max={100} value={f.campaignDays} onChange={(e) => setF({ ...f, campaignDays: e.target.value })} />}</Field>
        <button type="submit" disabled={!valid || action.busy}>
          Recalculate
        </button>
      </form>
      <ActionFeedback error={action.error} />
      {result && result.length === 0 && <Empty>No rows returned.</Empty>}
      {result && result.length > 0 && <CapacityTable rows={result} baseline={baseline} caption={`What-if for ${siteCode}: ${sessions >= 0 ? '+' : ''}${sessions} sessions/week, +${days} campaign days`} />}
    </section>
  );
}
