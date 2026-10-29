import { useEffect, useState } from 'react';
import { toEmployeeLookups, type EmployeeLookup } from '../api/referrals';
import { SessionExpiredError, type AuthClient } from '../auth/AuthClient';
import { describeError } from './format';

export const MIN_QUERY = 2;
const DEBOUNCE_MS = 300;

export interface EmployeeSearchProps {
  client: Pick<AuthClient, 'api'>;
  /** Prefix for element ids (`${idPrefix}-employeeId` is the search input). */
  idPrefix: string;
  selected?: EmployeeLookup;
  onSelect: (e: EmployeeLookup | undefined) => void;
  onSessionExpired: () => void;
  error?: string;
}

/** Selected-employee summary and result list – hook-free, used by EmployeeSearch. */
export function EmployeeResults({
  results,
  onSelect,
  listId,
}: {
  results: readonly EmployeeLookup[];
  onSelect: (e: EmployeeLookup) => void;
  listId: string;
}) {
  return (
    <ul className="lookup-results" id={listId} aria-label="Matching employees">
      {results.map((r) => (
        <li key={r.employeeId}>
          <button type="button" className="lookup-option" data-employee={r.employeeId} onClick={() => onSelect(r)}>
            <strong>{r.displayName}</strong>
            <span className="muted small">
              {' '}
              {r.employeeId}
              {r.departmentCode ? ` · ${r.departmentCode}` : ''}
              {r.jobCode ? ` · ${r.jobCode}` : ''}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

/**
 * Employee search for referral forms: `GET /intake/employees/lookup?q=` (own scope only, max 20 results),
 * debounced, from 2 characters. Results are held in component state only.
 */
export function EmployeeSearch({ client, idPrefix, selected, onSelect, onSessionExpired, error }: EmployeeSearchProps) {
  const [q, setQ] = useState('');
  const [state, setState] = useState<{ q: string; results?: EmployeeLookup[]; error?: string }>({ q: '' });
  const inputId = `${idPrefix}-employeeId`;
  const errorId = `${inputId}-error`;
  const query = q.trim();

  useEffect(() => {
    if (selected || query.length < MIN_QUERY) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      client.api<unknown>(`/intake/employees/lookup?q=${encodeURIComponent(query)}`).then(
        (body) => !cancelled && setState({ q: query, results: toEmployeeLookups(body) }),
        (e: unknown) => {
          if (cancelled) return;
          if (e instanceof SessionExpiredError) onSessionExpired();
          setState({ q: query, error: describeError(e) });
        },
      );
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [client, query, selected, onSessionExpired]);

  if (selected) {
    return (
      <div className="field">
        <span className="label" id={`${inputId}-label`}>
          Employee
        </span>
        <div className="selected-employee" aria-labelledby={`${inputId}-label`}>
          <strong>{selected.displayName}</strong>
          <span className="muted small">
            {' '}
            {selected.employeeId}
            {selected.departmentCode ? ` · ${selected.departmentCode}` : ''}
          </span>
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setQ('');
              setState({ q: '' });
              onSelect(undefined);
            }}
          >
            Change
          </button>
        </div>
      </div>
    );
  }

  const current = state.q === query && query.length >= MIN_QUERY ? state : undefined;
  return (
    <div className="field">
      <label htmlFor={inputId}>
        Employee<span className="req" aria-hidden="true"> *</span>
      </label>
      <p id={`${inputId}-hint`} className="muted small">
        Search by name or employee number (at least {MIN_QUERY} characters). Only employees in your area are shown.
      </p>
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
      <input
        id={inputId}
        type="search"
        autoComplete="off"
        value={q}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${inputId}-hint ${errorId}` : `${inputId}-hint`}
        aria-controls={`${inputId}-results`}
        onChange={(e) => setQ(e.target.value)}
      />
      <div aria-live="polite" className="small">
        {query.length >= MIN_QUERY && !current && <span className="muted">Searching…</span>}
        {current?.error && <span className="field-error">{current.error}</span>}
        {current?.results && current.results.length === 0 && <span className="muted">No employees found in your area.</span>}
      </div>
      {current?.results && current.results.length > 0 && (
        <EmployeeResults results={current.results} onSelect={onSelect} listId={`${inputId}-results`} />
      )}
    </div>
  );
}
