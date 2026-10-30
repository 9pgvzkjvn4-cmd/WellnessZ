import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { SCREENING_TYPES, promotionApi, type MeasureKey, type ScreeningEvent, type ScreeningResultResponse } from '../../api/promotion';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import { DEFAULT_THRESHOLDS, flagMeasures, flagTone, isAbnormal, list, MEASURES_FOR_TYPE, parseMeasures, responseFlags, validateScreeningEvent } from '../../domain/promotion';
import { employeeOrBadge } from '../../domain/surveillance';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

type Props = Pick<ScreenProps, 'client' | 'onSessionExpired'>;

function NewEvent({ client, onSessionExpired, onCreated, listCampaigns }: Props & { onCreated: (e: ScreeningEvent) => void; listCampaigns: boolean }) {
  // GET /promotion/campaigns is HEALTH_PROMOTION_LEAD only – clinicians create events without a campaign link.
  const campaigns = useAsync(() => (listCampaigns ? promotionApi(client).campaigns() : Promise.resolve([])), onSessionExpired, [listCampaigns]);
  const [f, setF] = useState({ type: 'BIOMETRIC', siteCode: '', date: todayIso(), campaignId: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateScreeningEvent(f);
    setErrors(errs);
    if (errs.length) return;
    let created: ScreeningEvent | undefined;
    const ok = await action.run(async () => {
      created = await promotionApi(client).createScreeningEvent({ type: f.type as 'BIOMETRIC', siteCode: f.siteCode.trim().toUpperCase(), date: f.date, ...(f.campaignId ? { campaignId: f.campaignId } : {}) });
      return 'Screening event created.';
    });
    if (ok && created) onCreated(created);
  }
  return (
    <details className="card">
      <summary>New screening event</summary>
      <form className="form inline-form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="se-type" label="Type" required error={errorFor(errors, 'se-type')}>{(db) => <select id="se-type" aria-describedby={db} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}><SelectOptions values={SCREENING_TYPES} /></select>}</IdField>
        <IdField id="se-site" label="Site" required error={errorFor(errors, 'se-site')}>{(db) => <input id="se-site" size={12} aria-describedby={db} value={f.siteCode} onChange={(e) => setF({ ...f, siteCode: e.target.value })} />}</IdField>
        <IdField id="se-date" label="Date" required error={errorFor(errors, 'se-date')}>{(db) => <input id="se-date" type="date" aria-describedby={db} value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />}</IdField>
        {(campaigns.data ?? []).length > 0 && (
          <Field label="Campaign (optional)">
            {(id) => (
              <select id={id} value={f.campaignId} onChange={(e) => setF({ ...f, campaignId: e.target.value })}>
                <option value="">None</option>
                {(campaigns.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
              </select>
            )}
          </Field>
        )}
        <button type="submit" disabled={action.busy}>Create event</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </details>
  );
}

function AddParticipants({ client, onSessionExpired, event, onAdded }: Props & { event: ScreeningEvent; onAdded: () => void }) {
  const [ids, setIds] = useState('');
  const [error, setError] = useState<string>();
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const parsed = list(ids);
    const bad = parsed.filter((x) => !employeeOrBadge(x, 'EMPLOYEE') || !/^[A-Z]\d{3,8}$/.test(x));
    if (parsed.length === 0 || bad.length > 0) {
      setError(parsed.length === 0 ? 'Enter at least one employee ID.' : `Not employee IDs: ${bad.join(', ')}`);
      return;
    }
    setError(undefined);
    const ok = await action.run(async () => {
      await promotionApi(client).addParticipants(event.id, parsed);
      return `${parsed.length} participant${parsed.length === 1 ? '' : 's'} added.`;
    });
    if (ok) {
      setIds('');
      onAdded();
    }
  }
  return (
    <form className="form inline-form" onSubmit={submit} noValidate>
      <IdField id="sp-ids" label="Add participants (employee IDs)" hint="Comma or space separated, e.g. E1001 E1004" error={error}>{(db) => <input id="sp-ids" size={40} aria-describedby={db} value={ids} onChange={(e) => setIds(e.target.value)} />}</IdField>
      <button type="submit" className="secondary" disabled={action.busy}>Add participants</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** Result entry (clinicians only) with live threshold flags; abnormal results open a follow-up case (≤ 48 h) server-side. */
function ResultEntry({ client, onSessionExpired, event, onRecorded }: Props & { event: ScreeningEvent; onRecorded: () => void }) {
  const keys: MeasureKey[] = MEASURES_FOR_TYPE[event.type]?.length ? MEASURES_FOR_TYPE[event.type]! : (Object.keys(DEFAULT_THRESHOLDS) as MeasureKey[]);
  const [employeeId, setEmployeeId] = useState('');
  const [form, setForm] = useState<Partial<Record<MeasureKey, string>>>({});
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [result, setResult] = useState<ScreeningResultResponse>();
  const action = useAction(onSessionExpired);
  const preview = flagMeasures(parseMeasures(form).measures);
  const pending = (event.participants ?? []).filter((p) => !p.resultRecorded);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const { measures, errors: errs } = parseMeasures(form);
    const who = employeeOrBadge(employeeId, 'EMPLOYEE');
    if (!who) errs.unshift({ field: 'sr-employee', message: 'Choose the participant.' });
    setErrors(errs.map((x) => (x.field === 'm-first' ? { ...x, field: `m-${keys[0]}` } : x)));
    if (errs.length || !who || !('employeeId' in who)) return;
    setResult(undefined);
    let r: ScreeningResultResponse | undefined;
    const ok = await action.run(async () => {
      r = await promotionApi(client).recordResult(event.id, { employeeId: who.employeeId, measures });
    });
    if (ok && r) {
      setResult(r);
      setForm({});
      setEmployeeId('');
      onRecorded();
    }
  }

  return (
    <section aria-labelledby="sr-title">
      <h3 id="sr-title">Enter results</h3>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="sr-employee" label="Participant" required error={errorFor(errors, 'sr-employee')}>
          {(db) =>
            pending.length > 0 ? (
              <select id="sr-employee" aria-describedby={db} value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
                <option value="">Choose…</option>
                {pending.map((p) => <option key={p.employeeId} value={p.employeeId}>{p.displayName ? `${p.displayName} (${p.employeeId})` : p.employeeId}</option>)}
              </select>
            ) : (
              <input id="sr-employee" size={10} aria-describedby={db} value={employeeId} onChange={(e) => setEmployeeId(e.target.value)} />
            )
          }
        </IdField>
        <div className="inline-form">
          {keys.map((k) => {
            const t = DEFAULT_THRESHOLDS[k];
            const flag = preview.find((p) => p.measure === k);
            return (
              <IdField key={k} id={`m-${k}`} label={`${t.label} (${t.unit})`} error={errorFor(errors, `m-${k}`)}>
                {(db) => (
                  <>
                    <input id={`m-${k}`} inputMode="decimal" size={6} aria-describedby={db} value={form[k] ?? ''} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
                    {flag && <ToneBadge tone={flagTone(flag.flag)}>{flag.flag === 'NORMAL' ? 'In range' : flag.flag === 'HIGH' ? 'High' : 'Low'}</ToneBadge>}
                  </>
                )}
              </IdField>
            );
          })}
        </div>
        {isAbnormal(preview) && <Alert kind="warning">Outside the screening thresholds – saving opens a follow-up case (within 48 h) and sends a result letter.</Alert>}
        <button type="submit" disabled={action.busy}>Save result</button>
      </form>
      <ActionFeedback error={action.error} />
      {result && (
        <div aria-live="polite">
          {result.abnormal ? (
            <Alert kind="warning">
              Abnormal result{responseFlags(result).length ? ` (${responseFlags(result).join(', ')})` : ''}. Follow-up case{' '}
              {result.followUpCaseId ? <a href={`#/cases/${encodeURIComponent(result.followUpCaseId)}`}>{result.followUpCaseRef ?? 'opened'}</a> : 'opened'}
              {result.followUpDueAt ? ` – contact due by ${formatDate(result.followUpDueAt)}` : ' – contact within 48 h'}.
              {result.letterDocumentId && <> <a href={documentContentUrl(result.letterDocumentId)} target="_blank" rel="noopener">Result letter</a></>}
            </Alert>
          ) : (
            <Alert kind="success">Result saved – within thresholds.</Alert>
          )}
        </div>
      )}
    </section>
  );
}

function EventDetail({ client, session, onSessionExpired, eventId }: ScreenProps & { eventId: string }) {
  const detail = useAsync(() => promotionApi(client).screeningEvent(eventId), onSessionExpired, [eventId]);
  const e = detail.data;
  const clinician = can(session.roles, 'screeningResultWrite');
  const count = e?.participantCount ?? e?.participants?.length ?? 0;
  return (
    <section className="card" aria-labelledby="se-detail">
      <h2 id="se-detail">{e ? `${humanise(e.type)} screening – ${e.siteCode}, ${formatDay(e.date)}` : 'Screening event'}</h2>
      {detail.loading && !e && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {e && (
        <>
          <p>{count} participant{count === 1 ? '' : 's'}{typeof e.resultsRecorded === 'number' ? ` · ${e.resultsRecorded} results recorded` : ''}</p>
          {can(session.roles, 'screeningEventWrite') && <AddParticipants client={client} onSessionExpired={onSessionExpired} event={e} onAdded={detail.reload} />}
          {clinician && e.participants && e.participants.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption>Participants</caption>
                <thead><tr><th scope="col">Participant</th><th scope="col">Result</th></tr></thead>
                <tbody>
                  {e.participants.map((p) => (
                    <tr key={p.employeeId}>
                      <th scope="row">{p.displayName ? `${p.displayName} (${p.employeeId})` : p.employeeId}</th>
                      <td>{p.resultRecorded ? p.abnormal ? <ToneBadge tone="red">Abnormal – follow-up</ToneBadge> : <ToneBadge tone="green">Recorded</ToneBadge> : <ToneBadge tone="muted">Pending</ToneBadge>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {clinician ? <ResultEntry client={client} onSessionExpired={onSessionExpired} event={e} onRecorded={detail.reload} /> : <p className="small muted">Results are entered by clinicians and are not shown here.</p>}
        </>
      )}
    </section>
  );
}

/** `#/promotion/screening` – screening events, participants and (clinicians) result entry with threshold flags (E12). */
export function ScreeningEvents(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const list = useAsync(() => promotionApi(client).screeningEvents(), onSessionExpired);
  const [selected, setSelected] = useState<string>();
  const rows = [...(list.data ?? [])].sort((a, b) => b.date.localeCompare(a.date));
  return (
    <section>
      <PageTitle sub="Biometric, MSK, hearing, vision and wellbeing screening. Results outside thresholds open a follow-up case for OH.">Screening events</PageTitle>
      {can(session.roles, 'screeningEventWrite') && <NewEvent client={client} onSessionExpired={onSessionExpired} listCampaigns={can(session.roles, 'promotionCampaigns')} onCreated={(e) => { list.reload(); setSelected(e.id); }} />}
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No screening events.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Screening events, latest first</caption>
            <thead><tr><th scope="col">Event</th><th scope="col">Site</th><th scope="col">Date</th><th scope="col" className="num">Participants</th></tr></thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id} aria-selected={e.id === selected}>
                  <th scope="row"><button type="button" className="link-button" onClick={() => setSelected(e.id)}>{humanise(e.type)} screening</button></th>
                  <td>{e.siteCode}</td>
                  <td>{formatDay(e.date)}</td>
                  <td className="num">{e.participantCount ?? e.participants?.length ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selected && <EventDetail key={selected} {...props} eventId={selected} />}
    </section>
  );
}
