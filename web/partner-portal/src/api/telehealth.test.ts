import { describe, expect, it, vi } from 'vitest';
import { mockApi, reply } from '../test/mockApi';
import { session } from '../test/fixtures';
import { toMessages } from './messages';
import { joinSession, joinState, openInNewTab, safeJoinUrl, toTelehealthSessions } from './telehealth';

const s = (over: Record<string, unknown> = {}) => toTelehealthSessions([session(over)])[0]!;

describe('telehealth sessions', () => {
  it('maps sessions without case / encounter ids', () => {
    const x = s();
    expect(x).toEqual({ id: 't1', ref: 'TH-2026-000001', displayName: 'Rami Ramp', scheduledAt: '2026-10-29T10:00:00Z', durationMinutes: 30, mode: 'VIDEO', status: 'SCHEDULED', crewLayover: true, layoverCity: 'Dubai', consentGiven: true });
  });

  it('opens the join window 15 minutes before the start until the end', () => {
    expect(joinState(s(), new Date('2026-10-29T09:44:00Z'))).toBe('early');
    expect(joinState(s(), new Date('2026-10-29T09:45:00Z'))).toBe('open');
    expect(joinState(s(), new Date('2026-10-29T10:30:00Z'))).toBe('open');
    expect(joinState(s(), new Date('2026-10-29T10:31:00Z'))).toBe('ended');
    expect(joinState(s({ consentGiven: false }), new Date('2026-10-29T10:00:00Z'))).toBe('no-consent');
    expect(joinState(s({ status: 'CANCELLED' }), new Date('2026-10-29T10:00:00Z'))).toBe('closed');
    expect(joinState(s({ status: 'COMPLETED' }), new Date('2026-10-29T10:00:00Z'))).toBe('ended');
  });

  it('accepts only absolute http(s) join links', () => {
    expect(safeJoinUrl('https://telehealth.wellnessz.local/room/abc?t=1')).toBe('https://telehealth.wellnessz.local/room/abc?t=1');
    expect(safeJoinUrl('http://localhost:9000/room/1')).toBe('http://localhost:9000/room/1');
    for (const bad of ['javascript:alert(1)', 'data:text/html,hi', '/room/1', 'ftp://x/y', '', 42, undefined]) expect(safeJoinUrl(bad)).toBeUndefined();
  });

  it('opens the link in a new tab with noopener only when safe', () => {
    const open = vi.fn();
    openInNewTab('https://t.example/room/1', open);
    openInNewTab('javascript:alert(1)', open);
    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith('https://t.example/room/1', '_blank', 'noopener,noreferrer');
  });

  it('join drops an unsafe URL and maps consent_required', async () => {
    const api = mockApi({
      'POST /partner/telehealth/sessions/t1/join': { joinUrl: 'javascript:alert(1)', expiresAt: '2026-10-29T10:30:00Z' },
      'POST /partner/telehealth/sessions/t2/join': reply(422, { status: 422, code: 'consent_required' }),
    });
    expect(await joinSession(api.client, 't1', 'x')).toEqual({ ok: true, body: { joinUrl: undefined, expiresAt: '2026-10-29T10:30:00Z' } });
    expect(await joinSession(api.client, 't2', 'Not joined')).toMatchObject({ ok: false, code: 'consent_required', message: expect.stringMatching(/consent/) });
  });

  it('maps chat messages oldest first', () => {
    expect(
      toMessages([
        { id: 'm2', sender: 'Nurse One', senderRole: 'OH_CLINICIAN', text: 'second', sentAt: '2026-10-29T10:05:00Z' },
        { id: 'm1', sender: 'Pat', senderRole: 'PARTNER_PROVIDER', text: 'first', sentAt: '2026-10-29T10:01:00Z' },
        { id: 'bad' },
      ]).map((m) => m.text),
    ).toEqual(['first', 'second']);
  });
});
