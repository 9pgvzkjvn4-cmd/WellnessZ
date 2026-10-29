import { useState } from 'react';
import { ACK_COMMENT_MAX, ACK_FIELDS, buildAck, documentContentUrl, needsManagerAck, toRtwPlans, validateAckComment, type RtwPlan } from '../api/fitness';
import { GUIDANCE, type FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { canAcknowledgeRtw } from '../auth/roles';
import { TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { ackCommentId } from '../components/ids';
import { describeError, formatDate, formatDay, humanise, idPart } from '../components/format';
import { NoClinicalInfoNote, RestrictionList } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface RtwPlanCardProps {
  plan: RtwPlan;
  /** MANAGER may acknowledge; HR sees the plan read-only. */
  canAck: boolean;
  comment: string;
  errors: FieldErrors;
  message?: string;
  busy: boolean;
  onComment: (v: string) => void;
  onAck: () => void;
}

/** E05-BL-007 – one RTW plan (functional content only). Hook-free. */
export function RtwPlanCard({ plan: p, canAck, comment, errors, message, busy, onComment, onAck }: RtwPlanCardProps) {
  const headingId = `rtw-${idPart(p.id)}-title`;
  const weeks = (from?: number, to?: number) => (from === undefined ? '–' : to === undefined || to === from ? `Week ${from}` : `Weeks ${from}–${to}`);
  return (
    <li className="card">
      <article aria-labelledby={headingId}>
        <div className="card-row">
          <h2 id={headingId}>
            {p.employeeName}
            {p.caseRef && <span className="muted small"> · {p.caseRef}</span>}
          </h2>
          <StatusBadge status={p.status} />
        </div>
        <dl className="details">
          <dt>Start date</dt>
          <dd>{formatDay(p.startDate)}</dd>
          <dt>Goal</dt>
          <dd>{p.goal ?? '–'}</dd>
          <dt>Next review</dt>
          <dd>{formatDay(p.nextReviewDate)}</dd>
          <dt>Employee acknowledgement</dt>
          <dd>
            {p.employeeAck?.at
              ? `Acknowledged on ${formatDate(p.employeeAck.at)}${p.employeeAck.agree === false ? ' – does not agree' : p.employeeAck.agree ? ' – agrees' : ''}`
              : 'Not yet acknowledged'}
          </dd>
          <dt>Manager acknowledgement</dt>
          <dd>
            {p.managerAck ? (
              <>
                Acknowledged {p.managerAck.by ? `by ${p.managerAck.by} ` : ''}on {formatDate(p.managerAck.at)}
                {p.managerAck.comment && <span className="muted"> – “{p.managerAck.comment}”</span>}
              </>
            ) : (
              'Awaiting manager acknowledgement'
            )}
          </dd>
        </dl>
        {p.phases.length > 0 && (
          <div className="table-wrap">
            <table>
              <caption>Phases</caption>
              <thead>
                <tr>
                  <th scope="col">Period</th>
                  <th scope="col">Hours per day</th>
                  <th scope="col">Duties</th>
                  <th scope="col">Restrictions</th>
                </tr>
              </thead>
              <tbody>
                {p.phases.map((ph, i) => (
                  <tr key={i}>
                    <th scope="row">{weeks(ph.fromWeek, ph.toWeek)}</th>
                    <td>{ph.hoursPerDay ?? '–'}</td>
                    <td>{ph.duties ?? '–'}</td>
                    <td>
                      <RestrictionList items={ph.restrictions} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {p.reviews.length > 0 && (
          <>
            <h3>Reviews</h3>
            <ul className="plain-list">
              {p.reviews.map((r, i) => (
                <li key={r.id ?? i}>
                  Due {formatDay(r.dueDate)} –{' '}
                  {r.completedAt ? (
                    <>
                      completed {formatDay(r.completedAt)}
                      {r.outcome && <> · {humanise(r.outcome)}</>}
                    </>
                  ) : (
                    <span className="muted">open</span>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
        {p.documentId && (
          <p>
            <a href={documentContentUrl(p.documentId)} download>
              Download plan (PDF)
            </a>
          </p>
        )}
        {canAck && needsManagerAck(p) && (
          <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); onAck(); }} aria-label={`Acknowledge plan for ${p.employeeName}`}>
            {message && <Alert kind="error">{message}</Alert>}
            <TextAreaField
              id={ackCommentId(p.id)}
              label="Comment (optional)"
              max={ACK_COMMENT_MAX}
              value={comment}
              onChange={onComment}
              hint={GUIDANCE}
              error={errors.comment}
              rows={2}
            />
            <button type="submit" disabled={busy}>
              {busy ? 'Saving…' : 'Acknowledge plan'}
            </button>
          </form>
        )}
      </article>
    </li>
  );
}

export function RtwPlans({ client, session, onSessionExpired }: ScreenProps) {
  const plans = useAsync(async () => toRtwPlans(await client.api<unknown>('/workspace/rtw-plans')), onSessionExpired);
  const canAck = canAcknowledgeRtw(session.roles);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, FieldErrors>>({});
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string>();
  const [done, setDone] = useState<string>();

  async function acknowledge(p: RtwPlan) {
    const comment = comments[p.id] ?? '';
    setDone(undefined);
    const v = validateAckComment(comment);
    setErrors((e) => ({ ...e, [p.id]: v }));
    setMessages((m) => ({ ...m, [p.id]: '' }));
    if (v.comment) {
      focusFirstError([ackCommentId(p.id)]);
      return;
    }
    setBusy(p.id);
    const r = await submitAction(client, `/rtw-plans/${encodeURIComponent(p.id)}/manager-acknowledge`, buildAck(comment), ACK_FIELDS, {
      failed: 'The acknowledgement was not saved',
    });
    setBusy(undefined);
    if (r.ok) {
      setDone(`You acknowledged the return-to-work plan for ${p.employeeName}.`);
      plans.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors((e) => ({ ...e, [p.id]: r.fieldErrors }));
    setMessages((m) => ({ ...m, [p.id]: r.message }));
    if (r.fieldErrors.comment) focusFirstError([ackCommentId(p.id)]);
  }

  return (
    <section>
      <PageTitle>Return-to-work plans</PageTitle>
      <p className="lead">
        Phased plans agreed by Occupational Health with the employee.{' '}
        {canAck ? 'Please acknowledge new plans so OH knows you can support them.' : 'HR view – read-only.'}
      </p>
      <NoClinicalInfoNote>Plans show hours, duties and functional restrictions only – no diagnoses or medical details.</NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={plans.reload} disabled={plans.loading}>
          Refresh
        </button>
      </div>
      {done && <Alert kind="success">{done}</Alert>}
      {plans.loading && <Loading />}
      {!!plans.error && <Alert kind="error">{describeError(plans.error)}</Alert>}
      {plans.data && plans.data.length === 0 && <p className="muted">No return-to-work plans in your scope.</p>}
      {plans.data && plans.data.length > 0 && (
        <ul className="card-list">
          {plans.data.map((p) => (
            <RtwPlanCard
              key={p.id}
              plan={p}
              canAck={canAck}
              comment={comments[p.id] ?? ''}
              errors={errors[p.id] ?? {}}
              message={messages[p.id] || undefined}
              busy={busy === p.id}
              onComment={(v) => {
                setComments((c) => ({ ...c, [p.id]: v }));
                setErrors((e) => ({ ...e, [p.id]: {} }));
              }}
              onAck={() => void acknowledge(p)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
