import { useState, type FormEvent } from 'react';
import { supportApi, type PsychosocialAction, type PsychosocialAssessment } from '../../api/support';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import { emptyAssessmentForm, previewAssessment, serverFieldError, validateAssessment, type AssessmentForm } from '../../domain/support';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { RagBadge } from './common';

const RATINGS = ['1', '2', '3', '4', '5'];

/** `#/psychosocial` – psychosocial risk register: per-unit assessment (likelihood × severity per factor), actions, dashboard (E09-F05). */
export function Psychosocial(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const api = supportApi(client);
  const dashboard = useAsync(() => api.psychosocialDashboard(), onSessionExpired);
  const [unit, setUnit] = useState('');
  const [newOpen, setNewOpen] = useState(false);
  const rows = dashboard.data ?? [];

  return (
    <section>
      <PageTitle sub="Organisational risk factors per unit. Support-case rates are anonymised and suppressed below the minimum group size.">Psychosocial risk register</PageTitle>
      <div className="toolbar"><span /><button type="button" onClick={() => setNewOpen(true)}>New assessment</button></div>
      {newOpen && <NewAssessment {...props} onDone={(u) => { setNewOpen(false); dashboard.reload(); if (u) setUnit(u); }} />}
      <section aria-labelledby="psy-dashboard">
        <h2 id="psy-dashboard">Dashboard</h2>
        {dashboard.loading && !dashboard.data && <Loading />}
        {!!dashboard.error && <ErrorAlert error={dashboard.error} />}
        {dashboard.data && rows.length === 0 && <Empty>No units assessed yet.</Empty>}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th scope="col">Unit</th><th scope="col">Overall risk</th><th scope="col">Assessed</th><th scope="col">Review</th><th scope="col" className="num">Open actions</th><th scope="col" className="num">Overdue actions</th><th scope="col" className="num">Support cases / 100 staff</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.unitCode}>
                    <th scope="row"><button type="button" className="link-button" aria-label={`Show assessments for ${r.unitCode}`} onClick={() => setUnit(r.unitCode)}>{r.unitCode}</button></th>
                    <td><RagBadge rag={r.rag} score={r.overallScore} /></td>
                    <td>{formatDay(r.assessedOn)}</td>
                    <td>{r.overdueReview ? <ToneBadge tone="red">Overdue {formatDay(r.reviewDate)}</ToneBadge> : formatDay(r.reviewDate)}</td>
                    <td className="num">{r.openActions ?? 0}</td>
                    <td className="num">{r.overdueActions ? <span className="text-red">{r.overdueActions}</span> : 0}</td>
                    <td className="num">{r.suppressed || r.supportCaseRatePer100 === null || r.supportCaseRatePer100 === undefined ? <span className="muted" title="Suppressed – group below the minimum size">suppressed</span> : r.supportCaseRatePer100.toFixed(1)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <form className="filters inline-form" onSubmit={(e) => e.preventDefault()} aria-label="Unit">
        <Field label="Show assessments for unit">{(id) => <input id={id} value={unit} onChange={(e) => setUnit(e.target.value.toUpperCase())} />}</Field>
      </form>
      {unit && <UnitAssessments key={unit} {...props} unitCode={unit} onChanged={dashboard.reload} />}
    </section>
  );
}

function NewAssessment({ client, onSessionExpired, onDone }: ScreenProps & { onDone: (unit?: string) => void }) {
  const [form, setForm] = useState<AssessmentForm>(() => emptyAssessmentForm());
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const preview = previewAssessment(form);
  const err = (f: string) => errorFor(errors, f);
  const setFactor = (i: number, patch: Partial<AssessmentForm['factors'][number]>) => setForm({ ...form, factors: form.factors.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateAssessment(form);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        await supportApi(client).createAssessment({
          unitCode: form.unitCode.trim().toUpperCase(),
          assessedOn: form.assessedOn,
          reviewDate: form.reviewDate,
          factors: form.factors.map((f) => ({ factor: f.factor, likelihood: Number(f.likelihood), severity: Number(f.severity), controls: f.controls.trim() })),
        });
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) onDone(form.unitCode.trim().toUpperCase());
    else {
      const map: Record<string, string> = { unitCode: 'psy-unit', assessedOn: 'psy-assessed', reviewDate: 'psy-review' };
      form.factors.forEach((_, i) => { map[`factors.${i}.likelihood`] = `psy-${i}-likelihood`; map[`factors.${i}.severity`] = `psy-${i}-severity`; map[`factors.${i}.controls`] = `psy-${i}-controls`; });
      const fe = serverFieldError(failure, map);
      if (fe) setErrors([fe]);
    }
  }

  return (
    <section className="card" aria-labelledby="psy-new">
      <h2 id="psy-new">New unit assessment</h2>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="psy-unit" label="Unit code" required error={err('psy-unit')}>{(d) => <input id="psy-unit" aria-describedby={d} value={form.unitCode} onChange={(e) => setForm({ ...form, unitCode: e.target.value })} />}</IdField>
          <IdField id="psy-assessed" label="Assessed on" required error={err('psy-assessed')}>{(d) => <input id="psy-assessed" type="date" max={todayIso()} aria-describedby={d} value={form.assessedOn} onChange={(e) => setForm({ ...form, assessedOn: e.target.value })} />}</IdField>
          <IdField id="psy-review" label="Review date" required error={err('psy-review')}>{(d) => <input id="psy-review" type="date" aria-describedby={d} value={form.reviewDate} onChange={(e) => setForm({ ...form, reviewDate: e.target.value })} />}</IdField>
        </div>
        <div className="table-wrap">
          <table>
            <caption>Factor scoring – likelihood × severity (1–5 each); 15+ red, 8–14 amber</caption>
            <thead><tr><th scope="col">Factor</th><th scope="col">Likelihood</th><th scope="col">Severity</th><th scope="col">Score</th><th scope="col">Existing controls</th></tr></thead>
            <tbody>
              {form.factors.map((f, i) => (
                <tr key={f.factor}>
                  <th scope="row">{humanise(f.factor)}</th>
                  <td>
                    <select id={`psy-${i}-likelihood`} aria-label={`${humanise(f.factor)} likelihood`} aria-invalid={!!err(`psy-${i}-likelihood`)} value={f.likelihood} onChange={(e) => setFactor(i, { likelihood: e.target.value })}>
                      <option value="">–</option>
                      {RATINGS.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                  </td>
                  <td>
                    <select id={`psy-${i}-severity`} aria-label={`${humanise(f.factor)} severity`} aria-invalid={!!err(`psy-${i}-severity`)} value={f.severity} onChange={(e) => setFactor(i, { severity: e.target.value })}>
                      <option value="">–</option>
                      {RATINGS.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                  </td>
                  <td><RagBadge rag={preview.factors[i]?.rag} score={preview.factors[i]?.score} /></td>
                  <td><input id={`psy-${i}-controls`} aria-label={`${humanise(f.factor)} controls`} value={f.controls} maxLength={1000} onChange={(e) => setFactor(i, { controls: e.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p aria-live="polite">Overall (highest factor): <RagBadge rag={preview.rag} score={preview.overall} /></p>
        <div className="actions">
          <button type="submit" disabled={action.busy}>Save assessment</button>
          <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={errors.length ? undefined : action.error} />
    </section>
  );
}

function UnitAssessments({ client, onSessionExpired, unitCode, onChanged }: ScreenProps & { unitCode: string; onChanged: () => void }) {
  const list = useAsync(() => supportApi(client).assessments(unitCode), onSessionExpired, [unitCode]);
  const sorted = [...(list.data ?? [])].sort((a, b) => b.assessedOn.localeCompare(a.assessedOn));
  return (
    <section aria-labelledby="psy-unit-h">
      <h2 id="psy-unit-h">Assessments – {unitCode}</h2>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && sorted.length === 0 && <Empty>No assessments for {unitCode}.</Empty>}
      {sorted.map((a, i) => <AssessmentCard key={a.id} client={client} onSessionExpired={onSessionExpired} a={a} current={i === 0} onChanged={() => { list.reload(); onChanged(); }} />)}
    </section>
  );
}

function AssessmentCard({ client, onSessionExpired, a, current, onChanged }: { client: ScreenProps['client']; onSessionExpired: () => void; a: PsychosocialAssessment; current: boolean; onChanged: () => void }) {
  const api = supportApi(client);
  const [action_, setAction] = useState({ title: '', owner: '', dueDate: '' });
  const action = useAction(onSessionExpired);
  const today = todayIso();
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!action_.title.trim() || !action_.owner.trim() || !action_.dueDate) return;
    if (await action.run(async () => { await api.addAction(a.id, { ...action_, title: action_.title.trim(), owner: action_.owner.trim(), status: 'OPEN' }); return 'Action added.'; })) {
      setAction({ title: '', owner: '', dueDate: '' });
      onChanged();
    }
  }
  async function toggle(x: PsychosocialAction) {
    const status = x.status === 'DONE' ? 'OPEN' : 'DONE';
    if (await action.run(async () => { await api.updateAction(a.id, x.id, { title: x.title, owner: x.owner, dueDate: x.dueDate, status }); return `${x.title}: ${humanise(status).toLowerCase()}.`; })) onChanged();
  }
  return (
    <article className="card" aria-label={`Assessment ${formatDay(a.assessedOn)}`}>
      <h3>
        {formatDay(a.assessedOn)} {current && <span className="small muted">(current)</span>} <RagBadge rag={a.rag} score={a.overallScore} />
      </h3>
      <p className="small">Review {a.reviewDate < today ? <ToneBadge tone="red">overdue {formatDay(a.reviewDate)}</ToneBadge> : formatDay(a.reviewDate)}{a.assessedBy ? ` · assessed by ${a.assessedBy}` : ''}</p>
      <table>
        <thead><tr><th scope="col">Factor</th><th scope="col" className="num">L</th><th scope="col" className="num">S</th><th scope="col">Score</th><th scope="col">Controls</th></tr></thead>
        <tbody>
          {a.factors.map((f) => (
            <tr key={f.factor}>
              <th scope="row">{humanise(f.factor)}</th>
              <td className="num">{f.likelihood}</td>
              <td className="num">{f.severity}</td>
              <td><RagBadge rag={f.rag} score={f.score ?? f.likelihood * f.severity} /></td>
              <td className="small">{f.controls || '–'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h4>Actions</h4>
      {(a.actions ?? []).length === 0 ? <Empty>No actions.</Empty> : (
        <ul className="plain-list">
          {(a.actions ?? []).map((x) => (
            <li key={x.id}>
              <label className="inline-row">
                <input type="checkbox" checked={x.status === 'DONE'} disabled={action.busy} onChange={() => void toggle(x)} /> {x.title}
              </label>{' '}
              <span className="small muted">{x.owner} · due {formatDay(x.dueDate)}</span>{' '}
              {x.status === 'OPEN' && x.dueDate < today && <ToneBadge tone="red">Overdue</ToneBadge>}
            </li>
          ))}
        </ul>
      )}
      {current && (
        <form className="form inline-form" onSubmit={add} aria-label="Add action">
          <Field label="Action" required>{(id) => <input id={id} value={action_.title} onChange={(e) => setAction({ ...action_, title: e.target.value })} />}</Field>
          <Field label="Owner" required>{(id) => <input id={id} value={action_.owner} onChange={(e) => setAction({ ...action_, owner: e.target.value })} />}</Field>
          <Field label="Due" required>{(id) => <input id={id} type="date" value={action_.dueDate} onChange={(e) => setAction({ ...action_, dueDate: e.target.value })} />}</Field>
          <button type="submit" className="secondary" disabled={action.busy}>Add action</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </article>
  );
}
