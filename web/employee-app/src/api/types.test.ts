import { describe, expect, it } from 'vitest';
import {
  CASE_STATUS_LABELS,
  CASE_STATUSES,
  CATEGORY_LABELS,
  CONSENT_PURPOSE_LABELS,
  CONSENT_PURPOSES,
  DECLARATION_REASONS,
  FITNESS_LABELS,
  FITNESS_STATUSES,
  REFERRAL_CATEGORIES,
} from './types';

describe('API contract enums', () => {
  it('matches the backend self-referral categories', () => {
    expect([...REFERRAL_CATEGORIES]).toEqual([
      'INJURY', 'ILLNESS', 'DECREASE_IN_FITNESS', 'PSYCHOLOGICAL', 'SUBSTANCE', 'EXPOSURE', 'OTHER',
    ]);
    REFERRAL_CATEGORIES.forEach((c) => expect(CATEGORY_LABELS[c]).toBeTruthy());
  });

  it('matches the backend consent purposes', () => {
    expect([...CONSENT_PURPOSES]).toEqual([
      'TREATMENT_SHARING_PARTNER', 'EAP_REFERRAL', 'INSURER_SHARING', 'WEARABLE_DATA', 'HEALTH_CAMPAIGNS', 'RESEARCH_DEIDENTIFIED',
    ]);
    CONSENT_PURPOSES.forEach((p) => expect(CONSENT_PURPOSE_LABELS[p]).toBeTruthy());
  });

  it('matches the increment-1 case and fitness enums', () => {
    expect([...CASE_STATUSES]).toEqual(['INTAKE', 'TRIAGE', 'ASSESSMENT', 'FITNESS_REVIEW', 'TREATMENT', 'CLAIMS', 'MONITORING', 'CLOSED', 'MERGED']);
    expect([...FITNESS_STATUSES]).toContain('UNFIT_PENDING');
    CASE_STATUSES.forEach((c) => expect(CASE_STATUS_LABELS[c]).toBeTruthy());
    FITNESS_STATUSES.forEach((f) => expect(FITNESS_LABELS[f]).toBeTruthy());
    expect([...DECLARATION_REASONS]).toEqual(['ILLNESS', 'INJURY', 'MEDICATION', 'FATIGUE', 'PREGNANCY', 'OTHER']);
  });
});
