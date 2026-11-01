import { useState, type FormEvent } from 'react';
import { OBSERVATION_CATEGORIES, RESOLUTION_CODES, UNMATCHED_STATUSES, engineApi, type ResolutionCode, type UnmatchedItem } from '../../api/deviceEngine';
import { attachmentHref } from '../../api/deviceCapture';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { formatBytes, formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { displayValue } from '../../domain/capture';
import { FlagBadge } from '../capture/CapturePreview';
import type { ScreenProps } from '../types';
import { NotPermitted, ShortHash, StateBadge } from './common';
import { isForbidden } from './util';

/**
 * Unmatched queue (contract §9, E31-F05): readings below the auto-file threshold (0.95) with candidate suggestions, SLA one
 * working day. Resolution needs a reason code and re-runs validation; restricted readings show no suggestions to other users.
 */
export function UnmatchedQueue({ client, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const [site, setSite] = useState('');
  const [status, setStatus] = useState('OPEN');
  const list = useAsync(() => api.unmatched({ siteCode: site || undefined, status }), onSessionExpired, [site, status]);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Device readings that could not be linked to an employee with enough confidence. Nothing below 0.95 is filed automatically – confirm the employee or discard the reading.">Unmatched device readings</PageTitle>
      <div className="filters inline-form">
        <Field label="Site">{(id) => <input id={id} value={site} maxLength={40} placeholder="All my sites" onChange={(e) => setSite(e.target.value.toUpperCase())} />}</Field>
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={UNMATCHED_STATUSES} />
            </select>
          )}
        </Field>
      </div>
      {list.loading && !list.data && <Loading />}
      <NotPermitted error={list.error} what="the unmatched queue" />
      {!!list.error && !isForbidden(list.error) && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No unmatched readings.</Empty>}
      <ul className="card-list">
        {rows.map((u) => (
          <UnmatchedCard key={u.id} item={u} client={client} onSessionExpired={onSessionExpired} onDone={list.reload} open={status === 'OPEN'} />
        ))}
      </ul>
    </section>
  );
}

function UnmatchedCard({ item: u, client, onSessionExpired, onDone, open }: { item: UnmatchedItem; client: ScreenProps['client']; onSessionExpired: () => void; onDone: () => void; open: boolean }) {
  const api = engineApi(client);
  const action = useAction(onSessionExpired);
  const [employee, setEmployee] = useState<{ employeeId: string; displayName: string }>();
  const [reasonCode, setReasonCode] = useState<ResolutionCode>('CONFIRMED_IDENTITY');
  const [note, setNote] = useState('');
  const [loadedAt] = useState(() => Date.now());
  const overdue = !!u.slaDueAt && new Date(u.slaDueAt).getTime() < loadedAt;

  async function resolve(e: FormEvent) {
    e.preventDefault();
    if (!employee) return;
    if (await action.run(async () => {
      const r = await api.resolve(u.id, { employeeId: employee.employeeId, reasonCode, ...(note.trim() ? { note: note.trim() } : {}) });
      return `Linked to ${employee.displayName}; reading re-validated (${humanise(r.observationStatus ?? r.status)}).`;
    })) onDone();
  }
  async function discard() {
    if (await action.run(async () => {
      await api.resolve(u.id, { reasonCode, discard: true, ...(note.trim() ? { note: note.trim() } : {}) });
      return 'Reading discarded.';
    })) onDone();
  }

  return (
    <li className="card">
      <div className="card-row">
        <strong>{humanise(u.category)}</strong> {u.restricted && <span className="badge badge-warning">Restricted</span>} {overdue && <span className="badge badge-overdue">SLA overdue</span>}
        <span className="small muted"> · received {formatDate(u.receivedAt)} ({u.ageHours} h) · device <span className="mono">{u.deviceInstanceId ?? '–'}</span>{u.reasonCode && <> · {humanise(u.reasonCode)}</>}</span>
      </div>
      {u.restricted && u.suggestions.length === 0 && <p className="small">Restricted reading – suggestions are shown to the MRO only.</p>}
      {u.suggestions.length > 0 && (
        <div>
          <p className="small">Suggested employees:</p>
          <ul className="plain-list">
            {u.suggestions.map((s) => (
              <li key={s.employeeId}>
                <label className="checkbox">
                  <input type="radio" name={`sugg-${u.id}`} checked={employee?.employeeId === s.employeeId} onChange={() => setEmployee({ employeeId: s.employeeId, displayName: s.displayName })} />
                  {s.displayName} <span className="mono small">{s.employeeId}</span> <span className="small muted">{humanise(s.method)} · confidence {Number(s.confidence).toFixed(2)}</span>
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}
      {open && (
        <>
          <details>
            <summary className="small">Search for another employee</summary>
            <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={(e: EmployeeRef) => setEmployee({ employeeId: e.employeeId, displayName: e.displayName })} label="Find employee" />
          </details>
          <form className="inline-form" onSubmit={resolve}>
            <Field label="Reason" required>
              {(id) => (
                <select id={id} value={reasonCode} onChange={(e) => setReasonCode(e.target.value as ResolutionCode)}>
                  <SelectOptions values={RESOLUTION_CODES} />
                </select>
              )}
            </Field>
            <Field label="Note (optional)">{(id) => <input id={id} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
            <button type="submit" disabled={action.busy || !employee}>{employee ? `Link to ${employee.displayName}` : 'Choose an employee'}</button>
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void discard()}>Discard reading</button>
          </form>
        </>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </li>
  );
}

/**
 * Observation browser (contract §12): device observations of one employee with status, capture method and flags (person-level
 * read, ABAC + audited). Restricted categories show "restricted reading" without values to users other than MRO / physician.
 * DEVICE_ADMIN / AUDITOR get the archive integrity check only (no person data).
 */
export function ObservationsBrowser({ client, session, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const clinical = can(session.roles, 'deviceObservations');
  const [employee, setEmployee] = useState<{ employeeId: string; displayName: string }>();
  const [category, setCategory] = useState('');
  const list = useAsync(() => (employee ? api.observations({ employeeId: employee.employeeId, category: category || undefined }) : Promise.resolve(undefined)), onSessionExpired, [employee?.employeeId, category]);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Device observations with provenance (device, driver, calibration, association) and the raw-payload hash. Values are immutable – a wrong value is rejected and re-measured.">Observations and provenance</PageTitle>
      {clinical && (
        <>
          <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={(e) => setEmployee({ employeeId: e.employeeId, displayName: e.displayName })} />
          {employee && (
            <div className="filters inline-form">
              <p>
                <strong>{employee.displayName}</strong> <span className="mono small">{employee.employeeId}</span>
              </p>
              <Field label="Category">
                {(id) => (
                  <select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
                    <SelectOptions values={OBSERVATION_CATEGORIES} empty="All" />
                  </select>
                )}
              </Field>
            </div>
          )}
          {list.loading && employee && <Loading />}
          {!!list.error && <ErrorAlert error={list.error} />}
          {list.data && rows.length === 0 && <Empty>No device observations for this employee.</Empty>}
          {rows.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Device observations</caption>
                <thead>
                  <tr>
                    <th scope="col">When</th>
                    <th scope="col">Category</th>
                    <th scope="col">Status</th>
                    <th scope="col">Device</th>
                    <th scope="col">Capture method</th>
                    <th scope="col">Flags</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((o) => (
                    <tr key={o.observationId}>
                      <th scope="row"><a href={`#/devices/observations/${encodeURIComponent(o.observationId)}`}>{formatDate(o.effectiveAt)}</a></th>
                      <td>{humanise(o.category)} {o.restricted && <span className="badge badge-warning">Restricted</span>}</td>
                      <td><StateBadge code={o.status} /></td>
                      <td>{o.model ?? '–'} <span className="mono small">{o.deviceInstanceId ?? ''}</span></td>
                      <td>{humanise(o.captureMethod)}</td>
                      <td className="small">{o.restrictedNote ?? (o.flags.length ? o.flags.join(', ') : '–')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      {can(session.roles, 'deviceArchiveVerify') && <ArchiveCheck client={client} onSessionExpired={onSessionExpired} />}
    </section>
  );
}

/** Raw-payload / attachment integrity: recompute SHA-256 of the archived object and compare with the stored hash (no content). */
function ArchiveCheck({ client, onSessionExpired, initial = '' }: { client: ScreenProps['client']; onSessionExpired: () => void; initial?: string }) {
  const action = useAction(onSessionExpired);
  const [archiveId, setArchiveId] = useState(initial);
  const [result, setResult] = useState<{ intact: boolean; sha256Stored: string; sha256Computed: string }>();
  async function check(e: FormEvent) {
    e.preventDefault();
    if (!/^[0-9a-f-]{36}$/i.test(archiveId.trim())) return;
    setResult(undefined);
    await action.run(async () => {
      setResult(await engineApi(client).verifyArchive(archiveId.trim()));
    });
  }
  return (
    <div className="tile">
      <h2>Archive integrity check</h2>
      <form className="inline-form" onSubmit={check}>
        <Field label="Archive id" hint="UUID of the raw payload or attachment">{(id) => <input id={id} className="mono" maxLength={36} value={archiveId} onChange={(e) => setArchiveId(e.target.value)} />}</Field>
        <button type="submit" disabled={action.busy}>Verify hash</button>
      </form>
      <ActionFeedback error={action.error} />
      {result && (
        <Alert kind={result.intact ? 'success' : 'error'}>
          {result.intact ? 'Intact – the stored and recomputed SHA-256 match.' : 'Mismatch – the archived object does not match its stored hash.'}
          <span className="small mono break"> stored {result.sha256Stored} · computed {result.sha256Computed}</span>
        </Alert>
      )}
    </div>
  );
}

/** One observation: values (or "restricted reading"), quality flags, provenance incl. raw-payload hash, attachments, FHIR. */
export function ObservationDetail({ client, session, onSessionExpired, observationId }: ScreenProps & { observationId: string }) {
  const api = engineApi(client);
  const [fhir, setFhir] = useState(false);
  const obs = useAsync(() => api.observation(observationId, fhir), onSessionExpired, [observationId, fhir]);
  const o = obs.data;
  const p = o?.provenance;
  return (
    <section>
      <p className="breadcrumb"><a href="#/devices/observations">Observations</a> › <span className="mono">{observationId.slice(0, 8)}</span></p>
      <PageTitle>Device observation</PageTitle>
      {obs.loading && !o && <Loading />}
      {!!obs.error && <ErrorAlert error={obs.error} />}
      {o && (
        <>
          <dl className="definitions">
            <div><dt>Category</dt><dd>{humanise(o.category)}</dd></div>
            <div><dt>Status</dt><dd><StateBadge code={o.status} /></dd></div>
            <div><dt>Measured</dt><dd>{formatDate(o.effectiveAt)}</dd></div>
            <div><dt>Capture method</dt><dd>{humanise(o.captureMethod)}</dd></div>
            <div><dt>Device instance</dt><dd className="mono">{o.deviceInstanceId ?? '–'}</dd></div>
            <div><dt>Form</dt><dd className="mono">{o.formCode ?? '–'}</dd></div>
            <div><dt>Filed as</dt><dd>{o.routedRecordType ? `${humanise(o.routedRecordType)} ${o.routedRecordId ?? ''}` : '–'}</dd></div>
          </dl>
          {o.components === null || o.components === undefined ? (
            <div className="restricted-reading" role="status"><strong>Restricted reading</strong><p className="small">{o.restrictedNote ?? 'Values are visible to the MRO only.'}</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <caption>Values</caption>
                <thead><tr><th scope="col">Measurement</th><th scope="col">Value</th><th scope="col">LOINC</th><th scope="col">MDC</th></tr></thead>
                <tbody>
                  {o.components.map((c, i) => (
                    <tr key={`${c.key}-${i}`}>
                      <th scope="row">{c.display ?? humanise(c.key)}</th>
                      <td className="num">{displayValue({ value: c.value, text: c.text, unit: c.ucum })}</td>
                      <td className="mono small">{c.loincCode ?? '–'}</td>
                      <td className="mono small">{c.mdcCode ?? '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {o.flags.length > 0 && (
            <>
              <h2>Quality flags</h2>
              <ul className="plain-list">
                {o.flags.map((f, i) => (
                  <li key={`${f.code}-${i}`}><FlagBadge outcome={f.outcome} /> {humanise(f.gate)} – {f.message ?? humanise(f.code)}</li>
                ))}
              </ul>
            </>
          )}
          {p && (
            <>
              <h2>Provenance</h2>
              <dl className="definitions">
                <div><dt>Device</dt><dd>{[p.manufacturer, p.model].filter(Boolean).join(' ') || '–'} {p.modelCode && <span className="mono small">{p.modelCode}</span>}</dd></div>
                <div><dt>Serial · UDI</dt><dd>{p.serial ?? '–'} · {p.udi ?? '–'}</dd></div>
                <div><dt>Firmware · software</dt><dd>{p.firmware ?? '–'} · {p.softwareVersion ?? '–'}</dd></div>
                <div><dt>Driver</dt><dd className="mono">{p.driverPackageId ? `${p.driverPackageId}@${p.driverVersion}` : '–'}</dd></div>
                <div><dt>Calibration</dt><dd>{p.calibrationId ?? '–'} {p.calibrationDate ?? ''} {p.calibrationVerdict ? `(${humanise(p.calibrationVerdict)})` : ''}</dd></div>
                <div><dt>Operator · verified by</dt><dd>{p.operator ?? '–'} · {p.verifiedBy ? `${p.verifiedBy} ${formatDate(p.verifiedAt)}` : 'not yet verified'}</dd></div>
                <div><dt>Association</dt><dd>{humanise(p.associationMethod)}{p.associationConfidence !== null && p.associationConfidence !== undefined ? ` · confidence ${Number(p.associationConfidence).toFixed(2)}` : ''}</dd></div>
                <div><dt>Clock offset</dt><dd>{p.clockSkewMillis ? `${Math.round(p.clockSkewMillis / 1000)} s (corrected)` : 'none'}</dd></div>
                <div><dt>Raw payload SHA-256</dt><dd className="mono break">{p.rawPayloadSha256 ?? '–'}</dd></div>
              </dl>
            </>
          )}
          {o.attachments.length > 0 && (
            <>
              <h2>Attachments</h2>
              <ul className="plain-list">
                {o.attachments.map((a) => (
                  <li key={a.archiveId}>
                    <a href={attachmentHref(`/device-engine/observations/${encodeURIComponent(o.observationId)}/attachments/${encodeURIComponent(a.archiveId)}`)} target="_blank" rel="noopener">{humanise(a.kind)}</a>{' '}
                    <span className="small muted">{a.mediaType} · {formatBytes(a.sizeBytes)} · SHA-256 <ShortHash value={a.sha256} /></span>
                  </li>
                ))}
              </ul>
            </>
          )}
          {!o.restricted && (
            <details onToggle={(e) => setFhir((e.target as HTMLDetailsElement).open)}>
              <summary>FHIR resources (Observation, DiagnosticReport, Device, Provenance)</summary>
              {fhir && (o.fhir ?? []).map((r) => (
                <div key={`${r.resourceType}-${r.resourceId}`}>
                  <h3 className="small">{r.resourceType} <span className="mono">{r.resourceId}</span> v{r.version}</h3>
                  <pre className="snippet">{prettyJson(r.json)}</pre>
                </div>
              ))}
            </details>
          )}
        </>
      )}
      {can(session.roles, 'deviceArchiveVerify') && <ArchiveCheck key={o?.observationId ?? 'none'} client={client} onSessionExpired={onSessionExpired} initial={o?.attachments[0]?.archiveId ?? ''} />}
    </section>
  );
}

function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
