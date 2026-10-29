import { useState, type FormEvent } from 'react';
import {
  declarePregnancy,
  fetchMyImmunisations,
  fetchMySurveillance,
  requestVaccinationCertificate,
  type CrewCertificate,
  type MyImmunisation,
  type MyProgramme,
} from '../api/occupational';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { labelOf } from '../i18n/portal';
import { ohErrorText, ohStrings, type OhStrings } from '../i18n/occupational';
import { today, validateDeclaredOn, type FormErrorCode } from '../intake/occupationalForms';
import type { PortalScreenProps } from './types';

export function ProgrammeList({ t, lang, programmes }: { t: OhStrings; lang: string; programmes: readonly MyProgramme[] }) {
  if (programmes.length === 0) return <p className="muted">{t.survProgrammesEmpty}</p>;
  return (
    <ul className="card-list" aria-label={t.survProgrammes}>
      {programmes.map((p, i) => (
        <li key={`${p.title}-${i}`} className="card">
          <div className="card-row">
            <strong>{p.title}</strong>
            <StatusBadge status={`surv-${p.status}`} label={labelOf(t.survStatus, p.status)} />
          </div>
          <dl className="case-facts">
            <dt>{t.survNextDue}</dt>
            <dd>{formatDay(p.nextDueOn, lang)}</dd>
          </dl>
          {p.appointment?.startsAt ? (
            <p className="small">
              {t.survAppointment(formatDate(p.appointment.startsAt, lang))} {p.appointment.siteCode && <span className="muted">({p.appointment.siteCode})</span>}
            </p>
          ) : (
            (p.status === 'DUE' || p.status === 'OVERDUE') && <p className="muted small">{t.survBookHint}</p>
          )}
        </li>
      ))}
    </ul>
  );
}

export function ImmunisationTable({ t, lang, items }: { t: OhStrings; lang: string; items: readonly MyImmunisation[] }) {
  if (items.length === 0) return <p className="muted">{t.survImmsEmpty}</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">{t.survImms}</caption>
        <thead>
          <tr>
            <th scope="col">{t.immVaccine}</th>
            <th scope="col">{t.statusLabel}</th>
            <th scope="col">{t.immLastDose}</th>
            <th scope="col">{t.immNextDue}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((i, k) => (
            <tr key={`${i.vaccineCode ?? i.vaccineName}-${k}`}>
              <th scope="row">
                {i.vaccineName}
                {i.dosesRequired !== undefined && i.dosesGiven !== undefined && <div className="muted small">{t.immDoses(i.dosesGiven, i.dosesRequired)}</div>}
              </th>
              <td>{i.status && <StatusBadge status={`imm-${i.status}`} label={labelOf(t.immStatus, i.status)} />}</td>
              <td>{formatDay(i.lastDoseOn, lang)}</td>
              <td>{formatDay(i.nextDueOn, lang)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function CrewCertificateCard({ t, lang, c }: { t: OhStrings; lang: string; c: CrewCertificate }) {
  return (
    <section className="card" aria-labelledby="crew-cert">
      <div className="card-row">
        <h2 id="crew-cert" className="card-title">
          {t.crewTitle}
          {c.certificateType && <span className="muted small"> · {c.certificateType}</span>}
        </h2>
        {c.status && <StatusBadge status={`crew-${c.status}`} label={labelOf(t.crewStatus, c.status)} />}
      </div>
      {c.validUntil && <p>{t.crewValidUntil(formatDay(c.validUntil, lang))}</p>}
    </section>
  );
}

export interface PregnancyFormProps {
  t: OhStrings;
  open: boolean;
  declaredOn: string;
  error?: FormErrorCode;
  busy: boolean;
  done: boolean;
  max: string;
  onOpen: () => void;
  onChange: (v: string) => void;
  onSubmit: (e: FormEvent) => void;
}

/** Crew pregnancy declaration entry point – explains who sees it (AME / CMO only). Hook-free. */
export function PregnancyForm({ t, open, declaredOn, error, busy, done, max, onOpen, onChange, onSubmit }: PregnancyFormProps) {
  return (
    <section className="card" aria-labelledby="preg-title">
      <h2 id="preg-title" className="card-title">
        {t.pregTitle}
      </h2>
      <p>{t.pregIntro}</p>
      <p className="muted small">{t.pregWho}</p>
      {done ? (
        <p className="ack-box" role="status">
          {t.pregOk}
        </p>
      ) : !open ? (
        <button type="button" className="secondary" onClick={onOpen}>
          {t.pregStart}
        </button>
      ) : (
        <form className="form" onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="preg-date">{t.pregDate}</label>
            <input id="preg-date" type="date" max={max} value={declaredOn} aria-invalid={!!error} aria-describedby={error ? 'preg-date-err' : undefined} onChange={(e) => onChange(e.target.value)} />
            {error && (
              <p id="preg-date-err" className="field-error">
                {t.fieldErrors[error]}
              </p>
            )}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>
              {busy ? t.sending : t.pregSubmit}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

/** `#/surveillance` – my programmes & due dates, immunisations, vaccination certificate, crew pregnancy declaration (E07). */
export function MySurveillance({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = ohStrings(lang);
  const surv = useAsync(() => fetchMySurveillance(client), onSessionExpired);
  const imms = useAsync(() => fetchMyImmunisations(client), onSessionExpired);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string; docs?: boolean }>();
  const [preg, setPreg] = useState({ open: false, declaredOn: today(), error: undefined as FormErrorCode | undefined, done: false });

  async function run(fn: () => Promise<void>, success: string, docs?: boolean) {
    setBusy(true);
    setMessage(undefined);
    try {
      await fn();
      setMessage({ kind: 'success', text: success, docs });
      return true;
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: ohErrorText(lang, e) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function declare(e: FormEvent) {
    e.preventDefault();
    const err = validateDeclaredOn(preg.declaredOn);
    setPreg((p) => ({ ...p, error: err }));
    if (err) return;
    const ok = await run(() => declarePregnancy(client, preg.declaredOn), t.pregOk);
    if (ok) setPreg((p) => ({ ...p, done: true, open: false }));
  }

  // `/me/immunisations` is the detailed list; fall back to the summary in `/me/surveillance`.
  const immunisations = imms.data && imms.data.length > 0 ? imms.data : (surv.data?.immunisations ?? []);
  const crew = surv.data?.crewCertificate;

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.survTitle}</PageTitle>
      <p className="lead">{t.survIntro}</p>
      <div aria-live="polite">
        {message && (
          <Alert kind={message.kind}>
            {message.text} {message.docs && <a href="#/documents">{t.survCertOpen}</a>}
          </Alert>
        )}
      </div>
      {crew && <CrewCertificateCard t={t} lang={lang} c={crew} />}
      <h2>{t.survProgrammes}</h2>
      {surv.loading && <Loading />}
      {!!surv.error && <Alert kind="error">{ohErrorText(lang, surv.error)}</Alert>}
      {surv.data && <ProgrammeList t={t} lang={lang} programmes={surv.data.programmes} />}
      <h2>{t.survImms}</h2>
      {imms.loading && <Loading />}
      {!!imms.error && !surv.data && <Alert kind="error">{ohErrorText(lang, imms.error)}</Alert>}
      {(imms.data || surv.data) && <ImmunisationTable t={t} lang={lang} items={immunisations} />}
      <section className="card" aria-labelledby="vacc-cert">
        <h2 id="vacc-cert" className="card-title">
          {t.survCertTitle}
        </h2>
        <p className="muted">{t.survCertIntro}</p>
        <button type="button" disabled={busy} onClick={() => void run(async () => void (await requestVaccinationCertificate(client)), t.survCertOk, true)}>
          {busy ? t.sending : t.survCertRequest}
        </button>
      </section>
      {crew && (
        <PregnancyForm
          t={t}
          open={preg.open}
          declaredOn={preg.declaredOn}
          error={preg.error}
          busy={busy}
          done={preg.done}
          max={today()}
          onOpen={() => setPreg((p) => ({ ...p, open: true }))}
          onChange={(declaredOn) => setPreg((p) => ({ ...p, declaredOn, error: undefined }))}
          onSubmit={(e) => void declare(e)}
        />
      )}
    </PortalSection>
  );
}
