import { useState, type FormEvent } from 'react';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';
import { CHECKLISTS, CHECKLIST_OUTCOMES as OUTCOMES, checklistFor, type ChecklistCode, type ChecklistOutcome as Outcome } from '../../domain/checklists';

interface ChecklistRecord {
  code: string;
  required?: boolean;
  answers?: Record<string, unknown>;
  outcome?: Outcome | null;
  completedAt?: string | null;
  completedBy?: string | null;
}

function ChecklistForm({ code, existing, onSave, busy }: { code: ChecklistCode; existing?: ChecklistRecord; onSave: (answers: Record<string, unknown>, outcome: Outcome) => void; busy: boolean }) {
  const def = CHECKLISTS[code];
  const [findings, setFindings] = useState<Record<string, boolean | undefined>>(() => {
    const a = existing?.answers ?? {};
    return Object.fromEntries(def.items.map((i) => [i, typeof a[i] === 'boolean' ? (a[i] as boolean) : undefined]));
  });
  const [outcome, setOutcome] = useState<Outcome | ''>(existing?.outcome ?? '');
  const complete = def.items.every((i) => findings[i] !== undefined) && !!outcome;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!complete || !outcome) return;
    onSave({ ...findings, [def.decision]: outcome }, outcome);
  }

  return (
    <form className="form" onSubmit={submit}>
      <fieldset>
        <legend>{def.title}</legend>
        {def.items.map((i) => (
          <div key={i} role="radiogroup" aria-labelledby={`cl-${code}-${i}`} className="wizard-q">
            <p id={`cl-${code}-${i}`} className="question-text">{humanise(i)} – concern present?</p>
            <label className="radio">
              <input type="radio" name={`cl-${code}-${i}`} checked={findings[i] === true} onChange={() => setFindings({ ...findings, [i]: true })} /> Yes
            </label>
            <label className="radio">
              <input type="radio" name={`cl-${code}-${i}`} checked={findings[i] === false} onChange={() => setFindings({ ...findings, [i]: false })} /> No
            </label>
          </div>
        ))}
        <Field label={humanise(def.decision)} required>
          {(id) => (
            <select id={id} value={outcome} onChange={(e) => setOutcome(e.target.value as Outcome | '')}>
              <option value="">Choose…</option>
              {OUTCOMES.map((o) => (
                <option key={o} value={o}>{humanise(o)}</option>
              ))}
            </select>
          )}
        </Field>
        <button type="submit" disabled={!complete || busy}>
          Save checklist
        </button>
      </fieldset>
    </form>
  );
}

export function ChecklistsTab({ client, caseId, detail, refresh, onSessionExpired }: CaseTabProps) {
  const list = useAsync(() => client.api<ChecklistRecord[]>(casePath(caseId, '/checklists')), onSessionExpired);
  const action = useAction(onSessionExpired);
  const applicable = checklistFor(detail.safetyCategory);
  const codes = [...new Set([...(applicable ? [applicable] : []), ...(list.data ?? []).map((c) => c.code).filter((c): c is ChecklistCode => c in CHECKLISTS)])];

  async function save(code: ChecklistCode, answers: Record<string, unknown>, outcome: Outcome) {
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, `/checklists/${code}`), { method: 'POST', body: { answers, outcome } });
      return `${CHECKLISTS[code].title} checklist saved.`;
    });
    if (ok) {
      list.reload();
      refresh();
    }
  }

  return (
    <div>
      <h2>Safety-critical checklists</h2>
      {detail.safetyCritical ? (
        <Alert kind="info">The checklist for this safety category is mandatory before the case can be closed.</Alert>
      ) : (
        <p className="muted">This employee is not in a safety-critical role.</p>
      )}
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {codes.length === 0 && !list.loading && <p className="muted">No checklist applies to this case.</p>}
      <div className="grid-2">
        {codes.map((code) => {
          const existing = list.data?.find((c) => c.code === code);
          return (
            <div key={code} className="tile">
              {existing?.completedAt && (
                <p className="small">
                  Last completed {formatDate(existing.completedAt)} {existing.completedBy ? `by ${existing.completedBy}` : ''} – outcome{' '}
                  <strong>{humanise(existing.outcome)}</strong>
                </p>
              )}
              <ChecklistForm key={existing?.completedAt ?? 'new'} code={code} existing={existing} busy={action.busy} onSave={(a, o) => void save(code, a, o)} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
