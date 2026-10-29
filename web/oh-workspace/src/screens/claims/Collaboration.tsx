import { useState, type FormEvent } from 'react';
import { claimsApi, TASK_ASSIGNEE_ROLES, type ClaimTask } from '../../api/claims';
import { IdField } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { clinicalContentField, daysUntil, isClinicalContentError, todayIso } from '../../domain/absence';
import type { ScreenProps } from '../types';
import { MessageThread } from '../treatment/common';

const ASSIGNEE_LABEL: Record<string, string> = { CLAIMS: 'Claims coordinator', NURSE_CM: 'Nurse case manager', ADJUSTER: 'Adjuster' };

function taskTone(t: ClaimTask): 'red' | 'amber' | 'green' | 'muted' {
  if (t.status === 'DONE') return 'green';
  const d = daysUntil(t.dueDate, todayIso());
  return d < 0 ? 'red' : d <= 2 ? 'amber' : 'muted';
}

/**
 * Nurse case manager ↔ claims ↔ adjuster collaboration (E08-F04): shared tasks and secure messages. Text is screened
 * for clinical content (the adjuster reads it) – a 422 is shown on the field.
 */
export function ClaimCollaboration({ props, claimId }: { props: ScreenProps; claimId: string }) {
  const { client, session, onSessionExpired } = props;
  const api = claimsApi(client);
  const tasks = useAsync(() => api.tasks(claimId), onSessionExpired, [claimId]);
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({ title: '', dueDate: '', assigneeRole: 'ADJUSTER' });
  const [titleError, setTitleError] = useState<string>();

  async function addTask(e: FormEvent) {
    e.preventDefault();
    if (!f.title.trim() || !f.dueDate) {
      setTitleError(!f.title.trim() ? 'Enter a task title' : undefined);
      return;
    }
    setTitleError(undefined);
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await api.createTask(claimId, { title: f.title.trim(), dueDate: f.dueDate, assigneeRole: f.assigneeRole });
        return `Task added for the ${ASSIGNEE_LABEL[f.assigneeRole]?.toLowerCase()}.`;
      } catch (ex) {
        failed = ex;
        throw ex;
      }
    });
    if (ok) {
      setF({ ...f, title: '', dueDate: '' });
      tasks.reload();
    } else if (isClinicalContentError(failed)) {
      setTitleError(`${humanise(clinicalContentField(failed) ?? 'title')}: remove clinical details – the adjuster can read tasks`);
      action.reset();
    }
  }

  const rows = tasks.data ?? [];
  return (
    <div className="grid-2">
      <section aria-labelledby="claim-tasks">
        <h3 id="claim-tasks">Shared tasks</h3>
        {tasks.loading && !tasks.data && <Loading />}
        {!!tasks.error && <ErrorAlert error={tasks.error} />}
        {tasks.data && rows.length === 0 && <Empty>No tasks.</Empty>}
        {rows.length > 0 && (
          <ul className="plain-list">
            {rows.map((t) => (
              <li key={t.id}>
                <ToneBadge tone={taskTone(t)}>{t.status === 'DONE' ? 'Done' : `Due ${formatDay(t.dueDate)}`}</ToneBadge> {t.title}{' '}
                <span className="small muted">{ASSIGNEE_LABEL[t.assigneeRole] ?? humanise(t.assigneeRole)} · by {t.createdBy ?? '–'}{t.completedAt ? ` · done ${formatDate(t.completedAt)}` : ''}</span>{' '}
                {t.status === 'OPEN' && (
                  <button type="button" className="secondary small-button" disabled={action.busy} aria-label={`Complete task ${t.title}`} onClick={() => void action.run(async () => { await api.completeTask(t.id); tasks.reload(); return 'Task completed.'; })}>
                    Complete
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
        <form className="form" onSubmit={addTask} noValidate aria-label="Add task">
          <IdField id={`ct-title-${claimId}`} label="Task" required error={titleError} hint="Shared with the adjuster – work and claim administration only.">
            {(db) => <input id={`ct-title-${claimId}`} maxLength={200} aria-describedby={db} aria-invalid={!!titleError} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />}
          </IdField>
          <div className="inline-form">
            <Field label="Due" required>{(id) => <input id={id} type="date" value={f.dueDate} onChange={(e) => setF({ ...f, dueDate: e.target.value })} />}</Field>
            <Field label="Assignee">
              {(id) => (
                <select id={id} value={f.assigneeRole} onChange={(e) => setF({ ...f, assigneeRole: e.target.value })}>
                  <SelectOptions values={TASK_ASSIGNEE_ROLES} />
                </select>
              )}
            </Field>
            <button type="submit" disabled={action.busy}>Add task</button>
          </div>
        </form>
        <ActionFeedback error={action.error} message={action.message} />
      </section>
      <section aria-labelledby="claim-messages">
        <h3 id="claim-messages">Secure messages</h3>
        <MessageThread
          label="Claim messages"
          load={() => api.messages(claimId)}
          send={(text) => api.sendMessage(claimId, text)}
          onSessionExpired={onSessionExpired}
          me={session.username}
          hint="Encrypted and screened for clinical content – the adjuster reads these messages. Notifications are content-free."
        />
      </section>
    </div>
  );
}
