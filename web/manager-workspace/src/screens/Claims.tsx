import { useState } from 'react';
import {
  CLAIM_STATUS_LABELS,
  CLAIM_STATUSES,
  CLAIM_TYPE_LABELS,
  CLAIM_TYPES,
  CLAIMS_PATH,
  claimStatusLabel,
  claimTypeLabel,
  emptyClaimFilter,
  filterClaims,
  summariseClaims,
  toClaimRows,
  type ClaimFilter,
  type ClaimRow,
  type ClaimStatus,
  type ClaimType,
} from '../api/claims';
import { describeError, formatDay } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface ClaimsViewProps {
  rows: readonly ClaimRow[];
  filter: ClaimFilter;
  onFilter: (f: ClaimFilter) => void;
}

/** E08-F01 – claim status of employees in HR's scope, status only. Hook-free view. */
export function ClaimsView({ rows, filter, onFilter }: ClaimsViewProps) {
  const shown = filterClaims(rows, filter);
  const sum = summariseClaims(rows);
  return (
    <>
      <dl className="stat-row" aria-label="Claims summary">
        <div>
          <dt>Open claims</dt>
          <dd>{sum.open}</dd>
        </div>
        <div>
          <dt>Open lost-time claims</dt>
          <dd>{sum.lostTime}</dd>
        </div>
        <div>
          <dt>Settled or closed</dt>
          <dd>{sum.closed}</dd>
        </div>
      </dl>
      <form className="filters" role="search" aria-label="Filter claims" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="cl-q">Employee or claim reference</label>
          <input id="cl-q" type="search" autoComplete="off" value={filter.q} onChange={(e) => onFilter({ ...filter, q: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="cl-status">Status</label>
          <select id="cl-status" value={filter.status} onChange={(e) => onFilter({ ...filter, status: e.target.value as '' | ClaimStatus })}>
            <option value="">Any</option>
            {CLAIM_STATUSES.map((st) => (
              <option key={st} value={st}>
                {CLAIM_STATUS_LABELS[st]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="cl-type">Claim type</label>
          <select id="cl-type" value={filter.claimType} onChange={(e) => onFilter({ ...filter, claimType: e.target.value as '' | ClaimType })}>
            <option value="">Any</option>
            {CLAIM_TYPES.map((ty) => (
              <option key={ty} value={ty}>
                {CLAIM_TYPE_LABELS[ty]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label className="checkbox" htmlFor="cl-lost-time">
            <input id="cl-lost-time" type="checkbox" checked={filter.lostTimeOnly} onChange={(e) => onFilter({ ...filter, lostTimeOnly: e.target.checked })} /> Lost time only
          </label>
        </div>
        <div className="field">
          <label className="checkbox" htmlFor="cl-open">
            <input id="cl-open" type="checkbox" checked={filter.openOnly} onChange={(e) => onFilter({ ...filter, openOnly: e.target.checked })} /> Open claims only
          </label>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {rows.length} claims.
      </p>
      {rows.length === 0 && <p className="muted">There are no claims for employees in your scope.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Claim status of employees in your scope</caption>
            <thead>
              <tr>
                <th scope="col">Claim</th>
                <th scope="col">Employee</th>
                <th scope="col">Type</th>
                <th scope="col">Status</th>
                <th scope="col">Lost time</th>
                <th scope="col">Incident date</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.claimRef}>
                  <th scope="row" className="mono">
                    {r.claimRef}
                  </th>
                  <td>
                    {r.displayName}
                    <span className="muted small"> {r.employeeId}</span>
                  </td>
                  <td>{claimTypeLabel(r.claimType)}</td>
                  <td>
                    <StatusBadge status={`claim-${r.status}`} label={claimStatusLabel(r.status)} />
                  </td>
                  <td>{r.lostTime ? <StatusBadge status="lost-time" label="Lost time" /> : <span className="muted">No</span>}</td>
                  <td>{formatDay(r.incidentDate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/** `#/claims` (HR only) – claim status of employees in scope (`GET /workspace/claims`, disclosure-logged). */
export function Claims({ client, onSessionExpired }: ScreenProps) {
  const rows = useAsync(async () => toClaimRows(await client.api<unknown>(CLAIMS_PATH)), onSessionExpired);
  const [filter, setFilter] = useState<ClaimFilter>(emptyClaimFilter);
  return (
    <section>
      <PageTitle>Claims</PageTitle>
      <p className="lead">Status of workers&apos; compensation and insurance claims for employees in your scope.</p>
      <NoClinicalInfoNote>
        No medical or cost details are shown – injuries, treatment, reserves and payments stay with Occupational Health and the claims
        team. Opening this page is recorded in the disclosure log.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={rows.reload} disabled={rows.loading}>
          Refresh
        </button>
      </div>
      {rows.loading && <Loading />}
      {!!rows.error && <Alert kind="error">{describeError(rows.error)}</Alert>}
      {rows.data && <ClaimsView rows={rows.data} filter={filter} onFilter={setFilter} />}
    </section>
  );
}
