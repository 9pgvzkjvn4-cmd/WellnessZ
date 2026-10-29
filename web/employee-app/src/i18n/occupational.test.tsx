import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import { OhTiles } from '../screens/Home';
import { ohErrorText, ohStrings, type OhStrings } from './occupational';
import { portalStrings } from './portal';

function keysOf(o: object, prefix = ''): string[] {
  return Object.entries(o)
    .flatMap(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v) ? keysOf(v as object, `${prefix}${k}.`) : [`${prefix}${k}`]))
    .sort();
}

describe('increment-3 strings (en / ar / fr)', () => {
  it('defines every string in every language, including nested label maps', () => {
    const en = keysOf(ohStrings('en'));
    for (const l of ['ar', 'fr']) expect(keysOf(ohStrings(l))).toEqual(en);
  });

  it('every value is non-empty and every function returns text in all languages', () => {
    for (const l of ['en', 'ar', 'fr']) {
      const t = ohStrings(l) as unknown as Record<string, unknown>;
      for (const [k, v] of Object.entries(t)) {
        if (typeof v === 'string') expect(v, `${l}.${k}`).not.toBe('');
        if (typeof v === 'function') expect(String((v as (...a: unknown[]) => unknown)('X', 2)), `${l}.${k}`).not.toBe('');
      }
    }
  });

  it('falls back to English and resolves regional codes', () => {
    expect(ohStrings('de').apptTitle).toBe('My appointments');
    expect(ohStrings('ar-AE').apptTitle).toBe('مواعيدي');
    expect(ohStrings('fr_CA').apptTitle).toBe('Mes rendez-vous');
  });

  it('explains clinical-content rejections specifically, other errors like the portal', () => {
    expect(ohErrorText('fr', new ApiError(422, { code: 'clinical_content', title: 'x' }))).toBe(ohStrings('fr').clinicalContent);
    expect(ohErrorText('en', new ApiError(409))).toBe(portalStrings('en').errors.conflict);
    expect(ohErrorText('en', new ApiError(422, { code: 'self_booking_notice', title: 'Too short notice', detail: 'echo' }))).toBe('Too short notice');
  });

  it('home tiles for the new sections, with counts, right-to-left in Arabic', () => {
    const en = renderToStaticMarkup(<OhTiles t={portalStrings('en')} oh={ohStrings('en')} lang="en" counts={{ appointments: 1, advice: 2, surveillance: 0 }} />);
    for (const href of ['#/appointments', '#/advice', '#/absence', '#/surveillance']) expect(en).toContain(`href="${href}"`);
    expect(en).toContain('2 items need your attention');
    expect(en.match(/need/g)).toHaveLength(2);
    const ar = renderToStaticMarkup(<OhTiles t={portalStrings('ar')} oh={ohStrings('ar')} lang="ar" />);
    expect(ar).toMatch(/^<nav class="tiles" aria-labelledby="oh-tiles-title" dir="rtl" lang="ar">/);
    expect(ar).toContain(ohStrings('ar').tileAbsence);
  });

  it('typed access compiles for every key', () => {
    const t: OhStrings = ohStrings('en');
    expect(t.fieldErrors.fileSize).toContain('10 MB');
  });
});
