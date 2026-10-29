import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import type { IncidentCase } from '../../api/types';
import { humanise } from '../../components/format';
import { Empty, ErrorAlert, Field, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { navigate } from '../../routing/useHashRoute';
import type { ScreenProps } from '../types';

/** E01-BL-020 multi-casualty incident → cases (status only). */
export function IncidentCases({ client, onSessionExpired, incidentId }: ScreenProps & { incidentId?: string }) {
  const [input, setInput] = useState(incidentId ?? '');
  const cases = useAsync(
    () => (incidentId ? client.api<IncidentCase[]>(`/intake/incidents/${seg(incidentId)}/cases`) : Promise.resolve(undefined)),
    onSessionExpired,
    [incidentId],
  );

  function open(e: FormEvent) {
    e.preventDefault();
    if (input.trim()) navigate(`/intake/incidents/${encodeURIComponent(input.trim())}`);
  }

  return (
    <section>
      <PageTitle sub="All cases opened from one incident (e.g. multi-casualty events). Status only.">Incident → cases</PageTitle>
      <form className="form inline-form" onSubmit={open}>
        <Field label="Incident id">{(id) => <input id={id} value={input} onChange={(e) => setInput(e.target.value)} />}</Field>
        <button type="submit" className="secondary">
          Show cases
        </button>
      </form>
      <p className="small muted">Tip: open an incident from the reconciliation list.</p>
      {cases.loading && incidentId && <Loading />}
      {!!cases.error && <ErrorAlert error={cases.error} />}
      {cases.data && cases.data.length === 0 && <Empty>No cases for this incident.</Empty>}
      {cases.data && cases.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{cases.data.length} casualties</caption>
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Employee</th>
                <th scope="col">Status</th>
                <th scope="col">Fitness</th>
              </tr>
            </thead>
            <tbody>
              {cases.data.map((c) => (
                <tr key={c.caseRef}>
                  <th scope="row" className="mono">{c.caseRef}</th>
                  <td>{c.employeeDisplayName}</td>
                  <td><StatusBadge status={c.status} /></td>
                  <td>{humanise(c.fitnessStatus)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
