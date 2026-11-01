import { useState, type FormEvent } from 'react';
import { devicesApi, type Approval, type ImportResult } from '../../api/devices';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { plural } from '../../domain/deviceConsole';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { CodeBadge } from './common';
import { DecideDialog } from './Wizard';

/** Why the current user may not decide an approval (four-eyes / role), or undefined. */
function blockReason(a: Approval, username: string | undefined, roles: readonly string[]): string | undefined {
  if (username && a.requestedBy === username) return 'You requested this – another approver must decide (four-eyes).';
  if (a.kind === 'POSTURE_EXCEPTION' && !roles.includes('SECURITY_OPS')) return 'Security Operations approve posture exceptions.';
  if ((a.kind === 'DECOMMISSION' || a.kind === 'SCHEDULE_CHANGE' || a.kind === 'IMPORT') && !roles.includes('DEVICE_APPROVER')) return 'A device approver decides this request.';
  return undefined;
}

/** `#/devices/approvals` – pending submissions, decommission requests, imports, posture exceptions, schedule changes. */
export function DeviceApprovals({ client, session, onSessionExpired }: ScreenProps) {
  const api = devicesApi(client);
  const list = useAsync(() => api.approvals('PENDING'), onSessionExpired);
  const [deciding, setDeciding] = useState<Approval>();
  const [viewImport, setViewImport] = useState<string>();
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Four-eyes: you cannot approve a change you requested, submitted or edited. Configuration certifications open in the wizard (step 7) with the full approver view.">Approvals</PageTitle>
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>Nothing is waiting for approval.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'pending approval')}</caption>
            <thead><tr><th scope="col">Kind</th><th scope="col">Reference</th><th scope="col">Requested by</th><th scope="col">Requested</th><th scope="col">Detail</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((a) => {
                const block = blockReason(a, session.username, session.roles);
                return (
                  <tr key={a.approvalId}>
                    <th scope="row">{humanise(a.kind)}</th>
                    <td className="mono small">{a.reference}{a.version ? ` v${a.version}` : ''}</td>
                    <td>{a.requestedBy}</td>
                    <td className="small">{formatDate(a.requestedAt)}</td>
                    <td className="small">{a.detail ? Object.entries(a.detail).filter(([, v]) => typeof v !== 'object').map(([k, v]) => `${humanise(k)}: ${String(v)}`).join(' · ') : '–'}</td>
                    <td>
                      {a.kind === 'CONFIGURATION' ? (
                        <a className="button small-button" href={`#/devices/wizard/${a.reference}`}>Open approver view</a>
                      ) : (
                        <>
                          {a.kind === 'IMPORT' && <button type="button" className="secondary small-button" onClick={() => setViewImport(a.reference)}>View diff</button>}{' '}
                          <button type="button" className="small-button" aria-label={`Decide ${humanise(a.kind)} ${a.reference}`} onClick={() => setDeciding(a)}>Decide</button>
                          {block && <span className="small muted block">{block}</span>}
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {viewImport && <ImportDiff importId={viewImport} client={client} session={session} onSessionExpired={onSessionExpired} onClose={() => setViewImport(undefined)} />}
      {deciding && (
        <DecideDialog
          title={`${humanise(deciding.kind)} ${deciding.reference}`}
          requestedBy={deciding.requestedBy}
          username={session.username}
          onClose={() => setDeciding(undefined)}
          onSubmit={async (approve, note) => {
            const a = deciding;
            setDeciding(undefined);
            if (await action.run(async () => { await api.decideApproval(a.approvalId, approve, note); return `${humanise(a.kind)} ${a.reference} ${approve ? 'approved' : 'rejected'}.`; })) list.reload();
          }}
        />
      )}
    </section>
  );
}

/** Import diff viewer (environment promotion): per configuration CREATE / UPDATE with path-level changes. */
export function ImportDiff({ importId, client, onSessionExpired, onClose }: ScreenProps & { importId: string; onClose: () => void }) {
  const view = useAsync(() => devicesApi(client).importView(importId), onSessionExpired, [importId]);
  return (
    <section className="card" aria-labelledby="imp-title">
      <h2 id="imp-title">Import {importId.slice(0, 8)} – changes</h2>
      {view.loading && <Loading />}
      <ErrorAlert error={view.error} />
      {view.data && <ImportItems result={view.data} />}
      <button type="button" className="secondary" onClick={onClose}>Close</button>
    </section>
  );
}

export function ImportItems({ result }: { result: ImportResult }) {
  return (
    <>
      <p className="small">Status: <CodeBadge code={result.status} /> {result.sourceEnvironment && <>from {result.sourceEnvironment}</>} {result.targetEnvironment && <>into {result.targetEnvironment}</>}</p>
      {result.items.map((i, n) => (
        <div key={`${i.configurationId}-${n}`} className="diff-item">
          <h3>{i.configurationId ?? 'New configuration'} – {humanise(i.action)}{i.targetConfigurationId ? ` (updates ${i.targetConfigurationId})` : ''}</h3>
          {(i.problems?.length ?? 0) > 0 && <Alert kind="warning">Problems: {i.problems!.map(humanise).join(', ')}</Alert>}
          {(i.secretsToRebind?.length ?? 0) > 0 && <p className="small">Secrets to re-bind in the target environment: {i.secretsToRebind!.join(', ')}</p>}
          {i.diff.length === 0 ? <p className="small muted">No differences.</p> : (
            <table>
              <caption className="visually-hidden">Differences</caption>
              <thead><tr><th scope="col">Path</th><th scope="col">Current</th><th scope="col">Imported</th></tr></thead>
              <tbody>
                {i.diff.slice(0, 200).map((d) => (
                  <tr key={d.path}>
                    <th scope="row" className="mono small">{d.path}</th>
                    <td className="small"><del aria-label="current value">{d.from === undefined || d.from === null ? '–' : JSON.stringify(d.from)}</del></td>
                    <td className="small"><ins aria-label="imported value">{d.to === undefined || d.to === null ? '–' : JSON.stringify(d.to)}</ins></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}
    </>
  );
}

/**
 * `#/devices/test-review` – OH nurse / nursing head records the review of the device test result shown in the test form
 * (wizard step 6). Nurses never see the console configuration itself.
 */
export function DeviceTestReview({ client, onSessionExpired }: ScreenProps) {
  const api = devicesApi(client);
  const [f, setF] = useState({ configurationId: '', outcome: '', note: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!/^CFG-\d{6}$/.test(f.configurationId.trim())) errs.push({ field: 'tr-config', message: 'Enter the configuration reference, e.g. CFG-000012' });
    if (!f.outcome) errs.push({ field: 'tr-accepted', message: 'Choose whether the test result is accepted' });
    if (f.outcome === 'REJECTED' && !f.note.trim()) errs.push({ field: 'tr-note', message: 'Explain what was wrong with the test result' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await api.nurseReview(f.configurationId.trim(), { outcome: f.outcome as 'ACCEPTED' | 'REJECTED', note: f.note.trim() }); return `Review recorded for ${f.configurationId.trim()}.`; })) setF({ configurationId: '', outcome: '', note: '' });
  }
  return (
    <section>
      <PageTitle sub="After the device administrator runs a test into the test form, check that the values landed in the right fields with the right units and record your review.">Device test review</PageTitle>
      <form className="card form" onSubmit={submit} noValidate aria-label="Record the test review">
        <ErrorSummary errors={errors} />
        <IdField id="tr-config" label="Configuration reference" required error={errorFor(errors, 'tr-config')}>
          {(d) => <input id="tr-config" aria-describedby={d} aria-invalid={!!errorFor(errors, 'tr-config')} maxLength={10} value={f.configurationId} onChange={(e) => setF({ ...f, configurationId: e.target.value.toUpperCase() })} />}
        </IdField>
        <fieldset>
          <legend>Outcome <span aria-hidden="true">*</span></legend>
          {errorFor(errors, 'tr-accepted') && <span className="field-error-text small">{errorFor(errors, 'tr-accepted')}</span>}
          <label className="inline-row"><input id="tr-accepted" type="radio" name="tr-outcome" checked={f.outcome === 'ACCEPTED'} onChange={() => setF({ ...f, outcome: 'ACCEPTED' })} /> Accepted – values and units correct in the test form</label>
          <label className="inline-row"><input type="radio" name="tr-outcome" checked={f.outcome === 'REJECTED'} onChange={() => setF({ ...f, outcome: 'REJECTED' })} /> Rejected</label>
        </fieldset>
        <IdField id="tr-note" label="Note" error={errorFor(errors, 'tr-note')}>{(d) => <textarea id="tr-note" rows={3} maxLength={1000} aria-describedby={d} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />}</IdField>
        <div className="actions"><button type="submit" disabled={action.busy}>Record review</button></div>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </section>
  );
}
