import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { stringsFor } from '../i18n/strings';
import { SelfReferralForm, type SelfReferralFormProps } from '../screens/SelfReferralForm';
import { CATERING, CORPORATE, REGIONS } from '../test/fixtures';
import { findAll, findOne } from '../test/tree';
import type { FileLike } from './attachments';
import {
  buildSelfReferral,
  FORM_KEYS,
  initialReferralState,
  pickDefaultTemplate,
  referralProgress,
  referralReducer,
  validateReferral,
  type ReferralState,
} from './referralForm';

const t = stringsFor('en');

function formProps(over: Partial<SelfReferralFormProps<FileLike>> = {}): SelfReferralFormProps<FileLike> {
  return {
    state: { ...initialReferralState<FileLike>('en'), templateCode: 'CATERING' },
    dispatch: vi.fn(),
    t,
    languages: [{ code: 'en', label: 'English' }, { code: 'ar', label: 'العربية' }, { code: 'fr', label: 'Français' }],
    templates: [CATERING, CORPORATE],
    template: CATERING,
    regions: REGIONS,
    errors: {},
    submitting: false,
    isNative: false,
    onLanguage: vi.fn(),
    onTemplate: vi.fn(),
    onFiles: vi.fn(),
    onSubmit: vi.fn(),
    ...over,
  };
}

function filled(): ReferralState<FileLike> {
  let s: ReferralState<FileLike> = { ...initialReferralState<FileLike>('en'), templateCode: 'CATERING' };
  const d = (a: Parameters<typeof referralReducer<FileLike>>[1]) => (s = referralReducer(s, a));
  d({ type: 'category', value: 'INJURY' });
  d({ type: 'answer', key: 'injuryType', value: 'CUT' });
  d({ type: 'answer', key: 'where', value: '  Kitchen 3 ' });
  d({ type: 'answer', key: 'handledFood', value: false });
  d({ type: 'answer', key: 'gi', value: ['VOMITING'] });
  d({ type: 'answer', key: 'onset', value: '2026-10-28' });
  d({ type: 'toggleBodyPart', code: 'HAND_R' });
  d({ type: 'toggleSymptom', value: 'PAIN' });
  d({ type: 'description', value: ' Cut my hand on a slicer. ' });
  d({ type: 'consent', value: true });
  return s;
}

describe('template-driven field rendering', () => {
  it('renders a control per field type from the template', () => {
    const html = renderToStaticMarkup(<SelfReferralForm {...formProps()} />);
    expect(html).toMatch(/<select id="f-injuryType"[^>]*aria-required="true"/);
    expect(html).toContain('<option value="CUT">Cut</option>');
    expect(html).toMatch(/<input id="f-where" type="text" maxLength="20"/);
    expect(html).toMatch(/<textarea id="f-details"[^>]*maxLength="500"/);
    expect(html).toMatch(/<input id="f-onset" type="date"/);
    // BOOLEAN → yes/no radios, MULTISELECT → checkboxes
    expect(findAll(SelfReferralForm(formProps()), (e) => e.type === 'input' && e.props.name === 'f-handledFood')).toHaveLength(2);
    expect(html).toContain('value="VOMITING"');
    // BODY_MAP / SYMPTOMS use the template labels and symptomOptions
    expect(html).toContain('Where does it hurt?');
    expect(html).toContain('value="SWELLING"');
    // required marker on required fields only
    expect(html).toMatch(/What happened\?<span class="req"/);
    expect(html).not.toMatch(/Anything else\?<span class="req"/);
  });

  it('changes the rendered fields when another template is selected', () => {
    const html = renderToStaticMarkup(<SelfReferralForm {...formProps({ template: CORPORATE, state: { ...initialReferralState<FileLike>('en'), templateCode: 'CORPORATE' } })} />);
    expect(html).toContain('Workstation issue?');
    expect(html).not.toContain('f-injuryType');
    expect(html).not.toContain('value="SWELLING"'); // no symptom options in this template
  });

  it('dispatches answers with the field key', () => {
    const p = formProps();
    const select = findOne(SelfReferralForm(p), (e) => e.type === 'select' && e.props.id === 'f-injuryType');
    select.props.onChange({ target: { value: 'BURN' } });
    expect(p.dispatch).toHaveBeenCalledWith({ type: 'answer', key: 'injuryType', value: 'BURN' });
    const yes = findAll(SelfReferralForm(p), (e) => e.type === 'input' && e.props.name === 'f-handledFood')[0]!;
    yes.props.onChange();
    expect(p.dispatch).toHaveBeenCalledWith({ type: 'answer', key: 'handledFood', value: true });
  });

  it('suggests the department template', () => {
    const html = renderToStaticMarkup(<SelfReferralForm {...formProps({ suggestedTemplateCode: 'CATERING' })} />);
    expect(html).toContain('Suggested for your department');
  });
});

describe('required validation', () => {
  it('reports every missing required item', () => {
    const e = validateReferral({ ...initialReferralState<FileLike>('en') }, CATERING, t);
    expect(Object.keys(e).sort()).toEqual(
      [FORM_KEYS.category, 'injuryType', 'where', 'handledFood', 'gi', 'onset', 'body', FORM_KEYS.description, FORM_KEYS.consent].sort(),
    );
    expect(e.injuryType).toBe('This field is required.');
    expect(e[FORM_KEYS.consent]).toBe(t.errConsent);
    expect(validateReferral(initialReferralState<FileLike>('en'), undefined, t)[FORM_KEYS.template]).toBe(t.errTemplate);
  });

  it('accepts a complete form – a "No" answer counts as answered, whitespace does not', () => {
    expect(validateReferral(filled(), CATERING, t)).toEqual({});
    const blank = referralReducer(filled(), { type: 'answer', key: 'where', value: '   ' });
    expect(validateReferral(blank, CATERING, t)).toEqual({ where: 'This field is required.' });
  });

  it('enforces maxLength and the 4000-character description', () => {
    let s = referralReducer(filled(), { type: 'answer', key: 'where', value: 'x'.repeat(21) });
    s = referralReducer(s, { type: 'description', value: 'y'.repeat(4001) });
    expect(validateReferral(s, CATERING, t)).toEqual({ where: 'Please use at most 20 characters.', [FORM_KEYS.description]: 'Please use at most 4000 characters.' });
  });

  it('requires a body part only when the template field is required', () => {
    const s = referralReducer(filled(), { type: 'toggleBodyPart', code: 'HAND_R' });
    expect(validateReferral(s, CATERING, t)).toEqual({ body: 'This field is required.' });
  });

  it('renders field errors with aria-invalid and an error summary', () => {
    const errors = validateReferral(initialReferralState<FileLike>('en'), CATERING, t);
    const html = renderToStaticMarkup(<SelfReferralForm {...formProps({ errors })} />);
    expect(html).toContain('id="rf-error-summary"');
    expect(html).toMatch(/<select id="f-injuryType" aria-invalid="true" aria-describedby="f-injuryType-error"/);
    expect(html).toContain('href="#f-injuryType"');
    expect(html).toContain('What happened?: This field is required.');
  });

  it('tracks progress over required items', () => {
    // template, category, 6 required template fields, description, consent
    expect(referralProgress(initialReferralState<FileLike>('en'), CATERING)).toEqual({ done: 1, total: 10 });
    expect(referralProgress(filled(), CATERING)).toEqual({ done: 10, total: 10 });
    expect(referralProgress(initialReferralState<FileLike>('en'), undefined)).toEqual({ done: 0, total: 4 });
  });
});

describe('request building', () => {
  it('builds the SelfReferral body with template answers, structured parts and consent', () => {
    const body = buildSelfReferral(referralReducer(filled(), { type: 'togglePurpose', value: 'EAP_REFERRAL' }), CATERING);
    expect(body).toEqual({
      templateCode: 'CATERING',
      category: 'INJURY',
      description: 'Cut my hand on a slicer.',
      bodyParts: ['HAND_R'],
      symptoms: ['PAIN'],
      answers: { injuryType: 'CUT', where: 'Kitchen 3', handledFood: false, gi: ['VOMITING'], onset: '2026-10-28' },
      preferredContact: 'EMAIL',
      language: 'en',
      consent: { assessment: true, purposes: ['EAP_REFERRAL'] },
    });
  });

  it('drops answers of fields that are not in the newly selected template', () => {
    const s = referralReducer(filled(), { type: 'template', code: 'CORPORATE', template: CORPORATE });
    expect(s.answers).toEqual({ where: '  Kitchen 3 ' });
  });

  it('pre-selects the template of the employee’s department', () => {
    expect(pickDefaultTemplate([CATERING, CORPORATE], ['catering'])).toBe('CATERING');
    expect(pickDefaultTemplate([CATERING, CORPORATE], [undefined, 'FINANCE'])).toBe('CORPORATE');
    expect(pickDefaultTemplate([CATERING, CORPORATE], ['RAMP'])).toBe('');
    expect(pickDefaultTemplate([CORPORATE], [])).toBe('CORPORATE');
  });
});
