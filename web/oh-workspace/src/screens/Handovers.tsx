import { seg } from '../api/links';
import type { Handover } from '../api/types';
import { formatDate } from '../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle } from '../components/ui';
import { useAction, useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** E02-BL-019 handovers waiting for my acknowledgement. */
export function Handovers({ client, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => client.api<Handover[]>('/handovers/pending'), onSessionExpired);
  const action = useAction(onSessionExpired);

  async function ack(h: Handover) {
    if (await action.run(async () => { await client.api(`/handovers/${seg(h.id)}/acknowledge`, { method: 'POST' }); return `Handover for ${h.caseRef ?? 'case'} acknowledged – you are now responsible.`; })) list.reload();
  }

  return (
    <section>
      <PageTitle sub="Cases handed over to you. Acknowledge to accept responsibility.">Pending handovers</PageTitle>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && list.data.length === 0 && <Empty>No pending handovers.</Empty>}
      <ul className="card-list">
        {(list.data ?? []).map((h) => (
          <li key={h.id} className="card">
            <div className="card-row">
              <span>
                <a href={`#/cases/${encodeURIComponent(h.caseId)}`}>{h.caseRef ?? 'Open case'}</a> from <strong>{h.fromUsername ?? '–'}</strong>
              </span>
              <span className="small muted">{formatDate(h.createdAt)}</span>
            </div>
            <p className="prewrap">{h.summary}</p>
            {h.outstandingActions && <p className="small prewrap"><strong>Outstanding:</strong> {h.outstandingActions}</p>}
            <p className="small">Next contact: {formatDate(h.nextContactAt)}</p>
            <button type="button" className="small-button" disabled={action.busy} onClick={() => void ack(h)}>
              Acknowledge
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
