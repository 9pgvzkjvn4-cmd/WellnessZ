import { useState } from 'react';
import { seg } from '../../api/links';
import { DISMISS_REASONS, type ReconciliationRow } from '../../api/types';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

function SuggestionRow({ row, busy, onAccept, onDismiss }: { row: ReconciliationRow; busy: boolean; onAccept: () => void; onDismiss: (reasonCode: string, note: string) => void }) {
  const [reasonCode, setReasonCode] = useState('');
  const [note, setNote] = useState('');
  const [dismissing, setDismissing] = useState(false);
  return (
    <tr>
      <th scope="row" className="mono">{row.externalRef}</th>
      <td>{formatDate(row.occurredAt)}</td>
      <td>{humanise(row.incidentClass)}</td>
      <td>{humanise(row.severity)}{row.lostTime ? ' · lost time' : ''}</td>
      <td>{row.unitCode} {row.location ? `· ${row.location}` : ''}</td>
      <td>{row.ruleName ?? '–'}</td>
      <td>
        {!dismissing ? (
          <div className="card-actions">
            <button type="button" className="small-button" disabled={busy} onClick={onAccept}>
              Accept → case
            </button>
            <button type="button" className="secondary small-button" disabled={busy} onClick={() => setDismissing(true)}>
              Dismiss
            </button>
          </div>
        ) : (
          <form
            className="card-actions"
            onSubmit={(e) => {
              e.preventDefault();
              onDismiss(reasonCode, note);
            }}
          >
            <label htmlFor={`dr-${row.incidentId}`} className="visually-hidden">Dismiss reason</label>
            <select id={`dr-${row.incidentId}`} required value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
              <SelectOptions values={DISMISS_REASONS} empty="Reason…" />
            </select>
            <label htmlFor={`dn-${row.incidentId}`} className="visually-hidden">Note</label>
            <input id={`dn-${row.incidentId}`} placeholder={reasonCode === 'OTHER' ? 'Note (required)' : 'Note (optional)'} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
            <button type="submit" className="small-button" disabled={busy || !reasonCode || (reasonCode === 'OTHER' && !note.trim())}>
              Confirm
            </button>
            <button type="button" className="link-button" onClick={() => setDismissing(false)}>
              Cancel
            </button>
          </form>
        )}
      </td>
    </tr>
  );
}

/** E01-BL-005 incidents whose intake rule says SUGGEST – accept into a case or dismiss with a reason code. */
export function Suggestions({ client, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => client.api<ReconciliationRow[]>('/intake/suggestions'), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [created, setCreated] = useState<{ caseId: string; caseRef: string }>();

  async function accept(r: ReconciliationRow) {
    setCreated(undefined);
    const ok = await action.run(async () => {
      const res = await client.api<{ caseId: string; caseRef: string }>(`/intake/suggestions/${seg(r.incidentId)}/accept`, { method: 'POST' });
      setCreated(res);
    });
    if (ok) list.reload();
  }

  async function dismiss(r: ReconciliationRow, reasonCode: string, note: string) {
    setCreated(undefined);
    const ok = await action.run(async () => {
      await client.api(`/intake/suggestions/${seg(r.incidentId)}/dismiss`, { method: 'POST', body: { reasonCode, ...(note.trim() ? { note: note.trim() } : {}) } });
      return `${r.externalRef} dismissed (${humanise(reasonCode)}).`;
    });
    if (ok) list.reload();
  }

  return (
    <section>
      <PageTitle sub="Incidents from the EHS feed that an intake rule marked for review.">Incident suggestions</PageTitle>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {created && (
        <div className="alert alert-success" role="status">
          Case <a href={`#/cases/${encodeURIComponent(created.caseId)}`}>{created.caseRef}</a> created.
        </div>
      )}
      {list.data && list.data.length === 0 && <Empty>No suggestions waiting for a decision.</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Incident</th>
                <th scope="col">Occurred</th>
                <th scope="col">Class</th>
                <th scope="col">Severity</th>
                <th scope="col">Unit / location</th>
                <th scope="col">Rule</th>
                <th scope="col">Decision</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((r) => (
                <SuggestionRow key={r.incidentId} row={r} busy={action.busy} onAccept={() => void accept(r)} onDismiss={(c, n) => void dismiss(r, c, n)} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
