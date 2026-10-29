import { useState, type FormEvent, type ReactNode } from 'react';
import {
  bookPreEmploymentExam,
  cancelAppointment,
  fetchMyPreEmployment,
  fetchQuestionnaire,
  fetchSites,
  givePreEmploymentConsent,
  PRE_EMPLOYMENT_PURPOSES,
  submitQuestionnaire,
  UPLOAD_ACCEPT,
  uploadPreEmploymentDocument,
  type ClinicSite,
  type Question,
  type StepId,
  preEmploymentSteps,
} from '../api/occupational';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { labelOf } from '../i18n/portal';
import { ohErrorText, ohStrings, type OhStrings } from '../i18n/occupational';
import { formatBytes, type FileLike } from '../intake/attachments';
import { hasErrors, today, validateDocument, validateQuestionnaire, type FormErrors } from '../intake/occupationalForms';
import { AppointmentCard } from './MyAppointments';
import type { PortalScreenProps } from './types';

export function Step({ t, id, title, done, children }: { t: OhStrings; id: StepId; title: string; done: boolean; children: ReactNode }) {
  return (
    <li className={`card step-card ${done ? 'step-done' : 'step-todo'}`}>
      <section aria-labelledby={`pre-${id}`}>
        <div className="card-row">
          <h2 id={`pre-${id}`} className="card-title">
            {title}
          </h2>
          <StatusBadge status={done ? 'step-done' : 'step-todo'} label={done ? t.preDone : t.preTodo} />
        </div>
        {children}
      </section>
    </li>
  );
}

export interface QuestionnaireFormProps {
  t: OhStrings;
  questions: readonly Question[];
  answers: Readonly<Record<string, string>>;
  errors: FormErrors;
  busy: boolean;
  onAnswer: (code: string, value: string) => void;
  onSubmit: (e: FormEvent) => void;
}

/** YES_NO → radio pair, DATE → date input, TEXT → textarea. Hook-free. */
export function QuestionnaireForm({ t, questions, answers, errors, busy, onAnswer, onSubmit }: QuestionnaireFormProps) {
  if (questions.length === 0) return <p className="muted">{t.preQEmpty}</p>;
  return (
    <form className="form" onSubmit={onSubmit} noValidate>
      <p className="muted small">{t.preQIntro}</p>
      {questions.map((q) => {
        const id = `q-${q.code}`;
        const e = errors[q.code];
        const errId = e ? `${id}-err` : undefined;
        const label = (
          <>
            {q.text}
            {q.required ? <span className="req" aria-hidden="true"> *</span> : <span className="muted"> {t.optional}</span>}
          </>
        );
        return (
          <div key={q.code} className="field">
            {q.type === 'YES_NO' ? (
              <fieldset aria-describedby={errId} aria-required={q.required || undefined}>
                <legend>{label}</legend>
                <div className="inline-options">
                  {(['YES', 'NO'] as const).map((v) => (
                    <label key={v} className="chip">
                      <input type="radio" name={id} value={v} checked={answers[q.code] === v} disabled={busy} onChange={() => onAnswer(q.code, v)} /> {v === 'YES' ? t.yes : t.no}
                    </label>
                  ))}
                </div>
              </fieldset>
            ) : (
              <>
                <label htmlFor={id}>{label}</label>
                {q.type === 'DATE' ? (
                  <input id={id} type="date" max={today()} value={answers[q.code] ?? ''} disabled={busy} aria-invalid={!!e} aria-describedby={errId} required={q.required} onChange={(ev) => onAnswer(q.code, ev.target.value)} />
                ) : (
                  <textarea id={id} rows={2} maxLength={2000} value={answers[q.code] ?? ''} disabled={busy} aria-invalid={!!e} aria-describedby={errId} required={q.required} onChange={(ev) => onAnswer(q.code, ev.target.value)} />
                )}
              </>
            )}
            {e && (
              <p id={errId} className="field-error">
                {t.fieldErrors[e]}
              </p>
            )}
          </div>
        );
      })}
      <div className="actions">
        <button type="submit" disabled={busy}>
          {busy ? t.sending : t.preQSubmit}
        </button>
      </div>
    </form>
  );
}

export function ConsentForm({ t, checked, busy, onCheck, onSubmit }: { t: OhStrings; checked: boolean; busy: boolean; onCheck: (v: boolean) => void; onSubmit: (e: FormEvent) => void }) {
  return (
    <form className="form" onSubmit={onSubmit}>
      <p>{t.preConsentIntro}</p>
      <ul>
        {PRE_EMPLOYMENT_PURPOSES.map((p) => (
          <li key={p}>{t.prePurposes[p]}</li>
        ))}
      </ul>
      <label className="chip">
        <input type="checkbox" checked={checked} disabled={busy} onChange={(e) => onCheck(e.target.checked)} /> {t.preConsentCheckbox}
      </label>
      <div className="actions">
        <button type="submit" disabled={busy || !checked}>
          {busy ? t.sending : t.preConsentSubmit}
        </button>
      </div>
    </form>
  );
}

export function DocumentForm({ t, file, title, errors, busy, onFile, onTitle, onSubmit }: { t: OhStrings; file?: FileLike; title: string; errors: FormErrors<'file' | 'title'>; busy: boolean; onFile: (f: File | undefined) => void; onTitle: (v: string) => void; onSubmit: (e: FormEvent) => void }) {
  return (
    <form className="form" onSubmit={onSubmit} noValidate>
      <p className="muted small">{t.preDocsIntro}</p>
      <div className="field">
        <label htmlFor="pre-doc-title">{t.preDocTitle}</label>
        <input id="pre-doc-title" value={title} maxLength={200} disabled={busy} aria-invalid={!!errors.title} aria-describedby={errors.title ? 'pre-doc-title-err' : undefined} onChange={(e) => onTitle(e.target.value)} />
        {errors.title && (
          <p id="pre-doc-title-err" className="field-error">
            {t.fieldErrors[errors.title]}
          </p>
        )}
      </div>
      <div className="actions">
        <label className="button secondary file-button">
          {t.absTakePhoto}
          <input type="file" className="visually-hidden" accept="image/*" capture="environment" disabled={busy} onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
        <label className="button secondary file-button">
          {t.absChooseFile}
          <input type="file" className="visually-hidden" accept={UPLOAD_ACCEPT} disabled={busy} onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
      </div>
      <p className="muted small">{t.absFileHint}</p>
      {file && (
        <p className="small">
          <span dir="auto">{file.name}</span> <span className="muted">({formatBytes(file.size)})</span>
        </p>
      )}
      {errors.file && <p className="field-error">{t.fieldErrors[errors.file]}</p>}
      <div className="actions">
        <button type="submit" disabled={busy}>
          {busy ? t.sending : t.preDocUpload}
        </button>
      </div>
    </form>
  );
}

export function BookExamForm({ t, enabled, sites, site, earliest, busy, onSite, onEarliest, onSubmit }: { t: OhStrings; enabled: boolean; sites: readonly ClinicSite[]; site: string; earliest: string; busy: boolean; onSite: (v: string) => void; onEarliest: (v: string) => void; onSubmit: (e: FormEvent) => void }) {
  if (!enabled) return <p className="muted">{t.preBookNeeds}</p>;
  return (
    <form className="form" onSubmit={onSubmit}>
      <p className="muted small">{t.preBookIntro}</p>
      <div className="field">
        <label htmlFor="pre-site">{t.preSite}</label>
        <select id="pre-site" value={site} disabled={busy} onChange={(e) => onSite(e.target.value)}>
          <option value="">{t.preSiteAny}</option>
          {sites.map((s) => (
            <option key={s.code} value={s.code}>
              {s.name}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="pre-earliest">
          {t.preEarliest} <span className="muted">{t.optional}</span>
        </label>
        <input id="pre-earliest" type="date" min={today()} value={earliest} disabled={busy} onChange={(e) => onEarliest(e.target.value)} />
      </div>
      <div className="actions">
        <button type="submit" disabled={busy}>
          {busy ? t.sending : t.preBookSubmit}
        </button>
      </div>
    </form>
  );
}

/**
 * `#/pre-employment` (CANDIDATE, E07-F03): offer summary, questionnaire, consent, document upload and exam booking.
 * Candidates without employee features see only this section.
 */
export function PreEmployment({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = ohStrings(lang);
  const rec = useAsync(() => fetchMyPreEmployment(client), onSessionExpired);
  const questions = useAsync(() => fetchQuestionnaire(client), onSessionExpired);
  const sites = useAsync(() => fetchSites(client).catch(() => [] as ClinicSite[]), onSessionExpired);
  // Answers live only in component memory until they are sent; they are cleared afterwards.
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [qErrors, setQErrors] = useState<FormErrors>({});
  const [consent, setConsent] = useState(false);
  const [doc, setDoc] = useState<{ file?: File; title: string; errors: FormErrors<'file' | 'title'> }>({ title: '', errors: {} });
  const [book, setBook] = useState({ site: '', earliest: '' });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [now] = useState(() => new Date());

  async function run(fn: () => Promise<string>) {
    setBusy(true);
    setMessage(undefined);
    try {
      setMessage({ kind: 'success', text: await fn() });
      rec.reload();
      return true;
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: ohErrorText(lang, e) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  const r = rec.data;
  const steps = r ? preEmploymentSteps(r) : undefined;
  const doneCount = steps ? Object.values(steps).filter(Boolean).length : 0;

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.preTitle}</PageTitle>
      <p className="lead">{t.preIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {rec.loading && <Loading />}
      {!!rec.error && <Alert kind="error">{ohErrorText(lang, rec.error)}</Alert>}
      {!rec.loading && !rec.error && !r && <EmptyState title={t.preNone} hint={t.preNoneHint} />}
      {r && steps && (
        <>
          <section className="card" aria-label={t.preTitle}>
            <dl className="case-facts">
              {r.jobTitle && (
                <>
                  <dt>{t.preJob}</dt>
                  <dd>{r.jobTitle}</dd>
                </>
              )}
              {r.offerRef && (
                <>
                  <dt>{t.preOffer}</dt>
                  <dd>
                    <bdi className="mono">{r.offerRef}</bdi>
                  </dd>
                </>
              )}
              {r.proposedStart && (
                <>
                  <dt>{t.preProposedStart}</dt>
                  <dd>{formatDay(r.proposedStart, lang)}</dd>
                </>
              )}
              <dt>{t.preStatusLabel}</dt>
              <dd>
                <StatusBadge status={`pre-${r.status}`} label={labelOf(t.preStatus, r.status)} />
              </dd>
            </dl>
            <p className="muted small">
              {t.preProgress(doneCount, 4)} · {t.preWhatHrSees}
            </p>
          </section>
          <ol className="steps">
            <Step t={t} id="questionnaire" title={t.preStepQuestionnaire} done={steps.questionnaire}>
              {steps.questionnaire ? (
                <p>{t.preQDone}</p>
              ) : questions.loading ? (
                <Loading />
              ) : questions.error ? (
                <Alert kind="error">{ohErrorText(lang, questions.error)}</Alert>
              ) : (
                <QuestionnaireForm
                  t={t}
                  questions={questions.data ?? []}
                  answers={answers}
                  errors={qErrors}
                  busy={busy}
                  onAnswer={(code, v) => {
                    setAnswers((a) => ({ ...a, [code]: v }));
                    setQErrors((e) => ({ ...e, [code]: undefined }));
                  }}
                  onSubmit={(e) => {
                    e.preventDefault();
                    const v = validateQuestionnaire(questions.data ?? [], answers);
                    setQErrors(v);
                    if (hasErrors(v)) return;
                    void run(async () => {
                      await submitQuestionnaire(client, answers);
                      setAnswers({});
                      return t.preQOk;
                    });
                  }}
                />
              )}
            </Step>
            <Step t={t} id="consent" title={t.preStepConsent} done={steps.consent}>
              {steps.consent ? (
                <p>{t.preConsentDone}</p>
              ) : (
                <ConsentForm
                  t={t}
                  checked={consent}
                  busy={busy}
                  onCheck={setConsent}
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (!consent) return;
                    void run(async () => {
                      await givePreEmploymentConsent(client);
                      return t.preConsentOk;
                    });
                  }}
                />
              )}
            </Step>
            <Step t={t} id="documents" title={t.preStepDocuments} done={steps.documents}>
              {r.documents.length > 0 && (
                <>
                  <h3 className="sub-title">{t.preDocsList}</h3>
                  <ul>
                    {r.documents.map((d) => (
                      <li key={d.id} dir="auto">
                        {d.title || d.id}
                      </li>
                    ))}
                  </ul>
                </>
              )}
              <DocumentForm
                t={t}
                file={doc.file}
                title={doc.title}
                errors={doc.errors}
                busy={busy}
                onFile={(file) => setDoc((d) => ({ ...d, file, errors: { ...d.errors, file: undefined } }))}
                onTitle={(title) => setDoc((d) => ({ ...d, title, errors: { ...d.errors, title: undefined } }))}
                onSubmit={(e) => {
                  e.preventDefault();
                  const v = validateDocument(doc.file, doc.title);
                  setDoc((d) => ({ ...d, errors: v }));
                  const file = doc.file;
                  if (hasErrors(v) || !file) return;
                  void run(async () => {
                    await uploadPreEmploymentDocument(client, file, file.name || 'document', doc.title);
                    setDoc({ title: '', errors: {} });
                    return t.preDocOk;
                  });
                }}
              />
            </Step>
            <Step t={t} id="book" title={t.preStepBook} done={steps.book}>
              {r.appointment && r.appointment.status !== 'CANCELLED' ? (
                <>
                  <h3 className="sub-title">{t.preAppointment}</h3>
                  <AppointmentCard
                    t={t}
                    lang={lang}
                    a={r.appointment}
                    now={now}
                    busy={busy}
                    cancelling={cancelling}
                    cancelReason={reason}
                    canReschedule={false}
                    onCheckIn={() => undefined}
                    onJoin={() => undefined}
                    onReschedule={() => undefined}
                    onCancel={() => {
                      setReason('');
                      setCancelling(true);
                    }}
                    onCancelReason={setReason}
                    onConfirmCancel={() => {
                      const id = r.appointment?.id;
                      if (!id) return;
                      void run(async () => {
                        await cancelAppointment(client, id, reason);
                        setCancelling(false);
                        return t.apptCancelledOk;
                      });
                    }}
                    onAbortCancel={() => setCancelling(false)}
                  />
                </>
              ) : (
                <BookExamForm
                  t={t}
                  enabled={steps.questionnaire && steps.consent}
                  sites={sites.data ?? []}
                  site={book.site}
                  earliest={book.earliest}
                  busy={busy}
                  onSite={(site) => setBook((b) => ({ ...b, site }))}
                  onEarliest={(earliest) => setBook((b) => ({ ...b, earliest }))}
                  onSubmit={(e) => {
                    e.preventDefault();
                    void run(async () => {
                      const a = await bookPreEmploymentExam(client, book.site || undefined, book.earliest || undefined);
                      return a ? t.preBookedOk(formatDate(a.startsAt, lang)) : t.preBookedOk('');
                    });
                  }}
                />
              )}
            </Step>
          </ol>
        </>
      )}
    </PortalSection>
  );
}
