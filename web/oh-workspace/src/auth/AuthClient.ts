export interface Session {
  authenticated: boolean;
  name?: string;
  /** Login name if the BFF exposes it (`username` / `preferredUsername`); used for "mine" highlighting only. */
  username?: string;
  roles: string[];
}

export const SIGNED_OUT: Session = { authenticated: false, roles: [] };

/** Thrown by `AuthClient.api` when the session is gone (401). */
export class SessionExpiredError extends Error {
  override name = 'SessionExpiredError';
}

/** RFC 9457 problem details as returned by wz-core-app (`{type, title, status, detail, code, correlationId}`). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  /** `409 code=cds_alert` (contract E03 §4.5): the alerts that need an override reason. Only known string fields are kept. */
  alerts?: ProblemAlert[];
}

export interface ProblemAlert {
  code: string;
  severity?: string;
  message?: string;
  reference?: string;
}

/** Non-2xx API response. `problem` is the RFC 9457 body if one was returned. */
export class ApiError extends Error {
  override name = 'ApiError';
  readonly status: number;
  readonly problem?: ProblemDetail;
  constructor(status: number, problem?: ProblemDetail) {
    super(`HTTP ${status}`);
    this.status = status;
    this.problem = problem;
  }
}

export interface ApiInit {
  method?: string;
  /** JSON-serialised, except `FormData` which is sent as multipart (browser sets the boundary). */
  body?: unknown;
}

/** Authentication strategy used by the UI (BFF session cookie; tokens never reach the browser). */
export interface AuthClient {
  readonly mode: 'bff';
  init(): Promise<Session>;
  login(): Promise<void>;
  logout(reason?: 'user' | 'idle'): Promise<void>;
  /**
   * Call the core API. `path` is relative to `/api/v1` (e.g. `/queues/counts`), sent as `/bff/api{path}`.
   * Returns parsed JSON (or undefined for 204 / non-JSON).
   */
  api<T>(path: string, init?: ApiInit): Promise<T>;
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

const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : undefined);

/** Pick only the known problem-detail fields (never keeps arbitrary payload). */
export function toProblem(body: unknown, headerCorrelationId?: string | null): ProblemDetail {
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  return {
    type: str(b.type),
    title: str(b.title),
    status: typeof b.status === 'number' ? b.status : undefined,
    detail: str(b.detail),
    code: str(b.code),
    correlationId: str(b.correlationId) ?? str(headerCorrelationId ?? undefined),
    ...(Array.isArray(b.alerts) ? { alerts: toAlerts(b.alerts) } : {}),
  };
}

function toAlerts(list: unknown[]): ProblemAlert[] {
  const out: ProblemAlert[] = [];
  for (const a of list) {
    if (!a || typeof a !== 'object') continue;
    const r = a as Record<string, unknown>;
    const code = str(r.code);
    if (!code) continue;
    out.push({ code, severity: str(r.severity), message: str(r.message), reference: str(r.reference) });
  }
  return out;
}

export async function parseResponse<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new SessionExpiredError();
  if (!res.ok) {
    const headerCid = res.headers.get('X-Correlation-Id');
    let problem: ProblemDetail | undefined = headerCid ? { correlationId: headerCid } : undefined;
    const ct = res.headers.get('content-type') ?? '';
    if (ct.includes('json')) {
      try {
        problem = toProblem(await res.json(), headerCid);
      } catch {
        /* ignore malformed body */
      }
    }
    throw new ApiError(res.status, problem);
  }
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') ?? '';
  if (!ct.includes('json')) return undefined as T;
  return (await res.json()) as T;
}
