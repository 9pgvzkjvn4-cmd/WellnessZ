import { useState, type FormEvent, type ReactNode } from 'react';
import { documentContentUrl } from '../../api/platform';
import { GOV_MEDICAL_STATUSES, GOV_MEDICAL_TYPES, PRE_EMPLOYMENT_OUTCOMES, PRE_EMPLOYMENT_STATUSES, surveillanceApi, type PreEmployment } from '../../api/surveillance';
import { can } from '../../auth/roles';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { clinicalContentField, isClinicalContentError, slaTone } from '../../domain/absence';
import type { ScreenProps } from '../types';

/** Questionnaire answers come as a map `{code: value}` or a list `[{code, text, value}]` – render either. */
function Answers({ data }: { data: unknown }) {
  if (!data) return <p className="small muted">Questionnaire not completed.</p>;
  let items: [string, ReactNode][] = [];
  if (Array.isArray(data)) {
    items = data.map((x, i) => {
      const o = (x && typeof x === 'object' ? x : {}) as Record<string, unknown>;
      return [String(o.text ?? o.code ?? i + 1), String(o.value ?? o.answer ?? '–')];
    });
  } else if (typeof data === 'object') {
    const o = data as Record<string, unknown>;
    const src = (o.answers && typeof o.answers === 'object' ? o.answers : o) as Record<string, unknown>;
    items = Object.entries(src).map(([k, v]) => [humanise(k), typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v ?? '–')]);
  }
  return items.length ? <Dl items={items} /> : <p className="small muted">No answers.</p>;
}

function Detail({ client, session, onSessionExpired, id, onChanged }: ScreenProps & { id: string; onChanged: () => void }) {
  const api = surveillanceApi(client);
  const pe = useAsync(() => api.preEmployment(id), onSessionExpired, [id]);
  const action = useAction(onSessionExpired);
  const [outcome, setOutcome] = useState({ outcome: 'FIT', conditions: '' });
  const [gov, setGov] = useState({ type: 'VISA', reference: '', status: 'PENDING', expiresOn: '' });
  const [conditionsError, setConditionsError] = useState<string>();
  const clinician = can(session.roles, 'preEmploymentOutcome');
  const p = pe.data;

  async function record(e: FormEvent) {
    e.preventDefault();
    if (!p) return;
    setConditionsError(undefined);
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await api.preEmploymentOutcome(p.id, { outcome: outcome.outcome, ...(outcome.conditions.trim() ? { conditions: outcome.conditions.trim() } : {}) });
      } catch (err) {
        failed = err;
        throw err;
      }
      return `Outcome recorded for ${p.candidateName}; HR receives the clearance status only.`;
    });
    if (!ok && isClinicalContentError(failed)) {
      setConditionsError(`${clinicalContentField(failed) === 'conditions' || !clinicalContentField(failed) ? 'Conditions' : humanise(clinicalContentField(failed))}: describe adjustments in work terms only – HR will see this text.`);
      return;
    }
    if (ok) {
      pe.reload();
      onChanged();
    }
  }

  async function saveGov(e: FormEvent) {
    e.preventDefault();
    if (!p || !gov.reference.trim()) return;
    const ok = await action.run(async () => {
      await api.governmentMedical(p.id, { type: gov.type, reference: gov.reference.trim(), status: gov.status, ...(gov.expiresOn ? { expiresOn: gov.expiresOn } : {}) });
      return 'Government / visa medical saved.';
    });
    if (ok) pe.reload();
  }

  async function withdraw() {
    if (!p) return;
    const ok = await action.run(async () => {
      await api.withdrawPreEmployment(p.id);
      return `${p.candidateName} withdrawn – the record is kept 12 months, then purged.`;
    });
    if (ok) {
      pe.reload();
      onChanged();
    }
  }

  if (pe.loading && !p) return <Loading />;
  if (pe.error) return <ErrorAlert error={pe.error} />;
  if (!p) return null;
  const closed = p.status === 'OUTCOME_RECORDED' || p.status === 'WITHDRAWN';
  return (
    <section className="side-panel" aria-labelledby="pe-detail">
      <h2 id="pe-detail">{p.candidateName} <span className="small muted">{p.offerRef}</span></h2>
      <Dl
        items={[
          ['Job', `${p.jobTitle ?? p.jobCode}`],
          ['Proposed start', formatDay(p.proposedStart)],
          ['Status', <StatusBadge status={p.status} />],
          ['HR clearance', <StatusBadge status={p.hrClearance} />],
          ['Questionnaire', p.questionnaireCompleted ? 'Completed' : 'Not completed'],
          ['Consent', p.consentGiven ? 'Given' : 'Not given'],
          ['Panel', p.panel.map(humanise).join(', ')],
          ['Appointment', p.appointment ? `${formatDate(p.appointment.startsAt)}${p.appointment.siteCode ? ` · ${p.appointment.siteCode}` : ''}` : undefined],
          ['Retention until', p.retentionUntil ? formatDay(p.retentionUntil) : undefined],
        ]}
      />
      <h3>Questionnaire answers</h3>
      <Answers data={p.answers ?? p.questionnaire} />
      <h3>Documents</h3>
      {p.documents.length === 0 ? (
        <p className="small muted">No documents uploaded.</p>
      ) : (
        <ul className="plain-list">
          {p.documents.map((d) => (
            <li key={d.id}><a href={documentContentUrl(d.id)} target="_blank" rel="noopener">{d.title}</a></li>
          ))}
        </ul>
      )}
      <h3>Government / visa medical</h3>
      {p.governmentMedical ? (
        <p>
          {humanise(p.governmentMedical.type)} <span className="mono">{p.governmentMedical.reference}</span> <StatusBadge status={p.governmentMedical.status} />
          {p.governmentMedical.expiresOn && <span className="small"> · expires {formatDay(p.governmentMedical.expiresOn)}</span>}
        </p>
      ) : (
        <p className="small muted">None recorded.</p>
      )}
      {clinician && !closed && (
        <form className="form compact" onSubmit={saveGov} aria-label="Government or visa medical">
          <div className="inline-form">
            <Field label="Type">
              {(fid) => (
                <select id={fid} value={gov.type} onChange={(e) => setGov({ ...gov, type: e.target.value })}>
                  <SelectOptions values={GOV_MEDICAL_TYPES} />
                </select>
              )}
            </Field>
            <Field label="Reference" required>{(fid) => <input id={fid} required maxLength={60} value={gov.reference} onChange={(e) => setGov({ ...gov, reference: e.target.value })} />}</Field>
            <Field label="Result">
              {(fid) => (
                <select id={fid} value={gov.status} onChange={(e) => setGov({ ...gov, status: e.target.value })}>
                  <SelectOptions values={GOV_MEDICAL_STATUSES} />
                </select>
              )}
            </Field>
            <Field label="Expires on">{(fid) => <input id={fid} type="date" value={gov.expiresOn} onChange={(e) => setGov({ ...gov, expiresOn: e.target.value })} />}</Field>
          </div>
          <button type="submit" className="secondary" disabled={action.busy || !gov.reference.trim()}>Save government medical</button>
        </form>
      )}
      {clinician && !closed && (
        <>
          <h3>Outcome</h3>
          <form className="form compact" onSubmit={record}>
            <fieldset>
              <legend className="visually-hidden">Outcome</legend>
              {PRE_EMPLOYMENT_OUTCOMES.map((o) => (
                <label key={o} className="inline-row">
                  <input type="radio" name="pe-outcome" checked={outcome.outcome === o} onChange={() => setOutcome({ ...outcome, outcome: o })} /> {humanise(o)}
                </label>
              ))}
            </fieldset>
            <div className={`field${conditionsError ? ' field-error' : ''}`}>
              <label htmlFor="pe-conditions">Conditions / adjustments (shared with HR – no medical detail)</label>
              {conditionsError && <span id="pe-conditions-error" className="field-error-text small"><span className="visually-hidden">Error: </span>{conditionsError}</span>}
              <textarea id="pe-conditions" rows={2} maxLength={1000} aria-invalid={!!conditionsError} aria-describedby={conditionsError ? 'pe-conditions-error' : undefined} value={outcome.conditions} onChange={(e) => setOutcome({ ...outcome, conditions: e.target.value })} />
            </div>
            <button type="submit" disabled={action.busy}>Record outcome</button>
          </form>
        </>
      )}
      {!closed && can(session.roles, 'preEmployment') && (
        <p><button type="button" className="link-button danger" disabled={action.busy} onClick={() => void withdraw()}>Candidate not hired – withdraw</button></p>
      )}
      <ActionFeedback error={conditionsError ? undefined : action.error} message={action.message} />
    </section>
  );
}

/** `#/surveillance/pre-employment` – pre-employment / pre-placement assessments (E07-F03). */
export function PreEmploymentScreen(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const [status, setStatus] = useState('');
  const list = useAsync(() => surveillanceApi(client).preEmployments(status || undefined), onSessionExpired, [status]);
  const [selected, setSelected] = useState<string>();
  const rows: PreEmployment[] = list.data ?? [];

  return (
    <section>
      <PageTitle sub="Triggered by HRIS offers. HR receives clearance status (pending / fit / unfit) and a non-clinical outcome letter only.">Pre-employment</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={PRE_EMPLOYMENT_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No pre-employment assessments.</Empty>}
      <div className={selected ? 'queue-layout' : undefined}>
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <caption>{rows.length} candidate{rows.length === 1 ? '' : 's'}</caption>
              <thead>
                <tr>
                  <th scope="col">Candidate</th>
                  <th scope="col">Job</th>
                  <th scope="col">Start</th>
                  <th scope="col">Progress</th>
                  <th scope="col">SLA</th>
                  <th scope="col">Status</th>
                  <th scope="col">Clearance</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => {
                  const sla = p.hrClearance === 'PENDING' && p.status !== 'WITHDRAWN' ? slaTone(p.slaDueAt) : undefined;
                  return (
                    <tr key={p.id} className={p.id === selected ? 'row-selected' : undefined}>
                      <th scope="row">
                        <button type="button" className="link-button" aria-pressed={p.id === selected} onClick={() => setSelected(p.id === selected ? undefined : p.id)}>
                          {p.candidateName}
                        </button>{' '}
                        <span className="small muted">{p.offerRef}</span>
                      </th>
                      <td>{p.jobTitle ?? p.jobCode}</td>
                      <td>{formatDay(p.proposedStart)}</td>
                      <td className="small">
                        {p.questionnaireCompleted ? '✓' : '✗'} questionnaire · {p.consentGiven ? '✓' : '✗'} consent{p.appointment ? ` · booked ${formatDay(p.appointment.startsAt)}` : ''}
                        {p.governmentMedical && <> · {humanise(p.governmentMedical.type)} {humanise(p.governmentMedical.status).toLowerCase()}</>}
                      </td>
                      <td>{sla ? <ToneBadge tone={sla.tone}>{sla.label}</ToneBadge> : '–'}</td>
                      <td><StatusBadge status={p.status} /></td>
                      <td><StatusBadge status={p.hrClearance} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {selected && <Detail key={selected} {...props} id={selected} onChanged={list.reload} />}
      </div>
    </section>
  );
}
