import { useState, type DragEvent, type FormEvent } from 'react';
import { queuePath, seg } from '../api/links';
import type { CaseloadRow, CaseSummary, Page, QueueCode } from '../api/types';
import { can } from '../auth/roles';
import { SafetyBadge, SlaBadge, UrgencyBadge } from '../components/badges';
import { describeError, formatDay } from '../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle } from '../components/ui';
import { useAction, useAsync } from '../components/useAsync';
import { acceptsCaseDrag, CASE_DRAG_MIME, encodeDragPayload, handleCaseDrop, reassign, type ReassignOutcome } from '../domain/dnd';
import type { ScreenProps } from './types';

function utilisationTone(pct: number): 'ok' | 'high' | 'over' {
  return pct > 100 ? 'over' : pct >= 85 ? 'high' : 'ok';
}

function outcomeMessage(o: ReassignOutcome, nameOf: (u: string) => string): { kind: 'success' | 'info' | 'error'; text: string } | undefined {
  switch (o.kind) {
    case 'reassigned': return { kind: 'success', text: `${o.caseRef} reassigned to ${nameOf(o.toUsername)}.` };
    case 'same-owner': return { kind: 'info', text: `${o.caseRef} is already owned by that clinician.` };
    case 'missing-reason': return { kind: 'error', text: 'Enter a reason for the reassignment first.' };
    case 'failed': return { kind: 'error', text: `${o.caseRef} could not be reassigned: ${describeError(o.error)}` };
    default: return undefined;
  }
}

/** E02-F03 caseload vs cap, out-of-office cover, drag-and-drop (or keyboard "Move to…") rebalancing. */
export function Caseload({ client, session, onSessionExpired }: ScreenProps) {
  const canRebalance = can(session.roles, 'caseloadRebalance');
  const caseload = useAsync(() => client.api<CaseloadRow[]>('/assignment/caseload'), onSessionExpired);
  const [queue, setQueue] = useState<QueueCode>('TEAM');
  const cases = useAsync(() => client.api<Page<CaseSummary>>(queuePath(queue, 0, {}, 100)), onSessionExpired, [queue]);
  const [reason, setReason] = useState('Caseload rebalancing');
  const [dropTarget, setDropTarget] = useState<string>();
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'info' | 'error'; text: string }>();
  const [busy, setBusy] = useState(false);
  const availability = useAction(onSessionExpired);
  const [avail, setAvail] = useState({ username: '', outOfOfficeUntil: '', coverUsername: '' });

  const staff = caseload.data ?? [];
  const nameOf = (u: string) => staff.find((s) => s.username === u)?.displayName ?? u;
  const assign = (caseId: string, body: { toUsername: string; reason: string }) => client.api(`/cases/${seg(caseId)}/assign`, { method: 'POST', body });

  async function finish(p: Promise<ReassignOutcome>) {
    setBusy(true);
    const o = await p;
    setBusy(false);
    setFeedback(outcomeMessage(o, nameOf));
    if (o.kind === 'reassigned') {
      cases.reload();
      caseload.reload();
    }
  }

  function onDrop(e: DragEvent<HTMLElement>, username: string) {
    e.preventDefault();
    setDropTarget(undefined);
    void finish(handleCaseDrop(e.dataTransfer, username, reason, assign));
  }

  function onDragOver(e: DragEvent<HTMLElement>, username: string) {
    if (!canRebalance || !acceptsCaseDrag(e.dataTransfer.types)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dropTarget !== username) setDropTarget(username);
  }

  async function saveAvailability(e: FormEvent) {
    e.preventDefault();
    const ok = await availability.run(async () => {
      await client.api(`/staff/${seg(avail.username)}/availability`, {
        method: 'PUT',
        body: { outOfOfficeUntil: avail.outOfOfficeUntil || null, coverUsername: avail.coverUsername || null },
      });
      return `Availability updated for ${nameOf(avail.username)}.`;
    });
    if (ok) caseload.reload();
  }

  return (
    <section>
      <PageTitle sub={canRebalance ? 'Drag a case onto a clinician, or use “Move to…” on the case row.' : 'Team caseload against configured caps.'}>Caseload</PageTitle>
      {caseload.loading && <Loading />}
      {!!caseload.error && <ErrorAlert error={caseload.error} />}
      {feedback && <Alert kind={feedback.kind}>{feedback.text}</Alert>}

      <ul className="nurse-grid" aria-label="Clinicians">
        {staff.map((n) => {
          const pct = Math.round(n.utilisationPct);
          const tone = utilisationTone(pct);
          return (
            <li
              key={n.username}
              className={`nurse-card ${dropTarget === n.username ? 'drop-active' : ''} ${n.outOfOfficeUntil ? 'nurse-ooo' : ''}`}
              onDragOver={(e) => onDragOver(e, n.username)}
              onDragLeave={() => setDropTarget(undefined)}
              onDrop={(e) => onDrop(e, n.username)}
              aria-label={`${n.displayName}: ${n.openCases} of ${n.caseloadCap} cases`}
            >
              <div className="card-row">
                <strong>{n.displayName}</strong>
                <span className="small muted">{(n.roles ?? []).join(', ')}</span>
              </div>
              <div className="util-bar" aria-hidden="true">
                <span className={`util-fill util-${tone}`} style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
              </div>
              <p className="small">
                {n.openCases} / {n.caseloadCap} open · {pct} %{tone === 'over' ? ' – over cap' : tone === 'high' ? ' – near cap' : ''} · {n.urgentCases} urgent
              </p>
              {n.outOfOfficeUntil && (
                <p className="small ooo">
                  Out of office until {formatDay(n.outOfOfficeUntil)}
                  {n.coverUsername ? ` · cover: ${nameOf(n.coverUsername)}` : ' · no cover set'}
                </p>
              )}
            </li>
          );
        })}
      </ul>

      <div className="grid-2 wide-left">
        <div>
          <h2>Cases</h2>
          <div className="toolbar">
            <label htmlFor="cl-queue">Queue</label>
            <select id="cl-queue" value={queue} onChange={(e) => setQueue(e.target.value as QueueCode)}>
              <option value="TEAM">Team</option>
              <option value="UNASSIGNED">Unassigned</option>
              <option value="OVERDUE">Overdue</option>
              <option value="ESCALATED">Escalated</option>
            </select>
            {canRebalance && (
              <>
                <label htmlFor="cl-reason">Reason</label>
                <input id="cl-reason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
              </>
            )}
          </div>
          {cases.loading && <Loading />}
          {!!cases.error && <ErrorAlert error={cases.error} />}
          {cases.data && cases.data.items.length === 0 && <Empty>No cases.</Empty>}
          <ul className="case-rows">
            {(cases.data?.items ?? []).map((c) => (
              <li
                key={c.id}
                className="case-row"
                draggable={canRebalance}
                onDragStart={(e) => {
                  e.dataTransfer.setData(CASE_DRAG_MIME, encodeDragPayload({ caseId: c.id, caseRef: c.caseRef, fromUsername: c.ownerUsername }));
                  e.dataTransfer.effectAllowed = 'move';
                }}
              >
                {canRebalance && <span className="drag-handle" aria-hidden="true">⠿</span>}
                <a href={`#/cases/${encodeURIComponent(c.id)}`}>{c.caseRef}</a>
                <UrgencyBadge urgency={c.urgency} />
                <SlaBadge sla={c.sla} compact />
                <SafetyBadge safetyCritical={c.safetyCritical} />
                <span className="small muted">{c.ownerName ?? c.ownerUsername ?? 'Unassigned'}</span>
                {canRebalance && (
                  <span className="move-to">
                    <label htmlFor={`mv-${c.id}`} className="visually-hidden">Move {c.caseRef} to</label>
                    <select
                      id={`mv-${c.id}`}
                      value=""
                      disabled={busy}
                      onChange={(e) => void finish(reassign({ caseId: c.id, caseRef: c.caseRef, fromUsername: c.ownerUsername }, e.target.value, reason, assign))}
                    >
                      <option value="">Move to…</option>
                      {staff.map((s) => (
                        <option key={s.username} value={s.username} disabled={s.username === c.ownerUsername}>
                          {s.displayName}
                        </option>
                      ))}
                    </select>
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h2>Out of office / cover</h2>
          <form className="form" onSubmit={saveAvailability}>
            <Field label="Clinician" hint={canRebalance ? undefined : 'You can set your own availability.'}>
              {(id) => (
                <select id={id} required value={avail.username} onChange={(e) => setAvail({ ...avail, username: e.target.value })}>
                  <option value="">Choose…</option>
                  {staff.map((s) => (
                    <option key={s.username} value={s.username}>{s.displayName}</option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Out of office until" hint="Leave empty to clear.">{(id) => <input id={id} type="date" value={avail.outOfOfficeUntil} onChange={(e) => setAvail({ ...avail, outOfOfficeUntil: e.target.value })} />}</Field>
            <Field label="Cover">
              {(id) => (
                <select id={id} value={avail.coverUsername} onChange={(e) => setAvail({ ...avail, coverUsername: e.target.value })}>
                  <option value="">No cover</option>
                  {staff.filter((s) => s.username !== avail.username).map((s) => (
                    <option key={s.username} value={s.username}>{s.displayName}</option>
                  ))}
                </select>
              )}
            </Field>
            <button type="submit" disabled={!avail.username || availability.busy}>
              Save availability
            </button>
          </form>
          <ActionFeedback error={availability.error} message={availability.message} />
        </div>
      </div>
    </section>
  );
}
