import { useState, type FormEvent } from 'react';
import { CONNECTOR_STATES, DLQ_STATUSES, PIPELINE_STAGES, engineApi, type DeadLetter } from '../../api/deviceEngine';
import { can } from '../../auth/roles';
import { formatDate, humanise, localToIso } from '../../components/format';
import { KpiTile } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { NotPermitted, ShortHash, StateBadge } from './common';
import { isForbidden, pct } from './util';

/**
 * Engine health (contract §12 operations API, E31-F11): connector health and latency per device instance, throughput,
 * error / block / unmatched rates, active alerts, unmatched counts. PHI-free – counts and states only.
 */
export function EngineHealth({ client, session, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const [site, setSite] = useState('');
  const [state, setState] = useState('');
  const [window, setWindow] = useState<'PT1H' | 'P1D'>('PT1H');
  const connectors = useAsync(() => api.connectors({ siteCode: site || undefined, state: state || undefined }), onSessionExpired, [site, state]);
  const metrics = useAsync(() => api.metrics(window), onSessionExpired, [window]);
  const alerts = useAsync(() => api.alerts(), onSessionExpired, []);
  const unmatched = useAsync(() => (can(session.roles, 'engineUnmatchedCount') ? api.unmatchedCount(site || undefined) : Promise.resolve(undefined)), onSessionExpired, [site]);
  const m = metrics.data;
  const rows = connectors.data ?? [];

  return (
    <section>
      <PageTitle sub="Connector health, latency and error rates of the device integration engine. Counts and states only – no person data.">Engine health</PageTitle>
      <div className="filters inline-form">
        <Field label="Site">{(id) => <input id={id} value={site} maxLength={40} placeholder="All sites" onChange={(e) => setSite(e.target.value.toUpperCase())} />}</Field>
        <Field label="Connector state">
          {(id) => (
            <select id={id} value={state} onChange={(e) => setState(e.target.value)}>
              <SelectOptions values={CONNECTOR_STATES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Metrics window">
          {(id) => (
            <select id={id} value={window} onChange={(e) => setWindow(e.target.value as 'PT1H' | 'P1D')}>
              <option value="PT1H">Last hour</option>
              <option value="P1D">Last 24 hours</option>
            </select>
          )}
        </Field>
        <button type="button" className="secondary" onClick={() => { connectors.reload(); metrics.reload(); alerts.reload(); unmatched.reload(); }}>Refresh</button>
      </div>

      <NotPermitted error={metrics.error} what="the engine operations views" />
      {!!metrics.error && !isForbidden(metrics.error) && <ErrorAlert error={metrics.error} />}
      {m && (
        <div className="tiles">
          <KpiTile label="Messages" value={m.messages} />
          <KpiTile label="Peak messages / s" value={m.perSecondPeak ?? 0} />
          <KpiTile label="Latency p50 / p95" value={`${m.latencyP50Ms ?? '–'} / ${m.latencyP95Ms ?? '–'} ms`} target="Target p95 ≤ 5 000 ms" status={m.latencyP95Ms === null || m.latencyP95Ms === undefined ? 'UNKNOWN' : m.latencyP95Ms <= 5000 ? 'MET' : 'MISSED'} />
          <KpiTile label="Parse errors" value={pct(m.parseErrorRate)} />
          <KpiTile label="Blocked by quality gates" value={pct(m.validationBlockRate)} />
          <KpiTile label="Unmatched" value={pct(m.unmatchedRate, 2)} target="Target < 0.5 %" status={m.messages === 0 ? 'UNKNOWN' : m.unmatchedRate < 0.005 ? 'MET' : 'MISSED'} />
          <KpiTile label="Unmatched open / overdue" value={`${unmatched.data?.open ?? m.unmatchedOpen} / ${unmatched.data?.overdue ?? m.slaBreaches}`} />
          <KpiTile label="Dead letters open" value={m.deadLettersOpen} />
        </div>
      )}

      <h2>Active alerts</h2>
      {alerts.loading && !alerts.data && <Loading />}
      {alerts.data && alerts.data.length === 0 && <Empty>No active alerts.</Empty>}
      {(alerts.data ?? []).length > 0 && (
        <ul className="card-list">
          {(alerts.data ?? []).map((a, i) => (
            <li key={`${a.type}-${a.deviceInstanceId ?? a.siteCode}-${i}`} className="card">
              <StateBadge code={a.level} /> <strong>{humanise(a.type)}</strong> {a.deviceInstanceId && <span className="mono">{a.deviceInstanceId}</span>} {a.siteCode && <span className="small muted">{a.siteCode}</span>}
              {a.since && <div className="small muted">since {formatDate(a.since)}</div>}
            </li>
          ))}
        </ul>
      )}

      <h2>Connectors</h2>
      {connectors.loading && !connectors.data && <Loading />}
      {!!connectors.error && !metrics.error && <ErrorAlert error={connectors.error} />}
      {connectors.data && rows.length === 0 && <Empty>No connectors match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Connector health</caption>
            <thead>
              <tr>
                <th scope="col">Device instance</th>
                <th scope="col">Endpoint</th>
                <th scope="col">Site · option</th>
                <th scope="col">State</th>
                <th scope="col">Last seen</th>
                <th scope="col" className="num">Messages 24 h</th>
                <th scope="col" className="num">Errors 24 h</th>
                <th scope="col" className="num">p95 latency</th>
                <th scope="col" className="num">Unmatched</th>
                <th scope="col" className="num">Dead letters</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.deviceInstanceId}>
                  <th scope="row" className="mono">{c.deviceInstanceId}</th>
                  <td className="mono">{c.endpointId ?? '–'}</td>
                  <td>{c.siteCode ?? '–'} · {c.option ?? '–'}</td>
                  <td><StateBadge code={c.state} /></td>
                  <td>{formatDate(c.lastSeenAt)}</td>
                  <td className="num">{c.messages24h}</td>
                  <td className={`num${c.errors24h > 0 ? ' text-red' : ''}`}>{c.errors24h}</td>
                  <td className={`num${(c.p95LatencyMillis ?? 0) > 5000 ? ' text-amber' : ''}`}>{c.p95LatencyMillis === null || c.p95LatencyMillis === undefined ? '–' : `${c.p95LatencyMillis} ms`}</td>
                  <td className="num">{c.unmatchedOpen}</td>
                  <td className="num">{c.deadLetters}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {can(session.roles, 'engineRangeReplay') && <RangeReplay {...{ client, session, onSessionExpired }} />}
    </section>
  );
}

function RangeReplay({ client, onSessionExpired }: ScreenProps) {
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({ from: '', to: '', deviceInstanceId: '', stageFrom: 'PARSE' as 'PARSE' | 'NORMALISE' });
  async function submit(e: FormEvent) {
    e.preventDefault();
    const from = localToIso(f.from);
    const to = localToIso(f.to);
    if (!from || !to) return;
    await action.run(async () => {
      const r = await client.api<{ jobId: string; messages: number; routedObservations?: number }>('/device-engine/replay', {
        method: 'POST', body: { from, to, stageFrom: f.stageFrom, ...(f.deviceInstanceId.trim() ? { deviceInstanceId: f.deviceInstanceId.trim() } : {}) },
      });
      return `Replay ${r.jobId.slice(0, 8)} re-processed ${r.messages} message(s); ${r.routedObservations ?? 0} observation(s) routed. Sinks are idempotent – no duplicates.`;
    });
  }
  return (
    <details className="tile">
      <summary>Replay a time range from the stored raw payloads</summary>
      <form className="inline-form" onSubmit={submit}>
        <Field label="From" required>{(id) => <input id={id} type="datetime-local" required value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} />}</Field>
        <Field label="To" required>{(id) => <input id={id} type="datetime-local" required value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} />}</Field>
        <Field label="Device instance (optional)">{(id) => <input id={id} maxLength={64} value={f.deviceInstanceId} onChange={(e) => setF({ ...f, deviceInstanceId: e.target.value })} />}</Field>
        <Field label="Start at stage">
          {(id) => (
            <select id={id} value={f.stageFrom} onChange={(e) => setF({ ...f, stageFrom: e.target.value as 'PARSE' | 'NORMALISE' })}>
              <option value="PARSE">Parse</option>
              <option value="NORMALISE">Normalise</option>
            </select>
          )}
        </Field>
        <button type="submit" disabled={action.busy}>Replay range</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </details>
  );
}

/** Dead letters (masked) with replay from the stored raw payload (optionally pinned driver version) or discard with a reason. */
export function DeadLetters({ client, session, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const [stage, setStage] = useState('');
  const [status, setStatus] = useState('OPEN');
  const list = useAsync(() => api.deadLetters({ stage: stage || undefined, status: status || undefined }), onSessionExpired, [stage, status]);
  const action = useAction(onSessionExpired);
  const [versions, setVersions] = useState<Record<string, string>>({});
  const [discarding, setDiscarding] = useState<DeadLetter>();
  const [reason, setReason] = useState('');
  const canReplay = can(session.roles, 'engineReplay');

  async function replay(d: DeadLetter) {
    const v = versions[d.id]?.trim();
    if (v && !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(v)) return;
    if (await action.run(async () => {
      const r = await api.replay(d.id, v || undefined);
      return `Replayed message ${r.messageId.slice(0, 8)}: ${humanise(r.status)}${r.errorCode ? ` (${r.errorCode})` : ''}${r.observationIds?.length ? ` – ${r.observationIds.length} observation(s)` : ''}.`;
    })) list.reload();
  }
  async function discard(e: FormEvent) {
    e.preventDefault();
    if (!discarding || !reason.trim()) return;
    if (await action.run(async () => {
      await api.discard(discarding.id, reason.trim());
      setDiscarding(undefined);
      setReason('');
      return 'Dead letter discarded; the raw payload moves to the unfiled retention class.';
    })) list.reload();
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Messages that failed a pipeline stage. Details are masked; replay re-runs the stored raw payload with the current (or a pinned) driver version – sinks are idempotent, so replays never duplicate results.">Dead letters</PageTitle>
      <div className="filters inline-form">
        <Field label="Stage">
          {(id) => (
            <select id={id} value={stage} onChange={(e) => setStage(e.target.value)}>
              <SelectOptions values={PIPELINE_STAGES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={DLQ_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      <NotPermitted error={list.error} what="dead letters" />
      {!!list.error && !isForbidden(list.error) && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No dead letters.</Empty>}
      {rows.length > 0 && (
        <ul className="card-list">
          {rows.map((d) => (
            <li key={d.id} className="card">
              <div className="card-row">
                <StateBadge code={d.status} /> <strong>{humanise(d.stage)}</strong> · <span className="mono">{d.code}</span>
                {d.deviceInstanceId && <> · <span className="mono">{d.deviceInstanceId}</span></>}
              </div>
              {d.detailMasked && <p className="small prewrap">{d.detailMasked}</p>}
              <div className="small muted">
                Message <ShortHash value={d.messageId} /> · attempts {d.attempts} · first failed {formatDate(d.firstFailedAt)} · last {formatDate(d.lastFailedAt)}
                {d.resolvedBy && <> · {humanise(d.status)} by {d.resolvedBy} {formatDate(d.resolvedAt)}</>}
                {d.reason && <> · {d.reason}</>}
              </div>
              {canReplay && d.status === 'OPEN' && (
                <div className="inline-form">
                  <Field label="Driver version (optional)" hint="e.g. 1.0.1 – default: currently published">
                    {(id) => <input id={id} maxLength={17} value={versions[d.id] ?? ''} onChange={(e) => setVersions({ ...versions, [d.id]: e.target.value })} />}
                  </Field>
                  <button type="button" disabled={action.busy} onClick={() => void replay(d)}>Replay</button>
                  <button type="button" className="secondary" disabled={action.busy} onClick={() => { setDiscarding(d); setReason(''); }}>Discard…</button>
                </div>
              )}
              {discarding?.id === d.id && (
                <form className="inline-form" onSubmit={discard}>
                  <Field label="Reason for discarding" required>{(id) => <input id={id} required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
                  <button type="submit" className="danger-button" disabled={action.busy || !reason.trim()}>Discard</button>
                  <button type="button" className="secondary" onClick={() => setDiscarding(undefined)}>Keep</button>
                </form>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
