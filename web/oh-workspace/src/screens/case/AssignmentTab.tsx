import { useState, type FormEvent } from 'react';
import type { AssignmentHistory, StaffMember } from '../../api/types';
import { formatDate, localToIso } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

function StaffSelect({ id, value, onChange, staff, required, empty = 'Choose clinician…' }: { id: string; value: string; onChange: (v: string) => void; staff: StaffMember[]; required?: boolean; empty?: string }) {
  return (
    <select id={id} value={value} required={required} onChange={(e) => onChange(e.target.value)}>
      <option value="">{empty}</option>
      {staff
        .filter((s) => s.active !== false)
        .map((s) => (
          <option key={s.username} value={s.username}>
            {s.displayName} ({s.username})
          </option>
        ))}
    </select>
  );
}

export function AssignmentTab({ client, caseId, detail, refresh, onSessionExpired }: CaseTabProps) {
  const staff = useAsync(() => client.api<StaffMember[]>('/staff'), onSessionExpired);
  const history = useAsync(() => client.api<AssignmentHistory[]>(casePath(caseId, '/assignments')), onSessionExpired);
  const assignAction = useAction(onSessionExpired);
  const handoverAction = useAction(onSessionExpired);
  const [toUsername, setToUsername] = useState('');
  const [queueCode, setQueueCode] = useState('');
  const [reason, setReason] = useState('');
  const [ho, setHo] = useState({ toUsername: '', summary: '', outstandingActions: '', nextContactAt: '' });

  async function assign(body: { toUsername?: string; queueCode?: string; reason: string }) {
    const ok = await assignAction.run(async () => {
      await client.api(casePath(caseId, '/assign'), { method: 'POST', body });
      return body.toUsername ? `Assigned to ${body.toUsername}. The new owner has been notified.` : body.queueCode ? `Moved to queue ${body.queueCode}.` : 'Assigned to you.';
    });
    if (ok) {
      setReason('');
      history.reload();
      refresh();
    }
  }

  function submitAssign(e: FormEvent) {
    e.preventDefault();
    void assign({ ...(toUsername ? { toUsername } : {}), ...(queueCode.trim() ? { queueCode: queueCode.trim().toUpperCase() } : {}), reason: reason.trim() });
  }

  async function submitHandover(e: FormEvent) {
    e.preventDefault();
    const ok = await handoverAction.run(async () => {
      await client.api(casePath(caseId, '/handover'), {
        method: 'POST',
        body: { toUsername: ho.toUsername, summary: ho.summary.trim(), outstandingActions: ho.outstandingActions.trim(), nextContactAt: localToIso(ho.nextContactAt) ?? null },
      });
      return `Handover sent to ${ho.toUsername}; a HANDOVER task was created for them.`;
    });
    if (ok) setHo({ toUsername: '', summary: '', outstandingActions: '', nextContactAt: '' });
  }

  const staffList = staff.data ?? [];

  return (
    <div className="grid-2">
      <div>
        <h2>Assign / reassign</h2>
        <p className="small">
          Current owner: <strong>{detail.ownerName ?? detail.ownerUsername ?? 'Unassigned'}</strong> · queue {detail.queueCode ?? '–'}
        </p>
        {!!staff.error && <ErrorAlert error={staff.error} />}
        <form className="form" onSubmit={submitAssign}>
          <Field label="Assign to" hint="Leave empty with no queue to assign the case to yourself.">
            {(id) => <StaffSelect id={id} value={toUsername} onChange={setToUsername} staff={staffList} empty="Me (self-assign)" />}
          </Field>
          <Field label="Queue (optional)">{(id) => <input id={id} value={queueCode} placeholder="e.g. PHYSICIAN" maxLength={40} onChange={(e) => setQueueCode(e.target.value)} />}</Field>
          <Field label="Reason" required hint="Kept in the assignment history.">
            {(id) => <textarea id={id} rows={2} required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </Field>
          <button type="submit" disabled={!reason.trim() || assignAction.busy}>
            {toUsername || queueCode ? 'Assign' : 'Assign to me'}
          </button>
        </form>
        <ActionFeedback error={assignAction.error} message={assignAction.message} />

        <h2>Assignment history</h2>
        {history.loading && <Loading />}
        {!!history.error && <ErrorAlert error={history.error} />}
        {history.data && history.data.length === 0 && <Empty>No assignment history.</Empty>}
        <ol className="history-list">
          {(history.data ?? []).map((h, i) => (
            <li key={`${h.at ?? ''}-${i}`}>
              {h.fromUsername ?? 'Unassigned'} → <strong>{h.toUsername ?? h.queueCode ?? 'queue'}</strong>
              {h.queueCode && h.toUsername ? <span className="small muted"> ({h.queueCode})</span> : null}
              <div className="small muted">
                {formatDate(h.at)} {h.actor ? `· by ${h.actor}` : ''} {h.reason ? `· ${h.reason}` : ''}
              </div>
            </li>
          ))}
        </ol>
      </div>
      <div>
        <h2>Handover (E02-BL-019)</h2>
        <form className="form" onSubmit={submitHandover}>
          <Field label="Hand over to" required>{(id) => <StaffSelect id={id} value={ho.toUsername} onChange={(v) => setHo({ ...ho, toUsername: v })} staff={staffList} required />}</Field>
          <Field label="Summary" required>
            {(id) => <textarea id={id} rows={4} required maxLength={2000} value={ho.summary} onChange={(e) => setHo({ ...ho, summary: e.target.value })} />}
          </Field>
          <Field label="Outstanding actions">
            {(id) => <textarea id={id} rows={3} maxLength={2000} value={ho.outstandingActions} onChange={(e) => setHo({ ...ho, outstandingActions: e.target.value })} />}
          </Field>
          <Field label="Next contact">{(id) => <input id={id} type="datetime-local" value={ho.nextContactAt} onChange={(e) => setHo({ ...ho, nextContactAt: e.target.value })} />}</Field>
          <button type="submit" disabled={!ho.toUsername || !ho.summary.trim() || handoverAction.busy}>
            Send handover
          </button>
        </form>
        <ActionFeedback error={handoverAction.error} message={handoverAction.message} />
      </div>
    </div>
  );
}
