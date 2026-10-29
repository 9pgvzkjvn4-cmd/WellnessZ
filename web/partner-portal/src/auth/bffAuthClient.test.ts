import { describe, expect, it, vi } from 'vitest';
import { normaliseRoles } from './AuthClient';
import { BffAuthClient, readXsrfToken } from './bffAuthClient';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('BffAuthClient', () => {
  it('maps /bff/session and normalises roles', async () => {
    const fetchMock = vi.fn(async () => json({ authenticated: true, name: 'Emp One', roles: ['ROLE_EMPLOYEE', 'employee'] }));
    const s = await new BffAuthClient(fetchMock as unknown as typeof fetch, () => {}).init();
    expect(fetchMock).toHaveBeenCalledWith('/bff/session', expect.anything());
    expect(s).toEqual({ authenticated: true, name: 'Emp One', roles: ['EMPLOYEE'] });
  });

  it('treats 401 as signed out', async () => {
    const s = await new BffAuthClient((async () => new Response(null, { status: 401 })) as typeof fetch, () => {}).init();
    expect(s.authenticated).toBe(false);
  });

  it('prefixes API calls with /bff/api', async () => {
    const fetchMock = vi.fn(async () => json([]));
    await new BffAuthClient(fetchMock as unknown as typeof fetch, () => {}).api('/cases');
    expect(fetchMock).toHaveBeenCalledWith('/bff/api/cases', expect.objectContaining({ method: 'GET' }));
  });

  it('redirects to the BFF login endpoint', async () => {
    const nav = vi.fn();
    await new BffAuthClient(vi.fn() as unknown as typeof fetch, nav).login();
    expect(nav).toHaveBeenCalledWith('/oauth2/authorization/wellnessz');
  });
});

describe('readXsrfToken', () => {
  it('reads the XSRF-TOKEN cookie', () => {
    expect(readXsrfToken('a=1; XSRF-TOKEN=abc%3D%3D; b=2')).toBe('abc==');
    expect(readXsrfToken('a=1')).toBeUndefined();
  });
});

describe('normaliseRoles', () => {
  it('ignores non-strings and dedupes', () => {
    expect(normaliseRoles(['ROLE_hr', 'HR', 5, null, ' manager '])).toEqual(['HR', 'MANAGER']);
    expect(normaliseRoles(undefined)).toEqual([]);
  });
});
