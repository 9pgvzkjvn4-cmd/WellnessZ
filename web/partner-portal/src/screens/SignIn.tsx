import { Alert } from '../components/ui';
import { S } from '../strings';

/** Sign-in page: explains that partner access needs multi-factor authentication (enforced by the IdP and the core API). */
export function SignIn({ notice, onSignIn }: { notice?: 'idle' | 'expired'; onSignIn: () => void }) {
  return (
    <main id="main" className="centered">
      <div className="signin-card">
        <div className="brand-mark" aria-hidden="true">
          Z
        </div>
        <h1>{S.signIn.title}</h1>
        <p className="lead">{S.signIn.lead}</p>
        {notice === 'idle' && <Alert kind="info">{S.signIn.idle}</Alert>}
        {notice === 'expired' && <Alert kind="info">{S.signIn.expired}</Alert>}
        <section className="mfa-note" aria-labelledby="mfa-title">
          <h2 id="mfa-title">{S.signIn.mfaTitle}</h2>
          <p className="small">{S.signIn.mfa}</p>
        </section>
        <p className="muted small">{S.signIn.confidential}</p>
        <button type="button" onClick={onSignIn}>
          {S.signIn.button}
        </button>
      </div>
    </main>
  );
}
