/**
 * PKCE (RFC 7636) helpers implemented with WebCrypto – no dependencies.
 */

/** Unpadded base64url encoding (RFC 4648 §5). */
export function base64UrlEncode(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Cryptographically random URL-safe string from `byteLength` random bytes. */
export function randomUrlSafe(byteLength = 32, cryptoImpl: Crypto = globalThis.crypto): string {
  const bytes = new Uint8Array(byteLength);
  cryptoImpl.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

/**
 * Code verifier: 43–128 chars from the unreserved set. 32 random bytes → 43 chars,
 * 64 bytes → 86 chars. We use 64 bytes.
 */
export function generateCodeVerifier(cryptoImpl: Crypto = globalThis.crypto): string {
  return randomUrlSafe(64, cryptoImpl);
}

/** S256 code challenge = BASE64URL(SHA-256(ASCII(verifier))). */
export async function codeChallengeS256(verifier: string, cryptoImpl: Crypto = globalThis.crypto): Promise<string> {
  const digest = await cryptoImpl.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64UrlEncode(new Uint8Array(digest));
}

export interface PkcePair {
  verifier: string;
  challenge: string;
  method: 'S256';
}

export async function createPkcePair(cryptoImpl: Crypto = globalThis.crypto): Promise<PkcePair> {
  const verifier = generateCodeVerifier(cryptoImpl);
  return { verifier, challenge: await codeChallengeS256(verifier, cryptoImpl), method: 'S256' };
}
