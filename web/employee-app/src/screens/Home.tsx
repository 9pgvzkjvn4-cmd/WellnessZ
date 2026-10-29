import { toMyCases } from '../api/cases';
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
import type { ScreenProps } from './types';

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

export function Home({ client, session, onSessionExpired }: ScreenProps) {
  const cases = useAsync(async () => toMyCases(await client.api<unknown>('/cases')), onSessionExpired);

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
