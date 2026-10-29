import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { addDays, APPEAL_FIELDS, buildAppeal, canAppeal, emptyAppealForm, toIhrAssessments, validateAppeal } from '../api/hr';
import { CLINICAL_CONTENT_MESSAGE } from '../api/referrals';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { appealIds } from '../components/ids';
import { findOne } from '../test/tree';
import { IhrCard, type IhrCardProps } from './IhrAssessments';

const TODAY = '2026-10-29';
const body = [
  {
    id: 'a1',
    type: 'REDEPLOYMENT',
    status: 'DECIDED',
    decisionText: 'Permanently unable to perform airside duties; suitable for office-based roles.',
    createdAt: '2026-10-01T09:00:00Z',
    decidedAt: '2026-10-20T09:00:00Z',
    findings: 'SECRET-FINDINGS',
    recommendation: 'SECRET-RECOMMENDATION',
    note: 'SECRET-NOTE',
    policyReference: 'SECRET-POLICY',
    physician: 'dr-secret',
  },
  { id: 'a2', type: 'ILL_HEALTH_RETIREMENT', status: 'APPEALED', decidedAt: '2026-09-01', appealedAt: '2026-09-20T10:00:00Z' },
  { id: 'a3', type: 'ILL_HEALTH_RETIREMENT', status: 'AWAITING_CMO' },
];
const [decided, appealed, awaiting] = toIhrAssessments(body);
const props = (over: Partial<IhrCardProps> = {}): IhrCardProps => ({
  assessment: decided!,
  today: TODAY,
  form: emptyAppealForm(),
  errors: {},
  busy: false,
  onForm: vi.fn(),
  onAppeal: vi.fn(),
  ...over,
});

describe('ill-health retirement / redeployment (E05-F07, HR view)', () => {
  it('keeps only type, status, decision text and dates', () => {
    expect(Object.keys(decided!).sort()).toEqual(['appealDecidedAt', 'appealDecisionDueAt', 'appealDeadline', 'appealedAt', 'createdAt', 'decidedAt', 'decisionText', 'id', 'status', 'type'].sort());
    expect(JSON.stringify(toIhrAssessments(body))).not.toMatch(/SECRET|dr-secret/);
  });

  it('derives the 28-day appeal window and the 30-day appeal decision date', () => {
    expect(decided!.appealDeadline).toBe('2026-11-17');
    expect(appealed!.appealDecisionDueAt).toBe('2026-10-20');
    expect(addDays('2026-01-31', 1)).toBe('2026-02-01');
    expect(toIhrAssessments([{ ...body[0], appealDeadline: '2026-11-01' }])[0]!.appealDeadline).toBe('2026-11-01');
  });

  it('renders status and the functional decision – no clinical fields', () => {
    const html = renderToStaticMarkup(<IhrCard {...props()} />);
    expect(html).toContain('Redeployment');
    expect(html).toContain('badge-decided');
    expect(html).toContain('suitable for office-based roles');
    expect(html).toContain('Appeal possible until');
    expect(html).not.toMatch(/SECRET|dr-secret/);
  });

  it('offers the appeal form only for a decided assessment inside the appeal window', () => {
    expect(canAppeal(decided!, TODAY)).toBe(true);
    expect(canAppeal(decided!, '2026-11-18')).toBe(false);
    expect(canAppeal(appealed!, TODAY)).toBe(false);
    expect(canAppeal(awaiting!, TODAY)).toBe(false);
    expect(renderToStaticMarkup(<IhrCard {...props()} />)).toContain(`id="${appealIds('a1').grounds}"`);
    const html = renderToStaticMarkup(<IhrCard {...props({ assessment: appealed! })} />);
    expect(html).not.toContain('<form');
    expect(html).toContain('Appeal decision due');
    expect(renderToStaticMarkup(<IhrCard {...props({ assessment: awaiting! })} />)).toContain('No decision yet');
  });

  it('validates and builds the appeal (grounds required, second opinion optional)', () => {
    expect(validateAppeal(emptyAppealForm())).toEqual({ grounds: 'Please state the grounds for the appeal.' });
    expect(validateAppeal({ grounds: 'x', secondOpinionBy: 'y'.repeat(201) })).toHaveProperty('secondOpinionBy');
    expect(buildAppeal({ grounds: ' Office roles were not considered ', secondOpinionBy: '' })).toEqual({ grounds: 'Office roles were not considered' });
    expect(buildAppeal({ grounds: 'g', secondOpinionBy: ' Dr Second ' })).toEqual({ grounds: 'g', secondOpinionBy: 'Dr Second' });
  });

  it('wires the appeal form', () => {
    const onForm = vi.fn();
    const onAppeal = vi.fn();
    const tree = IhrCard(props({ onForm, onAppeal }));
    findOne(tree, (e) => e.props.id === appealIds('a1').secondOpinionBy).props.onChange({ target: { value: 'Dr Second' } });
    expect(onForm).toHaveBeenCalledWith({ grounds: '', secondOpinionBy: 'Dr Second' });
    findOne(tree, (e) => e.type === 'form').props.onSubmit({});
    expect(onAppeal).toHaveBeenCalledOnce();
  });

  it('posts the appeal and highlights grounds on 422 clinical content', async () => {
    const ok = vi.fn().mockResolvedValue({});
    await submitAction({ api: ok }, '/ihr-assessments/a1/appeal', buildAppeal({ grounds: 'g', secondOpinionBy: '' }), APPEAL_FIELDS, { failed: 'x' });
    expect(ok).toHaveBeenCalledWith('/ihr-assessments/a1/appeal', { method: 'POST', body: { grounds: 'g' } });
    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'grounds' }));
    const r = await submitAction({ api }, '/ihr-assessments/a1/appeal', { grounds: 'g' }, APPEAL_FIELDS, { failed: 'The appeal was not lodged' });
    expect(r).toMatchObject({ ok: false, fieldErrors: { grounds: CLINICAL_CONTENT_MESSAGE } });
    const html = renderToStaticMarkup(<IhrCard {...props({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message })} />);
    expect(html).toMatch(/<textarea id="ihr-a1-grounds"[^>]*aria-invalid="true"/);
    expect(html).not.toMatch(/<input id="ihr-a1-secondOpinionBy"[^>]*aria-invalid/);
  });
});
