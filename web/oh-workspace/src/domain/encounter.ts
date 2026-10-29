import type { AnswerValue, BodyMapMark, BodySide, Diagnosis, EncounterTemplate, EncounterType } from '../api/record';
import type { FieldError } from './devices';

/** Encounter types that need ≥ 1 coded diagnosis before signing (contract §4.2), on top of the template flag. */
export const DIAGNOSIS_REQUIRED_TYPES: readonly EncounterType[] = ['INJURY', 'ILLNESS', 'MENTAL_HEALTH'];

export const answerFieldId = (key: string) => `enc-field-${key.replace(/[^A-Za-z0-9_-]/g, '_')}`;

function isEmpty(v: AnswerValue | undefined): boolean {
  if (v === undefined || v === null) return true;
  if (typeof v === 'string') return v.trim() === '';
  if (Array.isArray(v)) return v.length === 0;
  return false;
}

/** Client-side pre-sign check (the server validates again): mandatory template fields and the diagnosis rule. */
export function signBlockers(template: EncounterTemplate | undefined, type: EncounterType, answers: Record<string, AnswerValue>, diagnoses: readonly Diagnosis[]): FieldError[] {
  const errs: FieldError[] = [];
  for (const s of template?.sections ?? []) {
    for (const f of s.fields) {
      // BOOLEAN "required" means "must be answered"; an unticked box counts as answered "no".
      if (f.required && f.type !== 'BOOLEAN' && isEmpty(answers[f.key])) errs.push({ field: answerFieldId(f.key), message: `${s.title}: “${f.label}” is required.` });
    }
  }
  const needsDx = (template?.requiresDiagnosis ?? false) || DIAGNOSIS_REQUIRED_TYPES.includes(type);
  if (needsDx && diagnoses.length === 0) errs.push({ field: 'enc-diagnoses', message: 'Add at least one coded diagnosis before signing.' });
  if (diagnoses.length > 0 && diagnoses.filter((d) => d.primary).length !== 1) errs.push({ field: 'enc-diagnoses', message: 'Mark exactly one diagnosis as primary.' });
  return errs;
}

/** Add a diagnosis (no duplicates); the first one becomes primary. */
export function addDiagnosis(list: readonly Diagnosis[], d: Omit<Diagnosis, 'primary'>): Diagnosis[] {
  if (list.some((x) => x.system === d.system && x.code === d.code)) return [...list];
  return [...list, { ...d, primary: list.length === 0 }];
}

/** Make one diagnosis primary (exactly one primary). */
export function setPrimary(list: readonly Diagnosis[], index: number): Diagnosis[] {
  return list.map((d, i) => ({ ...d, primary: i === index }));
}

/** Remove a diagnosis; if the primary was removed the first remaining becomes primary. */
export function removeDiagnosis(list: readonly Diagnosis[], index: number): Diagnosis[] {
  const next = list.filter((_, i) => i !== index);
  if (next.length > 0 && !next.some((d) => d.primary)) next[0] = { ...next[0]!, primary: true };
  return next;
}

// ---------------------------------------------------------------- body map (E03-BL-005)
const LIMBS = ['SHOULDER', 'UPPER_ARM', 'ELBOW', 'FOREARM', 'WRIST', 'HAND', 'HIP', 'THIGH', 'KNEE', 'LOWER_LEG', 'ANKLE', 'FOOT'];
const lateral = (parts: string[]) => parts.flatMap((p) => [`RIGHT_${p}`, `LEFT_${p}`]);

/**
 * Regions per view. `side` in the contract's `{region, side, mechanism}` is read as the body-map view (FRONT / BACK);
 * laterality is part of the region code (e.g. LEFT_KNEE).
 */
export const BODY_REGIONS: Record<BodySide, string[]> = {
  FRONT: ['HEAD', 'FACE', 'EYES', 'NECK', 'CHEST', 'ABDOMEN', 'PELVIS_GROIN', ...lateral(LIMBS)],
  BACK: ['HEAD', 'NECK', 'UPPER_BACK', 'LOWER_BACK', 'BUTTOCKS', ...lateral(LIMBS)],
};

export function toggleRegion(marks: readonly BodyMapMark[], side: BodySide, region: string): BodyMapMark[] {
  const exists = marks.some((m) => m.side === side && m.region === region);
  return exists ? marks.filter((m) => !(m.side === side && m.region === region)) : [...marks, { side, region, mechanism: null }];
}

export function setMechanism(marks: readonly BodyMapMark[], side: BodySide, region: string, mechanism: string): BodyMapMark[] {
  return marks.map((m) => (m.side === side && m.region === region ? { ...m, mechanism: mechanism || null } : m));
}
