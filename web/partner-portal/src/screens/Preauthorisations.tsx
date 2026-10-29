import { useState, type FormEvent } from 'react';
import type { AuthClient } from '../auth/AuthClient';
import { documentContentUrl, documentFileName } from '../api/documents';
import { describeError, type FieldErrors } from '../api/errors';
import {
  APPROVAL_REF_MAX,
  buildDecision,
  decide,
  DECISIONS,
  isDecidable,
  isSlaOverdue,
  loadPreauths,
  NOTE_MAX,
  PREAUTH_STATUSES,
  type Decision,
  type DecisionForm,
  type Preauth,
} from '../api/preauth';
import { focusFirstError } from '../components/focus';
import { formatDateTime, formatDay, formatMoney, humanise, idPart, localDate } from '../components/format';
import { InputField, TextAreaField } from '../components/FormFields';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { orgLabel, type ScreenProps } from './types';

const DECISION_LABELS: Record<Decision, string> = { APPROVED: S.preauth.approve, PARTIALLY_APPROVED: S.preauth.partial, DECLINED: S.preauth.decline };

/** Hook-free summary of one request. */
export function PreauthSummary({ p, now }: { p: Preauth; now: Date }) {
  return (
    <>
      <div className="card-row">
        <h2 className="card-title">
          {p.ref}
          {p.displayName && ` · ${p.displayName}`}
        </h2>
        <span>
          <StatusBadge status={p.status} />
          {isSlaOverdue(p, now) && <StatusBadge status="overdue" label={S.preauth.overdue} />}
        </span>
      </div>
      <dl className="details">
        <dt>{S.preauth.service}</dt>
        <dd>{p.serviceCode ? humanise(p.serviceCode) : S.app.noValue}</dd>
        <dt>{S.preauth.provider}</dt>
        <dd>{p.providerName ?? p.providerCode ?? S.app.noValue}</dd>
        <dt>{S.preauth.estimate}</dt>
        <dd>{formatMoney(p.estimate)}</dd>
        <dt>{S.preauth.submitted}</dt>
        <dd>{formatDateTime(p.submittedAt)}</dd>
        {isDecidable(p) ? (
          <>
            <dt>{S.preauth.slaDue}</dt>
            <dd>{formatDateTime(p.slaDueAt)}</dd>
          </>
        ) : (
          <>
            <dt>{S.preauth.decidedAt}</dt>
            <dd>{formatDateTime(p.decidedAt)}</dd>
            {p.approvedAmount && (
              <>
                <dt>{S.preauth.approvedAmount}</dt>
                <dd>{formatMoney(p.approvedAmount)}</dd>
              </>
            )}
            {p.approvalRef && (
              <>
                <dt>{S.preauth.approvalRef}</dt>
                <dd>{p.approvalRef}</dd>
              </>
            )}
            {p.validUntil && (
              <>
                <dt>{S.preauth.validUntil}</dt>
                <dd>{formatDay(p.validUntil)}</dd>
              </>
            )}
          </>
        )}
      </dl>
      {p.documentId && (
        <p className="small">
          <a href={documentContentUrl(p.documentId)} download={documentFileName({ id: p.documentId })}>
            {S.preauth.request}
          </a>{' '}
          <span className="muted">{S.preauth.requestHint}</span>
        </p>
      )}
    </>
  );
}

const EMPTY: DecisionForm = { status: '', approvalRef: '', amount: '', validUntil: '', note: '' };

function DecisionFormView({ client, p, onDone, onSessionExpired }: { client: Pick<AuthClient, 'api'>; p: Preauth; onDone: () => void; onSessionExpired: () => void }) {
  const [f, setF] = useState<DecisionForm>(EMPTY);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const px = `pa-${idPart(p.id)}`;
  const ids: Record<string, string> = { status: `${px}-status-APPROVED`, approvalRef: `${px}-ref`, approvedAmount: `${px}-amount`, validUntil: `${px}-valid`, note: `${px}-note` };

  async function submitForm(e: FormEvent) {
    e.preventDefault();
    const { body, errors: errs } = buildDecision(p, f);
    setErrors(errs);
    setMessage(undefined);
    if (!body) return focusFirstError(Object.keys(errs).map((k) => ids[k] ?? ''));
    setBusy(true);
    const res = await decide(client, p.id, body, S.preauth.failed);
    setBusy(false);
    if (res.ok) return onDone();
    if (res.sessionExpired) onSessionExpired();
    setMessage(res.message);
    setErrors(res.fieldErrors);
    focusFirstError(Object.keys(res.fieldErrors).map((k) => ids[k] ?? ''));
  }

  const approved = f.status === 'APPROVED' || f.status === 'PARTIALLY_APPROVED';
  return (
    <form className="form" onSubmit={(e) => void submitForm(e)} noValidate>
      {message && <Alert kind="error">{message}</Alert>}
      <fieldset className={errors.status ? 'field-invalid' : undefined} aria-describedby={errors.status ? `${px}-status-error` : undefined}>
        <legend>
          {S.preauth.decide}
          <span className="req" aria-hidden="true">
            {' '}
            *
          </span>
        </legend>
        {errors.status && (
          <p id={`${px}-status-error`} className="field-error">
            {errors.status}
          </p>
        )}
        <div className="inline-options">
          {DECISIONS.map((d) => (
            <label key={d} className="radio" htmlFor={`${px}-status-${d}`}>
              <input id={`${px}-status-${d}`} type="radio" name={`${px}-status`} value={d} checked={f.status === d} onChange={() => setF({ ...f, status: d })} />
              {DECISION_LABELS[d]}
            </label>
          ))}
        </div>
      </fieldset>
      {f.status === 'PARTIALLY_APPROVED' && (
        <InputField
          id={ids.approvedAmount!}
          label={`${S.preauth.amount}${p.estimate ? ` (${p.estimate.currency})` : ''}`}
          value={f.amount}
          onChange={(v) => setF({ ...f, amount: v })}
          required
          inputMode="decimal"
          hint={p.estimate ? `${S.preauth.estimate}: ${formatMoney(p.estimate)}` : undefined}
          error={errors.approvedAmount}
        />
      )}
      {approved && (
        <>
          <InputField id={ids.approvalRef!} label={S.preauth.approvalRef} value={f.approvalRef} onChange={(v) => setF({ ...f, approvalRef: v })} maxLength={APPROVAL_REF_MAX} error={errors.approvalRef} />
          <InputField id={ids.validUntil!} type="date" label={S.preauth.validUntil} value={f.validUntil} onChange={(v) => setF({ ...f, validUntil: v })} min={localDate()} error={errors.validUntil} />
        </>
      )}
      <TextAreaField id={ids.note!} label={S.preauth.note} value={f.note} onChange={(v) => setF({ ...f, note: v })} max={NOTE_MAX} hint={S.preauth.noteHint} error={errors.note} rows={2} />
      <button type="submit" disabled={busy}>
        {S.preauth.submit}
      </button>
    </form>
  );
}

export function Preauthorisations({ client, me, onSessionExpired }: ScreenProps) {
  const [status, setStatus] = useState('SUBMITTED');
  const [saved, setSaved] = useState(false);
  const data = useAsync(() => loadPreauths(client, status), onSessionExpired);
  const now = new Date();
  return (
    <section>
      <PageTitle>{S.preauth.title}</PageTitle>
      <p className="lead">{S.preauth.lead}</p>
      <ConfidentialityNote>{S.confidentiality.adjuster(orgLabel(me))}</ConfidentialityNote>
      <form className="filters" role="search" aria-label="Filter pre-authorisations" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="pa-status">{S.preauth.status}</label>
          <select
            id="pa-status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setSaved(false);
              data.reload();
            }}
          >
            <option value="">{S.preauth.allStatuses}</option>
            {PREAUTH_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s === 'SUBMITTED' ? S.preauth.pending : humanise(s)}
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </form>
      {saved && <Alert kind="success">{S.preauth.saved}</Alert>}
      {data.loading && !data.data && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && data.data.length === 0 && <p className="muted">{S.preauth.empty}</p>}
      {data.data && data.data.length > 0 && (
        <ul className="card-list">
          {data.data.map((p) => (
            <li key={p.id} className="card">
              <PreauthSummary p={p} now={now} />
              {isDecidable(p) && (
                <DecisionFormView
                  client={client}
                  p={p}
                  onSessionExpired={onSessionExpired}
                  onDone={() => {
                    setSaved(true);
                    data.reload();
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
