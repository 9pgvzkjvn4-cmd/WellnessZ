import { useState, type FormEvent } from 'react';
import { absenceApi, RULE_ACTIONS, RULE_TYPES, type Rule, type RuleTestResult, type RuleType } from '../../api/absence';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { changeRuleType, describeParams, emptyRuleForm, paramsToForm, RULE_PARAM_SPEC, RULE_PATTERNS, ruleFieldId, toRuleBody, validateRuleForm, type RuleForm } from '../../domain/absence';
import type { ScreenProps } from '../types';

function TestResults({ result }: { result: RuleTestResult }) {
  return (
    <section aria-live="polite" aria-labelledby="rule-test-title">
      <h3 id="rule-test-title">Test mode: {result.count} employee{result.count === 1 ? '' : 's'} would match</h3>
      <p className="small muted">Nothing was created – no queue items or cases.</p>
      {result.matches.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Matching employees</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Subsidiary</th>
                <th scope="col">Detail</th>
              </tr>
            </thead>
            <tbody>
              {result.matches.map((m) => (
                <tr key={m.employeeId}>
                  <th scope="row">{m.displayName} <span className="small muted">{m.employeeId}</span></th>
                  <td>{m.subsidiaryCode ?? '–'}</td>
                  <td className="small">{m.detail ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Rule builder: type → parameter fields (contract §3.2); test mode runs the unsaved rule without creating anything. */
export function RuleEditor({ client, onSessionExpired, rule, onSaved, onCancel }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { rule?: Rule; onSaved: () => void; onCancel: () => void }) {
  const api = absenceApi(client);
  const [form, setForm] = useState<RuleForm>(() =>
    rule ? { code: rule.code, name: rule.name, subsidiaryCode: rule.subsidiaryCode ?? '', ruleType: rule.ruleType, action: rule.action, active: rule.active, params: paramsToForm(rule.ruleType, rule.params) } : emptyRuleForm(),
  );
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [test, setTest] = useState<RuleTestResult>();
  const action = useAction(onSessionExpired);
  const err = (k: string) => errors.find((e) => e.field === ruleFieldId(k))?.message;

  function check(): boolean {
    const e = validateRuleForm(form);
    setErrors(e);
    return e.length === 0;
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!check()) return;
    const body = toRuleBody(form);
    const ok = await action.run(async () => {
      if (rule) await api.updateRule(rule.id, body);
      else await api.createRule(body);
      return rule ? `${body.code} saved as version ${rule.version + 1}.` : `${body.code} created.`;
    });
    if (ok) onSaved();
  }

  async function runTest() {
    if (!check()) return;
    const body = toRuleBody(form);
    setTest(undefined);
    await action.run(async () => {
      setTest(await api.testRule({ rule: body, ...(body.subsidiaryCode ? { subsidiaryCode: body.subsidiaryCode } : {}) }));
    });
  }

  return (
    <section className="tile" aria-labelledby="rule-editor-title">
      <h2 id="rule-editor-title">{rule ? `Edit ${rule.code} (creates version ${rule.version + 1})` : 'New trigger rule'}</h2>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={save}>
        <div className="inline-form">
          <IdField id={ruleFieldId('code')} label="Code" required error={err('code')}>
            {(db) => <input id={ruleFieldId('code')} maxLength={40} readOnly={!!rule} aria-describedby={db} aria-invalid={!!err('code')} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />}
          </IdField>
          <IdField id={ruleFieldId('name')} label="Name" required error={err('name')}>
            {(db) => <input id={ruleFieldId('name')} maxLength={120} aria-describedby={db} aria-invalid={!!err('name')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />}
          </IdField>
          <IdField id={ruleFieldId('subsidiaryCode')} label="Subsidiary" hint="Empty = default rule; a subsidiary rule overrides the default of the same type">
            {(db) => <input id={ruleFieldId('subsidiaryCode')} maxLength={20} aria-describedby={db} value={form.subsidiaryCode} onChange={(e) => setForm({ ...form, subsidiaryCode: e.target.value.toUpperCase() })} />}
          </IdField>
        </div>
        <div className="inline-form">
          <IdField id={ruleFieldId('ruleType')} label="Rule type" required>
            {() => (
              <select id={ruleFieldId('ruleType')} value={form.ruleType} onChange={(e) => { setTest(undefined); setForm(changeRuleType(form, e.target.value as RuleType)); }}>
                <SelectOptions values={RULE_TYPES} />
              </select>
            )}
          </IdField>
          <IdField id={ruleFieldId('action')} label="Action" required>
            {() => (
              <select id={ruleFieldId('action')} value={form.action} onChange={(e) => setForm({ ...form, action: e.target.value as RuleForm['action'] })}>
                <SelectOptions values={RULE_ACTIONS} />
              </select>
            )}
          </IdField>
        </div>
        <fieldset>
          <legend>Parameters – {humanise(form.ruleType)}</legend>
          <div className="inline-form">
            {RULE_PARAM_SPEC[form.ruleType].map((s) => (
              <IdField key={s.key} id={ruleFieldId(s.key)} label={s.label} required hint={s.hint} error={err(s.key)}>
                {(db) =>
                  s.kind === 'pattern' ? (
                    <select id={ruleFieldId(s.key)} aria-describedby={db} value={form.params[s.key] ?? ''} onChange={(e) => setForm({ ...form, params: { ...form.params, [s.key]: e.target.value } })}>
                      <SelectOptions values={RULE_PATTERNS} empty="Choose…" />
                    </select>
                  ) : (
                    <input
                      id={ruleFieldId(s.key)}
                      type="number"
                      inputMode="numeric"
                      min={s.min}
                      max={s.max}
                      step={1}
                      aria-describedby={db}
                      aria-invalid={!!err(s.key)}
                      value={form.params[s.key] ?? ''}
                      onChange={(e) => setForm({ ...form, params: { ...form.params, [s.key]: e.target.value } })}
                    />
                  )
                }
              </IdField>
            ))}
          </div>
        </fieldset>
        <label className="inline-row">
          <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Active
        </label>
        <div className="actions">
          <button type="submit" disabled={action.busy}>{rule ? 'Save new version' : 'Create rule'}</button>
          <button type="button" className="secondary" disabled={action.busy} onClick={() => void runTest()}>Test rule</button>
          <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {test && <TestResults result={test} />}
    </section>
  );
}

/** `#/absence/rules` – trigger rules (E06-F02): list, builder per rule type, versions (audit), test mode. */
export function AbsenceRules({ client, session, onSessionExpired }: ScreenProps) {
  const api = absenceApi(client);
  const list = useAsync(() => api.rules(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<Rule | 'new'>();
  const [test, setTest] = useState<{ code: string; result: RuleTestResult }>();
  const write = can(session.roles, 'absenceRulesWrite');

  async function deactivate(r: Rule) {
    const ok = await action.run(async () => {
      await api.deactivateRule(r.id);
      return `${r.code} deactivated.`;
    });
    if (ok) list.reload();
  }

  async function testSaved(r: Rule) {
    setTest(undefined);
    await action.run(async () => setTest({ code: r.code, result: await api.testRule({ ruleId: r.id, ...(r.subsidiaryCode ? { subsidiaryCode: r.subsidiaryCode } : {}) }) }));
  }

  const rows = [...(list.data ?? [])].sort((a, b) => a.ruleType.localeCompare(b.ruleType) || a.code.localeCompare(b.code));
  return (
    <section>
      <PageTitle sub="Evaluated on every ingested or updated episode and nightly. Subsidiary rules override the default of the same type. Every change creates a new audited version.">
        Absence trigger rules
      </PageTitle>
      {write && !editing && (
        <button type="button" onClick={() => { action.reset(); setEditing('new'); }}>New rule</button>
      )}
      {editing && (
        <RuleEditor
          key={editing === 'new' ? 'new' : editing.id}
          client={client}
          onSessionExpired={onSessionExpired}
          rule={editing === 'new' ? undefined : editing}
          onCancel={() => setEditing(undefined)}
          onSaved={() => { setEditing(undefined); list.reload(); }}
        />
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No rules.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} rule{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Name</th>
                <th scope="col">Type</th>
                <th scope="col">Parameters</th>
                <th scope="col">Action</th>
                <th scope="col">Subsidiary</th>
                <th scope="col" className="num">Version</th>
                <th scope="col">Updated</th>
                <th scope="col">Status</th>
                {write && <th scope="col"><span className="visually-hidden">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row" className="mono">{r.code}</th>
                  <td>{r.name}</td>
                  <td>{humanise(r.ruleType)}</td>
                  <td className="small">{describeParams(r.ruleType, r.params)}</td>
                  <td>{humanise(r.action)}</td>
                  <td>{r.subsidiaryCode ?? <span className="muted">Default</span>}</td>
                  <td className="num">v{r.version}</td>
                  <td className="small">{r.updatedBy ?? '–'} {formatDate(r.updatedAt)}</td>
                  <td><StatusBadge status={r.active ? 'ACTIVE' : 'INACTIVE'} /></td>
                  {write && (
                    <td>
                      <div className="card-actions">
                        <button type="button" className="secondary small-button" onClick={() => { action.reset(); setEditing(r); }} aria-label={`Edit ${r.code}`}>Edit</button>
                        <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void testSaved(r)} aria-label={`Test ${r.code}`}>Test</button>
                        {r.active && <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void deactivate(r)} aria-label={`Deactivate ${r.code}`}>Deactivate</button>}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {test && (
        <div className="tile">
          <p className="small">Rule {test.code}</p>
          <TestResults result={test.result} />
        </div>
      )}
    </section>
  );
}
