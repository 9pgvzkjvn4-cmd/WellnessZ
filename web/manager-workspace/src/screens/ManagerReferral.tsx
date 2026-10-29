import { useState, type FormEvent, type ReactNode } from 'react';
import {
  buildManagerReferral,
  emptyManagerForm,
  GUIDANCE,
  MANAGER_FIELDS,
  MANAGER_TEXT_MAX,
  submitReferral,
  validateManagerReferral,
  type FieldErrors,
  type ManagerReferralForm as Form,
} from '../api/referrals';
import { EmployeeSearch } from '../components/EmployeeSearch';
import { FieldError, InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { Alert, PageTitle } from '../components/ui';
import type { ScreenProps } from './types';

const ORDER = MANAGER_FIELDS.map((k) => `mr-${k}`);

export interface ManagerReferralFormProps {
  form: Form;
  errors: FieldErrors;
  message?: string;
  submitting: boolean;
  onChange: <K extends keyof Form>(key: K, value: Form[K]) => void;
  onSubmit: (e: FormEvent) => void;
  /** Employee search control (stateful, rendered by the screen). */
  employeeSlot: ReactNode;
}

/** E01-BL-007 – work-impact facts only. Hook-free view. */
export function ManagerReferralView({ form, errors, message, submitting, onChange, onSubmit, employeeSlot }: ManagerReferralFormProps) {
  return (
    <form className="form referral-form" noValidate onSubmit={onSubmit} aria-describedby="mr-guidance">
      <div className="banner banner-warning" role="note" id="mr-guidance">
        <strong>Do not include diagnoses or medical opinions</strong>
        <span className="small">
          Describe only how work is affected – duties, dates, safety concerns and adjustments. Occupational Health will
          assess the health aspects with the employee confidentially.
        </span>
      </div>
      {message && <Alert kind="error">{message}</Alert>}

      {employeeSlot}

      <TextAreaField
        id="mr-dutiesAffected"
        label="Which duties are affected?"
        required
        max={MANAGER_TEXT_MAX}
        value={form.dutiesAffected}
        onChange={(v) => onChange('dutiesAffected', v)}
        hint={GUIDANCE}
        error={errors.dutiesAffected}
        rows={4}
      />
      <InputField
        id="mr-startDate"
        type="date"
        label="Since when?"
        required
        value={form.startDate}
        onChange={(v) => onChange('startDate', v)}
        error={errors.startDate}
      />
      <fieldset
        id="mr-safetyConcern"
        className={errors.safetyConcern ? 'field field-invalid' : 'field'}
        aria-invalid={errors.safetyConcern ? true : undefined}
        aria-describedby={errors.safetyConcern ? 'mr-safetyConcern-error' : undefined}
      >
        <legend>
          Is there a safety concern at work?<span className="req" aria-hidden="true"> *</span>
        </legend>
        <FieldError id="mr-safetyConcern" error={errors.safetyConcern} />
        <div className="inline-options">
          {(['yes', 'no'] as const).map((v) => (
            <label key={v} className="radio">
              <input type="radio" name="mr-safetyConcern" value={v} checked={form.safetyConcern === v} onChange={() => onChange('safetyConcern', v)} />
              {v === 'yes' ? 'Yes' : 'No'}
            </label>
          ))}
        </div>
      </fieldset>
      {form.safetyConcern === 'yes' && (
        <TextAreaField
          id="mr-safetyConcernDetail"
          label="Describe the safety concern"
          required
          max={MANAGER_TEXT_MAX}
          value={form.safetyConcernDetail}
          onChange={(v) => onChange('safetyConcernDetail', v)}
          hint="For example: operates airside vehicles, works at height, handles food. No medical details."
          error={errors.safetyConcernDetail}
        />
      )}
      <TextAreaField
        id="mr-adjustmentsTried"
        label="Adjustments already tried"
        max={MANAGER_TEXT_MAX}
        value={form.adjustmentsTried}
        onChange={(v) => onChange('adjustmentsTried', v)}
        hint={GUIDANCE}
        error={errors.adjustmentsTried}
      />
      <TextAreaField
        id="mr-questionsForOh"
        label="Questions for Occupational Health"
        max={MANAGER_TEXT_MAX}
        value={form.questionsForOh}
        onChange={(v) => onChange('questionsForOh', v)}
        hint="For example: Can they return to full duties? Which adjustments would help?"
        error={errors.questionsForOh}
      />
      <p className="muted small">Please discuss the referral with the employee before sending it.</p>
      <button type="submit" disabled={submitting}>
        {submitting ? 'Sending…' : 'Send referral'}
      </button>
    </form>
  );
}

export function ReferralSent({ caseRef, onAnother }: { caseRef?: string; onAnother: () => void }) {
  return (
    <>
      <Alert kind="success">
        Referral sent{caseRef ? <> – case reference <strong className="mono">{caseRef}</strong></> : ''}. Occupational
        Health will contact the employee.
      </Alert>
      <p>You will see status-only updates (status, fitness for work, restriction end date) under Team cases.</p>
      <div className="actions">
        <a className="button" href="#/">
          Team cases
        </a>
        <button type="button" className="secondary" onClick={onAnother}>
          Refer another employee
        </button>
      </div>
    </>
  );
}

export function ManagerReferral({ client, onSessionExpired }: ScreenProps) {
  const [form, setForm] = useState<Form>(emptyManagerForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState<{ caseRef?: string }>();

  const onChange = <K extends keyof Form>(key: K, value: Form[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    const errKey = key === 'employee' ? 'employeeId' : (key as string);
    setErrors((e) => {
      if (!e[errKey]) return e;
      const next = { ...e };
      delete next[errKey];
      return next;
    });
  };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(undefined);
    const v = validateManagerReferral(form);
    setErrors(v);
    if (Object.keys(v).length) {
      setMessage('Please check the highlighted fields.');
      focusFirstError(ORDER.filter((id) => v[id.slice(3)]));
      return;
    }
    setSubmitting(true);
    const r = await submitReferral(client, '/intake/referrals/manager', buildManagerReferral(form), MANAGER_FIELDS);
    setSubmitting(false);
    if (r.ok) {
      setSent({ caseRef: r.caseRef });
      setForm(emptyManagerForm());
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors(r.fieldErrors);
    setMessage(r.message);
    focusFirstError(ORDER.filter((id) => r.fieldErrors[id.slice(3)]));
  }

  return (
    <section>
      <PageTitle>Refer an employee to Occupational Health</PageTitle>
      <p className="lead">Management referral – tell OH how work is affected. You will not receive medical information.</p>
      {sent ? (
        <ReferralSent caseRef={sent.caseRef} onAnother={() => setSent(undefined)} />
      ) : (
        <ManagerReferralView
          form={form}
          errors={errors}
          message={message}
          submitting={submitting}
          onChange={onChange}
          onSubmit={(e) => void onSubmit(e)}
          employeeSlot={
            <EmployeeSearch
              client={client}
              idPrefix="mr"
              selected={form.employee}
              onSelect={(emp) => onChange('employee', emp)}
              onSessionExpired={onSessionExpired}
              error={errors.employeeId}
            />
          }
        />
      )}
    </section>
  );
}
