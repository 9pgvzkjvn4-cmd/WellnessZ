/** Shapes of the core API (/api/v1) as consumed by the employee app. Fields are optional where the brief does not pin them. */

export const REFERRAL_CATEGORIES = [
  'INJURY',
  'ILLNESS',
  'DECREASE_IN_FITNESS',
  'PSYCHOLOGICAL',
  'SUBSTANCE',
  'EXPOSURE',
  'OTHER',
] as const;
export type ReferralCategory = (typeof REFERRAL_CATEGORIES)[number];

export const CATEGORY_LABELS: Record<ReferralCategory, string> = {
  INJURY: 'Injury',
  ILLNESS: 'Illness',
  DECREASE_IN_FITNESS: 'Decrease in fitness',
  PSYCHOLOGICAL: 'Psychological wellbeing',
  SUBSTANCE: 'Alcohol or substance use',
  EXPOSURE: 'Workplace exposure (e.g. fumes, noise, chemicals)',
  OTHER: 'Other',
};

/** preferredContact is a free string (max 20 chars) in the API; the UI offers these values. */
export const CONTACT_OPTIONS = ['EMAIL', 'PHONE', 'IN_APP'] as const;
export type PreferredContact = (typeof CONTACT_OPTIONS)[number];

/* ---------- E01 intake (docs/increment-01/API-Contract-E01-E02.md §4.2) ---------- */

/** `GET /languages` */
export interface Language {
  code: string;
  label: string;
}

export const FIELD_TYPES = ['TEXT', 'TEXTAREA', 'SELECT', 'MULTISELECT', 'BOOLEAN', 'DATE', 'BODY_MAP', 'SYMPTOMS'] as const;
export type FieldType = (typeof FIELD_TYPES)[number];

export interface Option {
  value: string;
  label: string;
}

export interface TemplateField {
  key: string;
  label: string;
  type: FieldType | string;
  required?: boolean;
  options?: Option[];
  maxLength?: number | null;
}

/** `GET /intake/templates?audience=EMPLOYEE&lang=` */
export interface Template {
  code: string;
  title: string;
  audience?: string;
  departments?: string[];
  fields: TemplateField[];
  routingQueue?: string;
  symptomOptions?: Option[];
}

/** `GET /intake/body-map` */
export interface BodyMapRegion {
  code: string;
  label: string;
  side: 'FRONT' | 'BACK' | string;
}

export type AnswerValue = string | string[] | boolean;

/** `POST /intake/referrals/self` */
export interface SelfReferralRequest {
  templateCode: string;
  category: ReferralCategory;
  description: string;
  bodyParts: string[];
  symptoms: string[];
  answers: Record<string, AnswerValue>;
  preferredContact: PreferredContact;
  language: string;
  consent: { assessment: true; purposes: ConsentPurpose[] };
}

export interface SelfReferralResponse {
  caseId: string;
  caseRef: string;
  /** Contract does not pin the type; a string is expected, `{message|text}` objects are tolerated. */
  acknowledgement?: unknown;
}

export const DECLARATION_REASONS = ['ILLNESS', 'INJURY', 'MEDICATION', 'FATIGUE', 'PREGNANCY', 'OTHER'] as const;
export type DeclarationReason = (typeof DECLARATION_REASONS)[number];

export const DECLARATION_REASON_LABELS: Record<DeclarationReason, string> = {
  ILLNESS: 'Illness',
  INJURY: 'Injury',
  MEDICATION: 'Medication that may affect my fitness',
  FATIGUE: 'Fatigue',
  PREGNANCY: 'Pregnancy',
  OTHER: 'Other',
};

/** `POST /intake/triggers/fitness-declaration` (dates `YYYY-MM-DD`). */
export interface FitnessDeclarationRequest {
  reason: DeclarationReason;
  unfitFrom: string;
  expectedUntil?: string;
  details?: string;
}

/**
 * Response (increment 2, contract §5.4): `caseRef` plus `nextSteps[]`, `ameContact` and `acknowledgementDueBy`
 * (24-h SLA). Their element types are not pinned – see `toDeclarationOutcome` in `api/portal.ts`.
 */
export interface FitnessDeclarationResponse {
  caseId?: string;
  caseRef?: string;
  nextSteps?: unknown[];
  ameContact?: unknown;
  acknowledgementDueBy?: string;
}

/** `GET /me/advice` (E02 §5.1) */
export interface Advice {
  id: string;
  caseRef?: string;
  title?: string;
  text?: string;
  sentAt?: string;
}

export const CASE_STATUSES = [
  'INTAKE',
  'TRIAGE',
  'ASSESSMENT',
  'FITNESS_REVIEW',
  'TREATMENT',
  'CLAIMS',
  'MONITORING',
  'CLOSED',
  'MERGED',
] as const;
export type CaseStatus = (typeof CASE_STATUSES)[number];

export const FITNESS_STATUSES = [
  'FIT',
  'FIT_WITH_RESTRICTIONS',
  'TEMPORARILY_UNFIT',
  'PERMANENTLY_UNFIT',
  'UNDER_REVIEW',
  'NOT_ASSESSED',
  'UNFIT_PENDING',
] as const;
export type FitnessStatus = (typeof FITNESS_STATUSES)[number];

/** Employee-friendly wording for case status. */
export const CASE_STATUS_LABELS: Record<CaseStatus, string> = {
  INTAKE: 'Received',
  TRIAGE: 'Being reviewed by OH',
  ASSESSMENT: 'Assessment',
  FITNESS_REVIEW: 'Fitness review',
  TREATMENT: 'Treatment',
  CLAIMS: 'Claim in progress',
  MONITORING: 'Follow-up',
  CLOSED: 'Closed',
  MERGED: 'Merged with another case',
};

/** Employee-friendly wording for fitness-for-work status. */
export const FITNESS_LABELS: Record<FitnessStatus, string> = {
  FIT: 'Fit for work',
  FIT_WITH_RESTRICTIONS: 'Fit with restrictions',
  TEMPORARILY_UNFIT: 'Temporarily unfit',
  PERMANENTLY_UNFIT: 'Permanently unfit',
  UNDER_REVIEW: 'Under review',
  NOT_ASSESSED: 'Not yet assessed',
  UNFIT_PENDING: 'Unfit – pending assessment',
};

/** Case reference format `{UNIT}-{YYYY}-{NNNNNN}` (unit = subsidiary code, fallback `OH`). */
export const CASE_REF_PATTERN = /^[A-Z0-9]{2,10}-\d{4}-\d{6}$/;

/** Split a case reference into unit / year / sequence, or undefined for legacy references. */
export function parseCaseRef(ref: string | undefined | null): { unit: string; year: string; seq: string } | undefined {
  if (!ref || !CASE_REF_PATTERN.test(ref)) return undefined;
  const [unit, year, seq] = ref.split('-') as [string, string, string];
  return { unit, year, seq };
}

/** Case object returned by GET /cases (own cases). Only these fields are rendered. */
export interface MyCase {
  id: string;
  caseRef?: string;
  category?: string;
  status: CaseStatus | string;
  fitnessStatus?: FitnessStatus | string | null;
  restrictionUntil?: string | null;
  createdAt?: string;
  updatedAt?: string;
  disclosure?: string;
}

export const CONSENT_PURPOSES = [
  'TREATMENT_SHARING_PARTNER',
  'EAP_REFERRAL',
  'INSURER_SHARING',
  'WEARABLE_DATA',
  'HEALTH_CAMPAIGNS',
  'RESEARCH_DEIDENTIFIED',
  'AI_DOCUMENTATION',
  'TELEHEALTH_CONSULTATION',
] as const;
export type ConsentPurpose = (typeof CONSENT_PURPOSES)[number];

/**
 * Purposes the employee can grant in general (consent screen, self-referral). `AI_DOCUMENTATION` is captured per
 * encounter by the clinician and `TELEHEALTH_CONSULTATION` per session on the telehealth screen – both are listed
 * (and can be withdrawn) on the consent screen but not offered there for a blanket grant.
 */
export const SELF_GRANTABLE_PURPOSES = [
  'TREATMENT_SHARING_PARTNER',
  'EAP_REFERRAL',
  'INSURER_SHARING',
  'WEARABLE_DATA',
  'HEALTH_CAMPAIGNS',
  'RESEARCH_DEIDENTIFIED',
] as const satisfies readonly ConsentPurpose[];
export type GrantablePurpose = (typeof SELF_GRANTABLE_PURPOSES)[number];

export const CONSENT_PURPOSE_LABELS: Record<ConsentPurpose, string> = {
  TREATMENT_SHARING_PARTNER: 'Share treatment information with a partner provider',
  EAP_REFERRAL: 'Referral to the Employee Assistance Programme (EAP)',
  INSURER_SHARING: 'Share relevant information with the insurer',
  WEARABLE_DATA: 'Use data from my wearable device',
  HEALTH_CAMPAIGNS: 'Invitations to health campaigns',
  RESEARCH_DEIDENTIFIED: 'Use of de-identified data for research',
  AI_DOCUMENTATION: 'AI-assisted documentation of a consultation',
  TELEHEALTH_CONSULTATION: 'Video or secure-chat consultation (telehealth)',
};

export interface Consent {
  id: string;
  employeeId?: string;
  purpose: string;
  scope?: string | null;
  status: 'GRANTED' | 'WITHDRAWN' | string;
  noticeVersion?: string;
  grantedAt?: string;
  withdrawnAt?: string | null;
  capturedBy?: string;
}

export interface GrantConsentRequest {
  purpose: GrantablePurpose;
  scope?: string;
}

export interface Me {
  subject: string;
  username?: string;
  displayName?: string;
  employeeId?: string;
  orgScope?: string | string[];
  roles: string[];
  maxDisclosure?: string;
  /** Not in the /me contract yet – used for template pre-selection / crew hint when the backend provides them. */
  departmentCode?: string;
  jobCode?: string;
}

/** Spring Data pages or plain arrays are both accepted. */
export function asList<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[];
  if (body && typeof body === 'object' && Array.isArray((body as { content?: unknown }).content)) {
    return (body as { content: T[] }).content;
  }
  return [];
}
