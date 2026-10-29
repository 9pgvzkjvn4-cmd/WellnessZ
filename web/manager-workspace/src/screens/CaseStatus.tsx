import { toCaseStatusDetail } from '../api/statusOnly';
import { describeError, formatDate, humanise } from '../components/format';
import { StatusOnlyBanner } from '../components/StatusOnlyBanner';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function CaseStatus({ client, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const detail = useAsync(
    async () => toCaseStatusDetail(await client.api<unknown>(`/cases/${encodeURIComponent(caseId)}/status`)),
    onSessionExpired,
  );
  const d = detail.data;

  return (
    <section>
      <p>
        <a href="#/">← Back to team cases</a>
      </p>
      <PageTitle>Case status{d ? ` – ${d.caseRef}` : ''}</PageTitle>
      <StatusOnlyBanner />
      {detail.loading && <Loading />}
      {!!detail.error && <Alert kind="error">{describeError(detail.error)}</Alert>}
      {!detail.loading && !detail.error && !d && <Alert kind="error">Case status is not available.</Alert>}
      {d && (
        <>
          <dl className="details">
            {d.employeeDisplayName && (
              <>
                <dt>Employee</dt>
                <dd>{d.employeeDisplayName}</dd>
              </>
            )}
            <dt>Status</dt>
            <dd>
              <StatusBadge status={d.status} />
            </dd>
            <dt>Fitness for work</dt>
            <dd>{d.fitnessStatus ? humanise(d.fitnessStatus) : '–'}</dd>
            <dt>Restriction until</dt>
            <dd>{d.restrictionUntil ? formatDate(d.restrictionUntil) : '–'}</dd>
            <dt>Last updated</dt>
            <dd>{formatDate(d.updatedAt)}</dd>
          </dl>
          {d.history.length > 0 && (
            <>
              <h2>Status history</h2>
              <ol className="timeline">
                {d.history.map((h, i) => (
                  <li key={i}>
                    <StatusBadge status={h.status} /> <span className="muted small">{formatDate(h.at)}</span>
                  </li>
                ))}
              </ol>
            </>
          )}
        </>
      )}
    </section>
  );
}
