import { useState, type FormEvent } from 'react';
import { buildQuery, seg } from '../../api/links';
import { ESCALATION_LEVELS, ESCALATION_REASONS, type Conference, type Escalation, type EscalationLevel } from '../../api/types';
import { can } from '../../auth/roles';
import { formatDate, humanise, localToIso } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { EscalationCard } from '../EscalationCard';
import { casePath, type CaseTabProps } from './caseTabProps';

function MinutesForm({ conf, onSave, busy }: { conf: Conference; onSave: (body: { minutes: string; decisions: string; visibility: string }) => void; busy: boolean }) {
  const [minutes, setMinutes] = useState(conf.minutes ?? '');
  const [decisions, setDecisions] = useState(conf.decisions ?? '');
  const [visibility, setVisibility] = useState<string>(conf.visibility ?? 'CLINICAL_ONLY');
  return (
    <form
      className="form"
      onSubmit={(e) => {
        e.preventDefault();
        onSave({ minutes: minutes.trim(), decisions: decisions.trim(), visibility });
      }}
    >
      <Field label="Minutes" required>{(id) => <textarea id={id} rows={3} required maxLength={4000} value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>
      <Field label="Decisions">{(id) => <textarea id={id} rows={2} maxLength={2000} value={decisions} onChange={(e) => setDecisions(e.target.value)} />}</Field>
      <Field label="Visibility">
        {(id) => (
          <select id={id} value={visibility} onChange={(e) => setVisibility(e.target.value)}>
            <SelectOptions values={['CLINICAL_ONLY', 'ALL_ATTENDEES']} />
          </select>
        )}
      </Field>
      <button type="submit" className="secondary" disabled={busy || !minutes.trim()}>
        Save minutes
      </button>
    </form>
  );
}

export function EscalationTab({ client, caseId, session, refresh, onSessionExpired }: CaseTabProps) {
  const escalations = useAsync(() => client.api<Escalation[]>(`/escalations${buildQuery({ caseId })}`), onSessionExpired);
  const conferences = useAsync(() => client.api<Conference[]>(casePath(caseId, '/conferences')), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [level, setLevel] = useState<EscalationLevel>('PHYSICIAN');
  const [reasonCode, setReasonCode] = useState<string>('COMPLEX_CLINICAL');
  const [note, setNote] = useState('');
  const [conf, setConf] = useState({ scheduledAt: '', agenda: '', attendees: [{ name: '', role: '' }] });
  const canSchedule = can(session.roles, 'conferenceSchedule');

  async function escalate(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/escalations'), { method: 'POST', body: { level, reasonCode, note: note.trim() } });
      return level === 'EMERGENCY_SERVICES' ? 'Escalated to emergency services – act immediately.' : `Escalated to ${humanise(level)}.`;
    });
    if (ok) {
      setNote('');
      escalations.reload();
      refresh();
    }
  }

  async function schedule(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/conferences'), {
        method: 'POST',
        body: { scheduledAt: localToIso(conf.scheduledAt), agenda: conf.agenda.trim(), attendees: conf.attendees.filter((a) => a.name.trim()).map((a) => ({ name: a.name.trim(), role: a.role.trim() })) },
      });
      return 'Case conference scheduled.';
    });
    if (ok) {
      setConf({ scheduledAt: '', agenda: '', attendees: [{ name: '', role: '' }] });
      conferences.reload();
    }
  }

  async function saveMinutes(c: Conference, body: { minutes: string; decisions: string; visibility: string }) {
    const ok = await action.run(async () => {
      await client.api(`/conferences/${seg(c.id)}/minutes`, { method: 'POST', body });
      return 'Minutes saved.';
    });
    if (ok) conferences.reload();
  }

  return (
    <div className="grid-2">
      <div>
        <h2>Escalate</h2>
        <form className="form" onSubmit={escalate}>
          <Field label="Escalate to">
            {(id) => (
              <select id={id} value={level} onChange={(e) => setLevel(e.target.value as EscalationLevel)}>
                <SelectOptions values={ESCALATION_LEVELS} />
              </select>
            )}
          </Field>
          <Field label="Reason">
            {(id) => (
              <select id={id} value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
                <SelectOptions values={ESCALATION_REASONS} />
              </select>
            )}
          </Field>
          <Field label="Note" required>{(id) => <textarea id={id} rows={3} required maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
          <p className="small muted">Response SLA: physician 4 h, CMO 1 working day, emergency services immediate.</p>
          <button type="submit" disabled={action.busy || !note.trim()}>
            Escalate
          </button>
        </form>
        <ActionFeedback error={action.error} message={action.message} />
        <h2>Escalations on this case</h2>
        {escalations.loading && <Loading />}
        {!!escalations.error && <ErrorAlert error={escalations.error} />}
        {escalations.data && escalations.data.length === 0 && <Empty>No escalations.</Empty>}
        <ul className="card-list">
          {(escalations.data ?? []).map((e) => (
            <li key={e.id}>
              <EscalationCard client={client} session={session} onSessionExpired={onSessionExpired} escalation={e} onChanged={() => { escalations.reload(); refresh(); }} />
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h2>Case conferences</h2>
        {!!conferences.error && <ErrorAlert error={conferences.error} />}
        {conferences.data && conferences.data.length === 0 && <Empty>No conferences scheduled.</Empty>}
        <ul className="card-list">
          {(conferences.data ?? []).map((c) => (
            <li key={c.id} className="card">
              <p>
                <strong>{formatDate(c.scheduledAt)}</strong> – {c.agenda}
              </p>
              <p className="small muted">{c.attendees.map((a) => `${a.name} (${a.role})`).join(', ')}</p>
              {c.minutes && <p className="small prewrap">Minutes: {c.minutes}</p>}
              <details>
                <summary>{c.minutes ? 'Edit minutes' : 'Record minutes'}</summary>
                <MinutesForm conf={c} busy={action.busy} onSave={(b) => void saveMinutes(c, b)} />
              </details>
            </li>
          ))}
        </ul>
        {canSchedule && (
          <>
            <h3>Schedule a conference (CMO)</h3>
            <form className="form" onSubmit={schedule}>
              <Field label="Date and time" required>{(id) => <input id={id} type="datetime-local" required value={conf.scheduledAt} onChange={(e) => setConf({ ...conf, scheduledAt: e.target.value })} />}</Field>
              <Field label="Agenda" required>{(id) => <textarea id={id} rows={2} required maxLength={2000} value={conf.agenda} onChange={(e) => setConf({ ...conf, agenda: e.target.value })} />}</Field>
              <fieldset>
                <legend>Attendees</legend>
                {conf.attendees.map((a, i) => (
                  <div key={i} className="inline-row">
                    <input aria-label={`Attendee ${i + 1} name`} placeholder="Name" value={a.name} onChange={(e) => setConf({ ...conf, attendees: conf.attendees.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })} />
                    <input aria-label={`Attendee ${i + 1} role`} placeholder="Role" value={a.role} onChange={(e) => setConf({ ...conf, attendees: conf.attendees.map((x, j) => (j === i ? { ...x, role: e.target.value } : x)) })} />
                  </div>
                ))}
                <button type="button" className="link-button" onClick={() => setConf({ ...conf, attendees: [...conf.attendees, { name: '', role: '' }] })}>
                  Add attendee
                </button>
              </fieldset>
              <button type="submit" disabled={action.busy || !conf.scheduledAt || !conf.agenda.trim()}>
                Schedule
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
