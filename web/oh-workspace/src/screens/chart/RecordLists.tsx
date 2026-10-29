import { useState, type FormEvent } from 'react';
import {
  ALLERGY_CATEGORIES,
  ALLERGY_CRITICALITIES,
  codingText,
  IMMUNISATION_STATUSES,
  PROBLEM_STATUSES,
  recordApi,
  type Allergy,
  type AllergyInput,
  type Coding,
  type ImmunisationInput,
  type JobEntry,
  type JobEntryInput,
  type Problem,
  type ProblemInput,
} from '../../api/record';
import { WORK_RELATED } from '../../api/types';
import { ErrorSummary, IdField, errorFor, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { ActionFeedback, Empty, ErrorAlert, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ChartTabProps } from './chartTabProps';

function SelectedCode({ value, onClear }: { value?: Coding; onClear: () => void }) {
  if (!value) return <p className="small muted">No code selected.</p>;
  return (
    <p className="selected-code">
      Selected: <strong>{codingText(value)}</strong>{' '}
      <button type="button" className="link-button" onClick={onClear}>
        Clear
      </button>
    </p>
  );
}

// ---------------------------------------------------------------- Problems
type ProblemForm = Omit<ProblemInput, 'code'> & { code?: Coding };
const emptyProblem = (): ProblemForm => ({ status: 'ACTIVE', onsetDate: '', resolvedDate: '', workRelated: 'UNDER_REVIEW', note: '', caseId: '' });

export function ProblemsTab({ client, onSessionExpired, employeeId, canWrite, refreshSummary, caseId }: ChartTabProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.problems(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<Problem | 'new'>();
  const [form, setForm] = useState<ProblemForm>(emptyProblem());
  const [errors, setErrors] = useState<FieldError[]>([]);

  function open(p: Problem | 'new') {
    setEditing(p);
    setErrors([]);
    setForm(p === 'new' ? { ...emptyProblem(), caseId: caseId ?? '' } : { code: p.code, status: p.status, onsetDate: p.onsetDate ?? '', resolvedDate: p.resolvedDate ?? '', workRelated: p.workRelated ?? 'UNDER_REVIEW', note: p.note ?? '', caseId: p.caseId ?? '' });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.code) errs.push({ field: 'problem-code', message: 'Choose a coded problem (SNOMED CT or ICD).' });
    if (form.status === 'RESOLVED' && !form.resolvedDate) errs.push({ field: 'problem-resolved', message: 'Enter the resolved date for a resolved problem.' });
    setErrors(errs);
    if (errs.length || !form.code) return;
    const body: ProblemInput = { ...form, code: form.code, onsetDate: form.onsetDate || null, resolvedDate: form.resolvedDate || null, note: form.note?.trim() || null, caseId: form.caseId || null };
    const ok = await action.run(async () => {
      if (editing === 'new') await api.addProblem(employeeId, body);
      else if (editing) await api.updateProblem(editing.id, body);
      return 'Problem saved.';
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
      refreshSummary();
    }
  }

  const rows = list.data ?? [];
  return (
    <div>
      <h2>Problem list</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No problems recorded.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Problems</caption>
            <thead>
              <tr>
                <th scope="col">Problem</th>
                <th scope="col">Status</th>
                <th scope="col">Onset</th>
                <th scope="col">Work-related</th>
                <th scope="col">Recorded</th>
                {canWrite && <th scope="col"><span className="visually-hidden">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id}>
                  <th scope="row">{codingText(p.code)}{p.note && <div className="small muted prewrap">{p.note}</div>}</th>
                  <td><StatusBadge status={p.status} /></td>
                  <td>{formatDay(p.onsetDate)}{p.resolvedDate ? ` – ${formatDay(p.resolvedDate)}` : ''}</td>
                  <td>{humanise(p.workRelated)}</td>
                  <td className="small">{p.recordedBy} {formatDate(p.recordedAt)}</td>
                  {canWrite && (
                    <td>
                      <button type="button" className="secondary small-button" onClick={() => open(p)} aria-label={`Edit ${p.code.display}`}>
                        Edit
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canWrite && !editing && (
        <button type="button" className="secondary" onClick={() => open('new')}>
          Add problem
        </button>
      )}
      {canWrite && editing && (
        <div className="tile">
          <h3>{editing === 'new' ? 'Add problem' : 'Edit problem'}</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <div id="problem-code" tabIndex={-1}>
              <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label="Problem code" onSelect={(c) => setForm({ ...form, code: c })} />
              <SelectedCode value={form.code} onClear={() => setForm({ ...form, code: undefined })} />
              {errorFor(errors, 'problem-code') && <p className="field-error-text small">{errorFor(errors, 'problem-code')}</p>}
            </div>
            <div className="form-grid">
              <IdField id="problem-status" label="Status" required>
                {() => (
                  <select id="problem-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as ProblemInput['status'] })}>
                    <SelectOptions values={PROBLEM_STATUSES} />
                  </select>
                )}
              </IdField>
              <IdField id="problem-onset" label="Onset date">
                {() => <input id="problem-onset" type="date" value={form.onsetDate ?? ''} onChange={(e) => setForm({ ...form, onsetDate: e.target.value })} />}
              </IdField>
              <IdField id="problem-resolved" label="Resolved date" error={errorFor(errors, 'problem-resolved')}>
                {(d) => <input id="problem-resolved" type="date" value={form.resolvedDate ?? ''} aria-describedby={d} aria-invalid={!!errorFor(errors, 'problem-resolved')} onChange={(e) => setForm({ ...form, resolvedDate: e.target.value })} />}
              </IdField>
              <IdField id="problem-work" label="Work-related">
                {() => (
                  <select id="problem-work" value={form.workRelated} onChange={(e) => setForm({ ...form, workRelated: e.target.value as ProblemInput['workRelated'] })}>
                    <SelectOptions values={WORK_RELATED} />
                  </select>
                )}
              </IdField>
            </div>
            <IdField id="problem-note" label="Note">
              {() => <textarea id="problem-note" rows={2} maxLength={2000} value={form.note ?? ''} onChange={(e) => setForm({ ...form, note: e.target.value })} />}
            </IdField>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save problem</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Allergies
export function AllergiesTab({ client, onSessionExpired, employeeId, canWrite, refreshSummary }: ChartTabProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.allergies(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<Allergy | 'new'>();
  const [form, setForm] = useState<Partial<AllergyInput>>({});
  const [errors, setErrors] = useState<FieldError[]>([]);

  function open(a: Allergy | 'new') {
    setEditing(a);
    setErrors([]);
    setForm(a === 'new' ? { category: 'MEDICATION', criticality: 'HIGH', reaction: '' } : { substance: a.substance, category: a.category, criticality: a.criticality, reaction: a.reaction ?? '' });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.substance) errs.push({ field: 'allergy-substance', message: 'Choose the coded substance.' });
    setErrors(errs);
    if (errs.length || !form.substance || !form.category || !form.criticality) return;
    const body: AllergyInput = { substance: form.substance, category: form.category, criticality: form.criticality, reaction: form.reaction?.trim() || null };
    const ok = await action.run(async () => {
      if (editing === 'new') await api.addAllergy(employeeId, body);
      else if (editing) await api.updateAllergy(editing.id, body);
      return 'Allergy saved.';
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
      refreshSummary();
    }
  }

  const rows = list.data ?? [];
  return (
    <div>
      <h2>Allergies and intolerances</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No known allergies recorded.</Empty>}
      {rows.length > 0 && (
        <ul className="card-list">
          {rows.map((a) => (
            <li key={a.id} className="card">
              <strong>{codingText(a.substance)}</strong> <StatusBadge status={a.criticality === 'HIGH' ? 'HIGH_CRITICALITY' : a.criticality} />
              <div className="small">
                {humanise(a.category)}
                {a.reaction ? ` · ${a.reaction}` : ''} · {a.recordedBy} {formatDate(a.recordedAt)}
              </div>
              {canWrite && (
                <button type="button" className="secondary small-button" onClick={() => open(a)} aria-label={`Edit allergy ${a.substance.display}`}>
                  Edit
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canWrite && !editing && (
        <button type="button" className="secondary" onClick={() => open('new')}>
          Add allergy
        </button>
      )}
      {canWrite && editing && (
        <div className="tile">
          <h3>{editing === 'new' ? 'Add allergy' : 'Edit allergy'}</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <div id="allergy-substance" tabIndex={-1}>
              <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label="Substance" system="SNOMED_CT" onSelect={(c) => setForm({ ...form, substance: c })} />
              <SelectedCode value={form.substance} onClear={() => setForm({ ...form, substance: undefined })} />
            </div>
            <div className="form-grid">
              <IdField id="allergy-category" label="Category" required>
                {() => (
                  <select id="allergy-category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value as AllergyInput['category'] })}>
                    <SelectOptions values={ALLERGY_CATEGORIES} />
                  </select>
                )}
              </IdField>
              <IdField id="allergy-criticality" label="Criticality" required>
                {() => (
                  <select id="allergy-criticality" value={form.criticality} onChange={(e) => setForm({ ...form, criticality: e.target.value as AllergyInput['criticality'] })}>
                    <SelectOptions values={ALLERGY_CRITICALITIES} />
                  </select>
                )}
              </IdField>
            </div>
            <IdField id="allergy-reaction" label="Reaction">
              {() => <input id="allergy-reaction" maxLength={500} value={form.reaction ?? ''} onChange={(e) => setForm({ ...form, reaction: e.target.value })} />}
            </IdField>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save allergy</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Immunisations
export function ImmunisationsTab({ client, onSessionExpired, employeeId, canWrite, refreshSummary }: ChartTabProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.immunisations(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const blank = { vaccine: '', date: '', doseNumber: '', lotNumber: '', status: 'COMPLETE', nextDueDate: '' };
  const [form, setForm] = useState(blank);
  const [errors, setErrors] = useState<FieldError[]>([]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.vaccine.trim()) errs.push({ field: 'imm-vaccine', message: 'Enter the vaccine.' });
    if (form.status !== 'DUE' && !form.date) errs.push({ field: 'imm-date', message: 'Enter the date given.' });
    setErrors(errs);
    if (errs.length) return;
    const body: ImmunisationInput = {
      vaccine: form.vaccine.trim(),
      date: form.date || null,
      doseNumber: form.doseNumber ? Number(form.doseNumber) : null,
      lotNumber: form.lotNumber.trim() || null,
      status: form.status as ImmunisationInput['status'],
      nextDueDate: form.nextDueDate || null,
    };
    const ok = await action.run(async () => {
      await api.addImmunisation(employeeId, body);
      return 'Immunisation recorded.';
    });
    if (ok) {
      setForm(blank);
      list.reload();
      refreshSummary();
    }
  }

  const rows = list.data ?? [];
  return (
    <div className="grid-2 wide-left">
      <div>
        <h2>Immunisations</h2>
        {list.loading && <Loading />}
        {!!list.error && <ErrorAlert error={list.error} />}
        {list.data && rows.length === 0 && <Empty>No immunisations recorded.</Empty>}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Vaccine</th>
                  <th scope="col">Date</th>
                  <th scope="col">Dose</th>
                  <th scope="col">Lot</th>
                  <th scope="col">Status</th>
                  <th scope="col">Next due</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((i) => (
                  <tr key={i.id}>
                    <th scope="row">{i.vaccine}</th>
                    <td>{formatDay(i.date)}</td>
                    <td>{i.doseNumber ?? '–'}</td>
                    <td className="mono">{i.lotNumber ?? '–'}</td>
                    <td><StatusBadge status={i.status} /></td>
                    <td>{formatDay(i.nextDueDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {canWrite && (
        <div>
          <h2>Record immunisation</h2>
          <ErrorSummary errors={errors} />
          <ActionFeedback error={action.error} message={action.message} />
          <form className="form" onSubmit={submit} noValidate>
            <IdField id="imm-vaccine" label="Vaccine" required error={errorFor(errors, 'imm-vaccine')}>
              {(d) => <input id="imm-vaccine" maxLength={200} value={form.vaccine} aria-describedby={d} aria-invalid={!!errorFor(errors, 'imm-vaccine')} onChange={(e) => setForm({ ...form, vaccine: e.target.value })} />}
            </IdField>
            <IdField id="imm-status" label="Status">
              {() => (
                <select id="imm-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
                  <SelectOptions values={IMMUNISATION_STATUSES} />
                </select>
              )}
            </IdField>
            <IdField id="imm-date" label="Date given" error={errorFor(errors, 'imm-date')}>
              {(d) => <input id="imm-date" type="date" value={form.date} aria-describedby={d} aria-invalid={!!errorFor(errors, 'imm-date')} onChange={(e) => setForm({ ...form, date: e.target.value })} />}
            </IdField>
            <IdField id="imm-dose" label="Dose number">
              {() => <input id="imm-dose" type="number" min={1} max={10} value={form.doseNumber} onChange={(e) => setForm({ ...form, doseNumber: e.target.value })} />}
            </IdField>
            <IdField id="imm-lot" label="Lot number">
              {() => <input id="imm-lot" maxLength={60} value={form.lotNumber} onChange={(e) => setForm({ ...form, lotNumber: e.target.value })} />}
            </IdField>
            <IdField id="imm-next" label="Next due">
              {() => <input id="imm-next" type="date" value={form.nextDueDate} onChange={(e) => setForm({ ...form, nextDueDate: e.target.value })} />}
            </IdField>
            <button type="submit" disabled={action.busy}>Record immunisation</button>
          </form>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Jobs & exposures (ODH)
interface JobForm {
  jobTitle: string;
  jobCode: string;
  employer: string;
  industry: string;
  startDate: string;
  endDate: string;
  current: boolean;
  duties: string;
  weeklyHours: string;
  hazards: string;
}
const blankJob: JobForm = { jobTitle: '', jobCode: '', employer: '', industry: '', startDate: '', endDate: '', current: false, duties: '', weeklyHours: '', hazards: '' };

/** "NOISE, DUST:Wood dust" → hazards (code[:name]). */
export function parseHazards(text: string): { code: string; name: string }[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const [code = '', ...rest] = s.split(':');
      return { code: code.trim().toUpperCase().replace(/\s+/g, '_'), name: rest.join(':').trim() || code.trim() };
    });
}

export function JobsTab({ client, onSessionExpired, employeeId, canWrite }: ChartTabProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.jobs(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<JobEntry | 'new'>();
  const [form, setForm] = useState<JobForm>(blankJob);
  const [errors, setErrors] = useState<FieldError[]>([]);

  function open(j: JobEntry | 'new') {
    setEditing(j);
    setErrors([]);
    setForm(
      j === 'new'
        ? blankJob
        : {
            jobTitle: j.jobTitle, jobCode: j.jobCode ?? '', employer: j.employer ?? '', industry: j.industry ?? '', startDate: j.startDate ?? '', endDate: j.endDate ?? '',
            current: j.current, duties: j.duties ?? '', weeklyHours: j.weeklyHours != null ? String(j.weeklyHours) : '', hazards: j.hazards.map((h) => `${h.code}:${h.name}`).join(', '),
          },
    );
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.jobTitle.trim()) errs.push({ field: 'job-title', message: 'Enter the job title.' });
    if (!form.startDate) errs.push({ field: 'job-start', message: 'Enter the start date.' });
    if (form.endDate && form.startDate && form.endDate < form.startDate) errs.push({ field: 'job-end', message: 'End date must be after the start date.' });
    setErrors(errs);
    if (errs.length) return;
    const body: JobEntryInput = {
      jobTitle: form.jobTitle.trim(), jobCode: form.jobCode.trim() || undefined, employer: form.employer.trim() || undefined, industry: form.industry.trim() || undefined,
      startDate: form.startDate, endDate: form.current ? null : form.endDate || null, current: form.current, duties: form.duties.trim() || undefined,
      weeklyHours: form.weeklyHours ? Number(form.weeklyHours) : null, hazards: parseHazards(form.hazards),
    };
    const ok = await action.run(async () => {
      if (editing === 'new') await api.addJob(employeeId, body);
      else if (editing) await api.updateJob(editing.id, body);
      return 'Job history saved.';
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <div>
      <h2>Job and exposure history (ODH)</h2>
      <p className="small muted">The current job comes from HRIS with exposures from the job catalogue hazards; earlier jobs can be added manually.</p>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No job history.</Empty>}
      {rows.length > 0 && (
        <ul className="card-list">
          {rows.map((j) => (
            <li key={j.id} className="card">
              <strong>{j.jobTitle}</strong> {j.jobCode && <span className="mono small">{j.jobCode}</span>} {j.current && <span className="badge badge-fit">Current</span>}{' '}
              <span className="badge badge-muted">{j.source === 'HRIS' ? 'From HRIS' : 'Manual'}</span>
              <div className="small">
                {[j.employer, j.industry].filter(Boolean).join(' · ')} · {formatDay(j.startDate)} – {j.current ? 'present' : formatDay(j.endDate)}
                {j.weeklyHours ? ` · ${j.weeklyHours} h/week` : ''}
              </div>
              {j.duties && <div className="small prewrap">{j.duties}</div>}
              {j.hazards.length > 0 && (
                <ul className="chip-list" aria-label={`Exposures for ${j.jobTitle}`}>
                  {j.hazards.map((h) => (
                    <li key={h.code} className="chip">
                      {h.name}
                      {h.category ? <span className="small muted"> ({humanise(h.category)})</span> : null}
                    </li>
                  ))}
                </ul>
              )}
              {canWrite && (
                <button type="button" className="secondary small-button" onClick={() => open(j)} aria-label={`Correct ${j.jobTitle}`}>
                  Correct
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canWrite && !editing && (
        <button type="button" className="secondary" onClick={() => open('new')}>
          Add job
        </button>
      )}
      {canWrite && editing && (
        <div className="tile">
          <h3>{editing === 'new' ? 'Add job (manual)' : 'Correct job entry'}</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <div className="form-grid">
              <IdField id="job-title" label="Job title" required error={errorFor(errors, 'job-title')}>
                {(d) => <input id="job-title" maxLength={200} value={form.jobTitle} aria-describedby={d} aria-invalid={!!errorFor(errors, 'job-title')} onChange={(e) => setForm({ ...form, jobTitle: e.target.value })} />}
              </IdField>
              <IdField id="job-code" label="Job code">
                {() => <input id="job-code" maxLength={40} value={form.jobCode} onChange={(e) => setForm({ ...form, jobCode: e.target.value })} />}
              </IdField>
              <IdField id="job-employer" label="Employer">
                {() => <input id="job-employer" maxLength={200} value={form.employer} onChange={(e) => setForm({ ...form, employer: e.target.value })} />}
              </IdField>
              <IdField id="job-industry" label="Industry">
                {() => <input id="job-industry" maxLength={200} value={form.industry} onChange={(e) => setForm({ ...form, industry: e.target.value })} />}
              </IdField>
              <IdField id="job-start" label="Start date" required error={errorFor(errors, 'job-start')}>
                {(d) => <input id="job-start" type="date" value={form.startDate} aria-describedby={d} aria-invalid={!!errorFor(errors, 'job-start')} onChange={(e) => setForm({ ...form, startDate: e.target.value })} />}
              </IdField>
              <IdField id="job-end" label="End date" error={errorFor(errors, 'job-end')}>
                {(d) => <input id="job-end" type="date" disabled={form.current} value={form.endDate} aria-describedby={d} onChange={(e) => setForm({ ...form, endDate: e.target.value })} />}
              </IdField>
              <IdField id="job-hours" label="Weekly hours">
                {() => <input id="job-hours" type="number" min={0} max={100} value={form.weeklyHours} onChange={(e) => setForm({ ...form, weeklyHours: e.target.value })} />}
              </IdField>
            </div>
            <label className="checkbox">
              <input type="checkbox" checked={form.current} onChange={(e) => setForm({ ...form, current: e.target.checked })} /> Current job
            </label>
            <IdField id="job-duties" label="Usual duties">
              {() => <textarea id="job-duties" rows={2} maxLength={1000} value={form.duties} onChange={(e) => setForm({ ...form, duties: e.target.value })} />}
            </IdField>
            <IdField id="job-hazards" label="Exposures / hazards" hint="Comma-separated hazard codes, optionally CODE:Name – e.g. NOISE, DUST:Wood dust">
              {(d) => <input id="job-hazards" value={form.hazards} aria-describedby={d} onChange={(e) => setForm({ ...form, hazards: e.target.value })} />}
            </IdField>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save job</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Record timeline
export function RecordTimelineTab({ client, onSessionExpired, employeeId }: ChartTabProps) {
  const tl = useAsync(() => recordApi(client).timeline(employeeId), onSessionExpired);
  return (
    <div>
      <h2>Record timeline</h2>
      {tl.loading && <Loading />}
      {!!tl.error && <ErrorAlert error={tl.error} />}
      {tl.data && tl.data.length === 0 && <Empty>No entries.</Empty>}
      <ol className="timeline">
        {(tl.data ?? []).map((i, idx) => (
          <li key={`${i.kind}-${i.ref}-${idx}`}>
            <time dateTime={i.at} className="small muted">{formatDate(i.at)}</time>
            <div>
              <span className={`badge kind-${i.kind.toLowerCase()}`}>{humanise(i.kind)}</span>{' '}
              {i.kind === 'ENCOUNTER' ? <a href={`#/encounters/${encodeURIComponent(i.ref)}`}>{i.title}</a> : i.kind === 'CASE' ? <a href={`#/cases/${encodeURIComponent(i.ref)}`}>{i.title}</a> : i.title}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
