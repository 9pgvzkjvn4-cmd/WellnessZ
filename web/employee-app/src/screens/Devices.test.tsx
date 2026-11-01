import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchDeviceConsents, fetchDeviceSummaries, grantDeviceConsent, isActiveConsent, normaliseBadge, revokeDeviceConsent, type DeviceConsent, type KioskBridge } from '../api/devices';
import { ApiError } from '../auth/AuthClient';
import { CONSENT_PURPOSE_LABELS } from '../api/types';
import { deviceStrings } from '../i18n/devices';
import { portalStrings } from '../i18n/portal';
import { navFor } from '../routing/navigation';
import { SESSION, stubClient } from '../test/client';
import { bffWithFetch, json } from '../test/fetch';
import { findAll, findOne, textOf } from '../test/tree';
import { ConsentList, DeviceConsents, GrantForm, MyDeviceResults, SummaryList } from './DeviceScreens';
import { Kiosk, KioskView, type KioskViewProps } from './Kiosk';
import { afterConsent, afterIdentify, holdsPersonData, identifyFailure, idleCheck } from './kioskModel';

afterEach(() => vi.unstubAllGlobals());
const en = deviceStrings('en');
const summaries = [
  { summaryId: 's1', category: 'VITALS', measuredAt: '2026-10-30T09:00:00Z', language: 'en', plainLanguage: 'Your blood pressure is in the normal range.', educationLink: 'https://intranet.example/bp' },
  { summaryId: 's2', category: 'BODY_COMPOSITION', measuredAt: '2026-11-01T09:00:00Z', language: 'fr', plainLanguage: 'Votre IMC se situe dans la tranche « poids de forme ».', educationLink: 'javascript:alert(1)' },
];
const consents: DeviceConsent[] = [
  { id: 'c1', consentId: 'pc1', purpose: 'WEARABLE_DATA', scopes: ['STEPS', 'SLEEP'], status: 'GRANTED', grantedAt: '2026-10-01T10:00:00Z' },
  { id: 'c0', purpose: 'WEARABLE_DATA', scopes: ['HEART_RATE'], status: 'REVOKED', grantedAt: '2026-09-01T10:00:00Z', revokedAt: '2026-09-20T10:00:00Z' },
];

describe('employee device API (contract §14.8)', () => {
  it('reads summaries and consents, grants scopes and revokes', async () => {
    const { client, calls } = bffWithFetch((url, init) => {
      if (url.endsWith('/me/device-summaries')) return json(summaries);
      if (url.endsWith('/me/device-consents') && init.method === 'POST') return json({ ...consents[0], id: 'c2' }, 201);
      if (url.endsWith('/me/device-consents')) return json(consents);
      return json({ ...consents[0], status: 'REVOKED' });
    });
    expect((await fetchDeviceSummaries(client))[0]!.category).toBe('VITALS');
    expect(await fetchDeviceConsents(client)).toHaveLength(2);
    await grantDeviceConsent(client, ['STEPS', 'HEART_RATE']);
    await revokeDeviceConsent(client, 'c1');
    expect(calls().map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /bff/api/me/device-summaries', 'GET /bff/api/me/device-consents', 'POST /bff/api/me/device-consents', 'DELETE /bff/api/me/device-consents/c1',
    ]);
    expect(calls()[2]!.body).toEqual({ purpose: 'WEARABLE_DATA', scopes: ['STEPS', 'HEART_RATE'] });
    expect(isActiveConsent(consents[0]!)).toBe(true);
    expect(isActiveConsent(consents[1]!)).toBe(false);
  });
  it('normalises badge ids', () => {
    expect(normaliseBadge(' b-1001 ')).toBe('B-1001');
    expect(normaliseBadge('bad badge')).toBeUndefined();
  });
  it('has the KIOSK_SELF_MEASUREMENT consent label in every language', () => {
    expect(CONSENT_PURPOSE_LABELS.KIOSK_SELF_MEASUREMENT).toMatch(/kiosk/i);
    for (const l of ['en', 'fr', 'ar']) expect(portalStrings(l).purposes.KIOSK_SELF_MEASUREMENT).toBeTruthy();
  });
  it('adds the two screens to the employee navigation', () => {
    const paths = navFor(['EMPLOYEE']).map((n) => n.path);
    expect(paths).toContain('/health-checks');
    expect(paths).toContain('/device-consents');
    expect(paths).not.toContain('/kiosk');
    expect(navFor(['CANDIDATE']).map((n) => n.path)).toEqual(['/pre-employment']);
  });
});

describe('My device results', () => {
  it('lists summaries newest first in the language they were written in, never as a diagnosis', () => {
    const html = renderToStaticMarkup(<SummaryList t={en} lang="en" rows={summaries} />);
    expect(html.indexOf('Weight and body measurements')).toBeLessThan(html.indexOf('Blood pressure and pulse'));
    expect(html).toContain('Your blood pressure is in the normal range.');
    expect(html).toContain('lang="fr"');
    expect(html).toContain('Shown in the language of your profile.');
    expect(html).toContain('href="https://intranet.example/bp"');
    expect(html).not.toContain('javascript:');
    expect(html.toLowerCase()).not.toContain('diagnos');
  });
  it('shows the empty state and the container renders its not-a-diagnosis notice (Arabic right-to-left)', () => {
    expect(renderToStaticMarkup(<SummaryList t={en} lang="en" rows={[]} />)).toContain('No device results have been released to you yet.');
    const html = renderToStaticMarkup(<MyDeviceResults client={stubClient()} session={SESSION} onSessionExpired={() => {}} lang="ar" />);
    expect(html).toContain('dir="rtl"');
    expect(html).toContain(deviceStrings('ar').resNotDiagnosis);
  });
});

describe('Device and wearable consents', () => {
  const props = (over: Partial<Parameters<typeof ConsentList>[0]> = {}) => ({ t: en, lang: 'en', rows: consents, busy: false, onAsk: vi.fn(), onConfirm: vi.fn(), onCancel: vi.fn(), ...over });
  it('lists scopes and status; withdrawal asks for confirmation', () => {
    const p = props();
    const tree = <ConsentList {...p} />;
    expect(textOf(tree)).toContain('Steps, Sleep');
    expect(textOf(tree)).toContain('Withdrawn');
    findOne(tree, (e) => e.type === 'button' && textOf(e.props.children) === 'Withdraw').props.onClick();
    expect(p.onAsk).toHaveBeenCalledWith('c1');
    const confirming = props({ confirmId: 'c1' });
    const t2 = <ConsentList {...confirming} />;
    expect(textOf(t2)).toContain('Withdraw this consent?');
    findOne(t2, (e) => e.type === 'button' && textOf(e.props.children) === 'Yes, withdraw').props.onClick();
    expect(confirming.onConfirm).toHaveBeenCalledWith('c1');
    // the withdrawn consent offers no action
    expect(findAll(t2, (e) => e.type === 'button')).toHaveLength(2);
  });
  it('grant form toggles the four scopes', () => {
    const onToggle = vi.fn();
    const onSubmit = vi.fn();
    const tree = <GrantForm t={en} scopes={['STEPS']} busy={false} onToggle={onToggle} onSubmit={onSubmit} />;
    const boxes = findAll(tree, (e) => e.type === 'input' && e.props.type === 'checkbox');
    expect(boxes.map((b) => b.props.name)).toEqual(['scope-STEPS', 'scope-SLEEP', 'scope-HEART_RATE', 'scope-ACTIVITY']);
    expect(boxes[0]!.props.checked).toBe(true);
    boxes[2]!.props.onChange({ target: { checked: true } });
    expect(onToggle).toHaveBeenCalledWith('HEART_RATE', true);
  });
  it('container renders intro and kiosk note', () => {
    const html = renderToStaticMarkup(<DeviceConsents client={stubClient()} session={SESSION} onSessionExpired={() => {}} lang="en" />);
    expect(html).toContain(en.conIntro);
    expect(html).toContain('health kiosk');
  });
});

describe('Kiosk self-measurement (#/kiosk)', () => {
  const who = { kioskSessionId: 'k1', firstName: 'Erin', language: 'fr', consentRequired: true, consentNoticeVersion: '2026-10' };
  const view = (over: Partial<KioskViewProps> = {}): KioskViewProps => ({
    t: en, uiLang: 'en', step: 'badge', badge: '', busy: false, onBadge: vi.fn(), onIdentify: vi.fn(), onConsent: vi.fn(), onFinish: vi.fn(), onClear: vi.fn(), onTouch: vi.fn(), ...over,
  });

  it('flow: badge → consent (when required) → measure → summary; declining ends the visit', () => {
    expect(afterIdentify(who)).toBe('consent');
    expect(afterIdentify({ ...who, consentRequired: false })).toBe('measure');
    expect(afterConsent(true)).toBe('measure');
    expect(afterConsent(false)).toBe('declined');
    expect(holdsPersonData('badge')).toBe(false);
    expect(holdsPersonData('summary')).toBe(true);
    expect(identifyFailure(new ApiError(404))).toBe('notFound');
    expect(identifyFailure(new Error('x'))).toBe('error');
  });

  it('privacy timeout: 3 minutes idle clears the screen, with a countdown in the last 20 s', () => {
    expect(idleCheck(0, 60_000)).toEqual({ expired: false, remaining: undefined });
    expect(idleCheck(0, 165_000)).toEqual({ expired: false, remaining: 15 });
    expect(idleCheck(0, 180_000)).toEqual({ expired: true });
  });

  it('badge step reads a keyboard-wedge badge and starts', () => {
    const p = view({ badge: 'B-1001' });
    const tree = <KioskView {...p} />;
    const input = findOne(tree, (e) => e.type === 'input');
    expect(input.props.autoFocus).toBe(true);
    input.props.onChange({ target: { value: 'B-1002' } });
    expect(p.onBadge).toHaveBeenCalledWith('B-1002');
    findOne(tree, (e) => e.type === 'form').props.onSubmit({ preventDefault() {} });
    expect(p.onIdentify).toHaveBeenCalled();
  });

  it('greets by first name only, asks for consent and shows the notice version (in the employee\'s language)', () => {
    const fr = deviceStrings('fr');
    const p = view({ t: fr, uiLang: 'fr', step: 'consent', who });
    const tree = <KioskView {...p} />;
    expect(textOf(tree)).toContain('Bonjour Erin');
    expect(textOf(tree)).toContain('version 2026-10');
    findOne(tree, (e) => e.type === 'button' && textOf(e.props.children) === fr.kioskDecline).props.onClick();
    expect(p.onConsent).toHaveBeenCalledWith(false);
  });

  it('measure step shows progress; summary shows plain-language items and is not a diagnosis', () => {
    const summary = { kioskSessionId: 'k1', language: 'en', state: 'OPEN', items: [{ category: 'VITALS', measuredAt: '2026-11-01T10:00:00Z', plainLanguage: 'Your blood pressure is in the high-normal range.' }] };
    expect(textOf(<KioskView {...view({ step: 'measure', who: { ...who, language: 'en' } })} />)).toContain('Waiting for your measurements…');
    expect(textOf(<KioskView {...view({ step: 'measure', who: { ...who, language: 'en' }, summary })} />)).toContain('1 measurement(s) received.');
    const p = view({ step: 'summary', who: { ...who, language: 'en' }, summary });
    const tree = <KioskView {...p} />;
    expect(textOf(tree)).toContain('Blood pressure and pulse');
    expect(textOf(tree)).toContain('Your blood pressure is in the high-normal range.');
    expect(textOf(tree)).toContain('This summary is not a diagnosis.');
    findOne(tree, (e) => e.type === 'button' && textOf(e.props.children) === en.kioskFinish).props.onClick();
    expect(p.onClear).toHaveBeenCalled();
  });

  it('shows the idle countdown with "I\'m still here", and the privacy screen afterwards', () => {
    const p = view({ step: 'measure', who: { ...who, language: 'en' }, remaining: 12 });
    const tree = <KioskView {...p} />;
    expect(textOf(tree)).toContain('this screen will clear in 12 seconds');
    findOne(tree, (e) => e.type === 'button' && textOf(e.props.children) === "I'm still here").props.onClick();
    expect(p.onTouch).toHaveBeenCalled();
    expect(textOf(<KioskView {...view({ step: 'privacy' })} />)).toContain('cleared for the next person');
  });

  it('without the kiosk shell bridge the page explains it runs only on an enrolled kiosk', () => {
    expect(renderToStaticMarkup(<Kiosk lang="en" />)).toContain('enrolled WellnessZ health kiosk');
    const bridge: KioskBridge = { identify: vi.fn(), consent: vi.fn(), summary: vi.fn() };
    const html = renderToStaticMarkup(<Kiosk lang="en" bridge={bridge} />);
    expect(html).toContain('Scan your staff badge');
  });
});
