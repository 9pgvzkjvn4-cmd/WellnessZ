import { useState, type FormEvent } from 'react';
import { surveillanceApi, type IhImportResult, type Seg } from '../../api/surveillance';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { IH_COLUMNS, joinCodes, parseCosmicImport, parseIhImport, splitCodes, type ParsedImport } from '../../domain/surveillance';
import type { ScreenProps } from '../types';

function ImportErrors({ parsed }: { parsed: ParsedImport<unknown> }) {
  if (parsed.errors.length === 0) return null;
  return (
    <div className="alert alert-warning" role="status">
      <strong>{parsed.errors.length} problem{parsed.errors.length === 1 ? '' : 's'} – fix them before importing:</strong>
      <ul>
        {parsed.errors.slice(0, 20).map((e, i) => (
          <li key={`${e.line}-${i}`}>{e.line > 0 ? `Row ${e.line}: ` : ''}{e.message}</li>
        ))}
      </ul>
    </div>
  );
}

function Measurements({ client, onSessionExpired, seg }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { seg: Seg }) {
  const list = useAsync(() => surveillanceApi(client).ihMeasurements(seg.code), onSessionExpired, [seg.code]);
  const rows = [...(list.data ?? [])].sort((a, b) => b.measuredOn.localeCompare(a.measuredOn));
  return (
    <section className="tile" aria-labelledby="meas-title">
      <h2 id="meas-title">Measurements – {seg.name}</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No measurements.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Measurements</caption>
            <thead>
              <tr>
                <th scope="col">Measured</th>
                <th scope="col">Type</th>
                <th scope="col" className="num">Value</th>
                <th scope="col">Source ref</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.sourceRef}>
                  <th scope="row">{formatDay(m.measuredOn)}</th>
                  <td>{humanise(m.measurementType)}</td>
                  <td className="num">{m.value} {m.unit}{m.value >= seg.actionLevel && <> <ToneBadge tone="red">≥ action level</ToneBadge></>}</td>
                  <td className="mono small">{m.sourceRef}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

type SegForm = { code: string; name: string; locationCode: string; jobCodes: string; hazardCode: string; unit: string; actionLevel: string; limitValue: string };
const segToForm = (s?: Seg): SegForm => ({ code: s?.code ?? '', name: s?.name ?? '', locationCode: s?.locationCode ?? '', jobCodes: joinCodes(s?.jobCodes), hazardCode: s?.hazardCode ?? '', unit: s?.unit ?? '', actionLevel: s ? String(s.actionLevel) : '', limitValue: s ? String(s.limitValue) : '' });

function SegEditor({ client, onSessionExpired, seg, onClose, onSaved }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { seg?: Seg; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<SegForm>(() => segToForm(seg));
  const action = useAction(onSessionExpired);
  const ready = /^[A-Z0-9_-]{2,40}$/.test(f.code.trim().toUpperCase()) && !!f.name.trim() && !!f.hazardCode.trim() && !!f.unit.trim() && Number.isFinite(Number(f.actionLevel)) && f.actionLevel !== '' && Number.isFinite(Number(f.limitValue)) && f.limitValue !== '';
  async function save(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    const ok = await action.run(async () => {
      await surveillanceApi(client).saveSeg({
        code: f.code.trim().toUpperCase(),
        name: f.name.trim(),
        locationCode: f.locationCode.trim().toUpperCase(),
        jobCodes: splitCodes(f.jobCodes),
        hazardCode: f.hazardCode.trim().toUpperCase(),
        unit: f.unit.trim(),
        actionLevel: Number(f.actionLevel),
        limitValue: Number(f.limitValue),
      });
      return 'Exposure group saved.';
    });
    if (ok) onSaved();
  }
  const input = (k: keyof SegForm, label: string, opts: { type?: string; required?: boolean; readOnly?: boolean } = {}) => (
    <Field label={label} required={opts.required}>
      {(id) => <input id={id} type={opts.type ?? 'text'} step="any" required={opts.required} readOnly={opts.readOnly} maxLength={120} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} />}
    </Field>
  );
  return (
    <Dialog title={seg ? `Edit ${seg.code}` : 'New exposure group'} onClose={onClose}>
      <form className="form" onSubmit={save}>
        <div className="inline-form">
          {input('code', 'Code', { required: true, readOnly: !!seg })}
          {input('name', 'Name', { required: true })}
          {input('locationCode', 'Location')}
        </div>
        {input('jobCodes', 'Job codes (comma-separated)')}
        <div className="inline-form">
          {input('hazardCode', 'Hazard', { required: true })}
          {input('unit', 'Unit', { required: true })}
          {input('actionLevel', 'Action level', { type: 'number', required: true })}
          {input('limitValue', 'Limit value', { type: 'number', required: true })}
        </div>
        <div className="actions">
          <button type="submit" disabled={action.busy || !ready}>Save exposure group</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={action.error} />
    </Dialog>
  );
}

function IhImport({ client, onSessionExpired, onImported }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { onImported: () => void }) {
  const [text, setText] = useState('');
  const [result, setResult] = useState<IhImportResult>();
  const action = useAction(onSessionExpired);
  const parsed = text.trim() ? parseIhImport(text) : undefined;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!parsed || parsed.errors.length || parsed.rows.length === 0) return;
    setResult(undefined);
    const ok = await action.run(async () => {
      const r = await surveillanceApi(client).importIh(parsed.rows);
      setResult(r);
      return `${r.imported} imported, ${r.duplicates} duplicates skipped.`;
    });
    if (ok) {
      setText('');
      onImported();
    }
  }
  const above = result ? (Array.isArray(result.segsAboveActionLevel) ? result.segsAboveActionLevel.length : result.segsAboveActionLevel) : 0;
  return (
    <section className="tile" aria-labelledby="ih-import">
      <h2 id="ih-import">Import industrial-hygiene measurements</h2>
      <p className="small muted">
        Paste CSV with a header row (<span className="mono">{IH_COLUMNS.join(',')}</span>) or a JSON array. Measurement types: noise dosimetry, air sampling, biological, vibration. A group above
        its action level enrols its job codes into the hazard's programme.
      </p>
      <form className="form" onSubmit={submit}>
        <Field label="Measurements (CSV or JSON)" required>
          {(id) => <textarea id={id} rows={6} className="mono" spellCheck={false} value={text} onChange={(e) => setText(e.target.value)} />}
        </Field>
        {parsed && <ImportErrors parsed={parsed} />}
        {parsed && parsed.errors.length === 0 && <p className="small" aria-live="polite">{parsed.rows.length} measurement{parsed.rows.length === 1 ? '' : 's'} ready to import.</p>}
        <button type="submit" disabled={action.busy || !parsed || parsed.errors.length > 0 || parsed.rows.length === 0}>Import</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {result && (
        <p>
          {above > 0 ? <ToneBadge tone="red">{above} group{above === 1 ? '' : 's'} above action level</ToneBadge> : <ToneBadge tone="green">No group above action level</ToneBadge>} · {result.employeesEnrolled} employee{result.employeesEnrolled === 1 ? '' : 's'} enrolled
        </p>
      )}
    </section>
  );
}

function CosmicDose({ client, session, onSessionExpired }: ScreenProps) {
  const [f, setF] = useState({ year: String(new Date().getFullYear()), aboveThreshold: false });
  const [applied, setApplied] = useState(f);
  const list = useAsync(() => surveillanceApi(client).cosmicDose({ year: applied.year || undefined, aboveThreshold: applied.aboveThreshold || undefined }), onSessionExpired, [applied]);
  const [text, setText] = useState('');
  const action = useAction(onSessionExpired);
  const parsed = text.trim() ? parseCosmicImport(text) : undefined;
  const rows = list.data ?? [];

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!parsed || parsed.errors.length || parsed.rows.length === 0) return;
    const ok = await action.run(async () => {
      await surveillanceApi(client).importCosmic(parsed.rows);
      return `${parsed.rows.length} dose record${parsed.rows.length === 1 ? '' : 's'} imported.`;
    });
    if (ok) {
      setText('');
      list.reload();
    }
  }

  return (
    <section aria-labelledby="cosmic-title">
      <h2 id="cosmic-title">Cosmic radiation dose (aircrew)</h2>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(f); }}>
        <Field label="Year">{(id) => <input id={id} type="number" min={2000} max={2100} value={f.year} onChange={(e) => setF({ ...f, year: e.target.value })} />}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.aboveThreshold} onChange={(e) => setF({ ...f, aboveThreshold: e.target.checked })} /> Above alert threshold only</label>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No dose records.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} crew</caption>
            <thead>
              <tr>
                <th scope="col">Crew member</th>
                <th scope="col">Year</th>
                <th scope="col" className="num">Dose (mSv)</th>
                <th scope="col">Flags</th>
              </tr>
            </thead>
            <tbody>
              {[...rows].sort((a, b) => b.doseMsv - a.doseMsv).map((r) => (
                <tr key={`${r.employeeId}-${r.year}`}>
                  <th scope="row">{r.displayName ?? r.employeeId} <span className="small muted">{r.employeeId}</span></th>
                  <td>{r.year}</td>
                  <td className="num">{r.doseMsv.toFixed(2)}</td>
                  <td>
                    {r.aboveThreshold && <ToneBadge tone="red">Above alert threshold</ToneBadge>} {r.pregnancyDeclared && <ToneBadge tone="amber">Pregnancy declared – 1 mSv limit</ToneBadge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {can(session.roles, 'cosmicDoseImport') && (
        <form className="form" onSubmit={submit}>
          <Field label="Import doses (CSV: employeeId,year,doseMsv,source – or JSON)">
            {(id) => <textarea id={id} rows={4} className="mono" spellCheck={false} value={text} onChange={(e) => setText(e.target.value)} />}
          </Field>
          {parsed && <ImportErrors parsed={parsed} />}
          <button type="submit" className="secondary" disabled={action.busy || !parsed || parsed.errors.length > 0 || parsed.rows.length === 0}>Import doses</button>
          <ActionFeedback error={action.error} message={action.message} />
        </form>
      )}
    </section>
  );
}

/** `#/surveillance/exposure` – similar exposure groups, IH measurement import, cosmic dose (E07-F06). */
export function ExposureScreen(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const segs = useAsync(() => surveillanceApi(client).segs(), onSessionExpired);
  const [selected, setSelected] = useState<string>();
  const [editing, setEditing] = useState<Seg | 'new'>();
  const rows = [...(segs.data ?? [])].sort((a, b) => Number(b.aboveActionLevel) - Number(a.aboveActionLevel) || a.code.localeCompare(b.code));
  const current = rows.find((s) => s.code === selected);
  const segWrite = can(session.roles, 'segWrite');

  return (
    <section>
      <PageTitle sub="Similar exposure groups (SEGs) with their latest industrial-hygiene results. Groups above their action level are enrolled into the matching programme.">Exposure registers</PageTitle>
      {segWrite && <button type="button" onClick={() => setEditing('new')}>New exposure group</button>}
      {segs.loading && <Loading />}
      {!!segs.error && <ErrorAlert error={segs.error} />}
      {segs.data && rows.length === 0 && <Empty>No exposure groups.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} exposure group{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Group</th>
                <th scope="col">Location</th>
                <th scope="col">Jobs</th>
                <th scope="col">Hazard</th>
                <th scope="col" className="num">Latest</th>
                <th scope="col" className="num">Action level / limit</th>
                <th scope="col">Programmes</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.code} className={s.code === selected ? 'row-selected' : undefined}>
                  <th scope="row">
                    <button type="button" className="link-button" aria-pressed={s.code === selected} onClick={() => setSelected(s.code === selected ? undefined : s.code)}>{s.name}</button>{' '}
                    <span className="small muted mono">{s.code}</span>
                  </th>
                  <td>{s.locationCode}</td>
                  <td className="small">{s.jobCodes.map(humanise).join(', ')}</td>
                  <td>{humanise(s.hazardCode)}</td>
                  <td className="num">
                    {s.latestValue != null ? `${s.latestValue} ${s.unit}` : '–'}
                    {s.latestMeasuredOn && <div className="small muted">{formatDay(s.latestMeasuredOn)}</div>}
                    {s.aboveActionLevel && <ToneBadge tone="red">Above action level</ToneBadge>}
                  </td>
                  <td className="num">{s.actionLevel} / {s.limitValue} {s.unit}</td>
                  <td className="small">{s.enrolledProgrammes.length ? s.enrolledProgrammes.map(humanise).join(', ') : '–'}</td>
                  <td>{segWrite && <button type="button" className="secondary small-button" onClick={() => setEditing(s)} aria-label={`Edit ${s.code}`}>Edit</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {current && <Measurements client={client} onSessionExpired={onSessionExpired} seg={current} />}
      {can(session.roles, 'ihImport') && <IhImport client={client} onSessionExpired={onSessionExpired} onImported={segs.reload} />}
      {can(session.roles, 'cosmicDose') && <CosmicDose {...props} />}
      {editing && (
        <SegEditor
          client={client}
          onSessionExpired={onSessionExpired}
          seg={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(undefined)}
          onSaved={() => { setEditing(undefined); segs.reload(); }}
        />
      )}
    </section>
  );
}
