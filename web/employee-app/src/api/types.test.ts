import { describe, expect, it } from 'vitest';
import { CATEGORY_LABELS, CONSENT_PURPOSE_LABELS, CONSENT_PURPOSES, REFERRAL_CATEGORIES } from './types';

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
});
