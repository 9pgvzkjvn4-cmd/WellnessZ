import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { toInvitation, toTestingNotice, toVaccinationCampaign, type CheckIn } from '../api/prevention';
import { toCismOffer, toScreenForm, toScreenOutcome, type SharingSetting } from '../api/support';
import { EmergencyBanner } from '../components/wellbeing';
import { wellbeingStrings } from '../i18n/wellbeing';
import { SESSION, stubClient } from '../test/client';
import { findAll, findOne, textOf } from '../test/tree';
import { Campaigns, VaccinationCard, WearablePanel, type WearablePanelProps } from './Campaigns';
import { CheckInCard, type CheckInCardProps } from './CheckIns';
import { PeerList, PeerRequestForm, PeerSupport } from './PeerSupport';
import { CismOfferCard, Questionnaire, ScreenResult, SelfReferralView, SharingCard, Support, type SharingCardProps } from './Support';
import { NoticeCard, Testing } from './Testing';
import { AdvisoryList, CertificateList, Travel } from './Travel';

const t = wellbeingStrings('en');
const NOW = new Date(2026, 9, 30, 9, 0, 0);
const button = (tree: ReactElement, label: string) => findOne(tree, (e) => e.type === 'button' && textOf(e.props.children).trim() === label);
const hasButton = (tree: ReactElement, label: string) => findAll(tree, (e) => e.type === 'button' && textOf(e.props.children).trim() === label).length > 0;
/** Text as react-dom/server escapes it. */
const esc = (x: string) => x.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const props = { client: stubClient(), session: SESSION, onSessionExpired: vi.fn() };

describe('support screen', () => {
  it('shows the emergency guidance first, before any data has loaded, in every language', () => {
    for (const lang of ['en', 'ar', 'fr']) {
      const html = renderToStaticMarkup(<Support {...props} lang={lang} />);
      const s = wellbeingStrings(lang);
      expect(html.indexOf(esc(s.emDanger))).toBeGreaterThan(-1);
      expect(html.indexOf('emergency-banner')).toBeLessThan(html.indexOf(esc(s.spTitle)));
      expect(html).toContain(esc(s.emLocal));
    }
    expect(renderToStaticMarkup(<Support {...props} lang="ar" />)).toContain('dir="rtl"');
  });

  it('uses the server emergency number when given', () => {
    const html = renderToStaticMarkup(<EmergencyBanner t={t} emergency={{ text: 'In danger? Call 999.', phone: '999' }} />);
    expect(html).toContain('href="tel:999"');
    expect(html).toContain('In danger? Call 999.');
    expect(html).not.toContain(t.emLocal);
  });

  it('self-referral explains who reads it and offers every concern area and contact option', () => {
    const html = renderToStaticMarkup(
      <SelfReferralView t={t} lang="en" draft={{ preferredContact: 'PHONE', phone: '', bestTime: '', concern: '', message: '' }} errors={{ phone: 'required', concern: 'required' }} busy={false} onChange={vi.fn()} onSubmit={vi.fn()} />,
    );
    expect(html).toContain(t.srWhoReads);
    for (const c of Object.values(t.concerns)) expect(html).toContain(c);
    for (const c of Object.values(t.contacts)) expect(html).toContain(c);
    expect(html).toContain(t.errRequired);
    const done = renderToStaticMarkup(<SelfReferralView t={t} lang="en" draft={{ preferredContact: 'PHONE', phone: '', bestTime: '', concern: '', message: '' }} errors={{}} busy={false} receipt={{ caseRef: 'SUP-2026-000004' }} onChange={vi.fn()} onSubmit={vi.fn()} />);
    expect(done).toContain('SUP-2026-000004');
    expect(done).toContain(t.srNextContactSoon);
  });

  it('questionnaire lists every question and option; an incomplete submit shows a hint', () => {
    const form = toScreenForm({ instrument: 'GAD7', questions: [{ code: 'G1', text: 'Feeling nervous' }, { code: 'G2', text: 'Not able to stop worrying' }], options: [{ value: 0, label: 'Not at all' }, { value: 3, label: 'Nearly every day' }] }, 'GAD7')!;
    const onAnswer = vi.fn();
    const tree = Questionnaire({ t, form, answers: [0, undefined], busy: false, showIncomplete: true, onAnswer, onSubmit: vi.fn(), onBack: vi.fn() });
    const html = renderToStaticMarkup(tree);
    expect(html).toContain('Feeling nervous');
    expect(html).toContain(t.scQuestion(2, 2));
    expect(html).toContain(t.scIncomplete);
    findAll(tree, (e) => e.type === 'input' && e.props.name === 'q-G2')[1]!.props.onChange();
    expect(onAnswer).toHaveBeenCalledWith(1, 3);
  });

  it('urgent result: a clinician will call today plus emergency numbers – and never diagnostic wording', () => {
    for (const lang of ['en', 'ar', 'fr']) {
      const s = wellbeingStrings(lang);
      const outcome = toScreenOutcome({ score: 21, band: 'SEVERE', urgent: true, nextSteps: ['Keep your phone close', 'Diagnosis: severe depression'] }, toScreenForm({ questions: Array(9).fill('q'), options: [{ value: 0, label: 'a' }, { value: 3, label: 'b' }] }, 'PHQ9'));
      const html = renderToStaticMarkup(<ScreenResult t={s} outcome={outcome} emergency={{ phone: '999' }} onBack={vi.fn()} />);
      expect(html).toContain(esc(s.scUrgentTitle));
      expect(html).toContain(esc(s.scUrgentDanger));
      expect(html).toContain('href="tel:999"');
      expect(html).toContain(s.scScore(21, 27));
      expect(html).toContain('role="alert"');
      expect(html).not.toMatch(/diagnos|تشخيص/i);
    }
  });

  it('non-urgent result shows the band and its next-step hint without the urgent message', () => {
    const html = renderToStaticMarkup(<ScreenResult t={t} outcome={{ band: 'MILD', score: 6, maxScore: 27, urgent: false, nextSteps: [] }} onBack={vi.fn()} />);
    expect(html).toContain(t.bands.MILD);
    expect(html).toContain(t.bandHints.MILD);
    expect(html).not.toContain(t.scUrgentTitle);
  });

  function shareProps(over: Partial<SharingCardProps> = {}): SharingCardProps {
    const setting: SharingSetting = { caseId: 'c1', caseRef: 'SUP-2026-000001', choice: 'FITNESS_ONLY' };
    return { t, lang: 'en', setting, choice: 'FITNESS_ONLY', adjustments: '', busy: false, onChoice: vi.fn(), onAdjustments: vi.fn(), onSave: vi.fn(), onRevoke: vi.fn(), ...over };
  }

  it('sharing card explains exactly what the manager sees for each choice; save only after a change; revocable', () => {
    const p = shareProps();
    const tree = SharingCard(p);
    const html = renderToStaticMarkup(tree);
    for (const c of ['NOTHING', 'FITNESS_ONLY', 'FITNESS_AND_ADJUSTMENTS']) expect(html).toContain(esc(t.choiceExplain[c]!));
    expect(button(tree, t.shSave).props.disabled).toBe(true);
    button(tree, t.shRevoke).props.onClick();
    expect(p.onRevoke).toHaveBeenCalled();
    findAll(tree, (e) => e.type === 'input' && e.props.value === 'NOTHING')[0]!.props.onChange();
    expect(p.onChoice).toHaveBeenCalledWith('NOTHING');
  });

  it('adjustments field appears only for FITNESS_AND_ADJUSTMENTS; nothing to revoke when already NOTHING', () => {
    const adj = renderToStaticMarkup(<SharingCard {...shareProps({ choice: 'FITNESS_AND_ADJUSTMENTS' })} />);
    expect(adj).toContain(t.shAdjustments);
    const nothing = SharingCard(shareProps({ setting: { caseId: 'c1', choice: 'NOTHING' }, choice: 'NOTHING' }));
    expect(hasButton(nothing, t.shRevoke)).toBe(false);
    expect(renderToStaticMarkup(<SharingCard {...shareProps()} />)).not.toContain(t.shAdjustments);
  });

  it('CISM offer: accept / decline with preferred contact; answered offers show the answer only', () => {
    const offer = toCismOffer({ id: 'o1', title: 'Ramp incident', offerDueBy: '2026-11-01T10:00:00Z' })!;
    const onRespond = vi.fn();
    const tree = CismOfferCard({ t, lang: 'en', offer, contact: 'VIDEO', busy: false, onContact: vi.fn(), onRespond });
    button(tree, t.cmAccept).props.onClick();
    expect(onRespond).toHaveBeenCalledWith(offer, 'ACCEPTED');
    button(tree, t.cmDecline).props.onClick();
    expect(onRespond).toHaveBeenLastCalledWith(offer, 'DECLINED');
    const answered = CismOfferCard({ t, lang: 'en', offer: { ...offer, response: 'DECLINED' }, contact: 'PHONE', busy: false, onContact: vi.fn(), onRespond });
    expect(hasButton(answered, t.cmAccept)).toBe(false);
    expect(textOf(answered)).toContain(t.cmDeclined);
  });
});

describe('peer support screen', () => {
  it('explains non-punitive and anonymous handling, with emergency guidance', () => {
    const html = renderToStaticMarkup(<PeerSupport {...props} lang="fr" />);
    const s = wellbeingStrings('fr');
    for (const x of [s.peNonPunitive, s.peAnonymous, s.emDanger]) expect(html).toContain(esc(x));
  });

  it('lists peers by alias only and offers their bases / languages as preferences', () => {
    const peers = [{ alias: 'Sam', base: 'LHR', languages: ['en', 'fr'] }];
    expect(renderToStaticMarkup(<PeerList t={t} peers={peers} />)).toContain('Sam');
    const html = renderToStaticMarkup(<PeerRequestForm t={t} peers={peers} draft={{ alias: '', callbackPhone: '', base: '', language: '' }} errors={{ alias: 'required', callbackPhone: 'invalid' }} busy={false} onChange={vi.fn()} onSubmit={vi.fn()} />);
    expect(html).toContain('<option value="LHR">');
    expect(html).toContain('<option value="fr">');
    expect(html).toContain(t.errPhone);
    expect(html).toContain(t.peAliasHint);
  });
});

describe('travel screen', () => {
  it('advisories can be acknowledged once; certificates download through the document service', () => {
    const onAck = vi.fn();
    const tree = AdvisoryList({ t, lang: 'en', busy: false, onAcknowledge: onAck, items: [{ id: 'a1', title: 'Measles at DXB', severity: 'CRITICAL' }, { id: 'a2', title: 'Heat', severity: 'INFO', acknowledgedAt: 'x' }] });
    expect(findAll(tree, (e) => e.type === 'button')).toHaveLength(1);
    button(tree, t.trAcknowledge).props.onClick();
    expect(onAck).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
    const html = renderToStaticMarkup(<CertificateList t={t} lang="en" items={[{ documentId: 'd9' }]} linkFor={(c) => `/bff/api/documents/${c.documentId}/content`} onOpen={vi.fn()} />);
    expect(html).toContain('href="/bff/api/documents/d9/content"');
    expect(html).toContain(t.trCertificateFallback);
  });

  it('renders the container in Arabic right-to-left', () => {
    expect(renderToStaticMarkup(<Travel {...props} lang="ar" />)).toContain('dir="rtl"');
  });
});

describe('check-ins screen', () => {
  const item: CheckIn = { id: 'k1', dueOn: '2026-10-30', monitoringUntil: '2026-11-10' };
  const base = (over: Partial<CheckInCardProps> = {}): CheckInCardProps => ({ t, lang: 'en', item, now: NOW, symptoms: [], temperature: '', errors: {}, busy: false, onToggle: vi.fn(), onTemperature: vi.fn(), onSubmit: vi.fn(), ...over });

  it("today's check-in shows every symptom checkbox and the optional temperature", () => {
    const p = base({ errors: { symptoms: 'required', temperature: 'invalid' } });
    const tree = CheckInCard(p);
    expect(findAll(tree, (e) => e.type === 'input' && e.props.type === 'checkbox')).toHaveLength(7);
    const html = renderToStaticMarkup(tree);
    expect(html).toContain(t.chkErrSymptoms);
    expect(html).toContain(t.chkErrTemp);
    findAll(tree, (e) => e.type === 'input' && e.props.type === 'checkbox')[6]!.props.onChange();
    expect(p.onToggle).toHaveBeenCalledWith('NONE');
  });

  it('no form on another day or once sent', () => {
    expect(findAll(CheckInCard(base({ item: { ...item, dueOn: '2026-10-31' } })), (e) => e.type === 'form')).toHaveLength(0);
    expect(textOf(CheckInCard(base({ item: { ...item, submittedAt: 'x' } })))).toContain(t.windows.done);
  });
});

describe('campaigns screen', () => {
  const campaign = toInvitation({ campaignId: 'p1', title: 'Move more', risk: 'METABOLIC' })!;
  const wp = (over: Partial<WearablePanelProps> = {}): WearablePanelProps => ({
    t, lang: 'en', campaign, device: 'PHONE', consent: false, consentError: false, withdrawing: false, busy: false,
    onDevice: vi.fn(), onConsent: vi.fn(), onEnrol: vi.fn(), onWithdraw: vi.fn(), onConfirmWithdraw: vi.fn(), onCancelWithdraw: vi.fn(), ...over,
  });

  it('wearable consent is explicit and explains data minimisation (daily totals only)', () => {
    const p = wp({ consentError: true });
    const tree = WearablePanel(p);
    const html = renderToStaticMarkup(tree);
    expect(html).toContain(t.caMinimisation);
    for (const m of Object.values(t.metrics)) expect(html).toContain(m);
    expect(html).toContain(t.caConsentNeeded);
    const box = findOne(tree, (e) => e.type === 'input' && e.props.type === 'checkbox');
    expect(box.props.checked).toBe(false);
    box.props.onChange({ target: { checked: true } });
    expect(p.onConsent).toHaveBeenCalledWith(true);
    button(tree, t.caEnrol).props.onClick();
    expect(p.onEnrol).toHaveBeenCalledWith(campaign);
  });

  it('enrolled: withdrawal asks for confirmation and explains the data are deleted', () => {
    const p = wp({ enrolment: { id: 'e1', campaignId: 'p1', deviceType: 'SMARTWATCH' } });
    const tree = WearablePanel(p);
    expect(textOf(tree)).toContain(t.caEnrolled(t.devices.SMARTWATCH!));
    button(tree, t.caWithdraw).props.onClick();
    expect(p.onWithdraw).toHaveBeenCalledWith(p.enrolment);
    const confirm = renderToStaticMarkup(<WearablePanel {...p} withdrawing />);
    expect(confirm).toContain(esc(t.caWithdrawBody));
  });

  it('vaccination accept / decline only while open', () => {
    const c = toVaccinationCampaign({ id: 'v1', name: 'Flu 2026', endDate: '2026-11-30', siteCodes: ['LHR'] })!;
    const onRespond = vi.fn();
    button(VaccinationCard({ t, lang: 'en', campaign: c, now: NOW, busy: false, onRespond }), t.caAccept).props.onClick();
    expect(onRespond).toHaveBeenCalledWith(c, 'ACCEPT');
    const closed = VaccinationCard({ t, lang: 'en', campaign: { ...c, endDate: '2026-10-01' }, now: NOW, busy: false, onRespond });
    expect(hasButton(closed, t.caAccept)).toBe(false);
    expect(textOf(closed)).toContain(t.caClosed);
  });

  it('container renders headings in French', () => {
    expect(renderToStaticMarkup(<Campaigns {...props} lang="fr" />)).toContain(wellbeingStrings('fr').caVaccination);
  });
});

describe('workplace checks screen', () => {
  it('shows where and by when to report, with the time left – neutral wording, no result', () => {
    const n = toTestingNotice({ ref: 'DAT-2026-000007', status: 'NOTIFIED', reportBy: new Date(NOW.getTime() + 95 * 60_000).toISOString(), siteCode: 'LHR-OH-1', reason: 'RANDOM' })!;
    const html = renderToStaticMarkup(<NoticeCard t={t} lang="en" notice={n} now={NOW} />);
    expect(html).toContain('LHR-OH-1');
    expect(html).toContain(t.teLeft('1 h 35 min'));
    expect(html).not.toMatch(/random|drug|alcohol|positive|negative|result/i);
  });

  it('after the deadline asks to contact the team; completed notices show nothing but the phase', () => {
    const n = toTestingNotice({ ref: 'R1', status: 'DISPATCHED', reportBy: new Date(NOW.getTime() - 60_000).toISOString() })!;
    expect(renderToStaticMarkup(<NoticeCard t={t} lang="en" notice={n} now={NOW} />)).toContain(t.tePassed);
    const done = renderToStaticMarkup(<NoticeCard t={t} lang="en" notice={toTestingNotice({ ref: 'R2', status: 'VERIFIED_POSITIVE', siteCode: 'X' })!} now={NOW} />);
    expect(done).toContain(t.phases.DONE);
    expect(done).not.toMatch(/positive/i);
  });

  it('container explains the page in every language', () => {
    for (const lang of ['en', 'ar', 'fr']) expect(renderToStaticMarkup(<Testing {...props} lang={lang} />)).toContain(esc(wellbeingStrings(lang).teIntro));
  });
});
