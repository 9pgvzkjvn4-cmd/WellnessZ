import { codingText, recordApi, type CaseHandoverSummary } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDate, formatDay, humanise } from '../../components/format';
import { Dl, Empty, ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { ChartTabProps } from '../chart/chartTabProps';
import { DocumentsPanel } from '../documents/DocumentsPanel';
import { EncounterList } from '../encounter/EncounterList';
import type { CaseTabProps } from './caseTabProps';

const chartProps = (p: CaseTabProps): ChartTabProps => ({
  client: p.client,
  session: p.session,
  onSessionExpired: p.onSessionExpired,
  employeeId: p.detail.employeeId,
  caseId: p.caseId,
  canWrite: can(p.session.roles, 'chartWrite'),
  refreshSummary: p.refresh,
});

/** Case documents (generated, scanned, shared) – same panel as the chart, scoped to the case. */
export function CaseDocumentsTab(p: CaseTabProps) {
  return <DocumentsPanel {...chartProps(p)} />;
}

/** Encounters linked to this case, plus "new encounter" with the case pre-linked. */
export function CaseEncountersTab(p: CaseTabProps) {
  return <EncounterList {...chartProps(p)} />;
}

function keyDates(k: CaseHandoverSummary['keyDates']): [string, string][] {
  if (!k) return [];
  if (Array.isArray(k)) return k.map((x) => [x.label, formatDay(x.date)]);
  return Object.entries(k).map(([label, date]) => [humanise(label.replace(/([a-z])([A-Z])/g, '$1_$2')), formatDay(date)]);
}

/** Rule-based (non-AI) handover summary `GET /cases/{id}/summary`. */
export function SummaryTab({ client, onSessionExpired, caseId }: CaseTabProps) {
  const s = useAsync(() => recordApi(client).caseSummary(caseId), onSessionExpired);
  const d = s.data;
  return (
    <div>
      <h2>Case summary for handover</h2>
      <p className="small muted">Generated from the record by rules – no AI involved.</p>
      {s.loading && <Loading />}
      {!!s.error && <ErrorAlert error={s.error} />}
      {d && (
        <div className="grid-2">
          <div>
            <h3>Key dates</h3>
            {keyDates(d.keyDates).length === 0 ? <Empty>None.</Empty> : <Dl items={keyDates(d.keyDates)} />}
            <h3>Diagnoses</h3>
            {(d.diagnoses ?? []).length === 0 ? <Empty>No coded diagnosis.</Empty> : <ul className="plain-list">{(d.diagnoses ?? []).map((x) => <li key={`${x.system}-${x.code}`}>{codingText(x)}</li>)}</ul>}
            <h3>Last encounter</h3>
            {d.lastEncounter ? (
              <p>
                {d.lastEncounter.id ? <a href={`#/encounters/${encodeURIComponent(d.lastEncounter.id)}`}>{d.lastEncounter.title ?? humanise(d.lastEncounter.type)}</a> : (d.lastEncounter.title ?? humanise(d.lastEncounter.type))}{' '}
                <span className="small muted">{formatDate(d.lastEncounter.signedAt)}</span>
              </p>
            ) : (
              <Empty>None.</Empty>
            )}
          </div>
          <div>
            <h3>Fitness</h3>
            {d.fitness ? (
              <p>
                <StatusBadge status={d.fitness.status} /> <span className="small">valid until {formatDay(d.fitness.validUntil)} · review {formatDay(d.fitness.reviewDate)}</span>
              </p>
            ) : (
              <Empty>Not assessed.</Empty>
            )}
            <h3>Restrictions</h3>
            {(d.restrictions ?? []).length === 0 ? <Empty>None.</Empty> : <ul className="plain-list">{(d.restrictions ?? []).map((r, i) => <li key={i}>{r.label} <span className="small muted">until {formatDay(r.validUntil)}</span></li>)}</ul>}
            <h3>Open tasks</h3>
            {(d.openTasks ?? []).length === 0 ? <Empty>None.</Empty> : <ul className="plain-list">{(d.openTasks ?? []).map((t, i) => <li key={i}>{t.title} <span className="small muted">{formatDate(t.dueAt)}</span></li>)}</ul>}
          </div>
        </div>
      )}
    </div>
  );
}
