import { describe, expect, it } from 'vitest';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { describeError, problemFieldKey, submit, toActionFailure } from './errors';

describe('error mapping', () => {
  it('maps 404 to "not found or not shared with your organisation"', () => {
    expect(describeError(new ApiError(404))).toBe('Not found or not shared with your organisation.');
    expect(toActionFailure(new ApiError(404), 'The referral could not be accepted').message).toBe(
      'The referral could not be accepted. Not found or not shared with your organisation.',
    );
  });

  it('maps 403, 409, 5xx, session expiry and network errors', () => {
    expect(describeError(new ApiError(403))).toMatch(/multi-factor/);
    expect(describeError(new ApiError(409))).toMatch(/changed in the meantime/);
    expect(describeError(new ApiError(502))).toMatch(/temporarily unavailable/);
    expect(describeError(new SessionExpiredError())).toMatch(/session has ended/);
    expect(describeError(new TypeError('fetch failed'))).toMatch(/connection/);
    expect(toActionFailure(new SessionExpiredError(), 'x')).toMatchObject({ sessionExpired: true });
  });

  it('maps 422 clinical_content_not_allowed onto the named field', () => {
    const r = toActionFailure(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'instructions', detail: 'ECHO of input' }), 'Not saved', ['appointmentAt', 'instructions']);
    expect(r.fieldErrors).toEqual({ instructions: 'Remove medical information from this field.' });
    expect(r.message).toMatch(/^Not saved\. This text seems to contain medical information/);
    expect(r.message).not.toContain('ECHO');
  });

  it('shows clinical content without a known field as a general error', () => {
    const r = toActionFailure(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'somethingElse' }), 'Not saved', ['note']);
    expect(r.fieldErrors).toEqual({});
    expect(r.code).toBe('clinical_content_not_allowed');
  });

  it('explains 422 consent_required', () => {
    const r = toActionFailure(new ApiError(422, { code: 'consent_required', title: 'Consent required' }), 'Not joined');
    expect(r.message).toMatch(/consent for sharing with your organisation is not active/);
    expect(describeError(new ApiError(422, { code: 'consent_required' }))).toMatch(/consent/);
  });

  it('names the field for other validation problems', () => {
    expect(toActionFailure(new ApiError(400, { title: 'Invalid request', field: 'body.dueDate' }), 'Not created', ['title', 'dueDate'])).toMatchObject({
      message: 'Not created: Invalid request',
      fieldErrors: { dueDate: 'Please check this field.' },
    });
  });

  it('resolves nested and prefixed problem fields', () => {
    expect(problemFieldKey('body.instructions', ['instructions'])).toBe('instructions');
    expect(problemFieldKey('approvedAmount.amount', ['approvedAmount'])).toBe('approvedAmount');
    expect(problemFieldKey('tasks[0].title', ['title'])).toBe('title');
    expect(problemFieldKey(undefined, ['x'])).toBeUndefined();
  });

  it('submit() wraps success and failure', async () => {
    await expect(submit({ api: async <T,>() => ({ ok: 1 }) as T }, '/x', {}, 'f')).resolves.toEqual({ ok: true, body: { ok: 1 } });
    await expect(submit({ api: async () => Promise.reject(new ApiError(409)) }, '/x', {}, 'f')).resolves.toMatchObject({ ok: false, status: 409 });
  });
});
