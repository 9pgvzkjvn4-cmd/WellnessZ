import { useState, type FormEvent } from 'react';
import {
  DECLARATION_REASON_LABELS,
  DECLARATION_REASONS,
  type FitnessDeclarationResponse,
  type Me,
} from '../api/types';
import { SessionExpiredError } from '../auth/AuthClient';
import { describeError } from '../components/format';
import { Alert, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import {
  buildDeclaration,
  looksLikeCrew,
  MAX_DETAILS,
  todayIso,
  validateDeclaration,
  type DeclarationForm,
} from '../intake/fitnessDeclaration';
import type { ScreenProps } from './types';

/**
 * Crew decrease-in-fitness declaration (E01-BL-016): `POST /intake/triggers/fitness-declaration` opens a FITNESS case
 * with fitness status UNFIT_PENDING; crew scheduling receives the status only, the AME queue is notified.
 */
export function FitnessDeclaration({ client, onSessionExpired }: ScreenProps) {
  const me = useAsync<Me | undefined>(async () => {
    try {
      return await client.api<Me>('/me');
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      return undefined;
    }
  }, onSessionExpired);
  const [form, setForm] = useState<DeclarationForm>({ reason: '', unfitFrom: todayIso(), expectedUntil: '', details: '' });
  const [errors, setErrors] = useState<ReturnType<typeof validateDeclaration>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [done, setDone] = useState<FitnessDeclarationResponse>();

  const set = <K extends keyof DeclarationForm>(k: K, v: DeclarationForm[K]) => setForm((f) => ({ ...f, [k]: v }));

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const v = validateDeclaration(form);
    setErrors(v);
    if (Object.keys(v).length) return;
    setBusy(true);
    setError(undefined);
    try {
      const res = await client.api<FitnessDeclarationResponse>('/intake/triggers/fitness-declaration', {
        method: 'POST',
        body: buildDeclaration(form),
      });
      setDone(res ?? {});
    } catch (err) {
      if (err instanceof SessionExpiredError) onSessionExpired();
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <section>
        <PageTitle>Declaration received</PageTitle>
        <Alert kind="success">
          Thank you for declaring a decrease in fitness{done.caseRef ? ` – your case reference is ${done.caseRef}` : ''}. Do
          not operate until you have been assessed.
        </Alert>
        <h2>What happens next</h2>
        <ul>
          <li>An aeromedical examiner (AME) will contact you.</li>
          <li>
            <strong>Crew scheduling only sees “unfit – pending”.</strong> They do not see your reason or anything you
            wrote.
          </li>
          <li>Your status will be updated after the assessment. You can follow it on the home screen.</li>
        </ul>
        <a className="button" href="#/">
          Back to home
        </a>
      </section>
    );
  }

  const crew = looksLikeCrew(me.data);
  const errId = (k: keyof DeclarationForm) => (errors[k] ? `fd-${k}-error` : undefined);

  return (
    <section>
      <PageTitle>Decrease in fitness declaration</PageTitle>
      <p className="lead">
        For flight and cabin crew: tell the aeromedical team as soon as your fitness to fly may be reduced – for example by
        illness, injury, medication, fatigue or pregnancy.
      </p>
      {!crew && (
        <Alert kind="info">
          This declaration is intended for flight and cabin crew. If you are not crew, please use{' '}
          <a href="#/refer">Contact OH</a> instead.
        </Alert>
      )}
      <p className="muted small">
        Crew scheduling will only see the status “unfit – pending”. The reason and details are visible to the medical team
        only.
      </p>
      {error && <Alert kind="error">{error}</Alert>}
      <form className="form" noValidate onSubmit={(e) => void onSubmit(e)}>
        <fieldset id="fd-reason" aria-invalid={errors.reason ? true : undefined} aria-describedby={errId('reason')}>
          <legend>
            Reason<span className="req" aria-hidden="true"> *</span>
          </legend>
          {errors.reason && (
            <p id="fd-reason-error" className="field-error">
              {errors.reason}
            </p>
          )}
          {DECLARATION_REASONS.map((r) => (
            <label key={r} className="radio">
              <input type="radio" name="reason" value={r} checked={form.reason === r} onChange={() => set('reason', r)} />
              {DECLARATION_REASON_LABELS[r]}
            </label>
          ))}
        </fieldset>

        <label htmlFor="fd-unfitFrom">
          Unfit from<span className="req" aria-hidden="true"> *</span>
        </label>
        {errors.unfitFrom && (
          <p id="fd-unfitFrom-error" className="field-error">
            {errors.unfitFrom}
          </p>
        )}
        <input
          id="fd-unfitFrom"
          type="date"
          value={form.unfitFrom}
          aria-invalid={errors.unfitFrom ? true : undefined}
          aria-describedby={errId('unfitFrom')}
          onChange={(e) => set('unfitFrom', e.target.value)}
        />

        <label htmlFor="fd-expectedUntil">Expected until (if known)</label>
        {errors.expectedUntil && (
          <p id="fd-expectedUntil-error" className="field-error">
            {errors.expectedUntil}
          </p>
        )}
        <input
          id="fd-expectedUntil"
          type="date"
          min={form.unfitFrom || undefined}
          value={form.expectedUntil}
          aria-invalid={errors.expectedUntil ? true : undefined}
          aria-describedby={errId('expectedUntil')}
          onChange={(e) => set('expectedUntil', e.target.value)}
        />

        <label htmlFor="fd-details">Details for the medical team (optional)</label>
        {errors.details && (
          <p id="fd-details-error" className="field-error">
            {errors.details}
          </p>
        )}
        <textarea
          id="fd-details"
          rows={4}
          maxLength={MAX_DETAILS}
          autoComplete="off"
          value={form.details}
          aria-invalid={errors.details ? true : undefined}
          aria-describedby={errId('details') ?? 'fd-details-hint'}
          onChange={(e) => set('details', e.target.value)}
        />
        <p id="fd-details-hint" className="muted small">
          Only the medical team can read this. {MAX_DETAILS - form.details.length} characters left.
        </p>

        <button type="submit" disabled={busy}>
          {busy ? 'Sending…' : 'Send declaration'}
        </button>
      </form>
    </section>
  );
}
