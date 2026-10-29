import { useState } from 'react';
import { awaitingDecision, decideAdviceReport, fetchAdviceQuestions, fetchMyAdviceReports, MAX_COMMENT, type MyAdviceReport } from '../api/occupational';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ConfirmPanel, EmptyState } from '../components/portal';
import { Alert, Loading, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { labelOf } from '../i18n/portal';
import { ohErrorText, ohStrings, type OhStrings } from '../i18n/occupational';
import { dirFor } from '../i18n/strings';
import { hasErrors, validateDecision, type DecisionForm, type FormErrors } from '../intake/occupationalForms';
import type { PortalScreenProps } from './types';

export interface AdviceReportCardProps {
  t: OhStrings;
  lang: string;
  r: MyAdviceReport;
  questions: Readonly<Record<string, string>>;
  form: DecisionForm;
  errors: FormErrors<'decision' | 'comment' | 'declineReason'>;
  confirming: boolean;
  busy: boolean;
  onChange: (patch: Partial<DecisionForm>) => void;
  onContinue: () => void;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * One OH advice report (E06-F05): the full content the requester would receive, a plain explanation of what the
 * manager / HR will and will not see, and – while AWAITING_EMPLOYEE – consent / decline with comment. Hook-free.
 */
export function AdviceReportCard({ t, lang, r, questions, form, errors, confirming, busy, onChange, onContinue, onConfirm, onCancel }: AdviceReportCardProps) {
  const hid = `advr-${r.id}`;
  const role = labelOf(t.advRole, r.requestedByRole, t.advRole.MANAGER);
  const rep = r.report;
  const open = awaitingDecision(r);
  const err = (k: 'decision' | 'comment' | 'declineReason') => (errors[k] ? t.fieldErrors[errors[k]!] : undefined);
  return (
    <article className={`card${open ? ' doc-action' : ''}`} aria-labelledby={hid}>
      <div className="card-row">
        <h3 id={hid} className="card-title">
          {t.advRequestedBy(role)}
          {r.requestRef && (
            <>
              {' '}
              · <bdi className="mono">{r.requestRef}</bdi>
            </>
          )}
        </h3>
        <StatusBadge status={`advice-${r.status}`} label={labelOf(t.advStatus, r.status)} />
      </div>
      {r.createdAt && <p className="muted small">{formatDate(r.createdAt, lang)}</p>}
      {rep && (
        <dl className="case-facts">
          <dt>{t.advFitness}</dt>
          <dd>{rep.fitness ? labelOf(t.fitness, rep.fitness) : '–'}</dd>
          <dt>{t.advAdjustments}</dt>
          <dd>{rep.adjustments.length ? <ul className="restriction-list">{rep.adjustments.map((a, i) => <li key={i}>{a.text}</li>)}</ul> : t.advNone}</dd>
          <dt>{t.advRestrictions}</dt>
          <dd>{rep.restrictions.length ? <ul className="restriction-list">{rep.restrictions.map((a, i) => <li key={i}>{a.text}</li>)}</ul> : t.advNone}</dd>
          <dt>{t.advPrognosis}</dt>
          <dd>{rep.prognosis ? labelOf(t.prognosis, rep.prognosis) : '–'}</dd>
          {rep.expectedReturn && (
            <>
              <dt>{t.advExpectedReturn}</dt>
              <dd>{formatDay(rep.expectedReturn, lang)}</dd>
            </>
          )}
          {rep.reviewDate && (
            <>
              <dt>{t.advReviewDate}</dt>
              <dd>{formatDay(rep.reviewDate, lang)}</dd>
            </>
          )}
        </dl>
      )}
      {rep && rep.answers.length > 0 && (
        <>
          <h4 className="sub-title">{t.advAnswers}</h4>
          <dl className="details">
            {rep.answers.map((a) => (
              <div key={a.code}>
                <dt>{questions[a.code] ?? labelOf({}, a.code)}</dt>
                <dd className="advice-text">{a.text}</dd>
              </div>
            ))}
          </dl>
        </>
      )}
      {rep?.employeeComment && (
        <p className="small">
          <strong>{t.advYourComment}:</strong> <span className="advice-text">{rep.employeeComment}</span>
        </p>
      )}
      {r.status === 'RELEASED' && rep?.releasedAt && <p className="ack-box">{t.advReleasedOn(formatDate(rep.releasedAt, lang))}</p>}
      {r.status === 'WITHHELD' && <p className="ack-box">{t.advWithheld}</p>}

      {open && (
        <div className="form">
          <section className="ack-box" aria-labelledby={`${hid}-sees`}>
            <h4 id={`${hid}-sees`} className="sub-title">
              {t.advWhatSeesTitle(role)}
            </h4>
            <ul>
              <li>{t.advSeesConsent(role)}</li>
              <li>{t.advSeesDecline(role)}</li>
              <li>{t.advNeverSees}</li>
            </ul>
          </section>
          <fieldset aria-describedby={err('decision') ? `${hid}-dec-err` : undefined}>
            <legend>{t.advChoice}</legend>
            {err('decision') && (
              <p id={`${hid}-dec-err`} className="field-error">
                {err('decision')}
              </p>
            )}
            <label className="chip">
              <input type="radio" name={`${hid}-decision`} checked={form.decision === 'CONSENT'} disabled={busy} onChange={() => onChange({ decision: 'CONSENT' })} /> {t.advConsent}
            </label>
            <label className="chip">
              <input type="radio" name={`${hid}-decision`} checked={form.decision === 'DECLINE'} disabled={busy} onChange={() => onChange({ decision: 'DECLINE' })} /> {t.advDecline}
            </label>
          </fieldset>
          {form.decision !== 'DECLINE' && (
            <div className="field">
              <label htmlFor={`${hid}-comment`}>
                {t.advComment} <span className="muted">{t.optional}</span>
              </label>
              <textarea id={`${hid}-comment`} rows={3} maxLength={MAX_COMMENT} value={form.comment} disabled={busy} aria-invalid={!!err('comment')} aria-describedby={`${hid}-comment-hint`} onChange={(e) => onChange({ comment: e.target.value })} />
              <p id={`${hid}-comment-hint`} className="muted small">
                {err('comment') ?? t.advCommentHint}
              </p>
            </div>
          )}
          {form.decision === 'DECLINE' && (
            <div className="field">
              <label htmlFor={`${hid}-reason`}>{t.advDeclineReason}</label>
              <textarea id={`${hid}-reason`} rows={3} maxLength={MAX_COMMENT} value={form.declineReason} disabled={busy} aria-invalid={!!err('declineReason')} aria-describedby={`${hid}-reason-hint`} onChange={(e) => onChange({ declineReason: e.target.value })} />
              <p id={`${hid}-reason-hint`} className={err('declineReason') ? 'field-error' : 'muted small'}>
                {err('declineReason') ?? t.advDeclineReasonHint}
              </p>
            </div>
          )}
          {confirming && form.decision ? (
            <ConfirmPanel
              id={`${hid}-confirm`}
              title={form.decision === 'CONSENT' ? t.advConfirmConsentTitle : t.advConfirmDeclineTitle}
              confirmLabel={t.advConfirmYes}
              cancelLabel={t.back}
              busy={busy}
              busyLabel={t.sending}
              danger={form.decision === 'DECLINE'}
              onConfirm={onConfirm}
              onCancel={onCancel}
            >
              <p>{form.decision === 'CONSENT' ? t.advConfirmConsent(role) : t.advConfirmDecline(role)}</p>
            </ConfirmPanel>
          ) : (
            <div className="actions">
              <button type="button" disabled={busy} onClick={onContinue}>
                {t.advSubmit}
              </button>
            </div>
          )}
        </div>
      )}
    </article>
  );
}

const EMPTY: DecisionForm = { comment: '', declineReason: '' };

/** OH advice reports awaiting / after my decision: `GET /me/advice-reports`, `POST /me/advice-reports/{id}/decision`. */
export function AdviceReports({ client, onSessionExpired, lang }: Omit<PortalScreenProps, 'session'>) {
  const t = ohStrings(lang);
  const reports = useAsync(() => fetchMyAdviceReports(client), onSessionExpired);
  const questions = useAsync(() => fetchAdviceQuestions(client).catch(() => ({}) as Record<string, string>), onSessionExpired);
  const [forms, setForms] = useState<Record<string, DecisionForm>>({});
  const [errors, setErrors] = useState<Record<string, FormErrors<'decision' | 'comment' | 'declineReason'>>>({});
  const [confirming, setConfirming] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function confirm(r: MyAdviceReport) {
    const f = forms[r.id] ?? EMPTY;
    if (!f.decision) return;
    setBusy(true);
    setMessage(undefined);
    try {
      await decideAdviceReport(client, r.id, { decision: f.decision, comment: f.comment, declineReason: f.declineReason });
      setMessage({ kind: 'success', text: f.decision === 'CONSENT' ? t.advConsentedOk : t.advDeclinedOk });
      setConfirming(undefined);
      setForms((x) => ({ ...x, [r.id]: EMPTY }));
      reports.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: ohErrorText(lang, e) });
      setConfirming(undefined);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="advr-title" dir={dirFor(lang)} lang={lang}>
      <h2 id="advr-title">{t.advTitle}</h2>
      <p className="muted">{t.advIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {reports.loading && <Loading />}
      {!!reports.error && <Alert kind="error">{ohErrorText(lang, reports.error)}</Alert>}
      {reports.data && reports.data.length === 0 && <EmptyState title={t.advEmpty} hint={t.advEmptyHint} />}
      {reports.data && reports.data.length > 0 && (
        <ul className="card-list">
          {reports.data.map((r) => (
            <li key={r.id}>
              <AdviceReportCard
                t={t}
                lang={lang}
                r={r}
                questions={questions.data ?? {}}
                form={forms[r.id] ?? EMPTY}
                errors={errors[r.id] ?? {}}
                confirming={confirming === r.id}
                busy={busy}
                onChange={(patch) => {
                  setConfirming(undefined);
                  setForms((x) => ({ ...x, [r.id]: { ...(x[r.id] ?? EMPTY), ...patch } }));
                }}
                onContinue={() => {
                  const v = validateDecision(forms[r.id] ?? EMPTY);
                  setErrors((x) => ({ ...x, [r.id]: v }));
                  if (!hasErrors(v)) setConfirming(r.id);
                }}
                onConfirm={() => void confirm(r)}
                onCancel={() => setConfirming(undefined)}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
