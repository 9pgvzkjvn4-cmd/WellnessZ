import { useState, type FormEvent } from 'react';
import { ACK_ACTIONS, codingText, recordApi, type AckAction, type Result } from '../../api/record';
import { documentContentUrl } from '../../api/platform';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { OverdueBadge, ResultFlag, ResultValue } from './ResultFlag';

/** `#/results/worklist` – unacknowledged abnormal / critical results (E03-BL-012), acknowledge with action, result letter. */
export function ResultsWorklist({ client, onSessionExpired }: ScreenProps) {
  const api = recordApi(client);
  const [overdueOnly, setOverdueOnly] = useState(false);
  const list = useAsync(() => api.worklist(overdueOnly || undefined), onSessionExpired, [overdueOnly]);
  const action = useAction(onSessionExpired);
  const [acking, setAcking] = useState<Result>();
  const [ack, setAck] = useState<{ action: AckAction; note: string }>({ action: 'NO_ACTION', note: '' });
  const [letter, setLetter] = useState<{ resultId: string; documentId: string; number?: string }>();

  async function submitAck(e: FormEvent) {
    e.preventDefault();
    if (!acking) return;
    const r = acking;
    const ok = await action.run(async () => {
      await api.acknowledge(r.id, ack.action, ack.note.trim());
      return `Result acknowledged: ${humanise(ack.action)}.`;
    });
    if (ok) {
      setAcking(undefined);
      list.reload();
    }
  }

  async function generateLetter(r: Result) {
    await action.run(async () => {
      const doc = await api.resultLetter(r.id);
      setLetter({ resultId: r.id, documentId: doc.id, number: doc.documentNumber });
      return `Result letter ${doc.documentNumber ?? ''} generated as a draft – review and sign it in the employee's documents.`;
    });
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Unacknowledged abnormal and critical results. Results older than 72 hours are overdue and escalated to the CMO.">Results worklist</PageTitle>
      <label className="checkbox">
        <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} /> Overdue only
      </label>
      <ActionFeedback error={action.error} message={action.message} />
      {letter && (
        <p>
          <a href={documentContentUrl(letter.documentId)} target="_blank" rel="noopener">
            Open result letter {letter.number}
          </a>
        </p>
      )}
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nothing to acknowledge.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} result{rows.length === 1 ? '' : 's'} to acknowledge</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Test</th>
                <th scope="col">Value</th>
                <th scope="col">Flag</th>
                <th scope="col">Received</th>
                <th scope="col">Age</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={r.overdue ? 'row-breached' : undefined}>
                  <td>
                    <a href={`#/employees/${encodeURIComponent(r.employeeId)}/chart/results`}>{r.employeeName ?? r.employeeId}</a>
                  </td>
                  <th scope="row">{codingText(r.code)}</th>
                  <td><ResultValue r={r} /></td>
                  <td><ResultFlag flag={r.flag} /></td>
                  <td>{formatDate(r.receivedAt ?? r.effectiveAt)}</td>
                  <td><OverdueBadge overdue={r.overdue} ageHours={r.ageHours} /></td>
                  <td>
                    <div className="card-actions">
                      <button type="button" className="small-button" onClick={() => { setAck({ action: 'NO_ACTION', note: '' }); setAcking(r); }} aria-label={`Acknowledge ${r.code.display} for ${r.employeeName ?? r.employeeId}`}>
                        Acknowledge
                      </button>
                      <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void generateLetter(r)} aria-label={`Generate result letter for ${r.code.display}`}>
                        Generate result letter
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {acking && (
        <Dialog title={`Acknowledge ${acking.code.display}`} onClose={() => setAcking(undefined)}>
          <p>
            {acking.employeeName ?? acking.employeeId}: <ResultValue r={acking} /> <ResultFlag flag={acking.flag} />
          </p>
          <form className="form" onSubmit={submitAck}>
            <Field label="Action" required>
              {(id) => (
                <select id={id} value={ack.action} onChange={(e) => setAck({ ...ack, action: e.target.value as AckAction })}>
                  <SelectOptions values={ACK_ACTIONS} />
                </select>
              )}
            </Field>
            <Field label="Note" required={ack.action !== 'NO_ACTION'} hint="Recorded in the audit trail.">
              {(id) => <textarea id={id} rows={3} maxLength={1000} value={ack.note} onChange={(e) => setAck({ ...ack, note: e.target.value })} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || (ack.action !== 'NO_ACTION' && !ack.note.trim())}>
                Acknowledge result
              </button>
              <button type="button" className="secondary" onClick={() => setAcking(undefined)}>
                Cancel
              </button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
