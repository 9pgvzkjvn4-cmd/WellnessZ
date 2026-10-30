import { useRef, useState, type FormEvent } from 'react';
import { INJECTION_SITES, ROUTES } from '../../api/surveillance';
import { AE_EVENTS, AE_SEVERITIES, UPTAKE_GROUP_BY, travelApi, type VaccinationCampaign } from '../../api/travel';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { kpiStatus, todayIso } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import { employeeOrBadge, lotInput, type IdMode } from '../../domain/surveillance';
import { codes, uptakeTotals, uptakeView, validateCampaign, type CampaignForm } from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { LotField } from './common';
import { lotBody } from './hooks';

type Section = 'fast' | 'decline' | 'ae' | 'uptake';
type Props = Pick<ScreenProps, 'client' | 'onSessionExpired'> & { campaign: VaccinationCampaign };

function eligibilityText(e: VaccinationCampaign['eligibility']): string {
  const x = e as { all?: boolean; jobCodes?: string[]; subsidiaries?: string[] };
  if (x.all) return 'All employees';
  if (x.jobCodes?.length) return `Jobs: ${x.jobCodes.join(', ')}`;
  if (x.subsidiaries?.length) return `Subsidiaries: ${x.subsidiaries.join(', ')}`;
  return '–';
}

const emptyCampaign = (): CampaignForm => ({ name: '', vaccineCode: '', eligibilityMode: 'all', eligibilityCodes: '', startDate: todayIso(), endDate: '', siteCodes: '', targetUptakePct: '75' });

function NewCampaign({ client, onSessionExpired, onCreated }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { onCreated: (c: VaccinationCampaign) => void }) {
  const [f, setF] = useState<CampaignForm>(emptyCampaign);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateCampaign(f);
    setErrors(errs);
    if (errs.length) return;
    const eligibility = f.eligibilityMode === 'all' ? { all: true as const } : f.eligibilityMode === 'jobCodes' ? { jobCodes: codes(f.eligibilityCodes) } : { subsidiaries: codes(f.eligibilityCodes) };
    let created: VaccinationCampaign | undefined;
    const ok = await action.run(async () => {
      created = await travelApi(client).createCampaign({ name: f.name.trim(), vaccineCode: f.vaccineCode.trim().toUpperCase(), eligibility, startDate: f.startDate, endDate: f.endDate, siteCodes: codes(f.siteCodes), targetUptakePct: Number(f.targetUptakePct) });
      return `Campaign ${created.name} created.`;
    });
    if (ok && created) {
      setF(emptyCampaign());
      onCreated(created);
    }
  }
  return (
    <details className="card">
      <summary>New vaccination campaign</summary>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="inline-form">
          <IdField id="vc-name" label="Name" required error={errorFor(errors, 'vc-name')}>{(db) => <input id="vc-name" aria-describedby={db} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</IdField>
          <IdField id="vc-vaccine" label="Vaccine code" required error={errorFor(errors, 'vc-vaccine')}>{(db) => <input id="vc-vaccine" size={10} aria-describedby={db} value={f.vaccineCode} onChange={(e) => setF({ ...f, vaccineCode: e.target.value.toUpperCase() })} />}</IdField>
          <Field label="Eligibility">
            {(id) => (
              <select id={id} value={f.eligibilityMode} onChange={(e) => setF({ ...f, eligibilityMode: e.target.value as CampaignForm['eligibilityMode'] })}>
                <option value="all">All employees</option>
                <option value="jobCodes">Job codes</option>
                <option value="subsidiaries">Subsidiaries</option>
              </select>
            )}
          </Field>
          {f.eligibilityMode !== 'all' && (
            <IdField id="vc-elig" label={f.eligibilityMode === 'jobCodes' ? 'Job codes' : 'Subsidiary codes'} required error={errorFor(errors, 'vc-elig')}>{(db) => <input id="vc-elig" aria-describedby={db} value={f.eligibilityCodes} onChange={(e) => setF({ ...f, eligibilityCodes: e.target.value })} />}</IdField>
          )}
        </div>
        <div className="inline-form">
          <IdField id="vc-start" label="Start" required error={errorFor(errors, 'vc-start')}>{(db) => <input id="vc-start" type="date" aria-describedby={db} value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} />}</IdField>
          <IdField id="vc-end" label="End" required error={errorFor(errors, 'vc-end')}>{(db) => <input id="vc-end" type="date" aria-describedby={db} value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} />}</IdField>
          <IdField id="vc-sites" label="Sites" required hint="Comma separated site codes" error={errorFor(errors, 'vc-sites')}>{(db) => <input id="vc-sites" aria-describedby={db} value={f.siteCodes} onChange={(e) => setF({ ...f, siteCodes: e.target.value })} />}</IdField>
          <IdField id="vc-target" label="Target uptake %" required error={errorFor(errors, 'vc-target')}>{(db) => <input id="vc-target" type="number" min={1} max={100} size={4} aria-describedby={db} value={f.targetUptakePct} onChange={(e) => setF({ ...f, targetUptakePct: e.target.value })} />}</IdField>
        </div>
        <button type="submit" disabled={action.busy}>Create campaign</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </details>
  );
}

/** Fast entry (E07 pattern): lot / site / route stay set; per person: scan the badge, tick consent, Enter. */
function FastEntry({ client, onSessionExpired, campaign }: Props) {
  const [sticky, setSticky] = useState({ lot: '', site: 'LEFT_DELTOID', route: 'IM' });
  const [person, setPerson] = useState({ ref: '', mode: 'AUTO' as IdMode, consent: false });
  const [log, setLog] = useState<{ ref: string; at: string }[]>([]);
  const action = useAction(onSessionExpired);
  const personRef = useRef<HTMLInputElement>(null);
  const lotRef = useRef<HTMLInputElement>(null);
  const consentRef = useRef<HTMLInputElement>(null);
  const lotState = sticky.lot.trim() ? lotInput(sticky.lot) : undefined;

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    const lot = lotBody(sticky.lot);
    if (!lot) return lotRef.current?.focus();
    const who = employeeOrBadge(person.ref, person.mode);
    if (!who) return personRef.current?.focus();
    if (!person.consent) return consentRef.current?.focus();
    const ok = await action.run(async () => {
      await travelApi(client).administer(campaign.id, { ...who, ...lot, site: sticky.site, route: sticky.route, consentConfirmed: true });
      return `${campaign.vaccineCode} recorded for ${person.ref.trim()}.`;
    });
    if (ok) {
      setLog([{ ref: person.ref.trim(), at: new Date().toISOString() }, ...log].slice(0, 30));
      setPerson({ ...person, ref: '', consent: false });
      personRef.current?.focus();
    }
  }

  return (
    <section aria-labelledby="vc-fast">
      <h3 id="vc-fast">Fast entry – {campaign.vaccineCode}</h3>
      <form className="form config-form" onSubmit={submit}>
        <fieldset>
          <legend>Session (kept between entries)</legend>
          <div className="inline-form">
            <LotField id="vc-lot" value={sticky.lot} onChange={(v) => setSticky({ ...sticky, lot: v })} inputRef={lotRef} onEnter={() => personRef.current?.focus()} />
            <Field label="Site">{(id) => <select id={id} value={sticky.site} onChange={(e) => setSticky({ ...sticky, site: e.target.value, route: e.target.value === 'ORAL' ? 'ORAL' : sticky.route === 'ORAL' ? 'IM' : sticky.route })}><SelectOptions values={INJECTION_SITES} /></select>}</Field>
            <Field label="Route">{(id) => <select id={id} value={sticky.route} onChange={(e) => setSticky({ ...sticky, route: e.target.value })}>{ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}</select>}</Field>
          </div>
        </fieldset>
        <fieldset disabled={!!lotState?.expired}>
          <legend>Person</legend>
          <div className="inline-form">
            <div className="field">
              <label htmlFor="vc-person">Badge or employee ID</label>
              <input id="vc-person" ref={personRef} autoComplete="off" maxLength={40} value={person.ref} onChange={(e) => setPerson({ ...person, ref: e.target.value })} />
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
            <label className="inline-row"><input ref={consentRef} type="checkbox" checked={person.consent} onChange={(e) => setPerson({ ...person, consent: e.target.checked })} /> Consent confirmed</label>
          </div>
        </fieldset>
        <button type="submit" disabled={action.busy || !!lotState?.expired}>Record dose</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {log.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Recorded this session ({log.length})</caption>
            <thead><tr><th scope="col">Person</th><th scope="col">Time</th></tr></thead>
            <tbody>{log.map((l, i) => <tr key={`${l.ref}-${i}`}><th scope="row">{l.ref}</th><td>{formatDate(l.at)}</td></tr>)}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function DeclineForm({ client, onSessionExpired, campaign }: Props) {
  const [f, setF] = useState({ employeeId: '', reason: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    const who = employeeOrBadge(f.employeeId, 'EMPLOYEE');
    if (!who) errs.push({ field: 'dec-employee', message: 'Enter the employee ID.' });
    if (!f.reason.trim()) errs.push({ field: 'dec-reason', message: 'Enter the reason.' });
    setErrors(errs);
    if (errs.length || !who || !('employeeId' in who)) return;
    const ok = await action.run(async () => {
      await travelApi(client).decline(campaign.id, { employeeId: who.employeeId, reason: f.reason.trim() });
      return `Decline recorded for ${who.employeeId}.`;
    });
    if (ok) setF({ employeeId: '', reason: '' });
  }
  return (
    <form className="form" onSubmit={submit} noValidate aria-labelledby="dec-title">
      <h3 id="dec-title">Record a decline</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="dec-employee" label="Employee ID" required error={errorFor(errors, 'dec-employee')}>{(db) => <input id="dec-employee" size={10} aria-describedby={db} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}</IdField>
        <IdField id="dec-reason" label="Reason" required error={errorFor(errors, 'dec-reason')}>{(db) => <input id="dec-reason" size={40} maxLength={500} aria-describedby={db} value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />}</IdField>
      </div>
      <button type="submit" disabled={action.busy}>Record decline</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

function AdverseEventForm({ client, onSessionExpired, campaign }: Props) {
  const [f, setF] = useState({ employeeId: '', event: 'LOCAL_REACTION', severity: 'MILD', onsetAt: toLocalInput(), actions: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const serious = f.event === 'ANAPHYLAXIS' || f.severity === 'SEVERE';
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    const who = employeeOrBadge(f.employeeId, 'EMPLOYEE');
    if (!who) errs.push({ field: 'ae-employee', message: 'Enter the employee ID.' });
    const onset = localToIso(f.onsetAt);
    if (!onset) errs.push({ field: 'ae-onset', message: 'Enter the onset time.' });
    if (!f.actions.trim()) errs.push({ field: 'ae-actions', message: 'Describe the actions taken.' });
    setErrors(errs);
    if (errs.length || !who || !('employeeId' in who) || !onset) return;
    const ok = await action.run(async () => {
      await travelApi(client).adverseEvent(campaign.id, { employeeId: who.employeeId, event: f.event as 'FEVER', severity: f.severity as 'MILD', onsetAt: onset, actions: f.actions.trim() });
      return `Adverse event recorded for ${who.employeeId}.`;
    });
    if (ok) setF({ ...f, employeeId: '', actions: '' });
  }
  return (
    <form className="form" onSubmit={submit} noValidate aria-labelledby="ae-title">
      <h3 id="ae-title">Record an adverse event</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="ae-employee" label="Employee ID" required error={errorFor(errors, 'ae-employee')}>{(db) => <input id="ae-employee" size={10} aria-describedby={db} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}</IdField>
        <Field label="Event">{(id) => <select id={id} value={f.event} onChange={(e) => setF({ ...f, event: e.target.value })}><SelectOptions values={AE_EVENTS} /></select>}</Field>
        <Field label="Severity">{(id) => <select id={id} value={f.severity} onChange={(e) => setF({ ...f, severity: e.target.value })}><SelectOptions values={AE_SEVERITIES} /></select>}</Field>
        <IdField id="ae-onset" label="Onset" required error={errorFor(errors, 'ae-onset')}>{(db) => <input id="ae-onset" type="datetime-local" aria-describedby={db} value={f.onsetAt} onChange={(e) => setF({ ...f, onsetAt: e.target.value })} />}</IdField>
      </div>
      <IdField id="ae-actions" label="Actions taken" required error={errorFor(errors, 'ae-actions')}>{(db) => <textarea id="ae-actions" rows={2} maxLength={2000} aria-describedby={db} value={f.actions} onChange={(e) => setF({ ...f, actions: e.target.value })} />}</IdField>
      {serious && <Alert kind="warning">Serious reaction: follow the anaphylaxis / emergency protocol and report through the national yellow-card scheme.</Alert>}
      <button type="submit" disabled={action.busy}>Record adverse event</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

function Uptake({ client, onSessionExpired, campaign }: Props) {
  const [groupBy, setGroupBy] = useState<string>('SUBSIDIARY');
  const data = useAsync(() => travelApi(client).uptake(campaign.id, groupBy), onSessionExpired, [campaign.id, groupBy]);
  const rows = data.data ?? [];
  const totals = uptakeTotals(rows);
  return (
    <section aria-labelledby="uptake-title">
      <h3 id="uptake-title">Uptake by unit</h3>
      <div className="filters inline-form">
        <Field label="Group by">{(id) => <select id={id} value={groupBy} onChange={(e) => setGroupBy(e.target.value)}><SelectOptions values={UPTAKE_GROUP_BY} /></select>}</Field>
      </div>
      {data.loading && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {data.data && rows.length === 0 && <Empty>No uptake data yet.</Empty>}
      {rows.length > 0 && (
        <>
          <div className="tiles" role="group" aria-label="Uptake KPI">
            <KpiTile label="Uptake (reported units)" value={totals.pct === undefined ? '–' : `${totals.pct.toFixed(1)} %`} target={`Target ≥ ${campaign.targetUptakePct} %`} status={kpiStatus(totals.pct, campaign.targetUptakePct, 'atLeast')} />
            <KpiTile label="Vaccinated" value={totals.vaccinated} />
            <KpiTile label="Declined" value={totals.declined} />
          </div>
          <div className="table-wrap">
            <table>
              <caption>Uptake by {humanise(groupBy).toLowerCase()} – groups below the minimum size are suppressed{totals.suppressedRows ? ` (${totals.suppressedRows} suppressed, not in totals)` : ''}</caption>
              <thead><tr><th scope="col">Unit</th><th scope="col" className="num">Eligible</th><th scope="col" className="num">Vaccinated</th><th scope="col" className="num">Declined</th><th scope="col">Uptake</th></tr></thead>
              <tbody>
                {rows.map((r) => {
                  const v = uptakeView(r, campaign.targetUptakePct);
                  return v.suppressed ? (
                    <tr key={r.key} className="row-suppressed">
                      <th scope="row">{r.key}</th>
                      <td colSpan={4} className="suppressed">Suppressed – group too small to report</td>
                    </tr>
                  ) : (
                    <tr key={r.key}>
                      <th scope="row">{r.key}</th>
                      <td className="num">{v.eligible}</td>
                      <td className="num">{v.vaccinated}</td>
                      <td className="num">{v.declined}</td>
                      <td><ToneBadge tone={v.tone}>{v.pct}</ToneBadge></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}

/** `#/vaccination-campaigns` – create, invite, fast entry, declines, adverse events, uptake by unit (E11-F04). */
export function VaccinationCampaigns({ client, session, onSessionExpired }: ScreenProps) {
  const api = travelApi(client);
  const list = useAsync(() => api.campaigns(), onSessionExpired);
  const [selectedId, setSelectedId] = useState<string>();
  const invite = useAction(onSessionExpired);
  const roles = session.roles;
  const sections: [Section, string, boolean][] = [
    ['fast', 'Fast entry', can(roles, 'vaccinationCampaigns')],
    ['decline', 'Declines', can(roles, 'vaccinationCampaigns')],
    ['ae', 'Adverse events', can(roles, 'vaccinationCampaigns')],
    ['uptake', 'Uptake', can(roles, 'vaccinationUptake')],
  ];
  const visible = sections.filter((s) => s[2]);
  const [section, setSection] = useState<Section>(visible[0]?.[0] ?? 'fast');
  const campaign = list.data?.find((c) => c.id === selectedId);

  return (
    <section>
      <PageTitle sub="Campaigns record doses in the immunisation registry with the campaign id. Uptake is reported by unit with small groups suppressed.">Vaccination campaigns</PageTitle>
      {can(roles, 'vaccinationCampaignCreate') && <NewCampaign client={client} onSessionExpired={onSessionExpired} onCreated={(c) => { list.reload(); setSelectedId(c.id); }} />}
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && list.data.length === 0 && <Empty>No vaccination campaigns.</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Campaigns</caption>
            <thead><tr><th scope="col">Campaign</th><th scope="col">Vaccine</th><th scope="col">Eligibility</th><th scope="col">Dates</th><th scope="col">Sites</th><th scope="col" className="num">Target</th><th scope="col">Invited</th></tr></thead>
            <tbody>
              {list.data.map((c) => (
                <tr key={c.id} aria-selected={c.id === selectedId}>
                  <th scope="row"><button type="button" className="link-button" onClick={() => setSelectedId(c.id)}>{c.name}</button></th>
                  <td>{c.vaccineCode}</td>
                  <td className="small">{eligibilityText(c.eligibility)}</td>
                  <td className="small">{formatDay(c.startDate)} – {formatDay(c.endDate)}</td>
                  <td className="small">{c.siteCodes.join(', ')}</td>
                  <td className="num">{c.targetUptakePct} %</td>
                  <td className="small">{c.invitedAt ? formatDate(c.invitedAt) : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {campaign && (
        <section className="card" aria-labelledby="vc-selected">
          <h2 id="vc-selected">{campaign.name}</h2>
          {can(roles, 'vaccinationCampaignInvite') && (
            <p>
              <button type="button" className="secondary" disabled={invite.busy} onClick={() => void invite.run(async () => {
                const r = await api.invite(campaign.id);
                list.reload();
                return typeof r?.invited === 'number' ? `${r.invited} invitations sent.` : 'Invitations sent.';
              })}>
                Send invitations
              </button>
            </p>
          )}
          <ActionFeedback error={invite.error} message={invite.message} />
          <div className="mode-switch" role="group" aria-label="Section">
            {visible.map(([id, label]) => (
              <button key={id} type="button" className={`secondary small-button${section === id ? ' mode-active' : ''}`} aria-pressed={section === id} onClick={() => setSection(id)}>{label}</button>
            ))}
          </div>
          {section === 'fast' && <FastEntry key={campaign.id} client={client} onSessionExpired={onSessionExpired} campaign={campaign} />}
          {section === 'decline' && <DeclineForm key={campaign.id} client={client} onSessionExpired={onSessionExpired} campaign={campaign} />}
          {section === 'ae' && <AdverseEventForm key={campaign.id} client={client} onSessionExpired={onSessionExpired} campaign={campaign} />}
          {section === 'uptake' && can(roles, 'vaccinationUptake') && <Uptake client={client} onSessionExpired={onSessionExpired} campaign={campaign} />}
        </section>
      )}
    </section>
  );
}
