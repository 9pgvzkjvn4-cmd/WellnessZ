import { useState, type FormEvent } from 'react';
import { CASE_STATUSES, FITNESS_STATUSES, type CaseStatus, type FitnessStatus } from '../../api/types';
import { ApiError } from '../../auth/AuthClient';
import { can } from '../../auth/roles';
import { formatDay, humanise } from '../../components/format';
import { ActionFeedback, Alert, Field, SelectOptions } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

/** Hints for known business-rule rejections (the server `detail` is always shown as well). */
function transitionHint(error: unknown, caseId: string) {
  if (!(error instanceof ApiError) || (error.status !== 409 && error.status !== 422)) return null;
  const text = `${error.problem?.code ?? ''} ${error.problem?.detail ?? ''}`.toLowerCase();
  if (text.includes('recordab'))
    return (
      <p className="small">
        → <a href={`#/cases/${encodeURIComponent(caseId)}/recordability`}>Classify recordability</a> first.
      </p>
    );
  if (text.includes('fitness'))
    return (
      <p className="small">
        → <a href={`#/cases/${encodeURIComponent(caseId)}/fitness`}>Record a fitness status</a> first.
      </p>
    );
  if (text.includes('checklist'))
    return (
      <p className="small">
        → <a href={`#/cases/${encodeURIComponent(caseId)}/checklists`}>Complete the safety checklist</a> first.
      </p>
    );
  return null;
}

export function StatusTab({ client, caseId, detail, refresh, onSessionExpired }: CaseTabProps) {
  const action = useAction(onSessionExpired);
  const [status, setStatus] = useState<CaseStatus | ''>('');
  const options = CASE_STATUSES.filter((s) => s !== 'MERGED' && s !== detail.status);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!status) return;
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/transition'), { method: 'POST', body: { status } });
      return `Status changed to ${humanise(status)}.`;
    });
    if (ok) {
      setStatus('');
      refresh();
    }
  }

  return (
    <div>
      <h2>Status transition</h2>
      <p>
        Current status: <strong>{humanise(detail.status)}</strong>
      </p>
      <ul className="small muted">
        <li>Leaving INTAKE requires recordability to be classified for incident-derived injury, exposure and illness cases.</li>
        <li>Closing a safety-critical case requires a recorded fitness status (and the safety checklist).</li>
      </ul>
      {detail.status === 'MERGED' || detail.status === 'CLOSED' ? (
        <Alert kind="info">This case is {humanise(detail.status).toLowerCase()}.</Alert>
      ) : (
        <form className="form inline-form" onSubmit={submit}>
          <Field label="Move to">
            {(id) => (
              <select id={id} value={status} onChange={(e) => setStatus(e.target.value as CaseStatus | '')}>
                <SelectOptions values={options} empty="Choose status…" />
              </select>
            )}
          </Field>
          <button type="submit" disabled={!status || action.busy}>
            Change status
          </button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {transitionHint(action.error, caseId)}
    </div>
  );
}

export function FitnessTab({ client, caseId, detail, session, refresh, onSessionExpired }: CaseTabProps) {
  const canWrite = can(session.roles, 'fitnessWrite');
  const action = useAction(onSessionExpired);
  const [fitness, setFitness] = useState<FitnessStatus | ''>('');
  const [until, setUntil] = useState('');
  const needsUntil = fitness === 'FIT_WITH_RESTRICTIONS' || fitness === 'TEMPORARILY_UNFIT';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!fitness) return;
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/fitness'), { method: 'POST', body: { fitnessStatus: fitness, ...(until ? { restrictionUntil: until } : {}) } });
      return 'Fitness status recorded. Status-only feeds (crew scheduling, hygiene, HR) are updated.';
    });
    if (ok) {
      setFitness('');
      setUntil('');
      refresh();
    }
  }

  return (
    <div>
      <h2>Fitness status</h2>
      <p>
        Current: <strong>{humanise(detail.fitnessStatus ?? 'NOT_ASSESSED')}</strong>
        {detail.restrictionUntil && <> until {formatDay(detail.restrictionUntil)}</>}
      </p>
      {detail.safetyCritical && <Alert kind="warning">Safety-critical role: a fitness decision is required before this case can close.</Alert>}
      {!canWrite && <Alert kind="info">Recording fitness requires a nurse, physician, AME, CMO or psychological clinician role.</Alert>}
      <form className="form" onSubmit={submit}>
        <fieldset disabled={!canWrite}>
          <legend className="visually-hidden">Record fitness</legend>
          <Field label="Fitness status" required>
            {(id) => (
              <select id={id} value={fitness} onChange={(e) => setFitness(e.target.value as FitnessStatus | '')}>
                <SelectOptions values={FITNESS_STATUSES} empty="Choose…" />
              </select>
            )}
          </Field>
          <Field label="Restriction / unfit until" hint={needsUntil ? 'Recommended for restricted or temporarily unfit.' : undefined}>
            {(id) => <input id={id} type="date" value={until} onChange={(e) => setUntil(e.target.value)} />}
          </Field>
          <button type="submit" disabled={!fitness || action.busy}>
            Record fitness
          </button>
        </fieldset>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}
