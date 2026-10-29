import { useState, type DragEvent, type FormEvent, type KeyboardEvent } from 'react';
import { schedulingApi, type Appointment, type AppointmentType, type Diary as DiaryData, type MoveRequest, type Resource } from '../../api/scheduling';
import { SessionExpiredError } from '../../auth/AuthClient';
import { can } from '../../auth/roles';
import { formatDay, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import {
  addDays,
  APPOINTMENT_DRAG_MIME,
  appointmentMinutes,
  classifyMoveError,
  decodeAppointmentDrag,
  diaryColumns,
  diaryWindow,
  encodeAppointmentDrag,
  formatMinutes,
  hhmm,
  layoutLanes,
  snapMinutes,
  type DiaryColumn as Column,
  todayLocal,
  weekDays,
  zonedToUtcIso,
} from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { AppointmentDialog, BookDialog } from './AppointmentDialogs';
import { LocalTabs, SiteSelect } from './common';
import { useAppointmentTypes, useSites } from './useSchedulingData';

const PX_PER_MIN = 1.6;
const KEY_STEP = 15;

/** `#/scheduling/diary` – day / week diary per site and clinician (E14-F01). */
export function Diary({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites, siteCode, setSiteCode, site } = useSites(client, onSessionExpired);
  const tz = site?.timeZone;
  const [date, setDate] = useState(() => todayLocal());
  const [view, setView] = useState<'day' | 'week'>('day');
  const [clinician, setClinician] = useState('');
  const canBook = can(session.roles, 'schedulingBook');
  const canAttend = can(session.roles, 'schedulingAttendance');
  const canTelehealth = can(session.roles, 'telehealthLaunch');

  const day = useAsync(() => (siteCode && view === 'day' ? api.diary({ siteCode, date, clinician: clinician || undefined }) : Promise.resolve(undefined)), onSessionExpired, [siteCode, date, clinician, view]);
  const week = useAsync(
    () => (siteCode && view === 'week' ? Promise.all(weekDays(date).map((d) => api.diary({ siteCode, date: d, clinician: clinician || undefined }))) : Promise.resolve(undefined)),
    onSessionExpired,
    [siteCode, date, clinician, view],
  );
  const resources = useAsync(() => (siteCode ? api.resources(siteCode) : Promise.resolve([] as Resource[])), onSessionExpired, [siteCode]);
  const types = useAppointmentTypes(client, onSessionExpired);
  const [booking, setBooking] = useState(false);
  const [selected, setSelected] = useState<Appointment>();
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'error' | 'warning'; text: string }>();
  const [dropCol, setDropCol] = useState<string>();
  const [showWaitlist, setShowWaitlist] = useState(false);

  const reload = () => (view === 'day' ? day.reload() : week.reload());
  const done = (text: string) => {
    setFeedback({ kind: 'success', text });
    reload();
  };

  /** Shared by drag-and-drop, keyboard shortcuts and the Move dialog. Handles 409 stale / resource_conflict. */
  async function doMove(a: Appointment, body: MoveRequest): Promise<boolean> {
    setFeedback(undefined);
    try {
      const moved = await api.move(a.id, body);
      setFeedback({ kind: 'success', text: `Moved ${a.displayName ?? a.employeeId} to ${hhmm(moved?.startsAt ?? body.startsAt, tz)}${body.clinicianUsername ? ` with ${body.clinicianUsername}` : ''}.` });
      reload();
      return true;
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        onSessionExpired();
        return false;
      }
      const f = classifyMoveError(e);
      setFeedback({ kind: f.kind === 'stale' ? 'warning' : 'error', text: f.message });
      reload();
      return false;
    }
  }

  const allTypes: AppointmentType[] = types.data ?? [];
  const res = resources.data ?? [];
  const clinicianOptions = res.filter((r) => r.type === 'CLINICIAN');

  return (
    <section>
      <PageTitle sub="Sessions and appointments per site and clinician. Drag an appointment to move it, or focus it and press Alt + arrow keys (↑↓ 15 minutes, ←→ clinician).">
        Clinic diary
      </PageTitle>
      <div className="filters">
        <SiteSelect sites={sites.data} value={siteCode} onChange={setSiteCode} />
        <Field label="Date">{(id) => <input id={id} type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />}</Field>
        <Field label="Clinician">
          {(id) => (
            <select id={id} value={clinician} onChange={(e) => setClinician(e.target.value)}>
              <option value="">All clinicians</option>
              {clinicianOptions.map((r) => (
                <option key={r.id} value={r.username ?? r.code}>
                  {r.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <div className="actions">
          <button type="button" className="secondary small-button" onClick={() => setDate(addDays(date, view === 'week' ? -7 : -1))} aria-label={view === 'week' ? 'Previous week' : 'Previous day'}>
            ‹
          </button>
          <button type="button" className="secondary small-button" onClick={() => setDate(todayLocal())}>
            Today
          </button>
          <button type="button" className="secondary small-button" onClick={() => setDate(addDays(date, view === 'week' ? 7 : 1))} aria-label={view === 'week' ? 'Next week' : 'Next day'}>
            ›
          </button>
        </div>
      </div>
      <div className="toolbar toolbar-left">
        <LocalTabs label="Diary view" tabs={[{ id: 'day', label: 'Day' }, { id: 'week', label: 'Week' }] as const} current={view} onChange={setView} />
        {canBook && (
          <button type="button" onClick={() => setBooking(true)} disabled={!siteCode}>
            Book appointment
          </button>
        )}
        {canBook && (
          <button type="button" className="secondary" aria-expanded={showWaitlist} onClick={() => setShowWaitlist(!showWaitlist)}>
            {showWaitlist ? 'Hide waitlist' : 'Waitlist'}
          </button>
        )}
      </div>
      <div aria-live="polite">{feedback && <Alert kind={feedback.kind}>{feedback.text}</Alert>}</div>
      {!!sites.error && <ErrorAlert error={sites.error} />}
      {sites.data && sites.data.length === 0 && <Empty>No clinic sites are configured.</Empty>}

      <div className={showWaitlist ? 'grid-2 wide-left' : undefined}>
        <div>
          {view === 'day' && (
            <>
              {day.loading && <Loading label="Loading diary…" />}
              {!!day.error && <ErrorAlert error={day.error} />}
              {day.data && (
                <DayGrid
                  diary={day.data}
                  timeZone={tz}
                  canMove={canBook}
                  dropCol={dropCol}
                  setDropCol={setDropCol}
                  selectedId={selected?.id}
                  onOpen={setSelected}
                  onMove={(a, body) => void doMove(a, body)}
                />
              )}
            </>
          )}
          {view === 'week' && (
            <>
              {week.loading && <Loading label="Loading week…" />}
              {!!week.error && <ErrorAlert error={week.error} />}
              {week.data && <WeekGrid days={week.data} timeZone={tz} onOpen={setSelected} />}
            </>
          )}
        </div>
        {showWaitlist && canBook && <WaitlistPanel {...{ client, onSessionExpired }} siteCode={siteCode} types={allTypes} />}
      </div>

      {booking && siteCode && (
        <BookDialog client={client} onSessionExpired={onSessionExpired} timeZone={tz} siteCode={siteCode} date={date} types={allTypes} resources={res} onClose={() => setBooking(false)} onDone={done} />
      )}
      {selected && (
        <AppointmentDialog
          client={client}
          onSessionExpired={onSessionExpired}
          timeZone={tz}
          appointment={selected}
          resources={res}
          canBook={canBook}
          canAttend={canAttend}
          canTelehealth={canTelehealth}
          onMove={doMove}
          onClose={() => setSelected(undefined)}
          onDone={done}
        />
      )}
    </section>
  );
}

function apptLabel(a: Appointment, tz?: string): string {
  return `${hhmm(a.startsAt, tz)} ${a.displayName ?? a.employeeId}, ${a.appointmentTypeName ?? humanise(a.appointmentType)}, ${humanise(a.status)}`;
}

function DayGrid({
  diary,
  timeZone,
  canMove,
  dropCol,
  setDropCol,
  selectedId,
  onOpen,
  onMove,
}: {
  diary: DiaryData;
  timeZone?: string;
  canMove: boolean;
  dropCol?: string;
  setDropCol: (k: string | undefined) => void;
  selectedId?: string;
  onOpen: (a: Appointment) => void;
  onMove: (a: Appointment, body: MoveRequest) => void;
}) {
  const cols = diaryColumns(diary, timeZone);
  if (cols.length === 0) return <Empty>No sessions or appointments on {formatDay(diary.date)}.</Empty>;
  const ranges = cols.flatMap((c) => [...c.sessions, ...c.appointments.map((a) => appointmentMinutes(a, timeZone))]);
  const [lo, hi] = diaryWindow(ranges);
  const height = (hi - lo) * PX_PER_MIN;
  const hours = Array.from({ length: (hi - lo) / 60 + 1 }, (_, i) => lo + i * 60);
  const movable = (a: Appointment) => canMove && (a.status === 'BOOKED' || a.status === 'CHECKED_IN');

  function onDrop(e: DragEvent<HTMLDivElement>, col: Column) {
    e.preventDefault();
    setDropCol(undefined);
    const p = decodeAppointmentDrag(e.dataTransfer.getData(APPOINTMENT_DRAG_MIME));
    const a = p && diary.appointments.find((x) => x.id === p.id);
    if (!p || !a) return;
    const y = e.clientY - e.currentTarget.getBoundingClientRect().top;
    const m = snapMinutes(lo + y / PX_PER_MIN - p.grabOffset, 5, lo, hi);
    const startsAt = zonedToUtcIso(diary.date, m, timeZone);
    if (startsAt === new Date(a.startsAt).toISOString() && (col.key || null) === (a.clinicianUsername ?? null)) return;
    onMove(a, { startsAt, clinicianUsername: col.key || undefined, version: a.version });
  }

  function onKey(e: KeyboardEvent<HTMLButtonElement>, a: Appointment, colIndex: number) {
    if (!e.altKey || !movable(a)) return;
    const { start } = appointmentMinutes(a, timeZone);
    if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      e.preventDefault();
      const m = start + (e.key === 'ArrowUp' ? -KEY_STEP : KEY_STEP);
      if (m < 0 || m >= 24 * 60) return;
      onMove(a, { startsAt: zonedToUtcIso(diary.date, m, timeZone), clinicianUsername: a.clinicianUsername ?? undefined, version: a.version });
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      e.preventDefault();
      const target = cols[colIndex + (e.key === 'ArrowLeft' ? -1 : 1)];
      if (!target || !target.key) return;
      onMove(a, { startsAt: a.startsAt, clinicianUsername: target.key, version: a.version });
    }
  }

  return (
    <div className="diary" role="group" aria-label={`Diary for ${formatDay(diary.date)}`} style={{ gridTemplateColumns: `4rem repeat(${cols.length}, minmax(11rem, 1fr))` }}>
      <div className="diary-head" aria-hidden="true" />
      {cols.map((c) => (
        <div key={`h-${c.key}`} className="diary-head">
          {c.label}
          <div className="small muted">{c.sessions.map((s) => `${hhmm(String(Math.floor(s.start / 60)).padStart(2, '0') + ':' + String(s.start % 60).padStart(2, '0'))}–${hhmm(String(Math.floor(s.end / 60)).padStart(2, '0') + ':' + String(s.end % 60).padStart(2, '0'))}${s.roomCode ? ` ${s.roomCode}` : ''}`).join(', ') || 'No session'}</div>
        </div>
      ))}
      <div className="diary-axis" style={{ height }} aria-hidden="true">
        {hours.map((h) => (
          <span key={h} className="diary-hour" style={{ top: (h - lo) * PX_PER_MIN }}>
            {formatMinutes(h)}
          </span>
        ))}
      </div>
      {cols.map((c, ci) => {
        const lanes = layoutLanes(c.appointments.map((a) => ({ id: a.id, ...appointmentMinutes(a, timeZone) })));
        const sorted = [...c.appointments].sort((x, y) => x.startsAt.localeCompare(y.startsAt));
        return (
          <div
            key={`c-${c.key}`}
            className={`diary-col${dropCol === c.key ? ' drop-active' : ''}`}
            style={{ height }}
            role="list"
            aria-label={`${c.label}: ${c.appointments.length} appointment${c.appointments.length === 1 ? '' : 's'}`}
            onDragOver={(e) => {
              if (!canMove || !Array.from(e.dataTransfer.types).includes(APPOINTMENT_DRAG_MIME)) return;
              e.preventDefault();
              e.dataTransfer.dropEffect = 'move';
              if (dropCol !== c.key) setDropCol(c.key);
            }}
            onDragLeave={() => setDropCol(undefined)}
            onDrop={(e) => onDrop(e, c)}
          >
            {hours.map((h) => (
              <span key={h} className="diary-hour" style={{ top: (h - lo) * PX_PER_MIN }} aria-hidden="true" />
            ))}
            {c.sessions.map((s, i) => (
              <span key={i} className="diary-session" style={{ top: (s.start - lo) * PX_PER_MIN, height: (s.end - s.start) * PX_PER_MIN }} aria-hidden="true" />
            ))}
            {sorted.map((a) => {
              const m = appointmentMinutes(a, timeZone);
              const l = lanes.get(a.id) ?? { lane: 0, lanes: 1 };
              const canDrag = movable(a);
              return (
                <div role="listitem" key={a.id}>
                  <button
                    type="button"
                    className={`appt appt-${a.status.toLowerCase().replace(/_/g, '-')}${selectedId === a.id ? ' appt-selected' : ''}`}
                    style={{ top: (m.start - lo) * PX_PER_MIN, height: Math.max(22, (m.end - m.start) * PX_PER_MIN - 2), left: `${(l.lane / l.lanes) * 100}%`, width: `${100 / l.lanes}%` }}
                    draggable={canDrag}
                    aria-label={apptLabel(a, timeZone)}
                    aria-keyshortcuts={canDrag ? 'Alt+ArrowUp Alt+ArrowDown Alt+ArrowLeft Alt+ArrowRight' : undefined}
                    onClick={() => onOpen(a)}
                    onKeyDown={(e) => onKey(e, a, ci)}
                    onDragStart={(e) => {
                      const grab = e.nativeEvent.offsetY / PX_PER_MIN;
                      e.dataTransfer.setData(APPOINTMENT_DRAG_MIME, encodeAppointmentDrag({ id: a.id, version: a.version, grabOffset: Number.isFinite(grab) ? grab : 0 }));
                      e.dataTransfer.effectAllowed = 'move';
                    }}
                  >
                    <strong>{hhmm(a.startsAt, timeZone)}</strong> {a.displayName ?? a.employeeId}
                    <span className="small"> · {a.appointmentTypeName ?? humanise(a.appointmentType)}</span>
                    {a.roomCode && <span className="small muted"> · {a.roomCode}</span>}
                    {a.telehealth && <span className="small"> · video</span>}
                    {a.ticket && <span className="small"> · {a.ticket}</span>}
                  </button>
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}

function WeekGrid({ days, timeZone, onOpen }: { days: DiaryData[]; timeZone?: string; onOpen: (a: Appointment) => void }) {
  return (
    <div className="week-grid">
      {days.map((d) => {
        const list = [...(d.appointments ?? [])].sort((x, y) => x.startsAt.localeCompare(y.startsAt));
        return (
          <section key={d.date} className="week-day" aria-label={formatDay(d.date)}>
            <h3>{new Date(`${d.date}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</h3>
            <p className="small muted">
              {(d.sessions ?? []).length} session{(d.sessions ?? []).length === 1 ? '' : 's'} · {list.length} booked
            </p>
            <ul>
              {list.map((a) => (
                <li key={a.id}>
                  <button type="button" className={`appt appt-${a.status.toLowerCase().replace(/_/g, '-')}`} aria-label={apptLabel(a, timeZone)} onClick={() => onOpen(a)}>
                    <strong>{hhmm(a.startsAt, timeZone)}</strong> {a.displayName ?? a.employeeId}
                    <span className="small"> · {a.appointmentTypeName ?? humanise(a.appointmentType)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function WaitlistPanel({ client, onSessionExpired, siteCode, types }: { client: ScreenProps['client']; onSessionExpired: () => void; siteCode: string; types: AppointmentType[] }) {
  const api = schedulingApi(client);
  const list = useAsync(() => api.waitlist(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const today = todayLocal();
  const empty = { employeeId: '', appointmentType: '', earliest: today, latest: addDays(today, 14), priority: '3' };
  const [f, setF] = useState(empty);
  const valid = /^[A-Za-z0-9_-]{2,40}$/.test(f.employeeId.trim()) && !!f.appointmentType && !!f.earliest && !!f.latest && f.latest >= f.earliest;

  async function add(e: FormEvent) {
    e.preventDefault();
    if (!valid || !siteCode) return;
    const ok = await action.run(async () => {
      await api.addToWaitlist({ employeeId: f.employeeId.trim().toUpperCase(), appointmentType: f.appointmentType, siteCode, earliest: f.earliest, latest: f.latest, priority: Number(f.priority) });
      return `${f.employeeId.trim().toUpperCase()} added to the waitlist.`;
    });
    if (ok) {
      setF(empty);
      list.reload();
    }
  }

  const rows = (list.data ?? []).filter((w) => !siteCode || w.siteCode === siteCode).sort((a, b) => a.priority - b.priority || a.earliest.localeCompare(b.earliest));
  return (
    <aside className="side-panel" aria-labelledby="waitlist-h">
      <h2 id="waitlist-h">Waitlist – {siteCode}</h2>
      <p className="small muted">Cancelled slots are offered automatically to matching waiting employees (content-free notification).</p>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nobody is waiting at this site.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Waitlist</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Type</th>
                <th scope="col">Window</th>
                <th scope="col">Priority</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((w, i) => (
                <tr key={w.id ?? i}>
                  <th scope="row">
                    {w.displayName ?? w.employeeId}
                    <div className="small muted mono">{w.employeeId}</div>
                  </th>
                  <td>{types.find((t) => t.code === w.appointmentType)?.name ?? humanise(w.appointmentType)}</td>
                  <td className="small">
                    {formatDay(w.earliest)} – {formatDay(w.latest)}
                  </td>
                  <td className="num">{w.priority}</td>
                  <td>
                    <StatusBadge status={w.status ?? 'WAITING'} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="form" onSubmit={add}>
        <h3>Add to waitlist</h3>
        <Field label="Employee ID" required>{(id) => <input id={id} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}</Field>
        <Field label="Appointment type" required>
          {(id) => (
            <select id={id} value={f.appointmentType} onChange={(e) => setF({ ...f, appointmentType: e.target.value })}>
              <option value="">Choose…</option>
              {types.map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <div className="form-grid">
          <Field label="Earliest" required>{(id) => <input id={id} type="date" value={f.earliest} onChange={(e) => setF({ ...f, earliest: e.target.value })} />}</Field>
          <Field label="Latest" required>{(id) => <input id={id} type="date" value={f.latest} min={f.earliest} onChange={(e) => setF({ ...f, latest: e.target.value })} />}</Field>
          <Field label="Priority" hint="1 = highest">
            {(id) => (
              <select id={id} value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })}>
                {['1', '2', '3', '4', '5'].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>
        <button type="submit" disabled={!valid || action.busy}>
          Add to waitlist
        </button>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </aside>
  );
}
