import { useState } from 'react';
import { annualReportUrl, testingApi } from '../../api/testing';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import { rateCell, rowIsSuppressed, statCell } from '../../domain/testing';
import { DEFAULT_K } from '../../domain/suppression';
import type { ScreenProps } from '../types';

/** `#/testing/reports` – programme statistics (reason × role group, small cells suppressed), coverage and the annual report. */
export function TestingReports({ client, session, onSessionExpired }: ScreenProps) {
  const api = testingApi(client);
  const thisYear = Number(todayIso().slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const stats = useAsync(() => api.statistics(year), onSessionExpired, [year]);
  const showCoverage = can(session.roles, 'testingCoverage');
  const coverage = useAsync(() => (showCoverage ? api.coverage(year) : Promise.resolve([])), onSessionExpired, [year, showCoverage]);
  const k = DEFAULT_K;
  const rows = stats.data ?? [];

  return (
    <section>
      <PageTitle sub={`Anonymised programme figures. Groups with fewer than ${k} tests are suppressed – never individual results.`}>Testing statistics</PageTitle>
      <div className="toolbar">
        <form className="filters inline-form" onSubmit={(e) => e.preventDefault()}>
          <Field label="Year">
            {(id) => (
              <select id={id} value={year} onChange={(e) => setYear(Number(e.target.value))}>
                {[thisYear, thisYear - 1, thisYear - 2].map((y) => <option key={y} value={y}>{y}</option>)}
              </select>
            )}
          </Field>
        </form>
        {can(session.roles, 'testingAnnualReport') && (
          <div className="toolbar-left">
            <a className="button secondary" href={annualReportUrl(year, 'pdf')} download={`testing-annual-${year}.pdf`} rel="noopener">Annual report (PDF)</a>
            <a className="button secondary" href={annualReportUrl(year, 'csv')} download={`testing-annual-${year}.csv`} rel="noopener">Annual report (CSV)</a>
          </div>
        )}
      </div>
      <section aria-labelledby="stats-h">
        <h2 id="stats-h">Tests by reason and role group</h2>
        {stats.loading && !stats.data && <Loading />}
        {!!stats.error && <ErrorAlert error={stats.error} />}
        {stats.data && rows.length === 0 && <Empty>No tests in {year}.</Empty>}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th scope="col">Reason</th><th scope="col">Role group</th><th scope="col" className="num">Tests</th><th scope="col" className="num">Verified positive</th><th scope="col" className="num">Refusals</th><th scope="col" className="num">Positive rate</th></tr></thead>
              <tbody>
                {rows.map((r) => {
                  const sup = rowIsSuppressed(r, k);
                  return (
                    <tr key={`${r.reason}-${r.roleGroup}`} className={sup ? 'suppressed' : undefined}>
                      <th scope="row">{humanise(r.reason)}</th>
                      <td>{humanise(r.roleGroup)}</td>
                      <td className="num">{statCell(r.tests, sup, k)}</td>
                      <td className="num">{statCell(r.verifiedPositive, sup, k)}</td>
                      <td className="num">{statCell(r.refusals, sup, k)}</td>
                      <td className="num">{rateCell(r.positiveRatePct, sup)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="small muted">“&lt;{k}” = suppressed small group.</p>
          </div>
        )}
      </section>
      {showCoverage && (
        <section aria-labelledby="coverage-h">
          <h2 id="coverage-h">Random-testing coverage</h2>
          {coverage.loading && !coverage.data && <Loading />}
          {!!coverage.error && <ErrorAlert error={coverage.error} />}
          {coverage.data && coverage.data.length === 0 && <Empty>No coverage figures.</Empty>}
          {(coverage.data ?? []).length > 0 && (
            <table>
              <thead><tr><th scope="col">Role group</th><th scope="col" className="num">Pool</th><th scope="col" className="num">Target tests</th><th scope="col" className="num">Completed</th><th scope="col">Coverage</th></tr></thead>
              <tbody>
                {(coverage.data ?? []).map((c) => (
                  <tr key={c.roleGroup}>
                    <th scope="row">{humanise(c.roleGroup)}</th>
                    <td className="num">{c.poolSize}</td>
                    <td className="num">{c.targetTests}</td>
                    <td className="num">{c.completed}</td>
                    <td><ToneBadge tone={c.behindPlan ? 'red' : 'green'}>{c.coveragePct.toFixed(0)} %{c.behindPlan ? ' – behind plan' : ''}</ToneBadge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </section>
  );
}
