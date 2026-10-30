import { useState, type FormEvent, type ReactNode } from 'react';
import type { EmployeeLookup, FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import {
  buildForCause,
  countDutyStatus,
  DESCRIPTION_MAX,
  DUTY_STATUS_LABELS,
  DUTY_STATUS_PATH,
  DUTY_STATUSES,
  emptyForCauseForm,
  filterDutyStatus,
  FOR_CAUSE_FIELDS,
  FOR_CAUSE_PATH,
  LOCATION_MAX,
  OBSERVATION_LABELS,
  OBSERVATIONS,
  OBSERVER_MAX,
  REQUEST_PHASE_LABELS,
  toDutyStatusRows,
  toForCauseRows,
  toggleObservation,
  toLocalInput,
  toRequestRef,
  validateForCause,
  type DutyStatus,
  type DutyStatusRow,
  type ForCauseForm,
  type ForCauseRow,
} from '../api/testing';
import { testingSections } from '../auth/roles';
import { EmployeeSearch } from '../components/EmployeeSearch';
import { FieldError, InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { FOR_CAUSE_IDS } from '../components/ids';
import { describeError, formatDate, formatDay } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface ForCauseFormViewProps {
  form: ForCauseForm;
  errors: FieldErrors;
  busy: boolean;
  /** Latest allowed observation time (`datetime-local`), defaults to now. */
  maxObservedAt?: string;
  /** The employee picker (EmployeeSearch) – passed in so this view stays hook-free. */
  employeePicker: ReactNode;
  onChange: (f: ForCauseForm) => void;
  onSubmit: () => void;
}

/** E10-F02 for-cause request with the observation checklist (≥ 1). Hook-free. */
export function ForCauseFormView({ form, errors, busy, maxObservedAt, employeePicker, onChange, onSubmit }: ForCauseFormViewProps) {
  const set = (patch: Partial<ForCauseForm>) => onChange({ ...form, ...patch });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit();
  };
  return (
    <form className="form" onSubmit={submit} noValidate aria-labelledby="fc-title">
      <h2 id="fc-title">Request a for-cause test</h2>
      <div className="banner banner-warning" role="note">
        <strong>Where this goes</strong>
        <span className="small">
          Your observations go to the drug &amp; alcohol testing programme only (programme manager and medical review officer). They
          are not added to the employee&apos;s HR file or team cases, and you will see the status of the request only – never a
          result. Describe what you saw and heard; do not guess at causes or medical conditions.
        </span>
      </div>
      {employeePicker}
      <InputField id={FOR_CAUSE_IDS.observedAt} label="Observed at" type="datetime-local" max={maxObservedAt ?? toLocalInput(new Date())} value={form.observedAt} onChange={(v) => set({ observedAt: v })} required error={errors.observedAt} hint="Date and time of what you observed (your local time)." />
      <fieldset id={FOR_CAUSE_IDS.observations} aria-invalid={errors.observations ? true : undefined} aria-describedby={errors.observations ? `${FOR_CAUSE_IDS.observations}-error` : undefined}>
        <legend>
          What did you observe?<span className="req" aria-hidden="true"> *</span>
        </legend>
        <FieldError id={FOR_CAUSE_IDS.observations} error={errors.observations} />
        {OBSERVATIONS.map((o) => (
          <label key={o} className="checkbox" htmlFor={`fc-obs-${o}`}>
            <input id={`fc-obs-${o}`} type="checkbox" checked={form.observations.includes(o)} onChange={() => set({ observations: toggleObservation(form.observations, o) })} /> {OBSERVATION_LABELS[o]}
          </label>
        ))}
      </fieldset>
      <InputField id={FOR_CAUSE_IDS.location} label="Location" value={form.location} onChange={(v) => set({ location: v })} required maxLength={LOCATION_MAX} error={errors.location} hint="For example stand, gate, hangar or office." />
      <TextAreaField
        id={FOR_CAUSE_IDS.description}
        label="Description of the observation"
        value={form.description}
        onChange={(v) => set({ description: v })}
        max={DESCRIPTION_MAX}
        required
        rows={5}
        error={errors.description}
        hint="Seen only by the testing programme – stored encrypted."
      />
      <InputField id={FOR_CAUSE_IDS.secondObserverName} label="Second observer" value={form.secondObserverName} onChange={(v) => set({ secondObserverName: v })} required maxLength={OBSERVER_MAX} error={errors.secondObserverName} hint="Name of the second person (for example another supervisor) who observed the behaviour." />
      <button type="submit" disabled={busy}>
        {busy ? 'Sending…' : 'Send request to the testing programme'}
      </button>
    </form>
  );
}

/** Own requests – reference, employee, neutral phase, date. Hook-free. */
export function MyRequestsTable({ rows }: { rows: readonly ForCauseRow[] }) {
  if (rows.length === 0) return <p className="muted">You have not raised any for-cause requests.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">My for-cause requests (status only)</caption>
        <thead>
          <tr>
            <th scope="col">Reference</th>
            <th scope="col">Employee</th>
            <th scope="col">Status</th>
            <th scope="col">Requested</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <th scope="row" className="mono">
                {r.ref ?? '–'}
              </th>
              <td>{r.displayName}</td>
              <td>
                <StatusBadge status={`req-${r.phase}`} label={REQUEST_PHASE_LABELS[r.phase]} />
              </td>
              <td>{formatDate(r.requestedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export interface DutyStatusViewProps {
  rows: readonly DutyStatusRow[];
  q: string;
  status: '' | DutyStatus;
  onQ: (v: string) => void;
  onStatus: (v: '' | DutyStatus) => void;
}

/** E10-F04 duty-status list for HR / crew scheduling: status and since – no reasons or results. Hook-free. */
export function DutyStatusView({ rows, q, status, onQ, onStatus }: DutyStatusViewProps) {
  const shown = filterDutyStatus(rows, q, status);
  const counts = countDutyStatus(rows);
  return (
    <>
      <dl className="stat-row" aria-label="Duty status summary">
        {DUTY_STATUSES.map((st) => (
          <div key={st}>
            <dt>{DUTY_STATUS_LABELS[st]}</dt>
            <dd>{counts[st]}</dd>
          </div>
        ))}
      </dl>
      <form className="filters" role="search" aria-label="Filter duty status" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="ds-q">Employee</label>
          <input id="ds-q" type="search" autoComplete="off" value={q} onChange={(e) => onQ(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="ds-status">Duty status</label>
          <select id="ds-status" value={status} onChange={(e) => onStatus(e.target.value as '' | DutyStatus)}>
            <option value="">Any</option>
            {DUTY_STATUSES.map((st) => (
              <option key={st} value={st}>
                {DUTY_STATUS_LABELS[st]}
              </option>
            ))}
          </select>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {rows.length} employees.
      </p>
      {rows.length === 0 && <p className="muted">No employees in your scope have a duty-status entry.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Safety-sensitive duty status of employees in your scope</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Duty status</th>
                <th scope="col">Since</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.employeeId}>
                  <th scope="row">
                    {r.displayName}
                    <span className="muted small"> {r.employeeId}</span>
                  </th>
                  <td>
                    <StatusBadge status={`duty-${r.dutyStatus}`} label={DUTY_STATUS_LABELS[r.dutyStatus]} />
                  </td>
                  <td>{formatDay(r.since)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function ForCauseSection({ client, onSessionExpired }: ScreenProps) {
  const requests = useAsync(async () => toForCauseRows(await client.api<unknown>(FOR_CAUSE_PATH)), onSessionExpired);
  const [form, setForm] = useState<ForCauseForm>(emptyForCauseForm);
  const [employee, setEmployee] = useState<EmployeeLookup>();
  const [errors, setErrors] = useState<FieldErrors>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function submit() {
    const errs = validateForCause(form);
    setErrors(errs);
    setMessage(undefined);
    if (Object.keys(errs).length > 0) {
      focusFirstError(FOR_CAUSE_FIELDS.filter((f) => errs[f]).map((f) => FOR_CAUSE_IDS[f]));
      return;
    }
    setBusy(true);
    const res = await submitAction(client, FOR_CAUSE_PATH, buildForCause(form), FOR_CAUSE_FIELDS, { failed: 'The request was not sent' });
    setBusy(false);
    if (res.ok) {
      const ref = toRequestRef(res.body);
      setMessage({ kind: 'success', text: `Request sent to the testing programme${ref ? ` (reference ${ref})` : ''}. A collector is being arranged.` });
      setForm(emptyForCauseForm());
      setEmployee(undefined);
      requests.reload();
    } else {
      if (res.sessionExpired) onSessionExpired();
      setErrors(res.fieldErrors);
      setMessage({ kind: 'error', text: res.message });
    }
  }

  return (
    <>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      <ForCauseFormView
        form={form}
        errors={errors}
        busy={busy}
        onChange={setForm}
        onSubmit={() => void submit()}
        employeePicker={
          <EmployeeSearch
            client={client}
            idPrefix="fc"
            selected={employee}
            error={errors.employeeId}
            onSessionExpired={onSessionExpired}
            onSelect={(e) => {
              setEmployee(e);
              setForm((f) => ({ ...f, employeeId: e?.employeeId ?? '', employeeName: e?.displayName }));
            }}
          />
        }
      />
      <h2>My requests</h2>
      <p className="muted small">Status only – outcomes are handled confidentially by the medical review officer.</p>
      {requests.loading && <Loading />}
      {!!requests.error && <Alert kind="error">{describeError(requests.error)}</Alert>}
      {requests.data && <MyRequestsTable rows={requests.data} />}
    </>
  );
}

function DutyStatusSection({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toDutyStatusRows(await client.api<unknown>(DUTY_STATUS_PATH)), onSessionExpired);
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<'' | DutyStatus>('');
  return (
    <>
      <h2>Safety-sensitive duty status</h2>
      <NoClinicalInfoNote>
        Only whether employees are available for safety-sensitive duty is shown. Reasons, test types and results stay with the testing
        programme. Opening this list is recorded in the disclosure log.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && <DutyStatusView rows={rows.data} q={q} status={status} onQ={setQ} onStatus={setStatus} />}
    </>
  );
}

/** `#/testing` – for-cause requests (MANAGER, SAFETY_MANAGER) and the duty-status list (HR, CREW_SCHEDULING). */
export function Testing(props: ScreenProps) {
  const sections = testingSections(props.session.roles);
  return (
    <section>
      <PageTitle>Drug &amp; alcohol testing</PageTitle>
      <p className="lead">
        {sections.forCause && 'Raise a for-cause test request when you observe signs that someone may be impaired at work. '}
        {sections.dutyStatus && 'See who is available for safety-sensitive duty.'}
      </p>
      {sections.forCause && <ForCauseSection {...props} />}
      {sections.dutyStatus && <DutyStatusSection {...props} />}
    </section>
  );
}

