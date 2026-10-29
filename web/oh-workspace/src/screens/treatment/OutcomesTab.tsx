import { useState, type FormEvent } from 'react';
import { FUNCTION_SCALES, OUTCOME_MILESTONES, treatmentApi } from '../../api/treatment';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { describeImprovement, emptyOutcomeForm, hasClosureOutcome, normaliseOutcomes, outcomeImprovement, validateOutcome, type OutcomeForm } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { CaseTabProps } from '../case/caseTabProps';

const SCALE_HINT: Record<string, string> = {
  NONE: '',
  ODI: 'Oswestry Disability Index 0–100 – lower is better',
  QUICKDASH: 'QuickDASH 0–100 – lower is better',
  PSFS: 'Patient-Specific Functional Scale (scaled 0–100) – higher is better',
  LEFS: 'Lower Extremity Functional Scale (scaled 0–100) – higher is better',
};

/** `#/cases/{id}/outcomes` – BASELINE / REVIEW / CLOSURE outcomes and improvement (E04-F06). */
export function OutcomesTab({ client, session, onSessionExpired, caseId }: CaseTabProps) {
  const api = treatmentApi(client);
  const data = useAsync(() => api.outcomes(caseId), onSessionExpired);
  const outcomes = normaliseOutcomes(data.data);
  const canWrite = can(session.roles, 'outcomeWrite');
  const [form, setForm] = useState<OutcomeForm>(() => emptyOutcomeForm());
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const improvement = describeImprovement(outcomeImprovement(outcomes));
  const err = (k: string) => errorFor(errors, k);
  const lastScale = outcomes[outcomes.length - 1]?.functionScale;

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateOutcome(form);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.recordOutcome(caseId, {
        milestone: form.milestone,
        painScore: Number(form.painScore),
        functionScale: form.functionScale,
        ...(form.functionScale !== 'NONE' ? { functionScore: Number(form.functionScore) } : {}),
        ...(form.rtwDate ? { rtwDate: form.rtwDate } : {}),
        ...(form.residualImpairmentPct.trim() ? { residualImpairmentPct: Number(form.residualImpairmentPct) } : {}),
        ...(form.note.trim() ? { note: form.note.trim() } : {}),
      });
      return `${humanise(form.milestone)} outcome recorded.`;
    });
    if (ok) {
      setForm({ ...emptyOutcomeForm(form.milestone === 'BASELINE' ? 'REVIEW' : form.milestone), functionScale: form.functionScale });
      data.reload();
    }
  }

  return (
    <div>
      <h2 className="visually-hidden">Outcomes</h2>
      {!hasClosureOutcome(outcomes) && (
        <Alert kind="info">A case with a treatment plan or a referral can only be closed after a <strong>closure</strong> outcome is recorded.</Alert>
      )}
      {data.loading && !data.data && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {improvement.length > 0 && (
        <div className="tiles" role="group" aria-label="Improvement since baseline">
          {improvement.map((t) => (
            <div key={t} className="tile"><span className="kpi-label">{t}</span></div>
          ))}
        </div>
      )}
      {data.data && outcomes.length === 0 && <Empty>No outcomes recorded. Record a baseline at the start of treatment.</Empty>}
      {outcomes.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Outcome measures</caption>
            <thead>
              <tr><th scope="col">Milestone</th><th scope="col">Recorded</th><th scope="col" className="num">Pain (NPRS 0–10)</th><th scope="col">Function</th><th scope="col">RTW date</th><th scope="col" className="num">Residual impairment</th><th scope="col">Note</th></tr>
            </thead>
            <tbody>
              {outcomes.map((o, i) => (
                <tr key={o.id ?? i}>
                  <th scope="row"><ToneBadge tone={o.milestone === 'CLOSURE' ? 'green' : o.milestone === 'BASELINE' ? 'muted' : 'amber'}>{humanise(o.milestone)}</ToneBadge></th>
                  <td className="small">{formatDate(o.recordedAt)}{o.recordedBy ? ` · ${o.recordedBy}` : ''}</td>
                  <td className="num">{o.painScore}</td>
                  <td>{o.functionScale === 'NONE' ? '–' : `${o.functionScale} ${o.functionScore ?? '–'}`}</td>
                  <td>{formatDay(o.rtwDate)}</td>
                  <td className="num">{o.residualImpairmentPct !== undefined ? `${o.residualImpairmentPct} %` : '–'}</td>
                  <td>{o.note ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canWrite && (
        <section className="tile" aria-labelledby="oc-form">
          <h3 id="oc-form">Record outcome</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <fieldset>
              <legend>Milestone</legend>
              {OUTCOME_MILESTONES.map((m) => (
                <label key={m} className="inline-row"><input type="radio" name="oc-milestone" checked={form.milestone === m} onChange={() => setForm({ ...form, milestone: m })} /> {humanise(m)}</label>
              ))}
            </fieldset>
            <div className="inline-form">
              <IdField id="oc-pain" label="Pain (NPRS 0–10)" required error={err('oc-pain')}>
                {(db) => <input id="oc-pain" type="number" min={0} max={10} step={1} aria-describedby={db} aria-invalid={!!err('oc-pain')} value={form.painScore} onChange={(e) => setForm({ ...form, painScore: e.target.value })} />}
              </IdField>
              <IdField id="oc-scale" label="Function scale" hint={lastScale && lastScale !== form.functionScale && lastScale !== 'NONE' ? `Previous outcome used ${lastScale} – use the same scale to show improvement` : SCALE_HINT[form.functionScale]}>
                {(db) => (
                  <select id="oc-scale" aria-describedby={db} value={form.functionScale} onChange={(e) => setForm({ ...form, functionScale: e.target.value as OutcomeForm['functionScale'] })}>
                    <SelectOptions values={FUNCTION_SCALES} />
                  </select>
                )}
              </IdField>
              {form.functionScale !== 'NONE' && (
                <IdField id="oc-function" label="Function score (0–100)" required error={err('oc-function')}>
                  {(db) => <input id="oc-function" type="number" min={0} max={100} aria-describedby={db} aria-invalid={!!err('oc-function')} value={form.functionScore} onChange={(e) => setForm({ ...form, functionScore: e.target.value })} />}
                </IdField>
              )}
              <IdField id="oc-rtw" label="Return-to-work date" error={err('oc-rtw')}>
                {(db) => <input id="oc-rtw" type="date" aria-describedby={db} value={form.rtwDate} onChange={(e) => setForm({ ...form, rtwDate: e.target.value })} />}
              </IdField>
              <IdField id="oc-impairment" label="Residual impairment (%)" error={err('oc-impairment')}>
                {(db) => <input id="oc-impairment" type="number" min={0} max={100} aria-describedby={db} aria-invalid={!!err('oc-impairment')} value={form.residualImpairmentPct} onChange={(e) => setForm({ ...form, residualImpairmentPct: e.target.value })} />}
              </IdField>
            </div>
            <IdField id="oc-note" label="Note">
              {(db) => <textarea id="oc-note" rows={2} maxLength={2000} aria-describedby={db} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />}
            </IdField>
            <button type="submit" disabled={action.busy}>Record {humanise(form.milestone).toLowerCase()} outcome</button>
          </form>
          <ActionFeedback error={action.error} message={action.message} />
        </section>
      )}
    </div>
  );
}
