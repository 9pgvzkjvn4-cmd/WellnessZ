import { useState, type FormEvent } from 'react';
import { fitnessApi, REVIEW_OUTCOMES, type ReviewOutcome, type RtwPhase, type RtwPlan } from '../../api/fitness';
import { documentContentUrl, platformApi, type RestrictionDef } from '../../api/platform';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { RestrictionPicker, type RestrictionEntry } from '../../components/RestrictionPicker';
import { ActionFeedback, Empty, ErrorAlert, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { restrictionValues } from '../../domain/fitness';
import { validatePlan } from '../../domain/rtw';
import type { CaseTabProps } from './caseTabProps';

interface PhaseForm {
  fromWeek: string;
  toWeek: string;
  hoursPerDay: string;
  duties: string;
  restrictions: RestrictionEntry[];
}
interface PlanForm {
  startDate: string;
  goal: string;
  phases: PhaseForm[];
  reviewDates: string[];
}

const blankPhase = (from = 1): PhaseForm => ({ fromWeek: String(from), toWeek: String(from + 1), hoursPerDay: '4', duties: '', restrictions: [] });
const blankPlan = (): PlanForm => ({ startDate: isoDay(new Date()), goal: '', phases: [blankPhase()], reviewDates: [''] });

const toPhases = (f: PlanForm, cat: readonly RestrictionDef[]): RtwPhase[] =>
  f.phases.map((p) => ({ fromWeek: Number(p.fromWeek), toWeek: Number(p.toWeek), hoursPerDay: Number(p.hoursPerDay), duties: p.duties.trim(), restrictions: restrictionValues(p.restrictions, cat) }));

const fromPlan = (p: RtwPlan): PlanForm => ({
  startDate: p.startDate,
  goal: p.goal,
  phases: p.phases.map((x) => ({ fromWeek: String(x.fromWeek), toWeek: String(x.toWeek), hoursPerDay: String(x.hoursPerDay), duties: x.duties, restrictions: x.restrictions.map((r) => ({ code: r.code, value: r.value == null ? '' : String(r.value) })) })),
  reviewDates: p.reviews.map((r) => r.dueDate),
});

/** Phased RTW plan builder (E05-BL-007). */
function PlanBuilder({ initial, catalogue, busy, onSubmit, onCancel, submitLabel }: { initial: PlanForm; catalogue: RestrictionDef[]; busy: boolean; onSubmit: (f: PlanForm) => void; onCancel?: () => void; submitLabel: string }) {
  const [f, setF] = useState<PlanForm>(initial);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const err = (id: string) => errorFor(errors, id);
  const setPhase = (i: number, patch: Partial<PhaseForm>) => setF({ ...f, phases: f.phases.map((p, j) => (j === i ? { ...p, ...patch } : p)) });

  function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validatePlan(f.startDate, toPhases(f, catalogue), f.reviewDates, f.goal);
    setErrors(errs);
    if (!errs.length) onSubmit(f);
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorSummary errors={errors} />
      <div className="form-grid">
        <IdField id="rtw-start" label="Start date" required error={err('rtw-start')}>
          {(d) => <input id="rtw-start" type="date" value={f.startDate} aria-describedby={d} onChange={(e) => setF({ ...f, startDate: e.target.value })} />}
        </IdField>
      </div>
      <IdField id="rtw-goal" label="Goal (functional wording – shared with employee and manager)" required error={err('rtw-goal')}>
        {(d) => <textarea id="rtw-goal" rows={2} maxLength={1000} value={f.goal} aria-describedby={d} aria-invalid={!!err('rtw-goal')} onChange={(e) => setF({ ...f, goal: e.target.value })} />}
      </IdField>
      {f.phases.map((p, i) => (
        <fieldset key={i} className="phase">
          <legend>Phase {i + 1}</legend>
          <div className="form-grid">
            <IdField id={`rtw-phase-${i}-from`} label="From week" required error={err(`rtw-phase-${i}-from`)}>
              {(d) => <input id={`rtw-phase-${i}-from`} type="number" min={1} value={p.fromWeek} aria-describedby={d} aria-invalid={!!err(`rtw-phase-${i}-from`)} onChange={(e) => setPhase(i, { fromWeek: e.target.value })} />}
            </IdField>
            <IdField id={`rtw-phase-${i}-to`} label="To week" required>
              {() => <input id={`rtw-phase-${i}-to`} type="number" min={1} value={p.toWeek} onChange={(e) => setPhase(i, { toWeek: e.target.value })} />}
            </IdField>
            <IdField id={`rtw-phase-${i}-hours`} label="Hours per day" required error={err(`rtw-phase-${i}-hours`)}>
              {(d) => <input id={`rtw-phase-${i}-hours`} type="number" min={1} max={24} step="0.5" value={p.hoursPerDay} aria-describedby={d} aria-invalid={!!err(`rtw-phase-${i}-hours`)} onChange={(e) => setPhase(i, { hoursPerDay: e.target.value })} />}
            </IdField>
          </div>
          <IdField id={`rtw-phase-${i}-duties`} label="Duties" required error={err(`rtw-phase-${i}-duties`)}>
            {(d) => <textarea id={`rtw-phase-${i}-duties`} rows={2} maxLength={1000} value={p.duties} aria-describedby={d} aria-invalid={!!err(`rtw-phase-${i}-duties`)} onChange={(e) => setPhase(i, { duties: e.target.value })} />}
          </IdField>
          <RestrictionPicker catalogue={catalogue} value={p.restrictions} onChange={(r) => setPhase(i, { restrictions: r })} idPrefix={`rtw-phase-${i}-restriction`} label={`Phase ${i + 1} restrictions`} />
          {f.phases.length > 1 && (
            <button type="button" className="link-button" onClick={() => setF({ ...f, phases: f.phases.filter((_, j) => j !== i) })}>
              Remove phase {i + 1}
            </button>
          )}
        </fieldset>
      ))}
      <button type="button" className="secondary small-button" onClick={() => setF({ ...f, phases: [...f.phases, blankPhase(Number(f.phases[f.phases.length - 1]?.toWeek ?? 0) + 1)] })}>
        Add phase
      </button>
      <fieldset>
        <legend>Review dates</legend>
        {f.reviewDates.map((d, i) => (
          <div key={i} className="inline-row">
            <IdField id={`rtw-review-${i}`} label={`Review ${i + 1}`} error={err(`rtw-review-${i}`)}>
              {(dd) => <input id={`rtw-review-${i}`} type="date" value={d} aria-describedby={dd} onChange={(e) => setF({ ...f, reviewDates: f.reviewDates.map((x, j) => (j === i ? e.target.value : x)) })} />}
            </IdField>
            {f.reviewDates.length > 1 && (
              <button type="button" className="link-button" onClick={() => setF({ ...f, reviewDates: f.reviewDates.filter((_, j) => j !== i) })} aria-label={`Remove review ${i + 1}`}>
                Remove
              </button>
            )}
          </div>
        ))}
        <button type="button" className="secondary small-button" onClick={() => setF({ ...f, reviewDates: [...f.reviewDates, ''] })}>
          Add review date
        </button>
      </fieldset>
      <div className="actions">
        <button type="submit" disabled={busy}>{submitLabel}</button>
        {onCancel && <button type="button" className="secondary" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}

function ReviewForm({ onSubmit, busy }: { onSubmit: (o: ReviewOutcome, note: string) => void; busy: boolean }) {
  const [o, setO] = useState<ReviewOutcome>('ON_TRACK');
  const [note, setNote] = useState('');
  return (
    <form
      className="form inline-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(o, note.trim());
      }}
    >
      <div className="field">
        <label htmlFor="rv-outcome">Outcome</label>
        <select id="rv-outcome" value={o} onChange={(e) => setO(e.target.value as ReviewOutcome)}>
          <SelectOptions values={REVIEW_OUTCOMES} />
        </select>
      </div>
      <div className="field">
        <label htmlFor="rv-note">Note (functional)</label>
        <input id="rv-note" maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
      <button type="submit" className="small-button" disabled={busy}>
        Complete review
      </button>
    </form>
  );
}

/** RTW plans for the case: builder, activate, acknowledgements, reviews, job offers (read-only; HR issues offers). */
export function RtwPlanTab({ client, onSessionExpired, caseId }: CaseTabProps) {
  const api = fitnessApi(client);
  const catalogue = useAsync(() => platformApi(client).restrictions(), onSessionExpired);
  const plans = useAsync(() => api.plans(caseId), onSessionExpired);
  const offers = useAsync(() => api.jobOffers(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<RtwPlan | 'new'>();
  const [reviewing, setReviewing] = useState<string>();
  const cat = catalogue.data ?? [];

  async function save(f: PlanForm) {
    const body = { startDate: f.startDate, goal: f.goal.trim(), phases: toPhases(f, cat), reviewDates: f.reviewDates.filter(Boolean) };
    const ok = await action.run(async () => {
      if (editing === 'new') await api.createPlan(caseId, body);
      else if (editing) await api.updatePlan(editing.id, body);
      return 'RTW plan saved as a draft.';
    });
    if (ok) {
      setEditing(undefined);
      plans.reload();
    }
  }

  async function activate(p: RtwPlan) {
    if (await action.run(async () => { await api.activatePlan(p.id); return 'Plan activated: the employee and manager have acknowledgement tasks and the RTW plan document was generated.'; })) plans.reload();
  }

  async function completeReview(p: RtwPlan, reviewId: string, o: ReviewOutcome, note: string) {
    const ok = await action.run(async () => {
      await api.completeReview(p.id, reviewId, o, note);
      return `Review completed: ${humanise(o)}.`;
    });
    if (ok) {
      setReviewing(undefined);
      plans.reload();
    }
  }

  const list = plans.data ?? [];
  const today = isoDay(new Date());
  return (
    <div>
      <h2>Return-to-work plans</h2>
      {plans.loading && <Loading />}
      {!!plans.error && <ErrorAlert error={plans.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {plans.data && list.length === 0 && !editing && <Empty>No RTW plan yet.</Empty>}
      {list.map((p) => (
        <article key={p.id} className="tile rtw-plan" aria-label={`RTW plan starting ${formatDay(p.startDate)}`}>
          <h3>
            Plan from {formatDay(p.startDate)} <StatusBadge status={p.status} />
          </h3>
          <p className="prewrap">{p.goal}</p>
          <div className="table-wrap">
            <table>
              <caption className="visually-hidden">Phases</caption>
              <thead>
                <tr>
                  <th scope="col">Weeks</th>
                  <th scope="col">Hours / day</th>
                  <th scope="col">Duties</th>
                  <th scope="col">Restrictions</th>
                </tr>
              </thead>
              <tbody>
                {p.phases.map((x, i) => (
                  <tr key={i}>
                    <th scope="row">{x.fromWeek}–{x.toWeek}</th>
                    <td>{x.hoursPerDay}</td>
                    <td className="prewrap">{x.duties}</td>
                    <td>{x.restrictions.map((r) => r.label).join('; ') || '–'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h4>Reviews</h4>
          <ul className="plain-list">
            {p.reviews.map((r) => (
              <li key={r.id}>
                {formatDay(r.dueDate)}{' '}
                {r.completedAt ? (
                  <span className="small">
                    – {humanise(r.outcome)} by {r.completedBy} {formatDate(r.completedAt)}
                    {r.note ? `: ${r.note}` : ''}
                  </span>
                ) : (
                  <>
                    {r.dueDate < today && (
                      <span className="badge badge-overdue">
                        <span aria-hidden="true">■ </span>Overdue
                      </span>
                    )}{' '}
                    {p.status === 'ACTIVE' &&
                      (reviewing === r.id ? (
                        <ReviewForm busy={action.busy} onSubmit={(o, n) => void completeReview(p, r.id, o, n)} />
                      ) : (
                        <button type="button" className="secondary small-button" onClick={() => setReviewing(r.id)}>
                          Complete review
                        </button>
                      ))}
                  </>
                )}
              </li>
            ))}
          </ul>
          <p className="small">
            Employee: {p.employeeAck ? `${p.employeeAck.agree ? 'agreed' : 'did not agree'} ${formatDate(p.employeeAck.at)}${p.employeeAck.comment ? ` – “${p.employeeAck.comment}”` : ''}` : 'not yet acknowledged'}
            <br />
            Manager: {p.managerAck ? `acknowledged by ${p.managerAck.by} ${formatDate(p.managerAck.at)}${p.managerAck.comment ? ` – “${p.managerAck.comment}”` : ''}` : 'not yet acknowledged'}
            {p.nextReviewDate && (
              <>
                <br />
                Next review: {formatDay(p.nextReviewDate)}
              </>
            )}
          </p>
          <div className="card-actions">
            {p.documentId && (
              <a className="button secondary small-button" href={documentContentUrl(p.documentId)} target="_blank" rel="noopener">
                Printable plan (PDF)
              </a>
            )}
            {p.status === 'DRAFT' && (
              <>
                <button type="button" className="secondary small-button" onClick={() => setEditing(p)}>
                  Edit
                </button>
                <button type="button" className="small-button" disabled={action.busy} onClick={() => void activate(p)}>
                  Activate plan
                </button>
              </>
            )}
          </div>
        </article>
      ))}
      {!editing && (
        <button type="button" className="secondary" onClick={() => setEditing('new')}>
          New RTW plan
        </button>
      )}
      {editing && (
        <div className="tile">
          <h3>{editing === 'new' ? 'New RTW plan' : 'Edit draft plan'}</h3>
          <PlanBuilder initial={editing === 'new' ? blankPlan() : fromPlan(editing)} catalogue={cat} busy={action.busy} onSubmit={(f) => void save(f)} onCancel={() => setEditing(undefined)} submitLabel="Save draft plan" />
        </div>
      )}
      <h2>Alternative-duty job offers</h2>
      <p className="small muted">Issued by HR; the employee accepts or declines electronically.</p>
      {!!offers.error && <ErrorAlert error={offers.error} />}
      {offers.data && offers.data.length === 0 && <Empty>No job offers.</Empty>}
      {(offers.data ?? []).length > 0 && (
        <ul className="plain-list">
          {(offers.data ?? []).map((o) => (
            <li key={o.id}>
              {o.jobTitle} <StatusBadge status={o.status} /> <span className="small muted">issued {formatDate(o.issuedAt)}{o.respondedAt ? ` · responded ${formatDate(o.respondedAt)}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
