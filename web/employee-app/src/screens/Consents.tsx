import { useState, type FormEvent } from 'react';
import {
  asList,
  CONSENT_PURPOSE_LABELS,
  CONSENT_PURPOSES,
  type Consent,
  type ConsentPurpose,
  type GrantConsentRequest,
} from '../api/types';
import { SessionExpiredError } from '../auth/AuthClient';
import { describeError, formatDate, humanise } from '../components/format';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const isWithdrawn = (c: Consent) => c.status.toUpperCase() === 'WITHDRAWN' || !!c.withdrawnAt;

function labelFor(purpose: string): string {
  return CONSENT_PURPOSE_LABELS[purpose as ConsentPurpose] ?? humanise(purpose);
}

function ConsentMeta({ c }: { c: Consent }) {
  return (
    <div className="muted small">
      {c.scope ? `Scope: ${humanise(c.scope)} · ` : ''}Given {formatDate(c.grantedAt)}
      {c.noticeVersion ? ` · Privacy notice v${c.noticeVersion}` : ''}
      {c.withdrawnAt ? ` · Withdrawn ${formatDate(c.withdrawnAt)}` : ''}
    </div>
  );
}

export function Consents({ client, onSessionExpired }: ScreenProps) {
  const consents = useAsync(async () => asList<Consent>(await client.api<unknown>('/consents/me')), onSessionExpired);
  const [purpose, setPurpose] = useState<ConsentPurpose>(CONSENT_PURPOSES[0]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  const active = consents.data?.filter((c) => !isWithdrawn(c)) ?? [];
  const history = consents.data?.filter(isWithdrawn) ?? [];
  const activePurposes = new Set(active.map((c) => c.purpose));
  const grantable = CONSENT_PURPOSES.filter((p) => !activePurposes.has(p));
  const selected = grantable.includes(purpose) ? purpose : grantable[0];

  const handle = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setMessage(undefined);
    try {
      await fn();
      setMessage({ kind: 'success', text: ok });
      consents.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: describeError(e) });
    } finally {
      setBusy(false);
    }
  };

  const grant = (e: FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    const body: GrantConsentRequest = { purpose: selected };
    void handle(() => client.api('/consents', { method: 'POST', body }), 'Consent recorded.');
  };

  const withdraw = (c: Consent) => {
    const msg = `Withdraw consent for "${labelFor(c.purpose)}"?\n\nPartner providers lose access immediately to every document shared under this consent. This is recorded in your disclosure log.`;
    if (!window.confirm(msg)) return;
    void handle(
      () => client.api(`/consents/${encodeURIComponent(c.id)}`, { method: 'DELETE' }),
      'Consent withdrawn. Partner access has been stopped.',
    );
  };

  return (
    <section>
      <PageTitle>My consents</PageTitle>
      <p className="lead">
        You decide what Occupational Health may share. You can withdraw consent at any time; this does not affect
        processing that is required by law. See <a href="#/privacy">who received my information</a>.
      </p>
      {message && <Alert kind={message.kind}>{message.text}</Alert>}

      {consents.loading && <Loading />}
      {!!consents.error && <Alert kind="error">{describeError(consents.error)}</Alert>}
      {consents.data && active.length === 0 && <p className="muted">You have no active consents.</p>}
      {active.length > 0 && (
        <ul className="card-list" aria-label="Active consents">
          {active.map((c) => (
            <li key={c.id} className="card">
              <div className="card-row">
                <strong>{labelFor(c.purpose)}</strong>
                <StatusBadge status="GRANTED" />
              </div>
              <ConsentMeta c={c} />
              <button type="button" className="secondary small-button" disabled={busy} onClick={() => withdraw(c)}>
                Withdraw
              </button>
            </li>
          ))}
        </ul>
      )}

      <h2>Give consent</h2>
      {grantable.length === 0 ? (
        <p className="muted">You have given all available consents.</p>
      ) : (
        <form onSubmit={grant} className="form">
          <label htmlFor="purpose">Purpose</label>
          <select id="purpose" value={selected} onChange={(e) => setPurpose(e.target.value as ConsentPurpose)}>
            {grantable.map((p) => (
              <option key={p} value={p}>
                {CONSENT_PURPOSE_LABELS[p]}
              </option>
            ))}
          </select>
          <button type="submit" disabled={busy || !consents.data}>
            Give consent
          </button>
        </form>
      )}

      {history.length > 0 && (
        <>
          <h2>Consent history</h2>
          <ul className="card-list" aria-label="Withdrawn consents">
            {history.map((c) => (
              <li key={c.id} className="card history">
                <div className="card-row">
                  <span>{labelFor(c.purpose)}</span>
                  <StatusBadge status="WITHDRAWN" />
                </div>
                <ConsentMeta c={c} />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
