import type { DurationComparison, FitnessDecision, FitnessOutcome, RestrictionValue } from '../api/fitness';
import type { FitnessStandard, RestrictionDef } from '../api/platform';
import type { FieldError } from './devices';

export interface DecisionForm {
  standardCode: string;
  outcome: FitnessOutcome | '';
  limitationCodes: string[];
  restrictions: { code: string; value: string }[];
  validFrom: string;
  validUntil: string;
  reviewDate: string;
  rationale: string;
  durationOverrideReason: string;
  generateCertificate: boolean;
}

export const RATIONALE_MAX = 4000;

/** `validUntil` default from the standard's validity (months; the over-40 rule is applied by the server). */
export function defaultValidUntil(validFrom: string, months: number | null | undefined): string {
  if (!validFrom || !months) return '';
  const d = new Date(`${validFrom}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return '';
  d.setUTCMonth(d.getUTCMonth() + months);
  return d.toISOString().slice(0, 10);
}

/** Licensed standards (`requiresSignoffRole=AME`) can only be decided by AME or CMO (contract §5.1). */
export function mayDecide(standard: FitnessStandard | undefined, roles: readonly string[]): boolean {
  if (!standard) return true;
  if (standard.requiresSignoffRole === 'AME') return roles.includes('AME') || roles.includes('CMO');
  return true;
}

/** Picker entries → API restrictions (numeric value where the catalogue entry needs a value). */
export function restrictionValues(entries: readonly { code: string; value: string }[], catalogue: readonly RestrictionDef[]): RestrictionValue[] {
  return entries.map((r) => {
    const def = catalogue.find((c) => c.code === r.code);
    if (!def?.valueRequired) return { code: r.code };
    const n = Number(r.value);
    return { code: r.code, value: r.value.trim() !== '' && !Number.isNaN(n) ? n : r.value.trim() };
  });
}

export function validateDecision(form: DecisionForm, catalogue: readonly RestrictionDef[], duration: DurationComparison | null | undefined): FieldError[] {
  const errs: FieldError[] = [];
  if (!form.standardCode) errs.push({ field: 'fd-standard', message: 'Choose the fitness standard.' });
  if (!form.outcome) errs.push({ field: 'fd-outcome', message: 'Choose the outcome.' });
  if (form.outcome === 'FIT_WITH_LIMITATIONS' && form.restrictions.length === 0 && form.limitationCodes.length === 0)
    errs.push({ field: 'fd-restrictions', message: 'Fit with limitations needs at least one restriction or limitation code.' });
  form.restrictions.forEach((r, i) => {
    const def = catalogue.find((c) => c.code === r.code);
    if (def?.valueRequired && !r.value.trim()) errs.push({ field: `fd-restriction-${i}`, message: `Enter a value for “${def.label.replace('{value}', '…')}”.` });
  });
  if (!form.validFrom) errs.push({ field: 'fd-valid-from', message: 'Enter the date the decision is valid from.' });
  if (form.validUntil && form.validFrom && form.validUntil < form.validFrom) errs.push({ field: 'fd-valid-until', message: '“Valid until” must be after “valid from”.' });
  if (form.reviewDate && form.validFrom && form.reviewDate < form.validFrom) errs.push({ field: 'fd-review', message: 'The review date must be after “valid from”.' });
  if (!form.rationale.trim()) errs.push({ field: 'fd-rationale', message: 'Enter the clinical rationale (OH only).' });
  if (form.rationale.length > RATIONALE_MAX) errs.push({ field: 'fd-rationale', message: `The rationale must be ${RATIONALE_MAX} characters or fewer.` });
  if (duration?.exceeded && !form.durationOverrideReason.trim())
    errs.push({ field: 'fd-duration-reason', message: 'The absence exceeds the duration guideline – give a reason for the override.' });
  return errs;
}

/** Label with the catalogue value filled in: "No lifting above {value} kg" → "No lifting above 10 kg". */
export function restrictionLabel(def: Pick<RestrictionDef, 'label' | 'unit'> | undefined, code: string, value?: unknown): string {
  if (!def) return code;
  return def.label.includes('{value}') ? def.label.replace('{value}', value === undefined || value === null || value === '' ? '…' : String(value)) : def.label;
}

/** Who may sign off a decision: CMO for PERMANENTLY_UNFIT, otherwise AME or CMO (the server checks the standard's role). */
export function maySignoff(d: FitnessDecision, roles: readonly string[]): boolean {
  if (d.status !== 'AWAITING_SIGNOFF') return false;
  if (d.outcome === 'PERMANENTLY_UNFIT') return roles.includes('CMO');
  return roles.includes('AME') || roles.includes('CMO');
}
