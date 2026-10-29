import { describe, expect, it } from 'vitest';
import {
  analyticsExportUrl,
  analyticsUnitsPath,
  buildHrStage,
  emptyTeamAbsenceFilter,
  filterTeamAbsence,
  hrStageDraftFor,
  hrStagePath,
  isValidMonth,
  previousMonth,
  SETTABLE_HR_STAGES,
  summariseTeamAbsence,
  suppressedText,
  toLtaRows,
  toTeamAbsence,
  toUnitAnalytics,
  toWorkspaceCertificates,
  validateAnalyticsQuery,
  validateHrStage,
} from './absence';

const team = toTeamAbsence([
  { employeeId: 'E1001', displayName: 'Alice Agent', episodes12m: 1, ohEngaged: false, certificateValidity: 'VALID' },
  {
    employeeId: 'E1004',
    displayName: 'Rami Ramp',
    openEpisode: { startDate: '2026-10-01', days: 28 },
    episodes12m: 3,
    ohEngaged: true,
    certificateValidity: 'PENDING',
    reasonCategory: 'MSK',
    absenceType: 'SICKNESS',
  },
  { employeeId: 'E1009', displayName: 'Cole Cleaner', openEpisode: { startDate: '2026-10-25', days: '4' }, episodes12m: 0, certificateValidity: 'SUSPICIOUS' },
  { displayName: 'no id' },
]);

describe('team absence (E06 §3.1)', () => {
  it('keeps whitelisted status fields only – no reason category, type or non-HR certificate states', () => {
    expect(team).toHaveLength(3);
    expect(JSON.stringify(team)).not.toMatch(/MSK|SICKNESS|reasonCategory|SUSPICIOUS/);
    expect(team[1]).toEqual({
      employeeId: 'E1004',
      displayName: 'Rami Ramp',
      openEpisode: { startDate: '2026-10-01', days: 28 },
      episodes12m: 3,
      ohEngaged: true,
      certificateValidity: 'PENDING',
    });
    expect(team[2]!.openEpisode?.days).toBe(4);
    expect(team[2]!.ohEngaged).toBe(false);
    expect(team[2]!.certificateValidity).toBeUndefined();
  });

  it('sorts currently absent first (longest first) and filters', () => {
    expect(filterTeamAbsence(team, emptyTeamAbsenceFilter()).map((r) => r.employeeId)).toEqual(['E1004', 'E1009', 'E1001']);
    expect(filterTeamAbsence(team, { q: '', show: 'OPEN' }).map((r) => r.employeeId)).toEqual(['E1004', 'E1009']);
    expect(filterTeamAbsence(team, { q: '', show: 'OH' }).map((r) => r.employeeId)).toEqual(['E1004']);
    expect(filterTeamAbsence(team, { q: 'alice', show: '' }).map((r) => r.employeeId)).toEqual(['E1001']);
    expect(filterTeamAbsence(team, { q: 'e1009', show: '' })).toHaveLength(1);
  });

  it('summarises the team', () => {
    expect(summariseTeamAbsence(team)).toEqual({ total: 3, absentNow: 2, ohEngaged: 1 });
  });
});

describe('certificates (E06 §3.3)', () => {
  it('maps validity only and puts escalations first', () => {
    const certs = toWorkspaceCertificates([
      { id: 'c1', employeeId: 'E1001', displayName: 'Alice', fromDate: '2026-10-01', toDate: '2026-10-07', validity: 'VALID', escalated: false, issuerName: 'Dr SECRET' },
      { id: 'c2', employeeId: 'E1004', displayName: 'Rami', fromDate: '2026-09-01', toDate: '2026-09-07', validity: 'INVALID', escalated: true, alterationFlags: ['UNREADABLE'] },
      { id: 'c3', validity: 'VERIFIED_FRAUDULENT' },
    ]);
    expect(certs.map((c) => c.id)).toEqual(['c2', 'c1', 'c3']);
    expect(certs[2]!.validity).toBeUndefined();
    expect(JSON.stringify(certs)).not.toMatch(/SECRET|UNREADABLE|FRAUD/);
  });
});

describe('long-term absence (E06 §3.4)', () => {
  const rows = toLtaRows([
    { caseId: 'k1', caseRef: 'OH-2026-0100', employeeId: 'E1004', displayName: 'Rami', absenceStart: '2026-08-01', weeksAbsent: 12, hrStage: 'INFORMAL_REVIEW', nextMilestone: { week: 12, type: 'HR_REVIEW', dueDate: '2026-10-24' }, adviceReleased: 1, unit: 'GND', overdueCount: 2 },
    { caseId: 'k2', displayName: 'Ellie', weeksAbsent: 30, hrStage: 'BOGUS' },
  ]);

  it('maps the status-only pathway and sorts by weeks absent', () => {
    expect(rows.map((r) => r.caseId)).toEqual(['k2', 'k1']);
    expect(rows[0]).toMatchObject({ hrStage: 'NONE', adviceReleased: 0, nextMilestone: undefined });
    expect(rows[1]).toEqual({
      caseId: 'k1',
      caseRef: 'OH-2026-0100',
      employeeId: 'E1004',
      displayName: 'Rami',
      absenceStart: '2026-08-01',
      weeksAbsent: 12,
      hrStage: 'INFORMAL_REVIEW',
      nextMilestone: { week: 12, type: 'HR_REVIEW', dueDate: '2026-10-24' },
      adviceReleased: 1,
    });
  });

  it('offers the stages from INFORMAL_REVIEW to CLOSED', () => {
    expect(SETTABLE_HR_STAGES).toEqual(['INFORMAL_REVIEW', 'FORMAL_REVIEW_1', 'FORMAL_REVIEW_2', 'CAPABILITY_HEARING', 'CLOSED']);
  });

  it('validates and builds the HR-stage update', () => {
    expect(hrStageDraftFor(rows[0]!)).toEqual({ stage: '', note: '' });
    expect(hrStageDraftFor(rows[1]!)).toEqual({ stage: 'INFORMAL_REVIEW', note: '' });
    expect(validateHrStage({ stage: '', note: '' }, 'NONE')).toHaveProperty('stage');
    expect(validateHrStage({ stage: 'INFORMAL_REVIEW', note: '' }, 'INFORMAL_REVIEW')).toHaveProperty('stage');
    expect(validateHrStage({ stage: 'INFORMAL_REVIEW', note: 'Meeting held' }, 'INFORMAL_REVIEW')).toEqual({});
    expect(validateHrStage({ stage: 'FORMAL_REVIEW_1', note: 'x'.repeat(1001) }, 'INFORMAL_REVIEW')).toHaveProperty('note');
    expect(buildHrStage({ stage: 'FORMAL_REVIEW_1', note: '  ' })).toEqual({ stage: 'FORMAL_REVIEW_1' });
    expect(buildHrStage({ stage: 'CLOSED', note: ' Returned to work ' })).toEqual({ stage: 'CLOSED', note: 'Returned to work' });
    expect(hrStagePath('k/1')).toBe('/workspace/absence/lta/k%2F1/hr-stage');
  });
});

describe('absence analytics (E06 §3.6)', () => {
  it('drops categories, suppresses small units and never keeps their counts', () => {
    const a = toUnitAnalytics([
      { key: 'GND', label: 'Ground ops', headcount: 40, episodes: 12, daysLost: 90, absenceRatePct: 3.4, frequencyRate: 0.3, avgDurationDays: 7.5, bradfordMean: 88, categories: { MSK: 5 }, suppressed: false },
      { key: 'CAT', label: 'Catering', suppressed: true },
      { key: 'IT', label: 'IT', headcount: 3, episodes: 2, suppressed: false },
    ]);
    expect(a.minGroupSize).toBe(5);
    expect(a.rows.map((r) => r.key)).toEqual(['CAT', 'GND', 'IT']);
    expect(a.rows[0]).toEqual({ key: 'CAT', label: 'Catering', suppressed: true });
    expect(a.rows[2]).toEqual({ key: 'IT', label: 'IT', suppressed: true });
    expect(a.rows[1]).toMatchObject({ headcount: 40, episodes: 12, daysLost: 90, absenceRatePct: 3.4 });
    expect(JSON.stringify(a)).not.toMatch(/MSK|categories/);
  });

  it('honours a minGroupSize from a page object', () => {
    const a = toUnitAnalytics({ minGroupSize: 10, rows: [{ key: 'A', headcount: 8 }] });
    expect(a).toEqual({ minGroupSize: 10, rows: [{ key: 'A', label: 'A', suppressed: true }] });
    expect(suppressedText(10)).toBe('fewer than 10 – suppressed');
  });

  it('builds the query path and validates the period', () => {
    expect(analyticsUnitsPath({ groupBy: 'DEPARTMENT', from: '', to: '' })).toBe('/absence/analytics/units?groupBy=DEPARTMENT');
    expect(analyticsUnitsPath({ groupBy: 'JOB_FAMILY', from: '2026-01-01', to: '2026-06-30' })).toBe(
      '/absence/analytics/units?groupBy=JOB_FAMILY&from=2026-01-01&to=2026-06-30',
    );
    expect(validateAnalyticsQuery({ groupBy: 'SUBSIDIARY', from: '2026-06-01', to: '2026-01-01' })).toHaveProperty('to');
    expect(validateAnalyticsQuery({ groupBy: 'SUBSIDIARY', from: '', to: '' })).toEqual({});
  });

  it('exports the monthly CSV pack through the BFF', () => {
    expect(previousMonth(new Date(2026, 0, 15))).toBe('2025-12');
    expect(previousMonth(new Date(2026, 9, 29))).toBe('2026-09');
    expect(isValidMonth('2026-09')).toBe(true);
    expect(isValidMonth('2026-13')).toBe(false);
    expect(analyticsExportUrl('2026-09')).toBe('/bff/api/absence/analytics/export?month=2026-09');
  });
});
