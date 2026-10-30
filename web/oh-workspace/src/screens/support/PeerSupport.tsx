import { useState, type FormEvent } from 'react';
import { PEER_CATEGORIES, PEER_OUTCOMES, supportApi, type PeerContact, type TrainingRecord, type Volunteer, type VolunteerBody } from '../../api/support';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { emptyContactForm, isQuarter, previousQuarter, smsTotal, toContactBody, trainingTone, validateContact, type ContactForm } from '../../domain/support';
import { DEFAULT_K } from '../../domain/suppression';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

/** `#/peer-support` – coordinator: volunteer register + training + availability; volunteer: routed requests + pseudonymous contact log; SMS quarterly report. */
export function PeerSupport(props: ScreenProps) {
  const roles = props.session.roles;
  const coordinator = can(roles, 'peerSupportCoordinate');
  const volunteer = can(roles, 'peerSupportVolunteer');
  return (
    <section>
      <PageTitle sub="Flight-crew peer support. Contact logs are pseudonymous – no names, no employee IDs; the programme reports aggregated counts only.">Peer support</PageTitle>
      {coordinator && <VolunteerRegister {...props} />}
      {volunteer && <VolunteerWork {...props} />}
      {can(roles, 'peerSupportReport') && <QuarterlyReport {...props} />}
    </section>
  );
}

// ---------------------------------------------------------------- coordinator
interface VolunteerDraft {
  username: string;
  displayName: string;
  base: string;
  languages: string;
  available: boolean;
  trainingRecords: TrainingRecord[];
}
const toDraft = (v?: Volunteer): VolunteerDraft => ({
  username: v?.username ?? '', displayName: v?.displayName ?? '', base: v?.base ?? '', languages: (v?.languages ?? []).join(', '), available: v?.available ?? true, trainingRecords: v?.trainingRecords ?? [],
});
const toBody = (d: VolunteerDraft): VolunteerBody => ({
  username: d.username.trim(),
  displayName: d.displayName.trim(),
  base: d.base.trim().toUpperCase(),
  languages: d.languages.split(',').map((l) => l.trim()).filter(Boolean),
  available: d.available,
  trainingRecords: d.trainingRecords.filter((t) => t.course.trim() && t.completedOn).map((t) => ({ course: t.course.trim(), completedOn: t.completedOn, ...(t.expiresOn ? { expiresOn: t.expiresOn } : {}) })),
});

function VolunteerRegister({ client, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const list = useAsync(() => api.volunteers(), onSessionExpired);
  const [editing, setEditing] = useState<{ id?: string; draft: VolunteerDraft }>();
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    const d = editing.draft;
    const errs: FieldError[] = [];
    if (!d.username.trim()) errs.push({ field: 'vol-username', message: 'Enter the volunteer’s username.' });
    if (!d.displayName.trim()) errs.push({ field: 'vol-name', message: 'Enter the display name (first name / alias shown to crew).' });
    if (!d.base.trim()) errs.push({ field: 'vol-base', message: 'Enter the base.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      if (editing.id) await api.updateVolunteer(editing.id, toBody(d));
      else await api.createVolunteer(toBody(d));
      return `${d.displayName.trim()} saved.`;
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    }
  }
  async function toggle(v: Volunteer) {
    if (await action.run(async () => { await api.updateVolunteer(v.id, { ...toBody(toDraft(v)), available: !v.available }); return `${v.displayName} marked ${v.available ? 'unavailable' : 'available'}.`; })) list.reload();
  }

  const d = editing?.draft;
  const setD = (patch: Partial<VolunteerDraft>) => editing && setEditing({ ...editing, draft: { ...editing.draft, ...patch } });
  return (
    <section aria-labelledby="peer-volunteers">
      <div className="toolbar">
        <h2 id="peer-volunteers">Volunteers, training and availability</h2>
        <button type="button" onClick={() => { setErrors([]); action.reset(); setEditing({ draft: toDraft() }); }}>Add volunteer</button>
      </div>
      <ActionFeedback error={editing ? undefined : action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No volunteers registered.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.filter((v) => v.available).length} of {rows.length} volunteers available</caption>
            <thead><tr><th scope="col">Volunteer</th><th scope="col">Base</th><th scope="col">Languages</th><th scope="col">Training</th><th scope="col">Availability</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((v) => (
                <tr key={v.id}>
                  <th scope="row">{v.displayName} <span className="small muted">{v.username}</span></th>
                  <td>{v.base}</td>
                  <td>{v.languages.join(', ') || '–'}</td>
                  <td>
                    {v.trainingRecords.length === 0 ? <ToneBadge tone="red">No training</ToneBadge> : v.trainingRecords.map((t) => (
                      <div key={`${t.course}-${t.completedOn}`} className="small">
                        {t.course} <ToneBadge tone={trainingTone(t.expiresOn)}>{t.expiresOn ? `expires ${formatDay(t.expiresOn)}` : `completed ${formatDay(t.completedOn)}`}</ToneBadge>
                      </div>
                    ))}
                  </td>
                  <td><ToneBadge tone={v.available ? 'green' : 'muted'}>{v.available ? 'Available' : 'Unavailable'}</ToneBadge></td>
                  <td>
                    <div className="card-actions">
                      <button type="button" className="secondary small-button" disabled={action.busy} aria-label={`Mark ${v.displayName} ${v.available ? 'unavailable' : 'available'}`} onClick={() => void toggle(v)}>{v.available ? 'Set unavailable' : 'Set available'}</button>
                      <button type="button" className="secondary small-button" aria-label={`Edit ${v.displayName}`} onClick={() => { setErrors([]); action.reset(); setEditing({ id: v.id, draft: toDraft(v) }); }}>Edit</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && d && (
        <Dialog title={editing.id ? `Edit ${d.displayName}` : 'Add volunteer'} onClose={() => setEditing(undefined)}>
          <form className="form" onSubmit={save} noValidate>
            <ErrorSummary errors={errors} />
            <IdField id="vol-username" label="Username" required error={errorFor(errors, 'vol-username')}>{(x) => <input id="vol-username" aria-describedby={x} value={d.username} onChange={(e) => setD({ username: e.target.value })} />}</IdField>
            <IdField id="vol-name" label="Display name" required error={errorFor(errors, 'vol-name')} hint="Crew see the alias and base only – never surnames.">{(x) => <input id="vol-name" aria-describedby={x} value={d.displayName} onChange={(e) => setD({ displayName: e.target.value })} />}</IdField>
            <IdField id="vol-base" label="Base" required error={errorFor(errors, 'vol-base')}>{(x) => <input id="vol-base" aria-describedby={x} value={d.base} onChange={(e) => setD({ base: e.target.value })} />}</IdField>
            <IdField id="vol-languages" label="Languages" hint="Comma-separated, e.g. en, fr">{(x) => <input id="vol-languages" aria-describedby={x} value={d.languages} onChange={(e) => setD({ languages: e.target.value })} />}</IdField>
            <label className="inline-row"><input type="checkbox" checked={d.available} onChange={(e) => setD({ available: e.target.checked })} /> Available for contact requests</label>
            <fieldset>
              <legend>Training records</legend>
              {d.trainingRecords.map((t, i) => {
                const upd = (patch: Partial<TrainingRecord>) => setD({ trainingRecords: d.trainingRecords.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
                return (
                  <div key={i} className="form-grid" role="group" aria-label={`Training record ${i + 1}`}>
                    <Field label="Course">{(id) => <input id={id} value={t.course} onChange={(e) => upd({ course: e.target.value })} />}</Field>
                    <Field label="Completed on">{(id) => <input id={id} type="date" value={t.completedOn} onChange={(e) => upd({ completedOn: e.target.value })} />}</Field>
                    <Field label="Expires on">{(id) => <input id={id} type="date" value={t.expiresOn ?? ''} onChange={(e) => upd({ expiresOn: e.target.value || undefined })} />}</Field>
                    <button type="button" className="link-button" aria-label={`Remove training record ${i + 1}`} onClick={() => setD({ trainingRecords: d.trainingRecords.filter((_, j) => j !== i) })}>Remove</button>
                  </div>
                );
              })}
              <button type="button" className="secondary small-button" onClick={() => setD({ trainingRecords: [...d.trainingRecords, { course: '', completedOn: '' }] })}>Add training record</button>
            </fieldset>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- volunteer
function VolunteerWork({ client, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const requests = useAsync(() => api.myRequests(), onSessionExpired);
  const [form, setForm] = useState<ContactForm>(() => emptyContactForm());
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [logged, setLogged] = useState<PeerContact[]>([]);
  const action = useAction(onSessionExpired);
  const rows = requests.data ?? [];
  const err = (f: string) => errorFor(errors, f);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateContact(form);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const c = await api.logContact(toContactBody(form));
      setLogged((l) => [c, ...l]);
      return `Contact ${c.contactCode} logged.`;
    });
    if (ok) setForm(emptyContactForm());
  }
  async function refer(c: PeerContact) {
    await action.run(async () => {
      const r = await api.referContact(c.id);
      setLogged((l) => l.map((x) => (x.id === c.id ? { ...x, ...r, referredAt: r?.referredAt ?? new Date().toISOString() } : x)));
      return `${c.contactCode} referred to the trusted mental-health professional (pseudonymous code only).`;
    });
  }

  return (
    <>
      <section aria-labelledby="peer-requests">
        <h2 id="peer-requests">Requests routed to me</h2>
        {requests.loading && !requests.data && <Loading />}
        {!!requests.error && <ErrorAlert error={requests.error} />}
        {requests.data && rows.length === 0 && <Empty>No open requests.</Empty>}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th scope="col">Contact code</th><th scope="col">Alias (chosen by the requester)</th><th scope="col">Call-back number</th><th scope="col">Language</th><th scope="col">Requested</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.contactCode}>
                    <th scope="row" className="mono">{r.contactCode}</th>
                    <td>{r.alias}</td>
                    <td>{r.callbackPhone ? <a href={`tel:${r.callbackPhone}`}>{r.callbackPhone}</a> : '–'}</td>
                    <td>{r.language ?? '–'}</td>
                    <td className="small">{formatDate(r.requestedAt)}</td>
                    <td><button type="button" className="secondary small-button" aria-label={`Log contact ${r.contactCode}`} onClick={() => { setErrors([]); setForm(emptyContactForm(r.contactCode)); document.getElementById('contact-code')?.focus(); }}>Log contact</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="small muted">Call-back numbers are purged after 30 days.</p>
      </section>
      <section className="card" aria-labelledby="peer-log">
        <h2 id="peer-log">Contact log</h2>
        <p className="small muted">Pseudonymous: record the contact code only – never a name, employee ID or anything that identifies the crew member.</p>
        <form className="form" onSubmit={submit} noValidate>
          <ErrorSummary errors={errors} />
          <div className="form-grid">
            <IdField id="contact-code" label="Contact code" required error={err('contact-code')}>
              {(d) => <input id="contact-code" className="mono" autoComplete="off" aria-describedby={d} aria-invalid={!!err('contact-code')} value={form.contactCode} onChange={(e) => setForm({ ...form, contactCode: e.target.value })} />}
            </IdField>
            <IdField id="contact-date" label="Date" required error={err('contact-date')}>
              {(d) => <input id="contact-date" type="date" aria-describedby={d} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} />}
            </IdField>
            <IdField id="contact-duration" label="Duration (minutes)" required error={err('contact-duration')}>
              {(d) => <input id="contact-duration" type="number" min={1} max={600} aria-describedby={d} aria-invalid={!!err('contact-duration')} value={form.durationMinutes} onChange={(e) => setForm({ ...form, durationMinutes: e.target.value })} />}
            </IdField>
            <IdField id="contact-category" label="Category" required error={err('contact-category')}>
              {(d) => (
                <select id="contact-category" aria-describedby={d} value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value as ContactForm['category'] })}>
                  <SelectOptions values={PEER_CATEGORIES} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id="contact-outcome" label="Outcome" required error={err('contact-outcome')}>
              {(d) => (
                <select id="contact-outcome" aria-describedby={d} value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value as ContactForm['outcome'] })}>
                  <SelectOptions values={PEER_OUTCOMES} empty="Choose…" />
                </select>
              )}
            </IdField>
          </div>
          <div className="actions"><button type="submit" disabled={action.busy}>Log contact</button></div>
        </form>
        <ActionFeedback error={action.error} message={action.message} />
        {logged.length > 0 && (
          <table>
            <caption>Logged this session</caption>
            <thead><tr><th scope="col">Code</th><th scope="col">Date</th><th scope="col">Category</th><th scope="col">Outcome</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {logged.map((c) => (
                <tr key={c.id}>
                  <th scope="row" className="mono">{c.contactCode}</th>
                  <td>{formatDay(c.date)}</td>
                  <td>{humanise(c.category)}</td>
                  <td>{humanise(c.outcome)}</td>
                  <td>
                    {c.referredAt ? <ToneBadge tone="green">Referred</ToneBadge> : (
                      <button type="button" className="secondary small-button" disabled={action.busy} aria-label={`Refer ${c.contactCode} to the trusted professional`} onClick={() => void refer(c)}>Refer to trusted professional</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

// ---------------------------------------------------------------- quarterly SMS report
function QuarterlyReport({ client, session, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const [quarter, setQuarter] = useState(previousQuarter());
  const [applied, setApplied] = useState(quarter);
  const report = useAsync(() => api.quarterly(applied), onSessionExpired, [applied]);
  const [confirm, setConfirm] = useState(false);
  const action = useAction(onSessionExpired);
  const r = report.data;
  const k = r?.minGroupSize ?? DEFAULT_K;
  const total = r ? smsTotal(r) : undefined;
  const canSend = can(session.roles, 'peerSupportReportSend');

  async function send() {
    const ok = await action.run(async () => {
      const res = await api.sendQuarterly(applied);
      return `Quarterly report ${applied} sent to the safety management system${res?.reportRef ? ` (${res.reportRef})` : ''}.`;
    });
    setConfirm(false);
    if (ok) report.reload();
  }

  return (
    <section aria-labelledby="peer-report">
      <h2 id="peer-report">Quarterly report to the safety management system</h2>
      <p className="small muted">Aggregated contacts by base and category. Cells below {k} are suppressed and never shown.</p>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); if (isQuarter(quarter)) setApplied(quarter); }}>
        <Field label="Quarter" hint="YYYY-Qn">{(id) => <input id={id} value={quarter} pattern="\d{4}-Q[1-4]" onChange={(e) => setQuarter(e.target.value.toUpperCase())} />}</Field>
        <button type="submit" className="secondary" disabled={!isQuarter(quarter)}>Show</button>
      </form>
      {report.loading && !r && <Loading />}
      {!!report.error && <ErrorAlert error={report.error} />}
      {r && (
        <>
          {r.rows.length === 0 ? <Empty>No contacts in {r.quarter}.</Empty> : (
            <div className="table-wrap">
              <table>
                <caption>{r.quarter}{r.sentAt ? ` – sent ${formatDate(r.sentAt)}` : ''}</caption>
                <thead><tr><th scope="col">Base</th><th scope="col">Category</th><th scope="col" className="num">Contacts</th></tr></thead>
                <tbody>
                  {r.rows.map((row) => (
                    <tr key={`${row.base}-${row.category}`} className={row.suppressed ? 'suppressed' : undefined}>
                      <th scope="row">{row.base}</th>
                      <td>{humanise(row.category)}</td>
                      <td className="num">{row.suppressed || typeof row.contacts !== 'number' ? <span title="Suppressed – fewer than the minimum group size">{`<${k}`}<span className="visually-hidden"> (suppressed)</span></span> : row.contacts}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><th scope="row" colSpan={2}>Total</th><td className="num">{total ?? '–'}</td></tr></tfoot>
              </table>
            </div>
          )}
          {canSend && (
            <div className="actions">
              <button type="button" disabled={action.busy || r.rows.length === 0} onClick={() => setConfirm(true)}>{r.sentAt ? 'Send again' : 'Send to SMS'}</button>
            </div>
          )}
        </>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {confirm && (
        <Dialog title={`Send ${applied} report`} onClose={() => setConfirm(false)}>
          <p>The aggregated, suppressed figures are published to the safety management system. No individual data is sent.</p>
          <div className="actions">
            <button type="button" disabled={action.busy} onClick={() => void send()}>Send</button>
            <button type="button" className="secondary" onClick={() => setConfirm(false)}>Cancel</button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
