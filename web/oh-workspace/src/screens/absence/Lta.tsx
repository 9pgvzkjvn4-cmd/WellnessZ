import { useState, type FormEvent } from 'react';
import { absenceApi, CONFERENCE_PARTICIPANTS, CONTACT_CHANNELS, CONTACT_OUTCOMES, type Milestone, type Pathway } from '../../api/absence';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise, localToIso } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { daysUntil, isMilestoneOverdue, milestoneTone } from '../../domain/absence';
import type { ScreenProps } from '../types';

const ltaHref = (caseId: string) => `#/absence/lta/${encodeURIComponent(caseId)}`;

function NextMilestone({ m }: { m: Pathway['nextMilestone'] }) {
  if (!m) return <span className="muted">–</span>;
  const overdue = 'status' in m ? isMilestoneOverdue(m) : daysUntil(m.dueDate) < 0;
  return (
    <span>
      Week {m.week}: {humanise(m.type)} <span className="small muted">{formatDay(m.dueDate)}</span>
      {overdue && <> <ToneBadge tone="red">Overdue</ToneBadge></>}
    </span>
  );
}

/** `#/absence/lta` – long-term absence pathways (E06-F04). */
export function LtaList({ client, onSessionExpired }: ScreenProps) {
  const [status, setStatus] = useState('ACTIVE');
  const list = useAsync(() => absenceApi(client).ltaList(status), onSessionExpired, [status]);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Milestones run from the absence start: week 2 contact, 4 / 8 / 12 advice reports, 8 case conference, 12 HR review, 26 ill-health retirement consideration.">
        Long-term absence
      </PageTitle>
      <div className="mode-switch" role="group" aria-label="Pathway status">
        {['ACTIVE', 'CLOSED'].map((s) => (
          <button key={s} type="button" className={`secondary small-button${status === s ? ' mode-active' : ''}`} aria-pressed={status === s} onClick={() => setStatus(s)}>
            {humanise(s)}
          </button>
        ))}
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No pathways.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} pathway{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Employee</th>
                <th scope="col">Unit</th>
                <th scope="col">Absent since</th>
                <th scope="col" className="num">Weeks</th>
                <th scope="col">HR stage</th>
                <th scope="col">Next milestone</th>
                <th scope="col" className="num">Overdue</th>
                <th scope="col">RTW plan</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.caseId} className={p.overdueCount > 0 ? 'row-breached' : undefined}>
                  <th scope="row"><a href={ltaHref(p.caseId)}>{p.caseRef}</a></th>
                  <td>{p.displayName} <span className="small muted">{p.employeeId}</span></td>
                  <td>{p.unit ?? '–'}</td>
                  <td>{formatDay(p.absenceStart)}</td>
                  <td className="num">{p.weeksAbsent}</td>
                  <td>{humanise(p.hrStage)}</td>
                  <td><NextMilestone m={p.nextMilestone} /></td>
                  <td className="num">{p.overdueCount > 0 ? <ToneBadge tone="red">{p.overdueCount}</ToneBadge> : 0}</td>
                  <td>{p.rtwPlanId ? <a href={`#/cases/${encodeURIComponent(p.caseId)}/rtw`}>RTW plan</a> : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

type MilestoneDialog = { m: Milestone; mode: 'complete' | 'skip' };

/** `#/absence/lta/{caseId}` – pathway detail: milestones with overdue flags, contacts, case conference, advice reports, RTW plan. */
export function LtaDetail({ client, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const api = absenceApi(client);
  const detail = useAsync(() => api.ltaDetail(caseId), onSessionExpired, [caseId]);
  const action = useAction(onSessionExpired);
  const [dialog, setDialog] = useState<MilestoneDialog>();
  const [text, setText] = useState('');
  const [contact, setContact] = useState({ channel: 'PHONE', outcome: 'REACHED', note: '', nextContactDue: '' });
  const [conf, setConf] = useState<{ scheduledFor: string; participants: string[]; note: string }>({ scheduledFor: '', participants: ['EMPLOYEE', 'MANAGER', 'HR', 'OH'], note: '' });

  const d = detail.data;

  async function milestone(e: FormEvent) {
    e.preventDefault();
    if (!dialog || (dialog.mode === 'skip' && !text.trim())) return;
    const { m, mode } = dialog;
    const ok = await action.run(async () => {
      if (mode === 'complete') await api.completeMilestone(caseId, m.id, text.trim() || undefined);
      else await api.skipMilestone(caseId, m.id, text.trim());
      return `Week ${m.week} ${humanise(m.type).toLowerCase()} ${mode === 'complete' ? 'completed' : 'skipped'}.`;
    });
    if (ok) {
      setDialog(undefined);
      detail.reload();
    }
  }

  async function addContact(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await api.ltaContact(caseId, {
        channel: contact.channel,
        outcome: contact.outcome,
        ...(contact.note.trim() ? { note: contact.note.trim() } : {}),
        ...(contact.nextContactDue ? { nextContactDue: contact.nextContactDue } : {}),
      });
      return 'Contact recorded.';
    });
    if (ok) {
      setContact({ channel: 'PHONE', outcome: 'REACHED', note: '', nextContactDue: '' });
      detail.reload();
    }
  }

  async function schedule(e: FormEvent) {
    e.preventDefault();
    const at = localToIso(conf.scheduledFor);
    if (!at || conf.participants.length === 0) return;
    const ok = await action.run(async () => {
      await api.conference(caseId, { scheduledFor: at, participants: conf.participants, ...(conf.note.trim() ? { note: conf.note.trim() } : {}) });
      return `Case conference scheduled for ${formatDate(at)}.`;
    });
    if (ok) {
      setConf({ ...conf, scheduledFor: '', note: '' });
      detail.reload();
    }
  }

  return (
    <section>
      <p className="breadcrumb small">
        <a href="#/absence/lta">Long-term absence</a> <span aria-hidden="true">›</span> {d?.caseRef ?? caseId}
      </p>
      <PageTitle sub={d ? `${d.unit ?? ''} · absent since ${formatDay(d.absenceStart)} (${d.weeksAbsent} weeks)` : undefined}>{d ? `${d.displayName} – ${d.caseRef}` : 'Long-term absence pathway'}</PageTitle>
      {detail.loading && !d && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      <ActionFeedback error={dialog ? undefined : action.error} message={action.message} />
      {d && (
        <>
          <div className="toolbar">
            <div className="toolbar-left">
              <a className="button secondary" href={`#/cases/${encodeURIComponent(caseId)}`}>Open case</a>
              <a className="button secondary" href={`#/employees/${encodeURIComponent(d.employeeId)}/chart/absence`}>Absence history</a>
              {d.rtwPlan || d.rtwPlanId ? (
                <a className="button secondary" href={`#/cases/${encodeURIComponent(caseId)}/rtw`}>RTW plan{d.rtwPlan ? ` (${humanise(d.rtwPlan.status).toLowerCase()})` : ''}</a>
              ) : (
                <a className="button secondary" href={`#/cases/${encodeURIComponent(caseId)}/rtw`}>Create RTW plan</a>
              )}
            </div>
          </div>
          <Dl items={[['Status', <StatusBadge status={d.status} />], ['HR stage', humanise(d.hrStage)], ['Overdue milestones', d.overdueCount], ['Next milestone', <NextMilestone m={d.nextMilestone} />]]} />

          <h2>Milestones</h2>
          {d.milestones.length === 0 ? (
            <Empty>No milestones.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Milestones</caption>
                <thead>
                  <tr>
                    <th scope="col" className="num">Week</th>
                    <th scope="col">Milestone</th>
                    <th scope="col">Due</th>
                    <th scope="col">Status</th>
                    <th scope="col">Completed</th>
                    <th scope="col"><span className="visually-hidden">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {[...d.milestones].sort((a, b) => a.week - b.week || a.dueDate.localeCompare(b.dueDate)).map((m) => {
                    const tone = milestoneTone(m);
                    const overdue = isMilestoneOverdue(m);
                    return (
                      <tr key={m.id} className={overdue ? 'row-breached' : undefined}>
                        <td className="num">{m.week}</td>
                        <th scope="row">{humanise(m.type)}</th>
                        <td>{formatDay(m.dueDate)}</td>
                        <td><ToneBadge tone={tone}>{overdue ? 'Overdue' : humanise(m.status)}</ToneBadge></td>
                        <td className="small">{m.completedAt ? `${formatDate(m.completedAt)}${m.completedBy ? ` · ${m.completedBy}` : ''}` : '–'}{m.note && <div className="muted">{m.note}</div>}</td>
                        <td>
                          {(m.status === 'PENDING' || m.status === 'OVERDUE') && (
                            <div className="card-actions">
                              <button type="button" className="small-button" onClick={() => { action.reset(); setText(''); setDialog({ m, mode: 'complete' }); }} aria-label={`Complete week ${m.week} ${humanise(m.type)}`}>
                                Complete
                              </button>
                              <button type="button" className="secondary small-button" onClick={() => { action.reset(); setText(''); setDialog({ m, mode: 'skip' }); }} aria-label={`Skip week ${m.week} ${humanise(m.type)}`}>
                                Skip
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          <div className="grid-2">
            <section aria-labelledby="lta-contacts">
              <h2 id="lta-contacts">Contacts</h2>
              {d.contacts.length === 0 ? (
                <Empty>No contacts yet (fortnightly schedule).</Empty>
              ) : (
                <ul className="plain-list history-list">
                  {[...d.contacts].sort((a, b) => b.at.localeCompare(a.at)).map((c) => (
                    <li key={c.id}>
                      <strong>{formatDate(c.at)}</strong> · {humanise(c.channel)} · <StatusBadge status={c.outcome} /> <span className="small muted">by {c.by}</span>
                      {c.note && <div className="small prewrap">{c.note}</div>}
                      {c.nextContactDue && <div className="small">Next contact due {formatDay(c.nextContactDue)}</div>}
                    </li>
                  ))}
                </ul>
              )}
              <form className="form compact" onSubmit={addContact} aria-label="Record contact">
                <div className="inline-form">
                  <Field label="Channel">
                    {(id) => (
                      <select id={id} value={contact.channel} onChange={(e) => setContact({ ...contact, channel: e.target.value })}>
                        <SelectOptions values={CONTACT_CHANNELS} />
                      </select>
                    )}
                  </Field>
                  <Field label="Outcome">
                    {(id) => (
                      <select id={id} value={contact.outcome} onChange={(e) => setContact({ ...contact, outcome: e.target.value })}>
                        <SelectOptions values={CONTACT_OUTCOMES} />
                      </select>
                    )}
                  </Field>
                  <Field label="Next contact due">{(id) => <input id={id} type="date" value={contact.nextContactDue} onChange={(e) => setContact({ ...contact, nextContactDue: e.target.value })} />}</Field>
                </div>
                <Field label="Contact note">{(id) => <textarea id={id} rows={2} maxLength={2000} value={contact.note} onChange={(e) => setContact({ ...contact, note: e.target.value })} />}</Field>
                <button type="submit" disabled={action.busy}>Record contact</button>
              </form>
            </section>
            <section aria-labelledby="lta-conference">
              <h2 id="lta-conference">Case conference</h2>
              <form className="form compact" onSubmit={schedule}>
                <Field label="Date and time" required>
                  {(id) => <input id={id} type="datetime-local" required value={conf.scheduledFor} onChange={(e) => setConf({ ...conf, scheduledFor: e.target.value })} />}
                </Field>
                <fieldset>
                  <legend>Participants</legend>
                  {CONFERENCE_PARTICIPANTS.map((p) => (
                    <label key={p} className="inline-row">
                      <input
                        type="checkbox"
                        checked={conf.participants.includes(p)}
                        onChange={(e) => setConf({ ...conf, participants: e.target.checked ? [...conf.participants, p] : conf.participants.filter((x) => x !== p) })}
                      />{' '}
                      {p === 'OH' || p === 'HR' ? p : humanise(p)}
                    </label>
                  ))}
                </fieldset>
                <Field label="Agenda note" hint="Visible to OH only. Do not include clinical detail in invitations.">
                  {(id) => <textarea id={id} rows={2} maxLength={1000} value={conf.note} onChange={(e) => setConf({ ...conf, note: e.target.value })} />}
                </Field>
                <button type="submit" disabled={action.busy || !conf.scheduledFor || conf.participants.length === 0}>Schedule conference</button>
              </form>
              <h2>Advice reports</h2>
              {d.adviceReports.length === 0 ? (
                <Empty>No advice reports.</Empty>
              ) : (
                <ul className="plain-list">
                  {d.adviceReports.map((r) => (
                    <li key={r.id}>
                      <StatusBadge status={r.status} /> {r.releasedAt ? <span className="small">released {formatDate(r.releasedAt)}</span> : null} <a href="#/absence/advice" className="small">Advice requests</a>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </>
      )}
      {dialog && (
        <Dialog title={dialog.mode === 'complete' ? 'Complete milestone' : 'Skip milestone'} onClose={() => setDialog(undefined)}>
          <p>
            Week {dialog.m.week}: <strong>{humanise(dialog.m.type)}</strong> (due {formatDay(dialog.m.dueDate)})
          </p>
          <form className="form" onSubmit={milestone}>
            <Field label={dialog.mode === 'complete' ? 'Note' : 'Reason'} required={dialog.mode === 'skip'}>
              {(id) => <textarea id={id} rows={2} maxLength={1000} required={dialog.mode === 'skip'} value={text} onChange={(e) => setText(e.target.value)} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || (dialog.mode === 'skip' && !text.trim())}>{dialog.mode === 'complete' ? 'Complete milestone' : 'Skip milestone'}</button>
              <button type="button" className="secondary" onClick={() => setDialog(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
