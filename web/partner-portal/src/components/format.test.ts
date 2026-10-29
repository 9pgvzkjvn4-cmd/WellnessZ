import { describe, expect, it } from 'vitest';
import { compareAmounts, formatDay, formatMoney, humanise, isoToLocalInput, isValidDate, localDate, localInputToIso, parseAmount, toMoney } from './format';

describe('money', () => {
  it('formats contract money with the currency symbol', () => {
    expect(formatMoney({ amount: '1234.50', currency: 'GBP' }, 'en-GB')).toBe('£1,234.50');
    expect(formatMoney({ amount: '850', currency: 'USD' }, 'en-US')).toBe('$850.00');
    expect(formatMoney({ amount: '10.00', currency: 'AED' }, 'en-GB')).toMatch(/AED\s?10\.00/);
    expect(formatMoney(undefined)).toBe('–');
  });

  it('reads money tolerantly and rejects malformed values', () => {
    expect(toMoney({ amount: '1234.50', currency: 'GBP' })).toEqual({ amount: '1234.50', currency: 'GBP' });
    expect(toMoney({ amount: 99.5, currency: 'EUR' })).toEqual({ amount: '99.5', currency: 'EUR' });
    expect(toMoney({ amount: 'abc', currency: 'GBP' })).toBeUndefined();
    expect(toMoney({ amount: '1', currency: 'gbp' })).toBeUndefined();
    expect(toMoney('1234.50')).toBeUndefined();
  });

  it('parses amount input to a two-decimal string', () => {
    expect(parseAmount('1250')).toBe('1250.00');
    expect(parseAmount(' 12.5 ')).toBe('12.50');
    expect(parseAmount('1,250.75')).toBe('1250.75');
    expect(parseAmount('007.10')).toBe('7.10');
    for (const bad of ['', '0', '0.00', '-5', '1.234', '12,5', 'abc', '1e3']) expect(parseAmount(bad)).toBeUndefined();
  });

  it('compares decimal strings exactly', () => {
    expect(compareAmounts('849.99', '850.00')).toBe(-1);
    expect(compareAmounts('850', '850.00')).toBe(0);
    expect(compareAmounts('0.30', '0.1')).toBe(1);
  });
});

describe('date and date-time handling', () => {
  it('round-trips datetime-local values through ISO UTC', () => {
    const iso = localInputToIso('2026-11-03T09:30');
    expect(iso).toMatch(/^2026-11-0[2-4]T\d{2}:30:00\.000Z$/);
    expect(isoToLocalInput(iso)).toBe('2026-11-03T09:30');
    expect(new Date(iso!).getTime()).toBe(new Date(2026, 10, 3, 9, 30).getTime());
  });

  it('rejects impossible local date-times', () => {
    for (const bad of ['', '2026-02-30T10:00', '2026-11-03T25:00', '2026-11-03', 'tomorrow', '2026-13-01T10:00']) expect(localInputToIso(bad)).toBeUndefined();
    expect(isoToLocalInput('nope')).toBe('');
    expect(isoToLocalInput(undefined)).toBe('');
  });

  it('validates calendar dates and formats them without time-zone shift', () => {
    expect(isValidDate('2026-02-28')).toBe(true);
    expect(isValidDate('2026-02-29')).toBe(false);
    expect(isValidDate('2026-2-1')).toBe(false);
    expect(formatDay('2026-01-01')).toContain('2026');
    expect(formatDay('garbage')).toBe('–');
    expect(localDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
  });

  it('humanises codes', () => {
    expect(humanise('PARTIALLY_APPROVED')).toBe('Partially approved');
  });
});
