import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { toDeclarationOutcome } from '../api/portal';
import { portalStrings } from '../i18n/portal';
import { DeclarationOutcomeView } from './FitnessDeclaration';

const response = {
  caseId: 'c9',
  caseRef: 'FLT-2026-000007',
  nextSteps: ['Do not report for duty until assessed', { text: 'The AME on call will phone you' }],
  ameContact: { name: 'Dr. Aero', phone: '+971 4 000 0000', email: 'ame@example.com', hours: 'Available 24/7' },
  acknowledgementDueBy: '2026-10-30T10:00:00Z',
};

describe('crew declaration – what happens next (E05-BL-010)', () => {
  it('shows the server next steps, AME contact and acknowledgement due time', () => {
    const html = renderToStaticMarkup(<DeclarationOutcomeView t={portalStrings('en')} lang="en" outcome={toDeclarationOutcome(response)} />);
    expect(html).toContain('your case reference is FLT-2026-000007');
    expect(html).toContain('<li>Do not report for duty until assessed</li>');
    expect(html).toContain('<li>The AME on call will phone you</li>');
    expect(html).toContain('Crew scheduling only sees “unfit – pending”');
    expect(html).toContain('Your aeromedical contact');
    expect(html).toContain('Dr. Aero');
    expect(html).toContain('href="tel:+97140000000"');
    expect(html).toContain('href="mailto:ame@example.com"');
    expect(html).toContain('Available 24/7');
    expect(html).toMatch(/will acknowledge your declaration by [^<]*2026/);
    expect(html).not.toContain('An aeromedical examiner (AME) will contact you.'); // server steps replace defaults
  });

  it('falls back to the built-in steps when the response has none', () => {
    const html = renderToStaticMarkup(<DeclarationOutcomeView t={portalStrings('en')} lang="en" outcome={toDeclarationOutcome({})} />);
    expect(html).toContain('An aeromedical examiner (AME) will contact you.');
    expect(html).not.toContain('Your aeromedical contact');
    expect(html).not.toContain('acknowledge your declaration by');
  });

  it('renders a plain-text AME contact without interpreting HTML', () => {
    const html = renderToStaticMarkup(
      <DeclarationOutcomeView t={portalStrings('en')} lang="en" outcome={toDeclarationOutcome({ ameContact: '<b>AME desk</b> ext. 4242' })} />,
    );
    expect(html).toContain('&lt;b&gt;AME desk&lt;/b&gt; ext. 4242');
  });

  it('is translated (Arabic, French)', () => {
    const ar = renderToStaticMarkup(<DeclarationOutcomeView t={portalStrings('ar')} lang="ar" outcome={toDeclarationOutcome(response)} />);
    expect(ar).toContain('ما الذي سيحدث بعد ذلك');
    expect(ar).toContain('FLT-2026-000007');
    const fr = renderToStaticMarkup(<DeclarationOutcomeView t={portalStrings('fr')} lang="fr" outcome={toDeclarationOutcome({})} />);
    expect(fr).toContain('Prochaines étapes');
  });
});
