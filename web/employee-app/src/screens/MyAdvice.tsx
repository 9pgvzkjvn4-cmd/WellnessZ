import { fetchMyAdvice } from '../api/advice';
import type { Advice } from '../api/types';
import { describeError, formatDate } from '../components/format';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function AdviceList({ items }: { items: readonly Advice[] }) {
  if (items.length === 0) {
    return (
      <div className="empty-state">
        <p>
          <strong>No advice yet.</strong>
        </p>
        <p className="muted">
          When Occupational Health sends you advice about one of your cases, it will appear here. You will get a
          notification – it never contains health details.
        </p>
      </div>
    );
  }
  return (
    <ul className="card-list" aria-label="Advice from Occupational Health">
      {items.map((a) => (
        <li key={a.id} className="card">
          <article aria-labelledby={`adv-${a.id}`}>
            <div className="card-row">
              <h2 id={`adv-${a.id}`} className="card-title">
                {a.title || 'Advice from Occupational Health'}
              </h2>
              {a.caseRef && <span className="badge mono-badge">{a.caseRef}</span>}
            </div>
            <p className="muted small">Sent {formatDate(a.sentAt)}</p>
            {/* Plain text only – never interpreted as HTML. */}
            {a.text && <p className="advice-text">{a.text}</p>}
          </article>
        </li>
      ))}
    </ul>
  );
}

/** "My advice" (E02 §5.1): advice texts sent by OH via `POST /cases/{id}/advice`, read from `GET /me/advice`. */
export function MyAdvice({ client, onSessionExpired }: ScreenProps) {
  const advice = useAsync(() => fetchMyAdvice(client), onSessionExpired);
  return (
    <section>
      <PageTitle>My advice</PageTitle>
      <p className="lead">Advice and self-care information from Occupational Health about your cases.</p>
      {advice.loading && <Loading />}
      {!!advice.error && <Alert kind="error">{describeError(advice.error)}</Alert>}
      {advice.data && <AdviceList items={advice.data} />}
    </section>
  );
}
