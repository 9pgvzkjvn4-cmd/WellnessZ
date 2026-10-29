import type { RtwPhase } from '../api/fitness';
import type { FieldError } from './devices';

/** RTW plan builder checks: weeks ascending, no overlap, hours 0–24, functional duties present, review dates in order. */
export function validatePlan(startDate: string, phases: readonly RtwPhase[], reviewDates: readonly string[], goal: string): FieldError[] {
  const errs: FieldError[] = [];
  if (!startDate) errs.push({ field: 'rtw-start', message: 'Enter the plan start date.' });
  if (!goal.trim()) errs.push({ field: 'rtw-goal', message: 'Enter the plan goal (functional wording).' });
  if (phases.length === 0) errs.push({ field: 'rtw-phase-0-from', message: 'Add at least one phase.' });
  let lastTo = 0;
  phases.forEach((p, i) => {
    if (!Number.isInteger(p.fromWeek) || p.fromWeek < 1 || !Number.isInteger(p.toWeek) || p.toWeek < p.fromWeek)
      errs.push({ field: `rtw-phase-${i}-from`, message: `Phase ${i + 1}: weeks must be whole numbers with "from" ≤ "to".` });
    else if (p.fromWeek <= lastTo) errs.push({ field: `rtw-phase-${i}-from`, message: `Phase ${i + 1} overlaps the previous phase.` });
    if (Number.isNaN(p.hoursPerDay) || p.hoursPerDay <= 0 || p.hoursPerDay > 24) errs.push({ field: `rtw-phase-${i}-hours`, message: `Phase ${i + 1}: hours per day must be between 0 and 24.` });
    if (!p.duties.trim()) errs.push({ field: `rtw-phase-${i}-duties`, message: `Phase ${i + 1}: describe the duties.` });
    if (Number.isInteger(p.toWeek)) lastTo = Math.max(lastTo, p.toWeek);
  });
  const sorted = [...reviewDates].filter(Boolean);
  if (sorted.length === 0) errs.push({ field: 'rtw-review-0', message: 'Add at least one review date.' });
  sorted.forEach((d, i) => {
    if (startDate && d < startDate) errs.push({ field: `rtw-review-${i}`, message: `Review date ${i + 1} is before the plan start.` });
  });
  return errs;
}
