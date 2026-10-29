import { afterEach, describe, expect, it, vi } from 'vitest';
import { bffWithFetch, json } from '../test/fetch';
import {
  canChat,
  canJoinSession,
  canRespond,
  canSendChat,
  fetchChat,
  fetchMySessions,
  fetchMyTreatmentPlans,
  followUpResponseBody,
  giveSessionConsent,
  joinSession,
  loadCareCounts,
  needsConsent,
  respondToFollowUp,
  sendChat,
  sessionWindow,
  sortSessions,
  toChat,
  toJoinLink,
  toSession,
  toTreatmentPlan,
  toTreatmentPlans,
  usableLink,
  type MySession,
} from './care';

afterEach(() => vi.unstubAllGlobals());

const NOW = new Date('2026-10-29T09:00:00Z');

const RAW_PLAN = {
  id: 'p1',
  caseId: 'c-secret',
  caseRef: 'AIR-2026-000012',
  employeeId: 'E1004',
  status: 'ACTIVE',
  version: 3,
  goals: ['Lift 10 kg without pain', { text: 'Back to full duties' }, ''],
  interventions: [
    { type: 'PHYSIOTHERAPY', description: 'Core strengthening', frequency: 'twice a week', startDate: '2026-10-20', endDate: '2026-12-01', providerCode: 'PHYSIOPLUS' },
    { type: 'self_care', description: 'Daily walks' },
  ],
  followUps: [
    { id: 'f2', dueDate: '2026-11-20', type: 'PHONE', status: 'PLANNED' },
    { id: 'f1', dueDate: '2026-11-05', type: 'OH_REVIEW', status: 'BOOKED', appointmentId: 'a1', appointment: { startsAt: '2026-11-05T08:00:00Z', siteCode: 'HQ-MC' } },
    { id: 'f0', dueDate: '2026-10-25', type: 'OH_REVIEW', status: 'CONFIRMED', employeeResponse: { response: 'CONFIRM', at: '2026-10-21T10:00:00Z' } },
  ],
  externalAppointments: [{ referralId: 'r1', ref: 'RFL-2026-000001', serviceName: 'Physiotherapy', providerName: 'Physio Plus', appointmentAt: '2026-11-02T14:00:00Z', location: '12 High St', instructions: 'Bring trainers', status: 'SCHEDULED' }],
  expectedRecoveryDate: '2026-12-15',
  guideline: { code: 'BACK_STRAIN', title: 'Back strain', expectedDays: 42 },
  variance: { actualDays: 10, expectedDays: 42, pctOver: 0, alert: false },
  responsibleClinician: 'drsmith',
  documentId: 'd-secret',
};

describe('treatment plan mapping (E04 §3.1)', () => {
  it('keeps plain-language fields only and drops OH-internal ones', () => {
    const p = toTreatmentPlan(RAW_PLAN)!;
    expect(p.goals).toEqual(['Lift 10 kg without pain', 'Back to full duties']);
    expect(p.interventions[0]).toEqual({ type: 'PHYSIOTHERAPY', description: 'Core strengthening', frequency: 'twice a week', startDate: '2026-10-20', endDate: '2026-12-01', provider: 'PHYSIOPLUS' });
    expect(p.interventions[1]!.type).toBe('SELF_CARE');
    expect(p.followUps.map((f) => f.id)).toEqual(['f0', 'f1', 'f2']); // by due date
    expect(p.followUps[1]!.appointment).toEqual({ startsAt: '2026-11-05T08:00:00Z', endsAt: undefined, siteCode: 'HQ-MC', status: undefined });
    expect(p.followUps[0]!.response).toBe('CONFIRM');
    expect(p.externalAppointments).toEqual([
      { key: 'r1', ref: 'RFL-2026-000001', service: 'Physiotherapy', provider: 'Physio Plus', appointmentAt: '2026-11-02T14:00:00Z', location: '12 High St', instructions: 'Bring trainers', status: 'SCHEDULED' },
    ]);
    const s = JSON.stringify(p);
    for (const secret of ['c-secret', 'E1004', 'drsmith', 'BACK_STRAIN', 'pctOver', 'd-secret']) expect(s).not.toContain(secret);
    expect(toTreatmentPlan({ status: 'ACTIVE' })).toBeUndefined();
  });

  it('accepts arrays, wrappers and a single object; hides drafts; active first', () => {
    expect(toTreatmentPlans([{ id: 'a', status: 'COMPLETED' }, { id: 'b', status: 'ACTIVE' }, { id: 'c', status: 'DRAFT' }]).map((p) => p.id)).toEqual(['b', 'a']);
    expect(toTreatmentPlans({ plans: [{ id: 'x', status: 'ACTIVE' }] })).toHaveLength(1);
    expect(toTreatmentPlans({ id: 'y', status: 'ACTIVE' })).toHaveLength(1);
    expect(toTreatmentPlans(null)).toEqual([]);
  });

  it('a follow-up can be answered while planned / booked, unanswered and the plan is active', () => {
    const p = toTreatmentPlan(RAW_PLAN)!;
    const [f0, f1, f2] = p.followUps;
    expect(canRespond(f0!, p)).toBe(false); // already confirmed
    expect(canRespond(f1!, p)).toBe(true);
    expect(canRespond(f2!, p)).toBe(true);
    expect(canRespond(f2!, { status: 'COMPLETED' })).toBe(false);
    expect(canRespond({ ...f2!, status: 'MISSED' })).toBe(false);
    expect(canRespond({ ...f2!, response: 'DECLINE' })).toBe(false);
  });

  it('posts the response; the reason only on decline, trimmed and capped', async () => {
    expect(followUpResponseBody('CONFIRM', 'ignored')).toEqual({ response: 'CONFIRM' });
    expect(followUpResponseBody('DECLINE', '   ')).toEqual({ response: 'DECLINE' });
    expect(followUpResponseBody('DECLINE', 'x'.repeat(600)).reason).toHaveLength(500);
    const { client, calls } = bffWithFetch((url) => (url.endsWith('/treatment-plans') ? json([RAW_PLAN]) : new Response(null, { status: 204 })));
    expect((await fetchMyTreatmentPlans(client))[0]!.id).toBe('p1');
    await respondToFollowUp(client, 'p1', 'f/2', 'DECLINE', ' roster clash ');
    const c = calls();
    expect(c[0]!.url).toBe('/bff/api/me/treatment-plans');
    expect(c[1]).toMatchObject({ url: '/bff/api/me/treatment-plans/p1/follow-ups/f%2F2/response', method: 'POST', body: { response: 'DECLINE', reason: 'roster clash' } });
  });

  it('a 404 is an empty list', async () => {
    const { client } = bffWithFetch(() => new Response(null, { status: 404 }));
    expect(await fetchMyTreatmentPlans(client)).toEqual([]);
    expect(await fetchMySessions(client)).toEqual([]);
  });
});

const RAW_SESSION = {
  id: 's1',
  ref: 'TH-2026-000004',
  employeeId: 'E1004',
  displayName: 'Rami Haddad',
  caseId: 'c-secret',
  scheduledAt: '2026-10-29T09:30:00Z',
  durationMinutes: 20,
  mode: 'VIDEO',
  status: 'SCHEDULED',
  clinicianUsername: 'drsmith',
  providerCode: 'TELEDOC',
  crewLayover: true,
  layoverCity: 'Singapore',
  consentGiven: false,
  encounterId: 'enc-secret',
};
const session = (over: Partial<MySession> = {}): MySession => ({ ...toSession(RAW_SESSION)!, ...over });

describe('telehealth sessions (E04 §3.7)', () => {
  it('keeps display fields only', () => {
    const s = toSession(RAW_SESSION)!;
    expect(s).toEqual({ id: 's1', ref: 'TH-2026-000004', scheduledAt: '2026-10-29T09:30:00Z', durationMinutes: 20, mode: 'VIDEO', status: 'SCHEDULED', clinician: 'drsmith', partner: 'TELEDOC', crewLayover: true, layoverCity: 'Singapore', consentGiven: false });
    for (const secret of ['E1004', 'Rami', 'c-secret', 'enc-secret']) expect(JSON.stringify(s)).not.toContain(secret);
    expect(toSession({ id: 'x' })).toBeUndefined();
  });

  it('join window: 15 minutes before the start until the end, consent required', () => {
    const s = session({ consentGiven: true });
    expect(sessionWindow(s, new Date('2026-10-29T09:14:59Z'))).toBe('before');
    expect(sessionWindow(s, new Date('2026-10-29T09:15:00Z'))).toBe('open');
    expect(sessionWindow(s, new Date('2026-10-29T09:50:00Z'))).toBe('open'); // end = 09:50
    expect(sessionWindow(s, new Date('2026-10-29T09:50:01Z'))).toBe('ended');
    expect(canJoinSession(s, new Date('2026-10-29T09:20:00Z'))).toBe(true);
    expect(canJoinSession(s, NOW)).toBe(false);
    expect(canJoinSession({ ...s, consentGiven: false }, new Date('2026-10-29T09:20:00Z'))).toBe(false);
    expect(canJoinSession({ ...s, status: 'CANCELLED' }, new Date('2026-10-29T09:20:00Z'))).toBe(false);
    expect(canJoinSession({ ...s, status: 'IN_PROGRESS' }, new Date('2026-10-29T09:40:00Z'))).toBe(true);
  });

  it('consent and chat rules', () => {
    expect(needsConsent(session(), NOW)).toBe(true);
    expect(needsConsent(session(), new Date('2026-10-29T10:00:00Z'))).toBe(false);
    expect(needsConsent(session({ consentGiven: true }), NOW)).toBe(false);
    expect(canChat(session())).toBe(false);
    expect(canChat(session({ consentGiven: true, status: 'COMPLETED' }))).toBe(true);
    expect(canSendChat(session({ consentGiven: true, status: 'COMPLETED' }))).toBe(false);
    expect(canSendChat(session({ consentGiven: true }))).toBe(true);
  });

  it('join links: http(s) only, and only usable inside the window before they expire', () => {
    expect(toJoinLink({ joinUrl: 'javascript:alert(1)' })).toBeUndefined();
    expect(toJoinLink({ joinUrl: 'data:text/html,x' })).toBeUndefined();
    const link = toJoinLink({ joinUrl: 'https://telehealth.wellnessz.local/room/abc', expiresAt: '2026-10-29T09:50:00Z' })!;
    expect(link.url).toBe('https://telehealth.wellnessz.local/room/abc');
    const s = session({ consentGiven: true });
    expect(usableLink(s, link, new Date('2026-10-29T09:20:00Z'))).toBe(link);
    expect(usableLink(s, link, NOW)).toBeUndefined();
    expect(usableLink(s, { ...link, expiresAt: '2026-10-29T09:19:00Z' }, new Date('2026-10-29T09:20:00Z'))).toBeUndefined();
  });

  it('sorts upcoming first, then past', () => {
    const list = sortSessions(
      [session({ id: 'old', scheduledAt: '2026-10-01T09:00:00Z', status: 'COMPLETED' }), session({ id: 'later', scheduledAt: '2026-11-01T09:00:00Z' }), session(), session({ id: 'cx', status: 'CANCELLED', scheduledAt: '2026-10-30T09:00:00Z' })],
      NOW,
    );
    expect(list.map((s) => s.id)).toEqual(['s1', 'later', 'cx', 'old']);
  });

  it('chat messages: text only, mine by sender role, chronological', () => {
    const msgs = toChat([
      { id: 'm2', sender: 'drsmith', senderRole: 'OH_CLINICIAN', text: 'Hello', sentAt: '2026-10-29T09:31:00Z' },
      { id: 'm1', sender: 'E1004', senderRole: 'employee', text: 'Hi', sentAt: '2026-10-29T09:30:00Z' },
      { id: 'm3', text: '' },
    ]);
    expect(msgs.map((m) => [m.id, m.mine])).toEqual([['m1', true], ['m2', false]]);
  });

  it('calls the /me/telehealth endpoints', async () => {
    const { client, calls } = bffWithFetch((url) => {
      if (url.endsWith('/join')) return json({ joinUrl: 'https://telehealth.wellnessz.local/room/t1', expiresAt: '2026-10-29T09:50:00Z' });
      if (url.endsWith('/messages')) return json([{ id: 'm1', senderRole: 'EMPLOYEE', text: 'Hi' }]);
      if (url.endsWith('/telehealth')) return json([RAW_SESSION]);
      return new Response(null, { status: 204 });
    });
    expect((await fetchMySessions(client))[0]!.id).toBe('s1');
    await giveSessionConsent(client, 's1');
    expect((await joinSession(client, 's1'))?.url).toBe('https://telehealth.wellnessz.local/room/t1');
    expect(await fetchChat(client, 's1')).toHaveLength(1);
    await sendChat(client, 's1', `  ${'a'.repeat(2100)} `);
    await sendChat(client, 's1', '   '); // nothing sent
    const c = calls();
    expect(c.map((x) => `${x.method} ${x.url}`)).toEqual([
      'GET /bff/api/me/telehealth',
      'POST /bff/api/me/telehealth/s1/consent',
      'POST /bff/api/me/telehealth/s1/join',
      'GET /bff/api/me/telehealth/s1/messages',
      'POST /bff/api/me/telehealth/s1/messages',
    ]);
    expect((c[4]!.body as { text: string }).text).toHaveLength(2000);
  });

  it('home counts: follow-ups to answer and sessions needing consent or ready to join', async () => {
    const { client } = bffWithFetch((url) => (url.endsWith('/treatment-plans') ? json([RAW_PLAN]) : json([RAW_SESSION, { ...RAW_SESSION, id: 's2', consentGiven: true }])));
    expect(await loadCareCounts(client, NOW)).toEqual({ treatment: 2, telehealth: 1 });
    const failing = bffWithFetch(() => new Response(null, { status: 500 }));
    expect(await loadCareCounts(failing.client, NOW)).toEqual({});
  });
});
