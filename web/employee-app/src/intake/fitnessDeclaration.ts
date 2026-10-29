import { DECLARATION_REASONS, type DeclarationReason, type FitnessDeclarationRequest, type Me } from '../api/types';

export const MAX_DETAILS = 1000;

export interface DeclarationForm {
  reason: DeclarationReason | '';
  unfitFrom: string;
  expectedUntil: string;
  details: string;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Local calendar date `YYYY-MM-DD`. */
export function todayIso(now: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

export function validateDeclaration(f: DeclarationForm): Partial<Record<keyof DeclarationForm, string>> {
  const e: Partial<Record<keyof DeclarationForm, string>> = {};
  if (!f.reason || !(DECLARATION_REASONS as readonly string[]).includes(f.reason)) e.reason = 'Please choose a reason.';
  if (!DATE.test(f.unfitFrom)) e.unfitFrom = 'Please enter the date from which you are unfit.';
  if (f.expectedUntil && !DATE.test(f.expectedUntil)) e.expectedUntil = 'Please enter a valid date.';
  else if (f.expectedUntil && DATE.test(f.unfitFrom) && f.expectedUntil < f.unfitFrom)
    e.expectedUntil = 'The expected end date cannot be before the start date.';
  if (f.details.length > MAX_DETAILS) e.details = `Please use at most ${MAX_DETAILS} characters.`;
  return e;
}

export function buildDeclaration(f: DeclarationForm): FitnessDeclarationRequest {
  const body: FitnessDeclarationRequest = { reason: f.reason as DeclarationReason, unfitFrom: f.unfitFrom };
  if (f.expectedUntil) body.expectedUntil = f.expectedUntil;
  if (f.details.trim()) body.details = f.details.trim();
  return body;
}

const CREW_HINT = /(^|_)(PILOT|CREW|CABIN|CAB|FLIGHT|FLIGHTOPS|FLT|CAPTAIN|FIRST_OFFICER)(_|$)/i;

/**
 * Best-effort crew detection from `/me` (job code, department or org scope). Used only to emphasise the form –
 * the declaration is available to everyone and the server decides (EMPLOYEE (crew) role rule).
 */
export function looksLikeCrew(me: Me | undefined): boolean {
  if (!me) return false;
  const scope = Array.isArray(me.orgScope) ? me.orgScope : me.orgScope ? [me.orgScope] : [];
  return [me.jobCode, me.departmentCode, ...scope].some((c) => !!c && CREW_HINT.test(c));
}
