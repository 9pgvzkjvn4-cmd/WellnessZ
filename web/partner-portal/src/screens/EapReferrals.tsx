import { useState, type FormEvent } from 'react';
import {
  canGiveFeedback,
  CLOSURE_REASONS,
  contactLabel,
  EAP_STATUSES,
  eapStatusLabel,
  emptyFeedback,
  fetchEapReferrals,
  nextStatuses,
  sendFeedback,
  validateFeedback,
  type AttendanceRow,
  type EapReferral,
  type FeedbackForm,
} from '../api/eap';
import { describeError, type FieldErrors } from '../api/errors';
import { FieldError, SelectField } from '../components/FormFields';
import { formatDateTime, idPart, localDate } from '../components/format';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { orgLabel, type ScreenProps } from './types';

const telHref = (phone: string) => `tel:${phone.replace(/[^\d+]/g, '')}`;

export interface EapTableProps {
  referrals: readonly EapReferral[];
  status: string;
  selectedId?: string;
  onStatus: (s: string) => void;
  onSelect: (r: EapReferral) => void;
}

/** Minimal list: reference, employee, contact, sessions authorised, urgency, status. Hook-free. */
export function EapTable({ referrals, status, selectedId, onStatus, onSelect }: EapTableProps) {
  return (
    <>
      <form className="filters" role="search" aria-label="Filter EAP referrals" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="eap-status">{S.eap.status}</label>
          <select id="eap-status" value={status} onChange={(e) => onStatus(e.target.value)}>
            <option value="">{S.eap.allStatuses}</option>
            {EAP_STATUSES.map((s) => (
              <option key={s} value={s}>
                {eapStatusLabel(s)}
              </option>
            ))}
          </select>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        {S.eap.showing(referrals.length, referrals.length)}
      </p>
      {referrals.length === 0 ? (
        <p className="muted">{S.eap.empty}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">{S.eap.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{S.eap.colRef}</th>
                <th scope="col">{S.eap.colEmployee}</th>
                <th scope="col">{S.eap.colContact}</th>
                <th scope="col">{S.eap.colSessions}</th>
                <th scope="col">{S.eap.colUrgency}</th>
                <th scope="col">{S.eap.colStatus}</th>
                <th scope="col">{S.eap.colAction}</th>
              </tr>
            </thead>
            <tbody>
              {referrals.map((r) => (
                <tr key={r.id} aria-selected={selectedId === r.id ? true : undefined}>
                  <th scope="row">
                    <span className="mono">{r.ref}</span>
                    <span className="muted small block">{formatDateTime(r.sentAt)}</span>
                  </th>
                  <td>{r.displayName || S.app.noValue}</td>
                  <td>
                    {r.contactPhone ? <a href={telHref(r.contactPhone)}>{r.contactPhone}</a> : S.app.noValue}
                    {r.preferredContact && <span className="muted small block">{S.eap.prefers(contactLabel(r.preferredContact))}</span>}
                  </td>
                  <td>{r.sessionsAuthorised || S.app.noValue}</td>
                  <td>{r.urgency === 'PRIORITY' ? <StatusBadge status="urgent" label={S.eap.priority} /> : <StatusBadge status="routine" label={S.eap.routine} />}</td>
                  <td>
                    <StatusBadge status={r.status} label={eapStatusLabel(r.status)} />
                  </td>
                  <td>
                    {canGiveFeedback(r) ? (
                      <button type="button" className="secondary small-button" aria-label={S.eap.giveFeedback(r.ref)} aria-expanded={selectedId === r.id} onClick={() => onSelect(r)}>
                        {S.eap.feedbackButton}
                      </button>
                    ) : (
                      <span className="muted">{S.eap.closed}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export interface FeedbackFormViewProps {
  referral: EapReferral;
  form: FeedbackForm;
  errors: FieldErrors;
  busy: boolean;
  now?: Date;
  onChange: (f: FeedbackForm) => void;
  onSubmit: () => void;
  onCancel: () => void;
}

/** Structured feedback: status, attendance dates (attended yes / no) and closure reason – no free text. Hook-free. */
export function FeedbackFormView({ referral: r, form, errors, busy, now, onChange, onSubmit, onCancel }: FeedbackFormViewProps) {
  const p = `eap-${idPart(r.id)}`;
  const set = (patch: Partial<FeedbackForm>) => onChange({ ...form, ...patch });
  const setRow = (i: number, patch: Partial<AttendanceRow>) => set({ attendance: form.attendance.map((a, n) => (n === i ? { ...a, ...patch } : a)) });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit();
  };
  const statusOptions = [{ value: '', label: S.eap.choose }, ...nextStatuses(r.status).map((s) => ({ value: s, label: eapStatusLabel(s) }))];
  const today = localDate(now);
  return (
    <form className="form card" onSubmit={submit} noValidate aria-labelledby={`${p}-title`}>
      <h2 id={`${p}-title`}>{S.eap.feedbackTitle(r.ref)}</h2>
      <p className="banner" role="note">
        <span className="small">{S.eap.whyNoText}</span>
      </p>
      <SelectField id={`${p}-status`} label={S.eap.newStatus} value={form.status} onChange={(v) => set({ status: v as FeedbackForm['status'], closureReason: v === 'CLOSED' ? form.closureReason : '' })} options={statusOptions} required error={errors.status} />
      <fieldset id={`${p}-attendance`} aria-describedby={`${p}-attendance-hint`}>
        <legend>{S.eap.attendance}</legend>
        <p id={`${p}-attendance-hint`} className="muted small">
          {S.eap.attendanceHint}
        </p>
        <FieldError id={`${p}-attendance`} error={errors.attendance} />
        {form.attendance.length === 0 && <p className="muted small">{S.eap.noAttendance}</p>}
        {form.attendance.map((a, i) => {
          const n = i + 1;
          const dateErr = errors[`attendance.${i}.date`];
          const attErr = errors[`attendance.${i}.attended`];
          return (
            <div key={i} className="attendance-row">
              <div className={dateErr ? 'field field-invalid' : 'field'}>
                <label htmlFor={`${p}-date-${i}`}>{S.eap.sessionDate(n)}</label>
                <FieldError id={`${p}-date-${i}`} error={dateErr} />
                <input
                  id={`${p}-date-${i}`}
                  type="date"
                  max={today}
                  value={a.date}
                  aria-invalid={dateErr ? true : undefined}
                  aria-describedby={dateErr ? `${p}-date-${i}-error` : undefined}
                  onChange={(e) => setRow(i, { date: e.target.value })}
                />
              </div>
              <div className={attErr ? 'field field-invalid' : 'field'}>
                <label htmlFor={`${p}-att-${i}`}>{S.eap.attended(n)}</label>
                <FieldError id={`${p}-att-${i}`} error={attErr} />
                <select id={`${p}-att-${i}`} value={a.attended} aria-invalid={attErr ? true : undefined} onChange={(e) => setRow(i, { attended: e.target.value as AttendanceRow['attended'] })}>
                  <option value="">{S.eap.choose}</option>
                  <option value="YES">{S.eap.yes}</option>
                  <option value="NO">{S.eap.no}</option>
                </select>
              </div>
              <button type="button" className="link-button" onClick={() => set({ attendance: form.attendance.filter((_, k) => k !== i) })}>
                {S.eap.removeSession(n)}
              </button>
            </div>
          );
        })}
        <button type="button" className="secondary small-button" onClick={() => set({ attendance: [...form.attendance, { date: '', attended: '' }] })}>
          {S.eap.addSession}
        </button>
      </fieldset>
      {form.status === 'CLOSED' && (
        <SelectField
          id={`${p}-closureReason`}
          label={S.eap.closureReason}
          value={form.closureReason}
          onChange={(v) => set({ closureReason: v as FeedbackForm['closureReason'] })}
          options={[{ value: '', label: S.eap.choose }, ...CLOSURE_REASONS.map((c) => ({ value: c, label: S.eap.closureReasons[c]! }))]}
          required
          error={errors.closureReason}
        />
      )}
      <div className="actions">
        <button type="submit" disabled={busy}>
          {busy ? S.eap.busy : S.eap.submit}
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
          {S.app.cancel}
        </button>
      </div>
    </form>
  );
}

/** `#/eap-referrals` (PARTNER_PROVIDER) – EAP referrals of the own organisation and structured feedback (§3.2). */
export function EapReferrals({ client, me, onSessionExpired }: ScreenProps) {
  const [status, setStatus] = useState('');
  const data = useAsync(() => fetchEapReferrals(client, status), onSessionExpired);
  const [selected, setSelected] = useState<EapReferral>();
  const [form, setForm] = useState<FeedbackForm>(emptyFeedback());
  const [errors, setErrors] = useState<FieldErrors>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const select = (r: EapReferral) => {
    setSelected(r);
    setForm(emptyFeedback(r));
    setErrors({});
    setMessage(undefined);
  };

  async function submit() {
    if (!selected) return;
    const errs = validateFeedback(form, selected);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;
    setBusy(true);
    const res = await sendFeedback(client, selected.id, form);
    setBusy(false);
    if (res.ok) {
      setMessage({ kind: 'success', text: S.eap.saved });
      setSelected(undefined);
      data.reload();
    } else {
      if (res.sessionExpired) onSessionExpired();
      setErrors(res.fieldErrors);
      setMessage({ kind: 'error', text: res.message });
    }
  }

  return (
    <section>
      <PageTitle>{S.eap.title}</PageTitle>
      <p className="lead">{S.eap.lead}</p>
      <ConfidentialityNote>{S.eap.confidentiality(orgLabel(me))}</ConfidentialityNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </div>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && (
        <EapTable
          referrals={data.data}
          status={status}
          selectedId={selected?.id}
          onSelect={select}
          onStatus={(s) => {
            setStatus(s);
            setSelected(undefined);
            data.reload();
          }}
        />
      )}
      {selected && (
        <FeedbackFormView referral={selected} form={form} errors={errors} busy={busy} onChange={setForm} onSubmit={() => void submit()} onCancel={() => setSelected(undefined)} />
      )}
    </section>
  );
}
