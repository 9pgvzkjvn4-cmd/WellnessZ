import { supportApi, type Governance, type ProtocolStep } from '../../api/support';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Dl, Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { countdown } from '../../domain/support';
import type { ScreenProps } from '../types';
import { CountdownBadge, RestrictedBanner } from './common';
import { useNow } from './useNow';
import { supportCaseLink } from './util';

const stepText = (s: ProtocolStep): string => (typeof s === 'string' ? s : [s.title, s.text].filter(Boolean).join(' – '));

function signOffText(g: Governance): string {
  const s = g.signOff;
  if (!s) return 'Pending CMO / mental-health lead sign-off (E09-BL-014)';
  if (typeof s === 'string') return humanise(s);
  if (s.signedBy) return `Signed off by ${s.signedBy}${s.signedAt ? ` on ${formatDay(s.signedAt)}` : ''}`;
  return `${humanise(s.status ?? 'PENDING')}${s.pendingWith?.length ? ` – waiting for ${s.pendingWith.join(', ')}` : ''}`;
}

/** `#/support/urgent` – open urgent items (PHQ-9 item 9 > 0, severe band, programme referrals) with a call-back-by countdown. */
export function UrgentQueue({ client, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const now = useNow();
  const list = useAsync(() => api.urgent(), onSessionExpired);
  const protocol = useAsync(() => api.urgentProtocol(), onSessionExpired);
  const governance = useAsync(() => api.governance(), onSessionExpired);
  const rows = [...(list.data ?? [])].sort((a, b) => a.callBackBy.localeCompare(b.callBackBy));
  const overdue = rows.filter((r) => countdown(r.callBackBy, now).overdue).length;

  return (
    <section>
      <PageTitle sub="Same-day call-backs. Work the oldest deadline first; record the outcome on the support case.">Urgent call-backs</PageTitle>
      <RestrictedBanner />
      <div className="tiles" role="group" aria-label="Urgent queue summary">
        <div className="tile"><span className="tile-value">{rows.length}</span><span className="kpi-label">Open urgent items</span></div>
        <div className="tile"><span className={`tile-value${overdue ? ' text-red' : ''}`}>{overdue}</span><span className="kpi-label">Past call-back time</span></div>
      </div>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No open urgent items.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Urgent items by call-back deadline</caption>
            <thead><tr><th scope="col">Call back by</th><th scope="col">Case</th><th scope="col">Employee</th><th scope="col">Trigger</th><th scope="col">Raised</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id ?? `${r.caseId}-${i}`}>
                  <td>
                    <CountdownBadge dueAt={r.callBackBy} now={now} />
                    <div className="small muted">{formatDate(r.callBackBy)}</div>
                  </td>
                  <th scope="row"><a href={supportCaseLink(r.caseId)}>{r.ref ?? 'Open case'}</a></th>
                  <td>{r.displayName ?? '–'} {r.employeeId && <span className="small muted">{r.employeeId}</span>}</td>
                  <td>{r.reason ? humanise(r.reason) : r.band ? `Screen: ${humanise(r.band)}` : humanise(r.source)}</td>
                  <td className="small">{formatDate(r.raisedAt)}</td>
                  <td><a className="button secondary small-button" href={supportCaseLink(r.caseId)} aria-label={`Record contact outcome for ${r.ref ?? r.caseId}`}>Record outcome</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="grid-2">
        <section className="card" aria-labelledby="urgent-protocol">
          <h2 id="urgent-protocol">Urgent-risk protocol{protocol.data ? ` (version ${protocol.data.version})` : ''}</h2>
          {protocol.loading && <Loading />}
          {!!protocol.error && <ErrorAlert error={protocol.error} />}
          {protocol.data && (
            <ol className="question-list">
              {protocol.data.steps.map((s, i) => <li key={i}>{stepText(s)}</li>)}
            </ol>
          )}
        </section>
        <section className="card" aria-labelledby="urgent-governance">
          <h2 id="urgent-governance">Clinical governance</h2>
          {governance.loading && <Loading />}
          {!!governance.error && <ErrorAlert error={governance.error} />}
          {governance.data && (
            <>
              <Dl
                items={[
                  ['Protocol version', governance.data.protocolVersion ?? '–'],
                  ['Sign-off', <ToneBadge tone={typeof governance.data.signOff === 'object' && governance.data.signOff?.signedBy ? 'green' : 'amber'}>{signOffText(governance.data)}</ToneBadge>],
                ]}
              />
              {(governance.data.instruments ?? []).length > 0 && (
                <table>
                  <thead><tr><th scope="col">Instrument</th><th scope="col">Licence</th></tr></thead>
                  <tbody>
                    {(governance.data.instruments ?? []).map((i) => (
                      <tr key={i.code}><th scope="row">{i.title ?? i.code}</th><td>{i.licence ?? '–'} {i.licenceStatus && <StatusBadge status={i.licenceStatus} />}</td></tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  );
}

/** `#/support/eap` – EAP referrals of the support caseload with the 5-working-day first-appointment deadline. */
export function EapReferrals({ client, onSessionExpired }: ScreenProps) {
  const api = supportApi(client);
  const now = useNow(60_000);
  const list = useAsync(() => api.cases(), onSessionExpired);
  const rows = (list.data ?? []).filter((c) => c.eapReferral).sort((a, b) => (a.eapReferral?.firstAppointmentDueBy ?? '').localeCompare(b.eapReferral?.firstAppointmentDueBy ?? ''));
  return (
    <section>
      <PageTitle sub="Referrals to the employee assistance programme partner. The partner returns attendance and closure only – never clinical content.">EAP referrals</PageTitle>
      <RestrictedBanner />
      <p className="small muted">New referrals are made from the support case (consent required).</p>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No EAP referrals.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} EAP referral{rows.length === 1 ? '' : 's'}</caption>
            <thead><tr><th scope="col">Referral</th><th scope="col">Support case</th><th scope="col">Employee</th><th scope="col">Partner</th><th scope="col" className="num">Sessions</th><th scope="col">Urgency</th><th scope="col">Status</th><th scope="col">First appointment due</th></tr></thead>
            <tbody>
              {rows.map((c) => {
                const r = c.eapReferral!;
                const waiting = r.status === 'SENT' || r.status === 'ACCEPTED';
                return (
                  <tr key={r.id}>
                    <th scope="row">{r.ref}</th>
                    <td><a href={supportCaseLink(c.id)}>{c.ref}</a></td>
                    <td>{c.displayName}</td>
                    <td>{r.partnerCode ?? '–'}</td>
                    <td className="num">{r.sessionsAuthorised ?? '–'}</td>
                    <td>{humanise(r.urgency)}</td>
                    <td><StatusBadge status={r.status} /></td>
                    <td>
                      {formatDay(r.firstAppointmentDueBy)}{' '}
                      {waiting && r.firstAppointmentDueBy && <CountdownBadge dueAt={r.firstAppointmentDueBy} now={now} amberWithinMin={2 * 24 * 60} redWithinMin={0} />}
                    </td>
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
