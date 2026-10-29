import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { PortalTiles } from '../screens/Home';
import { errorText, labelOf, portalStrings } from './portal';

/** Keys of nested string maps, e.g. `docTypes.FITNESS_CERTIFICATE`. */
function keysOf(o: object, prefix = ''): string[] {
  return Object.entries(o)
    .flatMap(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v) ? keysOf(v as object, `${prefix}${k}.`) : [`${prefix}${k}`]))
    .sort();
}

describe('portal strings (en / ar / fr)', () => {
  it('defines every string in every language, including nested labels', () => {
    const en = keysOf(portalStrings('en'));
    for (const l of ['ar', 'fr']) expect(keysOf(portalStrings(l))).toEqual(en);
    for (const l of ['ar', 'fr']) expect(portalStrings(l).declDefaultSteps).toHaveLength(portalStrings('en').declDefaultSteps.length);
  });

  it('falls back to English and resolves regional codes', () => {
    expect(portalStrings('de').docsTitle).toBe('My documents');
    expect(portalStrings('ar-AE').docsTitle).toBe('مستنداتي');
    expect(portalStrings('fr_CA').docsTitle).toBe('Mes documents');
  });

  it('labels codes, humanises unknown codes and keeps free text', () => {
    const t = portalStrings('en');
    expect(labelOf(t.docTypes, 'RTW_PLAN')).toBe('Return-to-work plan');
    expect(labelOf(t.docTypes, 'NEW_TYPE')).toBe('New type');
    expect(labelOf(t.purposes, 'Occupational therapy')).toBe('Occupational therapy');
    expect(labelOf(t.purposes, undefined, '–')).toBe('–');
  });

  it('maps errors to localised text and never shows problem detail', () => {
    const t = portalStrings('fr');
    expect(errorText(t, new SessionExpiredError())).toBe(t.errors.session);
    expect(errorText(t, new ApiError(403))).toBe(t.errors.forbidden);
    expect(errorText(t, new ApiError(409))).toBe(t.errors.conflict);
    expect(errorText(t, new ApiError(503))).toBe(t.errors.unavailable);
    expect(errorText(t, new ApiError(422, { title: 'Comment too long', detail: 'secret echo' }))).toBe('Comment too long');
    expect(errorText(t, new ApiError(400))).toBe(t.errors.failed);
    expect(errorText(t, new TypeError('fetch failed'))).toBe(t.errors.network);
  });

  it('home tiles link to the new screens with pending counts, right-to-left in Arabic', () => {
    const en = renderToStaticMarkup(<PortalTiles t={portalStrings('en')} lang="en" counts={{ documents: 2, rtw: 1, offers: 0 }} />);
    for (const href of ['#/documents', '#/rtw', '#/job-offers', '#/privacy']) expect(en).toContain(`href="${href}"`);
    expect(en).toContain('2 items need your attention');
    expect(en).toContain('1 item needs your attention');
    expect(en.match(/need/g)).toHaveLength(2); // no badge for 0
    const ar = renderToStaticMarkup(<PortalTiles t={portalStrings('ar')} lang="ar" />);
    expect(ar).toMatch(/^<nav class="tiles" aria-labelledby="tiles-title" dir="rtl" lang="ar">/);
    expect(ar).toContain('من تلقّى معلوماتي');
  });
});
