import './capture.css';
import { useId, useState } from 'react';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { REJECT_REASONS, captureApi, type AcceptResult, type CaptureSession, type DeviceChoice, type RejectReason } from '../../api/deviceCapture';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { STATE_TEXT, acceptState, blockFlags, isFinal, pathUsed, previewFlags } from '../../domain/capture';
import { ComparisonBlock, MarkingTag, PreviewValues, ProvenanceDetails, QualityFlags } from './CapturePreview';
import { FallbackChain } from './FallbackChain';
import { StationPicker } from './StationPicker';
import { useLiveSession, type LiveChannel } from './useLiveSession';
import { rememberPendingSignOff, useStation } from './workstation';

const STATUS_TONE: Record<string, 'green' | 'amber' | 'red' | 'muted'> = { AVAILABLE: 'green', BUSY: 'amber', LOCKED_OUT: 'red', OFFLINE: 'muted' };
const STATUS_TEXT: Record<string, string> = { AVAILABLE: 'Available', BUSY: 'In use', LOCKED_OUT: 'Locked out', OFFLINE: 'Offline' };
const CHANNEL_TEXT: Record<LiveChannel, string> = { connecting: 'Connecting…', live: 'Live', polling: 'Updating every 2 seconds', closed: 'Finished' };
const SIGN_OFF_CATEGORIES = ['ECG', 'ABPM_HOLTER'];

export interface CaptureFromDeviceProps {
  client: AuthClient;
  session: Session;
  onSessionExpired: () => void;
  employeeId: string;
  employeeName?: string;
  /** Bound form, e.g. `E03.DEVICE_RESULT.VITALS`, `E10.BREATH_ALCOHOL`, `E12.SCREENING_RESULT`. */
  formCode: string;
  fieldGroup?: string;
  caseId?: string;
  /** E10 test id (mandatory for breath alcohol), E12 screening event id, E03 order reference. */
  orderRef?: string;
  title?: string;
  /** Called once the reading is accepted and filed by the module (the host form reloads / fills its fields read-only). */
  onAccepted?: (result: AcceptResult, session: CaptureSession) => void;
  /** Injected in tests: SSE factory (null = polling only) and polling interval. */
  live?: { eventSource?: ((url: string) => EventSource) | null; pollMs?: number };
}

/**
 * "Capture from device" (contract E31-E34 §14.2 UI contract, E33-HLR-01/-02/-10/-11). Lists the devices at this station
 * (status only, no technical settings), opens a capture session, shows live progress (SSE, polling fallback), previews values
 * with quality flags and provenance, and lets the clinician accept, reject or re-test. WARN flags must be acknowledged; BLOCK
 * flags disable posting and offer the fall-back chain. Restricted readings show "restricted reading" without values.
 */
export function CaptureFromDevice(props: CaptureFromDeviceProps) {
  const { client, onSessionExpired, employeeId, formCode, title = 'Capture from device' } = props;
  const station = useStation();
  const headingId = useId();
  // a new employee or form starts a fresh capture
  const scope = `${employeeId}|${formCode}`;
  const [opened, setOpened] = useState<{ scope: string; id: string }>();
  const sessionId = opened?.scope === scope ? opened.id : undefined;
  const setSessionId = (id: string | undefined) => setOpened(id ? { scope, id } : undefined);
  const live = useLiveSession(client, sessionId, onSessionExpired, props.live ?? {});
  const form = useAsync(() => captureApi(client).form(formCode), onSessionExpired, [formCode]);

  return (
    <section className="capture-panel" aria-labelledby={headingId}>
      <div className="capture-head">
        <h3 id={headingId}>{title}</h3>
        <span className="small muted">{form.data ? `${humanise(form.data.category)} · ${form.data.module}` : formCode}</span>
      </div>
      <StationPicker client={client} onSessionExpired={onSessionExpired} idPrefix={`st-${headingId.replace(/:/g, '')}`} />
      {station && !sessionId && <DeviceList {...props} station={station} onOpened={(s) => { live.set(s); setSessionId(s.sessionId); }} />}
      {sessionId && !live.session && !live.error && <Loading label="Opening capture session…" />}
      {!!live.error && !live.session && <ErrorAlert error={live.error} />}
      {live.session && (
        <SessionView
          {...props}
          s={live.session}
          channel={live.channel}
          progress={live.progress}
          form={form.data}
          onUpdated={live.set}
          onRestart={() => setSessionId(undefined)}
        />
      )}
    </section>
  );
}

function DeviceList({ client, session, onSessionExpired, employeeId, formCode, fieldGroup, caseId, orderRef, station, onOpened }: CaptureFromDeviceProps & { station: { siteCode: string; stationCode: string }; onOpened: (s: CaptureSession) => void }) {
  const api = captureApi(client);
  const devices = useAsync(() => api.devices(formCode, station.siteCode, station.stationCode), onSessionExpired, [formCode, station.siteCode, station.stationCode]);
  const action = useAction(onSessionExpired);
  const canCapture = can(session.roles, 'deviceCapture');

  async function open(d: DeviceChoice) {
    await action.run(async () => {
      const s = await api.open({ formCode, employeeId, deviceInstanceId: d.deviceInstanceId, siteCode: station.siteCode, stationCode: station.stationCode, ...(fieldGroup ? { fieldGroup } : {}), ...(caseId ? { caseId } : {}), ...(orderRef ? { orderRef } : {}) });
      onOpened(s);
    });
  }

  if (devices.loading && !devices.data) return <Loading label="Finding devices at this station…" />;
  if (devices.error) return <ErrorAlert error={devices.error} />;
  const list = devices.data ?? [];
  return (
    <div>
      {list.length === 0 ? (
        <Empty>No certified device for this measurement at this station. Use the form's manual entry or change the station.</Empty>
      ) : (
        <ul className="device-choices" aria-label="Devices at this station">
          {list.map((d) => (
            <li key={d.deviceInstanceId} className={`device-choice device-${d.status.toLowerCase().replace(/_/g, '-')}`}>
              <div>
                <strong>{d.displayName}</strong>
                {d.model && <span className="small muted"> · {d.model}</span>}
                <div>
                  <ToneBadge tone={STATUS_TONE[d.status] ?? 'muted'}>{STATUS_TEXT[d.status] ?? humanise(d.status)}</ToneBadge>
                  {d.status === 'BUSY' && d.heldBy && <span className="small muted"> by {d.heldBy}</span>}
                  {d.lockReason && <span className="small"> {d.lockReason}</span>}
                  {d.nextAction && <div className="small muted">{d.nextAction}</div>}
                </div>
              </div>
              {canCapture && d.status !== 'BUSY' && (
                <button type="button" className={d.status === 'AVAILABLE' ? undefined : 'secondary'} disabled={action.busy} onClick={() => void open(d)}>
                  {d.status === 'AVAILABLE' ? 'Start capture' : 'Start with other options'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <div className="actions">
        <button type="button" className="link-button" onClick={devices.reload}>Refresh device status</button>
      </div>
      <ActionFeedback error={action.error} />
    </div>
  );
}

function SessionView({ client, session: auth, onSessionExpired, s, channel, progress, form, onUpdated, onRestart, onAccepted }: CaptureFromDeviceProps & {
  s: CaptureSession; channel: LiveChannel; progress?: string; form?: Parameters<typeof FallbackChain>[0]['form']; onUpdated: (s: CaptureSession) => void; onRestart: () => void;
}) {
  const api = captureApi(client);
  const action = useAction(onSessionExpired);
  // acknowledgements belong to one observation round – a new round starts again
  const round = s.preview?.observationId ?? '';
  const [ackState, setAckState] = useState<{ round: string; keys: Set<string> }>({ round, keys: new Set() });
  const acked = ackState.round === round ? ackState.keys : new Set<string>();
  const setAcked = (keys: Set<string>) => setAckState({ round, keys });
  const [note, setNote] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState<RejectReason>('ARTEFACT');
  const [showFallback, setShowFallback] = useState(false);
  const [accepted, setAccepted] = useState<AcceptResult>();
  const flags = previewFlags(s);
  const accept = acceptState(s, acked);
  const blocked = blockFlags(flags).length > 0;
  const final = isFinal(s.state);
  const fallbackVisible = !final && s.fallbackOptions.length > 0 && (s.state === 'FALLBACK_OFFERED' || s.state === 'FALLBACK_ENTRY' || blocked || showFallback);

  async function doAccept() {
    await action.run(async () => {
      const r = await api.accept(s.sessionId, [...acked], note.trim() || undefined);
      setAccepted(r);
      const fresh = await api.session(s.sessionId);
      onUpdated(fresh);
      if (r.interpretationStatus === 'UNVERIFIED' || SIGN_OFF_CATEGORIES.includes(s.category)) {
        rememberPendingSignOff({ sessionId: s.sessionId, category: s.category, employeeId: s.employeeId, employeeName: s.employeeName, acceptedAt: new Date().toISOString() });
      }
      onAccepted?.(r, fresh);
      return 'Accepted – the values were filed in the record.';
    });
  }
  async function doReject(reason: RejectReason) {
    await action.run(async () => {
      onUpdated(await api.reject(s.sessionId, reason, note.trim() || undefined));
      setRejecting(false);
      setNote('');
      return reason === 'RETEST' ? 'Re-test requested – take the measurement again.' : 'Reading rejected – the values were not filed.';
    });
  }
  async function doCancel() {
    await action.run(async () => {
      onUpdated(await api.cancel(s.sessionId));
    });
  }

  return (
    <div className={`capture-session state-${s.state.toLowerCase().replace(/_/g, '-')}`}>
      <div className="capture-status" aria-live="polite">
        <span className={`badge state-badge state-${s.state.toLowerCase().replace(/_/g, '-')}`}>{humanise(s.state)}</span>{' '}
        <strong>{STATE_TEXT[s.state]}</strong>
        {progress && s.state === 'LISTENING' && <span className="small"> · Receiving data ({humanise(progress)})…</span>}
        <div className="small muted">
          {s.employeeName ?? s.employeeId} · {s.deviceName ?? s.deviceInstanceId ?? 'no device'} · {CHANNEL_TEXT[channel]}
          {s.expiresAt && !final && <> · expires {formatDate(s.expiresAt)}</>}
          {s.retestCount > 0 && <> · re-tests {s.retestCount}</>}
        </div>
        {s.contextPush && s.state === 'LISTENING' && <div className="small muted">{humanise(s.contextPush)}</div>}
      </div>

      {s.state === 'LISTENING' && (
        <p className="capture-hint">
          <span className="pulse" aria-hidden="true" /> Take the measurement on the device. The values appear here automatically.
        </p>
      )}
      {s.fallbackReason && !final && s.state !== 'PREVIEW' && <Alert kind="warning">Device not available: {humanise(s.fallbackReason)}.</Alert>}

      {s.restrictedReading && (
        <div className="restricted-reading" role="status">
          <strong>Restricted reading</strong>
          <p className="small">Breath-alcohol and drug-screen readings are visible only to the collector of the session and the MRO.</p>
        </div>
      )}

      {s.preview && !s.restrictedReading && (
        <div className="capture-preview">
          <div className="preview-head">
            <MarkingTag s={s} /> <span className="small muted">Path: {pathUsed(s)}</span>
            {s.preview.effectiveAt && <span className="small muted"> · measured {formatDate(s.preview.effectiveAt)}</span>}
          </div>
          <PreviewValues preview={s.preview} />
          <QualityFlags flags={flags} acknowledged={acked} idPrefix={`q-${s.sessionId.slice(0, 8)}`} readOnly={s.state !== 'PREVIEW'} onToggle={(k, on) => {
            const n = new Set(acked);
            if (on) n.add(k);
            else n.delete(k);
            setAcked(n);
          }} />
          <ComparisonBlock preview={s.preview} />
          <ProvenanceDetails p={s.preview.provenance} marking={s.preview.marking} />
          {s.preview.interpretationStatus === 'UNVERIFIED' && <Alert kind="warning">The device interpretation is unverified until a physician signs it off.</Alert>}
        </div>
      )}

      {s.state === 'PREVIEW' && !s.restrictedReading && (
        <div className="capture-actions">
          <div className="field">
            <label htmlFor={`note-${s.sessionId}`}>Note (optional)</label>
            <input id={`note-${s.sessionId}`} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="actions">
            <button type="button" disabled={!accept.enabled || action.busy} aria-describedby={accept.reason ? `why-${s.sessionId}` : undefined} onClick={() => void doAccept()}>
              Accept and file
            </button>
            {s.actions.includes('RETEST') && <button type="button" className="secondary" disabled={action.busy} onClick={() => void doReject('RETEST')}>Re-test</button>}
            {s.actions.includes('REJECT') && <button type="button" className="secondary" disabled={action.busy} aria-expanded={rejecting} onClick={() => setRejecting(!rejecting)}>Reject</button>}
          </div>
          {accept.reason && <p id={`why-${s.sessionId}`} className="small muted">{accept.reason}</p>}
          {rejecting && (
            <div className="inline-form">
              <div className="field">
                <label htmlFor={`rr-${s.sessionId}`}>Reason</label>
                <select id={`rr-${s.sessionId}`} value={rejectReason} onChange={(e) => setRejectReason(e.target.value as RejectReason)}>
                  <SelectOptions values={REJECT_REASONS.filter((r) => r !== 'RETEST')} />
                </select>
              </div>
              <button type="button" className="danger-button" disabled={action.busy} onClick={() => void doReject(rejectReason)}>Reject reading</button>
            </div>
          )}
        </div>
      )}

      {fallbackVisible && <FallbackChain client={client} onSessionExpired={onSessionExpired} session={s} form={form} onUpdated={(x) => { setShowFallback(false); onUpdated(x); }} />}

      {!final && (
        <div className="actions">
          {!fallbackVisible && s.fallbackOptions.length > 0 && (s.state === 'LISTENING' || s.state === 'PREVIEW') && (
            <button type="button" className="secondary" onClick={() => setShowFallback(true)}>Device not working? Other options</button>
          )}
          {s.actions.includes('CANCEL') && <button type="button" className="link-button" disabled={action.busy} onClick={() => void doCancel()}>Cancel capture</button>}
        </div>
      )}

      {s.state === 'ACCEPTED' && (
        <div className="capture-done">
          <Alert kind="success">
            Filed{s.routedRecordType ? ` as ${humanise(s.routedRecordType)}` : ''}
            {(accepted?.routedRecordId ?? s.routedRecordId) && <> <span className="mono">{accepted?.routedRecordId ?? s.routedRecordId}</span></>}. Path used: {pathUsed(s)}.
          </Alert>
          {s.observationId && can(auth.roles, 'deviceObservations') && (
            <p className="small"><a href={`#/devices/observations/${encodeURIComponent(s.observationId)}`}>View provenance</a></p>
          )}
          {SIGN_OFF_CATEGORIES.includes(s.category) && (s.interpretationStatus ?? accepted?.interpretationStatus) === 'UNVERIFIED' && (
            <p className="small">
              Awaiting physician sign-off.{' '}
              {can(auth.roles, 'deviceSignOff') && <a href={`#/devices/sign-off/${encodeURIComponent(s.sessionId)}`}>Sign off now</a>}
            </p>
          )}
        </div>
      )}
      {final && (
        <div className="actions">
          <button type="button" className="secondary" onClick={onRestart}>{s.state === 'ACCEPTED' ? 'Re-measure' : 'Start a new capture'}</button>
        </div>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}
