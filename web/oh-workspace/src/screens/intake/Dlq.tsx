import { seg } from '../../api/links';
import type { DlqItem } from '../../api/types';
import { formatDate } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

/** Dead-letter queue for schema-invalid EHS webhook payloads (OH_ADMIN, PLATFORM_ADMIN). */
export function Dlq({ client, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => client.api<DlqItem[]>('/intake/dlq'), onSessionExpired);
  const action = useAction(onSessionExpired);

  async function act(item: DlqItem, op: 'replay' | 'discard') {
    if (await action.run(async () => { await client.api(`/intake/dlq/${seg(item.id)}/${op}`, { method: 'POST' }); return op === 'replay' ? 'Item replayed.' : 'Item discarded.'; })) list.reload();
  }

  return (
    <section>
      <PageTitle sub="Incident payloads that failed validation. Replay after the source is fixed, or discard.">Dead-letter queue</PageTitle>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && list.data.length === 0 && <Empty>The dead-letter queue is empty.</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Received</th>
                <th scope="col">Source</th>
                <th scope="col">Reason</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((d) => (
                <tr key={d.id}>
                  <td>{formatDate(d.receivedAt)}</td>
                  <td>{d.source}</td>
                  <td className="small">{d.reason}</td>
                  <td><StatusBadge status={d.status} /></td>
                  <td>
                    <div className="card-actions">
                      <button type="button" className="small-button" disabled={action.busy} onClick={() => void act(d, 'replay')}>
                        Replay
                      </button>
                      <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void act(d, 'discard')}>
                        Discard
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
