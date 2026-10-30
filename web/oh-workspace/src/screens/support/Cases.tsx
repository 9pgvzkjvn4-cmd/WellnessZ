import { useState, type FormEvent } from 'react';
import {
  CONCERNS,
  EAP_PARTNERS,
  EAP_URGENCIES,
  INSTRUMENTS,
  RISKS,
  supportApi,
  SUPPORT_STATUSES,
  URGENT_OUTCOMES,
  type Concern,
  type Instrument,
  type Risk,
  type SupportCaseDetail,
  type UrgentOutcome,
} from '../../api/support';
import { can } from '../../auth/roles';
import { ConsentPicker } from '../../components/ConsentPicker';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import {
  bandFor,
  emptyEapForm,
  instrumentTitle,
  isUrgentScreen,
  itemsFor,
  SCREEN_OPTIONS,
  screenScore,
  serverFieldError,
  sharingLabel,
  validateEap,
  validateScreen,
  type EapForm,
} from '../../domain/support';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { BandBadge, CountdownBadge, RestrictedBanner, RiskBadge } from './common';
import { useNow } from './useNow';
import { supportCaseLink } from './util';

/** `#/support/cases` – confidential support caseload of the named mental-health clinicians (E09-F01). */
export function SupportCases({ client, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const [filter, setFilter] = useState({ status: '', risk: '' });
  const list = useAsync(() => api.cases(filter), onSessionExpired, [filter]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<{ employeeId: string; concern: Concern | ''; risk: Risk | ''; note: string }>({ employeeId: '', concern: '', risk: '', note: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.employeeId.trim()) errs.push({ field: 'new-employee', message: 'Enter the employee ID.' });
    if (!form.concern) errs.push({ field: 'new-concern', message: 'Choose the main concern.' });
    if (!form.risk) errs.push({ field: 'new-risk', message: 'Choose the risk level.' });
    setErrors(errs);
    if (errs.length) return;
    let createdId: string | undefined;
    const ok = await action.run(async () => {
      const c = await api.openCase({ employeeId: form.employeeId.trim(), concern: form.concern as Concern, risk: form.risk as Risk, ...(form.note.trim() ? { note: form.note.trim() } : {}) });
      createdId = c.id;
      return `Support case ${c.ref} opened.`;
    });
    if (ok) {
      setOpen(false);
      if (createdId) window.location.hash = supportCaseLink(createdId);
      else list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="Confidential support cases – self-referrals, clinician-opened, peer, CISM and programme referrals.">Support cases</PageTitle>
      <RestrictedBanner />
      <div className="toolbar">
        <form className="filters inline-form" onSubmit={(e) => e.preventDefault()} aria-label="Filter support cases">
          <Field label="Status">
            {(id) => (
              <select id={id} value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
                <SelectOptions values={SUPPORT_STATUSES} empty="Any status" />
              </select>
            )}
          </Field>
          <Field label="Risk">
            {(id) => (
              <select id={id} value={filter.risk} onChange={(e) => setFilter({ ...filter, risk: e.target.value })}>
                <SelectOptions values={RISKS} empty="Any risk" />
              </select>
            )}
          </Field>
        </form>
        <button type="button" onClick={() => { setErrors([]); action.reset(); setOpen(true); }}>Open support case</button>
      </div>
      <ActionFeedback error={open ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No support cases match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} support case{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr><th scope="col">Case</th><th scope="col">Employee</th><th scope="col">Risk</th><th scope="col">Concern</th><th scope="col">Status</th><th scope="col">Source</th><th scope="col">Opened</th><th scope="col">Last contact</th><th scope="col">Manager sees</th></tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <th scope="row"><a href={supportCaseLink(c.id)}>{c.ref}</a></th>
                  <td>{c.displayName} <span className="small muted">{c.employeeId}</span></td>
                  <td><RiskBadge risk={c.risk} /></td>
                  <td>{humanise(c.concern)}</td>
                  <td>
                    <StatusBadge status={c.status} />
                    {c.eapReferral && <span className="small muted"> · EAP {c.eapReferral.ref}</span>}
                    {c.relief && <span className="small muted"> · relieved from duties</span>}
                  </td>
                  <td>{humanise(c.source)}</td>
                  <td className="small">{formatDate(c.openedAt)}</td>
                  <td className="small">{formatDate(c.lastContactAt)}</td>
                  <td className="small">{sharingLabel(c.sharing)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {open && (
        <Dialog title="Open support case" onClose={() => setOpen(false)}>
          <form className="form" onSubmit={create} noValidate>
            <ErrorSummary errors={errors} />
            <IdField id="new-employee" label="Employee ID" required error={errorFor(errors, 'new-employee')}>
              {(d) => <input id="new-employee" value={form.employeeId} aria-describedby={d} aria-invalid={!!errorFor(errors, 'new-employee')} onChange={(e) => setForm({ ...form, employeeId: e.target.value })} />}
            </IdField>
            <IdField id="new-concern" label="Main concern" required error={errorFor(errors, 'new-concern')}>
              {(d) => (
                <select id="new-concern" value={form.concern} aria-describedby={d} onChange={(e) => setForm({ ...form, concern: e.target.value as Concern })}>
                  <SelectOptions values={CONCERNS} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id="new-risk" label="Risk" required error={errorFor(errors, 'new-risk')}>
              {(d) => (
                <select id="new-risk" value={form.risk} aria-describedby={d} onChange={(e) => setForm({ ...form, risk: e.target.value as Risk })}>
                  <SelectOptions values={RISKS} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id="new-note" label="Opening note" hint="Encrypted; visible to named clinicians only.">
              {(d) => <textarea id="new-note" rows={3} maxLength={4000} aria-describedby={d} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />}
            </IdField>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Open case</button>
              <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

/** `#/support/cases/{id}` – notes, screens, urgent contacts, EAP referral, relief from duties, sharing choice, close. */
export function SupportCaseScreen(props: ScreenProps & { caseId: string }) {
  const { client, onSessionExpired, caseId } = props;
  const api = supportApi(client);
  const detail = useAsync(() => api.supportCase(caseId), onSessionExpired, [caseId]);
  const c = detail.data;
  return (
    <section>
      <p className="breadcrumb small">
        <a href="#/support/cases">Support cases</a> <span aria-hidden="true">›</span> {c?.ref ?? caseId}
      </p>
      <PageTitle sub={c ? `${c.displayName} (${c.employeeId}) · ${humanise(c.concern)} · ${humanise(c.source)}` : undefined}>{c ? `Support case ${c.ref}` : 'Support case'}</PageTitle>
      <RestrictedBanner />
      {detail.loading && !c && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {c && <CaseBody {...props} c={c} reload={detail.reload} />}
    </section>
  );
}

function CaseBody({ client, session, onSessionExpired, c, reload }: ScreenProps & { c: SupportCaseDetail; reload: () => void }) {
  const closed = c.status === 'CLOSED';
  return (
    <>
      <Dl
        items={[
          ['Status', <StatusBadge status={c.status} />],
          ['Risk', <RiskBadge risk={c.risk} />],
          ['Opened', formatDate(c.openedAt)],
          ['Last contact', formatDate(c.lastContactAt)],
          ['Manager sharing (employee’s choice, revocable)', sharingLabel(c.sharing)],
          ...(closed ? ([['Closed', `${formatDate(c.closedAt)}${c.closeReason ? ` – ${c.closeReason}` : ''}`]] as [string, string][]) : []),
        ]}
      />
      <div className="grid-2">
        <NotesPanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} closed={closed} />
        <ScreensPanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} closed={closed} />
        <UrgentContactPanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} closed={closed} />
        <EapPanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} closed={closed} />
        {can(session.roles, 'supportRelief') && <ReliefPanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} closed={closed} />}
        {!closed && <ClosePanel client={client} onSessionExpired={onSessionExpired} c={c} reload={reload} />}
      </div>
      {(c.timeline ?? []).length > 0 && (
        <section aria-labelledby="sup-timeline">
          <h2 id="sup-timeline">Timeline</h2>
          <ol className="timeline">
            {(c.timeline ?? []).map((t, i) => (
              <li key={`${t.at}-${i}`}>
                <span className="small muted">{formatDate(t.at)}</span> {t.summary}
                {t.actor && <span className="small muted"> – {t.actor}</span>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}

interface PanelProps {
  client: ScreenProps['client'];
  onSessionExpired: () => void;
  c: SupportCaseDetail;
  reload: () => void;
  closed?: boolean;
}

function NotesPanel({ client, onSessionExpired, c, reload, closed }: PanelProps) {
  const [text, setText] = useState('');
  const action = useAction(onSessionExpired);
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    if (await action.run(async () => { await supportApi(client).addNote(c.id, text.trim()); return 'Note added.'; })) {
      setText('');
      reload();
    }
  }
  const notes = c.notes ?? [];
  return (
    <section className="card" aria-labelledby="sup-notes">
      <h2 id="sup-notes">Notes</h2>
      {notes.length === 0 ? <Empty>No notes yet.</Empty> : (
        <ul className="history-list">
          {notes.map((n, i) => (
            <li key={n.id ?? i}>
              <p className="prewrap">{n.text}</p>
              <span className="small muted">{formatDate(n.createdAt)}{n.author ? ` – ${n.author}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
      {!closed && (
        <form className="form" onSubmit={add}>
          <Field label="Add note" hint="Encrypted at rest; never shown outside the named clinicians.">
            {(id) => <textarea id={id} rows={3} maxLength={8000} value={text} onChange={(e) => setText(e.target.value)} />}
          </Field>
          <div className="actions"><button type="submit" disabled={action.busy || !text.trim()}>Add note</button></div>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function ScreensPanel({ client, onSessionExpired, c, reload, closed }: PanelProps) {
  const [instrument, setInstrument] = useState<Instrument>('PHQ9');
  const [answers, setAnswers] = useState<(number | undefined)[]>([]);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [open, setOpen] = useState(false);
  const action = useAction(onSessionExpired);
  const items = itemsFor(instrument);
  const complete = items.every((_, i) => typeof answers[i] === 'number');
  const score = screenScore(answers);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateScreen(instrument, answers);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const r = await supportApi(client).administerScreen(c.id, instrument, items.map((_, i) => answers[i] ?? 0));
      return `${instrumentTitle(instrument)} recorded: score ${r.score}, ${humanise(r.band).toLowerCase()}${r.urgent ? ' – URGENT: same-day call-back task created' : ''}.`;
    });
    if (ok) {
      setOpen(false);
      setAnswers([]);
      reload();
    }
  }

  const screens = c.screens ?? [];
  return (
    <section className="card" aria-labelledby="sup-screens">
      <h2 id="sup-screens">Screening history</h2>
      {screens.length === 0 ? <Empty>No screens recorded.</Empty> : (
        <table>
          <thead><tr><th scope="col">Date</th><th scope="col">Instrument</th><th scope="col" className="num">Score</th><th scope="col">Band</th><th scope="col">Source</th></tr></thead>
          <tbody>
            {screens.map((s, i) => (
              <tr key={s.id ?? i}>
                <td className="small">{formatDate(s.completedAt)}</td>
                <td>{instrumentTitle(s.instrument)}</td>
                <td className="num">{s.score}</td>
                <td>
                  <BandBadge instrument={s.instrument} band={s.band} />
                  {s.urgent && <> <ToneBadge tone="red">Urgent</ToneBadge></>}
                </td>
                <td className="small">{s.administeredBy ?? humanise(s.source)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ActionFeedback error={open ? undefined : action.error} message={action.message} />
      {!closed && !open && <button type="button" className="secondary" onClick={() => { action.reset(); setErrors([]); setOpen(true); }}>Administer screen</button>}
      {open && (
        <form className="form" onSubmit={submit} noValidate aria-label="Administer screen">
          <ErrorSummary errors={errors} />
          <Field label="Instrument">
            {(id) => (
              <select id={id} value={instrument} onChange={(e) => { setInstrument(e.target.value as Instrument); setAnswers([]); setErrors([]); }}>
                {INSTRUMENTS.map((i) => <option key={i} value={i}>{instrumentTitle(i)}</option>)}
              </select>
            )}
          </Field>
          <p className="small muted">Over the last 2 weeks, how often has the employee been bothered by the following?</p>
          {items.map((q, i) => (
            <IdField key={`${instrument}-${i}`} id={`screen-item-${i}`} label={`${i + 1}. ${q}`} required error={errorFor(errors, `screen-item-${i}`)}>
              {(d) => (
                <select id={`screen-item-${i}`} aria-describedby={d} value={answers[i] ?? ''} onChange={(e) => { const next = [...answers]; next[i] = e.target.value === '' ? undefined : Number(e.target.value); setAnswers(next); }}>
                  <option value="">Choose…</option>
                  {SCREEN_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.value} – {o.label}</option>)}
                </select>
              )}
            </IdField>
          ))}
          <p aria-live="polite">
            Score so far: <strong>{score}</strong>
            {complete && <> · <BandBadge instrument={instrument} band={bandFor(instrument, score)} /></>}
          </p>
          {isUrgentScreen(instrument, answers) && (
            <Alert kind="warning">Urgent rule met ({instrument === 'PHQ9' && (answers[8] ?? 0) > 0 ? 'item 9 above zero' : 'severe band'}) – follow the urgent-risk protocol today.</Alert>
          )}
          <div className="actions">
            <button type="submit" disabled={action.busy}>Record screen</button>
            <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
          </div>
          <ActionFeedback error={action.error} />
        </form>
      )}
    </section>
  );
}

function UrgentContactPanel({ client, onSessionExpired, c, reload, closed }: PanelProps) {
  const [outcome, setOutcome] = useState<UrgentOutcome | ''>('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!outcome) errs.push({ field: 'uc-outcome', message: 'Choose the contact outcome.' });
    if (!note.trim()) errs.push({ field: 'uc-note', message: 'Record what happened.' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await supportApi(client).urgentContact(c.id, outcome as UrgentOutcome, note.trim()); return `Outcome recorded: ${humanise(outcome)}.`; })) {
      setOutcome('');
      setNote('');
      reload();
    }
  }
  const contacts = c.contacts ?? [];
  return (
    <section className="card" aria-labelledby="sup-urgent">
      <h2 id="sup-urgent">Urgent contact outcomes</h2>
      {contacts.length === 0 ? <Empty>No urgent contacts recorded.</Empty> : (
        <ul className="history-list">
          {contacts.map((x, i) => (
            <li key={x.id ?? i}>
              <ToneBadge tone={x.outcome === 'REACHED_SAFE' ? 'green' : x.outcome === 'NOT_REACHED_RETRY' ? 'amber' : 'red'}>{humanise(x.outcome)}</ToneBadge>{' '}
              <span className="small muted">{formatDate(x.at)}{x.by ? ` – ${x.by}` : ''}</span>
              {x.note && <p className="prewrap small">{x.note}</p>}
            </li>
          ))}
        </ul>
      )}
      {!closed && (
        <form className="form" onSubmit={submit} noValidate aria-label="Record urgent contact">
          <ErrorSummary errors={errors} />
          <IdField id="uc-outcome" label="Outcome" required error={errorFor(errors, 'uc-outcome')}>
            {(d) => (
              <select id="uc-outcome" aria-describedby={d} value={outcome} onChange={(e) => setOutcome(e.target.value as UrgentOutcome)}>
                <SelectOptions values={URGENT_OUTCOMES} empty="Choose…" />
              </select>
            )}
          </IdField>
          <IdField id="uc-note" label="Note" required error={errorFor(errors, 'uc-note')}>
            {(d) => <textarea id="uc-note" rows={2} maxLength={4000} aria-describedby={d} value={note} onChange={(e) => setNote(e.target.value)} />}
          </IdField>
          <div className="actions"><button type="submit" disabled={action.busy}>Record outcome</button></div>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

const EAP_FIELDS = { partnerCode: 'eap-partner', sessionsAuthorised: 'eap-sessions', urgency: 'eap-urgency', consentId: 'eap-consent' };

function EapPanel({ client, onSessionExpired, c, reload, closed }: PanelProps) {
  const api = supportApi(client);
  const now = useNow();
  const [form, setForm] = useState<EapForm>(emptyEapForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [open, setOpen] = useState(false);
  const consents = useAsync(() => (open ? api.consents(c.caseId ?? c.id) : Promise.resolve([])), onSessionExpired, [open, c.id]);
  const action = useAction(onSessionExpired);
  const ref = c.eapReferral;

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateEap(form);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        const r = await api.eapReferral(c.id, { partnerCode: form.partnerCode, sessionsAuthorised: Number(form.sessionsAuthorised), urgency: form.urgency, consentId: form.consentId });
        return `EAP referral ${r.ref} sent – first appointment due by ${formatDay(r.firstAppointmentDueBy)}.`;
      } catch (err) {
        failure = err;
        throw err;
      }
    });
    if (ok) {
      setOpen(false);
      reload();
    } else {
      const fe = serverFieldError(failure, EAP_FIELDS);
      if (fe) setErrors([fe]);
    }
  }

  return (
    <section className="card" aria-labelledby="sup-eap">
      <h2 id="sup-eap">EAP referral</h2>
      {ref ? (
        <Dl
          items={[
            ['Reference', ref.ref],
            ['Status', <StatusBadge status={ref.status} />],
            ['Partner', ref.partnerCode],
            ['Sessions authorised', ref.sessionsAuthorised],
            ['First appointment due', ref.firstAppointmentDueBy ? <>{formatDay(ref.firstAppointmentDueBy)} <CountdownBadge dueAt={ref.firstAppointmentDueBy} now={now} amberWithinMin={2 * 24 * 60} redWithinMin={0} /></> : '–'],
          ]}
        />
      ) : (
        <Empty>No EAP referral.</Empty>
      )}
      <ActionFeedback error={open ? undefined : action.error} message={action.message} />
      {!ref && !closed && !open && <button type="button" className="secondary" onClick={() => { setErrors([]); action.reset(); setOpen(true); }}>Refer to EAP</button>}
      {open && (
        <form className="form" onSubmit={submit} noValidate aria-label="EAP referral">
          <ErrorSummary errors={errors} />
          <IdField id="eap-partner" label="EAP partner" required error={errorFor(errors, 'eap-partner')}>
            {(d) => (
              <select id="eap-partner" aria-describedby={d} value={form.partnerCode} onChange={(e) => setForm({ ...form, partnerCode: e.target.value })}>
                <SelectOptions values={EAP_PARTNERS} empty="Choose…" />
              </select>
            )}
          </IdField>
          <IdField id="eap-sessions" label="Sessions authorised (1–12)" required error={errorFor(errors, 'eap-sessions')}>
            {(d) => <input id="eap-sessions" type="number" min={1} max={12} aria-describedby={d} aria-invalid={!!errorFor(errors, 'eap-sessions')} value={form.sessionsAuthorised} onChange={(e) => setForm({ ...form, sessionsAuthorised: e.target.value })} />}
          </IdField>
          <IdField id="eap-urgency" label="Urgency" required>
            {(d) => (
              <select id="eap-urgency" aria-describedby={d} value={form.urgency} onChange={(e) => setForm({ ...form, urgency: e.target.value as EapForm['urgency'] })}>
                <SelectOptions values={EAP_URGENCIES} />
              </select>
            )}
          </IdField>
          <ConsentPicker
            id="eap-consent"
            legend="Employee consent to share with the partner"
            hint="Partner-sharing consent (TREATMENT_SHARING_PARTNER). The partner receives contact details only – no clinical content."
            consents={consents}
            value={form.consentId}
            onChange={(consentId) => setForm({ ...form, consentId })}
            error={errorFor(errors, 'eap-consent')}
          />
          <div className="actions">
            <button type="submit" disabled={action.busy}>Send referral</button>
            <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
          </div>
          <ActionFeedback error={errors.length ? undefined : action.error} />
        </form>
      )}
    </section>
  );
}

function ReliefPanel({ client, onSessionExpired, c, reload, closed }: PanelProps) {
  const [form, setForm] = useState({ from: todayIso(), expectedUntil: '' });
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!form.from) return;
    if (await action.run(async () => {
      await supportApi(client).startRelief({ employeeId: c.employeeId, from: form.from, ...(form.expectedUntil ? { expectedUntil: form.expectedUntil } : {}) });
      return 'Temporary relief from duties recorded – the roster receives status only.';
    })) reload();
  }
  return (
    <section className="card" aria-labelledby="sup-relief">
      <h2 id="sup-relief">Relief from duties</h2>
      {c.relief ? (
        <Dl items={[['Relieved from', formatDay(c.relief.from)], ['Expected until', formatDay(c.relief.expectedUntil)], ['Status', <StatusBadge status={c.relief.status ?? 'ACTIVE'} />], ['Relief reference', <span className="mono">{c.relief.id}</span>]]} />
      ) : (
        <p className="small muted">Temporarily relieves the employee from safety-sensitive duties. Rostering and the manager see the status only (unfit / pending) – never the reason. Return decisions are made by the AME.</p>
      )}
      {!c.relief && !closed && (
        <form className="form inline-form" onSubmit={submit}>
          <Field label="From" required>{(id) => <input id={id} type="date" required value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} />}</Field>
          <Field label="Expected until">{(id) => <input id={id} type="date" min={form.from} value={form.expectedUntil} onChange={(e) => setForm({ ...form, expectedUntil: e.target.value })} />}</Field>
          <button type="submit" className="secondary" disabled={action.busy}>Relieve from duties</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function ClosePanel({ client, onSessionExpired, c, reload }: PanelProps) {
  const [reason, setReason] = useState('');
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!reason.trim()) return;
    if (await action.run(async () => { await supportApi(client).closeCase(c.id, reason.trim()); return `${c.ref} closed.`; })) reload();
  }
  return (
    <section className="card" aria-labelledby="sup-close">
      <h2 id="sup-close">Close case</h2>
      <form className="form" onSubmit={submit}>
        <Field label="Reason" required>{(id) => <input id={id} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />}</Field>
        <div className="actions"><button type="submit" className="secondary" disabled={action.busy || !reason.trim()}>Close case</button></div>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}
