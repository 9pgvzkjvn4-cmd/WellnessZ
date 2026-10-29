import { useState } from 'react';
import { describeError } from '../api/errors';
import { isOverdue, REFERRAL_STATUSES, referralStatusLabel, reportDueState, sortInbox, toPartnerReferrals, type PartnerReferral } from '../api/referrals';
import { referralRoute } from '../auth/roles';
import { formatDateTime } from '../components/format';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { orgLabel, type ScreenProps } from './types';

export function UrgencyBadge({ urgency }: { urgency: string }) {
  return urgency === 'URGENT' ? <StatusBadge status="urgent" label={S.referrals.urgent} /> : <StatusBadge status="routine" label={S.referrals.routine} />;
}

export function ReportDue({ referral, now }: { referral: PartnerReferral; now: Date }) {
  const state = reportDueState(referral, now);
  return (
    <>
      {formatDateTime(referral.reportDueAt)}
      {state === 'overdue' && <StatusBadge status="overdue" label={S.referrals.overdue} />}
      {state === 'due-soon' && <StatusBadge status="due-soon" label={S.referrals.dueSoon} />}
    </>
  );
}

export interface ReferralInboxProps {
  referrals: readonly PartnerReferral[];
  status: string;
  onStatus: (s: string) => void;
  overdueOnly: boolean;
  onOverdueOnly: (v: boolean) => void;
  now: Date;
}

/** Hook-free inbox table with status filter (server-side) and overdue filter (client-side). */
export function ReferralInbox({ referrals, status, onStatus, overdueOnly, onOverdueOnly, now }: ReferralInboxProps) {
  const shown = sortInbox(overdueOnly ? referrals.filter((r) => isOverdue(r, now)) : referrals, now);
  return (
    <>
      <form className="filters" role="search" aria-label="Filter referrals" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="ref-status">{S.referrals.status}</label>
          <select id="ref-status" value={status} onChange={(e) => onStatus(e.target.value)}>
            <option value="">{S.referrals.allStatuses}</option>
            {REFERRAL_STATUSES.map((s) => (
              <option key={s} value={s}>
                {referralStatusLabel(s)}
              </option>
            ))}
          </select>
        </div>
        <label className="checkbox" htmlFor="ref-overdue">
          <input id="ref-overdue" type="checkbox" checked={overdueOnly} onChange={(e) => onOverdueOnly(e.target.checked)} />
          {S.referrals.overdueOnly}
        </label>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        {S.referrals.showing(shown.length, referrals.length)}
      </p>
      {shown.length === 0 ? (
        <p className="muted">{S.referrals.empty}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">{S.referrals.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{S.referrals.colRef}</th>
                <th scope="col">{S.referrals.colPatient}</th>
                <th scope="col">{S.referrals.colService}</th>
                <th scope="col">{S.referrals.colUrgency}</th>
                <th scope="col">{S.referrals.colStatus}</th>
                <th scope="col">{S.referrals.colAppointment}</th>
                <th scope="col">{S.referrals.colReportDue}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.id} className={isOverdue(r, now) ? 'row-overdue' : undefined}>
                  <th scope="row">
                    <a href={`#${referralRoute(r.id)}`} aria-label={S.referrals.open(r.ref)}>
                      {r.ref}
                    </a>
                    <span className="muted small block">{formatDateTime(r.sentAt)}</span>
                  </th>
                  <td>{r.patient.displayName}</td>
                  <td>{r.serviceName}</td>
                  <td>
                    <UrgencyBadge urgency={r.urgency} />
                  </td>
                  <td>
                    <StatusBadge status={r.status} label={referralStatusLabel(r.status)} />
                  </td>
                  <td>{formatDateTime(r.appointmentAt)}</td>
                  <td>
                    <ReportDue referral={r} now={now} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export function Referrals({ client, me, onSessionExpired }: ScreenProps) {
  const [status, setStatus] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const data = useAsync(async () => toPartnerReferrals(await client.api<unknown>(`/partner/referrals${status ? `?status=${encodeURIComponent(status)}` : ''}`)), onSessionExpired);
  const changeStatus = (s: string) => {
    setStatus(s);
    data.reload();
  };
  return (
    <section>
      <PageTitle>{S.referrals.title}</PageTitle>
      <p className="lead">{S.referrals.lead}</p>
      <ConfidentialityNote>{S.confidentiality.provider(orgLabel(me))}</ConfidentialityNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </div>
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && (
        <ReferralInbox referrals={data.data} status={status} onStatus={changeStatus} overdueOnly={overdueOnly} onOverdueOnly={setOverdueOnly} now={new Date()} />
      )}
    </section>
  );
}
