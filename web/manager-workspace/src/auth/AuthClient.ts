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

/** Non-2xx API response. `problem` is the RFC 9457 body if one was returned. */
export class ApiError extends Error {
  override name = 'ApiError';
  readonly status: number;
  readonly problem?: { title?: string; detail?: string };
  constructor(status: number, problem?: { title?: string; detail?: string }) {
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
   * Returns parsed JSON (or undefined for 204).
   */
  api<T>(path: string, init?: { method?: string; body?: unknown }): Promise<T>;
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
    let problem: { title?: string; detail?: string } | undefined;
    const ct = res.headers.get('content-type') ?? '';
    if (ct.includes('json')) {
      try {
        const body = (await res.json()) as { title?: string; detail?: string };
        problem = { title: body.title, detail: body.detail };
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
