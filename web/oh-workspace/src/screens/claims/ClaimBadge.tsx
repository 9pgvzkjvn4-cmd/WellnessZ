import { claimsApi } from '../../api/claims';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { useAsync } from '../../components/useAsync';
import { claimTone } from '../../domain/claims';
import { formatMoney } from '../../domain/money';
import type { ScreenProps } from '../types';
import { claimLink } from '../treatment/util';

/** Case-header claim badge from `GET /cases/{id}/claim-status` (204 → nothing shown; errors are silent – the header must not break). */
export function ClaimBadge({ client, session, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const status = useAsync(() => claimsApi(client).caseClaimStatus(caseId).catch(() => undefined), onSessionExpired, [caseId]);
  const s = status.data;
  if (!s?.claimId) return null;
  const label = (
    <>
      Claim {s.claimRef} · {humanise(s.status)}
      {s.lostTime ? ' · lost time' : ''}
      {s.litigationHold ? ' · legal hold' : ''}
      {s.paidTotal ? ` · paid ${formatMoney(s.paidTotal)}` : ''}
    </>
  );
  return can(session.roles, 'claimDetail') ? (
    <a className="claim-badge-link" href={claimLink(s.claimId)} aria-label={`Open claim ${s.claimRef}`}>
      <ToneBadge tone={s.litigationHold ? 'red' : claimTone(s.status)}>{label}</ToneBadge>
    </a>
  ) : (
    <a className="claim-badge-link" href={`#/cases/${encodeURIComponent(caseId)}/claim`}>
      <ToneBadge tone={s.litigationHold ? 'red' : claimTone(s.status)}>{label}</ToneBadge>
    </a>
  );
}
