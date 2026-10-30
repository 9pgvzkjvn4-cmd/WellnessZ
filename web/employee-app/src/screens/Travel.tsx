import { useState } from 'react';
import {
  acknowledgeAdvisory,
  fetchAdvisories,
  fetchMyTravel,
  type Advisory,
  type TravelCertificate,
  type TravelConsultation,
  type UpcomingDestination,
} from '../api/prevention';
import { documentContentPath, openDocumentNative } from '../api/portal';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDay } from '../components/format';
import { PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { Fact } from '../components/wellbeing';
import { labelOf } from '../i18n/portal';
import { wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

const joined = (xs: readonly string[]) => (xs.length ? xs.join(', ') : undefined);

export function UpcomingList({ t, lang, items }: { t: WellbeingStrings; lang: string; items: readonly UpcomingDestination[] }) {
  if (items.length === 0) return <p className="muted">{t.trUpcomingEmpty}</p>;
  return (
    <ul className="card-list">
      {items.map((u) => (
        <li key={u.key} className="card">
          <div className="card-row">
            <strong>
              <bdi>{u.name ?? u.code}</bdi>
            </strong>
            <span className="muted small">
              {u.date && formatDay(u.date, lang)}
              {u.dutyRef && <> · <bdi className="mono">{u.dutyRef}</bdi></>}
            </span>
          </div>
          <dl className="case-facts">
            <Fact label={t.trRisks}>{joined(u.risks)}</Fact>
            <Fact label={t.trVaccines}>{joined(u.recommendedVaccines)}</Fact>
            <Fact label={t.trProphylaxis}>{joined(u.prophylaxis)}</Fact>
          </dl>
          {u.advice && (
            <div className="ack-box">
              <strong>{t.trAdvice}</strong>
              <p>{u.advice}</p>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

export function ConsultationList({ t, lang, items }: { t: WellbeingStrings; lang: string; items: readonly TravelConsultation[] }) {
  if (items.length === 0) return <p className="muted">{t.trConsultationsEmpty}</p>;
  return (
    <ul className="card-list">
      {items.map((c) => (
        <li key={c.id} className="card">
          <div className="card-row">
            <strong>
              <bdi>{c.destinations.join(' · ') || t.trTrip}</bdi>
            </strong>
            {c.purpose && <StatusBadge status="active" label={labelOf(t.purposes, c.purpose)} />}
          </div>
          <dl className="case-facts">
            <Fact label={t.trTrip}>{c.departDate && `${formatDay(c.departDate, lang)} – ${c.returnDate ? formatDay(c.returnDate, lang) : '…'}`}</Fact>
            <Fact label={t.trVaccinesGiven}>{joined(c.vaccines)}</Fact>
            <Fact label={t.trProphylaxis}>{joined(c.prophylaxis)}</Fact>
          </dl>
          {c.advice && <p>{c.advice}</p>}
        </li>
      ))}
    </ul>
  );
}

export interface CertificateListProps {
  t: WellbeingStrings;
  lang: string;
  items: readonly TravelCertificate[];
  linkFor: (c: TravelCertificate) => string | undefined;
  onOpen: (c: TravelCertificate) => void;
}

/** Certificates are downloaded through the document service (same-origin BFF link, or fetched natively). Hook-free. */
export function CertificateList(p: CertificateListProps) {
  const { t } = p;
  if (p.items.length === 0) return <p className="muted">{t.trCertificatesEmpty}</p>;
  return (
    <ul className="card-list">
      {p.items.map((c) => {
        const href = p.linkFor(c);
        return (
          <li key={c.documentId} className="card">
            <div className="card-row">
              <span>
                <strong>{c.title ?? t.trCertificateFallback}</strong>
                {c.issuedAt && <span className="muted small"> · {formatDay(c.issuedAt, p.lang)}</span>}
              </span>
              {href ? (
                <a className="button secondary small-button" href={href} download onClick={() => p.onOpen(c)}>
                  {t.trDownload}
                </a>
              ) : (
                <button type="button" className="secondary small-button" onClick={() => p.onOpen(c)}>
                  {t.trDownload}
                </button>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

export interface AdvisoryListProps {
  t: WellbeingStrings;
  lang: string;
  items: readonly Advisory[];
  busy: boolean;
  onAcknowledge: (a: Advisory) => void;
}

export function AdvisoryList(p: AdvisoryListProps) {
  const { t } = p;
  if (p.items.length === 0) return <p className="muted">{t.trAdvisoriesEmpty}</p>;
  return (
    <ul className="card-list">
      {p.items.map((a) => (
        <li key={a.id} className={`card advisory advisory-${a.severity.toLowerCase()}`}>
          <div className="card-row">
            <strong>{a.title}</strong>
            <StatusBadge status={`sev-${a.severity}`} label={labelOf(t.severity, a.severity)} />
          </div>
          {a.body && <p className="advice-text">{a.body}</p>}
          <dl className="case-facts">
            <Fact label={t.trAppliesTo}>{[a.destinationCode, a.baseCode].filter(Boolean).join(' · ') || undefined}</Fact>
            <Fact label={t.trValidUntil}>{a.validUntil && formatDay(a.validUntil, p.lang)}</Fact>
          </dl>
          {a.acknowledgedAt ? (
            <StatusBadge status="doc-acknowledged" label={t.trAcknowledged} />
          ) : (
            <button type="button" disabled={p.busy} onClick={() => p.onAcknowledge(a)}>
              {t.trAcknowledge}
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}

/** `#/travel` – upcoming destinations with advice, consultations, certificates, advisories with acknowledge (E11 §5.1, §5.5). */
export function Travel({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const travel = useAsync(() => fetchMyTravel(client), onSessionExpired);
  const advisories = useAsync(() => fetchAdvisories(client), onSessionExpired);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function run(fn: () => Promise<void>, ok?: string) {
    setBusy(true);
    setMessage(undefined);
    try {
      await fn();
      if (ok) setMessage({ kind: 'success', text: ok });
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: wellbeingErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const open = (c: TravelCertificate) => {
    if (client.directUrl(documentContentPath(c.documentId))) return; // the link itself downloads the PDF
    void run(() => openDocumentNative(client, c.documentId));
  };

  const data = travel.data;
  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.trTitle}</PageTitle>
      <p className="lead">{t.trIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>

      <h2>{t.trAdvisories}</h2>
      {advisories.loading && <Loading />}
      {!!advisories.error && <Alert kind="error">{wellbeingErrorText(lang, advisories.error)}</Alert>}
      {advisories.data && (
        <AdvisoryList
          t={t}
          lang={lang}
          items={advisories.data}
          busy={busy}
          onAcknowledge={(a) =>
            void run(async () => {
              await acknowledgeAdvisory(client, a.id);
              advisories.reload();
            }, t.trAckOk)
          }
        />
      )}

      {travel.loading && <Loading />}
      {!!travel.error && <Alert kind="error">{wellbeingErrorText(lang, travel.error)}</Alert>}
      {data && (
        <>
          <h2>{t.trUpcoming}</h2>
          <UpcomingList t={t} lang={lang} items={data.upcoming} />
          <h2>{t.trCertificates}</h2>
          <CertificateList t={t} lang={lang} items={data.certificates} linkFor={(c) => client.directUrl(documentContentPath(c.documentId))} onOpen={open} />
          <h2>{t.trConsultations}</h2>
          <ConsultationList t={t} lang={lang} items={data.consultations} />
        </>
      )}
    </PortalSection>
  );
}
