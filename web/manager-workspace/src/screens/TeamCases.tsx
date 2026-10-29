import { toTeamCaseRows, type TeamCaseRow } from '../api/statusOnly';
import { ihrRoute, isHr, jobOffersRoute } from '../auth/roles';
import { describeError, formatDate, humanise } from '../components/format';
import { StatusOnlyBanner } from '../components/StatusOnlyBanner';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** Status-only case table; HR additionally gets per-case links to job offers and IHR / redeployment. Hook-free. */
export function TeamCasesTable({ rows, hr }: { rows: readonly TeamCaseRow[]; hr: boolean }) {
  return (
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
            {hr && <th scope="col">HR actions</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
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
              {hr && (
                <td>
                  <ul className="plain-list small">
                    <li>
                      <a href={`#${jobOffersRoute(r.id)}`} aria-label={`Job offers for case ${r.caseRef}`}>
                        Job offers
                      </a>
                    </li>
                    <li>
                      <a href={`#${ihrRoute(r.id)}`} aria-label={`Ill-health retirement or redeployment for case ${r.caseRef}`}>
                        Ill-health / redeployment
                      </a>
                    </li>
                  </ul>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function TeamCases({ client, session, onSessionExpired }: ScreenProps) {
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
      {rows.data && rows.data.length > 0 && <TeamCasesTable rows={rows.data} hr={isHr(session.roles)} />}
    </section>
  );
}
