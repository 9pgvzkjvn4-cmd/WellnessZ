import { ApiError, SessionExpiredError, type AuthClient } from '../auth/AuthClient';
import { CLINICAL_CONTENT_MESSAGE, isClinicalContentCode, problemFieldKey, type FieldErrors } from './referrals';

/**
 * Generic POST for the increment-2 workspace actions (duty-proposal decision, RTW acknowledgement, job offer,
 * withdraw, IHR appeal). Same outcome shape and 422 handling as the referral forms: `code=clinical_content_not_allowed`
 * (or the increment-3 `clinical_content`) with a `field` highlights that control; the problem `detail` (may echo input) is never shown.
 */
export type ActionResult<T = unknown> =
  | { ok: true; body: T }
  | { ok: false; fieldErrors: FieldErrors; message: string; sessionExpired?: boolean; status?: number; clinical?: boolean };

export interface ActionMessages {
  /** What failed, e.g. "The decision was not saved". */
  failed: string;
}

export async function submitAction<T = unknown>(
  client: Pick<AuthClient, 'api'>,
  path: string,
  body: unknown,
  allowedFields: readonly string[],
  { failed }: ActionMessages,
  /** Collapse indexed list fields (`restrictions.1`) onto their group key. */
  collapseLists = true,
): Promise<ActionResult<T>> {
  try {
    return { ok: true, body: await client.api<T>(path, { method: 'POST', body }) };
  } catch (e) {
    if (e instanceof SessionExpiredError) return { ok: false, fieldErrors: {}, message: 'Your session has ended. Please sign in again.', sessionExpired: true };
    if (e instanceof ApiError && (e.status === 422 || e.status === 400)) {
      const clinical = isClinicalContentCode(e.problem?.code);
      let key = problemFieldKey(e.problem?.field, allowedFields);
      if (key && collapseLists) key = key.split('.')[0];
      return {
        ok: false,
        status: e.status,
        clinical,
        fieldErrors: key ? { [key]: clinical ? CLINICAL_CONTENT_MESSAGE : 'Please check this field.' } : {},
        message: clinical
          ? `${failed} because it seems to contain medical information. Please remove diagnoses or medical opinions and use functional, work-related wording only.`
          : (e.problem?.title ?? `${failed}. Please check your entries.`),
      };
    }
    if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
      return { ok: false, status: e.status, fieldErrors: {}, message: `${failed}. It is not available to you or no longer exists.` };
    }
    if (e instanceof ApiError && e.status === 409) {
      return { ok: false, status: e.status, fieldErrors: {}, message: `${failed} because it has changed in the meantime. Please refresh.` };
    }
    if (e instanceof ApiError && e.status >= 500) {
      return { ok: false, status: e.status, fieldErrors: {}, message: 'The service is temporarily unavailable. Please try again later.' };
    }
    return { ok: false, fieldErrors: {}, message: `${failed}. Please check your connection and try again.` };
  }
}
