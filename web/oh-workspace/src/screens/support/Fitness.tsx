import { useState, type FormEvent } from 'react';
import { FITNESS_DECISIONS, RELIEF_DECISIONS, supportApi, type MhFitnessDecision, type Relief } from '../../api/support';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso, type Tone } from '../../domain/absence';
import { authorityReportForced, emptyFitnessForm, emptyMedication, serverFieldError, validateFitness, type FitnessForm } from '../../domain/support';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { RestrictedBanner } from './common';

const DECISION_TONE: Record<MhFitnessDecision, Tone> = { FIT: 'green', FIT_WITH_LIMITATIONS: 'amber', TEMPORARILY_UNFIT: 'red', REFER_TO_AUTHORITY: 'red' };
const FIELD_MAP = { employeeId: 'fit-employee', standardCode: 'fit-standard', findings: 'fit-findings', decision: 'fit-decision', limitations: 'fit-limitations', reviewDate: 'fit-review' };

/** `#/support/fitness` – AME / OH physician structured mental-health fitness assessment for safety-critical roles (E09-F06). */
export function SupportFitness(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const [employeeId, setEmployeeId] = useState('');
  const [loaded, setLoaded] = useState('');
  const history = useAsync(() => (loaded ? supportApi(client).fitnessAssessments(loaded) : Promise.resolve([])), onSessionExpired, [loaded]);
  const canWrite = can(session.roles, 'supportFitnessWrite');
  const today = todayIso();

  return (
    <section>
      <PageTitle sub="Medications, decision, limitations and review date. Rostering receives the fitness status only; the authority report is a restricted signed document.">Mental-health fitness (safety-critical)</PageTitle>
      <RestrictedBanner />
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setLoaded(employeeId.trim()); }} role="search" aria-label="Employee">
        <Field label="Employee ID">{(id) => <input id={id} value={employeeId} onChange={(e) => setEmployeeId(e.target.value)} />}</Field>
        <button type="submit" className="secondary" disabled={!employeeId.trim()}>Show assessments</button>
      </form>
      {loaded && (
        <section aria-labelledby="fit-history">
          <h2 id="fit-history">Assessment history – {loaded}</h2>
          {history.loading && <Loading />}
          {!!history.error && <ErrorAlert error={history.error} />}
          {history.data && history.data.length === 0 && <Empty>No mental-health fitness assessments.</Empty>}
          {(history.data ?? []).length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th scope="col">Assessed</th><th scope="col">Standard</th><th scope="col">Decision</th><th scope="col">Limitations</th><th scope="col">Medications</th><th scope="col">Review</th><th scope="col">Authority report</th></tr></thead>
                <tbody>
                  {(history.data ?? []).map((a) => (
                    <tr key={a.id}>
                      <td className="small">{formatDate(a.assessedAt)}{a.assessedBy ? ` – ${a.assessedBy}` : ''}</td>
                      <td>{a.standardCode}</td>
                      <td><ToneBadge tone={DECISION_TONE[a.decision] ?? 'muted'}>{humanise(a.decision)}</ToneBadge></td>
                      <td className="small">{a.limitations ?? '–'}</td>
                      <td className="small">{(a.medications ?? []).map((m) => `${m.name} ${m.dose} (${humanise(m.drugClass)})`).join('; ') || 'None'}</td>
                      <td>{a.reviewDate < today ? <ToneBadge tone="red">Overdue {formatDay(a.reviewDate)}</ToneBadge> : formatDay(a.reviewDate)}</td>
                      <td>{a.authorityReportRequired ? <ToneBadge tone="amber">Required</ToneBadge> : 'No'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      {canWrite && <AssessmentForm key={`a-${loaded}`} {...props} employeeId={loaded} onSaved={history.reload} />}
      {can(session.roles, 'supportRelief') && <ReliefTools key={`r-${loaded}`} {...props} employeeId={loaded} />}
    </section>
  );
}

function AssessmentForm({ client, onSessionExpired, employeeId, onSaved }: ScreenProps & { employeeId: string; onSaved: () => void }) {
  const [form, setForm] = useState<FitnessForm>(() => emptyFitnessForm(employeeId));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const set = (patch: Partial<FitnessForm>) => setForm({ ...form, ...patch });
  const forced = authorityReportForced(form.decision);
  const err = (f: string) => errorFor(errors, f);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateFitness(form);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        const a = await supportApi(client).createFitnessAssessment({
          employeeId: form.employeeId.trim(),
          ...(form.supportCaseId.trim() ? { supportCaseId: form.supportCaseId.trim() } : {}),
          standardCode: form.standardCode.trim(),
          medications: form.medications.map((m) => ({ ...m, name: m.name.trim(), drugClass: m.drugClass.trim(), dose: m.dose.trim() })),
          findings: form.findings.trim(),
          decision: form.decision as MhFitnessDecision,
          ...(form.decision === 'FIT_WITH_LIMITATIONS' ? { limitations: form.limitations.trim() } : {}),
          reviewDate: form.reviewDate,
          authorityReportRequired: forced || form.authorityReportRequired,
        });
        return `Assessment saved: ${humanise(a.decision)}, review ${formatDay(a.reviewDate)}.${a.documentId ? ' Authority fitness report generated (restricted).' : ''} Rostering receives the status only.`;
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) {
      setForm(emptyFitnessForm(form.employeeId));
      onSaved();
    } else {
      const fe = serverFieldError(failure, FIELD_MAP);
      if (fe) setErrors([fe]);
    }
  }

  return (
    <section className="card" aria-labelledby="fit-new">
      <h2 id="fit-new">New structured assessment</h2>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="fit-employee" label="Employee ID" required error={err('fit-employee')}>
            {(d) => <input id="fit-employee" aria-describedby={d} aria-invalid={!!err('fit-employee')} value={form.employeeId} onChange={(e) => set({ employeeId: e.target.value })} />}
          </IdField>
          <IdField id="fit-case" label="Support case (optional)">
            {(d) => <input id="fit-case" aria-describedby={d} value={form.supportCaseId} onChange={(e) => set({ supportCaseId: e.target.value })} />}
          </IdField>
          <IdField id="fit-standard" label="Fitness standard" required error={err('fit-standard')} hint="e.g. AME Class 1, cabin crew, airside driving">
            {(d) => <input id="fit-standard" aria-describedby={d} aria-invalid={!!err('fit-standard')} value={form.standardCode} onChange={(e) => set({ standardCode: e.target.value })} />}
          </IdField>
        </div>
        <fieldset>
          <legend>Current medications</legend>
          {form.medications.length === 0 && <p className="small muted">No medications recorded.</p>}
          {form.medications.map((m, i) => {
            const upd = (patch: Partial<typeof m>) => set({ medications: form.medications.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
            return (
              <div key={i} className="form-grid" role="group" aria-label={`Medication ${i + 1}`}>
                <IdField id={`fit-med-${i}-name`} label="Name" required error={err(`fit-med-${i}-name`)}>{(d) => <input id={`fit-med-${i}-name`} aria-describedby={d} value={m.name} onChange={(e) => upd({ name: e.target.value })} />}</IdField>
                <IdField id={`fit-med-${i}-class`} label="Drug class" required error={err(`fit-med-${i}-class`)} hint="e.g. SSRI">{(d) => <input id={`fit-med-${i}-class`} aria-describedby={d} value={m.drugClass} onChange={(e) => upd({ drugClass: e.target.value })} />}</IdField>
                <IdField id={`fit-med-${i}-dose`} label="Dose" required error={err(`fit-med-${i}-dose`)}>{(d) => <input id={`fit-med-${i}-dose`} aria-describedby={d} value={m.dose} onChange={(e) => upd({ dose: e.target.value })} />}</IdField>
                <IdField id={`fit-med-${i}-start`} label="Start date" required error={err(`fit-med-${i}-start`)}>{(d) => <input id={`fit-med-${i}-start`} type="date" aria-describedby={d} value={m.startDate} onChange={(e) => upd({ startDate: e.target.value })} />}</IdField>
                <button type="button" className="link-button" aria-label={`Remove medication ${i + 1}`} onClick={() => set({ medications: form.medications.filter((_, j) => j !== i) })}>Remove</button>
              </div>
            );
          })}
          <button type="button" className="secondary small-button" onClick={() => set({ medications: [...form.medications, emptyMedication()] })}>Add medication</button>
        </fieldset>
        <IdField id="fit-findings" label="Findings" required error={err('fit-findings')} hint="Encrypted; restricted to the assessing physicians and named clinicians.">
          {(d) => <textarea id="fit-findings" rows={4} maxLength={8000} aria-describedby={d} aria-invalid={!!err('fit-findings')} value={form.findings} onChange={(e) => set({ findings: e.target.value })} />}
        </IdField>
        <fieldset id="fit-decision" tabIndex={-1} className={err('fit-decision') ? 'field-error' : undefined} aria-invalid={err('fit-decision') ? true : undefined}>
          <legend>Decision<span aria-hidden="true"> *</span></legend>
          {err('fit-decision') && <span className="field-error-text small">{err('fit-decision')}</span>}
          {FITNESS_DECISIONS.map((d) => (
            <label key={d} className="inline-row">
              <input type="radio" name="fit-decision" value={d} checked={form.decision === d} onChange={() => set({ decision: d, authorityReportRequired: d === 'REFER_TO_AUTHORITY' ? true : form.authorityReportRequired })} /> {humanise(d)}
            </label>
          ))}
        </fieldset>
        {form.decision === 'FIT_WITH_LIMITATIONS' && (
          <IdField id="fit-limitations" label="Limitations" required error={err('fit-limitations')} hint="Operational wording only (e.g. multi-crew only) – shared with rostering as status.">
            {(d) => <textarea id="fit-limitations" rows={2} maxLength={2000} aria-describedby={d} value={form.limitations} onChange={(e) => set({ limitations: e.target.value })} />}
          </IdField>
        )}
        <div className="form-grid">
          <IdField id="fit-review" label="Review date" required error={err('fit-review')}>
            {(d) => <input id="fit-review" type="date" aria-describedby={d} aria-invalid={!!err('fit-review')} value={form.reviewDate} onChange={(e) => set({ reviewDate: e.target.value })} />}
          </IdField>
        </div>
        <label className="inline-row">
          <input type="checkbox" checked={forced || form.authorityReportRequired} disabled={forced} onChange={(e) => set({ authorityReportRequired: e.target.checked })} /> Authority report required (generates the restricted authority fitness report)
        </label>
        <div className="actions"><button type="submit" disabled={action.busy}>Save assessment</button></div>
      </form>
      <ActionFeedback error={errors.length ? undefined : action.error} message={action.message} />
    </section>
  );
}

function ReliefTools({ client, session, onSessionExpired, employeeId }: ScreenProps & { employeeId: string }) {
  const api = supportApi(client);
  const [start, setStart] = useState({ employeeId, from: todayIso(), expectedUntil: '' });
  const [ret, setRet] = useState<{ id: string; decision: (typeof RELIEF_DECISIONS)[number]; until: string }>({ id: '', decision: 'FIT', until: '' });
  const [last, setLast] = useState<Relief>();
  const action = useAction(onSessionExpired);
  const canReturn = can(session.roles, 'supportReliefReturn');

  async function startRelief(e: FormEvent) {
    e.preventDefault();
    if (!start.employeeId.trim() || !start.from) return;
    await action.run(async () => {
      const r = await api.startRelief({ employeeId: start.employeeId.trim(), from: start.from, ...(start.expectedUntil ? { expectedUntil: start.expectedUntil } : {}) });
      setLast(r);
      setRet((x) => ({ ...x, id: r.id }));
      return 'Relief from duties recorded – roster status set (status only).';
    });
  }
  async function returnDecision(e: FormEvent) {
    e.preventDefault();
    if (!ret.id.trim() || (ret.decision === 'EXTEND' && !ret.until)) return;
    await action.run(async () => {
      const r = await api.returnFromRelief(ret.id.trim(), { decision: ret.decision, ...(ret.decision === 'EXTEND' ? { until: ret.until } : {}) });
      setLast(r);
      return ret.decision === 'FIT' ? 'Returned to duties – roster status available.' : `Relief extended until ${formatDay(ret.until)}.`;
    });
  }

  return (
    <section className="card" aria-labelledby="fit-relief">
      <h2 id="fit-relief">Temporary relief from duties</h2>
      <form className="form inline-form" onSubmit={startRelief} aria-label="Relieve from duties">
        <Field label="Employee ID" required>{(id) => <input id={id} value={start.employeeId} onChange={(e) => setStart({ ...start, employeeId: e.target.value })} />}</Field>
        <Field label="From" required>{(id) => <input id={id} type="date" value={start.from} onChange={(e) => setStart({ ...start, from: e.target.value })} />}</Field>
        <Field label="Expected until">{(id) => <input id={id} type="date" min={start.from} value={start.expectedUntil} onChange={(e) => setStart({ ...start, expectedUntil: e.target.value })} />}</Field>
        <button type="submit" className="secondary" disabled={action.busy || !start.employeeId.trim()}>Relieve from duties</button>
      </form>
      {canReturn && (
        <form className="form inline-form" onSubmit={returnDecision} aria-label="Return decision">
          <Field label="Relief reference" required>{(id) => <input id={id} className="mono" value={ret.id} onChange={(e) => setRet({ ...ret, id: e.target.value })} />}</Field>
          <Field label="Decision">
            {(id) => (
              <select id={id} value={ret.decision} onChange={(e) => setRet({ ...ret, decision: e.target.value as typeof ret.decision })}>
                {RELIEF_DECISIONS.map((d) => <option key={d} value={d}>{d === 'FIT' ? 'Fit – return to duties' : 'Extend relief'}</option>)}
              </select>
            )}
          </Field>
          {ret.decision === 'EXTEND' && <Field label="Until" required>{(id) => <input id={id} type="date" value={ret.until} onChange={(e) => setRet({ ...ret, until: e.target.value })} />}</Field>}
          <button type="submit" className="secondary" disabled={action.busy || !ret.id.trim()}>Record return decision</button>
        </form>
      )}
      {last && <Dl items={[['Relief reference', <span className="mono">{last.id}</span>], ['From', formatDay(last.from)], ['Until', formatDay(last.until ?? last.expectedUntil)], ['Status', humanise(last.status)]]} />}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}
