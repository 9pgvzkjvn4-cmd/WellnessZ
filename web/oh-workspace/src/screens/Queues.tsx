import { useState, type FormEvent } from 'react';
import { PAGE_SIZE, queuePath, seg } from '../api/links';
import {
  CASE_TYPES,
  QUEUES,
  SAFETY_CATEGORIES,
  URGENCIES,
  type BulkAction,
  type BulkResult,
  type CaseSummary,
  type Page,
  type QueueCode,
  type QueueCounts,
  type QueueFilters,
  type SavedView,
  type StaffMember,
} from '../api/types';
import { can } from '../auth/roles';
import { DuplicateFlag, IntensityBadge, SafetyBadge, SlaBadge, UrgencyBadge } from '../components/badges';
import { BulkResultList } from '../components/BulkResultList';
import { humanise } from '../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../components/ui';
import { useAction, useAsync } from '../components/useAsync';
import { bulkRows, type BulkRow } from '../domain/bulk';
import type { ScreenProps } from './types';

const QUEUE_LABELS: Record<QueueCode, string> = {
  MY: 'My cases',
  TEAM: 'Team',
  UNASSIGNED: 'Unassigned',
  OVERDUE: 'Overdue',
  AWAITING_EXTERNAL: 'Awaiting external',
  AWAITING_EMPLOYEE: 'Awaiting employee',
  ESCALATED: 'Escalated',
};

const EMPTY_FILTERS: QueueFilters = { urgency: '', subsidiary: '', caseType: '', safetyCritical: '', q: '' };

export function Queues({ client, session, onSessionExpired }: ScreenProps) {
  const [queue, setQueue] = useState<QueueCode>('MY');
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<QueueFilters>(EMPTY_FILTERS);
  const [draft, setDraft] = useState<QueueFilters>(EMPTY_FILTERS);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulk, setBulk] = useState<{ action: string; rows: BulkRow[] }>();
  const [bulkAction, setBulkAction] = useState<BulkAction>('REMIND');
  const [toUsername, setToUsername] = useState('');
  const [viewName, setViewName] = useState('');
  const canReassign = can(session.roles, 'bulkReassign');

  const counts = useAsync(() => client.api<QueueCounts>('/queues/counts'), onSessionExpired);
  const rows = useAsync(() => client.api<Page<CaseSummary>>(queuePath(queue, page, filters)), onSessionExpired, [queue, page, filters]);
  const views = useAsync(() => client.api<SavedView[]>('/queues/views'), onSessionExpired);
  const staff = useAsync(() => (canReassign ? client.api<StaffMember[]>('/staff') : Promise.resolve([] as StaffMember[])), onSessionExpired);
  const action = useAction(onSessionExpired);

  const items = rows.data?.items ?? [];
  const total = rows.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function switchQueue(q: QueueCode) {
    setQueue(q);
    setPage(0);
    setSelected(new Set());
    setBulk(undefined);
  }

  function applyFilters(e: FormEvent) {
    e.preventDefault();
    setFilters({ ...draft });
    setPage(0);
    setSelected(new Set());
  }

  function toggle(id: string) {
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  async function runBulk(e: FormEvent) {
    e.preventDefault();
    const caseIds = items.filter((i) => selected.has(i.id)).map((i) => i.id);
    if (caseIds.length === 0) return;
    const refs = new Map(items.map((i) => [i.id, i.caseRef]));
    await action.run(async () => {
      const body: { action: BulkAction; caseIds: string[]; toUsername?: string } = { action: bulkAction, caseIds };
      if (bulkAction === 'REASSIGN') body.toUsername = toUsername;
      const result = await client.api<BulkResult>('/queues/bulk', { method: 'POST', body });
      setBulk({ action: humanise(bulkAction), rows: bulkRows(caseIds, result ?? { succeeded: [], failed: [] }, (id) => refs.get(id)) });
      setSelected(new Set());
      rows.reload();
      counts.reload();
    });
  }

  async function saveView(e: FormEvent) {
    e.preventDefault();
    if (!viewName.trim()) return;
    await action.run(async () => {
      const activeFilters = Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== '' && v !== undefined));
      await client.api<SavedView>('/queues/views', { method: 'POST', body: { name: viewName.trim(), queue, filters: activeFilters } });
      setViewName('');
      views.reload();
      return 'View saved.';
    });
  }

  function openView(v: SavedView) {
    const f = { ...EMPTY_FILTERS, ...v.filters };
    setQueue(QUEUES.includes(v.queue) ? v.queue : 'MY');
    setFilters(f);
    setDraft(f);
    setPage(0);
    setSelected(new Set());
  }

  async function deleteView(v: SavedView) {
    await action.run(async () => {
      await client.api(`/queues/views/${seg(v.id)}`, { method: 'DELETE' });
      views.reload();
    });
  }

  const allSelected = items.length > 0 && items.every((i) => selected.has(i.id));

  return (
    <section>
      <PageTitle sub="Sorted by SLA due time. SLA states: ● on track, ▲ at risk, ■ breached.">Work queues</PageTitle>

      <div className="queue-tabs" role="tablist" aria-label="Queues">
        {QUEUES.map((q) => (
          <button
            key={q}
            type="button"
            role="tab"
            aria-selected={queue === q}
            className={`queue-tab ${queue === q ? 'active' : ''} ${q === 'OVERDUE' || q === 'ESCALATED' ? 'queue-tab-alert' : ''}`}
            onClick={() => switchQueue(q)}
          >
            {QUEUE_LABELS[q]} <span className="count">{counts.data?.[q] ?? '–'}</span>
          </button>
        ))}
      </div>
      {!!counts.error && <ErrorAlert error={counts.error} />}

      <div className="queue-layout">
        <div>
          <form className="filters" onSubmit={applyFilters} aria-label="Queue filters">
            <Field label="Search">{(id) => <input id={id} type="search" value={draft.q} placeholder="Case ref, employee…" onChange={(e) => setDraft({ ...draft, q: e.target.value })} />}</Field>
            <Field label="Urgency">
              {(id) => (
                <select id={id} value={draft.urgency} onChange={(e) => setDraft({ ...draft, urgency: e.target.value })}>
                  <SelectOptions values={URGENCIES} empty="Any" />
                </select>
              )}
            </Field>
            <Field label="Subsidiary">{(id) => <input id={id} value={draft.subsidiary} size={6} placeholder="e.g. CAT" onChange={(e) => setDraft({ ...draft, subsidiary: e.target.value.toUpperCase() })} />}</Field>
            <Field label="Case type">
              {(id) => (
                <select id={id} value={draft.caseType} onChange={(e) => setDraft({ ...draft, caseType: e.target.value })}>
                  <SelectOptions values={CASE_TYPES} empty="Any" />
                </select>
              )}
            </Field>
            <Field label="Safety-critical">
              {(id) => (
                <select id={id} value={draft.safetyCritical} onChange={(e) => setDraft({ ...draft, safetyCritical: e.target.value })}>
                  <option value="">Any</option>
                  <option value="true">Yes</option>
                  <option value="false">No</option>
                </select>
              )}
            </Field>
            <div className="filter-actions">
              <button type="submit">Apply</button>
              <button type="button" className="secondary" onClick={() => { setDraft(EMPTY_FILTERS); setFilters(EMPTY_FILTERS); setPage(0); }}>
                Clear
              </button>
            </div>
          </form>

          <form className="bulk-bar" onSubmit={runBulk} aria-label="Bulk actions">
            <span className="small">{selected.size} selected</span>
            <label htmlFor="bulk-action" className="visually-hidden">Bulk action</label>
            <select id="bulk-action" value={bulkAction} onChange={(e) => setBulkAction(e.target.value as BulkAction)}>
              <option value="REMIND">Send reminder</option>
              <option value="CLOSE_DUPLICATES">Close as duplicates</option>
              {canReassign && <option value="REASSIGN">Reassign (Nursing Head)</option>}
            </select>
            {bulkAction === 'REASSIGN' && (
              <>
                <label htmlFor="bulk-to" className="visually-hidden">Reassign to</label>
                <select id="bulk-to" value={toUsername} required onChange={(e) => setToUsername(e.target.value)}>
                  <option value="">Choose clinician…</option>
                  {(staff.data ?? []).filter((s) => s.active !== false).map((s) => (
                    <option key={s.username} value={s.username}>{s.displayName}</option>
                  ))}
                </select>
              </>
            )}
            <button type="submit" disabled={selected.size === 0 || action.busy || (bulkAction === 'REASSIGN' && !toUsername)}>
              Apply to selected
            </button>
          </form>
          <ActionFeedback error={action.error} message={action.message} />
          {bulk && <BulkResultList action={bulk.action} rows={bulk.rows} />}

          {rows.loading && <Loading />}
          {!!rows.error && <ErrorAlert error={rows.error} />}
          {!rows.loading && !rows.error && items.length === 0 && <Empty>No cases in this queue.</Empty>}
          {items.length > 0 && (
            <div className="table-wrap">
              <table className="queue-table">
                <caption className="visually-hidden">{QUEUE_LABELS[queue]} – {total} cases</caption>
                <thead>
                  <tr>
                    <th scope="col">
                      <input
                        type="checkbox"
                        aria-label="Select all cases on this page"
                        checked={allSelected}
                        onChange={() => setSelected(allSelected ? new Set() : new Set(items.map((i) => i.id)))}
                      />
                    </th>
                    <th scope="col">Case</th>
                    <th scope="col">Employee / unit</th>
                    <th scope="col">Type</th>
                    <th scope="col">Urgency</th>
                    <th scope="col">SLA</th>
                    <th scope="col">Owner</th>
                    <th scope="col">Waiting</th>
                    <th scope="col">Flags</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((c) => (
                    <tr key={c.id} className={c.sla?.state === 'BREACHED' ? 'row-breached' : undefined}>
                      <td>
                        <input type="checkbox" aria-label={`Select ${c.caseRef}`} checked={selected.has(c.id)} onChange={() => toggle(c.id)} />
                      </td>
                      <th scope="row">
                        <a href={`#/cases/${encodeURIComponent(c.id)}`}>{c.caseRef}</a>
                        <div><StatusBadge status={c.status} /></div>
                      </th>
                      <td>
                        <a href={`#/employees/${encodeURIComponent(c.employeeId)}/chart`} title="Open OH chart">{c.employeeName}</a>
                        <div className="small muted">{[c.subsidiaryCode, c.departmentCode, c.locationCode].filter(Boolean).join(' · ')}</div>
                      </td>
                      <td>{humanise(c.caseType)}</td>
                      <td><UrgencyBadge urgency={c.urgency} /></td>
                      <td><SlaBadge sla={c.sla} /></td>
                      <td>{c.ownerName ?? c.ownerUsername ?? <span className="muted">Unassigned</span>}<div className="small muted">{c.queueCode}</div></td>
                      <td>{c.waitingState && c.waitingState !== 'NONE' ? humanise(c.waitingState) : '–'}</td>
                      <td className="flags">
                        <SafetyBadge safetyCritical={c.safetyCritical} category={c.safetyCategory} />
                        <IntensityBadge intensity={c.intensity === 'INTENSIVE' ? c.intensity : undefined} />
                        <DuplicateFlag suspect={c.duplicateSuspect} />
                        {c.earlyIntervention && <span className="badge badge-muted">Early intervention</span>}
                        {!!c.openTasks && <span className="badge badge-muted">{c.openTasks} task{c.openTasks === 1 ? '' : 's'}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {total > 0 && (
            <nav className="pager" aria-label="Pagination">
              <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Previous
              </button>
              <span className="small">
                Page {page + 1} of {pages} · {total} cases
              </span>
              <button type="button" className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
                Next
              </button>
            </nav>
          )}
        </div>

        <aside className="side-panel" aria-labelledby="views-heading">
          <h2 id="views-heading">Saved views</h2>
          {!!views.error && <ErrorAlert error={views.error} />}
          {views.data && views.data.length === 0 && <Empty>No saved views yet.</Empty>}
          <ul className="plain-list">
            {(views.data ?? []).map((v) => (
              <li key={v.id} className="view-row">
                <button type="button" className="link-button" onClick={() => openView(v)}>
                  {v.name}
                </button>
                <span className="small muted">{QUEUE_LABELS[v.queue] ?? v.queue}</span>
                <button type="button" className="link-button danger" aria-label={`Delete view ${v.name}`} onClick={() => void deleteView(v)}>
                  Delete
                </button>
              </li>
            ))}
          </ul>
          <form className="form compact" onSubmit={saveView}>
            <Field label="Save current queue + filters as">{(id) => <input id={id} value={viewName} maxLength={60} onChange={(e) => setViewName(e.target.value)} />}</Field>
            <button type="submit" className="secondary" disabled={!viewName.trim() || action.busy}>
              Save view
            </button>
          </form>
          <h2>Safety categories</h2>
          <p className="small muted">Safety-critical roles ({SAFETY_CATEGORIES.map((s) => humanise(s)).join(', ')}) route to physician / AME queues and cannot close without a fitness decision.</p>
        </aside>
      </div>
    </section>
  );
}
