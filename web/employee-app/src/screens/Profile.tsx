import type { Me } from '../api/types';
import type { RuntimeConfig } from '../config/runtimeConfig';
import { getSecureStorage } from '../auth/secureStorage';
import { describeError, humanise } from '../components/format';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function Profile({ client, onSessionExpired, config }: ScreenProps & { config: RuntimeConfig }) {
  const me = useAsync(() => client.api<Me>('/me'), onSessionExpired);

  return (
    <section>
      <PageTitle>Profile</PageTitle>
      {me.loading && <Loading />}
      {!!me.error && <Alert kind="error">{describeError(me.error)}</Alert>}
      {me.data && (
        <dl className="details">
          <dt>Name</dt>
          <dd>{me.data.displayName ?? '–'}</dd>
          <dt>Username</dt>
          <dd>{me.data.username ?? '–'}</dd>
          <dt>Employee number</dt>
          <dd>{me.data.employeeId ?? '–'}</dd>
          <dt>User ID</dt>
          <dd className="mono">{me.data.subject}</dd>
          <dt>Roles</dt>
          <dd>{me.data.roles?.length ? me.data.roles.map(humanise).join(', ') : '–'}</dd>
          <dt>Organisation scope</dt>
          <dd>{formatScope(me.data.orgScope)}</dd>
          <dt>Maximum disclosure level</dt>
          <dd>{me.data.maxDisclosure ? humanise(me.data.maxDisclosure) : '–'}</dd>
        </dl>
      )}

      <h2>About this app</h2>
      <dl className="details">
        <dt>Sign-in mode</dt>
        <dd>{client.mode === 'bff' ? 'Web session (secure cookie)' : 'Mobile app (OIDC + PKCE)'}</dd>
        <dt>Managed by organisation</dt>
        <dd>{config.source.managed === 'none' ? 'No' : `Yes (${config.source.managed})`}</dd>
        {client.mode === 'native-oidc' && (
          <>
            <dt>Credential storage</dt>
            <dd>{getSecureStorage().persistent ? 'Device secure storage' : 'Memory only (sign in after restart)'}</dd>
            <dt>Biometric unlock</dt>
            <dd>{config.requireBiometric ? 'Required' : 'Not required'}</dd>
          </>
        )}
        <dt>Version</dt>
        <dd>{__APP_VERSION__}</dd>
      </dl>
    </section>
  );
}

function formatScope(scope: Me['orgScope']): string {
  if (!scope || (Array.isArray(scope) && scope.length === 0)) return '–';
  return Array.isArray(scope) ? scope.join(', ') : scope;
}
