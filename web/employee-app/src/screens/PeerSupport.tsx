import { useState, type FormEvent } from 'react';
import { fetchPeers, MAX_ALIAS, peerChoices, requestPeerContact, validatePeerRequest, type Peer, type PeerRequestErrors, type PeerRequestInput } from '../api/support';
import { SessionExpiredError } from '../auth/AuthClient';
import { PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { EmergencyBanner } from '../components/wellbeing';
import { wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

/** Available peers: alias, base, languages only. Hook-free. */
export function PeerList({ t, peers }: { t: WellbeingStrings; peers: readonly Peer[] }) {
  if (peers.length === 0) return <p className="muted">{t.pePeersEmpty}</p>;
  return (
    <ul className="card-list">
      {peers.map((p, i) => (
        <li key={`${p.alias}-${i}`} className="card">
          <strong>
            <bdi>{p.alias}</bdi>
          </strong>
          <dl className="case-facts">
            {p.base && (
              <>
                <dt>{t.peBase}</dt>
                <dd>{p.base}</dd>
              </>
            )}
            {p.languages.length > 0 && (
              <>
                <dt>{t.peLanguages}</dt>
                <dd>{p.languages.join(', ')}</dd>
              </>
            )}
          </dl>
        </li>
      ))}
    </ul>
  );
}

export type PeerDraft = { alias: string; callbackPhone: string; base: string; language: string };

export interface PeerRequestFormProps {
  t: WellbeingStrings;
  peers: readonly Peer[];
  draft: PeerDraft;
  errors: PeerRequestErrors;
  busy: boolean;
  onChange: (d: PeerDraft) => void;
  onSubmit: () => void;
}

/** Anonymous call-back request: an alias chosen by the requester and a call-back number. Hook-free. */
export function PeerRequestForm(p: PeerRequestFormProps) {
  const { t, draft, errors } = p;
  const { bases, languages } = peerChoices(p.peers);
  const set = (patch: Partial<PeerDraft>) => p.onChange({ ...draft, ...patch });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    p.onSubmit();
  };
  return (
    <form className="form" onSubmit={submit} noValidate>
      <div className="field">
        <label htmlFor="pe-alias">{t.peAlias}</label>
        <input id="pe-alias" autoComplete="off" maxLength={MAX_ALIAS} value={draft.alias} onChange={(e) => set({ alias: e.target.value })} aria-invalid={!!errors.alias} aria-describedby="pe-alias-hint" />
        <p id="pe-alias-hint" className="muted small">
          {t.peAliasHint}
        </p>
        {errors.alias && <p className="field-error">{errors.alias === 'required' ? t.errRequired : t.errTooLong(MAX_ALIAS)}</p>}
      </div>
      <div className="field">
        <label htmlFor="pe-phone">{t.pePhone}</label>
        <input id="pe-phone" type="tel" inputMode="tel" autoComplete="tel" value={draft.callbackPhone} onChange={(e) => set({ callbackPhone: e.target.value })} aria-invalid={!!errors.callbackPhone} />
        {errors.callbackPhone && <p className="field-error">{errors.callbackPhone === 'required' ? t.errRequired : t.errPhone}</p>}
      </div>
      {bases.length > 0 && (
        <div className="field">
          <label htmlFor="pe-base">
            {t.peBasePref} <span className="muted">{t.optional}</span>
          </label>
          <select id="pe-base" value={draft.base} onChange={(e) => set({ base: e.target.value })}>
            <option value="">{t.noPreference}</option>
            {bases.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
        </div>
      )}
      {languages.length > 0 && (
        <div className="field">
          <label htmlFor="pe-lang">
            {t.peLanguagePref} <span className="muted">{t.optional}</span>
          </label>
          <select id="pe-lang" value={draft.language} onChange={(e) => set({ language: e.target.value })}>
            <option value="">{t.noPreference}</option>
            {languages.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
        </div>
      )}
      <button type="submit" disabled={p.busy}>
        {p.busy ? t.sending : t.peSubmit}
      </button>
    </form>
  );
}

/** `#/peer-support` – available peers and the anonymous call-back request (E09 §3.3). */
export function PeerSupport({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const peers = useAsync(() => fetchPeers(client), onSessionExpired);
  const [draft, setDraft] = useState<PeerDraft>({ alias: '', callbackPhone: '', base: '', language: '' });
  const [errors, setErrors] = useState<PeerRequestErrors>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [done, setDone] = useState(false);

  async function submit() {
    const errs = validatePeerRequest(draft);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;
    setBusy(true);
    setMessage(undefined);
    try {
      const code = await requestPeerContact(client, draft as PeerRequestInput);
      setMessage({ kind: 'success', text: code ? t.peDone(code) : t.peDoneNoCode });
      setDone(true);
      // the phone number is not kept in the form once sent
      setDraft({ alias: '', callbackPhone: '', base: '', language: '' });
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: wellbeingErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.peTitle}</PageTitle>
      <EmergencyBanner t={t} />
      <p className="lead">{t.peIntro}</p>
      <p className="consent-box">{t.peNonPunitive}</p>
      <p className="muted small">{t.peAnonymous}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>

      <h2>{t.pePeersTitle}</h2>
      {peers.loading && <Loading />}
      {!!peers.error && <Alert kind="error">{wellbeingErrorText(lang, peers.error)}</Alert>}
      {peers.data && <PeerList t={t} peers={peers.data} />}

      {!done && (
        <>
          <h2>{t.peRequestTitle}</h2>
          <PeerRequestForm t={t} peers={peers.data ?? []} draft={draft} errors={errors} busy={busy} onChange={setDraft} onSubmit={() => void submit()} />
        </>
      )}
    </PortalSection>
  );
}
