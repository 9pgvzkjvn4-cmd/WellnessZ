import { useState } from 'react';
import { fetchMyConsents, fetchMyDisclosures, isWithdrawn, withdrawConsent, type Disclosure } from '../api/portal';
import type { Consent } from '../api/types';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate } from '../components/format';
import { ConfirmPanel, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { errorText, labelOf, portalStrings, type PortalStrings } from '../i18n/portal';
import type { PortalScreenProps } from './types';

/** Hook-free disclosure log (E03-BL-022): date, recipient, document, purpose, ACTIVE / REVOKED. */
export function DisclosureLog({ t, lang, rows }: { t: PortalStrings; lang: string; rows: readonly Disclosure[] }) {
  if (rows.length === 0) return <p className="muted empty-state">{t.disclosuresEmpty}</p>;
  return (
    <ul className="card-list disclosure-list" aria-label={t.disclosuresTitle}>
      {rows.map((d) => {
        const recipientType = labelOf(t.recipientTypes, d.recipientType);
        return (
          <li key={d.key} className={`card disclosure ${d.status === 'REVOKED' ? 'history' : ''}`}>
            <div className="card-row">
              <strong>
                {d.recipientName ? <bdi>{d.recipientName}</bdi> : recipientType}
                {d.recipientName && recipientType && <span className="muted small"> · {recipientType}</span>}
              </strong>
              <StatusBadge status={d.status} label={labelOf(t.disclosureStatusLabels, d.status)} />
            </div>
            <dl className="case-facts">
              <dt>{t.disclosureDate}</dt>
              <dd>{formatDate(d.at, lang)}</dd>
              <dt>{t.disclosureDocument}</dt>
              <dd>{d.documentTitle ?? '–'}</dd>
              <dt>{t.disclosurePurpose}</dt>
              <dd>{labelOf(t.purposes, d.purpose, '–')}</dd>
            </dl>
          </li>
        );
      })}
    </ul>
  );
}

export interface ConsentWithdrawalProps {
  t: PortalStrings;
  lang: string;
  consents: readonly Consent[];
  pendingId?: string;
  busy: boolean;
  onWithdraw: (c: Consent) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Hook-free list of active consents with a two-step withdrawal that explains the effect on partner access. */
export function ConsentWithdrawal({ t, lang, consents, pendingId, busy, onWithdraw, onConfirm, onCancel }: ConsentWithdrawalProps) {
  const active = consents.filter((c) => !isWithdrawn(c));
  if (active.length === 0) return <p className="muted">{t.consentsEmpty}</p>;
  return (
    <ul className="card-list" aria-label={t.consentsTitle}>
      {active.map((c) => {
        const purpose = labelOf(t.purposes, c.purpose);
        return (
          <li key={c.id} className="card">
            <strong>{purpose}</strong>
            <p className="muted small">{t.consentGiven(formatDate(c.grantedAt, lang))}</p>
            {pendingId === c.id ? (
              <ConfirmPanel
                id={`consent-${c.id}`}
                title={t.withdrawTitle(purpose)}
                confirmLabel={t.withdrawConfirm}
                cancelLabel={t.cancel}
                busy={busy}
                busyLabel={t.sending}
                danger
                onConfirm={onConfirm}
                onCancel={onCancel}
              >
                <p>{t.withdrawExplain}</p>
                <ul>
                  <li>
                    <strong>{t.withdrawPartner}</strong>
                  </li>
                  <li>{t.withdrawLaw}</li>
                </ul>
              </ConfirmPanel>
            ) : (
              <button type="button" className="secondary small-button" disabled={busy || !!pendingId} onClick={() => onWithdraw(c)}>
                {t.withdraw}
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/**
 * "Who received my information" (E03-BL-022): `GET /me/disclosures` + consents (`GET /consents/me`,
 * `DELETE /consents/{id}`). After a withdrawal both lists are reloaded so the revoked partner shares show up.
 */
export function Privacy({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = portalStrings(lang);
  const disclosures = useAsync(() => fetchMyDisclosures(client), onSessionExpired);
  const consents = useAsync(() => fetchMyConsents(client), onSessionExpired);
  const [pendingId, setPendingId] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const onConfirm = async () => {
    if (!pendingId) return;
    setBusy(true);
    setMessage(undefined);
    try {
      await withdrawConsent(client, pendingId);
      setPendingId(undefined);
      setMessage({ kind: 'success', text: t.withdrawnOk });
      consents.reload();
      disclosures.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: errorText(t, e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.privacyTitle}</PageTitle>
      <p className="lead">{t.privacyIntro}</p>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}

      <h2>{t.disclosuresTitle}</h2>
      {disclosures.loading && <Loading label={t.loading} />}
      {!!disclosures.error && <Alert kind="error">{errorText(t, disclosures.error)}</Alert>}
      {disclosures.data && <DisclosureLog t={t} lang={lang} rows={disclosures.data} />}

      <h2>{t.consentsTitle}</h2>
      <p className="muted">{t.consentsIntro}</p>
      {consents.loading && <Loading label={t.loading} />}
      {!!consents.error && <Alert kind="error">{errorText(t, consents.error)}</Alert>}
      {consents.data && (
        <ConsentWithdrawal
          t={t}
          lang={lang}
          consents={consents.data}
          pendingId={pendingId}
          busy={busy}
          onWithdraw={(c) => {
            setMessage(undefined);
            setPendingId(c.id);
          }}
          onConfirm={() => void onConfirm()}
          onCancel={() => setPendingId(undefined)}
        />
      )}
      <p>
        <a href="#/consents">{t.consentManage}</a>
      </p>
    </PortalSection>
  );
}
