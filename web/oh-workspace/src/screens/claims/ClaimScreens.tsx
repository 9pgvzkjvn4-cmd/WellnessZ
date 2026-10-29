import { useState } from 'react';
import { CLAIM_STATUSES, CLAIM_TYPES, claimsApi, LEGAL_REQUEST_STATUSES, REPORT_STATUSES, type Claim, type ClaimFilter } from '../../api/claims';
import { PREAUTH_STATUSES, treatmentApi, type Preauth } from '../../api/treatment';
import { formatDate, formatDay, humanise } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { Alert, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { kpiStatus, slaTone, todayIso } from '../../domain/absence';
import { claimTone, deadlineCountdown } from '../../domain/claims';
import { formatMoney } from '../../domain/money';
import { addDays } from '../../domain/treatment';
import type { ScreenProps } from '../types';
import { PreauthDecisionDialog } from '../treatment/CostsTab';
import { claimLink, pct } from '../treatment/util';
import { LegalRequestList } from './LegalRequests';
import { OpenClaimForm } from './OpenClaimForm';

function ClaimTable({ rows, caption, money }: { rows: Claim[]; caption: string; money: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Claim</th>
            <th scope="col">Employee</th>
            <th scope="col">Type</th>
            <th scope="col">Status</th>
            <th scope="col">Payer</th>
            <th scope="col">Incident</th>
            <th scope="col">Nurse CM</th>
            <th scope="col" className="num">Open tasks</th>
            <th scope="col">Report</th>
            {money && <th scope="col" className="num">Paid</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.id}>
              <th scope="row">
                <a href={claimLink(c.id)}>{c.claimRef}</a>
                <span className="badge-row">
                  {c.lostTime && <ToneBadge tone="amber">Lost time</ToneBadge>}
                  {c.litigationHold && <ToneBadge tone="red">Hold</ToneBadge>}
                  {c.privileged && <ToneBadge tone="amber">Privileged</ToneBadge>}
                </span>
              </th>
              <td>{c.displayName} <span className="small muted">{c.unit ?? ''}</span></td>
              <td>{humanise(c.claimType)}</td>
              <td><ToneBadge tone={claimTone(c.status)}>{humanise(c.status)}</ToneBadge></td>
              <td>{c.payerName ?? c.payerCode ?? '–'}</td>
              <td>{formatDay(c.incidentDate)}</td>
              <td>{c.nurseCaseManager ?? (c.lostTime ? <ToneBadge tone="amber">Unassigned</ToneBadge> : '–')}</td>
              <td className="num">{c.openTasks ?? 0}</td>
              <td>{c.reportStatus ? <StatusBadge status={c.reportStatus} /> : '–'}</td>
              {money && <td className="num">{formatMoney(c.paidTotal)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** `#/claims` – claims worklist with filters and "open claim" (CLAIMS_COORDINATOR). */
export function ClaimList(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const [f, setF] = useState<ClaimFilter>({ status: '', type: '', lostTime: '', payerCode: '', q: '' });
  const [applied, setApplied] = useState(f);
  const list = useAsync(() => claimsApi(client).claims(applied), onSessionExpired, [applied]);
  const [opening, setOpening] = useState(false);
  const [message, setMessage] = useState<string>();
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Workers' compensation, employer liability, group medical and third-party claims. Status and money only – no clinical record.">Claims</PageTitle>
      {message && <Alert kind="success">{message}</Alert>}
      {opening ? (
        <OpenClaimForm props={props} onCancel={() => setOpening(false)} onCreated={(c) => { setOpening(false); setMessage(`Claim ${c.claimRef} opened.`); list.reload(); }} />
      ) : (
        <button type="button" onClick={() => { setMessage(undefined); setOpening(true); }}>Open claim</button>
      )}
      <form className="filters inline-form" role="search" onSubmit={(e) => { e.preventDefault(); setApplied(f); }}>
        <Field label="Search">{(id) => <input id={id} type="search" value={f.q} onChange={(e) => setF({ ...f, q: e.target.value })} placeholder="Claim ref, name, policy" />}</Field>
        <Field label="Status">{(id) => <select id={id} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><SelectOptions values={CLAIM_STATUSES} empty="All" /></select>}</Field>
        <Field label="Type">{(id) => <select id={id} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}><SelectOptions values={CLAIM_TYPES} empty="All" /></select>}</Field>
        <Field label="Lost time">
          {(id) => (
            <select id={id} value={String(f.lostTime)} onChange={(e) => setF({ ...f, lostTime: e.target.value === '' ? '' : e.target.value === 'true' })}>
              <option value="">All</option>
              <option value="true">Lost time</option>
              <option value="false">No lost time</option>
            </select>
          )}
        </Field>
        <Field label="Payer">{(id) => <input id={id} value={f.payerCode} onChange={(e) => setF({ ...f, payerCode: e.target.value.toUpperCase() })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No claims match.</Empty>}
      {rows.length > 0 && <ClaimTable rows={rows} caption={`${rows.length} claim${rows.length === 1 ? '' : 's'}`} money />}
    </section>
  );
}

/** `#/claims/preauthorisations` – pre-authorisation follow-up for claims coordinators (decision, SLA). */
export function PreauthList(props: ScreenProps) {
  const [status, setStatus] = useState('SUBMITTED');
  const list = useAsync(() => treatmentApi(props.client).preauths(status || undefined), props.onSessionExpired, [status]);
  const [deciding, setDeciding] = useState<Preauth>();
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Requests submitted to payers. The insurer SLA is 48 hours; record decisions received by phone or e-mail here.">Pre-authorisations</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={PREAUTH_STATUSES} empty="All" /></select>}</Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No pre-authorisations.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} pre-authorisation{rows.length === 1 ? '' : 's'}</caption>
            <thead><tr><th scope="col">Ref</th><th scope="col">Employee / case</th><th scope="col">Payer</th><th scope="col">Service / provider</th><th scope="col" className="num">Estimate</th><th scope="col">Status</th><th scope="col">SLA</th><th scope="col" className="num">Approved</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((p) => {
                const sla = p.status === 'SUBMITTED' ? slaTone(p.slaDueAt) : undefined;
                return (
                  <tr key={p.id} className={sla?.tone === 'red' ? 'row-breached' : undefined}>
                    <th scope="row">{p.ref}</th>
                    <td>{p.displayName ?? '–'} <span className="small muted">{p.caseRef ?? ''}</span></td>
                    <td>{p.payerName ?? p.payerCode ?? '–'}</td>
                    <td>{p.serviceCode} · {p.providerCode}</td>
                    <td className="num">{formatMoney(p.estimate)}</td>
                    <td><StatusBadge status={p.status} /></td>
                    <td>{sla ? <ToneBadge tone={sla.tone}>{sla.label}</ToneBadge> : formatDate(p.decidedAt)}</td>
                    <td className="num">{formatMoney(p.approvedAmount)}</td>
                    <td>{p.status === 'SUBMITTED' && <button type="button" className="secondary small-button" aria-label={`Record decision for ${p.ref}`} onClick={() => setDeciding(p)}>Decision</button>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {deciding && <PreauthDecisionDialog preauth={deciding} props={props} onClose={() => setDeciding(undefined)} onDone={() => { setDeciding(undefined); list.reload(); }} />}
    </section>
  );
}

/** `#/claims/reports` – statutory / insurer report deadline dashboard with countdowns. */
export function ClaimReportsDashboard({ client, onSessionExpired }: ScreenProps) {
  const [f, setF] = useState({ status: '', dueWithinHours: '' });
  const list = useAsync(() => claimsApi(client).reports({ status: f.status || undefined, dueWithinHours: f.dueWithinHours ? Number(f.dueWithinHours) : undefined }), onSessionExpired, [f]);
  const rows = [...(list.data ?? [])].sort((a, b) => a.dueAt.localeCompare(b.dueAt));
  const [now] = useState(() => Date.now());
  const open = rows.filter((r) => r.status === 'DRAFT' || r.status === 'LATE' || r.status === 'REJECTED');
  const overdue = open.filter((r) => deadlineCountdown(r, now).tone === 'red').length;
  const due24 = open.filter((r) => deadlineCountdown(r, now).tone === 'amber').length;
  const submitted = rows.filter((r) => r.submittedAt);
  const onTime = submitted.length ? (submitted.filter((r) => r.submittedAt! <= r.dueAt).length / submitted.length) * 100 : undefined;

  return (
    <section>
      <PageTitle sub="First reports of injury to authorities and insurers. Reminders go out 24 h before the deadline; past-due reports are marked late and escalated.">Report deadlines</PageTitle>
      <div className="tiles" role="group" aria-label="Report KPIs">
        <KpiTile label="Overdue" value={overdue} status={overdue > 0 ? 'MISSED' : undefined} />
        <KpiTile label="Due within 24 h" value={due24} />
        <KpiTile label="Open drafts" value={open.length} />
        <KpiTile label="On-time submissions (listed)" value={pct(onTime, 1)} target="Target 100 %" status={kpiStatus(onTime, 100, 'atLeast')} />
      </div>
      <div className="filters inline-form">
        <Field label="Status">{(id) => <select id={id} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><SelectOptions values={REPORT_STATUSES} empty="All" /></select>}</Field>
        <Field label="Due within">
          {(id) => (
            <select id={id} value={f.dueWithinHours} onChange={(e) => setF({ ...f, dueWithinHours: e.target.value })}>
              <option value="">Any time</option>
              <option value="24">24 hours</option>
              <option value="72">72 hours</option>
              <option value="168">7 days</option>
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No reports.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} report{rows.length === 1 ? '' : 's'}, soonest deadline first</caption>
            <thead><tr><th scope="col">Report</th><th scope="col">Claim</th><th scope="col">Form</th><th scope="col">Recipient</th><th scope="col">Status</th><th scope="col">Due</th><th scope="col">Countdown</th></tr></thead>
            <tbody>
              {rows.map((r) => {
                const c = deadlineCountdown(r, now);
                return (
                  <tr key={r.id} className={c.tone === 'red' ? 'row-breached' : undefined}>
                    <th scope="row">{r.ref}</th>
                    <td>{r.claimId ? <a href={claimLink(r.claimId, 'reports')}>{r.claimRef ?? 'Claim'}</a> : (r.claimRef ?? '–')}</td>
                    <td>{r.formCode}</td>
                    <td>{r.recipientCode}</td>
                    <td><StatusBadge status={r.status} /></td>
                    <td className="small">{formatDate(r.dueAt)}</td>
                    <td><ToneBadge tone={c.tone}>{c.label}</ToneBadge></td>
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

/** `#/claims/nurse-assignment` – lost-time claims with a nurse case manager within 48 h (NURSING_HEAD KPI). */
export function NurseAssignment({ client, onSessionExpired }: ScreenProps) {
  const [range, setRange] = useState({ from: addDays(todayIso(), -90), to: todayIso() });
  const [applied, setApplied] = useState(range);
  const kpi = useAsync(() => claimsApi(client).nurseAssignment(applied), onSessionExpired, [applied]);
  const k = kpi.data;
  return (
    <section>
      <PageTitle sub="Every lost-time claim gets a nurse case manager automatically – the case owner if a nurse, else the least-loaded case manager within the caseload cap.">Nurse case manager assignment</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {kpi.loading && !k && <Loading />}
      {!!kpi.error && <ErrorAlert error={kpi.error} />}
      {k && (
        <>
          <div className="tiles" role="group" aria-label="Assignment KPI">
            <KpiTile label="Lost-time claims" value={k.lostTimeClaims} />
            <KpiTile label="Assigned within 48 h" value={k.assignedWithin48h} />
            <KpiTile label="Within SLA" value={pct(k.pct, 1)} target="Target 100 %" status={kpiStatus(k.pct, 100, 'atLeast')} />
          </div>
          <h2>Unassigned</h2>
          {k.unassigned.length === 0 ? (
            <Empty>Every lost-time claim has a nurse case manager.</Empty>
          ) : (
            <ul className="plain-list">
              {k.unassigned.map((u) => (
                <li key={u.claimRef}><ToneBadge tone={slaTone(new Date(Date.parse(u.notifiedAt) + 48 * 3_600_000).toISOString()).tone}>{u.claimRef}</ToneBadge> <span className="small">notified {formatDate(u.notifiedAt)}</span></li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}

/** `#/legal` – LEGAL_COUNSEL: litigated / held claims, legal requests and report versions. */
export function LegalScreen(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const api = claimsApi(client);
  const claims = useAsync(() => api.claims(), onSessionExpired);
  const [status, setStatus] = useState('');
  const requests = useAsync(() => api.legalRequests(status || undefined), onSessionExpired, [status]);
  const rows = claims.data ?? [];
  return (
    <section>
      <PageTitle sub="Litigated claims and claims on litigation hold, medico-legal / expert / IME requests and signed report versions.">Legal & litigation</PageTitle>
      <h2>Claims</h2>
      {claims.loading && <Loading />}
      {!!claims.error && <ErrorAlert error={claims.error} />}
      {claims.data && rows.length === 0 && <Empty>No litigated or held claims.</Empty>}
      {rows.length > 0 && <ClaimTable rows={rows} caption={`${rows.length} claim${rows.length === 1 ? '' : 's'} (litigated or on hold)`} money={false} />}
      <h2>Legal requests</h2>
      <div className="filters inline-form">
        <Field label="Status">{(id) => <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}><SelectOptions values={LEGAL_REQUEST_STATUSES} empty="All" /></select>}</Field>
      </div>
      {requests.loading && <Loading />}
      {!!requests.error && <ErrorAlert error={requests.error} />}
      {requests.data && <LegalRequestList rows={requests.data} props={props} onChanged={requests.reload} />}
    </section>
  );
}

/** `#/claims/disclosures` – DPO report of every claim disclosure (recipient, documents, consent, by, at). */
export function ClaimDisclosureReport({ client, onSessionExpired }: ScreenProps) {
  const [range, setRange] = useState({ from: addDays(todayIso(), -30), to: todayIso() });
  const [applied, setApplied] = useState(range);
  const list = useAsync(() => claimsApi(client).disclosureReport(applied), onSessionExpired, [applied]);
  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Documents shared with insurers, TPAs and authorities from claims – each under a recorded consent or legal basis.">Claim disclosures</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No claim disclosures in this period.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} disclosure{rows.length === 1 ? '' : 's'}</caption>
            <thead><tr><th scope="col">When</th><th scope="col">Claim</th><th scope="col">Recipient</th><th scope="col">Documents</th><th scope="col">Consent / basis</th><th scope="col">By</th></tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.at ?? ''}-${i}`}>
                  <th scope="row" className="small">{formatDate(r.at)}</th>
                  <td>{r.claimRef ?? '–'}</td>
                  <td>{r.recipientCode ?? r.recipient ?? '–'}</td>
                  <td className="small">{(r.documents ?? []).map((d) => (typeof d === 'string' ? d : `${d.title ?? d.documentId}${d.type ? ` (${humanise(d.type)})` : ''}`)).join('; ') || '–'}</td>
                  <td className="mono small">{r.consentId ?? (r.purpose ? humanise(r.purpose) : '–')}</td>
                  <td>{r.by ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
