import { useState } from 'react';
import { claimsApi } from '../../api/claims';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Alert, Dl, Empty, ErrorAlert, Loading } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { claimTone } from '../../domain/claims';
import { formatMoney } from '../../domain/money';
import type { CaseTabProps } from '../case/caseTabProps';
import { ClaimCollaboration } from '../claims/Collaboration';
import { OpenClaimForm } from '../claims/OpenClaimForm';
import { claimLink } from './util';

/** `#/cases/{id}/claim` – claim summary, or "open claim" for CLAIMS_COORDINATOR / CASE_MANAGER; nurse ↔ adjuster collaboration. */
export function ClaimTab(props: CaseTabProps) {
  const { client, session, onSessionExpired, caseId } = props;
  const status = useAsync(() => claimsApi(client).caseClaimStatus(caseId), onSessionExpired);
  const [opening, setOpening] = useState(false);
  const [message, setMessage] = useState<string>();
  const s = status.data;

  return (
    <div>
      <h2 className="visually-hidden">Claim</h2>
      {message && <Alert kind="success">{message}</Alert>}
      {status.loading && !status.data && <Loading label="Loading claim…" />}
      {!!status.error && <ErrorAlert error={status.error} />}
      {!status.loading && !status.error && !s?.claimId && (
        <>
          <Empty>No claim is linked to this case.</Empty>
          {can(session.roles, 'claimCreate') &&
            (opening ? (
              <OpenClaimForm
                props={props}
                caseId={caseId}
                onCancel={() => setOpening(false)}
                onCreated={(c) => {
                  setOpening(false);
                  setMessage(`Claim ${c.claimRef} opened.`);
                  status.reload();
                  props.refresh();
                }}
              />
            ) : (
              <button type="button" onClick={() => setOpening(true)}>Open claim</button>
            ))}
        </>
      )}
      {s?.claimId && (
        <>
          <section className="tile" aria-label="Claim summary">
            <h3>
              {s.claimRef} <ToneBadge tone={claimTone(s.status)}>{humanise(s.status)}</ToneBadge>
            </h3>
            <Dl
              items={[
                ['Type', humanise(s.claimType)],
                ['Lost time', s.lostTime ? 'Yes' : 'No'],
                ['Litigation hold', s.litigationHold ? <ToneBadge tone="red">On hold – documents are retained</ToneBadge> : 'No'],
                ...(s.paidTotal ? ([['Paid to date', formatMoney(s.paidTotal)]] as [string, string][]) : []),
              ]}
            />
            {can(session.roles, 'claimDetail') && <a href={claimLink(s.claimId)}>Open the claim record</a>}
            <p className="small muted">Claims staff and the payer see status, RTW milestones and consented documents only – never the clinical record.</p>
          </section>
          {can(session.roles, 'claimCollaboration') && <ClaimCollaboration props={props} claimId={s.claimId} />}
        </>
      )}
    </div>
  );
}
