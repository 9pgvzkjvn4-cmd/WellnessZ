import { useState, type FormEvent } from 'react';
import {
  BEST_TIMES,
  CONCERNS,
  fetchCismOffers,
  fetchScreen,
  fetchSharing,
  fetchSignposting,
  INSTRUMENTS,
  MAX_ADJUSTMENTS,
  MAX_MESSAGE,
  respondToCism,
  saveSharing,
  screenComplete,
  SHARING_CHOICES,
  submitScreen,
  submitSelfReferral,
  SUPPORT_CONTACTS,
  validateSelfReferral,
  type CismOffer,
  type CismResponse,
  type Concern,
  type Emergency,
  type Instrument,
  type ScreenForm,
  type ScreenOutcome,
  type SelfReferralErrors,
  type SelfReferralInput,
  type SelfReferralReceipt,
  type SharingChoice,
  type SharingSetting,
  type SignpostService,
  type SupportContact,
} from '../api/support';
import { telHref } from '../api/portal';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { EmergencyBanner, Fact } from '../components/wellbeing';
import { labelOf } from '../i18n/portal';
import { wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

type Msg = { kind: 'error' | 'success'; text: string } | undefined;

/* ---------------------------------------------------------------- signposting */

export function SignpostList({ t, services }: { t: WellbeingStrings; services: readonly SignpostService[] }) {
  if (services.length === 0) return <p className="muted">{t.spEmpty}</p>;
  return (
    <ul className="card-list">
      {services.map((s) => (
        <li key={s.code} className="card">
          <div className="card-row">
            <strong>{s.title}</strong>
            {s.available24h && <StatusBadge status="active" label={t.sp24h} />}
          </div>
          {s.summary && <p>{s.summary}</p>}
          <div className="actions">
            {s.phone && (
              <a className="button secondary small-button" href={telHref(s.phone)}>
                <bdi>{t.spCall(s.phone)}</bdi>
              </a>
            )}
            {s.url && (
              <a className="button secondary small-button" href={s.url} target="_blank" rel="noopener noreferrer">
                {t.spWebsite} <span className="visually-hidden">{t.opensNewTab}</span>
              </a>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}

/* ---------------------------------------------------------------- self-referral */

export type SelfReferralDraft = { preferredContact: SupportContact; phone: string; bestTime: string; concern: Concern | ''; message: string };
const EMPTY_REFERRAL: SelfReferralDraft = { preferredContact: 'PHONE', phone: '', bestTime: '', concern: '', message: '' };

export interface SelfReferralViewProps {
  t: WellbeingStrings;
  lang: string;
  draft: SelfReferralDraft;
  errors: SelfReferralErrors;
  busy: boolean;
  receipt?: SelfReferralReceipt;
  onChange: (d: SelfReferralDraft) => void;
  onSubmit: () => void;
}

/** Confidential self-referral: preferred contact, best time, concern area, optional message – and who reads it. Hook-free. */
export function SelfReferralView(p: SelfReferralViewProps) {
  const { t, draft, errors } = p;
  if (p.receipt) {
    return (
      <Alert kind="success">
        <p>{p.receipt.caseRef ? t.srDone(p.receipt.caseRef) : t.srDoneNoRef}</p>
        <p>{p.receipt.nextContactBy ? t.srNextContact(formatDate(p.receipt.nextContactBy, p.lang)) : t.srNextContactSoon}</p>
      </Alert>
    );
  }
  const set = (patch: Partial<SelfReferralDraft>) => p.onChange({ ...draft, ...patch });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    p.onSubmit();
  };
  return (
    <form className="form" onSubmit={submit} noValidate aria-describedby="sr-who">
      <p id="sr-who" className="consent-box">
        {t.srWhoReads}
      </p>
      <fieldset>
        <legend>{t.srContact}</legend>
        <div className="inline-options">
          {SUPPORT_CONTACTS.map((c) => (
            <label key={c}>
              <input type="radio" name="sr-contact" value={c} checked={draft.preferredContact === c} onChange={() => set({ preferredContact: c })} /> {t.contacts[c]}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="field">
        <label htmlFor="sr-phone">
          {t.srPhone} {draft.preferredContact !== 'PHONE' && <span className="muted">{t.optional}</span>}
        </label>
        <input id="sr-phone" type="tel" autoComplete="tel" inputMode="tel" value={draft.phone} onChange={(e) => set({ phone: e.target.value })} aria-invalid={!!errors.phone} aria-describedby={errors.phone ? 'sr-phone-err' : undefined} />
        {errors.phone && (
          <p id="sr-phone-err" className="field-error">
            {errors.phone === 'required' ? t.errRequired : t.errPhone}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="sr-time">
          {t.srBestTime} <span className="muted">{t.optional}</span>
        </label>
        <select id="sr-time" value={draft.bestTime} onChange={(e) => set({ bestTime: e.target.value })}>
          <option value="">{t.noPreference}</option>
          {BEST_TIMES.map((b) => (
            <option key={b} value={b}>
              {t.bestTimes[b]}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="sr-concern">{t.srConcern}</label>
        <select id="sr-concern" value={draft.concern} onChange={(e) => set({ concern: e.target.value as Concern })} aria-invalid={!!errors.concern} aria-describedby={errors.concern ? 'sr-concern-err' : undefined}>
          <option value="">{t.select}</option>
          {CONCERNS.map((c) => (
            <option key={c} value={c}>
              {t.concerns[c]}
            </option>
          ))}
        </select>
        {errors.concern && (
          <p id="sr-concern-err" className="field-error">
            {t.errRequired}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="sr-message">
          {t.srMessage} <span className="muted">{t.optional}</span>
        </label>
        <textarea id="sr-message" rows={4} maxLength={MAX_MESSAGE} value={draft.message} onChange={(e) => set({ message: e.target.value })} aria-describedby="sr-message-hint" />
        <p id="sr-message-hint" className="muted small">
          {t.srMessageHint} · {t.charsLeft(MAX_MESSAGE - draft.message.length)}
        </p>
        {errors.message && <p className="field-error">{t.errTooLong(MAX_MESSAGE)}</p>}
      </div>
      <button type="submit" disabled={p.busy}>
        {p.busy ? t.sending : t.srSubmit}
      </button>
    </form>
  );
}

/* ---------------------------------------------------------------- screening */

/** Result band and next steps. Urgent → a clinician will call today + emergency guidance. No diagnostic wording. Hook-free. */
export function ScreenResult({ t, outcome, emergency, onBack }: { t: WellbeingStrings; outcome: ScreenOutcome; emergency?: Emergency; onBack: () => void }) {
  return (
    <div className="card screen-result" aria-labelledby="sc-result-title">
      <h3 id="sc-result-title" className="card-title">
        {t.scResultTitle}
      </h3>
      {outcome.urgent && (
        <div className="alert alert-urgent" role="alert">
          <p>
            <strong>{t.scUrgentTitle}</strong>
          </p>
          <p>{t.scUrgentBody}</p>
          <p>{t.scUrgentDanger}</p>
          {emergency?.phone ? (
            <a className="button emergency-call" href={telHref(emergency.phone)}>
              {t.emCall(emergency.phone)}
            </a>
          ) : (
            <p>{t.emLocal}</p>
          )}
        </div>
      )}
      {outcome.band && (
        <p>
          <StatusBadge status={`band-${outcome.band}`} label={labelOf(t.bands, outcome.band)} />
          {outcome.score !== undefined && outcome.maxScore !== undefined && <span className="muted small"> · {t.scScore(outcome.score, outcome.maxScore)}</span>}
        </p>
      )}
      {outcome.band && !outcome.urgent && <p>{t.bandHints[outcome.band]}</p>}
      {outcome.nextSteps.length > 0 && (
        <>
          <h4>{t.scNextSteps}</h4>
          <ul className="next-steps">
            {outcome.nextSteps.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ul>
        </>
      )}
      <p className="muted small">{t.scNotDiagnosis}</p>
      <button type="button" className="secondary" onClick={onBack}>
        {t.scAgain}
      </button>
    </div>
  );
}

export interface QuestionnaireProps {
  t: WellbeingStrings;
  form: ScreenForm;
  answers: readonly (number | undefined)[];
  busy: boolean;
  showIncomplete: boolean;
  onAnswer: (index: number, value: number) => void;
  onSubmit: () => void;
  onBack: () => void;
}

/** One questionnaire, all questions on one page with radio options. Hook-free. */
export function Questionnaire(p: QuestionnaireProps) {
  const { t, form } = p;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    p.onSubmit();
  };
  return (
    <form className="form" onSubmit={submit} aria-label={form.title ?? t.instruments[form.instrument]}>
      <h3>{form.title ?? t.instruments[form.instrument]}</h3>
      <p>{t.scLead}</p>
      {form.questions.map((q, i) => (
        <fieldset key={q.code} className="step-card">
          <legend>
            <span className="muted small">{t.scQuestion(i + 1, form.questions.length)}</span> {q.text}
          </legend>
          <div className="inline-options">
            {form.options.map((o) => (
              <label key={o.value}>
                <input type="radio" name={`q-${q.code}`} value={o.value} checked={p.answers[i] === o.value} onChange={() => p.onAnswer(i, o.value)} /> {o.label}
              </label>
            ))}
          </div>
        </fieldset>
      ))}
      {p.showIncomplete && !screenComplete(form, p.answers) && <p className="field-error">{t.scIncomplete}</p>}
      <div className="actions">
        <button type="submit" disabled={p.busy}>
          {p.busy ? t.sending : t.scSubmit}
        </button>
        <button type="button" className="secondary" disabled={p.busy} onClick={p.onBack}>
          {t.back}
        </button>
      </div>
    </form>
  );
}

/* ---------------------------------------------------------------- sharing */

export interface SharingCardProps {
  t: WellbeingStrings;
  lang: string;
  setting: SharingSetting;
  choice: SharingChoice;
  adjustments: string;
  busy: boolean;
  onChoice: (c: SharingChoice) => void;
  onAdjustments: (v: string) => void;
  onSave: () => void;
  onRevoke: () => void;
}

/** Sharing choice per support case with an exact explanation of what the manager sees; revocable. Hook-free. */
export function SharingCard(p: SharingCardProps) {
  const { t, setting } = p;
  const id = `share-${setting.caseId}`;
  const changed = p.choice !== setting.choice || (p.choice === 'FITNESS_AND_ADJUSTMENTS' && p.adjustments.trim() !== (setting.adjustments ?? ''));
  return (
    <article className="card" aria-labelledby={id}>
      <div className="card-row">
        <h3 id={id} className="card-title">
          {setting.caseRef ? t.shCase(setting.caseRef) : t.shCaseNoRef}
        </h3>
        <StatusBadge status={`share-${setting.choice}`} label={`${t.shCurrent}: ${t.choices[setting.choice]}`} />
      </div>
      {setting.updatedAt && <p className="muted small">{t.shUpdated(formatDate(setting.updatedAt, p.lang))}</p>}
      <fieldset>
        <legend className="visually-hidden">{t.shTitle}</legend>
        {SHARING_CHOICES.map((c) => (
          <label key={c} className="choice">
            <input type="radio" name={`${id}-choice`} value={c} checked={p.choice === c} onChange={() => p.onChoice(c)} />{' '}
            <strong>{t.choices[c]}</strong>
            <span className="muted small"> – {t.choiceExplain[c]}</span>
          </label>
        ))}
      </fieldset>
      {p.choice === 'FITNESS_AND_ADJUSTMENTS' && (
        <div className="field">
          <label htmlFor={`${id}-adj`}>{t.shAdjustments}</label>
          <textarea id={`${id}-adj`} rows={3} maxLength={MAX_ADJUSTMENTS} value={p.adjustments} onChange={(e) => p.onAdjustments(e.target.value)} aria-describedby={`${id}-adj-hint`} />
          <p id={`${id}-adj-hint`} className="muted small">
            {t.shAdjustmentsHint}
          </p>
        </div>
      )}
      <div className="actions">
        <button type="button" disabled={p.busy || !changed} onClick={p.onSave}>
          {p.busy ? t.sending : t.shSave}
        </button>
        {setting.choice !== 'NOTHING' && (
          <button type="button" className="secondary" disabled={p.busy} onClick={p.onRevoke}>
            {t.shRevoke}
          </button>
        )}
      </div>
    </article>
  );
}

/* ---------------------------------------------------------------- CISM */

export interface CismOfferCardProps {
  t: WellbeingStrings;
  lang: string;
  offer: CismOffer;
  contact: SupportContact;
  busy: boolean;
  onContact: (c: SupportContact) => void;
  onRespond: (offer: CismOffer, response: CismResponse) => void;
}

export function CismOfferCard(p: CismOfferCardProps) {
  const { t, offer } = p;
  const id = `cism-${offer.id}`;
  return (
    <article className="card" aria-labelledby={id}>
      <h3 id={id} className="card-title">
        {offer.title ?? t.cmFallback}
      </h3>
      <dl className="case-facts">
        <Fact label={t.cmOccurred}>{offer.occurredAt && formatDay(offer.occurredAt, p.lang)}</Fact>
        {!offer.response && <Fact label={t.cmRespondBy}>{offer.respondBy && formatDate(offer.respondBy, p.lang)}</Fact>}
      </dl>
      {offer.response === 'ACCEPTED' && <p className="muted">{t.cmAccepted}</p>}
      {offer.response === 'DECLINED' && <p className="muted">{t.cmDeclined}</p>}
      {!offer.response && (
        <>
          <div className="field">
            <label htmlFor={`${id}-contact`}>{t.cmContact}</label>
            <select id={`${id}-contact`} value={p.contact} onChange={(e) => p.onContact(e.target.value as SupportContact)}>
              {SUPPORT_CONTACTS.map((c) => (
                <option key={c} value={c}>
                  {t.contacts[c]}
                </option>
              ))}
            </select>
          </div>
          <div className="actions">
            <button type="button" disabled={p.busy} onClick={() => p.onRespond(offer, 'ACCEPTED')}>
              {t.cmAccept}
            </button>
            <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onRespond(offer, 'DECLINED')}>
              {t.cmDecline}
            </button>
          </div>
        </>
      )}
    </article>
  );
}

/* ---------------------------------------------------------------- screen */

/** `#/support` – emergency guidance, signposting, self-referral, screening, sharing choice, CISM offers (E09 §3). */
export function Support({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const signposting = useAsync(() => fetchSignposting(client), onSessionExpired);
  const sharing = useAsync(() => fetchSharing(client), onSessionExpired);
  const offers = useAsync(() => fetchCismOffers(client), onSessionExpired);

  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Msg>();
  // self-referral
  const [draft, setDraft] = useState<SelfReferralDraft>(EMPTY_REFERRAL);
  const [errors, setErrors] = useState<SelfReferralErrors>({});
  const [receipt, setReceipt] = useState<SelfReferralReceipt>();
  // screening
  const [form, setForm] = useState<ScreenForm>();
  const [answers, setAnswers] = useState<(number | undefined)[]>([]);
  const [incomplete, setIncomplete] = useState(false);
  const [outcome, setOutcome] = useState<ScreenOutcome>();
  // sharing drafts and CISM contact choice per id
  const [choices, setChoices] = useState<Record<string, SharingChoice>>({});
  const [adjustments, setAdjustments] = useState<Record<string, string>>({});
  const [contacts, setContacts] = useState<Record<string, SupportContact>>({});

  async function run(fn: () => Promise<void>, ok?: string) {
    setBusy(true);
    setMessage(undefined);
    try {
      await fn();
      if (ok) setMessage({ kind: 'success', text: ok });
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: wellbeingErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const submitReferral = () => {
    const input: Partial<SelfReferralInput> = { ...draft, concern: draft.concern || undefined };
    const errs = validateSelfReferral(input);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;
    void run(async () => setReceipt(await submitSelfReferral(client, input as SelfReferralInput)));
  };

  const startScreen = (instrument: Instrument) =>
    void run(async () => {
      const f = await fetchScreen(client, instrument);
      setForm(f);
      setAnswers(f ? f.questions.map(() => undefined) : []);
      setIncomplete(false);
      setOutcome(undefined);
    });

  const sendScreen = () => {
    if (!form) return;
    if (!screenComplete(form, answers)) {
      setIncomplete(true);
      return;
    }
    void run(async () => {
      setOutcome(await submitScreen(client, form, answers as number[]));
      setForm(undefined);
    });
  };

  const emergency = signposting.data?.emergency;
  const settings = sharing.data ?? [];
  return (
    <PortalSection lang={lang} className="support">
      <PageTitle>{t.supTitle}</PageTitle>
      <EmergencyBanner t={t} emergency={emergency} />
      <p className="lead">{t.supIntro}</p>
      <p className="muted small">{t.supConfidential}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>

      <h2>{t.spTitle}</h2>
      {signposting.loading && <Loading />}
      {!!signposting.error && <Alert kind="error">{wellbeingErrorText(lang, signposting.error)}</Alert>}
      {signposting.data && <SignpostList t={t} services={signposting.data.services} />}

      <h2 id="sr-title">{t.srTitle}</h2>
      <p>{t.srIntro}</p>
      <SelfReferralView t={t} lang={lang} draft={draft} errors={errors} busy={busy} receipt={receipt} onChange={setDraft} onSubmit={submitReferral} />

      <h2>{t.scTitle}</h2>
      <p>{t.scIntro}</p>
      <p className="muted small">{t.scWhoSees}</p>
      {outcome ? (
        <ScreenResult t={t} outcome={outcome} emergency={emergency} onBack={() => setOutcome(undefined)} />
      ) : form ? (
        <Questionnaire
          t={t}
          form={form}
          answers={answers}
          busy={busy}
          showIncomplete={incomplete}
          onAnswer={(i, v) => setAnswers((a) => a.map((x, n) => (n === i ? v : x)))}
          onSubmit={sendScreen}
          onBack={() => setForm(undefined)}
        />
      ) : (
        <div className="actions">
          {INSTRUMENTS.map((i) => (
            <button key={i} type="button" className="secondary" disabled={busy} onClick={() => startScreen(i)}>
              {t.scStart}: {t.instruments[i]}
            </button>
          ))}
        </div>
      )}

      <h2>{t.shTitle}</h2>
      <p>{t.shIntro}</p>
      <p className="muted small">{t.shNever}</p>
      {sharing.loading && <Loading />}
      {!!sharing.error && <Alert kind="error">{wellbeingErrorText(lang, sharing.error)}</Alert>}
      {sharing.data && settings.length === 0 && <p className="muted">{t.shEmpty}</p>}
      {settings.map((s) => (
        <SharingCard
          key={s.caseId}
          t={t}
          lang={lang}
          setting={s}
          choice={choices[s.caseId] ?? s.choice}
          adjustments={adjustments[s.caseId] ?? s.adjustments ?? ''}
          busy={busy}
          onChoice={(c) => setChoices((m) => ({ ...m, [s.caseId]: c }))}
          onAdjustments={(v) => setAdjustments((m) => ({ ...m, [s.caseId]: v }))}
          onSave={() => {
            const c = choices[s.caseId] ?? s.choice;
            void run(async () => {
              await saveSharing(client, s.caseId, c, adjustments[s.caseId] ?? s.adjustments);
              sharing.reload();
            }, c === 'NOTHING' ? t.shRevoked : t.shSaved);
          }}
          onRevoke={() =>
            void run(async () => {
              await saveSharing(client, s.caseId, 'NOTHING');
              setChoices((m) => ({ ...m, [s.caseId]: 'NOTHING' }));
              sharing.reload();
            }, t.shRevoked)
          }
        />
      ))}

      <h2>{t.cmTitle}</h2>
      <p>{t.cmIntro}</p>
      {offers.loading && <Loading />}
      {!!offers.error && <Alert kind="error">{wellbeingErrorText(lang, offers.error)}</Alert>}
      {offers.data && offers.data.length === 0 && <EmptyState title={t.cmEmpty} hint={t.cmIntro} />}
      {offers.data?.map((o) => (
        <CismOfferCard
          key={o.id}
          t={t}
          lang={lang}
          offer={o}
          contact={contacts[o.id] ?? 'PHONE'}
          busy={busy}
          onContact={(c) => setContacts((m) => ({ ...m, [o.id]: c }))}
          onRespond={(offer, response) =>
            void run(async () => {
              await respondToCism(client, offer.id, response, contacts[offer.id] ?? 'PHONE');
              offers.reload();
            }, response === 'ACCEPTED' ? t.cmAcceptOk : t.cmDeclineOk)
          }
        />
      ))}
    </PortalSection>
  );
}
