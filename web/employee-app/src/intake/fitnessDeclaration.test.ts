import { describe, expect, it } from 'vitest';
import { buildDeclaration, looksLikeCrew, todayIso, validateDeclaration } from './fitnessDeclaration';

describe('crew fitness declaration', () => {
  const base = { reason: 'FATIGUE' as const, unfitFrom: '2026-10-29', expectedUntil: '', details: '' };

  it('requires reason and start date', () => {
    expect(validateDeclaration({ ...base, reason: '', unfitFrom: '' })).toEqual({
      reason: 'Please choose a reason.',
      unfitFrom: 'Please enter the date from which you are unfit.',
    });
    expect(validateDeclaration(base)).toEqual({});
  });

  it('rejects an end date before the start date', () => {
    expect(validateDeclaration({ ...base, expectedUntil: '2026-10-28' }).expectedUntil).toMatch(/cannot be before/);
  });

  it('builds the request without empty optional fields', () => {
    expect(buildDeclaration(base)).toEqual({ reason: 'FATIGUE', unfitFrom: '2026-10-29' });
    expect(buildDeclaration({ ...base, expectedUntil: '2026-11-02', details: ' cold ' })).toEqual({
      reason: 'FATIGUE',
      unfitFrom: '2026-10-29',
      expectedUntil: '2026-11-02',
      details: 'cold',
    });
  });

  it('detects crew from job code, department or org scope', () => {
    expect(looksLikeCrew({ subject: 's', roles: [], jobCode: 'CABIN_CREW' })).toBe(true);
    expect(looksLikeCrew({ subject: 's', roles: [], orgScope: ['FLIGHTOPS'] })).toBe(true);
    expect(looksLikeCrew({ subject: 's', roles: [], jobCode: 'CATERING_ASSOC', orgScope: 'CATERING' })).toBe(false);
    expect(looksLikeCrew(undefined)).toBe(false);
  });

  it('formats today as a local date', () => {
    expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});
