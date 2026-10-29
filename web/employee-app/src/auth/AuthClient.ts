export interface Session {
  authenticated: boolean;
  name?: string;
  roles: string[];
}

export const SIGNED_OUT: Session = { authenticated: false, roles: [] };

/** Thrown by `AuthClient.api` when the session is gone (401). */
export class SessionExpiredError extends Error {
  override name = 'SessionExpiredError';
}

/**
 * Subset of an RFC 9457 problem detail kept by the client. `code` is the machine-readable error code
 * (e.g. `clinical_content_not_allowed`); `field` names the offending request field when the server provides it.
 * `detail` is kept for diagnostics only – the UI never renders it (it may echo user input).
 */
export interface Problem {
  title?: string;
  detail?: string;
  code?: string;
  field?: string;
  correlationId?: string;
}

/** Non-2xx API response. `problem` is the RFC 9457 body if one was returned. */
export class ApiError extends Error {
  override name = 'ApiError';
  readonly status: number;
  readonly problem?: Problem;
  constructor(status: number, problem?: Problem) {
    super(`HTTP ${status}`);
    this.status = status;
    this.problem = problem;
  }
}

/**
 * Authentication strategy used by the UI. Two implementations:
 *  - BffAuthClient: browser/PWA → BFF session cookie; tokens never reach the browser.
 *  - NativeOidcAuthClient: Capacitor native → OIDC Auth Code + PKCE via system browser.
 */
export interface AuthClient {
  readonly mode: 'bff' | 'native-oidc';
  /** Resolve the current session (e.g. after app start or a login redirect). */
  init(): Promise<Session>;
  /** Start interactive login. BFF: full-page redirect. Native: opens system browser. */
  login(): Promise<Session | void>;
  /** End the session. `reason` is used for UI messaging only. */
  logout(reason?: 'user' | 'idle'): Promise<void>;
  /**
   * Call the core API. `path` is relative to `/api/v1` (e.g. `/cases`).
   * `body` is sent as JSON, except a `FormData` body which is sent as multipart/form-data
   * (the browser sets the boundary header). Returns parsed JSON (or undefined for 204).
   */
  api<T>(path: string, init?: { method?: string; body?: unknown }): Promise<T>;
  /**
   * GET binary content (e.g. a document PDF) relative to `/api/v1`. Errors are mapped like `api` (401 →
   * SessionExpiredError, other non-2xx → ApiError).
   */
  download(path: string): Promise<Blob>;
  /**
   * Same-origin URL the browser can open or download directly with the session cookie (BFF mode), or `undefined`
   * when the request needs a bearer token (native mode – use `download` instead).
   */
  directUrl(path: string): string | undefined;
}

/** Normalise roles: strip Spring `ROLE_` prefix, upper-case, dedupe. */
export function normaliseRoles(roles: unknown): string[] {
  if (!Array.isArray(roles)) return [];
  return [
    ...new Set(
      roles
        .filter((r): r is string => typeof r === 'string')
        .map((r) => r.trim().toUpperCase().replace(/^ROLE_/, ''))
        .filter(Boolean),
    ),
  ];
}

export async function parseResponse<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new SessionExpiredError();
  if (!res.ok) {
    let problem: Problem | undefined;
    const ct = res.headers.get('content-type') ?? '';
    if (ct.includes('json')) {
      try {
        const body = (await res.json()) as Record<string, unknown>;
        const str = (v: unknown) => (typeof v === 'string' && v ? v : undefined);
        problem = {
          title: str(body.title),
          detail: str(body.detail),
          code: str(body.code),
          field: str(body.field),
          correlationId: str(body.correlationId),
        };
      } catch {
        /* ignore */
      }
    }
    throw new ApiError(res.status, problem);
  }
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') ?? '';
  if (!ct.includes('json')) return undefined as T;
  return (await res.json()) as T;
}

/** Binary response (e.g. `application/pdf`); non-2xx responses are mapped by `parseResponse`. */
export async function parseBlob(res: Response): Promise<Blob> {
  if (res.status === 401 || !res.ok) {
    await parseResponse<never>(res);
  }
  return res.blob();
}

/** Encode a request body: FormData stays multipart (no Content-Type header – fetch adds the boundary), anything else JSON. */
export function encodeBody(body: unknown): { body?: BodyInit; contentType?: string } {
  if (body === undefined) return {};
  if (typeof FormData !== 'undefined' && body instanceof FormData) return { body };
  return { body: JSON.stringify(body), contentType: 'application/json' };
}
