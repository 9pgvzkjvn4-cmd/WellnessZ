import { useState } from 'react';
import {
  acknowledgeDocument,
  documentAction,
  documentContentPath,
  documentState,
  fetchMyDocuments,
  openDocumentNative,
  type DocumentState,
  type MyDocument,
} from '../api/portal';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { errorText, labelOf, portalStrings, type PortalStrings } from '../i18n/portal';
import { errorName, log } from '../security/log';
import type { PortalScreenProps } from './types';

export interface DocumentListProps {
  t: PortalStrings;
  lang: string;
  docs: readonly MyDocument[];
  /** Documents opened in this session (memory only). */
  opened: ReadonlySet<string>;
  /** Same-origin link for a document (BFF mode) or undefined (native mode → `onOpen` downloads it). */
  linkFor: (d: MyDocument) => string | undefined;
  busyId?: string;
  openingId?: string;
  onOpen: (d: MyDocument) => void;
  onAcknowledge: (d: MyDocument) => void;
}

const BADGE: Record<DocumentState, keyof PortalStrings | undefined> = {
  ACTION: 'docBadgeAction',
  NEW: 'docBadgeNew',
  ACKNOWLEDGED: 'docBadgeAcknowledged',
  SUPERSEDED: 'docBadgeSuperseded',
  READ: undefined,
};

const fileName = (d: MyDocument) => `${(d.documentNumber ?? d.type).replace(/[^\w.-]+/g, '_')}.pdf`;

/** Hook-free view of the shared documents (E03-BL-015 / E05-BL-003). */
export function DocumentList({ t, lang, docs, opened, linkFor, busyId, openingId, onOpen, onAcknowledge }: DocumentListProps) {
  if (docs.length === 0) return <EmptyState title={t.docsEmpty} hint={t.docsEmptyHint} />;
  return (
    <ul className="card-list" aria-label={t.docsTitle}>
      {docs.map((d) => {
        const state = documentState(d, opened);
        const badgeKey = BADGE[state];
        const typeLabel = labelOf(t.docTypes, d.type);
        const href = linkFor(d);
        const action = documentAction(d);
        const titleId = `doc-${d.id}`;
        return (
          <li key={d.id} className={`card doc-card doc-${state.toLowerCase()}`}>
            <article aria-labelledby={titleId}>
              <div className="card-row">
                <h2 id={titleId} className="card-title">
                  {d.title || typeLabel}
                </h2>
                {badgeKey && <StatusBadge status={`doc-${state}`} label={t[badgeKey] as string} />}
              </div>
              {d.title && d.title !== typeLabel && <p className="muted small doc-type">{typeLabel}</p>}
              <dl className="case-facts">
                {d.documentNumber && (
                  <>
                    <dt>{t.docNumber}</dt>
                    <dd className="mono">
                      <bdi>{d.documentNumber}</bdi>
                      {d.version && d.version > 1 ? ` · ${t.docVersion(d.version)}` : ''}
                    </dd>
                  </>
                )}
                <dt>{t.docIssued}</dt>
                <dd>{formatDay(d.issuedAt, lang)}</dd>
              </dl>

              <div className="actions doc-actions">
                {href ? (
                  <>
                    <a className="button secondary small-button" href={href} target="_blank" rel="noopener noreferrer" onClick={() => onOpen(d)}>
                      {t.docOpen}
                      <span className="visually-hidden"> {t.docsOpensNewTab}</span>
                    </a>
                    <a className="button secondary small-button" href={href} download={fileName(d)} onClick={() => onOpen(d)}>
                      {t.docDownload}
                    </a>
                  </>
                ) : (
                  <button type="button" className="secondary small-button" disabled={openingId === d.id} onClick={() => onOpen(d)}>
                    {openingId === d.id ? t.docOpening : t.docOpen}
                  </button>
                )}
              </div>

              {action === 'ACKNOWLEDGE' && (
                <div className="doc-ack">
                  <button
                    type="button"
                    className="small-button"
                    aria-describedby={`${titleId}-ack-hint`}
                    disabled={busyId === d.id}
                    onClick={() => onAcknowledge(d)}
                  >
                    {busyId === d.id ? t.docAcknowledging : t.docAcknowledge}
                  </button>
                  <p id={`${titleId}-ack-hint`} className="muted small">
                    {t.docAcknowledgeHint}
                  </p>
                </div>
              )}
              {state === 'ACKNOWLEDGED' && d.acknowledgedAt && <p className="muted small ack-done">✓ {t.docAcknowledgedOn(formatDate(d.acknowledgedAt, lang))}</p>}
              {action === 'RTW' && (
                <p className="small">
                  <a href="#/rtw">{t.docSeeRtw}</a>
                </p>
              )}
              {action === 'OFFER' && (
                <p className="small">
                  <a href="#/job-offers">{t.docSeeOffers}</a>
                </p>
              )}
            </article>
          </li>
        );
      })}
    </ul>
  );
}

/** "My documents": `GET /me/documents`, open via `GET /documents/{id}/content`, `POST /me/documents/{id}/acknowledge`. */
export function MyDocuments({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = portalStrings(lang);
  const docs = useAsync(() => fetchMyDocuments(client), onSessionExpired);
  const [acked, setAcked] = useState<Record<string, string>>({});
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set());
  const [busyId, setBusyId] = useState<string>();
  const [openingId, setOpeningId] = useState<string>();
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const list = (docs.data ?? []).map((d) => (acked[d.id] ? { ...d, acknowledgedAt: acked[d.id] } : d));
  const markOpened = (id: string) => setOpened((s) => (s.has(id) ? s : new Set([...s, id])));

  const fail = (e: unknown, text?: string) => {
    if (e instanceof SessionExpiredError) onSessionExpired();
    setMessage({ kind: 'error', text: text && !(e instanceof SessionExpiredError) ? text : errorText(t, e) });
  };

  const onOpen = (d: MyDocument) => {
    markOpened(d.id);
    if (client.directUrl(documentContentPath(d.id))) return; // the link itself opens / downloads the PDF
    setOpeningId(d.id);
    setMessage(undefined);
    openDocumentNative(client, d.id)
      .catch((e: unknown) => {
        log.warn('documents.open-failed', { errorName: errorName(e) });
        fail(e, t.docOpenFailed);
      })
      .finally(() => setOpeningId(undefined));
  };

  const onAcknowledge = async (d: MyDocument) => {
    setBusyId(d.id);
    setMessage(undefined);
    try {
      const at = await acknowledgeDocument(client, d.id);
      setAcked((a) => ({ ...a, [d.id]: at }));
      setMessage({ kind: 'success', text: t.docAcknowledgedOk });
    } catch (e) {
      fail(e);
    } finally {
      setBusyId(undefined);
    }
  };

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.docsTitle}</PageTitle>
      <p className="lead">{t.docsIntro}</p>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      {docs.loading && <Loading label={t.loading} />}
      {!!docs.error && <Alert kind="error">{errorText(t, docs.error)}</Alert>}
      {docs.data && (
        <DocumentList
          t={t}
          lang={lang}
          docs={list}
          opened={opened}
          linkFor={(d) => client.directUrl(documentContentPath(d.id))}
          busyId={busyId}
          openingId={openingId}
          onOpen={onOpen}
          onAcknowledge={(d) => void onAcknowledge(d)}
        />
      )}
    </PortalSection>
  );
}
