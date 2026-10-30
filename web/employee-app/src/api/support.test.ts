import { afterEach, describe, expect, it, vi } from 'vitest';
import { bffWithFetch, json } from '../test/fetch';
import {
  cismResponseBody,
  fetchCismOffers,
  fetchPeers,
  fetchScreen,
  fetchSignposting,
  peerChoices,
  peerRequestBody,
  requestPeerContact,
  respondToCism,
  saveSharing,
  screenComplete,
  selfReferralBody,
  sharingBody,
  submitScreen,
  submitSelfReferral,
  toCismOffer,
  toPeer,
  toScreenForm,
  toScreenOutcome,
  toSharingList,
  toSignposting,
  validatePeerRequest,
  validateSelfReferral,
} from './support';

afterEach(() => vi.unstubAllGlobals());

const PHQ9_FORM = {
  instrument: 'PHQ9',
  title: 'PHQ-9',
  questions: Array.from({ length: 9 }, (_, i) => ({ code: `PHQ9_${i + 1}`, text: `Question ${i + 1}` })),
  options: [
    { value: 3, label: 'Nearly every day' },
    { value: 0, label: 'Not at all' },
    { value: 1, label: 'Several days' },
    { value: 2, label: 'More than half the days' },
  ],
};

describe('signposting and emergency guidance', () => {
  it('accepts a wrapped object with emergency, sorts 24-hour services first, drops unsafe links and extra fields', () => {
    const s = toSignposting({
      items: [
        { code: 'EAP', title: 'Employee Assistance Programme', summary: 'Counselling', phone: '0800 123', available24h: false, internalNote: 'x' },
        { code: 'SAM', title: 'Samaritans', url: 'javascript:alert(1)', phone: '116 123', available24h: true },
        { code: 'WEB', title: 'Self-help', url: 'https://example.org/help' },
        { summary: 'no title – dropped' },
      ],
      emergency: { text: 'If you are in danger call 999', phone: '999' },
    });
    expect(s.services.map((x) => x.code)).toEqual(['SAM', 'EAP', 'WEB']);
    expect(s.services[0]!.url).toBeUndefined();
    expect(s.services[2]!.url).toBe('https://example.org/help');
    expect(s.emergency).toEqual({ text: 'If you are in danger call 999', phone: '999' });
    expect(Object.keys(s.services[1]!)).not.toContain('internalNote');
  });

  it('a plain array is accepted without emergency; a 404 yields an empty list', async () => {
    expect(toSignposting([{ code: 'A', title: 'A' }]).emergency).toBeUndefined();
    const { client } = bffWithFetch(() => new Response(null, { status: 404 }));
    expect(await fetchSignposting(client)).toEqual({ services: [], emergency: undefined });
  });
});

describe('confidential self-referral', () => {
  it('requires a concern and a phone number when phone contact is chosen', () => {
    expect(validateSelfReferral({ preferredContact: 'PHONE' })).toEqual({ phone: 'required', concern: 'required' });
    expect(validateSelfReferral({ preferredContact: 'APP_CHAT', concern: 'SLEEP' })).toEqual({});
    expect(validateSelfReferral({ preferredContact: 'VIDEO', concern: 'SLEEP', phone: 'call me' })).toEqual({ phone: 'invalid' });
    expect(validateSelfReferral({ preferredContact: 'APP_CHAT', concern: 'OTHER', message: 'x'.repeat(2001) })).toEqual({ message: 'tooLong' });
  });

  it('sends only the contract fields, blanks omitted', async () => {
    expect(selfReferralBody({ preferredContact: 'APP_CHAT', concern: 'WORK_STRESS', phone: ' ', bestTime: '', message: '  ' })).toEqual({ preferredContact: 'APP_CHAT', concern: 'WORK_STRESS' });
    const { client, calls } = bffWithFetch(() => json({ caseRef: 'SUP-2026-000004', nextContactBy: '2026-10-31T12:00:00Z', caseId: 'secret', risk: 'LOW' }));
    const r = await submitSelfReferral(client, { preferredContact: 'PHONE', phone: '+44 7700 900123', bestTime: 'EVENING', concern: 'LOW_MOOD', message: 'Hard weeks' });
    expect(r).toEqual({ caseRef: 'SUP-2026-000004', nextContactBy: '2026-10-31T12:00:00Z' });
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/support/self-referral', method: 'POST', body: { preferredContact: 'PHONE', phone: '+44 7700 900123', bestTime: 'EVENING', concern: 'LOW_MOOD', message: 'Hard weeks' } });
  });
});

describe('screening questionnaires', () => {
  it('maps the questionnaire with options sorted by value', () => {
    const f = toScreenForm(PHQ9_FORM, 'PHQ9')!;
    expect(f.questions).toHaveLength(9);
    expect(f.options.map((o) => o.value)).toEqual([0, 1, 2, 3]);
    expect(toScreenForm({ questions: [], options: [] }, 'GAD7')).toBeUndefined();
  });

  it('is complete only when every question has an offered value', () => {
    const f = toScreenForm(PHQ9_FORM, 'PHQ9')!;
    expect(screenComplete(f, Array(9).fill(0))).toBe(true);
    expect(screenComplete(f, [...Array(8).fill(0), undefined])).toBe(false);
    expect(screenComplete(f, [...Array(8).fill(0), 7])).toBe(false);
  });

  it('outcome: band, score of max, urgent flag; next steps that read like a diagnosis are dropped', () => {
    const f = toScreenForm(PHQ9_FORM, 'PHQ9')!;
    const o = toScreenOutcome({ score: 22, band: 'severe', urgent: true, nextSteps: ['A clinician will call you today', 'Diagnosis: major depressive disorder'], caseId: 'x' }, f);
    expect(o).toEqual({ score: 22, maxScore: 27, band: 'SEVERE', urgent: true, nextSteps: ['A clinician will call you today'] });
    expect(toScreenOutcome({ score: 3, band: 'NONSENSE' }).band).toBeUndefined();
    expect(toScreenOutcome({ risk: 'URGENT' }).urgent).toBe(true);
  });

  it('fetches by instrument and posts integer answers', async () => {
    const { client, calls } = bffWithFetch((url) => (url.endsWith('/PHQ9') ? json(PHQ9_FORM) : json({ score: 4, band: 'MINIMAL', urgent: false, nextSteps: [] })));
    const f = (await fetchScreen(client, 'PHQ9'))!;
    const o = await submitScreen(client, f, Array(9).fill(0).map((_, i) => (i < 4 ? 1 : 0)));
    expect(o.band).toBe('MINIMAL');
    expect(calls()[0]!.url).toBe('/bff/api/me/support/screens/PHQ9');
    expect(calls()[1]).toMatchObject({ url: '/bff/api/me/support/screens', method: 'POST', body: { instrument: 'PHQ9', answers: [1, 1, 1, 1, 0, 0, 0, 0, 0] } });
  });
});

describe('sharing choice (manager sees fitness / adjustments only if chosen)', () => {
  it('maps one object or a list; unknown choices fall back to NOTHING; clinical fields are dropped', () => {
    const list = toSharingList([
      { caseId: 'c1', caseRef: 'SUP-2026-000001', choice: 'FITNESS_ONLY', risk: 'HIGH', notes: 'secret', concern: 'ANXIETY' },
      { caseId: 'c2', choice: 'EVERYTHING' },
    ]);
    expect(list).toEqual([
      { caseId: 'c1', caseRef: 'SUP-2026-000001', choice: 'FITNESS_ONLY', adjustments: undefined, updatedAt: undefined },
      { caseId: 'c2', caseRef: undefined, choice: 'NOTHING', adjustments: undefined, updatedAt: undefined },
    ]);
    expect(toSharingList({ caseId: 'c3', choice: 'FITNESS_AND_ADJUSTMENTS', adjustments: 'No nights' })[0]!.adjustments).toBe('No nights');
  });

  it('adjustments are sent only with FITNESS_AND_ADJUSTMENTS; revoking sends NOTHING', async () => {
    expect(sharingBody('c1', 'FITNESS_ONLY', 'No nights')).toEqual({ caseId: 'c1', choice: 'FITNESS_ONLY' });
    expect(sharingBody('c1', 'FITNESS_AND_ADJUSTMENTS', ' No nights ')).toEqual({ caseId: 'c1', choice: 'FITNESS_AND_ADJUSTMENTS', adjustments: 'No nights' });
    const { client, calls } = bffWithFetch(() => json({}));
    await saveSharing(client, 'c1', 'NOTHING', 'ignored');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/support/sharing', method: 'PUT', body: { caseId: 'c1', choice: 'NOTHING' } });
  });
});

describe('CISM offers', () => {
  it('maps offers, unanswered first; employee / clinician fields dropped', async () => {
    expect(toCismOffer({ id: 'o1', title: 'Ramp incident', offerDueBy: '2026-11-01T10:00:00Z', employeeId: 'E1', displayName: 'X', sessionType: 'GROUP' })).toEqual({
      id: 'o1', title: 'Ramp incident', occurredAt: undefined, offeredAt: undefined, respondBy: '2026-11-01T10:00:00Z', response: undefined, preferredContact: undefined,
    });
    const { client } = bffWithFetch(() => json([{ id: 'a', response: 'DECLINED', offeredAt: '2026-10-02' }, { id: 'b', offeredAt: '2026-10-01' }]));
    expect((await fetchCismOffers(client)).map((o) => o.id)).toEqual(['b', 'a']);
  });

  it('preferred contact only accompanies an acceptance', async () => {
    expect(cismResponseBody('DECLINED', 'PHONE')).toEqual({ response: 'DECLINED' });
    const { client, calls } = bffWithFetch(() => json({}));
    await respondToCism(client, 'o/1', 'ACCEPTED', 'VIDEO');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/cism/offers/o%2F1/response', method: 'POST', body: { response: 'ACCEPTED', preferredContact: 'VIDEO' } });
  });
});

describe('peer support (pseudonymous)', () => {
  it('keeps only alias, base and languages – never a surname, username or id', async () => {
    const p = toPeer({ alias: 'Sam', base: 'LHR', languages: ['en', 'fr'], surname: 'Smith', username: 'peer1', employeeId: 'S8801', id: 'v1' })!;
    expect(p).toEqual({ alias: 'Sam', base: 'LHR', languages: ['en', 'fr'] });
    const { client } = bffWithFetch(() => json([{ alias: 'Sam', base: 'LHR', languages: ['en'] }, { base: 'DXB' }]));
    expect(await fetchPeers(client)).toHaveLength(1);
    expect(peerChoices([p, { alias: 'Ali', base: 'DXB', languages: ['ar', 'en'] }])).toEqual({ bases: ['DXB', 'LHR'], languages: ['ar', 'en', 'fr'] });
  });

  it('requires an alias and a call-back number; posts the request and returns the contact code', async () => {
    expect(validatePeerRequest({})).toEqual({ alias: 'required', callbackPhone: 'required' });
    expect(validatePeerRequest({ alias: 'x'.repeat(41), callbackPhone: 'abc' })).toEqual({ alias: 'tooLong', callbackPhone: 'invalid' });
    expect(peerRequestBody({ alias: ' Blue ', callbackPhone: '+44 7700 900123', base: '', language: 'en' })).toEqual({ alias: 'Blue', callbackPhone: '+44 7700 900123', language: 'en' });
    const { client, calls } = bffWithFetch(() => json({ contactCode: 'PS-7K2Q', volunteer: 'peer1' }));
    expect(await requestPeerContact(client, { alias: 'Blue', callbackPhone: '+44 7700 900123' })).toBe('PS-7K2Q');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/peer-support/contact-request', method: 'POST', body: { alias: 'Blue', callbackPhone: '+44 7700 900123' } });
  });
});
