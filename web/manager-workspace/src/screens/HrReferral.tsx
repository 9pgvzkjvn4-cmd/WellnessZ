import { useState, type FormEvent, type ReactNode } from 'react';
import {
  buildHrReferral,
  emptyHrForm,
  GUIDANCE,
  HR_FIELDS,
  HR_MAX_QUESTIONS,
  HR_QUESTION_MAX,
  HR_SUMMARY_MAX,
  POLICY_STAGE_LABELS,
  POLICY_STAGES,
  sentQuestionRow,
  submitReferral,
  validateHrReferral,
  type FieldErrors,
  type HrReferralForm as Form,
  type PolicyStage,
} from '../api/referrals';
import { EmployeeSearch } from '../components/EmployeeSearch';
import { FieldError, InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { Alert, PageTitle } from '../components/ui';
import { ReferralSent } from './ManagerReferral';
import type { ScreenProps } from './types';

const questionId = (i: number) => `hr-adviceQuestions-${i}`;

/** Editable list of advice questions (add / edit / remove). Hook-free. */
export function AdviceQuestionsEditor({
  questions,
  errors,
  onChange,
}: {
  questions: readonly string[];
  errors: FieldErrors;
  onChange: (next: string[]) => void;
}) {
  const set = (i: number, v: string) => onChange(questions.map((q, j) => (j === i ? v : q)));
  return (
    <fieldset
      id="hr-adviceQuestions"
      className={errors.adviceQuestions ? 'field field-invalid' : 'field'}
      aria-invalid={errors.adviceQuestions ? true : undefined}
      aria-describedby={errors.adviceQuestions ? 'hr-adviceQuestions-hint hr-adviceQuestions-error' : 'hr-adviceQuestions-hint'}
    >
      <legend>
        Questions for Occupational Health<span className="req" aria-hidden="true"> *</span>
      </legend>
      <p id="hr-adviceQuestions-hint" className="muted small">
        What do you need advice on? For example: Is a phased return advisable? Is the employee likely to return within the
        policy period? {GUIDANCE}
      </p>
      <FieldError id="hr-adviceQuestions" error={errors.adviceQuestions} />
      <ol className="question-list">
        {questions.map((q, i) => {
          const id = questionId(i);
          const err = errors[`adviceQuestions.${i}`];
          return (
            <li key={i} className={err ? 'field-invalid' : undefined}>
              <label htmlFor={id} className="visually-hidden">
                Question {i + 1}
              </label>
              {err && (
                <p id={`${id}-error`} className="field-error">
                  {err}
                </p>
              )}
              <div className="question-row">
                <input
                  id={id}
                  type="text"
                  maxLength={HR_QUESTION_MAX}
                  autoComplete="off"
                  value={q}
                  aria-invalid={err ? true : undefined}
                  aria-describedby={err ? `${id}-error` : undefined}
                  onChange={(e) => set(i, e.target.value)}
                />
                <button
                  type="button"
                  className="secondary small-button"
                  aria-label={`Remove question ${i + 1}`}
                  disabled={questions.length === 1}
                  onClick={() => onChange(questions.filter((_, j) => j !== i))}
                >
                  Remove
                </button>
              </div>
            </li>
          );
        })}
      </ol>
      <button
        type="button"
        className="secondary small-button"
        disabled={questions.length >= HR_MAX_QUESTIONS}
        onClick={() => onChange([...questions, ''])}
      >
        Add question
      </button>
    </fieldset>
  );
}

export interface HrReferralViewProps {
  form: Form;
  errors: FieldErrors;
  message?: string;
  submitting: boolean;
  onChange: <K extends keyof Form>(key: K, value: Form[K]) => void;
  onSubmit: (e: FormEvent) => void;
  employeeSlot: ReactNode;
}

/** E01-BL-008 – long-term absence referral with policy context. Hook-free view. */
export function HrReferralView({ form, errors, message, submitting, onChange, onSubmit, employeeSlot }: HrReferralViewProps) {
  return (
    <form className="form referral-form" noValidate onSubmit={onSubmit} aria-describedby="hr-guidance">
      <div className="banner banner-warning" role="note" id="hr-guidance">
        <strong>Do not include diagnoses or medical opinions</strong>
        <span className="small">
          Give the absence facts and policy context only. HR receives fitness-for-work advice, never clinical notes.
        </span>
      </div>
      {message && <Alert kind="error">{message}</Alert>}
      {employeeSlot}
      <TextAreaField
        id="hr-absenceSummary"
        label="Absence summary"
        required
        max={HR_SUMMARY_MAX}
        value={form.absenceSummary}
        onChange={(v) => onChange('absenceSummary', v)}
        hint={`Dates and pattern of absence, current contact with the employee. ${GUIDANCE}`}
        error={errors.absenceSummary}
        rows={4}
      />
      <div className={errors.policyStage ? 'field field-invalid' : 'field'}>
        <label htmlFor="hr-policyStage">
          Absence policy stage<span className="req" aria-hidden="true"> *</span>
        </label>
        <FieldError id="hr-policyStage" error={errors.policyStage} />
        <select
          id="hr-policyStage"
          value={form.policyStage}
          aria-invalid={errors.policyStage ? true : undefined}
          aria-describedby={errors.policyStage ? 'hr-policyStage-error' : undefined}
          onChange={(e) => onChange('policyStage', e.target.value as PolicyStage | '')}
        >
          <option value="">Select…</option>
          {POLICY_STAGES.map((s) => (
            <option key={s} value={s}>
              {POLICY_STAGE_LABELS[s]}
            </option>
          ))}
        </select>
      </div>
      <InputField
        id="hr-absenceDaysLast12Months"
        type="number"
        inputMode="numeric"
        min={0}
        max={366}
        label="Days absent in the last 12 months"
        required
        value={form.absenceDaysLast12Months}
        onChange={(v) => onChange('absenceDaysLast12Months', v)}
        error={errors.absenceDaysLast12Months}
      />
      <AdviceQuestionsEditor questions={form.adviceQuestions} errors={errors} onChange={(q) => onChange('adviceQuestions', q)} />
      <button type="submit" disabled={submitting}>
        {submitting ? 'Sending…' : 'Send HR referral'}
      </button>
    </form>
  );
}

const firstErrorIds = (errors: FieldErrors) => [
  ...['employeeId', 'absenceSummary', 'policyStage', 'absenceDaysLast12Months', 'adviceQuestions'].filter((k) => errors[k]).map((k) => `hr-${k}`),
  ...Object.keys(errors)
    .filter((k) => k.startsWith('adviceQuestions.'))
    .map((k) => questionId(Number(k.split('.')[1]))),
];

export function HrReferral({ client, onSessionExpired }: ScreenProps) {
  const [form, setForm] = useState<Form>(emptyHrForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState<{ caseRef?: string }>();

  const onChange = <K extends keyof Form>(key: K, value: Form[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    const errKey = key === 'employee' ? 'employeeId' : (key as string);
    setErrors((e) => {
      const next = Object.fromEntries(Object.entries(e).filter(([k]) => k !== errKey && !(errKey === 'adviceQuestions' && k.startsWith('adviceQuestions.'))));
      return Object.keys(next).length === Object.keys(e).length ? e : next;
    });
  };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(undefined);
    const v = validateHrReferral(form);
    setErrors(v);
    if (Object.keys(v).length) {
      setMessage('Please check the highlighted fields.');
      focusFirstError(firstErrorIds(v));
      return;
    }
    setSubmitting(true);
    const r = await submitReferral(client, '/intake/referrals/hr', buildHrReferral(form), HR_FIELDS, sentQuestionRow(form.adviceQuestions));
    setSubmitting(false);
    if (r.ok) {
      setSent({ caseRef: r.caseRef });
      setForm(emptyHrForm());
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors(r.fieldErrors);
    setMessage(r.message);
    focusFirstError(firstErrorIds(r.fieldErrors));
  }

  return (
    <section>
      <PageTitle>HR referral to Occupational Health</PageTitle>
      <p className="lead">Refer long-term or frequent absence with policy context so OH can advise on fitness for work.</p>
      {sent ? (
        <ReferralSent caseRef={sent.caseRef} onAnother={() => setSent(undefined)} />
      ) : (
        <HrReferralView
          form={form}
          errors={errors}
          message={message}
          submitting={submitting}
          onChange={onChange}
          onSubmit={(e) => void onSubmit(e)}
          employeeSlot={
            <EmployeeSearch
              client={client}
              idPrefix="hr"
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
