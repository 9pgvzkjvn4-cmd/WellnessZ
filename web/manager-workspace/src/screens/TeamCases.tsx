import { toTeamCaseRows } from '../api/statusOnly';
import { describeError, formatDate, humanise } from '../components/format';
import { StatusOnlyBanner } from '../components/StatusOnlyBanner';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function TeamCases({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toTeamCaseRows(await client.api<unknown>('/cases')), onSessionExpired);

  return (
    <section>
      <PageTitle>Team case status</PageTitle>
      <StatusOnlyBanner />
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && rows.data.length === 0 && <p className="muted">No cases in your scope.</p>}
      {rows.data && rows.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Cases in your scope, status only</caption>
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Employee</th>
                <th scope="col">Status</th>
                <th scope="col">Fitness for work</th>
                <th scope="col">Restriction until</th>
                <th scope="col">Last updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.data.map((r) => (
                <tr key={r.id}>
                  <th scope="row">
                    <a href={`#/cases/${encodeURIComponent(r.id)}`}>{r.caseRef}</a>
                  </th>
                  <td>{r.employeeDisplayName}</td>
                  <td>
                    <StatusBadge status={r.status} />
                  </td>
                  <td>{r.fitnessStatus ? humanise(r.fitnessStatus) : '–'}</td>
                  <td>{r.restrictionUntil ? formatDate(r.restrictionUntil) : '–'}</td>
                  <td>{formatDate(r.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
