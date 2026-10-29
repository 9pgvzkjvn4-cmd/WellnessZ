import { PageTitle } from '../components/ui';

const WHO = {
  restrictions: 'managers and HR',
  dutyProposals: 'line managers',
  rtwPlans: 'managers and HR',
  documents: 'managers and HR',
  jobOffers: 'HR business partners',
  ihr: 'HR business partners',
} as const;

/** Shown when an increment-2 route is opened without the required role. */
export function FeatureNotAvailable({ feature }: { feature: keyof typeof WHO }) {
  return (
    <section>
      <PageTitle>Page not available</PageTitle>
      <p>This page is only available to {WHO[feature]}. If you think you need access, contact your IT service desk.</p>
      <a href="#/">Back to team cases</a>
    </section>
  );
}
