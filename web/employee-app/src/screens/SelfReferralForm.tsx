import type { FormEvent } from 'react';
import {
  SELF_GRANTABLE_PURPOSES,
  CONTACT_OPTIONS,
  REFERRAL_CATEGORIES,
  type BodyMapRegion,
  type Language,
  type PreferredContact,
  type Template,
  type TemplateField as Field,
} from '../api/types';
import { BodyMap } from '../components/BodyMap';
import { TemplateField } from '../components/TemplateField';
import { ACCEPT, formatBytes, MAX_FILES, type FileLike } from '../intake/attachments';
import {
  fieldId,
  FORM_IDS,
  FORM_KEYS,
  isStructural,
  MAX_DESCRIPTION,
  referralProgress,
  type ErrorMap,
  type ReferralAction,
  type ReferralState,
} from '../intake/referralForm';
import { dirFor, type ReferralStrings } from '../i18n/strings';

export interface SelfReferralFormProps<F extends FileLike> {
  state: ReferralState<F>;
  dispatch: (a: ReferralAction<F>) => void;
  t: ReferralStrings;
  languages: readonly Language[];
  templates: readonly Template[];
  template?: Template;
  suggestedTemplateCode?: string;
  regions: readonly BodyMapRegion[];
  errors: ErrorMap;
  /** Shown above the form after a failed submit. */
  submitError?: string;
  submitting: boolean;
  /** Native app: offer the camera (`<input capture>`). */
  isNative: boolean;
  onLanguage: (code: string) => void;
  onTemplate: (code: string) => void;
  onFiles: (files: F[]) => void;
  onSubmit: (e: FormEvent) => void;
}

/** Ordered list of `{id, label, message}` for the error summary. */
function summarise(errors: ErrorMap, t: ReferralStrings, template: Template | undefined, bodyKey: string, symptomKey: string, labels: Record<string, string>) {
  const order = [
    FORM_KEYS.template,
    FORM_KEYS.category,
    ...(template?.fields ?? []).map((f) => f.key),
    bodyKey,
    symptomKey,
    FORM_KEYS.description,
    FORM_KEYS.consent,
  ];
  return [...new Set(order)]
    .filter((k) => errors[k])
    .map((k) => ({
      id: FORM_IDS[k] ?? (k === bodyKey ? FORM_IDS[FORM_KEYS.bodyParts]! : k === symptomKey ? FORM_IDS[FORM_KEYS.symptoms]! : fieldId(k)),
      label: labels[k] ?? (template?.fields.find((f) => f.key === k)?.label || t.required),
      message: errors[k]!,
    }));
}

function focusById(id: string) {
  const el = document.getElementById(id);
  if (!el) return;
  el.scrollIntoView?.({ block: 'center' });
  const focusable = el.matches('input,select,textarea,button') ? el : el.querySelector<HTMLElement>('input,select,textarea,button');
  (focusable ?? el).focus();
}

/** One scrolling page (E01-BL-006: completable in < 3 minutes). Hook-free; state lives in the SelfReferral screen. */
export function SelfReferralForm<F extends FileLike>(p: SelfReferralFormProps<F>) {
  const { state: s, dispatch, t, template, errors } = p;
  const dir = dirFor(s.language);
  const bodyField: Field | undefined = template?.fields.find((f) => f.type === 'BODY_MAP');
  const symptomField: Field | undefined = template?.fields.find((f) => f.type === 'SYMPTOMS');
  const bodyKey = bodyField?.key ?? FORM_KEYS.bodyParts;
  const symptomKey = symptomField?.key ?? FORM_KEYS.symptoms;
  const symptomOptions = template?.symptomOptions ?? [];
  const otherFields = (template?.fields ?? []).filter((f) => !isStructural(f));
  const progress = referralProgress(s, template);
  const summary = summarise(errors, t, template, bodyKey, symptomKey, {
    [FORM_KEYS.template]: t.template,
    [FORM_KEYS.category]: t.category,
    [bodyKey]: bodyField?.label || t.bodyMap,
    [symptomKey]: symptomField?.label || t.symptoms,
    [FORM_KEYS.description]: t.description,
    [FORM_KEYS.consent]: t.consentLegend,
  });
  const err = (key: string) => errors[key];
  const errP = (key: string, id: string) =>
    err(key) ? (
      <p id={`${id}-error`} className="field-error">
        {err(key)}
      </p>
    ) : null;

  return (
    <div className="referral" dir={dir} lang={s.language} data-testid="referral-root">
      <h1 tabIndex={-1} id="rf-title">
        {t.title}
      </h1>
      <p className="lead">{t.intro}</p>
      <div className="alert alert-info" role="note">
        {t.urgent}
      </div>

      <div className="field lang-switch">
        <label htmlFor="rf-language">{t.language}</label>
        <select id="rf-language" value={s.language} onChange={(e) => p.onLanguage(e.target.value)}>
          {p.languages.map((l) => (
            <option key={l.code} value={l.code} lang={l.code}>
              {l.label}
            </option>
          ))}
        </select>
      </div>

      <div className="progress-hint">
        <progress max={progress.total} value={progress.done} aria-labelledby="rf-progress" />
        <span id="rf-progress" className="small muted">
          {t.progress(progress.done, progress.total)}
        </span>
      </div>

      {summary.length > 0 && (
        <div className="error-summary" role="alert" tabIndex={-1} id="rf-error-summary">
          <p>
            <strong>{t.errorSummary}</strong>
          </p>
          <ul>
            {summary.map((x) => (
              <li key={x.id}>
                <a
                  href={`#${x.id}`}
                  onClick={(e) => {
                    e.preventDefault(); // hash routing: never change location.hash
                    focusById(x.id);
                  }}
                >
                  {x.label}: {x.message}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
      {p.submitError && (
        <div className="alert alert-error" role="alert">
          {p.submitError}
        </div>
      )}

      <form className="form" noValidate onSubmit={p.onSubmit}>
        <section className="step" aria-labelledby="rf-step1">
          <h2 id="rf-step1">{t.stepAbout}</h2>
          <div className="field">
            <label htmlFor="rf-template">
              {t.template}
              <span className="req" aria-hidden="true"> *</span>
            </label>
            <p id="rf-template-hint" className="muted small">
              {t.templateHint}
            </p>
            {errP(FORM_KEYS.template, 'rf-template')}
            <select
              id="rf-template"
              value={s.templateCode}
              aria-invalid={err(FORM_KEYS.template) ? true : undefined}
              aria-describedby={err(FORM_KEYS.template) ? 'rf-template-hint rf-template-error' : 'rf-template-hint'}
              onChange={(e) => p.onTemplate(e.target.value)}
            >
              <option value="">{t.chooseTemplate}</option>
              {p.templates.map((tp) => (
                <option key={tp.code} value={tp.code}>
                  {tp.title}
                  {tp.code === p.suggestedTemplateCode ? ` – ${t.templateSuggested}` : ''}
                </option>
              ))}
            </select>
          </div>

          <fieldset
            className="field"
            id="rf-category"
            aria-invalid={err(FORM_KEYS.category) ? true : undefined}
            aria-describedby={err(FORM_KEYS.category) ? 'rf-category-error' : undefined}
          >
            <legend>
              {t.category}
              <span className="req" aria-hidden="true"> *</span>
            </legend>
            {errP(FORM_KEYS.category, 'rf-category')}
            <div className="chips">
              {REFERRAL_CATEGORIES.map((c) => (
                <label key={c} className="chip">
                  <input type="radio" name="category" value={c} checked={s.category === c} onChange={() => dispatch({ type: 'category', value: c })} />
                  {t.categories[c]}
                </label>
              ))}
            </div>
          </fieldset>
        </section>

        <section className="step" aria-labelledby="rf-step2">
          <h2 id="rf-step2">{t.stepWhere}</h2>
          {p.regions.length > 0 && (
            <BodyMap
              id={FORM_IDS[FORM_KEYS.bodyParts]!}
              regions={p.regions}
              selected={s.bodyParts}
              side={s.bodySide}
              onSideChange={(side) => dispatch({ type: 'side', side })}
              onToggle={(code) => dispatch({ type: 'toggleBodyPart', code })}
              labels={{ ...t, bodyMap: bodyField?.label || t.bodyMap }}
              required={!!bodyField?.required}
              error={err(bodyKey)}
            />
          )}
          {symptomOptions.length > 0 && (
            <fieldset
              className="field"
              id={FORM_IDS[FORM_KEYS.symptoms]}
              aria-invalid={err(symptomKey) ? true : undefined}
              aria-describedby={err(symptomKey) ? 'rf-symptoms-error' : undefined}
            >
              <legend>
                {symptomField?.label || t.symptoms}
                {symptomField?.required && <span className="req" aria-hidden="true"> *</span>}
              </legend>
              {errP(symptomKey, 'rf-symptoms')}
              <div className="chips">
                {symptomOptions.map((o) => (
                  <label key={o.value} className="chip">
                    <input
                      type="checkbox"
                      value={o.value}
                      checked={s.symptoms.includes(o.value)}
                      onChange={() => dispatch({ type: 'toggleSymptom', value: o.value })}
                    />
                    {o.label}
                  </label>
                ))}
              </div>
            </fieldset>
          )}
        </section>

        <section className="step" aria-labelledby="rf-step3">
          <h2 id="rf-step3">{t.stepDescribe}</h2>
          {otherFields.map((f) => (
            <TemplateField
              key={f.key}
              field={f}
              value={s.answers[f.key]}
              error={err(f.key)}
              labels={t}
              onChange={(value) => dispatch({ type: 'answer', key: f.key, value })}
            />
          ))}
          <div className="field">
            <label htmlFor="rf-description">
              {t.description}
              <span className="req" aria-hidden="true"> *</span>
            </label>
            {errP(FORM_KEYS.description, 'rf-description')}
            <textarea
              id="rf-description"
              rows={5}
              maxLength={MAX_DESCRIPTION}
              autoComplete="off"
              spellCheck
              value={s.description}
              aria-invalid={err(FORM_KEYS.description) ? true : undefined}
              aria-describedby={err(FORM_KEYS.description) ? 'rf-description-hint rf-description-error' : 'rf-description-hint'}
              onChange={(e) => dispatch({ type: 'description', value: e.target.value })}
            />
            <p id="rf-description-hint" className="muted small">
              {t.descriptionHint} {t.charsLeft(MAX_DESCRIPTION - s.description.length)}
            </p>
          </div>
        </section>

        <section className="step" aria-labelledby="rf-step4">
          <h2 id="rf-step4">{t.stepContact}</h2>
          <div className="field">
            <label htmlFor="rf-contact">{t.preferredContact}</label>
            <select id="rf-contact" value={s.preferredContact} onChange={(e) => dispatch({ type: 'contact', value: e.target.value as PreferredContact })}>
              {CONTACT_OPTIONS.map((o) => (
                <option key={o} value={o}>
                  {t.contacts[o]}
                </option>
              ))}
            </select>
          </div>
          <fieldset className="field">
            <legend>{t.consentLegend}</legend>
            {errP(FORM_KEYS.consent, 'rf-consent')}
            <label className="checkbox">
              <input
                id="rf-consent"
                type="checkbox"
                checked={s.consentAssessment}
                aria-invalid={err(FORM_KEYS.consent) ? true : undefined}
                aria-describedby={err(FORM_KEYS.consent) ? 'rf-consent-error' : undefined}
                onChange={(e) => dispatch({ type: 'consent', value: e.target.checked })}
              />
              <span>{t.consentAssessment}</span>
            </label>
            <details className="optional-consents">
              <summary>{t.consentOptional}</summary>
              {SELF_GRANTABLE_PURPOSES.map((pp) => (
                <label key={pp} className="checkbox">
                  <input type="checkbox" checked={s.purposes.includes(pp)} onChange={() => dispatch({ type: 'togglePurpose', value: pp })} />
                  <span>{t.purposes[pp]}</span>
                </label>
              ))}
            </details>
          </fieldset>
        </section>

        <section className="step" aria-labelledby="rf-step5">
          <h2 id="rf-step5">{t.stepAttach}</h2>
          <p id="rf-attach-hint" className="muted small">
            {t.attachmentsHint}
          </p>
          {s.attachmentProblems.length > 0 && (
            <ul className="field-error" role="alert">
              {s.attachmentProblems.map((pr, i) => (
                <li key={i}>
                  {pr.kind === 'type' ? t.errFileType(pr.name) : pr.kind === 'size' ? t.errFileSize(pr.name) : t.errFileCount(pr.max)}
                </li>
              ))}
            </ul>
          )}
          <div className="actions">
            <label className="button secondary file-button">
              {t.choosePhotos}
              <input
                type="file"
                className="visually-hidden"
                accept={ACCEPT}
                multiple
                disabled={s.files.length >= MAX_FILES}
                aria-describedby="rf-attach-hint"
                onChange={(e) => {
                  p.onFiles(Array.from(e.target.files ?? []) as unknown as F[]);
                  e.target.value = '';
                }}
              />
            </label>
            {p.isNative && (
              <label className="button secondary file-button">
                {t.takePhoto}
                <input
                  type="file"
                  className="visually-hidden"
                  accept="image/*"
                  capture="environment"
                  disabled={s.files.length >= MAX_FILES}
                  aria-describedby="rf-attach-hint"
                  onChange={(e) => {
                    p.onFiles(Array.from(e.target.files ?? []) as unknown as F[]);
                    e.target.value = '';
                  }}
                />
              </label>
            )}
          </div>
          {s.files.length > 0 && (
            <ul className="file-list" aria-label={t.attachments}>
              {s.files.map((f, i) => (
                <li key={`${f.name}-${i}`}>
                  <span dir="auto">{f.name}</span> <span className="muted small">({formatBytes(f.size)})</span>
                  <button type="button" className="link-button" onClick={() => dispatch({ type: 'removeFile', index: i })} aria-label={`${t.remove} ${f.name}`}>
                    {t.remove}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <button type="submit" disabled={p.submitting}>
          {p.submitting ? t.submitting : t.submit}
        </button>
      </form>
    </div>
  );
}
