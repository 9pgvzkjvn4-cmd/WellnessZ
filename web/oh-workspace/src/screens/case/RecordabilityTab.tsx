import { useState, type FormEvent } from 'react';
import { RECORDABILITY, WORK_RELATED, type Recordability, type RecordabilityResult, type RecordabilityWizard, type WorkRelated } from '../../api/types';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

type WizardAnswers = Record<string, boolean | number | undefined>;

export function RecordabilityTab({ client, caseId, detail, session, refresh, onSessionExpired }: CaseTabProps) {
  const canWrite = can(session.roles, 'recordabilityWrite');
  const wizard = useAsync(() => client.api<RecordabilityWizard>('/intake/recordability/wizard'), onSessionExpired);
  const history = useAsync(() => client.api<RecordabilityResult[]>(casePath(caseId, '/recordability/history')), onSessionExpired);
  const [answers, setAnswers] = useState<WizardAnswers>({});
  const [workRelated, setWorkRelated] = useState<WorkRelated>(detail.workRelated ?? 'UNDER_REVIEW');
  const [override, setOverride] = useState<Recordability | ''>('');
  const [reasonCode, setReasonCode] = useState('');
  const [rationale, setRationale] = useState('');
  const [result, setResult] = useState<RecordabilityResult>();
  const action = useAction(onSessionExpired);

  const alreadyClassified = !!detail.recordability && detail.recordability !== 'NOT_CLASSIFIED';
  const reasonRequired = alreadyClassified || !!override;
  const questions = wizard.data?.questions ?? [];
  const complete = questions.every((q) => answers[q.key] !== undefined);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = {
      answers,
      workRelated,
      ...(override ? { override } : {}),
      ...(reasonCode ? { reasonCode } : {}),
      rationale: rationale.trim(),
    };
    await action.run(async () => {
      const r = await client.api<RecordabilityResult>(casePath(caseId, '/recordability'), { method: 'POST', body });
      setResult(r);
      history.reload();
      refresh();
      return 'Recordability classified.';
    });
  }

  return (
    <div className="grid-2 wide-left">
      <div>
        <h2>Recordability wizard</h2>
        <p className="small muted">
          Current classification: <strong>{humanise(detail.recordability ?? 'NOT_CLASSIFIED')}</strong>. Leaving INTAKE requires a classification for
          incident-derived injury, illness and exposure cases.
        </p>
        {wizard.loading && <Loading />}
        {!!wizard.error && <ErrorAlert error={wizard.error} />}
        {wizard.data && (
          <form className="form" onSubmit={submit}>
            <fieldset disabled={!canWrite}>
              <legend>Questions</legend>
              {questions.map((q) =>
                q.type === 'BOOLEAN' ? (
                  <div key={q.key} className="wizard-q" role="radiogroup" aria-labelledby={`rq-${q.key}`}>
                    <p id={`rq-${q.key}`} className="question-text">{q.text}</p>
                    {q.help && <p className="small muted">{q.help}</p>}
                    <label className="radio">
                      <input type="radio" name={`rq-${q.key}`} checked={answers[q.key] === true} onChange={() => setAnswers({ ...answers, [q.key]: true })} /> Yes
                    </label>
                    <label className="radio">
                      <input type="radio" name={`rq-${q.key}`} checked={answers[q.key] === false} onChange={() => setAnswers({ ...answers, [q.key]: false })} /> No
                    </label>
                  </div>
                ) : (
                  <Field key={q.key} label={q.text} hint={q.help}>
                    {(id) => (
                      <input
                        id={id}
                        type="number"
                        min={0}
                        value={typeof answers[q.key] === 'number' ? (answers[q.key] as number) : ''}
                        onChange={(e) => setAnswers({ ...answers, [q.key]: e.target.value === '' ? undefined : e.target.valueAsNumber })}
                      />
                    )}
                  </Field>
                ),
              )}
              <Field label="Work-related">
                {(id) => (
                  <select id={id} value={workRelated} onChange={(e) => setWorkRelated(e.target.value as WorkRelated)}>
                    <SelectOptions values={WORK_RELATED} />
                  </select>
                )}
              </Field>
            </fieldset>
            <fieldset disabled={!canWrite}>
              <legend>Override / change</legend>
              <Field label="Override classification (optional)">
                {(id) => (
                  <select id={id} value={override} onChange={(e) => setOverride(e.target.value as Recordability | '')}>
                    <SelectOptions values={RECORDABILITY.filter((r) => r !== 'NOT_CLASSIFIED')} empty="Use computed result" />
                  </select>
                )}
              </Field>
              <Field label="Reason code" required={reasonRequired} hint={reasonRequired ? 'Required when changing an existing classification or overriding.' : undefined}>
                {(id) => (
                  <select id={id} value={reasonCode} required={reasonRequired} onChange={(e) => setReasonCode(e.target.value)}>
                    <SelectOptions values={wizard.data?.reasonCodes ?? []} empty="Choose…" />
                  </select>
                )}
              </Field>
              <Field label="Rationale">{(id) => <textarea id={id} rows={3} maxLength={2000} value={rationale} onChange={(e) => setRationale(e.target.value)} />}</Field>
              <button type="submit" disabled={!canWrite || !complete || action.busy || (reasonRequired && !reasonCode)}>
                Classify
              </button>
            </fieldset>
          </form>
        )}
        <ActionFeedback error={action.error} message={action.message} />
        {result && (
          <div className="result-panel" aria-live="polite">
            <h3>Result</h3>
            <Dl
              items={[
                ['Recordability', humanise(result.recordability)],
                ['Computed', humanise(result.computed)],
                ['Work-related', humanise(result.workRelated)],
                ['Local category', result.localCategory],
                ['CAA reportable', result.caaReportable === undefined ? undefined : result.caaReportable ? 'Yes – report to the authority' : 'No'],
                ['Version', String(result.version)],
              ]}
            />
            {result.caaReportable && <Alert kind="warning">This classification is reportable to the civil aviation authority.</Alert>}
          </div>
        )}
      </div>
      <div>
        <h2>Definitions</h2>
        {wizard.data && (
          <dl className="definitions">
            {Object.entries(wizard.data.definitions).map(([k, v]) => (
              <div key={k}>
                <dt>{humanise(k)}</dt>
                <dd className="small">{v}</dd>
              </div>
            ))}
          </dl>
        )}
        <h2>History</h2>
        {!!history.error && <ErrorAlert error={history.error} />}
        {history.data && history.data.length === 0 && <Empty>No classifications yet.</Empty>}
        <ol className="history-list">
          {(history.data ?? []).map((h) => (
            <li key={h.version}>
              v{h.version}: <strong>{humanise(h.recordability)}</strong> (computed {humanise(h.computed)}) · work-related {humanise(h.workRelated)}
              <div className="small muted">
                {formatDate(h.assessedAt)} {h.assessedBy ? `· ${h.assessedBy}` : ''} {h.reasonCode ? `· ${humanise(h.reasonCode)}` : ''}
              </div>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
