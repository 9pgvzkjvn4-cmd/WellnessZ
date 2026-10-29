import { useState } from 'react';
import { platformApi, type DocumentMeta } from '../../api/platform';
import { CHANNELS, treatmentApi, URGENCIES, type Provider, type ReferralDetail, type ReferralService } from '../../api/treatment';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorInfo, formatDay, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { formatMoney } from '../../domain/money';
import {
  cleanDataSet,
  dataSetFieldId,
  emptyReferralForm,
  filterProviders,
  problemCode,
  validateWizardStep,
  WIZARD_LABELS,
  WIZARD_STEPS,
  type ProviderQuery,
  type ReferralForm,
  type WizardStep,
} from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { ProviderBadges, ScoreBadge } from './common';

const CHANNEL_HINT: Record<string, string> = {
  PORTAL: 'Provider works the referral in the WellnessZ partner portal (recommended).',
  FHIR: 'Structured FHIR R4 ServiceRequest to the hospital system.',
  SECURE_EMAIL: 'Content-free notification; the letter is collected through the portal link.',
};

const fromDetail = (r: ReferralDetail): ReferralForm => ({
  serviceCode: r.serviceCode,
  urgency: r.urgency,
  providerCode: r.providerCode,
  reason: r.reason ?? '',
  question: r.question ?? '',
  dataSet: { ...(r.dataSet ?? {}) },
  consentId: '',
  channel: r.channel,
  attachDocumentIds: (r.attachments ?? []).map((a) => (typeof a === 'string' ? a : a.documentId)),
});

function ProviderPicker({ props, service, form, setForm, error }: { props: ScreenProps; service?: ReferralService; form: ReferralForm; setForm: (f: ReferralForm) => void; error?: string }) {
  const api = treatmentApi(props.client);
  const all = useAsync(() => api.providers({ serviceCode: form.serviceCode }), props.onSessionExpired, [form.serviceCode]);
  const [q, setQ] = useState<ProviderQuery>({ contract: '', preferredOnly: false, city: '', q: '' });
  const cities = [...new Set((all.data ?? []).map((p) => p.city).filter((c): c is string => !!c))].sort();
  const list = filterProviders(all.data ?? [], { ...q, serviceCode: form.serviceCode });
  const rate = (p: Provider) => (p.rates ?? p.contract?.rates ?? []).find((r) => r.serviceCode === form.serviceCode)?.price;

  return (
    <div>
      <div className="filters inline-form" role="search" aria-label="Provider search">
        <Field label="Search">{(id) => <input id={id} type="search" value={q.q} onChange={(e) => setQ({ ...q, q: e.target.value })} placeholder="Name, code, specialty" />}</Field>
        <Field label="City">
          {(id) => (
            <select id={id} value={q.city} onChange={(e) => setQ({ ...q, city: e.target.value })}>
              <option value="">Any city</option>
              {cities.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          )}
        </Field>
        <Field label="Contract">
          {(id) => (
            <select id={id} value={q.contract} onChange={(e) => setQ({ ...q, contract: e.target.value as ProviderQuery['contract'] })}>
              <option value="">Any</option>
              <option value="CONTRACTED">Contracted</option>
              <option value="NONE">No contract</option>
            </select>
          )}
        </Field>
        <label className="inline-row"><input type="checkbox" checked={!!q.preferredOnly} onChange={(e) => setQ({ ...q, preferredOnly: e.target.checked })} /> Preferred only</label>
      </div>
      {error && <p className="field-error-text small" id="rf-provider">{error}</p>}
      {all.loading && <Loading label="Searching providers…" />}
      {!!all.error && <ErrorAlert error={all.error} />}
      {all.data && list.length === 0 && <Empty>No provider offers {service?.name ?? 'this service'} with these filters.</Empty>}
      <ul className="provider-list" aria-label="Providers">
        {list.map((p) => {
          const price = rate(p);
          return (
            <li key={p.code} className="provider-card" data-selected={form.providerCode === p.code}>
              <label className="inline-row">
                <input type="radio" name="rf-provider" value={p.code} checked={form.providerCode === p.code} onChange={() => setForm({ ...form, providerCode: p.code })} />{' '}
                <strong>{p.name}</strong> <span className="small muted">{p.code} · {p.city ?? '–'}{p.coverage ? ` · ${humanise(p.coverage)}` : ''}</span>
              </label>
              <div className="badge-row">
                <ProviderBadges p={p} /> <ScoreBadge score={p.scorecard?.overall} />
              </div>
              <span className="small">
                {price ? `Contract rate ${formatMoney(price)}` : 'No contract rate'}
                {p.sla ? ` · SLA: appointment ${p.sla.appointmentDays} d (urgent ${p.sla.urgentHours} h), report ${p.sla.reportDays} d` : ''}
                {p.languages.length ? ` · ${p.languages.join(', ')}` : ''}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * E04-F02 referral wizard: service → provider → required data set → consent → channel → attachments, then save as DRAFT
 * or save and send. `existing` edits a DRAFT referral (PUT).
 */
export function ReferralWizard({ props, caseId, existing, onDone }: { props: ScreenProps; caseId: string; existing?: ReferralDetail; onDone: (message?: string) => void }) {
  const { client, onSessionExpired } = props;
  const api = treatmentApi(client);
  const services = useAsync(() => api.services(), onSessionExpired);
  const docs = useAsync(() => platformApi(client).documents({ caseId }).catch(() => [] as DocumentMeta[]), onSessionExpired);
  const [form, setForm] = useState<ReferralForm>(() => (existing ? fromDetail(existing) : emptyReferralForm()));
  const [step, setStep] = useState<WizardStep>('service');
  const [errors, setErrors] = useState<FieldError[]>([]);
  /** Id of the DRAFT once saved (a failed send keeps the draft; a retry updates it instead of creating another). */
  const [savedId, setSavedId] = useState<string | undefined>(existing?.id);
  const action = useAction(onSessionExpired);
  const service = (services.data ?? []).find((s) => s.code === form.serviceCode);
  const idx = WIZARD_STEPS.indexOf(step);
  const err = (f: string) => errorFor(errors, f);

  function go(to: WizardStep) {
    const target = WIZARD_STEPS.indexOf(to);
    if (target > idx) {
      for (const s of WIZARD_STEPS.slice(idx, target)) {
        const e = validateWizardStep(s, form, service);
        if (e.length) {
          setStep(s);
          setErrors(e);
          return;
        }
      }
    }
    setErrors([]);
    setStep(to);
  }

  async function save(send: boolean) {
    for (const s of WIZARD_STEPS) {
      const e = validateWizardStep(s, form, service);
      if (e.length) {
        setStep(s);
        setErrors(e);
        return;
      }
    }
    setErrors([]);
    const body = { ...form, reason: form.reason.trim(), question: form.question.trim(), dataSet: cleanDataSet(service, form.dataSet), consentId: form.consentId.trim() || undefined };
    let failed: unknown;
    let msg = '';
    const ok = await action.run(async () => {
      try {
        const r = savedId ? await api.updateReferral(savedId, body) : await api.createReferral(caseId, body);
        setSavedId(r.id);
        if (!send) {
          msg = `Draft referral ${r.ref} saved.`;
          return msg;
        }
        const sent = await api.sendReferral(r.id);
        msg = `Referral ${sent.ref} sent to ${sent.providerName ?? form.providerCode}. Report due ${formatDay(sent.reportDueAt)}.`;
        return msg;
      } catch (e) {
        failed = e;
        throw e;
      }
    });
    if (ok) {
      onDone(msg);
      return;
    }
    const code = problemCode(failed);
    if (code === 'consent_required') {
      setStep('consent');
      setErrors([{ field: 'rf-consent', message: `No active treatment-sharing consent for this provider${savedId || !existing ? ' – the referral is saved as a draft' : ''}. Record the employee's consent and enter its reference.` }]);
    } else if (code === 'data_set_incomplete' || code === 'required_field_missing') {
      setStep('data');
      setErrors([{ field: service?.requiredFields[0] ? dataSetFieldId(service.requiredFields[0].code) : 'rf-service', message: errorInfo(failed).message }]);
    }
  }

  return (
    <section className="tile" aria-labelledby="rf-title">
      <h2 id="rf-title">{existing ? `Edit draft ${existing.ref}` : 'New referral'}</h2>
      <ol className="wizard-steps" aria-label="Referral steps">
        {WIZARD_STEPS.map((s, i) => (
          <li key={s} aria-current={s === step ? 'step' : undefined} className={i < idx ? 'step-done' : undefined}>
            {i <= idx ? <button type="button" className="link-button" onClick={() => go(s)}>{WIZARD_LABELS[s]}</button> : WIZARD_LABELS[s]}
          </li>
        ))}
      </ol>
      <ErrorSummary errors={errors} />
      {services.loading && <Loading label="Loading services…" />}
      {!!services.error && <ErrorAlert error={services.error} />}

      {step === 'service' && (
        <div className="form">
          <IdField id="rf-service" label="Service" required error={err('rf-service')}>
            {(db) => (
              <select id="rf-service" aria-describedby={db} aria-invalid={!!err('rf-service')} value={form.serviceCode} onChange={(e) => setForm({ ...form, serviceCode: e.target.value, providerCode: '' })}>
                <option value="">Choose…</option>
                {(services.data ?? []).map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
              </select>
            )}
          </IdField>
          {service && (
            <p className="small muted">
              Appointment within {service.routineAppointmentDays} days (urgent: {service.urgentAppointmentHours} h); report due {service.reportDueDays} days after the visit.
            </p>
          )}
          <fieldset>
            <legend>Urgency</legend>
            {URGENCIES.map((u) => (
              <label key={u} className="inline-row">
                <input type="radio" name="rf-urgency" value={u} checked={form.urgency === u} onChange={() => setForm({ ...form, urgency: u })} /> {humanise(u)}
              </label>
            ))}
          </fieldset>
          <IdField id="rf-reason" label="Reason for referral" required error={err('rf-reason')}>
            {(db) => <textarea id="rf-reason" rows={3} maxLength={4000} aria-describedby={db} aria-invalid={!!err('rf-reason')} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />}
          </IdField>
          <IdField id="rf-question" label="Question for the provider" required error={err('rf-question')}>
            {(db) => <textarea id="rf-question" rows={2} maxLength={2000} aria-describedby={db} aria-invalid={!!err('rf-question')} value={form.question} onChange={(e) => setForm({ ...form, question: e.target.value })} />}
          </IdField>
        </div>
      )}

      {step === 'provider' && <ProviderPicker props={props} service={service} form={form} setForm={setForm} error={err('rf-provider')} />}

      {step === 'data' && (
        <div className="form">
          {!service || service.requiredFields.length === 0 ? (
            <Empty>No extra data required for this service.</Empty>
          ) : (
            <div className="fields-grid">
              {service.requiredFields.map((f) => {
                const id = dataSetFieldId(f.code);
                const v = form.dataSet[f.code] ?? '';
                const set = (val: string) => setForm({ ...form, dataSet: { ...form.dataSet, [f.code]: val } });
                return (
                  <IdField key={f.code} id={id} label={f.label} required error={err(id)} hint={f.type === 'LIST' ? 'Separate items with commas' : f.type === 'CODE' ? 'Code (e.g. SNOMED / ICD)' : undefined}>
                    {(db) =>
                      f.type === 'DATE' ? (
                        <input id={id} type="date" aria-describedby={db} aria-invalid={!!err(id)} value={v} onChange={(e) => set(e.target.value)} />
                      ) : f.type === 'TEXT' ? (
                        <textarea id={id} rows={2} aria-describedby={db} aria-invalid={!!err(id)} value={v} onChange={(e) => set(e.target.value)} />
                      ) : (
                        <input id={id} aria-describedby={db} aria-invalid={!!err(id)} value={v} onChange={(e) => set(e.target.value)} />
                      )
                    }
                  </IdField>
                );
              })}
            </div>
          )}
        </div>
      )}

      {step === 'consent' && (
        <div className="form">
          <p>The provider only sees the referral under the employee&apos;s active <strong>treatment sharing (partner)</strong> consent. Sending checks it.</p>
          <IdField id="rf-consent" label="Consent reference" required error={err('rf-consent')}>
            {(db) => <input id="rf-consent" aria-describedby={db} aria-invalid={!!err('rf-consent')} value={form.consentId} onChange={(e) => setForm({ ...form, consentId: e.target.value })} />}
          </IdField>
        </div>
      )}

      {step === 'channel' && (
        <fieldset className="form">
          <legend>Channel</legend>
          {CHANNELS.map((c) => (
            <label key={c} className="inline-row">
              <input type="radio" name="rf-channel" value={c} checked={form.channel === c} onChange={() => setForm({ ...form, channel: c })} /> {humanise(c)} <span className="small muted">– {CHANNEL_HINT[c]}</span>
            </label>
          ))}
        </fieldset>
      )}

      {step === 'attachments' && (
        <div className="form">
          <fieldset>
            <legend>Attach case documents</legend>
            {docs.loading && <Loading />}
            {docs.data && docs.data.length === 0 && <Empty>No documents on this case.</Empty>}
            {(docs.data ?? []).map((d) => (
              <label key={d.id} className="inline-row">
                <input
                  type="checkbox"
                  checked={form.attachDocumentIds.includes(d.id)}
                  onChange={(e) => setForm({ ...form, attachDocumentIds: e.target.checked ? [...form.attachDocumentIds, d.id] : form.attachDocumentIds.filter((x) => x !== d.id) })}
                />{' '}
                {d.title} <span className="small muted">{humanise(d.type)}</span>
              </label>
            ))}
          </fieldset>
          <h3>Review</h3>
          <dl className="details">
            <div className="details-row"><dt>Service</dt><dd>{service?.name ?? form.serviceCode} · {humanise(form.urgency)}</dd></div>
            <div className="details-row"><dt>Provider</dt><dd>{form.providerCode}</dd></div>
            <div className="details-row"><dt>Data set</dt><dd>{Object.keys(cleanDataSet(service, form.dataSet)).length} of {service?.requiredFields.length ?? 0} fields</dd></div>
            <div className="details-row"><dt>Consent</dt><dd>{form.consentId || '–'}</dd></div>
            <div className="details-row"><dt>Channel</dt><dd>{humanise(form.channel)}</dd></div>
            <div className="details-row"><dt>Attachments</dt><dd>{form.attachDocumentIds.length}</dd></div>
          </dl>
          <Alert kind="info">Sending generates and signs the referral letter, shares it with the provider and starts the report-due clock.</Alert>
        </div>
      )}

      <div className="actions">
        {idx > 0 && <button type="button" className="secondary" onClick={() => go(WIZARD_STEPS[idx - 1]!)}>Back</button>}
        {idx < WIZARD_STEPS.length - 1 && <button type="button" onClick={() => go(WIZARD_STEPS[idx + 1]!)}>Next</button>}
        {step === 'attachments' && (
          <>
            <button type="button" disabled={action.busy} onClick={() => void save(true)}>Save & send</button>
            <button type="button" className="secondary" disabled={action.busy} onClick={() => void save(false)}>Save draft</button>
          </>
        )}
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} />
    </section>
  );
}
