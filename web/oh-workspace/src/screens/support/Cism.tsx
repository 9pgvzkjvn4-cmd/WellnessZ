import { useState, type FormEvent } from 'react';
import { CISM_CATEGORIES, CISM_RESPONSES, CISM_SESSION_TYPES, supportApi, type AffectedUpdate, type CismAffected } from '../../api/support';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise, localToIso, toLocalInput } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { cismSla, followUpTone } from '../../domain/support';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { useNow } from './useNow';
import { cismLink } from './util';

/** `#/cism` – critical incident stress management events. SAFETY_MANAGER sees counts only (no names, no drill-down). */
export function CismEvents({ client, session, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const now = useNow(60_000);
  const list = useAsync(() => api.cismEvents(), onSessionExpired);
  const names = can(session.roles, 'cismNames');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ title: '', category: '', occurredAt: toLocalInput(), location: '', dutyRefs: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.title.trim()) errs.push({ field: 'cism-title', message: 'Enter a title (no personal details).' });
    if (!form.category) errs.push({ field: 'cism-category', message: 'Choose the category.' });
    if (!localToIso(form.occurredAt)) errs.push({ field: 'cism-occurred', message: 'Enter when it happened.' });
    if (!form.location.trim()) errs.push({ field: 'cism-location', message: 'Enter the location.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const ev = await api.createCismEvent({
        title: form.title.trim(), category: form.category, occurredAt: localToIso(form.occurredAt)!, location: form.location.trim(),
        dutyRefs: form.dutyRefs.split(',').map((s) => s.trim()).filter(Boolean),
      });
      return `CISM event ${ev.ref} created – ${ev.affected} affected from the rosters.`;
    });
    if (ok) {
      setOpen(false);
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub={names ? 'Affected staff come from the incident and the duty rosters (same duty / proximity group ± 2 h). Offers are due within 72 h; follow-ups at 1 and 4 weeks.' : 'Aggregated counts only – individual responses are confidential.'}>Critical incident stress management</PageTitle>
      <div className="toolbar"><span /><button type="button" onClick={() => { setErrors([]); action.reset(); setOpen(true); }}>Record CISM event</button></div>
      <ActionFeedback error={open ? undefined : action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No CISM events.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} event{rows.length === 1 ? '' : 's'}</caption>
            <thead><tr><th scope="col">Event</th><th scope="col">Category</th><th scope="col">Occurred</th><th scope="col" className="num">Affected</th><th scope="col" className="num">Offered</th><th scope="col" className="num">Accepted</th><th scope="col">72 h offer SLA</th></tr></thead>
            <tbody>
              {rows.map((e) => {
                const sla = cismSla(e, now);
                return (
                  <tr key={e.id}>
                    <th scope="row">{names ? <a href={cismLink(e.id)}>{e.ref}</a> : e.ref} <span className="small muted">{e.title}</span></th>
                    <td>{humanise(e.category)}</td>
                    <td className="small">{formatDate(e.occurredAt)}</td>
                    <td className="num">{e.affected}</td>
                    <td className="num">{e.offered}</td>
                    <td className="num">{e.accepted}</td>
                    <td><ToneBadge tone={sla.tone} title={formatDate(e.offerDueBy)}>{sla.label}</ToneBadge></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {open && (
        <Dialog title="Record CISM event" onClose={() => setOpen(false)}>
          <form className="form" onSubmit={create} noValidate>
            <ErrorSummary errors={errors} />
            <IdField id="cism-title" label="Title" required error={errorFor(errors, 'cism-title')} hint="Operational description only – no names or health details.">{(d) => <input id="cism-title" aria-describedby={d} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />}</IdField>
            <IdField id="cism-category" label="Category" required error={errorFor(errors, 'cism-category')}>
              {(d) => <select id="cism-category" aria-describedby={d} value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}><SelectOptions values={CISM_CATEGORIES} empty="Choose…" /></select>}
            </IdField>
            <IdField id="cism-occurred" label="Occurred at" required error={errorFor(errors, 'cism-occurred')}>{(d) => <input id="cism-occurred" type="datetime-local" aria-describedby={d} value={form.occurredAt} onChange={(e) => setForm({ ...form, occurredAt: e.target.value })} />}</IdField>
            <IdField id="cism-location" label="Location" required error={errorFor(errors, 'cism-location')}>{(d) => <input id="cism-location" aria-describedby={d} value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />}</IdField>
            <IdField id="cism-duties" label="Duty references" hint="Comma-separated flight / shift references, e.g. WZ101, RAMP-EARLY – affected staff are taken from these rosters.">{(d) => <input id="cism-duties" aria-describedby={d} value={form.dutyRefs} onChange={(e) => setForm({ ...form, dutyRefs: e.target.value })} />}</IdField>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Create event</button>
              <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

/** `#/cism/{id}` – affected list, offers, responses, sessions and 1 / 4-week follow-ups (PSYCH_CLINICIAN, OH_CLINICIAN). */
export function CismEventScreen({ client, onSessionExpired, eventId }: ScreenProps & { eventId: string }) {
  const api = supportApi(client);
  const now = useNow(60_000);
  const ev = useAsync(() => api.cismEvent(eventId), onSessionExpired, [eventId]);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<CismAffected>();
  const [upd, setUpd] = useState<{ response: string; sessionType: string; attendedAt: string; followUp1wDone: boolean; followUp4wDone: boolean; referToSupport: boolean }>();
  const e = ev.data;
  const affected = e?.affected ?? [];
  const notOffered = affected.filter((a) => !a.offerSentAt).length;

  async function offers() {
    if (await action.run(async () => { await api.sendOffers(eventId); return `Support offers sent to ${notOffered} staff (content-free notification).`; })) ev.reload();
  }
  function edit(a: CismAffected) {
    action.reset();
    setEditing(a);
    setUpd({ response: a.response ?? '', sessionType: a.sessionType ?? '', attendedAt: a.attendedAt ? toLocalInput(new Date(a.attendedAt)) : '', followUp1wDone: !!a.followUp1w?.done, followUp4wDone: !!a.followUp4w?.done, referToSupport: false });
  }
  async function save(ev2: FormEvent) {
    ev2.preventDefault();
    if (!editing || !upd) return;
    const body: AffectedUpdate = {
      ...(upd.response ? { response: upd.response } : {}),
      ...(upd.sessionType ? { sessionType: upd.sessionType } : {}),
      ...(localToIso(upd.attendedAt) ? { attendedAt: localToIso(upd.attendedAt) } : {}),
      followUp1wDone: upd.followUp1wDone,
      followUp4wDone: upd.followUp4wDone,
      ...(upd.referToSupport ? { referToSupport: true } : {}),
    };
    if (await action.run(async () => { await api.updateAffected(eventId, editing.employeeId, body); return `${editing.displayName} updated${upd.referToSupport ? ' and referred to a confidential support case' : ''}.`; })) {
      setEditing(undefined);
      ev.reload();
    }
  }

  return (
    <section>
      <p className="breadcrumb small"><a href="#/cism">CISM events</a> <span aria-hidden="true">›</span> {e?.ref ?? eventId}</p>
      <PageTitle sub={e ? `${humanise(e.category)} · ${formatDate(e.occurredAt)}${e.location ? ` · ${e.location}` : ''}` : undefined}>{e ? `${e.ref} – ${e.title}` : 'CISM event'}</PageTitle>
      {ev.loading && !e && <Loading />}
      {!!ev.error && <ErrorAlert error={ev.error} />}
      {e && (
        <>
          <Dl
            items={[
              ['Affected', affected.length],
              ['Offered', affected.filter((a) => a.offerSentAt).length],
              ['Accepted', affected.filter((a) => a.response === 'ACCEPTED').length],
              ['72 h offer SLA', (() => { const s = cismSla({ ...e, affected: affected.length, offered: affected.filter((a) => a.offerSentAt).length }, now); return <ToneBadge tone={s.tone}>{s.label}</ToneBadge>; })()],
              ['Duty references', (e.dutyRefs ?? []).join(', ') || '–'],
            ]}
          />
          <div className="actions">
            <button type="button" disabled={action.busy || notOffered === 0} onClick={() => void offers()}>{notOffered ? `Send offers to ${notOffered} not yet offered` : 'All offers sent'}</button>
          </div>
          <ActionFeedback error={editing ? undefined : action.error} message={action.message} />
          {affected.length === 0 ? <Empty>No affected staff found on the rosters.</Empty> : (
            <div className="table-wrap">
              <table>
                <caption>Affected staff</caption>
                <thead><tr><th scope="col">Name</th><th scope="col">Duty role</th><th scope="col">Offer sent</th><th scope="col">Response</th><th scope="col">Session</th><th scope="col">1-week follow-up</th><th scope="col">4-week follow-up</th><th scope="col">Referred</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
                <tbody>
                  {affected.map((a) => {
                    const f1 = followUpTone(a.followUp1w);
                    const f4 = followUpTone(a.followUp4w);
                    return (
                      <tr key={a.employeeId}>
                        <th scope="row">{a.displayName} <span className="small muted">{a.employeeId}</span></th>
                        <td>{a.dutyRole ?? '–'}</td>
                        <td className="small">{a.offerSentAt ? formatDate(a.offerSentAt) : <ToneBadge tone="amber">Not yet</ToneBadge>}</td>
                        <td>{a.response ? <ToneBadge tone={a.response === 'ACCEPTED' ? 'green' : 'muted'}>{humanise(a.response)}</ToneBadge> : '–'}</td>
                        <td className="small">{a.sessionType ? `${humanise(a.sessionType)}${a.attendedAt ? ` · ${formatDate(a.attendedAt)}` : ''}` : '–'}</td>
                        <td><ToneBadge tone={f1.tone}>{f1.label}</ToneBadge></td>
                        <td><ToneBadge tone={f4.tone}>{f4.label}</ToneBadge></td>
                        <td>{a.referred ? 'Yes' : 'No'}</td>
                        <td><button type="button" className="secondary small-button" aria-label={`Update ${a.displayName}`} onClick={() => edit(a)}>Update</button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      {editing && upd && (
        <Dialog title={`Update ${editing.displayName}`} onClose={() => setEditing(undefined)}>
          <form className="form" onSubmit={save}>
            <Field label="Response">{(id) => <select id={id} value={upd.response} onChange={(x) => setUpd({ ...upd, response: x.target.value })}><SelectOptions values={CISM_RESPONSES} empty="No response yet" /></select>}</Field>
            <Field label="Session type">{(id) => <select id={id} value={upd.sessionType} onChange={(x) => setUpd({ ...upd, sessionType: x.target.value })}><SelectOptions values={CISM_SESSION_TYPES} empty="None" /></select>}</Field>
            <Field label="Attended at">{(id) => <input id={id} type="datetime-local" value={upd.attendedAt} onChange={(x) => setUpd({ ...upd, attendedAt: x.target.value })} />}</Field>
            <label className="inline-row"><input type="checkbox" checked={upd.followUp1wDone} onChange={(x) => setUpd({ ...upd, followUp1wDone: x.target.checked })} /> 1-week follow-up done</label>
            <label className="inline-row"><input type="checkbox" checked={upd.followUp4wDone} onChange={(x) => setUpd({ ...upd, followUp4wDone: x.target.checked })} /> 4-week follow-up done</label>
            {!editing.referred && <label className="inline-row"><input type="checkbox" checked={upd.referToSupport} onChange={(x) => setUpd({ ...upd, referToSupport: x.target.checked })} /> Refer to a confidential support case</label>}
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
