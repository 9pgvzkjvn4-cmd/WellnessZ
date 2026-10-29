import { Fragment, useState, type FormEvent } from 'react';
import { absenceApi, CONTACT_CHANNELS, CONTACT_OUTCOMES, QUEUE_STATUSES, RULE_ACTIONS, type Episode, type QueueItem } from '../../api/absence';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { slaTone } from '../../domain/absence';
import type { ScreenProps } from '../types';

const chartLink = (employeeId: string) => `#/employees/${encodeURIComponent(employeeId)}/chart/absence`;

/** Episode history table (queue item history, chart). OH only – may contain the reason category. */
export function EpisodeTable({ episodes, caption }: { episodes: Episode[]; caption: string }) {
  if (episodes.length === 0) return <Empty>No episodes.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Start</th>
            <th scope="col">End</th>
            <th scope="col" className="num">Days</th>
            <th scope="col">Type</th>
            <th scope="col">Reason category</th>
            <th scope="col">Certificate</th>
            <th scope="col">Source</th>
          </tr>
        </thead>
        <tbody>
          {episodes.map((e) => (
            <tr key={e.id}>
              <th scope="row">{formatDay(e.startDate)}</th>
              <td>{e.open ? <ToneBadge tone="amber">Open</ToneBadge> : formatDay(e.endDate)}</td>
              <td className="num">{e.days}</td>
              <td>{humanise(e.absenceType)}</td>
              <td>{humanise(e.reasonCategory)}</td>
              <td>{e.certified ? <StatusBadge status={e.certificateValidity ?? 'PENDING'} /> : <span className="muted small">Self-certified</span>}</td>
              <td className="small">{e.sourceSystem}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

type DialogState = { item: QueueItem; mode: 'contact' | 'close' };

/** `#/absence/queue` – early-intervention queue raised by trigger rules (E06-F02); AME_REVIEW items are the AME queue. */
export function AbsenceQueue({ client, onSessionExpired }: ScreenProps) {
  const api = absenceApi(client);
  const [filter, setFilter] = useState({ status: '', action: '' });
  const list = useAsync(() => api.queue(filter), onSessionExpired, [filter]);
  const action = useAction(onSessionExpired);
  const [open, setOpen] = useState<string>();
  const [dialog, setDialog] = useState<DialogState>();
  const [contact, setContact] = useState({ channel: 'PHONE', outcome: 'REACHED', note: '' });
  const [reason, setReason] = useState('');

  async function accept(it: QueueItem) {
    const ok = await action.run(async () => {
      const r = await api.accept(it.id);
      return `Accepted – ${it.displayName} assigned to you${r.caseRef ? ` (case ${r.caseRef})` : ''}.`;
    });
    if (ok) list.reload();
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!dialog) return;
    const { item, mode } = dialog;
    if (mode === 'close' && !reason.trim()) return;
    const ok = await action.run(async () => {
      if (mode === 'contact') {
        await api.contact(item.id, { channel: contact.channel, outcome: contact.outcome, ...(contact.note.trim() ? { note: contact.note.trim() } : {}) });
        return `Contact recorded for ${item.displayName}.`;
      }
      await api.close(item.id, reason.trim());
      return `${item.displayName} closed.`;
    });
    if (ok) {
      setDialog(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Raised by the absence trigger rules. Accepting opens or links the absence case and tells the manager and HR that OH is engaged (no reasons are shared).">
        Early-intervention queue
      </PageTitle>
      <div className="mode-switch" role="group" aria-label="Queue">
        {['', ...RULE_ACTIONS].map((a) => (
          <button key={a || 'all'} type="button" className={`secondary small-button${filter.action === a ? ' mode-active' : ''}`} aria-pressed={filter.action === a} onClick={() => setFilter({ ...filter, action: a })}>
            {a === '' ? 'All' : a === 'AME_REVIEW' ? 'AME review' : humanise(a)}
          </button>
        ))}
      </div>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
              <SelectOptions values={QUEUE_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No queue items match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} item{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Unit</th>
                <th scope="col">Rule</th>
                <th scope="col">Action</th>
                <th scope="col">Raised</th>
                <th scope="col">SLA</th>
                <th scope="col">Status</th>
                <th scope="col">Case</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((it) => {
                const sla = it.status === 'CLOSED' || it.firstContactAt ? undefined : slaTone(it.slaDueAt);
                const expanded = open === it.id;
                return (
                  <Fragment key={it.id}>
                    <tr className={sla?.tone === 'red' ? 'row-breached' : undefined}>
                      <th scope="row"><a href={chartLink(it.employeeId)}>{it.displayName}</a> <span className="small muted">{it.employeeId}</span></th>
                      <td>{it.unit ?? '–'}</td>
                      <td>{it.ruleName} <span className="small muted mono">{it.ruleCode}</span></td>
                      <td>{it.action === 'AME_REVIEW' ? <ToneBadge tone="amber">AME review</ToneBadge> : humanise(it.action)}</td>
                      <td className="small">{formatDate(it.raisedAt)}</td>
                      <td>{sla ? <ToneBadge tone={sla.tone}>{sla.label}</ToneBadge> : it.firstContactAt ? <span className="small">Contacted {formatDay(it.firstContactAt)}</span> : '–'}</td>
                      <td><StatusBadge status={it.status} /></td>
                      <td>{it.caseId ? <a href={`#/cases/${encodeURIComponent(it.caseId)}`}>{it.caseRef ?? 'Case'}</a> : '–'}</td>
                      <td>
                        <div className="card-actions">
                          <button type="button" className="link-button" aria-expanded={expanded} aria-controls={`hist-${it.id}`} onClick={() => setOpen(expanded ? undefined : it.id)}>
                            History ({it.history.length})
                          </button>
                          {it.status === 'NEW' && (
                            <button type="button" className="small-button" disabled={action.busy} onClick={() => void accept(it)} aria-label={`Accept ${it.displayName}`}>
                              Accept
                            </button>
                          )}
                          {(it.status === 'ACCEPTED' || it.status === 'CONTACTED') && (
                            <button type="button" className="secondary small-button" onClick={() => { action.reset(); setContact({ channel: 'PHONE', outcome: 'REACHED', note: '' }); setDialog({ item: it, mode: 'contact' }); }} aria-label={`Record contact with ${it.displayName}`}>
                              Record contact
                            </button>
                          )}
                          {it.status !== 'CLOSED' && (
                            <button type="button" className="secondary small-button" onClick={() => { action.reset(); setReason(''); setDialog({ item: it, mode: 'close' }); }} aria-label={`Close ${it.displayName}`}>
                              Close
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                    {expanded && (
                      <tr id={`hist-${it.id}`}>
                        <td colSpan={9}>
                          <EpisodeTable episodes={it.history} caption={`Absence history – ${it.displayName}`} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <Dialog title={dialog.mode === 'contact' ? 'Record contact' : 'Close queue item'} onClose={() => setDialog(undefined)}>
          <p>
            <strong>{dialog.item.displayName}</strong> – {dialog.item.ruleName}
          </p>
          <form className="form" onSubmit={submit}>
            {dialog.mode === 'contact' ? (
              <>
                <Field label="Channel" required>
                  {(id) => (
                    <select id={id} value={contact.channel} onChange={(e) => setContact({ ...contact, channel: e.target.value })}>
                      <SelectOptions values={CONTACT_CHANNELS} />
                    </select>
                  )}
                </Field>
                <fieldset>
                  <legend>Outcome</legend>
                  {CONTACT_OUTCOMES.map((o) => (
                    <label key={o} className="inline-row">
                      <input type="radio" name="contact-outcome" value={o} checked={contact.outcome === o} onChange={() => setContact({ ...contact, outcome: o })} /> {humanise(o)}
                    </label>
                  ))}
                </fieldset>
                <Field label="Note" hint="The first reached contact counts towards the trigger-to-contact KPI.">
                  {(id) => <textarea id={id} rows={3} maxLength={2000} value={contact.note} onChange={(e) => setContact({ ...contact, note: e.target.value })} />}
                </Field>
              </>
            ) : (
              <Field label="Reason" required>
                {(id) => <textarea id={id} rows={2} required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
              </Field>
            )}
            <div className="actions">
              <button type="submit" disabled={action.busy || (dialog.mode === 'close' && !reason.trim())}>
                {dialog.mode === 'contact' ? 'Save contact' : 'Close item'}
              </button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
