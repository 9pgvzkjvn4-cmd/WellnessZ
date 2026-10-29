import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ApiError, parseResponse, SessionExpiredError, toProblem } from '../auth/AuthClient';
import { describeError, errorInfo } from './format';
import { ErrorAlert } from './ui';

const problem = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json', ...headers } });

describe('problem-detail error handling', () => {
  it('parses RFC 9457 bodies into ApiError with detail, code and correlationId', async () => {
    const res = problem(422, { type: 'about:blank', title: 'Unprocessable', status: 422, detail: 'Recordability must be classified before leaving INTAKE', code: 'recordability_required', correlationId: 'c-123', extra: 'dropped' });
    const err = await parseResponse(res).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).problem).toEqual({ type: 'about:blank', title: 'Unprocessable', status: 422, detail: 'Recordability must be classified before leaving INTAKE', code: 'recordability_required', correlationId: 'c-123' });
  });

  it('falls back to the X-Correlation-Id header', async () => {
    const err = (await parseResponse(new Response('oops', { status: 500, headers: { 'X-Correlation-Id': 'h-9' } })).catch((e: unknown) => e)) as ApiError;
    expect(err.problem?.correlationId).toBe('h-9');
    expect(toProblem({ detail: 'x' }, 'h-1').correlationId).toBe('h-1');
  });

  it('401 → SessionExpiredError', async () => {
    await expect(parseResponse(new Response(null, { status: 401 }))).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('shows detail + reference', () => {
    const e = new ApiError(409, { detail: 'A fitness status is required before closing', correlationId: 'abc', code: 'fitness_required' });
    expect(errorInfo(e)).toEqual({ message: 'A fitness status is required before closing', correlationId: 'abc', code: 'fitness_required', status: 409 });
    expect(describeError(e)).toBe('A fitness status is required before closing (reference abc)');
    const html = renderToStaticMarkup(<ErrorAlert error={e} />);
    expect(html).toContain('role="alert"');
    expect(html).toContain('A fitness status is required before closing');
    expect(html).toContain('Reference: <span class="mono">abc</span>');
  });

  it('uses safe fallbacks without a body', () => {
    expect(errorInfo(new ApiError(403)).message).toBe('You do not have permission to do this.');
    expect(errorInfo(new ApiError(503)).message).toMatch(/temporarily unavailable/);
    expect(errorInfo(new TypeError('Failed to fetch')).message).toMatch(/check your connection/);
  });
});
