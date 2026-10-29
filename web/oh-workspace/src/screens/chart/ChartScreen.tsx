import { useState } from 'react';
import { fhirExportUrl, odhExportUrl, recordApi, type ChartSummary } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDay, humanise } from '../../components/format';
import { Empty, ErrorAlert, Loading, PageTitle, StatusBadge, TabNav, type TabDef } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { DocumentsPanel } from '../documents/DocumentsPanel';
import { EncounterList } from '../encounter/EncounterList';
import { ResultsTab } from '../results/ResultsTab';
import type { ScreenProps } from '../types';
import { BreakGlass } from './BreakGlass';
import type { ChartTabProps } from './chartTabProps';
import { MedicationsTab } from './MedicationsTab';
import { AllergiesTab, ImmunisationsTab, JobsTab, ProblemsTab, RecordTimelineTab } from './RecordLists';

const CHART_TABS: TabDef[] = [
  { id: 'problems', label: 'Problems' },
  { id: 'medications', label: 'Medications' },
  { id: 'allergies', label: 'Allergies' },
  { id: 'immunisations', label: 'Immunisations' },
  { id: 'jobs', label: 'Jobs & exposures' },
  { id: 'results', label: 'Results' },
  { id: 'documents', label: 'Documents' },
  { id: 'encounters', label: 'Encounters' },
  { id: 'timeline', label: 'Timeline' },
];

const SEVERITY_SYMBOL: Record<string, string> = { HIGH: '!!', CRITICAL: '!!', MEDIUM: '!', WARNING: '!', LOW: 'i', INFO: 'i' };

function SeverityTag({ severity }: { severity: string }) {
  return (
    <span className={`badge sev sev-${severity.toLowerCase()}`}>
      <span aria-hidden="true">{SEVERITY_SYMBOL[severity] ?? '•'} </span>
      {humanise(severity)}
    </span>
  );
}

/** E03-BL-002 chart summary: alerts, fitness, restrictions, open cases, surveillance due, immunisations, counts. */
export function ChartSummaryPanel({ s }: { s: ChartSummary }) {
  return (
    <section className="chart-summary" aria-label="Chart summary">
      <div className="chart-summary-block">
        <h2>Alerts</h2>
        {s.alerts.length === 0 ? (
          <p className="muted small">No alerts.</p>
        ) : (
          <ul className="plain-list alert-list">
            {s.alerts.map((a, i) => (
              <li key={`${a.kind}-${i}`}>
                <SeverityTag severity={a.severity} /> <span className="small muted">{humanise(a.kind)}</span> – {a.text}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="chart-summary-block">
        <h2>Fitness</h2>
        {s.fitness ? (
          <p>
            <StatusBadge status={s.fitness.status} /> {s.fitness.standardCode && <span className="small">{humanise(s.fitness.standardCode)}</span>}
            <br />
            <span className="small muted">
              Valid until {formatDay(s.fitness.validUntil)} · review {formatDay(s.fitness.reviewDate)}
              {s.fitness.decidedBy ? ` · ${s.fitness.decidedBy}` : ''}
            </span>
          </p>
        ) : (
          <p className="muted small">Not assessed.</p>
        )}
        <h3>Restrictions</h3>
        {s.restrictions.length === 0 ? (
          <p className="muted small">None.</p>
        ) : (
          <ul className="plain-list">
            {s.restrictions.map((r) => (
              <li key={`${r.code}-${r.validUntil ?? ''}`}>
                {r.label} <span className="small muted">until {formatDay(r.validUntil)}</span> <StatusBadge status={r.status} />
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="chart-summary-block">
        <h2>Open cases</h2>
        {s.openCases.length === 0 ? (
          <p className="muted small">None.</p>
        ) : (
          <ul className="plain-list">
            {s.openCases.map((c) => (
              <li key={c.id}>
                <a href={`#/cases/${encodeURIComponent(c.id)}`}>{c.caseRef}</a> <span className="small">{humanise(c.caseType)}</span> <StatusBadge status={c.status} />
              </li>
            ))}
          </ul>
        )}
        <h3>Surveillance due</h3>
        {s.surveillanceDue.length === 0 ? (
          <p className="muted small">Nothing due.</p>
        ) : (
          <ul className="plain-list">
            {s.surveillanceDue.map((d) => (
              <li key={`${d.hazardCode}-${d.dueDate}`}>
                {d.programme} ({humanise(d.hazardCode)}) – {formatDay(d.dueDate)}{' '}
                {d.overdue && (
                  <span className="badge badge-overdue">
                    <span aria-hidden="true">■ </span>Overdue
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="chart-summary-block">
        <h2>Immunisations</h2>
        {s.immunisations.length === 0 ? (
          <p className="muted small">None recorded.</p>
        ) : (
          <ul className="plain-list">
            {s.immunisations.map((i) => (
              <li key={`${i.vaccine}-${i.date ?? ''}`}>
                {i.vaccine} <span className="small muted">{formatDay(i.date)}</span> <StatusBadge status={i.status} />
              </li>
            ))}
          </ul>
        )}
        <h3>Record</h3>
        <ul className="plain-list small counts">
          <li>{s.counts.problems} problems · {s.counts.medications} medications · {s.counts.allergies} allergies</li>
          <li>
            {s.counts.results} results
            {s.counts.unacknowledgedAbnormal > 0 && <strong className="text-red"> · {s.counts.unacknowledgedAbnormal} unacknowledged abnormal</strong>}
          </li>
          <li>
            {s.counts.documents} documents · {s.counts.unsignedEncounters} unsigned encounter{s.counts.unsignedEncounters === 1 ? '' : 's'}
          </li>
        </ul>
      </div>
    </section>
  );
}

function ChartTabBody({ tab, props }: { tab: string; props: ChartTabProps }) {
  switch (tab) {
    case 'medications': return <MedicationsTab {...props} />;
    case 'allergies': return <AllergiesTab {...props} />;
    case 'immunisations': return <ImmunisationsTab {...props} />;
    case 'jobs': return <JobsTab {...props} />;
    case 'results': return <ResultsTab {...props} />;
    case 'documents': return <DocumentsPanel {...props} />;
    case 'encounters': return <EncounterList {...props} />;
    case 'timeline': return <RecordTimelineTab {...props} />;
    default: return <ProblemsTab {...props} />;
  }
}

/** `#/employees/{id}/chart[/{tab}]` – E03-F01 longitudinal OH record. */
export function ChartScreen(props: ScreenProps & { employeeId: string; tab?: string }) {
  const { client, session, onSessionExpired, employeeId } = props;
  const chart = useAsync(() => recordApi(client).chart(employeeId), onSessionExpired);
  const [generation, setGeneration] = useState(0);
  const tab = CHART_TABS.some((t) => t.id === props.tab) ? (props.tab as string) : 'problems';
  const s = chart.data;
  const refreshSummary = chart.reload;
  const tabProps: ChartTabProps = { client, session, onSessionExpired, employeeId, canWrite: can(session.roles, 'chartWrite'), refreshSummary };
  const emp = s?.employee;

  return (
    <section>
      <p className="breadcrumb small">
        <a href="#/employees">Employee chart</a> <span aria-hidden="true">›</span> {employeeId}
      </p>
      <PageTitle sub={emp ? [emp.jobTitle ?? emp.jobCode, emp.departmentCode, emp.subsidiaryCode, emp.locationCode, emp.safetyCategory ? `Safety: ${humanise(emp.safetyCategory)}` : undefined].filter(Boolean).join(' · ') : undefined}>
        {emp ? `${emp.displayName} (${emp.employeeId})` : 'OH chart'}
      </PageTitle>
      <div className="toolbar">
        <div className="toolbar-left">
          <a className="button secondary" href={`#/employees/${encodeURIComponent(employeeId)}/timeline`}>Employee timeline</a>
          <a className="button secondary" href={fhirExportUrl(employeeId)} download={`${employeeId}-fhir.json`} rel="noopener">
            Download FHIR record
          </a>
          <a className="button secondary" href={odhExportUrl(employeeId)} download={`${employeeId}-odh.json`} rel="noopener">
            Download ODH (jobs & exposures)
          </a>
        </div>
      </div>
      {chart.loading && !s && <Loading label="Loading chart…" />}
      {!!chart.error && <ErrorAlert error={chart.error} />}
      {s && (
        <>
          <BreakGlass
            client={client}
            onSessionExpired={onSessionExpired}
            employeeId={employeeId}
            hidden={s.restrictedItemsHidden}
            canUse={can(session.roles, 'breakGlass')}
            onGranted={() => {
              chart.reload();
              setGeneration((g) => g + 1);
            }}
          />
          <ChartSummaryPanel s={s} />
        </>
      )}
      {!s && !chart.loading && !chart.error && <Empty>No chart.</Empty>}
      <TabNav tabs={CHART_TABS} current={tab} label="Chart sections" hrefFor={(id) => `#/employees/${encodeURIComponent(employeeId)}/chart/${id}`} />
      <div className="tab-panel" key={`${tab}-${generation}`}>
        <ChartTabBody tab={tab} props={tabProps} />
      </div>
    </section>
  );
}
