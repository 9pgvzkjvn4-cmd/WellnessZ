import { encodeBody, normaliseRoles, parseBlob, parseResponse, SIGNED_OUT, type AuthClient, type Session } from './AuthClient';

export const BFF_LOGIN_PATH = '/oauth2/authorization/wellnessz';
export const BFF_SESSION_PATH = '/bff/session';
export const BFF_API_PREFIX = '/bff/api';
export const BFF_LOGOUT_PATH = '/logout';

/** Read the Spring Security CSRF cookie (XSRF-TOKEN, not HttpOnly by design). */
export function readXsrfToken(cookie: string = typeof document !== 'undefined' ? document.cookie : ''): string | undefined {
  for (const part of cookie.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === 'XSRF-TOKEN') return decodeURIComponent(v.join('='));
  }
  return undefined;
}

/**
 * Browser / PWA auth: the BFF (apps/wz-bff, port 8090) holds tokens server-side
 * and issues an HttpOnly, Secure, SameSite=Strict `WZSESSION` cookie.
 */
export class BffAuthClient implements AuthClient {
  readonly mode = 'bff' as const;
  readonly #fetch: typeof fetch;
  readonly #navigate: (url: string) => void;

  constructor(fetchImpl: typeof fetch = (...a) => fetch(...a), navigate: (url: string) => void = (u) => window.location.assign(u)) {
    this.#fetch = fetchImpl;
    this.#navigate = navigate;
  }

  async init(): Promise<Session> {
    const res = await this.#fetch(BFF_SESSION_PATH, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (res.status === 401 || !res.ok) return SIGNED_OUT;
    const body = (await res.json()) as { authenticated?: boolean; name?: string; roles?: unknown };
    if (!body.authenticated) return SIGNED_OUT;
    return { authenticated: true, name: body.name, roles: normaliseRoles(body.roles) };
  }

  async login(): Promise<void> {
    this.#navigate(BFF_LOGIN_PATH);
  }

  async logout(): Promise<void> {
    const headers: Record<string, string> = {};
    const xsrf = readXsrfToken();
    if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;
    let target = '/';
    try {
      const res = await this.#fetch(BFF_LOGOUT_PATH, {
        method: 'POST',
        credentials: 'same-origin',
        headers,
        redirect: 'manual',
      });
      // A BFF may answer 2xx + Location (RP-initiated logout URL at the IdP) for SPAs.
      const loc = res.headers.get('Location');
      if (res.ok && loc) target = loc;
    } catch {
      /* session cookie will expire server-side; still leave the app */
    }
    this.#navigate(target);
  }

  async api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
    const method = (init.method ?? 'GET').toUpperCase();
    const headers: Record<string, string> = { Accept: 'application/json' };
    const encoded = encodeBody(init.body);
    if (encoded.contentType) headers['Content-Type'] = encoded.contentType;
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const xsrf = readXsrfToken();
      if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;
    }
    const res = await this.#fetch(`${BFF_API_PREFIX}${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      body: encoded.body,
    });
    return parseResponse<T>(res);
  }

  async download(path: string): Promise<Blob> {
    const res = await this.#fetch(`${BFF_API_PREFIX}${path}`, {
      method: 'GET',
      headers: { Accept: 'application/pdf, image/*, */*' },
      credentials: 'same-origin',
      cache: 'no-store',
    });
    return parseBlob(res);
  }

  /** The BFF relays `/bff/api/...` with the session cookie, so a plain link works (new tab / download). */
  directUrl(path: string): string {
    return `${BFF_API_PREFIX}${path}`;
  }
}
