import { PageTitle } from '../components/ui';

const WHO = {
  restrictions: 'managers and HR',
  dutyProposals: 'line managers',
  rtwPlans: 'managers and HR',
  documents: 'managers and HR',
  jobOffers: 'HR business partners',
  ihr: 'HR business partners',
  absence: 'managers and HR',
  adviceRequests: 'managers and HR',
  compliance: 'managers and HR',
  preEmployment: 'HR business partners',
  claims: 'HR business partners',
  testing: 'line managers, safety managers, HR and crew scheduling',
  psychosocial: 'line managers',
  campaigns: 'line managers',
  ergonomics: 'line managers',
} as const;

/** Shown when an increment-2 / increment-3 / increment-4 route is opened without the required role. */
export function FeatureNotAvailable({ feature }: { feature: keyof typeof WHO }) {
  return (
    <section>
      <PageTitle>Page not available</PageTitle>
      <p>This page is only available to {WHO[feature]}. If you think you need access, contact your IT service desk.</p>
      <a href="#/">Back to team cases</a>
    </section>
  );
}
