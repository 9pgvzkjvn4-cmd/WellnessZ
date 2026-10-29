/**
 * Test helper (no test-framework imports, so it type-checks with the app): a `BffAuthClient` backed by a fake `fetch`
 * that answers from a route table keyed by `"METHOD /path?query"` (path relative to `/api/v1`).
 * - a value → 200 JSON; `reply(status, body)` → that status (problem+json for ≥ 400); a function → called with the body
 * - `sequence(a, b, …)` → one response per call (the last one repeats)
 * - unmatched routes → 404 problem. Every call is recorded in `calls`.
 */

import { BffAuthClient } from '../auth/bffAuthClient';

export interface Reply {
  readonly __reply: true;
  status: number;
  body?: unknown;
}
export const reply = (status: number, body?: unknown): Reply => ({ __reply: true, status, body });

export type Handler = unknown;
export interface Call {
  method: string;
  path: string;
  body: unknown;
}

const isReply = (v: unknown): v is Reply => !!v && typeof v === 'object' && (v as Reply).__reply === true;

export function mockApi(routes: Record<string, Handler>) {
  const calls: Call[] = [];

  const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    const path = url.replace(/^\/bff\/api/, '');
    let body: unknown = init?.body;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        /* keep raw */
      }
    }
    calls.push({ method, path, body });
    const key = [`${method} ${path}`, `${method} ${path.split('?')[0]}`].find((k) => k in routes);
    if (!key) return new Response(JSON.stringify({ status: 404, title: 'Not Found', detail: `No mock for ${method} ${path}` }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } });
    let handler = routes[key];
    if (typeof handler === 'function') handler = (handler as (b: unknown, c: Call) => unknown)(body, calls[calls.length - 1]!);
    if (isReply(handler)) {
      const problem = handler.status >= 400;
      return new Response(handler.body === undefined ? null : JSON.stringify(handler.body), {
        status: handler.status,
        headers: handler.body === undefined ? {} : { 'Content-Type': problem ? 'application/problem+json' : 'application/json' },
      });
    }
    if (handler === undefined) return new Response(null, { status: 204 });
    return new Response(JSON.stringify(handler), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  const client = new BffAuthClient(fetchImpl as typeof fetch, () => undefined);
  return {
    client,
    calls,
    /** Calls matching method + path prefix. */
    find: (method: string, pathPrefix: string) => calls.filter((c) => c.method === method && c.path.startsWith(pathPrefix)),
  };
}

/** Responses for one route in sequence (e.g. a 409 then a 200). */
export function sequence(...responses: unknown[]): (b: unknown) => unknown {
  let i = 0;
  return () => responses[Math.min(i++, responses.length - 1)];
}

