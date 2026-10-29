import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { initialReferralState, referralReducer } from '../intake/referralForm';
import type { FileLike } from '../intake/attachments';
import { SelfReferralForm, type SelfReferralFormProps } from '../screens/SelfReferralForm';
import { CATERING, REGIONS } from '../test/fixtures';
import { findOne } from '../test/tree';
import { baseLang, DEFAULT_LANGUAGES, dirFor, initialLanguage, stringsFor } from './strings';

function render(language: string, onLanguage = vi.fn()) {
  const props: SelfReferralFormProps<FileLike> = {
    state: { ...initialReferralState<FileLike>(language), templateCode: 'CATERING' },
    dispatch: vi.fn(),
    t: stringsFor(language),
    languages: DEFAULT_LANGUAGES,
    templates: [CATERING],
    template: CATERING,
    regions: REGIONS,
    errors: {},
    submitting: false,
    isNative: false,
    onLanguage,
    onTemplate: vi.fn(),
    onFiles: vi.fn(),
    onSubmit: vi.fn(),
  };
  return { props, html: renderToStaticMarkup(<SelfReferralForm {...props} />) };
}

describe('language switch and RTL', () => {
  it('renders Arabic right-to-left with dir="rtl" and lang="ar"', () => {
    const { html } = render('ar');
    expect(html).toMatch(/^<div class="referral" dir="rtl" lang="ar"/);
    expect(html).toContain(stringsFor('ar').title);
    expect(html).toContain('إرسال إلى الصحة المهنية'); // submit button
  });

  it('renders English and French left-to-right', () => {
    expect(render('en').html).toMatch(/^<div class="referral" dir="ltr" lang="en"/);
    const fr = render('fr').html;
    expect(fr).toMatch(/dir="ltr" lang="fr"/);
    expect(fr).toContain('Envoyer à la Santé au travail');
  });

  it('switching the language changes direction (reducer + selector)', () => {
    const onLanguage = vi.fn();
    const { props } = render('en', onLanguage);
    const select = findOne(SelfReferralForm(props), (e) => e.type === 'select' && e.props.id === 'rf-language');
    select.props.onChange({ target: { value: 'ar' } });
    expect(onLanguage).toHaveBeenCalledWith('ar');
    const s = referralReducer(props.state, { type: 'language', language: 'ar' });
    expect(dirFor(s.language)).toBe('rtl');
    expect(render(s.language).html).toContain('dir="rtl"');
  });

  it('keeps the body-map drawing unmirrored in RTL (anatomical left/right)', () => {
    expect(render('ar').html).toMatch(/<svg class="body-map-svg"/);
  });

  it('resolves direction and strings for regional codes and falls back to English', () => {
    expect(dirFor('ar-AE')).toBe('rtl');
    expect(dirFor('fr')).toBe('ltr');
    expect(dirFor(undefined)).toBe('ltr');
    expect(baseLang('fr_CA')).toBe('fr');
    expect(stringsFor('de').title).toBe(stringsFor('en').title);
  });

  it('picks the initial language from browser preferences', () => {
    expect(initialLanguage(DEFAULT_LANGUAGES, ['fr-FR', 'en'])).toBe('fr');
    expect(initialLanguage(DEFAULT_LANGUAGES, ['de'])).toBe('en');
    expect(initialLanguage([], [])).toBe('en');
  });

  it('defines every string in every language', () => {
    const keys = Object.keys(stringsFor('en')).sort();
    for (const l of ['ar', 'fr']) expect(Object.keys(stringsFor(l)).sort()).toEqual(keys);
  });
});
