import { useState, type FormEvent } from 'react';
import { PATHWAYS, travelApi, type Chemical, type Exposure, type ExposureTimer } from '../../api/travel';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { FieldError } from '../../domain/devices';
import { clock, nextOpenTimer, overdueTimerCount, safeHttpUrl, serologyView, sortTimers, timerView } from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import { navigate } from '../../routing/useHashRoute';
import type { ScreenProps } from '../types';
import { useNow, yesNo } from './hooks';

const exposureLink = (id: string) => `#/exposures/${encodeURIComponent(id)}`;
const hazardsText = (h: Chemical['hazards']) => (Array.isArray(h) ? h.join(', ') : h);

function SdsLink({ chemical }: { chemical: Chemical }) {
  const url = safeHttpUrl(chemical.sdsUrl);
  return url ? (
    <a href={url} target="_blank" rel="noopener noreferrer">Safety data sheet: {chemical.name}{chemical.unNumber ? ` (UN ${chemical.unNumber})` : ''}</a>
  ) : (
    <span className="muted">No valid safety data sheet link for {chemical.name}</span>
  );
}

function NewExposureForm({ client, onSessionExpired, chemicals }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { chemicals: Chemical[] }) {
  const [employee, setEmployee] = useState<EmployeeRef>();
  const [f, setF] = useState({ pathway: '', occurredAt: toLocalInput(), details: '', chemicalCode: '', flightRef: '', sourceKnown: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const chemical = chemicals.find((c) => c.code === f.chemicalCode);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!employee) errs.push({ field: 'ex-employee', message: 'Choose the employee.' });
    if (!f.pathway) errs.push({ field: 'ex-pathway', message: 'Choose the pathway.' });
    const at = localToIso(f.occurredAt);
    if (!at) errs.push({ field: 'ex-occurred', message: 'Enter when it happened.' });
    else if (Date.parse(at) > Date.now() + 5 * 60_000) errs.push({ field: 'ex-occurred', message: 'The time cannot be in the future.' });
    if (f.pathway === 'CHEMICAL_DGR' && !f.chemicalCode) errs.push({ field: 'ex-chemical', message: 'Choose the chemical from the register.' });
    if (f.flightRef && !/^[A-Z0-9]{2,8}$/.test(f.flightRef.trim().toUpperCase())) errs.push({ field: 'ex-flight', message: 'Flight reference: 2–8 letters or digits, e.g. WZ101.' });
    setErrors(errs);
    if (errs.length || !employee || !at) return;
    let created: Exposure | undefined;
    const ok = await action.run(async () => {
      created = await travelApi(client).createExposure({
        employeeId: employee.employeeId,
        pathway: f.pathway,
        occurredAt: at,
        ...(f.details.trim() ? { details: f.details.trim() } : {}),
        ...(f.chemicalCode ? { chemicalCode: f.chemicalCode } : {}),
        ...(f.flightRef.trim() ? { flightRef: f.flightRef.trim().toUpperCase() } : {}),
        ...(f.sourceKnown ? { sourceKnown: f.sourceKnown === 'yes' } : {}),
      });
    });
    if (ok && created) navigate(`/exposures/${encodeURIComponent(created.id)}`);
  }

  return (
    <section className="card" aria-labelledby="ex-new">
      <h2 id="ex-new">Start an exposure pathway</h2>
      <p className="small muted">Incidents with a needlestick, chemical, fume or heat exposure start a pathway automatically.</p>
      <ErrorSummary errors={errors} />
      {employee ? (
        <p id="ex-employee" tabIndex={-1}>Employee: <strong>{employee.displayName}</strong> ({employee.employeeId}) <button type="button" className="link-button" onClick={() => setEmployee(undefined)}>Change</button></p>
      ) : (
        <div id="ex-employee" tabIndex={-1}><EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmployee} /></div>
      )}
      <form className="form" onSubmit={submit} noValidate>
        <div className="inline-form">
          <IdField id="ex-pathway" label="Pathway" required error={errorFor(errors, 'ex-pathway')}>{(db) => <select id="ex-pathway" aria-describedby={db} value={f.pathway} onChange={(e) => setF({ ...f, pathway: e.target.value })}><SelectOptions values={PATHWAYS} empty="Choose…" /></select>}</IdField>
          <IdField id="ex-occurred" label="Occurred at" required error={errorFor(errors, 'ex-occurred')}>{(db) => <input id="ex-occurred" type="datetime-local" aria-describedby={db} value={f.occurredAt} onChange={(e) => setF({ ...f, occurredAt: e.target.value })} />}</IdField>
          {f.pathway === 'CHEMICAL_DGR' && (
            <IdField id="ex-chemical" label="Chemical" required error={errorFor(errors, 'ex-chemical')}>
              {(db) => (
                <select id="ex-chemical" aria-describedby={db} value={f.chemicalCode} onChange={(e) => setF({ ...f, chemicalCode: e.target.value })}>
                  <option value="">Choose…</option>
                  {chemicals.map((c) => <option key={c.code} value={c.code}>{c.name}{c.unNumber ? ` (UN ${c.unNumber})` : ''}</option>)}
                </select>
              )}
            </IdField>
          )}
          {(f.pathway === 'CABIN_FUME' || f.pathway === 'CHEMICAL_DGR') && (
            <IdField id="ex-flight" label="Flight (optional)" error={errorFor(errors, 'ex-flight')}>{(db) => <input id="ex-flight" size={8} aria-describedby={db} value={f.flightRef} onChange={(e) => setF({ ...f, flightRef: e.target.value })} />}</IdField>
          )}
          {f.pathway === 'NEEDLESTICK_BBP' && (
            <Field label="Source known">
              {(id) => (
                <select id={id} value={f.sourceKnown} onChange={(e) => setF({ ...f, sourceKnown: e.target.value })}>
                  <option value="">Not recorded</option>
                  <option value="yes">Yes</option>
                  <option value="no">No</option>
                </select>
              )}
            </Field>
          )}
        </div>
        {chemical && <p><SdsLink chemical={chemical} /> <span className="small muted">Hazards: {hazardsText(chemical.hazards)}</span></p>}
        <IdField id="ex-details" label="Details (optional, stored encrypted)">{(db) => <textarea id="ex-details" rows={2} maxLength={4000} aria-describedby={db} value={f.details} onChange={(e) => setF({ ...f, details: e.target.value })} />}</IdField>
        <button type="submit" disabled={action.busy}>Start pathway</button>
      </form>
      <ActionFeedback error={action.error} />
    </section>
  );
}

/** `#/exposures` – exposure pathways with protocol timers (E11-F03). */
export function ExposureList({ client, session, onSessionExpired }: ScreenProps) {
  const api = travelApi(client);
  const [status, setStatus] = useState('');
  const list = useAsync(() => api.exposures(status || undefined), onSessionExpired, [status]);
  const chemicals = useAsync(() => api.chemicals(), onSessionExpired);
  const now = useNow(15_000);
  const rows = [...(list.data ?? [])].sort((a, b) => (nextOpenTimer(a.timers)?.dueAt ?? '9999').localeCompare(nextOpenTimer(b.timers)?.dueAt ?? '9999'));
  const overdue = rows.reduce((n, e) => n + overdueTimerCount(e.timers, now), 0);
  return (
    <section>
      <PageTitle sub="Needlestick, chemical / dangerous goods, cabin fume, heat illness and bite / sting pathways. Tasks due within 30 minutes or overdue raise an alert.">Exposure pathways</PageTitle>
      {can(session.roles, 'exposurePathways') && chemicals.data && <NewExposureForm client={client} onSessionExpired={onSessionExpired} chemicals={chemicals.data} />}
      {!!chemicals.error && <ErrorAlert error={chemicals.error} />}
      <div className="tiles" role="group" aria-label="Pathway KPIs">
        <KpiTile label="Open pathways" value={rows.filter((e) => nextOpenTimer(e.timers)).length} />
        <KpiTile label="Overdue tasks" value={overdue} status={overdue > 0 ? 'MISSED' : undefined} />
      </div>
      <div className="filters inline-form">
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All</option><option value="OPEN">Open</option><option value="CLOSED">Closed</option></select>}</Field>
      </div>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No exposures.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} exposure{rows.length === 1 ? '' : 's'}, next task due first</caption>
            <thead><tr><th scope="col">Exposure</th><th scope="col">Employee</th><th scope="col">Pathway</th><th scope="col">Occurred</th><th scope="col">Next task</th><th scope="col">Countdown</th><th scope="col" className="num">Overdue</th></tr></thead>
            <tbody>
              {rows.map((e) => {
                const next = nextOpenTimer(e.timers);
                const v = next ? timerView(next, now) : undefined;
                const od = overdueTimerCount(e.timers, now);
                return (
                  <tr key={e.id} className={od > 0 ? 'row-breached' : undefined}>
                    <th scope="row"><a href={exposureLink(e.id)}>{e.ref}</a></th>
                    <td>{e.displayName ?? e.employeeId ?? '–'}</td>
                    <td>{humanise(e.pathway)}</td>
                    <td className="small">{formatDate(e.occurredAt)}</td>
                    <td>{next?.label ?? <span className="muted">All tasks done</span>}</td>
                    <td>{v ? <ToneBadge tone={v.tone}>{v.label}</ToneBadge> : <ToneBadge tone="green">Complete</ToneBadge>}</td>
                    <td className="num">{od > 0 ? <strong className="text-red">{od}</strong> : 0}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function CompleteDialog({ timer, busy, onConfirm, onClose }: { timer: ExposureTimer; busy: boolean; onConfirm: (note: string) => void; onClose: () => void }) {
  const [note, setNote] = useState('');
  return (
    <Dialog title={`Complete: ${timer.label}`} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); onConfirm(note); }}>
        <Field label="Note (optional)">{(id) => <textarea id={id} rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
        <div className="actions">
          <button type="submit" disabled={busy}>Mark complete</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}

/** `#/exposures/{id}` – pathway timers with a live countdown, task completion, serology schedule, SDS link. */
export function ExposureDetail({ client, session, onSessionExpired, exposureId }: ScreenProps & { exposureId: string }) {
  const api = travelApi(client);
  const detail = useAsync(() => api.exposure(exposureId), onSessionExpired, [exposureId]);
  const chemicals = useAsync(() => api.chemicals(), onSessionExpired);
  const [completing, setCompleting] = useState<ExposureTimer>();
  const action = useAction(onSessionExpired);
  const now = useNow(1000);
  const e = detail.data;
  const chemical = e?.chemicalCode ? chemicals.data?.find((c) => c.code === e.chemicalCode) : undefined;
  const timers = e ? sortTimers(e.timers) : [];
  const next = e ? nextOpenTimer(e.timers) : undefined;
  const overdue = e ? overdueTimerCount(e.timers, now) : 0;
  const write = can(session.roles, 'exposurePathways');

  async function complete(t: ExposureTimer, note: string) {
    const ok = await action.run(async () => {
      await api.completeTask(exposureId, t.code, note.trim() || undefined);
      return `${t.label} completed.`;
    });
    if (ok) {
      setCompleting(undefined);
      detail.reload();
    }
  }

  return (
    <section>
      <p className="breadcrumb"><a href="#/exposures">Exposure pathways</a> › {e?.ref ?? 'Exposure'}</p>
      <PageTitle>{e ? `${e.ref} – ${humanise(e.pathway)}` : 'Exposure'}</PageTitle>
      {detail.loading && !e && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {e && (
        <>
          <div className="grid-2">
            <Dl items={[
              ['Employee', e.displayName ? `${e.displayName} (${e.employeeId ?? ''})` : e.employeeId],
              ['Occurred', formatDate(e.occurredAt)],
              ['Status', <StatusBadge status={e.status ?? (next ? 'OPEN' : 'COMPLETED')} />],
              ['OH case', <a href={`#/cases/${encodeURIComponent(e.caseId)}`}>Open case</a>],
              ...(e.flightRef ? [['Flight', e.flightRef] as [string, string]] : []),
              ...(e.pathway === 'NEEDLESTICK_BBP' ? [['Source known', yesNo(e.sourceKnown)] as [string, string]] : []),
            ]} />
            <div className="tiles" role="group" aria-label="Timer status">
              <KpiTile label={next ? `Next: ${next.label}` : 'All tasks done'} value={next ? <span className="mono" aria-live="off">{clock(next.dueAt, now)}</span> : '✓'} status={next && timerView(next, now).overdue ? 'MISSED' : undefined} />
              <KpiTile label="Overdue tasks" value={overdue} status={overdue > 0 ? 'MISSED' : undefined} />
            </div>
          </div>
          {e.chemicalCode && (
            <p>
              {chemical ? <SdsLink chemical={chemical} /> : <span className="muted">Chemical {e.chemicalCode}</span>}
              {chemical && <span className="small muted"> · Hazards: {hazardsText(chemical.hazards)}</span>}
            </p>
          )}
          <ActionFeedback error={action.error} message={action.message} />
          <div className="table-wrap">
            <table>
              <caption>Protocol tasks</caption>
              <thead><tr><th scope="col">Task</th><th scope="col">Due</th><th scope="col">Countdown</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
              <tbody>
                {timers.map((t) => {
                  const v = timerView(t, now);
                  return (
                    <tr key={t.code} className={v.overdue ? 'row-breached' : undefined}>
                      <th scope="row">{t.label}</th>
                      <td className="small">{formatDate(t.dueAt)}</td>
                      <td className="mono">{v.done ? formatDate(t.completedAt) : clock(t.dueAt, now)}</td>
                      <td><ToneBadge tone={v.tone}>{v.label}</ToneBadge></td>
                      <td>{write && !v.done && <button type="button" className="small-button" onClick={() => setCompleting(t)}>Complete {t.label}</button>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {e.serology.length > 0 && (
            <section aria-labelledby="serology-title">
              <h2 id="serology-title">Serology schedule</h2>
              <ul className="plain-list">
                {e.serology.map((s) => {
                  const v = serologyView(s);
                  return (
                    <li key={s.dueOn}>
                      {s.label ?? 'Serology'} – {formatDay(s.dueOn)} <ToneBadge tone={v.tone}>{v.label}</ToneBadge>
                    </li>
                  );
                })}
              </ul>
            </section>
          )}
        </>
      )}
      {completing && <CompleteDialog timer={completing} busy={action.busy} onConfirm={(n) => void complete(completing, n)} onClose={() => setCompleting(undefined)} />}
    </section>
  );
}
