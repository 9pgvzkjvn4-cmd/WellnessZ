import { seg } from '../api/links';
import type { EmployeeTimeline } from '../api/types';
import { formatDate, humanise } from '../components/format';
import { Dl, Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** E01-BL-018 longitudinal employee timeline: cases, encounters, absences, triggers, incidents. */
export function EmployeeTimelineScreen({ client, onSessionExpired, employeeId }: ScreenProps & { employeeId: string }) {
  const tl = useAsync(() => client.api<EmployeeTimeline>(`/employees/${seg(employeeId)}/timeline`), onSessionExpired);
  const emp = tl.data?.employee;
  return (
    <section>
      <PageTitle sub={emp?.displayName ? `${emp.displayName} (${employeeId})` : employeeId}>Employee timeline</PageTitle>
      {tl.loading && <Loading />}
      {!!tl.error && <ErrorAlert error={tl.error} />}
      {emp && (
        <Dl
          items={[
            ['Employee', emp.displayName ?? employeeId],
            ['Department', emp.departmentCode],
            ['Job', emp.jobCode],
          ]}
        />
      )}
      {tl.data && tl.data.items.length === 0 && <Empty>No history for this employee.</Empty>}
      <ol className="timeline">
        {(tl.data?.items ?? []).map((i, idx) => (
          <li key={`${i.kind}-${i.ref}-${idx}`}>
            <time dateTime={i.at} className="small muted">{formatDate(i.at)}</time>
            <div>
              <span className={`badge kind-${i.kind.toLowerCase()}`}>{humanise(i.kind)}</span>{' '}
              <span className="mono">{i.ref}</span> – {i.title} {i.status && <StatusBadge status={i.status} />}
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
