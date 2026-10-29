import { describe, expect, it } from 'vitest';
import type { Protocol } from '../api/types';
import { branchFor, matchesCondition, traverse } from './protocolRunner';

/** The MSK example from API-Contract-E01-E02 §5.1. */
const MSK: Protocol = {
  code: 'MSK',
  version: 1,
  title: 'Musculoskeletal pain / injury',
  presentation: 'MSK',
  status: 'DRAFT',
  start: 'q1',
  questions: [
    { id: 'q1', text: 'Obvious deformity, loss of pulse or numbness?', type: 'BOOLEAN', redFlag: true, onYes: { outcome: 'EMERGENCY' }, onNo: { next: 'q2' } },
    { id: 'q2', text: 'Pain score 0–10', type: 'NUMBER', min: 0, max: 10, rules: [{ if: '>=8', outcome: 'URGENT' }, { if: '>=4', next: 'q3' }, { else: true, next: 'q3' }] },
    { id: 'q3', text: 'Able to perform normal duties?', type: 'BOOLEAN', onYes: { outcome: 'ROUTINE' }, onNo: { outcome: 'SEMI_URGENT' } },
  ],
  outcomes: {
    EMERGENCY: { disposition: 'Call emergency services', adviceKey: 'EMERGENCY_GENERIC' },
    URGENT: { disposition: 'Same-day physician review', adviceKey: 'MSK_URGENT' },
  },
};

describe('protocol runner – traversal', () => {
  it('starts at the start question with nothing answered', () => {
    const t = traverse(MSK, {});
    expect(t.current?.id).toBe('q1');
    expect(t.outcome).toBeUndefined();
    expect(t.steps).toEqual([]);
  });

  it('red flag answered yes → hard stop EMERGENCY, red flag recorded', () => {
    const t = traverse(MSK, { q1: true });
    expect(t.outcome).toBe('EMERGENCY');
    expect(t.redFlagsTriggered).toEqual(['q1']);
    expect(t.current).toBeUndefined();
    expect(t.answersOnPath).toEqual({ q1: true });
  });

  it('red flag is a hard stop even if the protocol routes onYes elsewhere', () => {
    const p: Protocol = { ...MSK, questions: MSK.questions.map((q) => (q.id === 'q1' ? { ...q, onYes: { next: 'q2' } } : q)) };
    const t = traverse(p, { q1: true, q2: 2 });
    expect(t.outcome).toBe('EMERGENCY');
    expect(t.steps.map((s) => s.questionId)).toEqual(['q1']);
  });

  it('pain ≥ 8 → URGENT via rule threshold', () => {
    const t = traverse(MSK, { q1: false, q2: 8 });
    expect(t.outcome).toBe('URGENT');
    expect(t.redFlagsTriggered).toEqual([]);
  });

  it('pain 4..7 → next q3 (second rule), then onNo → SEMI_URGENT', () => {
    expect(traverse(MSK, { q1: false, q2: 7 }).current?.id).toBe('q3');
    expect(traverse(MSK, { q1: false, q2: 4, q3: false }).outcome).toBe('SEMI_URGENT');
  });

  it('pain < 4 → else rule → q3 → onYes ROUTINE', () => {
    const t = traverse(MSK, { q1: false, q2: 2, q3: true });
    expect(t.outcome).toBe('ROUTINE');
    expect(t.steps.map((s) => s.questionId)).toEqual(['q1', 'q2', 'q3']);
  });

  it('drops answers from abandoned branches (answersOnPath only)', () => {
    // nurse first answered q3, then raised pain to 9 – q3 is no longer on the path
    const t = traverse(MSK, { q1: false, q2: 9, q3: true });
    expect(t.outcome).toBe('URGENT');
    expect(t.answersOnPath).toEqual({ q1: false, q2: 9 });
  });

  it('rejects out-of-range numbers and keeps the question current', () => {
    const t = traverse(MSK, { q1: false, q2: 11 });
    expect(t.error).toBe('INVALID_ANSWER');
    expect(t.current?.id).toBe('q2');
    expect(t.outcome).toBeUndefined();
  });

  it('detects unknown questions and cycles', () => {
    expect(traverse({ start: 'x', questions: [] }, {}).error).toBe('UNKNOWN_QUESTION');
    const cyc = traverse(
      { start: 'a', questions: [{ id: 'a', text: 'A', type: 'BOOLEAN', onYes: { next: 'b' }, onNo: { next: 'b' } }, { id: 'b', text: 'B', type: 'BOOLEAN', onYes: { next: 'a' }, onNo: { next: 'a' } }] },
      { a: true, b: true },
    );
    expect(cyc.error).toBe('CYCLE');
  });

  it('falls back to question.next and reports NO_ROUTE when nothing matches', () => {
    expect(branchFor({ id: 'n', text: '', type: 'NUMBER', next: 'z' }, 3)).toEqual({ next: 'z' });
    const t = traverse({ start: 'n', questions: [{ id: 'n', text: '', type: 'NUMBER', rules: [{ if: '>5', outcome: 'URGENT' }] }] }, { n: 1 });
    expect(t.error).toBe('NO_ROUTE');
  });
});

describe('rule conditions', () => {
  it.each([
    ['>=8', 8, true],
    ['>=8', 7.9, false],
    ['>5', 5, false],
    ['<4', 3, true],
    ['<=3', 3, true],
    ['==0', 0, true],
    ['=2', 2, true],
    ['!=2', 3, true],
    ['5', 5, true],
    ['3..7', 7, true],
    ['3..7', 8, false],
    ['3-7', 3, true],
    ['== -1', -1, true],
  ] as const)('%s with %s → %s', (cond, value, expected) => {
    expect(matchesCondition(cond, value)).toBe(expected);
  });

  it('supports booleans and SELECT string equality; garbage never matches', () => {
    expect(matchesCondition('==true', true)).toBe(true);
    expect(matchesCondition('!=true', false)).toBe(true);
    expect(matchesCondition('==SEVERE', 'SEVERE')).toBe(true);
    expect(matchesCondition('==SEVERE', 'MILD')).toBe(false);
    expect(matchesCondition('>=8', '9')).toBe(false);
    expect(matchesCondition('drop table', 1)).toBe(false);
  });
});
