import { useState, type FormEvent } from 'react';
import { todayIso } from '../api/hr';
import type { FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import {
  emptyPreEmploymentForm,
  HR_CLEARANCE_LABELS,
  isStartAtRisk,
  normalisePreEmployment,
  PRE_EMPLOYMENT_FIELDS,
  PRE_EMPLOYMENT_LIMITS,
  PRE_EMPLOYMENT_PATH,
  toOnboardingRows,
  validatePreEmployment,
  type OnboardingRow,
  type PreEmploymentField,
  type PreEmploymentForm,
} from '../api/surveillance';
import { InputField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { describeError, formatDate, formatDay } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** Onboarding clearance list – status only. Hook-free. */
export function OnboardingTable({ rows, today }: { rows: readonly OnboardingRow[]; today: string }) {
  if (rows.length === 0) return <p className="muted">No pre-employment medicals in progress.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Onboarding medical clearance</caption>
        <thead>
          <tr>
            <th scope="col">Candidate</th>
            <th scope="col">Role</th>
            <th scope="col">Proposed start</th>
            <th scope="col">Clearance</th>
            <th scope="col">Last update</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <th scope="row">
                {r.candidateName}
                {r.offerRef && <span className="muted small"> {r.offerRef}</span>}
              </th>
              <td>{r.jobTitle ?? '–'}</td>
              <td>
                {formatDay(r.proposedStart)}
                {isStartAtRisk(r, today) && (
                  <>
                    {' '}
                    <StatusBadge status="start-at-risk" label="Start date at risk" />
                  </>
                )}
              </td>
              <td>
                <StatusBadge status={`clearance-${r.hrClearance}`} label={HR_CLEARANCE_LABELS[r.hrClearance]} />
              </td>
              <td>{formatDate(r.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const FIELD_META: Record<PreEmploymentField, { label: string; hint?: string; type?: 'text' | 'email' | 'date' }> = {
  offerRef: { label: 'Offer reference', hint: 'The offer reference from the HR system. A repeated reference returns the existing record.' },
  candidateId: { label: 'Candidate id', hint: 'For example C9001.' },
  candidateName: { label: 'Candidate name' },
  email: { label: 'Candidate e-mail', type: 'email', hint: 'The invitation to complete the health questionnaire is sent here.' },
  jobCode: { label: 'Job code', hint: 'For example SECURITY_SCREENER. Determines the medical panel.' },
  subsidiaryCode: { label: 'Subsidiary code' },
  departmentCode: { label: 'Department code' },
  locationCode: { label: 'Location code', hint: 'For example HQ or LHR – used to book the nearest clinic.' },
  proposedStart: { label: 'Proposed start date', type: 'date' },
};

export interface PreEmploymentFormViewProps {
  form: PreEmploymentForm;
  errors: FieldErrors;
  message?: string;
  submitting: boolean;
  onPatch: (p: Partial<PreEmploymentForm>) => void;
  onSubmit: (e: FormEvent) => void;
}

/** E07-F03 – HR manual trigger of a pre-employment medical. Hook-free view. */
export function PreEmploymentFormView({ form, errors, message, submitting, onPatch, onSubmit }: PreEmploymentFormViewProps) {
  return (
    <form className="form" noValidate onSubmit={onSubmit} aria-label="Start a pre-employment medical">
      {message && <Alert kind="error">{message}</Alert>}
      {PRE_EMPLOYMENT_FIELDS.map((k) => {
        const m = FIELD_META[k];
        return (
          <InputField
            key={k}
            id={`pe-${k}`}
            label={m.label}
            type={m.type ?? 'text'}
            required
            hint={m.hint}
            autoComplete="off"
            maxLength={m.type === 'date' ? undefined : PRE_EMPLOYMENT_LIMITS[k]}
            value={form[k]}
            onChange={(v) => onPatch({ [k]: v })}
            error={errors[k]}
          />
        );
      })}
      <button type="submit" disabled={submitting}>
        {submitting ? 'Starting…' : 'Start pre-employment medical'}
      </button>
    </form>
  );
}

const ORDER = PRE_EMPLOYMENT_FIELDS.map((k) => `pe-${k}`);

export function PreEmployment({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toOnboardingRows(await client.api<unknown>('/workspace/pre-employment')), onSessionExpired);
  const [form, setForm] = useState<PreEmploymentForm>(emptyPreEmploymentForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<string>();
  const today = todayIso();

  const onPatch = (p: Partial<PreEmploymentForm>) => {
    setForm((f) => ({ ...f, ...p }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => !(k in p))));
  };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(undefined);
    setNotice(undefined);
    const v = validatePreEmployment(form, today);
    setErrors(v);
    if (Object.keys(v).length) {
      setMessage('Please check the highlighted fields.');
      focusFirstError(ORDER.filter((id) => v[id.slice(3)]));
      return;
    }
    setSubmitting(true);
    const body = normalisePreEmployment(form);
    const r = await submitAction(client, PRE_EMPLOYMENT_PATH, body, PRE_EMPLOYMENT_FIELDS, { failed: 'The pre-employment medical was not started' });
    setSubmitting(false);
    if (r.ok) {
      setForm(emptyPreEmploymentForm());
      setNotice(`Pre-employment medical started for offer ${body.offerRef}. The candidate has been invited to complete the health questionnaire.`);
      rows.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors(r.fieldErrors);
    setMessage(r.message);
    focusFirstError(ORDER.filter((id) => r.fieldErrors[id.slice(3)]));
  }

  return (
    <section>
      <PageTitle>Pre-employment medicals</PageTitle>
      <p className="lead">Medical clearance status for candidates with a conditional offer.</p>
      <NoClinicalInfoNote>
        No medical details are shown – questionnaire answers, examination findings and conditions stay with Occupational Health. You see
        the clearance outcome only.
      </NoClinicalInfoNote>
      {notice && <Alert kind="success">{notice}</Alert>}
      <h2>Onboarding clearance</h2>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && <OnboardingTable rows={rows.data} today={today} />}

      <h2 className="section-gap">Start a pre-employment medical</h2>
      <p className="muted small">
        Offers from the HR system start automatically. Use this form only when an offer was made outside the HR system.
      </p>
      <PreEmploymentFormView form={form} errors={errors} message={message} submitting={submitting} onPatch={onPatch} onSubmit={(e) => void onSubmit(e)} />
    </section>
  );
}
