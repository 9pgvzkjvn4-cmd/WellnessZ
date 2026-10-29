import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { bffWithFetch, json, problem } from '../test/fetch';
import { CONSENTS, DISCLOSURES, DOCUMENTS, JOB_OFFERS, RTW_PLANS } from '../test/portalFixtures';
import {
  acknowledgeDocument,
  acknowledgeRtwPlan,
  documentAction,
  documentState,
  fetchMyConsents,
  fetchMyDisclosures,
  fetchMyDocuments,
  fetchMyJobOffers,
  fetchMyRtwPlans,
  loadPortalCounts,
  normaliseDocuments,
  openDocumentNative,
  respondByPassed,
  respondToOffer,
  telHref,
  toDeclarationOutcome,
  toRestriction,
  withdrawConsent,
} from './portal';

afterEach(() => vi.unstubAllGlobals());

describe('my documents (GET /me/documents)', () => {
  it('projects documents, hides revoked ones and puts those needing acknowledgement first', () => {
    const docs = normaliseDocuments(DOCUMENTS);
    expect(docs.map((d) => d.id)).toEqual(['d8', 'd1', 'd5', 'd4', 'd3', 'd2', 'd6']);
    expect(JSON.stringify(docs)).not.toContain('sha256');
    expect(JSON.stringify(docs)).not.toContain('E1004');
  });

  it('derives the action and badge state per document type', () => {
    const byId = Object.fromEntries(normaliseDocuments(DOCUMENTS).map((d) => [d.id, d]));
    expect(documentAction(byId.d1!)).toBe('ACKNOWLEDGE');
    expect(documentState(byId.d1!)).toBe('ACTION');
    expect(documentAction(byId.d2!)).toBeUndefined(); // already acknowledged
    expect(documentState(byId.d2!)).toBe('ACKNOWLEDGED');
    expect(documentAction(byId.d3!)).toBe('RTW');
    expect(documentAction(byId.d4!)).toBe('OFFER');
    expect(documentState(byId.d5!)).toBe('NEW');
    expect(documentState(byId.d5!, new Set(['d5']))).toBe('READ');
    expect(documentState(byId.d6!)).toBe('SUPERSEDED');
    expect(documentAction(byId.d6!)).toBeUndefined();
  });

  it('calls /bff/api/me/documents and treats 404 as an empty list', async () => {
    const ok = bffWithFetch(() => json(DOCUMENTS));
    expect(await fetchMyDocuments(ok.client)).toHaveLength(7);
    expect(ok.calls()[0]).toMatchObject({ url: '/bff/api/me/documents', method: 'GET' });
    expect(await fetchMyDocuments(bffWithFetch(() => new Response(null, { status: 404 })).client)).toEqual([]);
  });

  it('propagates server errors and session expiry', async () => {
    await expect(fetchMyDocuments(bffWithFetch(() => problem(500, 'boom')).client)).rejects.toBeInstanceOf(ApiError);
    await expect(fetchMyDocuments(bffWithFetch(() => new Response(null, { status: 401 })).client)).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('acknowledges with POST + CSRF header and returns the server timestamp', async () => {
    const { client, calls } = bffWithFetch(() => json({ id: 'd1', acknowledgedAt: '2026-10-29T10:00:00Z' }));
    expect(await acknowledgeDocument(client, 'd1')).toBe('2026-10-29T10:00:00Z');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/documents/d1/acknowledge', method: 'POST', body: {} });
    expect(calls()[0]!.headers['X-XSRF-TOKEN']).toBe('csrf-123');
  });

  it('falls back to now when the acknowledgement returns 204', async () => {
    const { client } = bffWithFetch(() => new Response(null, { status: 204 }));
    expect(await acknowledgeDocument(client, 'a/b', () => new Date('2026-10-29T11:00:00Z'))).toBe('2026-10-29T11:00:00.000Z');
  });

  it('surfaces a failed acknowledgement as ApiError with the problem title', async () => {
    const { client } = bffWithFetch(() => problem(409, 'Already acknowledged'));
    const err = await acknowledgeDocument(client, 'd1').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).problem?.title).toBe('Already acknowledged');
  });

  it('BFF mode links the PDF directly and can download it as a blob', async () => {
    const { client, calls } = bffWithFetch(() => new Response(new Blob(['%PDF-1.7']), { status: 200, headers: { 'Content-Type': 'application/pdf' } }));
    expect(client.directUrl('/documents/d1/content')).toBe('/bff/api/documents/d1/content');
    const blob = await client.download('/documents/d1/content');
    expect(await blob.text()).toBe('%PDF-1.7');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/documents/d1/content', method: 'GET' });
  });

  it('native mode opens the downloaded PDF through a short-lived object URL', async () => {
    const download = vi.fn().mockResolvedValue(new Blob(['%PDF'], { type: '' }));
    const deps = { open: vi.fn(), createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn(), defer: vi.fn((fn: () => void) => fn()) };
    await openDocumentNative({ download }, 'd 1', deps);
    expect(download).toHaveBeenCalledWith('/documents/d%201/content');
    expect((deps.createObjectURL.mock.calls[0] as unknown as [Blob])[0].type).toBe('application/pdf');
    expect(deps.open).toHaveBeenCalledWith('blob:x', '_blank');
    expect(deps.revokeObjectURL).toHaveBeenCalledWith('blob:x');
  });
});

describe('restrictions (functional wording)', () => {
  it('substitutes {value} and tolerates missing labels', () => {
    expect(toRestriction({ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 10 })?.text).toBe('No lifting above 10 kg');
    expect(toRestriction({ code: 'NIGHT', label: 'No night shifts' })?.text).toBe('No night shifts');
    expect(toRestriction({ code: 'HOURS_PER_DAY', label: 'Max hours per day', value: 6 })?.text).toBe('Max hours per day (6)');
    expect(toRestriction({ code: 'LIFT_MAX_KG', value: 5 })?.text).toBe('Lift max kg: 5');
    expect(toRestriction('Seated work')?.text).toBe('Seated work');
    expect(toRestriction(null)).toBeUndefined();
  });
});

describe('RTW plans (GET /me/rtw-plans, POST acknowledge)', () => {
  it('orders phases and reviews, keeps only functional fields, unacknowledged plans first', async () => {
    const { client } = bffWithFetch(() => json(RTW_PLANS));
    const plans = await fetchMyRtwPlans(client);
    expect(plans.map((p) => p.id)).toEqual(['p1', 'p2']);
    const p1 = plans[0]!;
    expect(p1.phases.map((p) => p.fromWeek)).toEqual([1, 3]);
    expect(p1.phases[0]!.restrictions.map((r) => r.text)).toEqual(['No lifting above 5 kg', 'No airside driving']);
    expect(p1.reviews.map((r) => r.dueDate)).toEqual(['2026-11-10', '2026-11-24']);
    expect(p1.managerAckAt).toBe('2026-10-30T10:00:00Z');
    expect(JSON.stringify(p1)).not.toContain('internal');
    expect(JSON.stringify(p1)).not.toContain('nurse1');
    expect(plans[1]!.contact).toEqual({ name: 'Nora Nurse', email: 'oh@example.com', phone: undefined });
    expect(plans[1]!.employeeAck).toEqual({ at: '2026-09-01T09:00:00Z', agree: true, comment: 'Thanks' });
  });

  it('sends {agree:true, comment?} with CSRF', async () => {
    const { client, calls } = bffWithFetch(() => new Response(null, { status: 204 }));
    await acknowledgeRtwPlan(client, 'p1', '  Can I start at 9?  ');
    await acknowledgeRtwPlan(client, 'p1', '   ');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/rtw-plans/p1/acknowledge', method: 'POST', body: { agree: true, comment: 'Can I start at 9?' } });
    expect(calls()[0]!.headers['X-XSRF-TOKEN']).toBe('csrf-123');
    expect(calls()[1]!.body).toEqual({ agree: true });
  });

  it('accepts legacy reviewDates and 404 as empty', async () => {
    const { client } = bffWithFetch(() => json([{ id: 'p9', reviewDates: ['2026-12-01'], phases: [] }]));
    const [p] = await fetchMyRtwPlans(client);
    expect(p!.reviews).toEqual([{ id: undefined, dueDate: '2026-12-01', completedAt: undefined }]);
    expect(p!.nextReviewDate).toBe('2026-12-01');
    expect(await fetchMyRtwPlans(bffWithFetch(() => new Response(null, { status: 404 })).client)).toEqual([]);
  });
});

describe('job offers (GET /me/job-offers, POST respond)', () => {
  it('lists open offers first', async () => {
    const { client } = bffWithFetch(() => json(JOB_OFFERS));
    const offers = await fetchMyJobOffers(client);
    expect(offers.map((o) => o.id)).toEqual(['o1', 'o0']);
    expect(offers[0]).toMatchObject({ payBasis: 'PROTECTED_PAY', hoursPerWeek: 30, contact: { name: 'Hana HR', email: 'hr@example.com' } });
    expect(offers[0]!.restrictions[0]!.text).toBe('Lift max kg: 5');
  });

  it('accepts and declines with {accept, comment?}', async () => {
    const { client, calls } = bffWithFetch((url, init) => {
      const body = JSON.parse(String(init.body)) as { accept: boolean };
      return json({ ...JOB_OFFERS[0], status: body.accept ? 'ACCEPTED' : 'DECLINED', respondedAt: '2026-10-29T12:00:00Z' });
    });
    const accepted = await respondToOffer(client, 'o1', true, ' See you Monday ');
    expect(accepted).toMatchObject({ status: 'ACCEPTED', respondedAt: '2026-10-29T12:00:00Z' });
    const declined = await respondToOffer(client, 'o1', false);
    expect(declined?.status).toBe('DECLINED');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/job-offers/o1/respond', method: 'POST', body: { accept: true, comment: 'See you Monday' } });
    expect(calls()[1]!.body).toEqual({ accept: false });
    expect(calls()[1]!.headers['X-XSRF-TOKEN']).toBe('csrf-123');
  });

  it('treats a date-only respondBy as valid until the end of that day', () => {
    expect(respondByPassed('2026-10-31', new Date(2026, 9, 31, 22, 0))).toBe(false);
    expect(respondByPassed('2026-10-31', new Date(2026, 10, 1, 0, 1))).toBe(true);
    expect(respondByPassed('2026-10-29T10:00:00Z', new Date('2026-10-29T11:00:00Z'))).toBe(true);
    expect(respondByPassed(undefined)).toBe(false);
  });
});

describe('disclosure log and consent withdrawal', () => {
  it('lists disclosures newest first with a stable key', async () => {
    const { client, calls } = bffWithFetch(() => json(DISCLOSURES));
    const rows = await fetchMyDisclosures(client);
    expect(calls()[0]!.url).toBe('/bff/api/me/disclosures');
    expect(rows.map((r) => r.recipientName)).toEqual(['PhysioPlus Clinic', 'Mona Manager', 'Old Clinic']);
    expect(new Set(rows.map((r) => r.key)).size).toBe(3);
    expect(rows[2]!.status).toBe('REVOKED');
  });

  it('withdraws with DELETE /consents/{id} (CSRF) and the reloaded log shows the share revoked', async () => {
    let withdrawn = false;
    const { client, calls } = bffWithFetch((url, init) => {
      if (init.method === 'DELETE') {
        withdrawn = true;
        return new Response(null, { status: 204 });
      }
      if (url.endsWith('/consents/me')) return json(CONSENTS.map((c) => (c.id === 'k1' && withdrawn ? { ...c, status: 'WITHDRAWN', withdrawnAt: '2026-10-29T12:00:00Z' } : c)));
      return json(DISCLOSURES.map((d) => (d.consentId === 'k1' && withdrawn ? { ...d, status: 'REVOKED' } : d)));
    });
    expect((await fetchMyConsents(client)).filter((c) => c.status === 'GRANTED')).toHaveLength(1);
    await withdrawConsent(client, 'k1');
    const del = calls().find((c) => c.method === 'DELETE')!;
    expect(del.url).toBe('/bff/api/consents/k1');
    expect(del.headers['X-XSRF-TOKEN']).toBe('csrf-123');
    expect((await fetchMyConsents(client)).filter((c) => c.status === 'GRANTED')).toHaveLength(0);
    expect((await fetchMyDisclosures(client)).find((d) => d.consentId === 'k1')?.status).toBe('REVOKED');
  });

  it('propagates a failed withdrawal', async () => {
    await expect(withdrawConsent(bffWithFetch(() => problem(403, 'Forbidden')).client, 'k1')).rejects.toBeInstanceOf(ApiError);
  });
});

describe('crew declaration outcome', () => {
  it('reads nextSteps (strings or objects), ameContact (object or string) and acknowledgementDueBy', () => {
    expect(
      toDeclarationOutcome({
        caseRef: 'FLT-2026-000007',
        nextSteps: ['Do not report for duty', { text: 'The AME on call will phone you within 24 hours' }, 5, null],
        ameContact: { name: 'Dr. Aero', phone: '+971 4 000 0000', email: 'ame@example.com', hours: '24/7' },
        acknowledgementDueBy: '2026-10-30T10:00:00Z',
      }),
    ).toEqual({
      caseRef: 'FLT-2026-000007',
      nextSteps: ['Do not report for duty', 'The AME on call will phone you within 24 hours', '5'],
      ameContact: { name: 'Dr. Aero', phone: '+971 4 000 0000', email: 'ame@example.com', hours: '24/7' },
      acknowledgementDueBy: '2026-10-30T10:00:00Z',
    });
    expect(toDeclarationOutcome({ ameContact: 'AME desk, ext. 4242' }).ameContact).toEqual({ text: 'AME desk, ext. 4242' });
    expect(toDeclarationOutcome(undefined)).toEqual({ caseRef: undefined, nextSteps: [], ameContact: undefined, acknowledgementDueBy: undefined });
    expect(telHref('+971 (4) 000-0000')).toBe('tel:+97140000000');
  });
});

describe('home counts', () => {
  it('counts pending items and tolerates a failing endpoint', async () => {
    const { client } = bffWithFetch((url) => {
      if (url.endsWith('/me/documents')) return json(DOCUMENTS);
      if (url.endsWith('/me/rtw-plans')) return json(RTW_PLANS);
      return problem(503, 'down');
    });
    expect(await loadPortalCounts(client)).toEqual({ documents: 2, rtw: 1 });
  });
});
