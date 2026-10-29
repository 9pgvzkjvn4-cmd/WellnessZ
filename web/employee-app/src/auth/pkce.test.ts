import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { base64UrlEncode, codeChallengeS256, createPkcePair, generateCodeVerifier } from './pkce';

describe('PKCE', () => {
  it('matches the RFC 7636 Appendix B test vector', async () => {
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
    expect(await codeChallengeS256(verifier)).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('generates verifiers within RFC length and charset limits', () => {
    for (let i = 0; i < 20; i++) {
      const v = generateCodeVerifier();
      expect(v.length).toBeGreaterThanOrEqual(43);
      expect(v.length).toBeLessThanOrEqual(128);
      expect(v).toMatch(/^[A-Za-z0-9\-._~]+$/);
    }
  });

  it('produces unique verifiers', () => {
    const set = new Set(Array.from({ length: 50 }, () => generateCodeVerifier()));
    expect(set.size).toBe(50);
  });

  it('creates an S256 pair whose challenge equals base64url(sha256(verifier))', async () => {
    const pair = await createPkcePair();
    expect(pair.method).toBe('S256');
    const expected = createHash('sha256').update(pair.verifier).digest('base64url');
    expect(pair.challenge).toBe(expected);
    expect(pair.challenge).not.toContain('=');
  });

  it('base64url-encodes without padding or unsafe characters', () => {
    expect(base64UrlEncode(new Uint8Array([0xfb, 0xff, 0xfe]))).toBe('-__-');
    expect(base64UrlEncode(new Uint8Array([1]))).toBe('AQ');
  });
});
