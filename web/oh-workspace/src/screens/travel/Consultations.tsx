import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { INJECTION_SITES, ROUTES, surveillanceApi } from '../../api/surveillance';
import { TRIP_PURPOSES, travelApi, type Destination, type TravelConsultation, type TravelRecommendation } from '../../api/travel';
import type { EmployeeRef } from '../../api/types';
import { ApiError, SessionExpiredError } from '../../auth/AuthClient';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { FieldError } from '../../domain/devices';
import { consultationBody, recommendationsFor, recommendationView, validateConsultation, type ConsultationForm, type VaccineRow } from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { LotField } from './common';

const emptyForm = (employeeId = ''): ConsultationForm => ({ employeeId, destinations: [], departDate: '', returnDate: '', purpose: 'BUSINESS', vaccines: [], prophylaxis: [], advice: '' });
const vaccineRow = (vaccineCode = ''): VaccineRow => ({ vaccineCode, doseNumber: '1', lot: '', site: 'LEFT_DELTOID', route: 'IM' });

function Recommendations({ rows, fallback, onGive, given }: { rows: TravelRecommendation[]; fallback: boolean; onGive: (code: string) => void; given: readonly string[] }) {
  if (rows.length === 0) return <Empty>No vaccines are recommended for the chosen destinations.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <caption>Recommended vaccines vs immunisation status{fallback ? ' (calculated from destination content and the immunisation record)' : ''}</caption>
        <thead><tr><th scope="col">Vaccine</th><th scope="col">For</th><th scope="col">Status</th><th scope="col">Last dose</th><th scope="col">Action</th></tr></thead>
        <tbody>
          {rows.map((r) => {
            const v = recommendationView(r);
            return (
              <tr key={r.vaccineCode}>
                <th scope="row">{r.vaccineName ? `${r.vaccineName} (${r.vaccineCode})` : r.vaccineCode}</th>
                <td className="small">{r.destinations?.join(', ') ?? '–'}</td>
                <td><ToneBadge tone={v.tone}>{v.label}</ToneBadge></td>
                <td className="small">{formatDay(r.lastDoseOn)}</td>
                <td>
                  {v.tone === 'green' ? <span className="small muted">{v.action}</span> : given.includes(r.vaccineCode) ? <span className="small">Added below</span> : (
                    <button type="button" className="small-button secondary" onClick={() => onGive(r.vaccineCode)}>Record {r.vaccineCode} dose</button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ConsultationFormView({ client, onSessionExpired, employee, destinations, onDone }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { employee: EmployeeRef; destinations: Destination[]; onDone: (c: TravelConsultation) => void }) {
  const api = travelApi(client);
  const [f, setF] = useState<ConsultationForm>(() => emptyForm(employee.employeeId));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const chosen = destinations.filter((d) => f.destinations.includes(d.countryCode));

  const recs = useAsync(
    async (): Promise<{ rows: TravelRecommendation[]; fallback: boolean }> => {
      if (f.destinations.length === 0) return { rows: [], fallback: false };
      try {
        return { rows: await api.recommendations(employee.employeeId, f.destinations), fallback: false };
      } catch (e) {
        if (e instanceof SessionExpiredError || (e instanceof ApiError && e.status === 403)) throw e;
        const imms = await surveillanceApi(client).employeeImmunisations(employee.employeeId);
        return { rows: recommendationsFor(destinations, f.destinations, imms), fallback: true };
      }
    },
    onSessionExpired,
    [employee.employeeId, f.destinations],
  );

  const setVaccine = (i: number, patch: Partial<VaccineRow>) => setF({ ...f, vaccines: f.vaccines.map((v, j) => (j === i ? { ...v, ...patch } : v)) });
  const toggleDest = (code: string) => setF({ ...f, destinations: f.destinations.includes(code) ? f.destinations.filter((c) => c !== code) : [...f.destinations, code] });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateConsultation(f);
    setErrors(errs);
    if (errs.length) return;
    let created: TravelConsultation | undefined;
    const ok = await action.run(async () => {
      created = await api.createConsultation(consultationBody(f));
    });
    if (ok && created) onDone(created);
  }

  const suggestedProphylaxis = [...new Set(chosen.flatMap((d) => d.prophylaxis))];
  return (
    <form className="form" onSubmit={submit} noValidate aria-labelledby="tc-form-title">
      <h2 id="tc-form-title">New consultation for {employee.displayName} ({employee.employeeId})</h2>
      <ErrorSummary errors={errors} />
      <fieldset>
        <legend>Trip</legend>
        <div className={`field${errorFor(errors, 'tc-destinations') ? ' field-error' : ''}`} role="group" aria-labelledby="tc-dest-label">
          <span id="tc-dest-label" className="label">Destinations *</span>
          {errorFor(errors, 'tc-destinations') && <span className="field-error-text small">{errorFor(errors, 'tc-destinations')}</span>}
          <div className="checkbox-group">
            {destinations.map((d, i) => (
              <label key={d.countryCode} className="inline-row">
                <input id={i === 0 ? 'tc-destinations' : undefined} type="checkbox" checked={f.destinations.includes(d.countryCode)} onChange={() => toggleDest(d.countryCode)} /> {d.name}
              </label>
            ))}
          </div>
        </div>
        <div className="inline-form">
          <IdField id="tc-depart" label="Departure" required error={errorFor(errors, 'tc-depart')}>{(db) => <input id="tc-depart" type="date" aria-describedby={db} value={f.departDate} onChange={(e) => setF({ ...f, departDate: e.target.value })} />}</IdField>
          <IdField id="tc-return" label="Return" required error={errorFor(errors, 'tc-return')}>{(db) => <input id="tc-return" type="date" aria-describedby={db} value={f.returnDate} onChange={(e) => setF({ ...f, returnDate: e.target.value })} />}</IdField>
          <IdField id="tc-purpose" label="Purpose" required error={errorFor(errors, 'tc-purpose')}>{(db) => <select id="tc-purpose" aria-describedby={db} value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })}><SelectOptions values={TRIP_PURPOSES} /></select>}</IdField>
        </div>
      </fieldset>

      {chosen.length > 0 && (
        <section aria-labelledby="tc-recs">
          <h3 id="tc-recs">Recommendations</h3>
          {recs.loading && <Loading label="Checking the immunisation record…" />}
          {!!recs.error && <ErrorAlert error={recs.error} />}
          {recs.data && <Recommendations rows={recs.data.rows} fallback={recs.data.fallback} given={f.vaccines.map((v) => v.vaccineCode)} onGive={(code) => setF({ ...f, vaccines: [...f.vaccines, vaccineRow(code)] })} />}
          {chosen.map((d) => (
            <details key={d.countryCode}>
              <summary>{d.name}: risks and advice</summary>
              {d.risks.length > 0 && <p className="small">Risks: {d.risks.join('; ')}</p>}
              <p className="small prewrap">{d.advice}</p>
            </details>
          ))}
        </section>
      )}

      <fieldset>
        <legend>Vaccines given (recorded in the immunisation registry)</legend>
        {f.vaccines.length === 0 && <p className="small muted">None yet.</p>}
        {f.vaccines.map((v, i) => (
          <div key={i} className="inline-form" role="group" aria-label={`Vaccine ${i + 1}`}>
            <IdField id={`tc-vac-${i}-code`} label="Vaccine code" required error={errorFor(errors, `tc-vac-${i}-code`)}>{(db) => <input id={`tc-vac-${i}-code`} size={10} aria-describedby={db} value={v.vaccineCode} onChange={(e) => setVaccine(i, { vaccineCode: e.target.value.toUpperCase() })} />}</IdField>
            <IdField id={`tc-vac-${i}-dose`} label="Dose" error={errorFor(errors, `tc-vac-${i}-dose`)}>{(db) => <input id={`tc-vac-${i}-dose`} type="number" min={1} max={10} size={3} aria-describedby={db} value={v.doseNumber} onChange={(e) => setVaccine(i, { doseNumber: e.target.value })} />}</IdField>
            <LotField id={`tc-vac-${i}-lot`} label={`Lot (vaccine ${i + 1})`} value={v.lot} onChange={(lot) => setVaccine(i, { lot })} error={errorFor(errors, `tc-vac-${i}-lot`)} />
            <IdField id={`tc-vac-${i}-site`} label="Site" error={errorFor(errors, `tc-vac-${i}-site`)}>
              {(db) => <select id={`tc-vac-${i}-site`} aria-describedby={db} value={v.site} onChange={(e) => setVaccine(i, { site: e.target.value, route: e.target.value === 'ORAL' ? 'ORAL' : v.route === 'ORAL' ? 'IM' : v.route })}><SelectOptions values={INJECTION_SITES} /></select>}
            </IdField>
            <IdField id={`tc-vac-${i}-route`} label="Route">{(db) => <select id={`tc-vac-${i}-route`} aria-describedby={db} value={v.route} onChange={(e) => setVaccine(i, { route: e.target.value })}>{ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}</select>}</IdField>
            <button type="button" className="link-button" onClick={() => setF({ ...f, vaccines: f.vaccines.filter((_, j) => j !== i) })}>Remove vaccine {i + 1}</button>
          </div>
        ))}
        <button type="button" className="secondary" onClick={() => setF({ ...f, vaccines: [...f.vaccines, vaccineRow()] })}>Add vaccine</button>
      </fieldset>

      <fieldset>
        <legend>Prophylaxis</legend>
        {suggestedProphylaxis.length > 0 && <p className="small muted">Suggested for the destinations: {suggestedProphylaxis.join('; ')}</p>}
        {f.prophylaxis.map((p, i) => (
          <div key={i} className="inline-form" role="group" aria-label={`Prophylaxis ${i + 1}`}>
            <IdField id={`tc-pro-${i}-drug`} label="Drug" required error={errorFor(errors, `tc-pro-${i}-drug`)}>{(db) => <input id={`tc-pro-${i}-drug`} aria-describedby={db} value={p.drug} onChange={(e) => setF({ ...f, prophylaxis: f.prophylaxis.map((x, j) => (j === i ? { ...x, drug: e.target.value } : x)) })} />}</IdField>
            <IdField id={`tc-pro-${i}-regimen`} label="Regimen" required error={errorFor(errors, `tc-pro-${i}-regimen`)}>{(db) => <input id={`tc-pro-${i}-regimen`} aria-describedby={db} value={p.regimen} onChange={(e) => setF({ ...f, prophylaxis: f.prophylaxis.map((x, j) => (j === i ? { ...x, regimen: e.target.value } : x)) })} />}</IdField>
            <IdField id={`tc-pro-${i}-start`} label="Start" required error={errorFor(errors, `tc-pro-${i}-start`)}>{(db) => <input id={`tc-pro-${i}-start`} type="date" aria-describedby={db} value={p.startDate} onChange={(e) => setF({ ...f, prophylaxis: f.prophylaxis.map((x, j) => (j === i ? { ...x, startDate: e.target.value } : x)) })} />}</IdField>
            <button type="button" className="link-button" onClick={() => setF({ ...f, prophylaxis: f.prophylaxis.filter((_, j) => j !== i) })}>Remove prophylaxis {i + 1}</button>
          </div>
        ))}
        <button type="button" className="secondary" onClick={() => setF({ ...f, prophylaxis: [...f.prophylaxis, { drug: '', regimen: '', startDate: f.departDate }] })}>Add prophylaxis</button>
      </fieldset>

      <IdField id="tc-advice" label="Additional advice (optional)">{(db) => <textarea id="tc-advice" rows={3} maxLength={4000} aria-describedby={db} value={f.advice} onChange={(e) => setF({ ...f, advice: e.target.value })} />}</IdField>
      <ActionFeedback error={action.error} />
      <button type="submit" disabled={action.busy}>Record consultation and issue certificate</button>
    </form>
  );
}

/** `#/travel/consultations` – travel & posting consultation (E11-F01). */
export function TravelConsultations({ client, onSessionExpired }: ScreenProps) {
  const api = travelApi(client);
  const [employee, setEmployee] = useState<EmployeeRef>();
  const [done, setDone] = useState<TravelConsultation>();
  const destinations = useAsync(() => api.destinations(), onSessionExpired);
  const history = useAsync(() => (employee ? api.consultations(employee.employeeId) : Promise.resolve([] as TravelConsultation[])), onSessionExpired, [employee?.employeeId, done?.id]);

  return (
    <section>
      <PageTitle sub="Trip, recommendations against the immunisation record, vaccines with lot and site, prophylaxis. The signed travel health certificate is shared with the employee.">Travel consultations</PageTitle>
      <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={(e) => { setEmployee(e); setDone(undefined); }} />
      {done && (
        <Alert kind="success">
          Consultation {done.ref ?? ''} recorded.{' '}
          {done.certificateDocumentId ? <a href={documentContentUrl(done.certificateDocumentId)} target="_blank" rel="noopener">Open the travel health certificate</a> : 'The certificate is being generated.'}
        </Alert>
      )}
      {employee && (
        <>
          {destinations.loading && <Loading label="Loading destinations…" />}
          {!!destinations.error && <ErrorAlert error={destinations.error} />}
          {destinations.data && !done && <ConsultationFormView key={employee.employeeId} client={client} onSessionExpired={onSessionExpired} employee={employee} destinations={destinations.data} onDone={setDone} />}
          {done && <button type="button" className="secondary" onClick={() => setDone(undefined)}>New consultation for {employee.displayName}</button>}
          <section aria-labelledby="tc-history">
            <h2 id="tc-history">Previous consultations</h2>
            {history.loading && <Loading />}
            {!!history.error && <ErrorAlert error={history.error} />}
            {history.data && history.data.length === 0 && <Empty>No travel consultations recorded.</Empty>}
            {history.data && history.data.length > 0 && (
              <div className="table-wrap">
                <table>
                  <caption>Travel consultations for {employee.displayName}</caption>
                  <thead><tr><th scope="col">Date</th><th scope="col">Destinations</th><th scope="col">Trip</th><th scope="col">Purpose</th><th scope="col">Vaccines given</th><th scope="col">Certificate</th></tr></thead>
                  <tbody>
                    {history.data.map((c) => (
                      <tr key={c.id}>
                        <th scope="row">{formatDate(c.createdAt)}</th>
                        <td>{c.trip.destinations.join(', ')}</td>
                        <td className="small">{formatDay(c.trip.departDate)} – {formatDay(c.trip.returnDate)}</td>
                        <td>{humanise(c.trip.purpose)}</td>
                        <td className="small">{(c.vaccinesGiven ?? []).map((v) => `${v.vaccineCode} #${v.doseNumber} (lot ${v.lotNumber})`).join('; ') || '–'}</td>
                        <td>{c.certificateDocumentId ? <a href={documentContentUrl(c.certificateDocumentId)} target="_blank" rel="noopener">Certificate</a> : '–'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}
