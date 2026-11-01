import { useState, type FormEvent } from 'react';
import { TERMINOLOGY_SYSTEMS, engineApi, type Conformance, type DriverReport, type GoldenDataset, type SimulatorRun } from '../../api/deviceEngine';
import { DEVICE_FAMILIES } from '../../api/devices';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, TabNav } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { NotPermitted, ShortHash, StateBadge } from './common';
import { isForbidden } from './util';

const TABS = [
  { id: 'datasets', label: 'Golden datasets & simulators' },
  { id: 'drivers', label: 'Driver validation & conformance' },
];

/**
 * Simulators and golden datasets ("run sample" feeds the pipeline as a virtual SIM-<adapter> endpoint; test traffic, never
 * routed to production sinks) and the driver SDK checks: schema / secret scan / mapping coverage, and conformance (all test
 * vectors in the simulator, evidence SHA-256). Synthetic data only.
 */
export function EngineTooling(props: ScreenProps & { tab?: string }) {
  const tab = props.tab === 'drivers' ? 'drivers' : 'datasets';
  return (
    <section>
      <PageTitle sub="Synthetic golden datasets per device family, simulator runs and driver package checks. Simulator traffic is flagged as test traffic and never reaches a clinical record.">Simulators and driver tests</PageTitle>
      <TabNav tabs={TABS} current={tab} hrefFor={(id) => `#/devices/simulators${id === 'datasets' ? '' : `/${id}`}`} label="Engine tooling" />
      <div className="tab-panel">{tab === 'datasets' ? <Datasets {...props} /> : <DriverChecks {...props} />}</div>
    </section>
  );
}

function Datasets({ client, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const [family, setFamily] = useState('');
  const list = useAsync(() => api.golden(family || undefined), onSessionExpired, [family]);
  const action = useAction(onSessionExpired);
  const [count, setCount] = useState<Record<string, string>>({});
  const [runs, setRuns] = useState<SimulatorRun[]>([]);
  const [device, setDevice] = useState('');

  async function run(d: GoldenDataset) {
    const n = Math.max(1, Math.min(500, Number(count[d.datasetId] ?? '1') || 1));
    const deviceInstanceId = device.trim() || undefined;
    await action.run(async () => {
      const r = await api.simulate(d.adapter, { datasetId: d.datasetId, count: n, ...(deviceInstanceId ? { deviceInstanceId } : {}) });
      setRuns([r, ...runs].slice(0, 10));
      return `Sample ${d.datasetId}${deviceInstanceId ? ` as ${deviceInstanceId}` : ''}: ${r.messages} message(s), ${r.accepted} accepted, ${r.duplicates} duplicate(s), ${r.failed} refused.`;
    });
  }

  const rows = list.data ?? [];
  return (
    <>
      <div className="filters inline-form">
        <Field label="Family">
          {(id) => (
            <select id={id} value={family} onChange={(e) => setFamily(e.target.value)}>
              <SelectOptions values={DEVICE_FAMILIES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Feed as device (optional)" hint="Published device instance, e.g. DEV-000003 – a capture session listening on it receives the simulated reading (marked as test traffic)">
          {(id) => <input id={id} className="mono" maxLength={64} pattern="[A-Za-z0-9._\-]{1,64}" value={device} onChange={(e) => setDevice(e.target.value)} />}
        </Field>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {runs.length > 0 && (
        <div className="tile">
          <h2>Recent runs</h2>
          <ul className="plain-list">
            {runs.map((r) => (
              <li key={r.runId} className="small">
                <span className="mono">{r.datasetId}</span> ({r.adapter}{r.deviceInstanceId && r.deviceInstanceId !== r.endpointId ? ` as ${r.deviceInstanceId}` : ''}) – {r.messages} sent · {r.accepted} accepted · {r.duplicates} duplicate · {r.failed} refused · outcomes {r.statuses.slice(0, 5).map((s) => humanise(s.split(':')[0])).join(', ')}
                {r.statuses.length > 5 ? '…' : ''} · evidence <ShortHash value={r.evidenceSha256} />
              </li>
            ))}
          </ul>
        </div>
      )}
      {list.loading && !list.data && <Loading />}
      <NotPermitted error={list.error} what="the golden datasets" />
      {!!list.error && !isForbidden(list.error) && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No golden datasets for this family.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Golden datasets</caption>
            <thead>
              <tr>
                <th scope="col">Dataset</th>
                <th scope="col">Family · adapter</th>
                <th scope="col">Kind</th>
                <th scope="col">Description</th>
                <th scope="col">Run sample</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.datasetId}>
                  <th scope="row" className="mono small">{d.datasetId}</th>
                  <td>{humanise(d.family)} · {d.adapter}</td>
                  <td><span className="badge badge-muted">{humanise(d.kind)}</span></td>
                  <td className="small">{d.description}</td>
                  <td>
                    <div className="inline-row">
                      <label className="visually-hidden" htmlFor={`n-${d.datasetId}`}>Messages for {d.datasetId}</label>
                      <input id={`n-${d.datasetId}`} className="value-input" type="number" min={1} max={500} value={count[d.datasetId] ?? '1'} onChange={(e) => setCount({ ...count, [d.datasetId]: e.target.value })} />
                      <button type="button" className="small-button" disabled={action.busy} onClick={() => void run(d)}>Run sample</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function DriverChecks({ client, session, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const samples = useAsync(() => api.drivers(), onSessionExpired, []);
  const action = useAction(onSessionExpired);
  const [text, setText] = useState('');
  const [parseError, setParseError] = useState<string>();
  const [report, setReport] = useState<DriverReport>();
  const [conf, setConf] = useState<Conformance>();
  const tester = can(session.roles, 'engineDriverValidate');

  function parsed(): unknown {
    try {
      const v: unknown = JSON.parse(text);
      setParseError(undefined);
      return v;
    } catch {
      setParseError('The package is not valid JSON.');
      return undefined;
    }
  }
  async function loadSample(packageId: string, version: string) {
    await action.run(async () => {
      const src = await api.driverSource(packageId, version);
      setText(JSON.stringify(src, null, 2));
      setReport(undefined);
      setConf(undefined);
    });
  }
  async function validate(e: FormEvent) {
    e.preventDefault();
    const pkg = parsed();
    if (pkg === undefined) return;
    await action.run(async () => {
      setReport(await api.validate(pkg));
    });
  }
  async function conformance() {
    const pkg = parsed();
    if (pkg === undefined) return;
    await action.run(async () => {
      setConf(await api.conformance(pkg));
    });
  }

  return (
    <>
      <h2>Bundled sample packages</h2>
      {samples.loading && !samples.data && <Loading />}
      <NotPermitted error={samples.error} what="the driver SDK" />
      {(samples.data ?? []).length > 0 && (
        <ul className="card-list">
          {(samples.data ?? []).map((d) => (
            <li key={`${d.packageId}@${d.version}`} className="card">
              <span className="mono">{d.packageId}@{d.version}</span> <span className="small muted">{humanise(d.family)} · {d.adapter} · options {d.options.join(', ')} · models {d.models.join(', ')}</span>
              <div className="small">SHA-256 <ShortHash value={d.sha256} /></div>
              <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void loadSample(d.packageId, d.version)}>Load into the editor</button>
            </li>
          ))}
        </ul>
      )}
      {!tester && <Alert kind="info">Validation and conformance runs are performed by integration testers.</Alert>}
      <form className="form" onSubmit={validate}>
        <Field label="Driver package (wellnessz.device-driver/1 JSON)" hint="Secrets must never appear in a package – the validator scans for them.">
          {(id) => <textarea id={id} className="mono" rows={14} spellCheck={false} value={text} onChange={(e) => setText(e.target.value)} />}
        </Field>
        {parseError && <p className="field-error-text" role="alert">{parseError}</p>}
        {tester && (
          <div className="actions">
            <button type="submit" disabled={action.busy || !text.trim()}>Validate</button>
            <button type="button" className="secondary" disabled={action.busy || !text.trim()} onClick={() => void conformance()}>Run conformance</button>
          </div>
        )}
      </form>
      <ActionFeedback error={action.error} />
      {report && (
        <div className="tile" aria-live="polite">
          <h3>Validation {report.valid ? <StateBadge code="PASSED" label="Valid" /> : <StateBadge code="FAILED" label="Invalid" />}</h3>
          <p className="small">{report.packageId}@{report.version} · SHA-256 <ShortHash value={report.sha256} /></p>
          {report.coverage && (
            <p className="small">Mapping coverage: {report.coverage.mapped}/{report.coverage.fields} fields mapped · {report.coverage.coded} coded (LOINC/MDC) · {report.coverage.quantitiesWithUcum}/{report.coverage.quantities} quantities with UCUM units</p>
          )}
          {report.errors.length > 0 && <><h4>Errors</h4><ul>{report.errors.map((e) => <li key={e}>{e}</li>)}</ul></>}
          {report.warnings.length > 0 && <><h4>Warnings</h4><ul>{report.warnings.map((e) => <li key={e}>{e}</li>)}</ul></>}
          {report.secretFindings.length > 0 && <Alert kind="error">Possible secrets found: {report.secretFindings.join('; ')}</Alert>}
        </div>
      )}
      {conf && (
        <div className="tile" aria-live="polite">
          <h3>Conformance {conf.passed ? <StateBadge code="PASSED" label="Passed" /> : <StateBadge code="FAILED" label="Failed" />}</h3>
          <p className="small">Evidence SHA-256 <ShortHash value={conf.evidenceSha256} /></p>
          <ul className="plain-list">
            {conf.results.map((r) => (
              <li key={r.name}>
                <StateBadge code={r.outcome} /> <span className="mono small">{r.name}</span>
                {r.diff.length > 0 && <ul className="small">{r.diff.map((d) => <li key={d}>{d}</li>)}</ul>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

/** Terminology viewer (versioned MDC → LOINC / UCUM maps, GLI-2012 predicted values) and the quality-gate catalogue with sign-off. */
export function TerminologyViewer({ client, onSessionExpired }: ScreenProps) {
  const api = engineApi(client);
  const [system, setSystem] = useState('');
  const [code, setCode] = useState('');
  const [query, setQuery] = useState<{ system?: string; code?: string }>({});
  const maps = useAsync(() => api.terminology(query), onSessionExpired, [query.system, query.code]);
  const versions = useAsync(() => api.terminologyVersions(), onSessionExpired, []);
  const gates = useAsync(() => api.gates(), onSessionExpired, []);
  const action = useAction(onSessionExpired);
  const [pred, setPred] = useState({ age: '40', sex: 'F' as 'M' | 'F', heightCm: '168' });
  const [predicted, setPredicted] = useState<Awaited<ReturnType<typeof api.predicted>>>();

  async function predict(e: FormEvent) {
    e.preventDefault();
    await action.run(async () => setPredicted(await api.predicted({ age: Number(pred.age), sex: pred.sex, heightCm: Number(pred.heightCm) })));
  }
  const rows = (maps.data ?? []).slice(0, 200);
  return (
    <section>
      <PageTitle sub="Reference data used by the engine: device codes (IEEE 11073 MDC) mapped to LOINC and UCUM, predicted-value equations and the quality-gate catalogue.">Terminology and quality gates</PageTitle>
      {versions.data && versions.data.length > 0 && (
        <p className="small">Releases: {versions.data.map((v) => `${v.version}${v.active ? ' (active)' : ''}${v.loadedAt ? ` loaded ${formatDate(v.loadedAt)}` : ''}`).join(' · ')}</p>
      )}
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setQuery({ system: system || undefined, code: code.trim() || undefined }); }}>
        <Field label="System">
          {(id) => (
            <select id={id} value={system} onChange={(e) => setSystem(e.target.value)}>
              <SelectOptions values={TERMINOLOGY_SYSTEMS} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Code">{(id) => <input id={id} maxLength={80} value={code} placeholder="e.g. MDC_PRESS_BLD_NONINV_SYS" onChange={(e) => setCode(e.target.value)} />}</Field>
        <button type="submit">Search</button>
      </form>
      {maps.loading && !maps.data && <Loading />}
      <NotPermitted error={maps.error} what="the terminology tables" />
      {!!maps.error && !isForbidden(maps.error) && <ErrorAlert error={maps.error} />}
      {maps.data && rows.length === 0 && <Empty>No entries.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Terminology map</caption>
            <thead><tr><th scope="col">Display</th><th scope="col">System · code</th><th scope="col">MDC</th><th scope="col">LOINC</th><th scope="col">SNOMED CT</th><th scope="col">UCUM</th></tr></thead>
            <tbody>
              {rows.map((t, i) => (
                <tr key={`${t.system}-${t.code}-${i}`}>
                  <th scope="row">{t.display ?? '–'}</th>
                  <td className="mono small">{t.system} · {t.code}</td>
                  <td className="mono small">{t.mdc ?? '–'}</td>
                  <td className="mono small">{t.loinc ?? '–'}</td>
                  <td className="mono small">{t.snomed ?? '–'}</td>
                  <td className="mono small">{t.ucum ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2>GLI-2012 predicted spirometry</h2>
      <form className="inline-form" onSubmit={predict}>
        <Field label="Age (years)">{(id) => <input id={id} type="number" min={3} max={95} value={pred.age} onChange={(e) => setPred({ ...pred, age: e.target.value })} />}</Field>
        <Field label="Sex">
          {(id) => (
            <select id={id} value={pred.sex} onChange={(e) => setPred({ ...pred, sex: e.target.value as 'M' | 'F' })}>
              <option value="F">Female</option>
              <option value="M">Male</option>
            </select>
          )}
        </Field>
        <Field label="Height (cm)">{(id) => <input id={id} type="number" min={90} max={230} value={pred.heightCm} onChange={(e) => setPred({ ...pred, heightCm: e.target.value })} />}</Field>
        <button type="submit" disabled={action.busy}>Calculate</button>
      </form>
      <ActionFeedback error={action.error} />
      {predicted && (
        <table className="small">
          <caption>Predicted values (release {predicted.release})</caption>
          <thead><tr><th scope="col">Index</th><th scope="col">Predicted</th><th scope="col">Lower limit of normal</th></tr></thead>
          <tbody>
            {(['FEV1', 'FVC', 'FEV1FVC'] as const).map((k) => (
              <tr key={k}><th scope="row">{k === 'FEV1FVC' ? 'FEV1/FVC' : k}</th><td className="num">{predicted[k].predicted.toFixed(2)}{k === 'FEV1FVC' ? '' : ' L'}</td><td className="num">{predicted[k].lln.toFixed(2)}{k === 'FEV1FVC' ? '' : ' L'}</td></tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Quality-gate catalogue</h2>
      {gates.loading && !gates.data && <Loading />}
      {!!gates.error && !isForbidden(gates.error) && <ErrorAlert error={gates.error} />}
      {(gates.data ?? []).length > 0 && (
        <ul className="card-list">
          {(gates.data ?? []).map((g) => (
            <li key={g.code} className="card">
              <span className="mono">{g.code}</span> {g.signOff && <StateBadge code={g.signOff.status} label={g.signOff.status === 'APPROVED' ? 'Clinical sign-off approved' : 'Clinical sign-off pending'} />}
              <div className="small">{g.description}</div>
              <div className="small muted">{g.families.map((f) => humanise(f)).join(', ')}{g.signOff?.by && <> · signed off by {g.signOff.by} {formatDate(g.signOff.at)}</>}</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
