import { PageTitle } from '../components/ui';
import { S } from '../strings';

/**
 * Shown when `/me` carries no partner role: `mfa` – partner account without MFA proof (or no partner access at all);
 * `other` – an account of another WellnessZ role.
 */
export function AccessDenied({ kind, onSignOut }: { kind: 'mfa' | 'other'; onSignOut: () => void }) {
  return (
    <main id="main" className="centered">
      <div className="signin-card">
        <PageTitle>{kind === 'mfa' ? S.access.mfaTitle : S.access.otherTitle}</PageTitle>
        <p>{kind === 'mfa' ? S.access.mfa : S.access.other}</p>
        <p className="muted small">{kind === 'mfa' ? S.access.mfaHint : S.access.otherHint}</p>
        <button type="button" onClick={onSignOut}>
          {S.app.signOut}
        </button>
      </div>
    </main>
  );
}
