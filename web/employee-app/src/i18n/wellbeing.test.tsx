import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ADVISORY_SEVERITIES, CAMPAIGN_RISKS, CHECK_PHASES, DEVICE_TYPES, SCREENING_TYPES, SYMPTOMS, TRIP_PURPOSES, WEARABLE_METRICS } from '../api/prevention';
import { BANDS, BEST_TIMES, CONCERNS, INSTRUMENTS, SHARING_CHOICES, SUPPORT_CONTACTS } from '../api/support';
import { ApiError } from '../auth/AuthClient';
import { navFor } from '../routing/navigation';
import { WellbeingTiles } from '../screens/Home';
import { portalStrings } from './portal';
import { durationText, wellbeingErrorText, wellbeingStrings } from './wellbeing';

function keysOf(o: object, prefix = ''): string[] {
  return Object.entries(o)
    .flatMap(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v) ? keysOf(v as object, `${prefix}${k}.`) : [`${prefix}${k}`]))
    .sort();
}

const LANGS = ['en', 'ar', 'fr'];

describe('increment-5 strings (en / ar / fr)', () => {
  it('defines every string in every language, including nested label maps', () => {
    const en = keysOf(wellbeingStrings('en'));
    for (const l of ['ar', 'fr']) expect(keysOf(wellbeingStrings(l))).toEqual(en);
  });

  it('every value is non-empty and every function returns text in all languages', () => {
    for (const l of LANGS) {
      for (const [k, v] of Object.entries(wellbeingStrings(l) as unknown as Record<string, unknown>)) {
        if (typeof v === 'string') expect(v, `${l}.${k}`).not.toBe('');
        if (typeof v === 'function') expect(String((v as (...a: unknown[]) => unknown)('X', 2)), `${l}.${k}`).not.toBe('');
      }
    }
  });

  it('labels every contract enum value', () => {
    for (const l of LANGS) {
      const t = wellbeingStrings(l);
      const check = (map: Record<string, string>, values: readonly string[]) => values.forEach((v) => expect(map[v], `${l} ${v}`).toBeTruthy());
      check(t.concerns, CONCERNS);
      check(t.contacts, SUPPORT_CONTACTS);
      check(t.bestTimes, BEST_TIMES);
      check(t.choices, SHARING_CHOICES);
      check(t.choiceExplain, SHARING_CHOICES);
      check(t.instruments, INSTRUMENTS);
      check(t.bands, BANDS);
      check(t.bandHints, BANDS);
      check(t.symptoms, SYMPTOMS);
      check(t.phases, CHECK_PHASES);
      check(t.severity, ADVISORY_SEVERITIES);
      check(t.purposes, TRIP_PURPOSES);
      check(t.risks, CAMPAIGN_RISKS);
      check(t.screeningTypes, SCREENING_TYPES);
      check(t.devices, DEVICE_TYPES);
      check(t.metrics, WEARABLE_METRICS);
      check(t.windows, ['today', 'upcoming', 'missed', 'done']);
    }
  });

  it('nav labels exist for every increment-5 route in every language', () => {
    const items = navFor(['EMPLOYEE']).filter((n) => n.source === 'wellbeing');
    expect(items.map((n) => n.path)).toEqual(['/support', '/peer-support', '/check-ins', '/travel', '/campaigns', '/testing']);
    for (const l of LANGS) for (const n of items) expect(wellbeingStrings(l).nav[n.key as keyof ReturnType<typeof wellbeingStrings>['nav']], `${l} ${n.key}`).toBeTruthy();
  });

  it('wording rules: no diagnosis in screening texts, no result words for workplace checks', () => {
    for (const l of LANGS) {
      const t = wellbeingStrings(l);
      const screening = [t.scIntro, t.scWhoSees, t.scNotDiagnosis, t.scUrgentBody, ...Object.values(t.bands), ...Object.values(t.bandHints)].join(' ');
      expect(screening, l).not.toMatch(/diagnos|تشخيص/i);
      const checks = [t.teTitle, t.teIntro, t.tePrivacy, ...Object.values(t.phases)].join(' ');
      expect(checks, l).not.toMatch(/drug|alcohol|positive|negative|result|drogue|alcool|positif|négatif|résultat|مخدر|كحول|إيجابي|سلبي|نتيجة/i);
    }
  });

  it('falls back to English and resolves regional codes', () => {
    expect(wellbeingStrings('de').supTitle).toBe('Support for your wellbeing');
    expect(wellbeingStrings('ar-AE').teTitle).toBe('فحوصات العمل');
    expect(wellbeingStrings('fr_CA').caTitle).toBe('Campagnes de santé');
  });

  it('clinical-content and consent rejections get friendly text; the server detail is never shown', () => {
    const clinical = new ApiError(422, { code: 'clinical_content_not_allowed', title: 'Clinical content', detail: 'adjustments contains "depression"', field: 'adjustments' });
    for (const l of LANGS) expect(wellbeingErrorText(l, clinical)).toBe(wellbeingStrings(l).clinicalContent);
    expect(wellbeingErrorText('en', clinical)).not.toContain('depression');
    expect(wellbeingErrorText('fr', new ApiError(422, { code: 'consent_required', title: 'x' }))).toBe(wellbeingStrings('fr').consentRequired);
    expect(wellbeingErrorText('en', new ApiError(409))).toBe(portalStrings('en').errors.conflict);
  });

  it('durations read naturally', () => {
    expect(durationText(wellbeingStrings('en'), 135)).toBe('2 h 15 min');
    expect(durationText(wellbeingStrings('en'), 9)).toBe('9 min');
    expect(durationText(wellbeingStrings('ar'), 61)).toBe('1 س 1 د');
  });

  it('home tiles link to the new sections, with counts, right-to-left in Arabic', () => {
    const en = renderToStaticMarkup(<WellbeingTiles t={portalStrings('en')} wb={wellbeingStrings('en')} lang="en" counts={{ checkIns: 1, testing: 0 }} />);
    for (const href of ['#/support', '#/peer-support', '#/check-ins', '#/travel', '#/campaigns', '#/testing']) expect(en).toContain(`href="${href}"`);
    expect(en.match(/need/g)).toHaveLength(1);
    const ar = renderToStaticMarkup(<WellbeingTiles t={portalStrings('ar')} wb={wellbeingStrings('ar')} lang="ar" />);
    expect(ar).toMatch(/^<nav class="tiles" aria-labelledby="wb-tiles-title" dir="rtl" lang="ar">/);
    expect(ar).toContain(wellbeingStrings('ar').tiles.support);
  });
});
