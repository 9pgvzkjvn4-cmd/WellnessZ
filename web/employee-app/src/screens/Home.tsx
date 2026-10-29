import { asList, type MyCase } from '../api/types';
import { describeError, formatDate, humanise } from '../components/format';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function Home({ client, session, onSessionExpired }: ScreenProps) {
  const cases = useAsync(async () => asList<MyCase>(await client.api<unknown>('/cases')), onSessionExpired);

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
        <a className="button secondary" href="#/consents">
          Manage my consents
        </a>
      </div>

      <h2 id="home-title">My cases</h2>
      {cases.loading && <Loading />}
      {!!cases.error && <Alert kind="error">{describeError(cases.error)}</Alert>}
      {cases.data && cases.data.length === 0 && <p className="muted">You have no open or past cases.</p>}
      {cases.data && cases.data.length > 0 && (
        <ul className="card-list">
          {cases.data.map((c) => (
            <li key={c.id} className="card">
              <div className="card-row">
                <strong>{c.caseRef ?? c.id}</strong>
                <StatusBadge status={c.status} />
              </div>
              <div className="muted small">
                {c.category ? `${humanise(c.category)} · ` : ''}
                {c.fitnessStatus ? `Fitness for work: ${humanise(c.fitnessStatus)} · ` : ''}
                {c.restrictionUntil ? `Restrictions until ${formatDate(c.restrictionUntil)} · ` : ''}
                Last updated {formatDate(c.updatedAt ?? c.createdAt)}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
