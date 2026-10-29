import { useState } from 'react';
import { recordApi } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { AiBadge } from './EncounterEditor';

/** `#/encounters/unsigned` – reminder list of DRAFT encounters older than 1 h (E03-BL-006). */
export function UnsignedNotes({ client, session, onSessionExpired }: ScreenProps) {
  const canAll = can(session.roles, 'unsignedNotesAll');
  const [mine, setMine] = useState(true);
  const list = useAsync(() => recordApi(client).unsigned(mine), onSessionExpired, [mine]);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Draft notes older than one hour. Sign them so the record is complete (target: signed within 24 h).">Unsigned notes</PageTitle>
      {canAll && (
        <div className="mode-switch" role="group" aria-label="Whose notes">
          <button type="button" className={`secondary small-button${mine ? ' mode-active' : ''}`} aria-pressed={mine} onClick={() => setMine(true)}>
            Mine
          </button>
          <button type="button" className={`secondary small-button${!mine ? ' mode-active' : ''}`} aria-pressed={!mine} onClick={() => setMine(false)}>
            All clinicians
          </button>
        </div>
      )}
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No unsigned notes. Well done.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} unsigned note{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Note</th>
                <th scope="col">Employee</th>
                <th scope="col">Author</th>
                <th scope="col">Started</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row">
                    <a href={`#/encounters/${encodeURIComponent(r.id)}`}>{r.title ?? humanise(r.type)}</a> <AiBadge on={r.aiAssisted} />
                  </th>
                  <td>
                    <a href={`#/employees/${encodeURIComponent(r.employeeId)}/chart`}>{r.employeeId}</a>
                  </td>
                  <td>{r.author ?? '–'}</td>
                  <td>{formatDate(r.createdAt)}</td>
                  <td><StatusBadge status={r.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
