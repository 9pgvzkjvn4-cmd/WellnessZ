import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import {
  ASSOCIATION_METHODS, CONFIG_STATUSES, DISPOSALS, PREREQ_STATUSES, certificatePdfUrl, devicesApi,
  type Configuration, type DevicesApi, type DriverManifest, type JsonSchema, type OptionComparison, type PrerequisiteItem, type PreviewResult,
  type PublishResult, type Station,
} from '../../api/devices';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { plural } from '../../domain/deviceConsole';
import {
  STEP_NAMES, approvalRoleFor, canOpenStep, deepMerge, fieldId, flattenSchema, fourEyesBlock, fromFormState, getAt, initialStep, isEditable, isFinal,
  isLive, labelFor, needsSecurityCoApproval, openPrerequisites, prereqId, secretPaths, serverFindings, step4FieldId, step5Body, step6Checklist,
  stepOf, stepStatusView, storedSecrets, toFormState, validateSchemaForm, validateStep1, validateStep2, validateStep3, validateStep5,
  type BindingDraft, type FormState, type Obj, type SchemaField, type Step5Form,
} from '../../domain/deviceWizard';
import { errorFor } from '../../domain/validation';
import { navigate } from '../../routing/useHashRoute';
import type { ScreenProps } from '../types';
import { CodeBadge, CodeOptions, Hash, OneTimeToken, UnavailableNotice } from './common';

const hashParam = (name: string): string => new URLSearchParams(window.location.hash.split('?')[1] ?? '').get(name) ?? '';

// ====================================================================== list + start

/** `#/devices/wizard` – configurations (drafts, awaiting approval, published …) and "start provisioning". */
export function DeviceWizardList(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [status, setStatus] = useState('');
  const [site, setSite] = useState('');
  const list = useAsync(() => api.configurations({ status: status || undefined, siteCode: site || undefined }), onSessionExpired, [status, site]);
  const stations = useAsync(() => api.stations(), onSessionExpired);
  const sites = [...new Set((stations.data ?? []).map((s) => s.siteCode))];
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Eight steps from device to published instance: select, choose option, prerequisites, configure, bind, test, four-eyes certification and publication.">Device provisioning</PageTitle>
      {can(session.roles, 'deviceConfigWrite') && <StartProvisioning {...props} stations={stations.data ?? []} />}
      <form className="filters" aria-label="Filter configurations" onSubmit={(e) => e.preventDefault()}>
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={CONFIG_STATUSES} empty="All statuses" /></select>}</Field>
        <Field label="Site">{(id) => <select id={id} value={site} onChange={(e) => setSite(e.target.value)}><CodeOptions values={sites} empty="All sites" /></select>}</Field>
      </form>
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      {list.data && rows.length === 0 && <Empty>No configurations match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'configuration')}</caption>
            <thead><tr><th scope="col">Configuration</th><th scope="col">Instance</th><th scope="col">Model</th><th scope="col">Option</th><th scope="col">Site / station</th><th scope="col">Status</th><th scope="col">Step</th><th scope="col">Updated</th></tr></thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.configurationId}>
                  <th scope="row"><a href={`#/devices/wizard/${c.configurationId}`}>{c.configurationId}</a> <span className="small muted">v{c.version}</span></th>
                  <td className="mono small">{c.deviceInstanceId}</td>
                  <td className="mono small">{c.modelCode}</td>
                  <td>{c.option ?? '–'}</td>
                  <td className="small">{c.siteCode ?? '–'}{c.stationCode ? ` / ${c.stationCode}` : ''}</td>
                  <td><CodeBadge code={c.status} /> {c.paused && <CodeBadge code="PAUSED" label="Paused" />}</td>
                  <td className="small">{c.currentStep} of 8 – {STEP_NAMES[c.currentStep - 1] ?? ''}</td>
                  <td className="small">{formatDate(c.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function StartProvisioning({ client, session, onSessionExpired, stations }: ScreenProps & { stations: Station[] }) {
  const api = devicesApi(client);
  const [modelCode, setModelCode] = useState(hashParam('model'));
  useEffect(() => {
    // "Provision this model" may land here while the list is already open (same route, new query)
    const onHash = () => { const m = hashParam('model'); if (m) setModelCode(m); };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  const [siteCode, setSiteCode] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const testerOnly = !can(session.roles, 'deviceAdmin');
  const sites = [...new Set(stations.filter((s) => !testerOnly || s.testSite).map((s) => s.siteCode))];
  async function start(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!/^(MD-\d{3}|WZ-\d{6})$/.test(modelCode.trim())) errs.push({ field: 'start-model', message: 'Enter a catalogue model code such as MD-027' });
    if (testerOnly && !siteCode) errs.push({ field: 'start-site', message: 'Integration testers provision on test sites only – choose the test site' });
    setErrors(errs);
    if (errs.length) return;
    let created: Configuration | undefined;
    if (await action.run(async () => { created = await api.createConfiguration({ modelCode: modelCode.trim(), ...(siteCode ? { siteCode } : {}) }); })) {
      if (created) navigate(`/devices/wizard/${created.configurationId}`);
    }
  }
  return (
    <form className="card inline-form" onSubmit={start} noValidate aria-labelledby="start-title">
      <h2 id="start-title">Start provisioning</h2>
      <ErrorSummary errors={errors} />
      <IdField id="start-model" label="Model code" required hint="Find the code in the device catalogue" error={errorFor(errors, 'start-model')}>
        {(d) => <input id="start-model" aria-describedby={d} aria-invalid={!!errorFor(errors, 'start-model')} value={modelCode} maxLength={9} onChange={(e) => setModelCode(e.target.value.toUpperCase())} />}
      </IdField>
      <IdField id="start-site" label="Site" required={testerOnly} error={errorFor(errors, 'start-site')}>
        {(d) => <select id="start-site" aria-describedby={d} value={siteCode} onChange={(e) => setSiteCode(e.target.value)}><CodeOptions values={sites} empty="Decide at step 5" /></select>}
      </IdField>
      <div className="actions"><button type="submit" disabled={action.busy}>Create draft</button> <a href="#/devices/catalogue">Browse the catalogue</a></div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

// ====================================================================== the wizard

interface StepProps extends ScreenProps {
  cfg: Configuration;
  api: DevicesApi;
  editable: boolean;
  /** Server returned the updated configuration; optionally move to another step. */
  onSaved: (cfg: Configuration, goTo?: number) => void;
  reload: () => void;
}

/** `#/devices/wizard/{configurationId}` – the eight-step provisioning wizard with stepper and lifecycle actions. */
export function DeviceWizard(props: ScreenProps & { configurationId: string }) {
  const { client, onSessionExpired, configurationId } = props;
  const api = devicesApi(client);
  const loaded = useAsync(() => api.configuration(configurationId), onSessionExpired, [configurationId]);
  const [local, setLocal] = useState<Configuration>();
  const [step, setStep] = useState<number>();
  const [notice, setNotice] = useState<string[]>([]);
  const cfg = local ?? loaded.data;
  const current = step ?? (cfg ? initialStep(cfg) : 1);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const moved = useRef(false);

  useEffect(() => {
    if (moved.current) headingRef.current?.focus();
  }, [current]);

  // Four-eyes work happens in other people's sessions (nurse review, approvals): refresh when the window regains focus.
  const reloadRef = useRef<() => void>(() => {});
  useEffect(() => {
    reloadRef.current = () => { setLocal(undefined); loaded.reload(); };
  });
  useEffect(() => {
    const onFocus = () => reloadRef.current();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, []);

  function goTo(n: number) {
    moved.current = true;
    setStep(n);
  }
  function onSaved(next: Configuration, to?: number) {
    setLocal(next);
    setNotice(next.warnings ?? []);
    if (to) goTo(to);
    else setStep(current); // stay on this step even if the server moved currentStep on
  }
  function reload() {
    setLocal(undefined);
    loaded.reload();
  }

  if (!cfg) {
    return (
      <section>
        <p className="breadcrumb"><a href="#/devices/wizard">Provisioning</a> › {configurationId}</p>
        {loaded.loading && <Loading />}
        <ErrorAlert error={loaded.error} />
      </section>
    );
  }
  const editable = isEditable(cfg);
  const st = stepOf(cfg, current);
  const sv = stepStatusView(st.status);
  const stepProps: StepProps = { ...props, cfg, api, editable, onSaved, reload };
  return (
    <section>
      <p className="breadcrumb"><a href="#/devices/wizard">Provisioning</a> › {cfg.configurationId}</p>
      <PageTitle sub={`Device instance ${cfg.deviceInstanceId} · version ${cfg.version} · model ${cfg.modelCode}${cfg.option ? ` · option ${cfg.option}` : ''}${cfg.stationCode ? ` · ${cfg.siteCode} / ${cfg.stationCode}` : ''}`}>
        Provision {cfg.configurationId}
      </PageTitle>
      <LifecycleBar {...stepProps} />
      {notice.length > 0 && <Alert kind="warning">Warnings: {notice.map((w) => humanise(w)).join('; ')}</Alert>}
      <nav aria-label="Wizard steps" className="stepper-nav">
        <ol className="stepper">
          {STEP_NAMES.map((name, i) => {
            const n = i + 1;
            const v = stepStatusView(stepOf(cfg, n).status);
            const open = canOpenStep(cfg, n);
            return (
              <li key={name} className={`stepper-item stepper-${v.tone}${n === current ? ' stepper-current' : ''}`}>
                <button type="button" aria-current={n === current ? 'step' : undefined} disabled={!open} onClick={() => goTo(n)}>
                  <span className="stepper-num" aria-hidden="true">{v.symbol === '○' ? n : v.symbol}</span>
                  <span className="stepper-text">
                    <span className="stepper-name">{n}. {name}</span>
                    <span className="stepper-status small">{v.label}{!open ? ' (locked)' : ''}</span>
                  </span>
                </button>
              </li>
            );
          })}
        </ol>
      </nav>
      <section className="card wizard-panel" aria-labelledby="wiz-step-title">
        <h2 id="wiz-step-title" ref={headingRef} tabIndex={-1}>
          Step {current} of 8: {STEP_NAMES[current - 1]} <span className="small"><span aria-hidden="true">{sv.symbol}</span> {sv.label}</span>
        </h2>
        {(st.errors?.length ?? 0) > 0 && st.status !== 'VALID' && (
          <div className="alert alert-warning" role="status">
            <p>This step is not complete yet:</p>
            <ul>{st.errors!.map((e) => <li key={`${e.field}-${e.code}`}>{e.message}</li>)}</ul>
          </div>
        )}
        {!editable && current <= 6 && (
          <p className="small muted">{cfg.paused ? 'The configuration is paused – resume it to make changes.' : `Read-only: the configuration is ${humanise(cfg.status).toLowerCase()}.${isLive(cfg.status) ? ' Revise it to change settings.' : ''}`}</p>
        )}
        {current === 1 && <Step1 key={`1-${cfg.version}-${st.status}-${st.updatedAt ?? ''}`} {...stepProps} />}
        {current === 2 && <Step2 key={`2-${cfg.version}-${st.status}-${st.updatedAt ?? ''}`} {...stepProps} />}
        {current === 3 && <Step3 key={`3-${cfg.version}-${st.status}-${st.updatedAt ?? ''}`} {...stepProps} />}
        {current === 4 && <Step4 key={`4-${cfg.version}-${st.status}-${st.updatedAt ?? ''}`} {...stepProps} />}
        {current === 5 && <Step5 key={`5-${cfg.version}-${st.status}-${st.updatedAt ?? ''}`} {...stepProps} />}
        {current === 6 && <Step6 key={`6-${cfg.version}`} {...stepProps} />}
        {current === 7 && <Step7 key={`7-${cfg.version}-${cfg.status}`} {...stepProps} />}
        {current === 8 && <Step8 key={`8-${cfg.version}-${cfg.status}`} {...stepProps} />}
      </section>
      <HistoryPanel {...stepProps} />
    </section>
  );
}

// ---------------------------------------------------------------------- lifecycle (pause, revise, suspend, decommission)

function LifecycleBar({ cfg, api, session, onSessionExpired, onSaved }: StepProps) {
  const action = useAction(onSessionExpired);
  const [dialog, setDialog] = useState<'suspend' | 'decommission' | 'decide'>();
  const writer = can(session.roles, 'deviceConfigWrite');
  const admin = can(session.roles, 'deviceAdmin');
  const final = isFinal(cfg.status);
  const pending = cfg.decommissionRequest && !cfg.disposal ? cfg.decommissionRequest : undefined;
  async function act(fn: () => Promise<Configuration>, msg: string, toStep?: number) {
    let next: Configuration | undefined;
    if (await action.run(async () => { next = await fn(); return msg; })) if (next) onSaved(next, toStep);
  }
  return (
    <div className="card lifecycle">
      <div className="toolbar">
        <div className="toolbar-left">
          <CodeBadge code={cfg.status} /> {cfg.paused && <CodeBadge code="PAUSED" label="Paused" />}
          {cfg.clonedFrom && <span className="small muted">cloned from {cfg.clonedFrom}</span>}
        </div>
        <div className="actions">
          {writer && !final && <button type="button" className="secondary" disabled={action.busy} onClick={() => void act(() => (cfg.paused ? api.resume(cfg.configurationId) : api.pause(cfg.configurationId)), cfg.paused ? 'Resumed.' : 'Paused – nothing can change until it is resumed.')}>{cfg.paused ? 'Resume' : 'Pause'}</button>}
          {writer && isLive(cfg.status) && <button type="button" className="secondary" disabled={action.busy} onClick={() => void act(() => api.revise(cfg.configurationId), 'New draft version created – the published version keeps running until the new one is published.', 4)}>Revise</button>}
          {admin && cfg.status === 'PUBLISHED' && <button type="button" className="secondary" onClick={() => setDialog('suspend')}>Suspend</button>}
          {admin && cfg.status === 'SUSPENDED' && <button type="button" className="secondary" disabled={action.busy} onClick={() => void act(() => api.resumeService(cfg.configurationId), 'Service resumed.')}>Resume service</button>}
          {admin && isLive(cfg.status) && !pending && <button type="button" className="danger-button" onClick={() => setDialog('decommission')}>Decommission</button>}
        </div>
      </div>
      {cfg.status === 'QUARANTINED' && (
        <Alert kind="error">
          <strong>Quarantined:</strong> the endpoint was isolated by Security Operations{cfg.statusReason ? ` (${humanise(cfg.statusReason)})` : ''}. Re-certification is required: revise → test → four-eyes approval → publish, then Security Operations releases the endpoint.
        </Alert>
      )}
      {cfg.status === 'SUSPENDED' && <Alert kind="warning">Suspended{cfg.statusReason ? `: ${cfg.statusReason}` : ''}. Nurses cannot capture from this device.</Alert>}
      {pending && (
        <Alert kind="warning">
          Decommissioning requested by {pending.requestedBy} ({humanise(pending.disposal)}{pending.transferTo ? ` to ${pending.transferTo}` : ''}): {pending.reason}. Awaiting four-eyes approval.
          {can(session.roles, 'deviceApprove') && session.roles.includes('DEVICE_APPROVER') && (
            <> <button type="button" className="secondary small-button" onClick={() => setDialog('decide')}>Decide</button></>
          )}
        </Alert>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {dialog === 'suspend' && <ReasonDialog title={`Suspend ${cfg.configurationId}`} label="Reason" submit="Suspend" onClose={() => setDialog(undefined)} onSubmit={async (reason) => { setDialog(undefined); await act(() => api.suspend(cfg.configurationId, reason), 'Suspended.'); }} />}
      {dialog === 'decommission' && <DecommissionDialog onClose={() => setDialog(undefined)} onSubmit={async (body) => { setDialog(undefined); await act(() => api.decommission(cfg.configurationId, body), 'Decommissioning requested – a device approver must approve it (four-eyes).'); }} />}
      {dialog === 'decide' && pending && (
        <DecideDialog title="Decommissioning decision" requestedBy={pending.requestedBy} username={session.username} onClose={() => setDialog(undefined)}
          onSubmit={async (approve, note) => { setDialog(undefined); await act(() => api.decideDecommission(cfg.configurationId, approve, note), approve ? 'Decommissioned: endpoint revoked, secrets destroyed, bindings removed.' : 'Decommissioning rejected.'); }} />
      )}
    </div>
  );
}

function ReasonDialog({ title, label, submit, onClose, onSubmit }: { title: string; label: string; submit: string; onClose: () => void; onSubmit: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string>();
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); if (!reason.trim()) { setError(`${label} is required`); return; } void onSubmit(reason.trim()); }}>
        <IdField id="dlg-reason" label={label} required error={error}>
          {(d) => <textarea id="dlg-reason" rows={3} maxLength={500} aria-describedby={d} aria-invalid={!!error} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </IdField>
        <div className="actions"><button type="submit">{submit}</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

function DecommissionDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (b: { reason: string; disposal: string; transferTo?: string }) => Promise<void> }) {
  const [f, setF] = useState({ reason: '', disposal: '', transferTo: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.reason.trim()) errs.push({ field: 'dc-reason', message: 'Enter the reason' });
    if (!f.disposal) errs.push({ field: 'dc-disposal', message: 'Choose the disposal' });
    if (f.disposal === 'TRANSFERRED' && !f.transferTo.trim()) errs.push({ field: 'dc-transfer', message: 'Enter where the device is transferred to' });
    setErrors(errs);
    if (!errs.length) void onSubmit({ reason: f.reason.trim(), disposal: f.disposal, ...(f.disposal === 'TRANSFERRED' ? { transferTo: f.transferTo.trim() } : {}) });
  }
  return (
    <Dialog title="Decommission device" onClose={onClose}>
      <form className="form" noValidate onSubmit={submit}>
        <ErrorSummary errors={errors} />
        <p className="small">After four-eyes approval the endpoint is revoked (within 5 minutes), the secrets destroyed and the bindings removed. Device data stays under its retention class.</p>
        <IdField id="dc-reason" label="Reason" required error={errorFor(errors, 'dc-reason')}>{(d) => <textarea id="dc-reason" rows={2} maxLength={500} aria-describedby={d} value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />}</IdField>
        <IdField id="dc-disposal" label="Disposal" required error={errorFor(errors, 'dc-disposal')}>{(d) => <select id="dc-disposal" aria-describedby={d} value={f.disposal} onChange={(e) => setF({ ...f, disposal: e.target.value })}><SelectOptions values={DISPOSALS} empty="Choose…" /></select>}</IdField>
        {f.disposal === 'TRANSFERRED' && <IdField id="dc-transfer" label="Transferred to" required error={errorFor(errors, 'dc-transfer')}>{(d) => <input id="dc-transfer" aria-describedby={d} maxLength={200} value={f.transferTo} onChange={(e) => setF({ ...f, transferTo: e.target.value })} />}</IdField>}
        <div className="actions"><button type="submit" className="danger-button">Request decommissioning</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

/** Approve / reject with a note; approve is disabled for the requester (four-eyes). */
export function DecideDialog({ title, requestedBy, username, onClose, onSubmit }: { title: string; requestedBy?: string; username?: string; onClose: () => void; onSubmit: (approve: boolean, note: string) => Promise<void> }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  const self = !!username && username === requestedBy;
  return (
    <Dialog title={title} onClose={onClose}>
      <div className="form">
        {self && <Alert kind="info">You requested this change – another approver must approve it (four-eyes).</Alert>}
        <IdField id="dec-note" label="Note (required to reject)" error={error}>{(d) => <textarea id="dec-note" rows={3} maxLength={1000} aria-describedby={d} value={note} onChange={(e) => setNote(e.target.value)} />}</IdField>
        <div className="actions">
          <button type="button" disabled={self} onClick={() => void onSubmit(true, note.trim())}>Approve</button>
          <button type="button" className="danger-button" onClick={() => { if (!note.trim()) { setError('Enter the reason for rejecting'); return; } void onSubmit(false, note.trim()); }}>Reject</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </Dialog>
  );
}

function HistoryPanel({ cfg, api, onSessionExpired, session }: StepProps) {
  const [open, setOpen] = useState(false);
  const allowed = can(session.roles, 'deviceWizard');
  const history = useAsync(() => (open && allowed ? api.history(cfg.configurationId) : Promise.resolve(undefined)), onSessionExpired, [open, cfg.configurationId, cfg.version, cfg.status]);
  return (
    <details className="card" onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}>
      <summary>Change history (versioned, with diffs)</summary>
      {history.loading && open && <Loading />}
      <ErrorAlert error={history.error} />
      {history.data && (
        <table>
          <caption className="visually-hidden">Change history</caption>
          <thead><tr><th scope="col">When</th><th scope="col">Who</th><th scope="col">Action</th><th scope="col">Step</th><th scope="col">Changes</th></tr></thead>
          <tbody>
            {history.data.map((h, i) => (
              <tr key={i}>
                <td className="small">{formatDate(h.at)}</td>
                <td>{h.actor}</td>
                <td>{humanise(h.action)} <span className="small muted">v{h.version}</span></td>
                <td>{h.step ?? '–'}</td>
                <td className="small">{h.diff?.length ? h.diff.slice(0, 6).map((d) => d.path).join(', ') + (h.diff.length > 6 ? ` +${h.diff.length - 6}` : '') : h.detail ? Object.keys(h.detail).join(', ') : '–'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </details>
  );
}

// ---------------------------------------------------------------------- step 1 – select device

function Step1({ cfg, api, editable, onSessionExpired, onSaved }: StepProps) {
  const saved = (stepOf(cfg, 1).data ?? {}) as Obj;
  const [f, setF] = useState({ modelCode: cfg.modelCode ?? '', serial: cfg.serial ?? '', udi: String(saved.udi ?? ''), equipmentDeviceId: cfg.equipmentDeviceId ?? '', firmware: cfg.firmware ?? '', softwareVersion: cfg.softwareVersion ?? '' });
  const [query, setQuery] = useState('');
  const [applied, setApplied] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const model = useAsync(() => (f.modelCode ? api.model(f.modelCode) : Promise.resolve(undefined)), onSessionExpired, [f.modelCode]);
  const results = useAsync(() => (applied ? api.models({ q: applied }) : Promise.resolve([])), onSessionExpired, [applied]);
  const action = useAction(onSessionExpired);
  const m = model.data;
  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = validateStep1(f.modelCode);
    setErrors(errs);
    if (errs.length) return;
    const body: Obj = { modelCode: f.modelCode };
    for (const k of ['serial', 'udi', 'equipmentDeviceId', 'firmware', 'softwareVersion'] as const) if (f[k].trim()) body[k] = f[k].trim();
    let next: Configuration | undefined;
    let failure: unknown;
    const ok = await action.run(async () => { try { next = await api.putStep(cfg.configurationId, 1, body); } catch (x) { failure = x; throw x; } });
    if (ok && next) onSaved(next, 2);
    else setErrors(serverFindings(failure, (fld) => (fld === 'modelCode' ? 'wiz-model' : undefined), 'wiz-model'));
  }
  return (
    <form className="form" onSubmit={save} noValidate aria-label="Select device">
      <ErrorSummary errors={errors} />
      <IdField id="wiz-model" label="Selected model" required error={errorFor(errors, 'wiz-model')}>
        {(d) => <input id="wiz-model" aria-describedby={d} readOnly value={m ? `${m.manufacturer} ${m.model} (${m.modelCode})` : f.modelCode} />}
      </IdField>
      {m && (
        <div className="grid-2">
          <Dl items={[['Family', m.familyLabel ?? humanise(m.family)], ['Lifecycle', <CodeBadge code={m.lifecycle} />], ['Security posture', <CodeBadge code={m.security.posture} />], ['Supported options', m.options.filter((o) => o.supported).map((o) => o.option).join(', ')]]} />
          <Dl items={[['US regulatory', humanise(m.regulatory.find((r) => r.market === 'US')?.status)], ['EU regulatory', humanise(m.regulatory.find((r) => r.market === 'EU')?.status)], ['Certified drivers', m.drivers.filter((d) => d.status === 'CERTIFIED').map((d) => `${d.packageId}@${d.version}`).join(', ') || 'None'], ['All drivers', String(m.drivers.length)]]} />
        </div>
      )}
      {m?.security.posture === 'BLOCKED' && <Alert kind="error">The model's security posture is blocked – it cannot be provisioned unless Security Operations approves an exception.</Alert>}
      {m?.lifecycle === 'DEPRECATED' && <Alert kind="warning">This model is deprecated – it can be provisioned, but plan its replacement.</Alert>}
      {editable && (
        <fieldset>
          <legend>Change the model</legend>
          <div className="inline-form">
            <Field label="Search by family, brand or model">{(id) => <input id={id} value={query} maxLength={80} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); setApplied(query.trim()); } }} />}</Field>
            <button type="button" className="secondary" onClick={() => setApplied(query.trim())}>Search</button>
          </div>
          {results.data && applied && (results.data.length === 0 ? <Empty>No match. <a href="#/devices/model-requests">Request a new model</a>.</Empty> : (
            <ul className="plain-list" aria-label="Search results">
              {results.data.slice(0, 12).map((r) => (
                <li key={r.modelCode}>
                  <label className="inline-row"><input type="radio" name="wiz-model-pick" checked={f.modelCode === r.modelCode} onChange={() => setF({ ...f, modelCode: r.modelCode })} /> {r.manufacturer} {r.model} <span className="small muted mono">{r.modelCode}</span> <CodeBadge code={r.posture} /></label>
                </li>
              ))}
            </ul>
          ))}
        </fieldset>
      )}
      <fieldset disabled={!editable}>
        <legend>Instance identification (optional)</legend>
        <div className="form-grid">
          <Field label="Serial number">{(id) => <input id={id} maxLength={60} value={f.serial} onChange={(e) => setF({ ...f, serial: e.target.value })} />}</Field>
          <Field label="UDI">{(id) => <input id={id} maxLength={120} value={f.udi} onChange={(e) => setF({ ...f, udi: e.target.value })} />}</Field>
          <Field label="Equipment-register ID (E14)">{(id) => <input id={id} maxLength={60} value={f.equipmentDeviceId} onChange={(e) => setF({ ...f, equipmentDeviceId: e.target.value })} />}</Field>
          <Field label="Firmware">{(id) => <input id={id} maxLength={40} value={f.firmware} onChange={(e) => setF({ ...f, firmware: e.target.value })} />}</Field>
          <Field label="Vendor software version">{(id) => <input id={id} maxLength={40} value={f.softwareVersion} onChange={(e) => setF({ ...f, softwareVersion: e.target.value })} />}</Field>
        </div>
      </fieldset>
      {editable && <div className="actions"><button type="submit" disabled={action.busy}>Save and continue</button></div>}
      <ActionFeedback error={errors.length ? undefined : action.error} />
    </form>
  );
}

// ---------------------------------------------------------------------- step 2 – choose connectivity option

function Step2({ cfg, api, editable, onSessionExpired, onSaved }: StepProps) {
  const rows = useAsync(() => api.optionComparison(cfg.configurationId), onSessionExpired, [cfg.configurationId, cfg.modelCode]);
  const packs = useAsync(() => api.options(), onSessionExpired);
  const [option, setOption] = useState(cfg.option ?? '');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const pack = packs.data?.find((p) => p.option === option);
  const list: OptionComparison[] = rows.data ?? [];
  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = validateStep2(option, list);
    setErrors(errs);
    if (errs.length) return;
    let next: Configuration | undefined;
    let failure: unknown;
    const ok = await action.run(async () => { try { next = await api.putStep(cfg.configurationId, 2, { option }); } catch (x) { failure = x; throw x; } });
    if (ok && next) onSaved(next, 3);
    else setErrors(serverFindings(failure, () => `wiz-option-${option}`, 'wiz-option-first'));
  }
  return (
    <form className="form" onSubmit={save} noValidate aria-label="Choose connectivity option">
      <ErrorSummary errors={errors} />
      {rows.loading && !rows.data && <Loading />}
      <ErrorAlert error={rows.error} />
      {rows.data && list.length === 0 && <Empty>The model supports no connectivity option. Request a new model or update the catalogue entry.</Empty>}
      {list.length > 0 && (
        <fieldset disabled={!editable}>
          <legend>Supported options for this model (unsupported options are hidden)</legend>
          <div className="table-wrap">
            <table className="option-compare">
              <caption className="visually-hidden">Comparison of the supported connectivity options</caption>
              <thead><tr><th scope="col">Choose</th><th scope="col">Option</th><th scope="col">Reliability</th><th scope="col">Security level</th><th scope="col">Effort</th><th scope="col">Prerequisites</th><th scope="col">Driver package</th></tr></thead>
              <tbody>
                {list.map((r, i) => (
                  <tr key={r.option} className={option === r.option ? 'row-selected' : undefined}>
                    <td><input type="radio" name="wiz-option" id={i === 0 ? 'wiz-option-first' : `wiz-option-${r.option}`} aria-label={`${r.option} ${r.name}`} checked={option === r.option} disabled={!r.driverAvailable} onChange={() => setOption(r.option)} /></td>
                    <th scope="row"><strong>{r.option}</strong> {r.name}</th>
                    <td>{humanise(r.reliability)}</td>
                    <td><CodeBadge code={r.securityLevel === 'HIGH' ? 'PASS' : r.securityLevel === 'LOW' ? 'FAIL' : 'WARN'} label={humanise(r.securityLevel)} /></td>
                    <td>{humanise(r.effort)}</td>
                    <td className="small">{(r.prerequisites ?? []).map(humanise).join(', ')}</td>
                    <td className="small">{r.driver ? <>{r.driver.packageId}@{r.driver.version} <CodeBadge code={r.driver.status} /></> : <CodeBadge code="FAIL" label="No driver package" />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </fieldset>
      )}
      {cfg.option && option && option !== cfg.option && <Alert kind="warning">Changing the option from {cfg.option} to {option} resets steps 3–8 (prerequisites, parameters, bindings, tests) and clears stored secrets.</Alert>}
      {pack && (
        <section aria-labelledby="pack-title" className="option-help">
          <h3 id="pack-title">{pack.option} – {pack.name}</h3>
          {pack.howItWorks && <p>{pack.howItWorks}</p>}
          {pack.typicalDevices && <p className="small">Typical devices: {pack.typicalDevices.join(', ')}</p>}
          {(pack.stepGuide?.length ?? 0) > 0 && <ol className="small">{pack.stepGuide!.map((g) => <li key={g.step}>{g.text}</li>)}</ol>}
          {pack.help?.ar && <details><summary>Arabic guidance</summary><p lang="ar" dir="rtl">{pack.help.ar}</p></details>}
        </section>
      )}
      {editable && <div className="actions"><button type="submit" disabled={action.busy}>Save and continue</button></div>}
      <ActionFeedback error={errors.length ? undefined : action.error} />
    </form>
  );
}

// ---------------------------------------------------------------------- step 3 – prerequisites checklist

function Step3({ cfg, api, editable, onSessionExpired, onSaved }: StepProps) {
  const initial = ((stepOf(cfg, 3).data?.items ?? []) as PrerequisiteItem[]).map((i) => ({ ...i, evidence: { ...(i.evidence ?? {}) } }));
  const [items, setItems] = useState<PrerequisiteItem[]>(initial);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const open = openPrerequisites(items);
  function patch(code: string, change: Partial<PrerequisiteItem>) {
    setItems((list) => list.map((i) => (i.code === code ? { ...i, ...change, evidence: { ...i.evidence, ...(change.evidence ?? {}) } } : i)));
  }
  async function autoCheck() {
    let next: Configuration | undefined;
    if (await action.run(async () => { next = await api.autoCheck(cfg.configurationId); return 'Automatic checks re-run.'; })) if (next) onSaved(next);
  }
  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = validateStep3(items);
    setErrors(errs);
    if (errs.length) return;
    const body = {
      items: items.map((i) => {
        const ev = Object.fromEntries(Object.entries(i.evidence ?? {}).filter(([, v]) => typeof v === 'string' && v.trim()));
        return { code: i.code, status: i.status, ...(Object.keys(ev).length ? { evidence: ev } : {}) };
      }),
    };
    let next: Configuration | undefined;
    let failure: unknown;
    const ok = await action.run(async () => { try { next = await api.putStep(cfg.configurationId, 3, body); } catch (x) { failure = x; throw x; } });
    if (ok && next) onSaved(next, stepOf(next, 3).status === 'VALID' ? 4 : undefined);
    else setErrors(serverFindings(failure, (fld) => (fld.startsWith('items.') ? prereqId(fld.slice(6), 'reference') : undefined), 'wiz-prereq-save'));
  }
  if (items.length === 0) {
    return (
      <div>
        <p>The checklist is generated from the option pack and the model (network zone, endpoint identity, vendor software, certificates, consumables, calibration evidence, vendor posture …).</p>
        {editable && <button type="button" onClick={() => void autoCheck()} disabled={action.busy}>Generate the checklist and run the automatic checks</button>}
        <ActionFeedback error={action.error} />
      </div>
    );
  }
  return (
    <form className="form" onSubmit={save} noValidate aria-label="Prerequisites checklist">
      <ErrorSummary errors={errors} />
      <p className="small">{open.length === 0 ? 'Every required item is done or not applicable.' : `${plural(open.length, 'required item')} still open.`} Automatic items are verified by the system; record evidence for every manual item you mark done.</p>
      <ul className="prereq-list">
        {items.map((i) => (
          <li key={i.code} className="prereq-item">
            <fieldset disabled={!editable}>
              <legend>
                {i.label} <span className="badge badge-muted">{i.kind === 'AUTO' ? 'Automatic check' : 'Manual'}</span> <CodeBadge code={i.status} />
              </legend>
              {i.guidance && <p className="small muted">{i.guidance}</p>}
              {i.kind === 'AUTO' && <p className="small"><span aria-hidden="true">⚙ </span>{i.autoResult ?? 'Not checked yet – run the automatic checks.'}</p>}
              <div className="form-grid">
                {i.kind === 'MANUAL' && (
                  <IdField id={prereqId(i.code, 'status')} label="Status">{(d) => <select id={prereqId(i.code, 'status')} aria-describedby={d} value={i.status} onChange={(e) => patch(i.code, { status: e.target.value as PrerequisiteItem['status'] })}><SelectOptions values={PREREQ_STATUSES} /></select>}</IdField>
                )}
                <IdField id={prereqId(i.code, 'reference')} label={i.code === 'NETWORK_ZONE' ? 'Zone (e.g. DEVICE_VLAN)' : i.code === 'ENDPOINT_IDENTITY' ? 'Endpoint ID (optional)' : i.code === 'CALIBRATION_EVIDENCE' ? 'Equipment-register ID' : 'Evidence reference'} error={errorFor(errors, prereqId(i.code, 'reference'))}>
                  {(d) => <input id={prereqId(i.code, 'reference')} aria-describedby={d} maxLength={100} value={i.evidence?.reference ?? ''} onChange={(e) => patch(i.code, { evidence: { reference: e.target.value } })} />}
                </IdField>
                <Field label="Evidence note">{(id) => <input id={id} maxLength={500} value={i.evidence?.text ?? ''} onChange={(e) => patch(i.code, { evidence: { text: e.target.value } })} />}</Field>
                <Field label="Document ID">{(id) => <input id={id} maxLength={100} value={i.evidence?.documentId ?? ''} onChange={(e) => patch(i.code, { evidence: { documentId: e.target.value } })} />}</Field>
              </div>
            </fieldset>
          </li>
        ))}
      </ul>
      {editable && (
        <div className="actions">
          <button type="submit" id="wiz-prereq-save" disabled={action.busy}>Save checklist</button>
          <button type="button" className="secondary" disabled={action.busy} onClick={() => void autoCheck()}>Re-run automatic checks</button>
        </div>
      )}
      <ActionFeedback error={errors.length ? undefined : action.error} message={action.message} />
    </form>
  );
}

// ---------------------------------------------------------------------- step 4 – configure parameters (schema-generated)

function Step4(props: StepProps) {
  const { cfg, api, onSessionExpired } = props;
  const defaults = useAsync(() => api.defaults(cfg.configurationId), onSessionExpired, [cfg.configurationId, cfg.option]);
  if (defaults.loading && !defaults.data) return <Loading />;
  if (defaults.error) return <ErrorAlert error={defaults.error} />;
  if (!defaults.data) return null;
  return <Step4Form {...props} d={defaults.data} />;
}

function Step4Form({ cfg, api, editable, onSessionExpired, onSaved, d }: StepProps & { d: NonNullable<Awaited<ReturnType<DevicesApi['defaults']>>> }) {
  const saved = (stepOf(cfg, 4).data ?? {}) as Obj;
  const pFields = useMemo(() => flattenSchema(d.parameterSchema), [d.parameterSchema]);
  const cFields = useMemo(() => flattenSchema(d.commonSchema), [d.commonSchema]);
  const secrets = useMemo(() => secretPaths(d.parameterSchema), [d.parameterSchema]);
  const stored = useMemo(() => storedSecrets(saved.parameters as Obj | undefined, secrets, saved.secrets as Obj | undefined), [saved.parameters, saved.secrets, secrets]);
  const [pState, setP] = useState<FormState>(() => toFormState(pFields, deepMerge(d.parameters, (saved.parameters as Obj) ?? {})));
  const [cState, setC] = useState<FormState>(() => toFormState(cFields, deepMerge(d.common, (saved.common as Obj) ?? {})));
  const [secretValues, setSecretValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const isSet = (p: string) => stored.has(p) || !!secretValues[p]?.trim();

  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = [...validateSchemaForm(pFields, pState, (p) => fieldId('p', p), isSet), ...validateSchemaForm(cFields, cState, (p) => fieldId('c', p))];
    setErrors(errs);
    if (errs.length) return;
    const secretBody = Object.fromEntries(Object.entries(secretValues).filter(([, v]) => v.trim()));
    const body = { parameters: fromFormState(pFields, pState), common: fromFormState(cFields, cState), secrets: secretBody };
    let next: Configuration | undefined;
    let failure: unknown;
    const ok = await action.run(async () => { try { next = await api.putStep(cfg.configurationId, 4, body); } catch (x) { failure = x; throw x; } });
    if (ok && next) {
      setSecretValues({});
      onSaved(next, 5);
    } else {
      // a server path may name an object (e.g. parameters.deviceAddress) – point at its first control
      const ids = new Set([...pFields.map((f) => fieldId('p', f.path)), ...cFields.map((f) => fieldId('c', f.path))]);
      const idFor = (server: string) => {
        const id = step4FieldId(server);
        if (!id || ids.has(id)) return id;
        return [...ids].find((x) => x.startsWith(`${id}-`));
      };
      setErrors(serverFindings(failure, idFor, 'wiz-p4-save'));
    }
  }
  return (
    <form className="form" onSubmit={save} noValidate aria-label="Configure parameters">
      <ErrorSummary errors={errors} />
      <p className="small">Defaults come from the driver package {String(d.common.driverPackageId ?? cfg.driverPackageId)}@{String(d.common.driverVersion ?? cfg.driverVersion)} – validate rather than invent. Secrets are write-only: they go straight to the vault and are only ever shown as “set”.</p>
      {(cfg.secretsToRebind?.length ?? 0) > 0 && <Alert kind="warning">Imported configuration: re-enter the secrets {cfg.secretsToRebind!.join(', ')} for this environment.</Alert>}
      <SchemaFieldset title={`${cfg.option} connectivity parameters`} fields={pFields} state={pState} setState={setP} form="p" errors={errors} disabled={!editable}
        secrets={{ stored, values: secretValues, set: (p, v) => setSecretValues((s) => ({ ...s, [p]: v })) }} />
      <SchemaFieldset title="Common settings" fields={cFields} state={cState} setState={setC} form="c" errors={errors} disabled={!editable} />
      {editable && <div className="actions"><button type="submit" id="wiz-p4-save" disabled={action.busy}>Validate and save</button></div>}
      <ActionFeedback error={errors.length ? undefined : action.error} />
    </form>
  );
}

interface SecretControl {
  stored: Set<string>;
  values: Record<string, string>;
  set: (path: string, value: string) => void;
}

function SchemaFieldset({ title, fields, state, setState, form, errors, disabled, secrets }: { title: string; fields: SchemaField[]; state: FormState; setState: (fn: (s: FormState) => FormState) => void; form: 'p' | 'c'; errors: FieldError[]; disabled: boolean; secrets?: SecretControl }) {
  const groups = new Map<string, SchemaField[]>();
  for (const f of fields) groups.set(f.group, [...(groups.get(f.group) ?? []), f]);
  return (
    <fieldset disabled={disabled} className="schema-form">
      <legend>{title}</legend>
      {[...groups.entries()].map(([group, list]) => (
        <div key={group || 'general'} className="schema-group">
          {group && <h3 className="schema-group-title">{labelFor(group)}</h3>}
          <div className="form-grid">
            {list.map((f) => <SchemaInput key={f.path} f={f} form={form} state={state} setState={setState} error={errorFor(errors, fieldId(form, f.path))} secrets={secrets} />)}
          </div>
        </div>
      ))}
    </fieldset>
  );
}

function SchemaInput({ f, form, state, setState, error, secrets }: { f: SchemaField; form: 'p' | 'c'; state: FormState; setState: (fn: (s: FormState) => FormState) => void; error?: string; secrets?: SecretControl }) {
  const id = fieldId(form, f.path);
  const label = f.group && f.path.startsWith(`${f.group}.`) ? labelFor(f.path.split('.').slice(-1)[0] ?? f.path) : f.label;
  const req = f.alwaysRequired;
  const set = (v: string | boolean | string[]) => setState((s) => ({ ...s, [f.path]: v }));
  const s: JsonSchema = f.schema;
  if (f.kind === 'secret' && secrets) {
    const isStored = secrets.stored.has(f.path);
    return (
      <IdField id={id} label={<>{label} {isStored && <span className="badge badge-complete"><span aria-hidden="true">● </span>Set</span>}</>} required={req && !isStored} error={error}
        hint={isStored ? 'Write-only. A value is stored in the vault – leave blank to keep it, or type a new value to rotate it.' : 'Write-only: stored in the vault, never shown again.'}>
        {(d) => <input id={id} type="password" autoComplete="new-password" aria-describedby={d} aria-invalid={!!error} value={secrets.values[f.path] ?? ''} onChange={(e) => secrets.set(f.path, e.target.value)} />}
      </IdField>
    );
  }
  if (f.kind === 'boolean') {
    return (
      <div className="field">
        <label className="inline-row" htmlFor={id}><input id={id} type="checkbox" checked={state[f.path] === true} onChange={(e) => set(e.target.checked)} /> {label}</label>
      </div>
    );
  }
  if (f.kind === 'multi') {
    const vals = (state[f.path] as string[] | undefined) ?? [];
    return (
      <fieldset className="checkbox-group" id={id}>
        <legend>{label}{req && <span aria-hidden="true"> *</span>}</legend>
        {error && <span className="field-error-text small">{error}</span>}
        {(s.items?.enum ?? []).map((o) => (
          <label key={String(o)} className="inline-row"><input type="checkbox" checked={vals.includes(String(o))} onChange={(e) => set(e.target.checked ? [...vals, String(o)] : vals.filter((v) => v !== String(o)))} /> {humanise(String(o))}</label>
        ))}
      </fieldset>
    );
  }
  return (
    <IdField id={id} label={label} required={req} error={error} hint={f.hint}>
      {(d) => {
        const common = { id, 'aria-describedby': d, 'aria-invalid': !!error };
        if (f.kind === 'const') return <input {...common} readOnly value={String(s.const)} />;
        if (f.kind === 'enum') {
          return (
            <select {...common} value={String(state[f.path] ?? '')} onChange={(e) => set(e.target.value)}>
              {!req && <option value="">–</option>}
              {req && !state[f.path] && <option value="">Choose…</option>}
              {(s.enum ?? []).map((o) => <option key={String(o)} value={String(o)}>{String(o).replace(/_/g, ' ')}</option>)}
            </select>
          );
        }
        if (f.kind === 'list' || f.kind === 'json') return <textarea {...common} rows={f.kind === 'json' ? 4 : 2} className={f.kind === 'json' ? 'mono' : undefined} value={String(state[f.path] ?? '')} onChange={(e) => set(e.target.value)} />;
        if (f.kind === 'integer' || f.kind === 'number') return <input {...common} type="number" inputMode="numeric" min={s.minimum} max={s.maximum} step={f.kind === 'integer' ? 1 : 'any'} value={String(state[f.path] ?? '')} onChange={(e) => set(e.target.value)} />;
        return <input {...common} maxLength={s.maxLength} value={String(state[f.path] ?? '')} onChange={(e) => set(e.target.value)} />;
      }}
    </IdField>
  );
}

// ---------------------------------------------------------------------- step 5 – associate & bind

function useManifest(api: DevicesApi, cfg: Configuration, onSessionExpired: () => void) {
  return useAsync(
    () => (cfg.driverPackageId && cfg.driverVersion ? api.driver(cfg.driverPackageId, cfg.driverVersion).then((d) => d.manifest ?? {}) : Promise.resolve({} as DriverManifest)),
    onSessionExpired,
    [cfg.driverPackageId, cfg.driverVersion],
  );
}

function Step5(props: StepProps) {
  const { cfg, api, onSessionExpired } = props;
  const manifest = useManifest(api, cfg, onSessionExpired);
  const stations = useAsync(() => api.stations(), onSessionExpired);
  if ((manifest.loading && !manifest.data) || (stations.loading && !stations.data)) return <Loading />;
  if (manifest.error || stations.error) return <ErrorAlert error={manifest.error ?? stations.error} />;
  return <Step5Form {...props} manifest={manifest.data ?? {}} stations={stations.data ?? []} />;
}

const RESTRICTED = ['BREATH_ALCOHOL', 'DRUG_SCREEN'];

function initialBindings(cfg: Configuration, manifest: DriverManifest): BindingDraft[] {
  const saved = (stepOf(cfg, 5).data?.bindings ?? []) as { formCode: string; fields?: Record<string, string>; verifyBeforePost?: boolean; target?: string; formVersion?: string; category?: string }[];
  const forms = (manifest.bindings ?? []).map((b) => b.formCode);
  for (const s of saved) if (!forms.includes(s.formCode)) forms.push(s.formCode);
  return forms.map((formCode) => {
    const def = manifest.bindings?.find((b) => b.formCode === formCode);
    const mine = saved.find((b) => b.formCode === formCode);
    const keys = [...new Set([...Object.keys(def?.fields ?? {}), ...Object.keys(mine?.fields ?? {})])];
    const verifyLocked = cfg.option === 'P9' || cfg.option === 'P10' || def?.verifyBeforePost !== false || RESTRICTED.includes(def?.category ?? mine?.category ?? '');
    return {
      formCode,
      include: saved.length ? !!mine : true,
      target: mine?.target ?? def?.target,
      formVersion: mine?.formVersion ?? def?.formVersion,
      category: mine?.category ?? def?.category,
      verifyBeforePost: mine?.verifyBeforePost ?? def?.verifyBeforePost ?? true,
      verifyLocked,
      fields: keys.map((key) => ({ key, formField: mine?.fields?.[key] ?? def?.fields?.[key] ?? '' })),
    };
  });
}

function Step5Form({ cfg, api, editable, onSessionExpired, onSaved, manifest, stations }: StepProps & { manifest: DriverManifest; stations: Station[] }) {
  const data = (stepOf(cfg, 5).data ?? {}) as Obj;
  const [f, setF] = useState<Step5Form>(() => ({
    associationMethods: (data.associationMethods as string[] | undefined) ?? [],
    siteCode: String(data.siteCode ?? cfg.siteCode ?? ''),
    stationCode: String(data.stationCode ?? cfg.stationCode ?? ''),
    roomCode: String(data.roomCode ?? ''),
    services: ((data.services as string[] | undefined) ?? []).join(', '),
    bindings: initialBindings(cfg, manifest),
  }));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [dataset, setDataset] = useState(manifest.testVectors?.[0]?.name ?? 'normal');
  const [preview, setPreview] = useState<PreviewResult>();
  const action = useAction(onSessionExpired);
  const previewAction = useAction(onSessionExpired);
  const sites = [...new Set(stations.filter((s) => s.active).map((s) => s.siteCode))];
  const siteStations = stations.filter((s) => s.active && (!f.siteCode || s.siteCode === f.siteCode));
  const push = manifest.capabilities?.worklistPush ?? 'NONE';
  const mappingKeys = (manifest.mappings ?? []).map((m) => m.key);
  const patchBinding = (formCode: string, change: Partial<BindingDraft>) => setF((s) => ({ ...s, bindings: s.bindings.map((b) => (b.formCode === formCode ? { ...b, ...change } : b)) }));

  async function save(e: FormEvent) {
    e.preventDefault();
    const errs = validateStep5(f);
    setErrors(errs);
    if (errs.length) return;
    let next: Configuration | undefined;
    let failure: unknown;
    const ok = await action.run(async () => { try { next = await api.putStep(cfg.configurationId, 5, step5Body(f)); } catch (x) { failure = x; throw x; } });
    if (ok && next) onSaved(next, 6);
    else setErrors(serverFindings(failure, (fld) => (fld.startsWith('associationMethods') ? 'wiz-assoc-WORKLIST' : fld.startsWith('stationCode') ? 'wiz-station' : fld.startsWith('bindings') || fld === 'formCodes' ? `wiz-bind-${f.bindings.find((b) => b.include)?.formCode ?? ''}` : undefined), 'wiz-p5-save'));
  }
  async function runPreview() {
    setPreview(undefined);
    await previewAction.run(async () => { setPreview(await api.preview(cfg.configurationId, dataset)); });
  }
  return (
    <form className="form" onSubmit={save} noValidate aria-label="Associate and bind">
      <ErrorSummary errors={errors} />
      <fieldset disabled={!editable}>
        <legend>Association methods</legend>
        <p className="small muted">How a reading is matched to the employee. Driver worklist push: <span className="mono">{push}</span>. Breath-alcohol and drug-screen results accept only worklist or capture-session association.</p>
        <div className="checkbox-group">
          {ASSOCIATION_METHODS.map((m) => (
            <label key={m} className="inline-row">
              <input id={`wiz-assoc-${m}`} type="checkbox" checked={f.associationMethods.includes(m)} onChange={(e) => setF({ ...f, associationMethods: e.target.checked ? [...f.associationMethods, m] : f.associationMethods.filter((x) => x !== m) })} />
              {humanise(m)}{m === 'WORKLIST' && push === 'NONE' ? ' (driver cannot receive a worklist)' : ''}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset disabled={!editable}>
        <legend>Station</legend>
        <div className="form-grid">
          <Field label="Site">{(id) => <select id={id} value={f.siteCode} onChange={(e) => setF({ ...f, siteCode: e.target.value, stationCode: '' })}><CodeOptions values={sites} empty="Choose…" /></select>}</Field>
          <IdField id="wiz-station" label="Station" required error={errorFor(errors, 'wiz-station')}>
            {(d) => (
              <select id="wiz-station" aria-describedby={d} aria-invalid={!!errorFor(errors, 'wiz-station')} value={f.stationCode} onChange={(e) => setF({ ...f, stationCode: e.target.value })}>
                <option value="">Choose…</option>
                {siteStations.map((s) => <option key={s.stationCode} value={s.stationCode}>{s.name} ({s.stationCode}){s.testSite ? ' – test site' : ''}</option>)}
              </select>
            )}
          </IdField>
          <Field label="Room (optional)">{(id) => <input id={id} maxLength={60} value={f.roomCode} onChange={(e) => setF({ ...f, roomCode: e.target.value })} />}</Field>
          <Field label="Services (optional)" hint="comma separated">{(id) => <input id={id} maxLength={300} value={f.services} onChange={(e) => setF({ ...f, services: e.target.value })} />}</Field>
        </div>
      </fieldset>
      <fieldset disabled={!editable}>
        <legend>Result-to-form bindings (pre-filled from the driver)</legend>
        {f.bindings.length === 0 && <Empty>The driver package declares no form bindings.</Empty>}
        {f.bindings.map((b) => (
          <div key={b.formCode} className="binding">
            <label className="inline-row"><input id={`wiz-bind-${b.formCode}`} type="checkbox" checked={b.include} onChange={(e) => patchBinding(b.formCode, { include: e.target.checked })} /> <strong className="mono">{b.formCode}</strong> <span className="small muted">→ {b.target ?? '–'} · {humanise(b.category)}</span></label>
            {b.include && (
              <>
                <label className="inline-row small"><input type="checkbox" checked={b.verifyBeforePost} disabled={b.verifyLocked} onChange={(e) => patchBinding(b.formCode, { verifyBeforePost: e.target.checked })} /> Clinician verifies before posting{b.verifyLocked ? ' (required by the driver / option – cannot be relaxed)' : ''}</label>
                <table>
                  <caption className="visually-hidden">Field bindings for {b.formCode}</caption>
                  <thead><tr><th scope="col">Driver result key</th><th scope="col">Form field</th></tr></thead>
                  <tbody>
                    {b.fields.map((x, i) => (
                      <tr key={x.key}>
                        <th scope="row" className="mono small">{x.key}{mappingKeys.length > 0 && !mappingKeys.includes(x.key) ? ' (derived)' : ''}</th>
                        <td><input aria-label={`Form field for ${x.key} in ${b.formCode}`} maxLength={80} value={x.formField} onChange={(e) => patchBinding(b.formCode, { fields: b.fields.map((y, j) => (j === i ? { ...y, formField: e.target.value } : y)) })} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </div>
        ))}
      </fieldset>
      {editable && <div className="actions"><button type="submit" id="wiz-p5-save" disabled={action.busy}>Save and continue</button></div>}
      <ActionFeedback error={errors.length ? undefined : action.error} />
      <section className="preview" aria-labelledby="pv-title">
        <h3 id="pv-title">Preview against the test vectors</h3>
        <p className="small muted">Shows how the saved bindings would fill the form from a golden dataset of the driver package (synthetic data). Save first to preview changes.</p>
        <div className="inline-form">
          <Field label="Test vector">{(id) => <select id={id} value={dataset} onChange={(e) => setDataset(e.target.value)}>{(manifest.testVectors ?? [{ name: 'normal' }]).map((v) => <option key={v.name} value={v.name}>{humanise(v.name)}</option>)}</select>}</Field>
          <button type="button" className="secondary" disabled={previewAction.busy} onClick={() => void runPreview()}>Preview</button>
        </div>
        <ActionFeedback error={previewAction.error} />
        {preview && (
          <div aria-live="polite">
            {preview.expectedError && <Alert kind="info">This vector is a negative case – the engine is expected to reject it with “{preview.expectedError}”.</Alert>}
            {preview.forms.map((form) => (
              <table key={form.formCode}>
                <caption>{form.formCode} {form.verifyBeforePost ? '(verified before posting)' : ''}</caption>
                <thead><tr><th scope="col">Result</th><th scope="col">Form field</th><th scope="col">Value</th><th scope="col">Unit</th><th scope="col">Filled</th></tr></thead>
                <tbody>
                  {form.fields.map((x) => (
                    <tr key={x.key}>
                      <th scope="row" className="small">{x.display ?? x.key}</th>
                      <td className="mono small">{x.formField}</td>
                      <td>{x.value === undefined || x.value === null ? '–' : String(x.value)}</td>
                      <td className="small">{x.unit ?? '–'}</td>
                      <td>{x.filled ? <CodeBadge code="PASS" label="Filled" /> : <CodeBadge code="NOT_RUN" label="Empty" />}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ))}
          </div>
        )}
      </section>
    </form>
  );
}

// ---------------------------------------------------------------------- step 6 – test

function Step6({ cfg, api, editable, session, onSessionExpired, onSaved, reload }: StepProps) {
  const manifest = useManifest(api, cfg, onSessionExpired);
  const [mode, setMode] = useState<'SIMULATOR' | 'LIVE'>('SIMULATOR');
  const [datasetId, setDatasetId] = useState('');
  const [justification, setJustification] = useState(cfg.simulatorOnlyJustification ?? '');
  const runAction = useAction(onSessionExpired);
  const justAction = useAction(onSessionExpired);
  const writer = can(session.roles, 'deviceConfigWrite');
  const checklist = step6Checklist(cfg);
  const latest = cfg.tests[cfg.tests.length - 1];
  async function run(e: FormEvent) {
    e.preventDefault();
    if (await runAction.run(async () => { const t = await api.runTest(cfg.configurationId, { mode, ...(datasetId ? { datasetId } : {}) }); return `${humanise(t.mode)} test ${humanise(t.status).toLowerCase()}.`; })) reload();
  }
  async function saveJustification(e: FormEvent) {
    e.preventDefault();
    let next: Configuration | undefined;
    if (await justAction.run(async () => { next = await api.putStep(cfg.configurationId, 6, { simulatorOnlyJustification: justification.trim() }); return justification.trim() ? 'Justification recorded – Security Operations must co-approve at step 7.' : 'Justification removed.'; })) if (next) onSaved(next);
  }
  return (
    <div className="form">
      <ul className="plain-list checklist" aria-label="Test requirements">
        {checklist.map((c) => (
          <li key={c.code}><CodeBadge code={c.met ? 'PASS' : 'OPEN'} label={c.met ? 'Met' : 'Open'} /> {c.label}</li>
        ))}
      </ul>
      {editable && writer && (
        <form className="form" onSubmit={run} aria-label="Run a test">
          <fieldset>
            <legend>Mode</legend>
            <label className="inline-row"><input type="radio" name="test-mode" checked={mode === 'SIMULATOR'} onChange={() => setMode('SIMULATOR')} /> Simulator (golden datasets incl. negative vectors)</label>
            <label className="inline-row"><input type="radio" name="test-mode" checked={mode === 'LIVE'} onChange={() => setMode('LIVE')} /> Live device (connection, end-to-end sample, mTLS and plaintext-refused checks)</label>
          </fieldset>
          <Field label="Dataset">{(id) => <select id={id} value={datasetId} onChange={(e) => setDatasetId(e.target.value)}><option value="">All vectors of the package</option>{(manifest.data?.testVectors ?? []).map((v) => <option key={v.name} value={v.name}>{humanise(v.name)}</option>)}</select>}</Field>
          <div className="actions"><button type="submit" disabled={runAction.busy}>{runAction.busy ? 'Running…' : 'Run diagnostics test'}</button></div>
        </form>
      )}
      <UnavailableNotice error={runAction.error} what="The diagnostics test runner (integration engine)" />
      {!(runAction.error && (runAction.error as { status?: number }).status === 503) && <ActionFeedback error={runAction.error} message={runAction.message} />}
      {cfg.tests.length === 0 ? <Empty>No test has been run for this version yet.</Empty> : (
        <table>
          <caption>Test runs and evidence</caption>
          <thead><tr><th scope="col">Finished</th><th scope="col">Mode</th><th scope="col">Dataset</th><th scope="col">Result</th><th scope="col">By</th><th scope="col">Evidence SHA-256</th></tr></thead>
          <tbody>
            {cfg.tests.map((t) => (
              <tr key={t.testRunId}>
                <td className="small">{formatDate(t.finishedAt)}</td>
                <td>{humanise(t.mode)}</td>
                <td className="small">{t.datasetId ? humanise(t.datasetId) : 'All'}</td>
                <td><CodeBadge code={t.status} /></td>
                <td>{t.by ?? '–'}</td>
                <td><Hash value={t.evidenceSha256} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {latest?.steps && latest.steps.length > 0 && (
        <details open>
          <summary>Steps of the latest run</summary>
          <ul className="plain-list">{latest.steps.map((s, i) => <li key={i}><CodeBadge code={s.outcome} /> {s.name}{s.detail ? ` – ${s.detail}` : ''}</li>)}</ul>
        </details>
      )}
      <section aria-labelledby="nr-title">
        <h3 id="nr-title">Nurse review of the test form</h3>
        {cfg.nurseReview ? <p><CodeBadge code={cfg.nurseReview.outcome} /> by {cfg.nurseReview.by} {formatDate(cfg.nurseReview.at)}{cfg.nurseReview.note ? ` – ${cfg.nurseReview.note}` : ''}</p>
          : <p className="small">Pending. An OH nurse or nursing head opens “Device test review” and records the outcome for <span className="mono">{cfg.configurationId}</span> after a test run.</p>}
      </section>
      {editable && writer && (
        <form className="form" onSubmit={saveJustification} aria-label="Simulator-only justification">
          <Field label="Simulator-only justification (production stations without a live run)" hint="Recording one makes Security Operations a second approver at step 7.">
            {(id) => <textarea id={id} rows={2} maxLength={1000} value={justification} onChange={(e) => setJustification(e.target.value)} />}
          </Field>
          <div className="actions"><button type="submit" className="secondary" disabled={justAction.busy}>Save justification</button></div>
          <ActionFeedback error={justAction.error} message={justAction.message} />
        </form>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------- step 7 – certify & approve (four-eyes)

function Step7({ cfg, api, session, onSessionExpired, onSaved }: StepProps) {
  const action = useAction(onSessionExpired);
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string>();
  const writer = can(session.roles, 'deviceConfigWrite');
  const s7 = stepOf(cfg, 7);
  const ready = [1, 2, 3, 4, 5, 6].every((n) => stepOf(cfg, n).status === 'VALID');
  const certified = ['CERTIFIED', 'PUBLISHED', 'SUSPENDED', 'QUARANTINED', 'SUPERSEDED', 'DECOMMISSIONED'].includes(cfg.status) && !!cfg.approvedBy;
  const block = fourEyesBlock(cfg, session.username);
  const role = approvalRoleFor(cfg, session.roles);
  const approver = can(session.roles, 'deviceApprove');
  async function act(fn: () => Promise<Configuration>, msg: string, to?: number) {
    let next: Configuration | undefined;
    if (await action.run(async () => { next = await fn(); return msg; })) if (next) onSaved(next, to);
  }
  if (cfg.status === 'DRAFT') {
    return (
      <div className="form">
        {s7.status === 'INVALID' && <Alert kind="error">Rejected by {String(s7.data?.rejectedBy ?? 'the approver')}: {String(s7.data?.reason ?? '')}. Fix the configuration and submit again.</Alert>}
        <ul className="plain-list" aria-label="Readiness for submission">
          {[1, 2, 3, 4, 5, 6].map((n) => { const v = stepStatusView(stepOf(cfg, n).status); return <li key={n}><CodeBadge code={stepOf(cfg, n).status === 'VALID' ? 'PASS' : 'OPEN'} label={v.label} /> {n}. {STEP_NAMES[n - 1]}</li>; })}
        </ul>
        {writer && <div className="actions"><button type="button" disabled={!ready || action.busy || cfg.paused} onClick={() => void act(() => api.submit(cfg.configurationId), 'Submitted for four-eyes approval.')}>Submit for approval</button>{!ready && <span className="small muted"> Complete steps 1–6 first.</span>}</div>}
        <ActionFeedback error={action.error} message={action.message} />
      </div>
    );
  }
  if (cfg.status === 'AWAITING_APPROVAL') {
    const prereqs = (stepOf(cfg, 3).data?.items ?? []) as PrerequisiteItem[];
    const coApproval = needsSecurityCoApproval(cfg);
    return (
      <div className="form approver-view">
        <Dl items={[
          ['Submitted by', `${cfg.submittedBy ?? '–'} ${formatDate(String(s7.data?.submittedAt ?? ''))}`],
          ['Model / option', `${cfg.modelCode} via ${cfg.option}`],
          ['Driver', `${cfg.driverPackageId}@${cfg.driverVersion}`],
          ['Site / station', `${cfg.siteCode ?? '–'} / ${cfg.stationCode ?? '–'}`],
          ['Prerequisites', `${prereqs.filter((i) => i.status === 'DONE' || i.status === 'NOT_APPLICABLE').length} of ${prereqs.length} done or not applicable`],
          ['Tests', cfg.tests.map((t) => `${humanise(t.mode)} ${humanise(t.status).toLowerCase()}`).join(', ') || 'None'],
          ['Nurse review', cfg.nurseReview ? `${humanise(cfg.nurseReview.outcome)} by ${cfg.nurseReview.by}` : 'Missing'],
          ['Approvals required', coApproval ? 'Device approver and Security Operations (simulator-only justification)' : 'Device approver'],
          ['Approvals given', (cfg.approvals ?? []).map((a) => `${humanise(a.role)}: ${a.by}`).join(', ') || 'None yet'],
        ]} />
        {cfg.simulatorOnlyJustification && <p className="small"><strong>Simulator-only justification:</strong> {cfg.simulatorOnlyJustification}</p>}
        {approver ? (
          <>
            {block && <Alert kind="info">{block}</Alert>}
            {!block && !role && <Alert kind="info">Your approval is not needed for this configuration (it is already recorded or your role is not required).</Alert>}
            <Field label="Approval note">{(id) => <textarea id={id} rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
            <div className="actions">
              <button type="button" disabled={!!block || !role || action.busy || cfg.paused} aria-describedby={block ? 'four-eyes-reason' : undefined} onClick={() => void act(() => api.approve(cfg.configurationId, note.trim()), role === 'SECURITY_OPS' ? 'Security co-approval recorded.' : 'Approved.')}>
                Approve and certify
              </button>
              {block && <span id="four-eyes-reason" className="visually-hidden">{block}</span>}
            </div>
            <IdField id="rej-reason" label="Reason for rejection" error={reasonError}>{(d) => <textarea id="rej-reason" rows={2} maxLength={1000} aria-describedby={d} value={reason} onChange={(e) => setReason(e.target.value)} />}</IdField>
            <div className="actions"><button type="button" className="danger-button" disabled={action.busy} onClick={() => { if (!reason.trim()) { setReasonError('Enter the reason'); return; } setReasonError(undefined); void act(() => api.reject(cfg.configurationId, reason.trim()), 'Rejected – returned to the submitter as a draft.'); }}>Reject</button></div>
          </>
        ) : <Alert kind="info">Awaiting four-eyes approval by a device approver{coApproval ? ' and Security Operations' : ''}. The submitter and anyone who changed steps 4–6 cannot approve.</Alert>}
        <ActionFeedback error={action.error} message={action.message} />
      </div>
    );
  }
  if (certified) return <CertificationPanel cfg={cfg} api={api} onSessionExpired={onSessionExpired} />;
  return <p className="muted">No certification for this version.</p>;
}

function CertificationPanel({ cfg, api, onSessionExpired }: { cfg: Configuration; api: DevicesApi; onSessionExpired: () => void }) {
  const record = useAsync(() => api.certification(cfg.configurationId), onSessionExpired, [cfg.configurationId, cfg.version]);
  const r = record.data;
  return (
    <div>
      {record.loading && !r && <Loading />}
      <ErrorAlert error={record.error} />
      {r && (
        <>
          <Dl items={[
            ['Certification', <span className="mono">{r.certificationId}</span>],
            ['Approved by', `${r.approvedBy ?? '–'}${r.coApprovedBy ? ` and ${r.coApprovedBy} (security)` : ''} ${formatDate(r.approvedAt)}`],
            ['Submitted by', `${r.submittedBy ?? '–'} ${formatDate(r.submittedAt)}`],
            ['Driver', r.driver ? `${r.driver.packageId}@${r.driver.version}` : '–'],
            ['Snapshot SHA-256', <Hash value={r.snapshotSha256} />],
            ['Approval signature hash', <Hash value={r.approvalSignatureHash} />],
            ['Test evidence', r.tests?.map((t) => `${humanise(t.mode)} ${humanise(t.status).toLowerCase()}`).join(', ')],
          ]} />
          <a className="button" href={certificatePdfUrl(cfg.configurationId)} download={`${cfg.configurationId}-certificate.pdf`}>Download certificate (PDF)</a>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------- step 8 – publish & monitor

function Step8({ cfg, api, session, onSessionExpired, reload }: StepProps) {
  const action = useAction(onSessionExpired);
  const [published, setPublished] = useState<PublishResult>();
  const [tokenHidden, setTokenHidden] = useState(false);
  const writer = can(session.roles, 'deviceConfigWrite');
  const flow = useAsync(() => (isLive(cfg.status) ? api.dataFlow(cfg.configurationId) : Promise.resolve(undefined)), onSessionExpired, [cfg.configurationId, cfg.status]);
  async function publish() {
    await action.run(async () => { setPublished(await api.publish(cfg.configurationId)); });
  }
  return (
    <div className="form">
      {cfg.status === 'CERTIFIED' && !published && (
        <>
          <p>Publishing makes {cfg.deviceInstanceId} visible in the nurses’ “capture from device” lists at <strong>{cfg.siteCode} / {cfg.stationCode}</strong>, activates the calibration / QC schedule and monitoring, and provisions the endpoint identity. A one-time enrolment token is shown once.</p>
          {writer && <div className="actions"><button type="button" disabled={action.busy || cfg.paused} onClick={() => void publish()}>Publish to {cfg.stationCode}</button></div>}
        </>
      )}
      {published && (
        <div aria-live="polite">
          <Alert kind="success">Published {published.deviceInstanceId} (version {published.configVersion}){published.endpointId ? ` on endpoint ${published.endpointId}` : ''}.</Alert>
          {published.enrolmentToken && !tokenHidden && <OneTimeToken label={`Enrolment token for ${published.endpointId ?? 'the endpoint'}`} token={published.enrolmentToken} expiresAt={published.enrolmentExpiresAt} onDismiss={() => setTokenHidden(true)} />}
          {published.enrolmentToken && tokenHidden && <p className="small muted">The enrolment token has been hidden and cannot be shown again. Security Operations can issue a new one if needed.</p>}
          {!published.enrolmentToken && <p className="small">The device uses an existing, enrolled endpoint – no new enrolment token was needed.</p>}
          <button type="button" className="secondary" onClick={reload}>Show the published configuration</button>
        </div>
      )}
      <ActionFeedback error={action.error} />
      {isLive(cfg.status) && (
        <>
          <Dl items={[['Status', <CodeBadge code={cfg.status} />], ['Published', formatDate(cfg.publishedAt)], ['Endpoint', cfg.endpointId], ['Site / station', `${cfg.siteCode} / ${cfg.stationCode}`]]} />
          <p><a href={`#/devices/fleet/${cfg.deviceInstanceId}`}>Monitor {cfg.deviceInstanceId} in the fleet view</a></p>
          {flow.data && (
            <section aria-labelledby="df-title">
              <h3 id="df-title">Data flow (no person data)</h3>
              <ol className="plain-list data-flow">
                <li>Device {String(getAt(flow.data.device, 'modelCode') ?? cfg.modelCode)} ({cfg.deviceInstanceId})</li>
                <li>Endpoint {flow.data.endpoint?.endpointId ?? '–'} ({humanise(flow.data.endpoint?.kind)}, zone {flow.data.endpoint?.zone ?? '–'})</li>
                <li>Driver {String(getAt(flow.data.driver, 'packageId') ?? '–')}@{String(getAt(flow.data.driver, 'version') ?? '')}</li>
                <li>Quality gates: {(flow.data.gates ?? []).join(' → ') || '–'}</li>
                <li>Association: {(flow.data.associationMethods ?? []).map(humanise).join(', ') || '–'}</li>
                <li>Bindings: {(flow.data.bindings ?? []).map((b) => `${b.form} → ${b.target ?? '–'}${b.verifyBeforePost ? ' (verified)' : ''}`).join('; ') || '–'}</li>
              </ol>
            </section>
          )}
        </>
      )}
      {!isFinal(cfg.status) && writer && [1, 2, 3, 4].every((n) => stepOf(cfg, n).status === 'VALID') && <CloneForm cfg={cfg} api={api} onSessionExpired={onSessionExpired} />}
    </div>
  );
}

function CloneForm({ cfg, api, onSessionExpired }: { cfg: Configuration; api: DevicesApi; onSessionExpired: () => void }) {
  const stations = useAsync(() => api.stations(), onSessionExpired);
  const [f, setF] = useState({ siteCode: '', stationCode: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const list = stations.data ?? [];
  const sites = [...new Set(list.map((s) => s.siteCode))];
  async function clone(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.stationCode) errs.push({ field: 'clone-station', message: 'Choose the station to clone to' });
    setErrors(errs);
    if (errs.length) return;
    let next: Configuration | undefined;
    const station = list.find((s) => s.stationCode === f.stationCode);
    if (await action.run(async () => { next = await api.clone(cfg.configurationId, { siteCode: station?.siteCode ?? f.siteCode, stationCode: f.stationCode }); })) if (next) navigate(`/devices/wizard/${next.configurationId}`);
  }
  return (
    <form className="card inline-form" onSubmit={clone} noValidate aria-labelledby="clone-title">
      <h3 id="clone-title">Clone to another station</h3>
      <p className="small muted">Creates a new draft at step 5 with the same model, option and parameters; tests (including a live test at the new station), approval and publication are required again.</p>
      <ErrorSummary errors={errors} />
      <Field label="Site">{(id) => <select id={id} value={f.siteCode} onChange={(e) => setF({ siteCode: e.target.value, stationCode: '' })}><CodeOptions values={sites} empty="Any site" /></select>}</Field>
      <IdField id="clone-station" label="Station" required error={errorFor(errors, 'clone-station')}>
        {(d) => <select id="clone-station" aria-describedby={d} value={f.stationCode} onChange={(e) => setF({ ...f, stationCode: e.target.value })}><option value="">Choose…</option>{list.filter((s) => s.active && s.stationCode !== cfg.stationCode && (!f.siteCode || s.siteCode === f.siteCode)).map((s) => <option key={s.stationCode} value={s.stationCode}>{s.name} ({s.stationCode})</option>)}</select>}
      </IdField>
      <div className="actions"><button type="submit" className="secondary" disabled={action.busy}>Clone configuration</button></div>
      <ActionFeedback error={action.error} />
    </form>
  );
}
