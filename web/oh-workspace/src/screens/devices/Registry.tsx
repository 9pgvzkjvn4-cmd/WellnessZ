import { useState, type FormEvent } from 'react';
import { DEVICE_FAMILIES, DRIVER_STATUSES, SCOPING_STATUSES, URGENCY_LEVELS, devicesApi, type DriverEntry, type ExportPayload, type ImportResult, type ModelRequest } from '../../api/devices';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise } from '../../components/format';
import { KpiTile } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { plural } from '../../domain/deviceConsole';
import { splitList } from '../../domain/deviceWizard';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { ImportItems } from './Approvals';
import { CodeBadge, Hash } from './common';

// ====================================================================== driver registry

/** `#/devices/drivers` – signed driver packages: register (tester), certify (approver, not the registrant), revoke (security). */
export function DriverRegistry(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [status, setStatus] = useState('');
  const [modelCode, setModelCode] = useState('');
  const [applied, setApplied] = useState({ status: '', modelCode: '' });
  const list = useAsync(() => api.drivers({ status: applied.status || undefined, modelCode: applied.modelCode || undefined }), onSessionExpired, [applied]);
  const [selected, setSelected] = useState<DriverEntry>();
  const [reasonFor, setReasonFor] = useState<{ d: DriverEntry; kind: 'deprecate' | 'revoke' }>();
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];
  async function act(fn: () => Promise<unknown>, msg: string) {
    if (await action.run(async () => { await fn(); return msg; })) list.reload();
  }
  return (
    <section>
      <PageTitle sub="Signed (Ed25519) driver packages per model and option, with conformance evidence. Instances on a revoked package are suspended automatically.">Driver registry</PageTitle>
      <form className="filters" aria-label="Filter drivers" onSubmit={(e) => { e.preventDefault(); setApplied({ status, modelCode: modelCode.trim().toUpperCase() }); }}>
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={DRIVER_STATUSES} empty="All" /></select>}</Field>
        <Field label="Model code">{(id) => <input id={id} value={modelCode} maxLength={9} onChange={(e) => setModelCode(e.target.value)} />}</Field>
        <div className="filter-actions"><button type="submit">Filter</button></div>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      {list.data && rows.length === 0 && <Empty>No driver package matches.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'package')}</caption>
            <thead><tr><th scope="col">Package</th><th scope="col">Models</th><th scope="col">Options</th><th scope="col">Adapter</th><th scope="col">Status</th><th scope="col">Signature key</th><th scope="col">Registered</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((d) => {
                const own = session.username && d.registeredBy === session.username;
                return (
                  <tr key={`${d.packageId}@${d.version}`}>
                    <th scope="row"><button type="button" className="link-button mono" onClick={() => setSelected(d)}>{d.packageId}@{d.version}</button></th>
                    <td className="small">{d.models.join(', ')}</td>
                    <td>{d.options.join(', ')}</td>
                    <td className="small">{d.adapter ?? '–'}</td>
                    <td><CodeBadge code={d.status} /></td>
                    <td className="mono small">{d.signatureKeyId ?? '–'}</td>
                    <td className="small">{d.registeredBy ?? '–'} {formatDate(d.registeredAt)}</td>
                    <td className="actions">
                      {can(session.roles, 'deviceDriverCertify') && d.status === 'REGISTERED' && (
                        <button type="button" className="small-button" disabled={!!own || action.busy} title={own ? 'You registered this package – another approver must certify it' : undefined} onClick={() => void act(() => api.certifyDriver(d.packageId, d.version), `${d.packageId}@${d.version} certified.`)}>Certify</button>
                      )}
                      {can(session.roles, 'deviceDriverDeprecate') && (d.status === 'CERTIFIED' || d.status === 'REGISTERED') && <button type="button" className="secondary small-button" onClick={() => setReasonFor({ d, kind: 'deprecate' })}>Deprecate</button>}
                      {can(session.roles, 'deviceDriverRevoke') && d.status !== 'REVOKED' && <button type="button" className="danger-button small-button" onClick={() => setReasonFor({ d, kind: 'revoke' })}>Revoke</button>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {selected && <DriverDetail {...props} entry={selected} onClose={() => setSelected(undefined)} />}
      {can(session.roles, 'deviceDriverRegister') && <RegisterDriver {...props} onDone={list.reload} />}
      {reasonFor && (
        <DriverReasonDialog
          title={`${reasonFor.kind === 'revoke' ? 'Revoke' : 'Deprecate'} ${reasonFor.d.packageId}@${reasonFor.d.version}`}
          warning={reasonFor.kind === 'revoke' ? 'Every device instance on this version is suspended and the on-duty administrator is alerted.' : undefined}
          onClose={() => setReasonFor(undefined)}
          onSubmit={(reason) => {
            const { d, kind } = reasonFor;
            setReasonFor(undefined);
            void act(() => (kind === 'revoke' ? api.revokeDriver(d.packageId, d.version, reason) : api.deprecateDriver(d.packageId, d.version, reason)), `${d.packageId}@${d.version} ${kind === 'revoke' ? 'revoked' : 'deprecated'}.`);
          }}
        />
      )}
    </section>
  );
}

function DriverReasonDialog({ title, warning, onClose, onSubmit }: { title: string; warning?: string; onClose: () => void; onSubmit: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string>();
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); if (!reason.trim()) { setError('Enter the reason'); return; } onSubmit(reason.trim()); }}>
        {warning && <p className="small">{warning}</p>}
        <IdField id="drv-reason" label="Reason" required error={error}>{(d) => <textarea id="drv-reason" rows={2} maxLength={500} aria-describedby={d} value={reason} onChange={(e) => setReason(e.target.value)} />}</IdField>
        <div className="actions"><button type="submit">Confirm</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

function DriverDetail({ client, onSessionExpired, entry, onClose }: ScreenProps & { entry: DriverEntry; onClose: () => void }) {
  const api = devicesApi(client);
  const detail = useAsync(() => api.driver(entry.packageId, entry.version), onSessionExpired, [entry.packageId, entry.version]);
  const matrix = useAsync(() => api.matrix(entry.models[0]), onSessionExpired, [entry.models[0]]);
  const m = detail.data?.manifest;
  return (
    <section className="card" aria-labelledby="drv-title">
      <h2 id="drv-title" className="mono">{entry.packageId}@{entry.version}</h2>
      <Dl items={[['Status', <CodeBadge code={entry.status} />], ['Package SHA-256', <Hash value={entry.sha256} />], ['Conformance run', entry.conformanceRunId], ['Conformance evidence', <Hash value={entry.conformanceEvidenceSha256} />], ['Engine range', entry.engineRange], ['Certified by', entry.certifiedBy ? `${entry.certifiedBy} ${formatDate(entry.certifiedAt)}` : '–'], ['Status reason', entry.statusReason]]} />
      {detail.loading && <Loading />}
      <ErrorAlert error={detail.error} />
      {m && (
        <div className="grid-2">
          <div>
            <h3>Mappings</h3>
            <ul className="plain-list small">{(m.mappings ?? []).map((x) => <li key={x.key}><span className="mono">{x.key}</span> → {x.display ?? '–'} {x.loinc ? `(LOINC ${x.loinc})` : ''} {x.ucum ?? ''}</li>)}</ul>
          </div>
          <div>
            <h3>Test vectors and gates</h3>
            <p className="small">Vectors: {(m.testVectors ?? []).map((v) => humanise(v.name)).join(', ') || '–'}</p>
            <p className="small">Quality gates: {(m.qualityGates ?? []).join(' → ') || '–'}</p>
            <p className="small">Worklist push: {humanise(m.capabilities?.worklistPush)}</p>
          </div>
        </div>
      )}
      {matrix.data && matrix.data.length > 0 && (
        <table>
          <caption>Compatibility matrix for {entry.models[0]}</caption>
          <thead><tr><th scope="col">Option</th><th scope="col">Package</th><th scope="col">Status</th><th scope="col">Engine range</th></tr></thead>
          <tbody>{matrix.data.map((r) => <tr key={`${r.option}-${r.packageId}-${r.version}`}><th scope="row">{r.option}</th><td className="mono small">{r.packageId}@{r.version}</td><td><CodeBadge code={r.status} /></td><td className="small">{r.engineRange ?? '–'}</td></tr>)}</tbody>
        </table>
      )}
      <button type="button" className="secondary" onClick={onClose}>Close</button>
    </section>
  );
}

function RegisterDriver({ client, onSessionExpired, onDone }: ScreenProps & { onDone: () => void }) {
  const api = devicesApi(client);
  const [f, setF] = useState({ json: '', runId: '', evidence: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  function parse(): Record<string, unknown> | undefined {
    try {
      const v: unknown = JSON.parse(f.json);
      return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
    } catch {
      return undefined;
    }
  }
  async function validate() {
    const pkg = parse();
    if (!pkg) { setErrors([{ field: 'reg-json', message: 'Paste the signed package as a JSON object' }]); return; }
    setErrors([]);
    await action.run(async () => { const r = await api.validateDriver(pkg); return r.valid ? `Valid (SHA-256 ${r.sha256?.slice(0, 12)}…).` : `Not valid: ${(r.errors ?? []).map((e) => e.message).join('; ')}`; });
  }
  async function register(e: FormEvent) {
    e.preventDefault();
    const pkg = parse();
    const errs: FieldError[] = [];
    if (!pkg) errs.push({ field: 'reg-json', message: 'Paste the signed package as a JSON object' });
    if (!f.runId.trim()) errs.push({ field: 'reg-run', message: 'Enter the conformance run ID' });
    if (f.evidence && !/^[0-9a-f]{64}$/.test(f.evidence)) errs.push({ field: 'reg-evidence', message: 'Evidence SHA-256 must be 64 lower-case hex characters' });
    setErrors(errs);
    if (errs.length || !pkg) return;
    if (await action.run(async () => { const d = await api.registerDriver({ packageJson: pkg, conformanceRunId: f.runId.trim(), ...(f.evidence ? { conformanceEvidenceSha256: f.evidence } : {}) }); return `${d.packageId}@${d.version} registered.`; })) { setF({ json: '', runId: '', evidence: '' }); onDone(); }
  }
  return (
    <details className="card">
      <summary>Register a signed driver package</summary>
      <form className="form" onSubmit={register} noValidate aria-label="Register driver package">
        <ErrorSummary errors={errors} />
        <IdField id="reg-json" label="Signed package (JSON)" required hint="The signature member is verified; literal secrets are rejected." error={errorFor(errors, 'reg-json')}>
          {(d) => <textarea id="reg-json" className="mono" rows={8} aria-describedby={d} value={f.json} onChange={(e) => setF({ ...f, json: e.target.value })} />}
        </IdField>
        <IdField id="reg-run" label="Conformance run ID" required error={errorFor(errors, 'reg-run')}>{(d) => <input id="reg-run" aria-describedby={d} maxLength={60} value={f.runId} onChange={(e) => setF({ ...f, runId: e.target.value })} />}</IdField>
        <IdField id="reg-evidence" label="Conformance evidence SHA-256" error={errorFor(errors, 'reg-evidence')}>{(d) => <input id="reg-evidence" className="mono" aria-describedby={d} maxLength={64} value={f.evidence} onChange={(e) => setF({ ...f, evidence: e.target.value.trim() })} />}</IdField>
        <div className="actions"><button type="button" className="secondary" disabled={action.busy} onClick={() => void validate()}>Validate</button><button type="submit" disabled={action.busy}>Register</button></div>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </details>
  );
}

// ====================================================================== request-a-new-model

/** `#/devices/model-requests` – request a model that is not in the catalogue; testers scope it (KPI ≤ 5 working days). */
export function ModelRequests(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const list = useAsync(() => api.modelRequests(), onSessionExpired);
  const kpi = useAsync(() => api.modelRequestKpi(), onSessionExpired);
  const [scoping, setScoping] = useState<ModelRequest>();
  const rows = list.data ?? [];
  const reload = () => { list.reload(); kpi.reload(); };
  return (
    <section>
      <PageTitle sub="Devices outside the 133-model inventory: requested by the device administrator, scoped by integration testing.">Model requests</PageTitle>
      {kpi.data && (
        <div className="tiles">
          <KpiTile label="Median scoping turnaround (working days)" value={kpi.data.medianScopingWorkingDays ?? '–'} target={`Target ≤ ${kpi.data.targetWorkingDays}`} status={kpi.data.medianScopingWorkingDays == null ? 'UNKNOWN' : kpi.data.medianScopingWorkingDays <= kpi.data.targetWorkingDays ? 'MET' : 'MISSED'} />
          <KpiTile label="Awaiting scoping" value={kpi.data.awaitingScoping} />
          <KpiTile label="Scoped within target" value={kpi.data.withinTargetPct == null ? '–' : `${kpi.data.withinTargetPct} %`} />
        </div>
      )}
      {can(session.roles, 'deviceAdmin') && <NewModelRequest {...props} onDone={reload} />}
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      {list.data && rows.length === 0 && <Empty>No model requests yet.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'request')}</caption>
            <thead><tr><th scope="col">Reference</th><th scope="col">Model</th><th scope="col">Family</th><th scope="col">Urgency</th><th scope="col">Status</th><th scope="col">Scoping</th><th scope="col">Requested</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row" className="mono">{r.requestRef}</th>
                  <td>{r.manufacturer} {r.model}</td>
                  <td>{humanise(r.family)}</td>
                  <td><CodeBadge code={r.urgency === 'HIGH' ? 'HIGH' : r.urgency === 'LOW' ? 'LOW' : 'MEDIUM'} label={humanise(r.urgency)} /></td>
                  <td><CodeBadge code={r.status} /></td>
                  <td className="small">{r.scoping ? `${r.scoping.adapter ?? '–'}, ${r.scoping.effortDays ?? '?'} d, sprint ${r.scoping.targetSprint ?? '–'}` : '–'}{r.scopingWorkingDays != null ? ` (${r.scopingWorkingDays} working days)` : ''}</td>
                  <td className="small">{r.requestedBy} {formatDate(r.requestedAt)}</td>
                  <td>{can(session.roles, 'deviceModelRequestScope') && <button type="button" className="small-button" aria-label={`Scope ${r.requestRef}`} onClick={() => setScoping(r)}>Scope</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {scoping && <ScopeDialog {...props} request={scoping} onClose={() => setScoping(undefined)} onDone={() => { setScoping(undefined); reload(); }} />}
    </section>
  );
}

function NewModelRequest({ client, onSessionExpired, onDone }: ScreenProps & { onDone: () => void }) {
  const api = devicesApi(client);
  const empty = { manufacturer: '', model: '', family: '', connectivity: '', interfaceDocumentIds: '', sites: '', urgency: 'NORMAL' };
  const [f, setF] = useState(empty);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.manufacturer.trim()) errs.push({ field: 'mr-manufacturer', message: 'Enter the manufacturer (brand)' });
    if (!f.model.trim()) errs.push({ field: 'mr-model', message: 'Enter the model' });
    if (!f.family) errs.push({ field: 'mr-family', message: 'Choose the family' });
    if (!f.connectivity.trim()) errs.push({ field: 'mr-connectivity', message: 'Describe the connectivity (ports, protocols, vendor software)' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { const r = await api.requestModel({ manufacturer: f.manufacturer.trim(), model: f.model.trim(), family: f.family, connectivity: f.connectivity.trim(), interfaceDocumentIds: splitList(f.interfaceDocumentIds), sites: splitList(f.sites), urgency: f.urgency }); return `Request ${r.requestRef} submitted – integration testing will scope it.`; })) { setF(empty); onDone(); }
  }
  return (
    <details className="card" open={false}>
      <summary>Request a new model</summary>
      <form className="form" onSubmit={submit} noValidate aria-label="Request a new model">
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="mr-manufacturer" label="Manufacturer" required error={errorFor(errors, 'mr-manufacturer')}>{(d) => <input id="mr-manufacturer" aria-describedby={d} maxLength={200} value={f.manufacturer} onChange={(e) => setF({ ...f, manufacturer: e.target.value })} />}</IdField>
          <IdField id="mr-model" label="Model" required error={errorFor(errors, 'mr-model')}>{(d) => <input id="mr-model" aria-describedby={d} maxLength={300} value={f.model} onChange={(e) => setF({ ...f, model: e.target.value })} />}</IdField>
          <IdField id="mr-family" label="Family" required error={errorFor(errors, 'mr-family')}>{(d) => <select id="mr-family" aria-describedby={d} value={f.family} onChange={(e) => setF({ ...f, family: e.target.value })}><SelectOptions values={DEVICE_FAMILIES} empty="Choose…" /></select>}</IdField>
          <Field label="Urgency">{(id) => <select id={id} value={f.urgency} onChange={(e) => setF({ ...f, urgency: e.target.value })}><SelectOptions values={URGENCY_LEVELS} /></select>}</Field>
        </div>
        <IdField id="mr-connectivity" label="Connectivity" required error={errorFor(errors, 'mr-connectivity')}>{(d) => <textarea id="mr-connectivity" rows={2} maxLength={1000} aria-describedby={d} value={f.connectivity} onChange={(e) => setF({ ...f, connectivity: e.target.value })} />}</IdField>
        <div className="form-grid">
          <Field label="Interface documents (IDs)" hint="comma separated">{(id) => <input id={id} value={f.interfaceDocumentIds} onChange={(e) => setF({ ...f, interfaceDocumentIds: e.target.value })} />}</Field>
          <Field label="Sites" hint="comma separated">{(id) => <input id={id} value={f.sites} onChange={(e) => setF({ ...f, sites: e.target.value.toUpperCase() })} />}</Field>
        </div>
        <div className="actions"><button type="submit" disabled={action.busy}>Submit request</button></div>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </details>
  );
}

function ScopeDialog({ client, onSessionExpired, request, onClose, onDone }: ScreenProps & { request: ModelRequest; onClose: () => void; onDone: () => void }) {
  const api = devicesApi(client);
  const s = request.scoping ?? {};
  const [f, setF] = useState({ status: request.status === 'SUBMITTED' ? 'SCOPING' : request.status, existingFamilyAdapter: !!s.existingFamilyAdapter, adapter: s.adapter ?? '', effortDays: s.effortDays?.toString() ?? '', targetSprint: s.targetSprint ?? '', note: s.note ?? '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (f.status === 'SCOPED' && !f.adapter.trim()) errs.push({ field: 'sc-adapter', message: 'A scoped request needs the adapter' });
    if (f.effortDays && (!/^\d+$/.test(f.effortDays) || Number(f.effortDays) > 500)) errs.push({ field: 'sc-effort', message: 'Effort must be 0–500 days' });
    if (f.status === 'SCOPED' && !f.effortDays) errs.push({ field: 'sc-effort', message: 'A scoped request needs the effort' });
    if (f.status === 'SCOPED' && !f.targetSprint.trim()) errs.push({ field: 'sc-sprint', message: 'A scoped request needs the target sprint' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await api.scopeRequest(request.id, { status: f.status, existingFamilyAdapter: f.existingFamilyAdapter, ...(f.adapter ? { adapter: f.adapter.trim() } : {}), ...(f.effortDays ? { effortDays: Number(f.effortDays) } : {}), ...(f.targetSprint ? { targetSprint: f.targetSprint.trim() } : {}), ...(f.note ? { note: f.note.trim() } : {}) }); })) onDone();
  }
  return (
    <Dialog title={`Scope ${request.requestRef}`} onClose={onClose}>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <Field label="Status">{(id) => <select id={id} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><SelectOptions values={SCOPING_STATUSES} /></select>}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.existingFamilyAdapter} onChange={(e) => setF({ ...f, existingFamilyAdapter: e.target.checked })} /> An existing family adapter can be reused</label>
        <IdField id="sc-adapter" label="Adapter" error={errorFor(errors, 'sc-adapter')}>{(d) => <input id="sc-adapter" aria-describedby={d} maxLength={60} value={f.adapter} onChange={(e) => setF({ ...f, adapter: e.target.value })} />}</IdField>
        <IdField id="sc-effort" label="Effort (days)" error={errorFor(errors, 'sc-effort')}>{(d) => <input id="sc-effort" type="number" min={0} max={500} aria-describedby={d} value={f.effortDays} onChange={(e) => setF({ ...f, effortDays: e.target.value })} />}</IdField>
        <IdField id="sc-sprint" label="Target sprint" error={errorFor(errors, 'sc-sprint')}>{(d) => <input id="sc-sprint" aria-describedby={d} maxLength={20} value={f.targetSprint} onChange={(e) => setF({ ...f, targetSprint: e.target.value })} />}</IdField>
        <Field label="Note">{(id) => <textarea id={id} rows={2} maxLength={1000} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />}</Field>
        <div className="actions"><button type="submit" disabled={action.busy}>Save scoping</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

// ====================================================================== governance & promotion

/** `#/devices/governance` – complete change history and environment promotion (export → import with diff → approval). */
export function DeviceGovernance(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [configurationId, setConfigurationId] = useState('');
  const [applied, setApplied] = useState('');
  const changes = useAsync(() => api.changes({ configurationId: applied || undefined }), onSessionExpired, [applied]);
  const rows = (changes.data ?? []).slice(0, 200);
  return (
    <section>
      <PageTitle sub="Every change with actor, diff and approval reference. Promotion between environments carries no secret values and needs a four-eyes approval in the target environment.">Governance & promotion</PageTitle>
      {can(session.roles, 'deviceAdmin') && <Promotion {...props} />}
      <form className="filters" aria-label="Filter changes" onSubmit={(e) => { e.preventDefault(); setApplied(configurationId.trim().toUpperCase()); }}>
        <Field label="Configuration">{(id) => <input id={id} value={configurationId} maxLength={10} placeholder="CFG-000001" onChange={(e) => setConfigurationId(e.target.value)} />}</Field>
        <div className="filter-actions"><button type="submit">Filter</button></div>
      </form>
      {changes.loading && !changes.data && <Loading />}
      <ErrorAlert error={changes.error} />
      {changes.data && rows.length === 0 && <Empty>No changes recorded.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Change history{changes.data && changes.data.length > rows.length ? ` (latest ${rows.length} of ${changes.data.length})` : ''}</caption>
            <thead><tr><th scope="col">When</th><th scope="col">Entity</th><th scope="col">Action</th><th scope="col">Actor</th><th scope="col">Step</th><th scope="col">Approval</th><th scope="col">Changed paths</th></tr></thead>
            <tbody>
              {rows.map((c, i) => (
                <tr key={i}>
                  <td className="small">{formatDate(c.at)}</td>
                  <th scope="row" className="small">{humanise(c.entityType)} <span className="mono">{c.entityId}</span>{c.version ? ` v${c.version}` : ''}</th>
                  <td>{humanise(c.action)}</td>
                  <td>{c.actor}</td>
                  <td>{c.step ?? '–'}</td>
                  <td className="mono small">{c.approvalRef ? c.approvalRef.slice(0, 8) : '–'}</td>
                  <td className="small">{Array.isArray(c.diff) ? (c.diff as { path?: string }[]).map((d) => d.path).filter(Boolean).slice(0, 5).join(', ') || '–' : c.diff && typeof c.diff === 'object' ? Object.keys(c.diff).slice(0, 5).join(', ') : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Promotion({ client, onSessionExpired }: ScreenProps) {
  const api = devicesApi(client);
  const [ids, setIds] = useState('');
  const [exported, setExported] = useState<ExportPayload>();
  const [importText, setImportText] = useState('');
  const [imported, setImported] = useState<ImportResult>();
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function doExport(e: FormEvent) {
    e.preventDefault();
    const list = splitList(ids.toUpperCase());
    if (!list.length || list.some((x) => !/^CFG-\d{6}$/.test(x))) { setErrors([{ field: 'gov-ids', message: 'Enter configuration references such as CFG-000001, separated by commas' }]); return; }
    setErrors([]);
    await action.run(async () => { const p = await api.exportConfigurations(list); setExported(p); setImportText(JSON.stringify(p, null, 2)); return `Exported ${plural(p.items.length, 'configuration')} (SHA-256 ${p.sha256.slice(0, 12)}…) – secret values are not included.`; });
  }
  async function doImport(e: FormEvent) {
    e.preventDefault();
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(importText) as Record<string, unknown>;
    } catch {
      setErrors([{ field: 'gov-import', message: 'Paste the export file content (JSON)' }]);
      return;
    }
    setErrors([]);
    await action.run(async () => { const r = await api.importExport(payload); setImported(r); return `Import staged – a device approver must approve it; the drafts then need tests in this environment.`; });
  }
  return (
    <div className="grid-2">
      <form className="card form" onSubmit={doExport} noValidate aria-label="Export configurations">
        <h2>Export</h2>
        <ErrorSummary errors={errors.filter((x) => x.field === 'gov-ids')} />
        <IdField id="gov-ids" label="Configurations" required error={errorFor(errors, 'gov-ids')}>{(d) => <input id="gov-ids" aria-describedby={d} value={ids} onChange={(e) => setIds(e.target.value)} />}</IdField>
        <div className="actions"><button type="submit" disabled={action.busy}>Export</button></div>
        {exported && (
          <a className="button secondary" download={`device-export-${exported.exportId.slice(0, 8)}.json`} href={`data:application/json;charset=utf-8,${encodeURIComponent(JSON.stringify(exported, null, 2))}`}>Save export file</a>
        )}
      </form>
      <form className="card form" onSubmit={doImport} noValidate aria-label="Import configurations">
        <h2>Import with diff</h2>
        <ErrorSummary errors={errors.filter((x) => x.field === 'gov-import')} />
        <IdField id="gov-import" label="Export file content (JSON)" required error={errorFor(errors, 'gov-import')}>{(d) => <textarea id="gov-import" className="mono" rows={5} aria-describedby={d} value={importText} onChange={(e) => setImportText(e.target.value)} />}</IdField>
        <div className="actions"><button type="submit" disabled={action.busy}>Stage import</button></div>
      </form>
      <div className="span-all">
        <ActionFeedback error={action.error} message={action.message} />
        {imported && <section className="card" aria-label="Staged import"><ImportItems result={imported} /></section>}
      </div>
    </div>
  );
}
