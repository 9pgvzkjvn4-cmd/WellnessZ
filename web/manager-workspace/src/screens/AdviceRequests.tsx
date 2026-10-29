import { Fragment, useState, type FormEvent } from 'react';
import {
  ADVICE_CONTEXT_MAX,
  ADVICE_REQUEST_FIELDS,
  adviceStatusLabel,
  adviceWithdrawPath,
  buildAdviceRequest,
  canWithdrawAdvice,
  emptyAdviceRequestForm,
  FITNESS_LABELS,
  isSlaOverdue,
  PROGNOSIS_LABELS,
  questionText,
  toAdviceQuestions,
  toAdviceRequests,
  validateAdviceRequest,
  type AdviceQuestion,
  type AdviceReport,
  type AdviceRequest,
  type AdviceRequestForm,
} from '../api/advice';
import { documentContentUrl } from '../api/fitness';
import type { FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { EmployeeSearch } from '../components/EmployeeSearch';
import { FieldError, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { describeError, formatDate, formatDay, humanise, idPart } from '../components/format';
import { adviceQuestionId } from '../components/ids';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const ORDER: Record<string, string> = { employeeId: 'adv-employeeId', questionCodes: 'adv-questionCodes', context: 'adv-context' };
const focusErrors = (e: FieldErrors) => focusFirstError(Object.keys(ORDER).filter((k) => e[k]).map((k) => ORDER[k]!));

export const CONTEXT_HINT =
  'Optional. Goes only to Occupational Health – it is stored encrypted and visible to OH and to you as the requester. Describe the work situation and what you need to know; do not include diagnoses or medical opinions.';

/* ---------------- request form ---------------- */

export interface QuestionPickerProps {
  questions: readonly AdviceQuestion[];
  selected: readonly string[];
  error?: string;
  onChange: (codes: string[]) => void;
}

/** Question library checkboxes (≥ 1 required). Hook-free. */
export function QuestionPicker({ questions, selected, error, onChange }: QuestionPickerProps) {
  const set = new Set(selected);
  return (
    <fieldset
      id="adv-questionCodes"
      className={error ? 'field field-invalid' : 'field'}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? 'adv-questionCodes-hint adv-questionCodes-error' : 'adv-questionCodes-hint'}
    >
      <legend>
        Questions for Occupational Health<span className="req" aria-hidden="true"> *</span>
      </legend>
      <p id="adv-questionCodes-hint" className="muted small">
        Choose at least one. OH answers each question in functional terms.
      </p>
      <FieldError id="adv-questionCodes" error={error} />
      {questions.length === 0 ? (
        <p className="muted small">The question library is not available.</p>
      ) : (
        <ul className="checkbox-list">
          {questions.map((q) => {
            const id = adviceQuestionId(q.code);
            return (
              <li key={q.code}>
                <label className="checkbox" htmlFor={id}>
                  <input
                    id={id}
                    type="checkbox"
                    checked={set.has(q.code)}
                    onChange={(e) => onChange(e.target.checked ? [...selected, q.code] : selected.filter((c) => c !== q.code))}
                  />
                  {q.text}
                </label>
              </li>
            );
          })}
        </ul>
      )}
    </fieldset>
  );
}

/* ---------------- released report ---------------- */

const label = (map: Record<string, string>, v?: string) => (v ? (map[v] ?? humanise(v)) : '–');
const lines = (xs: readonly string[]) =>
  xs.length === 0 ? (
    <span className="muted">None</span>
  ) : (
    <ul className="plain-list">
      {xs.map((x, i) => (
        <li key={i}>{x}</li>
      ))}
    </ul>
  );

/** The released OH advice report (functional advice only). Hook-free. */
export function AdviceReportView({ report, questions }: { report: AdviceReport; questions: readonly AdviceQuestion[] }) {
  return (
    <div className="report">
      <dl className="details">
        <dt>Fitness for work</dt>
        <dd>{label(FITNESS_LABELS, report.fitness)}</dd>
        <dt>Recommended adjustments</dt>
        <dd>{lines(report.adjustments)}</dd>
        <dt>Restrictions</dt>
        <dd>{lines(report.restrictions)}</dd>
        <dt>Prognosis</dt>
        <dd>{label(PROGNOSIS_LABELS, report.prognosis)}</dd>
        <dt>Expected return</dt>
        <dd>{report.expectedReturn ? formatDay(report.expectedReturn) : '–'}</dd>
        <dt>OH review date</dt>
        <dd>{report.reviewDate ? formatDay(report.reviewDate) : '–'}</dd>
        <dt>Released</dt>
        <dd>{formatDate(report.releasedAt)}</dd>
      </dl>
      {report.answers.length > 0 && (
        <>
          <h4>Answers to your questions</h4>
          <dl className="details">
            {report.answers.map((a) => (
              <Fragment key={a.code}>
                <dt>{questionText(questions, a.code)}</dt>
                <dd>{a.text}</dd>
              </Fragment>
            ))}
          </dl>
        </>
      )}
      {report.employeeComment && (
        <>
          <h4>Employee comment</h4>
          <blockquote className="quote">{report.employeeComment}</blockquote>
        </>
      )}
      {report.documentId && (
        <p>
          <a href={documentContentUrl(report.documentId)} download>
            Download signed advice report (PDF)
          </a>
        </p>
      )}
    </div>
  );
}

/* ---------------- request list ---------------- */

export interface AdviceRequestListProps {
  items: readonly AdviceRequest[];
  questions: readonly AdviceQuestion[];
  now?: Date;
  confirming?: string;
  busy?: string;
  onWithdraw: (id: string) => void;
  onConfirm: (id: string) => void;
  onCancel: () => void;
}

/** Requests with status and SLA; RELEASED shows the report, WITHHELD shows status only. Hook-free. */
export function AdviceRequestList({ items, questions, now = new Date(), confirming, busy, onWithdraw, onConfirm, onCancel }: AdviceRequestListProps) {
  if (items.length === 0) return <p className="muted">You have not requested OH advice yet.</p>;
  return (
    <ul className="card-list">
      {items.map((r) => {
        const headingId = `adv-${idPart(r.id)}-heading`;
        const overdue = isSlaOverdue(r, now);
        return (
          <li key={r.id} className="card">
            <article aria-labelledby={headingId}>
              <div className="card-row">
                <h3 id={headingId}>
                  {r.displayName}
                  <span className="muted small"> {r.ref ?? r.id}</span>
                </h3>
                <StatusBadge status={`advice-${r.status}`} label={adviceStatusLabel(r.status)} />
              </div>
              <dl className="details">
                <dt>Requested</dt>
                <dd>
                  {formatDate(r.requestedAt)}
                  {r.requestedBy && <span className="muted"> by {r.requestedBy}</span>}
                </dd>
                <dt>OH response due</dt>
                <dd>
                  {formatDay(r.slaDueAt)}
                  {overdue && <StatusBadge status="sla-overdue" label="Past due date" />}
                </dd>
                {r.caseRef && (
                  <>
                    <dt>OH case</dt>
                    <dd>{r.caseRef}</dd>
                  </>
                )}
                <dt>Questions</dt>
                <dd>
                  <ul className="plain-list">
                    {r.questionCodes.map((c) => (
                      <li key={c}>{questionText(questions, c)}</li>
                    ))}
                  </ul>
                </dd>
              </dl>
              {r.status === 'RELEASED' && r.report && <AdviceReportView report={r.report} questions={questions} />}
              {r.status === 'RELEASED' && !r.report && <p className="muted">The advice report has been released but is not available yet. Please refresh later.</p>}
              {r.status === 'WITHHELD' && (
                <Alert kind="info">The employee has not consented to release – OH will contact you.</Alert>
              )}
              {r.status === 'AWAITING_EMPLOYEE' && (
                <p className="muted small">OH has completed the report. The employee is reviewing it before release.</p>
              )}
              {canWithdrawAdvice(r) && (
                <div className="actions">
                  {confirming === r.id ? (
                    <>
                      <button type="button" disabled={busy === r.id} onClick={() => onConfirm(r.id)}>
                        {busy === r.id ? 'Withdrawing…' : 'Confirm withdrawal'}
                      </button>
                      <button type="button" className="secondary" disabled={busy === r.id} onClick={onCancel}>
                        Keep request
                      </button>
                    </>
                  ) : (
                    <button type="button" className="secondary" aria-label={`Withdraw request ${r.ref ?? r.id}`} onClick={() => onWithdraw(r.id)}>
                      Withdraw request
                    </button>
                  )}
                </div>
              )}
            </article>
          </li>
        );
      })}
    </ul>
  );
}

/* ---------------- screen ---------------- */

export function AdviceRequests({ client, onSessionExpired }: ScreenProps) {
  const questions = useAsync(async () => toAdviceQuestions(await client.api<unknown>('/absence/advice/questions')), onSessionExpired);
  const requests = useAsync(async () => toAdviceRequests(await client.api<unknown>('/workspace/advice-requests')), onSessionExpired);
  const [form, setForm] = useState<AdviceRequestForm>(emptyAdviceRequestForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string }>();
  const [confirming, setConfirming] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const library = questions.data ?? [];

  const patch = (p: Partial<AdviceRequestForm>, keys: string[]) => {
    setForm((f) => ({ ...f, ...p }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => !keys.includes(k))));
  };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(undefined);
    setNotice(undefined);
    const v = validateAdviceRequest(form, library);
    setErrors(v);
    if (Object.keys(v).length) {
      setMessage('Please check the highlighted fields.');
      focusErrors(v);
      return;
    }
    setSubmitting(true);
    const r = await submitAction<unknown>(client, '/workspace/advice-requests', buildAdviceRequest(form, library), ADVICE_REQUEST_FIELDS, {
      failed: 'The advice request was not sent',
    });
    setSubmitting(false);
    if (r.ok) {
      const created = toAdviceRequests([r.body])[0];
      setForm(emptyAdviceRequestForm());
      const ref = created?.ref ? ` ${created.ref}` : '';
      const due = created?.slaDueAt ? ` A response is due by ${formatDay(created.slaDueAt)}.` : '';
      setNotice({ kind: 'success', text: `Advice request${ref} sent to Occupational Health.${due}` });
      requests.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors(r.fieldErrors);
    setMessage(r.message);
    focusErrors(r.fieldErrors);
  }

  async function withdraw(id: string) {
    setBusy(id);
    setNotice(undefined);
    const r = await submitAction(client, adviceWithdrawPath(id), {}, [], { failed: 'The request was not withdrawn' });
    setBusy(undefined);
    setConfirming(undefined);
    if (r.ok) {
      setNotice({ kind: 'success', text: 'The advice request was withdrawn.' });
      requests.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setNotice({ kind: 'error', text: r.message });
  }

  return (
    <section>
      <PageTitle>OH advice requests</PageTitle>
      <p className="lead">Ask Occupational Health for management advice about an employee in your area.</p>
      <NoClinicalInfoNote>
        No medical details are shown. The advice report states fitness, adjustments and prognosis in functional terms and is released
        to you only with the employee’s consent.
      </NoClinicalInfoNote>
      {notice && <Alert kind={notice.kind}>{notice.text}</Alert>}

      <h2>My requests</h2>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={requests.reload} disabled={requests.loading}>
          Refresh
        </button>
      </div>
      {requests.loading && <Loading />}
      {!!requests.error && <Alert kind="error">{describeError(requests.error)}</Alert>}
      {requests.data && (
        <AdviceRequestList
          items={requests.data}
          questions={library}
          confirming={confirming}
          busy={busy}
          onWithdraw={setConfirming}
          onConfirm={(id) => void withdraw(id)}
          onCancel={() => setConfirming(undefined)}
        />
      )}

      <h2 className="section-gap">New advice request</h2>
      {questions.loading && <Loading label="Loading question library…" />}
      {!!questions.error && <Alert kind="error">{describeError(questions.error)}</Alert>}
      {questions.data && (
        <form className="form" noValidate onSubmit={(e) => void onSubmit(e)} aria-label="New OH advice request">
          {message && <Alert kind="error">{message}</Alert>}
          <EmployeeSearch
            client={client}
            idPrefix="adv"
            selected={form.employee}
            onSelect={(employee) => patch({ employee }, ['employeeId'])}
            onSessionExpired={onSessionExpired}
            error={errors.employeeId}
          />
          <QuestionPicker questions={library} selected={form.questionCodes} error={errors.questionCodes} onChange={(questionCodes) => patch({ questionCodes }, ['questionCodes'])} />
          <TextAreaField
            id="adv-context"
            label="Context for Occupational Health"
            max={ADVICE_CONTEXT_MAX}
            value={form.context}
            onChange={(context) => patch({ context }, ['context'])}
            hint={CONTEXT_HINT}
            error={errors.context}
            rows={5}
          />
          <p className="muted small">OH aims to respond within 5 working days.</p>
          <button type="submit" disabled={submitting}>
            {submitting ? 'Sending…' : 'Send request to OH'}
          </button>
        </form>
      )}
    </section>
  );
}
