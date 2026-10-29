import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FOLLOW_UP_STATUSES, FOLLOW_UP_TYPES, INTERVENTION_TYPES, PLAN_STATUSES, TELEHEALTH_STATUSES } from '../api/care';
import { CONSENT_PURPOSES } from '../api/types';
import { ApiError } from '../auth/AuthClient';
import { CareTiles } from '../screens/Home';
import { careErrorText, careStrings } from './care';
import { portalStrings } from './portal';

function keysOf(o: object, prefix = ''): string[] {
  return Object.entries(o)
    .flatMap(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v) ? keysOf(v as object, `${prefix}${k}.`) : [`${prefix}${k}`]))
    .sort();
}

describe('increment-4 strings (en / ar / fr)', () => {
  it('defines every string in every language, including nested label maps', () => {
    const en = keysOf(careStrings('en'));
    for (const l of ['ar', 'fr']) expect(keysOf(careStrings(l))).toEqual(en);
  });

  it('every value is non-empty and every function returns text in all languages', () => {
    for (const l of ['en', 'ar', 'fr']) {
      const t = careStrings(l) as unknown as Record<string, unknown>;
      for (const [k, v] of Object.entries(t)) {
        if (typeof v === 'string') expect(v, `${l}.${k}`).not.toBe('');
        if (typeof v === 'function') expect(String((v as (...a: unknown[]) => unknown)('X', 2)), `${l}.${k}`).not.toBe('');
      }
    }
  });

  it('labels every contract enum value', () => {
    for (const l of ['en', 'ar', 'fr']) {
      const t = careStrings(l);
      INTERVENTION_TYPES.forEach((v) => expect(t.interventionTypes[v], `${l} ${v}`).toBeTruthy());
      FOLLOW_UP_TYPES.forEach((v) => expect(t.followUpTypes[v], `${l} ${v}`).toBeTruthy());
      FOLLOW_UP_STATUSES.forEach((v) => expect(t.followUpStatus[v], `${l} ${v}`).toBeTruthy());
      TELEHEALTH_STATUSES.forEach((v) => expect(t.sessionStatus[v], `${l} ${v}`).toBeTruthy());
      PLAN_STATUSES.filter((s) => s !== 'DRAFT').forEach((v) => expect(t.planStatus[v], `${l} ${v}`).toBeTruthy());
      expect(t.mode.VIDEO && t.mode.CHAT).toBeTruthy();
    }
  });

  it('consent purposes, including TELEHEALTH_CONSULTATION, are labelled in every language for the privacy screen', () => {
    for (const l of ['en', 'ar', 'fr']) {
      const p = portalStrings(l).purposes;
      CONSENT_PURPOSES.forEach((c) => expect(p[c], `${l} ${c}`).toBeTruthy());
    }
  });

  it('falls back to English and resolves regional codes', () => {
    expect(careStrings('de').trTitle).toBe('My treatment plan');
    expect(careStrings('ar-AE').trTitle).toBe('خطة علاجي');
    expect(careStrings('fr_CA').thTitle).toBe('Consultations vidéo et chat');
  });

  it('clinical-content and consent rejections get friendly text; the server detail is never shown', () => {
    const clinical = new ApiError(422, { code: 'clinical_content_not_allowed', title: 'Clinical content', detail: 'reason contains "slipped disc"', field: 'reason' });
    for (const l of ['en', 'ar', 'fr']) expect(careErrorText(l, clinical)).toBe(careStrings(l).clinicalContent);
    expect(careErrorText('en', clinical)).not.toContain('slipped');
    expect(careErrorText('fr', new ApiError(422, { code: 'consent_required', title: 'Consent required' }))).toBe(careStrings('fr').consentRequired);
    expect(careErrorText('en', new ApiError(409))).toBe(portalStrings('en').errors.conflict);
  });

  it('home tiles link to the new sections, with counts, right-to-left in Arabic', () => {
    const en = renderToStaticMarkup(<CareTiles t={portalStrings('en')} care={careStrings('en')} lang="en" counts={{ treatment: 2, telehealth: 0 }} />);
    expect(en).toContain('href="#/treatment"');
    expect(en).toContain('href="#/telehealth"');
    expect(en).toContain('2 items need your attention');
    expect(en.match(/need/g)).toHaveLength(1);
    const ar = renderToStaticMarkup(<CareTiles t={portalStrings('ar')} care={careStrings('ar')} lang="ar" />);
    expect(ar).toMatch(/^<nav class="tiles" aria-labelledby="care-tiles-title" dir="rtl" lang="ar">/);
    expect(ar).toContain(careStrings('ar').tileTelehealth);
  });
});
