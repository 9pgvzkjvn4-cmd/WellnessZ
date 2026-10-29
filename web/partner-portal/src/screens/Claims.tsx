import { useState } from 'react';
import { CLAIM_STATUSES, loadClaims, type PartnerClaim } from '../api/claims';
import { describeError } from '../api/errors';
import { claimRoute } from '../auth/roles';
import { formatDay, humanise } from '../components/format';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { orgLabel, type ScreenProps } from './types';

/** Hook-free claims table. */
export function ClaimsTable({ claims, status, onStatus }: { claims: readonly PartnerClaim[]; status: string; onStatus: (s: string) => void }) {
  return (
    <>
      <form className="filters" role="search" aria-label="Filter claims" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="claim-status">{S.claims.status}</label>
          <select id="claim-status" value={status} onChange={(e) => onStatus(e.target.value)}>
            <option value="">{S.claims.allStatuses}</option>
            {CLAIM_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanise(s)}
              </option>
            ))}
          </select>
        </div>
      </form>
      {claims.length === 0 ? (
        <p className="muted">{S.claims.empty}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">{S.claims.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{S.claims.colClaim}</th>
                <th scope="col">{S.claims.colEmployee}</th>
                <th scope="col">{S.claims.colType}</th>
                <th scope="col">{S.claims.colStatus}</th>
                <th scope="col">{S.claims.colIncident}</th>
                <th scope="col">{S.claims.colLostTime}</th>
                <th scope="col" className="num">
                  {S.claims.colTasks}
                </th>
                <th scope="col" className="num">
                  {S.claims.colMessages}
                </th>
              </tr>
            </thead>
            <tbody>
              {claims.map((c) => (
                <tr key={c.id}>
                  <th scope="row">
                    <a href={`#${claimRoute(c.id)}`} aria-label={S.claims.open(c.claimRef)}>
                      {c.claimRef}
                    </a>
                  </th>
                  <td>{c.displayName}</td>
                  <td>{humanise(c.claimType)}</td>
                  <td>
                    <StatusBadge status={c.status} />
                  </td>
                  <td>{formatDay(c.incidentDate)}</td>
                  <td>{c.lostTime ? <StatusBadge status="lost-time" label={S.claims.yes} /> : S.claims.no}</td>
                  <td className="num">{c.openTasks}</td>
                  <td className="num">{c.unreadMessages > 0 ? <StatusBadge status="unread" label={S.claims.unread(c.unreadMessages)} /> : 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export function Claims({ client, me, onSessionExpired }: ScreenProps) {
  const [status, setStatus] = useState('');
  const data = useAsync(() => loadClaims(client, status), onSessionExpired);
  return (
    <section>
      <PageTitle>{S.claims.title}</PageTitle>
      <p className="lead">{S.claims.lead}</p>
      <ConfidentialityNote>{S.confidentiality.adjuster(orgLabel(me))}</ConfidentialityNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </div>
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && (
        <ClaimsTable
          claims={data.data}
          status={status}
          onStatus={(s) => {
            setStatus(s);
            data.reload();
          }}
        />
      )}
    </section>
  );
}
