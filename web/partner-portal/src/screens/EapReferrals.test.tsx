// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toEapReferrals, type FeedbackForm } from '../api/eap';
import { App } from '../App';
import { S } from '../strings';
import { eapRaw, meProvider } from '../test/fixtures';
import { mockApi } from '../test/mockApi';
import { EapTable, FeedbackFormView } from './EapReferrals';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

const list = toEapReferrals([eapRaw(), eapRaw({ id: 'e2', ref: 'EAP-2026-000002', status: 'CLOSED', urgency: 'ROUTINE', contactPhone: undefined })]);

describe('EAP referral list view', () => {
  it('shows the minimal data set and a feedback button only for open referrals', () => {
    const onSelect = vi.fn();
    const html = renderToStaticMarkup(<EapTable referrals={list} status="" onStatus={vi.fn()} onSelect={onSelect} />);
    for (const s of ['EAP-2026-000001', 'Rami Ramp', 'href="tel:+447700900123"', 'Prefers phone', '>6<', 'Priority', '>New<', 'Closed']) expect(html).toContain(s);
    render(<EapTable referrals={list} status="" onStatus={vi.fn()} onSelect={onSelect} />);
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]!);
    expect(onSelect).toHaveBeenCalledWith(list[0]);
  });
});

describe('feedback form', () => {
  const base = (form: Partial<FeedbackForm> = {}, errors = {}) => ({
    referral: list[0]!,
    form: { status: 'ACCEPTED', attendance: [], closureReason: '', ...form } as FeedbackForm,
    errors,
    busy: false,
    now: new Date(2026, 9, 30),
    onChange: vi.fn(),
    onSubmit: vi.fn(),
    onCancel: vi.fn(),
  });

  it('has no free-text field at all and explains why', () => {
    const { container } = render(<FeedbackFormView {...base({ status: 'CLOSED', attendance: [{ date: '2026-10-21', attended: 'YES' }] })} />);
    expect(container.querySelectorAll('textarea')).toHaveLength(0);
    const inputs = [...container.querySelectorAll('input')];
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.every((i) => i.type === 'date')).toBe(true);
    expect(container.textContent).toContain('Why there are no comment fields');
    expect(screen.getByLabelText(new RegExp(S.eap.closureReason))).toBeTruthy();
    expect(inputs[0]!.getAttribute('max')).toBe('2026-10-30');
  });

  it('closure reason only appears when closing; sessions can be added', () => {
    const p = base();
    render(<FeedbackFormView {...p} />);
    expect(screen.queryByLabelText(new RegExp(S.eap.closureReason))).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: S.eap.addSession }));
    expect(p.onChange).toHaveBeenCalledWith(expect.objectContaining({ attendance: [{ date: '', attended: '' }] }));
  });
});

describe('EAP referrals in the portal', () => {
  it('a partner provider opens EAP referrals from the nav and sends structured feedback', async () => {
    window.location.hash = '#/eap-referrals';
    const api = mockApi({
      'GET /bff/session': { authenticated: true, name: 'Eve', roles: ['PARTNER_PROVIDER'] },
      'GET /me': { ...meProvider, orgScope: '/PARTNERS/EAP_WELLBEING' },
      'GET /partner/eap-referrals': [eapRaw()],
      'POST /partner/eap-referrals/e1/feedback': undefined,
    });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'EAP referrals' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'EAP referrals' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByLabelText('Confidentiality').textContent).toContain('EAP_WELLBEING');
    fireEvent.click(await screen.findByRole('button', { name: S.eap.giveFeedback('EAP-2026-000001') }));
    const formEl = screen.getByRole('form', { name: S.eap.feedbackTitle('EAP-2026-000001') });
    fireEvent.click(within(formEl).getByRole('button', { name: S.eap.addSession }));
    fireEvent.change(within(formEl).getByLabelText(S.eap.sessionDate(1)), { target: { value: '2026-10-21' } });
    fireEvent.change(within(formEl).getByLabelText(S.eap.attended(1)), { target: { value: 'YES' } });
    fireEvent.click(within(formEl).getByRole('button', { name: S.eap.submit }));
    await waitFor(() => expect(api.find('POST', '/partner/eap-referrals/e1/feedback')).toHaveLength(1));
    expect(api.find('POST', '/partner/eap-referrals/e1/feedback')[0]!.body).toEqual({ status: 'ACCEPTED', attendance: [{ date: '2026-10-21', attended: true }] });
    expect(await screen.findByText(S.eap.saved)).toBeTruthy();
  });
});
