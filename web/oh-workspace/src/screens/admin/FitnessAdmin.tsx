import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import { DEMAND_DIMENSIONS, platformApi, type JobDemandProfile } from '../../api/platform';
import { can } from '../../auth/roles';
import { ConfigTable, type ConfigRow, type FieldSpec } from '../../components/ConfigTable';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { parseJobDemands } from '../../domain/imports';
import type { ScreenProps } from '../types';

// ---------------------------------------------------------------- Fitness standards (versioned)
/** Body = FitnessStandard minus `version` (and the audit fields) – the server creates the next version. */
const SERVER_MANAGED = ['version', 'updatedAt', 'updatedBy'];
const STANDARD_FIELDS: FieldSpec[] = [
  { key: 'code', label: 'Code', kind: 'text', required: true },
  { key: 'title', label: 'Title', kind: 'text', required: true },
  { key: 'roleCategory', label: 'Role category', kind: 'text' },
  { key: 'source', label: 'Source', kind: 'text', column: false },
  { key: 'referenceUrl', label: 'Reference URL', kind: 'text', column: false },
  { key: 'validityMonths', label: 'Validity (months)', kind: 'number', nullable: true },
  { key: 'validityMonthsOver40', label: 'Validity over 40 (months)', kind: 'number', nullable: true },
  { key: 'requiresSignoffRole', label: 'Sign-off role', kind: 'select', options: ['AME', 'CMO'], nullable: true, hint: 'Empty = no sign-off' },
  { key: 'limitationCodes', label: 'Limitation codes', kind: 'list', hint: 'Comma-separated, e.g. TML, VDL' },
  { key: 'version', label: 'Version', kind: 'number', readOnly: true },
  { key: 'updatedBy', label: 'Updated by', kind: 'text', readOnly: true, column: false },
  { key: 'updatedAt', label: 'Updated', kind: 'text', readOnly: true },
];

/** `#/admin/fitness-standards` – role fitness standards; every save creates a new version (E05-BL-001). */
export function FitnessStandardsAdmin({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Standard, source and validity rules per role. Saving creates a new version; decisions keep the version they were made against.">Fitness standards</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Active fitness standards"
        fields={STANDARD_FIELDS}
        listPath="/masterdata/fitness-standards"
        updatePath={(r) => `/masterdata/fitness-standards/${seg(String(r.code))}`}
        rowKey={(r, i) => String(r.code ?? i)}
        canWrite={can(session.roles, 'fitnessStandardsAdmin')}
        mode="perRow"
        createMethod="PUT"
        toBody={(r: ConfigRow) => Object.fromEntries(Object.entries(r).filter(([k]) => !SERVER_MANAGED.includes(k)))}
        newRow={{ code: '', title: '', limitationCodes: [], requiresSignoffRole: null, status: 'ACTIVE' }}
      />
    </section>
  );
}

// ---------------------------------------------------------------- Restriction catalogue
const RESTRICTION_FIELDS: FieldSpec[] = [
  { key: 'code', label: 'Code', kind: 'text', required: true },
  { key: 'label', label: 'Label', kind: 'text', required: true, hint: 'Functional wording; {value} is replaced by the value' },
  { key: 'dimension', label: 'Job-demand dimension', kind: 'select', options: DEMAND_DIMENSIONS, required: true },
  { key: 'valueRequired', label: 'Value required', kind: 'bool' },
  { key: 'unit', label: 'Unit', kind: 'text', nullable: true },
  { key: 'active', label: 'Active', kind: 'bool' },
];

/** `#/admin/restrictions` – restriction catalogue (functional wording, no diagnosis). */
export function RestrictionCatalogueAdmin({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Restrictions are shown to managers and employees – use functional wording only, never a diagnosis.">Restriction catalogue</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Restriction catalogue"
        fields={RESTRICTION_FIELDS}
        listPath="/masterdata/restrictions"
        updatePath={(r) => `/masterdata/restrictions/${seg(String(r.code))}`}
        rowKey={(r, i) => String(r.code ?? i)}
        canWrite={can(session.roles, 'restrictionCatalogue')}
        mode="perRow"
        createMethod="PUT"
        newRow={{ code: '', label: '', dimension: 'LIFT_KG', valueRequired: false, unit: null, active: true }}
      />
    </section>
  );
}

// ---------------------------------------------------------------- Job-demand profiles
const demandSummary = (p: JobDemandProfile): string =>
  Object.entries(p.demands)
    .filter(([, v]) => v !== false && v !== 0 && v !== null && v !== undefined)
    .map(([k, v]) => (v === true ? humanise(k) : `${humanise(k)} ${String(v)}`))
    .join(', ');

/** `#/admin/job-demands` – job-demand profiles: edit one (JSON) or bulk import a JSON array (OH_ADMIN). */
export function JobDemandsAdmin({ client, session, onSessionExpired }: ScreenProps) {
  const api = platformApi(client);
  const [unit, setUnit] = useState('');
  const [applied, setApplied] = useState('');
  const list = useAsync(() => api.jobDemands(applied || undefined), onSessionExpired, [applied]);
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState<{ title: string; json: string }>();
  const [parseError, setParseError] = useState<string>();
  const [importText, setImportText] = useState('');
  const canEdit = can(session.roles, 'jobDemandsAdmin');
  const canImport = can(session.roles, 'jobDemandsImport');

  async function saveOne(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    const parsed = parseJobDemands(editing.json);
    if (!parsed.ok || parsed.items.length !== 1) {
      setParseError(parsed.ok ? 'Edit exactly one profile.' : parsed.error);
      return;
    }
    setParseError(undefined);
    const profile = parsed.items[0]!;
    const ok = await action.run(async () => {
      await api.saveJobDemand(profile);
      return `Profile ${profile.jobCode} saved.`;
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    }
  }

  async function runImport(e: FormEvent) {
    e.preventDefault();
    const parsed = parseJobDemands(importText);
    if (!parsed.ok) {
      setParseError(parsed.error);
      return;
    }
    setParseError(undefined);
    const ok = await action.run(async () => {
      const r = await api.importJobDemands(parsed.items);
      return `${r.upserted} profile${r.upserted === 1 ? '' : 's'} imported.`;
    });
    if (ok) {
      setImportText('');
      list.reload();
    }
  }

  const rows = list.data ?? [];
  const blank: JobDemandProfile = { jobCode: '', title: '', unit: '', demands: Object.fromEntries(DEMAND_DIMENSIONS.map((d) => [d, /KG|HOURS/.test(d) ? 0 : false])), sitting: 'LOW' };
  return (
    <section>
      <PageTitle sub="Physical, environmental, cognitive and safety-critical demands per job code, used to match restrictions to suitable duties.">Job-demand profiles</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(unit.trim()); }}>
        <Field label="Unit">{(id) => <input id={id} maxLength={20} value={unit} onChange={(e) => setUnit(e.target.value)} />}</Field>
        <button type="submit" className="secondary">Filter</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No profiles.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} job-demand profile{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Job</th>
                <th scope="col">Unit</th>
                <th scope="col">Demands</th>
                <th scope="col">Sitting</th>
                <th scope="col">Validated</th>
                {canEdit && <th scope="col"><span className="visually-hidden">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.jobCode}>
                  <th scope="row">{p.title} <span className="mono small">{p.jobCode}</span></th>
                  <td>{p.unit}</td>
                  <td className="small">{demandSummary(p) || '–'}</td>
                  <td>{humanise(p.sitting)}</td>
                  <td className="small">{p.validatedBy ? `${p.validatedBy} ${formatDate(p.validatedAt)}` : 'Not validated'}</td>
                  {canEdit && (
                    <td>
                      <button type="button" className="secondary small-button" onClick={() => { setParseError(undefined); setEditing({ title: `Edit ${p.jobCode}`, json: JSON.stringify(p, null, 2) }); }} aria-label={`Edit ${p.jobCode}`}>
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
      {canEdit && (
        <button type="button" className="secondary" onClick={() => { setParseError(undefined); setEditing({ title: 'New job-demand profile', json: JSON.stringify(blank, null, 2) }); }}>
          Add profile
        </button>
      )}
      {canImport && (
        <form className="form tile" onSubmit={runImport}>
          <h2>Bulk import (JSON)</h2>
          <p className="small muted">A JSON array of profiles – e.g. the validated “top 150 job codes”. Existing job codes are updated.</p>
          <div className="field">
            <label htmlFor="jd-file">Load from file</label>
            <input id="jd-file" type="file" accept="application/json,.json" onChange={(e) => { const f = e.target.files?.[0]; if (f) void f.text().then(setImportText); }} />
          </div>
          <div className="field">
            <label htmlFor="jd-import">Profiles JSON</label>
            <textarea id="jd-import" className="mono" rows={8} value={importText} onChange={(e) => setImportText(e.target.value)} />
          </div>
          {parseError && !editing && <p className="alert alert-error" role="alert">{parseError}</p>}
          <button type="submit" disabled={!importText.trim() || action.busy}>Import profiles</button>
        </form>
      )}
      {editing && (
        <Dialog title={editing.title} onClose={() => setEditing(undefined)}>
          <form className="form" onSubmit={saveOne}>
            <div className="field">
              <label htmlFor="jd-json">Profile (JSON)</label>
              <textarea id="jd-json" className="mono" rows={18} value={editing.json} onChange={(e) => setEditing({ ...editing, json: e.target.value })} />
              <span className="hint small">Dimensions: {DEMAND_DIMENSIONS.join(', ')}. Weights in kg, hours as numbers, other demands true / false.</span>
            </div>
            {parseError && <p className="alert alert-error" role="alert">{parseError}</p>}
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save profile</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}
