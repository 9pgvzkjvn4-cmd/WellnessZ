import { useState, type FormEvent } from 'react';
import { checkInWindow, fetchCheckIns, submitCheckIn, SYMPTOMS, TEMP_MAX, TEMP_MIN, toggleSymptom, validateCheckIn, type CheckIn, type CheckInErrors, type Symptom } from '../api/prevention';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDay } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { Fact } from '../components/wellbeing';
import { wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

export interface CheckInCardProps {
  t: WellbeingStrings;
  lang: string;
  item: CheckIn;
  now?: Date;
  symptoms: readonly Symptom[];
  temperature: string;
  errors: CheckInErrors;
  busy: boolean;
  onToggle: (s: Symptom) => void;
  onTemperature: (v: string) => void;
  onSubmit: (item: CheckIn) => void;
}

/** One daily check-in: symptom checkboxes and optional temperature, only on its day. Hook-free. */
export function CheckInCard(p: CheckInCardProps) {
  const { t, item } = p;
  const w = checkInWindow(item, p.now);
  const id = `chk-${item.id}`;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    p.onSubmit(item);
  };
  return (
    <article className="card" aria-labelledby={id}>
      <div className="card-row">
        <h3 id={id} className="card-title">
          {t.chkFor} {item.dueOn ? formatDay(item.dueOn, p.lang) : '–'}
        </h3>
        <StatusBadge status={`chk-${w}`} label={t.windows[w]} />
      </div>
      <dl className="case-facts">
        <Fact label={t.chkUntil}>{item.monitoringUntil && formatDay(item.monitoringUntil, p.lang)}</Fact>
      </dl>
      {w === 'today' && (
        <form className="form" onSubmit={submit} noValidate>
          <fieldset aria-describedby={p.errors.symptoms ? `${id}-sym-err` : undefined}>
            <legend>{t.chkSymptoms}</legend>
            <div className="chips">
              {SYMPTOMS.map((s) => (
                <label key={s} className="large-touch">
                  <input type="checkbox" checked={p.symptoms.includes(s)} onChange={() => p.onToggle(s)} /> {t.symptoms[s]}
                </label>
              ))}
            </div>
            {p.errors.symptoms && (
              <p id={`${id}-sym-err`} className="field-error">
                {t.chkErrSymptoms}
              </p>
            )}
          </fieldset>
          <div className="field">
            <label htmlFor={`${id}-temp`}>
              {t.chkTemp} <span className="muted">{t.optional}</span>
            </label>
            <input id={`${id}-temp`} type="number" inputMode="decimal" step="0.1" min={TEMP_MIN} max={TEMP_MAX} value={p.temperature} onChange={(e) => p.onTemperature(e.target.value)} aria-invalid={!!p.errors.temperature} aria-describedby={`${id}-temp-hint`} />
            <p id={`${id}-temp-hint`} className="muted small">
              {t.chkTempHint}
            </p>
            {p.errors.temperature && <p className="field-error">{t.chkErrTemp}</p>}
          </div>
          <button type="submit" disabled={p.busy}>
            {p.busy ? t.sending : t.chkSubmit}
          </button>
        </form>
      )}
    </article>
  );
}

/** `#/check-ins` – daily symptom check-ins for contacts of a communicable-disease case (E11 §5.2). */
export function CheckIns({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const items = useAsync(() => fetchCheckIns(client), onSessionExpired);
  const [symptoms, setSymptoms] = useState<Symptom[]>([]);
  const [temperature, setTemperature] = useState('');
  const [errors, setErrors] = useState<CheckInErrors>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success' | 'info'; text: string }>();

  async function send(item: CheckIn) {
    const errs = validateCheckIn(symptoms, temperature);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;
    setBusy(true);
    setMessage(undefined);
    try {
      const symptomatic = await submitCheckIn(client, item.id, symptoms, temperature);
      setMessage(symptomatic ? { kind: 'info', text: t.chkSymptomatic } : { kind: 'success', text: t.chkOk });
      setSymptoms([]);
      setTemperature('');
      items.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: wellbeingErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.chkTitle}</PageTitle>
      <p className="lead">{t.chkIntro}</p>
      <p className="muted small">{t.chkPrivacy}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {items.loading && <Loading />}
      {!!items.error && <Alert kind="error">{wellbeingErrorText(lang, items.error)}</Alert>}
      {items.data && items.data.length === 0 && <EmptyState title={t.chkEmpty} hint={t.chkEmptyHint} />}
      {items.data && items.data.length > 0 && (
        <ul className="card-list">
          {items.data.map((c) => (
            <li key={c.id}>
              <CheckInCard
                t={t}
                lang={lang}
                item={c}
                symptoms={symptoms}
                temperature={temperature}
                errors={errors}
                busy={busy}
                onToggle={(s) => setSymptoms((cur) => toggleSymptom(cur, s))}
                onTemperature={setTemperature}
                onSubmit={(i) => void send(i)}
              />
            </li>
          ))}
        </ul>
      )}
    </PortalSection>
  );
}
