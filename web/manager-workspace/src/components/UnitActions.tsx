import { ACTION_STATE_LABELS, actionState, sortActions, summariseActions, type UnitAction } from '../api/unit';
import { formatDay } from './format';
import { StatusBadge } from './ui';

export interface ActionTableProps {
  caption: string;
  actions: readonly UnitAction[];
  now?: Date;
  busyId?: string;
  canMarkDone: (a: UnitAction) => boolean;
  onMarkDone: (a: UnitAction) => void;
}

/** Unit actions with due / overdue state; "Mark done" only where the session may change the status. Hook-free. */
export function ActionTable({ caption, actions, now, busyId, canMarkDone, onMarkDone }: ActionTableProps) {
  const sorted = sortActions(actions, now);
  const sum = summariseActions(actions, now);
  if (actions.length === 0) return <p className="muted">No actions recorded.</p>;
  return (
    <>
      <dl className="stat-row" aria-label={`${caption} summary`}>
        <div>
          <dt>Overdue</dt>
          <dd>{sum.OVERDUE}</dd>
        </div>
        <div>
          <dt>Due within 14 days</dt>
          <dd>{sum.DUE_SOON}</dd>
        </div>
        <div>
          <dt>Open</dt>
          <dd>{sum.OPEN + sum.DUE_SOON + sum.OVERDUE}</dd>
        </div>
        <div>
          <dt>Done</dt>
          <dd>{sum.DONE}</dd>
        </div>
      </dl>
      <div className="table-wrap">
        <table>
          <caption className="visually-hidden">{caption}</caption>
          <thead>
            <tr>
              <th scope="col">Action</th>
              <th scope="col">Owner</th>
              <th scope="col">Due</th>
              <th scope="col">State</th>
              <th scope="col">
                <span className="visually-hidden">Update</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((a) => {
              const st = actionState(a, now);
              return (
                <tr key={a.id}>
                  <th scope="row">
                    {a.title}
                    {a.context && <span className="muted small"> · {a.context}</span>}
                  </th>
                  <td>{a.owner ?? '–'}</td>
                  <td>{formatDay(a.dueDate)}</td>
                  <td>
                    <StatusBadge status={`action-${st}`} label={ACTION_STATE_LABELS[st]} />
                  </td>
                  <td>
                    {canMarkDone(a) && (
                      <button type="button" className="secondary small-button" disabled={!!busyId} onClick={() => onMarkDone(a)} aria-label={`Mark "${a.title}" done`}>
                        {busyId === a.id ? 'Saving…' : 'Mark done'}
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
