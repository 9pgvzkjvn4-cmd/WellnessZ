import { describe, expect, it } from 'vitest';
import { buildAuthorizationUrl } from './nativeOidcAuthClient';

describe('buildAuthorizationUrl', () => {
  it('builds a PKCE S256 authorization code request', () => {
    const url = new URL(
      buildAuthorizationUrl('http://localhost:8180/realms/wellnessz/protocol/openid-connect/auth', {
        clientId: 'wellnessz-mobile',
        redirectUri: 'com.wellnessz.app://callback',
        challenge: 'abc',
        state: 'st',
        nonce: 'no',
      }),
    );
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('client_id')).toBe('wellnessz-mobile');
    expect(url.searchParams.get('redirect_uri')).toBe('com.wellnessz.app://callback');
    expect(url.searchParams.get('code_challenge')).toBe('abc');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('state')).toBe('st');
    expect(url.searchParams.get('nonce')).toBe('no');
    expect(url.searchParams.get('scope')).toContain('openid');
  });
});
