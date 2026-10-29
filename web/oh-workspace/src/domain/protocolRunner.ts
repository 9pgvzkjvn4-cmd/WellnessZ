import type { AnswerValue, Protocol, ProtocolBranch, ProtocolQuestion, ProtocolRule, Urgency } from '../api/types';

/**
 * Client-side traversal of a triage Protocol (E02-F01). It only decides which question to ask next
 * and shows a *preview* outcome – the server re-evaluates the answers and is authoritative
 * (contract §5.1: "the client never decides urgency"). A red-flag question answered "yes"
 * is a hard stop to EMERGENCY.
 */

export type Answers = Record<string, AnswerValue | undefined>;

export interface TraversalStep {
  questionId: string;
  answer: AnswerValue;
  redFlagTriggered: boolean;
}

export interface Traversal {
  /** Questions answered along the active path, in order. */
  steps: TraversalStep[];
  /** Next question that needs an answer (undefined when finished or on error). */
  current?: ProtocolQuestion;
  /** Preview outcome when the path ends in an outcome. */
  outcome?: Urgency;
  redFlagsTriggered: string[];
  /** Answers on the active path only (answers of abandoned branches are dropped before submit). */
  answersOnPath: Record<string, AnswerValue>;
  error?: 'UNKNOWN_QUESTION' | 'CYCLE' | 'NO_ROUTE' | 'INVALID_ANSWER';
  errorAt?: string;
}

const COND = /^\s*(>=|<=|==|!=|=|>|<)?\s*(-?\d+(?:\.\d+)?|true|false|[A-Za-z_][\w-]*)\s*$/;
const RANGE = /^\s*(-?\d+(?:\.\d+)?)\s*(?:\.\.|–|-(?=\s*\d))\s*(-?\d+(?:\.\d+)?)\s*$/;

/**
 * Evaluate a rule condition such as ">=8", "<4", "==0", "5", "3..7" (inclusive range),
 * "==true" or "==SEVERE" (string equality for SELECT questions). Unknown syntax → false.
 */
export function matchesCondition(cond: string, value: AnswerValue): boolean {
  const range = RANGE.exec(cond);
  if (range) {
    if (typeof value !== 'number') return false;
    return value >= Number(range[1]) && value <= Number(range[2]);
  }
  const m = COND.exec(cond);
  if (!m) return false;
  const op = m[1] ?? '==';
  const raw = m[2] ?? '';
  if (raw === 'true' || raw === 'false') {
    const b = raw === 'true';
    if (typeof value !== 'boolean') return false;
    return op === '!=' ? value !== b : (op === '==' || op === '=') && value === b;
  }
  const n = Number(raw);
  if (!Number.isNaN(n) && typeof value === 'number') {
    switch (op) {
      case '>=': return value >= n;
      case '<=': return value <= n;
      case '>': return value > n;
      case '<': return value < n;
      case '!=': return value !== n;
      default: return value === n;
    }
  }
  if (typeof value === 'string') {
    if (op === '!=') return value !== raw;
    if (op === '==' || op === '=') return value === raw;
  }
  return false;
}

export function isValidAnswer(q: ProtocolQuestion, value: AnswerValue | undefined): value is AnswerValue {
  if (value === undefined) return false;
  switch (q.type) {
    case 'BOOLEAN':
      return typeof value === 'boolean';
    case 'NUMBER':
      return typeof value === 'number' && Number.isFinite(value) && (q.min === undefined || value >= q.min) && (q.max === undefined || value <= q.max);
    case 'SELECT':
      return typeof value === 'string' && (!q.options || q.options.some((o) => o.value === value));
    default:
      return false;
  }
}

/** Branch taken for one answered question (undefined = no route defined). */
export function branchFor(q: ProtocolQuestion, value: AnswerValue): ProtocolBranch | undefined {
  if (q.redFlag && value === true) return { outcome: 'EMERGENCY' };
  if (q.type === 'BOOLEAN') {
    const b = value === true ? q.onYes : q.onNo;
    if (b && (b.outcome || b.next)) return b;
  }
  if (q.rules?.length) {
    const hit: ProtocolRule | undefined = q.rules.find((r) => !r.else && r.if !== undefined && matchesCondition(r.if, value)) ?? q.rules.find((r) => r.else);
    if (hit && (hit.outcome || hit.next)) return { outcome: hit.outcome, next: hit.next };
  }
  if (q.next) return { next: q.next };
  return undefined;
}

export function traverse(protocol: Pick<Protocol, 'start' | 'questions'>, answers: Answers): Traversal {
  const byId = new Map(protocol.questions.map((q) => [q.id, q]));
  const steps: TraversalStep[] = [];
  const redFlags: string[] = [];
  const answersOnPath: Record<string, AnswerValue> = {};
  const visited = new Set<string>();
  let id: string | undefined = protocol.start;
  const done = (extra: Partial<Traversal>): Traversal => ({ steps, redFlagsTriggered: redFlags, answersOnPath, ...extra });

  while (id !== undefined) {
    const q = byId.get(id);
    if (!q) return done({ error: 'UNKNOWN_QUESTION', errorAt: id });
    if (visited.has(id)) return done({ error: 'CYCLE', errorAt: id });
    visited.add(id);
    const value = answers[id];
    if (value === undefined) return done({ current: q });
    if (!isValidAnswer(q, value)) return done({ current: q, error: 'INVALID_ANSWER', errorAt: id });
    const redFlagTriggered = !!q.redFlag && value === true;
    if (redFlagTriggered) redFlags.push(q.id);
    steps.push({ questionId: q.id, answer: value, redFlagTriggered });
    answersOnPath[q.id] = value;
    const branch = branchFor(q, value);
    if (!branch) return done({ error: 'NO_ROUTE', errorAt: id });
    if (branch.outcome) return done({ outcome: branch.outcome });
    id = branch.next;
  }
  return done({ error: 'NO_ROUTE' });
}

/** Urgency ordering (EMERGENCY highest). */
export const URGENCY_RANK: Record<Urgency, number> = { EMERGENCY: 0, URGENT: 1, SEMI_URGENT: 2, ROUTINE: 3, ADVICE: 4 };
