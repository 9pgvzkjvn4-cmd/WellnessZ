import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { normaliseDisclosures } from '../api/portal';
import type { Consent } from '../api/types';
import { portalStrings } from '../i18n/portal';
import { SESSION, stubClient } from '../test/client';
import { CONSENTS, DISCLOSURES } from '../test/portalFixtures';
import { findAll, findOne, textOf } from '../test/tree';
import { ConsentWithdrawal, DisclosureLog, Privacy, type ConsentWithdrawalProps } from './Privacy';

const t = portalStrings('en');
const rows = normaliseDisclosures(DISCLOSURES);

function cprops(over: Partial<ConsentWithdrawalProps> = {}): ConsentWithdrawalProps {
  return { t, lang: 'en', consents: CONSENTS as Consent[], busy: false, onWithdraw: vi.fn(), onConfirm: vi.fn(), onCancel: vi.fn(), ...over };
}

describe('Privacy – who received my information', () => {
  it('lists date, recipient, document, purpose and access status', () => {
    const html = renderToStaticMarkup(<DisclosureLog t={t} lang="en" rows={rows} />);
    expect(html).toContain('PhysioPlus Clinic');
    expect(html).toContain('Partner provider');
    expect(html).toContain('Mona Manager');
    expect(html).toContain('Referral letter');
    expect(html).toContain('Share treatment information with a partner provider');
    expect(html).toContain('Fitness for work status');
    expect(html).toContain('Physiotherapy follow-up'); // free-text purpose shown as sent
    expect(html).toContain('Access active');
    expect(html).toContain('badge-revoked');
    expect(html).toContain('Access revoked');
    expect(html).toMatch(/Date<\/dt><dd>[^<]*2026/);
  });

  it('empty log', () => {
    expect(renderToStaticMarkup(<DisclosureLog t={t} lang="en" rows={[]} />)).toContain('has been shared outside Occupational Health');
  });

  it('lists only active consents and withdraws in two steps with a clear explanation', () => {
    const p = cprops();
    const tree = ConsentWithdrawal(p);
    const btn = findOne(tree, (e) => e.type === 'button');
    expect(textOf(btn.props.children)).toBe('Withdraw consent');
    btn.props.onClick();
    expect(p.onWithdraw).toHaveBeenCalledWith(expect.objectContaining({ id: 'k1' }));
    expect(renderToStaticMarkup(<ConsentWithdrawal {...p} />)).not.toContain('Employee Assistance'); // withdrawn one hidden

    const confirming = ConsentWithdrawal(cprops({ ...p, pendingId: 'k1' }));
    const html = renderToStaticMarkup(<ConsentWithdrawal {...p} pendingId="k1" />);
    expect(html).toContain('role="alertdialog"');
    expect(html).toContain('Partner providers lose access immediately');
    expect(html).toContain('recorded in your disclosure log');
    findOne(confirming, (e) => e.type === 'button' && textOf(e.props.children) === 'Yes, withdraw').props.onClick();
    expect(p.onConfirm).toHaveBeenCalled();
    findOne(confirming, (e) => e.type === 'button' && textOf(e.props.children) === 'Cancel').props.onClick();
    expect(p.onCancel).toHaveBeenCalled();
  });

  it('disables actions while the withdrawal is sent and shows the empty state afterwards', () => {
    const busy = ConsentWithdrawal(cprops({ pendingId: 'k1', busy: true }));
    expect(findAll(busy, (e) => e.type === 'button').every((b) => b.props.disabled === true)).toBe(true);
    const none = CONSENTS.map((c) => ({ ...c, status: 'WITHDRAWN' })) as Consent[];
    expect(renderToStaticMarkup(<ConsentWithdrawal {...cprops({ consents: none })} />)).toContain('You have no active consents.');
  });

  it('renders Arabic right-to-left and French', () => {
    const ar = renderToStaticMarkup(<Privacy client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="ar" />);
    expect(ar).toMatch(/^<section class="portal" dir="rtl" lang="ar"><h1[^>]*>من تلقّى معلوماتي<\/h1>/);
    expect(ar).toContain('سجل الإفصاح');
    const arWithdraw = renderToStaticMarkup(<ConsentWithdrawal {...cprops({ t: portalStrings('ar'), lang: 'ar', pendingId: 'k1' })} />);
    expect(arWithdraw).toContain('يفقد مقدمو الخدمات الشركاء فورًا');
    expect(renderToStaticMarkup(<DisclosureLog t={portalStrings('fr')} lang="fr" rows={rows} />)).toContain('Accès révoqué');
  });
});
