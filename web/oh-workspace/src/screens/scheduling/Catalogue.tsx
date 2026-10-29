import { useState, type FormEvent } from 'react';
import { chargingExportUrl, EQUIPMENT_CATEGORIES, ROOM_TYPES, schedulingApi, type AppointmentType, type DiaryTemplate, type Resource } from '../../api/scheduling';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { formatAmount, validateTemplates, WEEKDAYS } from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { LocalTabs, SiteSelect } from './common';
import { useSites } from './useSchedulingData';

type Tab = 'types' | 'usage' | 'templates';

/** `#/scheduling/catalogue` – appointment types & service catalogue, monthly charging, site diary templates (E14-F01, BL-010). */
export function Catalogue(props: ScreenProps) {
  const roles = props.session.roles;
  const tabs = (
    [
      { id: 'types', label: 'Appointment types & charges', show: true },
      { id: 'usage', label: 'Monthly charging', show: can(roles, 'chargingUsage') },
      { id: 'templates', label: 'Site templates', show: true },
    ] as { id: Tab; label: string; show: boolean }[]
  ).filter((t) => t.show);
  const [tab, setTab] = useState<Tab>('types');
  return (
    <section>
      <PageTitle sub="Appointment types with durations, resources and internal charge codes; weekly diary templates per site.">Service catalogue</PageTitle>
      <LocalTabs label="Catalogue views" tabs={tabs} current={tab} onChange={setTab} />
      {tab === 'types' && <Types {...props} />}
      {tab === 'usage' && <Usage {...props} />}
      {tab === 'templates' && <Templates {...props} />}
    </section>
  );
}

function Types({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const list = useAsync(() => api.serviceCatalogue(), onSessionExpired);
  const canWrite = can(session.roles, 'appointmentTypesWrite');
  const [editing, setEditing] = useState<AppointmentType | 'new'>();
  const [message, setMessage] = useState<string>();
  const rows = [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  return (
    <>
      {canWrite && (
        <div className="toolbar toolbar-left">
          <button type="button" onClick={() => setEditing('new')}>
            New appointment type
          </button>
        </div>
      )}
      <ActionFeedback message={message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No appointment types configured.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} appointment type{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Type</th>
                <th scope="col">Duration</th>
                <th scope="col">Clinician roles</th>
                <th scope="col">Room / device</th>
                <th scope="col">Options</th>
                <th scope="col">Charge code</th>
                <th scope="col">Internal price</th>
                {canWrite && (
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.code} className={t.active === false ? 'muted' : undefined}>
                  <th scope="row">
                    {t.name}
                    <div className="small muted mono">{t.code}</div>
                  </th>
                  <td className="num">{t.durationMinutes} min</td>
                  <td className="small">{(t.clinicianRoles ?? []).map(humanise).join(', ') || 'Any'}</td>
                  <td className="small">{[t.roomType && humanise(t.roomType), t.deviceCategory && humanise(t.deviceCategory)].filter(Boolean).join(' · ') || '–'}</td>
                  <td className="small">{[t.telehealth && 'Telehealth', t.selfBookable && 'Self-bookable', t.active === false && 'Inactive'].filter(Boolean).join(' · ') || '–'}</td>
                  <td className="mono">{t.chargeCode ?? '–'}</td>
                  <td className="num">{formatAmount(t.internalPrice)}</td>
                  {canWrite && (
                    <td>
                      <button type="button" className="small-button secondary" onClick={() => setEditing(t)} aria-label={`Edit ${t.name}`}>
                        Edit
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <TypeDialog
          client={client}
          onSessionExpired={onSessionExpired}
          item={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(undefined)}
          onDone={(m) => {
            setMessage(m);
            list.reload();
          }}
        />
      )}
    </>
  );
}

const CLINICIAN_ROLE_OPTIONS = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'PSYCH_CLINICIAN'] as const;

function TypeDialog({ client, onSessionExpired, item, onClose, onDone }: { client: ScreenProps['client']; onSessionExpired: () => void; item?: AppointmentType; onClose: () => void; onDone: (m: string) => void }) {
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({
    code: item?.code ?? '',
    name: item?.name ?? '',
    durationMinutes: String(item?.durationMinutes ?? 30),
    clinicianRoles: item?.clinicianRoles ?? [],
    roomType: item?.roomType ?? '',
    deviceCategory: item?.deviceCategory ?? '',
    telehealth: !!item?.telehealth,
    selfBookable: !!item?.selfBookable,
    chargeCode: item?.chargeCode ?? '',
    internalPrice: item?.internalPrice?.toString() ?? '',
    active: item?.active !== false,
  });
  const duration = Number(f.durationMinutes);
  const price = f.internalPrice.trim() === '' ? undefined : Number(f.internalPrice.replace(',', '.'));
  const valid = /^[A-Z][A-Z0-9_]{1,39}$/.test(f.code) && !!f.name.trim() && Number.isInteger(duration) && duration >= 5 && duration <= 480 && (price === undefined || (Number.isFinite(price) && price >= 0));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const body: AppointmentType = {
      code: f.code,
      name: f.name.trim(),
      durationMinutes: duration,
      clinicianRoles: f.clinicianRoles,
      roomType: (f.roomType || null) as AppointmentType['roomType'],
      deviceCategory: (f.deviceCategory || null) as AppointmentType['deviceCategory'],
      telehealth: f.telehealth,
      selfBookable: f.selfBookable,
      chargeCode: f.chargeCode.trim() || null,
      internalPrice: price ?? null,
      active: f.active,
    };
    const ok = await action.run(async () => {
      await schedulingApi(client).saveAppointmentType(body);
      onDone(`${body.name} saved.`);
    });
    if (ok) onClose();
  }

  return (
    <Dialog title={item ? `Edit ${item.name}` : 'New appointment type'} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <div className="form-grid">
          <Field label="Code" required hint="Upper case, e.g. GENERAL_OH">
            {(id) => <input id={id} value={f.code} readOnly={!!item} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} />}
          </Field>
          <Field label="Name" required>{(id) => <input id={id} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</Field>
          <Field label="Duration (minutes)" required>{(id) => <input id={id} type="number" min={5} max={480} value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })} />}</Field>
          <Field label="Room type">
            {(id) => (
              <select id={id} value={f.roomType} onChange={(e) => setF({ ...f, roomType: e.target.value })}>
                <SelectOptions values={ROOM_TYPES} empty="Any" />
              </select>
            )}
          </Field>
          <Field label="Device category">
            {(id) => (
              <select id={id} value={f.deviceCategory} onChange={(e) => setF({ ...f, deviceCategory: e.target.value })}>
                <SelectOptions values={EQUIPMENT_CATEGORIES} empty="None" />
              </select>
            )}
          </Field>
          <Field label="Charge code">{(id) => <input id={id} value={f.chargeCode} onChange={(e) => setF({ ...f, chargeCode: e.target.value })} />}</Field>
          <Field label="Internal price">{(id) => <input id={id} inputMode="decimal" value={f.internalPrice} onChange={(e) => setF({ ...f, internalPrice: e.target.value })} />}</Field>
        </div>
        <fieldset>
          <legend>Clinician roles</legend>
          <div className="checkbox-group">
            {CLINICIAN_ROLE_OPTIONS.map((r) => (
              <label key={r} className="checkbox">
                <input
                  type="checkbox"
                  checked={f.clinicianRoles.includes(r)}
                  onChange={() => setF({ ...f, clinicianRoles: f.clinicianRoles.includes(r) ? f.clinicianRoles.filter((x) => x !== r) : [...f.clinicianRoles, r] })}
                />{' '}
                {humanise(r)}
              </label>
            ))}
          </div>
        </fieldset>
        <label className="checkbox">
          <input type="checkbox" checked={f.telehealth} onChange={(e) => setF({ ...f, telehealth: e.target.checked })} /> Telehealth
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={f.selfBookable} onChange={(e) => setF({ ...f, selfBookable: e.target.checked })} /> Employees can self-book
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Active
        </label>
        <div className="actions">
          <button type="submit" disabled={!valid || action.busy}>
            Save
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

function Usage({ client, onSessionExpired }: ScreenProps) {
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [applied, setApplied] = useState(month);
  const usage = useAsync(() => schedulingApi(client).chargingUsage(applied), onSessionExpired, [applied]);
  const rows = usage.data ?? [];
  const total = rows.reduce((s, r) => s + (r.amount ?? 0), 0);
  const appts = rows.reduce((s, r) => s + (r.appointments ?? 0), 0);
  return (
    <>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          if (/^\d{4}-\d{2}$/.test(month)) setApplied(month);
        }}
      >
        <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
        <button type="submit">Show</button>
        <a className="button secondary" href={chargingExportUrl(applied)} download>
          Export CSV for finance
        </a>
      </form>
      {usage.loading && <Loading />}
      {!!usage.error && <ErrorAlert error={usage.error} />}
      {usage.data && rows.length === 0 && <Empty>No chargeable appointments in {applied}.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Charging usage {applied}</caption>
            <thead>
              <tr>
                <th scope="col">Subsidiary</th>
                <th scope="col">Charge code</th>
                <th scope="col">Service</th>
                <th scope="col">Appointments</th>
                <th scope="col">Amount</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.subsidiaryCode}-${r.chargeCode}-${r.service}`}>
                  <th scope="row">{r.subsidiaryCode}</th>
                  <td className="mono">{r.chargeCode}</td>
                  <td>{r.service}</td>
                  <td className="num">{r.appointments}</td>
                  <td className="num">{formatAmount(r.amount)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colSpan={3}>
                  Total
                </th>
                <td className="num">{appts}</td>
                <td className="num">{formatAmount(total)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </>
  );
}

const EMPTY_ROW: DiaryTemplate = { weekday: 1, start: '08:00', end: '16:00', clinicianUsername: '', roomCode: '', appointmentTypes: [], telehealth: false };

function Templates({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites, siteCode, setSiteCode } = useSites(client, onSessionExpired);
  const canWrite = can(session.roles, 'siteTemplatesWrite');
  const tpl = useAsync(() => (siteCode ? api.templates(siteCode) : Promise.resolve([] as DiaryTemplate[])), onSessionExpired, [siteCode]);
  const resources = useAsync(() => (siteCode ? api.resources(siteCode) : Promise.resolve([] as Resource[])), onSessionExpired, [siteCode]);
  const types = useAsync(() => api.appointmentTypes(), onSessionExpired);
  const [draft, setDraft] = useState<{ site: string; rows: DiaryTemplate[] }>();
  const action = useAction(onSessionExpired);
  const rows = draft?.site === siteCode ? draft.rows : (tpl.data ?? []);
  const dirty = draft?.site === siteCode;
  const errors = dirty ? validateTemplates(rows) : [];
  const clinicians = (resources.data ?? []).filter((r) => r.type === 'CLINICIAN');
  const rooms = (resources.data ?? []).filter((r) => r.type === 'ROOM');

  const set = (i: number, patch: Partial<DiaryTemplate>) => setDraft({ site: siteCode, rows: rows.map((r, j) => (j === i ? { ...r, ...patch } : r)) });

  async function save() {
    if (errors.length) return;
    const ok = await action.run(async () => {
      await api.saveTemplates(
        siteCode,
        rows.map((r) => ({ ...r, roomCode: r.roomCode || null })),
      );
      return `Templates for ${siteCode} saved (${rows.length} session${rows.length === 1 ? '' : 's'} per week).`;
    });
    if (ok) {
      setDraft(undefined);
      tpl.reload();
    }
  }

  const sorted = rows.map((r, i) => ({ r, i })).sort((a, b) => a.r.weekday - b.r.weekday || a.r.start.localeCompare(b.r.start));
  return (
    <>
      <div className="filters">
        <SiteSelect sites={sites.data} value={siteCode} onChange={(s) => setSiteCode(s)} />
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {tpl.loading && <Loading />}
      {!!tpl.error && <ErrorAlert error={tpl.error} />}
      {errors.length > 0 && (
        <Alert kind="error">
          <ul className="plain-list">
            {errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        </Alert>
      )}
      {tpl.data && rows.length === 0 && <Empty>No weekly sessions defined for this site.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Weekly diary template – {siteCode}</caption>
            <thead>
              <tr>
                <th scope="col">Weekday</th>
                <th scope="col">Start</th>
                <th scope="col">End</th>
                <th scope="col">Clinician</th>
                <th scope="col">Room</th>
                <th scope="col">Appointment types</th>
                <th scope="col">Telehealth</th>
                {canWrite && (
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {sorted.map(({ r, i }) => {
                const n = `row ${i + 1}`;
                return (
                  <tr key={i}>
                    <td>
                      {canWrite ? (
                        <select aria-label={`Weekday, ${n}`} value={r.weekday} onChange={(e) => set(i, { weekday: Number(e.target.value) })}>
                          {WEEKDAYS.map((d, k) => (
                            <option key={d} value={k + 1}>
                              {d}
                            </option>
                          ))}
                        </select>
                      ) : (
                        WEEKDAYS[r.weekday - 1]
                      )}
                    </td>
                    <td>{canWrite ? <input aria-label={`Start, ${n}`} type="time" value={r.start} onChange={(e) => set(i, { start: e.target.value })} /> : r.start}</td>
                    <td>{canWrite ? <input aria-label={`End, ${n}`} type="time" value={r.end} onChange={(e) => set(i, { end: e.target.value })} /> : r.end}</td>
                    <td>
                      {canWrite ? (
                        <select aria-label={`Clinician, ${n}`} value={r.clinicianUsername} onChange={(e) => set(i, { clinicianUsername: e.target.value })}>
                          <option value="">Choose…</option>
                          {clinicians.map((c) => (
                            <option key={c.id} value={c.username ?? c.code}>
                              {c.name}
                            </option>
                          ))}
                          {r.clinicianUsername && !clinicians.some((c) => (c.username ?? c.code) === r.clinicianUsername) && <option value={r.clinicianUsername}>{r.clinicianUsername}</option>}
                        </select>
                      ) : (
                        r.clinicianUsername
                      )}
                    </td>
                    <td>
                      {canWrite ? (
                        <select aria-label={`Room, ${n}`} value={r.roomCode ?? ''} onChange={(e) => set(i, { roomCode: e.target.value })}>
                          <option value="">None</option>
                          {rooms.map((c) => (
                            <option key={c.id} value={c.code}>
                              {c.name} ({c.code})
                            </option>
                          ))}
                        </select>
                      ) : (
                        (r.roomCode ?? '–')
                      )}
                    </td>
                    <td>
                      {canWrite ? (
                        <select
                          multiple
                          size={3}
                          aria-label={`Appointment types, ${n}`}
                          value={r.appointmentTypes}
                          onChange={(e) => set(i, { appointmentTypes: Array.from(e.target.selectedOptions).map((o) => o.value) })}
                        >
                          {(types.data ?? []).map((t) => (
                            <option key={t.code} value={t.code}>
                              {t.name}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <span className="small">{r.appointmentTypes.join(', ') || 'All'}</span>
                      )}
                    </td>
                    <td>
                      {canWrite ? (
                        <input type="checkbox" aria-label={`Telehealth, ${n}`} checked={r.telehealth} onChange={(e) => set(i, { telehealth: e.target.checked })} />
                      ) : r.telehealth ? (
                        'Yes'
                      ) : (
                        'No'
                      )}
                    </td>
                    {canWrite && (
                      <td>
                        <button type="button" className="small-button secondary" onClick={() => setDraft({ site: siteCode, rows: rows.filter((_, j) => j !== i) })} aria-label={`Remove ${n}`}>
                          Remove
                        </button>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {canWrite && siteCode && (
        <div className="actions">
          <button type="button" className="secondary" onClick={() => setDraft({ site: siteCode, rows: [...rows, { ...EMPTY_ROW }] })}>
            Add session
          </button>
          <button type="button" onClick={() => void save()} disabled={!dirty || errors.length > 0 || action.busy}>
            Save templates
          </button>
          {dirty && (
            <button type="button" className="secondary" onClick={() => setDraft(undefined)}>
              Discard changes
            </button>
          )}
        </div>
      )}
    </>
  );
}
