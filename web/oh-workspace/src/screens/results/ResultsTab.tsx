import { useState } from 'react';
import { codingText, ORDER_CATEGORIES, recordApi } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ChartTabProps } from '../chart/chartTabProps';
import { DeviceResultForm } from './DeviceResultForm';
import { OrderForm } from './OrderForm';
import { OverdueBadge, ResultFlag, ResultValue } from './ResultFlag';

/** Chart "Results" tab: results with flags (filter by category), orders (place / cancel) and device result entry. */
export function ResultsTab({ client, session, onSessionExpired, employeeId, caseId, refreshSummary }: ChartTabProps) {
  const api = recordApi(client);
  const [category, setCategory] = useState('');
  const results = useAsync(() => api.results(employeeId, category || undefined), onSessionExpired, [category]);
  const orders = useAsync(() => api.orders(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [panel, setPanel] = useState<'order' | 'device'>();
  const canOrder = can(session.roles, 'orderWrite');
  const canDevice = can(session.roles, 'deviceResults');

  async function cancel(id: string, ref: string) {
    if (await action.run(async () => { await api.cancelOrder(id); return `Order ${ref} cancelled.`; })) orders.reload();
  }

  const rows = results.data ?? [];
  return (
    <div>
      <h2>Results</h2>
      <div className="filters inline-form">
        <Field label="Category">
          {(id) => (
            <select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
              <SelectOptions values={ORDER_CATEGORIES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      {results.loading && <Loading />}
      {!!results.error && <ErrorAlert error={results.error} />}
      {results.data && rows.length === 0 && <Empty>No results.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Results</caption>
            <thead>
              <tr>
                <th scope="col">Test</th>
                <th scope="col">Value</th>
                <th scope="col">Flag</th>
                <th scope="col">Status</th>
                <th scope="col">Taken</th>
                <th scope="col">Source</th>
                <th scope="col">Acknowledged</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row">{codingText(r.code)}<div className="small muted">{humanise(r.category)}</div></th>
                  <td><ResultValue r={r} /></td>
                  <td><ResultFlag flag={r.flag} /></td>
                  <td><StatusBadge status={r.status} /></td>
                  <td>{formatDate(r.effectiveAt)}</td>
                  <td>
                    {humanise(r.source)}
                    {r.calibrationId && <div className="small muted">Cal. {r.calibrationId}</div>}
                  </td>
                  <td>
                    {r.acknowledgedAt ? (
                      <>
                        {humanise(r.ackAction)} <div className="small muted">{r.acknowledgedBy} {formatDate(r.acknowledgedAt)}</div>
                      </>
                    ) : r.flag && r.flag !== 'NORMAL' ? (
                      <OverdueBadge overdue={r.overdue} ageHours={r.ageHours} />
                    ) : (
                      '–'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2>Orders</h2>
      <ActionFeedback error={action.error} message={action.message} />
      {orders.loading && <Loading />}
      {!!orders.error && <ErrorAlert error={orders.error} />}
      {orders.data && orders.data.length === 0 && <Empty>No orders.</Empty>}
      {(orders.data ?? []).length > 0 && (
        <ul className="card-list">
          {(orders.data ?? []).map((o) => (
            <li key={o.id} className="card">
              <span className="mono">{o.placerOrderNumber}</span> {humanise(o.category)} <StatusBadge status={o.status} /> {o.priority === 'URGENT' && <span className="badge badge-warning">Urgent</span>}
              <div className="small">{o.items.map((i) => i.display).join(', ')}</div>
              <div className="small muted">
                {o.orderedBy} {formatDate(o.orderedAt)} · {o.resultCount ?? 0} result(s)
              </div>
              {canOrder && (o.status === 'QUEUED' || o.status === 'DRAFT' || o.status === 'SENT') && (
                <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void cancel(o.id, o.placerOrderNumber)}>
                  Cancel order
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <div className="actions">
        {canOrder && (
          <button type="button" className="secondary" aria-expanded={panel === 'order'} onClick={() => setPanel(panel === 'order' ? undefined : 'order')}>
            New order
          </button>
        )}
        {canDevice && (
          <button type="button" className="secondary" aria-expanded={panel === 'device'} onClick={() => setPanel(panel === 'device' ? undefined : 'device')}>
            Enter device result
          </button>
        )}
      </div>
      {panel === 'order' && canOrder && <OrderForm client={client} onSessionExpired={onSessionExpired} employeeId={employeeId} caseId={caseId} onPlaced={() => orders.reload()} />}
      {panel === 'device' && canDevice && (
        <DeviceResultForm
          client={client}
          onSessionExpired={onSessionExpired}
          employeeId={employeeId}
          caseId={caseId}
          onSaved={() => {
            results.reload();
            refreshSummary();
          }}
        />
      )}
    </div>
  );
}
