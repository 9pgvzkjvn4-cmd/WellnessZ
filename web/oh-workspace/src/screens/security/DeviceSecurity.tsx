import { useState, type FormEvent } from 'react';
import {
  ENDPOINT_KINDS, ENDPOINT_STATUSES, EVENT_TYPES, REASON_CODES, TEST_OUTCOMES, ZONES, conduitExportUrl, deviceSecurityApi,
  type Endpoint, type Issued, type Rotated, type SecretMetadata, type SecurityAlert, type TestPackResult,
} from '../../api/deviceSecurity';
import { ApiError } from '../../auth/AuthClient';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { KpiTile } from '../../components/Tone';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { plural } from '../../domain/deviceConsole';
import { splitList } from '../../domain/deviceWizard';
import { errorFor } from '../../domain/validation';
import { CodeBadge, Hash, OneTimeToken } from '../devices/common';
import type { ScreenProps } from '../types';

const SITE_RE = /^[A-Z0-9]{2,10}$/;
const INSTANCE_RE = /^DEV-\d{6}$/;

// ====================================================================== endpoints

/** `#/security/devices` – endpoint identities: inventory with certificate expiry, issue an enrolment token. */
export function SecurityEndpoints(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const api = deviceSecurityApi(client);
  const [filters, setFilters] = useState({ status: '', kind: '', siteCode: '' });
  const list = useAsync(() => api.endpoints({ status: filters.status || undefined, kind: filters.kind || undefined, siteCode: filters.siteCode || undefined }), onSessionExpired, [filters]);
  const [issued, setIssued] = useState<Issued>();
  const rows = list.data ?? [];
  const count = (s: string) => rows.filter((r) => r.status === s).length;
  return (
    <section>
      <PageTitle sub="One identity per gateway, Device Host agent, tablet, kiosk, data manager and vendor cloud – no shared secrets. Enrolment binds the client certificate from the group PKI.">Endpoint identities</PageTitle>
      {list.data && (
        <div className="tiles">
          <KpiTile label="Active" value={count('ACTIVE')} />
          <KpiTile label="Pending enrolment" value={count('PENDING_ENROLMENT')} />
          <KpiTile label="Isolated" value={count('ISOLATED')} status={count('ISOLATED') ? 'MISSED' : undefined} />
          <KpiTile label="Certificates expiring ≤ 30 days" value={rows.filter((r) => r.certificateExpiresInDays != null && r.certificateExpiresInDays <= 30).length} />
        </div>
      )}
      <form className="filters" aria-label="Filter endpoints" onSubmit={(e) => e.preventDefault()}>
        <Field label="Status">{(id) => <select id={id} value={filters.status} onChange={(e) => setFilters({ ...filters, status: e.target.value })}><SelectOptions values={ENDPOINT_STATUSES} empty="All" /></select>}</Field>
        <Field label="Kind">{(id) => <select id={id} value={filters.kind} onChange={(e) => setFilters({ ...filters, kind: e.target.value })}><SelectOptions values={ENDPOINT_KINDS} empty="All" /></select>}</Field>
        <Field label="Site">{(id) => <input id={id} maxLength={10} value={filters.siteCode} onChange={(e) => setFilters({ ...filters, siteCode: e.target.value.toUpperCase() })} />}</Field>
      </form>
      {issued && <OneTimeToken label={`Enrolment token for ${issued.endpointId}`} token={issued.enrolmentToken} expiresAt={issued.enrolmentExpiresAt} onDismiss={() => setIssued(undefined)} />}
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      {list.data && rows.length === 0 && <Empty>No endpoint matches.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'endpoint')}</caption>
            <thead><tr><th scope="col">Endpoint</th><th scope="col">Kind</th><th scope="col">Site / zone</th><th scope="col">Status</th><th scope="col">Certificate</th><th scope="col">Token expires</th><th scope="col">Devices</th><th scope="col">Last seen</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.endpointId}>
                  <th scope="row"><a href={`#/security/devices/endpoints/${encodeURIComponent(r.endpointId)}`} className="mono">{r.endpointId}</a></th>
                  <td>{humanise(r.kind)}</td>
                  <td className="small">{r.siteCode} / {humanise(r.zone)}</td>
                  <td><CodeBadge code={r.status} /> {r.recertificationRequired && <CodeBadge code="WARN" label="Re-certification required" />}</td>
                  <td className="small">{r.certificateExpiresInDays == null ? 'Not enrolled' : <CodeBadge code={r.certificateExpiresInDays <= 30 ? 'WARN' : 'PASS'} label={`${r.certificateExpiresInDays} days left`} />}</td>
                  <td className="small">{formatDate(r.tokenExpiresAt ?? r.enrolmentExpiresAt)}</td>
                  <td className="small mono">{r.deviceInstanceIds.join(', ') || '–'}</td>
                  <td className="small">{formatDate(r.lastSeenAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <CreateEndpoint {...props} onIssued={(i) => { setIssued(i); list.reload(); }} />
    </section>
  );
}

function CreateEndpoint({ client, onSessionExpired, onIssued }: ScreenProps & { onIssued: (i: Issued) => void }) {
  const api = deviceSecurityApi(client);
  const empty = { kind: '', siteCode: '', zone: 'GATEWAY_ZONE', deviceInstanceIds: '', allowedCidrs: '' };
  const [f, setF] = useState(empty);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.kind) errs.push({ field: 'ep-kind', message: 'Choose the endpoint kind' });
    if (!SITE_RE.test(f.siteCode)) errs.push({ field: 'ep-site', message: 'Enter the site code (2–10 capital letters or digits)' });
    if (f.kind === 'DEVICE' && f.zone === 'DEVICE_VLAN') errs.push({ field: 'ep-zone', message: 'Devices in the device VLAN never reach the core directly – enrol the gateway instead' });
    const instances = splitList(f.deviceInstanceIds.toUpperCase());
    if (instances.some((i) => !INSTANCE_RE.test(i))) errs.push({ field: 'ep-instances', message: 'Device instances look like DEV-000001' });
    setErrors(errs);
    if (errs.length) return;
    let issued: Issued | undefined;
    if (await action.run(async () => { issued = await api.createEndpoint({ kind: f.kind as Endpoint['kind'], siteCode: f.siteCode, zone: f.zone, deviceInstanceIds: instances, ...(f.allowedCidrs.trim() ? { allowedCidrs: splitList(f.allowedCidrs) } : {}) }); })) {
      if (issued) onIssued(issued);
      setF(empty);
    }
  }
  return (
    <details className="card">
      <summary>Create an endpoint and issue an enrolment token</summary>
      <form className="form" onSubmit={submit} noValidate aria-label="Create endpoint">
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="ep-kind" label="Kind" required error={errorFor(errors, 'ep-kind')}>{(d) => <select id="ep-kind" aria-describedby={d} value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}><SelectOptions values={ENDPOINT_KINDS} empty="Choose…" /></select>}</IdField>
          <IdField id="ep-site" label="Site" required error={errorFor(errors, 'ep-site')}>{(d) => <input id="ep-site" aria-describedby={d} maxLength={10} value={f.siteCode} onChange={(e) => setF({ ...f, siteCode: e.target.value.toUpperCase() })} />}</IdField>
          <IdField id="ep-zone" label="Zone" error={errorFor(errors, 'ep-zone')}>{(d) => <select id="ep-zone" aria-describedby={d} value={f.zone} onChange={(e) => setF({ ...f, zone: e.target.value })}><SelectOptions values={ZONES} /></select>}</IdField>
          <IdField id="ep-instances" label="Device instances" hint="comma separated, e.g. DEV-000003" error={errorFor(errors, 'ep-instances')}>{(d) => <input id="ep-instances" aria-describedby={d} value={f.deviceInstanceIds} onChange={(e) => setF({ ...f, deviceInstanceIds: e.target.value })} />}</IdField>
          <Field label="Allowed source ranges (CIDR)" hint="optional, comma separated">{(id) => <input id={id} value={f.allowedCidrs} onChange={(e) => setF({ ...f, allowedCidrs: e.target.value })} />}</Field>
        </div>
        <div className="actions"><button type="submit" disabled={action.busy}>Create and issue token</button></div>
        <ActionFeedback error={action.error} />
      </form>
    </details>
  );
}

type StatusAction = 'isolate' | 'release' | 'revoke';

/** `#/security/devices/endpoints/{id}` – detail, rotate, re-issue enrolment token, isolate / release / revoke, events. */
export function SecurityEndpointDetail(props: ScreenProps & { endpointId: string }) {
  const { client, onSessionExpired, endpointId } = props;
  const api = deviceSecurityApi(client);
  const ep = useAsync(() => api.endpoint(endpointId), onSessionExpired, [endpointId]);
  const events = useAsync(() => api.endpointEvents(endpointId), onSessionExpired, [endpointId]);
  const [dialog, setDialog] = useState<StatusAction>();
  const [rotated, setRotated] = useState<Rotated>();
  const [issued, setIssued] = useState<Issued>();
  const [recert, setRecert] = useState(false);
  const action = useAction(onSessionExpired);
  const e = ep.data;
  async function status(kind: StatusAction, reasonCode: string, note: string) {
    setDialog(undefined);
    setRecert(false);
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        const r = kind === 'isolate' ? await api.isolate(endpointId, reasonCode, note) : kind === 'release' ? await api.release(endpointId, reasonCode, note) : await api.revoke(endpointId, reasonCode, note);
        return kind === 'isolate' ? `Isolated${r.minutesToIsolate != null ? ` in ${r.minutesToIsolate.toFixed(1)} minutes` : ''}${r.incidentRef ? ` – incident ${r.incidentRef}` : ''}. The devices are quarantined in the console.` : kind === 'release' ? 'Released – authentication accepted again.' : 'Revoked – effective on the next request.';
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (!ok && failure instanceof ApiError && failure.problem?.code === 'recertification_required') setRecert(true);
    ep.reload();
    events.reload();
  }
  return (
    <section>
      <p className="breadcrumb"><a href="#/security/devices">Endpoints</a> › {endpointId}</p>
      <PageTitle sub={e ? `${humanise(e.kind)} at ${e.siteCode} in ${humanise(e.zone)}` : undefined}>{endpointId}</PageTitle>
      {ep.loading && !e && <Loading />}
      <ErrorAlert error={ep.error} />
      {e && (
        <>
          {(e.status === 'ISOLATED' || e.recertificationRequired) && (
            <Alert kind="warning">
              <strong>Re-certification required.</strong> Isolation quarantined {plural(e.deviceInstanceIds.length, 'device instance')} in the device console. Release is possible only after every instance is re-certified there (revise → test → four-eyes approval → publish).
            </Alert>
          )}
          {recert && <Alert kind="error">Release refused: at least one device instance has not been re-certified and published again in the device console (recertification_required).</Alert>}
          <div className="grid-2">
            <section className="card" aria-labelledby="ep-id">
              <h2 id="ep-id">Identity</h2>
              <Dl items={[
                ['Status', <><CodeBadge code={e.status} />{e.statusReason ? ` ${humanise(e.statusReason)}` : ''}</>],
                ['Tenant', e.tenantCode],
                ['Device instances', e.deviceInstanceIds.join(', ') || '–'],
                ['Created', `${e.createdBy ?? '–'} ${formatDate(e.createdAt)}`],
                ['Enrolled', formatDate(e.enrolledAt)],
                ['Token issued / expires', `${formatDate(e.tokenIssuedAt)} / ${formatDate(e.tokenExpiresAt)}`],
                ['Enrolment token expires', formatDate(e.enrolmentExpiresAt)],
                ['Allowed ranges', e.allowedCidrs?.join(', ') || 'Site gateway zone'],
                ['Isolated / released', `${formatDate(e.isolatedAt)} / ${formatDate(e.releasedAt)}`],
              ]} />
            </section>
            <section className="card" aria-labelledby="ep-cert">
              <h2 id="ep-cert">Certificate and host</h2>
              <Dl items={[
                ['Thumbprint (SHA-256)', <Hash value={e.certificate?.sha256} />],
                ['Subject', e.certificate?.subject],
                ['Issuer', e.certificate?.issuer],
                ['Valid until', e.certificate?.notAfter ? `${formatDate(e.certificate.notAfter)} (${e.certificateExpiresInDays} days)` : undefined],
                ['Host', e.hostname],
                ['Platform', [e.platformOs, e.platformOsVersion].filter(Boolean).join(' ') || undefined],
                ['Agent version', e.agentVersion],
                ['Hardening', e.hardeningStatus ? <><CodeBadge code={e.hardeningStatus === 'OK' ? 'PASS' : e.hardeningStatus} label={humanise(e.hardeningStatus)} /> {e.hardeningBaseline}</> : undefined],
                ['Hardware attestation', e.attested ? 'Yes' : 'No'],
              ]} />
            </section>
          </div>
          <section className="card" aria-labelledby="ep-actions">
            <h2 id="ep-actions">Actions</h2>
            <div className="actions">
              {e.status === 'PENDING_ENROLMENT' && <button type="button" className="secondary" disabled={action.busy} onClick={() => void action.run(async () => { setIssued(await api.reissueEnrolmentToken(endpointId)); })}>Issue a new enrolment token</button>}
              {e.status === 'ACTIVE' && <button type="button" className="secondary" disabled={action.busy} onClick={() => void action.run(async () => { setRotated(await api.rotateToken(endpointId)); ep.reload(); })}>Rotate endpoint token</button>}
              {e.status === 'ACTIVE' && <button type="button" className="danger-button" onClick={() => setDialog('isolate')}>Isolate</button>}
              {e.status === 'ISOLATED' && <button type="button" onClick={() => setDialog('release')}>Release from isolation</button>}
              {e.status !== 'REVOKED' && <button type="button" className="danger-button" onClick={() => setDialog('revoke')}>Revoke</button>}
            </div>
            <ActionFeedback error={recert ? undefined : action.error} message={action.message} />
            {issued && <OneTimeToken label={`Enrolment token for ${issued.endpointId}`} token={issued.enrolmentToken} expiresAt={issued.enrolmentExpiresAt} onDismiss={() => setIssued(undefined)} />}
            {rotated && (
              <>
                <OneTimeToken label={`New endpoint token for ${rotated.endpointId}`} token={rotated.endpointToken} expiresAt={rotated.tokenExpiresAt} onDismiss={() => setRotated(undefined)} />
                {rotated.previousTokenValidUntil && <p className="small">The previous token stays valid until {formatDate(rotated.previousTokenValidUntil)} (grace period).</p>}
              </>
            )}
          </section>
        </>
      )}
      <section className="card" aria-labelledby="ep-ev">
        <h2 id="ep-ev">Security events of this endpoint</h2>
        {events.loading && !events.data && <Loading />}
        <ErrorAlert error={events.error} />
        {events.data && <EventTable events={events.data} />}
      </section>
      {dialog && <StatusDialog kind={dialog} endpointId={endpointId} onClose={() => setDialog(undefined)} onSubmit={(r, n) => void status(dialog, r, n)} />}
    </section>
  );
}

function StatusDialog({ kind, endpointId, onClose, onSubmit }: { kind: StatusAction; endpointId: string; onClose: () => void; onSubmit: (reasonCode: string, note: string) => void }) {
  const [reasonCode, setReasonCode] = useState(kind === 'release' ? 'RECERTIFIED' : '');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  const title = `${kind === 'isolate' ? 'Isolate' : kind === 'release' ? 'Release' : 'Revoke'} ${endpointId}`;
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); if (!reasonCode) { setError('Choose the reason'); return; } onSubmit(reasonCode, note.trim()); }}>
        <p className="small">
          {kind === 'isolate' && 'Authentication is refused at once, an ISOLATE command is queued for the gateway, evidence is preserved and the console quarantines the device instances.'}
          {kind === 'release' && 'Release requires every device instance of this endpoint to be re-certified in the device console first.'}
          {kind === 'revoke' && 'Revocation is permanent; the endpoint must enrol again with a new identity.'}
        </p>
        <IdField id="st-reason" label="Reason" required error={error}>{(d) => <select id="st-reason" aria-describedby={d} value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}><SelectOptions values={REASON_CODES} empty="Choose…" /></select>}</IdField>
        <Field label="Note">{(id) => <textarea id={id} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
        <div className="actions"><button type="submit" className={kind === 'release' ? undefined : 'danger-button'}>{kind === 'isolate' ? 'Isolate now' : kind === 'release' ? 'Release' : 'Revoke'}</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

function EventTable({ events }: { events: Awaited<ReturnType<ReturnType<typeof deviceSecurityApi>['events']>> }) {
  if (events.length === 0) return <Empty>No events.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Security events</caption>
        <thead><tr><th scope="col">#</th><th scope="col">When</th><th scope="col">Type</th><th scope="col">Severity</th><th scope="col">Endpoint / device</th><th scope="col">Actor</th><th scope="col">Source</th><th scope="col">Reason</th><th scope="col">Chain hash</th></tr></thead>
        <tbody>
          {events.map((ev) => (
            <tr key={ev.id}>
              <td className="num small">{ev.id}</td>
              <td className="small">{formatDate(ev.occurredAt)}</td>
              <th scope="row">{humanise(ev.type)}</th>
              <td><CodeBadge code={ev.severity} /></td>
              <td className="mono small">{ev.endpointId ?? '–'}{ev.deviceInstanceId ? ` / ${ev.deviceInstanceId}` : ''}</td>
              <td className="small">{ev.actor ?? '–'}</td>
              <td className="small">{ev.sourceModule}</td>
              <td className="small">{ev.reasonCode ? humanise(ev.reasonCode) : '–'}</td>
              <td><Hash value={ev.hash} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ====================================================================== alerts & incidents

/** `#/security/devices/alerts` – anomaly alerts (acknowledge, close, open incident) and the compromise-playbook incidents. */
export function SecurityAlerts(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const api = deviceSecurityApi(client);
  const [status, setStatus] = useState('OPEN');
  const alerts = useAsync(() => api.alerts(status || undefined), onSessionExpired, [status]);
  const incidents = useAsync(() => api.incidents(), onSessionExpired);
  const rules = useAsync(() => api.anomalyRules(), onSessionExpired);
  const [closing, setClosing] = useState<SecurityAlert>();
  const [incidentFor, setIncidentFor] = useState<SecurityAlert>();
  const [closingIncident, setClosingIncident] = useState<string>();
  const action = useAction(onSessionExpired);
  const rows = alerts.data ?? [];
  return (
    <section>
      <PageTitle sub="Anomaly rules raise alerts; isolation completes within 15 minutes of detection (drill record per incident).">Alerts & incidents</PageTitle>
      <form className="filters" aria-label="Filter alerts" onSubmit={(e) => e.preventDefault()}>
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={['OPEN', 'ACKNOWLEDGED', 'CLOSED']} empty="All" /></select>}</Field>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {alerts.loading && !alerts.data && <Loading />}
      <ErrorAlert error={alerts.error} />
      {alerts.data && rows.length === 0 && <Empty>No alerts.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'alert')}</caption>
            <thead><tr><th scope="col">Detected</th><th scope="col">Rule</th><th scope="col">Severity</th><th scope="col">Endpoint</th><th scope="col" className="num">Occurrences</th><th scope="col">Automatic action</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <td className="small">{formatDate(a.detectedAt)}</td>
                  <th scope="row">{humanise(a.ruleCode)}</th>
                  <td><CodeBadge code={a.severity} /></td>
                  <td className="mono small">{a.endpointId ? <a href={`#/security/devices/endpoints/${encodeURIComponent(a.endpointId)}`}>{a.endpointId}</a> : a.subjectKey ?? '–'}</td>
                  <td className="num">{a.occurrences}</td>
                  <td className="small">{a.autoAction ? humanise(a.autoAction) : '–'}{a.isolatedAt ? ` · isolated ${formatDate(a.isolatedAt)}` : a.isolationDueAt ? ` · isolation due ${formatDate(a.isolationDueAt)}` : ''}</td>
                  <td><CodeBadge code={a.status} />{a.resolution ? <span className="small muted block">{a.resolution}</span> : null}</td>
                  <td className="actions">
                    {a.status === 'OPEN' && <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void action.run(async () => { await api.acknowledgeAlert(a.id); alerts.reload(); return 'Alert acknowledged.'; })}>Acknowledge</button>}
                    {a.status !== 'CLOSED' && <button type="button" className="secondary small-button" onClick={() => setClosing(a)}>Close</button>}
                    {a.endpointId && a.status !== 'CLOSED' && <button type="button" className="small-button" onClick={() => setIncidentFor(a)}>Open incident</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <section className="card" aria-labelledby="inc-title">
        <h2 id="inc-title">Incidents</h2>
        {incidents.loading && !incidents.data && <Loading />}
        <ErrorAlert error={incidents.error} />
        {incidents.data && incidents.data.length === 0 && <Empty>No incidents.</Empty>}
        {(incidents.data?.length ?? 0) > 0 && (
          <table>
            <caption className="visually-hidden">Incidents</caption>
            <thead><tr><th scope="col">Incident</th><th scope="col">Endpoint</th><th scope="col">Opened</th><th scope="col">Time to isolate</th><th scope="col">Evidence</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {incidents.data!.map((i) => (
                <tr key={i.incidentRef}>
                  <th scope="row" className="mono">{i.incidentRef}{i.summary ? <span className="small muted block">{i.summary}</span> : null}</th>
                  <td className="mono small">{i.endpointId}</td>
                  <td className="small">{i.openedBy} {formatDate(i.openedAt)}</td>
                  <td>{i.minutesToIsolate == null ? <span className="muted">Not isolated</span> : <CodeBadge code={i.withinSla ? 'PASS' : 'FAIL'} label={`${i.minutesToIsolate.toFixed(1)} min (SLA ${i.slaMinutes})`} />}</td>
                  <td className="small">{(i.evidence ?? []).map((ev) => humanise(ev.type)).join(', ') || '–'}</td>
                  <td><CodeBadge code={i.status} /></td>
                  <td>{i.status === 'OPEN' && <button type="button" className="secondary small-button" onClick={() => setClosingIncident(i.incidentRef)}>Close</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <details className="card">
        <summary>Anomaly rules</summary>
        <ErrorAlert error={rules.error} />
        {rules.data && (
          <table>
            <caption className="visually-hidden">Anomaly rules</caption>
            <thead><tr><th scope="col">Rule</th><th scope="col">Severity</th><th scope="col">Threshold / window</th><th scope="col">Action</th><th scope="col">Enabled</th></tr></thead>
            <tbody>{rules.data.map((r) => <tr key={r.code}><th scope="row">{humanise(r.code)}{r.description ? <span className="small muted block">{r.description}</span> : null}</th><td><CodeBadge code={r.severity} /></td><td className="small">{r.threshold} in {r.windowMinutes} min{r.isolateThreshold ? ` · isolate at ${r.isolateThreshold}` : ''}</td><td>{humanise(r.action)}</td><td>{r.enabled ? 'Yes' : 'No'}</td></tr>)}</tbody>
          </table>
        )}
      </details>
      {closing && <TextDialog title={`Close alert ${humanise(closing.ruleCode)}`} label="Resolution" onClose={() => setClosing(undefined)} onSubmit={(t) => { const a = closing; setClosing(undefined); void action.run(async () => { await api.closeAlert(a.id, t); alerts.reload(); return 'Alert closed.'; }); }} />}
      {closingIncident && <TextDialog title={`Close incident ${closingIncident}`} label="Resolution" onClose={() => setClosingIncident(undefined)} onSubmit={(t) => { const ref = closingIncident; setClosingIncident(undefined); void action.run(async () => { await api.closeIncident(ref, t); incidents.reload(); return `Incident ${ref} closed.`; }); }} />}
      {incidentFor && <TextDialog title={`Open incident for ${incidentFor.endpointId}`} label="Summary" onClose={() => setIncidentFor(undefined)} onSubmit={(t) => { const a = incidentFor; setIncidentFor(undefined); void action.run(async () => { const r = await api.openIncident({ endpointId: a.endpointId ?? '', alertIds: [a.id], summary: t }); incidents.reload(); return `Incident ${r.incidentRef} opened – evidence preserved by archive reference and hash.`; }); }} />}
    </section>
  );
}

function TextDialog({ title, label, onClose, onSubmit }: { title: string; label: string; onClose: () => void; onSubmit: (text: string) => void }) {
  const [text, setText] = useState('');
  const [error, setError] = useState<string>();
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); if (!text.trim()) { setError(`${label} is required`); return; } onSubmit(text.trim()); }}>
        <IdField id="txt-dlg" label={label} required error={error}>{(d) => <textarea id="txt-dlg" rows={3} maxLength={500} aria-describedby={d} value={text} onChange={(e) => setText(e.target.value)} />}</IdField>
        <div className="actions"><button type="submit">Save</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

// ====================================================================== security event log

/** `#/security/devices/events` – append-only, hash-chained security event log with chain verification. */
export function SecurityEvents({ client, onSessionExpired }: ScreenProps) {
  const api = deviceSecurityApi(client);
  const [f, setF] = useState({ type: '', endpointId: '' });
  const [applied, setApplied] = useState(f);
  const events = useAsync(() => api.events({ type: applied.type || undefined, endpointId: applied.endpointId || undefined, limit: 200 }), onSessionExpired, [applied]);
  const verify = useAction(onSessionExpired);
  const [result, setResult] = useState<{ intact: boolean; checked: number; firstBrokenId?: number | null }>();
  return (
    <section>
      <PageTitle sub="PHI-free events from the kernel, engine, console and capture modules – forwarded to the SIEM. The table rejects updates and deletes; verification recomputes every hash.">Security event log</PageTitle>
      <div className="card toolbar">
        <div>
          <button type="button" disabled={verify.busy} onClick={() => void verify.run(async () => { setResult(await api.verifyChain()); })}>{verify.busy ? 'Verifying…' : 'Verify hash chain'}</button>
        </div>
        <div aria-live="polite">
          {result && (result.intact
            ? <CodeBadge code="PASS" label={`Chain intact – ${result.checked} events verified`} />
            : <CodeBadge code="FAIL" label={`Chain broken at event #${result.firstBrokenId} (${result.checked} checked)`} />)}
        </div>
      </div>
      <ActionFeedback error={verify.error} />
      <form className="filters" aria-label="Filter events" onSubmit={(e) => { e.preventDefault(); setApplied({ type: f.type, endpointId: f.endpointId.trim() }); }}>
        <Field label="Type">{(id) => <select id={id} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}><SelectOptions values={EVENT_TYPES} empty="All types" /></select>}</Field>
        <Field label="Endpoint">{(id) => <input id={id} maxLength={64} value={f.endpointId} onChange={(e) => setF({ ...f, endpointId: e.target.value })} />}</Field>
        <div className="filter-actions"><button type="submit">Filter</button></div>
      </form>
      {events.loading && !events.data && <Loading />}
      <ErrorAlert error={events.error} />
      {events.data && <EventTable events={events.data} />}
    </section>
  );
}

// ====================================================================== zones, keys, secrets

/** `#/security/devices/zones` – zones, conduits and the firewall policy template export. */
export function SecurityZones({ client, onSessionExpired }: ScreenProps) {
  const api = deviceSecurityApi(client);
  const zones = useAsync(() => api.zones(), onSessionExpired);
  const conduits = useAsync(() => api.conduits(), onSessionExpired);
  const cidrs = useAsync(() => api.zoneCidrs(), onSessionExpired);
  return (
    <section>
      <PageTitle sub="Deny-by-default device VLAN; devices never reach the core directly. Gateways and Device Host agents must call from their site's gateway-zone ranges.">Zones & conduits</PageTitle>
      <div className="actions">
        <a className="button" href={conduitExportUrl('csv')} download="device-conduits-firewall-template.csv">Export firewall template (CSV)</a>
        <a className="button secondary" href={conduitExportUrl('json')} download="device-conduits-firewall-template.json">Export (JSON)</a>
      </div>
      <ErrorAlert error={zones.error ?? conduits.error} />
      {zones.data && (
        <section className="card" aria-labelledby="z-title">
          <h2 id="z-title">Zones</h2>
          <table>
            <caption className="visually-hidden">Zones</caption>
            <thead><tr><th scope="col">Zone</th><th scope="col">Default policy</th><th scope="col">Description</th></tr></thead>
            <tbody>{zones.data.map((z) => <tr key={z.code}><th scope="row">{z.name} <span className="mono small muted">{z.code}</span></th><td>{humanise(z.defaultPolicy)}</td><td className="small">{z.description ?? '–'}</td></tr>)}</tbody>
          </table>
        </section>
      )}
      {conduits.data && (
        <section className="card" aria-labelledby="c-title">
          <h2 id="c-title">Conduits</h2>
          <table>
            <caption className="visually-hidden">Conduits</caption>
            <thead><tr><th scope="col">From</th><th scope="col">To</th><th scope="col">Protocol</th><th scope="col">Ports</th><th scope="col">Direction</th><th scope="col">Purpose</th></tr></thead>
            <tbody>{conduits.data.map((c) => <tr key={c.id}><th scope="row">{humanise(c.fromZone)}</th><td>{humanise(c.toZone)}</td><td className="mono small">{c.protocol}</td><td className="mono small">{c.ports}</td><td>{humanise(c.direction)}</td><td className="small">{c.description ?? '–'}</td></tr>)}</tbody>
          </table>
        </section>
      )}
      {cidrs.data && (
        <section className="card" aria-labelledby="r-title">
          <h2 id="r-title">Zone address ranges</h2>
          {cidrs.data.length === 0 ? <Empty>No ranges configured.</Empty> : (
            <table>
              <caption className="visually-hidden">Zone address ranges</caption>
              <thead><tr><th scope="col">Site</th><th scope="col">Zone</th><th scope="col">CIDR</th></tr></thead>
              <tbody>{cidrs.data.map((c) => <tr key={c.id}><th scope="row">{c.siteCode}</th><td>{humanise(c.zone)}</td><td className="mono">{c.cidr}</td></tr>)}</tbody>
            </table>
          )}
        </section>
      )}
    </section>
  );
}

/** `#/security/devices/keys` – driver-package signing keys: rotate, revoke (packages signed with a revoked key are revoked). */
export function SigningKeys({ client, session, onSessionExpired }: ScreenProps) {
  const api = deviceSecurityApi(client);
  const keys = useAsync(() => api.signingKeys(), onSessionExpired);
  const [confirmRevoke, setConfirmRevoke] = useState<string>();
  const [algorithm, setAlgorithm] = useState('Ed25519');
  const action = useAction(onSessionExpired);
  const writer = can(session.roles, 'securityControls');
  return (
    <section>
      <PageTitle sub="Ed25519 (or ECDSA P-256) keys sign the canonical driver-package JSON. Private keys stay encrypted server-side; only public keys are shown.">Signing keys</PageTitle>
      {writer && (
        <form className="card inline-form" aria-label="Rotate the signing key" onSubmit={(e) => { e.preventDefault(); void action.run(async () => { const k = await api.rotateKey(algorithm); keys.reload(); return `New active key ${k.keyId}; the previous key is retired (still verifies existing packages).`; }); }}>
          <Field label="Algorithm">{(id) => <select id={id} value={algorithm} onChange={(e) => setAlgorithm(e.target.value)}><option value="Ed25519">Ed25519</option><option value="SHA256withECDSA">ECDSA P-256 (SHA-256)</option></select>}</Field>
          <button type="submit" disabled={action.busy}>Rotate key</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {keys.loading && !keys.data && <Loading />}
      <ErrorAlert error={keys.error} />
      {keys.data && (keys.data.length === 0 ? <Empty>No signing keys.</Empty> : (
        <table>
          <caption>{plural(keys.data.length, 'key')}</caption>
          <thead><tr><th scope="col">Key</th><th scope="col">Algorithm</th><th scope="col">Status</th><th scope="col">Public key</th><th scope="col">Created</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
          <tbody>
            {keys.data.map((k) => (
              <tr key={k.keyId}>
                <th scope="row" className="mono">{k.keyId}</th>
                <td>{k.algorithm}</td>
                <td><CodeBadge code={k.status === 'RETIRED' ? 'RETIRED_KEY' : k.status} label={humanise(k.status)} /></td>
                <td><Hash value={k.publicKey} /></td>
                <td className="small">{k.createdBy ?? '–'} {formatDate(k.createdAt)}</td>
                <td>{writer && k.status !== 'REVOKED' && <button type="button" className="danger-button small-button" onClick={() => setConfirmRevoke(k.keyId)}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
      {confirmRevoke && (
        <Dialog title={`Revoke key ${confirmRevoke}`} onClose={() => setConfirmRevoke(undefined)}>
          <p>Every driver package signed with this key becomes REVOKED in the registry and the device instances using them are suspended. This cannot be undone.</p>
          <div className="actions">
            <button type="button" className="danger-button" onClick={() => { const id = confirmRevoke; setConfirmRevoke(undefined); void action.run(async () => { await api.revokeKey(id); keys.reload(); return `Key ${id} revoked.`; }); }}>Revoke key</button>
            <button type="button" className="secondary" onClick={() => setConfirmRevoke(undefined)}>Cancel</button>
          </div>
        </Dialog>
      )}
    </section>
  );
}

/** `#/security/devices/secrets` – connector secrets: metadata only, values are write-only (rotate keeps the reference). */
export function ConnectorSecrets({ client, onSessionExpired }: ScreenProps) {
  const api = deviceSecurityApi(client);
  const [scope, setScope] = useState('');
  const [applied, setApplied] = useState('');
  const list = useAsync(() => api.secrets(applied || undefined), onSessionExpired, [applied]);
  const [rotating, setRotating] = useState<SecretMetadata>();
  const action = useAction(onSessionExpired);
  return (
    <section>
      <PageTitle sub="Values are encrypted, never logged and never returned to a browser. Only the engine runtime and the bound gateway can resolve them (audited with purpose).">Connector secrets</PageTitle>
      <form className="filters" aria-label="Filter secrets" onSubmit={(e) => { e.preventDefault(); setApplied(scope.trim()); }}>
        <Field label="Scope">{(id) => <input id={id} maxLength={80} value={scope} placeholder="device-configuration:CFG-000001" onChange={(e) => setScope(e.target.value)} />}</Field>
        <div className="filter-actions"><button type="submit">Filter</button></div>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      <ErrorAlert error={list.error} />
      {list.data && (list.data.length === 0 ? <Empty>No secrets.</Empty> : (
        <div className="table-wrap">
          <table>
            <caption>{plural(list.data.length, 'secret')} (metadata only)</caption>
            <thead><tr><th scope="col">Reference</th><th scope="col">Scope / name</th><th scope="col">Value</th><th scope="col">Created</th><th scope="col">Rotated</th><th scope="col">Last resolved</th><th scope="col">Bound instances</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {list.data.map((s) => (
                <tr key={s.reference}>
                  <th scope="row" className="mono small">{s.reference}</th>
                  <td className="small">{s.scope} / {s.name}</td>
                  <td>{s.destroyed ? <CodeBadge code="REVOKED" label="Destroyed" /> : <CodeBadge code="PASS" label="Set" />}</td>
                  <td className="small">{s.createdBy ?? '–'} {formatDate(s.createdAt)}</td>
                  <td className="small">{formatDate(s.rotatedAt)}</td>
                  <td className="small">{formatDate(s.lastResolvedAt)}</td>
                  <td className="mono small">{s.boundInstances?.join(', ') || '–'}</td>
                  <td>{!s.destroyed && <button type="button" className="secondary small-button" aria-label={`Rotate ${s.name}`} onClick={() => setRotating(s)}>Rotate</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      {rotating && <RotateSecretDialog name={rotating.name} onClose={() => setRotating(undefined)} onSubmit={(v) => { const s = rotating; setRotating(undefined); void action.run(async () => { await api.rotateSecret(s.reference, v); list.reload(); return `${s.name} rotated – the reference is unchanged.`; }); }} />}
    </section>
  );
}

function RotateSecretDialog({ name, onClose, onSubmit }: { name: string; onClose: () => void; onSubmit: (value: string) => void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState<string>();
  return (
    <Dialog title={`Rotate ${name}`} onClose={onClose}>
      <form className="form" noValidate onSubmit={(e) => { e.preventDefault(); if (!value) { setError('Enter the new value'); return; } onSubmit(value); }}>
        <IdField id="sec-value" label="New value" required hint="Write-only – it is never shown again." error={error}>{(d) => <input id="sec-value" type="password" autoComplete="new-password" aria-describedby={d} value={value} onChange={(e) => setValue(e.target.value)} />}</IdField>
        <div className="actions"><button type="submit">Rotate</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
      </form>
    </Dialog>
  );
}

// ====================================================================== assurance & go-live

/** `#/security/devices/assurance` – go-live readiness gate, security test-pack runs, SDLC and penetration-test records. */
export function SecurityAssurance(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = deviceSecurityApi(client);
  const readiness = useAsync(() => api.readiness(), onSessionExpired);
  const runs = useAsync(() => api.testPackRuns(), onSessionExpired);
  const sdlc = useAsync(() => api.sdlc(), onSessionExpired);
  const pentest = useAsync(() => api.pentestFindings(), onSessionExpired);
  const agreement = useAsync(() => api.agreement(), onSessionExpired);
  const r = readiness.data;
  return (
    <section>
      <PageTitle sub="The go-live gate: no open critical penetration-test finding, no overdue critical / high vulnerability, a passing test-pack run, a signed IEC 80001-1 responsibility agreement and an approved DPIA.">Assurance & go-live</PageTitle>
      <section className="card" aria-labelledby="gl-title">
        <h2 id="gl-title">Go-live readiness</h2>
        {readiness.loading && !r && <Loading />}
        <ErrorAlert error={readiness.error} />
        {r && (
          <>
            <p>{r.ready ? <CodeBadge code="PASS" label="Ready for go-live" /> : <CodeBadge code="FAIL" label="Not ready for go-live" />}</p>
            <ul className="plain-list checklist">{r.checks.map((c) => <li key={c.code}><CodeBadge code={c.passed ? 'PASS' : 'FAIL'} label={c.passed ? 'Passed' : 'Failed'} /> {humanise(c.code)}{c.detail ? ` – ${c.detail}` : ''}</li>)}</ul>
          </>
        )}
      </section>
      <section className="card" aria-labelledby="tp-title">
        <h2 id="tp-title">Security test-pack runs</h2>
        {runs.loading && !runs.data && <Loading />}
        <ErrorAlert error={runs.error} />
        {runs.data && (runs.data.length === 0 ? <Empty>No runs recorded.</Empty> : (
          <table>
            <caption className="visually-hidden">Test-pack runs</caption>
            <thead><tr><th scope="col">Run</th><th scope="col">Environment</th><th scope="col" className="num">Passed</th><th scope="col" className="num">Failed</th><th scope="col" className="num">Not run</th><th scope="col">Failed controls</th></tr></thead>
            <tbody>
              {runs.data.map((t) => (
                <tr key={t.id}>
                  <th scope="row">v{t.version} <span className="small muted">{t.runBy} {formatDate(t.runAt)}</span></th>
                  <td>{t.environment}</td>
                  <td className="num">{t.passed}</td>
                  <td className={`num${t.failed ? ' text-red' : ''}`}>{t.failed}</td>
                  <td className="num">{t.notRun}</td>
                  <td className="small">{t.results.filter((x) => x.outcome === 'FAIL').map((x) => `${x.control} (${x.script})`).join(', ') || 'None'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
        {can(session.roles, 'securityControls') && <RecordRun {...props} onDone={() => { runs.reload(); readiness.reload(); }} />}
      </section>
      <div className="grid-2">
        <section className="card" aria-labelledby="pt-title">
          <h2 id="pt-title">Penetration-test findings</h2>
          <ErrorAlert error={pentest.error} />
          {pentest.data && (pentest.data.length === 0 ? <Empty>No findings.</Empty> : (
            <table>
              <caption className="visually-hidden">Penetration-test findings</caption>
              <thead><tr><th scope="col">Finding</th><th scope="col">Severity</th><th scope="col">Status</th></tr></thead>
              <tbody>{pentest.data.map((p) => <tr key={p.ref}><th scope="row"><span className="mono small">{p.ref}</span> {p.title}</th><td><CodeBadge code={p.severity} /></td><td><CodeBadge code={p.status} />{p.closedOn ? <span className="small muted"> {formatDay(p.closedOn)}</span> : null}</td></tr>)}</tbody>
            </table>
          ))}
        </section>
        <section className="card" aria-labelledby="sd-title">
          <h2 id="sd-title">Secure life-cycle</h2>
          <ErrorAlert error={sdlc.error} />
          {sdlc.data && (
            <>
              <Dl items={[
                ['SBOM', sdlc.data.sbom ? `${sdlc.data.sbom.release} (${sdlc.data.sbom.format}) ${formatDay(sdlc.data.sbom.generatedOn)}` : 'Not recorded'],
                ['Threat models', `${sdlc.data.threatModels.length} reviewed`],
                ['Disclosure URL', sdlc.data.disclosureUrl],
                ['IEC 80001-1 agreement', agreement.data?.signedOn ? `v${agreement.data.version} signed ${formatDay(agreement.data.signedOn)}` : 'Not signed'],
              ]} />
              {sdlc.data.vulnerabilities.length > 0 && (
                <table>
                  <caption>Vulnerabilities</caption>
                  <thead><tr><th scope="col">ID</th><th scope="col">Severity</th><th scope="col">Due</th><th scope="col">Status</th></tr></thead>
                  <tbody>{sdlc.data.vulnerabilities.map((v) => <tr key={v.id}><th scope="row" className="mono small">{v.id} <span className="muted">{v.component}</span></th><td><CodeBadge code={v.severity} /></td><td className="small">{formatDay(v.dueOn)} {v.overdue && <CodeBadge code="OVERDUE" label="Overdue" />}</td><td><CodeBadge code={v.status} /></td></tr>)}</tbody>
                </table>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  );
}

const PACK_SCRIPTS: [string, string][] = [
  ['01_network_segmentation_check.py', 'SC-02'], ['02_tls_mtls_check.sh', 'SC-01'], ['03_mllp_auth_and_robustness.py', 'SC-12'], ['04_fhir_api_authz.py', 'SC-11'],
  ['05_gateway_hardening_linux.sh', 'SC-03'], ['06_ble_pairing_policy.py', 'SC-04'], ['07_sbom_and_vuln_scan.sh', 'SC-06'], ['08_secrets_and_config_audit.py', 'SC-05'],
  ['09_audit_log_integrity.py', 'SC-08'], ['10_encryption_at_rest_check.sh', 'SC-10'], ['11_zap_api_baseline.sh', 'SC-11'], ['12_device_default_credentials_check.py', 'SC-07'],
  ['13_time_sync_and_cert_expiry.py', 'SC-14'], ['14_rate_limit_and_anomaly_check.py', 'SC-13'],
];

function RecordRun({ client, onSessionExpired, onDone }: ScreenProps & { onDone: () => void }) {
  const api = deviceSecurityApi(client);
  const [meta, setMeta] = useState({ version: '', environment: 'test' });
  const [results, setResults] = useState<TestPackResult[]>(PACK_SCRIPTS.map(([script, control]) => ({ script, control, outcome: 'NOT_RUN' })));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!meta.version.trim()) errs.push({ field: 'run-version', message: 'Enter the test-pack version' });
    if (!/^[A-Za-z0-9_-]{1,30}$/.test(meta.environment)) errs.push({ field: 'run-env', message: 'Enter the environment (letters, digits, - or _)' });
    results.forEach((r, i) => { if (r.evidenceSha256 && !/^[0-9a-f]{64}$/.test(r.evidenceSha256)) errs.push({ field: `run-sha-${i}`, message: `${r.script}: evidence SHA-256 must be 64 lower-case hex characters` }); });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { const run = await api.recordTestPackRun({ version: meta.version.trim(), environment: meta.environment, results: results.map((r) => ({ ...r, ...(r.evidenceSha256 ? {} : { evidenceSha256: undefined }) })) }); return `Run recorded: ${run.passed} passed, ${run.failed} failed, ${run.notRun} not run.`; })) onDone();
  }
  return (
    <details>
      <summary>Record a test-pack run</summary>
      <form className="form" onSubmit={submit} noValidate aria-label="Record a test-pack run">
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="run-version" label="Test-pack version" required error={errorFor(errors, 'run-version')}>{(d) => <input id="run-version" aria-describedby={d} maxLength={30} value={meta.version} onChange={(e) => setMeta({ ...meta, version: e.target.value })} />}</IdField>
          <IdField id="run-env" label="Environment" required error={errorFor(errors, 'run-env')}>{(d) => <input id="run-env" aria-describedby={d} maxLength={30} value={meta.environment} onChange={(e) => setMeta({ ...meta, environment: e.target.value })} />}</IdField>
        </div>
        <table>
          <caption className="visually-hidden">Script results</caption>
          <thead><tr><th scope="col">Script</th><th scope="col">Control</th><th scope="col">Outcome</th><th scope="col">Evidence SHA-256</th></tr></thead>
          <tbody>
            {results.map((r, i) => (
              <tr key={r.script}>
                <th scope="row" className="mono small">{r.script}</th>
                <td>{r.control}</td>
                <td><select aria-label={`Outcome of ${r.script}`} value={r.outcome} onChange={(e) => setResults(results.map((x, j) => (j === i ? { ...x, outcome: e.target.value } : x)))}><SelectOptions values={TEST_OUTCOMES} /></select></td>
                <td><input id={`run-sha-${i}`} aria-label={`Evidence SHA-256 of ${r.script}`} className="mono" maxLength={64} value={r.evidenceSha256 ?? ''} onChange={(e) => setResults(results.map((x, j) => (j === i ? { ...x, evidenceSha256: e.target.value.trim() || undefined } : x)))} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="actions"><button type="submit" disabled={action.busy}>Record run</button></div>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </details>
  );
}
