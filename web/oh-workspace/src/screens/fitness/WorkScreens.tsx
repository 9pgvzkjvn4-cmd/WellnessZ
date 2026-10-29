import { useState, type FormEvent } from 'react';
import { fitnessApi, RESTRICTION_STATUSES, type FitnessDecision, type RestrictionInstance } from '../../api/fitness';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { cellText, readK } from '../../domain/suppression';
import type { ScreenProps } from '../types';
import { maySignoff } from '../../domain/fitness';
import { SignoffDialog } from './DecisionParts';

// ---------------------------------------------------------------- Fitness sign-off queue (AME / CMO)
/** `#/fitness/signoff` – decisions awaiting AME / CMO sign-off (licensed standards, permanently unfit). */
export function SignoffQueue({ client, session, onSessionExpired }: ScreenProps) {
  const api = fitnessApi(client);
  const list = useAsync(() => api.awaitingSignoff(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [signing, setSigning] = useState<FitnessDecision>();

  async function submit(d: FitnessDecision, approve: boolean, note: string) {
    const ok = await action.run(async () => {
      await api.signoff(d.id, approve, note);
      return `${d.caseRef ?? 'Decision'} ${approve ? 'signed off' : 'rejected'}.`;
    });
    if (ok) {
      setSigning(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Licensed-role decisions need AME sign-off; permanently unfit needs the CMO.">Fitness sign-off</PageTitle>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nothing awaiting sign-off.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} decision{rows.length === 1 ? '' : 's'} awaiting sign-off</caption>
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Employee</th>
                <th scope="col">Standard</th>
                <th scope="col">Outcome</th>
                <th scope="col">Decided</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.id}>
                  <th scope="row"><a href={`#/cases/${encodeURIComponent(d.caseId)}/fitness`}>{d.caseRef ?? d.caseId}</a></th>
                  <td>{d.employeeName ?? d.employeeId}</td>
                  <td>{d.standardTitle ?? humanise(d.standardCode)}</td>
                  <td>{humanise(d.outcome)}{d.limitationCodes.length > 0 && <div className="small">{d.limitationCodes.join(', ')}</div>}</td>
                  <td className="small">{d.decidedBy} {formatDate(d.decidedAt)}</td>
                  <td>
                    {maySignoff(d, session.roles) ? (
                      <button type="button" className="small-button" onClick={() => setSigning(d)} aria-label={`Sign off ${d.caseRef ?? d.id}`}>
                        Sign off
                      </button>
                    ) : (
                      <span className="small muted">CMO only</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {signing && <SignoffDialog decision={signing} busy={action.busy} error={action.error} onClose={() => setSigning(undefined)} onSubmit={(a, n) => void submit(signing, a, n)} />}
    </section>
  );
}

// ---------------------------------------------------------------- Active restrictions (+ dashboard)
function RestrictionsDashboard({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const dash = useAsync(() => fitnessApi(client).restrictionsDashboard(), onSessionExpired);
  return (
    <section className="tile" aria-labelledby="rd-title">
      <h2 id="rd-title">Restrictions by unit</h2>
      {dash.loading && <Loading />}
      {!!dash.error && <ErrorAlert error={dash.error} />}
      {dash.data && dash.data.byUnit.length === 0 && <Empty>No active restrictions.</Empty>}
      {(dash.data?.byUnit ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Unit</th>
                <th scope="col" className="num">Active</th>
                <th scope="col" className="num">Expiring (≤ 5 days)</th>
                <th scope="col" className="num">Lapsed</th>
              </tr>
            </thead>
            <tbody>
              {(dash.data?.byUnit ?? []).map((u) => (
                <tr key={u.unit}>
                  <th scope="row">{u.unit}</th>
                  <td className="num">{u.active}</td>
                  <td className="num">{u.expiring}</td>
                  <td className="num">{u.lapsed > 0 ? <strong className="text-red">{u.lapsed}</strong> : u.lapsed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** `#/restrictions` – active / expiring / lapsed restrictions with one-click extend or close (E05-BL-016). */
export function ActiveRestrictions({ client, session, onSessionExpired }: ScreenProps) {
  const api = fitnessApi(client);
  const [status, setStatus] = useState('');
  const [unit, setUnit] = useState('');
  const [applied, setApplied] = useState({ status: '', unit: '' });
  const list = useAsync(() => api.activeRestrictions(applied), onSessionExpired, [applied]);
  const action = useAction(onSessionExpired);
  const [dialog, setDialog] = useState<{ r: RestrictionInstance; mode: 'extend' | 'close' }>();
  const [form, setForm] = useState({ validUntil: '', reason: '' });

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!dialog || !form.reason.trim() || (dialog.mode === 'extend' && !form.validUntil)) return;
    const { r, mode } = dialog;
    const ok = await action.run(async () => {
      if (mode === 'extend') await api.extendRestriction(r.id, form.validUntil, form.reason.trim());
      else await api.closeRestriction(r.id, form.reason.trim());
      return mode === 'extend' ? `“${r.label}” extended to ${formatDay(form.validUntil)}.` : `“${r.label}” closed.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Alerts go to the case owner, the manager and the employee five days before expiry; lapsed restrictions need a decision.">Active restrictions</PageTitle>
      {can(session.roles, 'restrictionsDashboard') && <RestrictionsDashboard client={client} onSessionExpired={onSessionExpired} />}
      <form
        className="filters inline-form"
        onSubmit={(e) => {
          e.preventDefault();
          setApplied({ status, unit: unit.trim() });
        }}
      >
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={RESTRICTION_STATUSES.filter((s) => s !== 'CLOSED')} empty="All open" />
            </select>
          )}
        </Field>
        <Field label="Unit">{(id) => <input id={id} maxLength={20} value={unit} onChange={(e) => setUnit(e.target.value)} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      <div className="mode-switch" role="group" aria-label="Quick filters">
        {['EXPIRING', 'LAPSED'].map((s) => (
          <button key={s} type="button" className={`secondary small-button${applied.status === s ? ' mode-active' : ''}`} aria-pressed={applied.status === s} onClick={() => { setStatus(s); setApplied({ status: s, unit: unit.trim() }); }}>
            {humanise(s)}
          </button>
        ))}
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No restrictions match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} restriction{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Restriction</th>
                <th scope="col">Case</th>
                <th scope="col">Valid</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={r.status === 'LAPSED' ? 'row-breached' : undefined}>
                  <td>{r.employeeName ?? r.employeeId}</td>
                  <th scope="row">{r.label}</th>
                  <td>{r.caseId ? <a href={`#/cases/${encodeURIComponent(r.caseId)}/restrictions`}>{r.caseRef ?? 'Case'}</a> : '–'}</td>
                  <td className="small">{formatDay(r.validFrom)} – {formatDay(r.validUntil)}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td>
                    {r.status !== 'CLOSED' && (
                      <div className="card-actions">
                        <button type="button" className="secondary small-button" onClick={() => { setForm({ validUntil: '', reason: '' }); setDialog({ r, mode: 'extend' }); }} aria-label={`Extend ${r.label} for ${r.employeeName ?? r.employeeId}`}>
                          Extend
                        </button>
                        <button type="button" className="secondary small-button" onClick={() => { setForm({ validUntil: '', reason: '' }); setDialog({ r, mode: 'close' }); }} aria-label={`Close ${r.label} for ${r.employeeName ?? r.employeeId}`}>
                          Close
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <Dialog title={`${dialog.mode === 'extend' ? 'Extend' : 'Close'} restriction`} onClose={() => setDialog(undefined)}>
          <p>
            {dialog.r.employeeName ?? dialog.r.employeeId}: <strong>{dialog.r.label}</strong> (until {formatDay(dialog.r.validUntil)})
          </p>
          <form className="form" onSubmit={submit}>
            {dialog.mode === 'extend' && (
              <Field label="New end date" required>
                {(id) => <input id={id} type="date" min={isoDay(new Date())} required value={form.validUntil} onChange={(e) => setForm({ ...form, validUntil: e.target.value })} />}
              </Field>
            )}
            <Field label="Reason" required>
              {(id) => <textarea id={id} rows={2} required maxLength={500} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || !form.reason.trim() || (dialog.mode === 'extend' && !form.validUntil)}>
                {dialog.mode === 'extend' ? 'Extend restriction' : 'Close restriction'}
              </button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- RTW overdue reviews
/** `#/rtw/overdue` – overdue RTW plan reviews (escalated to the Nursing Head after 2 days). */
export function RtwOverdue({ client, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => fitnessApi(client).overdueReviews(), onSessionExpired);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Reviews more than two days overdue are escalated to the Nursing Head.">RTW overdue reviews</PageTitle>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No overdue reviews.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Employee</th>
                <th scope="col">Review date</th>
                <th scope="col" className="num">Days overdue</th>
                <th scope="col">Owner</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.planId}-${r.reviewDate}`} className={r.daysOverdue > 2 ? 'row-breached' : undefined}>
                  <th scope="row">{r.caseId ? <a href={`#/cases/${encodeURIComponent(r.caseId)}/rtw`}>{r.caseRef}</a> : r.caseRef}</th>
                  <td>{r.employeeName}</td>
                  <td>{formatDay(r.reviewDate)}</td>
                  <td className="num">
                    {r.daysOverdue}
                    {r.daysOverdue > 2 && <span className="badge badge-overdue"><span aria-hidden="true">■ </span>Escalated</span>}
                  </td>
                  <td>{r.owner ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Crew dashboard (CMO)
/** `#/crew/dashboard` – crew unfit / limited counts by base and fleet, trend. No identifiers; cells < 5 shown as "<5". */
export function CrewDashboard({ client, onSessionExpired }: ScreenProps) {
  const [range, setRange] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState(range);
  const dash = useAsync(() => fitnessApi(client).crewDashboard(applied.from || undefined, applied.to || undefined), onSessionExpired, [applied]);
  const d = dash.data;
  const k = readK(d);
  const days = (v: unknown) => (typeof v === 'number' ? v.toFixed(1) : '–');

  const baseTable = (title: string, rows: { base: string; count: unknown; avgDays?: unknown }[]) => (
    <div className="table-wrap">
      <table>
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Base</th>
            <th scope="col" className="num">Crew</th>
            <th scope="col" className="num">Average days</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const t = cellText(r.count, k);
            return (
              <tr key={r.base}>
                <th scope="row">{r.base}</th>
                <td className={`num${t.startsWith('<') ? ' suppressed' : ''}`}>{t}</td>
                <td className="num">{t.startsWith('<') ? '–' : days(r.avgDays)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );

  return (
    <section>
      <PageTitle sub={`Operational impact of crew fitness. No names or IDs; groups smaller than ${k} are shown as “<${k}”.`}>Crew dashboard</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {dash.loading && <Loading />}
      {!!dash.error && <ErrorAlert error={dash.error} />}
      {d && (
        <>
          <div className="grid-2">
            {baseTable('Unfit by base', d.unfitByBase)}
            {baseTable('Limited by base', d.limitedByBase)}
          </div>
          <div className="table-wrap">
            <table>
              <caption>By fleet</caption>
              <thead>
                <tr>
                  <th scope="col">Fleet</th>
                  <th scope="col" className="num">Unfit</th>
                  <th scope="col" className="num">Limited</th>
                </tr>
              </thead>
              <tbody>
                {d.byFleet.map((r) => (
                  <tr key={r.fleet}>
                    <th scope="row">{r.fleet}</th>
                    <td className="num">{cellText(r.unfit ?? r.count, k)}</td>
                    <td className="num">{cellText(r.limited, k)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="table-wrap">
            <table>
              <caption>Weekly trend</caption>
              <thead>
                <tr>
                  <th scope="col">Week</th>
                  <th scope="col" className="num">Unfit</th>
                  <th scope="col" className="num">Limited</th>
                </tr>
              </thead>
              <tbody>
                {d.trend.map((t) => (
                  <tr key={t.week}>
                    <th scope="row">{t.week}</th>
                    <td className="num">{cellText(t.unfit, k)}</td>
                    <td className="num">{cellText(t.limited, k)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Roster feed reconciliation
/** `#/crew/roster-feed` – rostering status feed: sent vs acknowledged, unacknowledged > 15 min, p95 latency. */
export function RosterReconciliation({ client, onSessionExpired }: ScreenProps) {
  const r = useAsync(() => fitnessApi(client).rosterReconciliation(), onSessionExpired);
  const feed = useAsync(() => fitnessApi(client).rosterFeed(), onSessionExpired);
  const d = r.data;
  return (
    <section>
      <PageTitle sub="Fitness status (fit / unfit / limited / pending + dates) must reach rostering within 15 minutes.">Roster-feed reconciliation</PageTitle>
      <button type="button" className="secondary" onClick={() => { r.reload(); feed.reload(); }} disabled={r.loading}>
        Refresh
      </button>
      {r.loading && <Loading />}
      {!!r.error && <ErrorAlert error={r.error} />}
      {d && (
        <>
          <div className="tiles">
            <div className="tile"><span className="tile-value">{d.sent}</span> sent</div>
            <div className="tile"><span className="tile-value">{d.acknowledged}</span> acknowledged</div>
            <div className="tile">
              <span className={`tile-value${d.unacknowledgedOver15Min.length > 0 ? ' text-red' : ''}`}>{d.unacknowledgedOver15Min.length}</span> unacknowledged &gt; 15 min
            </div>
            <div className="tile">
              <span className={`tile-value${d.p95LatencySeconds != null && d.p95LatencySeconds > 900 ? ' text-red' : ''}`}>{d.p95LatencySeconds != null ? `${Math.round(d.p95LatencySeconds)} s` : '–'}</span> p95 latency
            </div>
          </div>
          {d.unacknowledgedOver15Min.length === 0 ? (
            <Empty>All messages acknowledged within 15 minutes.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <caption>Unacknowledged after 15 minutes</caption>
                <thead>
                  <tr>
                    <th scope="col">Message</th>
                    <th scope="col">Employee</th>
                    <th scope="col">Roster status</th>
                    <th scope="col">Sent</th>
                  </tr>
                </thead>
                <tbody>
                  {d.unacknowledgedOver15Min.map((m) => (
                    <tr key={m.messageId}>
                      <th scope="row" className="mono">{m.messageId}</th>
                      <td>{m.employeeId ?? '–'}</td>
                      <td>{m.rosterStatus ? <StatusBadge status={m.rosterStatus} /> : '–'}</td>
                      <td>{formatDate(m.sentAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      {!!feed.error && <ErrorAlert error={feed.error} />}
      {(feed.data ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Recent status messages</caption>
            <thead>
              <tr>
                <th scope="col">Message</th>
                <th scope="col">Employee</th>
                <th scope="col">Roster status</th>
                <th scope="col">Valid</th>
                <th scope="col">Operational limitations</th>
                <th scope="col">Sent</th>
                <th scope="col">Acknowledged</th>
              </tr>
            </thead>
            <tbody>
              {(feed.data ?? []).map((m) => (
                <tr key={m.messageId}>
                  <th scope="row" className="mono">{m.messageId}</th>
                  <td>{m.employeeId}</td>
                  <td><StatusBadge status={m.rosterStatus} /></td>
                  <td className="small">{formatDay(m.validFrom)} – {formatDay(m.validUntil)}</td>
                  <td>{Array.isArray(m.operationalLimitations) ? m.operationalLimitations.join(', ') : (m.operationalLimitations ?? '–')}</td>
                  <td>{formatDate(m.sentAt)}</td>
                  <td>{m.ackAt ? formatDate(m.ackAt) : <span className="badge badge-warning">Not acknowledged</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
