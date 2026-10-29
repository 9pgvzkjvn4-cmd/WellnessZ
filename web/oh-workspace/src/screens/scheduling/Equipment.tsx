import { useState, type FormEvent } from 'react';
import {
  EQUIPMENT_CATEGORIES,
  EQUIPMENT_STATUSES,
  EXCURSION_STATUSES,
  schedulingApi,
  type CalibrationInput,
  type Equipment as EquipmentRow,
  type EquipmentCategory,
  type EquipmentInput,
  type EquipmentStatus,
  type Excursion,
  type ExcursionStatus,
  type TemperatureReading,
} from '../../api/scheduling';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { addDays, fridgeRange, outOfRange, parseTemperature, todayLocal } from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { CalibrationBadge, LocalTabs, SiteSelect } from './common';
import { useSites } from './useSchedulingData';

type Tab = 'register' | 'due' | 'coldchain' | 'excursions';

/** `#/scheduling/equipment` – equipment register, calibration, cold chain and excursions (E14-F04). */
export function EquipmentScreen(props: ScreenProps) {
  const roles = props.session.roles;
  const tabs = (
    [
      { id: 'register', label: 'Register', show: true },
      { id: 'due', label: 'Calibration due', show: can(roles, 'calibrationDue') },
      { id: 'coldchain', label: 'Cold chain', show: can(roles, 'coldChain') },
      { id: 'excursions', label: 'Excursions', show: can(roles, 'coldChain') },
    ] as { id: Tab; label: string; show: boolean }[]
  ).filter((t) => t.show);
  const [tab, setTab] = useState<Tab>(() => (can(roles, 'equipmentWrite') || can(roles, 'calibrationDue') || !can(roles, 'coldChain') ? 'register' : 'coldchain'));
  return (
    <section>
      <PageTitle sub="Devices with calibration state, vaccine fridges with temperature logs. Out-of-calibration devices are blocked from results and campaigns.">Equipment & cold chain</PageTitle>
      <LocalTabs label="Equipment views" tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'register' && <Register {...props} />}
      {tab === 'due' && <CalibrationDue {...props} />}
      {tab === 'coldchain' && <ColdChain {...props} />}
      {tab === 'excursions' && <Excursions {...props} />}
    </section>
  );
}

function Register({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites } = useSites(client, onSessionExpired);
  const [siteCode, setSiteCode] = useState('');
  const [category, setCategory] = useState('');
  const list = useAsync(() => api.equipment({ siteCode: siteCode || undefined, category: category || undefined }), onSessionExpired, [siteCode, category]);
  const [editing, setEditing] = useState<EquipmentRow | 'new'>();
  const [calibrating, setCalibrating] = useState<EquipmentRow>();
  const [statusFor, setStatusFor] = useState<EquipmentRow>();
  const [history, setHistory] = useState<EquipmentRow>();
  const [message, setMessage] = useState<string>();
  const canWrite = can(session.roles, 'equipmentWrite');
  const canCal = can(session.roles, 'calibrationWrite');
  const today = todayLocal();
  const done = (m: string) => {
    setMessage(m);
    list.reload();
  };
  const rows = list.data ?? [];
  return (
    <>
      <div className="filters">
        <SiteSelect sites={sites.data} value={siteCode} onChange={setSiteCode} allowAll />
        <Field label="Category">
          {(id) => (
            <select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
              <SelectOptions values={EQUIPMENT_CATEGORIES} empty="All categories" />
            </select>
          )}
        </Field>
        {canWrite && (
          <button type="button" onClick={() => setEditing('new')}>
            Register equipment
          </button>
        )}
      </div>
      <ActionFeedback message={message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No equipment matches.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} item{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Asset</th>
                <th scope="col">Category / model</th>
                <th scope="col">Site</th>
                <th scope="col">Status</th>
                <th scope="col">Calibration</th>
                <th scope="col">Cold chain</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <th scope="row">
                    {e.assetTag}
                    {e.deviceId && <div className="small muted mono">{e.deviceId}</div>}
                  </th>
                  <td>
                    {humanise(e.category)}
                    <div className="small muted">{[e.model, e.serialNumber].filter(Boolean).join(' · ')}</div>
                  </td>
                  <td>{e.siteCode}</td>
                  <td>
                    <StatusBadge status={e.status} />
                  </td>
                  <td>
                    <CalibrationBadge state={e.calibrationState} dueOn={e.calibrationDueOn} today={today} />
                    <div className="small muted">
                      {e.calibrationDueOn ? `due ${formatDay(e.calibrationDueOn)}` : 'no due date'}
                      {e.lastCertificateNumber && ` · cert ${e.lastCertificateNumber}`}
                    </div>
                  </td>
                  <td className="small">
                    {e.category === 'VACCINE_FRIDGE' ? (
                      <>
                        {e.lastReading ? (
                          <span className={outOfRange(e.lastReading.temperatureC, e.minTempC, e.maxTempC) ? 'text-red' : undefined}>
                            {e.lastReading.temperatureC.toFixed(1)} °C <span className="muted">{formatDate(e.lastReading.recordedAt)}</span>
                          </span>
                        ) : (
                          'No readings'
                        )}
                        {!!e.openExcursions && (
                          <div>
                            <span className="badge badge-overdue">
                              <span aria-hidden="true">■ </span>
                              {e.openExcursions} open excursion{e.openExcursions === 1 ? '' : 's'}
                            </span>
                          </div>
                        )}
                      </>
                    ) : (
                      '–'
                    )}
                  </td>
                  <td>
                    <div className="actions">
                      <button type="button" className="small-button secondary" onClick={() => setHistory(e)} aria-label={`Calibration history for ${e.assetTag}`}>
                        History
                      </button>
                      {canCal && (
                        <button type="button" className="small-button secondary" onClick={() => setCalibrating(e)} aria-label={`Record calibration for ${e.assetTag}`}>
                          Calibration…
                        </button>
                      )}
                      {canCal && (
                        <button type="button" className="small-button secondary" onClick={() => setStatusFor(e)} aria-label={`Change status of ${e.assetTag}`}>
                          Status…
                        </button>
                      )}
                      {canWrite && (
                        <button type="button" className="small-button secondary" onClick={() => setEditing(e)} aria-label={`Edit ${e.assetTag}`}>
                          Edit
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <EquipmentDialog client={client} onSessionExpired={onSessionExpired} item={editing === 'new' ? undefined : editing} sites={sites.data ?? []} onClose={() => setEditing(undefined)} onDone={done} />}
      {calibrating && <CalibrationDialog client={client} onSessionExpired={onSessionExpired} item={calibrating} onClose={() => setCalibrating(undefined)} onDone={done} />}
      {statusFor && <StatusDialog client={client} onSessionExpired={onSessionExpired} item={statusFor} onClose={() => setStatusFor(undefined)} onDone={done} />}
      {history && <HistoryDialog client={client} onSessionExpired={onSessionExpired} item={history} onClose={() => setHistory(undefined)} />}
    </>
  );
}

interface DialogProps {
  client: ScreenProps['client'];
  onSessionExpired: () => void;
  onClose: () => void;
  onDone: (message: string) => void;
}

function EquipmentDialog({ client, onSessionExpired, item, sites, onClose, onDone }: DialogProps & { item?: EquipmentRow; sites: { code: string; name: string }[] }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({
    assetTag: item?.assetTag ?? '',
    deviceId: item?.deviceId ?? '',
    category: (item?.category ?? 'AUDIOMETER') as EquipmentCategory,
    model: item?.model ?? '',
    serialNumber: item?.serialNumber ?? '',
    siteCode: item?.siteCode ?? sites[0]?.code ?? '',
    calibrationIntervalMonths: String(item?.calibrationIntervalMonths ?? 12),
    lastCalibratedOn: item?.lastCalibratedOn ?? '',
    minTempC: item?.minTempC?.toString() ?? '2',
    maxTempC: item?.maxTempC?.toString() ?? '8',
  });
  const fridge = f.category === 'VACCINE_FRIDGE';
  const min = parseTemperature(f.minTempC);
  const max = parseTemperature(f.maxTempC);
  const interval = Number(f.calibrationIntervalMonths);
  const valid = !!f.assetTag.trim() && !!f.siteCode && Number.isInteger(interval) && interval >= 1 && interval <= 120 && (!fridge || (min !== undefined && max !== undefined && min < max));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const body: EquipmentInput = {
      assetTag: f.assetTag.trim(),
      deviceId: f.deviceId.trim() || undefined,
      category: f.category,
      model: f.model.trim() || undefined,
      serialNumber: f.serialNumber.trim() || undefined,
      siteCode: f.siteCode,
      calibrationIntervalMonths: interval,
      lastCalibratedOn: f.lastCalibratedOn || undefined,
      ...(fridge ? { minTempC: min, maxTempC: max } : {}),
    };
    const ok = await action.run(async () => {
      if (item) await api.updateEquipment(item.id, body);
      else await api.registerEquipment(body);
      onDone(`${body.assetTag} ${item ? 'updated' : 'registered'}.`);
    });
    if (ok) onClose();
  }

  return (
    <Dialog title={item ? `Edit ${item.assetTag}` : 'Register equipment'} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <div className="form-grid">
          <Field label="Asset tag" required>{(id) => <input id={id} value={f.assetTag} maxLength={40} onChange={(e) => setF({ ...f, assetTag: e.target.value })} />}</Field>
          <Field label="Device ID" hint="Identifier the device sends with results">{(id) => <input id={id} value={f.deviceId} maxLength={60} onChange={(e) => setF({ ...f, deviceId: e.target.value })} />}</Field>
          <Field label="Category" required>
            {(id) => (
              <select id={id} value={f.category} onChange={(e) => setF({ ...f, category: e.target.value as EquipmentCategory })}>
                <SelectOptions values={EQUIPMENT_CATEGORIES} />
              </select>
            )}
          </Field>
          <Field label="Site" required>
            {(id) => (
              <select id={id} value={f.siteCode} onChange={(e) => setF({ ...f, siteCode: e.target.value })}>
                <option value="">Choose…</option>
                {sites.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.name} ({s.code})
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Model">{(id) => <input id={id} value={f.model} onChange={(e) => setF({ ...f, model: e.target.value })} />}</Field>
          <Field label="Serial number">{(id) => <input id={id} value={f.serialNumber} onChange={(e) => setF({ ...f, serialNumber: e.target.value })} />}</Field>
          <Field label="Calibration interval (months)" required>
            {(id) => <input id={id} type="number" min={1} max={120} value={f.calibrationIntervalMonths} onChange={(e) => setF({ ...f, calibrationIntervalMonths: e.target.value })} />}
          </Field>
          <Field label="Last calibrated">{(id) => <input id={id} type="date" value={f.lastCalibratedOn} onChange={(e) => setF({ ...f, lastCalibratedOn: e.target.value })} />}</Field>
          {fridge && (
            <>
              <Field label="Minimum °C" required>{(id) => <input id={id} inputMode="decimal" value={f.minTempC} onChange={(e) => setF({ ...f, minTempC: e.target.value })} />}</Field>
              <Field label="Maximum °C" required>{(id) => <input id={id} inputMode="decimal" value={f.maxTempC} onChange={(e) => setF({ ...f, maxTempC: e.target.value })} />}</Field>
            </>
          )}
        </div>
        <div className="actions">
          <button type="submit" disabled={!valid || action.busy}>
            {item ? 'Save' : 'Register'}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

function CalibrationDialog({ client, onSessionExpired, item, onClose, onDone }: DialogProps & { item: EquipmentRow }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const [f, setF] = useState<CalibrationInput>({ performedOn: todayLocal(), vendor: '', certificateNumber: '', result: 'PASS', nextDueOn: '' });
  const [file, setFile] = useState<File>();
  const valid = !!f.performedOn && !!f.vendor.trim() && !!f.certificateNumber.trim() && (!f.nextDueOn || f.nextDueOn > f.performedOn) && (!file || file.size <= 10 * 1024 * 1024);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const ok = await action.run(async () => {
      const documentBase64 = file ? await toBase64(file) : undefined;
      await api.recordCalibration(item.id, { performedOn: f.performedOn, vendor: f.vendor.trim(), certificateNumber: f.certificateNumber.trim(), result: f.result, nextDueOn: f.nextDueOn || undefined, documentBase64 });
      onDone(f.result === 'FAIL' ? `${item.assetTag}: calibration failed – the device is now out of service.` : `${item.assetTag}: calibration recorded.`);
    });
    if (ok) onClose();
  }

  return (
    <Dialog title={`Record calibration – ${item.assetTag}`} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <div className="form-grid">
          <Field label="Performed on" required>{(id) => <input id={id} type="date" max={todayLocal()} value={f.performedOn} onChange={(e) => setF({ ...f, performedOn: e.target.value })} />}</Field>
          <Field label="Vendor" required>{(id) => <input id={id} value={f.vendor} onChange={(e) => setF({ ...f, vendor: e.target.value })} />}</Field>
          <Field label="Certificate number" required>{(id) => <input id={id} value={f.certificateNumber} onChange={(e) => setF({ ...f, certificateNumber: e.target.value })} />}</Field>
          <Field label="Next due" hint={item.calibrationIntervalMonths ? `Default: + ${item.calibrationIntervalMonths} months` : undefined}>
            {(id) => <input id={id} type="date" value={f.nextDueOn} min={addDays(f.performedOn || todayLocal(), 1)} onChange={(e) => setF({ ...f, nextDueOn: e.target.value })} />}
          </Field>
        </div>
        <fieldset>
          <legend>Result</legend>
          {(['PASS', 'FAIL'] as const).map((r) => (
            <label key={r} className="checkbox">
              <input type="radio" name="cal-result" checked={f.result === r} onChange={() => setF({ ...f, result: r })} /> {r === 'PASS' ? 'Pass' : 'Fail (takes the device out of service)'}
            </label>
          ))}
        </fieldset>
        <Field label="Certificate (PDF / image, optional)" hint="Max 10 MB">
          {(id) => <input id={id} type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0])} />}
        </Field>
        <div className="actions">
          <button type="submit" disabled={!valid || action.busy}>
            Record calibration
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

async function toBase64(file: Blob): Promise<string> {
  const buf = new Uint8Array(await file.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return btoa(s);
}

function StatusDialog({ client, onSessionExpired, item, onClose, onDone }: DialogProps & { item: EquipmentRow }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const [status, setStatus] = useState<EquipmentStatus>(item.status);
  const [reason, setReason] = useState('');
  const valid = status !== item.status && !!reason.trim();
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const ok = await action.run(async () => {
      await api.equipmentStatus(item.id, status, reason.trim());
      onDone(`${item.assetTag}: status set to ${humanise(status).toLowerCase()}.`);
    });
    if (ok) onClose();
  }
  return (
    <Dialog title={`Change status – ${item.assetTag}`} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <Field label="New status" required>
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value as EquipmentStatus)}>
              <SelectOptions values={EQUIPMENT_STATUSES} />
            </select>
          )}
        </Field>
        <Field label="Reason" required>{(id) => <textarea id={id} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
        <div className="actions">
          <button type="submit" disabled={!valid || action.busy}>
            Change status
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
        <ActionFeedback error={action.error} />
      </form>
    </Dialog>
  );
}

function HistoryDialog({ client, onSessionExpired, item, onClose }: Omit<DialogProps, 'onDone'> & { item: EquipmentRow }) {
  const h = useAsync(() => schedulingApi(client).calibrations(item.id), onSessionExpired, [item.id]);
  const rows = [...(h.data ?? [])].sort((a, b) => b.performedOn.localeCompare(a.performedOn));
  return (
    <Dialog title={`Calibration history – ${item.assetTag}`} onClose={onClose}>
      {h.loading && <Loading />}
      {!!h.error && <ErrorAlert error={h.error} />}
      {h.data && rows.length === 0 && <Empty>No calibrations recorded.</Empty>}
      {rows.length > 0 && (
        <table>
          <thead>
            <tr>
              <th scope="col">Performed</th>
              <th scope="col">Vendor</th>
              <th scope="col">Certificate</th>
              <th scope="col">Result</th>
              <th scope="col">Next due</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id ?? i}>
                <td>{formatDay(r.performedOn)}</td>
                <td>{r.vendor}</td>
                <td className="mono">{r.certificateNumber}</td>
                <td>
                  <StatusBadge status={r.result === 'PASS' ? 'ACCEPTED' : 'REJECTED'} /> <span className="visually-hidden">{r.result}</span>
                </td>
                <td>{formatDay(r.nextDueOn)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Dialog>
  );
}

function CalibrationDue({ client, onSessionExpired }: ScreenProps) {
  const [days, setDays] = useState(30);
  const list = useAsync(() => schedulingApi(client).calibrationDue(days), onSessionExpired, [days]);
  const today = todayLocal();
  const rows = [...(list.data ?? [])].sort((a, b) => (a.calibrationDueOn ?? '').localeCompare(b.calibrationDueOn ?? ''));
  return (
    <>
      <div className="filters">
        <Field label="Due within">
          {(id) => (
            <select id={id} value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {[7, 30, 60, 90].map((d) => (
                <option key={d} value={d}>
                  {d} days
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nothing due for calibration in the next {days} days.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} item{rows.length === 1 ? '' : 's'} due or overdue</caption>
            <thead>
              <tr>
                <th scope="col">Asset</th>
                <th scope="col">Category</th>
                <th scope="col">Site</th>
                <th scope="col">Due</th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <th scope="row">{e.assetTag}</th>
                  <td>{humanise(e.category)}</td>
                  <td>{e.siteCode}</td>
                  <td>{formatDay(e.calibrationDueOn)}</td>
                  <td>
                    <CalibrationBadge state={e.calibrationState} dueOn={e.calibrationDueOn} today={today} />
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

function ColdChain({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const fridges = useAsync(() => api.equipment({ category: 'VACCINE_FRIDGE' }), onSessionExpired);
  const [chosen, setChosen] = useState('');
  const [from, setFrom] = useState(() => addDays(todayLocal(), -7));
  const [to, setTo] = useState(() => todayLocal());
  const fridge = fridges.data?.find((f) => f.id === (chosen || fridges.data?.[0]?.id));
  const readings = useAsync(
    () => (fridge ? api.temperatures(fridge.id, { from: `${from}T00:00:00Z`, to: `${addDays(to, 1)}T00:00:00Z` }) : Promise.resolve([] as TemperatureReading[])),
    onSessionExpired,
    [fridge?.id, from, to],
  );
  const canEnter = can(session.roles, 'coldChain');
  const action = useAction(onSessionExpired);
  const [temp, setTemp] = useState('');
  const [at, setAt] = useState(() => toLocalInput());
  const t = parseTemperature(temp);

  async function record(e: FormEvent) {
    e.preventDefault();
    if (!fridge || t === undefined) return;
    const ok = await action.run(async () => {
      const r = await api.recordTemperature(fridge.id, t, localToIso(at));
      const exc = r?.excursion ?? outOfRange(t, fridge.minTempC, fridge.maxTempC);
      return exc ? `Reading ${t.toFixed(1)} °C recorded – outside range, an excursion has been raised.` : `Reading ${t.toFixed(1)} °C recorded.`;
    });
    if (ok) {
      setTemp('');
      readings.reload();
      fridges.reload();
    }
  }

  const rows = [...(readings.data ?? [])].sort((a, b) => a.recordedAt.localeCompare(b.recordedAt));
  const [lo, hi] = fridgeRange(fridge?.minTempC, fridge?.maxTempC);
  return (
    <>
      <div className="filters">
        <Field label="Fridge">
          {(id) => (
            <select id={id} value={fridge?.id ?? ''} onChange={(e) => setChosen(e.target.value)}>
              {(fridges.data ?? []).map((f) => (
                <option key={f.id} value={f.id}>
                  {f.assetTag} ({f.siteCode})
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="From">{(id) => <input id={id} type="date" value={from} max={to} onChange={(e) => e.target.value && setFrom(e.target.value)} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={to} min={from} onChange={(e) => e.target.value && setTo(e.target.value)} />}</Field>
      </div>
      {!!fridges.error && <ErrorAlert error={fridges.error} />}
      {fridges.data && fridges.data.length === 0 && <Empty>No vaccine fridges are registered.</Empty>}
      {fridge && (
        <>
          <p className="small">
            Safe range {lo}–{hi} °C{fridge.openExcursions ? ` · ${fridge.openExcursions} open excursion${fridge.openExcursions === 1 ? '' : 's'}` : ''}
          </p>
          {readings.loading && <Loading />}
          {!!readings.error && <ErrorAlert error={readings.error} />}
          {readings.data && rows.length === 0 && <Empty>No readings in this period.</Empty>}
          {rows.length > 0 && <TemperatureChart rows={rows} lo={lo} hi={hi} label={`Temperature of ${fridge.assetTag}`} />}
          {canEnter && (
            <form className="form inline-form card" onSubmit={record} aria-labelledby="manual-h">
              <h3 id="manual-h" className="span-all">
                Manual reading
              </h3>
              <Field label="Temperature (°C)" required hint={temp && t === undefined ? 'Enter a number such as 4.5' : undefined}>
                {(id) => <input id={id} inputMode="decimal" value={temp} onChange={(e) => setTemp(e.target.value)} aria-invalid={!!temp && t === undefined} />}
              </Field>
              <Field label="Recorded at">{(id) => <input id={id} type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} />}</Field>
              <button type="submit" disabled={t === undefined || action.busy}>
                Record reading
              </button>
            </form>
          )}
          <ActionFeedback error={action.error} message={action.message} />
          {rows.length > 0 && (
            <details>
              <summary>Readings table ({rows.length})</summary>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Recorded</th>
                      <th scope="col">°C</th>
                      <th scope="col">Source</th>
                      <th scope="col">Excursion</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...rows].reverse().map((r) => (
                      <tr key={`${r.recordedAt}-${r.source}`} className={r.excursion ? 'row-excursion' : undefined}>
                        <td>{formatDate(r.recordedAt)}</td>
                        <td className="num">{r.temperatureC.toFixed(1)}</td>
                        <td>{humanise(r.source)}</td>
                        <td>{r.excursion ? <strong className="text-red">Yes</strong> : 'No'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
          )}
        </>
      )}
    </>
  );
}

function TemperatureChart({ rows, lo, hi, label }: { rows: TemperatureReading[]; lo: number; hi: number; label: string }) {
  const W = 600;
  const H = 200;
  const pad = { l: 34, r: 8, t: 8, b: 20 };
  const times = rows.map((r) => Date.parse(r.recordedAt));
  const t0 = Math.min(...times);
  const t1 = Math.max(...times, t0 + 1);
  const temps = rows.map((r) => r.temperatureC);
  const yMin = Math.floor(Math.min(lo - 2, ...temps));
  const yMax = Math.ceil(Math.max(hi + 2, ...temps));
  const x = (t: number) => pad.l + ((t - t0) / (t1 - t0)) * (W - pad.l - pad.r);
  const y = (v: number) => pad.t + (1 - (v - yMin) / (yMax - yMin)) * (H - pad.t - pad.b);
  const path = rows.map((r, i) => `${i === 0 ? 'M' : 'L'}${x(times[i]!).toFixed(1)},${y(r.temperatureC).toFixed(1)}`).join(' ');
  const ticks = [yMin, lo, hi, yMax];
  const excursions = rows.filter((r) => r.excursion).length;
  return (
    <figure className="temp-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${label}: ${rows.length} readings, ${excursions} outside ${lo}–${hi} °C`} preserveAspectRatio="none">
        <rect className="temp-band" x={pad.l} y={y(hi)} width={W - pad.l - pad.r} height={Math.max(0, y(lo) - y(hi))} />
        {ticks.map((v) => (
          <text key={v} className="temp-axis" x={2} y={y(v) + 3}>
            {v}°
          </text>
        ))}
        <path className="temp-line" d={path} />
        {rows.map((r, i) => (
          <circle key={`${r.recordedAt}-${i}`} className={r.excursion ? 'temp-point-excursion' : 'temp-point'} cx={x(times[i]!)} cy={y(r.temperatureC)} r={r.excursion ? 4 : 2.5}>
            <title>{`${formatDate(r.recordedAt)}: ${r.temperatureC.toFixed(1)} °C${r.excursion ? ' (excursion)' : ''}`}</title>
          </circle>
        ))}
      </svg>
      <figcaption className="small muted">
        Shaded band = safe range {lo}–{hi} °C; red points are excursions. Full values in the readings table.
      </figcaption>
    </figure>
  );
}

function Excursions({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const [status, setStatus] = useState<ExcursionStatus | ''>('OPEN');
  const list = useAsync(() => api.excursions(status || undefined), onSessionExpired, [status]);
  const action = useAction(onSessionExpired);
  const canAck = can(session.roles, 'excursionAcknowledge');
  const [ack, setAck] = useState<Excursion>();
  const [note, setNote] = useState('');

  async function acknowledge(e: FormEvent) {
    e.preventDefault();
    if (!ack || !note.trim()) return;
    const x = ack;
    const ok = await action.run(async () => {
      await api.acknowledgeExcursion(x.id, note.trim());
      return `Excursion on ${x.assetTag ?? x.equipmentId} acknowledged.`;
    });
    if (ok) {
      setAck(undefined);
      setNote('');
      list.reload();
    }
  }

  async function close(x: Excursion) {
    await action.run(async () => {
      await api.closeExcursion(x.id);
      list.reload();
      return `Excursion on ${x.assetTag ?? x.equipmentId} closed.`;
    });
  }

  const rows = list.data ?? [];
  return (
    <>
      <div className="filters">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value as ExcursionStatus | '')}>
              <SelectOptions values={EXCURSION_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No excursions.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} excursion{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Fridge</th>
                <th scope="col">Period</th>
                <th scope="col">Min / max °C</th>
                <th scope="col">Status</th>
                <th scope="col">Action taken</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.id}>
                  <th scope="row">
                    {x.assetTag ?? x.equipmentId}
                    <div className="small muted">{x.siteCode}</div>
                  </th>
                  <td className="small">
                    {formatDate(x.startedAt)} – {x.endedAt ? formatDate(x.endedAt) : 'ongoing'}
                  </td>
                  <td className="num">
                    {x.minC ?? '–'} / {x.maxC ?? '–'}
                  </td>
                  <td>
                    <StatusBadge status={x.status} />
                  </td>
                  <td className="small prewrap">
                    {x.action ?? '–'}
                    {x.acknowledgedBy && <div className="muted">{x.acknowledgedBy}</div>}
                  </td>
                  <td>
                    {canAck && x.status === 'OPEN' && (
                      <button type="button" className="small-button" onClick={() => { setNote(''); setAck(x); }} aria-label={`Acknowledge excursion on ${x.assetTag ?? x.equipmentId}`}>
                        Acknowledge…
                      </button>
                    )}
                    {canAck && x.status === 'ACKNOWLEDGED' && (
                      <button type="button" className="small-button secondary" disabled={action.busy} onClick={() => void close(x)} aria-label={`Close excursion on ${x.assetTag ?? x.equipmentId}`}>
                        Close
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {ack && (
        <Dialog title={`Acknowledge excursion – ${ack.assetTag ?? ack.equipmentId}`} onClose={() => setAck(undefined)}>
          <form className="form" onSubmit={acknowledge}>
            <Field label="Action taken" required hint="E.g. stock quarantined, manufacturer stability data checked, fridge serviced.">
              {(id) => <textarea id={id} rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={!note.trim() || action.busy}>
                Acknowledge
              </button>
              <button type="button" className="secondary" onClick={() => setAck(undefined)}>
                Cancel
              </button>
            </div>
            <ActionFeedback error={action.error} />
          </form>
        </Dialog>
      )}
    </>
  );
}
