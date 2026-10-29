import { useState, type FormEvent } from 'react';
import { PANEL_TEST_PREFIXES, PANEL_TESTS, surveillanceApi, type Programme, type SimulationResult } from '../../api/surveillance';
import { can, OH_ROLES } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { ELIGIBILITY_KEYS, formToProgramme, isValidPanelTest, programmeToForm, progFieldId, validateProgramme, type ProgrammeForm } from '../../domain/surveillance';
import { navigate } from '../../routing/useHashRoute';
import type { ScreenProps } from '../types';

const ELIGIBILITY_LABELS: Record<(typeof ELIGIBILITY_KEYS)[number], string> = {
  jobCodes: 'Job codes',
  departmentCodes: 'Department codes',
  subsidiaryCodes: 'Subsidiary codes',
  hazardCodes: 'Hazard codes',
  exposureGroups: 'Exposure groups (SEG codes)',
  licenceTypes: 'Licence types',
};

const eligibilitySummary = (p: Programme) =>
  [
    ...ELIGIBILITY_KEYS.flatMap((k) => (p.eligibility?.[k]?.length ? [`${ELIGIBILITY_LABELS[k].replace(/ \(.*\)/, '')}: ${p.eligibility[k].join(', ')}`] : [])),
    p.eligibility?.minAge ? `age ≥ ${p.eligibility.minAge}` : '',
  ]
    .filter(Boolean)
    .join(' · ');

/** `#/surveillance/programmes` – surveillance programmes & protocols (E07-F01). */
export function ProgrammeList({ client, session, onSessionExpired }: ScreenProps) {
  const api = surveillanceApi(client);
  const list = useAsync(() => api.programmes(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const rows = [...(list.data ?? [])].sort((a, b) => a.title.localeCompare(b.title));

  async function recalc() {
    await action.run(async () => {
      const r = await api.recalculate();
      return `Due list recalculated: ${r.enrolled} enrolled, ${r.updated} updated, ${r.removed} removed.`;
    });
  }

  return (
    <section>
      <PageTitle sub="Eligibility (job, department, subsidiary, hazard, exposure group, licence, age) decides who is enrolled; the panel and frequency drive the due list.">Surveillance programmes</PageTitle>
      <div className="toolbar">
        <div className="toolbar-left">
          {can(session.roles, 'programmeWrite') && <a className="button" href="#/surveillance/programmes/new">New programme</a>}
          {can(session.roles, 'dueRecalculate') && <button type="button" className="secondary" disabled={action.busy} onClick={() => void recalc()}>Recalculate due list now</button>}
        </div>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No programmes.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} programme{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Programme</th>
                <th scope="col">Eligibility</th>
                <th scope="col">Panel</th>
                <th scope="col">Frequency</th>
                <th scope="col" className="num">Eligible</th>
                <th scope="col">Version</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.code}>
                  <th scope="row">
                    <a href={`#/surveillance/programmes/${encodeURIComponent(p.code)}`}>{p.title}</a> <span className="small muted mono">{p.code}</span>
                  </th>
                  <td className="small">{eligibilitySummary(p) || '–'}</td>
                  <td className="small">{p.panel.map((x) => humanise(x.test.split(':')[0]) + (x.test.includes(':') ? ` ${x.test.split(':')[1]}` : '')).join(', ')}</td>
                  <td className="small">{p.frequencyMonths} months{p.frequencyMonthsOver40 ? ` (${p.frequencyMonthsOver40} over 40)` : ''}</td>
                  <td className="num">{p.eligibleHeadcount ?? '–'}</td>
                  <td>v{p.version}</td>
                  <td><StatusBadge status={p.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function PanelEditor({ form, setForm, errFor, disabled }: { form: ProgrammeForm; setForm: (f: ProgrammeForm) => void; errFor: (k: string) => string | undefined; disabled: boolean }) {
  const [kind, setKind] = useState<string>('AUDIOMETRY');
  const [param, setParam] = useState('');
  const [standard, setStandard] = useState('');
  const parameterised = (PANEL_TEST_PREFIXES as readonly string[]).includes(kind);
  const test = parameterised ? `${kind}:${param.trim()}` : kind;
  const canAdd = isValidPanelTest(test) && !form.panel.some((p) => p.test === test);

  function add() {
    if (!canAdd) return;
    setForm({ ...form, panel: [...form.panel, { test, ...(standard.trim() ? { standard: standard.trim() } : {}) }] });
    setParam('');
    setStandard('');
  }

  return (
    <fieldset>
      <legend>Panel</legend>
      {form.panel.length === 0 ? (
        <p className="small muted">No tests yet.</p>
      ) : (
        <ul className="plain-list" aria-label="Panel tests">
          {form.panel.map((p, i) => (
            <li key={p.test} id={progFieldId(`panel-${i}`)} tabIndex={-1}>
              <span className="mono">{p.test}</span>
              {p.standard && <span className="small muted"> · standard {p.standard}</span>}
              {errFor(`panel-${i}`) && <span className="field-error-text small"> {errFor(`panel-${i}`)}</span>}{' '}
              {!disabled && (
                <button type="button" className="link-button danger" onClick={() => setForm({ ...form, panel: form.panel.filter((_, j) => j !== i) })} aria-label={`Remove ${p.test}`}>
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {!disabled && (
        <div className="inline-form">
          <IdField id={progFieldId('panel-new')} label="Test" error={errFor('panel-new')}>
            {(db) => (
              <select id={progFieldId('panel-new')} aria-describedby={db} value={kind} onChange={(e) => setKind(e.target.value)}>
                <SelectOptions values={[...PANEL_TESTS, ...PANEL_TEST_PREFIXES]} />
              </select>
            )}
          </IdField>
          {parameterised && (
            <Field label={kind === 'LAB' ? 'LOINC code' : kind === 'IMMUNISATION' ? 'Vaccine code' : 'Biomonitoring code'} required>
              {(id) => (
                <input
                  id={id}
                  maxLength={30}
                  value={param}
                  onChange={(e) => setParam(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      add();
                    }
                  }}
                />
              )}
            </Field>
          )}
          <Field label="Standard (optional)">{(id) => <input id={id} maxLength={40} value={standard} onChange={(e) => setStandard(e.target.value)} />}</Field>
          <button type="button" className="secondary" disabled={!canAdd} onClick={add}>Add test</button>
        </div>
      )}
    </fieldset>
  );
}

function SimulationView({ sim }: { sim: SimulationResult }) {
  return (
    <section aria-live="polite" aria-labelledby="sim-title" className="tile">
      <h2 id="sim-title">Population impact</h2>
      <div className="tiles">
        <div className="tile"><span className="tile-value">{sim.eligibleHeadcount}</span> eligible</div>
        <div className="tile"><span className="tile-value">{sim.newlyEnrolled}</span> newly enrolled</div>
        <div className="tile"><span className={`tile-value${sim.wouldBeOverdue > 0 ? ' text-red' : ''}`}>{sim.wouldBeOverdue}</span> would be overdue</div>
      </div>
      {sim.byUnit.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Eligible headcount by unit</caption>
            <thead>
              <tr>
                <th scope="col">Subsidiary</th>
                <th scope="col">Department</th>
                <th scope="col" className="num">Employees</th>
              </tr>
            </thead>
            <tbody>
              {sim.byUnit.map((u) => (
                <tr key={`${u.subsidiaryCode}-${u.departmentCode}`}>
                  <th scope="row">{u.subsidiaryCode}</th>
                  <td>{u.departmentCode}</td>
                  <td className="num">{u.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="small muted">Simulation only – nothing was enrolled.</p>
    </section>
  );
}

/** `#/surveillance/programmes/{code}` (or `/new`) – programme builder: eligibility, panel, frequency, simulate, activate, versions. */
export function ProgrammeBuilder({ client, session, onSessionExpired, code }: ScreenProps & { code: string }) {
  const api = surveillanceApi(client);
  const isNew = code === 'new';
  const detail = useAsync(() => (isNew ? Promise.resolve(undefined) : api.programme(code)), onSessionExpired, [code]);
  const p = detail.data;
  return (
    <section>
      <p className="breadcrumb small">
        <a href="#/surveillance/programmes">Surveillance programmes</a> <span aria-hidden="true">›</span> {isNew ? 'New' : code}
      </p>
      <PageTitle sub={p ? `Version ${p.version} · ${humanise(p.status)}${p.effectiveFrom ? ` · effective ${formatDay(p.effectiveFrom)}` : ''}` : undefined}>{isNew ? 'New programme' : (p?.title ?? code)}</PageTitle>
      {detail.loading && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {(isNew || p) && <BuilderForm key={p ? `${p.code}-${p.version}` : 'new'} client={client} session={session} onSessionExpired={onSessionExpired} programme={p} reload={detail.reload} />}
    </section>
  );
}

function BuilderForm({ client, session, onSessionExpired, programme, reload }: ScreenProps & { programme?: Programme & { versions?: { version: number; status: string; effectiveFrom?: string | null; approvedBy?: string | null; approvedAt?: string | null }[] }; reload: () => void }) {
  const api = surveillanceApi(client);
  const [form, setForm] = useState<ProgrammeForm>(() => programmeToForm(programme));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [sim, setSim] = useState<SimulationResult>();
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const action = useAction(onSessionExpired);
  const write = can(session.roles, 'programmeWrite');
  const errFor = (k: string) => errors.find((e) => e.field === progFieldId(k))?.message;
  const isNew = !programme;

  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = validateProgramme(form);
    setErrors(errs);
    if (errs.length) return;
    const body = formToProgramme(form);
    const ok = await action.run(async () => {
      if (programme) await api.updateProgramme(programme.code, body);
      else await api.createProgramme(body);
      return isNew ? `${body.code} created as a draft.` : `New draft version of ${body.code} saved.`;
    });
    if (ok) {
      if (isNew) navigate(`/surveillance/programmes/${encodeURIComponent(body.code)}`);
      else reload();
    }
  }

  async function simulate() {
    if (!programme) return;
    setSim(undefined);
    await action.run(async () => setSim(await api.simulate(programme.code, programme.version)));
  }

  async function activate() {
    if (!programme) return;
    const ok = await action.run(async () => {
      await api.activate(programme.code, effectiveFrom || undefined);
      return `${programme.code} activated – enrolment is being recalculated.`;
    });
    if (ok) reload();
  }

  async function retire() {
    if (!programme) return;
    const ok = await action.run(async () => {
      await api.retire(programme.code);
      return `${programme.code} retired.`;
    });
    if (ok) reload();
  }

  const listField = (k: (typeof ELIGIBILITY_KEYS)[number]) => (
    <IdField key={k} id={progFieldId(k)} label={ELIGIBILITY_LABELS[k]} hint="Comma-separated codes" error={errFor(k)}>
      {(db) => <input id={progFieldId(k)} aria-describedby={db} aria-invalid={!!errFor(k)} value={form.eligibility[k]} onChange={(e) => setForm({ ...form, eligibility: { ...form.eligibility, [k]: e.target.value } })} />}
    </IdField>
  );
  const text = (k: 'title' | 'standardCode' | 'serviceCode' | 'regulatoryBasis' | 'frequencyMonths' | 'frequencyMonthsOver40', label: string, opts: { required?: boolean; number?: boolean; hint?: string } = {}) => (
    <IdField id={progFieldId(k)} label={label} required={opts.required} hint={opts.hint} error={errFor(k)}>
      {(db) => (
        <input
          id={progFieldId(k)}
          type={opts.number ? 'number' : 'text'}
          min={opts.number ? 1 : undefined}
          max={opts.number ? 120 : undefined}
          aria-describedby={db}
          aria-invalid={!!errFor(k)}
          value={form[k]}
          onChange={(e) => setForm({ ...form, [k]: e.target.value })}
        />
      )}
    </IdField>
  );

  return (
    <>
      <ErrorSummary errors={errors} />
      <form className="form config-form" onSubmit={save}>
        <fieldset disabled={!write}>
          <legend className="visually-hidden">Programme</legend>
          <div className="inline-form">
            <IdField id={progFieldId('code')} label="Code" required error={errFor('code')}>
              {(db) => <input id={progFieldId('code')} readOnly={!isNew} maxLength={40} aria-describedby={db} aria-invalid={!!errFor('code')} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />}
            </IdField>
            {text('title', 'Title', { required: true })}
          </div>
          <fieldset>
            <legend>Eligibility</legend>
            <p className="small muted">An employee is eligible when they match any listed code in each filled criterion.</p>
            <div className="form-grid">
              {ELIGIBILITY_KEYS.map(listField)}
              <IdField id={progFieldId('minAge')} label="Minimum age" error={errFor('minAge')}>
                {(db) => <input id={progFieldId('minAge')} type="number" min={16} max={80} aria-describedby={db} value={form.eligibility.minAge} onChange={(e) => setForm({ ...form, eligibility: { ...form.eligibility, minAge: e.target.value } })} />}
              </IdField>
            </div>
          </fieldset>
          <PanelEditor form={form} setForm={setForm} errFor={errFor} disabled={!write} />
          <fieldset>
            <legend>Frequency & ownership</legend>
            <div className="form-grid">
              {text('frequencyMonths', 'Frequency (months)', { required: true, number: true })}
              {text('frequencyMonthsOver40', 'Frequency over 40 (months)', { number: true })}
              {text('standardCode', 'Fitness standard', { hint: 'e.g. CAA_CLASS1' })}
              <IdField id={progFieldId('responsibleRole')} label="Responsible role" required error={errFor('responsibleRole')}>
                {(db) => (
                  <select id={progFieldId('responsibleRole')} aria-describedby={db} value={form.responsibleRole} onChange={(e) => setForm({ ...form, responsibleRole: e.target.value })}>
                    <SelectOptions values={OH_ROLES.filter((r) => r !== 'CASE_MANAGER')} />
                  </select>
                )}
              </IdField>
              {text('serviceCode', 'Appointment service code', { required: true, hint: 'Appointment type used for bookings, e.g. AUDIOMETRY' })}
              {text('regulatoryBasis', 'Regulatory basis')}
            </div>
          </fieldset>
        </fieldset>
        {write && (
          <div className="actions">
            <button type="submit" disabled={action.busy}>{isNew ? 'Create draft' : 'Save as new draft version'}</button>
          </div>
        )}
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {programme && (
        <>
          <div className="toolbar">
            <div className="toolbar-left">
              {write && <button type="button" className="secondary" disabled={action.busy} onClick={() => void simulate()}>Simulate population impact</button>}
              {can(session.roles, 'programmeActivate') && programme.status === 'DRAFT' && (
                <span className="inline-form">
                  <Field label="Effective from">{(id) => <input id={id} type="date" value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} />}</Field>
                  <button type="button" disabled={action.busy} onClick={() => void activate()}>Activate version {programme.version}</button>
                </span>
              )}
              {can(session.roles, 'programmeRetire') && programme.status === 'ACTIVE' && (
                <button type="button" className="danger-button" disabled={action.busy} onClick={() => void retire()}>Retire programme</button>
              )}
            </div>
          </div>
          {sim && <SimulationView sim={sim} />}
          <h2>Versions</h2>
          {(programme.versions ?? []).length === 0 ? (
            <Empty>No version history.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Versions</caption>
                <thead>
                  <tr>
                    <th scope="col">Version</th>
                    <th scope="col">Status</th>
                    <th scope="col">Effective from</th>
                    <th scope="col">Approved</th>
                  </tr>
                </thead>
                <tbody>
                  {[...(programme.versions ?? [])].sort((a, b) => b.version - a.version).map((v) => (
                    <tr key={v.version}>
                      <th scope="row">v{v.version}</th>
                      <td><StatusBadge status={v.status} /></td>
                      <td>{formatDay(v.effectiveFrom)}</td>
                      <td className="small">{v.approvedBy ? `${v.approvedBy} · ${formatDate(v.approvedAt)}` : '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  );
}
