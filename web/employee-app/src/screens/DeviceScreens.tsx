import { useState, type FormEvent } from 'react';
import {
  WEARABLE_SCOPES, fetchDeviceConsents, fetchDeviceSummaries, grantDeviceConsent, isActiveConsent, revokeDeviceConsent, type DeviceConsent, type DeviceSummary,
  type WearableScope,
} from '../api/devices';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { deviceStrings, type DeviceStrings } from '../i18n/devices';
import { errorText, labelOf, portalStrings } from '../i18n/portal';
import { baseLang, dirFor } from '../i18n/strings';
import type { PortalScreenProps } from './types';

/** Newest first; only https education links are shown. */
export function SummaryList({ t, lang, rows }: { t: DeviceStrings; lang: string; rows: readonly DeviceSummary[] }) {
  if (rows.length === 0) return <EmptyState title={t.resEmpty} hint={t.resNotDiagnosis} />;
  const sorted = [...rows].sort((a, b) => b.measuredAt.localeCompare(a.measuredAt));
  return (
    <ul className="card-list" aria-label={t.resTitle}>
      {sorted.map((s) => {
        const other = !!s.language && baseLang(s.language) !== baseLang(lang);
        return (
          <li key={s.summaryId} className="card device-summary">
            <div className="card-row">
              <strong>{labelOf(t.categories, s.category)}</strong>
              <span className="muted small">{t.resMeasured(formatDate(s.measuredAt, lang))}</span>
            </div>
            <p className="summary-text" lang={s.language ?? undefined} dir={s.language ? dirFor(s.language) : undefined}>
              {s.plainLanguage}
            </p>
            {other && <p className="muted small">{t.resOtherLanguage}</p>}
            {s.educationLink && /^https:\/\//.test(s.educationLink) && (
              <a href={s.educationLink} target="_blank" rel="noopener noreferrer">
                {t.resLearnMore}
              </a>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/**
 * "My device results" (`#/health-checks`, contract §14.8 / §18): released plain-language summaries in the preferred language –
 * blood-pressure category, BMI band and similar – never a diagnosis.
 */
export function MyDeviceResults({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = deviceStrings(lang);
  const p = portalStrings(lang);
  const list = useAsync(() => fetchDeviceSummaries(client), onSessionExpired);
  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.resTitle}</PageTitle>
      <p className="lead">{t.resIntro}</p>
      <Alert kind="info">{t.resNotDiagnosis}</Alert>
      {list.loading && <Loading label={t.loading} />}
      {!!list.error && <Alert kind="error">{errorText(p, list.error)}</Alert>}
      {list.data && <SummaryList t={t} lang={lang} rows={list.data} />}
    </PortalSection>
  );
}

export interface ConsentListProps {
  t: DeviceStrings;
  lang: string;
  rows: readonly DeviceConsent[];
  busy: boolean;
  confirmId?: string;
  onAsk: (id: string) => void;
  onConfirm: (id: string) => void;
  onCancel: () => void;
}

/** Wearable consents with their scopes; withdrawal asks for confirmation first. */
export function ConsentList({ t, lang, rows, busy, confirmId, onAsk, onConfirm, onCancel }: ConsentListProps) {
  if (rows.length === 0) return <p className="muted">{t.conEmpty}</p>;
  return (
    <ul className="card-list" aria-label={t.conActive}>
      {rows.map((c) => (
        <li key={c.id} className="card">
          <div className="card-row">
            <strong>{c.scopes.map((s) => labelOf(t.scopes, s)).join(', ')}</strong>
            <StatusBadge status={isActiveConsent(c) ? 'active' : 'withdrawn'} label={labelOf(t.conStatus, c.status)} />
          </div>
          <p className="muted small">
            {c.grantedAt && t.conSince(formatDate(c.grantedAt, lang))}
            {c.revokedAt && <> · {t.conWithdrawnOn(formatDate(c.revokedAt, lang))}</>}
          </p>
          {isActiveConsent(c) &&
            (confirmId === c.id ? (
              <div role="alertdialog" aria-label={t.conRevoke} className="confirm-panel">
                <p>{t.conRevokeConfirm}</p>
                <button type="button" className="danger" disabled={busy} onClick={() => onConfirm(c.id)}>
                  {t.conRevokeYes}
                </button>{' '}
                <button type="button" className="secondary" onClick={onCancel}>
                  {t.conCancel}
                </button>
              </div>
            ) : (
              <button type="button" className="secondary" onClick={() => onAsk(c.id)}>
                {t.conRevoke}
              </button>
            ))}
        </li>
      ))}
    </ul>
  );
}

export interface GrantFormProps {
  t: DeviceStrings;
  scopes: readonly WearableScope[];
  busy: boolean;
  onToggle: (scope: WearableScope, on: boolean) => void;
  onSubmit: (e: FormEvent) => void;
}

export function GrantForm({ t, scopes, busy, onToggle, onSubmit }: GrantFormProps) {
  return (
    <form className="form" onSubmit={onSubmit} noValidate aria-label={t.conGrantTitle}>
      <fieldset>
        <legend>{t.conScopes}</legend>
        {WEARABLE_SCOPES.map((s) => (
          <label key={s} className="checkbox">
            <input type="checkbox" name={`scope-${s}`} checked={scopes.includes(s)} onChange={(e) => onToggle(s, e.target.checked)} /> {t.scopes[s]}{' '}
            <span className="muted small">– {t.scopeHints[s]}</span>
          </label>
        ))}
      </fieldset>
      <button type="submit" disabled={busy}>
        {t.conGrant}
      </button>
    </form>
  );
}

/** "Device and wearable consents" (`#/device-consents`): grant WEARABLE_DATA scopes, see and withdraw consents. */
export function DeviceConsents({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = deviceStrings(lang);
  const p = portalStrings(lang);
  const list = useAsync(() => fetchDeviceConsents(client), onSessionExpired);
  const [scopes, setScopes] = useState<WearableScope[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [message, setMessage] = useState<string>();
  const [confirmId, setConfirmId] = useState<string>();

  async function run(fn: () => Promise<unknown>, ok: string): Promise<boolean> {
    setBusy(true);
    setError(undefined);
    setMessage(undefined);
    try {
      await fn();
      setMessage(ok);
      list.reload();
      return true;
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setError(errorText(p, e));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function grant(e: FormEvent) {
    e.preventDefault();
    if (scopes.length === 0) {
      setError(t.conPickOne);
      return;
    }
    if (await run(() => grantDeviceConsent(client, scopes), t.conGranted)) setScopes([]);
  }

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.conTitle}</PageTitle>
      <p className="lead">{t.conIntro}</p>
      <div aria-live="polite">
        {message && <Alert kind="success">{message}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
      </div>
      <h2>{t.conActive}</h2>
      {list.loading && <Loading label={t.loading} />}
      {!!list.error && <Alert kind="error">{errorText(p, list.error)}</Alert>}
      {list.data && (
        <ConsentList
          t={t}
          lang={lang}
          rows={list.data}
          busy={busy}
          confirmId={confirmId}
          onAsk={setConfirmId}
          onCancel={() => setConfirmId(undefined)}
          onConfirm={(id) => void run(() => revokeDeviceConsent(client, id), t.conRevoked).then(() => setConfirmId(undefined))}
        />
      )}
      <h2>{t.conGrantTitle}</h2>
      <GrantForm t={t} scopes={scopes} busy={busy} onToggle={(s, on) => setScopes(on ? [...scopes, s] : scopes.filter((x) => x !== s))} onSubmit={(e) => void grant(e)} />
      <p className="muted small">{t.conKioskNote}</p>
    </PortalSection>
  );
}
