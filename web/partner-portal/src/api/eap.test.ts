import { describe, expect, it } from 'vitest';
import { mockApi, reply } from '../test/mockApi';
import { S } from '../strings';
import { eapRaw } from '../test/fixtures';
import { buildFeedback, EAP_FIELDS, emptyFeedback, fetchEapReferrals, nextStatuses, sendFeedback, toEapReferrals, validateFeedback, type EapReferral, type FeedbackForm } from './eap';


const NOW = new Date(2026, 9, 30, 12, 0, 0);
const ref = (over: Partial<EapReferral> = {}): EapReferral => ({ ...toEapReferrals([eapRaw()])[0]!, ...over });
const form = (over: Partial<FeedbackForm> = {}): FeedbackForm => ({ status: 'IN_PROGRESS', attendance: [], closureReason: '', ...over });

describe('EAP referral list (minimal data)', () => {
  it('keeps only the contract fields – no case, concern, risk, screening or employee id', () => {
    const [r] = toEapReferrals([eapRaw(), { ref: 'no id' }]);
    expect(Object.keys(r!).sort()).toEqual([...EAP_FIELDS].sort());
    expect(r).toMatchObject({ preferredContact: 'PHONE', sessionsAuthorised: 6, urgency: 'PRIORITY' });
    expect(JSON.stringify(r)).not.toMatch(/sup-secret|LOW_MOOD|HIGH|PHQ9|E1004/);
    expect(toEapReferrals([eapRaw({ sessionsAuthorised: 40 })])[0]!.sessionsAuthorised).toBe(12);
  });

  it('fetches with the status filter; open priority referrals first', async () => {
    const api = mockApi({
      'GET /partner/eap-referrals?status=SENT': [eapRaw()],
      'GET /partner/eap-referrals': [eapRaw({ id: 'c', status: 'CLOSED', urgency: 'PRIORITY' }), eapRaw({ id: 'r', urgency: 'ROUTINE' }), eapRaw({ id: 'p' })],
    });
    expect((await fetchEapReferrals(api.client)).map((r) => r.id)).toEqual(['p', 'r', 'c']);
    expect(await fetchEapReferrals(api.client, 'SENT')).toHaveLength(1);
  });
});

describe('feedback rules', () => {
  it('allowed next statuses follow the referral state; closed referrals take no feedback', () => {
    expect(nextStatuses('SENT')).toEqual(['ACCEPTED', 'IN_PROGRESS', 'CLOSED']);
    expect(nextStatuses('IN_PROGRESS')).toEqual(['IN_PROGRESS', 'CLOSED']);
    expect(nextStatuses('CLOSED')).toEqual([]);
    expect(emptyFeedback(ref()).status).toBe('ACCEPTED');
    expect(emptyFeedback(ref({ status: 'ACCEPTED' })).status).toBe('IN_PROGRESS');
  });

  it('closing needs a reason; COMPLETED needs at least one attended session', () => {
    expect(validateFeedback(form({ status: 'CLOSED' }), ref({ status: 'IN_PROGRESS' }), NOW)).toEqual({ closureReason: S.eap.closureRequired });
    expect(validateFeedback(form({ status: 'CLOSED', closureReason: 'COMPLETED' }), ref({ status: 'IN_PROGRESS' }), NOW)).toEqual({ closureReason: S.eap.completedNeedsSession });
    expect(validateFeedback(form({ status: 'CLOSED', closureReason: 'DISENGAGED', attendance: [{ date: '2026-10-22', attended: 'NO' }] }), ref({ status: 'IN_PROGRESS' }), NOW)).toEqual({});
    expect(validateFeedback(form({ status: 'ACCEPTED' }), ref({ status: 'IN_PROGRESS' }), NOW)).toEqual({ status: S.eap.statusNotAllowed });
  });

  it('attendance dates: valid, not in the future, not before the referral, no duplicates; attended answered; ≤ sessions authorised', () => {
    const e = validateFeedback(
      form({
        attendance: [
          { date: '2026-11-02', attended: 'YES' },
          { date: '2026-10-01', attended: 'YES' },
          { date: '2026-10-22', attended: 'YES' },
          { date: '2026-10-22', attended: '' },
          { date: 'nope', attended: 'NO' },
        ],
      }),
      ref({ sessionsAuthorised: 2 }),
      NOW,
    );
    expect(e['attendance.0.date']).toBe(S.eap.dateFuture);
    expect(e['attendance.1.date']).toBe(S.eap.dateBeforeReferral);
    expect(e['attendance.2.date']).toBeUndefined();
    expect(e['attendance.3.date']).toBe(S.eap.dateDuplicate);
    expect(e['attendance.3.attended']).toBe(S.eap.attendedRequired);
    expect(e['attendance.4.date']).toBe(S.validation.date);
    expect(e.attendance).toBe(S.eap.tooManySessions(2));
  });

  it('builds the structured body only: status, sorted attendance booleans, closure reason only when closing', () => {
    expect(buildFeedback(form({ status: 'IN_PROGRESS', closureReason: 'COMPLETED', attendance: [{ date: '2026-10-28', attended: 'NO' }, { date: '2026-10-21', attended: 'YES' }] }))).toEqual({
      status: 'IN_PROGRESS',
      attendance: [
        { date: '2026-10-21', attended: true },
        { date: '2026-10-28', attended: false },
      ],
    });
    const closed = buildFeedback(form({ status: 'CLOSED', closureReason: 'REFERRED_ON' }));
    expect(closed).toEqual({ status: 'CLOSED', attendance: [], closureReason: 'REFERRED_ON' });
    expect(Object.values(closed).some((v) => typeof v === 'string' && v.includes(' '))).toBe(false);
  });

  it('posts the feedback; server rejections are mapped without echoing detail', async () => {
    const api = mockApi({ 'POST /partner/eap-referrals/e%2F1/feedback': reply(204) });
    expect(await sendFeedback(api.client, 'e/1', form({ attendance: [{ date: '2026-10-21', attended: 'YES' }] }))).toMatchObject({ ok: true });
    expect(api.calls.at(-1)).toMatchObject({ method: 'POST', path: '/partner/eap-referrals/e%2F1/feedback', body: { status: 'IN_PROGRESS', attendance: [{ date: '2026-10-21', attended: true }] } });
    const bad = mockApi({ 'POST /partner/eap-referrals/e1/feedback': reply(422, { status: 422, code: 'invalid_transition', title: 'Invalid status', field: 'status', detail: 'echo' }) });
    const res = await sendFeedback(bad.client, 'e1', form());
    expect(res).toMatchObject({ ok: false, fieldErrors: { status: S.errors.checkField } });
    expect(JSON.stringify(res)).not.toContain('echo');
  });
});
