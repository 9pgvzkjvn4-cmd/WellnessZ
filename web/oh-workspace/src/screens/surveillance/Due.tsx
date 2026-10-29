import { Fragment, useState, type FormEvent } from 'react';
import { COMPLIANCE_STATUSES, ENROLMENT_OUTCOMES, surveillanceApi, type Enrolment, type InviteResult } from '../../api/surveillance';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { toggleAll, toggleId } from '../../domain/surveillance';
import type { ScreenProps } from '../types';

const MAX_BULK = 5000;
const STATUS_TONE: Record<string, 'red' | 'amber' | 'green' | 'muted'> = { OVERDUE: 'red', DUE: 'amber', CURRENT: 'green', SCHEDULED: 'green', EXEMPT: 'muted' };

function InvitationLog({ client, onSessionExpired, enrolmentId }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { enrolmentId: string }) {
  const log = useAsync(() => surveillanceApi(client).invitations(enrolmentId), onSessionExpired, [enrolmentId]);
  if (log.loading) return <Loading />;
  if (log.error) return <ErrorAlert error={log.error} />;
  const rows = log.data ?? [];
  if (rows.length === 0) return <p className="small muted">No invitations sent.</p>;
  return (
    <ul className="plain-list history-list" aria-label="Invitation log">
      {rows.map((i) => (
        <li key={i.id}>
          {formatDate(i.sentAt)} · {humanise(i.channel)} · <StatusBadge status={i.status} />
          {i.appointmentId && <span className="small muted"> · appointment booked</span>}
        </li>
      ))}
    </ul>
  );
}

type RowDialog = { e: Enrolment; mode: 'complete' | 'exempt' };

/** `#/surveillance/due` – due list (E07-F02): filters, multi-select, bulk invite + book, invitation log. */
export function SurveillanceDue({ client, session, onSessionExpired }: ScreenProps) {
  const api = surveillanceApi(client);
  const empty = { programme: '', subsidiary: '', department: '', status: '', dueBefore: '' };
  const [form, setForm] = useState(empty);
  const [applied, setApplied] = useState(empty);
  const list = useAsync(() => api.due(applied), onSessionExpired, [applied]);
  const action = useAction(onSessionExpired);
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [bulk, setBulk] = useState({ book: true, siteCode: '', campaignId: '' });
  const [result, setResult] = useState<InviteResult>();
  const [openLog, setOpenLog] = useState<string>();
  const [dialog, setDialog] = useState<RowDialog>();
  const [complete, setComplete] = useState({ performedOn: isoDay(new Date()), outcome: 'FIT', encounterId: '' });
  const [exempt, setExempt] = useState({ reason: '', until: '' });
  const clinician = can(session.roles, 'enrolmentComplete');
  const physician = can(session.roles, 'enrolmentExempt');

  const rows = list.data ?? [];
  const visibleIds = rows.map((r) => r.id);
  const selected = visibleIds.filter((id) => sel.has(id));
  const allSelected = visibleIds.length > 0 && selected.length === visibleIds.length;

  async function invite() {
    if (selected.length === 0 || selected.length > MAX_BULK) return;
    setResult(undefined);
    const ok = await action.run(async () => {
      const r = await api.invite({ enrolmentIds: selected, book: bulk.book, ...(bulk.siteCode.trim() ? { siteCode: bulk.siteCode.trim().toUpperCase() } : {}), ...(bulk.campaignId.trim() ? { campaignId: bulk.campaignId.trim() } : {}) });
      setResult(r);
      return `${r.invited} invited${bulk.book ? `, ${r.booked} booked, ${r.notBooked} not booked` : ''}.`;
    });
    if (ok) {
      setSel(new Set());
      list.reload();
    }
  }

  async function submitRow(ev: FormEvent) {
    ev.preventDefault();
    if (!dialog) return;
    const { e, mode } = dialog;
    if (mode === 'exempt' && !exempt.reason.trim()) return;
    const ok = await action.run(async () => {
      if (mode === 'complete') {
        await api.completeEnrolment(e.id, { performedOn: complete.performedOn, outcome: complete.outcome, ...(complete.encounterId.trim() ? { encounterId: complete.encounterId.trim() } : {}) });
        return `${e.programmeTitle} recorded for ${e.displayName}; next due date recalculated.`;
      }
      await api.exemptEnrolment(e.id, { reason: exempt.reason.trim(), ...(exempt.until ? { until: exempt.until } : {}) });
      return `${e.displayName} exempted from ${e.programmeTitle}.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="Select people and send invitations (content-free template); with “book” an appointment is booked at their home site. After two ignored invitations the manager is told (status only).">
        Surveillance due list
      </PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setSel(new Set()); setApplied({ ...form, programme: form.programme.trim().toUpperCase(), subsidiary: form.subsidiary.trim().toUpperCase(), department: form.department.trim().toUpperCase() }); }}>
        <Field label="Programme code">{(id) => <input id={id} maxLength={40} value={form.programme} onChange={(e) => setForm({ ...form, programme: e.target.value })} />}</Field>
        <Field label="Subsidiary">{(id) => <input id={id} maxLength={20} value={form.subsidiary} onChange={(e) => setForm({ ...form, subsidiary: e.target.value })} />}</Field>
        <Field label="Department">{(id) => <input id={id} maxLength={20} value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} />}</Field>
        <Field label="Status">
          {(id) => (
            <select id={id} value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
              <SelectOptions values={COMPLIANCE_STATUSES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Due before">{(id) => <input id={id} type="date" value={form.dueBefore} onChange={(e) => setForm({ ...form, dueBefore: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>

      <div className="bulk-bar" role="region" aria-label="Bulk invitation">
        <span aria-live="polite">{selected.length} selected</span>
        <label className="inline-row">
          <input type="checkbox" checked={bulk.book} onChange={(e) => setBulk({ ...bulk, book: e.target.checked })} /> Book appointments
        </label>
        <Field label="Site (optional)">{(id) => <input id={id} maxLength={20} size={10} value={bulk.siteCode} onChange={(e) => setBulk({ ...bulk, siteCode: e.target.value })} />}</Field>
        <Field label="Campaign id (optional)">{(id) => <input id={id} maxLength={60} size={12} value={bulk.campaignId} onChange={(e) => setBulk({ ...bulk, campaignId: e.target.value })} />}</Field>
        <button type="button" disabled={action.busy || selected.length === 0 || selected.length > MAX_BULK} onClick={() => void invite()}>
          {bulk.book ? 'Invite and book' : 'Invite'} {selected.length > 0 ? selected.length : ''}
        </button>
        {selected.length > MAX_BULK && <span className="text-red small">At most {MAX_BULK} per batch.</span>}
      </div>
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {result && result.notBooked > 0 && <p className="small text-amber">{result.notBooked} could not be booked (no free slot) – they were invited only.</p>}

      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nobody matches.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} enrolment{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">
                  <input type="checkbox" aria-label="Select all shown" checked={allSelected} onChange={() => setSel(toggleAll(sel, visibleIds))} />
                </th>
                <th scope="col">Employee</th>
                <th scope="col">Unit</th>
                <th scope="col">Programme</th>
                <th scope="col">Last done</th>
                <th scope="col">Next due</th>
                <th scope="col">Status</th>
                <th scope="col">Invitations</th>
                <th scope="col">Appointment</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <Fragment key={e.id}>
                  <tr className={sel.has(e.id) ? 'row-selected' : undefined}>
                    <td>
                      <input type="checkbox" aria-label={`Select ${e.displayName} – ${e.programmeTitle}`} checked={sel.has(e.id)} onChange={() => setSel(toggleId(sel, e.id))} />
                    </td>
                    <th scope="row">{e.displayName} <span className="small muted">{e.employeeId}</span></th>
                    <td className="small">{[e.subsidiaryCode, e.departmentCode].filter(Boolean).join(' / ') || '–'}</td>
                    <td>{e.programmeTitle} <span className="small muted">{humanise(e.reason)}</span></td>
                    <td>{formatDay(e.lastCompletedOn)}</td>
                    <td>{formatDay(e.nextDueOn)}</td>
                    <td>
                      <ToneBadge tone={STATUS_TONE[e.status] ?? 'muted'}>{humanise(e.status)}</ToneBadge>
                      {e.escalatedAt && <> <ToneBadge tone="red" title={`Manager told ${formatDate(e.escalatedAt)}`}>Escalated</ToneBadge></>}
                    </td>
                    <td>
                      <button type="button" className="link-button" aria-expanded={openLog === e.id} aria-controls={`inv-${e.id}`} onClick={() => setOpenLog(openLog === e.id ? undefined : e.id)}>
                        {e.invitations} sent{e.lastInvitedAt ? ` · last ${formatDay(e.lastInvitedAt)}` : ''}
                      </button>
                    </td>
                    <td className="small">{e.appointment ? `${formatDate(e.appointment.startsAt)} · ${e.appointment.siteCode}` : '–'}</td>
                    <td>
                      <div className="card-actions">
                        {clinician && <button type="button" className="secondary small-button" onClick={() => { action.reset(); setDialog({ e, mode: 'complete' }); }} aria-label={`Record completion for ${e.displayName} – ${e.programmeTitle}`}>Complete</button>}
                        {physician && <button type="button" className="secondary small-button" onClick={() => { action.reset(); setExempt({ reason: '', until: '' }); setDialog({ e, mode: 'exempt' }); }} aria-label={`Exempt ${e.displayName} – ${e.programmeTitle}`}>Exempt</button>}
                      </div>
                    </td>
                  </tr>
                  {openLog === e.id && (
                    <tr id={`inv-${e.id}`}>
                      <td colSpan={10}><InvitationLog client={client} onSessionExpired={onSessionExpired} enrolmentId={e.id} /></td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <Dialog title={dialog.mode === 'complete' ? 'Record surveillance completed' : 'Exempt from programme'} onClose={() => setDialog(undefined)}>
          <p>{dialog.e.displayName} – <strong>{dialog.e.programmeTitle}</strong></p>
          <form className="form" onSubmit={submitRow}>
            {dialog.mode === 'complete' ? (
              <>
                <Field label="Performed on" required>{(id) => <input id={id} type="date" required max={isoDay(new Date())} value={complete.performedOn} onChange={(e) => setComplete({ ...complete, performedOn: e.target.value })} />}</Field>
                <Field label="Outcome" required>
                  {(id) => (
                    <select id={id} value={complete.outcome} onChange={(e) => setComplete({ ...complete, outcome: e.target.value })}>
                      <SelectOptions values={ENROLMENT_OUTCOMES} />
                    </select>
                  )}
                </Field>
                <Field label="Encounter id (optional)">{(id) => <input id={id} maxLength={60} value={complete.encounterId} onChange={(e) => setComplete({ ...complete, encounterId: e.target.value })} />}</Field>
              </>
            ) : (
              <>
                <Field label="Reason" required>{(id) => <textarea id={id} rows={2} required maxLength={500} value={exempt.reason} onChange={(e) => setExempt({ ...exempt, reason: e.target.value })} />}</Field>
                <Field label="Exempt until (optional)">{(id) => <input id={id} type="date" value={exempt.until} onChange={(e) => setExempt({ ...exempt, until: e.target.value })} />}</Field>
              </>
            )}
            <div className="actions">
              <button type="submit" disabled={action.busy || (dialog.mode === 'exempt' && !exempt.reason.trim())}>{dialog.mode === 'complete' ? 'Record completion' : 'Exempt'}</button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
