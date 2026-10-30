import { supportApi } from '../../api/support';
import type { AuthClient } from '../../auth/AuthClient';
import { can } from '../../auth/roles';
import { useAsync } from '../../components/useAsync';

/**
 * Confidentiality rule 1: general OH users see only a "restricted case" placeholder (count) for E09 support cases –
 * never the content. Named clinicians get a link to the support caseload instead. Renders nothing when there are none
 * or the count cannot be read (the server answers 404 for roles without access).
 */
export function RestrictedCasePlaceholder({ client, roles, employeeId, onSessionExpired }: { client: AuthClient; roles: readonly string[]; employeeId: string; onSessionExpired: () => void }) {
  const allowed = can(roles, 'supportPlaceholder');
  const count = useAsync(() => (allowed ? supportApi(client).placeholder(employeeId) : Promise.resolve({ restrictedCases: 0 })), onSessionExpired, [employeeId, allowed]);
  const n = count.data?.restrictedCases ?? 0;
  if (!allowed || count.error || n <= 0) return null;
  return (
    <div className="alert alert-info" role="status">
      <strong>
        {n} restricted case{n === 1 ? '' : 's'}
      </strong>{' '}
      – confidential support records held by the named mental-health clinicians. Content is not shown here; access attempts are audited.
      {can(roles, 'supportCases') && (
        <>
          {' '}
          <a href="#/support/cases">Open support cases</a>
        </>
      )}
    </div>
  );
}
