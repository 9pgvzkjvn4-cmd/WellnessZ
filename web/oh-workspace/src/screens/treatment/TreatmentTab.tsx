import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { schedulingApi } from '../../api/scheduling';
import { FOLLOW_UP_TYPES, INTERVENTION_TYPES, treatmentApi, type FollowUp, type PlanTemplate, type TreatmentPlan } from '../../api/treatment';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import {
  emptyFollowUp,
  emptyIntervention,
  emptyPlanForm,
  planFieldId,
  planFromTemplate,
  toPlanBody,
  validatePlanForm,
  varianceTone,
  type PlanForm,
} from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { CaseTabProps } from '../case/caseTabProps';
import { AdministerPanel } from './AdministerPanel';

const fromPlan = (p: TreatmentPlan): PlanForm => ({
  templateCode: '',
  goals: p.goals.length ? [...p.goals] : [''],
  interventions: p.interventions.map((i) => ({ type: i.type, description: i.description, frequency: i.frequency ?? '', startDate: i.startDate ?? '', endDate: i.endDate ?? '', providerCode: i.providerCode ?? '' })),
  followUps: p.followUps.filter((f) => f.status === 'PLANNED' || f.status === 'BOOKED' || f.status === 'CONFIRMED').map((f) => ({ dueDate: f.dueDate, type: f.type, siteCode: '', book: false })),
  expectedRecoveryDays: p.guideline?.expectedDays !== undefined ? String(p.guideline.expectedDays) : '',
  guidelineCode: p.guideline?.code ?? '',
  varianceThresholdPct: '',
});

const OPEN_FOLLOW_UP = ['PLANNED', 'BOOKED', 'CONFIRMED', 'DECLINED'];

function followUpTone(f: FollowUp): 'red' | 'amber' | 'green' | 'muted' {
  if (f.status === 'MISSED' || f.status === 'DECLINED') return 'red';
  if (f.status === 'DONE') return 'green';
  if (f.status === 'CANCELLED') return 'muted';
  return f.dueDate < todayIso() ? 'red' : 'amber';
}

/** Plan builder: from a template or blank; goals, interventions, follow-ups (optional booking), guideline, variance threshold. */
function PlanBuilder({ props, templates, editing, onDone }: { props: CaseTabProps; templates: PlanTemplate[]; editing?: TreatmentPlan; onDone: () => void }) {
  const { client, onSessionExpired, caseId } = props;
  const api = treatmentApi(client);
  const sites = useAsync(() => schedulingApi(client).clinicSites().catch(() => []), onSessionExpired);
  const [form, setForm] = useState<PlanForm>(() => (editing ? fromPlan(editing) : emptyPlanForm()));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const err = (k: string) => errorFor(errors, planFieldId(k));

  function applyTemplate(code: string) {
    const t = templates.find((x) => x.code === code);
    setForm(t ? planFromTemplate(t) : { ...emptyPlanForm(), templateCode: '' });
  }

  async function save(activate: boolean) {
    const errs = validatePlanForm(form);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const body = toPlanBody(form);
      if (editing) {
        await api.updatePlan(editing.id, body);
        return `Plan updated – version ${editing.version + 1}.`;
      }
      const p = await api.createPlan(caseId, body, activate);
      return activate ? 'Plan created and activated. Follow-ups were added to the case tasks.' : `Draft plan saved (${humanise(p.status)}).`;
    });
    if (ok) onDone();
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    void save(!editing);
  }

  const setInt = (i: number, patch: Partial<PlanForm['interventions'][number]>) => setForm({ ...form, interventions: form.interventions.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
  const setFu = (i: number, patch: Partial<PlanForm['followUps'][number]>) => setForm({ ...form, followUps: form.followUps.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  return (
    <section className="tile" aria-labelledby="plan-builder">
      <h2 id="plan-builder">{editing ? `Edit plan (new version of v${editing.version})` : 'New treatment plan'}</h2>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={onSubmit} noValidate>
        {!editing && (
          <IdField id={planFieldId('template')} label="Start from template" hint="Fills goals, interventions, follow-up days and the guideline duration – edit anything before saving.">
            {(db) => (
              <select id={planFieldId('template')} aria-describedby={db} value={form.templateCode} onChange={(e) => applyTemplate(e.target.value)}>
                <option value="">Blank plan</option>
                {templates.map((t) => (
                  <option key={t.code} value={t.code}>
                    {t.title} ({humanise(t.injuryType)}){t.expectedDays ? ` – ${t.expectedDays} days` : ''}
                  </option>
                ))}
              </select>
            )}
          </IdField>
        )}

        <fieldset>
          <legend>Goals</legend>
          {form.goals.map((g, i) => (
            <div key={i} className="inline-form">
              <IdField id={planFieldId(`goal-${i}`)} label={`Goal ${i + 1}`} required={i === 0} error={err(`goal-${i}`)}>
                {(db) => <input id={planFieldId(`goal-${i}`)} aria-describedby={db} aria-invalid={!!err(`goal-${i}`)} value={g} maxLength={500} onChange={(e) => setForm({ ...form, goals: form.goals.map((x, j) => (j === i ? e.target.value : x)) })} />}
              </IdField>
              {form.goals.length > 1 && (
                <button type="button" className="link-button" onClick={() => setForm({ ...form, goals: form.goals.filter((_, j) => j !== i) })} aria-label={`Remove goal ${i + 1}`}>Remove</button>
              )}
            </div>
          ))}
          <button type="button" className="secondary small-button" onClick={() => setForm({ ...form, goals: [...form.goals, ''] })}>Add goal</button>
        </fieldset>

        <fieldset>
          <legend>Interventions</legend>
          {form.interventions.map((it, i) => (
            <div key={i} className="inline-form">
              <IdField id={planFieldId(`int-${i}-type`)} label="Type">
                {(db) => (
                  <select id={planFieldId(`int-${i}-type`)} aria-describedby={db} value={it.type} onChange={(e) => setInt(i, { type: e.target.value as typeof it.type })}>
                    <SelectOptions values={INTERVENTION_TYPES} />
                  </select>
                )}
              </IdField>
              <IdField id={planFieldId(`int-${i}-description`)} label={`Intervention ${i + 1}`} required error={err(`int-${i}-description`)}>
                {(db) => <input id={planFieldId(`int-${i}-description`)} aria-describedby={db} aria-invalid={!!err(`int-${i}-description`)} value={it.description} maxLength={500} onChange={(e) => setInt(i, { description: e.target.value })} />}
              </IdField>
              <IdField id={planFieldId(`int-${i}-frequency`)} label="Frequency">
                {(db) => <input id={planFieldId(`int-${i}-frequency`)} aria-describedby={db} value={it.frequency} placeholder="e.g. 2× weekly" onChange={(e) => setInt(i, { frequency: e.target.value })} />}
              </IdField>
              <IdField id={planFieldId(`int-${i}-startDate`)} label="Start" error={err(`int-${i}-startDate`)}>
                {(db) => <input id={planFieldId(`int-${i}-startDate`)} type="date" aria-describedby={db} value={it.startDate} onChange={(e) => setInt(i, { startDate: e.target.value })} />}
              </IdField>
              <IdField id={planFieldId(`int-${i}-endDate`)} label="End" error={err(`int-${i}-endDate`)}>
                {(db) => <input id={planFieldId(`int-${i}-endDate`)} type="date" aria-describedby={db} aria-invalid={!!err(`int-${i}-endDate`)} value={it.endDate} onChange={(e) => setInt(i, { endDate: e.target.value })} />}
              </IdField>
              <IdField id={planFieldId(`int-${i}-provider`)} label="Provider code">
                {(db) => <input id={planFieldId(`int-${i}-provider`)} aria-describedby={db} value={it.providerCode} onChange={(e) => setInt(i, { providerCode: e.target.value.toUpperCase() })} />}
              </IdField>
              {form.interventions.length > 1 && (
                <button type="button" className="link-button" onClick={() => setForm({ ...form, interventions: form.interventions.filter((_, j) => j !== i) })} aria-label={`Remove intervention ${i + 1}`}>Remove</button>
              )}
            </div>
          ))}
          <button type="button" className="secondary small-button" onClick={() => setForm({ ...form, interventions: [...form.interventions, emptyIntervention()] })}>Add intervention</button>
        </fieldset>

        <fieldset>
          <legend>Follow-ups</legend>
          <p className="small muted">Each follow-up becomes a case task and the employee is reminded. Tick “book” to book it in the clinic diary.</p>
          <datalist id="plan-sites">
            {(sites.data ?? []).map((s) => (
              <option key={s.code} value={s.code}>{s.name}</option>
            ))}
          </datalist>
          {form.followUps.map((fu, i) => (
            <div key={i} className="inline-form">
              <IdField id={planFieldId(`fu-${i}-dueDate`)} label={`Follow-up ${i + 1} due`} required error={err(`fu-${i}-dueDate`)}>
                {(db) => <input id={planFieldId(`fu-${i}-dueDate`)} type="date" aria-describedby={db} aria-invalid={!!err(`fu-${i}-dueDate`)} value={fu.dueDate} onChange={(e) => setFu(i, { dueDate: e.target.value })} />}
              </IdField>
              <IdField id={planFieldId(`fu-${i}-type`)} label="Type">
                {(db) => (
                  <select id={planFieldId(`fu-${i}-type`)} aria-describedby={db} value={fu.type} onChange={(e) => setFu(i, { type: e.target.value as typeof fu.type })}>
                    <SelectOptions values={FOLLOW_UP_TYPES} />
                  </select>
                )}
              </IdField>
              <label className="inline-row">
                <input type="checkbox" checked={fu.book} onChange={(e) => setFu(i, { book: e.target.checked })} /> Book appointment
              </label>
              {fu.book && (
                <IdField id={planFieldId(`fu-${i}-siteCode`)} label="Clinic site" required error={err(`fu-${i}-siteCode`)}>
                  {(db) => <input id={planFieldId(`fu-${i}-siteCode`)} list="plan-sites" aria-describedby={db} aria-invalid={!!err(`fu-${i}-siteCode`)} value={fu.siteCode} onChange={(e) => setFu(i, { siteCode: e.target.value.toUpperCase() })} />}
                </IdField>
              )}
              <button type="button" className="link-button" onClick={() => setForm({ ...form, followUps: form.followUps.filter((_, j) => j !== i) })} aria-label={`Remove follow-up ${i + 1}`}>Remove</button>
            </div>
          ))}
          <button type="button" className="secondary small-button" onClick={() => setForm({ ...form, followUps: [...form.followUps, emptyFollowUp()] })}>Add follow-up</button>
        </fieldset>

        <fieldset>
          <legend>Guideline & variance</legend>
          <div className="inline-form">
            <IdField id={planFieldId('guidelineCode')} label="Guideline code">
              {(db) => <input id={planFieldId('guidelineCode')} aria-describedby={db} value={form.guidelineCode} onChange={(e) => setForm({ ...form, guidelineCode: e.target.value })} />}
            </IdField>
            <IdField id={planFieldId('expectedRecoveryDays')} label="Expected recovery (days)" error={err('expectedRecoveryDays')}>
              {(db) => <input id={planFieldId('expectedRecoveryDays')} inputMode="numeric" aria-describedby={db} aria-invalid={!!err('expectedRecoveryDays')} value={form.expectedRecoveryDays} onChange={(e) => setForm({ ...form, expectedRecoveryDays: e.target.value })} />}
            </IdField>
            <IdField id={planFieldId('varianceThresholdPct')} label="Variance alert threshold (%)" hint="Default 20 % over the expected duration." error={err('varianceThresholdPct')}>
              {(db) => <input id={planFieldId('varianceThresholdPct')} inputMode="numeric" aria-describedby={db} aria-invalid={!!err('varianceThresholdPct')} value={form.varianceThresholdPct} onChange={(e) => setForm({ ...form, varianceThresholdPct: e.target.value })} />}
            </IdField>
          </div>
        </fieldset>

        <div className="actions">
          {editing ? (
            <button type="submit" disabled={action.busy}>Save new version</button>
          ) : (
            <>
              <button type="submit" disabled={action.busy}>Save & activate</button>
              <button type="button" className="secondary" disabled={action.busy} onClick={() => void save(false)}>Save as draft</button>
            </>
          )}
          <button type="button" className="secondary" onClick={onDone}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function PlanView({ plan, props, onChanged, onEdit }: { plan: TreatmentPlan; props: CaseTabProps; onChanged: () => void; onEdit: () => void }) {
  const { client, session, onSessionExpired } = props;
  const api = treatmentApi(client);
  const canWrite = can(session.roles, 'treatmentWrite');
  const action = useAction(onSessionExpired);
  const [docId, setDocId] = useState(plan.documentId);
  const v = varianceTone(plan.variance);
  const open = plan.status === 'DRAFT' || plan.status === 'ACTIVE';

  async function run(fn: () => Promise<string>) {
    if (await action.run(fn)) onChanged();
  }

  return (
    <section className="tile print-plan" aria-labelledby={`plan-${plan.id}`}>
      <h2 id={`plan-${plan.id}`}>
        Treatment plan v{plan.version} <StatusBadge status={plan.status} />
      </h2>
      <dl className="details">
        <div className="details-row"><dt>Guideline</dt><dd>{plan.guideline ? `${plan.guideline.title} (${plan.guideline.code}) – ${plan.guideline.expectedDays} days` : '–'}</dd></div>
        <div className="details-row"><dt>Expected recovery</dt><dd>{formatDay(plan.expectedRecoveryDate)}</dd></div>
        <div className="details-row"><dt>Variance</dt><dd><ToneBadge tone={v.tone}>{v.label}</ToneBadge></dd></div>
        <div className="details-row"><dt>Responsible clinician</dt><dd>{plan.responsibleClinician ?? '–'}</dd></div>
      </dl>
      <h3>Goals</h3>
      <ul>
        {plan.goals.map((g) => <li key={g}>{g}</li>)}
      </ul>
      <h3>Interventions</h3>
      {plan.interventions.length === 0 ? (
        <Empty>No interventions.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Interventions</caption>
            <thead>
              <tr><th scope="col">Type</th><th scope="col">Description</th><th scope="col">Frequency</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Provider</th></tr>
            </thead>
            <tbody>
              {plan.interventions.map((i, idx) => (
                <tr key={idx}>
                  <th scope="row">{humanise(i.type)}</th>
                  <td>{i.description}</td>
                  <td>{i.frequency ?? '–'}</td>
                  <td>{formatDay(i.startDate)}</td>
                  <td>{formatDay(i.endDate)}</td>
                  <td>{i.providerCode ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>Follow-ups</h3>
      {plan.followUps.length === 0 ? (
        <Empty>No follow-ups.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Follow-ups</caption>
            <thead>
              <tr><th scope="col">Due</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col">Employee</th><th scope="col" className="no-print"><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {plan.followUps.map((f) => (
                <tr key={f.id}>
                  <th scope="row">{formatDay(f.dueDate)}</th>
                  <td>{humanise(f.type)}{f.appointmentId ? <span className="small muted"> · booked</span> : ''}</td>
                  <td><ToneBadge tone={followUpTone(f)}>{humanise(f.status)}</ToneBadge></td>
                  <td>{f.employeeResponse ? humanise(f.employeeResponse) : '–'}</td>
                  <td className="no-print">
                    {canWrite && OPEN_FOLLOW_UP.includes(f.status) && plan.status === 'ACTIVE' && (
                      <div className="card-actions">
                        {(['DONE', 'MISSED', 'CANCELLED'] as const).map((s) => (
                          <button key={s} type="button" className="secondary small-button" disabled={action.busy} onClick={() => void run(async () => { await api.followUpStatus(plan.id, f.id, s); return `Follow-up ${formatDay(f.dueDate)} marked ${humanise(s).toLowerCase()}.`; })} aria-label={`Mark follow-up ${formatDay(f.dueDate)} ${humanise(s).toLowerCase()}`}>
                            {s === 'DONE' ? 'Done' : s === 'MISSED' ? 'Missed' : 'Cancel'}
                          </button>
                        ))}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canWrite && (
        <div className="actions no-print">
          {plan.status === 'DRAFT' && (
            <button type="button" disabled={action.busy} onClick={() => void run(async () => { await api.planStatus(plan.id, 'ACTIVE'); return 'Plan activated.'; })}>Activate</button>
          )}
          {open && <button type="button" className="secondary" onClick={onEdit}>Edit (new version)</button>}
          {plan.status === 'ACTIVE' && (
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void run(async () => { await api.planStatus(plan.id, 'COMPLETED'); return 'Plan completed.'; })}>Complete plan</button>
          )}
          {open && (
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void run(async () => { await api.planStatus(plan.id, 'CANCELLED'); return 'Plan cancelled.'; })}>Cancel plan</button>
          )}
          {plan.status !== 'CANCELLED' && (
            <button
              type="button"
              className="secondary"
              disabled={action.busy}
              onClick={() =>
                void action.run(async () => {
                  const d = await api.printPlan(plan.id);
                  setDocId(d.id);
                  return 'Plain-language plan generated, signed and shared with the employee.';
                })
              }
            >
              Print for employee
            </button>
          )}
          <button type="button" className="secondary" onClick={() => window.print()}>Print this page</button>
        </div>
      )}
      {docId && (
        <p className="small no-print">
          <a href={documentContentUrl(docId)} target="_blank" rel="noopener">Open the employee plan letter</a>
        </p>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/cases/{id}/treatment` – treatment plan (E04-F01) and clinic medication administration (E04-F05). */
export function TreatmentTab(props: CaseTabProps) {
  const { client, session, onSessionExpired, caseId } = props;
  const api = treatmentApi(client);
  const plans = useAsync(() => api.plans(caseId), onSessionExpired);
  const canWrite = can(session.roles, 'treatmentWrite');
  const templates = useAsync(() => (canWrite ? api.templates() : Promise.resolve<PlanTemplate[]>([])), onSessionExpired);
  const [mode, setMode] = useState<{ kind: 'new' } | { kind: 'edit'; plan: TreatmentPlan }>();
  const list = (plans.data ?? []).filter((p) => p.status !== 'SUPERSEDED');
  const current = list.find((p) => p.status === 'ACTIVE') ?? list.find((p) => p.status === 'DRAFT');
  const past = list.filter((p) => p !== current);

  const done = () => {
    setMode(undefined);
    plans.reload();
  };

  return (
    <div>
      <h2 className="visually-hidden">Treatment</h2>
      {plans.loading && !plans.data && <Loading label="Loading treatment plans…" />}
      {!!plans.error && <ErrorAlert error={plans.error} />}
      {mode ? (
        <PlanBuilder props={props} templates={templates.data ?? []} editing={mode.kind === 'edit' ? mode.plan : undefined} onDone={done} />
      ) : (
        <>
          {plans.data && !current && <Empty>No active treatment plan.</Empty>}
          {canWrite && !current && plans.data && (
            <button type="button" onClick={() => setMode({ kind: 'new' })}>Create treatment plan</button>
          )}
          {current && <PlanView key={`${current.id}-${current.version}`} plan={current} props={props} onChanged={plans.reload} onEdit={() => setMode({ kind: 'edit', plan: current })} />}
        </>
      )}
      {past.length > 0 && (
        <details>
          <summary>Previous plans ({past.length})</summary>
          <ul className="plain-list">
            {past.map((p) => (
              <li key={p.id}>
                v{p.version} <StatusBadge status={p.status} /> <span className="small muted">{formatDate(p.createdAt)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      <AdministerPanel {...props} />
    </div>
  );
}
