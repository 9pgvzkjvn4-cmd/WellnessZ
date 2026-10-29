import { useState, type FormEvent } from 'react';
import { schedulingApi, type Appointment, type AppointmentType, type FreeSlot, type MoveRequest, type Resource, type TelehealthLink } from '../../api/scheduling';
import type { AuthClient } from '../../auth/AuthClient';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Dl, Field, StatusBadge } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import { addDays, hhmm, zonedDay, zonedToUtcIso } from '../../domain/scheduling';

const EMPLOYEE_ID = /^[A-Za-z0-9_-]{2,40}$/;
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

function toMinutes(t: string): number {
  return Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
}

/** Only http(s) join links are rendered. */
function safeUrl(u: string | undefined): string | undefined {
  if (!u) return undefined;
  try {
    const p = new URL(u);
    return p.protocol === 'https:' || p.protocol === 'http:' ? p.href : undefined;
  } catch {
    return undefined;
  }
}

interface Common {
  client: AuthClient;
  onSessionExpired: () => void;
  timeZone?: string;
  onClose: () => void;
  onDone: (message: string) => void;
}

// ---------------------------------------------------------------- book
export function BookDialog({ client, onSessionExpired, timeZone, onClose, onDone, siteCode, date, types, resources }: Common & { siteCode: string; date: string; types: AppointmentType[]; resources: Resource[] }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const slotSearch = useAction(onSessionExpired);
  const [f, setF] = useState({ employeeId: '', appointmentType: '', clinicianUsername: '', roomCode: '', deviceId: '', date, time: '09:00', adminNote: '', caseId: '' });
  const [slots, setSlots] = useState<FreeSlot[]>();
  const [touched, setTouched] = useState(false);
  const type = types.find((t) => t.code === f.appointmentType);
  const clinicians = resources.filter((r) => r.type === 'CLINICIAN' && r.active !== false);
  const rooms = resources.filter((r) => r.type === 'ROOM' && r.active !== false && (!type?.roomType || r.roomType === type.roomType));
  const devices = resources.filter((r) => r.type === 'DEVICE' && r.active !== false);

  const errors: Record<string, string> = {};
  if (!EMPLOYEE_ID.test(f.employeeId.trim())) errors.employeeId = 'Enter a valid employee ID.';
  if (!f.appointmentType) errors.appointmentType = 'Choose an appointment type.';
  if (!f.date || !TIME.test(f.time)) errors.time = 'Choose a date and time (HH:MM).';
  const valid = Object.keys(errors).length === 0;

  async function findSlots() {
    if (!f.appointmentType) return;
    await slotSearch.run(async () => {
      setSlots(await api.slots({ type: f.appointmentType, siteCode, from: f.date, to: addDays(f.date, 6), clinician: f.clinicianUsername || undefined }));
    });
  }

  function pick(s: FreeSlot) {
    setF({ ...f, date: zonedDay(s.startsAt, timeZone), time: hhmm(s.startsAt, timeZone), clinicianUsername: s.clinicianUsername, roomCode: s.roomCode ?? '', deviceId: s.deviceId ?? '' });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (!valid) return;
    const startsAt = zonedToUtcIso(f.date, toMinutes(f.time), timeZone);
    const ok = await action.run(async () => {
      const a = await api.book({
        employeeId: f.employeeId.trim().toUpperCase(),
        appointmentType: f.appointmentType,
        siteCode,
        startsAt,
        clinicianUsername: f.clinicianUsername || undefined,
        roomCode: f.roomCode || undefined,
        deviceId: f.deviceId || undefined,
        caseId: f.caseId.trim() || undefined,
        adminNote: f.adminNote.trim() || undefined,
        sourceType: 'MANUAL',
      });
      onDone(`Booked ${a.appointmentTypeName ?? humanise(a.appointmentType)} for ${a.displayName ?? a.employeeId} at ${hhmm(a.startsAt, timeZone)} on ${zonedDay(a.startsAt, timeZone)}.`);
    });
    if (ok) onClose();
  }

  return (
    <Dialog title="Book appointment" onClose={onClose}>
      <form className="form" onSubmit={submit} noValidate>
        <div className="form-grid">
          <Field label="Employee ID" required hint={touched ? errors.employeeId : undefined}>
            {(id) => <input id={id} value={f.employeeId} autoComplete="off" aria-invalid={touched && !!errors.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}
          </Field>
          <Field label="Appointment type" required hint={touched ? errors.appointmentType : undefined}>
            {(id) => (
              <select id={id} value={f.appointmentType} aria-invalid={touched && !!errors.appointmentType} onChange={(e) => { setSlots(undefined); setF({ ...f, appointmentType: e.target.value, roomCode: '' }); }}>
                <option value="">Choose…</option>
                {types.map((t) => (
                  <option key={t.code} value={t.code}>
                    {t.name} ({t.durationMinutes} min{t.telehealth ? ', telehealth' : ''})
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Site">{(id) => <input id={id} value={siteCode} readOnly />}</Field>
          <Field label="Clinician">
            {(id) => (
              <select id={id} value={f.clinicianUsername} onChange={(e) => setF({ ...f, clinicianUsername: e.target.value })}>
                <option value="">Any available</option>
                {clinicians.map((r) => (
                  <option key={r.id} value={r.username ?? r.code}>
                    {r.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Room" hint={type?.roomType ? `Needs a ${humanise(type.roomType).toLowerCase()} room` : undefined}>
            {(id) => (
              <select id={id} value={f.roomCode} onChange={(e) => setF({ ...f, roomCode: e.target.value })}>
                <option value="">Any suitable</option>
                {rooms.map((r) => (
                  <option key={r.id} value={r.code}>
                    {r.name} ({r.code})
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Device" hint={type?.deviceCategory ? `Needs: ${humanise(type.deviceCategory)}` : undefined}>
            {(id) => (
              <select id={id} value={f.deviceId} onChange={(e) => setF({ ...f, deviceId: e.target.value })}>
                <option value="">Any suitable</option>
                {devices.map((r) => (
                  <option key={r.id} value={r.code}>
                    {r.name} ({r.code})
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Date" required>
            {(id) => <input id={id} type="date" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />}
          </Field>
          <Field label="Start time" required hint={touched ? errors.time : timeZone ? `Site time (${timeZone})` : undefined}>
            {(id) => <input id={id} type="time" step={300} value={f.time} onChange={(e) => setF({ ...f, time: e.target.value })} />}
          </Field>
        </div>
        <div className="actions">
          <button type="button" className="secondary" onClick={() => void findSlots()} disabled={!f.appointmentType || slotSearch.busy}>
            Find free slots (7 days)
          </button>
        </div>
        <ActionFeedback error={slotSearch.error} />
        {slots && slots.length === 0 && <p className="muted">No free slots in the next 7 days for this type{f.clinicianUsername ? ' and clinician' : ''}.</p>}
        {slots && slots.length > 0 && (
          <fieldset>
            <legend>Free slots</legend>
            <div className="chip-list">
              {slots.slice(0, 40).map((s) => {
                const label = `${zonedDay(s.startsAt, timeZone)} ${hhmm(s.startsAt, timeZone)} · ${s.clinicianUsername}${s.roomCode ? ` · ${s.roomCode}` : ''}`;
                const chosen = f.date === zonedDay(s.startsAt, timeZone) && f.time === hhmm(s.startsAt, timeZone) && f.clinicianUsername === s.clinicianUsername;
                return (
                  <button key={`${s.startsAt}-${s.clinicianUsername}-${s.roomCode ?? ''}`} type="button" className={`small-button ${chosen ? '' : 'secondary'}`} aria-pressed={chosen} onClick={() => pick(s)}>
                    {label}
                  </button>
                );
              })}
            </div>
          </fieldset>
        )}
        <Field label="Case ID (optional)">{(id) => <input id={id} value={f.caseId} onChange={(e) => setF({ ...f, caseId: e.target.value })} />}</Field>
        <Field label="Admin note (optional)" hint="Visible to scheduling staff – logistics only, no clinical details.">
          {(id) => <textarea id={id} rows={2} maxLength={500} value={f.adminNote} onChange={(e) => setF({ ...f, adminNote: e.target.value })} />}
        </Field>
        <div className="actions">
          <button type="submit" disabled={action.busy}>
            Book
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

// ---------------------------------------------------------------- detail (move, cancel, attendance, telehealth)
export function AppointmentDialog({
  client,
  onSessionExpired,
  timeZone,
  onClose,
  onDone,
  appointment: a,
  resources,
  canBook,
  canAttend,
  canTelehealth,
  onMove,
}: Common & { appointment: Appointment; resources: Resource[]; canBook: boolean; canAttend: boolean; canTelehealth: boolean; onMove: (a: Appointment, body: MoveRequest) => Promise<boolean> }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const [mode, setMode] = useState<'view' | 'move' | 'cancel'>('view');
  const [move, setMove] = useState({ date: zonedDay(a.startsAt, timeZone), time: hhmm(a.startsAt, timeZone), clinicianUsername: a.clinicianUsername ?? '', roomCode: a.roomCode ?? '' });
  const [reason, setReason] = useState('');
  const [link, setLink] = useState<TelehealthLink>();
  const open = a.status === 'BOOKED' || a.status === 'CHECKED_IN';
  const attendable = open || a.status === 'IN_PROGRESS';
  const clinicians = resources.filter((r) => r.type === 'CLINICIAN');
  const rooms = resources.filter((r) => r.type === 'ROOM');
  const label = a.displayName ?? a.employeeId;

  async function attendance(status: 'ATTENDED' | 'NO_SHOW') {
    const ok = await action.run(async () => {
      await api.attendance(a.id, status);
      onDone(`${label}: marked ${humanise(status).toLowerCase()}.`);
    });
    if (ok) onClose();
  }

  async function cancel(e: FormEvent) {
    e.preventDefault();
    if (!reason.trim()) return;
    const ok = await action.run(async () => {
      await api.cancel(a.id, reason.trim());
      onDone(`Appointment for ${label} cancelled. Any matching waitlist entry is offered the slot.`);
    });
    if (ok) onClose();
  }

  async function submitMove(e: FormEvent) {
    e.preventDefault();
    if (!TIME.test(move.time) || !move.date) return;
    const ok = await onMove(a, { startsAt: zonedToUtcIso(move.date, toMinutes(move.time), timeZone), clinicianUsername: move.clinicianUsername || undefined, roomCode: move.roomCode || undefined, version: a.version });
    if (ok) onClose();
  }

  async function telehealth() {
    await action.run(async () => setLink(await api.telehealth(a.id)));
  }

  const join = safeUrl(link?.joinUrl);
  const docs = safeUrl(link?.documentationLink);

  return (
    <Dialog title={`Appointment – ${label}`} onClose={onClose}>
      <Dl
        items={[
          ['Employee', `${a.displayName ?? ''} (${a.employeeId})`],
          ['Type', a.appointmentTypeName ?? humanise(a.appointmentType)],
          ['When', `${zonedDay(a.startsAt, timeZone)} ${hhmm(a.startsAt, timeZone)}–${hhmm(a.endsAt, timeZone)}`],
          ['Clinician', a.clinicianUsername],
          ['Room', a.roomCode],
          ['Device', a.deviceId],
          ['Status', <StatusBadge key="s" status={a.status} />],
          ['Ticket', a.ticket],
          ['Checked in', a.checkedInAt ? formatDate(a.checkedInAt) : undefined],
          ['Source', a.sourceType ? humanise(a.sourceType) : undefined],
          ['Telehealth', a.telehealth ? 'Yes' : 'No'],
        ]}
      />
      {mode === 'view' && (
        <div className="actions">
          {canBook && open && (
            <button type="button" onClick={() => setMode('move')}>
              Move…
            </button>
          )}
          {canBook && open && (
            <button type="button" className="secondary" onClick={() => setMode('cancel')}>
              Cancel appointment…
            </button>
          )}
          {canAttend && attendable && (
            <>
              <button type="button" className="secondary" disabled={action.busy} onClick={() => void attendance('ATTENDED')}>
                Mark attended
              </button>
              <button type="button" className="secondary" disabled={action.busy} onClick={() => void attendance('NO_SHOW')}>
                Mark no-show
              </button>
            </>
          )}
          {canTelehealth && a.telehealth && attendable && (
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void telehealth()}>
              Launch telehealth
            </button>
          )}
        </div>
      )}
      {join && (
        <p>
          <a className="button" href={join} target="_blank" rel="noopener noreferrer">
            Join video consultation (opens in a new window)
          </a>{' '}
          {docs && (
            <a href={docs} target="_blank" rel="noopener noreferrer">
              Documentation
            </a>
          )}
        </p>
      )}
      {mode === 'move' && (
        <form className="form" onSubmit={submitMove}>
          <div className="form-grid">
            <Field label="New date" required>{(id) => <input id={id} type="date" value={move.date} onChange={(e) => setMove({ ...move, date: e.target.value })} />}</Field>
            <Field label="New start time" required>{(id) => <input id={id} type="time" step={300} value={move.time} onChange={(e) => setMove({ ...move, time: e.target.value })} />}</Field>
            <Field label="Clinician">
              {(id) => (
                <select id={id} value={move.clinicianUsername} onChange={(e) => setMove({ ...move, clinicianUsername: e.target.value })}>
                  <option value="">Keep / any</option>
                  {clinicians.map((r) => (
                    <option key={r.id} value={r.username ?? r.code}>
                      {r.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Room">
              {(id) => (
                <select id={id} value={move.roomCode} onChange={(e) => setMove({ ...move, roomCode: e.target.value })}>
                  <option value="">Keep / any</option>
                  {rooms.map((r) => (
                    <option key={r.id} value={r.code}>
                      {r.name} ({r.code})
                    </option>
                  ))}
                </select>
              )}
            </Field>
          </div>
          <div className="actions">
            <button type="submit">Move appointment</button>
            <button type="button" className="secondary" onClick={() => setMode('view')}>
              Back
            </button>
          </div>
        </form>
      )}
      {mode === 'cancel' && (
        <form className="form" onSubmit={cancel}>
          <Field label="Cancellation reason" required hint="Logistics only – the employee is notified with a content-free message.">
            {(id) => <textarea id={id} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </Field>
          <div className="actions">
            <button type="submit" className="danger-button" disabled={action.busy || !reason.trim()}>
              Cancel appointment
            </button>
            <button type="button" className="secondary" onClick={() => setMode('view')}>
              Back
            </button>
          </div>
        </form>
      )}
      <ActionFeedback error={action.error} />
    </Dialog>
  );
}
