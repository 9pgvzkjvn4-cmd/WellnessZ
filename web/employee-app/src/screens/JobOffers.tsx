import { useState } from 'react';
import { canRespond, fetchMyJobOffers, MAX_COMMENT, respondByPassed, respondToOffer, type MyJobOffer } from '../api/portal';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ConfirmPanel, ContactLines, EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { errorText, labelOf, portalStrings, type PortalStrings } from '../i18n/portal';
import type { PortalScreenProps } from './types';

export interface PendingResponse {
  offerId: string;
  accept: boolean;
}

export interface JobOfferListProps {
  t: PortalStrings;
  lang: string;
  offers: readonly MyJobOffer[];
  comments: Readonly<Record<string, string>>;
  pending?: PendingResponse;
  busy: boolean;
  now?: Date;
  onComment: (offerId: string, value: string) => void;
  /** First step: ask for confirmation. */
  onChoose: (offerId: string, accept: boolean) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Hook-free view of the electronic job offers with accept / decline (E05-BL-008). */
export function JobOfferList({ t, lang, offers, comments, pending, busy, now, onComment, onChoose, onConfirm, onCancel }: JobOfferListProps) {
  if (offers.length === 0) return <EmptyState title={t.offersEmpty} hint={t.offersEmptyHint} />;
  return (
    <ul className="card-list" aria-label={t.offersTitle}>
      {offers.map((o) => {
        const hid = `offer-${o.id}`;
        const open = canRespond(o);
        const confirming = pending?.offerId === o.id ? pending : undefined;
        const comment = comments[o.id] ?? '';
        return (
          <li key={o.id} className="card offer">
            <article aria-labelledby={hid}>
              <div className="card-row">
                <h2 id={hid} className="card-title">
                  {o.jobTitle ?? o.jobCode ?? t.offersTitle}
                </h2>
                <StatusBadge status={`offer-${o.status}`} label={labelOf(t.offerStatus, o.status)} />
              </div>
              <dl className="case-facts offer-facts">
                {o.jobTitle && o.jobCode && (
                  <>
                    <dt>{t.offerRole}</dt>
                    <dd>
                      {o.jobTitle} (<bdi className="mono">{o.jobCode}</bdi>)
                    </dd>
                  </>
                )}
                {o.duties && (
                  <>
                    <dt>{t.offerDuties}</dt>
                    <dd className="advice-text">{o.duties}</dd>
                  </>
                )}
                {o.location && (
                  <>
                    <dt>{t.offerLocation}</dt>
                    <dd>{o.location}</dd>
                  </>
                )}
                {o.shift && (
                  <>
                    <dt>{t.offerShift}</dt>
                    <dd>{o.shift}</dd>
                  </>
                )}
                {o.hoursPerWeek !== undefined && (
                  <>
                    <dt>{t.offerHoursLabel}</dt>
                    <dd>{t.offerHours(o.hoursPerWeek)}</dd>
                  </>
                )}
                {(o.payBasis || o.payNote) && (
                  <>
                    <dt>{t.offerPay}</dt>
                    <dd>
                      {o.payBasis && labelOf(t.payBasis, o.payBasis)}
                      {o.payBasis && o.payNote ? ' – ' : ''}
                      {o.payNote}
                    </dd>
                  </>
                )}
                <dt>{t.offerStart}</dt>
                <dd>{formatDay(o.startDate, lang)}</dd>
                <dt>{t.offerEnd}</dt>
                <dd>{o.endDate ? formatDay(o.endDate, lang) : t.offerOpenEnded}</dd>
                {o.respondBy && (
                  <>
                    <dt>{t.offerRespondBy}</dt>
                    <dd>{/^\d{4}-\d{2}-\d{2}$/.test(o.respondBy) ? formatDay(o.respondBy, lang) : formatDate(o.respondBy, lang)}</dd>
                  </>
                )}
                {o.contact && (
                  <>
                    <dt>{t.offerContact}</dt>
                    <dd>
                      <ContactLines contact={o.contact} callLabel={t.declCall} emailLabel={t.declEmail} />
                    </dd>
                  </>
                )}
              </dl>

              {o.restrictions.length > 0 && (
                <>
                  <h3 className="sub-title">{t.offerRestrictions}</h3>
                  <p className="muted small">{t.offerRestrictionsHint}</p>
                  <ul className="restriction-list">
                    {o.restrictions.map((r, j) => (
                      <li key={`${r.code ?? ''}-${j}`}>{r.text}</li>
                    ))}
                  </ul>
                </>
              )}

              {!open && o.respondedAt && (
                <div className="ack-box" role="status">
                  <p>
                    {o.status === 'ACCEPTED' ? '✓ ' : ''}
                    {t.offerRespondedOn(formatDate(o.respondedAt, lang))}
                  </p>
                  {o.comment && <p className="small advice-text">{o.comment}</p>}
                </div>
              )}

              {open && (
                <div className="form offer-respond">
                  {respondByPassed(o.respondBy, now) && <Alert kind="info">{t.offerRespondByPassed}</Alert>}
                  <label htmlFor={`${hid}-comment`}>{t.offerComment}</label>
                  <textarea
                    id={`${hid}-comment`}
                    rows={3}
                    maxLength={MAX_COMMENT}
                    autoComplete="off"
                    value={comment}
                    disabled={busy}
                    aria-describedby={`${hid}-comment-hint`}
                    onChange={(e) => onComment(o.id, e.target.value)}
                  />
                  <p id={`${hid}-comment-hint`} className="muted small">
                    {t.offerCommentHint}
                  </p>
                  {confirming ? (
                    <ConfirmPanel
                      id={`${hid}-confirm`}
                      title={confirming.accept ? t.offerConfirmAcceptTitle : t.offerConfirmDeclineTitle}
                      confirmLabel={confirming.accept ? t.offerConfirmYesAccept : t.offerConfirmYesDecline}
                      cancelLabel={t.cancel}
                      busy={busy}
                      busyLabel={t.sending}
                      danger={!confirming.accept}
                      onConfirm={onConfirm}
                      onCancel={onCancel}
                    >
                      <p>{confirming.accept ? t.offerConfirmAccept : t.offerConfirmDecline}</p>
                    </ConfirmPanel>
                  ) : (
                    <div className="actions">
                      <button type="button" disabled={busy || !!pending} onClick={() => onChoose(o.id, true)}>
                        {t.offerAccept}
                      </button>
                      <button type="button" className="secondary" disabled={busy || !!pending} onClick={() => onChoose(o.id, false)}>
                        {t.offerDecline}
                      </button>
                    </div>
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

/** "Job offers": `GET /me/job-offers`, `POST /me/job-offers/{id}/respond {accept, comment?}`. */
export function JobOffers({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = portalStrings(lang);
  const offers = useAsync(() => fetchMyJobOffers(client), onSessionExpired);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [pending, setPending] = useState<PendingResponse>();
  const [responded, setResponded] = useState<Record<string, MyJobOffer>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const list = (offers.data ?? []).map((o) => responded[o.id] ?? o);

  const onConfirm = async () => {
    if (!pending) return;
    const offer = list.find((o) => o.id === pending.offerId);
    if (!offer) return;
    setBusy(true);
    setMessage(undefined);
    const comment = comments[offer.id]?.trim() || undefined;
    try {
      const updated = await respondToOffer(client, offer.id, pending.accept, comment);
      setResponded((r) => ({
        ...r,
        [offer.id]: {
          ...offer,
          ...(updated ?? {}),
          status: updated?.status && updated.status !== 'ISSUED' ? updated.status : pending.accept ? 'ACCEPTED' : 'DECLINED',
          respondedAt: updated?.respondedAt ?? new Date().toISOString(),
          comment: updated?.comment ?? comment,
          restrictions: updated?.restrictions.length ? updated.restrictions : offer.restrictions,
        },
      }));
      setMessage({ kind: 'success', text: pending.accept ? t.offerAcceptedOk : t.offerDeclinedOk });
      setPending(undefined);
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: errorText(t, e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.offersTitle}</PageTitle>
      <p className="lead">{t.offersIntro}</p>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      {offers.loading && <Loading label={t.loading} />}
      {!!offers.error && <Alert kind="error">{errorText(t, offers.error)}</Alert>}
      {offers.data && (
        <JobOfferList
          t={t}
          lang={lang}
          offers={list}
          comments={comments}
          pending={pending}
          busy={busy}
          onComment={(id, v) => setComments((c) => ({ ...c, [id]: v }))}
          onChoose={(offerId, accept) => {
            setMessage(undefined);
            setPending({ offerId, accept });
          }}
          onConfirm={() => void onConfirm()}
          onCancel={() => setPending(undefined)}
        />
      )}
    </PortalSection>
  );
}
