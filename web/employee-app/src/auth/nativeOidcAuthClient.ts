import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { Browser } from '@capacitor/browser';
import type { RuntimeConfig } from '../config/runtimeConfig';
import { errorName, log } from '../security/log';
import { encodeBody, normaliseRoles, parseBlob, parseResponse, SessionExpiredError, SIGNED_OUT, type AuthClient, type Session } from './AuthClient';
import { verifyBiometric } from './biometric';
import { createPkcePair, randomUrlSafe } from './pkce';
import { getSecureStorage, type SecureStorage } from './secureStorage';

const REFRESH_TOKEN_KEY = 'wz.refresh_token';
const LOGIN_TIMEOUT_MS = 5 * 60 * 1000;

interface Discovery {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
  revocation_endpoint?: string;
}

interface TokenResponse {
  access_token: string;
  expires_in?: number;
  refresh_token?: string;
  id_token?: string;
  token_type?: string;
}

/** Build the OIDC authorization request URL (exported for tests). */
export function buildAuthorizationUrl(
  authorizationEndpoint: string,
  p: { clientId: string; redirectUri: string; challenge: string; state: string; nonce: string; scope?: string },
): string {
  const url = new URL(authorizationEndpoint);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', p.clientId);
  url.searchParams.set('redirect_uri', p.redirectUri);
  url.searchParams.set('scope', p.scope ?? 'openid profile offline_access');
  url.searchParams.set('code_challenge', p.challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('state', p.state);
  url.searchParams.set('nonce', p.nonce);
  return url.toString();
}

/**
 * Native (Capacitor) auth: OIDC Authorization Code + PKCE (RFC 7636, RFC 8252
 * "OAuth 2.0 for Native Apps") using the system browser (SFSafariViewController /
 * Chrome Custom Tabs via @capacitor/browser) and the custom-scheme redirect
 * `com.wellnessz.app://callback` delivered through @capacitor/app `appUrlOpen`.
 *
 * Token handling:
 *  - access token + id token: memory only (lost on app kill, by design)
 *  - refresh token: SecureStorage (Keychain/Keystore plugin in production)
 *  - never localStorage / Preferences / cookies
 */
export class NativeOidcAuthClient implements AuthClient {
  readonly mode = 'native-oidc' as const;
  readonly #cfg: RuntimeConfig;
  readonly #storage: SecureStorage;
  #discovery?: Discovery;
  #accessToken?: string;
  #accessTokenExpiresAt = 0;
  #idToken?: string;
  #refreshing?: Promise<boolean>;

  constructor(cfg: RuntimeConfig, storage: SecureStorage = getSecureStorage()) {
    this.#cfg = cfg;
    this.#storage = storage;
  }

  async #discover(): Promise<Discovery> {
    if (this.#discovery) return this.#discovery;
    const res = await fetch(`${this.#cfg.oidcIssuer}/.well-known/openid-configuration`, { cache: 'no-store' });
    if (!res.ok) throw new Error('OIDC discovery failed');
    this.#discovery = (await res.json()) as Discovery;
    return this.#discovery;
  }

  async init(): Promise<Session> {
    const rt = await this.#storage.get(REFRESH_TOKEN_KEY);
    if (!rt) return SIGNED_OUT;
    if (this.#cfg.requireBiometric && !(await verifyBiometric())) {
      log.warn('auth.biometric-unavailable-or-failed');
      return SIGNED_OUT;
    }
    if (!(await this.#refresh())) return SIGNED_OUT;
    return this.#loadSession();
  }

  async #loadSession(): Promise<Session> {
    const me = await this.api<{ subject?: string; displayName?: string; username?: string; roles?: unknown }>('/me');
    return { authenticated: true, name: me.displayName ?? me.username ?? me.subject, roles: normaliseRoles(me.roles) };
  }

  async login(): Promise<Session> {
    const d = await this.#discover();
    const pkce = await createPkcePair();
    const state = randomUrlSafe(32);
    const nonce = randomUrlSafe(32);
    const authUrl = buildAuthorizationUrl(d.authorization_endpoint, {
      clientId: this.#cfg.oidcClientId,
      redirectUri: this.#cfg.redirectUri,
      challenge: pkce.challenge,
      state,
      nonce,
    });

    const callbackUrl = await new Promise<string>((resolve, reject) => {
      const handles: PluginListenerHandle[] = [];
      let settled = false;
      const finish = (fn: () => void) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        handles.forEach((h) => void h.remove());
        fn();
      };
      const timer = setTimeout(() => finish(() => reject(new Error('Login timed out'))), LOGIN_TIMEOUT_MS);
      Promise.all([
        App.addListener('appUrlOpen', ({ url }) => {
          if (url.startsWith(this.#cfg.redirectUri)) finish(() => resolve(url));
        }),
        // User closed the in-app browser without completing sign-in.
        Browser.addListener('browserFinished', () => finish(() => reject(new Error('Login cancelled')))),
      ])
        .then((hs) => {
          handles.push(...hs);
          if (settled) {
            hs.forEach((h) => void h.remove());
            return;
          }
          return Browser.open({ url: authUrl, presentationStyle: 'popover' });
        })
        .catch((e: unknown) => finish(() => reject(e instanceof Error ? e : new Error('Login failed'))));
    });
    await Browser.close().catch(() => undefined); // iOS closes automatically; Android may not

    const params = new URL(callbackUrl).searchParams;
    if (params.get('state') !== state) throw new Error('OIDC state mismatch');
    if (params.get('error')) throw new Error('Login was cancelled or failed');
    const code = params.get('code');
    if (!code) throw new Error('No authorization code');

    await this.#tokenRequest({
      grant_type: 'authorization_code',
      code,
      redirect_uri: this.#cfg.redirectUri,
      client_id: this.#cfg.oidcClientId,
      code_verifier: pkce.verifier,
    });
    // Nonce check on the ID token (signature is validated by the API for access tokens;
    // the ID token is only used as logout hint, so claims are checked defensively).
    if (this.#idToken && decodeJwtPayload(this.#idToken)?.nonce !== nonce) {
      await this.#clear();
      throw new Error('OIDC nonce mismatch');
    }
    return this.#loadSession();
  }

  async #tokenRequest(form: Record<string, string>): Promise<void> {
    const d = await this.#discover();
    const res = await fetch(d.token_endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: new URLSearchParams(form).toString(),
      cache: 'no-store',
    });
    if (!res.ok) throw new SessionExpiredError();
    const t = (await res.json()) as TokenResponse;
    this.#accessToken = t.access_token;
    this.#accessTokenExpiresAt = Date.now() + (t.expires_in ?? 300) * 1000;
    if (t.id_token) this.#idToken = t.id_token;
    if (t.refresh_token) await this.#storage.set(REFRESH_TOKEN_KEY, t.refresh_token);
  }

  /** Single-flight refresh. Returns false when the refresh token is missing/rejected. */
  #refresh(): Promise<boolean> {
    this.#refreshing ??= (async () => {
      try {
        const rt = await this.#storage.get(REFRESH_TOKEN_KEY);
        if (!rt) return false;
        await this.#tokenRequest({ grant_type: 'refresh_token', refresh_token: rt, client_id: this.#cfg.oidcClientId });
        return true;
      } catch (e) {
        log.warn('auth.refresh-failed', { errorName: errorName(e) });
        await this.#clear();
        return false;
      } finally {
        this.#refreshing = undefined;
      }
    })();
    return this.#refreshing;
  }

  async #clear(): Promise<void> {
    this.#accessToken = undefined;
    this.#idToken = undefined;
    this.#accessTokenExpiresAt = 0;
    await this.#storage.remove(REFRESH_TOKEN_KEY);
  }

  async logout(): Promise<void> {
    const rt = await this.#storage.get(REFRESH_TOKEN_KEY);
    const d = this.#discovery;
    await this.#clear();
    // Best-effort token revocation (RFC 7009); no browser round-trip so idle logout is silent.
    if (rt && d?.revocation_endpoint) {
      await fetch(d.revocation_endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ token: rt, token_type_hint: 'refresh_token', client_id: this.#cfg.oidcClientId }).toString(),
      }).catch(() => undefined);
    }
  }

  async #send(path: string, init: { method?: string; body?: unknown; accept?: string } = {}): Promise<Response> {
    const send = async () => {
      if (!this.#accessToken || Date.now() > this.#accessTokenExpiresAt - 30_000) {
        if (!(await this.#refresh())) throw new SessionExpiredError();
      }
      const headers: Record<string, string> = {
        Accept: init.accept ?? 'application/json',
        Authorization: `Bearer ${this.#accessToken}`,
      };
      const encoded = encodeBody(init.body);
      if (encoded.contentType) headers['Content-Type'] = encoded.contentType;
      return fetch(`${this.#cfg.apiBaseUrl}${path}`, {
        method: init.method ?? 'GET',
        headers,
        cache: 'no-store',
        credentials: 'omit',
        body: encoded.body,
      });
    };
    let res = await send();
    if (res.status === 401) {
      this.#accessToken = undefined; // force one refresh + retry
      res = await send();
    }
    return res;
  }

  async api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
    return parseResponse<T>(await this.#send(path, init));
  }

  /** Bearer-authenticated GET of binary content (the WebView cannot open an authenticated URL directly). */
  async download(path: string): Promise<Blob> {
    return parseBlob(await this.#send(path, { accept: 'application/pdf, image/*, */*' }));
  }

  directUrl(): undefined {
    return undefined;
  }
}

function decodeJwtPayload(jwt: string): Record<string, unknown> | undefined {
  try {
    const part = jwt.split('.')[1];
    if (!part) return undefined;
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const json = new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)));
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return undefined;
  }
}
