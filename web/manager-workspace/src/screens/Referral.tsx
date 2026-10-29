import { PageTitle } from '../components/ui';

/** Shown when a referral route is opened without the required role (MANAGER for /refer, HR for /refer/hr). */
export function ReferralNotAvailable({ kind }: { kind: 'manager' | 'hr' }) {
  return (
    <section>
      <PageTitle>Referral not available</PageTitle>
      <p>
        {kind === 'hr'
          ? 'HR referrals can only be made by HR business partners.'
          : 'Management referrals can only be made by line managers.'}{' '}
        If you need to refer someone, contact Occupational Health directly.
      </p>
      <a href="#/">Back to team cases</a>
    </section>
  );
}
