import { useRef, useState, type FormEvent, type KeyboardEvent, type Ref } from 'react';
import { IMMUNISATION_STATUSES, INJECTION_SITES, ROUTES, surveillanceApi, type ImmunisationRequirement } from '../../api/surveillance';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDay, humanise, isoDay } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { employeeOrBadge, lotInput, type IdMode } from '../../domain/surveillance';
import type { ScreenProps } from '../types';

type Section = 'matrix' | 'due' | 'fast' | 'batch' | 'titre';
const STATUS_TONE: Record<string, 'red' | 'amber' | 'green'> = { OVERDUE: 'red', DUE: 'amber', TITRE_NEEDED: 'amber', COMPLETE: 'green' };

function vaccinesOf(reqs: readonly ImmunisationRequirement[] | undefined): { code: string; name: string }[] {
  const m = new Map<string, string>();
  for (const r of reqs ?? []) m.set(r.vaccineCode, r.vaccineName);
  return [...m.entries()].map(([code, name]) => ({ code, name })).sort((a, b) => a.name.localeCompare(b.name));
}

function VaccineSelect({ id, value, onChange, vaccines }: { id: string; value: string; onChange: (v: string) => void; vaccines: { code: string; name: string }[] }) {
  return vaccines.length > 0 ? (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)} required>
      <option value="">Choose…</option>
      {vaccines.map((v) => (
        <option key={v.code} value={v.code}>{v.name} ({v.code})</option>
      ))}
    </select>
  ) : (
    <input id={id} required maxLength={30} value={value} onChange={(e) => onChange(e.target.value.toUpperCase())} />
  );
}

/** Lot input: keyboard-wedge scanners type the GS1 string then Enter – the parsed lot / expiry is shown live. */
function LotField({ value, onChange, inputRef, onEnter }: { value: string; onChange: (v: string) => void; inputRef?: Ref<HTMLInputElement>; onEnter?: () => void }) {
  const parsed = value.trim() ? lotInput(value) : undefined;
  return (
    <div className={`field${parsed?.error ? ' field-error' : ''}`}>
      <label htmlFor="imm-lot">Lot – scan the vial barcode or type the lot number</label>
      <input
        id="imm-lot"
        ref={inputRef}
        autoComplete="off"
        spellCheck={false}
        maxLength={80}
        aria-describedby="imm-lot-status"
        aria-invalid={!!parsed?.error}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e: KeyboardEvent<HTMLInputElement>) => {
          if (e.key === 'Enter' && onEnter) {
            e.preventDefault();
            onEnter();
          }
        }}
      />
      <span id="imm-lot-status" className={`small ${parsed?.error ? 'field-error-text' : 'hint'}`} aria-live="polite">
        {parsed?.error ?? (parsed?.lotNumber ? `Lot ${parsed.lotNumber}${parsed.expiry ? ` · expires ${formatDay(parsed.expiry)}` : ''}` : 'GS1 “(17)YYMMDD(10)LOT” or plain lot number')}
      </span>
    </div>
  );
}

function lotBody(raw: string): { lotNumber?: string; lotBarcode?: string; lotExpiry?: string } | undefined {
  const p = lotInput(raw);
  if (p.error) return undefined;
  return p.lotBarcode ? { lotBarcode: p.lotBarcode, ...(p.expiry ? { lotExpiry: p.expiry } : {}) } : { lotNumber: p.lotNumber };
}

/** Fast entry: vaccine / lot / site / route stay set between people; only the person, dose and consent change. */
function FastEntry({ client, onSessionExpired, vaccines }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { vaccines: { code: string; name: string }[] }) {
  const [sticky, setSticky] = useState({ vaccineCode: '', lot: '', site: 'LEFT_DELTOID', route: 'IM', campaignId: '' });
  const [person, setPerson] = useState({ ref: '', mode: 'AUTO' as IdMode, doseNumber: '1', consent: false });
  const [log, setLog] = useState<{ ref: string; vaccine: string; dose: string; seconds?: number }[]>([]);
  const [started, setStarted] = useState<number>();
  const action = useAction(onSessionExpired);
  const personRef = useRef<HTMLInputElement>(null);
  const lotRef = useRef<HTMLInputElement>(null);
  const consentRef = useRef<HTMLInputElement>(null);
  const vaccineRef = useRef<HTMLDivElement>(null);

  /** Focus the first missing field; returns true when the entry is complete. */
  function focusMissing(): boolean {
    if (!sticky.vaccineCode) {
      vaccineRef.current?.querySelector<HTMLElement>('select, input')?.focus();
      return false;
    }
    if (!lotBody(sticky.lot)) {
      lotRef.current?.focus();
      return false;
    }
    if (!employeeOrBadge(person.ref, person.mode)) {
      personRef.current?.focus();
      return false;
    }
    if (!person.consent) {
      consentRef.current?.focus();
      return false;
    }
    return true;
  }

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!focusMissing()) return;
    const who = employeeOrBadge(person.ref, person.mode);
    const lot = lotBody(sticky.lot);
    if (!who || !lot) return;
    const dose = Math.max(1, Number(person.doseNumber) || 1);
    const vaccine = vaccines.find((v) => v.code === sticky.vaccineCode)?.name ?? sticky.vaccineCode;
    const ok = await action.run(async () => {
      await surveillanceApi(client).administer({
        ...who,
        vaccineCode: sticky.vaccineCode,
        doseNumber: dose,
        ...lot,
        site: sticky.site,
        route: sticky.route,
        consentConfirmed: true,
        ...(sticky.campaignId.trim() ? { campaignId: sticky.campaignId.trim() } : {}),
      });
      return `${vaccine} dose ${dose} recorded for ${person.ref.trim()}.`;
    });
    if (ok) {
      const seconds = started ? Math.round((Date.now() - started) / 1000) : undefined;
      setLog([{ ref: person.ref.trim(), vaccine, dose: String(dose), seconds }, ...log].slice(0, 20));
      setPerson({ ...person, ref: '', doseNumber: '1', consent: false });
      setStarted(undefined);
      personRef.current?.focus();
    }
  }

  const lotState = sticky.lot.trim() ? lotInput(sticky.lot) : undefined;
  return (
    <section aria-labelledby="fast-title">
      <h2 id="fast-title">Fast entry</h2>
      <p className="small muted">Set the vaccine and scan the vial once; then per person: scan the badge (or type the ID), tick consent, press Enter.</p>
      <form className="form config-form" onSubmit={submit}>
        <fieldset>
          <legend>Session (kept between entries)</legend>
          <div className="inline-form">
            <div ref={vaccineRef}>
              <Field label="Vaccine" required>{(id) => <VaccineSelect id={id} value={sticky.vaccineCode} onChange={(v) => setSticky({ ...sticky, vaccineCode: v })} vaccines={vaccines} />}</Field>
            </div>
            <LotField value={sticky.lot} onChange={(v) => setSticky({ ...sticky, lot: v })} inputRef={lotRef} onEnter={() => personRef.current?.focus()} />
            <Field label="Site">
              {(id) => (
                <select id={id} value={sticky.site} onChange={(e) => setSticky({ ...sticky, site: e.target.value, route: e.target.value === 'ORAL' ? 'ORAL' : sticky.route === 'ORAL' ? 'IM' : sticky.route })}>
                  <SelectOptions values={INJECTION_SITES} />
                </select>
              )}
            </Field>
            <Field label="Route">
              {(id) => (
                <select id={id} value={sticky.route} onChange={(e) => setSticky({ ...sticky, route: e.target.value })}>
                  {ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              )}
            </Field>
            <Field label="Campaign id (optional)">{(id) => <input id={id} maxLength={60} size={12} value={sticky.campaignId} onChange={(e) => setSticky({ ...sticky, campaignId: e.target.value })} />}</Field>
          </div>
        </fieldset>
        <fieldset disabled={!!lotState?.expired}>
          <legend>Person</legend>
          <div className="inline-form">
            <div className="field">
              <label htmlFor="imm-person">Badge or employee ID</label>
              <input
                id="imm-person"
                ref={personRef}
                autoFocus
                autoComplete="off"
                maxLength={40}
                value={person.ref}
                onChange={(e) => {
                  if (!person.ref && e.target.value) setStarted(Date.now());
                  setPerson({ ...person, ref: e.target.value });
                }}
              />
              <span className="hint small" aria-live="polite">
                {person.ref.trim() ? (() => { const w = employeeOrBadge(person.ref, person.mode); return w ? ('employeeId' in w ? `Employee ${w.employeeId}` : `Badge ${w.badgeId}`) : 'Not a valid ID'; })() : 'Scan the badge – Enter records the dose'}
              </span>
            </div>
            <Field label="Read as">
              {(id) => (
                <select id={id} value={person.mode} onChange={(e) => setPerson({ ...person, mode: e.target.value as IdMode })}>
                  <option value="AUTO">Auto</option>
                  <option value="EMPLOYEE">Employee ID</option>
                  <option value="BADGE">Badge</option>
                </select>
              )}
            </Field>
            <Field label="Dose number">{(id) => <input id={id} type="number" min={1} max={10} inputMode="numeric" size={3} value={person.doseNumber} onChange={(e) => setPerson({ ...person, doseNumber: e.target.value })} />}</Field>
            <label className="inline-row">
              <input ref={consentRef} type="checkbox" checked={person.consent} onChange={(e) => setPerson({ ...person, consent: e.target.checked })} /> Consent confirmed
            </label>
          </div>
        </fieldset>
        <button type="submit" disabled={action.busy || !!lotState?.expired}>Record dose</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {log.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Recorded this session ({log.length})</caption>
            <thead>
              <tr>
                <th scope="col">Person</th>
                <th scope="col">Vaccine</th>
                <th scope="col" className="num">Dose</th>
                <th scope="col" className="num">Time taken</th>
              </tr>
            </thead>
            <tbody>
              {log.map((l, i) => (
                <tr key={`${l.ref}-${i}`}>
                  <th scope="row">{l.ref}</th>
                  <td>{l.vaccine}</td>
                  <td className="num">{l.dose}</td>
                  <td className="num">{l.seconds !== undefined ? `${l.seconds} s` : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

interface BatchEntry {
  ref: string;
  consent: boolean;
  dose: string;
}

/** Campaign batch: shared vaccine / lot / site / route; scan people into a list, confirm consent per person, submit once. */
function BatchEntryView({ client, onSessionExpired, vaccines }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { vaccines: { code: string; name: string }[] }) {
  const [shared, setShared] = useState({ vaccineCode: '', lot: '', site: 'LEFT_DELTOID', route: 'IM', campaignId: '' });
  const [entries, setEntries] = useState<BatchEntry[]>([]);
  const [scan, setScan] = useState('');
  const [scanError, setScanError] = useState<string>();
  const [result, setResult] = useState<{ recorded: number; rejected: { ref: string; reason: string }[] }>();
  const action = useAction(onSessionExpired);

  function add() {
    const v = scan.trim();
    if (!employeeOrBadge(v)) {
      setScanError('Not a valid badge or employee ID');
      return;
    }
    if (entries.some((e) => e.ref.toUpperCase() === v.toUpperCase())) {
      setScanError(`${v} is already in the list`);
      return;
    }
    setScanError(undefined);
    setEntries([...entries, { ref: v, consent: false, dose: '' }]);
    setScan('');
  }

  const lot = lotBody(shared.lot);
  const ready = !!shared.vaccineCode && !!lot && entries.length > 0 && entries.every((e) => e.consent);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || !lot) return;
    setResult(undefined);
    let rejected: string[] = [];
    const ok = await action.run(async () => {
      const r = await surveillanceApi(client).administerBatch({
        vaccineCode: shared.vaccineCode,
        ...lot,
        site: shared.site,
        route: shared.route,
        ...(shared.campaignId.trim() ? { campaignId: shared.campaignId.trim() } : {}),
        entries: entries.map((x) => ({ ...employeeOrBadge(x.ref)!, consentConfirmed: x.consent, ...(x.dose ? { doseNumber: Number(x.dose) } : {}) })),
      });
      setResult(r);
      rejected = r.rejected.map((x) => x.ref.toUpperCase());
      return `${r.recorded} recorded, ${r.rejected.length} rejected.`;
    });
    // keep only the rejected people in the list so they can be corrected and resubmitted
    if (ok) setEntries((cur) => cur.filter((x) => rejected.includes(x.ref.toUpperCase())));
  }

  return (
    <section aria-labelledby="batch-title">
      <h2 id="batch-title">Campaign batch</h2>
      <form className="form config-form" onSubmit={submit}>
        <div className="inline-form">
          <Field label="Vaccine" required>{(id) => <VaccineSelect id={id} value={shared.vaccineCode} onChange={(v) => setShared({ ...shared, vaccineCode: v })} vaccines={vaccines} />}</Field>
          <LotField value={shared.lot} onChange={(v) => setShared({ ...shared, lot: v })} />
          <Field label="Site">
            {(id) => (
              <select id={id} value={shared.site} onChange={(e) => setShared({ ...shared, site: e.target.value })}>
                <SelectOptions values={INJECTION_SITES} />
              </select>
            )}
          </Field>
          <Field label="Route">
            {(id) => (
              <select id={id} value={shared.route} onChange={(e) => setShared({ ...shared, route: e.target.value })}>
                {ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            )}
          </Field>
          <Field label="Campaign id (optional)">{(id) => <input id={id} maxLength={60} value={shared.campaignId} onChange={(e) => setShared({ ...shared, campaignId: e.target.value })} />}</Field>
        </div>
        <div className="inline-form">
          <div className={`field${scanError ? ' field-error' : ''}`}>
            <label htmlFor="batch-scan">Scan badge or type employee ID, then Enter</label>
            <input
              id="batch-scan"
              autoComplete="off"
              maxLength={40}
              aria-describedby={scanError ? 'batch-scan-error' : undefined}
              value={scan}
              onChange={(e) => setScan(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  add();
                }
              }}
            />
            {scanError && <span id="batch-scan-error" className="field-error-text small" role="alert">{scanError}</span>}
          </div>
          <button type="button" className="secondary" onClick={add}>Add to list</button>
          {entries.length > 0 && (
            <button type="button" className="secondary" onClick={() => setEntries(entries.map((x) => ({ ...x, consent: true })))}>Consent confirmed for all</button>
          )}
        </div>
        {entries.length > 0 && (
          <div className="table-wrap">
            <table>
              <caption>{entries.length} in this batch</caption>
              <thead>
                <tr>
                  <th scope="col">Person</th>
                  <th scope="col">Consent</th>
                  <th scope="col">Dose (optional)</th>
                  <th scope="col"><span className="visually-hidden">Remove</span></th>
                </tr>
              </thead>
              <tbody>
                {entries.map((x, i) => (
                  <tr key={x.ref}>
                    <th scope="row">{x.ref}</th>
                    <td><input type="checkbox" aria-label={`Consent confirmed for ${x.ref}`} checked={x.consent} onChange={(e) => setEntries(entries.map((y, j) => (j === i ? { ...y, consent: e.target.checked } : y)))} /></td>
                    <td><input type="number" min={1} max={10} size={3} aria-label={`Dose number for ${x.ref}`} value={x.dose} onChange={(e) => setEntries(entries.map((y, j) => (j === i ? { ...y, dose: e.target.value } : y)))} /></td>
                    <td><button type="button" className="link-button danger" onClick={() => setEntries(entries.filter((_, j) => j !== i))} aria-label={`Remove ${x.ref}`}>Remove</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {entries.some((x) => !x.consent) && <p className="small text-amber">Confirm consent for everyone in the list before recording.</p>}
        <button type="submit" disabled={action.busy || !ready}>Record batch</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {result && result.rejected.length > 0 && (
        <ul className="plain-list" aria-label="Rejected entries">
          {result.rejected.map((r) => (
            <li key={r.ref}><ToneBadge tone="red">{r.ref}</ToneBadge> {r.reason}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function RequirementMatrix({ client, session, onSessionExpired, reqs, reload }: ScreenProps & { reqs: ImmunisationRequirement[]; reload: () => void }) {
  const write = can(session.roles, 'immunisationRequirementWrite');
  const [editing, setEditing] = useState<ImmunisationRequirement>();
  const action = useAction(onSessionExpired);
  const blank: ImmunisationRequirement = { id: '', jobCode: '', hazardCode: '', vaccineCode: '', vaccineName: '', doses: 1, boosterMonths: null, titreRequired: false, mandatory: true };

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing || !editing.vaccineCode.trim() || !editing.vaccineName.trim() || (!editing.jobCode?.trim() && !editing.hazardCode?.trim())) return;
    const r = editing;
    const id = r.id || `${(r.jobCode || r.hazardCode || '').trim().toUpperCase()}-${r.vaccineCode.trim().toUpperCase()}`;
    const ok = await action.run(async () => {
      await surveillanceApi(client).saveRequirement({
        ...r,
        id,
        jobCode: r.jobCode?.trim().toUpperCase() || null,
        hazardCode: r.hazardCode?.trim().toUpperCase() || null,
        vaccineCode: r.vaccineCode.trim().toUpperCase(),
        vaccineName: r.vaccineName.trim(),
      });
      return `Requirement ${id} saved.`;
    });
    if (ok) {
      setEditing(undefined);
      reload();
    }
  }

  return (
    <section aria-labelledby="matrix-title">
      <h2 id="matrix-title">Requirement matrix</h2>
      {write && <button type="button" className="secondary" onClick={() => { action.reset(); setEditing(blank); }}>Add requirement</button>}
      <ActionFeedback error={editing ? undefined : action.error} message={action.message} />
      {reqs.length === 0 ? (
        <Empty>No requirements.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Immunisation requirements by job or hazard</caption>
            <thead>
              <tr>
                <th scope="col">Job / hazard</th>
                <th scope="col">Vaccine</th>
                <th scope="col" className="num">Doses</th>
                <th scope="col">Booster</th>
                <th scope="col">Titre</th>
                <th scope="col">Mandatory</th>
                {write && <th scope="col"><span className="visually-hidden">Edit</span></th>}
              </tr>
            </thead>
            <tbody>
              {[...reqs].sort((a, b) => (a.jobCode ?? a.hazardCode ?? '').localeCompare(b.jobCode ?? b.hazardCode ?? '')).map((r) => (
                <tr key={r.id}>
                  <th scope="row">{r.jobCode ? humanise(r.jobCode) : r.hazardCode ? `Hazard: ${humanise(r.hazardCode)}` : '–'}</th>
                  <td>{r.vaccineName} <span className="small muted mono">{r.vaccineCode}</span></td>
                  <td className="num">{r.doses}</td>
                  <td>{r.boosterMonths ? `${r.boosterMonths} months` : '–'}</td>
                  <td>{r.titreRequired ? 'Required' : '–'}</td>
                  <td>{r.mandatory ? 'Yes' : 'Recommended'}</td>
                  {write && <td><button type="button" className="secondary small-button" onClick={() => { action.reset(); setEditing({ ...r }); }} aria-label={`Edit ${r.id}`}>Edit</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <Dialog title={editing.id ? `Edit ${editing.id}` : 'Add requirement'} onClose={() => setEditing(undefined)}>
          <form className="form" onSubmit={save}>
            <div className="inline-form">
              <Field label="Job code">{(id) => <input id={id} maxLength={40} value={editing.jobCode ?? ''} onChange={(e) => setEditing({ ...editing, jobCode: e.target.value })} />}</Field>
              <Field label="or hazard code">{(id) => <input id={id} maxLength={40} value={editing.hazardCode ?? ''} onChange={(e) => setEditing({ ...editing, hazardCode: e.target.value })} />}</Field>
            </div>
            <div className="inline-form">
              <Field label="Vaccine code" required>{(id) => <input id={id} required maxLength={30} value={editing.vaccineCode} onChange={(e) => setEditing({ ...editing, vaccineCode: e.target.value })} />}</Field>
              <Field label="Vaccine name" required>{(id) => <input id={id} required maxLength={80} value={editing.vaccineName} onChange={(e) => setEditing({ ...editing, vaccineName: e.target.value })} />}</Field>
            </div>
            <div className="inline-form">
              <Field label="Doses" required>{(id) => <input id={id} type="number" min={1} max={10} required value={editing.doses} onChange={(e) => setEditing({ ...editing, doses: Number(e.target.value) })} />}</Field>
              <Field label="Booster (months)">{(id) => <input id={id} type="number" min={1} max={240} value={editing.boosterMonths ?? ''} onChange={(e) => setEditing({ ...editing, boosterMonths: e.target.value ? Number(e.target.value) : null })} />}</Field>
            </div>
            <label className="inline-row"><input type="checkbox" checked={editing.titreRequired} onChange={(e) => setEditing({ ...editing, titreRequired: e.target.checked })} /> Titre required</label>
            <label className="inline-row"><input type="checkbox" checked={editing.mandatory} onChange={(e) => setEditing({ ...editing, mandatory: e.target.checked })} /> Mandatory</label>
            <div className="actions">
              <button type="submit" disabled={action.busy || (!editing.jobCode?.trim() && !editing.hazardCode?.trim())}>Save requirement</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

function DueList({ client, onSessionExpired, vaccines }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { vaccines: { code: string; name: string }[] }) {
  const [f, setF] = useState({ vaccine: '', subsidiary: '', department: '' });
  const [applied, setApplied] = useState(f);
  const [status, setStatus] = useState('');
  const list = useAsync(() => surveillanceApi(client).immunisationDue(applied), onSessionExpired, [applied]);
  const rows = (list.data ?? []).filter((r) => !status || r.status === status);
  return (
    <section aria-labelledby="imm-due-title">
      <h2 id="imm-due-title">Due list</h2>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied({ vaccine: f.vaccine, subsidiary: f.subsidiary.trim().toUpperCase(), department: f.department.trim().toUpperCase() }); }}>
        <Field label="Vaccine">
          {(id) => (
            <select id={id} value={f.vaccine} onChange={(e) => setF({ ...f, vaccine: e.target.value })}>
              <option value="">All</option>
              {vaccines.map((v) => <option key={v.code} value={v.code}>{v.name}</option>)}
            </select>
          )}
        </Field>
        <Field label="Subsidiary">{(id) => <input id={id} maxLength={20} value={f.subsidiary} onChange={(e) => setF({ ...f, subsidiary: e.target.value })} />}</Field>
        <Field label="Department">{(id) => <input id={id} maxLength={20} value={f.department} onChange={(e) => setF({ ...f, department: e.target.value })} />}</Field>
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={IMMUNISATION_STATUSES} empty="All" />
            </select>
          )}
        </Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nobody due.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} due</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Vaccine</th>
                <th scope="col" className="num">Doses</th>
                <th scope="col">Next due</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.employeeId}-${r.vaccineCode}`}>
                  <th scope="row">{r.displayName} <span className="small muted">{r.employeeId}</span></th>
                  <td>{r.vaccineName}</td>
                  <td className="num">{r.dosesGiven} / {r.dosesRequired}</td>
                  <td>{formatDay(r.nextDueOn)}</td>
                  <td><ToneBadge tone={STATUS_TONE[r.status] ?? 'amber'}>{humanise(r.status)}</ToneBadge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function TitreForm({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const empty = { employeeId: '', antigen: 'HEP_B', value: '', unit: 'mIU/mL', protective: false, testedOn: isoDay(new Date()) };
  const [f, setF] = useState(empty);
  const action = useAction(onSessionExpired);
  const ready = !!f.employeeId.trim() && !!f.antigen.trim() && f.value !== '' && Number.isFinite(Number(f.value)) && !!f.unit.trim() && !!f.testedOn;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    const ok = await action.run(async () => {
      await surveillanceApi(client).titre({ employeeId: f.employeeId.trim().toUpperCase(), antigen: f.antigen.trim().toUpperCase(), value: Number(f.value), unit: f.unit.trim(), protective: f.protective, testedOn: f.testedOn });
      return `Titre recorded for ${f.employeeId.trim().toUpperCase()}.`;
    });
    if (ok) setF(empty);
  }
  return (
    <section aria-labelledby="titre-title">
      <h2 id="titre-title">Record titre</h2>
      <form className="form" onSubmit={submit}>
        <div className="inline-form">
          <Field label="Employee ID" required>{(id) => <input id={id} required maxLength={20} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}</Field>
          <Field label="Antigen" required>{(id) => <input id={id} required maxLength={30} value={f.antigen} onChange={(e) => setF({ ...f, antigen: e.target.value })} />}</Field>
          <Field label="Value" required>{(id) => <input id={id} required type="number" step="any" min={0} value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} />}</Field>
          <Field label="Unit" required>{(id) => <input id={id} required maxLength={20} value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })} />}</Field>
          <Field label="Tested on" required>{(id) => <input id={id} required type="date" max={isoDay(new Date())} value={f.testedOn} onChange={(e) => setF({ ...f, testedOn: e.target.value })} />}</Field>
        </div>
        <label className="inline-row"><input type="checkbox" checked={f.protective} onChange={(e) => setF({ ...f, protective: e.target.checked })} /> Protective</label>
        <button type="submit" disabled={action.busy || !ready}>Record titre</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/surveillance/immunisation` – requirement matrix, due list, fast entry (< 1 min per person) and campaign batch mode (E07-F05). */
export function ImmunisationScreen(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const reqs = useAsync(() => surveillanceApi(client).requirements(), onSessionExpired);
  const clinician = can(session.roles, 'immunisationAdminister');
  const sections: [Section, string, boolean][] = [
    ['fast', 'Fast entry', clinician],
    ['batch', 'Campaign batch', clinician],
    ['due', 'Due list', can(session.roles, 'immunisationDue')],
    ['matrix', 'Requirement matrix', true],
    ['titre', 'Titres', clinician],
  ];
  const visible = sections.filter(([, , show]) => show);
  const [section, setSection] = useState<Section>(visible[0]?.[0] ?? 'matrix');
  const vaccines = vaccinesOf(reqs.data);
  return (
    <section>
      <PageTitle sub="Doses are mirrored into the employee's immunisation record. Expired lots are refused.">Immunisation</PageTitle>
      <div className="mode-switch" role="group" aria-label="Section">
        {visible.map(([id, label]) => (
          <button key={id} type="button" className={`secondary small-button${section === id ? ' mode-active' : ''}`} aria-pressed={section === id} onClick={() => setSection(id)}>
            {label}
          </button>
        ))}
      </div>
      {!!reqs.error && <ErrorAlert error={reqs.error} />}
      {section === 'fast' && clinician && <FastEntry client={client} onSessionExpired={onSessionExpired} vaccines={vaccines} />}
      {section === 'batch' && clinician && <BatchEntryView client={client} onSessionExpired={onSessionExpired} vaccines={vaccines} />}
      {section === 'due' && <DueList client={client} onSessionExpired={onSessionExpired} vaccines={vaccines} />}
      {section === 'matrix' && (reqs.loading && !reqs.data ? <Loading /> : <RequirementMatrix {...props} reqs={reqs.data ?? []} reload={reqs.reload} />)}
      {section === 'titre' && clinician && <TitreForm client={client} onSessionExpired={onSessionExpired} />}
    </section>
  );
}
