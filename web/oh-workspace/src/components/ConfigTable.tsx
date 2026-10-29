import { useState, type FormEvent, type ReactNode } from 'react';
import type { AuthClient } from '../auth/AuthClient';
import { formatDate, humanise } from './format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading } from './ui';
import { useAction, useAsync } from './useAsync';

export interface FieldSpec {
  key: string;
  label: string;
  /** `list`: string array edited as comma-separated text. */
  kind: 'text' | 'number' | 'bool' | 'select' | 'tristate' | 'list';
  options?: readonly string[];
  /** Empty value is sent as null (wildcard / optional). */
  nullable?: boolean;
  required?: boolean;
  hint?: string;
  /** Show in the table (default true). */
  column?: boolean;
  readOnly?: boolean;
}

export type ConfigRow = Record<string, unknown>;

/** Form value → API value according to the field spec. */
function toApiValue(f: FieldSpec, v: unknown): unknown {
  if (f.kind === 'bool') return v === true;
  if (f.kind === 'tristate') return v === true || v === 'true' ? true : v === false || v === 'false' ? false : null;
  if (f.kind === 'list') return Array.isArray(v) ? v : String(v ?? '').split(',').map((x) => x.trim()).filter(Boolean);
  if (v === '' || v === undefined || v === null) return f.nullable ? null : f.kind === 'number' ? 0 : '';
  if (f.kind === 'number') return typeof v === 'number' ? v : Number(v);
  return v;
}

function cellDisplay(f: FieldSpec, v: unknown): string {
  if (f.kind === 'bool') return v === true ? 'Yes' : 'No';
  if (f.kind === 'tristate') return v === true ? 'Yes' : v === false ? 'No' : 'Any';
  if (f.kind === 'list') return Array.isArray(v) && v.length ? v.join(', ') : '–';
  if (v === null || v === undefined || v === '') return f.nullable ? 'Any' : '–';
  if (f.kind === 'select') return humanise(String(v));
  if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return formatDate(v);
  return String(v);
}

function RowForm({ fields, initial, onSubmit, onCancel, busy, submitLabel }: { fields: FieldSpec[]; initial: ConfigRow; onSubmit: (row: ConfigRow) => void; onCancel?: () => void; busy: boolean; submitLabel: string }) {
  const [v, setV] = useState<ConfigRow>(initial);
  function submit(e: FormEvent) {
    e.preventDefault();
    const out: ConfigRow = { ...initial };
    for (const f of fields) if (!f.readOnly) out[f.key] = toApiValue(f, v[f.key]);
    onSubmit(out);
  }
  return (
    <form className="form config-form" onSubmit={submit}>
      <div className="form-grid">
        {fields
          .filter((f) => !f.readOnly)
          .map((f) =>
            f.kind === 'bool' ? (
              <label key={f.key} className="checkbox">
                <input type="checkbox" checked={v[f.key] === true} onChange={(e) => setV({ ...v, [f.key]: e.target.checked })} /> {f.label}
              </label>
            ) : (
              <Field key={f.key} label={f.label} required={f.required} hint={f.hint ?? (f.nullable ? 'Empty = any' : undefined)}>
                {(id) =>
                  f.kind === 'tristate' ? (
                    <select id={id} value={v[f.key] === true ? 'true' : v[f.key] === false ? 'false' : ''} onChange={(e) => setV({ ...v, [f.key]: e.target.value === '' ? null : e.target.value === 'true' })}>
                      <option value="">Any</option>
                      <option value="true">Yes</option>
                      <option value="false">No</option>
                    </select>
                  ) : f.kind === 'select' ? (
                    <select id={id} required={f.required} value={v[f.key] == null ? '' : String(v[f.key])} onChange={(e) => setV({ ...v, [f.key]: e.target.value })}>
                      <option value="">{f.nullable ? 'Any' : 'Choose…'}</option>
                      {(f.options ?? []).map((o) => (
                        <option key={o} value={o}>{humanise(o)}</option>
                      ))}
                    </select>
                  ) : (
                    <input
                      id={id}
                      type={f.kind === 'number' ? 'number' : 'text'}
                      required={f.required}
                      value={v[f.key] == null ? '' : Array.isArray(v[f.key]) ? (v[f.key] as unknown[]).join(', ') : String(v[f.key])}
                      onChange={(e) => setV({ ...v, [f.key]: f.kind === 'number' ? (e.target.value === '' ? '' : e.target.valueAsNumber) : e.target.value })}
                    />
                  )
                }
              </Field>
            ),
          )}
      </div>
      <div className="actions">
        <button type="submit" disabled={busy}>
          {submitLabel}
        </button>
        {onCancel && (
          <button type="button" className="secondary" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}

export interface ConfigTableProps {
  client: AuthClient;
  onSessionExpired: () => void;
  caption: string;
  fields: FieldSpec[];
  listPath: string;
  rowKey: (row: ConfigRow, index: number) => string;
  canWrite: boolean;
  /** perRow: POST createPath / PUT updatePath(row). wholeList: PUT listPath with the entire array. */
  mode: 'perRow' | 'wholeList';
  createPath?: string;
  updatePath?: (row: ConfigRow) => string;
  newRow?: ConfigRow;
  rowExtra?: (row: ConfigRow) => ReactNode;
  /** `PUT`: new rows are upserted with `PUT updatePath(row)` (master data keyed by code). Default POST createPath. */
  createMethod?: 'POST' | 'PUT';
  /** Shape the request body (e.g. drop server-managed `version`). */
  toBody?: (row: ConfigRow) => ConfigRow;
}

/** Generic admin table: list, edit and create config rows from a field spec. */
export function ConfigTable(p: ConfigTableProps) {
  const list = useAsync(() => p.client.api<ConfigRow[]>(p.listPath), p.onSessionExpired);
  const action = useAction(p.onSessionExpired);
  const [editing, setEditing] = useState<number | 'new'>();
  const [draft, setDraft] = useState<ConfigRow[]>();
  const rows = draft ?? list.data ?? [];
  const cols = p.fields.filter((f) => f.column !== false);

  async function save(row: ConfigRow, index: number | 'new') {
    if (p.mode === 'wholeList') {
      const next = index === 'new' ? [...rows, row] : rows.map((r, i) => (i === index ? row : r));
      setDraft(next);
      setEditing(undefined);
      return;
    }
    const ok = await action.run(async () => {
      const body = p.toBody ? p.toBody(row) : row;
      if (index === 'new' && p.createMethod !== 'PUT') await p.client.api(p.createPath ?? p.listPath, { method: 'POST', body });
      else await p.client.api(p.updatePath ? p.updatePath(row) : p.listPath, { method: 'PUT', body });
      return 'Saved.';
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    }
  }

  async function publishList() {
    if (!draft) return;
    const ok = await action.run(async () => {
      await p.client.api(p.listPath, { method: 'PUT', body: draft });
      return 'Saved.';
    });
    if (ok) {
      setDraft(undefined);
      list.reload();
    }
  }

  return (
    <div>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No entries.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{p.caption}</caption>
            <thead>
              <tr>
                {cols.map((f) => (
                  <th key={f.key} scope="col">{f.label}</th>
                ))}
                {(p.canWrite || p.rowExtra) && <th scope="col"><span className="visually-hidden">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) =>
                editing === i ? (
                  <tr key={p.rowKey(r, i)}>
                    <td colSpan={cols.length + 1}>
                      <RowForm fields={p.fields} initial={r} busy={action.busy} submitLabel={p.mode === 'wholeList' ? 'Apply' : 'Save'} onCancel={() => setEditing(undefined)} onSubmit={(row) => void save(row, i)} />
                    </td>
                  </tr>
                ) : (
                  <tr key={p.rowKey(r, i)}>
                    {cols.map((f) => (
                      <td key={f.key}>{cellDisplay(f, r[f.key])}</td>
                    ))}
                    {(p.canWrite || p.rowExtra) && (
                      <td>
                        <div className="card-actions">
                          {p.canWrite && (
                            <button type="button" className="secondary small-button" onClick={() => setEditing(i)}>
                              Edit
                            </button>
                          )}
                          {p.rowExtra?.(r)}
                        </div>
                      </td>
                    )}
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
      {p.canWrite && p.newRow && editing !== 'new' && (
        <button type="button" className="secondary" onClick={() => setEditing('new')}>
          Add entry
        </button>
      )}
      {p.canWrite && p.newRow && editing === 'new' && (
        <div className="tile">
          <h3>New entry</h3>
          <RowForm fields={p.fields} initial={p.newRow} busy={action.busy} submitLabel={p.mode === 'wholeList' ? 'Add' : 'Create'} onCancel={() => setEditing(undefined)} onSubmit={(row) => void save(row, 'new')} />
        </div>
      )}
      {p.mode === 'wholeList' && draft && (
        <div className="alert alert-warning" role="status">
          Unsaved changes.{' '}
          <button type="button" className="small-button" disabled={action.busy} onClick={() => void publishList()}>
            Save all
          </button>{' '}
          <button type="button" className="link-button" onClick={() => setDraft(undefined)}>
            Discard
          </button>
        </div>
      )}
    </div>
  );
}
