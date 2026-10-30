import { useState } from 'react';
import {
  canMarkPsychosocialDone,
  FACTOR_LABELS,
  fitnessLabel,
  PSYCHOSOCIAL_PATH,
  psychosocialActionPath,
  RAG_LABELS,
  reviewOverdue,
  SHARED_OUTCOMES_PATH,
  toSharedOutcomes,
  toUnitAssessment,
  type SharedOutcome,
  type UnitAction,
  type UnitAssessment,
} from '../api/unit';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { describeError, formatDate, formatDay, humanise } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { ActionTable } from '../components/UnitActions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface AssessmentViewProps {
  assessment: UnitAssessment;
  now?: Date;
  busyId?: string;
  onMarkDone: (a: UnitAction) => void;
}

/** The unit's current psychosocial risk assessment: factors with RAG, review date and actions. Hook-free. */
export function AssessmentView({ assessment: a, now, busyId, onMarkDone }: AssessmentViewProps) {
  const overdue = reviewOverdue(a, now);
  return (
    <>
      <dl className="details">
        <dt>Unit</dt>
        <dd>{a.unitCode ?? '–'}</dd>
        <dt>Assessed on</dt>
        <dd>{formatDay(a.assessedOn)}</dd>
        <dt>Next review</dt>
        <dd>
          {formatDay(a.reviewDate)} {overdue && <StatusBadge status="action-OVERDUE" label="Review overdue" />}
        </dd>
        <dt>Overall</dt>
        <dd>
          {a.rag ? <StatusBadge status={`rag-${a.rag}`} label={RAG_LABELS[a.rag]} /> : '–'}
          {a.overallScore !== undefined && <span className="muted small"> score {a.overallScore}</span>}
        </dd>
      </dl>
      <h3>Risk factors</h3>
      {a.factors.length === 0 ? (
        <p className="muted">No factors recorded.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Psychosocial risk factors of your unit</caption>
            <thead>
              <tr>
                <th scope="col">Factor</th>
                <th scope="col">Likelihood</th>
                <th scope="col">Severity</th>
                <th scope="col">Risk</th>
                <th scope="col">Controls</th>
              </tr>
            </thead>
            <tbody>
              {a.factors.map((f) => (
                <tr key={f.factor}>
                  <th scope="row">{FACTOR_LABELS[f.factor] ?? humanise(f.factor)}</th>
                  <td>{f.likelihood ?? '–'}</td>
                  <td>{f.severity ?? '–'}</td>
                  <td>{f.rag ? <StatusBadge status={`rag-${f.rag}`} label={`${RAG_LABELS[f.rag]}${f.score !== undefined ? ` (${f.score})` : ''}`} /> : '–'}</td>
                  <td>{f.controls ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>Actions</h3>
      <p className="muted small">Actions are maintained by the HSE team. You can mark an action done only where it has been assigned to you for completion.</p>
      <ActionTable caption="Psychosocial actions of your unit" actions={a.actions} now={now} busyId={busyId} canMarkDone={canMarkPsychosocialDone} onMarkDone={onMarkDone} />
    </>
  );
}

/** What employees chose to share: fitness and, where chosen, work adjustments. Hook-free. */
export function SharedOutcomesTable({ rows }: { rows: readonly SharedOutcome[] }) {
  if (rows.length === 0) return <p className="muted">No employee in your unit has chosen to share an outcome with you.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Outcomes shared with you by employees</caption>
        <thead>
          <tr>
            <th scope="col">Employee</th>
            <th scope="col">Fitness for work</th>
            <th scope="col">Work adjustments</th>
            <th scope="col">Shared</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.employeeId}>
              <th scope="row">
                {r.displayName}
                <span className="muted small"> {r.employeeId}</span>
              </th>
              <td>
                <StatusBadge status={`fit-${r.fitness}`} label={fitnessLabel(r.fitness)} />
              </td>
              <td>{r.adjustments ?? <span className="muted">Not shared</span>}</td>
              <td>{formatDate(r.sharedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** `#/psychosocial` (MANAGER) – unit assessment and actions, plus outcomes employees chose to share. */
export function Psychosocial({ client, onSessionExpired }: ScreenProps) {
  const assessment = useAsync(async () => {
    try {
      return toUnitAssessment(await client.api<unknown>(PSYCHOSOCIAL_PATH));
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return undefined;
      throw e;
    }
  }, onSessionExpired);
  const shared = useAsync(async () => toSharedOutcomes(await client.api<unknown>(SHARED_OUTCOMES_PATH)), onSessionExpired);
  const [busyId, setBusyId] = useState<string>();
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function markDone(a: UnitAction) {
    setBusyId(a.id);
    setMessage(undefined);
    try {
      await client.api(psychosocialActionPath(a), { method: 'PUT', body: { status: 'DONE' } });
      setMessage({ kind: 'success', text: `"${a.title}" marked done.` });
      assessment.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: describeError(e) });
    } finally {
      setBusyId(undefined);
    }
  }

  return (
    <section>
      <PageTitle>Psychosocial risk</PageTitle>
      <p className="lead">Your unit&apos;s psychosocial risk assessment (workload, fatigue, aggression, support …) and the agreed actions.</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      <h2>Unit assessment</h2>
      {assessment.loading && <Loading />}
      {!!assessment.error && <Alert kind="error">{describeError(assessment.error)}</Alert>}
      {!assessment.loading && !assessment.error && !assessment.data && <p className="muted">No psychosocial risk assessment has been recorded for your unit yet.</p>}
      {assessment.data && <AssessmentView assessment={assessment.data} busyId={busyId} onMarkDone={(a) => void markDone(a)} />}

      <h2>Shared outcomes</h2>
      <NoClinicalInfoNote>
        Shown only because these employees chose to share it with you – fitness for work and, if they chose, work adjustments.
        Employees can withdraw this at any time. Whether anyone uses confidential support, and anything they discuss, is never
        shown. Opening this list is recorded in the disclosure log.
      </NoClinicalInfoNote>
      {shared.loading && <Loading />}
      {!!shared.error && <Alert kind="error">{describeError(shared.error)}</Alert>}
      {shared.data && <SharedOutcomesTable rows={shared.data} />}
    </section>
  );
}
