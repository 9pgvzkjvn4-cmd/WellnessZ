import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import type { Task, TaskType } from '../../api/types';
import { formatDate, humanise, localToIso } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

const TASK_TYPES: TaskType[] = ['FOLLOW_UP', 'CALL_BACK', 'REMINDER', 'CHECKLIST', 'HANDOVER'];

export function TasksTab({ client, caseId, refresh, onSessionExpired }: CaseTabProps) {
  const tasks = useAsync(() => client.api<Task[]>(casePath(caseId, '/tasks')), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [form, setForm] = useState({ type: 'FOLLOW_UP' as TaskType, title: '', dueAt: '', assignee: '' });

  async function create(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/tasks'), {
        method: 'POST',
        body: { type: form.type, title: form.title.trim(), dueAt: localToIso(form.dueAt) ?? null, ...(form.assignee.trim() ? { assignee: form.assignee.trim() } : {}) },
      });
      return 'Task created.';
    });
    if (ok) {
      setForm({ type: 'FOLLOW_UP', title: '', dueAt: '', assignee: '' });
      tasks.reload();
      refresh();
    }
  }

  async function complete(t: Task) {
    if (await action.run(async () => { await client.api(`/tasks/${seg(t.id)}/complete`, { method: 'POST' }); return `“${t.title}” completed.`; })) {
      tasks.reload();
      refresh();
    }
  }

  const list = tasks.data ?? [];
  return (
    <div className="grid-2 wide-left">
      <div>
        <h2>Tasks</h2>
        {tasks.loading && <Loading />}
        {!!tasks.error && <ErrorAlert error={tasks.error} />}
        {tasks.data && list.length === 0 && <Empty>No tasks.</Empty>}
        <ActionFeedback error={action.error} message={action.message} />
        {list.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Task</th>
                  <th scope="col">Type</th>
                  <th scope="col">Due</th>
                  <th scope="col">Assignee</th>
                  <th scope="col">Status</th>
                  <th scope="col"><span className="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {list.map((t) => (
                  <tr key={t.id}>
                    <td>{t.title}</td>
                    <td>{humanise(t.type)}</td>
                    <td>{formatDate(t.dueAt)}</td>
                    <td>{t.assignee ?? '–'}</td>
                    <td><StatusBadge status={t.status} /></td>
                    <td>
                      {t.status === 'OPEN' && (
                        <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void complete(t)}>
                          Complete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div>
        <h2>New task</h2>
        <form className="form" onSubmit={create}>
          <Field label="Type">
            {(id) => (
              <select id={id} value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as TaskType })}>
                <SelectOptions values={TASK_TYPES} />
              </select>
            )}
          </Field>
          <Field label="Title" required hint="Avoid clinical detail – titles appear in task lists.">
            {(id) => <input id={id} required maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />}
          </Field>
          <Field label="Due">{(id) => <input id={id} type="datetime-local" value={form.dueAt} onChange={(e) => setForm({ ...form, dueAt: e.target.value })} />}</Field>
          <Field label="Assignee (username, optional)">{(id) => <input id={id} maxLength={80} value={form.assignee} onChange={(e) => setForm({ ...form, assignee: e.target.value })} />}</Field>
          <button type="submit" disabled={action.busy || !form.title.trim()}>
            Create task
          </button>
        </form>
      </div>
    </div>
  );
}
