import { useState, type FormEvent } from 'react';
import {
  ASSIGNEE_ROLES,
  canCompleteTask,
  claimMessagesPath,
  completeTask,
  createTask,
  loadClaim,
  loadTasks,
  TASK_TITLE_MAX,
  validateTask,
  type ClaimDetail as Claim,
  type ClaimTask,
  type RtwStatus,
} from '../api/claims';
import { describeError, type FieldErrors } from '../api/errors';
import { TPA_ADJUSTER } from '../auth/roles';
import type { AuthClient } from '../auth/AuthClient';
import { focusFirstError } from '../components/focus';
import { formatDateTime, formatDay, humanise, idPart, localDate } from '../components/format';
import { InputField, SelectField } from '../components/FormFields';
import { MessageThread } from '../components/MessageThread';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { DocumentTable } from './Documents';
import { orgLabel, type ScreenProps } from './types';

const assigneeLabel = (r: string) => S.labels.assigneeRole[r] ?? humanise(r);

/** Hook-free RTW block: fitness status, restriction end, plan status and phase milestones only. */
export function RtwPanel({ rtw }: { rtw?: RtwStatus }) {
  if (!rtw) return <p className="muted">{S.claim.noRtw}</p>;
  const plan = rtw.rtwPlan;
  return (
    <>
      <dl className="details">
        <dt>{S.claim.fitness}</dt>
        <dd>{rtw.fitnessStatus ? <StatusBadge status={rtw.fitnessStatus} /> : S.app.noValue}</dd>
        <dt>{S.claim.restrictionUntil}</dt>
        <dd>{formatDay(rtw.restrictionUntil)}</dd>
        {plan && (
          <>
            <dt>{S.claim.plan}</dt>
            <dd>{plan.status ? <StatusBadge status={plan.status} /> : S.app.noValue}</dd>
            <dt>{S.claim.plannedStart}</dt>
            <dd>{formatDay(plan.plannedStart)}</dd>
            <dt>{S.claim.nextReview}</dt>
            <dd>{formatDay(plan.nextReview)}</dd>
          </>
        )}
      </dl>
      {plan && plan.phases.length > 0 && (
        <>
          <h3>{S.claim.milestones}</h3>
          <ol className="timeline">
            {plan.phases.map((p, i) => (
              <li key={`${p.from ?? ''}-${i}`}>
                {S.claim.phase(formatDay(p.from), formatDay(p.to))}
                {p.hoursPerDay !== undefined && ` · ${S.claim.hoursPerDay(p.hoursPerDay)}`}
                {p.dutiesSummary && <span className="muted small block">{p.dutiesSummary}</span>}
              </li>
            ))}
          </ol>
        </>
      )}
    </>
  );
}

/** Hook-free task list; the adjuster completes open ADJUSTER tasks. */
export function TaskList({ tasks, busy, onComplete }: { tasks: readonly ClaimTask[]; busy?: string; onComplete: (t: ClaimTask) => void }) {
  if (tasks.length === 0) return <p className="muted">{S.claim.noTasks}</p>;
  return (
    <ul className="card-list">
      {tasks.map((t) => (
        <li key={t.id} className="card card-row">
          <div>
            <strong>{t.title}</strong> <StatusBadge status={t.status} />
            <p className="small muted">
              {S.claim.taskDue}: {formatDay(t.dueDate)} · {S.claim.taskAssignee}: {assigneeLabel(t.assigneeRole)}
              {t.completedAt && ` · ${formatDateTime(t.completedAt)}`}
            </p>
          </div>
          {canCompleteTask(t) && (
            <button type="button" className="small-button" disabled={busy === t.id} onClick={() => onComplete(t)} aria-label={`${S.claim.complete}: ${t.title}`}>
              {S.claim.complete}
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}

function Tasks({ client, claimId, initial, onSessionExpired }: { client: Pick<AuthClient, 'api'>; claimId: string; initial: ClaimTask[]; onSessionExpired: () => void }) {
  const [tasks, setTasks] = useState(initial);
  const [busy, setBusy] = useState<string>();
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [f, setF] = useState({ title: '', dueDate: '', assigneeRole: 'CLAIMS' });
  const [errors, setErrors] = useState<FieldErrors>({});
  const p = `claim-${idPart(claimId)}-task`;
  const ids = { title: `${p}-title`, dueDate: `${p}-due`, assigneeRole: `${p}-assignee` };

  async function refresh() {
    try {
      setTasks(await loadTasks(client, claimId));
    } catch {
      /* list stays as it was; the action result is shown */
    }
  }

  async function complete(t: ClaimTask) {
    setBusy(t.id);
    const res = await completeTask(client, t.id, S.claim.failedComplete);
    setBusy(undefined);
    if (!res.ok && res.sessionExpired) onSessionExpired();
    setMessage(res.ok ? { kind: 'success', text: S.claim.completed } : { kind: 'error', text: res.message });
    if (res.ok) await refresh();
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs = validateTask(f);
    setErrors(errs);
    if (Object.keys(errs).length) return focusFirstError(Object.keys(errs).map((k) => ids[k as keyof typeof ids]));
    setBusy('new');
    const res = await createTask(client, claimId, f, S.claim.failedTask);
    setBusy(undefined);
    if (res.ok) {
      setF({ title: '', dueDate: '', assigneeRole: f.assigneeRole });
      setMessage({ kind: 'success', text: S.claim.taskCreated });
      await refresh();
      return;
    }
    if (res.sessionExpired) onSessionExpired();
    setMessage({ kind: 'error', text: res.message });
    setErrors(res.fieldErrors);
    focusFirstError(Object.keys(res.fieldErrors).map((k) => ids[k as keyof typeof ids] ?? ''));
  }

  return (
    <>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      <TaskList tasks={tasks} busy={busy} onComplete={(t) => void complete(t)} />
      <form className="form card" onSubmit={(e) => void create(e)} noValidate aria-labelledby={`${p}-heading`}>
        <h3 id={`${p}-heading`}>{S.claim.newTask}</h3>
        <InputField id={ids.title} label={S.claim.taskTitle} value={f.title} onChange={(v) => setF({ ...f, title: v })} required maxLength={TASK_TITLE_MAX} hint={S.confidentiality.noClinicalText} error={errors.title} />
        <InputField id={ids.dueDate} type="date" label={S.claim.taskDueDate} value={f.dueDate} onChange={(v) => setF({ ...f, dueDate: v })} required min={localDate()} error={errors.dueDate} />
        <SelectField
          id={ids.assigneeRole}
          label={S.claim.taskAssigneeRole}
          value={f.assigneeRole}
          onChange={(v) => setF({ ...f, assigneeRole: v })}
          options={ASSIGNEE_ROLES.map((r) => ({ value: r, label: assigneeLabel(r) }))}
          required
          error={errors.assigneeRole}
        />
        <button type="submit" disabled={busy === 'new'}>
          {S.claim.createTask}
        </button>
      </form>
    </>
  );
}

export function ClaimOverview({ claim }: { claim: Claim }) {
  return (
    <dl className="details">
      <dt>{S.claim.employee}</dt>
      <dd>{claim.displayName}</dd>
      <dt>{S.claim.type}</dt>
      <dd>{humanise(claim.claimType)}</dd>
      <dt>{S.claim.status}</dt>
      <dd>
        <StatusBadge status={claim.status} />
      </dd>
      <dt>{S.claim.incidentDate}</dt>
      <dd>{formatDay(claim.incidentDate)}</dd>
      <dt>{S.claim.lostTime}</dt>
      <dd>{claim.lostTime ? S.claims.yes : S.claims.no}</dd>
    </dl>
  );
}

export function ClaimDetail({ client, me, claimId, onSessionExpired }: ScreenProps & { claimId: string }) {
  const data = useAsync(() => loadClaim(client, claimId), onSessionExpired);
  const c = data.data;
  return (
    <section>
      <p>
        <a href="#/claims">← {S.claim.back}</a>
      </p>
      <PageTitle>{S.claim.title(c?.claimRef ?? '')}</PageTitle>
      <ConfidentialityNote>{S.confidentiality.adjuster(orgLabel(me))}</ConfidentialityNote>
      {data.loading && !c && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {!data.loading && !data.error && !c && <Alert kind="error">{S.errors.notFound}</Alert>}
      {c && (
        <div className="detail-layout">
          <div>
            <h2>{S.claim.overview}</h2>
            <ClaimOverview claim={c} />
            <h2>{S.claim.rtw}</h2>
            <RtwPanel rtw={c.rtw} />
            <h2>{S.claim.documents}</h2>
            {c.documents.length === 0 ? <p className="muted">{S.claim.noDocuments}</p> : <DocumentTable docs={c.documents} caption={S.claim.documents} />}
          </div>
          <div>
            <h2>{S.claim.tasks}</h2>
            <Tasks client={client} claimId={c.id} initial={c.tasks} onSessionExpired={onSessionExpired} />
            <h2>{S.claim.messages}</h2>
            <MessageThread client={client} path={claimMessagesPath(c.id)} idPrefix={`claim-${idPart(c.id)}-msg`} ownRoles={[TPA_ADJUSTER]} hint={S.claim.messagesHint} onSessionExpired={onSessionExpired} />
          </div>
        </div>
      )}
    </section>
  );
}
