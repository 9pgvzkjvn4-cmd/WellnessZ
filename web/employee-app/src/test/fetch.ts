import { vi } from 'vitest';
import { BffAuthClient } from '../auth/bffAuthClient';

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export const problem = (status: number, title: string) =>
  new Response(JSON.stringify({ title, status }), { status, headers: { 'Content-Type': 'application/problem+json' } });

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

/**
 * A real BffAuthClient over a mocked `fetch`: requests go to `/bff/api/...` with the CSRF header taken from a stubbed
 * `XSRF-TOKEN` cookie, exactly as in the browser.
 */
export function bffWithFetch(handler: Handler) {
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=csrf-123' });
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => handler(url, init));
  const client = new BffAuthClient(fetchMock as unknown as typeof fetch, () => {});
  const calls = () =>
    fetchMock.mock.calls.map(([url, init = {}]) => ({
      url,
      method: init.method ?? 'GET',
      headers: (init.headers ?? {}) as Record<string, string>,
      body: typeof init.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
    }));
  return { client, fetchMock, calls };
}
