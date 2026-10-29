import { PageTitle } from '../components/ui';

export function AccessRestricted({ onSignOut }: { onSignOut: () => void }) {
  return (
    <main id="main" className="centered">
      <div className="signin-card">
        <PageTitle>Access restricted</PageTitle>
        <p>
          The WellnessZ Workspace is available to managers, HR and Occupational Health staff only. Your account does not
          have one of these roles.
        </p>
        <p className="muted small">
          Employees and crew can use the WellnessZ app. If you believe this is wrong, contact your IT service desk.
        </p>
        <button type="button" onClick={onSignOut}>
          Sign out
        </button>
      </div>
    </main>
  );
}
