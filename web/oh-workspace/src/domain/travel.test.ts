import { describe, expect, it } from 'vitest';
import type { Destination, TracedContact } from '../api/travel';
import {
  ackRateView,
  checkInText,
  clock,
  consultationBody,
  monitoringView,
  nextOpenTimer,
  overdueTimerCount,
  overlapText,
  recommendationsFor,
  recommendationView,
  reportDeadline,
  riskCounts,
  riskView,
  safeHttpUrl,
  serologyView,
  sortContacts,
  sortTimers,
  suggestedUntil,
  timerView,
  tracingKpi,
  uptakePct,
  uptakeTotals,
  uptakeView,
  validateAdvisory,
  validateCampaign,
  validateConsultation,
  validateExclusion,
  validateTrace,
  type ConsultationForm,
} from './travel';

const NOW = Date.parse('2026-10-30T10:00:00Z');
const at = (min: number) => new Date(NOW + min * 60_000).toISOString();

describe('exposure protocol timers', () => {
  it('done / overdue / within 30 min / later', () => {
    expect(timerView({ dueAt: at(-10), status: 'DONE' }, NOW)).toMatchObject({ tone: 'green', label: 'Done', done: true, overdue: false });
    expect(timerView({ dueAt: at(-65), status: 'PENDING' }, NOW)).toMatchObject({ tone: 'red', label: 'Overdue by 1 h 5 min', overdue: true, minutesLeft: -65 });
    expect(timerView({ dueAt: at(30), status: 'PENDING' }, NOW)).toMatchObject({ tone: 'amber', label: '30 min left', overdue: false });
    expect(timerView({ dueAt: at(31), status: 'PENDING' }, NOW)).toMatchObject({ tone: 'muted', label: '31 min left' });
    expect(timerView({ dueAt: at(125), status: 'PENDING' }, NOW).label).toBe('2 h 5 min left');
  });

  it('server OVERDUE wins even before the due time; bad dates do not crash', () => {
    expect(timerView({ dueAt: at(5), status: 'OVERDUE' }, NOW)).toMatchObject({ tone: 'red', label: 'Overdue', overdue: true });
    expect(timerView({ dueAt: 'nope', status: 'PENDING' }, NOW)).toMatchObject({ tone: 'muted', label: 'No due time' });
    expect(timerView({ dueAt: 'nope', status: 'OVERDUE' }, NOW).overdue).toBe(true);
  });

  it('a few seconds overdue already counts as overdue (≥ 1 min shown)', () => {
    const v = timerView({ dueAt: new Date(NOW - 5_000).toISOString(), status: 'PENDING' }, NOW);
    expect(v).toMatchObject({ overdue: true, label: 'Overdue by 1 min' });
  });

  it('live clock counts down and goes negative', () => {
    expect(clock(at(61), NOW)).toBe('01:01:00');
    expect(clock(new Date(NOW + 5_000).toISOString(), NOW)).toBe('00:00:05');
    expect(clock(at(-2), NOW)).toBe('−00:02:00');
    expect(clock('x', NOW)).toBe('–');
  });

  it('sorts open timers first by due time, finds the next and counts overdue', () => {
    const timers = [
      { code: 'C', label: 'Serology', dueAt: at(500), status: 'PENDING' as const },
      { code: 'A', label: 'Risk assessment', dueAt: at(-5), status: 'DONE' as const },
      { code: 'B', label: 'PEP decision', dueAt: at(-1), status: 'PENDING' as const },
    ];
    expect(sortTimers(timers).map((t) => t.code)).toEqual(['B', 'C', 'A']);
    expect(nextOpenTimer(timers)?.code).toBe('B');
    expect(overdueTimerCount(timers, NOW)).toBe(1);
    expect(nextOpenTimer([timers[1]!])).toBeUndefined();
  });

  it('serology schedule', () => {
    expect(serologyView({ dueOn: '2026-10-01', done: true }, '2026-10-30').label).toBe('Done');
    expect(serologyView({ dueOn: '2026-10-28', done: false }, '2026-10-30')).toEqual({ tone: 'red', label: 'Overdue 2 d' });
    expect(serologyView({ dueOn: '2026-10-30', done: false }, '2026-10-30')).toEqual({ tone: 'amber', label: 'Due today' });
    expect(serologyView({ dueOn: '2026-11-05', done: false }, '2026-10-30')).toEqual({ tone: 'amber', label: 'Due in 6 d' });
    expect(serologyView({ dueOn: '2026-12-11', done: false }, '2026-10-30').tone).toBe('muted');
  });

  it('only renders http(s) SDS links', () => {
    expect(safeHttpUrl('https://sds.example.com/a.pdf')).toBe('https://sds.example.com/a.pdf');
    expect(safeHttpUrl('javascript:alert(1)')).toBeUndefined();
    expect(safeHttpUrl('data:text/html,x')).toBeUndefined();
    expect(safeHttpUrl('not a url')).toBeUndefined();
    expect(safeHttpUrl(undefined)).toBeUndefined();
  });
});

describe('contact tracing display', () => {
  const contact = (over: Partial<TracedContact>): TracedContact => ({ id: 'x', employeeId: 'E1', displayName: 'A', contactType: 'SAME_DUTY', overlapMinutes: 10, riskLevel: 'LOW', ...over });

  it('risk badges never rely on colour alone and explain HIGH', () => {
    expect(riskView('HIGH')).toMatchObject({ tone: 'red', label: 'High risk' });
    expect(riskView('HIGH').title).toMatch(/15 minutes/);
    expect(riskView('MEDIUM')).toMatchObject({ tone: 'amber', label: 'Medium risk' });
    expect(riskView('LOW')).toMatchObject({ tone: 'muted', label: 'Low risk' });
    expect(riskView('BOGUS').label).toBe('Low risk');
  });

  it('sorts HIGH first, then longest overlap, then name; counts by level', () => {
    const list = [
      contact({ id: '1', displayName: 'Zed', riskLevel: 'LOW', overlapMinutes: 300 }),
      contact({ id: '2', displayName: 'Bea', riskLevel: 'HIGH', overlapMinutes: 20 }),
      contact({ id: '3', displayName: 'Al', riskLevel: 'HIGH', overlapMinutes: 600 }),
      contact({ id: '4', displayName: 'Cy', riskLevel: 'MEDIUM', overlapMinutes: 5 }),
      contact({ id: '5', displayName: 'Ann', riskLevel: 'HIGH', overlapMinutes: 20 }),
    ];
    expect(sortContacts(list).map((c) => c.id)).toEqual(['3', '5', '2', '4', '1']);
    expect(riskCounts(list)).toEqual({ HIGH: 3, MEDIUM: 1, LOW: 1 });
  });

  it('overlap text', () => {
    expect(overlapText(45)).toBe('45 min');
    expect(overlapText(125)).toBe('2 h 5 min');
    expect(overlapText(0)).toBe('–');
  });

  it('tracing KPI: contact list within 4 h of confirmation', () => {
    expect(tracingKpi(2.5)).toEqual({ status: 'MET', label: 'Contact list generated in 2.5 h' });
    expect(tracingKpi(4)).toMatchObject({ status: 'MET' });
    expect(tracingKpi(4.2)).toEqual({ status: 'MISSED', label: 'Contact list generated in 4.2 h' });
    expect(tracingKpi(0.5).label).toBe('Contact list generated in 30 min');
    expect(tracingKpi(undefined)).toEqual({ status: 'UNKNOWN', label: 'Not generated yet' });
  });

  it('monitoring / check-in state', () => {
    expect(monitoringView(contact({ symptomatic: true, notifiedAt: at(0) }))).toEqual({ tone: 'red', label: 'Symptomatic' });
    expect(monitoringView(contact({}))).toEqual({ tone: 'amber', label: 'Not notified' });
    expect(monitoringView(contact({ notifiedAt: at(0), monitoringUntil: '2026-10-01' }), '2026-10-30').label).toBe('Monitoring ended');
    expect(monitoringView(contact({ notifiedAt: at(0), monitoringUntil: '2026-11-05', lastCheckIn: at(0) }), '2026-10-30').label).toBe('Monitoring – checked in');
    expect(checkInText({ at: '2026-10-30T08:00:00Z', symptoms: ['SORE_THROAT'], temperatureC: 38.24 })).toEqual({ at: '2026-10-30T08:00:00Z', detail: 'sore throat, 38.2 °C' });
    expect(checkInText('2026-10-30T08:00:00Z')).toEqual({ at: '2026-10-30T08:00:00Z' });
    expect(checkInText(null)).toEqual({});
  });

  it('validates the tracing window and include options', () => {
    expect(validateTrace({ windowFrom: '2026-10-28T08:00', windowTo: '2026-10-30T08:00', include: ['SAME_DUTY'] })).toEqual([]);
    expect(validateTrace({ windowFrom: '', windowTo: '', include: [] }).map((e) => e.field)).toEqual(['trace-from', 'trace-to', 'trace-include']);
    expect(validateTrace({ windowFrom: '2026-10-30T08:00', windowTo: '2026-10-29T08:00', include: ['FACILITY'] })[0]?.message).toMatch(/end after/);
    expect(validateTrace({ windowFrom: '2026-08-01T08:00', windowTo: '2026-10-29T08:00', include: ['FACILITY'] })[0]?.message).toMatch(/31 days/);
  });

  it('validates exclusion decisions (return criteria required unless cleared) and suggests the end date', () => {
    expect(validateExclusion({ decision: 'EXCLUDED', from: '2026-10-30', until: '', returnCriteria: '48 h symptom-free' })).toEqual([]);
    expect(validateExclusion({ decision: 'ISOLATING', from: '2026-10-30', until: '2026-10-29', returnCriteria: ' ' }).map((e) => e.field)).toEqual(['excl-until', 'excl-criteria']);
    expect(validateExclusion({ decision: 'CLEARED', from: '2026-10-30', until: '', returnCriteria: '' })).toEqual([]);
    expect(suggestedUntil('2026-10-30', { exclusionDays: 2 })).toBe('2026-11-01');
    expect(suggestedUntil('2026-10-30', { exclusionDays: null })).toBe('');
  });
});

describe('vaccination campaign uptake', () => {
  it('computes uptake and suppresses small groups (server flag or eligible < k)', () => {
    expect(uptakeView({ key: 'GND', eligible: 40, vaccinated: 30, declined: 4, suppressed: false }, 75)).toMatchObject({ suppressed: false, pct: '75.0 %', pctValue: 75, tone: 'green', eligible: '40' });
    expect(uptakeView({ key: 'GND', eligible: 40, vaccinated: 24, declined: 4, suppressed: false }, 75)).toMatchObject({ pct: '60.0 %', tone: 'amber' });
    expect(uptakeView({ key: 'GND', eligible: 40, vaccinated: 10, suppressed: false }, 75).tone).toBe('red');
    expect(uptakeView({ key: 'GND', eligible: 40, vaccinated: 10, uptakePct: 26.3, suppressed: false }).pct).toBe('26.3 %');
    expect(uptakeView({ key: 'CAT', eligible: 12, vaccinated: 3, suppressed: true })).toMatchObject({ suppressed: true, eligible: '<5', vaccinated: '–', pct: 'Suppressed' });
    expect(uptakeView({ key: 'CAT', eligible: 3, vaccinated: 3, suppressed: false }).suppressed).toBe(true);
    expect(uptakeView({ key: 'CAT', eligible: null, suppressed: false }).suppressed).toBe(true);
    expect(uptakeView({ key: 'CAT', eligible: 8, vaccinated: 1, suppressed: false }, undefined, 10).eligible).toBe('<10');
  });

  it('uptake percentage is capped and undefined without an eligible group', () => {
    expect(uptakePct(1, 3)).toBe(33.3);
    expect(uptakePct(5, 4)).toBe(100);
    expect(uptakePct(0, 0)).toBeUndefined();
  });

  it('totals use reported rows only so suppressed cells cannot be back-calculated', () => {
    const t = uptakeTotals([
      { key: 'A', eligible: 50, vaccinated: 40, declined: 5, suppressed: false },
      { key: 'B', eligible: 30, vaccinated: 10, declined: 2, suppressed: false },
      { key: 'C', eligible: 4, vaccinated: 4, declined: 0, suppressed: false },
      { key: 'D', suppressed: true },
    ]);
    expect(t).toEqual({ eligible: 80, vaccinated: 50, declined: 7, pct: 62.5, suppressedRows: 2 });
  });

  it('validates the campaign form', () => {
    const ok = { name: 'Flu 2026', vaccineCode: 'FLU', eligibilityMode: 'all' as const, eligibilityCodes: '', startDate: '2026-11-01', endDate: '2026-12-15', siteCodes: 'LHR', targetUptakePct: '75' };
    expect(validateCampaign(ok)).toEqual([]);
    expect(validateCampaign({ ...ok, eligibilityMode: 'jobCodes', eligibilityCodes: ' ' }).map((e) => e.field)).toEqual(['vc-elig']);
    expect(validateCampaign({ ...ok, endDate: '2026-10-01', siteCodes: '', targetUptakePct: '120' }).map((e) => e.field)).toEqual(['vc-end', 'vc-sites', 'vc-target']);
    expect(validateCampaign({ ...ok, name: ' ', vaccineCode: '' }).map((e) => e.field)).toEqual(['vc-name', 'vc-vaccine']);
  });
});

describe('public-health deadlines and advisories', () => {
  it('hours left from the server, else from dueAt; submitted is done', () => {
    expect(reportDeadline({ hoursLeft: 30, status: 'DRAFT' }, NOW)).toMatchObject({ tone: 'green', label: '1 d 6 h left' });
    expect(reportDeadline({ hoursLeft: 5.5, status: 'DRAFT' }, NOW)).toMatchObject({ tone: 'amber', label: '5 h 30 min left' });
    expect(reportDeadline({ hoursLeft: -2, status: 'DRAFT' }, NOW)).toMatchObject({ tone: 'red', label: 'Overdue by 2 h' });
    expect(reportDeadline({ dueAt: at(90), status: 'DRAFT' }, NOW)).toMatchObject({ tone: 'amber', label: '1 h 30 min left' });
    expect(reportDeadline({ dueAt: at(90), status: 'DRAFT', submittedAt: at(0) }, NOW).label).toBe('Submitted');
    expect(reportDeadline({ status: 'DRAFT' }, NOW)).toEqual({ tone: 'muted', label: 'No deadline' });
  });

  it('acknowledgement rate', () => {
    expect(ackRateView({ targeted: 40, acknowledged: 38, ratePct: 95 })).toEqual({ tone: 'green', label: '95 % (38 of 40)' });
    expect(ackRateView({ targeted: 40, acknowledged: 20, ratePct: NaN })).toEqual({ tone: 'amber', label: '50 % (20 of 40)' });
    expect(ackRateView({ targeted: 10, acknowledged: 1, ratePct: 10 }).tone).toBe('red');
    expect(ackRateView({ targeted: 0, acknowledged: 0, ratePct: 0 }).label).toBe('No one targeted');
  });

  it('validates advisories: targeting, body length, validity', () => {
    const ok = { title: 'Measles in SIN', destinationCode: 'SG', baseCode: '', roles: '', severity: 'WARNING', body: 'Check MMR status.', validFrom: '2026-10-30', validUntil: '2026-11-30' };
    expect(validateAdvisory(ok)).toEqual([]);
    expect(validateAdvisory({ ...ok, destinationCode: '' }).map((e) => e.field)).toEqual(['adv-destination']);
    expect(validateAdvisory({ ...ok, baseCode: 'LHR' }).map((e) => e.field)).toEqual(['adv-roles']);
    expect(validateAdvisory({ ...ok, body: 'x'.repeat(4001) })[0]?.message).toMatch(/4000/);
    expect(validateAdvisory({ ...ok, validUntil: '2026-10-01' }).map((e) => e.field)).toEqual(['adv-until']);
  });
});

describe('travel consultation', () => {
  const dests: Destination[] = [
    { countryCode: 'SG', name: 'Singapore', risks: ['Dengue'], recommendedVaccines: ['HEPA', 'TYPHOID'], prophylaxis: [], advice: 'Bite avoidance', source: 'IN_HOUSE' },
    { countryCode: 'KE', name: 'Kenya', risks: ['Malaria'], recommendedVaccines: ['HEPA', 'YF'], prophylaxis: ['Atovaquone-proguanil'], advice: '…', source: 'IN_HOUSE' },
  ];

  it('recommendations vs E07 status (fallback calculation)', () => {
    const r = recommendationsFor(dests, ['SG', 'KE'], [{ vaccineCode: 'HEPA', vaccineName: 'Hepatitis A', status: 'COMPLETE' }, { vaccineCode: 'TYPHOID', vaccineName: 'Typhoid', status: 'OVERDUE', lastDoseOn: '2022-01-01' }]);
    expect(r.map((x) => [x.vaccineCode, x.status, x.destinations])).toEqual([['HEPA', 'COMPLETE', ['SG', 'KE']], ['TYPHOID', 'OVERDUE', ['SG']], ['YF', 'NOT_RECORDED', ['KE']]]);
    expect(recommendationsFor(dests, ['SG'], []).map((x) => x.vaccineCode)).toEqual(['HEPA', 'TYPHOID']);
  });

  it('recommendation badges', () => {
    expect(recommendationView({ status: 'COMPLETE' })).toMatchObject({ tone: 'green', label: 'Covered' });
    expect(recommendationView({ covered: true })).toMatchObject({ tone: 'green' });
    expect(recommendationView({ status: 'DUE' })).toMatchObject({ tone: 'amber', label: 'Booster due' });
    expect(recommendationView({ status: 'TITRE_NEEDED' }).label).toBe('Titre needed');
    expect(recommendationView({ status: 'OVERDUE' })).toMatchObject({ tone: 'red', action: 'Give dose' });
    expect(recommendationView({})).toMatchObject({ tone: 'red', label: 'Not recorded' });
  });

  const form = (over: Partial<ConsultationForm> = {}): ConsultationForm => ({
    employeeId: 'E1002', destinations: ['SG'], departDate: '2026-11-10', returnDate: '2026-11-20', purpose: 'LAYOVER',
    vaccines: [{ vaccineCode: 'hepa', doseNumber: '1', lot: '(17)991231(10)HA-77', site: 'LEFT_DELTOID', route: 'IM' }],
    prophylaxis: [{ drug: 'Atovaquone-proguanil', regimen: '1 tab daily', startDate: '2026-11-09' }], advice: ' ', ...over,
  });

  it('validates trip, vaccines (lot via the GS1 parser) and prophylaxis', () => {
    expect(validateConsultation(form(), '2026-10-30')).toEqual([]);
    expect(validateConsultation(form({ employeeId: '', destinations: [], returnDate: '2026-11-01' }), '2026-10-30').map((e) => e.field)).toEqual(['tc-employee', 'tc-destinations', 'tc-return']);
    const errs = validateConsultation(form({ vaccines: [{ vaccineCode: '', doseNumber: '0', lot: '(17)200101(10)OLD', site: '', route: 'IM' }], prophylaxis: [{ drug: '', regimen: '', startDate: '' }] }), '2026-10-30');
    expect(errs.map((e) => e.field)).toEqual(['tc-vac-0-code', 'tc-vac-0-lot', 'tc-vac-0-dose', 'tc-vac-0-site', 'tc-pro-0-drug', 'tc-pro-0-regimen', 'tc-pro-0-start']);
    expect(errs[1]?.message).toMatch(/expired/);
  });

  it('builds the contract body with the parsed lot number', () => {
    expect(consultationBody(form())).toEqual({
      employeeId: 'E1002',
      trip: { destinations: ['SG'], departDate: '2026-11-10', returnDate: '2026-11-20', purpose: 'LAYOVER' },
      vaccinesGiven: [{ vaccineCode: 'HEPA', doseNumber: 1, lotNumber: 'HA-77', site: 'LEFT_DELTOID', route: 'IM' }],
      prophylaxis: [{ drug: 'Atovaquone-proguanil', regimen: '1 tab daily', startDate: '2026-11-09' }],
    });
  });
});
