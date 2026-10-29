import { useState, type FormEvent } from 'react';
import { buildQuery, reconciliationExportUrl } from '../../api/links';
import { RECON_OUTCOMES, type ReconciliationRow } from '../../api/types';
import { can } from '../../auth/roles';
import { formatDate, humanise, isoDay } from '../../components/format';
import { Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

interface Filters {
  from: string;
  to: string;
  outcome: string;
  unit: string;
}

function defaultRange(): Filters {
  const to = new Date();
  const from = new Date(to.getTime() - 7 * 86_400_000);
  return { from: isoDay(from), to: isoDay(to), outcome: '', unit: '' };
}

function RowsTable({ rows, caption }: { rows: ReconciliationRow[]; caption: string }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Incident</th>
            <th scope="col">Occurred</th>
            <th scope="col">Class</th>
            <th scope="col">Unit</th>
            <th scope="col">Outcome</th>
            <th scope="col">Rule</th>
            <th scope="col">Case</th>
            <th scope="col">Decided by</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.incidentId}>
              <th scope="row">
                <a href={`#/intake/incidents/${encodeURIComponent(r.incidentId)}`} className="mono">{r.externalRef}</a>
              </th>
              <td>{formatDate(r.occurredAt)}</td>
              <td>
                {humanise(r.incidentClass)}
                <div className="small muted">{humanise(r.severity)}{r.lostTime ? ' · lost time' : ''}</div>
              </td>
              <td>{r.unitCode} <span className="small muted">{r.location}</span></td>
              <td>
                <StatusBadge status={r.outcome} />
                {r.dismissReason && <div className="small muted">{humanise(r.dismissReason)}</div>}
              </td>
              <td>{r.ruleName ?? '–'}</td>
              <td>
                {r.caseRef ? (r.caseId ? <a href={`#/cases/${encodeURIComponent(r.caseId)}`}>{r.caseRef}</a> : r.caseRef) : '–'}
                {r.caseStatus && <div className="small muted">{humanise(r.caseStatus)}</div>}
              </td>
              <td>{r.decidedBy ?? '–'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** E01-BL-004 incident ↔ case reconciliation with CSV export and weekly exception report. */
export function Reconciliation({ client, session, onSessionExpired }: ScreenProps) {
  const [draft, setDraft] = useState<Filters>(defaultRange);
  const [filters, setFilters] = useState<Filters>(draft);
  const [showExceptions, setShowExceptions] = useState(false);
  const rows = useAsync(() => client.api<ReconciliationRow[]>(`/intake/reconciliation${buildQuery({ ...filters })}`), onSessionExpired, [filters]);
  const exceptions = useAsync(
    () => (showExceptions ? client.api<ReconciliationRow[]>(`/intake/reconciliation/exceptions${buildQuery({ from: filters.from, to: filters.to })}`) : Promise.resolve(undefined)),
    onSessionExpired,
    [showExceptions, filters.from, filters.to],
  );
  const canExceptions = can(session.roles, 'reconciliationExceptions');

  function apply(e: FormEvent) {
    e.preventDefault();
    setFilters({ ...draft });
  }

  return (
    <section>
      <PageTitle sub="Every EHS incident and what intake did with it – no clinical text.">Incident reconciliation</PageTitle>
      <form className="filters" onSubmit={apply} aria-label="Reconciliation filters">
        <Field label="From">{(id) => <input id={id} type="date" required value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" required value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />}</Field>
        <Field label="Outcome">
          {(id) => (
            <select id={id} value={draft.outcome} onChange={(e) => setDraft({ ...draft, outcome: e.target.value })}>
              <SelectOptions values={RECON_OUTCOMES} empty="Any" />
            </select>
          )}
        </Field>
        <Field label="Unit">{(id) => <input id={id} size={6} value={draft.unit} onChange={(e) => setDraft({ ...draft, unit: e.target.value.toUpperCase() })} />}</Field>
        <div className="filter-actions">
          <button type="submit">Apply</button>
          <a className="button secondary" href={reconciliationExportUrl({ from: filters.from, to: filters.to })} download>
            Export CSV
          </a>
        </div>
      </form>
      {rows.loading && <Loading />}
      {!!rows.error && <ErrorAlert error={rows.error} />}
      {rows.data && rows.data.length === 0 && <Empty>No incidents in this range.</Empty>}
      {rows.data && rows.data.length > 0 && <RowsTable rows={rows.data} caption={`${rows.data.length} incidents`} />}

      {canExceptions && (
        <>
          <h2>Exception report</h2>
          <p className="small muted">Ignored or unmatched incidents that involved an injury or lost time.</p>
          {!showExceptions ? (
            <button type="button" className="secondary" onClick={() => setShowExceptions(true)}>
              Show exceptions for this range
            </button>
          ) : (
            <>
              {exceptions.loading && <Loading />}
              {!!exceptions.error && <ErrorAlert error={exceptions.error} />}
              {exceptions.data && exceptions.data.length === 0 && <Empty>No exceptions – every injury / lost-time incident has a case or a decision.</Empty>}
              {exceptions.data && exceptions.data.length > 0 && <RowsTable rows={exceptions.data} caption={`${exceptions.data.length} exceptions`} />}
            </>
          )}
        </>
      )}
    </section>
  );
}
