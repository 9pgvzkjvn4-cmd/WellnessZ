import { describe, expect, it } from 'vitest';
import { cellText, isSuppressed, readK } from './suppression';

describe('dashboard k-suppression', () => {
  it('renders server-suppressed cells (null / flag / object) as "<k"', () => {
    expect(cellText(null, 5)).toBe('<5');
    expect(cellText(undefined, 5)).toBe('<5');
    expect(cellText({ suppressed: true }, 5)).toBe('<5');
    expect(cellText(12, 5, true)).toBe('<5');
  });

  it('defence in depth: positive counts below k are suppressed; 0 and ≥k shown', () => {
    expect(cellText(3, 5)).toBe('<5');
    expect(cellText(0, 5)).toBe('0');
    expect(cellText(5, 5)).toBe('5');
    expect(cellText({ count: 7 }, 5)).toBe('7');
    expect(isSuppressed(4, 10)).toBe(true);
  });

  it('reads k from the response, default 5', () => {
    expect(readK({ minGroupSize: 10 })).toBe(10);
    expect(readK({ k: 3 })).toBe(3);
    expect(readK(undefined)).toBe(5);
    expect(cellText(4, readK({ minGroupSize: 10 }))).toBe('<10');
  });
});
