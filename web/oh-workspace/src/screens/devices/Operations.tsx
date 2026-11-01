import { useState, type FormEvent } from 'react';
import { DEVICE_FAMILIES, calibrationReportUrl, certificatePdfUrl, devicesApi, type CalibrationSchedule, type CalibrationStatus } from '../../api/devices';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { KpiTile } from '../../components/Tone';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { plural } from '../../domain/deviceConsole';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { CodeBadge, CodeOptions, UnavailableNotice } from './common';

// ====================================================================== calibration, QC and lockout

/** `#/devices/calibration` – daily / periodic checks and QC lots (E14 stays the calibration authority), lockout and release. */
export function DeviceCalibration(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [site, setSite] = useState('');
  const status = useAsync(() => api.calibrationStatus({ siteCode: site || undefined }), onSessionExpired, [site]);
  const schedules = useAsync(() => api.schedules(), onSessionExpired);
  const [checkFor, setCheckFor] = useState<CalibrationStatus>();
  const [historyFor, setHistoryFor] = useState<string>();
  const [editSchedule, setEditSchedule] = useState<CalibrationSchedule>();
  const action = useAction(onSessionExpired);
  const rows = status.data ?? [];
  const sites = [...new Set(rows.map((r) => r.siteCode).filter((s): s is string => !!s))];
  const locked = rows.filter((r) => r.lockedOut).length;
  return (
    <section>
      <PageTitle sub="A failed or overdue check locks the device out of capture until a newer passing check is recorded – there is no override.">Calibration & QC</PageTitle>
      <div className="tiles">
        <KpiTile label="Published devices" value={rows.length} />
        <KpiTile label="Locked out" value={locked} status={locked ? 'MISSED' : 'MET'} target="Target 0" />
        <KpiTile label="Checks due or overdue" value={rows.reduce((n, r) => n + r.checks.filter((c) => c.verdict !== 'VALID').length, 0)} />
      </div>
      <form className="filters" aria-label="Filter devices" onSubmit={(e) => e.preventDefault()}>
        <Field label="Site">{(id) => <select id={id} value={site} onChange={(e) => setSite(e.target.value)}><CodeOptions values={sites.length ? sites : site ? [site] : []} empty="All sites" /></select>}</Field>
        {can(session.roles, 'deviceCalibrationReport') && (
          <div className="filter-actions">
            <a className="button secondary" href={calibrationReportUrl(site || undefined, 'csv')} download>Compliance report (CSV)</a>
            <a className="button secondary" href={calibrationReportUrl(site || undefined, 'pdf')} download>Compliance report (PDF)</a>
          </div>
        )}
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {status.loading && !status.data && <Loading />}
      <ErrorAlert error={status.error} />
      {status.data && rows.length === 0 && <Empty>No published devices.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Device check status</caption>
            <thead><tr><th scope="col">Device</th><th scope="col">Station</th><th scope="col">Checks</th><th scope="col">Calibration (E14)</th><th scope="col">Capture</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.deviceInstanceId}>
                  <th scope="row">{r.model} <span className="mono small muted">{r.deviceInstanceId}</span></th>
                  <td className="small">{r.siteCode} / {r.stationCode}</td>
                  <td className="small">
                    <ul className="plain-list">{r.checks.map((c) => <li key={c.checkType}><CodeBadge code={c.verdict} /> {humanise(c.checkType)}{c.dueAt ? ` – due ${formatDate(c.dueAt)}` : ''}</li>)}</ul>
                  </td>
                  <td className="small"><CodeBadge code={r.calibration?.verdict} />{r.calibration?.dueOn ? ` due ${formatDay(r.calibration.dueOn)}` : ''}</td>
                  <td>{r.lockedOut ? <CodeBadge code="FAIL" label={`Locked out (${humanise(r.lockoutReason ?? 'QC')})`} /> : <CodeBadge code="PASS" label="Available" />}</td>
                  <td className="actions">
                    {can(session.roles, 'deviceCalibrationCheck') && <button type="button" className="small-button" aria-label={`Record a check for ${r.deviceInstanceId}`} onClick={() => setCheckFor(r)}>Record check</button>}
                    {can(session.roles, 'deviceAdmin') && r.lockedOut && r.lockoutReason === 'QC' && (
                      <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void action.run(async () => { await api.releaseLockout(r.deviceInstanceId); status.reload(); return `${r.deviceInstanceId} released.`; })}>Release</button>
                    )}
                    <button type="button" className="link-button small" onClick={() => setHistoryFor(r.deviceInstanceId)}>History</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {historyFor && <CheckHistory {...props} instanceId={historyFor} onClose={() => setHistoryFor(undefined)} />}
      <section className="card" aria-labelledby="sch-title">
        <h2 id="sch-title">Schedules</h2>
        {schedules.loading && <Loading />}
        <ErrorAlert error={schedules.error} />
        {schedules.data && (
          <table>
            <caption className="visually-hidden">Check schedules</caption>
            <thead><tr><th scope="col">Check</th><th scope="col">Family</th><th scope="col" className="num">Interval (h)</th><th scope="col" className="num">Grace (min)</th><th scope="col">Lockout</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {schedules.data.map((s) => (
                <tr key={`${s.family}-${s.checkType}`}>
                  <th scope="row">{humanise(s.checkType)}{s.description ? <span className="small muted block">{s.description}</span> : null}</th>
                  <td>{humanise(s.family)}</td>
                  <td className="num">{s.intervalHours}{s.perLot ? ' + per lot' : ''}</td>
                  <td className="num">{s.graceMinutes ?? 0}</td>
                  <td>{s.lockout ? 'Yes' : 'No'}</td>
                  <td>{can(session.roles, 'deviceAdmin') && <button type="button" className="secondary small-button" aria-label={`Edit ${humanise(s.checkType)} schedule`} onClick={() => setEditSchedule(s)}>Edit</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {checkFor && <CheckDialog {...props} device={checkFor} onClose={() => setCheckFor(undefined)} onDone={(msg) => { setCheckFor(undefined); status.reload(); void action.run(async () => msg); }} />}
      {editSchedule && <ScheduleDialog {...props} schedule={editSchedule} onClose={() => setEditSchedule(undefined)} onDone={(msg) => { setEditSchedule(undefined); schedules.reload(); void action.run(async () => msg); }} />}
    </section>
  );
}

function CheckDialog({ client, onSessionExpired, device, onClose, onDone }: ScreenProps & { device: CalibrationStatus; onClose: () => void; onDone: (msg: string) => void }) {
  const api = devicesApi(client);
  const [f, setF] = useState({ checkType: device.checks[0]?.checkType ?? '', performedAt: toLocalInput(), outcome: '', lot: '', lotExpiry: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.checkType) errs.push({ field: 'qc-type', message: 'Choose the check' });
    const at = localToIso(f.performedAt);
    if (!at) errs.push({ field: 'qc-at', message: 'Enter when the check was performed' });
    else if (new Date(at).getTime() > Date.now() + 5 * 60_000) errs.push({ field: 'qc-at', message: 'The check cannot be in the future' });
    if (!f.outcome) errs.push({ field: 'qc-pass', message: 'Record pass or fail' });
    setErrors(errs);
    if (errs.length || !at) return;
    if (await action.run(async () => { await api.recordCheck({ deviceInstanceId: device.deviceInstanceId, checkType: f.checkType, performedAt: at, outcome: f.outcome as 'PASS' | 'FAIL', ...(f.lot ? { lot: f.lot } : {}), ...(f.lotExpiry ? { lotExpiry: f.lotExpiry } : {}) }); })) {
      onDone(f.outcome === 'FAIL' ? `${device.deviceInstanceId}: failed check recorded – the device is locked out.` : `${device.deviceInstanceId}: passing check recorded.`);
    }
  }
  return (
    <Dialog title={`Record a check – ${device.model}`} onClose={onClose}>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="qc-type" label="Check" required error={errorFor(errors, 'qc-type')}>{(d) => <select id="qc-type" aria-describedby={d} value={f.checkType} onChange={(e) => setF({ ...f, checkType: e.target.value })}><SelectOptions values={device.checks.map((c) => c.checkType)} empty="Choose…" /></select>}</IdField>
        <IdField id="qc-at" label="Performed at" required error={errorFor(errors, 'qc-at')}>{(d) => <input id="qc-at" type="datetime-local" aria-describedby={d} value={f.performedAt} onChange={(e) => setF({ ...f, performedAt: e.target.value })} />}</IdField>
        <fieldset>
          <legend>Outcome <span aria-hidden="true">*</span></legend>
          {errorFor(errors, 'qc-pass') && <span className="field-error-text small">{errorFor(errors, 'qc-pass')}</span>}
          <label className="inline-row"><input id="qc-pass" type="radio" name="qc-outcome" checked={f.outcome === 'PASS'} onChange={() => setF({ ...f, outcome: 'PASS' })} /> Pass</label>
          <label className="inline-row"><input type="radio" name="qc-outcome" checked={f.outcome === 'FAIL'} onChange={() => setF({ ...f, outcome: 'FAIL' })} /> Fail (locks the device out)</label>
        </fieldset>
        <div className="form-grid">
          <Field label="QC lot">{(id) => <input id={id} maxLength={40} value={f.lot} onChange={(e) => setF({ ...f, lot: e.target.value })} />}</Field>
          <Field label="Lot expiry">{(id) => <input id={id} type="date" value={f.lotExpiry} onChange={(e) => setF({ ...f, lotExpiry: e.target.value })} />}</Field>
        </div>
        <div className="actions"><button type="submit" disabled={action.busy}>Record check</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

function ScheduleDialog({ client, onSessionExpired, schedule, onClose, onDone }: ScreenProps & { schedule: CalibrationSchedule; onClose: () => void; onDone: (msg: string) => void }) {
  const api = devicesApi(client);
  const [f, setF] = useState({ intervalHours: String(schedule.intervalHours), graceMinutes: String(schedule.graceMinutes ?? 0), lockout: schedule.lockout, reminderBeforeHours: String(schedule.reminderBeforeHours ?? 2) });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    const num = (v: string) => /^\d+$/.test(v);
    if (!num(f.intervalHours) || Number(f.intervalHours) < 1) errs.push({ field: 'sc-interval', message: 'Interval must be a whole number of hours (at least 1)' });
    if (!num(f.graceMinutes)) errs.push({ field: 'sc-grace', message: 'Grace must be a whole number of minutes' });
    if (!num(f.reminderBeforeHours)) errs.push({ field: 'sc-reminder', message: 'Reminder must be a whole number of hours' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await api.putSchedule(schedule.family, schedule.checkType, { intervalHours: Number(f.intervalHours), graceMinutes: Number(f.graceMinutes), lockout: f.lockout, reminderBeforeHours: Number(f.reminderBeforeHours) }); })) onDone('Schedule change saved (production changes wait for a device approver).');
  }
  return (
    <Dialog title={`${humanise(schedule.checkType)} schedule`} onClose={onClose}>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="sc-interval" label="Interval (hours)" required error={errorFor(errors, 'sc-interval')}>{(d) => <input id="sc-interval" type="number" min={1} aria-describedby={d} value={f.intervalHours} onChange={(e) => setF({ ...f, intervalHours: e.target.value })} />}</IdField>
        <IdField id="sc-grace" label="Grace (minutes)" error={errorFor(errors, 'sc-grace')}>{(d) => <input id="sc-grace" type="number" min={0} aria-describedby={d} value={f.graceMinutes} onChange={(e) => setF({ ...f, graceMinutes: e.target.value })} />}</IdField>
        <IdField id="sc-reminder" label="Reminder before (hours)" error={errorFor(errors, 'sc-reminder')}>{(d) => <input id="sc-reminder" type="number" min={0} aria-describedby={d} value={f.reminderBeforeHours} onChange={(e) => setF({ ...f, reminderBeforeHours: e.target.value })} />}</IdField>
        <label className="inline-row"><input type="checkbox" checked={f.lockout} onChange={(e) => setF({ ...f, lockout: e.target.checked })} /> Lock the device out when the check fails or is overdue</label>
        <div className="actions"><button type="submit" disabled={action.busy}>Save</button><button type="button" className="secondary" onClick={onClose}>Cancel</button></div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

function CheckHistory({ client, onSessionExpired, instanceId, onClose }: ScreenProps & { instanceId: string; onClose: () => void }) {
  const h = useAsync(() => devicesApi(client).calibrationHistory(instanceId), onSessionExpired, [instanceId]);
  return (
    <section className="card" aria-labelledby="ch-title">
      <h2 id="ch-title">Check history – {instanceId}</h2>
      {h.loading && <Loading />}
      <ErrorAlert error={h.error} />
      {h.data && (
        <div className="grid-2">
          <table>
            <caption>Checks</caption>
            <thead><tr><th scope="col">When</th><th scope="col">Check</th><th scope="col">Outcome</th><th scope="col">Source</th></tr></thead>
            <tbody>{h.data.history.slice(0, 50).map((c, i) => <tr key={i}><td className="small">{formatDate(c.performedAt)}</td><th scope="row" className="small">{humanise(c.checkType)}{c.lot ? ` (lot ${c.lot})` : ''}</th><td><CodeBadge code={c.outcome} /></td><td className="small">{humanise(c.source)} {c.recordedBy ?? ''}</td></tr>)}</tbody>
          </table>
          <table>
            <caption>Lockouts and releases</caption>
            <thead><tr><th scope="col">When</th><th scope="col">Event</th><th scope="col">Check</th><th scope="col">By</th></tr></thead>
            <tbody>{h.data.lockoutEvents.slice(0, 50).map((c, i) => <tr key={i}><td className="small">{formatDate(c.at)}</td><th scope="row"><CodeBadge code={c.event === 'LOCKED' ? 'FAIL' : 'PASS'} label={humanise(c.event)} /></th><td className="small">{humanise(c.checkType)}</td><td className="small">{c.actor ?? '–'}</td></tr>)}</tbody>
          </table>
        </div>
      )}
      <button type="button" className="secondary" onClick={onClose}>Close</button>
    </section>
  );
}

// ====================================================================== fleet

/** `#/devices/fleet` – site inventory map, device health (counts and states only), alerts with ITSM ticket. */
export function DeviceFleet(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = devicesApi(client);
  const [filters, setFilters] = useState({ siteCode: '', family: '', state: '' });
  const sites = useAsync(() => api.fleetSites(), onSessionExpired);
  const fleet = useAsync(() => api.fleet({ siteCode: filters.siteCode || undefined, family: filters.family || undefined, state: filters.state || undefined }), onSessionExpired, [filters]);
  const alerts = useAsync(() => api.fleetAlerts(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const rows = fleet.data ?? [];
  const admin = can(session.roles, 'deviceAdmin');
  return (
    <section>
      <PageTitle sub="Health from the integration engine, versions, endpoint state, calibration and QC per published instance. No person data.">Fleet & alerts</PageTitle>
      {sites.data && (
        <div className="table-wrap">
          <table>
            <caption>Inventory by site</caption>
            <thead><tr><th scope="col">Site</th><th scope="col" className="num">Devices</th><th scope="col" className="num">Healthy</th><th scope="col" className="num">Degraded</th><th scope="col" className="num">Silent</th><th scope="col" className="num">Quarantined</th><th scope="col" className="num">Calibration due</th><th scope="col" className="num">Locked out</th></tr></thead>
            <tbody>
              {sites.data.map((s) => (
                <tr key={s.siteCode}>
                  <th scope="row"><button type="button" className="link-button" aria-pressed={filters.siteCode === s.siteCode} onClick={() => setFilters({ ...filters, siteCode: filters.siteCode === s.siteCode ? '' : s.siteCode })}>{s.siteCode}</button></th>
                  <td className="num">{s.devices}</td><td className="num">{s.healthy}</td><td className="num">{s.degraded}</td><td className="num">{s.silent}</td>
                  <td className={`num${s.quarantined ? ' text-red' : ''}`}>{s.quarantined}</td><td className="num">{s.calibrationDue}</td><td className={`num${s.lockedOut ? ' text-red' : ''}`}>{s.lockedOut}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="filters" aria-label="Filter the fleet" onSubmit={(e) => e.preventDefault()}>
        <Field label="Family">{(id) => <select id={id} value={filters.family} onChange={(e) => setFilters({ ...filters, family: e.target.value })}><SelectOptions values={DEVICE_FAMILIES} empty="All families" /></select>}</Field>
        <Field label="State">{(id) => <select id={id} value={filters.state} onChange={(e) => setFilters({ ...filters, state: e.target.value })}><SelectOptions values={['PUBLISHED', 'SUSPENDED', 'QUARANTINED', 'HEALTHY', 'DEGRADED', 'SILENT', 'STALE', 'UNKNOWN']} empty="All" /></select>}</Field>
      </form>
      {fleet.loading && !fleet.data && <Loading />}
      <ErrorAlert error={fleet.error} />
      {fleet.data && rows.length === 0 && <Empty>No published devices match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{plural(rows.length, 'device')}</caption>
            <thead><tr><th scope="col">Device</th><th scope="col">Station</th><th scope="col">Status</th><th scope="col">Health</th><th scope="col">Last seen</th><th scope="col">Endpoint</th><th scope="col">Driver / firmware</th><th scope="col">Calibration / QC</th><th scope="col">Alerts</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.deviceInstanceId}>
                  <th scope="row"><a href={`#/devices/fleet/${r.deviceInstanceId}`}>{r.model ?? r.modelCode}</a> <span className="mono small muted">{r.deviceInstanceId}</span></th>
                  <td className="small">{r.siteCode} / {r.stationCode}</td>
                  <td><CodeBadge code={r.status} /></td>
                  <td><CodeBadge code={r.health.state} title={r.health.reason} /></td>
                  <td className="small">{formatDate(r.lastSeenAt)}</td>
                  <td className="small">{r.endpointId ?? '–'} {r.endpointStatus && <CodeBadge code={r.endpointStatus} />}</td>
                  <td className="small mono">{r.driverPackageId}@{r.driverVersion}{r.firmware ? ` · fw ${r.firmware}` : ''}</td>
                  <td>{r.lockedOut ? <CodeBadge code="FAIL" label="Locked out" /> : <CodeBadge code={r.calibration?.verdict ?? 'UNKNOWN'} />}</td>
                  <td>{r.alerts.length ? <CodeBadge code="OPEN" label={plural(r.alerts.length, 'alert')} /> : <span className="muted">None</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <section className="card" aria-labelledby="fa-title">
        <h2 id="fa-title">Open alerts</h2>
        <ActionFeedback error={action.error} message={action.message} />
        {alerts.loading && !alerts.data && <Loading />}
        <ErrorAlert error={alerts.error} />
        {alerts.data && alerts.data.length === 0 && <Empty>No open alerts.</Empty>}
        {(alerts.data?.length ?? 0) > 0 && (
          <table>
            <caption className="visually-hidden">Open fleet alerts</caption>
            <thead><tr><th scope="col">Raised</th><th scope="col">Device</th><th scope="col">Alert</th><th scope="col">Level</th><th scope="col">Status</th><th scope="col">Ticket</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {alerts.data!.map((a) => (
                <tr key={a.alertId}>
                  <td className="small">{formatDate(a.raisedAt)}</td>
                  <td className="mono small">{a.deviceInstanceId} <span className="muted">{a.siteCode}</span></td>
                  <th scope="row">{humanise(a.type)}{a.detail ? <span className="small muted block">{a.detail}</span> : null}</th>
                  <td><CodeBadge code={a.level} /></td>
                  <td><CodeBadge code={a.status} /></td>
                  <td className="mono small">{a.ticketRef ?? '–'}</td>
                  <td className="actions">
                    {admin && a.status === 'OPEN' && <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void action.run(async () => { await api.acknowledgeAlert(a.alertId); alerts.reload(); return 'Alert acknowledged.'; })}>Acknowledge</button>}
                    {admin && !a.ticketRef && <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void action.run(async () => { const r = await api.ticketAlert(a.alertId); alerts.reload(); return `ITSM ticket ${r.ticketRef ?? ''} raised.`; })}>Raise ITSM ticket</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </section>
  );
}

/** `#/devices/fleet/{deviceInstanceId}` – one instance: health, versions, consumables, masked diagnostics, certificate. */
export function DeviceInstance(props: ScreenProps & { deviceInstanceId: string }) {
  const { client, session, onSessionExpired, deviceInstanceId } = props;
  const api = devicesApi(client);
  const fleet = useAsync(() => api.fleet(), onSessionExpired, [deviceInstanceId]);
  const row = fleet.data?.find((r) => r.deviceInstanceId === deviceInstanceId);
  const admin = can(session.roles, 'deviceAdmin');
  const diagnostics = can(session.roles, 'deviceDiagnostics');
  return (
    <section>
      <p className="breadcrumb"><a href="#/devices/fleet">Fleet</a> › {deviceInstanceId}</p>
      <PageTitle sub={row ? `${row.siteCode} / ${row.stationCode} · ${row.configurationId} v${row.configVersion}` : undefined}>{row?.model ?? deviceInstanceId}</PageTitle>
      {fleet.loading && !fleet.data && <Loading />}
      <ErrorAlert error={fleet.error} />
      {fleet.data && !row && <Alert kind="info">{deviceInstanceId} is not published (or was decommissioned).</Alert>}
      {row && (
        <>
          <div className="grid-2">
            <section className="card" aria-labelledby="in-health">
              <h2 id="in-health">Health</h2>
              <Dl items={[
                ['Status', <CodeBadge code={row.status} />],
                ['Health', <CodeBadge code={row.health.state} />],
                ['Last seen', formatDate(row.lastSeenAt)],
                ['Messages / errors (24 h)', row.health.messages24h == null ? '–' : `${row.health.messages24h} / ${row.health.errors24h ?? 0}`],
                ['p95 latency', row.health.p95LatencyMillis == null ? '–' : `${row.health.p95LatencyMillis} ms`],
                ['Unmatched / dead letters', row.health.unmatchedOpen == null ? '–' : `${row.health.unmatchedOpen} / ${row.health.deadLetters ?? 0}`],
                ['Endpoint', <>{row.endpointId ?? '–'} {row.endpointStatus && <CodeBadge code={row.endpointStatus} />}</>],
              ]} />
              {row.health.reason && <p className="small muted">{row.health.reason}</p>}
              <p><a href={`#/devices/wizard/${row.configurationId}`}>Open configuration {row.configurationId}</a> · <a href={certificatePdfUrl(row.configurationId)} download>Certificate (PDF)</a></p>
            </section>
            <VersionsCard {...props} instanceId={deviceInstanceId} firmware={row.firmware ?? ''} softwareVersion={row.softwareVersion ?? ''} driver={`${row.driverPackageId}@${row.driverVersion}`} editable={admin} onSaved={fleet.reload} />
          </div>
          <ConsumablesCard {...props} instanceId={deviceInstanceId} />
          {diagnostics && <DiagnosticsCard {...props} instanceId={deviceInstanceId} canResend={admin} />}
        </>
      )}
    </section>
  );
}

function VersionsCard({ client, onSessionExpired, instanceId, firmware, softwareVersion, driver, editable, onSaved }: ScreenProps & { instanceId: string; firmware: string; softwareVersion: string; driver: string; editable: boolean; onSaved: () => void }) {
  const api = devicesApi(client);
  const [f, setF] = useState({ firmware, softwareVersion });
  const action = useAction(onSessionExpired);
  return (
    <section className="card" aria-labelledby="in-ver">
      <h2 id="in-ver">Versions</h2>
      <p className="small">Pinned driver <span className="mono">{driver}</span>. Recording a version the driver does not support raises a DRIVER_INCOMPATIBLE alert.</p>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void action.run(async () => { const r = await api.putVersions(instanceId, f); onSaved(); return r.compatible ? 'Versions recorded – compatible with the pinned driver.' : 'Versions recorded – NOT compatible with the pinned driver; an alert was raised.'; }); }}>
        <fieldset disabled={!editable}>
          <legend className="visually-hidden">Firmware and software</legend>
          <Field label="Firmware">{(id) => <input id={id} maxLength={40} value={f.firmware} onChange={(e) => setF({ ...f, firmware: e.target.value })} />}</Field>
          <Field label="Vendor software version">{(id) => <input id={id} maxLength={40} value={f.softwareVersion} onChange={(e) => setF({ ...f, softwareVersion: e.target.value })} />}</Field>
        </fieldset>
        {editable && <div className="actions"><button type="submit" className="secondary" disabled={action.busy}>Record versions</button></div>}
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </section>
  );
}

function ConsumablesCard({ client, session, onSessionExpired, instanceId }: ScreenProps & { instanceId: string }) {
  const api = devicesApi(client);
  const allowed = can(session.roles, 'deviceAdmin') || can(session.roles, 'deviceCalibrationCheck');
  const list = useAsync(() => (allowed ? api.consumables(instanceId) : Promise.resolve([])), onSessionExpired, [instanceId]);
  const [f, setF] = useState({ item: '', lot: '', expiry: '', quantity: '1' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  if (!allowed) return null;
  async function add(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.item.trim()) errs.push({ field: 'cons-item', message: 'Enter the item' });
    if (!/^\d+$/.test(f.quantity) || Number(f.quantity) < 1) errs.push({ field: 'cons-qty', message: 'Quantity must be at least 1' });
    setErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await api.addConsumable(instanceId, { item: f.item.trim(), lot: f.lot.trim(), expiry: f.expiry, quantity: Number(f.quantity) }); return 'Consumable recorded.'; })) { setF({ item: '', lot: '', expiry: '', quantity: '1' }); list.reload(); }
  }
  return (
    <section className="card" aria-labelledby="in-cons">
      <h2 id="in-cons">Consumables</h2>
      {list.data && list.data.length === 0 && <Empty>No consumables recorded.</Empty>}
      {(list.data?.length ?? 0) > 0 && (
        <table>
          <caption className="visually-hidden">Consumables</caption>
          <thead><tr><th scope="col">Item</th><th scope="col">Lot</th><th scope="col">Expiry</th><th scope="col" className="num">Quantity</th></tr></thead>
          <tbody>{list.data!.map((c, i) => <tr key={i}><th scope="row">{c.item}</th><td className="mono small">{c.lot ?? '–'}</td><td>{formatDay(c.expiry)} {c.expired && <CodeBadge code="FAIL" label="Expired" />}</td><td className="num">{c.quantity}</td></tr>)}</tbody>
        </table>
      )}
      <form className="inline-form" onSubmit={add} noValidate aria-label="Add consumable">
        <ErrorSummary errors={errors} />
        <IdField id="cons-item" label="Item" required error={errorFor(errors, 'cons-item')}>{(d) => <input id="cons-item" aria-describedby={d} maxLength={80} value={f.item} onChange={(e) => setF({ ...f, item: e.target.value })} />}</IdField>
        <Field label="Lot">{(id) => <input id={id} maxLength={40} value={f.lot} onChange={(e) => setF({ ...f, lot: e.target.value })} />}</Field>
        <Field label="Expiry">{(id) => <input id={id} type="date" value={f.expiry} onChange={(e) => setF({ ...f, expiry: e.target.value })} />}</Field>
        <IdField id="cons-qty" label="Quantity" required error={errorFor(errors, 'cons-qty')}>{(d) => <input id="cons-qty" type="number" min={1} aria-describedby={d} value={f.quantity} onChange={(e) => setF({ ...f, quantity: e.target.value })} />}</IdField>
        <button type="submit" className="secondary" disabled={action.busy}>Add</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** Masked diagnostics trace (§13.10) – values and identifiers are replaced by «masked» server-side. */
export function DiagnosticsCard({ client, onSessionExpired, instanceId, canResend }: ScreenProps & { instanceId: string; canResend: boolean }) {
  const api = devicesApi(client);
  const [open, setOpen] = useState(false);
  const trace = useAsync(() => (open ? api.trace(instanceId, 50) : Promise.resolve(undefined)), onSessionExpired, [open, instanceId]);
  const action = useAction(onSessionExpired);
  return (
    <section className="card" aria-labelledby="in-diag">
      <h2 id="in-diag">Diagnostics (masked trace)</h2>
      <p className="small muted">Viewing the trace is audited. Person data and values are masked.</p>
      <div className="actions">
        <button type="button" className="secondary" onClick={() => setOpen(true)} disabled={open && trace.loading}>{open ? 'Refresh trace' : 'Load trace'}</button>
        {canResend && <button type="button" className="secondary" disabled={action.busy} onClick={() => void action.run(async () => { await api.resend(instanceId); return 'Last message re-sent through the pipeline.'; })}>Re-send last message</button>}
      </div>
      <UnavailableNotice error={trace.error ?? action.error} what="The integration engine diagnostics" />
      {!(trace.error && (trace.error as { status?: number }).status === 503) && <ErrorAlert error={trace.error} />}
      {!(action.error && (action.error as { status?: number }).status === 503) && <ActionFeedback error={action.error} message={action.message} />}
      {trace.data && (trace.data.entries.length === 0 ? <Empty>No messages in the trace.</Empty> : (
        <table>
          <caption>Latest {trace.data.entries.length} messages · driver {trace.data.driver}{trace.data.timeZone ? ` · ${trace.data.timeZone}` : ''}</caption>
          <thead><tr><th scope="col">Received</th><th scope="col">Stage</th><th scope="col">Outcome</th><th scope="col">Excerpt</th><th scope="col">Mapping</th><th scope="col" className="num">Clock offset</th></tr></thead>
          <tbody>
            {trace.data.entries.map((t, i) => (
              <tr key={t.messageId ?? i}>
                <td className="small">{formatDate(t.receivedAt)}</td>
                <th scope="row">{humanise(t.stage)}</th>
                <td><CodeBadge code={t.outcome} />{t.code ? <span className="mono small"> {t.code}</span> : null}</td>
                <td className="mono small prewrap">{t.maskedExcerpt ?? '–'}</td>
                <td className="small">{t.mappingOutcome ?? '–'}</td>
                <td className="num small">{t.clockOffsetMillis == null ? '–' : `${t.clockOffsetMillis} ms`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </section>
  );
}
