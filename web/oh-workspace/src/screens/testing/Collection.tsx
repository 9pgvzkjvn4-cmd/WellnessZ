import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { CUSTODY_EVENTS, REFUSAL_REASONS, SPECIMEN_TYPES, testingApi, type CustodyEvent, type RefusalReason, type Requirement, type SpecimenType } from '../../api/testing';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { humanise, localToIso, toLocalInput } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { serverFieldError } from '../../domain/support';
import { emptyCollectionForm, normaliseSpecimenId, toCollectionBody, validateCollection, validateRefusal, type CollectionForm } from '../../domain/testing';
import { errorFor } from '../../domain/validation';
import { useNow } from '../support/useNow';
import type { ScreenProps } from '../types';
import { WindowBadge } from './Requirements';

const OPEN = new Set(['REQUIRED', 'NOTIFIED', 'DISPATCHED']);
const CCF_FIELDS = { specimenId: 'ccf-specimen', specimenType: 'ccf-type', collectedAt: 'ccf-collected', siteCode: 'ccf-site', temperatureInRange: 'ccf-temperature', sealIntact: 'ccf-seal', donorIdentityChecked: 'ccf-identity', breathScreenResult: 'ccf-breath' };

/** `#/testing/collection` – COLLECTOR: electronic chain-of-custody form (barcode / keyboard-wedge specimen ID), custody events, refusal. */
export function TestingCollection(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const now = useNow(30_000);
  const list = useAsync(() => testingApi(client).requirements(), onSessionExpired);
  const [selected, setSelected] = useState<Requirement>();
  const [collected, setCollected] = useState<{ specimenId: string; ref: string }>();
  const rows = (list.data ?? []).filter((r) => OPEN.has(r.status)).sort((a, b) => a.dueAt.localeCompare(b.dueAt));

  return (
    <section>
      <PageTitle sub="Collect, seal and hand over specimens. You never see results.">Specimen collection</PageTitle>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No donors waiting for collection.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Waiting for collection</caption>
            <thead><tr><th scope="col">Window</th><th scope="col">Reference</th><th scope="col">Donor</th><th scope="col">Reason</th><th scope="col">Panels</th><th scope="col">Site</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} aria-selected={selected?.id === r.id}>
                  <td><WindowBadge r={r} now={now} /></td>
                  <th scope="row">{r.ref}</th>
                  <td>{r.displayName} <span className="small muted">{r.employeeId}</span></td>
                  <td>{humanise(r.reason)}</td>
                  <td>{r.panels.map(humanise).join(', ')}</td>
                  <td>{r.siteCode ?? '–'}</td>
                  <td><button type="button" className="secondary small-button" aria-label={`Start collection ${r.ref}`} onClick={() => { setCollected(undefined); setSelected(r); }}>Start e-CCF</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selected && (
        <CcfForm
          key={selected.id}
          {...props}
          req={selected}
          onDone={(specimenId) => {
            setCollected(specimenId ? { specimenId, ref: selected.ref } : undefined);
            setSelected(undefined);
            list.reload();
          }}
        />
      )}
      {collected && <CustodyPanel {...props} specimenId={collected.specimenId} testRef={collected.ref} />}
    </section>
  );
}

function CcfForm({ client, onSessionExpired, req, onDone }: ScreenProps & { req: Requirement; onDone: (specimenId?: string) => void }) {
  const api = testingApi(client);
  const defaultType: SpecimenType | '' = req.panels.length === 1 && req.panels[0] === 'ALCOHOL_BREATH' ? 'BREATH' : '';
  const [form, setForm] = useState<CollectionForm>(() => ({ ...emptyCollectionForm(req.siteCode ?? '', toLocalInput()), specimenType: defaultType }));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [refusal, setRefusal] = useState<{ reason: RefusalReason | ''; observations: string }>();
  const [refusalErrors, setRefusalErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const typeRef = useRef<HTMLSelectElement>(null);
  const collectedRef = useRef<HTMLInputElement>(null);
  const set = (patch: Partial<CollectionForm>) => setForm({ ...form, ...patch });
  const err = (f: string) => errorFor(errors, f);

  /** Keyboard-wedge scanners type the barcode followed by Enter: accept the scan instead of submitting the form. */
  function onSpecimenKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    set({ specimenId: normaliseSpecimenId(form.specimenId) });
    (form.specimenType ? collectedRef.current : typeRef.current)?.focus();
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateCollection(form);
    setErrors(errs);
    if (errs.length) return;
    const body = toCollectionBody(form);
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        await api.recordCollection(req.id, body);
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) onDone(body.specimenId);
    else {
      const fe = serverFieldError(failure, CCF_FIELDS);
      if (fe) setErrors([fe]);
    }
  }
  async function refuse(e: FormEvent) {
    e.preventDefault();
    if (!refusal) return;
    const errs = validateRefusal(refusal);
    setRefusalErrors(errs);
    if (errs.length) return;
    if (await action.run(async () => { await api.refusal(req.id, { reason: refusal.reason as RefusalReason, observations: refusal.observations.trim() }); })) {
      setRefusal(undefined);
      onDone();
    }
  }

  const type = form.specimenType;
  return (
    <section className="card" aria-labelledby="ccf-title">
      <h2 id="ccf-title">Chain of custody – {req.ref} · {req.displayName}</h2>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="form-grid">
          <IdField id="ccf-specimen" label="Specimen ID" required error={err('ccf-specimen')} hint="Scan the barcode label (scanner sends Enter) or type it.">
            {(d) => (
              <input
                id="ccf-specimen"
                className="mono"
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                aria-describedby={d}
                aria-invalid={!!err('ccf-specimen')}
                value={form.specimenId}
                onKeyDown={onSpecimenKey}
                onBlur={() => set({ specimenId: normaliseSpecimenId(form.specimenId) })}
                onChange={(e) => set({ specimenId: e.target.value })}
              />
            )}
          </IdField>
          <IdField id="ccf-type" label="Specimen type" required error={err('ccf-type')}>
            {(d) => <select id="ccf-type" ref={typeRef} aria-describedby={d} value={type} onChange={(e) => set({ specimenType: e.target.value as SpecimenType })}><SelectOptions values={SPECIMEN_TYPES} empty="Choose…" /></select>}
          </IdField>
          <IdField id="ccf-collected" label="Collected at" required error={err('ccf-collected')}>
            {(d) => <input id="ccf-collected" ref={collectedRef} type="datetime-local" aria-describedby={d} value={form.collectedAt} onChange={(e) => set({ collectedAt: e.target.value })} />}
          </IdField>
          <IdField id="ccf-site" label="Collection site" required error={err('ccf-site')}>
            {(d) => <input id="ccf-site" aria-describedby={d} value={form.siteCode} onChange={(e) => set({ siteCode: e.target.value.toUpperCase() })} />}
          </IdField>
        </div>
        <label className={`inline-row${err('ccf-identity') ? ' field-error' : ''}`}>
          <input id="ccf-identity" type="checkbox" checked={form.donorIdentityChecked} aria-invalid={!!err('ccf-identity')} onChange={(e) => set({ donorIdentityChecked: e.target.checked })} /> Donor photo identity checked
        </label>
        {type === 'URINE' && (
          <fieldset id="ccf-temperature" tabIndex={-1} className={err('ccf-temperature') ? 'field-error' : undefined}>
            <legend>Temperature in range (32–38 °C within 4 minutes)<span aria-hidden="true"> *</span></legend>
            {err('ccf-temperature') && <span className="field-error-text small">{err('ccf-temperature')}</span>}
            {(['yes', 'no'] as const).map((v) => (
              <label key={v} className="inline-row"><input type="radio" name="ccf-temp" value={v} checked={form.temperatureInRange === v} onChange={() => set({ temperatureInRange: v })} /> {v === 'yes' ? 'Yes' : 'No – observed recollection required'}</label>
            ))}
          </fieldset>
        )}
        {type && type !== 'BREATH' && (
          <label className={`inline-row${err('ccf-seal') ? ' field-error' : ''}`}>
            <input id="ccf-seal" type="checkbox" checked={form.sealIntact} aria-invalid={!!err('ccf-seal')} onChange={(e) => set({ sealIntact: e.target.checked })} /> Tamper-evident seal applied in front of the donor and intact
          </label>
        )}
        {type === 'URINE' && (
          <label className="inline-row"><input type="checkbox" checked={form.splitSpecimen} onChange={(e) => set({ splitSpecimen: e.target.checked })} /> Split specimen (bottles A and B)</label>
        )}
        {type === 'BREATH' && (
          <IdField id="ccf-breath" label="Breath screen result (g/210 L)" required error={err('ccf-breath')} hint="Evidential breath device reading – e.g. 0.000">
            {(d) => <input id="ccf-breath" type="number" inputMode="decimal" step="0.001" min={0} max={1} aria-describedby={d} aria-invalid={!!err('ccf-breath')} value={form.breathScreenResult} onChange={(e) => set({ breathScreenResult: e.target.value })} />}
          </IdField>
        )}
        <Field label="Remarks">{(id) => <textarea id={id} rows={2} maxLength={2000} value={form.remarks} onChange={(e) => set({ remarks: e.target.value })} />}</Field>
        <div className="actions">
          <button type="submit" disabled={action.busy}>Sign &amp; submit e-CCF</button>
          <button type="button" className="danger-button" onClick={() => { setRefusalErrors([]); setRefusal({ reason: '', observations: '' }); }}>Record refusal / no-show</button>
          <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={errors.length || refusal ? undefined : action.error} />
      {refusal && (
        <Dialog title={`Refusal – ${req.ref}`} onClose={() => setRefusal(undefined)}>
          <form className="form" onSubmit={refuse} noValidate>
            <ErrorSummary errors={refusalErrors} />
            <p className="small">A refusal goes to the MRO for review and is treated like a verified positive if confirmed.</p>
            <IdField id="ref-reason" label="Reason" required error={errorFor(refusalErrors, 'ref-reason')}>
              {(d) => <select id="ref-reason" aria-describedby={d} value={refusal.reason} onChange={(e) => setRefusal({ ...refusal, reason: e.target.value as RefusalReason })}><SelectOptions values={REFUSAL_REASONS} empty="Choose…" /></select>}
            </IdField>
            <IdField id="ref-observations" label="Observations" required error={errorFor(refusalErrors, 'ref-observations')}>
              {(d) => <textarea id="ref-observations" rows={3} maxLength={2000} aria-describedby={d} value={refusal.observations} onChange={(e) => setRefusal({ ...refusal, observations: e.target.value })} />}
            </IdField>
            <div className="actions">
              <button type="submit" className="danger-button" disabled={action.busy}>Record refusal</button>
              <button type="button" className="secondary" onClick={() => setRefusal(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

function CustodyPanel({ client, session, onSessionExpired, specimenId, testRef }: ScreenProps & { specimenId: string; testRef: string }) {
  const [form, setForm] = useState<{ event: CustodyEvent; at: string; by: string }>({ event: 'SEALED', at: toLocalInput(), by: session.username ?? session.name ?? '' });
  const [events, setEvents] = useState<{ event: CustodyEvent; at: string }[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const at = localToIso(form.at);
    if (!at || !form.by.trim()) return;
    if (await action.run(async () => { await testingApi(client).custody(specimenId, { event: form.event, at, by: form.by.trim() }); return `${humanise(form.event)} recorded for ${specimenId}.`; })) {
      setEvents((x) => [...x, { event: form.event, at }]);
      const next = CUSTODY_EVENTS[CUSTODY_EVENTS.indexOf(form.event) + 1];
      setForm({ ...form, event: next ?? form.event, at: toLocalInput() });
    }
  }
  return (
    <section className="card" aria-labelledby="custody-title">
      <h2 id="custody-title">Custody – specimen <span className="mono">{specimenId}</span> ({testRef})</h2>
      <p><ToneBadge tone="green">Collected</ToneBadge> The signed chain-of-custody form is stored as a restricted document.</p>
      {events.length > 0 && (
        <ol className="plain-list">{events.map((x, i) => <li key={i}>{humanise(x.event)} – {new Date(x.at).toLocaleString()}</li>)}</ol>
      )}
      <form className="form inline-form" onSubmit={submit} aria-label="Record custody event">
        <Field label="Event">{(id) => <select id={id} value={form.event} onChange={(e) => setForm({ ...form, event: e.target.value as CustodyEvent })}><SelectOptions values={CUSTODY_EVENTS} /></select>}</Field>
        <Field label="At">{(id) => <input id={id} type="datetime-local" value={form.at} onChange={(e) => setForm({ ...form, at: e.target.value })} />}</Field>
        <Field label="By">{(id) => <input id={id} value={form.by} onChange={(e) => setForm({ ...form, by: e.target.value })} />}</Field>
        <button type="submit" className="secondary" disabled={action.busy}>Record custody event</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}
