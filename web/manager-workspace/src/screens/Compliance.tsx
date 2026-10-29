import { useState } from 'react';
import {
  COMPLIANCE_LABELS,
  emptyComplianceFilter,
  filterCompliance,
  summariseCompliance,
  TEAM_COMPLIANCE_STATUSES,
  toComplianceRows,
  type ComplianceFilter,
  type ComplianceRow,
  type TeamComplianceStatus,
} from '../api/surveillance';
import { describeError, formatDay } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface ComplianceViewProps {
  rows: readonly ComplianceRow[];
  filter: ComplianceFilter;
  onFilter: (f: ComplianceFilter) => void;
}

/** E07-F02 – team surveillance compliance, status only. Hook-free view. */
export function ComplianceView({ rows, filter, onFilter }: ComplianceViewProps) {
  const shown = filterCompliance(rows, filter);
  const sum = summariseCompliance(rows);
  return (
    <>
      <dl className="stat-row" aria-label="Team compliance summary">
        <div>
          <dt>Compliance</dt>
          <dd>{sum.compliancePct === null ? '–' : `${sum.compliancePct}%`}</dd>
        </div>
        <div>
          <dt>Current</dt>
          <dd>{sum.current}</dd>
        </div>
        <div>
          <dt>Due within 30 days</dt>
          <dd>{sum.due}</dd>
        </div>
        <div>
          <dt>Overdue</dt>
          <dd>{sum.overdue}</dd>
        </div>
        <div>
          <dt>Escalated</dt>
          <dd>{sum.escalated}</dd>
        </div>
      </dl>
      <form className="filters" role="search" aria-label="Filter team compliance" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="co-q">Employee or programme</label>
          <input id="co-q" type="search" autoComplete="off" value={filter.q} onChange={(e) => onFilter({ ...filter, q: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="co-status">Status</label>
          <select id="co-status" value={filter.status} onChange={(e) => onFilter({ ...filter, status: e.target.value as '' | TeamComplianceStatus })}>
            <option value="">Any</option>
            {TEAM_COMPLIANCE_STATUSES.map((st) => (
              <option key={st} value={st}>
                {COMPLIANCE_LABELS[st]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label className="checkbox" htmlFor="co-escalated">
            <input id="co-escalated" type="checkbox" checked={filter.escalatedOnly} onChange={(e) => onFilter({ ...filter, escalatedOnly: e.target.checked })} />{' '}
            Escalated only
          </label>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {rows.length} programme enrolments.
      </p>
      {rows.length === 0 && <p className="muted">No one in your team is enrolled in a health surveillance programme.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Team health surveillance compliance</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Programme</th>
                <th scope="col">Status</th>
                <th scope="col">Next due</th>
                <th scope="col">Escalated</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={`${r.employeeId}-${r.programmeTitle}`}>
                  <th scope="row">
                    {r.displayName}
                    <span className="muted small"> {r.employeeId}</span>
                  </th>
                  <td>{r.programmeTitle}</td>
                  <td>
                    <StatusBadge status={`compliance-${r.status}`} label={COMPLIANCE_LABELS[r.status]} />
                  </td>
                  <td>{formatDay(r.nextDueOn)}</td>
                  <td>
                    {r.escalated ? (
                      <StatusBadge status="escalated" label="Invitations ignored – please follow up" />
                    ) : (
                      <span className="muted">No</span>
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

export function Compliance({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toComplianceRows(await client.api<unknown>('/workspace/surveillance/compliance')), onSessionExpired);
  const [filter, setFilter] = useState<ComplianceFilter>(emptyComplianceFilter);
  return (
    <section>
      <PageTitle>Health surveillance</PageTitle>
      <p className="lead">Whether your team is up to date with statutory and periodic medicals.</p>
      <NoClinicalInfoNote>
        No medical details are shown – results, exposures and questionnaire answers stay with Occupational Health. Please release
        people to attend their appointments; OH sends the invitations.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && <ComplianceView rows={rows.data} filter={filter} onFilter={setFilter} />}
    </section>
  );
}
