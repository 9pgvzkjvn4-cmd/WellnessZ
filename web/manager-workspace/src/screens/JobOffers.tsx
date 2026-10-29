import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../api/fitness';
import {
  buildJobOffer,
  canWithdrawOffer,
  emptyJobOfferForm,
  JOB_OFFER_FIELDS,
  MAX_HOURS_PER_WEEK,
  OFFER_LIMITS,
  PAY_BASES,
  PAY_BASIS_LABELS,
  todayIso,
  toJobOffers,
  toJobProfiles,
  toRestrictionCatalogue,
  validateJobOffer,
  type CatalogueRestriction,
  type JobOffer,
  type JobOfferForm,
  type JobProfile,
  type PayBasis,
} from '../api/hr';
import { GUIDANCE, type FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { CaseHeader } from '../components/CaseHeader';
import { FieldError, InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { restrictionCheckboxId } from '../components/ids';
import { describeError, formatDate, formatDay, humanise, idPart } from '../components/format';
import { NoClinicalInfoNote, RestrictionList } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const ORDER = JOB_OFFER_FIELDS.map((k) => `jo-${k}`);
const catalogueText = (c: CatalogueRestriction) => c.label.replace(/\{value\}/g, '…');

export interface JobOfferFormViewProps {
  form: JobOfferForm;
  errors: FieldErrors;
  message?: string;
  submitting: boolean;
  catalogue: readonly CatalogueRestriction[];
  jobs: readonly JobProfile[];
  onPatch: (patch: Partial<JobOfferForm>) => void;
  onSubmit: (e: FormEvent) => void;
}

/** E05-BL-008 – electronic alternative-duty job offer (JobOfferRequest). Hook-free view. */
export function JobOfferFormView({ form, errors, message, submitting, catalogue, jobs, onPatch, onSubmit }: JobOfferFormViewProps) {
  const selected = new Map(form.restrictions.map((r) => [r.code, r]));
  const toggle = (c: CatalogueRestriction, on: boolean) =>
    onPatch({ restrictions: on ? [...form.restrictions, { code: c.code, value: '' }] : form.restrictions.filter((r) => r.code !== c.code) });
  const setValue = (code: string, value: string) => onPatch({ restrictions: form.restrictions.map((r) => (r.code === code ? { ...r, value } : r)) });

  return (
    <form className="form" noValidate onSubmit={onSubmit} aria-describedby="jo-guidance" aria-label="New job offer">
      <div className="banner banner-warning" role="note" id="jo-guidance">
        <strong>Do not include diagnoses or medical opinions</strong>
        <span className="small">
          The offer is sent to the employee and stored on the case. Describe the role, hours, pay and functional restrictions
          only.
        </span>
      </div>
      {message && <Alert kind="error">{message}</Alert>}

      <div className={errors.jobCode ? 'field field-invalid' : 'field'}>
        <label htmlFor="jo-jobCode">
          Job (job-demand profile)<span className="req" aria-hidden="true"> *</span>
        </label>
        <FieldError id="jo-jobCode" error={errors.jobCode} />
        <select
          id="jo-jobCode"
          value={form.jobCode}
          aria-required
          aria-invalid={errors.jobCode ? true : undefined}
          aria-describedby={errors.jobCode ? 'jo-jobCode-error' : undefined}
          onChange={(e) => onPatch({ jobCode: e.target.value, jobTitle: jobs.find((j) => j.jobCode === e.target.value)?.title ?? '' })}
        >
          <option value="">Select…</option>
          {jobs.map((j) => (
            <option key={j.jobCode} value={j.jobCode}>
              {j.title} ({j.jobCode}
              {j.unit ? `, ${j.unit}` : ''})
            </option>
          ))}
        </select>
      </div>
      <InputField id="jo-jobTitle" label="Job title" required maxLength={OFFER_LIMITS.jobTitle} value={form.jobTitle} onChange={(v) => onPatch({ jobTitle: v })} error={errors.jobTitle} />
      <TextAreaField id="jo-duties" label="Duties" required max={OFFER_LIMITS.duties} value={form.duties} onChange={(v) => onPatch({ duties: v })} hint={GUIDANCE} error={errors.duties} rows={4} />
      <InputField id="jo-location" label="Location" required maxLength={OFFER_LIMITS.location} value={form.location} onChange={(v) => onPatch({ location: v })} error={errors.location} />
      <InputField
        id="jo-shift"
        label="Shift pattern"
        required
        maxLength={OFFER_LIMITS.shift}
        hint="For example: Mon–Fri, day shift 08:00–14:00."
        value={form.shift}
        onChange={(v) => onPatch({ shift: v })}
        error={errors.shift}
      />
      <InputField
        id="jo-hoursPerWeek"
        type="number"
        inputMode="numeric"
        min={1}
        max={MAX_HOURS_PER_WEEK}
        label="Hours per week"
        required
        value={form.hoursPerWeek}
        onChange={(v) => onPatch({ hoursPerWeek: v })}
        error={errors.hoursPerWeek}
      />
      <fieldset
        id="jo-payBasis"
        className={errors.payBasis ? 'field field-invalid' : 'field'}
        aria-invalid={errors.payBasis ? true : undefined}
        aria-describedby={errors.payBasis ? 'jo-payBasis-error' : undefined}
      >
        <legend>
          Pay basis<span className="req" aria-hidden="true"> *</span>
        </legend>
        <FieldError id="jo-payBasis" error={errors.payBasis} />
        <div className="inline-options">
          {PAY_BASES.map((b) => (
            <label key={b} className="radio">
              <input type="radio" name="jo-payBasis" value={b} checked={form.payBasis === b} onChange={() => onPatch({ payBasis: b as PayBasis })} />
              {PAY_BASIS_LABELS[b]}
            </label>
          ))}
        </div>
      </fieldset>
      <TextAreaField
        id="jo-payNote"
        label="Pay note"
        required={form.payBasis === 'ADJUSTED_PAY'}
        max={OFFER_LIMITS.payNote}
        value={form.payNote}
        onChange={(v) => onPatch({ payNote: v })}
        hint="Required for adjusted pay – for example the protected period or allowances."
        error={errors.payNote}
        rows={2}
      />
      <fieldset
        id="jo-restrictions"
        className={errors.restrictions ? 'field field-invalid' : 'field'}
        aria-invalid={errors.restrictions ? true : undefined}
        aria-describedby={errors.restrictions ? 'jo-restrictions-hint jo-restrictions-error' : 'jo-restrictions-hint'}
      >
        <legend>Restrictions the role respects</legend>
        <p id="jo-restrictions-hint" className="muted small">
          From the restriction catalogue – functional wording only. Use the restrictions advised by Occupational Health.
        </p>
        <FieldError id="jo-restrictions" error={errors.restrictions} />
        {catalogue.length === 0 ? (
          <p className="muted small">The restriction catalogue is not available.</p>
        ) : (
          <ul className="checkbox-list">
            {catalogue.map((c) => {
              const id = restrictionCheckboxId(c.code);
              const sel = selected.get(c.code);
              return (
                <li key={c.code}>
                  <label className="checkbox" htmlFor={id}>
                    <input id={id} type="checkbox" checked={!!sel} onChange={(e) => toggle(c, e.target.checked)} />
                    {catalogueText(c)}
                  </label>
                  {sel && c.valueRequired && (
                    <>
                      <label htmlFor={`${id}-value`} className="visually-hidden">
                        Value{c.unit ? ` (${c.unit})` : ''} for {catalogueText(c)}
                      </label>
                      <input
                        id={`${id}-value`}
                        type="number"
                        inputMode="numeric"
                        min={0}
                        value={sel.value}
                        aria-required
                        onChange={(e) => setValue(c.code, e.target.value)}
                      />
                      {c.unit && <span className="muted small"> {c.unit}</span>}
                    </>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </fieldset>
      <InputField id="jo-startDate" type="date" label="Start date" required value={form.startDate} onChange={(v) => onPatch({ startDate: v })} error={errors.startDate} />
      <InputField id="jo-endDate" type="date" label="End date (optional)" value={form.endDate} onChange={(v) => onPatch({ endDate: v })} error={errors.endDate} />
      <InputField id="jo-contactName" label="Contact name" required maxLength={OFFER_LIMITS.contactName} value={form.contactName} onChange={(v) => onPatch({ contactName: v })} error={errors.contactName} />
      <InputField
        id="jo-contactEmail"
        type="email"
        label="Contact e-mail"
        required
        maxLength={OFFER_LIMITS.contactEmail}
        autoComplete="off"
        value={form.contactEmail}
        onChange={(v) => onPatch({ contactEmail: v })}
        error={errors.contactEmail}
      />
      <InputField
        id="jo-respondBy"
        type="date"
        label="Employee to respond by"
        required
        hint="On or before the start date."
        value={form.respondBy}
        onChange={(v) => onPatch({ respondBy: v })}
        error={errors.respondBy}
      />
      <button type="submit" disabled={submitting}>
        {submitting ? 'Issuing…' : 'Issue job offer'}
      </button>
    </form>
  );
}

export interface OfferListProps {
  offers: readonly JobOffer[];
  confirming?: string;
  busy?: string;
  onWithdraw: (id: string) => void;
  onConfirm: (id: string) => void;
  onCancel: () => void;
}

/** Offers for the case with their status; ISSUED offers can be withdrawn (two-step). Hook-free. */
export function OfferList({ offers, confirming, busy, onWithdraw, onConfirm, onCancel }: OfferListProps) {
  if (offers.length === 0) return <p className="muted">No job offers for this case yet.</p>;
  return (
    <ul className="card-list">
      {offers.map((o) => {
        const headingId = `jo-offer-${idPart(o.id)}`;
        return (
          <li key={o.id} className="card">
            <article aria-labelledby={headingId}>
              <div className="card-row">
                <h3 id={headingId}>
                  {o.jobTitle ?? o.jobCode ?? 'Job offer'}
                  {o.jobCode && <span className="muted small"> ({o.jobCode})</span>}
                </h3>
                <StatusBadge status={o.status} />
              </div>
              <dl className="details">
                <dt>Duties</dt>
                <dd>{o.duties ?? '–'}</dd>
                <dt>Location / shift</dt>
                <dd>
                  {o.location ?? '–'} · {o.shift ?? '–'}
                </dd>
                <dt>Hours per week</dt>
                <dd>{o.hoursPerWeek ?? '–'}</dd>
                <dt>Pay</dt>
                <dd>
                  {o.payBasis ? (PAY_BASIS_LABELS[o.payBasis as PayBasis] ?? humanise(o.payBasis)) : '–'}
                  {o.payNote && <span className="muted"> – {o.payNote}</span>}
                </dd>
                <dt>Restrictions</dt>
                <dd>
                  <RestrictionList items={o.restrictions} />
                </dd>
                <dt>Dates</dt>
                <dd>
                  {formatDay(o.startDate)} – {o.endDate ? formatDay(o.endDate) : 'open-ended'}
                </dd>
                <dt>Respond by</dt>
                <dd>{formatDay(o.respondBy)}</dd>
                <dt>Contact</dt>
                <dd>
                  {o.contactName ?? '–'}
                  {o.contactEmail && <> · {o.contactEmail}</>}
                </dd>
                <dt>Issued</dt>
                <dd>{formatDate(o.issuedAt)}</dd>
                {o.respondedAt && (
                  <>
                    <dt>Employee responded</dt>
                    <dd>{formatDate(o.respondedAt)}</dd>
                  </>
                )}
              </dl>
              <div className="actions">
                {o.documentId && (
                  <a href={documentContentUrl(o.documentId)} download>
                    Download offer (PDF)
                  </a>
                )}
                {canWithdrawOffer(o) &&
                  (confirming === o.id ? (
                    <>
                      <button type="button" disabled={busy === o.id} onClick={() => onConfirm(o.id)}>
                        {busy === o.id ? 'Withdrawing…' : 'Confirm withdrawal'}
                      </button>
                      <button type="button" className="secondary" disabled={busy === o.id} onClick={onCancel}>
                        Keep offer
                      </button>
                    </>
                  ) : (
                    <button type="button" className="secondary" aria-label={`Withdraw offer ${o.jobTitle ?? o.id}`} onClick={() => onWithdraw(o.id)}>
                      Withdraw offer
                    </button>
                  ))}
              </div>
            </article>
          </li>
        );
      })}
    </ul>
  );
}

export function JobOffers({ client, onSessionExpired, caseId }: ScreenProps & { caseId: string }) {
  const base = `/cases/${encodeURIComponent(caseId)}/job-offers`;
  const offers = useAsync(async () => toJobOffers(await client.api<unknown>(base)), onSessionExpired);
  const master = useAsync(async () => {
    const [restrictions, jobs] = await Promise.all([client.api<unknown>('/masterdata/restrictions'), client.api<unknown>('/masterdata/job-demands')]);
    return { catalogue: toRestrictionCatalogue(restrictions), jobs: toJobProfiles(jobs) };
  }, onSessionExpired);

  const [form, setForm] = useState<JobOfferForm>(emptyJobOfferForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string }>();
  const [confirming, setConfirming] = useState<string>();
  const [busy, setBusy] = useState<string>();

  const catalogue = master.data?.catalogue ?? [];

  const onPatch = (patch: Partial<JobOfferForm>) => {
    setForm((f) => ({ ...f, ...patch }));
    setErrors((e) => {
      const next = Object.fromEntries(Object.entries(e).filter(([k]) => !(k in patch)));
      return Object.keys(next).length === Object.keys(e).length ? e : next;
    });
  };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(undefined);
    setNotice(undefined);
    const v = validateJobOffer(form, catalogue, todayIso());
    setErrors(v);
    if (Object.keys(v).length) {
      setMessage('Please check the highlighted fields.');
      focusFirstError(ORDER.filter((id) => v[id.slice(3)]));
      return;
    }
    setSubmitting(true);
    const r = await submitAction(client, base, buildJobOffer(form, catalogue), JOB_OFFER_FIELDS, { failed: 'The job offer was not issued' });
    setSubmitting(false);
    if (r.ok) {
      setForm(emptyJobOfferForm());
      setNotice({ kind: 'success', text: 'Job offer issued. The employee has been notified and the offer document is stored on the case.' });
      offers.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors(r.fieldErrors);
    setMessage(r.message);
    focusFirstError(ORDER.filter((id) => r.fieldErrors[id.slice(3)]));
  }

  async function withdraw(id: string) {
    setBusy(id);
    setNotice(undefined);
    const r = await submitAction(client, `/job-offers/${encodeURIComponent(id)}/withdraw`, {}, [], { failed: 'The offer was not withdrawn' });
    setBusy(undefined);
    setConfirming(undefined);
    if (r.ok) {
      setNotice({ kind: 'success', text: 'The job offer was withdrawn.' });
      offers.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setNotice({ kind: 'error', text: r.message });
  }

  return (
    <section>
      <p>
        <a href="#/">← Back to team cases</a>
      </p>
      <PageTitle>Alternative-duty job offers</PageTitle>
      <CaseHeader client={client} caseId={caseId} onSessionExpired={onSessionExpired} />
      <NoClinicalInfoNote>Offers state role, hours, pay, functional restrictions and a contact – never medical details.</NoClinicalInfoNote>
      {notice && <Alert kind={notice.kind}>{notice.text}</Alert>}
      <h2>Offers for this case</h2>
      {offers.loading && <Loading />}
      {!!offers.error && <Alert kind="error">{describeError(offers.error)}</Alert>}
      {offers.data && (
        <OfferList
          offers={offers.data}
          confirming={confirming}
          busy={busy}
          onWithdraw={setConfirming}
          onConfirm={(id) => void withdraw(id)}
          onCancel={() => setConfirming(undefined)}
        />
      )}
      <h2 className="section-gap">New alternative-duty offer</h2>
      {master.loading && <Loading label="Loading job profiles and restriction catalogue…" />}
      {!!master.error && <Alert kind="error">{describeError(master.error)}</Alert>}
      {master.data && (
        <JobOfferFormView
          form={form}
          errors={errors}
          message={message}
          submitting={submitting}
          catalogue={master.data.catalogue}
          jobs={master.data.jobs}
          onPatch={onPatch}
          onSubmit={(e) => void onSubmit(e)}
        />
      )}
    </section>
  );
}
