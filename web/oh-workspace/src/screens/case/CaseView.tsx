import { seg } from '../../api/links';
import type { CaseDetail, CaseHeader as Header } from '../../api/types';
import { DuplicateFlag, IntensityBadge, SafetyBadge, SlaBadge, UrgencyBadge } from '../../components/badges';
import { formatDate, humanise } from '../../components/format';
import { ErrorAlert, Loading, PageTitle, StatusBadge, TabNav, type TabDef } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { AssignmentTab } from './AssignmentTab';
import { ChecklistsTab } from './ChecklistsTab';
import { ComplexityTab } from './ComplexityTab';
import { DuplicatesTab } from './DuplicatesTab';
import { EscalationTab } from './EscalationTab';
import { FitnessTab, StatusTab } from './StatusTabs';
import { OverviewTab } from './OverviewTab';
import { RecordabilityTab } from './RecordabilityTab';
import { TasksTab } from './TasksTab';
import { TimelineTab } from './TimelineTab';
import { TriageTab } from './TriageTab';
import type { CaseTabProps } from './caseTabProps';

const CASE_TABS: TabDef[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'triage', label: 'Triage' },
  { id: 'recordability', label: 'Recordability' },
  { id: 'checklists', label: 'Checklists' },
  { id: 'assignment', label: 'Assignment' },
  { id: 'escalation', label: 'Escalation' },
  { id: 'complexity', label: 'Complexity' },
  { id: 'duplicates', label: 'Duplicates' },
  { id: 'tasks', label: 'Tasks' },
  { id: 'timeline', label: 'Timeline' },
  { id: 'fitness', label: 'Fitness' },
  { id: 'status', label: 'Status' },
];

function TabBody({ tab, props }: { tab: string; props: CaseTabProps }) {
  switch (tab) {
    case 'triage': return <TriageTab {...props} />;
    case 'recordability': return <RecordabilityTab {...props} />;
    case 'checklists': return <ChecklistsTab {...props} />;
    case 'assignment': return <AssignmentTab {...props} />;
    case 'escalation': return <EscalationTab {...props} />;
    case 'complexity': return <ComplexityTab {...props} />;
    case 'duplicates': return <DuplicatesTab {...props} />;
    case 'tasks': return <TasksTab {...props} />;
    case 'timeline': return <TimelineTab {...props} />;
    case 'fitness': return <FitnessTab {...props} />;
    case 'status': return <StatusTab {...props} />;
    default: return <OverviewTab {...props} />;
  }
}

/** E02-BL-018 case header: SLA, safety badge, badges, open tasks, next action, owner, queue, intensity, complexity. */
function CaseHeaderPanel({ h, detail }: { h?: Header; detail?: CaseDetail }) {
  const urgency = h?.urgency ?? detail?.urgency;
  return (
    <div className="case-header" aria-label="Case summary">
      <div className="case-header-badges">
        <StatusBadge status={h?.status ?? detail?.status} />
        <UrgencyBadge urgency={urgency} />
        <SlaBadge sla={h?.sla ?? detail?.sla} />
        <SafetyBadge safetyCritical={h?.safetyCritical ?? detail?.safetyCritical} category={h?.safetyCategory ?? detail?.safetyCategory} />
        <IntensityBadge intensity={h?.intensity ?? detail?.intensity} />
        <DuplicateFlag suspect={detail?.duplicateSuspect} />
        {(h?.badges ?? []).map((b) => (
          <span key={b} className="badge badge-muted">{humanise(b)}</span>
        ))}
      </div>
      <dl className="case-header-grid">
        <div><dt>Owner</dt><dd>{h?.owner ?? detail?.ownerName ?? detail?.ownerUsername ?? 'Unassigned'}</dd></div>
        <div><dt>Queue</dt><dd>{h?.queueCode ?? detail?.queueCode ?? '–'}</dd></div>
        <div><dt>Complexity</dt><dd>{h?.complexityScore ?? detail?.complexityScore ?? '–'}{(h?.complexityScore ?? detail?.complexityScore) != null ? ' / 100' : ''}</dd></div>
        <div><dt>Next action</dt><dd>{h?.nextAction ?? '–'}</dd></div>
        <div>
          <dt>Open tasks</dt>
          <dd>
            {h?.openTasks && h.openTasks.length > 0 ? (
              <ul className="plain-list">
                {h.openTasks.slice(0, 3).map((t) => (
                  <li key={t.id}>{t.title} <span className="small muted">{t.dueAt ? `due ${formatDate(t.dueAt)}` : ''}</span></li>
                ))}
                {h.openTasks.length > 3 && <li className="small muted">+{h.openTasks.length - 3} more</li>}
              </ul>
            ) : (
              (detail?.openTasks ?? 0) || 'None'
            )}
          </dd>
        </div>
      </dl>
    </div>
  );
}

export function CaseView(props: ScreenProps & { caseId: string; tab: string }) {
  const { client, onSessionExpired, caseId, tab } = props;
  const detail = useAsync(() => client.api<CaseDetail>(`/cases/${seg(caseId)}`), onSessionExpired);
  const header = useAsync(() => client.api<Header>(`/cases/${seg(caseId)}/header`), onSessionExpired);
  const refresh = () => {
    detail.reload();
    header.reload();
  };
  const d = detail.data;
  const tabProps: CaseTabProps | undefined = d ? { ...props, caseId, detail: d, refresh } : undefined;
  const current = CASE_TABS.some((t) => t.id === tab) ? tab : 'overview';

  return (
    <section>
      <p className="breadcrumb small">
        <a href="#/queues">Work queues</a> <span aria-hidden="true">›</span> Case
      </p>
      <PageTitle sub={d ? `${d.employeeName} (${d.employeeId}) · ${humanise(d.caseType)} · ${humanise(d.source)}` : undefined}>
        {d?.caseRef ?? header.data?.caseRef ?? 'Case'}
      </PageTitle>
      {d?.status === 'MERGED' && d.mergedIntoCaseId && (
        <div className="alert alert-info" role="status">
          This case was merged. <a href={`#/cases/${encodeURIComponent(d.mergedIntoCaseId)}`}>Open the surviving case</a>.
        </div>
      )}
      {!!header.error && !detail.error && <ErrorAlert error={header.error} />}
      <CaseHeaderPanel h={header.data} detail={d} />
      <TabNav tabs={CASE_TABS} current={current} label="Case sections" hrefFor={(id) => `#/cases/${encodeURIComponent(caseId)}/${id}`} />
      <div className="tab-panel">
        {detail.loading && !d && <Loading label="Loading case…" />}
        {!!detail.error && <ErrorAlert error={detail.error} />}
        {tabProps && <TabBody tab={current} props={tabProps} />}
      </div>
    </section>
  );
}
