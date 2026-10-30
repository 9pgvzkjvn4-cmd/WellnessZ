import { fetchTestingNotices, reportWindow, type TestingNotice } from '../api/prevention';
import { formatDate } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { Fact } from '../components/wellbeing';
import { durationText, wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

/** One notice: where and by when to report, in neutral wording. No reason, panel or result exists in the model. Hook-free. */
export function NoticeCard({ t, lang, notice, now }: { t: WellbeingStrings; lang: string; notice: TestingNotice; now?: Date }) {
  const w = reportWindow(notice, now);
  return (
    <article className="card" aria-label={`${t.teRef} ${notice.ref}`}>
      <div className="card-row">
        <span className="mono">{notice.ref}</span>
        <StatusBadge status={`check-${notice.phase}`} label={t.phases[notice.phase]} />
      </div>
      {notice.phase === 'REPORT' && (
        <>
          <dl className="case-facts">
            <Fact label={t.teWhere}>{notice.siteCode && <bdi>{notice.siteCode}</bdi>}</Fact>
            <Fact label={t.teBy}>{notice.reportBy && formatDate(notice.reportBy, lang)}</Fact>
          </dl>
          {w.state === 'open' && (
            <p>
              <strong>{t.teLeft(durationText(t, w.minutesLeft))}</strong>
            </p>
          )}
          {w.state === 'passed' && <p className="field-error">{t.tePassed}</p>}
          <p className="muted small">{t.teBring}</p>
        </>
      )}
      {notice.phase === 'MISSED' && <p className="muted">{t.tePassed}</p>}
    </article>
  );
}

/** `#/testing` – my workplace-check notices (E10 §4.3 `/me/testing`). */
export function Testing({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const notices = useAsync(() => fetchTestingNotices(client), onSessionExpired);
  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.teTitle}</PageTitle>
      <p className="lead">{t.teIntro}</p>
      <p className="muted small">{t.tePrivacy}</p>
      {notices.loading && <Loading />}
      {!!notices.error && <Alert kind="error">{wellbeingErrorText(lang, notices.error)}</Alert>}
      {notices.data && notices.data.length === 0 && <EmptyState title={t.teEmpty} hint={t.teEmptyHint} />}
      {notices.data && notices.data.length > 0 && (
        <ul className="card-list">
          {notices.data.map((n) => (
            <li key={n.ref}>
              <NoticeCard t={t} lang={lang} notice={n} />
            </li>
          ))}
        </ul>
      )}
    </PortalSection>
  );
}
