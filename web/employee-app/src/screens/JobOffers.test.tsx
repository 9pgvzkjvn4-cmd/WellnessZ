import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { toJobOffer, type MyJobOffer } from '../api/portal';
import { portalStrings } from '../i18n/portal';
import { SESSION, stubClient } from '../test/client';
import { JOB_OFFERS } from '../test/portalFixtures';
import { findAll, findOne, textOf } from '../test/tree';
import { JobOfferList, JobOffers, type JobOfferListProps } from './JobOffers';

const offers = JOB_OFFERS.map(toJobOffer).filter((o): o is MyJobOffer => !!o);
const NOW = new Date(2026, 9, 29, 12, 0);

function props(over: Partial<JobOfferListProps> = {}): JobOfferListProps {
  return {
    t: portalStrings('en'),
    lang: 'en',
    offers,
    comments: {},
    busy: false,
    now: NOW,
    onComment: vi.fn(),
    onChoose: vi.fn(),
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    ...over,
  };
}

const button = (tree: ReturnType<typeof JobOfferList>, label: string) =>
  findOne(tree, (e) => e.type === 'button' && textOf(e.props.children) === label);

describe('Job offers', () => {
  it('shows role, duties, location, shift, hours, pay, restrictions, dates, respond-by and contact', () => {
    const html = renderToStaticMarkup(<JobOfferList {...props()} />);
    for (const s of [
      'Baggage reconciliation agent',
      'GND-OFFICE',
      'Reconcile baggage manifests',
      'Terminal 1, Ops building',
      'Day shift 08:00–16:00',
      '30 hours per week',
      'Pay protected – Ramp allowance continues for 8 weeks',
      'Restrictions respected',
      'Lift max kg: 5',
      'Please reply by',
      'Hana HR',
      'mailto:hr@example.com',
      'Awaiting your reply',
      'Accepted',
      'Until reviewed', // o0 has no end date
    ])
      expect(html).toContain(s);
    expect(html).not.toContain('The reply date has passed');
  });

  it('accept asks for confirmation first, then confirms', () => {
    const p = props();
    button(JobOfferList(p), 'Accept offer').props.onClick();
    expect(p.onChoose).toHaveBeenCalledWith('o1', true);
    const confirming = JobOfferList(props({ ...p, pending: { offerId: 'o1', accept: true } }));
    expect(findAll(confirming, (e) => e.props.role === 'alertdialog')).toHaveLength(1);
    expect(findAll(confirming, (e) => e.type === 'button' && textOf(e.props.children) === 'Accept offer')).toHaveLength(0);
    const yes = button(confirming, 'Yes, accept');
    expect(yes.props.autoFocus).toBe(true);
    yes.props.onClick();
    expect(p.onConfirm).toHaveBeenCalled();
    expect(renderToStaticMarkup(<JobOfferList {...props({ pending: { offerId: 'o1', accept: true } })} />)).toContain('replaces a signature on paper');
  });

  it('decline with comment and cancel', () => {
    const p = props({ comments: { o1: 'Prefer nights' } });
    const tree = JobOfferList(p);
    const ta = findOne(tree, (e) => e.type === 'textarea');
    expect(ta.props.value).toBe('Prefer nights');
    ta.props.onChange({ target: { value: 'x' } });
    expect(p.onComment).toHaveBeenCalledWith('o1', 'x');
    button(tree, 'Decline offer').props.onClick();
    expect(p.onChoose).toHaveBeenCalledWith('o1', false);
    const confirming = JobOfferList({ ...p, pending: { offerId: 'o1', accept: false } });
    expect(button(confirming, 'Yes, decline').props.className).toBe('danger');
    button(confirming, 'Cancel').props.onClick();
    expect(p.onCancel).toHaveBeenCalled();
  });

  it('disables the confirmation while sending', () => {
    const tree = JobOfferList(props({ pending: { offerId: 'o1', accept: true }, busy: true }));
    expect(button(tree, 'Sending…').props.disabled).toBe(true);
    expect(button(tree, 'Cancel').props.disabled).toBe(true);
  });

  it('responded offers show the reply instead of buttons', () => {
    const responded = offers.map((o) => ({ ...o, status: 'DECLINED', respondedAt: '2026-10-29T12:00:00Z', comment: 'Prefer nights' }));
    const tree = JobOfferList(props({ offers: responded }));
    expect(findAll(tree, (e) => e.type === 'button')).toHaveLength(0);
    const html = renderToStaticMarkup(<JobOfferList {...props({ offers: responded })} />);
    expect(html).toMatch(/You replied on/);
    expect(html).toContain('Declined');
  });

  it('warns when the reply date has passed', () => {
    expect(renderToStaticMarkup(<JobOfferList {...props({ now: new Date(2026, 10, 2) })} />)).toContain('The reply date has passed');
  });

  it('empty state', () => {
    expect(renderToStaticMarkup(<JobOfferList {...props({ offers: [] })} />)).toContain('You have no job offers.');
  });

  it('renders Arabic right-to-left and French', () => {
    const ar = renderToStaticMarkup(<JobOffers client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="ar" />);
    expect(ar).toMatch(/^<section class="portal" dir="rtl" lang="ar"><h1[^>]*>عروض العمل<\/h1>/);
    const arView = renderToStaticMarkup(<JobOfferList {...props({ t: portalStrings('ar'), lang: 'ar' })} />);
    expect(arView).toContain('قبول العرض');
    expect(arView).toContain('أجر محمي');
    expect(renderToStaticMarkup(<JobOfferList {...props({ t: portalStrings('fr'), lang: 'fr' })} />)).toContain('Accepter l’offre');
  });
});
