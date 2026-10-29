// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from './App';
import { meAdjuster, meProvider, referral } from './test/fixtures';
import { mockApi, reply } from './test/mockApi';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

const navLinks = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent);

describe('partner portal shell', () => {
  it('shows the sign-in page with the MFA explanation when signed out', async () => {
    const api = mockApi({ 'GET /bff/session': reply(401) });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { name: 'WellnessZ Partner Portal' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Multi-factor sign-in required' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in with multi-factor authentication' })).toBeTruthy();
    expect(api.calls.some((c) => c.path === '/me')).toBe(false);
  });

  it('a partner provider lands on the referral inbox with the organisation code in the header', async () => {
    window.location.hash = '#/';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, name: 'Pat', roles: ['PARTNER_PROVIDER'] }, 'GET /me': meProvider, 'GET /partner/referrals': [referral()] });
    render(<App client={api.client} />);
    await waitFor(() => expect(window.location.hash).toBe('#/referrals'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Referral inbox' })).toBeTruthy();
    expect(navLinks()).toEqual(['Referrals', 'Telehealth', 'Shared documents']);
    expect(screen.getByRole('banner').textContent).toContain('CITYHOSP');
    expect(screen.getByRole('link', { name: 'Referrals' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByLabelText('Confidentiality').textContent).toContain('CITYHOSP');
  });

  it('a TPA adjuster gets claims and pre-authorisations and cannot open the referral inbox', async () => {
    window.location.hash = '#/referrals';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['TPA_ADJUSTER'] }, 'GET /me': meAdjuster });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { name: 'Page not available' })).toBeTruthy();
    expect(navLinks()).toEqual(['Claims', 'Pre-authorisations', 'Shared documents']);
    expect(screen.getByRole('banner').textContent).toContain('GULFTPA');
    expect(api.calls.some((c) => c.path.startsWith('/partner/referrals'))).toBe(false);
  });

  it('asks for multi-factor sign-in when /me withholds the partner role', async () => {
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['PARTNER_PROVIDER'] }, 'GET /me': { ...meProvider, roles: [] } });
    render(<App client={api.client} />);
    expect(
      await screen.findByText('Your account needs multi-factor sign-in or has no partner access – contact WellnessZ Occupational Health.'),
    ).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
  });

  it('treats a 403 from /me as missing MFA', async () => {
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: [] }, 'GET /me': reply(403, { status: 403 }) });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { name: 'Partner access not available' })).toBeTruthy();
  });

  it('tells other WellnessZ roles that the portal is for partner organisations', async () => {
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['EMPLOYEE'] }, 'GET /me': { roles: ['EMPLOYEE'], orgScope: '/AIRLINE/OPS' } });
    render(<App client={api.client} />);
    expect(await screen.findByText('This portal is for WellnessZ partner organisations.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
  });

  it('returns to sign-in with a notice when the session expires', async () => {
    window.location.hash = '#/documents';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['PARTNER_PROVIDER'] }, 'GET /me': meProvider, 'GET /partner/documents': reply(401) });
    render(<App client={api.client} />);
    expect(await screen.findByText('Your session has ended. Please sign in again.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in with multi-factor authentication' })).toBeTruthy();
    expect(api.calls.some((c) => c.method === 'POST' && c.path === '/logout')).toBe(true);
  });
});
