import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { kioskBridge, normaliseBadge, type KioskBridge, type KioskIdentity, type KioskSummary } from '../api/devices';
import { deviceStrings, type DeviceStrings } from '../i18n/devices';
import { labelOf } from '../i18n/portal';
import { dirFor } from '../i18n/strings';
import { afterConsent, afterIdentify, holdsPersonData, identifyFailure, idleCheck, KIOSK_IDLE_MS, type KioskStep as Step } from './kioskModel';

export interface KioskProps {
  /** App language until the employee is identified; then the employee's preferred language. */
  lang: string;
  /** Injected in tests; default: the bridge of the kiosk shell (`window.wellnesszKiosk`). */
  bridge?: KioskBridge;
  /** Privacy timeout after the last interaction (contract §14.6: 3 minutes). */
  idleMs?: number;
  /** Countdown shown before the screen clears. */
  warnMs?: number;
  /** How often the measuring step asks for the summary. */
  pollMs?: number;
  /** How long the privacy screen stays before the kiosk is ready again. */
  privacyMs?: number;
}

/**
 * Kiosk self-measurement (`#/kiosk`, contract §14.6, E33-HLR-07): badge → first name → consent (KIOSK_SELF_MEASUREMENT) →
 * measure → plain-language summary (BP category, BMI band – no diagnosis). Clears to a privacy screen after 3 minutes without
 * interaction and after every visit; holds nothing but the first name and the summary, in memory only.
 */
export function Kiosk({ lang, bridge = kioskBridge(), idleMs = KIOSK_IDLE_MS, warnMs = 20_000, pollMs = 3000, privacyMs = 5000 }: KioskProps) {
  const [step, setStep] = useState<Step>('badge');
  const [who, setWho] = useState<KioskIdentity>();
  const [summary, setSummary] = useState<KioskSummary>();
  const [badge, setBadge] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [remaining, setRemaining] = useState<number>();
  const lastActivity = useRef(0);
  const uiLang = who?.language ?? lang;
  const t = deviceStrings(uiLang);

  const clear = useCallback((next: Step) => {
    setWho(undefined);
    setSummary(undefined);
    setBadge('');
    setError(undefined);
    setRemaining(undefined);
    setStep(next);
  }, []);

  // privacy timeout: any interaction restarts it; a countdown is shown during the last `warnMs`
  const touch = useCallback(() => {
    lastActivity.current = Date.now();
    setRemaining(undefined);
  }, []);
  useEffect(() => {
    if (!holdsPersonData(step)) return;
    lastActivity.current = Date.now();
    const events = ['pointerdown', 'keydown', 'touchstart'] as const;
    events.forEach((e) => window.addEventListener(e, touch));
    const timer = setInterval(() => {
      const c = idleCheck(lastActivity.current, Date.now(), idleMs, warnMs);
      if (c.expired) clear('privacy');
      else setRemaining(c.remaining);
    }, Math.min(1000, Math.max(50, Math.floor(warnMs / 4))));
    return () => {
      events.forEach((e) => window.removeEventListener(e, touch));
      clearInterval(timer);
    };
  }, [step, idleMs, warnMs, touch, clear]);

  // privacy screen → ready for the next person
  useEffect(() => {
    if (step !== 'privacy' && step !== 'declined') return;
    const timer = setTimeout(() => clear('badge'), privacyMs);
    return () => clearTimeout(timer);
  }, [step, privacyMs, clear]);

  // measuring: ask for the summary until readings arrive (the devices post them with the kiosk session id)
  const sessionId = who?.kioskSessionId;
  useEffect(() => {
    if (step !== 'measure' || !bridge || !sessionId) return;
    let stopped = false;
    const load = () =>
      bridge.summary(sessionId).then(
        (s) => {
          if (!stopped) setSummary(s);
        },
        () => undefined,
      );
    void load();
    const timer = setInterval(() => void load(), pollMs);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [step, bridge, sessionId, pollMs]);

  if (!bridge) {
    return (
      <main id="main" className="kiosk" lang={lang} dir={dirFor(lang)}>
        <h1>{t.kioskTitle}</h1>
        <p className="kiosk-message">{t.kioskUnavailable}</p>
      </main>
    );
  }
  const api = bridge;

  async function identify(e: FormEvent) {
    e.preventDefault();
    const id = normaliseBadge(badge);
    if (!id) {
      setError(t.kioskNotFound);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.identify(id);
      setWho(r);
      setBadge('');
      setStep(afterIdentify(r));
    } catch (err) {
      setBadge('');
      setError(identifyFailure(err) === 'notFound' ? t.kioskNotFound : t.kioskError);
    } finally {
      setBusy(false);
    }
  }

  async function consent(accepted: boolean) {
    if (!who) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.consent(who.kioskSessionId, accepted, who.consentNoticeVersion);
      const next = afterConsent(accepted);
      if (next === 'measure') setStep(next);
      else clear(next);
    } catch {
      setError(t.kioskError);
    } finally {
      setBusy(false);
    }
  }

  async function finish() {
    if (!who) return;
    setBusy(true);
    try {
      setSummary(await api.summary(who.kioskSessionId));
      setStep('summary');
    } catch {
      setError(t.kioskError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <KioskView
      t={t}
      uiLang={uiLang}
      step={step}
      who={who}
      summary={summary}
      badge={badge}
      busy={busy}
      error={error}
      remaining={remaining}
      onBadge={setBadge}
      onIdentify={(e) => void identify(e)}
      onConsent={(a) => void consent(a)}
      onFinish={() => void finish()}
      onClear={() => clear('privacy')}
      onTouch={touch}
    />
  );
}

export interface KioskViewProps {
  t: DeviceStrings;
  uiLang: string;
  step: Step;
  who?: KioskIdentity;
  summary?: KioskSummary;
  badge: string;
  busy: boolean;
  error?: string;
  remaining?: number;
  onBadge: (v: string) => void;
  onIdentify: (e: FormEvent) => void;
  onConsent: (accepted: boolean) => void;
  onFinish: () => void;
  onClear: () => void;
  onTouch: () => void;
}

/** Hook-free kiosk screens (one per step). */
export function KioskView({ t, uiLang, step, who, summary, badge, busy, error, remaining, onBadge, onIdentify, onConsent, onFinish, onClear, onTouch }: KioskViewProps) {
  return (
    <main id="main" className={`kiosk kiosk-step-${step}`} lang={uiLang} dir={dirFor(uiLang)}>
      <header className="kiosk-head">
        <span className="brand">WellnessZ</span> <span>{t.kioskTitle}</span>
      </header>
      {remaining !== undefined && (
        <div className="kiosk-idle" role="alert">
          <p>{t.kioskIdle(remaining)}</p>
          <button type="button" className="large-touch" onClick={onTouch}>
            {t.kioskStillHere}
          </button>
        </div>
      )}
      {error && (
        <p className="alert alert-error" role="alert">
          {error}
        </p>
      )}

      {step === 'badge' && (
        <form className="kiosk-card" onSubmit={onIdentify}>
          <h1>{t.kioskWelcome}</h1>
          <p>{t.kioskBadgeHint}</p>
          <label htmlFor="kiosk-badge">{t.kioskBadge}</label>
          {/* keyboard-wedge badge readers type the number followed by Enter */}
          <input id="kiosk-badge" className="kiosk-input" autoFocus autoComplete="off" inputMode="text" maxLength={64} value={badge} onChange={(e) => onBadge(e.target.value)} />
          <button type="submit" className="large-touch" disabled={busy || !badge.trim()}>
            {t.kioskStart}
          </button>
        </form>
      )}

      {step === 'consent' && who && (
        <section className="kiosk-card" aria-labelledby="kiosk-consent">
          <h1>{t.kioskHello(who.firstName)}</h1>
          <h2 id="kiosk-consent">{t.kioskConsentTitle}</h2>
          <p>{t.kioskConsentText}</p>
          {who.consentNoticeVersion && <p className="muted small">{t.kioskConsentVersion(who.consentNoticeVersion)}</p>}
          <div className="actions">
            <button type="button" className="large-touch" disabled={busy} onClick={() => onConsent(true)}>
              {t.kioskAgree}
            </button>
            <button type="button" className="large-touch secondary" disabled={busy} onClick={() => onConsent(false)}>
              {t.kioskDecline}
            </button>
          </div>
        </section>
      )}

      {step === 'measure' && who && (
        <section className="kiosk-card" aria-labelledby="kiosk-measure">
          <h1>{t.kioskHello(who.firstName)}</h1>
          <h2 id="kiosk-measure">{t.kioskMeasureTitle}</h2>
          <ol className="kiosk-steps">
            {t.kioskMeasureSteps.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ol>
          <p aria-live="polite" className="kiosk-progress">
            {summary && summary.items.length > 0 ? t.kioskReceived(summary.items.length) : t.kioskWaiting}
          </p>
          <button type="button" className="large-touch" disabled={busy} onClick={onFinish}>
            {t.kioskDone}
          </button>
        </section>
      )}

      {step === 'summary' && who && (
        <section className="kiosk-card" aria-labelledby="kiosk-summary">
          <h1 id="kiosk-summary">{t.kioskSummaryTitle}</h1>
          {summary && summary.items.length > 0 ? (
            <ul className="kiosk-summary">
              {summary.items.map((i, k) => (
                <li key={`${i.category}-${k}`}>
                  <strong>{labelOf(t.categories, i.category)}</strong>
                  <p>{i.plainLanguage}</p>
                </li>
              ))}
            </ul>
          ) : (
            <p>{t.kioskSummaryEmpty}</p>
          )}
          <p className="muted small">{t.kioskNotDiagnosis}</p>
          <button type="button" className="large-touch" onClick={onClear}>
            {t.kioskFinish}
          </button>
        </section>
      )}

      {(step === 'privacy' || step === 'declined') && (
        <section className="kiosk-card" aria-live="polite">
          <h1>{t.kioskTitle}</h1>
          <p>{step === 'declined' ? t.kioskDeclined : t.kioskPrivacy}</p>
        </section>
      )}
    </main>
  );
}
