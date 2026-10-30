import { afterEach, describe, expect, it, vi } from 'vitest';
import { bffWithFetch, json } from '../test/fetch';
import {
  acknowledgeAdvisory,
  activeWearableConsent,
  canRespondToCampaign,
  checkInBody,
  checkInWindow,
  checkPhase,
  enrolWearable,
  fetchMyTravel,
  fetchTestingNotices,
  loadWellbeingCounts,
  reportWindow,
  respondToVaccination,
  sortAdvisories,
  submitCheckIn,
  toAdvisory,
  toCheckIn,
  toggleSymptom,
  toInvitation,
  toMyCampaigns,
  toMyTravel,
  toTestingNotice,
  toVaccinationCampaign,
  validateCheckIn,
  withdrawWearable,
  type CheckIn,
} from './prevention';

afterEach(() => vi.unstubAllGlobals());

const NOW = new Date(2026, 9, 30, 9, 0, 0); // 30 Oct 2026 09:00 local

describe('workplace check notices (E10 – no results ever)', () => {
  it('keeps only ref, neutral phase, report-by and site; reason, panels and verdicts are dropped', () => {
    const n = toTestingNotice({ ref: 'DAT-2026-000007', status: 'NOTIFIED', reportBy: '2026-10-30T11:30:00Z', siteCode: 'LHR-OH-1', reason: 'FOR_CAUSE', panels: ['DRUGS_10'], verdict: 'POSITIVE', results: [{ analyte: 'THC' }] })!;
    expect(n).toEqual({ ref: 'DAT-2026-000007', phase: 'REPORT', reportBy: '2026-10-30T11:30:00Z', siteCode: 'LHR-OH-1' });
    expect(JSON.stringify(n)).not.toMatch(/FOR_CAUSE|DRUGS|POSITIVE|THC/);
  });

  it('maps every status to a neutral phase – outcomes are indistinguishable', () => {
    for (const s of ['REQUIRED', 'NOTIFIED', 'DISPATCHED']) expect(checkPhase(s)).toBe('REPORT');
    for (const s of ['COLLECTED', 'IN_LAB', 'MRO_REVIEW', 'VERIFIED_NEGATIVE', 'VERIFIED_POSITIVE', 'REFUSAL']) expect(checkPhase(s)).toBe('DONE');
    expect(checkPhase('WINDOW_MISSED')).toBe('MISSED');
    expect(checkPhase('cancelled')).toBe('CANCELLED');
  });

  it('time window: minutes left until report-by, passed afterwards, none once done', () => {
    const n = toTestingNotice({ ref: 'R', status: 'DISPATCHED', reportBy: new Date(NOW.getTime() + 135 * 60_000).toISOString() })!;
    expect(reportWindow(n, NOW)).toEqual({ state: 'open', minutesLeft: 135 });
    expect(reportWindow(n, new Date(NOW.getTime() + 136 * 60_000))).toEqual({ state: 'passed' });
    expect(reportWindow({ ...n, phase: 'DONE' }, NOW)).toEqual({ state: 'none' });
  });

  it('open notices first', async () => {
    const { client, calls } = bffWithFetch(() => json([{ ref: 'A', status: 'VERIFIED_NEGATIVE' }, { ref: 'B', status: 'NOTIFIED', reportBy: '2026-10-30T12:00:00Z' }]));
    expect((await fetchTestingNotices(client)).map((n) => n.ref)).toEqual(['B', 'A']);
    expect(calls()[0]!.url).toBe('/bff/api/me/testing');
  });
});

describe('travel health (E11)', () => {
  const RAW = {
    consultations: [
      { id: 'c1', employeeId: 'E1', clinician: 'dr x', trip: { destinations: ['SG', { name: 'Thailand' }], departDate: '2026-11-03', returnDate: '2026-11-06', purpose: 'LAYOVER' }, vaccinesGiven: [{ vaccineCode: 'HEPA', lotNumber: 'L1' }], prophylaxis: [{ drug: 'Atovaquone', regimen: 'daily' }], certificateDocumentId: 'd9' },
    ],
    upcoming: [
      { dutyRef: 'WZ205', dutyDate: '2026-11-02', destinationCode: 'AE', destination: { name: 'United Arab Emirates', risks: ['Heat'], recommendedVaccines: ['Hep A'], advice: 'Hydrate' }, aircraft: 'G-WZAA' },
      { dutyRef: 'WZ101', dutyDate: '2026-11-01', destinationCode: 'SG' },
    ],
  };

  it('maps consultations and upcoming destinations; lot numbers and clinician names are not kept', () => {
    const t = toMyTravel(RAW);
    expect(t.consultations[0]).toMatchObject({ destinations: ['SG', 'Thailand'], purpose: 'LAYOVER', vaccines: ['HEPA'], prophylaxis: ['Atovaquone – daily'], certificateId: 'd9' });
    expect(JSON.stringify(t)).not.toMatch(/L1|dr x|G-WZAA|E1"/);
    expect(t.upcoming.map((u) => u.dutyRef)).toEqual(['WZ101', 'WZ205']);
    expect(t.upcoming[1]).toMatchObject({ name: 'United Arab Emirates', risks: ['Heat'], recommendedVaccines: ['Hep A'], advice: 'Hydrate' });
  });

  it('certificates fall back to travel-health certificates in my documents', async () => {
    const { client } = bffWithFetch((url) =>
      url.endsWith('/me/travel')
        ? json(RAW)
        : json([{ id: 'd9', type: 'TRAVEL_HEALTH_CERTIFICATE', title: 'Certificate', status: 'SIGNED' }, { id: 'd1', type: 'FITNESS_CERTIFICATE' }]),
    );
    expect((await fetchMyTravel(client)).certificates).toEqual([{ documentId: 'd9', title: 'Certificate', issuedAt: undefined }]);
  });

  it('advisories: expired hidden, unacknowledged and critical first; acknowledge posts', async () => {
    const a = [
      toAdvisory({ id: '1', title: 'Old', validUntil: '2026-10-01' })!,
      toAdvisory({ id: '2', title: 'Info', severity: 'INFO' })!,
      toAdvisory({ id: '3', title: 'Read', severity: 'CRITICAL', acknowledgedAt: '2026-10-29T08:00:00Z' })!,
      toAdvisory({ id: '4', title: 'Measles', severity: 'CRITICAL', targeted: 120, pending: ['E1'] })!,
    ];
    expect(sortAdvisories(a, NOW).map((x) => x.id)).toEqual(['4', '2', '3']);
    expect(Object.keys(a[3]!)).not.toContain('pending');
    const { client, calls } = bffWithFetch(() => json({}));
    await acknowledgeAdvisory(client, '4');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/advisories/4/acknowledge', method: 'POST' });
  });
});

describe('contact check-ins (E11 §5.2)', () => {
  const item = (dueOn?: string, submittedAt?: string): CheckIn => ({ id: 'k', dueOn, submittedAt });

  it('can be sent on its own day only', () => {
    expect(checkInWindow(item('2026-10-30'), NOW)).toBe('today');
    expect(checkInWindow(item('2026-10-31'), NOW)).toBe('upcoming');
    expect(checkInWindow(item('2026-10-29'), NOW)).toBe('missed');
    expect(checkInWindow(item('2026-10-30', '2026-10-30T07:00:00Z'), NOW)).toBe('done');
    expect(toCheckIn({ id: 'k', dueOn: '2026-10-30', conditionCode: 'MEASLES', indexCaseId: 'cd1', riskLevel: 'HIGH' })).toEqual({ id: 'k', dueOn: '2026-10-30', monitoringUntil: undefined, submittedAt: undefined });
  });

  it('"no symptoms" excludes the others; temperature optional and 34–43 °C', () => {
    expect(toggleSymptom(['FEVER', 'COUGH'], 'NONE')).toEqual(['NONE']);
    expect(toggleSymptom(['NONE'], 'RASH')).toEqual(['RASH']);
    expect(toggleSymptom(['RASH'], 'RASH')).toEqual([]);
    expect(validateCheckIn([], '')).toEqual({ symptoms: 'required' });
    expect(validateCheckIn(['NONE'], '45')).toEqual({ temperature: 'invalid' });
    expect(validateCheckIn(['FEVER'], '38,4')).toEqual({});
    expect(checkInBody(['COUGH', 'FEVER'], '38,44')).toEqual({ symptoms: ['FEVER', 'COUGH'], temperatureC: 38.4 });
    expect(checkInBody(['NONE'], '')).toEqual({ symptoms: ['NONE'] });
  });

  it('posts the check-in and reports whether it counted as symptomatic', async () => {
    const { client, calls } = bffWithFetch(() => json({ symptomatic: true }));
    expect(await submitCheckIn(client, 'k1', ['FEVER'], '38.5')).toBe(true);
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/check-ins/k1', method: 'POST', body: { symptoms: ['FEVER'], temperatureC: 38.5 } });
    const empty = bffWithFetch(() => new Response(null, { status: 204 }));
    expect(await submitCheckIn(empty.client, 'k1', ['NONE'], '')).toBe(false);
  });
});

describe('campaigns, vaccination and the wearable programme', () => {
  it('vaccination: respond while not ended and not yet answered', async () => {
    const c = toVaccinationCampaign({ id: 'v1', name: 'Flu 2026', endDate: '2026-11-30', eligibility: { all: true }, targetUptakePct: 60 })!;
    expect(Object.keys(c)).not.toContain('targetUptakePct');
    expect(canRespondToCampaign(c, NOW)).toBe(true);
    expect(canRespondToCampaign({ ...c, endDate: '2026-10-29' }, NOW)).toBe(false);
    expect(canRespondToCampaign(toVaccinationCampaign({ id: 'v1', name: 'Flu', myResponse: 'ACCEPTED' })!, NOW)).toBe(false);
    const { client, calls } = bffWithFetch(() => json({}));
    await respondToVaccination(client, 'v1', 'DECLINE');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/vaccination-campaigns/v1/response', method: 'POST', body: { response: 'DECLINE' } });
  });

  it('invitations drop budget / KPIs; the wearable offer follows the server flag, else the campaign risk', () => {
    const inv = toInvitation({ campaignId: 'p1', title: 'Move more', risk: 'METABOLIC', budget: { amount: '100.00' }, kpis: [{ metric: 'x' }], owners: ['promo1'] })!;
    expect(inv.wearable).toBe(true);
    expect(JSON.stringify(inv)).not.toMatch(/budget|promo1|kpis/);
    expect(toInvitation({ id: 'p2', title: 'Hearing', risk: 'HEARING' })!.wearable).toBe(false);
    expect(toInvitation({ id: 'p3', title: 'Sleep', risk: 'FATIGUE', wearable: false })!.wearable).toBe(false);
    const mine = toMyCampaigns({ invitations: [inv], screenings: [{ id: 's1', type: 'BIOMETRIC', siteCode: 'LHR', date: '2026-11-04' }], enrolments: [{ id: 'e1', campaignId: 'p1', deviceType: 'PHONE' }, { id: 'e0', campaignId: 'p9', status: 'WITHDRAWN' }] });
    expect(mine.screenings).toHaveLength(1);
    expect(mine.enrolments.map((e) => e.id)).toEqual(['e1']);
  });

  it('enrolment never happens without the explicit consent tick', async () => {
    const { client, calls } = bffWithFetch(() => json([]));
    await expect(enrolWearable(client, 'p1', 'PHONE', false)).rejects.toThrow();
    expect(calls()).toHaveLength(0);
  });

  it('records a WEARABLE_DATA consent first, then enrols with its id', async () => {
    const { client, calls } = bffWithFetch((url, init) => {
      if (url.endsWith('/consents/me')) return json([{ id: 'old', purpose: 'WEARABLE_DATA', status: 'WITHDRAWN' }]);
      if (url.endsWith('/consents') && init.method === 'POST') return json({ id: 'cons-9', purpose: 'WEARABLE_DATA', status: 'GRANTED' });
      return json({ id: 'e1' });
    });
    await enrolWearable(client, 'p1', 'SMARTWATCH', true);
    const c = calls();
    expect(c.map((x) => `${x.method} ${x.url}`)).toEqual(['GET /bff/api/consents/me', 'POST /bff/api/consents', 'POST /bff/api/me/wearables/enrolments']);
    expect(c[1]!.body).toEqual({ purpose: 'WEARABLE_DATA' });
    expect(c[2]!.body).toEqual({ campaignId: 'p1', deviceType: 'SMARTWATCH', consentId: 'cons-9' });
  });

  it('reuses an active WEARABLE_DATA consent; withdrawal deletes the enrolment', async () => {
    expect(activeWearableConsent([{ id: 'a', purpose: 'HEALTH_CAMPAIGNS', status: 'GRANTED' }, { id: 'w', purpose: 'WEARABLE_DATA', status: 'GRANTED' }])?.id).toBe('w');
    const { client, calls } = bffWithFetch((url) => (url.endsWith('/consents/me') ? json([{ id: 'w', purpose: 'WEARABLE_DATA', status: 'GRANTED' }]) : json({})));
    await enrolWearable(client, 'p1', 'PHONE', true);
    await withdrawWearable(client, 'e1');
    expect(calls().map((x) => `${x.method} ${x.url}`)).toEqual(['GET /bff/api/consents/me', 'POST /bff/api/me/wearables/enrolments', 'DELETE /bff/api/me/wearables/enrolments/e1']);
    expect(calls()[1]!.body).toMatchObject({ consentId: 'w' });
  });
});

describe('home counts', () => {
  it('counts open items; a failing source just hides its count', async () => {
    const { client } = bffWithFetch((url) => {
      if (url.endsWith('/me/cism/offers')) return json([{ id: 'o1' }, { id: 'o2', response: 'ACCEPTED' }]);
      if (url.endsWith('/me/testing')) return json([{ ref: 'R', status: 'NOTIFIED', reportBy: new Date(NOW.getTime() + 3_600_000).toISOString() }]);
      if (url.endsWith('/me/check-ins')) return json([{ id: 'k', dueOn: '2026-10-30' }]);
      if (url.endsWith('/me/advisories')) return new Response(null, { status: 500 });
      return json([{ id: 'v', name: 'Flu', endDate: '2026-12-01' }]);
    });
    expect(await loadWellbeingCounts(client, NOW)).toEqual({ support: 1, testing: 1, checkIns: 1, campaigns: 1 });
  });
});
