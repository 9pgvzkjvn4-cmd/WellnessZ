import { useState, type FormEvent } from 'react';
import { treatmentApi, VARIANCE_ALERT_STATUSES, type VarianceAlert } from '../../api/treatment';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import { formatMoney } from '../../domain/money';
import { addDays } from '../../domain/treatment';
import type { ScreenProps } from '../types';
import { caseLink, num, pct } from './util';

/** `#/treatment/variance` – recovery-variance alerts raised by the nightly job (acknowledge / escalate to Nursing Head & CMO). */
export function VarianceAlerts({ client, onSessionExpired }: ScreenProps) {
  const api = treatmentApi(client);
  const [status, setStatus] = useState<string>('OPEN');
  const list = useAsync(() => api.varianceAlerts(status), onSessionExpired, [status]);
  const action = useAction(onSessionExpired);
  const [dialog, setDialog] = useState<{ alert: VarianceAlert; mode: 'acknowledge' | 'escalate' }>();
  const [note, setNote] = useState('');
  const rows = list.data ?? [];

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!dialog) return;
    const { alert, mode } = dialog;
    const ok = await action.run(async () => {
      if (mode === 'acknowledge') await api.acknowledgeVariance(alert.id, note.trim() || undefined);
      else await api.escalateVariance(alert.id, note.trim() || undefined);
      return mode === 'acknowledge' ? `${alert.caseRef} acknowledged.` : `${alert.caseRef} escalated to the Nursing Head and CMO.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="Cases whose recovery is running longer than the treatment guideline plus the plan's threshold (default 20 %).">Recovery variance alerts</PageTitle>
      <div className="mode-switch" role="group" aria-label="Alert status">
        {VARIANCE_ALERT_STATUSES.map((s) => (
          <button key={s} type="button" className={`secondary small-button${status === s ? ' mode-active' : ''}`} aria-pressed={status === s} onClick={() => setStatus(s)}>
            {humanise(s)}
          </button>
        ))}
      </div>
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No {humanise(status).toLowerCase()} alerts.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} alert{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr><th scope="col">Employee</th><th scope="col">Case</th><th scope="col" className="num">Expected days</th><th scope="col" className="num">Actual days</th><th scope="col">Over guideline</th><th scope="col">Raised</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <th scope="row">{a.displayName} <span className="small muted">{a.employeeId}</span></th>
                  <td><a href={caseLink(a.caseId, 'treatment')}>{a.caseRef}</a></td>
                  <td className="num">{a.expectedDays}</td>
                  <td className="num">{a.actualDays}</td>
                  <td><ToneBadge tone={a.status === 'ESCALATED' ? 'red' : 'amber'}>+{a.pctOver.toFixed(0)} %</ToneBadge></td>
                  <td className="small">{formatDate(a.raisedAt)}</td>
                  <td>
                    {a.status === 'OPEN' && (
                      <div className="card-actions">
                        <button type="button" className="secondary small-button" aria-label={`Acknowledge ${a.caseRef}`} onClick={() => { setNote(''); action.reset(); setDialog({ alert: a, mode: 'acknowledge' }); }}>Acknowledge</button>
                        <button type="button" className="secondary small-button" aria-label={`Escalate ${a.caseRef}`} onClick={() => { setNote(''); action.reset(); setDialog({ alert: a, mode: 'escalate' }); }}>Escalate</button>
                      </div>
                    )}
                    {a.status === 'ACKNOWLEDGED' && (
                      <button type="button" className="secondary small-button" aria-label={`Escalate ${a.caseRef}`} onClick={() => { setNote(''); action.reset(); setDialog({ alert: a, mode: 'escalate' }); }}>Escalate</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <Dialog title={dialog.mode === 'acknowledge' ? `Acknowledge ${dialog.alert.caseRef}` : `Escalate ${dialog.alert.caseRef}`} onClose={() => setDialog(undefined)}>
          <form className="form" onSubmit={submit}>
            <Field label="Note" hint={dialog.mode === 'escalate' ? 'The Nursing Head and CMO are notified.' : 'Optional – e.g. the plan was reviewed.'}>
              {(id) => <textarea id={id} rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy}>{dialog.mode === 'acknowledge' ? 'Acknowledge' : 'Escalate'}</button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

/** `#/treatment/kpis` – referral timeliness, report turnaround, missed appointments, medical cost per case (CMO, Nursing Head). */
export function TreatmentKpis({ client, onSessionExpired }: ScreenProps) {
  const api = treatmentApi(client);
  const [range, setRange] = useState({ from: addDays(todayIso(), -90), to: todayIso() });
  const [applied, setApplied] = useState(range);
  const kpis = useAsync(() => api.kpis(applied), onSessionExpired, [applied]);
  const k = kpis.data;
  return (
    <section>
      <PageTitle sub="Aggregated across all external referrals in the period – no individual data.">Treatment coordination KPIs</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {kpis.loading && !k && <Loading />}
      {!!kpis.error && <ErrorAlert error={kpis.error} />}
      {k && (
        <div className="tiles" role="group" aria-label="Key performance indicators">
          <KpiTile label="Median working days referral → appointment" value={num(k.medianWorkingDaysReferralToAppointment)} />
          <KpiTile label="Urgent referrals seen within 48 h" value={pct(k.urgentWithin48hPct, 1)} />
          <KpiTile label="Reports within 7 days" value={pct(k.reportWithin7DaysPct, 1)} />
          <KpiTile label="Missed appointments" value={pct(k.missedAppointmentPct, 1)} />
          <KpiTile label="Average medical cost per case" value={formatMoney(k.avgMedicalCostPerCase)} />
        </div>
      )}
    </section>
  );
}
