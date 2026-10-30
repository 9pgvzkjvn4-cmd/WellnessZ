import { useState } from 'react';
import { TEST_STATUSES, testingApi, type Requirement } from '../../api/testing';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { requirementCountdown, testStatusTone } from '../../domain/testing';
import { useNow } from '../support/useNow';
import type { ScreenProps } from '../types';

export function WindowBadge({ r, now }: { r: Pick<Requirement, 'dueAt' | 'panels' | 'status'>; now: number }) {
  const c = requirementCountdown(r, now);
  return (
    <ToneBadge tone={c.tone} title={formatDate(r.dueAt)}>
      {c.kind === 'ALCOHOL' ? 'Alcohol' : 'Drug'} window: {c.label}
    </ToneBadge>
  );
}

/** `#/testing/requirements` – open test requirements with a live countdown to the alcohol (8 h) / drug (32 h) windows. */
export function TestingRequirements({ client, session, onSessionExpired }: ScreenProps) {
  const now = useNow(30_000);
  const [status, setStatus] = useState('');
  const list = useAsync(() => testingApi(client).requirements(status || undefined), onSessionExpired, [status]);
  const rows = [...(list.data ?? [])].sort((a, b) => a.dueAt.localeCompare(b.dueAt));
  const collector = can(session.roles, 'testingCollection');
  return (
    <section>
      <PageTitle sub="Post-incident tests must be collected within 8 h (alcohol) and 32 h (drugs) of the incident; random and for-cause tests have a short lead time. Results are never shown here.">Test requirements</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => e.preventDefault()}>
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={TEST_STATUSES.filter((s) => !s.startsWith('VERIFIED'))} empty="All open" /></select>}</Field>
      </form>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No test requirements.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} requirement{rows.length === 1 ? '' : 's'} – soonest deadline first</caption>
            <thead><tr><th scope="col">Window</th><th scope="col">Reference</th><th scope="col">Employee</th><th scope="col">Reason</th><th scope="col">Panels</th><th scope="col">Status</th><th scope="col">Site</th><th scope="col">Dispatched</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td><WindowBadge r={r} now={now} /></td>
                  <th scope="row">{collector ? <a href="#/testing/collection">{r.ref}</a> : r.ref}</th>
                  <td>{r.displayName} <span className="small muted">{r.employeeId}</span></td>
                  <td>{humanise(r.reason)}</td>
                  <td>{r.panels.map(humanise).join(', ')}</td>
                  <td><ToneBadge tone={testStatusTone(r.status)}>{humanise(r.status)}</ToneBadge></td>
                  <td>{r.siteCode ?? '–'}</td>
                  <td className="small">{formatDate(r.dispatchedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
