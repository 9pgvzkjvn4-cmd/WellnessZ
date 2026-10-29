import { ApiError, SessionExpiredError, type AuthClient } from '../auth/AuthClient';
import { S } from '../strings';

/**
 * Error mapping (RFC 9457 problem details). Contract conventions: denied record access is reported as `404`
 * (shown as "not found or not shared with your organisation"), business rules as `422` with `code`
 * (`clinical_content_not_allowed` with `field`, `consent_required`), conflicts as `409`.
 * The problem `detail` may echo user input and is never rendered.
 */
export const CLINICAL_CONTENT_CODE = 'clinical_content_not_allowed';
export const CONSENT_REQUIRED_CODE = 'consent_required';

/** Older increments report `clinical_content`; treat both alike. */
export const isClinicalContent = (code?: string) => code === CLINICAL_CONTENT_CODE || code === 'clinical_content';

/** User-facing text for a failed read. */
export function describeError(e: unknown): string {
  if (e instanceof SessionExpiredError) return S.errors.sessionEnded;
  if (e instanceof ApiError) {
    if (e.status === 404) return S.errors.notFound;
    if (e.status === 403) return S.errors.forbidden;
    if (e.status === 409) return S.errors.conflict;
    if (e.status === 413) return S.errors.tooLarge;
    if (e.status >= 500) return S.errors.unavailable;
    if (e.status === 422 && e.problem?.code === CONSENT_REQUIRED_CODE) return S.errors.consentRequired;
    if (e.status === 422 && isClinicalContent(e.problem?.code)) return S.errors.clinicalContent;
    return e.problem?.title ?? S.errors.generic;
  }
  return S.errors.network;
}

export const isNotFound = (e: unknown) => e instanceof ApiError && e.status === 404;

/**
 * Map a problem `field` (`instructions`, `body.instructions`, `request.note`, `approvedAmount.amount`) onto one of the
 * form's own field keys; undefined when it names none of them.
 */
export function problemFieldKey(field: string | undefined, allowed: readonly string[]): string | undefined {
  if (!field) return undefined;
  const parts = field.split(/[.[\]]/).filter(Boolean);
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    if (allowed.includes(p)) return p;
  }
  return undefined;
}

export type FieldErrors = Record<string, string>;

export type ActionResult<T = unknown> =
  | { ok: true; body: T }
  | { ok: false; message: string; fieldErrors: FieldErrors; status?: number; code?: string; sessionExpired?: boolean };

/** Turn any thrown value from a write into an `ActionResult` failure (field-level for 422 with a known `field`). */
export function toActionFailure(e: unknown, failed: string, allowedFields: readonly string[] = []): Extract<ActionResult, { ok: false }> {
  if (e instanceof SessionExpiredError) return { ok: false, message: S.errors.sessionEnded, fieldErrors: {}, sessionExpired: true };
  if (e instanceof ApiError) {
    const code = e.problem?.code;
    const base = { ok: false as const, status: e.status, code };
    if (e.status === 422 || e.status === 400) {
      if (code === CONSENT_REQUIRED_CODE) return { ...base, message: S.errors.consentRequired, fieldErrors: {} };
      const key = problemFieldKey(e.problem?.field, allowedFields);
      if (isClinicalContent(code)) {
        return { ...base, message: `${failed}. ${S.errors.clinicalContent}`, fieldErrors: key ? { [key]: S.errors.clinicalContentField } : {} };
      }
      return { ...base, message: e.problem?.title ? `${failed}: ${e.problem.title}` : S.errors.failed(failed), fieldErrors: key ? { [key]: S.errors.checkField } : {} };
    }
    if (e.status === 404) return { ...base, message: `${failed}. ${S.errors.notFound}`, fieldErrors: {} };
    if (e.status === 403) return { ...base, message: `${failed}. ${S.errors.forbidden}`, fieldErrors: {} };
    if (e.status === 409) return { ...base, message: `${failed}. ${S.errors.conflict}`, fieldErrors: {} };
    if (e.status === 413) return { ...base, message: `${failed}. ${S.errors.tooLarge}`, fieldErrors: { file: S.errors.tooLarge } };
    if (e.status >= 500) return { ...base, message: S.errors.unavailable, fieldErrors: {} };
    return { ...base, message: `${failed}. ${S.errors.generic}`, fieldErrors: {} };
  }
  return { ok: false, message: `${failed}. ${S.errors.network}`, fieldErrors: {} };
}

/** POST (JSON or multipart `FormData`) with uniform error mapping. */
export async function submit<T = unknown>(
  client: Pick<AuthClient, 'api'>,
  path: string,
  body: unknown,
  failed: string,
  allowedFields: readonly string[] = [],
): Promise<ActionResult<T>> {
  try {
    return { ok: true, body: await client.api<T>(path, { method: 'POST', body }) };
  } catch (e) {
    return toActionFailure(e, failed, allowedFields);
  }
}

/** Encode a path segment taken from server data. */
export const seg = (id: string) => encodeURIComponent(id);
