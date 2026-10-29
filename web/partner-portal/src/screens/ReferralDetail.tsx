import { useState, type FormEvent, type ReactNode } from 'react';
import type { AuthClient } from '../auth/AuthClient';
import { documentContentUrl, documentFileName } from '../api/documents';
import { describeError, type ActionResult, type FieldErrors } from '../api/errors';
import {
  acceptReferral,
  APPOINTMENT_FIELDS,
  buildAppointment,
  buildInvoice,
  declineReferral,
  DECLINE_REASON_MAX,
  INSTRUCTIONS_MAX,
  INVOICE_NUMBER_MAX,
  invoiceForm,
  LOCATION_MAX,
  loadReferral,
  markSeen,
  referralActions,
  referralStatusLabel,
  reportForm,
  setAppointment,
  SUMMARY_MAX,
  uploadInvoice,
  uploadReport,
  validateDecline,
  validateReport,
  type PartnerReferral,
} from '../api/referrals';
import { UPLOAD_ACCEPT } from '../api/upload';
import { focusFirstError } from '../components/focus';
import { CURRENCIES, formatDateTime, humanise, idPart, isoToLocalInput, localDate } from '../components/format';
import { FileField, InputField, SelectField, TextAreaField } from '../components/FormFields';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { ReportDue, UrgencyBadge } from './Referrals';
import { orgLabel, type ScreenProps } from './types';

/** Hook-free read-only part of the referral (patient, request, data set, documents, history). */
export function ReferralSummary({ referral: r, now }: { referral: PartnerReferral; now: Date }) {
  const docs = [
    ...(r.letterDocumentId ? [{ documentId: r.letterDocumentId, title: S.referral.letter }] : []),
    ...r.attachments.filter((a) => a.documentId !== r.letterDocumentId),
  ];
  return (
    <>
      <dl className="details">
        <dt>{S.referral.patient}</dt>
        <dd>{r.patient.displayName}</dd>
        {r.patient.employeeId && (
          <>
            <dt>{S.referral.employeeId}</dt>
            <dd>{r.patient.employeeId}</dd>
          </>
        )}
        {r.patient.phone && (
          <>
            <dt>{S.referral.phone}</dt>
            <dd>
              <a href={`tel:${r.patient.phone.replace(/[^+\d]/g, '')}`}>{r.patient.phone}</a>
            </dd>
          </>
        )}
        <dt>{S.referral.service}</dt>
        <dd>{r.serviceName}</dd>
        <dt>{S.referral.urgency}</dt>
        <dd>
          <UrgencyBadge urgency={r.urgency} />
        </dd>
        <dt>{S.referral.status}</dt>
        <dd>
          <StatusBadge status={r.status} label={referralStatusLabel(r.status)} />
        </dd>
        <dt>{S.referral.sentAt}</dt>
        <dd>{formatDateTime(r.sentAt)}</dd>
        <dt>{S.referral.appointment}</dt>
        <dd>{formatDateTime(r.appointmentAt)}</dd>
        <dt>{S.referral.reportDue}</dt>
        <dd>
          <ReportDue referral={r} now={now} />
        </dd>
      </dl>

      <h2>{S.referral.reason}</h2>
      <p className="quote">{r.reason ?? S.app.noValue}</p>
      <h2>{S.referral.question}</h2>
      <p className="quote">{r.question ?? S.app.noValue}</p>

      <h2>{S.referral.dataSet}</h2>
      {r.dataSet.length === 0 ? (
        <p className="muted">{S.referral.noDataSet}</p>
      ) : (
        <dl className="details">
          {r.dataSet.map((e) => (
            <div key={e.key} className="contents">
              <dt>{humanise(e.key.replace(/([a-z])([A-Z])/g, '$1_$2'))}</dt>
              <dd>{e.value}</dd>
            </div>
          ))}
        </dl>
      )}

      <h2>{S.referral.documents}</h2>
      {docs.length === 0 ? (
        <p className="muted">{S.referral.noDocuments}</p>
      ) : (
        <ul className="plain-list">
          {docs.map((d) => (
            <li key={d.documentId}>
              <a href={documentContentUrl(d.documentId)} download={documentFileName({ id: d.documentId })} aria-label={S.referral.download(d.title)}>
                {d.title}
              </a>
            </li>
          ))}
        </ul>
      )}

      <h2>{S.referral.history}</h2>
      {r.history.length === 0 ? (
        <p className="muted">{S.referral.noHistory}</p>
      ) : (
        <ol className="timeline">
          {r.history.map((h, i) => (
            <li key={`${h.at}-${i}`}>
              <time dateTime={h.at}>{formatDateTime(h.at)}</time> – {referralStatusLabel(h.status)}
              {h.by ? <span className="muted small"> ({h.by})</span> : null}
            </li>
          ))}
        </ol>
      )}
    </>
  );
}

type Done = (message: string) => void;
interface ActionProps {
  client: Pick<AuthClient, 'api'>;
  referral: PartnerReferral;
  onDone: Done;
  onSessionExpired: () => void;
}

/** Shared submit state for one action form. */
function useAction(onSessionExpired: () => void) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();
  const [errors, setErrors] = useState<FieldErrors>({});
  async function run(fieldIds: Record<string, string>, call: () => Promise<ActionResult>, onOk: () => void) {
    setBusy(true);
    setMessage(undefined);
    const res = await call();
    setBusy(false);
    if (res.ok) {
      setErrors({});
      onOk();
      return;
    }
    if (res.sessionExpired) onSessionExpired();
    setMessage(res.message);
    setErrors(res.fieldErrors);
    focusFirstError(Object.keys(res.fieldErrors).map((k) => fieldIds[k] ?? ''));
  }
  function invalid(fieldIds: Record<string, string>, errs: FieldErrors) {
    setErrors(errs);
    setMessage(undefined);
    focusFirstError(Object.keys(errs).map((k) => fieldIds[k] ?? ''));
  }
  return { busy, message, errors, run, invalid };
}

function ActionCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card action-card">
      <h3>{title}</h3>
      {children}
    </section>
  );
}

function SimpleActions({ client, referral, onDone, onSessionExpired }: ActionProps) {
  const acts = referralActions(referral.status);
  const a = useAction(onSessionExpired);
  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState('');
  const p = `rf-${idPart(referral.id)}`;
  const ids = { reason: `${p}-decline-reason` };
  if (!acts.accept && !acts.decline && !acts.seen) return null;

  const decline = (e: FormEvent) => {
    e.preventDefault();
    const errs = validateDecline(reason);
    if (Object.keys(errs).length) return a.invalid(ids, errs);
    void a.run(ids, () => declineReferral(client, referral.id, reason.trim(), S.referral.failedDecline), () => onDone(S.referral.declined));
  };

  return (
    <ActionCard title={S.referral.actions}>
      {a.message && <Alert kind="error">{a.message}</Alert>}
      <div className="actions">
        {acts.accept && (
          <button type="button" disabled={a.busy} onClick={() => void a.run({}, () => acceptReferral(client, referral.id, S.referral.failedAccept), () => onDone(S.referral.accepted))}>
            {S.referral.accept}
          </button>
        )}
        {acts.seen && (
          <button type="button" disabled={a.busy} onClick={() => void a.run({}, () => markSeen(client, referral.id, S.referral.failedSeen), () => onDone(S.referral.seenDone))}>
            {S.referral.seen}
          </button>
        )}
        {acts.decline && !declining && (
          <button type="button" className="secondary" aria-expanded={false} onClick={() => setDeclining(true)}>
            {S.referral.decline}
          </button>
        )}
      </div>
      {acts.decline && declining && (
        <form className="form" onSubmit={decline} noValidate aria-label={S.referral.decline}>
          <TextAreaField id={ids.reason} label={S.referral.declineReason} value={reason} onChange={setReason} max={DECLINE_REASON_MAX} required hint={S.referral.declineHint} error={a.errors.reason} />
          <div className="actions">
            <button type="submit" disabled={a.busy}>
              {a.busy ? S.referral.busy : S.referral.declineConfirm}
            </button>
            <button type="button" className="secondary" onClick={() => setDeclining(false)}>
              {S.app.cancel}
            </button>
          </div>
        </form>
      )}
    </ActionCard>
  );
}

function AppointmentAction({ client, referral, onDone, onSessionExpired }: ActionProps) {
  const a = useAction(onSessionExpired);
  const [f, setF] = useState({ appointmentAt: isoToLocalInput(referral.appointmentAt), location: '', instructions: '' });
  const p = `rf-${idPart(referral.id)}-appt`;
  const ids = Object.fromEntries(APPOINTMENT_FIELDS.map((k) => [k, `${p}-${k}`]));
  const submitForm = (e: FormEvent) => {
    e.preventDefault();
    const { body, errors } = buildAppointment(f);
    if (!body) return a.invalid(ids, errors);
    void a.run(ids, () => setAppointment(client, referral.id, body, S.referral.failedAppointment), () => onDone(S.referral.appointmentSaved));
  };
  return (
    <ActionCard title={S.referral.appointmentTitle}>
      <form className="form" onSubmit={submitForm} noValidate>
        {a.message && <Alert kind="error">{a.message}</Alert>}
        <InputField id={ids.appointmentAt!} type="datetime-local" label={S.referral.appointmentAt} value={f.appointmentAt} onChange={(v) => setF({ ...f, appointmentAt: v })} required hint={S.referral.appointmentHint} error={a.errors.appointmentAt} />
        <InputField id={ids.location!} label={S.referral.location} value={f.location} onChange={(v) => setF({ ...f, location: v })} maxLength={LOCATION_MAX} error={a.errors.location} />
        <TextAreaField id={ids.instructions!} label={S.referral.instructions} value={f.instructions} onChange={(v) => setF({ ...f, instructions: v })} max={INSTRUCTIONS_MAX} hint={S.referral.instructionsHint} error={a.errors.instructions} />
        <button type="submit" disabled={a.busy}>
          {a.busy ? S.referral.busy : S.referral.saveAppointment}
        </button>
      </form>
    </ActionCard>
  );
}

function ReportAction({ client, referral, onDone, onSessionExpired }: ActionProps) {
  const a = useAction(onSessionExpired);
  const [file, setFile] = useState<File>();
  const [summary, setSummary] = useState('');
  const p = `rf-${idPart(referral.id)}-report`;
  const ids = { file: `${p}-file`, summary: `${p}-summary` };
  const submitForm = (e: FormEvent) => {
    e.preventDefault();
    const errs = validateReport(file, summary);
    if (Object.keys(errs).length || !file) return a.invalid(ids, errs);
    void a.run(ids, () => uploadReport(client, referral.id, reportForm(file, summary), S.referral.failedReport), () => onDone(S.referral.reportUploaded));
  };
  return (
    <ActionCard title={S.referral.reportTitle}>
      <form className="form" onSubmit={submitForm} noValidate>
        {a.message && <Alert kind="error">{a.message}</Alert>}
        <FileField id={ids.file} label={S.referral.reportFile} accept={UPLOAD_ACCEPT} onChange={setFile} required hint={S.referral.fileHint} error={a.errors.file} />
        <TextAreaField id={ids.summary} label={S.referral.summary} value={summary} onChange={setSummary} max={SUMMARY_MAX} hint={S.referral.summaryHint} error={a.errors.summary} />
        <button type="submit" disabled={a.busy}>
          {a.busy ? S.referral.busy : S.referral.uploadReport}
        </button>
      </form>
    </ActionCard>
  );
}

function InvoiceAction({ client, referral, onDone, onSessionExpired }: ActionProps) {
  const a = useAction(onSessionExpired);
  const [file, setFile] = useState<File>();
  const [f, setF] = useState({ invoiceNumber: '', amount: '', currency: 'GBP', serviceDate: '' });
  const p = `rf-${idPart(referral.id)}-invoice`;
  const ids = { file: `${p}-file`, invoiceNumber: `${p}-number`, amount: `${p}-amount`, currency: `${p}-currency`, serviceDate: `${p}-date` };
  const submitForm = (e: FormEvent) => {
    e.preventDefault();
    const { request, errors } = buildInvoice(file, f);
    if (!request || !file) return a.invalid(ids, errors);
    void a.run(ids, () => uploadInvoice(client, referral.id, invoiceForm(file, request), S.referral.failedInvoice), () => onDone(S.referral.invoiceUploaded));
  };
  return (
    <ActionCard title={S.referral.invoiceTitle}>
      <form className="form" onSubmit={submitForm} noValidate>
        {a.message && <Alert kind="error">{a.message}</Alert>}
        <FileField id={ids.file} label={S.referral.invoiceFile} accept={UPLOAD_ACCEPT} onChange={setFile} required hint={S.referral.fileHint} error={a.errors.file} />
        <InputField id={ids.invoiceNumber} label={S.referral.invoiceNumber} value={f.invoiceNumber} onChange={(v) => setF({ ...f, invoiceNumber: v })} required maxLength={INVOICE_NUMBER_MAX} error={a.errors.invoiceNumber} />
        <div className="field-row">
          <InputField id={ids.amount} label={S.referral.amount} value={f.amount} onChange={(v) => setF({ ...f, amount: v })} required inputMode="decimal" error={a.errors.amount} />
          <SelectField id={ids.currency} label={S.referral.currency} value={f.currency} onChange={(v) => setF({ ...f, currency: v })} options={CURRENCIES.map((c) => ({ value: c, label: c }))} required error={a.errors.currency} />
        </div>
        <InputField id={ids.serviceDate} type="date" label={S.referral.serviceDate} value={f.serviceDate} onChange={(v) => setF({ ...f, serviceDate: v })} required max={localDate()} error={a.errors.serviceDate} />
        <button type="submit" disabled={a.busy}>
          {a.busy ? S.referral.busy : S.referral.uploadInvoice}
        </button>
      </form>
    </ActionCard>
  );
}

export function ReferralDetail({ client, me, referralId, onSessionExpired }: ScreenProps & { referralId: string }) {
  const data = useAsync(() => loadReferral(client, referralId), onSessionExpired);
  const [success, setSuccess] = useState<string>();
  const [rev, setRev] = useState(0);
  const onDone = (msg: string) => {
    setSuccess(msg);
    setRev((n) => n + 1);
    data.reload();
  };
  const r = data.data;
  const acts = r ? referralActions(r.status) : undefined;
  const actionProps = r ? { client, referral: r, onDone, onSessionExpired } : undefined;
  return (
    <section>
      <p>
        <a href="#/referrals">← {S.referral.back}</a>
      </p>
      <PageTitle>{S.referral.title(r?.ref ?? '')}</PageTitle>
      <ConfidentialityNote>{S.confidentiality.provider(orgLabel(me))}</ConfidentialityNote>
      {success && <Alert kind="success">{success}</Alert>}
      {data.loading && !r && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {!data.loading && !data.error && !r && <Alert kind="error">{S.errors.notFound}</Alert>}
      {r && actionProps && acts && (
        <div className="detail-layout">
          <div>
            <ReferralSummary referral={r} now={new Date()} />
          </div>
          <div className="action-column">
            <h2 className="visually-hidden">{S.referral.actions}</h2>
            <p className="muted small">{S.confidentiality.noClinicalText}</p>
            <SimpleActions key={`s-${rev}`} {...actionProps} />
            {acts.appointment && <AppointmentAction key={`a-${rev}`} {...actionProps} />}
            {acts.report && <ReportAction key={`r-${rev}`} {...actionProps} />}
            {acts.invoice && <InvoiceAction key={`i-${rev}`} {...actionProps} />}
            {!acts.accept && !acts.decline && !acts.seen && !acts.appointment && !acts.report && !acts.invoice && <p className="muted">{S.referral.noActions}</p>}
          </div>
        </div>
      )}
    </section>
  );
}
