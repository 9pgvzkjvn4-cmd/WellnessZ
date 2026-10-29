import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { toSession, toTreatmentPlan, type MySession } from '../api/care';
import { careStrings } from '../i18n/care';
import { SESSION, stubClient } from '../test/client';
import { findAll, findOne, textOf } from '../test/tree';
import { ChatPanel, MyTelehealth, SessionCard, type ChatPanelProps, type SessionCardProps } from './MyTelehealth';
import { ExternalAppointmentList, FollowUpCard, MyTreatment, PlanView, type FollowUpCardProps, type PlanViewProps } from './MyTreatment';

const t = careStrings('en');
const button = (tree: ReactElement, label: string) => findOne(tree, (e) => e.type === 'button' && textOf(e.props.children).trim() === label);
const hasButton = (tree: ReactElement, label: string) => findAll(tree, (e) => e.type === 'button' && textOf(e.props.children).trim() === label).length > 0;

const plan = toTreatmentPlan({
  id: 'p1',
  caseRef: 'AIR-2026-000012',
  status: 'ACTIVE',
  goals: ['Lift 10 kg without pain'],
  interventions: [{ type: 'PHYSIOTHERAPY', description: 'Core strengthening', frequency: 'twice a week', providerName: 'Physio Plus' }],
  followUps: [
    { id: 'f1', dueDate: '2026-11-05', type: 'OH_REVIEW', status: 'BOOKED', appointment: { startsAt: '2026-11-05T08:00:00Z', siteCode: 'HQ-MC' } },
    { id: 'f2', dueDate: '2026-10-20', type: 'PHONE', status: 'DONE' },
  ],
  externalAppointments: [{ id: 'r1', serviceName: 'Physiotherapy', providerName: 'Physio Plus', appointmentAt: '2026-11-02T14:00:00Z', location: '12 High St', instructions: '<b>Bring trainers</b>', status: 'SCHEDULED' }],
})!;

function fuProps(over: Partial<FollowUpCardProps> = {}): FollowUpCardProps {
  return {
    t, lang: 'en', plan, f: plan.followUps[1]!, busy: false, declining: false, reason: '',
    onConfirm: vi.fn(), onDecline: vi.fn(), onReason: vi.fn(), onSubmitDecline: vi.fn(), onAbortDecline: vi.fn(),
    ...over,
  };
}

describe('treatment plan view', () => {
  it('renders goals, interventions, check-ins and external appointments in plain language', () => {
    const p: PlanViewProps = { ...fuProps(), plan, decliningId: undefined };
    const html = renderToStaticMarkup(<PlanView {...p} />);
    for (const s of ['Lift 10 kg without pain', 'Physiotherapy', 'twice a week', 'Review with Occupational Health', 'Physio Plus', '12 High St', t.trExternal, 'For case AIR-2026-000012']) expect(html).toContain(s);
    expect(html).toContain('&lt;b&gt;Bring trainers&lt;/b&gt;'); // text, never HTML
    expect(html).not.toContain('<b>');
  });

  it('confirm / decline only on an open check-in', () => {
    const open = fuProps({ f: plan.followUps.find((f) => f.id === 'f1')! });
    const tree = FollowUpCard(open);
    button(tree, t.trConfirm).props.onClick();
    expect(open.onConfirm).toHaveBeenCalledWith(plan, open.f);
    button(tree, t.trDecline).props.onClick();
    expect(open.onDecline).toHaveBeenCalled();
    const done = FollowUpCard(fuProps({ f: plan.followUps.find((f) => f.id === 'f2')! }));
    expect(hasButton(done, t.trConfirm)).toBe(false);
    const answered = renderToStaticMarkup(<FollowUpCard {...fuProps({ f: { ...open.f, response: 'DECLINE' } })} />);
    expect(answered).toContain(t.trYouDeclined);
    expect(answered).not.toContain(t.trConfirm);
  });

  it('decline asks for an optional reason inside a confirmation panel', () => {
    const p = fuProps({ f: plan.followUps.find((f) => f.id === 'f1')!, declining: true, reason: 'roster' });
    const tree = FollowUpCard(p);
    const area = findOne(tree, (e) => e.type === 'textarea');
    expect(area.props.maxLength).toBe(500);
    area.props.onChange({ target: { value: 'roster clash' } });
    expect(p.onReason).toHaveBeenCalledWith('roster clash');
    button(tree, t.trDeclineConfirm).props.onClick();
    expect(p.onSubmitDecline).toHaveBeenCalled();
    expect(renderToStaticMarkup(<FollowUpCard {...p} />)).toContain('role="alertdialog"');
  });

  it('empty external list and screen shell (Arabic right-to-left)', () => {
    expect(renderToStaticMarkup(<ExternalAppointmentList t={t} lang="en" items={[]} />)).toContain(t.trExternalEmpty);
    const html = renderToStaticMarkup(<MyTreatment client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="ar" />);
    expect(html).toContain('dir="rtl"');
    expect(html).toContain(careStrings('ar').trTitle);
  });
});

const base = toSession({ id: 's1', scheduledAt: '2026-10-29T09:30:00Z', durationMinutes: 20, mode: 'VIDEO', status: 'SCHEDULED', providerCode: 'TELEDOC', crewLayover: true, layoverCity: 'Singapore', consentGiven: false })!;
function cardProps(s: MySession, over: Partial<SessionCardProps> = {}): SessionCardProps {
  return {
    t, lang: 'en', s, now: new Date('2026-10-29T09:00:00Z'), busy: false, consentChecked: false, chatOpen: false,
    onConsentCheck: vi.fn(), onConsent: vi.fn(), onJoin: vi.fn(), onToggleChat: vi.fn(),
    ...over,
  };
}

describe('telehealth view', () => {
  it('shows mode, partner and the layover note; consent explains what is recorded and who sees the note', () => {
    const html = renderToStaticMarkup(<SessionCard {...cardProps(base)} />);
    for (const s of [t.mode.VIDEO, t.thPartner('TELEDOC'), t.thLayover('Singapore'), t.thLayoverHint, t.thRecNote, t.thRecNoVideo, t.thNoteOhOnly, t.thPartnerSees, t.thJoinNeedsConsent]) expect(html).toContain(s);
    const tree = SessionCard(cardProps(base));
    expect(button(tree, t.thConsentGive).props.disabled).toBe(true); // checkbox first
    expect(button(tree, t.thJoin).props.disabled).toBe(true);
    expect(hasButton(tree, t.chShow)).toBe(false); // chat after consent
  });

  it('consent is given after ticking the box', () => {
    const p = cardProps(base, { consentChecked: true });
    const tree = SessionCard(p);
    findOne(tree, (e) => e.type === 'input' && e.props.type === 'checkbox').props.onChange({ target: { checked: false } });
    expect(p.onConsentCheck).toHaveBeenCalledWith(false);
    button(tree, t.thConsentGive).props.onClick();
    expect(p.onConsent).toHaveBeenCalledWith(base);
  });

  it('join is enabled only in the window; the link opens in a new tab only while usable', () => {
    const s = { ...base, consentGiven: true };
    expect(button(SessionCard(cardProps(s)), t.thJoin).props.disabled).toBe(true);
    expect(renderToStaticMarkup(<SessionCard {...cardProps(s)} />)).toContain('15 minutes before');
    const inWindow = cardProps(s, { now: new Date('2026-10-29T09:16:00Z') });
    button(SessionCard(inWindow), t.thJoin).props.onClick();
    expect(inWindow.onJoin).toHaveBeenCalledWith(s);
    const link = { url: 'https://telehealth.wellnessz.local/room/abc', expiresAt: '2026-10-29T09:50:00Z' };
    const html = renderToStaticMarkup(<SessionCard {...cardProps(s, { now: new Date('2026-10-29T09:16:00Z'), link })} />);
    expect(html).toContain('href="https://telehealth.wellnessz.local/room/abc"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
    const after = renderToStaticMarkup(<SessionCard {...cardProps(s, { now: new Date('2026-10-29T10:00:00Z'), link })} />);
    expect(after).not.toContain('room/abc');
    expect(after).toContain(t.thJoinEnded);
  });

  it('chat mode has no video join; chat toggles once consented', () => {
    const s = { ...base, mode: 'CHAT', consentGiven: true };
    const p = cardProps(s);
    const tree = SessionCard(p);
    expect(hasButton(tree, t.thJoin)).toBe(false);
    button(tree, t.chShow).props.onClick();
    expect(p.onToggleChat).toHaveBeenCalledWith(s);
    expect(renderToStaticMarkup(<SessionCard {...p} />)).toContain(t.thChatMode);
  });

  it('chat panel renders messages as text and sends within the limit; closed sessions are read-only', () => {
    const s = { ...base, consentGiven: true };
    const props: ChatPanelProps = {
      t, lang: 'en', s, loading: false, draft: 'Feeling better', busy: false, onDraft: vi.fn(), onSend: vi.fn(), onRefresh: vi.fn(),
      messages: [
        { id: 'm1', mine: true, text: 'Hi', sentAt: '2026-10-29T09:30:00Z' },
        { id: 'm2', mine: false, sender: 'Dr Lee', text: '<img src=x onerror=alert(1)>', sentAt: '2026-10-29T09:31:00Z' },
      ],
    };
    const html = renderToStaticMarkup(<ChatPanel {...props} />);
    expect(html).toContain('&lt;img');
    expect(html).not.toContain('<img');
    expect(html).toContain('Dr Lee');
    expect(html).toContain(t.chYou);
    const tree = ChatPanel(props);
    expect(findOne(tree, (e) => e.type === 'textarea').props.maxLength).toBe(2000);
    const preventDefault = vi.fn();
    findOne(tree, (e) => e.type === 'form').props.onSubmit({ preventDefault });
    expect(props.onSend).toHaveBeenCalled();
    const closed = renderToStaticMarkup(<ChatPanel {...props} s={{ ...s, status: 'COMPLETED' }} />);
    expect(closed).toContain(t.chReadOnly);
    expect(closed).not.toContain('<textarea');
  });

  it('screen shell renders in French', () => {
    const html = renderToStaticMarkup(<MyTelehealth client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="fr" />);
    expect(html).toContain(careStrings('fr').thTitle);
    expect(html).toContain('lang="fr"');
  });
});
