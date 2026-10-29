import { describe, expect, it, vi } from 'vitest';
import { ApiError, normaliseRoles, SessionExpiredError } from './AuthClient';
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

describe('problem details', () => {
  it('keeps code and field from an RFC 9457 body', async () => {
    const res = new Response(JSON.stringify({ title: 'Unprocessable', status: 422, code: 'clinical_content_not_allowed', field: 'description', detail: 'x' }), {
      status: 422,
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const err = await new BffAuthClient((async () => res) as unknown as typeof fetch, () => {}).api('/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).problem).toMatchObject({ code: 'clinical_content_not_allowed', field: 'description', title: 'Unprocessable' });
  });
});

describe('binary download', () => {
  it('maps 401 to SessionExpiredError and other errors to ApiError', async () => {
    const c401 = new BffAuthClient((async () => new Response(null, { status: 401 })) as unknown as typeof fetch, () => {});
    await expect(c401.download('/documents/d1/content')).rejects.toBeInstanceOf(SessionExpiredError);
    const c404 = new BffAuthClient((async () => new Response(null, { status: 404 })) as unknown as typeof fetch, () => {});
    await expect(c404.download('/documents/d1/content')).rejects.toMatchObject({ status: 404 });
  });
});
