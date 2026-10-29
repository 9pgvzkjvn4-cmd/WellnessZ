import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import { bffWithFetch, json, problem } from '../test/fetch';
import {
  adviceDecisionBody,
  bookAppointment,
  bookPreEmploymentExam,
  canChange,
  canCheckIn,
  canJoinTelehealth,
  cancelAppointment,
  certificateStatus,
  checkInAppointment,
  decideAdviceReport,
  fetchMyAdviceReports,
  fetchMyEpisodes,
  fetchMyPreEmployment,
  fetchMySurveillance,
  givePreEmploymentConsent,
  loadOhCounts,
  preEmploymentSteps,
  requestVaccinationCertificate,
  safeHttpUrl,
  slotsByDay,
  slotsPath,
  sortAppointments,
  submitQuestionnaire,
  telehealthLink,
  toAdviceReport,
  toAppointment,
  toBookableTypes,
  toCertificate,
  toEpisode,
  toMySurveillance,
  toPreEmployment,
  toQuestions,
  toSlots,
  uploadCertificate,
  type MyAppointment,
} from './occupational';

afterEach(() => vi.unstubAllGlobals());

const NOW = new Date('2026-10-29T09:00:00Z');
const appt = (over: Record<string, unknown> = {}) =>
  toAppointment({ id: 'a1', employeeId: 'E1004', appointmentType: 'VACCINATION', appointmentTypeName: 'Vaccination', siteCode: 'HQ-MC', startsAt: '2026-10-29T09:30:00Z', endsAt: '2026-10-29T09:40:00Z', status: 'BOOKED', telehealth: false, version: 2, adminNote: 'secret', clinicianUsername: 'nurse1', ...over })!;

describe('appointments (E14 §5.2)', () => {
  it('keeps display fields only and sorts upcoming before past', () => {
    const a = appt();
    expect(a).toEqual({ id: 'a1', type: 'VACCINATION', typeName: 'Vaccination', siteCode: 'HQ-MC', startsAt: '2026-10-29T09:30:00Z', endsAt: '2026-10-29T09:40:00Z', status: 'BOOKED', telehealth: false, ticket: undefined, checkedInAt: undefined });
    expect(JSON.stringify(a)).not.toContain('secret');
    expect(toAppointment({ id: 'x' })).toBeUndefined();
    const list = sortAppointments([appt({ id: 'past', startsAt: '2026-10-01T09:00:00Z', endsAt: '2026-10-01T09:10:00Z', status: 'ATTENDED' }), appt({ id: 'later', startsAt: '2026-11-05T09:00:00Z', endsAt: '2026-11-05T09:10:00Z' }), appt(), appt({ id: 'cx', status: 'CANCELLED', startsAt: '2026-11-01T09:00:00Z' })], NOW);
    expect(list.map((x) => x.id)).toEqual(['a1', 'later', 'cx', 'past']);
  });

  it('applies check-in (60 min), change and telehealth (15 min) windows', () => {
    const a = appt();
    expect(canCheckIn(a, NOW)).toBe(true); // 30 min before
    expect(canCheckIn(a, new Date('2026-10-29T08:29:00Z'))).toBe(false);
    expect(canCheckIn(a, new Date('2026-10-29T09:41:00Z'))).toBe(false);
    expect(canCheckIn({ ...a, status: 'CHECKED_IN' }, NOW)).toBe(false);
    expect(canChange(a, NOW)).toBe(true);
    expect(canChange(a, new Date('2026-10-29T09:31:00Z'))).toBe(false);
    const tele: MyAppointment = { ...a, telehealth: true };
    expect(canJoinTelehealth(tele, NOW)).toBe(false);
    expect(canJoinTelehealth(tele, new Date('2026-10-29T09:16:00Z'))).toBe(true);
    expect(canJoinTelehealth(a, new Date('2026-10-29T09:16:00Z'))).toBe(false);
  });

  it('lists self-bookable active types only and filters slots to the 24 h – 60 day window', () => {
    expect(toBookableTypes([{ code: 'VACCINATION', name: 'Vaccination', selfBookable: true, active: true, durationMinutes: 10 }, { code: 'AUDIOMETRY', selfBookable: false }, { code: 'OLD', selfBookable: true, active: false }]).map((t) => t.code)).toEqual(['VACCINATION']);
    const slots = toSlots(
      [
        { startsAt: '2026-10-29T15:00:00Z', clinicianUsername: 'n1' }, // < 24 h
        { startsAt: '2026-10-31T10:00:00Z', clinicianUsername: 'n1' },
        { startsAt: '2026-10-31T10:00:00Z', clinicianUsername: 'n2' }, // duplicate time
        { startsAt: '2026-10-30T10:00:00Z' },
        { startsAt: '2027-02-01T10:00:00Z' }, // > 60 days
      ],
      NOW,
    );
    expect(slots.map((s) => s.startsAt)).toEqual(['2026-10-30T10:00:00Z', '2026-10-31T10:00:00Z']);
    expect(JSON.stringify(slots)).not.toContain('n1');
    expect(slotsByDay(slots)).toHaveLength(2);
    expect(slotsPath('VACCINATION', 'HQ-MC', '2026-10-30', '2026-12-28')).toBe('/me/appointments/slots?type=VACCINATION&siteCode=HQ-MC&from=2026-10-30&to=2026-12-28');
  });

  it('books, cancels, checks in and gets a safe telehealth link', async () => {
    const { client, calls } = bffWithFetch((url) => {
      if (url.endsWith('/check-in')) return json({ ticket: 'A012' });
      if (url.endsWith('/telehealth')) return json({ joinUrl: 'javascript:alert(1)' });
      if (url.endsWith('/cancel')) return new Response(null, { status: 204 });
      return json({ id: 'a9', startsAt: '2026-11-02T09:00:00Z', status: 'BOOKED', appointmentType: 'VACCINATION' });
    });
    expect((await bookAppointment(client, 'VACCINATION', 'HQ-MC', '2026-11-02T09:00:00Z'))?.id).toBe('a9');
    await cancelAppointment(client, 'a9', '  clash with shift ');
    await cancelAppointment(client, 'a9');
    expect(await checkInAppointment(client, 'a9')).toBe('A012');
    expect(await telehealthLink(client, 'a9')).toBeUndefined();
    const c = calls();
    expect(c[0]).toMatchObject({ url: '/bff/api/me/appointments', method: 'POST', body: { appointmentType: 'VACCINATION', siteCode: 'HQ-MC', startsAt: '2026-11-02T09:00:00Z' } });
    expect(c[1]).toMatchObject({ url: '/bff/api/me/appointments/a9/cancel', body: { reason: 'clash with shift' } });
    expect(c[2]?.body).toEqual({});
    expect(c[3]?.url).toBe('/bff/api/me/appointments/a9/check-in');
    expect(c[4]?.url).toBe('/bff/api/scheduling/appointments/a9/telehealth');
    expect(safeHttpUrl('https://video.example/r/1')).toBe('https://video.example/r/1');
  });
});

describe('absence (E06 §3.1, §3.3)', () => {
  it('never keeps the reason category and folds certificate states to validity', () => {
    const e = toEpisode({ id: 'ep1', startDate: '2026-10-01', endDate: '2026-10-05', days: 5, absenceType: 'SICKNESS', reasonCategory: 'MENTAL_HEALTH', certified: true, certificateValidity: 'VALID', open: false })!;
    expect(JSON.stringify(e)).not.toContain('MENTAL');
    expect(e).toMatchObject({ days: 5, open: false, certified: true });
    expect(toEpisode({ id: 'ep2', startDate: '2026-10-20' })?.open).toBe(true);
    expect(certificateStatus('SUSPICIOUS')).toBe('RECEIVED');
    expect(certificateStatus('VERIFIED_GENUINE')).toBe('VALID');
    expect(certificateStatus('VERIFIED_FRAUDULENT')).toBe('INVALID');
    expect(toCertificate({ id: 'c1', fromDate: '2026-10-01', toDate: '2026-10-05', status: 'VALID', extracted: { issuerName: 'Dr X' } })).toEqual({ id: 'c1', fromDate: '2026-10-01', toDate: '2026-10-05', status: 'VALID', receivedAt: undefined });
  });

  it('lists episodes newest first; 404 is empty', async () => {
    const ok = bffWithFetch(() => json([{ id: 'a', startDate: '2026-01-01' }, { id: 'b', startDate: '2026-09-01' }]));
    expect((await fetchMyEpisodes(ok.client)).map((x) => x.id)).toEqual(['b', 'a']);
    const nf = bffWithFetch(() => problem(404, 'Not Found'));
    expect(await fetchMyEpisodes(nf.client)).toEqual([]);
  });

  it('uploads the certificate as multipart file + dates + episode', async () => {
    const { client, fetchMock } = bffWithFetch(() => json({ id: 'c9', status: 'RECEIVED', fromDate: '2026-10-27', toDate: '2026-10-31' }));
    const c = await uploadCertificate(client, new Blob(['%PDF'], { type: 'application/pdf' }), 'note.pdf', '2026-10-27', '2026-10-31', 'ep1');
    expect(c?.status).toBe('RECEIVED');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/bff/api/me/absence/certificates');
    const fd = (init as RequestInit).body as FormData;
    expect(fd.get('fromDate')).toBe('2026-10-27');
    expect(fd.get('toDate')).toBe('2026-10-31');
    expect(fd.get('episodeId')).toBe('ep1');
    expect((fd.get('file') as File).name).toBe('note.pdf');
  });
});

describe('advice reports (E06 §3.5)', () => {
  const raw = {
    id: 'r1', requestRef: 'ADV-2026-000001', requestedByRole: 'MANAGER', status: 'AWAITING_EMPLOYEE', createdAt: '2026-10-28T10:00:00Z',
    report: { fitness: 'FIT_WITH_ADJUSTMENTS', adjustments: 'Phased return over 4 weeks', restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: '10' }], prognosis: 'RTW_WITHIN_4_WEEKS', answers: { EXPECTED_RTW: 'Within 4 weeks', FIT_FOR_ROLE: '' }, diagnosis: 'never' },
  };

  it('maps the report the requester would receive (no extra fields)', () => {
    const r = toAdviceReport(raw)!;
    expect(r.report?.adjustments).toEqual([{ text: 'Phased return over 4 weeks' }]);
    expect(r.report?.restrictions[0]?.text).toBe('No lifting above 10 kg');
    expect(r.report?.answers).toEqual([{ code: 'EXPECTED_RTW', text: 'Within 4 weeks' }]);
    expect(JSON.stringify(r)).not.toContain('never');
  });

  it('puts reports awaiting my decision first', async () => {
    const { client } = bffWithFetch(() => json([{ ...raw, id: 'old', status: 'RELEASED', createdAt: '2026-10-29T00:00:00Z' }, raw]));
    expect((await fetchMyAdviceReports(client)).map((r) => r.id)).toEqual(['r1', 'old']);
  });

  it('sends the decision; decline reason only when declining', async () => {
    expect(adviceDecisionBody({ decision: 'CONSENT', comment: ' ok ', declineReason: 'ignored' })).toEqual({ decision: 'CONSENT', comment: 'ok' });
    expect(adviceDecisionBody({ decision: 'DECLINE', comment: '', declineReason: ' wrong dates ' })).toEqual({ decision: 'DECLINE', declineReason: 'wrong dates' });
    const { client, calls } = bffWithFetch(() => json({ ...raw, status: 'RELEASED' }));
    expect((await decideAdviceReport(client, 'r1', { decision: 'CONSENT' }))?.status).toBe('RELEASED');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/advice-reports/r1/decision', method: 'POST', body: { decision: 'CONSENT' } });
  });

  it('surfaces a clinical-content rejection', async () => {
    const { client } = bffWithFetch(() => new Response(JSON.stringify({ status: 422, code: 'clinical_content', title: 'Clinical content not allowed' }), { status: 422, headers: { 'Content-Type': 'application/problem+json' } }));
    await expect(decideAdviceReport(client, 'r1', { decision: 'CONSENT', comment: 'my depression' })).rejects.toBeInstanceOf(ApiError);
  });
});

describe('surveillance & immunisations (E07 §4.2, §4.5)', () => {
  it('accepts an array or an object body, orders overdue first', () => {
    const arr = toMySurveillance([{ programmeTitle: 'Hearing conservation', nextDueOn: '2027-01-01', status: 'CURRENT' }, { programmeTitle: 'Respiratory', nextDueOn: '2026-10-01', status: 'OVERDUE' }]);
    expect(arr.programmes.map((p) => p.title)).toEqual(['Respiratory', 'Hearing conservation']);
    const obj = toMySurveillance({
      programmes: [{ programmeTitle: 'Pilot class 1', nextDueOn: '2026-12-01', status: 'DUE', appointment: { id: 'a1', startsAt: '2026-11-20T09:00:00Z', siteCode: 'HQ-MC' } }],
      crewCertificate: { certificateType: 'CLASS1', validUntil: '2026-12-31', status: 'expiring' },
      immunisations: [{ vaccineCode: 'INFLUENZA', vaccineName: 'Influenza', status: 'DUE' }],
    });
    expect(obj.programmes[0]?.appointment).toEqual({ startsAt: '2026-11-20T09:00:00Z', siteCode: 'HQ-MC' });
    expect(obj.crewCertificate).toEqual({ certificateType: 'CLASS1', validUntil: '2026-12-31', status: 'EXPIRING' });
    expect(obj.immunisations[0]?.vaccineName).toBe('Influenza');
  });

  it('404 is an empty record; the certificate request returns the document id', async () => {
    expect(await fetchMySurveillance(bffWithFetch(() => problem(404, 'Not Found')).client)).toEqual({ programmes: [], immunisations: [] });
    const { client, calls } = bffWithFetch(() => json({ id: 'doc-77', type: 'VACCINATION_CERTIFICATE' }));
    expect(await requestVaccinationCertificate(client)).toBe('doc-77');
    expect(calls()[0]).toMatchObject({ url: '/bff/api/me/immunisations/certificate', method: 'POST' });
  });
});

describe('pre-employment (E07 §4.3)', () => {
  it('maps the record, questions and step state', () => {
    const r = toPreEmployment({ offerRef: 'OFF-1', jobTitle: 'Ramp agent', proposedStart: '2026-12-01', status: 'invited', questionnaireCompleted: true, consentGiven: false, documents: [{ id: 'd1', title: 'Vaccination record' }, { title: 'no id' }] })!;
    expect(r.status).toBe('INVITED');
    expect(r.documents).toEqual([{ id: 'd1', title: 'Vaccination record' }]);
    expect(preEmploymentSteps(r)).toEqual({ questionnaire: true, consent: false, documents: true, book: false });
    expect(toQuestions([{ code: 'Q1', text: 'Any back problems?', type: 'YES_NO', required: true }, { code: 'Q2', text: 'Date of last tetanus', type: 'date' }, { code: 'Q3', text: 'Other', type: 'WEIRD' }, { code: 'bad' }])).toEqual([
      { code: 'Q1', text: 'Any back problems?', type: 'YES_NO', required: true },
      { code: 'Q2', text: 'Date of last tetanus', type: 'DATE', required: false },
      { code: 'Q3', text: 'Other', type: 'TEXT', required: false },
    ]);
  });

  it('sends answers, consent purposes and the booking request', async () => {
    const { client, calls } = bffWithFetch((url) => (url.endsWith('/book') ? json({ id: 'a5', startsAt: '2026-11-10T09:00:00Z', status: 'BOOKED' }) : new Response(null, { status: 204 })));
    await submitQuestionnaire(client, { Q1: 'NO', Q2: ' ', Q3: ' text ' });
    await givePreEmploymentConsent(client);
    expect((await bookPreEmploymentExam(client, 'HQ-MC'))?.id).toBe('a5');
    await bookPreEmploymentExam(client);
    const c = calls();
    expect(c[0]).toMatchObject({ url: '/bff/api/me/pre-employment/questionnaire', method: 'PUT', body: { answers: { Q1: 'NO', Q3: 'text' } } });
    expect(c[1]).toMatchObject({ url: '/bff/api/me/pre-employment/consent', body: { consentGiven: true, purposes: ['PRE_EMPLOYMENT_ASSESSMENT', 'HR_STATUS_FEED'] } });
    expect(c[2]?.body).toEqual({ siteCode: 'HQ-MC' });
    expect(c[3]?.body).toEqual({});
  });

  it('no record (404) is undefined', async () => {
    expect(await fetchMyPreEmployment(bffWithFetch(() => problem(404, 'Not Found')).client)).toBeUndefined();
  });
});

describe('home counts', () => {
  it('counts upcoming appointments, advice awaiting decision and due programmes; failures hide a count', async () => {
    const { client } = bffWithFetch((url) => {
      if (url.endsWith('/me/appointments')) return json([{ id: 'a', startsAt: '2026-11-01T09:00:00Z', endsAt: '2026-11-01T09:10:00Z', status: 'BOOKED' }, { id: 'b', startsAt: '2026-10-01T09:00:00Z', status: 'ATTENDED' }]);
      if (url.endsWith('/me/advice-reports')) return problem(500, 'boom');
      return json([{ programmeTitle: 'X', status: 'OVERDUE' }, { programmeTitle: 'Y', status: 'CURRENT' }]);
    });
    expect(await loadOhCounts(client, NOW)).toEqual({ appointments: 1, surveillance: 1 });
  });
});
