import { useState, type FormEvent } from 'react';
import { FOLLOW_UP_DECISIONS, FOLLOW_UP_STATUSES, RAI_REASONS, RAI_STATUSES, surveillanceApi, type FollowUp, type Rai } from '../../api/surveillance';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { daysUntil } from '../../domain/absence';
import type { ScreenProps } from '../types';

/** `#/surveillance/follow-ups` – abnormal surveillance results needing physician review (E07-F07). */
export function SurveillanceFollowUps({ client, session, onSessionExpired }: ScreenProps) {
  const api = surveillanceApi(client);
  const [status, setStatus] = useState('OPEN');
  const list = useAsync(() => api.followUps(status || undefined), onSessionExpired, [status]);
  const action = useAction(onSessionExpired);
  const [reviewing, setReviewing] = useState<FollowUp>();
  const [form, setForm] = useState({ decision: 'CONFIRMED', note: '', regulatoryNotificationRequired: false });
  const physician = can(session.roles, 'followUpReview');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!reviewing) return;
    const f = reviewing;
    const ok = await action.run(async () => {
      await api.reviewFollowUp(f.id, { decision: form.decision, regulatoryNotificationRequired: form.regulatoryNotificationRequired, ...(form.note.trim() ? { note: form.note.trim() } : {}) });
      return `Follow-up for ${f.displayName} reviewed: ${humanise(form.decision).toLowerCase()}.`;
    });
    if (ok) {
      setReviewing(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Created automatically when a result breaches a programme rule (STS, spirometry decline, biomonitoring limit, vision, abnormal flag).">Surveillance follow-ups</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={FOLLOW_UP_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={reviewing ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No follow-ups.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} follow-up{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Programme</th>
                <th scope="col">Rule</th>
                <th scope="col">Case</th>
                <th scope="col">Recheck due</th>
                <th scope="col">Regulatory</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => {
                const recheckOverdue = !!f.recheckDueOn && f.status === 'OPEN' && daysUntil(f.recheckDueOn) < 0;
                return (
                  <tr key={f.id}>
                    <th scope="row"><a href={`#/employees/${encodeURIComponent(f.employeeId)}/chart/results`}>{f.displayName}</a> <span className="small muted">{f.employeeId}</span></th>
                    <td>{humanise(f.programmeCode)}</td>
                    <td className="mono small">{f.ruleCode}</td>
                    <td>{f.caseId ? <a href={`#/cases/${encodeURIComponent(f.caseId)}`}>{f.caseRef ?? 'Case'}</a> : (f.caseRef ?? '–')}</td>
                    <td>{formatDay(f.recheckDueOn)}{recheckOverdue && <> <ToneBadge tone="red">Overdue</ToneBadge></>}</td>
                    <td>{f.regulatoryNotificationRequired ? <ToneBadge tone="amber">Notification required</ToneBadge> : '–'}</td>
                    <td>
                      <StatusBadge status={f.status} />
                      {f.reviewDecision && <div className="small">{humanise(f.reviewDecision)}{f.reviewedBy ? ` · ${f.reviewedBy}` : ''}</div>}
                    </td>
                    <td>
                      {physician && f.status === 'OPEN' && (
                        <button type="button" className="small-button" onClick={() => { action.reset(); setForm({ decision: 'CONFIRMED', note: '', regulatoryNotificationRequired: f.regulatoryNotificationRequired }); setReviewing(f); }} aria-label={`Review follow-up for ${f.displayName}`}>
                          Review
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {reviewing && (
        <Dialog title="Physician review" onClose={() => setReviewing(undefined)}>
          <p>{reviewing.displayName} – {humanise(reviewing.programmeCode)} · <span className="mono">{reviewing.ruleCode}</span> · created {formatDate(reviewing.createdAt)}</p>
          <form className="form" onSubmit={submit}>
            <fieldset>
              <legend>Decision</legend>
              {FOLLOW_UP_DECISIONS.map((d) => (
                <label key={d} className="inline-row">
                  <input type="radio" name="fu-decision" checked={form.decision === d} onChange={() => setForm({ ...form, decision: d })} /> {humanise(d)}
                </label>
              ))}
            </fieldset>
            <label className="inline-row">
              <input type="checkbox" checked={form.regulatoryNotificationRequired} onChange={(e) => setForm({ ...form, regulatoryNotificationRequired: e.target.checked })} /> Regulatory notification required
            </label>
            <Field label="Note">{(id) => <textarea id={id} rows={3} maxLength={2000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />}</Field>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save review</button>
              <button type="button" className="secondary" onClick={() => setReviewing(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

type RaiDialog = { r: Rai; mode: 'complete' | 'waive' };

/** `#/surveillance/rai` – return-after-serious-illness medicals (E07-BL-020); a REQUIRED item blocks fit decisions for safety-critical roles. */
export function ReturnAfterIllness({ client, session, onSessionExpired }: ScreenProps) {
  const api = surveillanceApi(client);
  const [status, setStatus] = useState('REQUIRED');
  const list = useAsync(() => api.rai(status || undefined), onSessionExpired, [status]);
  const action = useAction(onSessionExpired);
  const [dialog, setDialog] = useState<RaiDialog>();
  const [text, setText] = useState({ encounterId: '', note: '', reason: '' });
  const [creating, setCreating] = useState(false);
  const [emp, setEmp] = useState<EmployeeRef>();
  const [newRai, setNewRai] = useState({ reason: 'SURGERY', caseId: '' });
  const clinician = can(session.roles, 'raiComplete');
  const physician = can(session.roles, 'raiWaive');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!dialog) return;
    const { r, mode } = dialog;
    if (mode === 'waive' && !text.reason.trim()) return;
    const ok = await action.run(async () => {
      if (mode === 'complete') await api.completeRai(r.id, { ...(text.encounterId.trim() ? { encounterId: text.encounterId.trim() } : {}), ...(text.note.trim() ? { note: text.note.trim() } : {}) });
      else await api.waiveRai(r.id, text.reason.trim());
      return `Return-after-illness medical for ${r.displayName} ${mode === 'complete' ? 'completed' : 'waived'}.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!emp) return;
    const ok = await action.run(async () => {
      await api.createRai({ employeeId: emp.employeeId, reason: newRai.reason, ...(newRai.caseId.trim() ? { caseId: newRai.caseId.trim() } : {}) });
      return `Return-after-illness medical required for ${emp.displayName}.`;
    });
    if (ok) {
      setCreating(false);
      setEmp(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Required after long-term absence or serious illness in safety-critical roles. While required, fit decisions for those roles are blocked.">Return after illness</PageTitle>
      {clinician && !creating && <button type="button" onClick={() => { action.reset(); setCreating(true); }}>Add requirement</button>}
      {creating && (
        <section className="tile" aria-labelledby="rai-new">
          <h2 id="rai-new">New return-after-illness requirement</h2>
          {!emp ? (
            <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmp} label="Employee (name or employee ID)" />
          ) : (
            <form className="form compact" onSubmit={create}>
              <p>Employee: <strong>{emp.displayName}</strong> ({emp.employeeId}) <button type="button" className="link-button" onClick={() => setEmp(undefined)}>Change</button></p>
              <Field label="Reason" required>
                {(id) => (
                  <select id={id} value={newRai.reason} onChange={(e) => setNewRai({ ...newRai, reason: e.target.value })}>
                    <SelectOptions values={RAI_REASONS} />
                  </select>
                )}
              </Field>
              <Field label="Case id (optional)">{(id) => <input id={id} maxLength={60} value={newRai.caseId} onChange={(e) => setNewRai({ ...newRai, caseId: e.target.value })} />}</Field>
              <div className="actions">
                <button type="submit" disabled={action.busy}>Add requirement</button>
                <button type="button" className="secondary" onClick={() => setCreating(false)}>Cancel</button>
              </div>
            </form>
          )}
        </section>
      )}
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={RAI_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nothing here.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} item{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Reason</th>
                <th scope="col">Source</th>
                <th scope="col">Case</th>
                <th scope="col">Created</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row">{r.displayName} <span className="small muted">{r.employeeId}</span></th>
                  <td>{humanise(r.reason)}</td>
                  <td>{r.source === 'E06' ? 'Absence (long-term)' : 'Manual'}</td>
                  <td>{r.caseId ? <a href={`#/cases/${encodeURIComponent(r.caseId)}`}>Case</a> : '–'}</td>
                  <td className="small">{formatDate(r.createdAt)}</td>
                  <td>{r.status === 'REQUIRED' ? <ToneBadge tone="red">Required</ToneBadge> : <StatusBadge status={r.status} />}{r.completedAt && <div className="small">{formatDate(r.completedAt)}</div>}</td>
                  <td>
                    {r.status === 'REQUIRED' && (
                      <div className="card-actions">
                        {clinician && <button type="button" className="small-button" onClick={() => { action.reset(); setText({ encounterId: '', note: '', reason: '' }); setDialog({ r, mode: 'complete' }); }} aria-label={`Complete for ${r.displayName}`}>Complete</button>}
                        {physician && <button type="button" className="secondary small-button" onClick={() => { action.reset(); setText({ encounterId: '', note: '', reason: '' }); setDialog({ r, mode: 'waive' }); }} aria-label={`Waive for ${r.displayName}`}>Waive</button>}
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
        <Dialog title={dialog.mode === 'complete' ? 'Complete return-after-illness medical' : 'Waive return-after-illness medical'} onClose={() => setDialog(undefined)}>
          <p>{dialog.r.displayName} – {humanise(dialog.r.reason)}</p>
          <form className="form" onSubmit={submit}>
            {dialog.mode === 'complete' ? (
              <>
                <Field label="Encounter id (optional)">{(id) => <input id={id} maxLength={60} value={text.encounterId} onChange={(e) => setText({ ...text, encounterId: e.target.value })} />}</Field>
                <Field label="Note">{(id) => <textarea id={id} rows={2} maxLength={1000} value={text.note} onChange={(e) => setText({ ...text, note: e.target.value })} />}</Field>
              </>
            ) : (
              <Field label="Reason" required>{(id) => <textarea id={id} rows={2} required maxLength={500} value={text.reason} onChange={(e) => setText({ ...text, reason: e.target.value })} />}</Field>
            )}
            <div className="actions">
              <button type="submit" disabled={action.busy || (dialog.mode === 'waive' && !text.reason.trim())}>{dialog.mode === 'complete' ? 'Complete' : 'Waive'}</button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
