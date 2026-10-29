import { useState, type FormEvent } from 'react';
import { CONTRACT_STATUSES, COVERAGES, scorecardExportUrl, treatmentApi, type Contract, type Provider, type ProviderDetail } from '../../api/treatment';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { previousMonth } from '../../domain/absence';
import { isMonth } from '../../domain/claims';
import { CURRENCIES, formatMoney, parseAmount } from '../../domain/money';
import { contractTone, filterProviders, isIsoDay, scoreTone, type ProviderQuery } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { ProviderBadges, ScoreBadge } from './common';
import { csv, num, pct } from './util';

const providerLink = (code: string) => `#/providers/${encodeURIComponent(code)}`;

function ContractCell({ c }: { c?: Contract | null }) {
  const status = c?.status ?? 'NONE';
  return (
    <span>
      <ToneBadge tone={contractTone(status)}>{humanise(status)}</ToneBadge>
      {c?.end && <span className="small muted"> until {formatDay(c.end)}</span>}
    </span>
  );
}

/** `#/providers` – partner network directory: search by service / city / contract, coverage, rates, SLA and scorecard (E04-F03). */
export function ProviderDirectory({ client, onSessionExpired }: ScreenProps) {
  const api = treatmentApi(client);
  const [f, setF] = useState<ProviderQuery & { contractStatus: string }>({ serviceCode: '', city: '', contract: '', contractStatus: '', preferredOnly: false, q: '' });
  const [applied, setApplied] = useState(f);
  const list = useAsync(() => api.providers({ serviceCode: applied.serviceCode, city: applied.city, contractStatus: applied.contractStatus, q: applied.q, preferredOnly: applied.preferredOnly }), onSessionExpired, [applied]);
  const all = list.data ?? [];
  const services = [...new Set(all.flatMap((p) => p.services))].sort();
  const cities = [...new Set(all.map((p) => p.city).filter((c): c is string => !!c))].sort();
  const rows = filterProviders(all, { ...applied, contract: (applied.contractStatus || '') as ProviderQuery['contract'] });
  const rate = (p: Provider) => (applied.serviceCode ? (p.rates ?? p.contract?.rates ?? []).find((r) => r.serviceCode === applied.serviceCode)?.price : undefined);

  return (
    <section>
      <PageTitle sub="Contracted and preferred partners, their coverage at hubs, outstations and crew layover cities, rates, SLAs and monthly scorecards.">Provider network</PageTitle>
      <form className="filters inline-form" role="search" onSubmit={(e) => { e.preventDefault(); setApplied(f); }}>
        <Field label="Search">{(id) => <input id={id} type="search" value={f.q} onChange={(e) => setF({ ...f, q: e.target.value })} placeholder="Name, code, specialty" />}</Field>
        <Field label="Service">
          {(id) => (
            <select id={id} value={f.serviceCode} onChange={(e) => setF({ ...f, serviceCode: e.target.value })}>
              <option value="">Any</option>
              {[...new Set([...services, ...(f.serviceCode ? [f.serviceCode] : [])])].map((s) => <option key={s} value={s}>{humanise(s)}</option>)}
            </select>
          )}
        </Field>
        <Field label="City">
          {(id) => (
            <>
              <input id={id} list="provider-cities" value={f.city} onChange={(e) => setF({ ...f, city: e.target.value })} />
              <datalist id="provider-cities">{cities.map((c) => <option key={c} value={c} />)}</datalist>
            </>
          )}
        </Field>
        <Field label="Contract">
          {(id) => (
            <select id={id} value={f.contractStatus} onChange={(e) => setF({ ...f, contractStatus: e.target.value })}>
              <SelectOptions values={CONTRACT_STATUSES} empty="Any" />
            </select>
          )}
        </Field>
        <label className="inline-row"><input type="checkbox" checked={!!f.preferredOnly} onChange={(e) => setF({ ...f, preferredOnly: e.target.checked })} /> Preferred only</label>
        <button type="submit" className="secondary">Search</button>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No providers match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} provider{rows.length === 1 ? '' : 's'} (preferred and contracted first)</caption>
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">Location</th>
                <th scope="col">Coverage</th>
                <th scope="col">Contract</th>
                {applied.serviceCode && <th scope="col" className="num">Rate</th>}
                <th scope="col">SLA</th>
                <th scope="col">Scorecard</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => {
                const price = rate(p);
                return (
                  <tr key={p.code}>
                    <th scope="row">
                      <a href={providerLink(p.code)}>{p.name}</a> <span className="small muted">{p.code} · {humanise(p.orgType)}</span>
                      <br />
                      <ProviderBadges p={p} />
                    </th>
                    <td>{p.city ?? '–'}{p.countryCode ? `, ${p.countryCode}` : ''}</td>
                    <td>{p.coverage ? <ToneBadge tone={p.coverage === 'HUB' ? 'green' : p.coverage === 'LAYOVER' ? 'amber' : 'muted'}>{humanise(p.coverage)}</ToneBadge> : '–'}</td>
                    <td><ContractCell c={p.contract} /></td>
                    {applied.serviceCode && <td className="num">{price ? formatMoney(price) : '–'}</td>}
                    <td className="small">{p.sla ? `Appt ${p.sla.appointmentDays} d · urgent ${p.sla.urgentHours} h · report ${p.sla.reportDays} d` : '–'}</td>
                    <td><ScoreBadge score={p.scorecard?.overall} label={p.scorecard?.month ?? 'Score'} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

interface ProfileForm {
  specialties: string;
  services: string;
  languages: string;
  accreditation: string;
  coverage: string;
  preferred: boolean;
  latitude: string;
  longitude: string;
}
const toProfileForm = (p: ProviderDetail): ProfileForm => ({
  specialties: p.specialties.join(', '),
  services: p.services.join(', '),
  languages: p.languages.join(', '),
  accreditation: p.accreditation.map((a) => (a.validUntil ? `${a.body} | ${a.validUntil}` : a.body)).join('\n'),
  coverage: p.coverage ?? '',
  preferred: p.preferred,
  latitude: p.latitude !== undefined ? String(p.latitude) : '',
  longitude: p.longitude !== undefined ? String(p.longitude) : '',
});

function ProfileEditor({ p, props, onDone }: { p: ProviderDetail; props: ScreenProps; onDone: () => void }) {
  const api = treatmentApi(props.client);
  const [f, setF] = useState<ProfileForm>(() => toProfileForm(p));
  const [error, setError] = useState<string>();
  const action = useAction(props.onSessionExpired);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const accreditation = f.accreditation.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
      const [body = '', until = ''] = l.split('|').map((x) => x.trim());
      return until ? { body, validUntil: until } : { body };
    });
    const badDate = accreditation.find((a) => a.validUntil && !isIsoDay(a.validUntil));
    const lat = f.latitude.trim() ? Number(f.latitude) : undefined;
    const lng = f.longitude.trim() ? Number(f.longitude) : undefined;
    if (badDate) return setError(`Accreditation "${badDate.body}": valid-until must be YYYY-MM-DD`);
    if ((lat !== undefined && (Number.isNaN(lat) || lat < -90 || lat > 90)) || (lng !== undefined && (Number.isNaN(lng) || lng < -180 || lng > 180))) return setError('Latitude −90…90 and longitude −180…180');
    setError(undefined);
    const ok = await action.run(async () => {
      await api.saveProvider(p.code, {
        specialties: csv(f.specialties),
        services: csv(f.services).map((s) => s.toUpperCase()),
        languages: csv(f.languages),
        accreditation,
        ...(f.coverage ? { coverage: f.coverage as ProviderDetail['coverage'] } : {}),
        preferred: f.preferred,
        ...(lat !== undefined ? { latitude: lat } : {}),
        ...(lng !== undefined ? { longitude: lng } : {}),
      });
      return 'Profile saved.';
    });
    if (ok) onDone();
  }

  return (
    <form className="form tile" onSubmit={submit} aria-label="Edit provider profile">
      <h2>Edit profile</h2>
      <p className="small muted">Name, address and contact details are maintained in partner-organisation master data.</p>
      {error && <p className="field-error-text" role="alert">{error}</p>}
      <Field label="Specialties" hint="Comma separated">{(id) => <input id={id} value={f.specialties} onChange={(e) => setF({ ...f, specialties: e.target.value })} />}</Field>
      <Field label="Services" hint="Referral service codes, comma separated (e.g. PHYSIOTHERAPY, IMAGING_MRI)">{(id) => <input id={id} value={f.services} onChange={(e) => setF({ ...f, services: e.target.value })} />}</Field>
      <Field label="Languages" hint="Comma separated">{(id) => <input id={id} value={f.languages} onChange={(e) => setF({ ...f, languages: e.target.value })} />}</Field>
      <Field label="Accreditation" hint="One per line: body | valid-until (YYYY-MM-DD)">{(id) => <textarea id={id} rows={3} value={f.accreditation} onChange={(e) => setF({ ...f, accreditation: e.target.value })} />}</Field>
      <div className="inline-form">
        <Field label="Coverage">
          {(id) => (
            <select id={id} value={f.coverage} onChange={(e) => setF({ ...f, coverage: e.target.value })}>
              <SelectOptions values={COVERAGES} empty="–" />
            </select>
          )}
        </Field>
        <Field label="Latitude">{(id) => <input id={id} inputMode="decimal" value={f.latitude} onChange={(e) => setF({ ...f, latitude: e.target.value })} />}</Field>
        <Field label="Longitude">{(id) => <input id={id} inputMode="decimal" value={f.longitude} onChange={(e) => setF({ ...f, longitude: e.target.value })} />}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.preferred} onChange={(e) => setF({ ...f, preferred: e.target.checked })} /> Preferred provider</label>
      </div>
      <div className="actions">
        <button type="submit" disabled={action.busy}>Save profile</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

interface RateRow {
  serviceCode: string;
  amount: string;
  currency: string;
}

function ContractEditor({ p, props, onDone }: { p: ProviderDetail; props: ScreenProps; onDone: () => void }) {
  const api = treatmentApi(props.client);
  const [f, setF] = useState({ ref: '', start: '', end: '', appointmentDays: String(p.sla?.appointmentDays ?? 5), urgentHours: String(p.sla?.urgentHours ?? 48), reportDays: String(p.sla?.reportDays ?? 7) });
  const [rates, setRates] = useState<RateRow[]>(() => (p.services.length ? p.services : ['']).map((s) => ({ serviceCode: s, amount: '', currency: 'GBP' })));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(props.onSessionExpired);
  const err = (k: string) => errorFor(errors, k);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.ref.trim()) errs.push({ field: 'ct-ref', message: 'Enter the contract reference' });
    if (!isIsoDay(f.start)) errs.push({ field: 'ct-start', message: 'Enter the start date' });
    if (!isIsoDay(f.end) || f.end <= f.start) errs.push({ field: 'ct-end', message: 'End date must be after the start date' });
    const parsed = rates.filter((r) => r.serviceCode.trim() || r.amount.trim()).map((r, i) => {
      const amount = parseAmount(r.amount);
      if (!r.serviceCode.trim() || !amount) errs.push({ field: `ct-rate-${i}`, message: `Rate ${i + 1}: service code and price (e.g. 85.00) required` });
      return { serviceCode: r.serviceCode.trim().toUpperCase(), price: { amount: amount ?? '0.00', currency: r.currency } };
    });
    if (parsed.length === 0) errs.push({ field: 'ct-rate-0', message: 'Add at least one rate' });
    for (const [k, label] of [['appointmentDays', 'Appointment days'], ['urgentHours', 'Urgent hours'], ['reportDays', 'Report days']] as const) {
      if (!/^\d{1,3}$/.test(f[k]) || Number(f[k]) < 1) errs.push({ field: `ct-${k}`, message: `${label}: whole number ≥ 1` });
    }
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.addContract(p.code, { ref: f.ref.trim(), start: f.start, end: f.end, rates: parsed, sla: { appointmentDays: Number(f.appointmentDays), urgentHours: Number(f.urgentHours), reportDays: Number(f.reportDays) } });
      return `Contract ${f.ref.trim()} added.`;
    });
    if (ok) onDone();
  }

  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="Add contract">
      <h2>Add contract (new version)</h2>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="ct-ref" label="Contract reference" required error={err('ct-ref')}>
          {(db) => <input id="ct-ref" aria-describedby={db} aria-invalid={!!err('ct-ref')} value={f.ref} onChange={(e) => setF({ ...f, ref: e.target.value })} />}
        </IdField>
        <IdField id="ct-start" label="Start" required error={err('ct-start')}>
          {(db) => <input id="ct-start" type="date" aria-describedby={db} aria-invalid={!!err('ct-start')} value={f.start} onChange={(e) => setF({ ...f, start: e.target.value })} />}
        </IdField>
        <IdField id="ct-end" label="End" required error={err('ct-end')}>
          {(db) => <input id="ct-end" type="date" aria-describedby={db} aria-invalid={!!err('ct-end')} value={f.end} onChange={(e) => setF({ ...f, end: e.target.value })} />}
        </IdField>
      </div>
      <fieldset>
        <legend>Rate card</legend>
        {rates.map((r, i) => (
          <div key={i} className="inline-form">
            <IdField id={`ct-rate-${i}`} label={`Service ${i + 1}`} error={err(`ct-rate-${i}`)}>
              {(db) => <input id={`ct-rate-${i}`} aria-describedby={db} aria-invalid={!!err(`ct-rate-${i}`)} value={r.serviceCode} onChange={(e) => setRates(rates.map((x, j) => (j === i ? { ...x, serviceCode: e.target.value } : x)))} />}
            </IdField>
            <Field label="Price">{(id) => <input id={id} inputMode="decimal" value={r.amount} onChange={(e) => setRates(rates.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} />}</Field>
            <Field label="Currency">
              {(id) => (
                <select id={id} value={r.currency} onChange={(e) => setRates(rates.map((x, j) => (j === i ? { ...x, currency: e.target.value } : x)))}>
                  {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              )}
            </Field>
            {rates.length > 1 && <button type="button" className="link-button" onClick={() => setRates(rates.filter((_, j) => j !== i))} aria-label={`Remove rate ${i + 1}`}>Remove</button>}
          </div>
        ))}
        <button type="button" className="secondary small-button" onClick={() => setRates([...rates, { serviceCode: '', amount: '', currency: 'GBP' }])}>Add rate</button>
      </fieldset>
      <fieldset>
        <legend>Service levels</legend>
        <div className="inline-form">
          {([['appointmentDays', 'Routine appointment (days)'], ['urgentHours', 'Urgent appointment (hours)'], ['reportDays', 'Report (days)']] as const).map(([k, label]) => (
            <IdField key={k} id={`ct-${k}`} label={label} required error={err(`ct-${k}`)}>
              {(db) => <input id={`ct-${k}`} inputMode="numeric" aria-describedby={db} aria-invalid={!!err(`ct-${k}`)} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} />}
            </IdField>
          ))}
        </div>
      </fieldset>
      <div className="actions">
        <button type="submit" disabled={action.busy}>Add contract</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

/** `#/providers/{code}` – profile, accreditation, rates, SLA, scorecard, contract history; PROCUREMENT edits and adds contracts. */
export function ProviderScreen(props: ScreenProps & { code: string }) {
  const { client, session, onSessionExpired, code } = props;
  const detail = useAsync(() => treatmentApi(client).provider(code), onSessionExpired, [code]);
  const [mode, setMode] = useState<'profile' | 'contract'>();
  const [saved, setSaved] = useState<string>();
  const p = detail.data;
  const history = p?.contractHistory ?? p?.contracts ?? [];
  const done = (msg?: string) => {
    setMode(undefined);
    setSaved(msg);
    detail.reload();
  };

  return (
    <section>
      <p className="breadcrumb small"><a href="#/providers">Provider network</a> <span aria-hidden="true">›</span> {code}</p>
      <PageTitle sub={p ? `${humanise(p.orgType)} · ${p.city ?? ''}${p.countryCode ? `, ${p.countryCode}` : ''}` : undefined}>{p?.name ?? code}</PageTitle>
      {detail.loading && !p && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {saved && <p className="alert alert-success" role="status">{saved}</p>}
      {p && (
        <>
          <div className="badge-row"><ProviderBadges p={p} /> <ScoreBadge score={p.scorecard?.overall} /></div>
          <div className="toolbar">
            {can(session.roles, 'providerEdit') && <button type="button" className="secondary" onClick={() => setMode('profile')}>Edit profile</button>}
            {can(session.roles, 'providerContracts') && <button type="button" className="secondary" onClick={() => setMode('contract')}>Add contract</button>}
          </div>
          {mode === 'profile' && <ProfileEditor p={p} props={props} onDone={() => done('Profile saved.')} />}
          {mode === 'contract' && <ContractEditor p={p} props={props} onDone={() => done('Contract added.')} />}
          <div className="grid-2">
            <section className="tile" aria-labelledby="prov-profile">
              <h2 id="prov-profile">Profile</h2>
              <Dl
                items={[
                  ['Coverage', p.coverage ? humanise(p.coverage) : '–'],
                  ['Specialties', p.specialties.join(', ')],
                  ['Services', p.services.map(humanise).join(', ')],
                  ['Languages', p.languages.join(', ')],
                  ['Accreditation', p.accreditation.map((a) => `${a.body}${a.validUntil ? ` (until ${formatDay(a.validUntil)})` : ''}`).join('; ')],
                  ['Partner portal', p.portalEnabled ? 'Enabled' : 'Not enabled'],
                  ['Location', p.latitude !== undefined && p.longitude !== undefined ? `${p.latitude.toFixed(4)}, ${p.longitude.toFixed(4)}` : '–'],
                ]}
              />
            </section>
            <section className="tile" aria-labelledby="prov-score">
              <h2 id="prov-score">Scorecard {p.scorecard?.month ?? ''}</h2>
              {p.scorecard ? (
                <Dl
                  items={(['overall', 'timeliness', 'reportQuality', 'outcome', 'cost'] as const).map((k) => [humanise(k.replace(/([A-Z])/g, '_$1')), p.scorecard?.[k] !== undefined ? <ToneBadge tone={scoreTone(p.scorecard[k])}>{num(p.scorecard[k], 0)}</ToneBadge> : '–'])}
                />
              ) : (
                <Empty>No scorecard yet.</Empty>
              )}
            </section>
          </div>
          <section className="tile" aria-labelledby="prov-contract">
            <h2 id="prov-contract">Current contract</h2>
            {p.contract ? (
              <>
                <Dl items={[['Reference', p.contract.ref], ['Status', <ContractCell c={p.contract} />], ['Period', `${formatDay(p.contract.start)} – ${formatDay(p.contract.end)}`], ['SLA', p.sla ? `Routine appointment ${p.sla.appointmentDays} d · urgent ${p.sla.urgentHours} h · report ${p.sla.reportDays} d` : '–']]} />
                {(p.rates ?? p.contract.rates ?? []).length > 0 && (
                  <table>
                    <caption>Rate card</caption>
                    <thead><tr><th scope="col">Service</th><th scope="col" className="num">Price</th></tr></thead>
                    <tbody>
                      {(p.rates ?? p.contract.rates ?? []).map((r) => (
                        <tr key={r.serviceCode}><th scope="row">{humanise(r.serviceCode)}</th><td className="num">{formatMoney(r.price)}</td></tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </>
            ) : (
              <Empty>No contract.</Empty>
            )}
            {history.length > 0 && (
              <details>
                <summary>Contract history ({history.length})</summary>
                <ul className="plain-list">
                  {history.map((c, i) => (
                    <li key={`${c.ref}-${c.version ?? i}`}>
                      {c.ref}{c.version ? ` v${c.version}` : ''} <ContractCell c={c} /> <span className="small muted">{formatDay(c.start)} – {formatDay(c.end)}{c.createdBy ? ` · ${c.createdBy}` : ''}{c.createdAt ? ` · ${formatDate(c.createdAt)}` : ''}</span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </section>
        </>
      )}
    </section>
  );
}

/** `#/providers/scorecards` – monthly scorecards (month picker, CSV review pack, recompute). */
export function ProviderScorecards({ client, session, onSessionExpired }: ScreenProps) {
  const api = treatmentApi(client);
  const [month, setMonth] = useState(previousMonth());
  const valid = isMonth(month);
  const list = useAsync(() => (valid ? api.scorecards(month) : Promise.resolve([])), onSessionExpired, [month]);
  const action = useAction(onSessionExpired);
  const rows = [...(list.data ?? [])].sort((a, b) => (b.overall ?? -1) - (a.overall ?? -1));

  return (
    <section>
      <PageTitle sub="Timeliness, report quality, outcomes and cost per provider – computed on the 1st of each month.">Provider scorecards</PageTitle>
      <div className="filters inline-form">
        <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
        {valid && <a className="button secondary" href={scorecardExportUrl(month)} download={`provider-scorecards-${month}.csv`} rel="noopener">Export CSV</a>}
        {valid && can(session.roles, 'scorecardRecompute') && (
          <button type="button" className="secondary" disabled={action.busy} onClick={() => void action.run(async () => { await api.recomputeScorecards(month); list.reload(); return `Scorecards for ${month} recomputed.`; })}>Recompute</button>
        )}
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No scorecards for {month}.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Scorecards {month}</caption>
            <thead>
              <tr><th scope="col">Provider</th><th scope="col" className="num">Referrals</th><th scope="col" className="num">Median days to appointment</th><th scope="col" className="num">Reports ≤ 7 days</th><th scope="col" className="num">Avg report quality</th><th scope="col" className="num">Outcome improved</th><th scope="col" className="num">Avg invoice</th><th scope="col">Overall</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.providerCode}>
                  <th scope="row"><a href={providerLink(r.providerCode)}>{r.providerName}</a></th>
                  <td className="num">{r.referrals}</td>
                  <td className="num">{num(r.medianDaysToAppointment)}</td>
                  <td className="num">{pct(r.reportWithin7DaysPct)}</td>
                  <td className="num">{num(r.avgReportQuality)} / 5</td>
                  <td className="num">{pct(r.outcomeImprovedPct)}</td>
                  <td className="num">{formatMoney(r.avgInvoice)}</td>
                  <td>{r.overall !== undefined ? <ToneBadge tone={scoreTone(r.overall)}>{num(r.overall, 0)}</ToneBadge> : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** `#/providers/gaps` – services × hub / outstation / layover cities without an active contract. */
export function NetworkGaps({ client, onSessionExpired }: ScreenProps) {
  const gaps = useAsync(() => treatmentApi(client).networkGaps(), onSessionExpired);
  const rows = gaps.data ?? [];
  const byCity = [...new Set(rows.map((g) => g.city))].sort();
  return (
    <section>
      <PageTitle sub="Where employees and crew cannot be referred to a contracted provider – input for procurement.">Network gaps</PageTitle>
      {gaps.loading && <Loading />}
      {!!gaps.error && <ErrorAlert error={gaps.error} />}
      {gaps.data && rows.length === 0 && <Empty>No gaps – every service is covered by an active contract in every city.</Empty>}
      {byCity.map((city) => (
        <section key={city} className="tile" aria-labelledby={`gap-${city}`}>
          <h2 id={`gap-${city}`}>{city}</h2>
          <ul className="plain-list">
            {rows.filter((g) => g.city === city).map((g) => (
              <li key={g.serviceCode}><ToneBadge tone="amber">{humanise(g.serviceCode)}</ToneBadge> <span className="small">{g.reason}</span></li>
            ))}
          </ul>
        </section>
      ))}
    </section>
  );
}
