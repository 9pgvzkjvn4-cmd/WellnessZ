import { describe, expect, it } from 'vitest';
import { canUse, currentArea, homeRoute, portalNav, resolveAccess, resolveRoute } from './roles';

const paths = (roles: string[]) => portalNav(roles).map((n) => n.path);

describe('role-based navigation', () => {
  it('gives partner providers referrals, EAP referrals, telehealth and documents', () => {
    expect(paths(['PARTNER_PROVIDER'])).toEqual(['/referrals', '/eap-referrals', '/telehealth', '/documents']);
    expect(homeRoute(['ROLE_partner_provider'])).toBe('/referrals');
  });

  it('gives TPA adjusters claims, pre-authorisations and documents', () => {
    expect(paths(['TPA_ADJUSTER'])).toEqual(['/claims', '/preauthorisations', '/documents']);
    expect(homeRoute(['TPA_ADJUSTER'])).toBe('/claims');
  });

  it('gives an account with both roles the union, documents once', () => {
    expect(paths(['TPA_ADJUSTER', 'PARTNER_PROVIDER'])).toEqual(['/referrals', '/eap-referrals', '/telehealth', '/claims', '/preauthorisations', '/documents']);
  });

  it.each([[[]], [['EMPLOYEE']], [['OH_CLINICIAN']], [['CLAIMS_COORDINATOR']], [['PARTNER']]])('gives %j nothing', (roles) => {
    expect(paths(roles)).toEqual([]);
  });

  it('blocks direct navigation to the other role’s areas', () => {
    expect(canUse('claims', ['PARTNER_PROVIDER'])).toBe(false);
    expect(canUse('referrals', ['TPA_ADJUSTER'])).toBe(false);
    expect(resolveRoute('/claims', ['PARTNER_PROVIDER'])).toEqual({ kind: 'area', area: 'claims', allowed: false });
    expect(resolveRoute('/referrals/r%2F1', ['PARTNER_PROVIDER'])).toEqual({ kind: 'referral', id: 'r/1', allowed: true });
    expect(resolveRoute('/referrals/r1', ['TPA_ADJUSTER'])).toEqual({ kind: 'referral', id: 'r1', allowed: false });
    expect(resolveRoute('/claims/c1', ['TPA_ADJUSTER'])).toEqual({ kind: 'claim', id: 'c1', allowed: true });
    expect(resolveRoute('/', ['TPA_ADJUSTER'])).toEqual({ kind: 'home' });
    expect(resolveRoute('/referrals/%E0%A4%A', ['PARTNER_PROVIDER'])).toEqual({ kind: 'notFound' });
    expect(resolveRoute('/claims/c1/costs', ['TPA_ADJUSTER'])).toEqual({ kind: 'notFound' });
  });

  it('highlights the list entry on detail pages', () => {
    expect(currentArea('/referrals/r1')).toBe('referrals');
    expect(currentArea('/preauthorisations')).toBe('preauthorisations');
    expect(currentArea('/nope')).toBeUndefined();
  });
});

describe('access decision (MFA)', () => {
  it('admits a partner role reported by /me', () => {
    expect(resolveAccess(['PARTNER_PROVIDER'], ['PARTNER_PROVIDER'])).toBe('partner');
    expect(resolveAccess([], ['TPA_ADJUSTER'])).toBe('partner');
  });

  it('asks for MFA when the session has a partner role but /me does not (no MFA proof)', () => {
    expect(resolveAccess(['TPA_ADJUSTER'], [])).toBe('mfa');
    expect(resolveAccess(['PARTNER_PROVIDER'], ['EMPLOYEE'])).toBe('mfa');
  });

  it('asks for MFA / partner access when no role is known at all', () => {
    expect(resolveAccess([], [])).toBe('mfa');
    expect(resolveAccess(undefined, undefined)).toBe('mfa');
  });

  it('tells other WellnessZ roles that the portal is for partners', () => {
    expect(resolveAccess(['EMPLOYEE'], ['EMPLOYEE'])).toBe('other');
    expect(resolveAccess(['OH_CLINICIAN'], ['OH_CLINICIAN', 'CLAIMS_COORDINATOR'])).toBe('other');
  });
});

describe('EAP referrals (increment 5)', () => {
  it('is a partner-provider area; adjusters are blocked', () => {
    expect(canUse('eapReferrals', ['PARTNER_PROVIDER'])).toBe(true);
    expect(canUse('eapReferrals', ['TPA_ADJUSTER'])).toBe(false);
    expect(resolveRoute('/eap-referrals', ['PARTNER_PROVIDER'])).toEqual({ kind: 'area', area: 'eapReferrals', allowed: true });
    expect(resolveRoute('/eap-referrals', ['TPA_ADJUSTER'])).toEqual({ kind: 'area', area: 'eapReferrals', allowed: false });
    expect(resolveRoute('/eap-referrals/e1', ['PARTNER_PROVIDER'])).toEqual({ kind: 'notFound' });
    expect(currentArea('/eap-referrals')).toBe('eapReferrals');
    expect(homeRoute(['PARTNER_PROVIDER'])).toBe('/referrals');
  });
});
