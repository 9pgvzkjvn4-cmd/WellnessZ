import './capture.css';
import { useEffect, useState, useSyncExternalStore, type FormEvent } from 'react';
import { captureApi, type BatchView, type CaptureSession, type QueueEntry } from '../../api/deviceCapture';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { formatDate, humanise, isoDay } from '../../components/format';
import { KpiTile } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { navigate } from '../../routing/useHashRoute';
import { acceptState, blockFlags, pathUsed, previewFlags, warnFlags } from '../../domain/capture';
import type { ScreenProps } from '../types';
import { ComparisonBlock, MarkingTag, PreviewValues, ProvenanceDetails, QualityFlags } from './CapturePreview';
import { StationPicker } from './StationPicker';
import { useLiveSession } from './useLiveSession';
import { forgetPendingSignOff, usePendingSignOffs, useStation } from './workstation';

// ---------------------------------------------------------------- connectivity (batch mode works offline on the tablet)
const subscribeOnline = (cb: () => void) => {
  window.addEventListener('online', cb);
  window.addEventListener('offline', cb);
  return () => {
    window.removeEventListener('online', cb);
    window.removeEventListener('offline', cb);
  };
};
function useOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, () => navigator.onLine, () => true);
}

const BATCH_FORMS = ['E12.SCREENING_RESULT', 'E03.DEVICE_RESULT.VITALS', 'E03.DEVICE_RESULT.BODY_COMPOSITION', 'E03.DEVICE_RESULT.POC', 'E03.DEVICE_RESULT.AUDIOMETRY', 'E03.DEVICE_RESULT.SPIROMETRY', 'E03.DEVICE_RESULT.VISION'];

/**
 * Batch mode for screening days (contract §14.5, E33-F10): queue of employees, one tap to accept and move on or to skip, accept
 * all clean readings (no WARN / BLOCK flag) at once, offline indicator. Readings taken offline on the tablet are reconciled by
 * the gateway on reconnection; the queue here refreshes when the connection returns.
 */
export function BatchMode({ client, session, onSessionExpired, batchId }: ScreenProps & { batchId?: string }) {
  return batchId ? <BatchRun client={client} session={session} onSessionExpired={onSessionExpired} batchId={batchId} /> : <BatchSetup client={client} session={session} onSessionExpired={onSessionExpired} />;
}

function BatchSetup({ client, onSessionExpired }: ScreenProps) {
  const api = captureApi(client);
  const station = useStation();
  const online = useOnline();
  const [formCode, setFormCode] = useState(BATCH_FORMS[0]!);
  const [eventId, setEventId] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [people, setPeople] = useState<EmployeeRef[]>([]);
  const [badges, setBadges] = useState('');
  const [error, setError] = useState<string>();
  const devices = useAsync(() => (station ? api.devices(formCode, station.siteCode, station.stationCode) : Promise.resolve([])), onSessionExpired, [formCode, station?.siteCode, station?.stationCode]);
  const action = useAction(onSessionExpired);

  async function create() {
    const badgeIds = badges.split(/[\s,;]+/).map((b) => b.trim()).filter(Boolean);
    if (!station) return setError('Choose the capture station first.');
    if (picked.length === 0) return setError('Select at least one device.');
    if (people.length === 0 && badgeIds.length === 0) return setError('Add employees or scan badge ids.');
    if (people.length > 0 && badgeIds.length > 0) return setError('Use either the employee list or badge ids, not both.');
    if (people.length + badgeIds.length > 300) return setError('A batch holds up to 300 employees.');
    setError(undefined);
    await action.run(async () => {
      const b = await api.createBatch({
        siteCode: station.siteCode, formCode, deviceInstanceIds: picked, ...(eventId.trim() ? { screeningEventId: eventId.trim() } : {}),
        ...(people.length ? { employeeIds: people.map((p) => p.employeeId) } : { badgeIds }),
      });
      navigate(`/devices/batch/${b.batchId}`);
    });
  }

  return (
    <section>
      <PageTitle sub="Prepare a queue for a screening day. Each employee gets a capture session; take the readings one after another and accept clean readings in one go.">Screening batch mode</PageTitle>
      {!online && <OfflineBanner />}
      <StationPicker client={client} onSessionExpired={onSessionExpired} idPrefix="batch-station" />
      <div className="form">
        <div className="form-grid">
          <Field label="Measurement form">
            {(id) => (
              <select id={id} value={formCode} onChange={(e) => { setFormCode(e.target.value); setPicked([]); }}>
                {BATCH_FORMS.map((f) => <option key={f} value={f}>{f.startsWith('E12') ? 'Screening result (health promotion)' : humanise(f.split('.').pop())}</option>)}
              </select>
            )}
          </Field>
          <Field label="Screening event id (optional)" hint="Links the results to the E12 screening event">
            {(id) => <input id={id} maxLength={80} value={eventId} onChange={(e) => setEventId(e.target.value)} />}
          </Field>
        </div>
        <fieldset>
          <legend>Devices</legend>
          {!station && <p className="small muted">Choose the station to see its devices.</p>}
          {devices.loading && station && <Loading />}
          {!!devices.error && <ErrorAlert error={devices.error} />}
          {devices.data && devices.data.length === 0 && station && <Empty>No device for this form at the station.</Empty>}
          {(devices.data ?? []).map((d) => (
            <label key={d.deviceInstanceId} className="checkbox">
              <input type="checkbox" disabled={d.status === 'LOCKED_OUT'} checked={picked.includes(d.deviceInstanceId)} onChange={(e) => setPicked(e.target.checked ? [...picked, d.deviceInstanceId] : picked.filter((x) => x !== d.deviceInstanceId))} />
              {d.displayName} <span className="small muted">{humanise(d.status)}{d.lockReason ? ` – ${d.lockReason}` : ''}</span>
            </label>
          ))}
        </fieldset>
        <fieldset>
          {/* the employee search is its own form: this block deliberately is not a <form> */}
          <legend>Employees ({people.length})</legend>
          <EmployeeSearch client={client} onSessionExpired={onSessionExpired} label="Add employee" onPick={(p) => setPeople(people.some((x) => x.employeeId === p.employeeId) ? people : [...people, p])} />
          {people.length > 0 && (
            <ol className="plain-list">
              {people.map((p) => (
                <li key={p.employeeId}>
                  {p.displayName} <span className="mono small">{p.employeeId}</span>{' '}
                  <button type="button" className="link-button" onClick={() => setPeople(people.filter((x) => x.employeeId !== p.employeeId))} aria-label={`Remove ${p.displayName}`}>Remove</button>
                </li>
              ))}
            </ol>
          )}
          <Field label="Or badge ids (scan one per line)">{(id) => <textarea id={id} rows={3} value={badges} onChange={(e) => setBadges(e.target.value)} />}</Field>
        </fieldset>
        {error && <p className="field-error-text" role="alert">{error}</p>}
        <button type="button" disabled={action.busy || !online} onClick={() => void create()}>Create queue</button>
      </div>
      <ActionFeedback error={action.error} />
    </section>
  );
}

function OfflineBanner() {
  return (
    <div className="offline-banner" role="status">
      <strong>Offline.</strong> Readings taken now stay on the tablet and are reconciled automatically when the connection returns. Accepting and skipping need a connection.
    </div>
  );
}

function BatchRun({ client, onSessionExpired, batchId }: ScreenProps & { batchId: string }) {
  const api = captureApi(client);
  const online = useOnline();
  const batch = useAsync(() => api.batch(batchId), onSessionExpired, [batchId, online]);
  const action = useAction(onSessionExpired);
  const [view, setView] = useState<BatchView>();
  const [focus, setFocus] = useState<string>();
  const b = view ?? batch.data;

  // keep the queue fresh while the batch is open (readings arrive from the devices)
  const reload = batch.reload;
  useEffect(() => {
    if (!online || b?.status !== 'OPEN') return;
    const t = setInterval(() => reload(), 5000);
    return () => clearInterval(t);
  }, [online, b?.status, reload]);

  const entries = b?.sessions ?? [];
  const current = entries.find((e) => e.current);
  const selected = entries.find((e) => e.sessionId === focus) ?? entries.find((e) => e.state === 'PREVIEW') ?? current;
  const clean = entries.filter((e) => e.state === 'PREVIEW');
  const done = entries.filter((e) => ['ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED'].includes(e.state)).length;

  async function next() {
    await action.run(async () => {
      const v = await api.next(batchId);
      setView(v);
      setFocus(undefined);
      return 'Moved to the next employee.';
    });
  }
  async function acceptAll() {
    await action.run(async () => {
      const r = await api.acceptAll(batchId, clean.map((e) => e.sessionId));
      setView(await api.batch(batchId));
      return `${r.accepted.length} clean reading(s) accepted${r.skipped.length ? `; ${r.skipped.length} need individual review (${[...new Set(r.skipped.map((s) => humanise(s.reason)))].join(', ')})` : ''}.`;
    });
  }

  return (
    <section className="batch-run">
      <p className="breadcrumb"><a href="#/devices/batch">Batch mode</a> › queue</p>
      <PageTitle sub={b ? `${humanise(b.formCode.split('.').pop())} · ${b.siteCode} · ${done}/${entries.length} done · ${humanise(b.status)}` : undefined}>Screening queue</PageTitle>
      <p className={`connection ${online ? 'online' : 'offline'}`} role="status">
        <span aria-hidden="true">{online ? '●' : '○'}</span> {online ? 'Online' : 'Offline'}
      </p>
      {!online && <OfflineBanner />}
      {batch.loading && !b && <Loading />}
      {!!batch.error && !b && <ErrorAlert error={batch.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {b && (
        <div className="batch-layout">
          <ol className="batch-queue" aria-label="Queue">
            {entries.map((e) => (
              <li key={e.queueEntryId} className={`queue-entry${e.current ? ' is-current' : ''}${selected?.sessionId === e.sessionId ? ' is-selected' : ''}`}>
                <button type="button" className="queue-button" aria-current={selected?.sessionId === e.sessionId ? 'true' : undefined} onClick={() => setFocus(e.sessionId)}>
                  <span className="queue-pos">{e.position}</span>
                  <span className="queue-name">{e.displayName}</span>
                  <span className={`badge state-badge state-${e.state.toLowerCase().replace(/_/g, '-')}`}>{e.current ? 'Now' : humanise(e.state)}</span>
                  {e.readings > 0 && <span className="small muted"> {e.readings} reading(s)</span>}
                </button>
              </li>
            ))}
          </ol>
          <div className="batch-detail">
            <div className="actions batch-bar">
              <button type="button" className="big-button" disabled={!online || action.busy || b.status !== 'OPEN' || !current} onClick={() => void next()}>
                Skip to next
              </button>
              <button type="button" className="big-button secondary" disabled={!online || action.busy || clean.length === 0} onClick={() => void acceptAll()}>
                Accept all clean readings ({clean.length})
              </button>
            </div>
            {selected ? (
              <BatchEntry key={selected.sessionId} client={client} onSessionExpired={onSessionExpired} entry={selected} online={online} onChanged={() => { setView(undefined); batch.reload(); }} />
            ) : (
              <Empty>{b.status === 'OPEN' ? 'Waiting for the next reading.' : 'The batch is complete.'}</Empty>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function BatchEntry({ client, onSessionExpired, entry, online, onChanged }: { client: ScreenProps['client']; onSessionExpired: () => void; entry: QueueEntry; online: boolean; onChanged: () => void }) {
  const api = captureApi(client);
  const live = useLiveSession(client, online ? entry.sessionId : undefined, onSessionExpired, { eventSource: null, pollMs: 3000 });
  const action = useAction(onSessionExpired);
  const [acked, setAcked] = useState<Set<string>>(new Set());
  const s = live.session;
  if (!online) return <p className="muted">{entry.displayName}: details are shown again when the connection returns.</p>;
  if (!s) return live.error ? <ErrorAlert error={live.error} /> : <Loading />;
  const flags = previewFlags(s);
  const acc = acceptState(s, acked);

  async function accept() {
    if (await action.run(async () => {
      await api.accept(s!.sessionId, [...acked]);
      return `${s!.employeeName ?? entry.displayName}: accepted.`;
    })) onChanged();
  }
  async function retest() {
    if (await action.run(async () => {
      live.set(await api.reject(s!.sessionId, 'RETEST'));
      return 'Re-test – take the measurement again.';
    })) onChanged();
  }

  return (
    <div className="tile batch-entry">
      <h2>{s.employeeName ?? entry.displayName}</h2>
      <p className="small muted">{humanise(s.state)} · {s.deviceName ?? s.deviceInstanceId ?? '–'} · {pathUsed(s)}</p>
      {s.state === 'LISTENING' && <p className="capture-hint"><span className="pulse" aria-hidden="true" /> Waiting for the reading…</p>}
      {s.restrictedReading && <div className="restricted-reading"><strong>Restricted reading</strong></div>}
      {s.preview && !s.restrictedReading && (
        <>
          <MarkingTag s={s} />
          <PreviewValues preview={s.preview} />
          <QualityFlags flags={flags} acknowledged={acked} idPrefix={`b-${s.sessionId.slice(0, 8)}`} readOnly={s.state !== 'PREVIEW'} onToggle={(k, on) => { const n = new Set(acked); if (on) n.add(k); else n.delete(k); setAcked(n); }} />
          {warnFlags(flags).length + blockFlags(flags).length > 0 && s.state === 'PREVIEW' && <p className="small">This reading is excluded from "accept all" – review it here.</p>}
        </>
      )}
      {s.state === 'PREVIEW' && !s.restrictedReading && (
        <div className="actions">
          <button type="button" className="big-button" disabled={!acc.enabled || action.busy} onClick={() => void accept()}>Accept</button>
          <button type="button" className="big-button secondary" disabled={action.busy} onClick={() => void retest()}>Re-test</button>
        </div>
      )}
      {acc.reason && s.state === 'PREVIEW' && <p className="small muted">{acc.reason}</p>}
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}

// ---------------------------------------------------------------- ECG / ABPM physician sign-off (§14.4 E33-F06)

/**
 * Physician sign-off of device interpretations (ECG 12-lead, ABPM / Holter): the device interpretation stays "unverified" until
 * a physician signs it off; sign-off acknowledges the E03 result and releases the summary where the form allows it.
 */
export function SignOffScreen({ client, session, onSessionExpired, sessionId }: ScreenProps & { sessionId?: string }) {
  const pending = usePendingSignOffs();
  const [lookup, setLookup] = useState('');
  if (sessionId) return <SignOffDetail client={client} session={session} onSessionExpired={onSessionExpired} sessionId={sessionId} />;
  return (
    <section>
      <PageTitle sub="Device interpretations of ECGs and ambulatory blood pressure / Holter recordings are unverified until a physician signs them off.">ECG and ABPM sign-off</PageTitle>
      <h2>Captured at this workstation</h2>
      {pending.length === 0 ? (
        <Empty>No captures awaiting sign-off in this session. Open a capture by its reference below.</Empty>
      ) : (
        <ul className="card-list">
          {pending.map((p) => (
            <li key={p.sessionId} className="card">
              <a href={`#/devices/sign-off/${encodeURIComponent(p.sessionId)}`}>{humanise(p.category)} – {p.employeeName ?? p.employeeId}</a>
              <div className="small muted">accepted {formatDate(p.acceptedAt)} · reference <span className="mono">{p.sessionId.slice(0, 8)}</span></div>
            </li>
          ))}
        </ul>
      )}
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); if (/^[0-9a-f-]{36}$/i.test(lookup.trim())) navigate(`/devices/sign-off/${lookup.trim()}`); }}>
        <Field label="Capture reference" hint="The session id shown on the capture">{(id) => <input id={id} className="mono" maxLength={36} value={lookup} onChange={(e) => setLookup(e.target.value)} />}</Field>
        <button type="submit">Open</button>
      </form>
    </section>
  );
}

function SignOffDetail({ client, session, onSessionExpired, sessionId }: ScreenProps & { sessionId: string }) {
  const api = captureApi(client);
  const s = useAsync(() => api.session(sessionId), onSessionExpired, [sessionId]);
  const action = useAction(onSessionExpired);
  const [note, setNote] = useState('');
  const [signed, setSigned] = useState<CaptureSession>();
  const v = signed ?? s.data;
  const physician = can(session.roles, 'deviceSignOff');

  async function signOff(e: FormEvent) {
    e.preventDefault();
    await action.run(async () => {
      const r = await api.signOff(sessionId, note.trim() || undefined);
      setSigned(r);
      forgetPendingSignOff(sessionId);
      return 'Interpretation signed off; the result is acknowledged.';
    });
  }

  return (
    <section>
      <p className="breadcrumb"><a href="#/devices/sign-off">Sign-off</a> › <span className="mono">{sessionId.slice(0, 8)}</span></p>
      <PageTitle>{v ? `${humanise(v.category)} – ${v.employeeName ?? v.employeeId}` : 'Device interpretation'}</PageTitle>
      {s.loading && !v && <Loading />}
      {!!s.error && <ErrorAlert error={s.error} />}
      {v && (
        <>
          <p>
            <span className={`badge ${v.interpretationStatus === 'SIGNED' ? 'badge-signed' : 'badge-awaiting-signoff'}`}>{v.interpretationStatus === 'SIGNED' ? 'Signed off' : v.interpretationStatus === 'UNVERIFIED' ? 'Interpretation unverified' : humanise(v.interpretationStatus ?? v.state)}</span>
            {v.signedBy && <span className="small muted"> by {v.signedBy} {formatDate(v.signedAt)}</span>}
          </p>
          {v.restrictedReading ? (
            <div className="restricted-reading"><strong>Restricted reading</strong></div>
          ) : v.preview ? (
            <>
              <MarkingTag s={v} />
              <PreviewValues preview={v.preview} />
              <QualityFlags flags={previewFlags(v)} acknowledged={new Set()} onToggle={() => undefined} idPrefix="so" readOnly />
              <ComparisonBlock preview={v.preview} />
              <ProvenanceDetails p={v.preview.provenance} marking={v.preview.marking} />
            </>
          ) : (
            <Empty>No reading in this capture.</Empty>
          )}
          {v.annotations.length > 0 && (
            <ul className="plain-list small">
              {v.annotations.map((a) => <li key={a.id}>{a.author} {formatDate(a.createdAt)}: {a.text}</li>)}
            </ul>
          )}
          {v.state !== 'ACCEPTED' && <Alert kind="info">The capture is {humanise(v.state).toLowerCase()} – only an accepted capture can be signed off.</Alert>}
          {physician && v.state === 'ACCEPTED' && v.interpretationStatus === 'UNVERIFIED' && (
            <form className="form" onSubmit={signOff}>
              <Field label="Physician comment (optional)">{(id) => <textarea id={id} rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
              <button type="submit" disabled={action.busy}>Sign off the interpretation</button>
            </form>
          )}
          {!physician && v.interpretationStatus === 'UNVERIFIED' && <Alert kind="info">A physician signs off this interpretation.</Alert>}
        </>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

// ---------------------------------------------------------------- capture KPIs

/** Capture KPIs (E33): device usage, minutes saved, re-test and unmatched rates, paths and outcomes for a site and period. */
export function CaptureKpis({ client, onSessionExpired }: ScreenProps) {
  const today = new Date();
  const [q, setQ] = useState({ siteCode: '', from: isoDay(new Date(today.getTime() - 29 * 86400000)), to: isoDay(today) });
  const [applied, setApplied] = useState(q);
  const kpi = useAsync(() => captureApi(client).kpis({ siteCode: applied.siteCode || undefined, from: applied.from, to: applied.to }), onSessionExpired, [applied.siteCode, applied.from, applied.to]);
  const k = kpi.data?.kpis;
  const t = kpi.data?.targets ?? {};
  const status = (v: number | null | undefined, target: number | undefined, higherIsBetter: boolean): 'MET' | 'MISSED' | 'UNKNOWN' =>
    v === null || v === undefined || target === undefined ? 'UNKNOWN' : (higherIsBetter ? v >= target : v <= target) ? 'MET' : 'MISSED';
  const fmt = (v: number | null | undefined, suffix = '') => (v === null || v === undefined ? '–' : `${Number(v).toLocaleString('en-GB', { maximumFractionDigits: 1 })}${suffix}`);
  const targetKey = (part: string) => Object.keys(t).find((x) => x.toLowerCase().includes(part));
  const usageT = targetKey('usage');
  const retestT = targetKey('retest');
  const unmatchedT = targetKey('unmatched');
  return (
    <section>
      <PageTitle sub="How often measurements are captured from devices rather than typed, the time saved, and the quality of capture.">Device capture KPIs</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(q); }}>
        <Field label="Site">{(id) => <input id={id} maxLength={40} value={q.siteCode} placeholder="All sites" onChange={(e) => setQ({ ...q, siteCode: e.target.value.toUpperCase() })} />}</Field>
        <Field label="From">{(id) => <input id={id} type="date" value={q.from} onChange={(e) => setQ({ ...q, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={q.to} onChange={(e) => setQ({ ...q, to: e.target.value })} />}</Field>
        <button type="submit">Apply</button>
      </form>
      {kpi.loading && !k && <Loading />}
      {!!kpi.error && <ErrorAlert error={kpi.error} />}
      {k && (
        <>
          <div className="tiles">
            <KpiTile label="Capture sessions" value={k.sessions} />
            <KpiTile label="Accepted" value={k.accepted} />
            <KpiTile label="Captured from a device" value={fmt(k.usagePct, ' %')} target={usageT ? `Target ≥ ${t[usageT]} %` : undefined} status={status(k.usagePct, usageT ? t[usageT] : undefined, true)} />
            <KpiTile label="Minutes saved" value={fmt(k.minutesSaved)} target={k.minutesSavedPerDeviceTest !== null && k.minutesSavedPerDeviceTest !== undefined ? `${fmt(k.minutesSavedPerDeviceTest)} min per device test` : undefined} />
            <KpiTile label="Re-test rate" value={fmt(k.retestRatePct, ' %')} target={retestT ? `Target ≤ ${t[retestT]} %` : undefined} status={status(k.retestRatePct, retestT ? t[retestT] : undefined, false)} />
            <KpiTile label="Unmatched readings" value={fmt(k.unmatchedRatePct, ' %')} target={unmatchedT ? `Target < ${t[unmatchedT]} %` : 'Target < 0.5 %'} status={status(k.unmatchedRatePct, unmatchedT ? t[unmatchedT] : 0.5, false)} />
          </div>
          <div className="grid-2">
            <div className="tile">
              <h2>Capture path used</h2>
              <ul className="plain-list">
                {Object.entries(k.byPath).map(([p, n]) => <li key={p}>{p === 'DEVICE' ? 'Device' : humanise(p)}: <strong>{n}</strong></li>)}
                {Object.keys(k.byPath).length === 0 && <li className="muted">No accepted captures.</li>}
              </ul>
            </div>
            <div className="tile">
              <h2>Session outcomes</h2>
              <ul className="plain-list">
                {Object.entries(k.byState).map(([p, n]) => <li key={p}>{humanise(p)}: <strong>{n}</strong></li>)}
                {Object.keys(k.byState).length === 0 && <li className="muted">No sessions in the period.</li>}
              </ul>
            </div>
          </div>
          <p className="small muted">{k.observations} engine observations noticed · {k.unmatched} unmatched · {k.retests} re-tests · period {kpi.data?.from} – {kpi.data?.to}</p>
        </>
      )}
    </section>
  );
}
