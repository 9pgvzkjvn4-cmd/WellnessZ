import { Fragment, useState, type FormEvent } from 'react';
import { TELEHEALTH_MODES, TELEHEALTH_STATUSES, treatmentApi, type TelehealthSession } from '../../api/treatment';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise, localToIso, toLocalInput } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import { addDays, joinWindow, validateSessionForm, type SessionForm } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { MessageThread } from './common';
import { caseLink } from './util';

const emptySession = (): SessionForm => {
  const d = new Date(Date.now() + 60 * 60_000);
  d.setMinutes(Math.ceil(d.getMinutes() / 15) * 15, 0, 0);
  return { employeeId: '', caseId: '', scheduledAt: toLocalInput(d), durationMinutes: '20', mode: 'VIDEO', clinicianUsername: '', providerCode: '', crewLayover: false, layoverCity: '' };
};

function ScheduleForm({ props, onDone }: { props: ScreenProps; onDone: (s: TelehealthSession) => void }) {
  const api = treatmentApi(props.client);
  const [f, setF] = useState<SessionForm>(emptySession);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(props.onSessionExpired);
  const err = (k: string) => errorFor(errors, k);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateSessionForm(f);
    setErrors(errs);
    if (errs.length) return;
    let created: TelehealthSession | undefined;
    const opt = (v: string) => (v.trim() ? v.trim() : undefined);
    const ok = await action.run(async () => {
      created = await api.createSession({
        employeeId: f.employeeId.trim().toUpperCase(),
        caseId: opt(f.caseId),
        scheduledAt: localToIso(f.scheduledAt)!,
        durationMinutes: Number(f.durationMinutes),
        mode: f.mode,
        clinicianUsername: opt(f.clinicianUsername),
        providerCode: opt(f.providerCode)?.toUpperCase(),
        crewLayover: f.crewLayover,
        ...(f.crewLayover ? { layoverCity: f.layoverCity.trim() } : {}),
      });
      return `Session ${created.ref} scheduled – the employee is invited.`;
    });
    if (ok && created) onDone(created);
  }

  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="Schedule telehealth session">
      <h2>Schedule session</h2>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="th-employee" label="Employee ID" required error={err('th-employee')}>
          {(db) => <input id="th-employee" aria-describedby={db} aria-invalid={!!err('th-employee')} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}
        </IdField>
        <IdField id="th-case" label="Case id">
          {(db) => <input id="th-case" aria-describedby={db} value={f.caseId} onChange={(e) => setF({ ...f, caseId: e.target.value })} />}
        </IdField>
        <IdField id="th-at" label="Start" required error={err('th-at')}>
          {(db) => <input id="th-at" type="datetime-local" aria-describedby={db} aria-invalid={!!err('th-at')} value={f.scheduledAt} onChange={(e) => setF({ ...f, scheduledAt: e.target.value })} />}
        </IdField>
        <IdField id="th-duration" label="Duration (min)" required error={err('th-duration')}>
          {(db) => <input id="th-duration" type="number" min={10} max={90} step={5} aria-describedby={db} aria-invalid={!!err('th-duration')} value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })} />}
        </IdField>
        <Field label="Mode">
          {(id) => (
            <select id={id} value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value as SessionForm['mode'] })}>
              <SelectOptions values={TELEHEALTH_MODES} />
            </select>
          )}
        </Field>
        <Field label="Clinician username" hint="Defaults to you">{(id) => <input id={id} value={f.clinicianUsername} onChange={(e) => setF({ ...f, clinicianUsername: e.target.value })} />}</Field>
        <Field label="Partner specialist" hint="Provider code, e.g. TELEDOC">{(id) => <input id={id} value={f.providerCode} onChange={(e) => setF({ ...f, providerCode: e.target.value })} />}</Field>
      </div>
      <label className="inline-row">
        <input type="checkbox" checked={f.crewLayover} onChange={(e) => setF({ ...f, crewLayover: e.target.checked })} /> Crew member on layover
      </label>
      {f.crewLayover && (
        <>
          <IdField id="th-city" label="Layover city" required error={err('th-city')} hint="Times are shown to the crew member in their local time; chat mode works on poor hotel connections.">
            {(db) => <input id="th-city" aria-describedby={db} aria-invalid={!!err('th-city')} value={f.layoverCity} onChange={(e) => setF({ ...f, layoverCity: e.target.value })} />}
          </IdField>
        </>
      )}
      <div className="actions">
        <button type="submit" disabled={action.busy}>Schedule</button>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

type DialogState = { s: TelehealthSession; mode: 'complete' | 'cancel' };

/** `#/telehealth` – schedule (incl. crew-layover mode), list, join, secure chat, complete with note → draft encounter (E04-F07). */
export function TelehealthScreen(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = treatmentApi(client);
  const [filter, setFilter] = useState({ status: 'SCHEDULED', from: addDays(todayIso(), -7), to: addDays(todayIso(), 30) });
  const list = useAsync(() => api.sessions(filter), onSessionExpired, [filter]);
  const canWrite = can(session.roles, 'telehealthWrite');
  const action = useAction(onSessionExpired);
  const [scheduling, setScheduling] = useState(false);
  const [chat, setChat] = useState<string>();
  const [links, setLinks] = useState<Record<string, { joinUrl: string; expiresAt: string }>>({});
  const [dialog, setDialog] = useState<DialogState>();
  const [text, setText] = useState({ note: '', followUpDate: '', reason: '' });
  const [completed, setCompleted] = useState<TelehealthSession>();
  const rows = list.data ?? [];

  async function join(s: TelehealthSession) {
    await action.run(async () => {
      const j = await api.join(s.id);
      setLinks((l) => ({ ...l, [s.id]: j }));
      return `Join link for ${s.ref} ready (single use, valid until ${formatDate(j.expiresAt)}).`;
    });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!dialog) return;
    const { s, mode } = dialog;
    if (mode === 'complete' && !text.note.trim()) return;
    if (mode === 'cancel' && !text.reason.trim()) return;
    const ok = await action.run(async () => {
      if (mode === 'complete') {
        const done = await api.completeSession(s.id, text.note.trim(), text.followUpDate || undefined);
        setCompleted(done);
        return `${s.ref} completed – a draft encounter was created for signature.`;
      }
      await api.cancelSession(s.id, text.reason.trim());
      return `${s.ref} cancelled.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="Video or chat consultations with employees and crew on layover. The employee must consent before joining; links are single-use.">Telehealth</PageTitle>
      {canWrite && !scheduling && <button type="button" onClick={() => setScheduling(true)}>Schedule session</button>}
      {scheduling && <ScheduleForm props={props} onDone={() => { setScheduling(false); list.reload(); }} />}
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
              <SelectOptions values={TELEHEALTH_STATUSES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="From">{(id) => <input id={id} type="date" value={filter.from} onChange={(e) => setFilter({ ...filter, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={filter.to} onChange={(e) => setFilter({ ...filter, to: e.target.value })} />}</Field>
      </div>
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {completed?.encounterId && (
        <Alert kind="success">
          Draft encounter created for {completed.displayName ?? completed.employeeId}: <a href={`#/encounters/${encodeURIComponent(completed.encounterId)}`}>open and sign the encounter</a>.
        </Alert>
      )}
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No sessions.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} session{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr><th scope="col">Session</th><th scope="col">Employee</th><th scope="col">When</th><th scope="col">Mode</th><th scope="col">Consent</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {rows.map((s) => {
                const w = joinWindow(s);
                const link = links[s.id];
                const active = s.status === 'SCHEDULED' || s.status === 'IN_PROGRESS';
                return (
                  <Fragment key={s.id}>
                    <tr>
                      <th scope="row">
                        {s.ref}
                        {s.crewLayover && <> <ToneBadge tone="amber">Crew layover{s.layoverCity ? ` – ${s.layoverCity}` : ''}</ToneBadge></>}
                        {s.providerCode && <span className="small muted"> with {s.providerCode}</span>}
                      </th>
                      <td>{s.displayName ?? s.employeeId} {s.caseId && <a className="small" href={caseLink(s.caseId)}>case</a>}</td>
                      <td className="small">{formatDate(s.scheduledAt)} · {s.durationMinutes} min</td>
                      <td>{humanise(s.mode)}</td>
                      <td>{s.consentGiven ? <ToneBadge tone="green">Given</ToneBadge> : <ToneBadge tone="amber">Awaiting</ToneBadge>}</td>
                      <td>
                        <StatusBadge status={s.status} />
                        {s.encounterId && <> <a className="small" href={`#/encounters/${encodeURIComponent(s.encounterId)}`}>encounter</a></>}
                      </td>
                      <td>
                        <div className="card-actions">
                          {active && (link ? (
                            <a className="button small-button" href={link.joinUrl} target="_blank" rel="noopener noreferrer">Open room</a>
                          ) : (
                            <button type="button" className="small-button" disabled={!w.open || action.busy} title={w.open ? undefined : w.label} aria-label={`Join ${s.ref}`} onClick={() => void join(s)}>
                              {w.open ? 'Join' : w.label || 'Join'}
                            </button>
                          ))}
                          <button type="button" className="secondary small-button" aria-expanded={chat === s.id} aria-label={`Chat for ${s.ref}`} onClick={() => setChat(chat === s.id ? undefined : s.id)}>Chat</button>
                          {canWrite && active && (
                            <>
                              <button type="button" className="secondary small-button" aria-label={`Complete ${s.ref}`} onClick={() => { action.reset(); setText({ note: '', followUpDate: '', reason: '' }); setDialog({ s, mode: 'complete' }); }}>Complete</button>
                              <button type="button" className="secondary small-button" aria-label={`Cancel ${s.ref}`} onClick={() => { action.reset(); setText({ note: '', followUpDate: '', reason: '' }); setDialog({ s, mode: 'cancel' }); }}>Cancel</button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                    {chat === s.id && (
                      <tr>
                        <td colSpan={7}>
                          <MessageThread
                            label={`Secure chat – ${s.ref}`}
                            load={() => api.messages(s.id)}
                            send={(t) => api.sendMessage(s.id, t)}
                            onSessionExpired={onSessionExpired}
                            me={session.username}
                            canSend={active}
                            hint="Encrypted at rest; part of the consultation record."
                          />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <Dialog title={dialog.mode === 'complete' ? `Complete ${dialog.s.ref}` : `Cancel ${dialog.s.ref}`} onClose={() => setDialog(undefined)}>
          <form className="form" onSubmit={submit}>
            {dialog.mode === 'complete' ? (
              <>
                <Field label="Consultation note" required hint="Saved as a draft illness encounter for your signature.">
                  {(id) => <textarea id={id} rows={6} required maxLength={20000} value={text.note} onChange={(e) => setText({ ...text, note: e.target.value })} />}
                </Field>
                <Field label="Follow-up date" hint="Adds a follow-up to the active treatment plan.">
                  {(id) => <input id={id} type="date" value={text.followUpDate} onChange={(e) => setText({ ...text, followUpDate: e.target.value })} />}
                </Field>
              </>
            ) : (
              <Field label="Reason" required>{(id) => <textarea id={id} rows={2} required value={text.reason} onChange={(e) => setText({ ...text, reason: e.target.value })} />}</Field>
            )}
            <div className="actions">
              <button type="submit" disabled={action.busy || (dialog.mode === 'complete' ? !text.note.trim() : !text.reason.trim())}>{dialog.mode === 'complete' ? 'Complete session' : 'Cancel session'}</button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Close</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
