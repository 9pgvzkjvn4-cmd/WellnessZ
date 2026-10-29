import { useState, type FormEvent } from 'react';
import { CAMPAIGN_TYPES, schedulingApi, type Appointment, type Campaign, type CampaignType, type Equipment, type Resource } from '../../api/scheduling';
import { can } from '../../auth/roles';
import { formatDay, humanise } from '../../components/format';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { addDays, campaignSlotCount, dateRange, hhmm, MAX_BULK_BOOK, parseEmployeeIds, pct, todayLocal, toCampaignInput, validateCampaignForm, type CampaignForm } from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { CalibrationBadge } from './common';
import { useAppointmentTypes, useSites } from './useSchedulingData';

/** `#/scheduling/campaigns` – campaign list and setup (E14-F02). */
export function Campaigns({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const list = useAsync(() => api.campaigns(), onSessionExpired);
  const [creating, setCreating] = useState(false);
  const canCreate = can(session.roles, 'campaigns');
  const rows = [...(list.data ?? [])].sort((a, b) => (a.days[0] ?? '').localeCompare(b.days[0] ?? ''));
  return (
    <section>
      <PageTitle sub="Surveillance, vaccination and mobile-clinic campaigns: set up days, slots, stations, device kit and staff, then bulk book.">Campaigns</PageTitle>
      {canCreate && !creating && (
        <div className="toolbar toolbar-left">
          <button type="button" onClick={() => setCreating(true)}>
            New campaign
          </button>
        </div>
      )}
      {creating && <CampaignSetup {...{ client, session, onSessionExpired }} onCancel={() => setCreating(false)} />}
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No campaigns yet.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} campaign{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Campaign</th>
                <th scope="col">Type</th>
                <th scope="col">Site</th>
                <th scope="col">Days</th>
                <th scope="col">Hours</th>
                <th scope="col">Target</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <th scope="row">
                    <a href={`#/scheduling/campaigns/${encodeURIComponent(c.id)}`}>{c.name}</a>
                  </th>
                  <td>{humanise(c.campaignType)}</td>
                  <td>{c.siteCode}</td>
                  <td className="small">
                    {formatDay(c.days[0])}
                    {c.days.length > 1 && ` – ${formatDay(c.days[c.days.length - 1])} (${c.days.length} days)`}
                  </td>
                  <td className="small">
                    {c.dayStart}–{c.dayEnd}, {c.slotMinutes} min × {c.stations}
                  </td>
                  <td className="num">{c.targetCount}</td>
                  <td>
                    <StatusBadge status={c.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function CampaignSetup({ client, onSessionExpired, onCancel }: ScreenProps & { onCancel: () => void }) {
  const api = schedulingApi(client);
  const { sites, siteCode } = useSites(client, onSessionExpired);
  const types = useAppointmentTypes(client, onSessionExpired);
  const start = addDays(todayLocal(), 14);
  const [f, setF] = useState<CampaignForm>({
    name: '',
    campaignType: 'SURVEILLANCE',
    siteCode: '',
    appointmentType: '',
    from: start,
    to: start,
    skipWeekends: true,
    dayStart: '08:00',
    dayEnd: '16:00',
    slotMinutes: '10',
    stations: '4',
    deviceKit: [],
    staff: [],
    targetCount: '',
  });
  const site = f.siteCode || siteCode;
  const equipment = useAsync(() => (site ? api.equipment({ siteCode: site }) : Promise.resolve([] as Equipment[])), onSessionExpired, [site]);
  const resources = useAsync(() => (site ? api.resources(site) : Promise.resolve([] as Resource[])), onSessionExpired, [site]);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const [created, setCreated] = useState<Campaign>();
  const today = todayLocal();
  const form = { ...f, siteCode: site };
  const err = (field: string) => errors.find((e) => e.field === field)?.message;
  const days = dateRange(form.from, form.to, form.skipWeekends);
  const expectedSlots = campaignSlotCount(days.length, form.dayStart, form.dayEnd, Number(form.slotMinutes) || 0, Number(form.stations) || 0);
  const lastDay = days[days.length - 1] ?? form.to;
  const kitProblems = (equipment.data ?? []).filter((e) => form.deviceKit.includes(e.id) && (e.status !== 'IN_SERVICE' || (!!e.calibrationDueOn && e.calibrationDueOn < lastDay)));

  function toggle(list: 'deviceKit' | 'staff', v: string) {
    const cur = f[list];
    setF({ ...f, [list]: cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v] });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const v = validateCampaignForm(form);
    setErrors(v);
    if (v.length) return;
    await action.run(async () => {
      const c = await api.createCampaign(toCampaignInput(form));
      setCreated(c);
    });
  }

  if (created) {
    return (
      <Alert kind="success">
        Campaign “{created.name}” created. <a href={`#/scheduling/campaigns/${encodeURIComponent(created.id)}`}>Open it to generate slots and book employees</a>.
      </Alert>
    );
  }

  return (
    <form className="form card" onSubmit={submit} noValidate aria-labelledby="cmp-h">
      <h2 id="cmp-h">New campaign</h2>
      <ErrorSummary errors={errors} />
      <div className="form-grid">
        <IdField id="cmp-name" label="Name" required error={err('cmp-name')}>
          {(d) => <input id="cmp-name" value={f.name} maxLength={120} aria-describedby={d} aria-invalid={!!err('cmp-name')} onChange={(e) => setF({ ...f, name: e.target.value })} />}
        </IdField>
        <IdField id="cmp-kind" label="Campaign type" required>
          {(d) => (
            <select id="cmp-kind" value={f.campaignType} aria-describedby={d} onChange={(e) => setF({ ...f, campaignType: e.target.value as CampaignType })}>
              <SelectOptions values={CAMPAIGN_TYPES} />
            </select>
          )}
        </IdField>
        <IdField id="cmp-site" label="Site" required error={err('cmp-site')}>
          {(d) => (
            <select id="cmp-site" value={site} aria-describedby={d} onChange={(e) => setF({ ...f, siteCode: e.target.value, deviceKit: [], staff: [] })}>
              <option value="">Choose…</option>
              {(sites.data ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name} ({s.code})
                </option>
              ))}
            </select>
          )}
        </IdField>
        <IdField id="cmp-type" label="Appointment type" required error={err('cmp-type')}>
          {(d) => (
            <select id="cmp-type" value={f.appointmentType} aria-describedby={d} aria-invalid={!!err('cmp-type')} onChange={(e) => {
              const t = types.data?.find((x) => x.code === e.target.value);
              setF({ ...f, appointmentType: e.target.value, slotMinutes: t ? String(t.durationMinutes) : f.slotMinutes });
            }}>
              <option value="">Choose…</option>
              {(types.data ?? []).map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
        </IdField>
        <IdField id="cmp-from" label="First day" required error={err('cmp-from')}>
          {(d) => <input id="cmp-from" type="date" value={f.from} aria-describedby={d} onChange={(e) => setF({ ...f, from: e.target.value, to: f.to < e.target.value ? e.target.value : f.to })} />}
        </IdField>
        <IdField id="cmp-to" label="Last day" required>
          {(d) => <input id="cmp-to" type="date" value={f.to} min={f.from} aria-describedby={d} onChange={(e) => setF({ ...f, to: e.target.value })} />}
        </IdField>
        <IdField id="cmp-start" label="Day start" required error={err('cmp-start')}>
          {(d) => <input id="cmp-start" type="time" value={f.dayStart} aria-describedby={d} onChange={(e) => setF({ ...f, dayStart: e.target.value })} />}
        </IdField>
        <IdField id="cmp-end" label="Day end" required>
          {(d) => <input id="cmp-end" type="time" value={f.dayEnd} aria-describedby={d} onChange={(e) => setF({ ...f, dayEnd: e.target.value })} />}
        </IdField>
        <IdField id="cmp-slot" label="Slot length (minutes)" required error={err('cmp-slot')}>
          {(d) => <input id="cmp-slot" type="number" min={5} max={240} value={f.slotMinutes} aria-describedby={d} onChange={(e) => setF({ ...f, slotMinutes: e.target.value })} />}
        </IdField>
        <IdField id="cmp-stations" label="Stations" required error={err('cmp-stations')}>
          {(d) => <input id="cmp-stations" type="number" min={1} max={50} value={f.stations} aria-describedby={d} onChange={(e) => setF({ ...f, stations: e.target.value })} />}
        </IdField>
        <IdField id="cmp-target" label="Target headcount" error={err('cmp-target')}>
          {(d) => <input id="cmp-target" type="number" min={0} value={f.targetCount} aria-describedby={d} onChange={(e) => setF({ ...f, targetCount: e.target.value })} />}
        </IdField>
      </div>
      <label className="checkbox">
        <input type="checkbox" checked={f.skipWeekends} onChange={(e) => setF({ ...f, skipWeekends: e.target.checked })} /> Skip weekends
      </label>
      <p className="small" aria-live="polite">
        {days.length} day{days.length === 1 ? '' : 's'} · {expectedSlots} slot{expectedSlots === 1 ? '' : 's'} expected (days × stations × slots per day)
        {f.targetCount && Number(f.targetCount) > expectedSlots && <strong className="text-red"> – fewer slots than the target</strong>}
      </p>
      <fieldset>
        <legend>Device kit (must be in service and calibrated on every day)</legend>
        {equipment.loading && <Loading />}
        {!!equipment.error && <ErrorAlert error={equipment.error} />}
        {equipment.data && equipment.data.length === 0 && <p className="muted small">No equipment registered at this site.</p>}
        <div className="checkbox-group">
          {(equipment.data ?? [])
            .filter((e) => e.category !== 'VACCINE_FRIDGE' || f.campaignType === 'VACCINATION')
            .map((e) => (
              <label key={e.id} className="checkbox">
                <input type="checkbox" checked={f.deviceKit.includes(e.id)} onChange={() => toggle('deviceKit', e.id)} /> {e.assetTag} – {humanise(e.category)}{' '}
                <CalibrationBadge state={e.calibrationState} dueOn={e.calibrationDueOn} today={today} />
                {e.status !== 'IN_SERVICE' && <StatusBadge status={e.status} />}
              </label>
            ))}
        </div>
        {kitProblems.length > 0 && (
          <Alert kind="warning">
            {kitProblems.map((k) => k.assetTag).join(', ')} {kitProblems.length === 1 ? 'is' : 'are'} out of service or due for calibration before the last campaign day – the
            booking service will reject the kit.
          </Alert>
        )}
      </fieldset>
      <fieldset>
        <legend>Staff</legend>
        <div className="checkbox-group">
          {(resources.data ?? [])
            .filter((r) => r.type === 'CLINICIAN')
            .map((r) => {
              const u = r.username ?? r.code;
              return (
                <label key={r.id} className="checkbox">
                  <input type="checkbox" checked={f.staff.includes(u)} onChange={() => toggle('staff', u)} /> {r.name}
                </label>
              );
            })}
        </div>
      </fieldset>
      <div className="actions">
        <button type="submit" disabled={action.busy}>
          Create campaign
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

/** `#/scheduling/campaigns/{id}` – generate slots, bulk book, status, attendance grid, performance (E14-F02). */
export function CampaignDetail({ client, session, onSessionExpired, campaignId }: ScreenProps & { campaignId: string }) {
  const api = schedulingApi(client);
  const c = useAsync(() => api.campaign(campaignId), onSessionExpired, [campaignId]);
  const { sites } = useSites(client, onSessionExpired);
  const action = useAction(onSessionExpired);
  const canManage = can(session.roles, 'campaignManage');
  const canAttend = can(session.roles, 'schedulingAttendance');
  const [ids, setIds] = useState('');
  const parsed = parseEmployeeIds(ids);

  if (c.loading && !c.data) return <Loading />;
  if (c.error) return <ErrorAlert error={c.error} />;
  const cmp = c.data;
  if (!cmp) return <Empty>Campaign not found.</Empty>;
  const p = cmp.performance;
  const tz = sites.data?.find((s) => s.code === cmp.siteCode)?.timeZone;

  async function generate() {
    await action.run(async () => {
      const r = await api.generateSlots(campaignId);
      c.reload();
      return `${r.slots} slot${r.slots === 1 ? '' : 's'} generated.`;
    });
  }

  async function setStatus(status: 'OPEN' | 'IN_PROGRESS' | 'CLOSED') {
    await action.run(async () => {
      await api.campaignStatus(campaignId, status);
      c.reload();
      return `Campaign status set to ${humanise(status).toLowerCase()}.`;
    });
  }

  async function book(e: FormEvent) {
    e.preventDefault();
    if (parsed.ids.length === 0 || parsed.tooMany) return;
    const ok = await action.run(async () => {
      const r = await api.bulkBook(campaignId, parsed.ids);
      c.reload();
      return `${r.booked} booked, ${r.waitlisted} waitlisted.`;
    });
    if (ok) setIds('');
  }

  const next: Partial<Record<Campaign['status'], 'OPEN' | 'IN_PROGRESS' | 'CLOSED'>> = { PLANNED: 'OPEN', OPEN: 'IN_PROGRESS', IN_PROGRESS: 'CLOSED' };
  const nextStatus = next[cmp.status];

  return (
    <section>
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <a href="#/scheduling/campaigns">Campaigns</a> › {cmp.name}
      </nav>
      <PageTitle sub={`${humanise(cmp.campaignType)} · ${cmp.siteCode} · ${cmp.days.length} day${cmp.days.length === 1 ? '' : 's'}`}>{cmp.name}</PageTitle>
      <div className="badge-row">
        <StatusBadge status={cmp.status} />
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      <div className="tiles" aria-label="Campaign performance">
        {(
          [
            ['Slots', p?.slots],
            ['Booked', p?.booked],
            ['Attended', p?.attended],
            ['No-shows', p?.noShow],
            ['Utilisation', pct(p?.utilisationPct)],
            ['Attendance', pct(p?.attendancePct)],
          ] as [string, number | string | undefined][]
        ).map(([k, v]) => (
          <div className="tile" key={k}>
            <h2>{k}</h2>
            <p className="tile-value">{v ?? '–'}</p>
          </div>
        ))}
      </div>
      <div className="grid-2">
        <div>
          <h2>Setup</h2>
          <Dl
            items={[
              ['Appointment type', cmp.appointmentType],
              ['Days', cmp.days.map((d) => formatDay(d)).join(', ')],
              ['Hours', `${cmp.dayStart}–${cmp.dayEnd}`],
              ['Slot length', `${cmp.slotMinutes} min`],
              ['Stations', cmp.stations],
              ['Device kit', cmp.deviceKit.join(', ')],
              ['Staff', cmp.staff.join(', ')],
              ['Target', cmp.targetCount],
            ]}
          />
          {canManage && cmp.status !== 'CLOSED' && (
            <div className="actions">
              <button type="button" className="secondary" disabled={action.busy} onClick={() => void generate()}>
                Generate slots
              </button>
              {nextStatus && (
                <button type="button" className="secondary" disabled={action.busy} onClick={() => void setStatus(nextStatus)}>
                  {nextStatus === 'OPEN' ? 'Open for booking' : nextStatus === 'IN_PROGRESS' ? 'Start campaign' : 'Close campaign'}
                </button>
              )}
            </div>
          )}
        </div>
        {canManage && cmp.status !== 'CLOSED' && (
          <form className="form" onSubmit={book} aria-labelledby="bulk-h">
            <h2 id="bulk-h">Bulk book</h2>
            <Field label="Employee IDs" hint={`Paste up to ${MAX_BULK_BOOK} ids – one per line, or separated by commas / spaces. Anyone without a free slot is waitlisted.`}>
              {(id) => <textarea id={id} className="ids-input" rows={6} value={ids} onChange={(e) => setIds(e.target.value)} spellCheck={false} />}
            </Field>
            <p className="small" aria-live="polite">
              {parsed.ids.length} unique id{parsed.ids.length === 1 ? '' : 's'}
              {parsed.duplicates > 0 && `, ${parsed.duplicates} duplicate${parsed.duplicates === 1 ? '' : 's'} ignored`}
              {parsed.invalid.length > 0 && (
                <span className="text-red">
                  , {parsed.invalid.length} not valid ({parsed.invalid.slice(0, 5).join(', ')}
                  {parsed.invalid.length > 5 ? '…' : ''}) – ignored
                </span>
              )}
              {parsed.tooMany && <strong className="text-red"> – more than {MAX_BULK_BOOK}; split the list</strong>}
            </p>
            <button type="submit" disabled={action.busy || parsed.ids.length === 0 || parsed.tooMany}>
              Book {parsed.ids.length || ''} employee{parsed.ids.length === 1 ? '' : 's'}
            </button>
          </form>
        )}
      </div>
      {canAttend && <AttendanceGrid client={client} onSessionExpired={onSessionExpired} campaign={cmp} timeZone={tz} onSaved={c.reload} />}
    </section>
  );
}

function AttendanceGrid({ client, onSessionExpired, campaign, timeZone, onSaved }: { client: ScreenProps['client']; onSessionExpired: () => void; campaign: Campaign; timeZone?: string; onSaved: () => void }) {
  const api = schedulingApi(client);
  const today = todayLocal();
  const [day, setDay] = useState(campaign.days.includes(today) ? today : (campaign.days[0] ?? today));
  const list = useAsync(() => api.appointments({ siteCode: campaign.siteCode, date: day }).then((l) => (l ?? []).filter((a) => a.campaignId === campaign.id)), onSessionExpired, [day, campaign.id]);
  const [marks, setMarks] = useState<Record<string, 'ATTENDED' | 'NO_SHOW'>>({});
  const action = useAction(onSessionExpired);
  const rows: Appointment[] = [...(list.data ?? [])].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  const pending = Object.entries(marks).map(([appointmentId, status]) => ({ appointmentId, status }));

  async function save() {
    if (pending.length === 0) return;
    const ok = await action.run(async () => {
      await api.campaignAttendance(campaign.id, pending);
      return `Attendance saved for ${pending.length} appointment${pending.length === 1 ? '' : 's'}.`;
    });
    if (ok) {
      setMarks({});
      list.reload();
      onSaved();
    }
  }

  function markAll(status: 'ATTENDED' | 'NO_SHOW') {
    const m: Record<string, 'ATTENDED' | 'NO_SHOW'> = {};
    for (const a of rows) if (a.status !== 'CANCELLED' && a.status !== 'ATTENDED' && a.status !== 'NO_SHOW') m[a.id] = status;
    setMarks(m);
  }

  return (
    <section aria-labelledby="att-h">
      <h2 id="att-h">Attendance capture</h2>
      <div className="filters">
        <Field label="Campaign day">
          {(id) => (
            <select id={id} value={day} onChange={(e) => { setMarks({}); setDay(e.target.value); }}>
              {campaign.days.map((d) => (
                <option key={d} value={d}>
                  {formatDay(d)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <div className="actions">
          <button type="button" className="secondary small-button" onClick={() => markAll('ATTENDED')} disabled={rows.length === 0}>
            Mark all open as attended
          </button>
          <button type="button" className="small-button" onClick={() => void save()} disabled={action.busy || pending.length === 0}>
            Save attendance ({pending.length})
          </button>
        </div>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No campaign appointments on this day.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Attendance for {formatDay(day)}</caption>
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Employee</th>
                <th scope="col">Status</th>
                <th scope="col">Attended</th>
                <th scope="col">No-show</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => {
                const locked = a.status === 'CANCELLED' || a.status === 'ATTENDED' || a.status === 'NO_SHOW';
                const name = a.displayName ?? a.employeeId;
                return (
                  <tr key={a.id}>
                    <td className="num">{hhmm(a.startsAt, timeZone)}</td>
                    <th scope="row">
                      {name} <span className="small muted mono">{a.employeeId}</span>
                    </th>
                    <td>
                      <StatusBadge status={a.status} />
                      {a.ticket && <span className="small"> {a.ticket}</span>}
                    </td>
                    <td>
                      <input type="radio" name={`att-${a.id}`} disabled={locked} checked={marks[a.id] === 'ATTENDED'} onChange={() => setMarks({ ...marks, [a.id]: 'ATTENDED' })} aria-label={`${name} attended`} />
                    </td>
                    <td>
                      <input type="radio" name={`att-${a.id}`} disabled={locked} checked={marks[a.id] === 'NO_SHOW'} onChange={() => setMarks({ ...marks, [a.id]: 'NO_SHOW' })} aria-label={`${name} did not attend`} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

