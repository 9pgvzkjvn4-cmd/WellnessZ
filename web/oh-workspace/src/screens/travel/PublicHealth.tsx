import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { DIRECTIONS, SUBMIT_CHANNELS, travelApi, type PublicHealthReport } from '../../api/travel';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, humanise, localToIso, toLocalInput } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { FieldError } from '../../domain/devices';
import { reportDeadline } from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { useNow } from './hooks';

type Props = Pick<ScreenProps, 'client' | 'onSessionExpired'>;
const isOpen = (r: PublicHealthReport) => !r.submittedAt && r.status !== 'SUBMITTED' && r.status !== 'ACKNOWLEDGED' && r.status !== 'CLOSED';

function NewReport({ client, onSessionExpired, onCreated }: Props & { onCreated: (r: PublicHealthReport) => void }) {
  const api = travelApi(client);
  const cases = useAsync(() => api.cases(), onSessionExpired);
  const exposures = useAsync(() => api.exposures(), onSessionExpired);
  const [f, setF] = useState({ source: '', authorityCode: 'UKHSA_LOCAL' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.source) errs.push({ field: 'ph-source', message: 'Choose the case or exposure to report.' });
    if (!/^[A-Z0-9_]{2,40}$/.test(f.authorityCode.trim().toUpperCase())) errs.push({ field: 'ph-authority', message: 'Enter the authority code, e.g. UKHSA_LOCAL.' });
    setErrors(errs);
    if (errs.length) return;
    const [kind, id] = f.source.split(':') as ['cd' | 'exp', string];
    let created: PublicHealthReport | undefined;
    const ok = await action.run(async () => {
      created = await api.createReport({ ...(kind === 'cd' ? { communicableCaseId: id } : { exposureId: id }), authorityCode: f.authorityCode.trim().toUpperCase() });
      return `Report ${created.ref ?? ''} generated.`;
    });
    if (ok && created) onCreated(created);
  }
  return (
    <details className="card">
      <summary>Draft a public-health report</summary>
      <form className="form inline-form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="ph-source" label="Case or exposure" required error={errorFor(errors, 'ph-source')}>
          {(db) => (
            <select id="ph-source" aria-describedby={db} value={f.source} onChange={(e) => setF({ ...f, source: e.target.value })}>
              <option value="">Choose…</option>
              <optgroup label="Notifiable communicable-disease cases">
                {(cases.data ?? []).filter((c) => c.notifiable).map((c) => <option key={c.id} value={`cd:${c.id}`}>{c.ref} {c.conditionName ?? c.conditionCode ?? ''}</option>)}
              </optgroup>
              <optgroup label="Exposures">
                {(exposures.data ?? []).map((x) => <option key={x.id} value={`exp:${x.id}`}>{x.ref} {humanise(x.pathway)}</option>)}
              </optgroup>
            </select>
          )}
        </IdField>
        <IdField id="ph-authority" label="Authority" required error={errorFor(errors, 'ph-authority')}>{(db) => <input id="ph-authority" size={14} aria-describedby={db} value={f.authorityCode} onChange={(e) => setF({ ...f, authorityCode: e.target.value })} />}</IdField>
        <button type="submit" disabled={action.busy}>Generate report</button>
      </form>
      {(!!cases.error || !!exposures.error) && <ErrorAlert error={cases.error ?? exposures.error} />}
      <ActionFeedback error={action.error} message={action.message} />
    </details>
  );
}

function SubmitForm({ client, onSessionExpired, report, onDone }: Props & { report: PublicHealthReport; onDone: () => void }) {
  const [f, setF] = useState({ channel: 'PORTAL', submittedAt: toLocalInput(), reference: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const at = localToIso(f.submittedAt);
    const errs: FieldError[] = [];
    if (!at) errs.push({ field: 'phs-at', message: 'Enter when it was submitted.' });
    else if (Date.parse(at) > Date.now() + 5 * 60_000) errs.push({ field: 'phs-at', message: 'Submission time cannot be in the future.' });
    if (f.channel === 'PORTAL' && !f.reference.trim()) errs.push({ field: 'phs-ref', message: 'Enter the portal reference.' });
    setErrors(errs);
    if (errs.length || !at) return;
    const ok = await action.run(async () => {
      await travelApi(client).submitReport(report.id, { channel: f.channel, submittedAt: at, ...(f.reference.trim() ? { reference: f.reference.trim() } : {}) });
      return 'Submission recorded.';
    });
    if (ok) onDone();
  }
  return (
    <form className="form" onSubmit={submit} noValidate aria-labelledby="phs-title">
      <h3 id="phs-title">Record submission</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <Field label="Channel">{(id) => <select id={id} value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value })}><SelectOptions values={SUBMIT_CHANNELS} /></select>}</Field>
        <IdField id="phs-at" label="Submitted at" required error={errorFor(errors, 'phs-at')}>{(db) => <input id="phs-at" type="datetime-local" aria-describedby={db} value={f.submittedAt} onChange={(e) => setF({ ...f, submittedAt: e.target.value })} />}</IdField>
        <IdField id="phs-ref" label="Reference" required={f.channel === 'PORTAL'} error={errorFor(errors, 'phs-ref')}>{(db) => <input id="phs-ref" maxLength={80} aria-describedby={db} value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} />}</IdField>
      </div>
      <button type="submit" disabled={action.busy}>Record submission</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

function CorrespondenceLog({ client, onSessionExpired, report, write, onAdded }: Props & { report: PublicHealthReport; write: boolean; onAdded: () => void }) {
  const [f, setF] = useState({ direction: 'OUT', summary: '', documentId: '' });
  const [error, setError] = useState<string>();
  const action = useAction(onSessionExpired);
  const entries = [...(report.correspondence ?? [])].sort((a, b) => (b.at ?? '').localeCompare(a.at ?? ''));
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!f.summary.trim()) {
      setError('Enter a summary.');
      return;
    }
    setError(undefined);
    const ok = await action.run(async () => {
      await travelApi(client).correspondence(report.id, { direction: f.direction, summary: f.summary.trim(), ...(f.documentId.trim() ? { documentId: f.documentId.trim() } : {}) });
      return 'Correspondence logged.';
    });
    if (ok) {
      setF({ ...f, summary: '', documentId: '' });
      onAdded();
    }
  }
  return (
    <section aria-labelledby="phc-title">
      <h3 id="phc-title">Correspondence log</h3>
      {entries.length === 0 ? <Empty>No correspondence yet.</Empty> : (
        <ul className="history-list" aria-label="Correspondence">
          {entries.map((c, i) => (
            <li key={c.id ?? i}>
              <ToneBadge tone={c.direction === 'IN' ? 'green' : 'muted'}>{c.direction === 'IN' ? 'Received' : 'Sent'}</ToneBadge> <span className="small muted">{formatDate(c.at)}{c.author ? ` · ${c.author}` : ''}</span>
              <div>{c.summary}</div>
              {c.documentId && <a className="small" href={documentContentUrl(c.documentId)} target="_blank" rel="noopener">Document</a>}
            </li>
          ))}
        </ul>
      )}
      {write && (
        <form className="form" onSubmit={add} noValidate>
          <div className="inline-form">
            <Field label="Direction">{(id) => <select id={id} value={f.direction} onChange={(e) => setF({ ...f, direction: e.target.value })}>{DIRECTIONS.map((d) => <option key={d} value={d}>{d === 'IN' ? 'Received (in)' : 'Sent (out)'}</option>)}</select>}</Field>
            <IdField id="phc-summary" label="Summary" required error={error}>{(db) => <input id="phc-summary" size={40} maxLength={1000} aria-describedby={db} value={f.summary} onChange={(e) => setF({ ...f, summary: e.target.value })} />}</IdField>
            <Field label="Document id (optional)">{(id) => <input id={id} size={12} value={f.documentId} onChange={(e) => setF({ ...f, documentId: e.target.value })} />}</Field>
          </div>
          <button type="submit" className="secondary" disabled={action.busy}>Log correspondence</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/public-health` – reports to the public-health authority with hours left, submission and correspondence (E11-F06). */
export function PublicHealthReports({ client, session, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => travelApi(client).reports(), onSessionExpired);
  const [show, setShow] = useState<'open' | 'all'>('open');
  const [selected, setSelected] = useState<string>();
  const now = useNow(60_000);
  const write = can(session.roles, 'publicHealthWrite');
  const all = [...(list.data ?? [])].sort((a, b) => (a.dueAt ?? '9999').localeCompare(b.dueAt ?? '9999'));
  const rows = show === 'open' ? all.filter(isOpen) : all;
  const overdue = all.filter((r) => isOpen(r) && reportDeadline(r, now).tone === 'red').length;
  const report = all.find((r) => r.id === selected);
  return (
    <section>
      <PageTitle sub="Notifiable diseases and exposures reported to the public-health authority within the condition's deadline.">Public-health reports</PageTitle>
      {write && <NewReport client={client} onSessionExpired={onSessionExpired} onCreated={(r) => { list.reload(); setSelected(r.id); }} />}
      <div className="tiles" role="group" aria-label="Report KPIs">
        <KpiTile label="Open reports" value={all.filter(isOpen).length} />
        <KpiTile label="Overdue" value={overdue} status={overdue > 0 ? 'MISSED' : undefined} />
      </div>
      <div className="filters inline-form">
        <Field label="Show">{(id) => <select id={id} value={show} onChange={(e) => setShow(e.target.value as 'open' | 'all')}><option value="open">Open</option><option value="all">All</option></select>}</Field>
      </div>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No reports.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} report{rows.length === 1 ? '' : 's'}, soonest deadline first</caption>
            <thead><tr><th scope="col">Report</th><th scope="col">Source</th><th scope="col">Authority</th><th scope="col">Status</th><th scope="col">Due</th><th scope="col">Hours left</th></tr></thead>
            <tbody>
              {rows.map((r) => {
                const d = reportDeadline(r, now);
                return (
                  <tr key={r.id} className={d.tone === 'red' ? 'row-breached' : undefined} aria-selected={r.id === selected}>
                    <th scope="row"><button type="button" className="link-button" onClick={() => setSelected(r.id)}>{r.ref ?? r.id}</button></th>
                    <td>
                      {r.communicableCaseId ? <a href={`#/communicable/${encodeURIComponent(r.communicableCaseId)}`}>{r.sourceRef ?? 'Communicable case'}</a> : r.exposureId ? <a href={`#/exposures/${encodeURIComponent(r.exposureId)}`}>{r.sourceRef ?? 'Exposure'}</a> : '–'}
                    </td>
                    <td>{r.authorityCode}</td>
                    <td><StatusBadge status={r.status} /></td>
                    <td className="small">{formatDate(r.dueAt)}</td>
                    <td><ToneBadge tone={d.tone}>{d.label}</ToneBadge></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {report && (
        <section className="card" aria-labelledby="ph-detail">
          <h2 id="ph-detail">Report {report.ref ?? ''}</h2>
          <Dl items={[
            ['Authority', report.authorityCode],
            ['Status', <StatusBadge status={report.status} />],
            ['Deadline', <ToneBadge tone={reportDeadline(report, now).tone}>{reportDeadline(report, now).label}</ToneBadge>],
            ['Document', report.documentId ? <a href={documentContentUrl(report.documentId)} target="_blank" rel="noopener">Public-health report (PDF)</a> : '–'],
            ['Submitted', report.submittedAt ? `${formatDate(report.submittedAt)} via ${humanise(report.channel)}${report.reference ? ` – ref ${report.reference}` : ''}` : 'Not yet'],
          ]} />
          {write && isOpen(report) && <SubmitForm key={report.id} client={client} onSessionExpired={onSessionExpired} report={report} onDone={list.reload} />}
          <CorrespondenceLog key={`c-${report.id}`} client={client} onSessionExpired={onSessionExpired} report={report} write={write} onAdded={list.reload} />
        </section>
      )}
    </section>
  );
}
