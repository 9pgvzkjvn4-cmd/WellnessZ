import { useState } from 'react';
import { buildQuery } from '../api/links';
import type { Escalation } from '../api/types';
import { can } from '../auth/roles';
import { Empty, ErrorAlert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { EscalationCard } from './EscalationCard';
import type { ScreenProps } from './types';

/** Escalations inbox for physicians / AME / CMO (E02-F05); other OH roles see what they raised. */
export function Escalations(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const [status, setStatus] = useState('OPEN');
  const [mine, setMine] = useState(true);
  const list = useAsync(() => client.api<Escalation[]>(`/escalations${buildQuery({ status, mine })}`), onSessionExpired, [status, mine]);
  const decider = can(session.roles, 'escalationDecide');

  return (
    <section>
      <PageTitle sub={decider ? 'Acknowledge and decide escalations within the response SLA.' : 'Escalations you raised or are involved in.'}>Escalations</PageTitle>
      <div className="toolbar">
        <label htmlFor="esc-status">Status</label>
        <select id="esc-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="OPEN">Open</option>
          <option value="ACKNOWLEDGED">Acknowledged</option>
          <option value="DECIDED">Decided</option>
          <option value="">All</option>
        </select>
        <label className="checkbox">
          <input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Mine only
        </label>
        <button type="button" className="secondary" onClick={list.reload}>
          Refresh
        </button>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && list.data.length === 0 && <Empty>No escalations.</Empty>}
      <ul className="card-list">
        {(list.data ?? []).map((e) => (
          <li key={e.id}>
            <EscalationCard {...props} escalation={e} showCase onChanged={list.reload} />
          </li>
        ))}
      </ul>
    </section>
  );
}
