import { useState, type FormEvent } from 'react';
import { EXCLUSION_DECISIONS, TRACE_INCLUDE, travelApi, type CdCase, type CdCondition, type TraceResult, type TracedContact } from '../../api/travel';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import {
  checkInText,
  monitoringView,
  overlapText,
  reportDeadline,
  riskCounts,
  riskView,
  sortContacts,
  splitLines,
  suggestedUntil,
  tracingKpi,
  TRACING_KPI_HOURS,
  validateExclusion,
  validateTrace,
  type ExclusionForm,
} from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import { navigate } from '../../routing/useHashRoute';
import type { ScreenProps } from '../types';
import { useNow } from './hooks';

const caseLink = (id: string) => `#/communicable/${encodeURIComponent(id)}`;

function ConditionFacts({ c }: { c: CdCondition }) {
  return (
    <p className="small">
      {c.notifiable ? <ToneBadge tone="red">Notifiable{c.reportWithinHours ? ` – report within ${c.reportWithinHours} h` : ''}</ToneBadge> : <ToneBadge tone="muted">Not notifiable</ToneBadge>}{' '}
      {c.exclusionDays ? `Exclusion ${c.exclusionDays} d. ` : ''}
      {c.foodHandlerExclusion ? 'Food handlers excluded. ' : ''}
      Contact monitoring {c.monitoringDays} d.
    </p>
  );
}

function NewCaseForm({ client, onSessionExpired, conditions }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { conditions: CdCondition[] }) {
  const [employee, setEmployee] = useState<EmployeeRef>();
  const [f, setF] = useState({ conditionCode: '', onsetDate: todayIso(), confirmedOn: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const condition = conditions.find((c) => c.code === f.conditionCode);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!employee) errs.push({ field: 'cd-employee', message: 'Choose the employee.' });
    if (!f.conditionCode) errs.push({ field: 'cd-condition', message: 'Choose the condition.' });
    if (!f.onsetDate) errs.push({ field: 'cd-onset', message: 'Enter the onset date.' });
    else if (f.onsetDate > todayIso()) errs.push({ field: 'cd-onset', message: 'Onset cannot be in the future.' });
    if (f.confirmedOn && f.onsetDate && f.confirmedOn < f.onsetDate) errs.push({ field: 'cd-confirmed', message: 'Confirmation cannot be before onset.' });
    setErrors(errs);
    if (errs.length || !employee) return;
    let created: CdCase | undefined;
    const ok = await action.run(async () => {
      created = await travelApi(client).createCase({ employeeId: employee.employeeId, conditionCode: f.conditionCode, onsetDate: f.onsetDate, ...(f.confirmedOn ? { confirmedOn: f.confirmedOn } : {}) });
    });
    if (ok && created) navigate(`/communicable/${encodeURIComponent(created.id)}`);
  }

  return (
    <section className="card" aria-labelledby="cd-new">
      <h2 id="cd-new">Record a case</h2>
      <ErrorSummary errors={errors} />
      {employee ? (
        <p id="cd-employee" tabIndex={-1}>
          Employee: <strong>{employee.displayName}</strong> ({employee.employeeId}){' '}
          <button type="button" className="link-button" onClick={() => setEmployee(undefined)}>Change</button>
        </p>
      ) : (
        <div id="cd-employee" tabIndex={-1}>
          <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmployee} />
        </div>
      )}
      <form className="form inline-form" onSubmit={submit} noValidate>
        <IdField id="cd-condition" label="Condition" required error={errorFor(errors, 'cd-condition')}>
          {(db) => (
            <select id="cd-condition" aria-describedby={db} value={f.conditionCode} onChange={(e) => setF({ ...f, conditionCode: e.target.value })}>
              <option value="">Choose…</option>
              {conditions.map((c) => <option key={c.code} value={c.code}>{c.name}{c.notifiable ? ' (notifiable)' : ''}</option>)}
            </select>
          )}
        </IdField>
        <IdField id="cd-onset" label="Onset date" required error={errorFor(errors, 'cd-onset')}>{(db) => <input id="cd-onset" type="date" aria-describedby={db} value={f.onsetDate} onChange={(e) => setF({ ...f, onsetDate: e.target.value })} />}</IdField>
        <IdField id="cd-confirmed" label="Confirmed on (optional)" error={errorFor(errors, 'cd-confirmed')}>{(db) => <input id="cd-confirmed" type="date" aria-describedby={db} value={f.confirmedOn} onChange={(e) => setF({ ...f, confirmedOn: e.target.value })} />}</IdField>
        <button type="submit" disabled={action.busy}>Record case</button>
      </form>
      {condition && <ConditionFacts c={condition} />}
      <ActionFeedback error={action.error} />
    </section>
  );
}

/** `#/communicable` – communicable-disease cases and new case entry (E11-F02). */
export function CommunicableList({ client, session, onSessionExpired }: ScreenProps) {
  const api = travelApi(client);
  const conditions = useAsync(() => api.conditions(), onSessionExpired);
  const cases = useAsync(() => api.cases(), onSessionExpired);
  const now = useNow(60_000);
  const names = new Map((conditions.data ?? []).map((c) => [c.code, c.name]));
  return (
    <section>
      <PageTitle sub="Cases, exclusion decisions, contact tracing from duty rosters and daily check-ins of contacts.">Communicable disease</PageTitle>
      {can(session.roles, 'communicable') && conditions.data && <NewCaseForm client={client} onSessionExpired={onSessionExpired} conditions={conditions.data} />}
      {!!conditions.error && <ErrorAlert error={conditions.error} />}
      <h2>Cases</h2>
      {cases.loading && <Loading />}
      {!!cases.error && <ErrorAlert error={cases.error} />}
      {cases.data && cases.data.length === 0 && <Empty>No communicable-disease cases.</Empty>}
      {cases.data && cases.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{cases.data.length} case{cases.data.length === 1 ? '' : 's'}</caption>
            <thead><tr><th scope="col">Case</th><th scope="col">Employee</th><th scope="col">Condition</th><th scope="col">Status</th><th scope="col">Exclusion</th><th scope="col">Public-health report</th></tr></thead>
            <tbody>
              {cases.data.map((c) => {
                const due = c.notifiable && c.reportDueAt ? reportDeadline({ dueAt: c.reportDueAt, status: c.status }, now) : undefined;
                return (
                  <tr key={c.id}>
                    <th scope="row"><a href={caseLink(c.id)}>{c.ref}</a></th>
                    <td>{c.displayName ?? c.employeeId ?? '–'}</td>
                    <td>{c.conditionName ?? (c.conditionCode ? names.get(c.conditionCode) ?? c.conditionCode : '–')}</td>
                    <td><StatusBadge status={c.status} /></td>
                    <td className="small">{c.exclusion ? `${humanise(c.exclusion.decision)} from ${formatDay(c.exclusion.from)}${c.exclusion.until ? ` to ${formatDay(c.exclusion.until)}` : ''}` : '–'}</td>
                    <td>{c.notifiable ? due ? <ToneBadge tone={due.tone}>{due.label}</ToneBadge> : <ToneBadge tone="amber">Notifiable</ToneBadge> : <span className="muted">Not notifiable</span>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function ExclusionPanel({ client, onSessionExpired, caseId, condition, current, onSaved }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { caseId: string; condition?: CdCondition; current?: CdCase['exclusion']; onSaved: () => void }) {
  const [f, setF] = useState<ExclusionForm>({ decision: current?.decision ?? 'EXCLUDED', from: current?.from ?? todayIso(), until: current?.until ?? suggestedUntil(todayIso(), condition), returnCriteria: (current?.returnCriteria ?? []).join('\n') });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateExclusion(f);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await travelApi(client).exclusion(caseId, { decision: f.decision as 'EXCLUDED', from: f.from, ...(f.until ? { until: f.until } : {}), returnCriteria: splitLines(f.returnCriteria) });
      return `Decision recorded: ${humanise(f.decision).toLowerCase()}. The fitness status feed is updated (status only).`;
    });
    if (ok) onSaved();
  }
  return (
    <section className="card" aria-labelledby="excl-title">
      <h2 id="excl-title">Exclusion decision</h2>
      {condition?.foodHandlerExclusion && <p className="small">Food handlers: catering hygiene receives the exclusion status only.</p>}
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="inline-form">
          <IdField id="excl-decision" label="Decision" required error={errorFor(errors, 'excl-decision')}>{(db) => <select id="excl-decision" aria-describedby={db} value={f.decision} onChange={(e) => setF({ ...f, decision: e.target.value })}><SelectOptions values={EXCLUSION_DECISIONS} /></select>}</IdField>
          <IdField id="excl-from" label="From" required error={errorFor(errors, 'excl-from')}>{(db) => <input id="excl-from" type="date" aria-describedby={db} value={f.from} onChange={(e) => setF({ ...f, from: e.target.value, until: f.until || suggestedUntil(e.target.value, condition) })} />}</IdField>
          <IdField id="excl-until" label="Until (optional)" hint={condition?.exclusionDays ? `Condition default: ${condition.exclusionDays} days` : undefined} error={errorFor(errors, 'excl-until')}>{(db) => <input id="excl-until" type="date" aria-describedby={db} value={f.until} onChange={(e) => setF({ ...f, until: e.target.value })} />}</IdField>
        </div>
        <IdField id="excl-criteria" label="Return criteria (one per line)" required={f.decision !== 'CLEARED'} hint="e.g. 48 h symptom-free; negative stool sample" error={errorFor(errors, 'excl-criteria')}>
          {(db) => <textarea id="excl-criteria" rows={3} aria-describedby={db} value={f.returnCriteria} onChange={(e) => setF({ ...f, returnCriteria: e.target.value })} />}
        </IdField>
        <button type="submit" disabled={action.busy}>Record decision</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function TracePanel({ client, onSessionExpired, cdCase, onTraced }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { cdCase?: CdCase; onTraced: (r: TraceResult) => void }) {
  const onset = cdCase?.onsetDate ? new Date(`${cdCase.onsetDate}T00:00:00`) : new Date();
  const start = new Date(onset.getTime() - 2 * 24 * 3600_000);
  const [f, setF] = useState({ windowFrom: toLocalInput(start), windowTo: toLocalInput(new Date()), include: ['SAME_DUTY', 'PROXIMITY_GROUP'] as string[] });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateTrace(f);
    setErrors(errs);
    if (errs.length || !cdCase) return;
    let result: TraceResult | undefined;
    const ok = await action.run(async () => {
      result = await travelApi(client).trace(cdCase.id, { windowFrom: localToIso(f.windowFrom)!, windowTo: localToIso(f.windowTo)!, include: f.include as ('SAME_DUTY' | 'PROXIMITY_GROUP' | 'FACILITY')[] });
      return `${result.contacts.length} contact${result.contacts.length === 1 ? '' : 's'} found.`;
    });
    if (ok && result) onTraced(result);
  }
  return (
    <section className="card" aria-labelledby="trace-title">
      <h2 id="trace-title">Contact tracing</h2>
      <p className="small muted">Contacts come from crew rosters, shifts and facility access in the infectious window. High risk = same proximity group for 15 minutes or more.</p>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="inline-form">
          <IdField id="trace-from" label="Window from" required error={errorFor(errors, 'trace-from')}>{(db) => <input id="trace-from" type="datetime-local" aria-describedby={db} value={f.windowFrom} onChange={(e) => setF({ ...f, windowFrom: e.target.value })} />}</IdField>
          <IdField id="trace-to" label="Window to" required error={errorFor(errors, 'trace-to')}>{(db) => <input id="trace-to" type="datetime-local" aria-describedby={db} value={f.windowTo} onChange={(e) => setF({ ...f, windowTo: e.target.value })} />}</IdField>
        </div>
        <fieldset className={errorFor(errors, 'trace-include') ? 'field-error' : undefined}>
          <legend>Include</legend>
          {errorFor(errors, 'trace-include') && <span className="field-error-text small">{errorFor(errors, 'trace-include')}</span>}
          <div className="checkbox-group">
            {TRACE_INCLUDE.map((i, n) => (
              <label key={i} className="inline-row">
                <input id={n === 0 ? 'trace-include' : undefined} type="checkbox" checked={f.include.includes(i)} onChange={() => setF({ ...f, include: f.include.includes(i) ? f.include.filter((x) => x !== i) : [...f.include, i] })} /> {humanise(i)}
              </label>
            ))}
          </div>
        </fieldset>
        <button type="submit" disabled={action.busy || !cdCase}>Generate contact list</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function ContactsTable({ client, onSessionExpired, caseId, contacts, onNotified }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { caseId: string; contacts: TracedContact[]; onNotified: () => void }) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const action = useAction(onSessionExpired);
  const rows = sortContacts(contacts);
  const counts = riskCounts(contacts);
  const notNotified = rows.filter((c) => !c.notifiedAt).map((c) => c.id);
  const toggle = (id: string) => {
    const s = new Set(selected);
    if (s.has(id)) s.delete(id);
    else s.add(id);
    setSelected(s);
  };
  async function notify() {
    const ids = [...selected];
    const ok = await action.run(async () => {
      await travelApi(client).notifyContacts(caseId, ids);
      return `${ids.length} contact${ids.length === 1 ? '' : 's'} notified; daily check-ins started.`;
    });
    if (ok) {
      setSelected(new Set());
      onNotified();
    }
  }
  if (contacts.length === 0) return <Empty>No contacts found for this window.</Empty>;
  return (
    <>
      <p>
        <ToneBadge tone="red">{counts.HIGH} high</ToneBadge> <ToneBadge tone="amber">{counts.MEDIUM} medium</ToneBadge> <ToneBadge tone="muted">{counts.LOW} low</ToneBadge>
      </p>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={() => setSelected(new Set(notNotified))} disabled={notNotified.length === 0}>Select all not yet notified</button>
        <button type="button" className="secondary" onClick={() => setSelected(new Set(rows.filter((c) => c.riskLevel === 'HIGH' && !c.notifiedAt).map((c) => c.id)))}>Select high risk</button>
        <button type="button" onClick={() => void notify()} disabled={action.busy || selected.size === 0}>Notify {selected.size} selected</button>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      <div className="table-wrap">
        <table>
          <caption>Contacts ({contacts.length}), highest risk first</caption>
          <thead>
            <tr>
              <th scope="col"><span className="visually-hidden">Select</span></th>
              <th scope="col">Contact</th><th scope="col">Type</th><th scope="col">Duty</th><th scope="col" className="num">Overlap</th><th scope="col">Risk</th><th scope="col">Monitoring</th><th scope="col">Notified</th><th scope="col">Monitoring until</th><th scope="col">Last check-in</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => {
              const r = riskView(c.riskLevel);
              const m = monitoringView(c);
              const ci = checkInText(c.lastCheckIn);
              return (
                <tr key={c.id} className={c.symptomatic ? 'row-breached' : undefined}>
                  <td><input type="checkbox" aria-label={`Select ${c.displayName}`} checked={selected.has(c.id)} onChange={() => toggle(c.id)} /></td>
                  <th scope="row">{c.displayName} <span className="small muted">{c.employeeId}</span></th>
                  <td>{humanise(c.contactType)}</td>
                  <td className="mono small">{c.dutyRef ?? '–'}</td>
                  <td className="num">{overlapText(c.overlapMinutes)}</td>
                  <td><ToneBadge tone={r.tone} title={r.title}>{r.label}</ToneBadge></td>
                  <td><ToneBadge tone={m.tone}>{m.label}</ToneBadge></td>
                  <td className="small">{formatDate(c.notifiedAt)}</td>
                  <td className="small">{formatDay(c.monitoringUntil)}</td>
                  <td className="small">{ci.at ? formatDate(ci.at) : '–'}{ci.detail ? ` · ${ci.detail}` : ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

function ClearPanel({ client, onSessionExpired, caseId, criteria, onCleared }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { caseId: string; criteria: string[]; onCleared: () => void }) {
  const [met, setMet] = useState<string[]>([]);
  const [free, setFree] = useState('');
  const action = useAction(onSessionExpired);
  const all = criteria.length > 0 ? met.length === criteria.length : splitLines(free).length > 0;
  async function clear() {
    const ok = await action.run(async () => {
      await travelApi(client).clear(caseId, criteria.length > 0 ? met : splitLines(free));
      return 'Case cleared – fit to return.';
    });
    if (ok) onCleared();
  }
  return (
    <section className="card" aria-labelledby="clear-title">
      <h2 id="clear-title">Clear for return</h2>
      {criteria.length > 0 ? (
        <fieldset>
          <legend>Return criteria met</legend>
          {criteria.map((c) => (
            <label key={c} className="inline-row">
              <input type="checkbox" checked={met.includes(c)} onChange={() => setMet(met.includes(c) ? met.filter((x) => x !== c) : [...met, c])} /> {c}
            </label>
          ))}
        </fieldset>
      ) : (
        <Field label="Criteria met (one per line)">{(id) => <textarea id={id} rows={2} value={free} onChange={(e) => setFree(e.target.value)} />}</Field>
      )}
      <button type="button" onClick={() => void clear()} disabled={action.busy || !all}>Clear case</button>
      {!all && <p className="small muted">All return criteria must be confirmed before clearing.</p>}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/communicable/{id}` – case, exclusion, contact tracing, notification and monitoring, clearance (E11-F02). */
export function CommunicableCase({ client, session, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const api = travelApi(client);
  const detail = useAsync(() => api.case(caseId), onSessionExpired, [caseId]);
  const conditions = useAsync(() => api.conditions(), onSessionExpired);
  const contacts = useAsync(() => api.contacts(caseId), onSessionExpired, [caseId]);
  const [trace, setTrace] = useState<TraceResult>();
  const phAction = useAction(onSessionExpired);
  const c = detail.data;
  const condition = conditions.data?.find((x) => x.code === c?.conditionCode);
  const kpi = tracingKpi(trace?.hoursSinceConfirmation ?? c?.hoursSinceConfirmation);
  const write = can(session.roles, 'communicable');

  return (
    <section>
      <p className="breadcrumb"><a href="#/communicable">Communicable disease</a> › {c?.ref ?? 'Case'}</p>
      <PageTitle>{c ? `${c.ref} – ${c.conditionName ?? condition?.name ?? c.conditionCode ?? 'Communicable disease'}` : 'Communicable-disease case'}</PageTitle>
      {detail.loading && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {c && (
        <div className="grid-2">
          <Dl items={[
            ['Employee', c.displayName ? `${c.displayName} (${c.employeeId ?? ''})` : c.employeeId],
            ['Status', <StatusBadge status={c.status} />],
            ['Onset', formatDay(c.onsetDate)],
            ['Confirmed', formatDay(c.confirmedOn)],
            ['Notifiable', c.notifiable ? `Yes – report due ${formatDate(c.reportDueAt)}` : 'No'],
            ['Exclusion', c.exclusion ? `${humanise(c.exclusion.decision)} from ${formatDay(c.exclusion.from)}${c.exclusion.until ? ` to ${formatDay(c.exclusion.until)}` : ''}` : 'None recorded'],
          ]} />
          <div className="tiles" role="group" aria-label="Tracing KPI">
            <KpiTile label={kpi.label} value={kpi.status === 'UNKNOWN' ? '–' : kpi.status === 'MET' ? 'Within target' : 'Late'} target={`Target ≤ ${TRACING_KPI_HOURS} h from confirmation`} status={kpi.status} />
          </div>
        </div>
      )}
      {condition && <ConditionFacts c={condition} />}
      {c?.notifiable && can(session.roles, 'publicHealthWrite') && (
        <p>
          <button
            type="button"
            className="secondary"
            disabled={phAction.busy}
            onClick={() => void phAction.run(async () => {
              const r = await api.createReport({ communicableCaseId: c.id, authorityCode: 'UKHSA_LOCAL' });
              return `Public-health report ${r.ref ?? ''} drafted – see Public-health reports.`;
            })}
          >
            Draft public-health report
          </button>
        </p>
      )}
      <ActionFeedback error={phAction.error} message={phAction.message} />
      {write && c && (
        <>
          <ExclusionPanel key={`${c.id}-${c.exclusion?.decision ?? ''}-${condition?.code ?? ''}`} client={client} onSessionExpired={onSessionExpired} caseId={caseId} condition={condition} current={c.exclusion} onSaved={detail.reload} />
          <TracePanel client={client} onSessionExpired={onSessionExpired} cdCase={c} onTraced={(r) => { setTrace(r); contacts.reload(); }} />
        </>
      )}
      <section aria-labelledby="contacts-title">
        <h2 id="contacts-title">Contacts</h2>
        {trace && <p className="small">Generated {formatDate(trace.generatedAt)} · {kpi.label} (target ≤ {TRACING_KPI_HOURS} h)</p>}
        {contacts.loading && !contacts.data && <Loading />}
        {!!contacts.error && <ErrorAlert error={contacts.error} />}
        {contacts.data && <ContactsTable client={client} onSessionExpired={onSessionExpired} caseId={caseId} contacts={contacts.data} onNotified={contacts.reload} />}
      </section>
      {write && c && c.status !== 'CLEARED' && c.status !== 'CLOSED' && <ClearPanel client={client} onSessionExpired={onSessionExpired} caseId={caseId} criteria={c.exclusion?.returnCriteria ?? []} onCleared={detail.reload} />}
    </section>
  );
}
