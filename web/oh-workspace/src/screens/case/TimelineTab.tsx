import type { TimelineEvent } from '../../api/types';
import { formatDate, humanise } from '../../components/format';
import { Empty, ErrorAlert, Loading } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

export function TimelineTab({ client, caseId, detail, onSessionExpired }: CaseTabProps) {
  const events = useAsync(() => client.api<TimelineEvent[]>(casePath(caseId, '/timeline')), onSessionExpired);
  return (
    <div>
      <div className="card-row">
        <h2>Case timeline</h2>
        <a href={`#/employees/${encodeURIComponent(detail.employeeId)}/timeline`}>Employee timeline (all cases, encounters, absences) →</a>
      </div>
      {events.loading && <Loading />}
      {!!events.error && <ErrorAlert error={events.error} />}
      {events.data && events.data.length === 0 && <Empty>No events.</Empty>}
      <ol className="timeline">
        {(events.data ?? []).map((e, i) => (
          <li key={`${e.at}-${i}`}>
            <time dateTime={e.at} className="small muted">{formatDate(e.at)}</time>
            <div>
              <span className="badge badge-muted">{humanise(e.type)}</span> {e.summary}
              {e.actor && <span className="small muted"> · {e.actor}</span>}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
