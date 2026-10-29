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

export interface SelfReferralRequest {
  category: ReferralCategory;
  description: string;
  preferredContact: PreferredContact;
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
] as const;
export type CaseStatus = (typeof CASE_STATUSES)[number];

export const FITNESS_STATUSES = [
  'FIT',
  'FIT_WITH_RESTRICTIONS',
  'TEMPORARILY_UNFIT',
  'PERMANENTLY_UNFIT',
  'UNDER_REVIEW',
  'NOT_ASSESSED',
] as const;
export type FitnessStatus = (typeof FITNESS_STATUSES)[number];

/** Case object returned by GET /cases (own cases) and POST /cases/self-referral. */
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
] as const;
export type ConsentPurpose = (typeof CONSENT_PURPOSES)[number];

export const CONSENT_PURPOSE_LABELS: Record<ConsentPurpose, string> = {
  TREATMENT_SHARING_PARTNER: 'Share treatment information with a partner provider',
  EAP_REFERRAL: 'Referral to the Employee Assistance Programme (EAP)',
  INSURER_SHARING: 'Share relevant information with the insurer',
  WEARABLE_DATA: 'Use data from my wearable device',
  HEALTH_CAMPAIGNS: 'Invitations to health campaigns',
  RESEARCH_DEIDENTIFIED: 'Use of de-identified data for research',
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
  purpose: ConsentPurpose;
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
}

/** Spring Data pages or plain arrays are both accepted. */
export function asList<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[];
  if (body && typeof body === 'object' && Array.isArray((body as { content?: unknown }).content)) {
    return (body as { content: T[] }).content;
  }
  return [];
}
