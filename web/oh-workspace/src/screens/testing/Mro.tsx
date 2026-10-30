import { useState, type FormEvent } from 'react';
import { RTD_OUTCOMES, testingApi, VERDICTS, type MroQueueItem, type RtdPlan } from '../../api/testing';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { serverFieldError } from '../../domain/support';
import { emptyMroForm, emptyRtdForm, nonNegative, toMroBody, validateMro, validateRtd, type MroForm, type RtdForm } from '../../domain/testing';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

/** `#/testing/mro` – medical review officer queue: lab results, donor interview, verdict, prescription verification, split request. */
export function MroQueue(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const queue = useAsync(() => testingApi(client).mroQueue(), onSessionExpired);
  const [selected, setSelected] = useState<MroQueueItem>();
  const [message, setMessage] = useState<string>();
  const rows = queue.data ?? [];
  return (
    <section>
      <PageTitle sub="Restricted: laboratory results are visible to the MRO only. A verified positive or refusal removes the employee from safety-sensitive duty (status only) and opens the support referral.">MRO review</PageTitle>
      {message && <p role="status" className="alert alert-success">{message}</p>}
      {queue.loading && !queue.data && <Loading />}
      {!!queue.error && <ErrorAlert error={queue.error} />}
      {queue.data && rows.length === 0 && <Empty>The review queue is empty.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} result{rows.length === 1 ? '' : 's'} awaiting review</caption>
            <thead><tr><th scope="col">Test</th><th scope="col">Donor</th><th scope="col">Reason</th><th scope="col">Specimen</th><th scope="col">Validity</th><th scope="col">Non-negative</th><th scope="col">Received</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((r) => {
                const nn = nonNegative(r.results);
                return (
                  <tr key={r.testId}>
                    <th scope="row">{r.ref}</th>
                    <td>{r.displayName} <span className="small muted">{r.employeeId}</span></td>
                    <td>{humanise(r.reason)}</td>
                    <td className="mono">{r.specimenId}</td>
                    <td><ToneBadge tone={r.validity === 'VALID' ? 'green' : r.validity === 'DILUTE' ? 'amber' : 'red'}>{humanise(r.validity)}</ToneBadge></td>
                    <td>{nn.length ? <ToneBadge tone="red">{nn.join(', ')}</ToneBadge> : 'None'}</td>
                    <td className="small">{formatDate(r.receivedAt)}</td>
                    <td><button type="button" className="secondary small-button" aria-label={`Review ${r.ref}`} onClick={() => { setMessage(undefined); setSelected(r); }}>Review</button></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {selected && (
        <MroDecision
          key={selected.testId}
          {...props}
          item={selected}
          onDone={(msg) => {
            setSelected(undefined);
            if (msg) setMessage(msg);
            queue.reload();
          }}
        />
      )}
    </section>
  );
}

function MroDecision({ client, onSessionExpired, item, onDone }: ScreenProps & { item: MroQueueItem; onDone: (message?: string) => void }) {
  const api = testingApi(client);
  const [form, setForm] = useState<MroForm>(emptyMroForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const err = (f: string) => errorFor(errors, f);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateMro(form);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        await api.mroDecision(item.testId, toMroBody(form));
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) onDone(`${item.ref}: ${humanise(form.verdict)} recorded.`);
    else {
      const fe = serverFieldError(failure, { verdict: 'mro-verdict', prescriptionVerified: 'mro-prescription', interviewNote: 'mro-note' });
      if (fe) setErrors([fe]);
    }
  }
  async function split() {
    if (await action.run(async () => { await api.splitRequest(item.testId); return 'Split specimen (bottle B) ordered from the laboratory.'; })) onDone(`${item.ref}: split specimen requested.`);
  }

  return (
    <section className="card" aria-labelledby="mro-review">
      <h2 id="mro-review">Review {item.ref} – {item.displayName}</h2>
      <Dl items={[['Reason', humanise(item.reason)], ['Specimen', <span className="mono">{item.specimenId}</span>], ['Validity', humanise(item.validity)], ['Received', formatDate(item.receivedAt)]]} />
      <table>
        <caption>Laboratory results</caption>
        <thead><tr><th scope="col">Analyte</th><th scope="col" className="num">Cut-off</th><th scope="col" className="num">Value</th><th scope="col">Unit</th><th scope="col">Outcome</th></tr></thead>
        <tbody>
          {item.results.map((r) => (
            <tr key={r.analyte}>
              <th scope="row">{r.analyte}</th>
              <td className="num">{r.cutoff}</td>
              <td className="num">{r.value}</td>
              <td>{r.unit}</td>
              <td><ToneBadge tone={r.outcome === 'NEGATIVE' ? 'green' : 'red'}>{humanise(r.outcome)}</ToneBadge></td>
            </tr>
          ))}
        </tbody>
      </table>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="mro-verdict" label="Verdict" required error={err('mro-verdict')}>
          {(d) => <select id="mro-verdict" aria-describedby={d} value={form.verdict} onChange={(e) => setForm({ ...form, verdict: e.target.value as MroForm['verdict'] })}><SelectOptions values={VERDICTS} empty="Choose…" /></select>}
        </IdField>
        {form.verdict === 'NEGATIVE_LEGITIMATE_PRESCRIPTION' && (
          <label className={`inline-row${err('mro-prescription') ? ' field-error' : ''}`}>
            <input id="mro-prescription" type="checkbox" checked={form.prescriptionVerified} aria-invalid={!!err('mro-prescription')} onChange={(e) => setForm({ ...form, prescriptionVerified: e.target.checked })} /> Prescription verified with the prescriber / pharmacy record
          </label>
        )}
        <IdField id="mro-note" label="Donor interview note" error={err('mro-note')} hint="Encrypted; MRO only. Required for positive and legitimate-prescription verdicts.">
          {(d) => <textarea id="mro-note" rows={3} maxLength={4000} aria-describedby={d} aria-invalid={!!err('mro-note')} value={form.interviewNote} onChange={(e) => setForm({ ...form, interviewNote: e.target.value })} />}
        </IdField>
        <label className="inline-row"><input type="checkbox" checked={form.splitSpecimenRequested} onChange={(e) => setForm({ ...form, splitSpecimenRequested: e.target.checked })} /> Donor requested split-specimen testing</label>
        <div className="actions">
          <button type="submit" disabled={action.busy}>Record verdict</button>
          <button type="button" className="secondary" disabled={action.busy} onClick={() => void split()}>Request split specimen now</button>
          <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={errors.length ? undefined : action.error} />
    </section>
  );
}

/** `#/testing/rtd` – return-to-duty plans: evaluation, follow-up tests on unannounced dates (MRO, OH_PHYSICIAN acting as MRO). */
export function RtdPlans({ client, session, onSessionExpired }: ScreenProps) {
  const api = testingApi(client);
  const [status, setStatus] = useState('');
  const list = useAsync(() => api.rtdPlans(status || undefined), onSessionExpired, [status]);
  const [form, setForm] = useState<RtdForm>(emptyRtdForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [open, setOpen] = useState(false);
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];
  const err = (f: string) => errorFor(errors, f);
  const statuses = [...new Set(rows.map((r) => r.status).filter((s): s is string => !!s))];

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs = validateRtd(form);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        await api.createRtdPlan({
          employeeId: form.employeeId.trim(), caseId: form.caseId.trim(), evaluator: form.evaluator.trim(),
          ...(form.treatmentProvider.trim() ? { treatmentProvider: form.treatmentProvider.trim() } : {}),
          followUpTests: Number(form.followUpTests), followUpMonths: Number(form.followUpMonths),
        });
        return 'Return-to-duty plan created; follow-up tests scheduled on unannounced dates.';
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) {
      setOpen(false);
      setForm(emptyRtdForm());
      list.reload();
    } else {
      const fe = serverFieldError(failure, { employeeId: 'rtd-employee', caseId: 'rtd-case', evaluator: 'rtd-evaluator', followUpTests: 'rtd-tests', followUpMonths: 'rtd-months' });
      if (fe) setErrors([fe]);
    }
  }

  return (
    <section>
      <PageTitle sub="READY needs a verified-negative return-to-duty test before the duty status becomes available. Follow-up dates are never disclosed to the employee or the manager.">Return to duty</PageTitle>
      <div className="toolbar">
        <form className="filters inline-form" onSubmit={(e) => e.preventDefault()}>
          <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={statuses.length ? statuses : ['DRAFT', 'ACTIVE', 'COMPLETED']} empty="All" /></select>}</Field>
        </form>
        {can(session.roles, 'testingRtd') && <button type="button" onClick={() => { setErrors([]); action.reset(); setOpen(!open); }}>{open ? 'Close form' : 'New plan'}</button>}
      </div>
      {open && (
        <section className="card" aria-labelledby="rtd-new">
          <h2 id="rtd-new">New return-to-duty plan</h2>
          <form className="form" onSubmit={create} noValidate>
            <ErrorSummary errors={errors} />
            <div className="form-grid">
              <IdField id="rtd-employee" label="Employee ID" required error={err('rtd-employee')}>{(d) => <input id="rtd-employee" aria-describedby={d} value={form.employeeId} onChange={(e) => setForm({ ...form, employeeId: e.target.value })} />}</IdField>
              <IdField id="rtd-case" label="Substance case" required error={err('rtd-case')} hint="The restricted SUBSTANCE case opened by the verified result">{(d) => <input id="rtd-case" aria-describedby={d} value={form.caseId} onChange={(e) => setForm({ ...form, caseId: e.target.value })} />}</IdField>
              <IdField id="rtd-evaluator" label="Evaluator" required error={err('rtd-evaluator')}>{(d) => <input id="rtd-evaluator" aria-describedby={d} value={form.evaluator} onChange={(e) => setForm({ ...form, evaluator: e.target.value })} />}</IdField>
              <IdField id="rtd-provider" label="Treatment provider">{(d) => <input id="rtd-provider" aria-describedby={d} value={form.treatmentProvider} onChange={(e) => setForm({ ...form, treatmentProvider: e.target.value })} />}</IdField>
              <IdField id="rtd-tests" label="Follow-up tests" required error={err('rtd-tests')}>{(d) => <input id="rtd-tests" type="number" min={1} max={60} aria-describedby={d} value={form.followUpTests} onChange={(e) => setForm({ ...form, followUpTests: e.target.value })} />}</IdField>
              <IdField id="rtd-months" label="Over (months)" required error={err('rtd-months')}>{(d) => <input id="rtd-months" type="number" min={1} max={60} aria-describedby={d} value={form.followUpMonths} onChange={(e) => setForm({ ...form, followUpMonths: e.target.value })} />}</IdField>
            </div>
            <div className="actions"><button type="submit" disabled={action.busy}>Create plan</button></div>
          </form>
        </section>
      )}
      <ActionFeedback error={errors.length ? undefined : action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No return-to-duty plans.</Empty>}
      {rows.map((p) => <RtdCard key={p.id} client={client} onSessionExpired={onSessionExpired} plan={p} onChanged={list.reload} />)}
    </section>
  );
}

function RtdCard({ client, onSessionExpired, plan, onChanged }: { client: ScreenProps['client']; onSessionExpired: () => void; plan: RtdPlan; onChanged: () => void }) {
  const [evalForm, setEvalForm] = useState<{ outcome: (typeof RTD_OUTCOMES)[number] | ''; note: string }>({ outcome: '', note: '' });
  const action = useAction(onSessionExpired);
  async function evaluate(e: FormEvent) {
    e.preventDefault();
    if (!evalForm.outcome) return;
    const outcome = evalForm.outcome;
    if (await action.run(async () => {
      await testingApi(client).evaluateRtd(plan.id, { outcome, ...(evalForm.note.trim() ? { note: evalForm.note.trim() } : {}) });
      return outcome === 'READY' ? 'Evaluation READY – duty status becomes available after a verified-negative return-to-duty test.' : 'Evaluation NOT READY recorded.';
    })) onChanged();
  }
  const followUps = plan.followUps ?? [];
  return (
    <article className="card" aria-labelledby={`rtd-${plan.id}`}>
      <h3 id={`rtd-${plan.id}`}>{plan.displayName ?? plan.employeeId} {plan.status && <StatusBadge status={plan.status} />} {plan.dutyStatus && <ToneBadge tone={plan.dutyStatus === 'AVAILABLE' ? 'green' : plan.dutyStatus === 'REMOVED' ? 'red' : 'amber'}>Duty: {humanise(plan.dutyStatus)}</ToneBadge>}</h3>
      <Dl
        items={[
          ['Evaluator', plan.evaluator],
          ['Treatment provider', plan.treatmentProvider],
          ['Follow-up', `${plan.followUpTests} tests over ${plan.followUpMonths} months`],
          ['Evaluation', plan.evaluation ? `${humanise(plan.evaluation.outcome)}${plan.evaluation.at ? ` – ${formatDate(plan.evaluation.at)}` : ''}` : 'Not yet evaluated'],
        ]}
      />
      {followUps.length > 0 && (
        <details>
          <summary>Follow-up schedule ({followUps.filter((f) => f.status === 'VERIFIED_NEGATIVE' || f.status === 'DONE').length} of {followUps.length} done)</summary>
          <ol className="plain-list">
            {followUps.map((f, i) => <li key={f.id ?? i}>{formatDay(f.scheduledFor ?? f.dueOn)} – {humanise(f.status ?? 'SCHEDULED')}</li>)}
          </ol>
          <p className="small muted">Unannounced – do not disclose these dates.</p>
        </details>
      )}
      {plan.evaluation?.outcome !== 'READY' && (
        <form className="form inline-form" onSubmit={evaluate} aria-label={`Evaluation for ${plan.displayName ?? plan.employeeId}`}>
          <Field label="Evaluation">{(id) => <select id={id} value={evalForm.outcome} onChange={(e) => setEvalForm({ ...evalForm, outcome: e.target.value as typeof evalForm.outcome })}><SelectOptions values={RTD_OUTCOMES} empty="Choose…" /></select>}</Field>
          <Field label="Note">{(id) => <input id={id} value={evalForm.note} maxLength={2000} onChange={(e) => setEvalForm({ ...evalForm, note: e.target.value })} />}</Field>
          <button type="submit" className="secondary" disabled={action.busy || !evalForm.outcome}>Record evaluation</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </article>
  );
}
