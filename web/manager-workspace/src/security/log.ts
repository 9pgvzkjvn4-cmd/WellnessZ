/**
 * PHI-safe logger.
 *
 * Application code must never call console.* directly (enforced by ESLint
 * `no-console`). This logger only accepts a static event code plus an optional
 * error *name* / HTTP status – never payloads, URLs with identifiers, form
 * values, tokens or server messages, which could contain PHI.
 * In production builds only warnings/errors are emitted.
 */
export type LogDetail = { status?: number; errorName?: string };

function emit(level: 'debug' | 'warn' | 'error', code: string, detail?: LogDetail) {
  if (level === 'debug' && !import.meta.env.DEV) return;
  const safe = detail ? { status: detail.status, errorName: detail.errorName } : undefined;
  console[level](`[wz] ${code}`, safe ?? '');
}

export const log = {
  debug: (code: string, detail?: LogDetail) => emit('debug', code, detail),
  warn: (code: string, detail?: LogDetail) => emit('warn', code, detail),
  error: (code: string, detail?: LogDetail) => emit('error', code, detail),
};

/** Reduce an unknown thrown value to its class name only. */
export function errorName(e: unknown): string {
  return e instanceof Error ? e.name : typeof e;
}
