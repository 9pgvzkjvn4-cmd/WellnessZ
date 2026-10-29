// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toMe } from '../auth/me';
import { meProvider, session as th } from '../test/fixtures';
import { mockApi, reply } from '../test/mockApi';
import { Telehealth } from './Telehealth';

const me = toMe(meProvider);
const session = { authenticated: true, roles: ['PARTNER_PROVIDER'] };
const soon = () => new Date(Date.now() + 5 * 60_000).toISOString();

let open: ReturnType<typeof vi.fn>;
beforeEach(() => {
  open = vi.fn(() => null);
  vi.stubGlobal('open', open);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const renderScreen = (routes: Record<string, unknown>) => {
  const api = mockApi(routes);
  render(<Telehealth client={api.client} session={session} me={me} onSessionExpired={vi.fn()} />);
  return api;
};

describe('telehealth screen', () => {
  it('joins an open session in a new tab via an https link', async () => {
    renderScreen({ 'GET /partner/telehealth/sessions': [th({ scheduledAt: soon() })], 'POST /partner/telehealth/sessions/t1/join': { joinUrl: 'https://telehealth.wellnessz.local/room/abc', expiresAt: soon() } });
    expect(await screen.findByText(/Crew on layover in Dubai/)).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Join session' }));
    expect(await screen.findByRole('link', { name: 'Open the consultation room' })).toBeTruthy();
    expect(open).toHaveBeenCalledWith('https://telehealth.wellnessz.local/room/abc', '_blank', 'noopener,noreferrer');
  });

  it('never opens a non-http join link', async () => {
    renderScreen({ 'GET /partner/telehealth/sessions': [th({ scheduledAt: soon() })], 'POST /partner/telehealth/sessions/t1/join': { joinUrl: 'javascript:alert(1)' } });
    await userEvent.click(await screen.findByRole('button', { name: 'Join session' }));
    expect(await screen.findByText(/not a web address/)).toBeTruthy();
    expect(open).not.toHaveBeenCalled();
  });

  it('hides join before the window opens and shows consent_required errors inline', async () => {
    renderScreen({
      'GET /partner/telehealth/sessions': [th({ id: 'later', ref: 'TH-LATER', scheduledAt: new Date(Date.now() + 86_400_000).toISOString() }), th({ id: 't2', ref: 'TH-NOW', scheduledAt: soon() })],
      'POST /partner/telehealth/sessions/t2/join': reply(422, { status: 422, code: 'consent_required' }),
    });
    expect(await screen.findByText('You can join from 15 minutes before the start.')).toBeTruthy();
    const buttons = screen.getAllByRole('button', { name: 'Join session' });
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]!);
    expect(await screen.findByText(/consent for sharing with your organisation is not active/)).toBeTruthy();
  });

  it('opens the secure chat and sends a message', async () => {
    let msgs = [{ id: 'm1', sender: 'Nurse One', senderRole: 'OH_CLINICIAN', text: 'Patient is ready', sentAt: '2026-10-29T10:01:00Z' }];
    const api = renderScreen({
      'GET /partner/telehealth/sessions': [th({ scheduledAt: soon() })],
      'GET /partner/telehealth/sessions/t1/messages': () => msgs,
      'POST /partner/telehealth/sessions/t1/messages': (b: unknown) => {
        msgs = [...msgs, { id: 'm2', sender: 'Pat', senderRole: 'PARTNER_PROVIDER', text: (b as { text: string }).text, sentAt: '2026-10-29T10:02:00Z' }];
        return msgs[1];
      },
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Open secure chat for TH-2026-000001' }));
    expect(await screen.findByText('Patient is ready')).toBeTruthy();
    await userEvent.type(screen.getByRole('textbox', { name: /^Message/ }), 'Joining in two minutes');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Joining in two minutes')).toBeTruthy();
    expect(api.find('POST', '/partner/telehealth/sessions/t1/messages')[0]!.body).toEqual({ text: 'Joining in two minutes' });
  });
});
