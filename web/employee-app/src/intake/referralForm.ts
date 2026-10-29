import type {
  AnswerValue,
  GrantablePurpose,
  PreferredContact,
  ReferralCategory,
  SelfReferralRequest,
  Template,
  TemplateField,
} from '../api/types';
import type { ReferralStrings } from '../i18n/strings';
import { addAttachments, type AttachmentProblem, type FileLike } from './attachments';

/** Max length of the free-text description (contract: `description (≤4000)`). */
export const MAX_DESCRIPTION = 4000;

/** Form-level keys used in the error map next to template field keys. */
export const FORM_KEYS = {
  template: '_template',
  category: '_category',
  description: '_description',
  bodyParts: '_bodyParts',
  symptoms: '_symptoms',
  consent: '_consent',
  attachments: '_attachments',
} as const;

/** DOM id of a template field control. */
export const fieldId = (key: string) => `f-${key.replace(/[^A-Za-z0-9_-]/g, '_')}`;

/** DOM ids of the fixed form controls (error summary links focus these). */
export const FORM_IDS: Record<string, string> = {
  [FORM_KEYS.template]: 'rf-template',
  [FORM_KEYS.category]: 'rf-category',
  [FORM_KEYS.description]: 'rf-description',
  [FORM_KEYS.bodyParts]: 'rf-bodymap',
  [FORM_KEYS.symptoms]: 'rf-symptoms',
  [FORM_KEYS.consent]: 'rf-consent',
};

export interface ReferralState<F extends FileLike = File> {
  language: string;
  templateCode: string;
  category: ReferralCategory | '';
  description: string;
  bodyParts: string[];
  symptoms: string[];
  /** Answers of the template's non-structural fields (everything except BODY_MAP / SYMPTOMS). */
  answers: Record<string, AnswerValue>;
  preferredContact: PreferredContact;
  consentAssessment: boolean;
  purposes: GrantablePurpose[];
  files: F[];
  attachmentProblems: AttachmentProblem[];
  bodySide: 'FRONT' | 'BACK';
}

export function initialReferralState<F extends FileLike = File>(language = 'en'): ReferralState<F> {
  return {
    language,
    templateCode: '',
    category: '',
    description: '',
    bodyParts: [],
    symptoms: [],
    answers: {},
    preferredContact: 'EMAIL',
    consentAssessment: false,
    purposes: [],
    files: [],
    attachmentProblems: [],
    bodySide: 'FRONT',
  };
}

export type ReferralAction<F extends FileLike = File> =
  | { type: 'language'; language: string }
  | { type: 'template'; code: string; template?: Template }
  | { type: 'category'; value: ReferralCategory }
  | { type: 'description'; value: string }
  | { type: 'toggleBodyPart'; code: string }
  | { type: 'toggleSymptom'; value: string }
  | { type: 'answer'; key: string; value: AnswerValue }
  | { type: 'contact'; value: PreferredContact }
  | { type: 'consent'; value: boolean }
  | { type: 'togglePurpose'; value: GrantablePurpose }
  | { type: 'addFiles'; files: F[] }
  | { type: 'removeFile'; index: number }
  | { type: 'clearFiles' }
  | { type: 'side'; side: 'FRONT' | 'BACK' }
  | { type: 'reset'; language: string };

/** Add `code` if absent, remove it if present (order preserved). */
export function toggleCode(list: readonly string[], code: string): string[] {
  return list.includes(code) ? list.filter((c) => c !== code) : [...list, code];
}

export function referralReducer<F extends FileLike>(s: ReferralState<F>, a: ReferralAction<F>): ReferralState<F> {
  switch (a.type) {
    case 'language':
      return { ...s, language: a.language };
    case 'template': {
      // keep answers whose key still exists in the new template
      const keys = new Set((a.template?.fields ?? []).map((f) => f.key));
      const answers = Object.fromEntries(Object.entries(s.answers).filter(([k]) => keys.has(k)));
      return { ...s, templateCode: a.code, answers };
    }
    case 'category':
      return { ...s, category: a.value };
    case 'description':
      return { ...s, description: a.value };
    case 'toggleBodyPart':
      return { ...s, bodyParts: toggleCode(s.bodyParts, a.code) };
    case 'toggleSymptom':
      return { ...s, symptoms: toggleCode(s.symptoms, a.value) };
    case 'answer':
      return { ...s, answers: { ...s.answers, [a.key]: a.value } };
    case 'contact':
      return { ...s, preferredContact: a.value };
    case 'consent':
      return { ...s, consentAssessment: a.value };
    case 'togglePurpose':
      return { ...s, purposes: toggleCode(s.purposes, a.value) as GrantablePurpose[] };
    case 'addFiles': {
      const r = addAttachments(s.files, a.files);
      return { ...s, files: r.files, attachmentProblems: r.problems };
    }
    case 'removeFile':
      return { ...s, files: s.files.filter((_, i) => i !== a.index), attachmentProblems: [] };
    case 'clearFiles':
      return { ...s, files: [], attachmentProblems: [] };
    case 'side':
      return { ...s, bodySide: a.side };
    case 'reset':
      return initialReferralState<F>(a.language);
  }
}

/** Template fields that map to structured request properties rather than `answers`. */
export const isStructural = (f: TemplateField) => f.type === 'BODY_MAP' || f.type === 'SYMPTOMS';

/**
 * Choose the template to pre-select: the one whose `departments` contains the employee's department, else one
 * matching any org-scope code, else the only template if there is exactly one. Returns '' when undecided.
 */
export function pickDefaultTemplate(templates: readonly Template[], departmentCodes: readonly (string | undefined)[]): string {
  const codes = departmentCodes.filter((c): c is string => !!c).map((c) => c.toUpperCase());
  for (const code of codes) {
    const t = templates.find((x) => (x.departments ?? []).some((d) => d.toUpperCase() === code));
    if (t) return t.code;
  }
  return templates.length === 1 ? templates[0]!.code : '';
}

function isEmpty(v: AnswerValue | undefined): boolean {
  if (v === undefined) return true;
  if (typeof v === 'boolean') return false; // an answered yes/no is never empty
  if (Array.isArray(v)) return v.length === 0;
  return v.trim() === '';
}

export type ErrorMap = Record<string, string>;

export type ErrorCode =
  | { kind: 'template' }
  | { kind: 'category' }
  | { kind: 'required' }
  | { kind: 'tooLong'; max: number }
  | { kind: 'consent' };

/** Language-neutral validation. Keys are template field keys or FORM_KEYS values. */
export function referralErrorCodes(s: ReferralState<FileLike>, template: Template | undefined): Record<string, ErrorCode> {
  const e: Record<string, ErrorCode> = {};
  if (!template) e[FORM_KEYS.template] = { kind: 'template' };
  if (!s.category) e[FORM_KEYS.category] = { kind: 'category' };
  for (const f of template?.fields ?? []) {
    if (f.type === 'BODY_MAP') {
      if (f.required && s.bodyParts.length === 0) e[f.key] = { kind: 'required' };
      continue;
    }
    if (f.type === 'SYMPTOMS') {
      if (f.required && s.symptoms.length === 0) e[f.key] = { kind: 'required' };
      continue;
    }
    const v = s.answers[f.key];
    if (f.required && isEmpty(v)) e[f.key] = { kind: 'required' };
    else if (typeof v === 'string' && f.maxLength && v.length > f.maxLength) e[f.key] = { kind: 'tooLong', max: f.maxLength };
  }
  if (!s.description.trim()) e[FORM_KEYS.description] = { kind: 'required' };
  else if (s.description.length > MAX_DESCRIPTION) e[FORM_KEYS.description] = { kind: 'tooLong', max: MAX_DESCRIPTION };
  if (!s.consentAssessment) e[FORM_KEYS.consent] = { kind: 'consent' };
  return e;
}

/** Validate the whole form and return localised messages. */
export function validateReferral(s: ReferralState<FileLike>, template: Template | undefined, t: ReferralStrings): ErrorMap {
  const msg = (c: ErrorCode): string => {
    switch (c.kind) {
      case 'template':
        return t.errTemplate;
      case 'category':
        return t.errCategory;
      case 'required':
        return t.errRequired;
      case 'tooLong':
        return t.errTooLong(c.max);
      case 'consent':
        return t.errConsent;
    }
  };
  return Object.fromEntries(Object.entries(referralErrorCodes(s, template)).map(([k, c]) => [k, msg(c)]));
}

/** Required items for the progress hint: template, category, required template fields, description, consent. */
export function referralProgress(s: ReferralState<FileLike>, template: Template | undefined): { done: number; total: number } {
  const requiredKeys = [
    FORM_KEYS.template,
    FORM_KEYS.category,
    ...(template?.fields ?? []).filter((f) => f.required).map((f) => f.key),
    FORM_KEYS.description,
    FORM_KEYS.consent,
  ];
  const errors = referralErrorCodes(s, template);
  return { done: requiredKeys.filter((k) => !errors[k]).length, total: requiredKeys.length };
}

/** Build the `POST /intake/referrals/self` body. Only answers of fields in the selected template are sent. */
export function buildSelfReferral(s: ReferralState<FileLike>, template: Template): SelfReferralRequest {
  const answers: Record<string, AnswerValue> = {};
  for (const f of template.fields) {
    if (isStructural(f)) continue;
    const v = s.answers[f.key];
    if (v === undefined || isEmpty(v)) continue;
    answers[f.key] = typeof v === 'string' ? v.trim() : v;
  }
  return {
    templateCode: template.code,
    category: s.category as ReferralCategory,
    description: s.description.trim(),
    bodyParts: [...s.bodyParts],
    symptoms: [...s.symptoms],
    answers,
    preferredContact: s.preferredContact,
    language: s.language,
    consent: { assessment: true, purposes: [...s.purposes] },
  };
}

/** Server acknowledgement as plain text (the contract does not pin its type). */
export function acknowledgementText(ack: unknown): string | undefined {
  if (typeof ack === 'string') return ack.trim() || undefined;
  if (ack && typeof ack === 'object') {
    const o = ack as { message?: unknown; text?: unknown };
    if (typeof o.message === 'string') return o.message;
    if (typeof o.text === 'string') return o.text;
  }
  return undefined;
}
