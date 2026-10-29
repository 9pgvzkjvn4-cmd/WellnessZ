import { describe, expect, it, vi } from 'vitest';
import { normaliseRoles } from './AuthClient';
import { BffAuthClient, readXsrfToken } from './bffAuthClient';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('BffAuthClient', () => {
  it('maps /bff/session and normalises roles', async () => {
    const fetchMock = vi.fn(async () => json({ authenticated: true, name: 'Emp One', roles: ['ROLE_OH_CLINICIAN', 'oh_clinician'] }));
    const s = await new BffAuthClient(fetchMock as unknown as typeof fetch, () => {}).init();
    expect(fetchMock).toHaveBeenCalledWith('/bff/session', expect.anything());
    expect(s).toEqual({ authenticated: true, name: 'Emp One', roles: ['OH_CLINICIAN'] });
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

  it('sends X-XSRF-TOKEN on state-changing calls only', async () => {
    const g = globalThis as Record<string, unknown>;
    g.document = { cookie: 'XSRF-TOKEN=tok%3D' };
    try {
      const fetchMock = vi.fn(async () => json({ caseId: 'c' }));
      const c = new BffAuthClient(fetchMock as unknown as typeof fetch, () => {});
      await c.api('/cases/c/assign', { method: 'POST', body: { reason: 'x' } });
      await c.api('/queues/counts');
      const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
      expect(calls[0]?.[0]).toBe('/bff/api/cases/c/assign');
      expect(calls[0]?.[1].headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok=', 'Content-Type': 'application/json' });
      expect(calls[0]?.[1].cache).toBe('no-store');
      expect(calls[1]?.[1].headers).not.toHaveProperty('X-XSRF-TOKEN');
    } finally {
      delete g.document;
    }
  });

  it('exposes username when the BFF provides one', async () => {
    const s = await new BffAuthClient((async () => json({ authenticated: true, name: 'Nina', preferredUsername: 'nurse1', roles: ['OH_CLINICIAN'] })) as typeof fetch, () => {}).init();
    expect(s).toEqual({ authenticated: true, name: 'Nina', username: 'nurse1', roles: ['OH_CLINICIAN'] });
  });

  it('redirects to the BFF login endpoint', async () => {
    const nav = vi.fn();
    await new BffAuthClient(vi.fn() as unknown as typeof fetch, nav).login();
    expect(nav).toHaveBeenCalledWith('/oauth2/authorization/wellnessz');
  });
});

describe('increment 2 client additions', () => {
  it('sends FormData as multipart (no JSON content type, body untouched) with the CSRF header', async () => {
    const g = globalThis as Record<string, unknown>;
    g.document = { cookie: 'XSRF-TOKEN=t' };
    try {
      const fetchMock = vi.fn(async () => json({ documentId: 'd1' }));
      const fd = new FormData();
      fd.append('consent', 'true');
      await new BffAuthClient(fetchMock as unknown as typeof fetch, () => {}).api('/encounters/e1/photos', { method: 'POST', body: fd });
      const init = (fetchMock.mock.calls as unknown as [string, RequestInit][])[0]?.[1];
      expect(init?.body).toBe(fd);
      expect(init?.headers).not.toHaveProperty('Content-Type');
      expect(init?.headers).toMatchObject({ 'X-XSRF-TOKEN': 't' });
    } finally {
      delete g.document;
    }
  });

  it('keeps cds_alert alerts (known string fields only) on the ApiError problem', async () => {
    const body = { status: 409, code: 'cds_alert', alerts: [{ code: 'ALLERGY_CLASS', severity: 'CRITICAL', message: 'm', secret: 'x' }, { nope: 1 }] };
    const c = new BffAuthClient((async () => json(body, 409)) as typeof fetch, () => {});
    const err = await c.api('/employees/E1/medications', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect((err as { problem?: unknown }).problem).toMatchObject({ code: 'cds_alert', alerts: [{ code: 'ALLERGY_CLASS', severity: 'CRITICAL', message: 'm', reference: undefined }] });
    expect(JSON.stringify((err as { problem?: unknown }).problem)).not.toContain('secret');
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
