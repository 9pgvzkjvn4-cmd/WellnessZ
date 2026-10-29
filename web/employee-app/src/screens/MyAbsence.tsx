import { useState, type FormEvent } from 'react';
import { fetchMyCertificates, fetchMyEpisodes, UPLOAD_ACCEPT, uploadCertificate, type MyCertificate, type MyEpisode } from '../api/occupational';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { labelOf } from '../i18n/portal';
import { ohErrorText, ohStrings, type OhStrings } from '../i18n/occupational';
import { formatBytes, type FileLike } from '../intake/attachments';
import { hasErrors, validateCertificate, type FormErrors } from '../intake/occupationalForms';
import type { PortalScreenProps } from './types';

export function EpisodeList({ t, lang, episodes }: { t: OhStrings; lang: string; episodes: readonly MyEpisode[] }) {
  if (episodes.length === 0) return <p className="muted">{t.absEpisodesEmpty}</p>;
  return (
    <ul className="card-list" aria-label={t.absEpisodes}>
      {episodes.map((e) => (
        <li key={e.id} className="card">
          <div className="card-row">
            <strong>{e.absenceType ? labelOf(t.absTypes, e.absenceType) : t.absEpisodes}</strong>
            {e.certificateValidity ? (
              <StatusBadge status={`cert-${e.certificateValidity}`} label={labelOf(t.certValidity, e.certificateValidity)} />
            ) : (
              <StatusBadge status={e.certified ? 'cert-received' : 'cert-none'} label={e.certified ? t.absCertified : t.absNotCertified} />
            )}
          </div>
          <dl className="case-facts">
            <dt>{t.absPeriod}</dt>
            <dd>
              {formatDay(e.startDate, lang)} – {e.endDate ? formatDay(e.endDate, lang) : t.absOngoing}
              {e.days !== undefined && <span className="muted"> ({t.absDays(e.days)})</span>}
            </dd>
          </dl>
        </li>
      ))}
    </ul>
  );
}

export function CertificateList({ t, lang, certificates }: { t: OhStrings; lang: string; certificates: readonly MyCertificate[] }) {
  if (certificates.length === 0) return <p className="muted">{t.absCertificatesEmpty}</p>;
  return (
    <ul className="card-list" aria-label={t.absCertificates}>
      {certificates.map((c) => (
        <li key={c.id} className="card">
          <div className="card-row">
            <span>
              {formatDay(c.fromDate, lang)} – {formatDay(c.toDate, lang)}
            </span>
            <StatusBadge status={`cert-${c.status}`} label={labelOf(t.certStatus, c.status)} />
          </div>
          <p className="muted small">
            {c.receivedAt && t.certReceived(formatDate(c.receivedAt, lang))} {labelOf(t.certStatusHint, c.status)}
          </p>
        </li>
      ))}
    </ul>
  );
}

export interface CertificateFormState {
  file?: FileLike;
  fromDate: string;
  toDate: string;
  episodeId: string;
}

export interface CertificateFormProps {
  t: OhStrings;
  state: CertificateFormState;
  errors: FormErrors<'file' | 'fromDate' | 'toDate'>;
  episodes: readonly MyEpisode[];
  busy: boolean;
  onFile: (f: File | undefined) => void;
  onChange: (patch: Partial<CertificateFormState>) => void;
  onSubmit: (e: FormEvent) => void;
}

/** Sick-certificate upload: camera or file + dates (+ optional absence period). Hook-free. */
export function CertificateForm({ t, state, errors, episodes, busy, onFile, onChange, onSubmit }: CertificateFormProps) {
  const err = (k: 'file' | 'fromDate' | 'toDate') => (errors[k] ? t.fieldErrors[errors[k]!] : undefined);
  return (
    <form className="form" onSubmit={onSubmit} noValidate aria-labelledby="cert-title">
      <h2 id="cert-title">{t.absUploadTitle}</h2>
      <p className="muted">{t.absUploadIntro}</p>
      <div className="actions" role="group" aria-describedby="cert-file-hint">
        <label className="button file-button">
          {t.absTakePhoto}
          <input type="file" className="visually-hidden" accept="image/*" capture="environment" disabled={busy} onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
        <label className="button secondary file-button">
          {t.absChooseFile}
          <input type="file" className="visually-hidden" accept={UPLOAD_ACCEPT} disabled={busy} onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
      </div>
      <p id="cert-file-hint" className="muted small">
        {t.absFileHint}
      </p>
      {state.file && (
        <p className="small">
          {t.absSelectedFile('')}
          <span dir="auto">{state.file.name}</span> <span className="muted">({formatBytes(state.file.size)})</span>
        </p>
      )}
      {err('file') && (
        <p className="field-error" id="cert-file-err" role="alert">
          {err('file')}
        </p>
      )}
      <div className="field">
        <label htmlFor="cert-from">{t.absFrom}</label>
        <input id="cert-from" type="date" value={state.fromDate} aria-invalid={!!err('fromDate')} aria-describedby={err('fromDate') ? 'cert-from-err' : undefined} onChange={(e) => onChange({ fromDate: e.target.value })} />
        {err('fromDate') && (
          <p id="cert-from-err" className="field-error">
            {err('fromDate')}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="cert-to">{t.absTo}</label>
        <input id="cert-to" type="date" value={state.toDate} min={state.fromDate || undefined} aria-invalid={!!err('toDate')} aria-describedby={err('toDate') ? 'cert-to-err' : undefined} onChange={(e) => onChange({ toDate: e.target.value })} />
        {err('toDate') && (
          <p id="cert-to-err" className="field-error">
            {err('toDate')}
          </p>
        )}
      </div>
      {episodes.length > 0 && (
        <div className="field">
          <label htmlFor="cert-episode">
            {t.absEpisode} <span className="muted">{t.optional}</span>
          </label>
          <select id="cert-episode" value={state.episodeId} onChange={(e) => onChange({ episodeId: e.target.value })}>
            <option value="">{t.absEpisodeNone}</option>
            {episodes.map((ep) => (
              <option key={ep.id} value={ep.id}>
                {ep.startDate} – {ep.endDate ?? t.absOngoing}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={busy}>
          {busy ? t.sending : t.absUpload}
        </button>
      </div>
    </form>
  );
}

const EMPTY: CertificateFormState = { fromDate: '', toDate: '', episodeId: '' };

/** `#/absence` – my absence periods, sick certificate upload (camera / file + dates) and certificate status (E06). */
export function MyAbsence({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = ohStrings(lang);
  const episodes = useAsync(() => fetchMyEpisodes(client), onSessionExpired);
  const certs = useAsync(() => fetchMyCertificates(client), onSessionExpired);
  // The selected File is held in memory only and dropped after the upload (success or failure).
  const [file, setFile] = useState<File>();
  const [form, setForm] = useState<CertificateFormState>(EMPTY);
  const [errors, setErrors] = useState<FormErrors<'file' | 'fromDate' | 'toDate'>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function submit(e: FormEvent) {
    e.preventDefault();
    const v = validateCertificate({ ...form, file });
    setErrors(v);
    if (hasErrors(v) || !file) return;
    setBusy(true);
    setMessage(undefined);
    try {
      await uploadCertificate(client, file, file.name || 'certificate', form.fromDate, form.toDate, form.episodeId || undefined);
      setMessage({ kind: 'success', text: t.absUploadedOk });
      setForm(EMPTY);
      setFile(undefined);
      certs.reload();
      episodes.reload();
    } catch (err) {
      if (err instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: ohErrorText(lang, err) });
      setFile(undefined);
    } finally {
      setBusy(false);
    }
  }

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.absTitle}</PageTitle>
      <p className="lead">{t.absIntro}</p>
      <Alert kind="info">{t.absPrivacy}</Alert>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      <section className="card">
        <CertificateForm
          t={t}
          state={{ ...form, file }}
          errors={errors}
          episodes={(episodes.data ?? []).filter((x) => x.open || !x.certified)}
          busy={busy}
          onFile={(f) => {
            setFile(f);
            setErrors((x) => ({ ...x, file: undefined }));
          }}
          onChange={(patch) => setForm((f) => ({ ...f, ...patch }))}
          onSubmit={(e) => void submit(e)}
        />
      </section>
      <h2>{t.absCertificates}</h2>
      {certs.loading && <Loading />}
      {!!certs.error && <Alert kind="error">{ohErrorText(lang, certs.error)}</Alert>}
      {certs.data && <CertificateList t={t} lang={lang} certificates={certs.data} />}
      <h2>{t.absEpisodes}</h2>
      {episodes.loading && <Loading />}
      {!!episodes.error && <Alert kind="error">{ohErrorText(lang, episodes.error)}</Alert>}
      {episodes.data && (episodes.data.length === 0 ? <EmptyState title={t.absEpisodesEmpty} hint={t.absPrivacy} /> : <EpisodeList t={t} lang={lang} episodes={episodes.data} />)}
    </PortalSection>
  );
}
