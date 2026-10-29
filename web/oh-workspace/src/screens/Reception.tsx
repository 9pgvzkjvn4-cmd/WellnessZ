import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent, type KeyboardEvent } from 'react';
import { buildQuery, seg } from '../api/links';
import type { Encounter, EncounterMode, EmployeeRef } from '../api/types';
import { ApiError, type AuthClient } from '../auth/AuthClient';
import { can } from '../auth/roles';
import { SafetyBadge } from '../components/badges';
import { formatDate, humanise, localToIso, toLocalInput } from '../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAction, useAsync } from '../components/useAsync';
import { EncounterOutbox, newClientRef, type OutboxItem } from '../domain/offlineQueue';
import type { ScreenProps } from './types';

/*
 * One outbox per signed-in client, kept in module memory so pending registrations survive navigation
 * inside the app. Nothing is written to browser storage (PHI) – closing the tab loses unsent items,
 * which is why the page shows a warning and a beforeunload prompt while items are pending.
 */
let outboxClient: AuthClient | undefined;
let outbox: EncounterOutbox | undefined;
function getOutbox(client: AuthClient): EncounterOutbox {
  if (!outbox || outboxClient !== client) {
    outboxClient = client;
    outbox = new EncounterOutbox({
      send: (req) => client.api<Encounter>('/intake/encounters', { method: 'POST', body: req }),
      isOnline: () => navigator.onLine,
    });
  }
  return outbox;
}

interface FormState {
  employeeInput: string;
  occurredAt: string;
  location: string;
  chiefComplaint: string;
  actionTaken: string;
}
const blankForm = (): FormState => ({ employeeInput: '', occurredAt: toLocalInput(), location: '', chiefComplaint: '', actionTaken: '' });

function EnrichForm({ client, encounter, onDone, onSessionExpired }: { client: AuthClient; encounter: Encounter; onDone: () => void; onSessionExpired: () => void }) {
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({ chiefComplaint: encounter.chiefComplaint ?? '', actionTaken: encounter.actionTaken ?? '', notes: '', heartRate: '', systolic: '', diastolic: '', spo2: '', temperatureC: '', respiratoryRate: '' });
  const num = (v: string) => (v.trim() === '' || Number.isNaN(Number(v)) ? undefined : Number(v));

  async function submit(e: FormEvent) {
    e.preventDefault();
    const vitals = Object.fromEntries(
      (['heartRate', 'systolic', 'diastolic', 'spo2', 'temperatureC', 'respiratoryRate'] as const).map((k) => [k, num(f[k])]).filter(([, v]) => v !== undefined),
    );
    const ok = await action.run(async () => {
      await client.api(`/intake/encounters/${seg(encounter.id)}`, {
        method: 'PATCH',
        body: {
          chiefComplaint: f.chiefComplaint.trim() || undefined,
          actionTaken: f.actionTaken.trim() || undefined,
          ...(Object.keys(vitals).length ? { vitals } : {}),
          ...(f.notes.trim() ? { notes: f.notes.trim() } : {}),
        },
      });
      return 'Encounter enriched.';
    });
    if (ok) onDone();
  }

  const vital = (k: keyof typeof f, label: string) => (
    <Field label={label}>{(id) => <input id={id} inputMode="decimal" size={6} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} />}</Field>
  );

  return (
    <form className="form" onSubmit={submit}>
      <Field label="Chief complaint">{(id) => <input id={id} maxLength={500} value={f.chiefComplaint} onChange={(e) => setF({ ...f, chiefComplaint: e.target.value })} />}</Field>
      <Field label="Action taken">{(id) => <input id={id} maxLength={500} value={f.actionTaken} onChange={(e) => setF({ ...f, actionTaken: e.target.value })} />}</Field>
      <fieldset>
        <legend>Vitals</legend>
        <div className="vitals-grid">
          {vital('heartRate', 'Heart rate (/min)')}
          {vital('systolic', 'BP systolic')}
          {vital('diastolic', 'BP diastolic')}
          {vital('spo2', 'SpO₂ (%)')}
          {vital('temperatureC', 'Temp (°C)')}
          {vital('respiratoryRate', 'Resp. rate (/min)')}
        </div>
      </fieldset>
      <Field label="Notes">{(id) => <textarea id={id} rows={3} maxLength={4000} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />}</Field>
      <button type="submit" className="secondary" disabled={action.busy}>
        Save enrichment
      </button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

function OutboxList({ items, onDiscard, onClear }: { items: readonly OutboxItem[]; onDiscard: (ref: string) => void; onClear: () => void }) {
  if (items.length === 0) return null;
  return (
    <div className="tile">
      <div className="card-row">
        <h2>Registered at this post (this session)</h2>
        <button type="button" className="link-button" onClick={onClear}>
          Clear sent
        </button>
      </div>
      <ul className="plain-list">
        {items.map((i) => (
          <li key={i.clientRef} className={`outbox-${i.status.toLowerCase()}`}>
            <span aria-hidden="true">{i.status === 'SENT' ? '✓ ' : i.status === 'FAILED' ? '✕ ' : '⟳ '}</span>
            <strong>{humanise(i.request.mode)}</strong> · {i.encounter?.employeeName ?? i.request.employeeId} · {formatDate(i.request.occurredAt)} –{' '}
            {i.status === 'SENT' ? 'Registered' : i.status === 'FAILED' ? 'Rejected' : i.status === 'SENDING' ? 'Sending…' : `Waiting for network (attempts: ${i.attempts})`}
            {i.status === 'FAILED' && (
              <>
                <ErrorAlert error={i.error} />
                <button type="button" className="link-button" onClick={() => onDiscard(i.clientRef)}>
                  Discard
                </button>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** E01-F03 walk-in / emergency registration for clinic reception, first aiders and OH nurses. */
export function Reception({ client, session, onSessionExpired }: ScreenProps) {
  const box = getOutbox(client);
  const items = useSyncExternalStore(
    (cb) => box.subscribe(cb),
    () => box.items,
    () => box.items,
  );
  const pending = items.filter((i) => i.status === 'PENDING' || i.status === 'SENDING').length;
  const [online, setOnline] = useState(() => navigator.onLine);
  const [mode, setMode] = useState<EncounterMode>('WALK_IN');
  const [siteCode, setSiteCode] = useState('');
  const [form, setForm] = useState<FormState>(blankForm);
  const [employee, setEmployee] = useState<EmployeeRef>();
  const [lookupError, setLookupError] = useState<string>();
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<EmployeeRef[]>();
  const [submitMsg, setSubmitMsg] = useState<{ kind: 'success' | 'warning' | 'error'; text: string }>();
  const [statusFilter, setStatusFilter] = useState('OPEN');
  const [enrichId, setEnrichId] = useState<string>();
  const badgeRef = useRef<HTMLInputElement>(null);
  const convert = useAction(onSessionExpired);
  const lookup = useAction(onSessionExpired);
  const canList = can(session.roles, 'encounterList');
  const canConvert = can(session.roles, 'encounterConvert');
  const encounters = useAsync(
    () => (canList ? client.api<Encounter[]>(`/intake/encounters${buildQuery({ status: statusFilter, site: siteCode })}`) : Promise.resolve([] as Encounter[])),
    onSessionExpired,
    [statusFilter, siteCode],
  );

  useEffect(() => {
    const on = () => {
      setOnline(true);
      void box.flush();
    };
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    const timer = window.setInterval(() => void box.flush(), 30_000);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
      window.clearInterval(timer);
    };
  }, [box]);

  useEffect(() => {
    if (pending === 0) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pending]);

  async function lookupBadge() {
    const badge = form.employeeInput.trim();
    setLookupError(undefined);
    setEmployee(undefined);
    if (!badge) return;
    if (!navigator.onLine) {
      setLookupError('Offline – the badge cannot be checked now. The ID you entered will be used as the employee ID.');
      return;
    }
    await lookup.run(async () => {
      try {
        const emp = await client.api<EmployeeRef>(`/intake/employees/by-badge/${seg(badge)}`);
        setEmployee(emp);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) setLookupError('No employee found for this badge. Check the badge, search by name, or enter the employee ID.');
        else throw e;
      }
    });
  }

  function onBadgeKey(e: KeyboardEvent<HTMLInputElement>) {
    // Keyboard-wedge scanners type the badge id followed by Enter: look up instead of submitting the form.
    if (e.key === 'Enter') {
      e.preventDefault();
      void lookupBadge();
    }
  }

  async function searchEmployees(e: FormEvent) {
    e.preventDefault();
    if (search.trim().length < 2) return;
    await lookup.run(async () => {
      setResults(await client.api<EmployeeRef[]>(`/intake/employees/lookup${buildQuery({ q: search.trim() })}`));
    });
  }

  async function register(e: FormEvent) {
    e.preventDefault();
    const employeeId = employee?.employeeId ?? form.employeeInput.trim();
    if (!employeeId) return;
    const req = {
      clientRef: newClientRef(),
      mode,
      employeeId,
      occurredAt: localToIso(form.occurredAt) ?? new Date().toISOString(),
      siteCode: siteCode.trim().toUpperCase(),
      location: form.location.trim(),
      chiefComplaint: form.chiefComplaint.trim(),
      actionTaken: form.actionTaken.trim(),
    };
    const r = await box.submit(req);
    if (r.kind === 'sent') {
      setSubmitMsg({ kind: 'success', text: `Registered ${r.encounter.employeeName ?? employeeId} (${humanise(mode)}).${mode === 'EMERGENCY' ? ' Remember to enrich this encounter later.' : ''}` });
      encounters.reload();
    } else if (r.kind === 'queued') {
      setSubmitMsg({ kind: 'warning', text: 'No network – the registration is queued in this tab and will be sent automatically. Do not close this tab.' });
    } else {
      setSubmitMsg({ kind: 'error', text: 'The registration was rejected – see the list below.' });
      return;
    }
    setForm(blankForm());
    setEmployee(undefined);
    badgeRef.current?.focus();
  }

  async function convertEncounter(enc: Encounter) {
    await convert.run(async () => {
      const r = await client.api<{ caseId: string; caseRef: string }>(`/intake/encounters/${seg(enc.id)}/convert`, { method: 'POST' });
      encounters.reload();
      return `Case ${r.caseRef} created from the encounter.`;
    });
  }

  const emergency = mode === 'EMERGENCY';
  const ready = !!(employee?.employeeId ?? form.employeeInput.trim()) && !!form.occurredAt && !!form.location.trim() && !!form.chiefComplaint.trim() && !!form.actionTaken.trim() && !!siteCode.trim();
  const sentEncounters = items.filter((i) => i.status === 'SENT' && i.encounter).map((i) => i.encounter as Encounter);

  return (
    <section>
      <PageTitle sub="Register walk-ins and first-aid encounters. Scan the staff badge or type the ID.">Walk-in &amp; first aid</PageTitle>
      {!online && <Alert kind="warning">You are offline. Registrations are kept in this tab only and sent when the network returns.</Alert>}
      {pending > 0 && (
        <Alert kind="warning">
          {pending} registration{pending === 1 ? '' : 's'} waiting to be sent. Keep this tab open.{' '}
          <button type="button" className="link-button" onClick={() => void box.flush()}>
            Retry now
          </button>
        </Alert>
      )}

      <div className="toolbar toolbar-left">
        <Field label="Site / first-aid post" required hint="Set once for this workstation.">
          {(id) => <input id={id} value={siteCode} placeholder="e.g. LHR-T5-FA1" maxLength={30} onChange={(e) => setSiteCode(e.target.value)} />}
        </Field>
        <div className="mode-switch" role="radiogroup" aria-label="Registration mode">
          <label className={`mode ${!emergency ? 'mode-active' : ''}`}>
            <input type="radio" name="mode" checked={!emergency} onChange={() => setMode('WALK_IN')} /> Walk-in
          </label>
          <label className={`mode mode-emergency ${emergency ? 'mode-active' : ''}`}>
            <input type="radio" name="mode" checked={emergency} onChange={() => setMode('EMERGENCY')} /> Emergency
          </label>
        </div>
      </div>

      <div className="grid-2 wide-left">
        <div className={emergency ? 'emergency-panel' : ''}>
          <h2>{emergency ? 'Emergency registration – 5 fields' : 'Walk-in registration'}</h2>
          <form className="form" onSubmit={register} aria-label={emergency ? 'Emergency registration' : 'Walk-in registration'}>
            <Field label="Employee (badge scan or ID)" required>
              {(id) => (
                <div className="inline-row">
                  <input
                    id={id}
                    ref={badgeRef}
                    autoFocus
                    autoComplete="off"
                    spellCheck={false}
                    value={form.employeeInput}
                    onChange={(e) => {
                      setForm({ ...form, employeeInput: e.target.value });
                      setEmployee(undefined);
                    }}
                    onKeyDown={onBadgeKey}
                  />
                  <button type="button" className="secondary small-button" onClick={() => void lookupBadge()} disabled={lookup.busy}>
                    Look up
                  </button>
                </div>
              )}
            </Field>
            {employee && (
              <p className="employee-chip" role="status">
                <strong>{employee.displayName}</strong> ({employee.employeeId}) · {employee.departmentCode} · {employee.jobCode}{' '}
                <SafetyBadge safetyCritical={employee.safetyCritical} />
              </p>
            )}
            {lookupError && <p className="small text-amber" role="status">{lookupError}</p>}
            <Field label="Time" required>{(id) => <input id={id} type="datetime-local" required value={form.occurredAt} onChange={(e) => setForm({ ...form, occurredAt: e.target.value })} />}</Field>
            <Field label="Location" required>{(id) => <input id={id} required maxLength={120} value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />}</Field>
            <Field label="Chief complaint" required>{(id) => <input id={id} required maxLength={500} value={form.chiefComplaint} onChange={(e) => setForm({ ...form, chiefComplaint: e.target.value })} />}</Field>
            <Field label="Action taken" required>{(id) => <input id={id} required maxLength={500} value={form.actionTaken} onChange={(e) => setForm({ ...form, actionTaken: e.target.value })} />}</Field>
            <button type="submit" className={emergency ? 'danger-button' : undefined} disabled={!ready}>
              {emergency ? 'Register emergency' : 'Register walk-in'}
            </button>
            {!siteCode.trim() && <p className="small muted">Set the site / post above first.</p>}
          </form>
          {submitMsg && <Alert kind={submitMsg.kind}>{submitMsg.text}</Alert>}
        </div>
        <div>
          {!emergency && (
            <>
              <h2>Find employee by name</h2>
              <form className="form inline-form" onSubmit={searchEmployees}>
                <Field label="Name or ID">{(id) => <input id={id} type="search" value={search} onChange={(e) => setSearch(e.target.value)} />}</Field>
                <button type="submit" className="secondary" disabled={lookup.busy || search.trim().length < 2}>
                  Search
                </button>
              </form>
              {results && results.length === 0 && <Empty>No matches.</Empty>}
              <ul className="plain-list">
                {(results ?? []).map((r) => (
                  <li key={r.employeeId}>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => {
                        setEmployee(r);
                        setForm({ ...form, employeeInput: r.employeeId });
                        setResults(undefined);
                      }}
                    >
                      {r.displayName} ({r.employeeId})
                    </button>{' '}
                    <span className="small muted">{r.departmentCode} · {r.jobCode}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
          <ActionFeedback error={lookup.error} />
          <OutboxList items={items} onDiscard={(r) => box.discard(r)} onClear={() => box.clearSettled()} />
        </div>
      </div>

      {sentEncounters.length > 0 && !canList && (
        <>
          <h2>Enrich my registrations</h2>
          <ul className="card-list">
            {sentEncounters.map((enc) => (
              <li key={enc.id} className="card">
                <div className="card-row">
                  <span>{enc.employeeName ?? enc.employeeId} · {formatDate(enc.occurredAt)}</span>
                  <button type="button" className="secondary small-button" onClick={() => setEnrichId(enrichId === enc.id ? undefined : enc.id)}>
                    {enrichId === enc.id ? 'Close' : 'Enrich'}
                  </button>
                </div>
                {enrichId === enc.id && <EnrichForm client={client} encounter={enc} onSessionExpired={onSessionExpired} onDone={() => setEnrichId(undefined)} />}
              </li>
            ))}
          </ul>
        </>
      )}

      {canList && (
        <>
          <h2>Encounters</h2>
          <div className="toolbar toolbar-left">
            <label htmlFor="enc-status">Status</label>
            <select id="enc-status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="OPEN">Open</option>
              <option value="CONVERTED">Converted</option>
            </select>
            <button type="button" className="secondary" onClick={encounters.reload}>
              Refresh
            </button>
          </div>
          <ActionFeedback error={convert.error} message={convert.message} />
          {encounters.loading && <Loading />}
          {!!encounters.error && <ErrorAlert error={encounters.error} />}
          {encounters.data && encounters.data.length === 0 && <Empty>No encounters.</Empty>}
          <ul className="card-list">
            {(encounters.data ?? []).map((enc) => (
              <li key={enc.id} className={`card ${enc.mode === 'EMERGENCY' ? 'card-emergency' : ''}`}>
                <div className="card-row">
                  <span>
                    <strong>{enc.employeeName ?? enc.employeeId}</strong> · {humanise(enc.mode)} · {formatDate(enc.occurredAt)} · {enc.siteCode} {enc.location}
                  </span>
                  <span className="badge-row">
                    <StatusBadge status={enc.status} />
                    {enc.enrichmentDue && <span className="badge badge-warning">Enrichment due</span>}
                  </span>
                </div>
                {enc.chiefComplaint && <p className="small">Complaint: {enc.chiefComplaint}{enc.actionTaken ? ` · Action: ${enc.actionTaken}` : ''}</p>}
                <div className="card-actions">
                  {enc.caseRef && enc.caseId ? (
                    <a href={`#/cases/${encodeURIComponent(enc.caseId)}`}>Case {enc.caseRef}</a>
                  ) : (
                    canConvert && (
                      <button type="button" className="small-button" disabled={convert.busy} onClick={() => void convertEncounter(enc)}>
                        Convert to case
                      </button>
                    )
                  )}
                  {enc.status !== 'CONVERTED' && enc.status !== 'CLOSED' && (
                    <button type="button" className="secondary small-button" onClick={() => setEnrichId(enrichId === enc.id ? undefined : enc.id)}>
                      {enrichId === enc.id ? 'Close' : 'Enrich'}
                    </button>
                  )}
                </div>
                {enrichId === enc.id && (
                  <EnrichForm
                    client={client}
                    encounter={enc}
                    onSessionExpired={onSessionExpired}
                    onDone={() => {
                      setEnrichId(undefined);
                      encounters.reload();
                    }}
                  />
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
