import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { toRtwPlan, type MyRtwPlan } from '../api/portal';
import { portalStrings } from '../i18n/portal';
import { SESSION, stubClient } from '../test/client';
import { RTW_PLANS } from '../test/portalFixtures';
import { findAll, findOne, textOf } from '../test/tree';
import { MyReturnToWork, RtwPlanList, type RtwPlanListProps } from './MyReturnToWork';

const plans = RTW_PLANS.map(toRtwPlan).filter((p): p is MyRtwPlan => !!p);

function props(over: Partial<RtwPlanListProps> = {}): RtwPlanListProps {
  return { t: portalStrings('en'), lang: 'en', plans, comments: {}, onComment: vi.fn(), onAgree: vi.fn(), ...over };
}

describe('My return to work', () => {
  it('renders phases (hours, duties, restrictions), review dates and contact', () => {
    const html = renderToStaticMarkup(<RtwPlanList {...props()} />);
    expect(html).toContain('GND-2026-000042');
    expect(html).toContain('Weeks 1–2');
    expect(html).toContain('4 hours per day');
    expect(html).toContain('Baggage reconciliation office');
    expect(html).toContain('No lifting above 5 kg');
    expect(html).toContain('No airside driving');
    expect(html).toContain('Week 1<'); // p2 single-week phase
    expect(html).toContain('No restrictions in this phase');
    expect(html).toMatch(/reviewed .*2026/);
    expect(html).toContain('Back to full ramp duties');
    expect(html).toMatch(/Your manager acknowledged the plan on/);
    expect(html).toContain('href="#/refer"'); // p1 has no contact → fallback
    expect(html).toContain('mailto:oh@example.com'); // p2 contact
    expect(html.indexOf('Weeks 1–2')).toBeLessThan(html.indexOf('Weeks 3–4'));
  });

  it('agree flow: comment is passed up and submit calls onAgree', () => {
    const p = props({ comments: { p1: 'Can I start at 9?' } });
    const tree = RtwPlanList(p);
    const textarea = findOne(tree, (e) => e.type === 'textarea');
    expect(textarea.props.value).toBe('Can I start at 9?');
    textarea.props.onChange({ target: { value: 'New text' } });
    expect(p.onComment).toHaveBeenCalledWith('p1', 'New text');
    const form = findOne(tree, (e) => e.type === 'form');
    const preventDefault = vi.fn();
    form.props.onSubmit({ preventDefault });
    expect(preventDefault).toHaveBeenCalled();
    expect(p.onAgree).toHaveBeenCalledWith(expect.objectContaining({ id: 'p1' }));
    expect(textOf(findOne(tree, (e) => e.type === 'button').props.children)).toBe('I agree to this plan');
  });

  it('shows the agreed state with the comment instead of the form', () => {
    const agreed = plans.map((p) => ({ ...p, employeeAck: { at: '2026-10-29T10:00:00Z', agree: true, comment: 'Fine' } }));
    const tree = RtwPlanList(props({ plans: agreed }));
    expect(findAll(tree, (e) => e.type === 'form')).toHaveLength(0);
    const html = renderToStaticMarkup(<RtwPlanList {...props({ plans: agreed })} />);
    expect(html).toMatch(/✓ You agreed to this plan on/);
    expect(html).toContain('Fine');
  });

  it('busy state disables the button', () => {
    const btn = findOne(RtwPlanList(props({ busyId: 'p1' })), (e) => e.type === 'button');
    expect(btn.props.disabled).toBe(true);
    expect(textOf(btn.props.children)).toBe('Saving…');
  });

  it('empty state', () => {
    expect(renderToStaticMarkup(<RtwPlanList {...props({ plans: [] })} />)).toContain('You have no active return-to-work plan.');
  });

  it('renders Arabic right-to-left and French', () => {
    const ar = renderToStaticMarkup(<MyReturnToWork client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="ar" />);
    expect(ar).toMatch(/^<section class="portal" dir="rtl" lang="ar"><h1[^>]*>عودتي إلى العمل<\/h1>/);
    expect(renderToStaticMarkup(<RtwPlanList {...props({ t: portalStrings('ar'), lang: 'ar' })} />)).toContain('أوافق على هذه الخطة');
    expect(renderToStaticMarkup(<RtwPlanList {...props({ t: portalStrings('fr'), lang: 'fr' })} />)).toContain('Semaines 1 à 2');
  });
});
