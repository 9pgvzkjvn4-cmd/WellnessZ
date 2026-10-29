import { useEffect, useReducer, useRef, useState, type FormEvent } from 'react';
import type { BodyMapRegion, Language, Me, SelfReferralResponse, Template } from '../api/types';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { describeError } from '../components/format';
import { Alert, Loading } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { DEFAULT_LANGUAGES, dirFor, initialLanguage, stringsFor } from '../i18n/strings';
import {
  acknowledgementText,
  buildSelfReferral,
  initialReferralState,
  pickDefaultTemplate,
  referralReducer,
  validateReferral,
  type ErrorMap,
} from '../intake/referralForm';
import { uploadAttachments } from '../intake/upload';
import { errorName, log } from '../security/log';
import { SelfReferralForm } from './SelfReferralForm';
import type { ScreenProps } from './types';

type Ack = { caseRef: string; text?: string; uploaded: number; total: number; language: string };

const browserLanguages = (): readonly string[] => (typeof navigator !== 'undefined' ? navigator.languages ?? [] : []);

/** Department codes that may select a template: `/me.departmentCode` (if provided), then org-scope codes. */
function departmentHints(me: Me | undefined): (string | undefined)[] {
  if (!me) return [];
  const scope = Array.isArray(me.orgScope) ? me.orgScope : me.orgScope ? [me.orgScope] : [];
  return [me.departmentCode, ...scope];
}

/**
 * Self-referral (E01-BL-006/009/010): language switch, department template, dynamic fields, body map, symptoms,
 * consent, attachments – one scrolling page. Submits `POST /intake/referrals/self`, then uploads attachments to
 * `POST /cases/{id}/attachments` and drops the files from memory.
 */
export function SelfReferral({ client, onSessionExpired, isNative = false }: ScreenProps & { isNative?: boolean }) {
  const languages = useAsync<Language[]>(async () => {
    try {
      const l = await client.api<Language[]>('/languages');
      return Array.isArray(l) && l.length ? l : DEFAULT_LANGUAGES;
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      return DEFAULT_LANGUAGES;
    }
  }, onSessionExpired);
  const me = useAsync<Me | undefined>(async () => {
    try {
      return await client.api<Me>('/me');
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      return undefined; // pre-selection is a convenience only
    }
  }, onSessionExpired);
  const regions = useAsync<BodyMapRegion[]>(async () => {
    const body = await client.api<{ regions?: BodyMapRegion[] }>('/intake/body-map');
    return Array.isArray(body?.regions) ? body.regions : [];
  }, onSessionExpired);

  const [state, dispatch] = useReducer(referralReducer<File>, undefined, () => initialReferralState<File>(''));
  const [templates, setTemplates] = useState<{ lang: string; list?: Template[]; error?: unknown }>({ lang: '' });
  const [errors, setErrors] = useState<ErrorMap>({});
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string>();
  const [ack, setAck] = useState<Ack>();
  const userPickedTemplate = useRef(false);
  const focusSummary = useRef(0);

  // Initial language once /languages is known.
  const offered = languages.data;
  useEffect(() => {
    if (offered && !state.language) dispatch({ type: 'language', language: initialLanguage(offered, browserLanguages()) });
  }, [offered, state.language]);

  // Templates are re-fetched in the selected language (labels are localised by the server).
  const lang = state.language;
  useEffect(() => {
    if (!lang) return;
    let cancelled = false;
    client
      .api<Template[]>(`/intake/templates?audience=EMPLOYEE&lang=${encodeURIComponent(lang)}`)
      .then(
        (list) => !cancelled && setTemplates({ lang, list: Array.isArray(list) ? list : [] }),
        (error: unknown) => {
          if (cancelled) return;
          if (error instanceof SessionExpiredError) onSessionExpired();
          setTemplates({ lang, error });
        },
      );
    return () => {
      cancelled = true;
    };
  }, [client, lang, onSessionExpired]);

  const list = templates.list;
  const suggested = list ? pickDefaultTemplate(list, departmentHints(me.data)) : '';
  const template = list?.find((x) => x.code === state.templateCode);

  // Pre-select the department template until the user picks one; keep the choice across language switches.
  useEffect(() => {
    if (!list) return;
    const current = state.templateCode;
    const exists = list.some((x) => x.code === current);
    const target = userPickedTemplate.current ? (exists ? current : suggested) : suggested || (exists ? current : '');
    if (target !== current) dispatch({ type: 'template', code: target, template: list.find((x) => x.code === target) });
  }, [list, suggested, state.templateCode]);

  // Move focus to the error summary after a failed validation.
  useEffect(() => {
    if (focusSummary.current > 0) document.getElementById('rf-error-summary')?.focus();
  }, [errors]);

  const t = stringsFor(state.language);
  // Re-validate live once the user has tried to submit, so messages disappear as fields are fixed.
  const shownErrors = submitted ? validateReferral(state, template, t) : errors;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    setSubmitError(undefined);
    const v = validateReferral(state, template, t);
    if (Object.keys(v).length > 0 || !template) {
      focusSummary.current++;
      setErrors(v);
      return;
    }
    setSubmitting(true);
    const files = state.files;
    try {
      const res = await client.api<SelfReferralResponse>('/intake/referrals/self', {
        method: 'POST',
        body: buildSelfReferral(state, template),
      });
      let uploaded = 0;
      if (files.length && res?.caseId) {
        try {
          uploaded = (await uploadAttachments(client, res.caseId, files)).uploaded;
        } finally {
          dispatch({ type: 'clearFiles' }); // never keep files after upload
        }
      }
      setAck({ caseRef: res?.caseRef ?? '', text: acknowledgementText(res?.acknowledgement), uploaded, total: files.length, language: state.language });
      dispatch({ type: 'reset', language: state.language });
      setSubmitted(false);
      setErrors({});
      window.scrollTo?.(0, 0);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        dispatch({ type: 'clearFiles' });
        onSessionExpired();
      }
      log.warn('intake.self-referral-failed', { errorName: errorName(err), status: err instanceof ApiError ? err.status : undefined });
      setSubmitError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (ack) {
    const at = stringsFor(ack.language);
    return (
      <section dir={dirFor(ack.language)} lang={ack.language}>
        <h1 tabIndex={-1}>{at.ackTitle}</h1>
        <Alert kind="success">
          {/* the server acknowledgement already names the case reference; fall back to the local text without it */}
          <p className="ack-ref">{ack.text && ack.caseRef && ack.text.includes(ack.caseRef) ? ack.text : at.ackBody(ack.caseRef || '–')}</p>
          {ack.text && !(ack.caseRef && ack.text.includes(ack.caseRef)) && <p>{ack.text}</p>}
          {ack.total > 0 && <p>{at.ackUploads(ack.uploaded, ack.total)}</p>}
        </Alert>
        <p>{at.ackNext}</p>
        <a className="button" href="#/">
          {at.backHome}
        </a>
      </section>
    );
  }

  if (languages.loading || !state.language) return <Loading />;
  const loadError = templates.error ?? regions.error;

  return (
    <>
      {loadError ? <Alert kind="error">{describeError(loadError)}</Alert> : null}
      {!list && !templates.error && <Loading label={t.loading} />}
      <SelfReferralForm<File>
        state={state}
        dispatch={dispatch}
        t={t}
        languages={languages.data ?? DEFAULT_LANGUAGES}
        templates={list ?? []}
        template={template}
        suggestedTemplateCode={suggested || undefined}
        regions={regions.data ?? []}
        errors={shownErrors}
        submitError={submitError}
        submitting={submitting}
        isNative={isNative}
        onLanguage={(code) => dispatch({ type: 'language', language: code })}
        onTemplate={(code) => {
          userPickedTemplate.current = true;
          dispatch({ type: 'template', code, template: list?.find((x) => x.code === code) });
        }}
        onFiles={(files) => dispatch({ type: 'addFiles', files })}
        onSubmit={(e) => void onSubmit(e)}
      />
    </>
  );
}
