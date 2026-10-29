import { useState, type FormEvent } from 'react';
import {
  APPEAL_FIELDS,
  APPEAL_LIMITS,
  APPEAL_WINDOW_DAYS,
  buildAppeal,
  canAppeal,
  emptyAppealForm,
  todayIso,
  toIhrAssessments,
  validateAppeal,
  type AppealForm,
  type IhrAssessment,
} from '../api/hr';
import { GUIDANCE, type FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { CaseHeader } from '../components/CaseHeader';
import { InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { appealIds } from '../components/ids';
import { describeError, formatDay, humanise, idPart } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const TYPE_LABELS: Record<string, string> = { ILL_HEALTH_RETIREMENT: 'Ill-health retirement', REDEPLOYMENT: 'Redeployment' };

export interface IhrCardProps {
  assessment: IhrAssessment;
  today: string;
  form: AppealForm;
  errors: FieldErrors;
  message?: string;
  busy: boolean;
  onForm: (f: AppealForm) => void;
  onAppeal: (e: FormEvent) => void;
}

/** E05-F07 – HR view of one assessment: type, status, functional decision text and dates only. Hook-free. */
export function IhrCard({ assessment: a, today, form, errors, message, busy, onForm, onAppeal }: IhrCardProps) {
  const ids = appealIds(a.id);
  const headingId = `ihr-${idPart(a.id)}-title`;
  const row = (label: string, value?: string) =>
    value ? (
      <>
        <dt>{label}</dt>
        <dd>{formatDay(value)}</dd>
      </>
    ) : null;
  return (
    <li className="card">
      <article aria-labelledby={headingId}>
        <div className="card-row">
          <h2 id={headingId}>{TYPE_LABELS[a.type] ?? humanise(a.type)}</h2>
          <StatusBadge status={a.status} />
        </div>
        <dl className="details">
          <dt>Decision</dt>
          <dd>{a.decisionText ?? <span className="muted">No decision yet</span>}</dd>
          {row('Started', a.createdAt)}
          {row('Decided', a.decidedAt)}
          {a.status === 'DECIDED' && row('Appeal possible until', a.appealDeadline)}
          {row('Appeal lodged', a.appealedAt)}
          {a.status === 'APPEALED' && row('Appeal decision due', a.appealDecisionDueAt)}
          {row('Appeal decided', a.appealDecidedAt)}
        </dl>
        {canAppeal(a, today) && (
          <form className="form" noValidate onSubmit={onAppeal} aria-label={`Appeal: ${TYPE_LABELS[a.type] ?? humanise(a.type)}`}>
            <h3>Appeal on behalf of the employee</h3>
            <p className="muted small">
              Appeals must be lodged within {APPEAL_WINDOW_DAYS} days of the decision. A CMO who did not make the original
              decision reviews the appeal within 30 days.
            </p>
            {message && <Alert kind="error">{message}</Alert>}
            <TextAreaField
              id={ids.grounds}
              label="Grounds for the appeal"
              required
              max={APPEAL_LIMITS.grounds}
              value={form.grounds}
              onChange={(v) => onForm({ ...form, grounds: v })}
              hint={`For example: procedural points, new information about available roles. ${GUIDANCE}`}
              error={errors.grounds}
              rows={4}
            />
            <InputField
              id={ids.secondOpinionBy}
              label="Second opinion by (optional)"
              hint="Name of the independent physician or provider giving a second opinion, if any."
              maxLength={APPEAL_LIMITS.secondOpinionBy}
              value={form.secondOpinionBy}
              onChange={(v) => onForm({ ...form, secondOpinionBy: v })}
              error={errors.secondOpinionBy}
            />
            <button type="submit" disabled={busy}>
              {busy ? 'Sending…' : 'Lodge appeal'}
            </button>
          </form>
        )}
      </article>
    </li>
  );
}

export function IhrAssessments({ client, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const list = useAsync(async () => toIhrAssessments(await client.api<unknown>(`/cases/${encodeURIComponent(caseId)}/ihr-assessments`)), onSessionExpired);
  const [forms, setForms] = useState<Record<string, AppealForm>>({});
  const [errors, setErrors] = useState<Record<string, FieldErrors>>({});
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string>();
  const [done, setDone] = useState<string>();
  const today = todayIso();

  async function appeal(e: FormEvent, a: IhrAssessment) {
    e.preventDefault();
    const f = forms[a.id] ?? emptyAppealForm();
    const ids = appealIds(a.id);
    setDone(undefined);
    const v = validateAppeal(f);
    setErrors((x) => ({ ...x, [a.id]: v }));
    setMessages((m) => ({ ...m, [a.id]: '' }));
    const focus = (errs: FieldErrors) => focusFirstError(APPEAL_FIELDS.filter((k) => errs[k]).map((k) => ids[k]));
    if (Object.keys(v).length) {
      focus(v);
      return;
    }
    setBusy(a.id);
    const r = await submitAction(client, `/ihr-assessments/${encodeURIComponent(a.id)}/appeal`, buildAppeal(f), APPEAL_FIELDS, { failed: 'The appeal was not lodged' });
    setBusy(undefined);
    if (r.ok) {
      setForms((all) => ({ ...all, [a.id]: emptyAppealForm() }));
      setDone('Appeal lodged. The CMO will decide within 30 days.');
      list.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors((x) => ({ ...x, [a.id]: r.fieldErrors }));
    setMessages((m) => ({ ...m, [a.id]: r.message }));
    focus(r.fieldErrors);
  }

  return (
    <section>
      <p>
        <a href="#/">← Back to team cases</a>
      </p>
      <PageTitle>Ill-health retirement and redeployment</PageTitle>
      <CaseHeader client={client} caseId={caseId} onSessionExpired={onSessionExpired} />
      <NoClinicalInfoNote>
        HR sees the type, status, the CMO’s functional decision and dates only – medical findings stay with Occupational
        Health.
      </NoClinicalInfoNote>
      {done && <Alert kind="success">{done}</Alert>}
      {list.loading && <Loading />}
      {!!list.error && <Alert kind="error">{describeError(list.error)}</Alert>}
      {list.data && list.data.length === 0 && <p className="muted">No ill-health retirement or redeployment assessments for this case.</p>}
      {list.data && list.data.length > 0 && (
        <ul className="card-list">
          {list.data.map((a) => (
            <IhrCard
              key={a.id}
              assessment={a}
              today={today}
              form={forms[a.id] ?? emptyAppealForm()}
              errors={errors[a.id] ?? {}}
              message={messages[a.id] || undefined}
              busy={busy === a.id}
              onForm={(f) => {
                setForms((all) => ({ ...all, [a.id]: f }));
                setErrors((x) => ({ ...x, [a.id]: {} }));
              }}
              onAppeal={(e) => void appeal(e, a)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
