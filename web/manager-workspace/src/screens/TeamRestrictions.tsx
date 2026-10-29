import { useState } from 'react';
import {
  emptyRestrictionFilter,
  filterTeamRestrictions,
  toTeamRestrictions,
  type TeamRestrictionFilter,
  type TeamRestrictionRow,
} from '../api/fitness';
import { FITNESS_STATUSES } from '../api/statusOnly';
import { describeError, formatDay, humanise } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const RESTRICTION_FILTERS: { value: TeamRestrictionFilter['restrictionStatus']; label: string }[] = [
  { value: '', label: 'All employees' },
  { value: 'ANY', label: 'With current restrictions' },
  { value: 'EXPIRING', label: 'Restriction expiring (≤ 5 days)' },
  { value: 'LAPSED', label: 'Restriction lapsed' },
];

export interface TeamRestrictionsViewProps {
  rows: readonly TeamRestrictionRow[];
  filter: TeamRestrictionFilter;
  onFilter: (f: TeamRestrictionFilter) => void;
}

/** E05-BL-017 – team restrictions, status only. Hook-free view. */
export function TeamRestrictionsView({ rows, filter, onFilter }: TeamRestrictionsViewProps) {
  const shown = filterTeamRestrictions(rows, filter);
  return (
    <>
      <form className="filters" role="search" aria-label="Filter team restrictions" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="tr-q">Employee</label>
          <input id="tr-q" type="search" autoComplete="off" value={filter.q} onChange={(e) => onFilter({ ...filter, q: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="tr-fitness">Fitness for work</label>
          <select id="tr-fitness" value={filter.fitnessStatus} onChange={(e) => onFilter({ ...filter, fitnessStatus: e.target.value })}>
            <option value="">Any</option>
            {FITNESS_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanise(s)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="tr-status">Restrictions</label>
          <select
            id="tr-status"
            value={filter.restrictionStatus}
            onChange={(e) => onFilter({ ...filter, restrictionStatus: e.target.value as TeamRestrictionFilter['restrictionStatus'] })}
          >
            {RESTRICTION_FILTERS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {rows.length} employees.
      </p>
      {rows.length === 0 && <p className="muted">No employees with a fitness status in your scope.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Team fitness status and functional restrictions</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Fitness for work</th>
                <th scope="col">Functional restrictions</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.employeeId}>
                  <th scope="row">
                    {r.employeeName}
                    <span className="muted small"> {r.employeeId}</span>
                  </th>
                  <td>{r.fitnessStatus ? <StatusBadge status={r.fitnessStatus} /> : '–'}</td>
                  <td>
                    {r.restrictions.length === 0 ? (
                      <span className="muted">None</span>
                    ) : (
                      <ul className="plain-list">
                        {r.restrictions.map((x, i) => (
                          <li key={i} className="restriction-item">
                            <span>{x.label}</span>
                            <span className="muted small">{x.validUntil ? `valid until ${formatDay(x.validUntil)}` : 'no end date'}</span>
                            {(x.status === 'EXPIRING' || x.status === 'LAPSED') && <StatusBadge status={x.status} />}
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export function TeamRestrictions({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toTeamRestrictions(await client.api<unknown>('/workspace/team-restrictions')), onSessionExpired);
  const [filter, setFilter] = useState<TeamRestrictionFilter>(emptyRestrictionFilter);
  return (
    <section>
      <PageTitle>Team restrictions</PageTitle>
      <p className="lead">Use this to allocate work safely. Restrictions marked “Expiring” end within 5 days.</p>
      <NoClinicalInfoNote>
        Fitness status and functional restrictions only – diagnoses and medical details stay with Occupational Health.
        Access to this view is logged.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && <TeamRestrictionsView rows={rows.data} filter={filter} onFilter={setFilter} />}
    </section>
  );
}
