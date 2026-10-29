import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

type Predicate = (url: string, method: string, origin: string) => boolean;

/** Evaluate public/sw.js in a stub worker scope and extract its cache predicate. */
function loadPredicate(): Predicate {
  const src = readFileSync(fileURLToPath(new URL('../../public/sw.js', import.meta.url)), 'utf8');
  const self: Record<string, unknown> = { addEventListener: () => {} };
  new Function('self', src)(self);
  return self.isCacheableRequest as Predicate;
}

describe('service worker cache policy (no PHI caching)', () => {
  const isCacheable = loadPredicate();
  const o = 'https://app.example';

  it.each([
    '/bff/api/cases',
    '/bff/session',
    '/api/v1/me',
    '/oauth2/authorization/wellnessz',
    '/login/oauth2/code/wellnessz',
    '/logout',
    '/config.json',
    '/assets/app.js?x=1',
  ])('never caches %s', (path) => {
    expect(isCacheable(o + path, 'GET', o)).toBe(false);
  });

  it('never caches non-GET or cross-origin requests', () => {
    expect(isCacheable(o + '/assets/app.js', 'POST', o)).toBe(false);
    expect(isCacheable('https://idp.example/assets/x.js', 'GET', o)).toBe(false);
  });

  it('caches the app shell and hashed assets', () => {
    expect(isCacheable(o + '/', 'GET', o)).toBe(true);
    expect(isCacheable(o + '/assets/index-abc123.js', 'GET', o)).toBe(true);
    expect(isCacheable(o + '/manifest.webmanifest', 'GET', o)).toBe(true);
  });
});
