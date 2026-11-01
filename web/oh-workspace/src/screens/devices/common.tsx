import { useState } from 'react';
import { formatDate, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Alert } from '../../components/ui';
import { toneFor } from '../../domain/deviceConsole';
import { isModuleUnavailable, problemCode } from '../../domain/deviceWizard';

export function CodeBadge({ code, label, title }: { code: string | null | undefined; label?: string; title?: string }) {
  if (!code) return <span className="muted">–</span>;
  return <ToneBadge tone={toneFor(code)} title={title}>{label ?? humanise(code)}</ToneBadge>;
}

/** SHA-256 / long ids: abbreviated, full value available to assistive technology and on hover. */
export function Hash({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">–</span>;
  return (
    <span className="mono" title={value}>
      {value.slice(0, 12)}…<span className="visually-hidden"> full value {value}</span>
    </span>
  );
}

/** `503 module_disabled`: a dependent module (e.g. the integration engine) is not deployed yet – an expected state. */
export function UnavailableNotice({ error, what }: { error: unknown; what: string }) {
  if (!isModuleUnavailable(error)) return null;
  return (
    <Alert kind="warning">
      <strong>{what} is not available in this environment yet.</strong>{' '}
      {problemCode(error) === 'module_disabled'
        ? 'The module that runs it is disabled or not deployed (module_disabled). Everything else can continue; try again once it is enabled.'
        : 'The service returned no result. Try again later.'}
    </Alert>
  );
}

/**
 * A one-time secret (enrolment or endpoint token): shown once with a copy button and never stored by the console.
 * The value lives only in this component's state – navigating away discards it.
 */
export function OneTimeToken({ label, token, expiresAt, onDismiss }: { label: string; token: string; expiresAt?: string | null; onDismiss: () => void }) {
  const [copied, setCopied] = useState<'yes' | 'no'>();
  async function copy() {
    try {
      await navigator.clipboard.writeText(token);
      setCopied('yes');
    } catch {
      setCopied('no');
    }
  }
  return (
    <div className="token-box" role="region" aria-label={label}>
      <h3>{label}</h3>
      <p className="small">
        <strong>Shown once.</strong> Copy it now and hand it to the installer over a secure channel – it is stored only as a hash and cannot be displayed again.
        {expiresAt && <> Expires {formatDate(expiresAt)}.</>}
      </p>
      <code className="token-value" aria-label={`${label} value`}>{token}</code>
      <div className="actions">
        <button type="button" onClick={() => void copy()}>Copy token</button>
        <button type="button" className="secondary" onClick={onDismiss}>I have copied it – hide the token</button>
      </div>
      <p aria-live="polite" className="small">
        {copied === 'yes' && 'Copied to the clipboard.'}
        {copied === 'no' && 'Copying is blocked by the browser – select the token and copy it manually.'}
      </p>
    </div>
  );
}

/** Options for code lists shown verbatim (site codes, technical codes) – no humanising. */
export function CodeOptions({ values, empty }: { values: readonly string[]; empty?: string }) {
  return (
    <>
      {empty !== undefined && <option value="">{empty}</option>}
      {values.map((v) => <option key={v} value={v}>{v}</option>)}
    </>
  );
}
