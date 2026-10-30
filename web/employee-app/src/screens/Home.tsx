import { loadCareCounts, type CareCounts } from '../api/care';
import { toMyCases } from '../api/cases';
import { loadOhCounts, type OhCounts } from '../api/occupational';
import { loadPortalCounts, type PortalCounts } from '../api/portal';
import {
  CASE_STATUS_LABELS,
  CATEGORY_LABELS,
  FITNESS_LABELS,
  parseCaseRef,
  type CaseStatus,
  type FitnessStatus,
  type MyCase,
  type ReferralCategory,
} from '../api/types';
import { describeError, formatDate, formatDay, humanise } from '../components/format';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { careStrings, type CareStrings } from '../i18n/care';
import { wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import { loadWellbeingCounts, type WellbeingCounts } from '../api/prevention';
import { ohStrings, type OhStrings } from '../i18n/occupational';
import { portalStrings, type PortalStrings } from '../i18n/portal';
import { dirFor } from '../i18n/strings';
import type { PortalScreenProps } from './types';

/** Hook-free tiles linking to the increment-2 screens, with an "n items need your attention" hint. */
export function PortalTiles({ t, lang, counts = {} }: { t: PortalStrings; lang: string; counts?: Partial<PortalCounts> }) {
  const tiles = [
    { href: '#/documents', label: t.tileDocuments, count: counts.documents },
    { href: '#/rtw', label: t.tileRtw, count: counts.rtw },
    { href: '#/job-offers', label: t.tileOffers, count: counts.offers },
    { href: '#/privacy', label: t.tilePrivacy, count: undefined },
  ];
  return (
    <nav className="tiles" aria-labelledby="tiles-title" dir={dirFor(lang)} lang={lang}>
      <h2 id="tiles-title">{t.tilesTitle}</h2>
      <ul>
        {tiles.map((tile) => (
          <li key={tile.href}>
            <a className="tile" href={tile.href}>
              <span className="tile-label">{tile.label}</span>
              {!!tile.count && <span className="tile-count badge badge-doc-action">{t.tileActions(tile.count)}</span>}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** Hook-free tiles for the increment-3 sections (appointments, absence, advice reports, health checks). */
export function OhTiles({ t, oh, lang, counts = {} }: { t: PortalStrings; oh: OhStrings; lang: string; counts?: Partial<OhCounts> }) {
  const tiles = [
    { href: '#/appointments', label: oh.tileAppointments, count: counts.appointments },
    { href: '#/advice', label: oh.tileAdvice, count: counts.advice },
    { href: '#/absence', label: oh.tileAbsence, count: undefined },
    { href: '#/surveillance', label: oh.tileSurveillance, count: counts.surveillance },
  ];
  return (
    <nav className="tiles" aria-labelledby="oh-tiles-title" dir={dirFor(lang)} lang={lang}>
      <h2 id="oh-tiles-title">{oh.tilesTitle}</h2>
      <ul>
        {tiles.map((tile) => (
          <li key={tile.href}>
            <a className="tile" href={tile.href}>
              <span className="tile-label">{tile.label}</span>
              {!!tile.count && <span className="tile-count badge badge-doc-action">{t.tileActions(tile.count)}</span>}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** Hook-free tiles for the increment-4 sections (treatment plan, telehealth consultations). */
export function CareTiles({ t, care, lang, counts = {} }: { t: PortalStrings; care: CareStrings; lang: string; counts?: Partial<CareCounts> }) {
  const tiles = [
    { href: '#/treatment', label: care.tileTreatment, count: counts.treatment },
    { href: '#/telehealth', label: care.tileTelehealth, count: counts.telehealth },
  ];
  return (
    <nav className="tiles" aria-labelledby="care-tiles-title" dir={dirFor(lang)} lang={lang}>
      <h2 id="care-tiles-title">{care.tilesTitle}</h2>
      <ul>
        {tiles.map((tile) => (
          <li key={tile.href}>
            <a className="tile" href={tile.href}>
              <span className="tile-label">{tile.label}</span>
              {!!tile.count && <span className="tile-count badge badge-doc-action">{t.tileActions(tile.count)}</span>}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** Hook-free tiles for the increment-5 sections (support, peer support, check-ins, travel, campaigns, workplace checks). */
export function WellbeingTiles({ t, wb, lang, counts = {} }: { t: PortalStrings; wb: WellbeingStrings; lang: string; counts?: Partial<WellbeingCounts> }) {
  const tiles = [
    { href: '#/support', label: wb.tiles.support, count: counts.support },
    { href: '#/peer-support', label: wb.tiles.peer, count: undefined },
    { href: '#/check-ins', label: wb.tiles.checkIns, count: counts.checkIns },
    { href: '#/travel', label: wb.tiles.travel, count: counts.travel },
    { href: '#/campaigns', label: wb.tiles.campaigns, count: counts.campaigns },
    { href: '#/testing', label: wb.tiles.testing, count: counts.testing },
  ];
  return (
    <nav className="tiles" aria-labelledby="wb-tiles-title" dir={dirFor(lang)} lang={lang}>
      <h2 id="wb-tiles-title">{wb.tilesTitle}</h2>
      <ul>
        {tiles.map((tile) => (
          <li key={tile.href}>
            <a className="tile" href={tile.href}>
              <span className="tile-label">{tile.label}</span>
              {!!tile.count && <span className="tile-count badge badge-doc-action">{t.tileActions(tile.count)}</span>}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

const statusLabel = (s: string) => CASE_STATUS_LABELS[s as CaseStatus] ?? humanise(s);
const fitnessLabel = (s: string) => FITNESS_LABELS[s as FitnessStatus] ?? humanise(s);

/** One case: reference (`{UNIT}-{YYYY}-{NNNNNN}`), status and fitness-for-work status. No clinical content. */
export function CaseCard({ c }: { c: MyCase }) {
  const ref = parseCaseRef(c.caseRef);
  const title = c.caseRef ?? 'Case';
  return (
    <article className="card case-card" aria-label={`Case ${title}`}>
      <div className="card-row">
        <div>
          <span className="case-ref mono">{title}</span>
          {ref && (
            <span className="muted small case-ref-meta">
              {' '}
              · {ref.unit === 'OH' ? 'Occupational Health' : ref.unit} · {ref.year}
            </span>
          )}
        </div>
        <StatusBadge status={c.status} label={statusLabel(c.status)} />
      </div>
      <dl className="case-facts">
        {c.category && (
          <>
            <dt>Type</dt>
            <dd>{CATEGORY_LABELS[c.category as ReferralCategory] ?? humanise(c.category)}</dd>
          </>
        )}
        <dt>Fitness for work</dt>
        <dd>{c.fitnessStatus ? <StatusBadge status={c.fitnessStatus} label={fitnessLabel(c.fitnessStatus)} /> : 'Not yet assessed'}</dd>
        {c.restrictionUntil && (
          <>
            <dt>Restrictions until</dt>
            <dd>{formatDay(c.restrictionUntil)}</dd>
          </>
        )}
        <dt>Last updated</dt>
        <dd>{formatDate(c.updatedAt ?? c.createdAt)}</dd>
      </dl>
    </article>
  );
}

export function Home({ client, session, onSessionExpired, lang }: PortalScreenProps) {
  const cases = useAsync(async () => toMyCases(await client.api<unknown>('/cases')), onSessionExpired);
  const counts = useAsync(() => loadPortalCounts(client), onSessionExpired);
  const ohCounts = useAsync(() => loadOhCounts(client), onSessionExpired);
  const careCounts = useAsync(() => loadCareCounts(client), onSessionExpired);
  const wbCounts = useAsync(() => loadWellbeingCounts(client), onSessionExpired);

  return (
    <section aria-labelledby="home-title">
      <PageTitle>Welcome{session.name ? `, ${session.name}` : ''}</PageTitle>
      <p className="lead">
        Occupational Health is here to support your health and fitness at work. Information you share with OH is
        confidential; your manager only sees work-relevant status.
      </p>
      <div className="actions">
        <a className="button" href="#/refer">
          Contact Occupational Health
        </a>
        <a className="button secondary" href="#/advice">
          My advice
        </a>
        <a className="button secondary" href="#/fitness-declaration">
          Crew: declare decrease in fitness
        </a>
      </div>

      <OhTiles t={portalStrings(lang)} oh={ohStrings(lang)} lang={lang} counts={ohCounts.data} />
      <CareTiles t={portalStrings(lang)} care={careStrings(lang)} lang={lang} counts={careCounts.data} />
      <WellbeingTiles t={portalStrings(lang)} wb={wellbeingStrings(lang)} lang={lang} counts={wbCounts.data} />
      <PortalTiles t={portalStrings(lang)} lang={lang} counts={counts.data} />

      <h2 id="home-title">My cases</h2>
      {cases.loading && <Loading />}
      {!!cases.error && <Alert kind="error">{describeError(cases.error)}</Alert>}
      {cases.data && cases.data.length === 0 && <p className="muted">You have no open or past cases.</p>}
      {cases.data && cases.data.length > 0 && (
        <ul className="card-list">
          {cases.data.map((c) => (
            <li key={c.id}>
              <CaseCard c={c} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
