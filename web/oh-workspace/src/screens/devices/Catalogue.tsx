import { useState, type FormEvent } from 'react';
import { DEVICE_FAMILIES, MODEL_LIFECYCLES, OPTION_CODES, POSTURES, devicesApi, type ModelRecord, type PostureIntake } from '../../api/devices';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDay, humanise } from '../../components/format';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { plural } from '../../domain/deviceConsole';
import { CodeBadge } from './common';

/** `#/devices/catalogue` – search by family, brand or model; compare up to six models side by side. */
export function DeviceCatalogue(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [filters, setFilters] = useState({ family: '', q: '', option: '', lifecycle: '', posture: '' });
  const [applied, setApplied] = useState(filters);
  const [picked, setPicked] = useState<string[]>([]);
  const [compareCodes, setCompareCodes] = useState<string[]>([]);
  const families = useAsync(() => api.families(), onSessionExpired);
  const models = useAsync(() => api.models(applied), onSessionExpired, [applied]);
  const compare = useAsync(() => (compareCodes.length ? api.compare(compareCodes) : Promise.resolve([] as ModelRecord[])), onSessionExpired, [compareCodes]);
  const rows = models.data ?? [];

  function search(e: FormEvent) {
    e.preventDefault();
    setApplied({ ...filters, q: filters.q.trim() });
  }
  function toggle(code: string) {
    setPicked((p) => (p.includes(code) ? p.filter((c) => c !== code) : p.length >= 6 ? p : [...p, code]));
  }

  return (
    <section>
      <PageTitle sub="133 inventory models across 11 families: regulatory status per market, supported connectivity options, driver packages and vendor security posture.">Device catalogue</PageTitle>
      {families.data && (
        <ul className="tiles" aria-label="Families">
          {families.data.map((f) => (
            <li key={f.family} className="tile">
              <button type="button" className="link-button tile-button" aria-pressed={applied.family === f.family} onClick={() => { const next = { ...filters, family: applied.family === f.family ? '' : f.family }; setFilters(next); setApplied(next); }}>
                <span className="tile-value">{f.models}</span>
                <span className="kpi-label">{f.label}</span>
                <span className="small muted">{f.certifiedModels} certified</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="filters" role="search" aria-label="Search the catalogue" onSubmit={search}>
        <Field label="Brand, model or code">{(id) => <input id={id} value={filters.q} maxLength={80} onChange={(e) => setFilters({ ...filters, q: e.target.value })} />}</Field>
        <Field label="Family">{(id) => <select id={id} value={filters.family} onChange={(e) => setFilters({ ...filters, family: e.target.value })}><SelectOptions values={DEVICE_FAMILIES} empty="All families" /></select>}</Field>
        <Field label="Connectivity option">{(id) => <select id={id} value={filters.option} onChange={(e) => setFilters({ ...filters, option: e.target.value })}><option value="">Any option</option>{OPTION_CODES.map((o) => <option key={o} value={o}>{o}</option>)}</select>}</Field>
        <Field label="Lifecycle">{(id) => <select id={id} value={filters.lifecycle} onChange={(e) => setFilters({ ...filters, lifecycle: e.target.value })}><SelectOptions values={MODEL_LIFECYCLES} empty="Any" /></select>}</Field>
        <Field label="Security posture">{(id) => <select id={id} value={filters.posture} onChange={(e) => setFilters({ ...filters, posture: e.target.value })}><SelectOptions values={POSTURES} empty="Any" /></select>}</Field>
        <div className="filter-actions">
          <button type="submit">Search</button>
          {can(session.roles, 'deviceAdmin') && <a className="button secondary" href="#/devices/model-requests">Request a new model</a>}
        </div>
      </form>
      {models.loading && !models.data && <Loading />}
      <ErrorAlert error={models.error} />
      {models.data && rows.length === 0 && <Empty>No model matches. If the device is missing, request a new model.</Empty>}
      {rows.length > 0 && (
        <>
          <div className="toolbar">
            <span className="small">{picked.length ? `${picked.length} selected for comparison (max 6)` : 'Select up to six models to compare.'}</span>
            <button type="button" className="secondary" disabled={picked.length < 2} onClick={() => setCompareCodes(picked)}>Compare selected</button>
          </div>
          <div className="table-wrap">
            <table>
              <caption>{plural(rows.length, 'model')}</caption>
              <thead><tr><th scope="col"><span className="visually-hidden">Compare</span></th><th scope="col">Model</th><th scope="col">Family</th><th scope="col">Options</th><th scope="col">Lifecycle</th><th scope="col">Posture</th><th scope="col" className="num">Certified drivers</th><th scope="col">Confidence</th></tr></thead>
              <tbody>
                {rows.map((m) => (
                  <tr key={m.modelCode}>
                    <td><input type="checkbox" aria-label={`Compare ${m.manufacturer} ${m.model}`} checked={picked.includes(m.modelCode)} disabled={!picked.includes(m.modelCode) && picked.length >= 6} onChange={() => toggle(m.modelCode)} /></td>
                    <th scope="row"><a href={`#/devices/catalogue/${m.modelCode}`}>{m.manufacturer} {m.model}</a> <span className="small muted mono">{m.modelCode}</span></th>
                    <td>{humanise(m.family)}</td>
                    <td className="small">{m.options.join(' · ') || '–'}</td>
                    <td><CodeBadge code={m.lifecycle} /></td>
                    <td><CodeBadge code={m.posture} /></td>
                    <td className="num">{m.certifiedDrivers}</td>
                    <td className="small">{m.confidence ?? '–'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {compareCodes.length > 0 && (
        <section className="card" aria-labelledby="cmp-title">
          <h2 id="cmp-title">Side-by-side comparison</h2>
          {compare.loading && <Loading />}
          <ErrorAlert error={compare.error} />
          {compare.data && compare.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Compared models</caption>
                <thead><tr><th scope="col">Attribute</th>{compare.data.map((m) => <th key={m.modelCode} scope="col">{m.manufacturer} {m.model}</th>)}</tr></thead>
                <tbody>
                  <tr><th scope="row">Family</th>{compare.data.map((m) => <td key={m.modelCode}>{humanise(m.family)}</td>)}</tr>
                  <tr><th scope="row">Supported options</th>{compare.data.map((m) => <td key={m.modelCode}>{m.options.filter((o) => o.supported).map((o) => o.option).join(', ') || '–'}</td>)}</tr>
                  <tr><th scope="row">Lifecycle</th>{compare.data.map((m) => <td key={m.modelCode}><CodeBadge code={m.lifecycle} /></td>)}</tr>
                  <tr><th scope="row">Posture</th>{compare.data.map((m) => <td key={m.modelCode}><CodeBadge code={m.security.posture} /></td>)}</tr>
                  <tr><th scope="row">US status</th>{compare.data.map((m) => <td key={m.modelCode}>{humanise(m.regulatory.find((r) => r.market === 'US')?.status)}</td>)}</tr>
                  <tr><th scope="row">Drivers</th>{compare.data.map((m) => <td key={m.modelCode} className="small">{m.drivers.map((d) => `${d.packageId}@${d.version}`).join(', ') || 'None'}</td>)}</tr>
                  <tr><th scope="row">Protocol</th>{compare.data.map((m) => <td key={m.modelCode} className="small">{m.protocol ?? '–'}</td>)}</tr>
                </tbody>
              </table>
            </div>
          )}
          <button type="button" className="secondary" onClick={() => setCompareCodes([])}>Close comparison</button>
        </section>
      )}
    </section>
  );
}

/** `#/devices/catalogue/{modelCode}` – model record: regulatory status per market, options, drivers, posture (+ intake). */
export function DeviceModelDetail(props: ScreenProps & { modelCode: string }) {
  const { client, session, onSessionExpired, modelCode } = props;
  const api = devicesApi(client);
  const model = useAsync(() => api.model(modelCode), onSessionExpired, [modelCode]);
  const m = model.data;
  return (
    <section>
      <p className="breadcrumb"><a href="#/devices/catalogue">Device catalogue</a> › {modelCode}</p>
      {model.loading && !m && <Loading />}
      <ErrorAlert error={model.error} />
      {m && (
        <>
          <PageTitle sub={m.ohUse}>{m.manufacturer} {m.model}</PageTitle>
          <div className="actions">
            {can(session.roles, 'deviceConfigWrite') && m.lifecycle !== 'RETIRED' && <a className="button" href={`#/devices/wizard?model=${encodeURIComponent(m.modelCode)}`}>Provision this model</a>}
            {can(session.roles, 'deviceAdmin') && <a className="button secondary" href="#/devices/model-requests">Request a different model</a>}
          </div>
          <div className="grid-2">
            <section className="card" aria-labelledby="md-ident">
              <h2 id="md-ident">Identification</h2>
              <Dl items={[
                ['Model code', <span className="mono">{m.modelCode}</span>],
                ['Family', m.familyLabel ?? humanise(m.family)],
                ['Lifecycle', <CodeBadge code={m.lifecycle} />],
                ['Interface confidence', m.confidence],
                ['Variants', m.variants?.join(', ')],
                ['Protocol', m.protocol],
                ['Connectivity', m.connectivity],
                ['FDA product codes', m.fdaCodes?.join(', ')],
                ['UDI', m.udi?.deviceIdentifier ? `${m.udi.deviceIdentifier} (${m.udi.source ?? 'manual'})` : undefined],
                ['Forms fed', m.formsFed?.join(', ')],
                ['Locally modified', m.locallyModified ? 'Yes' : 'No'],
              ]} />
            </section>
            <section className="card" aria-labelledby="md-posture">
              <h2 id="md-posture">Security posture</h2>
              <p><CodeBadge code={m.security.posture} /> {m.security.assessedBy && <span className="small muted">assessed by {m.security.assessedBy}</span>}</p>
              <Dl items={[
                ['MDS2 received', m.security.mds2?.received ? `Yes${m.security.mds2.version ? ` (${m.security.mds2.version})` : ''}` : 'No'],
                ['SBOM', m.security.sbom?.available ? `Available${m.security.sbom.format ? ` (${m.security.sbom.format})` : ''}` : 'Not available'],
                ['Support status', humanise(m.security.supportStatus)],
                ['Patch path', humanise(m.security.patchPath)],
                ['Remote support', humanise(m.security.remoteSupport)],
              ]} />
              {(m.security.findings?.length ?? 0) > 0 && (
                <ul className="plain-list">
                  {m.security.findings!.map((f) => <li key={f.rule}><CodeBadge code={f.outcome} /> {f.message ?? humanise(f.rule)}</li>)}
                </ul>
              )}
              {can(session.roles, 'devicePostureWrite') && <PostureIntakeForm {...props} model={m} onSaved={model.reload} />}
            </section>
          </div>
          <section className="card" aria-labelledby="md-reg">
            <h2 id="md-reg">Regulatory status per market</h2>
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Regulatory status</caption>
                <thead><tr><th scope="col">Market</th><th scope="col">Regime</th><th scope="col">Status</th><th scope="col">Product codes</th><th scope="col">Reference</th><th scope="col">Verified</th></tr></thead>
                <tbody>
                  {m.regulatory.map((r) => (
                    <tr key={r.market}>
                      <th scope="row">{r.market}</th>
                      <td className="small">{r.regime ?? '–'}</td>
                      <td><CodeBadge code={r.status === 'CLEARED' || r.status === 'CE_MARKED' || r.status === 'REGISTERED' ? 'PASS' : r.status === 'NOT_REQUIRED' ? 'NOT_APPLICABLE' : 'UNKNOWN'} label={humanise(r.status)} /></td>
                      <td className="small">{r.productCodes?.join(', ') || '–'}</td>
                      <td className="small">{r.reference ?? '–'}</td>
                      <td className="small">{formatDay(r.verifiedOn ?? undefined)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <div className="grid-2">
            <section className="card" aria-labelledby="md-opts">
              <h2 id="md-opts">Supported connectivity options</h2>
              <ul className="plain-list">
                {m.options.filter((o) => o.supported).map((o) => <li key={o.option}><strong>{o.option}</strong>{o.notes ? ` – ${o.notes}` : ''}</li>)}
              </ul>
              <p className="small muted">{plural(m.options.filter((o) => !o.supported).length, 'option')} not supported by this model (hidden in the wizard).</p>
              {(m.vendorSoftware?.length ?? 0) > 0 && <p className="small">Vendor software: {m.vendorSoftware!.map((v) => `${v.name}${v.versions.length ? ` (${v.versions.join(', ')})` : ''}`).join('; ')}</p>}
            </section>
            <section className="card" aria-labelledby="md-drv">
              <h2 id="md-drv">Driver packages</h2>
              {m.drivers.length === 0 ? <Empty>No driver package yet – this model cannot be provisioned until one is registered.</Empty> : (
                <table>
                  <caption className="visually-hidden">Driver packages</caption>
                  <thead><tr><th scope="col">Package</th><th scope="col">Options</th><th scope="col">Status</th></tr></thead>
                  <tbody>
                    {m.drivers.map((d) => <tr key={`${d.packageId}@${d.version}`}><th scope="row" className="mono small">{d.packageId}@{d.version}</th><td>{d.options?.join(', ')}</td><td><CodeBadge code={d.status} /></td></tr>)}
                  </tbody>
                </table>
              )}
            </section>
          </div>
        </>
      )}
    </section>
  );
}

const PATCH_PATHS = ['VENDOR', 'SELF', 'NONE'] as const;
const REMOTE = ['BROKERED', 'NONE', 'DIRECT'] as const;
const SUPPORT = ['SUPPORTED', 'END_OF_SUPPORT', 'UNKNOWN'] as const;

/** Vendor security onboarding (§13.8): MDS2 / SBOM / support / OS / patching / remote access → policy evaluation. */
function PostureIntakeForm({ client, onSessionExpired, model, onSaved }: ScreenProps & { model: ModelRecord; onSaved: () => void }) {
  const api = devicesApi(client);
  const s = model.security;
  const [f, setF] = useState({ mds2: !!s.mds2?.received, mds2Version: s.mds2?.version ?? '', sbom: !!s.sbom?.available, sbomFormat: s.sbom?.format ?? 'CYCLONEDX', supportStatus: s.supportStatus ?? 'UNKNOWN', osName: s.osName ?? '', osSupported: s.osSupported !== false, patchPath: s.patchPath ?? '', remoteSupport: s.remoteSupport ?? 'NONE', securityEmail: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function save(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.patchPath) errs.push({ field: 'pi-patch', message: 'Choose the patch path' });
    if (f.securityEmail && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.securityEmail)) errs.push({ field: 'pi-email', message: 'Enter a valid security contact e-mail address' });
    setErrors(errs);
    if (errs.length) return;
    const intake: PostureIntake = {
      mds2: { received: f.mds2, ...(f.mds2Version ? { version: f.mds2Version } : {}) },
      sbom: { available: f.sbom, ...(f.sbom ? { format: f.sbomFormat } : {}) },
      supportStatus: f.supportStatus,
      ...(f.osName ? { osName: f.osName } : {}),
      osSupported: f.osSupported,
      patchPath: f.patchPath,
      remoteSupport: f.remoteSupport,
      ...(f.securityEmail ? { contacts: { securityEmail: f.securityEmail } } : {}),
    };
    if (await action.run(async () => { const r = await api.putPosture(model.modelCode, intake); return `Posture evaluated: ${humanise(r.posture)}.`; })) onSaved();
  }
  return (
    <details className="config-form">
      <summary>Record vendor security intake</summary>
      <form className="form" onSubmit={save} noValidate aria-label="Vendor security intake">
        <ErrorSummary errors={errors} />
        <label className="inline-row"><input type="checkbox" checked={f.mds2} onChange={(e) => setF({ ...f, mds2: e.target.checked })} /> MDS2 form received</label>
        <Field label="MDS2 version">{(id) => <input id={id} value={f.mds2Version} maxLength={20} onChange={(e) => setF({ ...f, mds2Version: e.target.value })} />}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.sbom} onChange={(e) => setF({ ...f, sbom: e.target.checked })} /> SBOM available</label>
        <Field label="Support status">{(id) => <select id={id} value={f.supportStatus} onChange={(e) => setF({ ...f, supportStatus: e.target.value })}><SelectOptions values={SUPPORT} /></select>}</Field>
        <Field label="Operating system (vendor PC)">{(id) => <input id={id} value={f.osName} maxLength={60} onChange={(e) => setF({ ...f, osName: e.target.value })} />}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.osSupported} onChange={(e) => setF({ ...f, osSupported: e.target.checked })} /> Operating system still supported</label>
        <IdField id="pi-patch" label="Patch path" required error={errors.find((x) => x.field === 'pi-patch')?.message}>
          {(d) => <select id="pi-patch" aria-describedby={d} value={f.patchPath} onChange={(e) => setF({ ...f, patchPath: e.target.value })}><SelectOptions values={PATCH_PATHS} empty="Choose…" /></select>}
        </IdField>
        <Field label="Vendor remote support">{(id) => <select id={id} value={f.remoteSupport} onChange={(e) => setF({ ...f, remoteSupport: e.target.value })}><SelectOptions values={REMOTE} /></select>}</Field>
        <IdField id="pi-email" label="Security contact e-mail" error={errors.find((x) => x.field === 'pi-email')?.message}>
          {(d) => <input id="pi-email" type="email" aria-describedby={d} value={f.securityEmail} onChange={(e) => setF({ ...f, securityEmail: e.target.value })} />}
        </IdField>
        <div className="actions"><button type="submit" disabled={action.busy}>Evaluate posture</button></div>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </details>
  );
}
