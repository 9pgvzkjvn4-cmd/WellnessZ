import { useState } from 'react';
import { acknowledgeRtwPlan, fetchMyRtwPlans, MAX_COMMENT, type MyRtwPlan } from '../api/portal';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ContactLines, EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { errorText, labelOf, portalStrings, type PortalStrings } from '../i18n/portal';
import type { PortalScreenProps } from './types';

export interface RtwPlanListProps {
  t: PortalStrings;
  lang: string;
  plans: readonly MyRtwPlan[];
  comments: Readonly<Record<string, string>>;
  busyId?: string;
  onComment: (planId: string, value: string) => void;
  onAgree: (plan: MyRtwPlan) => void;
}

/** Hook-free view of the active RTW plans with the employee acknowledgement (E05-BL-007). */
export function RtwPlanList({ t, lang, plans, comments, busyId, onComment, onAgree }: RtwPlanListProps) {
  if (plans.length === 0) return <EmptyState title={t.rtwEmpty} hint={t.rtwEmptyHint} />;
  return (
    <ul className="card-list" aria-label={t.rtwTitle}>
      {plans.map((p) => {
        const hid = `rtw-${p.id}`;
        const comment = comments[p.id] ?? '';
        return (
          <li key={p.id} className="card rtw-plan">
            <article aria-labelledby={hid}>
              <div className="card-row">
                <h2 id={hid} className="card-title">
                  {t.rtwTitle}
                  {p.caseRef && (
                    <span className="muted small case-ref-meta">
                      {' '}
                      · <bdi className="mono">{p.caseRef}</bdi>
                    </span>
                  )}
                </h2>
                <StatusBadge status={p.status} label={labelOf(t.rtwStatus, p.status)} />
              </div>
              <dl className="case-facts">
                <dt>{t.rtwStart}</dt>
                <dd>{formatDay(p.startDate, lang)}</dd>
                {p.nextReviewDate && (
                  <>
                    <dt>{t.rtwNextReview}</dt>
                    <dd>{formatDay(p.nextReviewDate, lang)}</dd>
                  </>
                )}
                {p.goal && (
                  <>
                    <dt>{t.rtwGoal}</dt>
                    <dd className="advice-text">{p.goal}</dd>
                  </>
                )}
              </dl>

              {p.phases.length > 0 && (
                <>
                  <h3 className="sub-title">{t.rtwPhases}</h3>
                  <ol className="phase-list">
                    {p.phases.map((ph, i) => (
                      <li key={`${ph.fromWeek}-${i}`} className="phase">
                        <div className="card-row">
                          <strong>{t.rtwWeeks(ph.fromWeek, ph.toWeek)}</strong>
                          {ph.hoursPerDay !== undefined && <span className="badge">{t.rtwHoursPerDay(ph.hoursPerDay)}</span>}
                        </div>
                        {ph.duties && (
                          <p className="advice-text">
                            <span className="muted">{t.rtwDuties}: </span>
                            {ph.duties}
                          </p>
                        )}
                        <p className="muted small phase-restr-title">{t.rtwRestrictions}</p>
                        {ph.restrictions.length ? (
                          <ul className="restriction-list">
                            {ph.restrictions.map((r, j) => (
                              <li key={`${r.code ?? ''}-${j}`}>{r.text}</li>
                            ))}
                          </ul>
                        ) : (
                          <p className="small">{t.rtwNoRestrictions}</p>
                        )}
                      </li>
                    ))}
                  </ol>
                </>
              )}

              {p.reviews.length > 0 && (
                <>
                  <h3 className="sub-title">{t.rtwReviews}</h3>
                  <ul className="review-list">
                    {p.reviews.map((r, i) => (
                      <li key={r.id ?? `${r.dueDate}-${i}`}>
                        {formatDay(r.dueDate, lang)}
                        {r.completedAt && <span className="muted"> – {t.rtwReviewDone(formatDay(r.completedAt, lang))}</span>}
                      </li>
                    ))}
                  </ul>
                </>
              )}

              <p className="small rtw-contact">
                <span className="muted">{t.rtwContact}: </span>
                {p.contact ? (
                  <ContactLines contact={p.contact} callLabel={t.declCall} emailLabel={t.declEmail} />
                ) : (
                  <a href="#/refer">{t.rtwContactFallback}</a>
                )}
              </p>
              {p.managerAckAt && <p className="muted small">{t.rtwManagerAck(formatDate(p.managerAckAt, lang))}</p>}

              {p.employeeAck ? (
                <div className="ack-box" role="status">
                  <p>✓ {t.rtwAgreedOn(formatDate(p.employeeAck.at, lang))}</p>
                  {p.employeeAck.comment && (
                    <p className="small">
                      <span className="muted">{t.rtwYourComment}: </span>
                      <span className="advice-text">{p.employeeAck.comment}</span>
                    </p>
                  )}
                </div>
              ) : (
                p.status === 'ACTIVE' && (
                  <form
                    className="form ack-form"
                    onSubmit={(e) => {
                      e.preventDefault();
                      onAgree(p);
                    }}
                  >
                    <label htmlFor={`${hid}-comment`}>{t.rtwComment}</label>
                    <textarea
                      id={`${hid}-comment`}
                      rows={3}
                      maxLength={MAX_COMMENT}
                      autoComplete="off"
                      value={comment}
                      aria-describedby={`${hid}-comment-hint`}
                      onChange={(e) => onComment(p.id, e.target.value)}
                    />
                    <p id={`${hid}-comment-hint`} className="muted small">
                      {t.rtwCommentHint}
                    </p>
                    <button type="submit" disabled={busyId === p.id}>
                      {busyId === p.id ? t.rtwAgreeing : t.rtwAgree}
                    </button>
                    <p className="muted small">{t.rtwDisagreeHint}</p>
                  </form>
                )
              )}
            </article>
          </li>
        );
      })}
    </ul>
  );
}

/** "My return to work": `GET /me/rtw-plans`, `POST /me/rtw-plans/{id}/acknowledge {agree:true, comment?}`. */
export function MyReturnToWork({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = portalStrings(lang);
  const plans = useAsync(() => fetchMyRtwPlans(client), onSessionExpired);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [agreed, setAgreed] = useState<Record<string, NonNullable<MyRtwPlan['employeeAck']>>>({});
  const [busyId, setBusyId] = useState<string>();
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const list = (plans.data ?? []).map((p) => (agreed[p.id] ? { ...p, employeeAck: agreed[p.id] } : p));

  const onAgree = async (p: MyRtwPlan) => {
    setBusyId(p.id);
    setMessage(undefined);
    const comment = comments[p.id]?.trim() || undefined;
    try {
      await acknowledgeRtwPlan(client, p.id, comment);
      setAgreed((a) => ({ ...a, [p.id]: { at: new Date().toISOString(), agree: true, comment } }));
      setMessage({ kind: 'success', text: t.rtwAgreedOk });
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: errorText(t, e) });
    } finally {
      setBusyId(undefined);
    }
  };

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.rtwTitle}</PageTitle>
      <p className="lead">{t.rtwIntro}</p>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      {plans.loading && <Loading label={t.loading} />}
      {!!plans.error && <Alert kind="error">{errorText(t, plans.error)}</Alert>}
      {plans.data && (
        <RtwPlanList
          t={t}
          lang={lang}
          plans={list}
          comments={comments}
          busyId={busyId}
          onComment={(id, v) => setComments((c) => ({ ...c, [id]: v }))}
          onAgree={(p) => void onAgree(p)}
        />
      )}
    </PortalSection>
  );
}
