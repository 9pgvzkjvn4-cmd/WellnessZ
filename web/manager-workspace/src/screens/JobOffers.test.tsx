import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import {
  buildJobOffer,
  emptyJobOfferForm,
  JOB_OFFER_FIELDS,
  toJobOffers,
  toJobProfiles,
  toRestrictionCatalogue,
  validateJobOffer,
  type JobOfferForm,
} from '../api/hr';
import { CLINICAL_CONTENT_MESSAGE } from '../api/referrals';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { restrictionCheckboxId } from '../components/ids';
import { findAll, findOne } from '../test/tree';
import { JobOfferFormView, OfferList, type JobOfferFormViewProps } from './JobOffers';

const TODAY = '2026-10-29';
const catalogue = toRestrictionCatalogue([
  { code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', dimension: 'LIFT_KG', valueRequired: true, unit: 'kg', active: true },
  { code: 'NO_AIRSIDE', label: 'No airside driving', dimension: 'AIRSIDE_DRIVE', valueRequired: false, active: true },
  { code: 'OLD', label: 'Retired entry', active: false },
]);
const jobs = toJobProfiles([
  { jobCode: 'GND_BAGGAGE_SORT', title: 'Baggage sortation', unit: 'GND', demands: { LIFT_KG: 23 } },
  { jobCode: 'CORP_ADMIN', title: 'Administration assistant', unit: 'CORP' },
]);
const complete: JobOfferForm = {
  jobCode: 'CORP_ADMIN',
  jobTitle: ' Administration assistant ',
  duties: 'Data entry and filing for the ramp office',
  location: 'Hangar 3 office',
  shift: 'Mon–Fri 08:00–14:00',
  hoursPerWeek: '30',
  payBasis: 'PROTECTED_PAY',
  payNote: '',
  restrictions: [{ code: 'LIFT_MAX_KG', value: '10' }, { code: 'NO_AIRSIDE', value: '' }],
  startDate: '2026-11-09',
  endDate: '2026-12-31',
  contactName: 'Hannah HR',
  contactEmail: 'hannah.hr@example.com',
  respondBy: '2026-11-05',
};
const view = (over: Partial<JobOfferFormViewProps> = {}): JobOfferFormViewProps => ({
  form: complete,
  errors: {},
  submitting: false,
  catalogue,
  jobs,
  onPatch: vi.fn(),
  onSubmit: vi.fn(),
  ...over,
});

describe('job offer form (E05-BL-008)', () => {
  it('renders every JobOfferRequest field with catalogue restrictions and job profiles', () => {
    const html = renderToStaticMarkup(<JobOfferFormView {...view()} />);
    for (const k of JOB_OFFER_FIELDS) expect(html).toContain(`id="jo-${k}"`);
    expect(html).toContain('<option value="GND_BAGGAGE_SORT">Baggage sortation (GND_BAGGAGE_SORT, GND)</option>');
    expect(html).toContain('No lifting above … kg');
    expect(html).not.toContain('Retired entry');
    expect(html).toContain(`id="${restrictionCheckboxId('LIFT_MAX_KG')}-value"`);
    expect(html).not.toContain(`id="${restrictionCheckboxId('NO_AIRSIDE')}-value"`);
    expect(html).toContain('Do not include diagnoses or medical opinions');
    expect(html).not.toMatch(/id="jo-(diagnosis|findings|clinical)/);
  });

  it('validates required fields, e-mail, hours, restriction values and dates', () => {
    expect(Object.keys(validateJobOffer(emptyJobOfferForm(), catalogue, TODAY)).sort()).toEqual(
      ['contactEmail', 'contactName', 'duties', 'hoursPerWeek', 'jobCode', 'jobTitle', 'location', 'payBasis', 'respondBy', 'shift', 'startDate'].sort(),
    );
    expect(validateJobOffer(complete, catalogue, TODAY)).toEqual({});
    expect(validateJobOffer({ ...complete, contactEmail: 'nope' }, catalogue, TODAY)).toHaveProperty('contactEmail');
    expect(validateJobOffer({ ...complete, hoursPerWeek: '61' }, catalogue, TODAY)).toHaveProperty('hoursPerWeek');
    expect(validateJobOffer({ ...complete, hoursPerWeek: '0' }, catalogue, TODAY)).toHaveProperty('hoursPerWeek');
    expect(validateJobOffer({ ...complete, payBasis: 'ADJUSTED_PAY' }, catalogue, TODAY)).toEqual({ payNote: 'Please explain the adjusted pay.' });
    expect(validateJobOffer({ ...complete, restrictions: [{ code: 'LIFT_MAX_KG', value: '' }] }, catalogue, TODAY)).toHaveProperty('restrictions');
    expect(validateJobOffer({ ...complete, restrictions: [{ code: 'UNKNOWN', value: '' }] }, catalogue, TODAY)).toHaveProperty('restrictions');
    expect(validateJobOffer({ ...complete, endDate: '2026-11-01' }, catalogue, TODAY)).toHaveProperty('endDate');
    expect(validateJobOffer({ ...complete, respondBy: '2026-10-28' }, catalogue, TODAY)).toHaveProperty('respondBy');
    expect(validateJobOffer({ ...complete, respondBy: '2026-11-10' }, catalogue, TODAY)).toHaveProperty('respondBy');
    expect(validateJobOffer({ ...complete, duties: 'x'.repeat(1001) }, catalogue, TODAY)).toEqual({ duties: 'Please use at most 1000 characters.' });
  });

  it('builds the JobOfferRequest body', () => {
    expect(buildJobOffer(complete, catalogue)).toEqual({
      jobCode: 'CORP_ADMIN',
      jobTitle: 'Administration assistant',
      duties: 'Data entry and filing for the ramp office',
      location: 'Hangar 3 office',
      shift: 'Mon–Fri 08:00–14:00',
      hoursPerWeek: 30,
      payBasis: 'PROTECTED_PAY',
      restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }, { code: 'NO_AIRSIDE' }],
      startDate: '2026-11-09',
      endDate: '2026-12-31',
      contactName: 'Hannah HR',
      contactEmail: 'hannah.hr@example.com',
      respondBy: '2026-11-05',
    });
    expect(buildJobOffer({ ...complete, endDate: '', payNote: 'Protected 12 weeks' }, catalogue)).toMatchObject({ payNote: 'Protected 12 weeks' });
    expect(buildJobOffer({ ...complete, endDate: '' }, catalogue)).not.toHaveProperty('endDate');
  });

  it('takes the job title from the selected job-demand profile and toggles restrictions', () => {
    const onPatch = vi.fn();
    const tree = JobOfferFormView(view({ onPatch, form: { ...complete, restrictions: [] } }));
    findOne(tree, (e) => e.props.id === 'jo-jobCode').props.onChange({ target: { value: 'GND_BAGGAGE_SORT' } });
    expect(onPatch).toHaveBeenLastCalledWith({ jobCode: 'GND_BAGGAGE_SORT', jobTitle: 'Baggage sortation' });
    findOne(tree, (e) => e.props.id === restrictionCheckboxId('LIFT_MAX_KG')).props.onChange({ target: { checked: true } });
    expect(onPatch).toHaveBeenLastCalledWith({ restrictions: [{ code: 'LIFT_MAX_KG', value: '' }] });
    const radios = findAll(tree, (e) => e.type === 'input' && e.props.name === 'jo-payBasis');
    radios[2]!.props.onChange();
    expect(onPatch).toHaveBeenLastCalledWith({ payBasis: 'ADJUSTED_PAY' });
  });

  it('highlights the field named by a 422 clinical_content_not_allowed', async () => {
    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'duties', detail: 'ECHO' }));
    const r = await submitAction({ api }, '/cases/c1/job-offers', buildJobOffer(complete, catalogue), JOB_OFFER_FIELDS, { failed: 'The job offer was not issued' });
    expect(api).toHaveBeenCalledWith('/cases/c1/job-offers', { method: 'POST', body: expect.objectContaining({ jobCode: 'CORP_ADMIN' }) });
    expect(r).toMatchObject({ ok: false, fieldErrors: { duties: CLINICAL_CONTENT_MESSAGE } });
    expect(r.ok === false && r.message).toMatch(/The job offer was not issued because it seems to contain medical information/);
    const html = renderToStaticMarkup(<JobOfferFormView {...view({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message })} />);
    expect(html).toMatch(/<textarea id="jo-duties"[^>]*aria-invalid="true"/);
    expect(html).not.toMatch(/<input id="jo-location"[^>]*aria-invalid/);
    expect(html).not.toContain('ECHO');
  });

  it('maps indexed restriction fields onto the restriction group and ignores unknown fields', async () => {
    const mk = (field: string) => vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field }));
    const r1 = await submitAction({ api: mk('restrictions[1].value') }, '/x', {}, JOB_OFFER_FIELDS, { failed: 'x' });
    expect(r1).toMatchObject({ ok: false, fieldErrors: {} });
    const r2 = await submitAction({ api: mk('restrictions[1]') }, '/x', {}, JOB_OFFER_FIELDS, { failed: 'x' });
    expect(r2).toMatchObject({ ok: false, fieldErrors: { restrictions: CLINICAL_CONTENT_MESSAGE } });
    const r3 = await submitAction({ api: mk('body.payNote') }, '/x', {}, JOB_OFFER_FIELDS, { failed: 'x' });
    expect(r3).toMatchObject({ ok: false, fieldErrors: { payNote: CLINICAL_CONTENT_MESSAGE } });
  });
});

describe('job offer list', () => {
  const offers = toJobOffers([
    { id: 'o1', status: 'ISSUED', ...buildJobOffer(complete, catalogue), restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 10 }], issuedAt: '2026-10-29T09:00:00Z', documentId: 'doc-1' },
    { id: 'o2', status: 'DECLINED', jobTitle: 'Old offer', comment: 'SECRET-EMPLOYEE-COMMENT', respondedAt: '2026-10-20T09:00:00Z' },
  ]);

  it('shows offers with status and never the employee comment', () => {
    const html = renderToStaticMarkup(<OfferList offers={offers} onWithdraw={vi.fn()} onConfirm={vi.fn()} onCancel={vi.fn()} />);
    expect(html).toContain('badge-issued');
    expect(html).toContain('badge-declined');
    expect(html).toContain('Protected pay');
    expect(html).toContain('No lifting above 10 kg');
    expect(html).toContain('href="/bff/api/documents/doc-1/content"');
    expect(html).not.toContain('SECRET');
    expect(html.match(/Withdraw offer/g)).toHaveLength(2); // button text + aria-label, ISSUED offer only
  });

  it('withdraws in two steps', () => {
    const onWithdraw = vi.fn();
    const onConfirm = vi.fn();
    const first = OfferList({ offers, onWithdraw, onConfirm, onCancel: vi.fn() });
    findOne(first, (e) => e.type === 'button' && e.props.children === 'Withdraw offer').props.onClick();
    expect(onWithdraw).toHaveBeenCalledWith('o1');
    const second = OfferList({ offers, confirming: 'o1', onWithdraw, onConfirm, onCancel: vi.fn() });
    findOne(second, (e) => e.type === 'button' && e.props.children === 'Confirm withdrawal').props.onClick();
    expect(onConfirm).toHaveBeenCalledWith('o1');
  });

  it('posts withdraw to the contract path', async () => {
    const api = vi.fn().mockResolvedValue(undefined);
    expect(await submitAction({ api }, '/job-offers/o1/withdraw', {}, [], { failed: 'x' })).toMatchObject({ ok: true });
    expect(api).toHaveBeenCalledWith('/job-offers/o1/withdraw', { method: 'POST', body: {} });
  });
});
