import { useEffect, useRef, useState, type FormEvent } from 'react';
import { schedulingApi, type CallNextResult, type CheckInTicket, type QueueEntry } from '../../api/scheduling';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { checkInBody, encounterHandOff, formatWait, type CheckInMode } from '../../domain/scheduling';
import type { ScreenProps } from '../types';
import { SiteSelect } from './common';
import { useSites } from './useSchedulingData';

const RESET_AFTER_MS = 20_000;
export const QUEUE_REFRESH_MS = 15_000;

const MODES: { id: CheckInMode; label: string; field: string }[] = [
  { id: 'badge', label: 'Scan badge', field: 'Badge number' },
  { id: 'employee', label: 'Use employee ID', field: 'Employee ID' },
  { id: 'appointment', label: 'Use appointment ID', field: 'Appointment ID' },
];

/**
 * `#/scheduling/reception` – kiosk-style check-in (E14-F03). A badge scanner acts as a keyboard wedge (types + Enter), so
 * the input keeps focus. The ticket screen shows only the ticket number and appointment type – no clinical information.
 */
export function ReceptionKiosk({ client, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites, siteCode, setSiteCode } = useSites(client, onSessionExpired);
  const [mode, setMode] = useState<CheckInMode>('badge');
  const [value, setValue] = useState('');
  const [ticket, setTicket] = useState<CheckInTicket>();
  const action = useAction(onSessionExpired);
  const input = useRef<HTMLInputElement>(null);
  const field = MODES.find((m) => m.id === mode)!;

  useEffect(() => {
    if (!ticket) return;
    const t = window.setTimeout(() => setTicket(undefined), RESET_AFTER_MS);
    return () => window.clearTimeout(t);
  }, [ticket]);

  useEffect(() => {
    if (!ticket) input.current?.focus();
  }, [ticket, mode]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = checkInBody(siteCode, mode, value);
    if (!body) return;
    const ok = await action.run(async () => setTicket(await api.checkIn(body)));
    setValue('');
    if (!ok) input.current?.focus();
  }

  const valid = !!checkInBody(siteCode, mode, value);
  return (
    <section className="kiosk">
      <PageTitle sub="Check in for today’s appointment. You will receive a ticket number – watch the queue screen.">Clinic check-in</PageTitle>
      <div className="filters">
        <SiteSelect sites={sites.data} value={siteCode} onChange={setSiteCode} label="Clinic site" />
      </div>
      {!!sites.error && <ErrorAlert error={sites.error} />}
      {ticket ? (
        <div className="ticket-card" role="status" aria-live="assertive">
          <p className="lead">You are checked in. Your ticket number is</p>
          <p className="ticket-number">{ticket.ticket}</p>
          {ticket.appointmentTypeName && <p>{ticket.appointmentTypeName}</p>}
          <p>
            {ticket.position !== undefined && <>Position in queue: <strong>{ticket.position}</strong>. </>}
            {ticket.estimatedWaitMinutes !== undefined && <>Estimated wait: <strong>{formatWait(ticket.estimatedWaitMinutes)}</strong>.</>}
          </p>
          <button type="button" onClick={() => setTicket(undefined)}>
            Next person
          </button>
        </div>
      ) : (
        <form className="form" onSubmit={submit}>
          <fieldset className="kiosk-modes">
            <legend className="visually-hidden">Identify by</legend>
            {MODES.map((m) => (
              <label key={m.id} className="checkbox">
                <input type="radio" name="checkin-mode" checked={mode === m.id} onChange={() => setMode(m.id)} /> {m.label}
              </label>
            ))}
          </fieldset>
          <Field label={field.field} hint={mode === 'badge' ? 'Scan your badge, or type the number and press Enter.' : undefined}>
            {(id) => <input id={id} ref={input} type="text" inputMode={mode === 'badge' ? 'numeric' : 'text'} autoComplete="off" value={value} maxLength={64} onChange={(e) => setValue(e.target.value)} />}
          </Field>
          <button type="submit" disabled={action.busy || !valid}>
            Check in
          </button>
          <ActionFeedback error={action.error} />
        </form>
      )}
    </section>
  );
}

/**
 * `#/scheduling/queue` – large-format queue board: tickets only (no names), average wait, auto-refresh. Clinicians call
 * the next patient and hand off to the employee chart encounters (`#/employees/{id}/chart/encounters`).
 */
export function QueueBoardScreen({ client, session, onSessionExpired }: ScreenProps) {
  const api = schedulingApi(client);
  const { sites, siteCode, setSiteCode } = useSites(client, onSessionExpired);
  const board = useAsync(() => (siteCode ? api.queue(siteCode) : Promise.resolve(undefined)), onSessionExpired, [siteCode]);
  const reload = board.reload;
  const [auto, setAuto] = useState(true);
  const canCall = can(session.roles, 'queueCallNext');
  const boardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!auto || !siteCode) return;
    const t = window.setInterval(reload, QUEUE_REFRESH_MS);
    return () => window.clearInterval(t);
  }, [auto, siteCode, reload]);

  const entries = board.data?.entries ?? [];
  const called = entries.filter((e) => e.status === 'CALLED' || e.status === 'IN_PROGRESS');
  const waiting = entries.filter((e) => e.status === 'WAITING').sort((a, b) => (a.checkedInAt ?? '').localeCompare(b.checkedInAt ?? ''));

  return (
    <section>
      <PageTitle sub="Ticket numbers only – no names are shown on this screen.">Queue board</PageTitle>
      <div className="filters">
        <SiteSelect sites={sites.data} value={siteCode} onChange={setSiteCode} />
        <label className="checkbox">
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Refresh every {QUEUE_REFRESH_MS / 1000} s
        </label>
        <div className="actions">
          <button type="button" className="secondary small-button" onClick={reload}>
            Refresh now
          </button>
          {typeof document !== 'undefined' && document.fullscreenEnabled && (
            <button type="button" className="secondary small-button" onClick={() => void boardRef.current?.requestFullscreen?.()}>
              Full screen
            </button>
          )}
        </div>
      </div>
      {canCall && siteCode && <CallNext client={client} onSessionExpired={onSessionExpired} siteCode={siteCode} onCalled={reload} />}
      {!!board.error && <ErrorAlert error={board.error} />}
      {board.loading && !board.data && <Loading label="Loading queue…" />}
      {board.data && (
        <div className="queue-board" ref={boardRef}>
          <p className="queue-stat">
            Average wait today: <strong>{formatWait(board.data.averageWaitMinutesToday)}</strong> · Waiting: <strong>{waiting.length}</strong>
          </p>
          <div className="queue-columns">
            <section aria-labelledby="q-called">
              <h2 id="q-called">Now calling</h2>
              {called.length === 0 ? <Empty>Nobody called yet.</Empty> : <TicketList entries={called} live />}
            </section>
            <section aria-labelledby="q-waiting">
              <h2 id="q-waiting">Waiting</h2>
              {waiting.length === 0 ? <Empty>The queue is empty.</Empty> : <TicketList entries={waiting} />}
            </section>
          </div>
        </div>
      )}
    </section>
  );
}

function TicketList({ entries, live }: { entries: QueueEntry[]; live?: boolean }) {
  return (
    <ul className="queue-tickets" aria-live={live ? 'polite' : undefined}>
      {entries.map((e) => (
        <li key={e.ticket} className={`queue-ticket${e.status !== 'WAITING' ? ' queue-ticket-called' : ''}`}>
          <strong>{e.ticket}</strong>
          <span>
            {e.status === 'WAITING' ? (
              <>
                {e.appointmentTypeName && <span className="small">{e.appointmentTypeName} · </span>}
                {formatWait(e.waitMinutes)}
              </>
            ) : (
              <>
                {e.roomCode ? <>Go to room <strong>{e.roomCode}</strong></> : humanise(e.status)}
              </>
            )}
          </span>
        </li>
      ))}
    </ul>
  );
}

function CallNext({ client, onSessionExpired, siteCode, onCalled }: { client: ScreenProps['client']; onSessionExpired: () => void; siteCode: string; onCalled: () => void }) {
  const api = schedulingApi(client);
  const action = useAction(onSessionExpired);
  const [room, setRoom] = useState('');
  const [called, setCalled] = useState<CallNextResult>();
  const [started, setStarted] = useState(false);

  async function call(e: FormEvent) {
    e.preventDefault();
    setStarted(false);
    await action.run(async () => {
      setCalled(await api.callNext(siteCode, room.trim() || undefined));
      onCalled();
    });
  }

  async function start() {
    if (!called) return;
    const ok = await action.run(async () => {
      await api.start(called.appointmentId);
    });
    if (ok) setStarted(true);
  }

  return (
    <div className="card">
      <form className="form inline-form" onSubmit={call}>
        <Field label="My room (optional)">{(id) => <input id={id} value={room} maxLength={20} onChange={(e) => setRoom(e.target.value)} />}</Field>
        <button type="submit" disabled={action.busy}>
          Call next patient
        </button>
      </form>
      <ActionFeedback error={action.error} />
      {called && (
        <Alert kind="info">
          <span>
            Ticket <strong>{called.ticket}</strong> – {called.displayName ?? called.employeeId} ({called.employeeId}){called.appointmentType ? `, ${humanise(called.appointmentType)}` : ''}.
          </span>{' '}
          <span className="actions">
            {!started && (
              <button type="button" className="small-button secondary" onClick={() => void start()} disabled={action.busy}>
                Start consultation
              </button>
            )}
            <a className="button small-button" href={encounterHandOff(called.encounterLink, called.employeeId)}>
              Open chart encounters
            </a>
            {called.caseId && <a href={`#/cases/${encodeURIComponent(called.caseId)}`}>Open case</a>}
          </span>
        </Alert>
      )}
    </div>
  );
}
