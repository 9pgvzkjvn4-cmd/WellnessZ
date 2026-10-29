import { useId, useState, type FormEvent } from 'react';
import { buildQuery } from '../api/links';
import type { EmployeeRef } from '../api/types';
import type { AuthClient } from '../auth/AuthClient';
import { ErrorAlert } from './ui';
import { useAction } from './useAsync';

/** `/intake/employees/lookup?q=` search; each hit links to the target route for that employee (default: the OH chart). */
export function EmployeeSearch({
  client,
  onSessionExpired,
  hrefFor = (id) => `#/employees/${encodeURIComponent(id)}/chart`,
  onPick,
  label = 'Find employee (name or employee ID)',
}: {
  client: AuthClient;
  onSessionExpired: () => void;
  hrefFor?: (employeeId: string) => string;
  onPick?: (e: EmployeeRef) => void;
  label?: string;
}) {
  const id = useId();
  const [q, setQ] = useState('');
  const [hits, setHits] = useState<EmployeeRef[]>();
  const action = useAction(onSessionExpired);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (q.trim().length < 2) return;
    await action.run(async () => setHits(await client.api<EmployeeRef[]>(`/intake/employees/lookup${buildQuery({ q: q.trim() })}`)));
  }

  return (
    <div className="employee-search">
      <form className="form inline-form" role="search" onSubmit={search}>
        <div className="field">
          <label htmlFor={id}>{label}</label>
          <input id={id} type="search" value={q} minLength={2} autoComplete="off" onChange={(e) => setQ(e.target.value)} />
        </div>
        <button type="submit" className="secondary" disabled={action.busy || q.trim().length < 2}>
          Search
        </button>
      </form>
      {!!action.error && <ErrorAlert error={action.error} />}
      <div aria-live="polite">{hits && hits.length === 0 && <p className="muted">No employees found.</p>}</div>
      {hits && hits.length > 0 && (
        <ul className="plain-list" aria-label="Employees found">
          {hits.map((h) => (
            <li key={h.employeeId}>
              {onPick ? (
                <button type="button" className="link-button" onClick={() => onPick(h)}>
                  {h.displayName} ({h.employeeId})
                </button>
              ) : (
                <a href={hrefFor(h.employeeId)}>
                  {h.displayName} ({h.employeeId})
                </a>
              )}{' '}
              <span className="small muted">{[h.departmentCode, h.jobCode].filter(Boolean).join(' · ')}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
