import { toCaseStatusDetail } from '../api/statusOnly';
import type { AuthClient } from '../auth/AuthClient';
import { humanise } from './format';
import { useAsync } from './useAsync';

/** Case reference / employee / fitness status (status-only projection) for case-scoped HR screens. */
export function CaseHeader({ client, caseId, onSessionExpired }: { client: Pick<AuthClient, 'api'>; caseId: string; onSessionExpired: () => void }) {
  const d = useAsync(async () => toCaseStatusDetail(await client.api<unknown>(`/cases/${encodeURIComponent(caseId)}/status`)), onSessionExpired).data;
  return (
    <p className="muted">
      Case <strong className="mono">{d?.caseRef ?? caseId}</strong>
      {d?.employeeDisplayName && <> · {d.employeeDisplayName}</>}
      {d?.fitnessStatus && <> · Fitness for work: {humanise(d.fitnessStatus)}</>}
    </p>
  );
}
