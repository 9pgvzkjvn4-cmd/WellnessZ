import { useState, type FormEvent } from 'react';
import { codingText, MEDICATION_SOURCES, MEDICATION_STATUSES, recordApi, type CdsAlert, type Coding, type Medication, type MedicationInput } from '../../api/record';
import { CdsOverridePanel } from '../../components/CdsOverride';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { formatDay, humanise } from '../../components/format';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { ActionFeedback, Empty, ErrorAlert, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { cdsAlertsOf, missingOverrideReasons, overrideFieldId, toOverrides } from '../../domain/cds';
import type { ChartTabProps } from './chartTabProps';

interface MedForm {
  name: string;
  code?: Coding;
  dose: string;
  route: string;
  frequency: string;
  source: MedicationInput['source'];
  status: MedicationInput['status'];
  startDate: string;
  endDate: string;
}
const blank: MedForm = { name: '', dose: '', route: 'ORAL', frequency: '', source: 'REPORTED', status: 'ACTIVE', startDate: '', endDate: '' };
const ROUTES = ['ORAL', 'TOPICAL', 'INTRAMUSCULAR', 'SUBCUTANEOUS', 'INTRAVENOUS', 'INHALED', 'OTHER'];

/**
 * Medications with clinical decision support: a `409 cds_alert` shows the alerts and requires an override reason per
 * alert before the same entry is resubmitted with `overrides`.
 */
export function MedicationsTab({ client, onSessionExpired, employeeId, canWrite, refreshSummary }: ChartTabProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.medications(employeeId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<Medication | 'new'>();
  const [form, setForm] = useState<MedForm>(blank);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [alerts, setAlerts] = useState<CdsAlert[]>();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [showCode, setShowCode] = useState(false);

  function open(m: Medication | 'new') {
    setEditing(m);
    setErrors([]);
    setAlerts(undefined);
    setReasons({});
    action.reset();
    setForm(
      m === 'new'
        ? blank
        : { name: m.name, code: m.code ?? undefined, dose: m.dose ?? '', route: m.route ?? '', frequency: m.frequency ?? '', source: m.source, status: m.status, startDate: m.startDate ?? '', endDate: m.endDate ?? '' },
    );
  }

  function update(patch: Partial<MedForm>) {
    setForm({ ...form, ...patch });
    // Any change to the entry means the previous alerts no longer apply: the server re-checks on submit.
    if (alerts) {
      setAlerts(undefined);
      setReasons({});
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!form.name.trim()) errs.push({ field: 'med-name', message: 'Enter the medication name.' });
    if (form.endDate && form.startDate && form.endDate < form.startDate) errs.push({ field: 'med-end', message: 'End date must be after the start date.' });
    if (alerts) for (const code of missingOverrideReasons(alerts, reasons)) errs.push({ field: overrideFieldId(code), message: `Give an override reason for ${humanise(code)}.` });
    setErrors(errs);
    if (errs.length) return;
    const body: MedicationInput = {
      name: form.name.trim(),
      ...(form.code ? { code: form.code } : {}),
      dose: form.dose.trim() || undefined,
      route: form.route || undefined,
      frequency: form.frequency.trim() || undefined,
      source: form.source,
      status: form.status,
      startDate: form.startDate || null,
      endDate: form.endDate || null,
      ...(alerts ? { overrides: toOverrides(alerts, reasons) } : {}),
    };
    let blocked: CdsAlert[] | undefined;
    const ok = await action.run(async () => {
      try {
        if (editing === 'new') await api.addMedication(employeeId, body);
        else if (editing) await api.updateMedication(editing.id, body);
      } catch (err) {
        blocked = cdsAlertsOf(err);
        if (blocked) return;
        throw err;
      }
      return alerts ? 'Medication recorded with documented overrides.' : 'Medication recorded.';
    });
    if (blocked) {
      setAlerts(blocked);
      setReasons({});
      return;
    }
    if (ok) {
      setEditing(undefined);
      setAlerts(undefined);
      list.reload();
      refreshSummary();
    }
  }

  const rows = list.data ?? [];
  return (
    <div>
      <h2>Medications</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No medications recorded.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Medications</caption>
            <thead>
              <tr>
                <th scope="col">Medication</th>
                <th scope="col">Dose / route / frequency</th>
                <th scope="col">Source</th>
                <th scope="col">Status</th>
                <th scope="col">Dates</th>
                {canWrite && <th scope="col"><span className="visually-hidden">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id}>
                  <th scope="row">
                    {m.name}
                    {m.code && <div className="small muted">{codingText(m.code)}</div>}
                    {(m.overrides ?? []).length > 0 && <div className="small">Alerts overridden: {(m.overrides ?? []).map((o) => humanise(o.alertCode)).join(', ')}</div>}
                  </th>
                  <td>{[m.dose, m.route ? humanise(m.route) : undefined, m.frequency].filter(Boolean).join(' · ') || '–'}</td>
                  <td>{humanise(m.source)}</td>
                  <td><StatusBadge status={m.status} /></td>
                  <td>{formatDay(m.startDate)}{m.endDate ? ` – ${formatDay(m.endDate)}` : ''}</td>
                  {canWrite && (
                    <td>
                      <button type="button" className="secondary small-button" onClick={() => open(m)} aria-label={`Edit ${m.name}`}>
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
          Add medication
        </button>
      )}
      {canWrite && editing && (
        <div className="tile">
          <h3>{editing === 'new' ? 'Add medication' : 'Edit medication'}</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <div className="form-grid">
              <IdField id="med-name" label="Medication" required error={errorFor(errors, 'med-name')}>
                {(d) => <input id="med-name" maxLength={200} value={form.name} aria-describedby={d} aria-invalid={!!errorFor(errors, 'med-name')} onChange={(e) => update({ name: e.target.value })} />}
              </IdField>
              <IdField id="med-dose" label="Dose">
                {() => <input id="med-dose" maxLength={60} value={form.dose} onChange={(e) => update({ dose: e.target.value })} />}
              </IdField>
              <IdField id="med-route" label="Route">
                {() => (
                  <select id="med-route" value={form.route} onChange={(e) => update({ route: e.target.value })}>
                    <SelectOptions values={ROUTES} empty="–" />
                  </select>
                )}
              </IdField>
              <IdField id="med-frequency" label="Frequency">
                {() => <input id="med-frequency" maxLength={60} value={form.frequency} onChange={(e) => update({ frequency: e.target.value })} />}
              </IdField>
              <IdField id="med-source" label="Source" hint="Administered = given in clinic">
                {(d) => (
                  <select id="med-source" value={form.source} aria-describedby={d} onChange={(e) => update({ source: e.target.value as MedForm['source'] })}>
                    <SelectOptions values={MEDICATION_SOURCES} />
                  </select>
                )}
              </IdField>
              <IdField id="med-status" label="Status">
                {() => (
                  <select id="med-status" value={form.status} onChange={(e) => update({ status: e.target.value as MedForm['status'] })}>
                    <SelectOptions values={MEDICATION_STATUSES} />
                  </select>
                )}
              </IdField>
              <IdField id="med-start" label="Start date">
                {() => <input id="med-start" type="date" value={form.startDate} onChange={(e) => update({ startDate: e.target.value })} />}
              </IdField>
              <IdField id="med-end" label="End date" error={errorFor(errors, 'med-end')}>
                {(d) => <input id="med-end" type="date" value={form.endDate} aria-describedby={d} onChange={(e) => update({ endDate: e.target.value })} />}
              </IdField>
            </div>
            {form.code && <p className="small">Coded as {codingText(form.code)}</p>}
            <button type="button" className="link-button" aria-expanded={showCode} onClick={() => setShowCode(!showCode)}>
              {showCode ? 'Hide coding' : 'Add a code (optional)'}
            </button>
            {showCode && <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label="Medication code" system="SNOMED_CT" onSelect={(c) => update({ code: c, name: form.name || c.display })} />}
            {alerts && <CdsOverridePanel alerts={alerts} reasons={reasons} onChange={setReasons} errors={errors} />}
            <div className="actions">
              <button type="submit" disabled={action.busy}>
                {alerts ? 'Save with overrides' : 'Save medication'}
              </button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
