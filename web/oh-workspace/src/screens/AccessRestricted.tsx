import { PageTitle } from '../components/ui';

export function AccessRestricted({ onSignOut }: { onSignOut: () => void }) {
  return (
    <main id="main" className="centered">
      <div className="signin-card">
        <PageTitle>Access restricted</PageTitle>
        <p>
          The WellnessZ OH Workspace is available to Occupational Health staff, clinic reception and first aiders only.
          Your account does not have one of these roles.
        </p>
        <p className="muted small">
          Managers and HR use the WellnessZ Workspace; employees and crew use the WellnessZ app. If you believe this is
          wrong, contact your IT service desk.
        </p>
        <button type="button" onClick={onSignOut}>
          Sign out
        </button>
      </div>
    </main>
  );
}
