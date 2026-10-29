import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { PREAUTH_DECISIONS, treatmentApi, type Invoice, type Preauth, type PreauthDecision } from '../../api/treatment';
import { can, hasAnyRole, OH_ROLES } from '../../auth/roles';
import { ConsentPicker } from '../../components/ConsentPicker';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { slaTone } from '../../domain/absence';
import { asMoney, CURRENCIES, describeServerVariance, formatMoney, invoiceVariance, parseAmount } from '../../domain/money';
import { problemCode } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { CaseTabProps } from '../case/caseTabProps';
import type { ScreenProps } from '../types';

interface PanelProps extends ScreenProps {
  caseId: string;
}

const today = () => new Date().toISOString().slice(0, 10);

function CurrencySelect({ id, value, onChange }: { id: string; value: string; onChange: (v: string) => void }) {
  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
    </select>
  );
}

function PreauthForm({ props, onDone }: { props: PanelProps; onDone: () => void }) {
  const { client, session, onSessionExpired, caseId } = props;
  const api = treatmentApi(client);
  const oh = hasAnyRole(session.roles, OH_ROLES);
  const services = useAsync(() => api.services(), onSessionExpired);
  const referrals = useAsync(() => (oh ? api.caseReferrals(caseId) : Promise.resolve([])), onSessionExpired);
  const consents = useAsync(() => api.caseConsents(caseId, 'INSURER_SHARING'), onSessionExpired, [caseId]);
  const [f, setF] = useState({ payerCode: '', serviceCode: '', providerCode: '', referralId: '', amount: '', currency: 'GBP', justification: '', consentId: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const err = (k: string) => errorFor(errors, k);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const amount = parseAmount(f.amount);
    const errs: FieldError[] = [];
    if (!f.serviceCode) errs.push({ field: 'pa-service', message: 'Choose the service' });
    if (!f.providerCode.trim()) errs.push({ field: 'pa-provider', message: 'Enter the provider code' });
    if (!amount) errs.push({ field: 'pa-amount', message: 'Estimate: enter an amount such as 450.00' });
    if (oh && !f.justification.trim()) errs.push({ field: 'pa-justification', message: 'Clinical justification is required' });
    if (!f.consentId) errs.push({ field: 'pa-consent', message: "Choose the employee's insurer-sharing consent" });
    setErrors(errs);
    if (errs.length || !amount) return;
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        const p = await api.createPreauth(caseId, {
          ...(f.payerCode.trim() ? { payerCode: f.payerCode.trim().toUpperCase() } : {}),
          serviceCode: f.serviceCode,
          providerCode: f.providerCode.trim().toUpperCase(),
          ...(f.referralId ? { referralId: f.referralId } : {}),
          estimate: { amount, currency: f.currency },
          ...(oh && f.justification.trim() ? { justification: f.justification.trim() } : {}),
          consentId: f.consentId,
        });
        return `Pre-authorisation ${p.ref} created as draft.`;
      } catch (ex) {
        failed = ex;
        throw ex;
      }
    });
    if (ok) onDone();
    else if (problemCode(failed) === 'consent_required') {
      consents.reload();
      setF((cur) => ({ ...cur, consentId: '' }));
      setErrors([{ field: 'pa-consent', message: 'The chosen insurer-sharing consent is no longer active – choose another or ask the employee to grant it in the employee app' }]);
      action.reset();
    }
  }

  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="New pre-authorisation">
      <h3>New pre-authorisation</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="pa-payer" label="Payer code" hint="Defaults to the claim's payer when empty.">
          {(db) => <input id="pa-payer" aria-describedby={db} value={f.payerCode} onChange={(e) => setF({ ...f, payerCode: e.target.value })} />}
        </IdField>
        <IdField id="pa-service" label="Service" required error={err('pa-service')}>
          {(db) => (
            <select id="pa-service" aria-describedby={db} aria-invalid={!!err('pa-service')} value={f.serviceCode} onChange={(e) => setF({ ...f, serviceCode: e.target.value })}>
              <option value="">Choose…</option>
              {(services.data ?? []).map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
            </select>
          )}
        </IdField>
        <IdField id="pa-provider" label="Provider code" required error={err('pa-provider')}>
          {(db) => <input id="pa-provider" aria-describedby={db} aria-invalid={!!err('pa-provider')} value={f.providerCode} onChange={(e) => setF({ ...f, providerCode: e.target.value })} />}
        </IdField>
        {oh && (referrals.data ?? []).length > 0 && (
          <IdField id="pa-referral" label="Referral">
            {(db) => (
              <select id="pa-referral" aria-describedby={db} value={f.referralId} onChange={(e) => {
                const r = referrals.data?.find((x) => x.id === e.target.value);
                setF({ ...f, referralId: e.target.value, ...(r ? { serviceCode: r.serviceCode, providerCode: r.providerCode } : {}) });
              }}>
                <option value="">None</option>
                {(referrals.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.ref} – {r.serviceName}</option>)}
              </select>
            )}
          </IdField>
        )}
        <IdField id="pa-amount" label="Estimate" required error={err('pa-amount')}>
          {(db) => <input id="pa-amount" inputMode="decimal" aria-describedby={db} aria-invalid={!!err('pa-amount')} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}
        </IdField>
        <Field label="Currency">{(id) => <CurrencySelect id={id} value={f.currency} onChange={(v) => setF({ ...f, currency: v })} />}</Field>
      </div>
      {oh && (
        <IdField id="pa-justification" label="Clinical justification" required error={err('pa-justification')} hint="Clinical – visible to OH only; reaches the insurer only inside the consented request document.">
          {(db) => <textarea id="pa-justification" rows={3} maxLength={4000} aria-describedby={db} aria-invalid={!!err('pa-justification')} value={f.justification} onChange={(e) => setF({ ...f, justification: e.target.value })} />}
        </IdField>
      )}
      <ConsentPicker id="pa-consent" legend="Insurer-sharing consent" consents={consents} value={f.consentId} onChange={(consentId) => setF({ ...f, consentId })} error={err('pa-consent')} />
      <div className="actions">
        <button type="submit" disabled={action.busy}>Create draft</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** Decision dialog for claims coordinators (approve / partially approve / decline). */
export function PreauthDecisionDialog({ preauth, props, onClose, onDone }: { preauth: Preauth; props: ScreenProps; onClose: () => void; onDone: () => void }) {
  const api = treatmentApi(props.client);
  const action = useAction(props.onSessionExpired);
  const [f, setF] = useState({ status: 'APPROVED' as PreauthDecision['status'], approvalRef: '', approvedAmount: preauth.estimate.amount, validUntil: '', note: '' });
  const [error, setError] = useState<string>();

  async function submit(e: FormEvent) {
    e.preventDefault();
    const amount = f.status === 'DECLINED' ? undefined : parseAmount(f.approvedAmount);
    if (f.status !== 'DECLINED' && !amount) {
      setError('Approved amount: enter an amount such as 450.00');
      return;
    }
    setError(undefined);
    const body: PreauthDecision = {
      status: f.status,
      ...(f.approvalRef.trim() ? { approvalRef: f.approvalRef.trim() } : {}),
      ...(amount ? { approvedAmount: { amount, currency: preauth.estimate.currency } } : {}),
      ...(f.validUntil ? { validUntil: f.validUntil } : {}),
      ...(f.note.trim() ? { note: f.note.trim() } : {}),
    };
    if (await action.run(async () => { await api.decidePreauth(preauth.id, body); return `${preauth.ref}: ${humanise(f.status).toLowerCase()}.`; })) onDone();
  }

  return (
    <Dialog title={`Decision – ${preauth.ref}`} onClose={onClose}>
      <p>Estimate {formatMoney(preauth.estimate)} · {preauth.serviceCode} at {preauth.providerCode}</p>
      <form className="form" onSubmit={submit}>
        <fieldset>
          <legend>Decision</legend>
          {PREAUTH_DECISIONS.map((s) => (
            <label key={s} className="inline-row"><input type="radio" name="pa-decision" checked={f.status === s} onChange={() => setF({ ...f, status: s })} /> {humanise(s)}</label>
          ))}
        </fieldset>
        <Field label="Approval reference">{(id) => <input id={id} value={f.approvalRef} onChange={(e) => setF({ ...f, approvalRef: e.target.value })} />}</Field>
        {f.status !== 'DECLINED' && (
          <IdField id="pa-approved" label={`Approved amount (${preauth.estimate.currency})`} error={error}>
            {(db) => <input id="pa-approved" inputMode="decimal" aria-describedby={db} aria-invalid={!!error} value={f.approvedAmount} onChange={(e) => setF({ ...f, approvedAmount: e.target.value })} />}
          </IdField>
        )}
        <Field label="Valid until">{(id) => <input id={id} type="date" value={f.validUntil} onChange={(e) => setF({ ...f, validUntil: e.target.value })} />}</Field>
        <Field label="Note">{(id) => <textarea id={id} rows={2} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />}</Field>
        <div className="actions">
          <button type="submit" disabled={action.busy}>Record decision</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={action.error} />
    </Dialog>
  );
}

function InvoiceForm({ props, preauths, onDone }: { props: PanelProps; preauths: Preauth[]; onDone: () => void }) {
  const api = treatmentApi(props.client);
  const [f, setF] = useState({ providerCode: '', invoiceNumber: '', amount: '', currency: 'GBP', serviceDate: today(), preauthId: '', documentId: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(props.onSessionExpired);
  const err = (k: string) => errorFor(errors, k);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const amount = parseAmount(f.amount);
    const errs: FieldError[] = [];
    if (!f.providerCode.trim()) errs.push({ field: 'inv-provider', message: 'Enter the provider code' });
    if (!f.invoiceNumber.trim()) errs.push({ field: 'inv-number', message: 'Enter the invoice number' });
    if (!amount) errs.push({ field: 'inv-amount', message: 'Amount: enter an amount such as 120.00' });
    if (!f.serviceDate) errs.push({ field: 'inv-date', message: 'Enter the service date' });
    setErrors(errs);
    if (errs.length || !amount) return;
    if (
      await action.run(async () => {
        const inv = await api.createInvoice(props.caseId, {
          providerCode: f.providerCode.trim().toUpperCase(),
          invoiceNumber: f.invoiceNumber.trim(),
          amount,
          currency: f.currency,
          serviceDate: f.serviceDate,
          ...(f.preauthId ? { preauthId: f.preauthId } : {}),
          ...(f.documentId.trim() ? { documentId: f.documentId.trim() } : {}),
        });
        return `Invoice ${inv.invoiceNumber} recorded – ${humanise(inv.status).toLowerCase()}.`;
      })
    )
      onDone();
  }

  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="Record invoice">
      <h3>Record provider invoice</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="inv-provider" label="Provider code" required error={err('inv-provider')}>
          {(db) => <input id="inv-provider" aria-describedby={db} aria-invalid={!!err('inv-provider')} value={f.providerCode} onChange={(e) => setF({ ...f, providerCode: e.target.value })} />}
        </IdField>
        <IdField id="inv-number" label="Invoice number" required error={err('inv-number')}>
          {(db) => <input id="inv-number" aria-describedby={db} aria-invalid={!!err('inv-number')} value={f.invoiceNumber} onChange={(e) => setF({ ...f, invoiceNumber: e.target.value })} />}
        </IdField>
        <IdField id="inv-amount" label="Amount" required error={err('inv-amount')}>
          {(db) => <input id="inv-amount" inputMode="decimal" aria-describedby={db} aria-invalid={!!err('inv-amount')} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}
        </IdField>
        <Field label="Currency">{(id) => <CurrencySelect id={id} value={f.currency} onChange={(v) => setF({ ...f, currency: v })} />}</Field>
        <IdField id="inv-date" label="Service date" required error={err('inv-date')}>
          {(db) => <input id="inv-date" type="date" aria-describedby={db} value={f.serviceDate} onChange={(e) => setF({ ...f, serviceDate: e.target.value })} />}
        </IdField>
        <Field label="Pre-authorisation" hint="Matched automatically when left empty.">
          {(id) => (
            <select id={id} value={f.preauthId} onChange={(e) => {
              const p = preauths.find((x) => x.id === e.target.value);
              setF({ ...f, preauthId: e.target.value, ...(p ? { providerCode: p.providerCode, currency: p.estimate.currency } : {}) });
            }}>
              <option value="">Auto-match</option>
              {preauths.map((p) => <option key={p.id} value={p.id}>{p.ref} – {humanise(p.status)}</option>)}
            </select>
          )}
        </Field>
        <Field label="Invoice document id">{(id) => <input id={id} value={f.documentId} onChange={(e) => setF({ ...f, documentId: e.target.value })} />}</Field>
      </div>
      <div className="actions">
        <button type="submit" disabled={action.busy}>Record invoice</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** Invoice vs approval: server variance if given, else computed against the matched pre-authorisation. */
export function InvoiceVarianceCell({ inv, preauths }: { inv: Invoice; preauths: Preauth[] }) {
  const p = preauths.find((x) => x.id === (inv.matchedPreauthId ?? inv.preauthId));
  const amount = asMoney(inv.amount, inv.currency);
  const computed = p ? invoiceVariance(amount, p.approvedAmount ?? p.estimate) : undefined;
  if (computed) return <ToneBadge tone={computed.tone}>{computed.label}</ToneBadge>;
  const server = describeServerVariance(inv.variance, amount?.currency);
  return server ? <span>{server}</span> : <span className="muted">–</span>;
}

const INVOICE_TONE: Record<string, 'red' | 'amber' | 'green' | 'muted'> = { RECEIVED: 'amber', MATCHED: 'green', DISPUTED: 'red', APPROVED: 'green', PAID: 'muted' };

/** Pre-authorisations and provider invoices of one case (case tab for OH, claim costs tab for claims coordinators). */
export function CaseCostsPanel(props: PanelProps) {
  const { client, session, onSessionExpired, caseId } = props;
  const api = treatmentApi(client);
  const preauths = useAsync(() => api.casePreauths(caseId), onSessionExpired, [caseId]);
  const invoices = useAsync(() => api.caseInvoices(caseId), onSessionExpired, [caseId]);
  const action = useAction(onSessionExpired);
  const [form, setForm] = useState<'preauth' | 'invoice'>();
  const [deciding, setDeciding] = useState<Preauth>();
  const canCreate = can(session.roles, 'preauthCreate');
  const canDecide = can(session.roles, 'preauthDecide');
  const canInvoice = can(session.roles, 'invoiceWrite');
  const pas = preauths.data ?? [];
  const invs = invoices.data ?? [];
  const reload = () => {
    preauths.reload();
    invoices.reload();
  };

  return (
    <div>
      <h3>Pre-authorisations</h3>
      {canCreate && form !== 'preauth' && <button type="button" className="secondary" onClick={() => setForm('preauth')}>New pre-authorisation</button>}
      {form === 'preauth' && <PreauthForm props={props} onDone={() => { setForm(undefined); reload(); }} />}
      <ActionFeedback error={action.error} message={action.message} />
      {preauths.loading && !preauths.data && <Loading />}
      {!!preauths.error && <ErrorAlert error={preauths.error} />}
      {preauths.data && pas.length === 0 && <Empty>No pre-authorisations.</Empty>}
      {pas.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Pre-authorisations</caption>
            <thead>
              <tr><th scope="col">Ref</th><th scope="col">Service / provider</th><th scope="col">Payer</th><th scope="col" className="num">Estimate</th><th scope="col">Status</th><th scope="col">Insurer SLA</th><th scope="col" className="num">Approved</th><th scope="col">Valid until</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {pas.map((p) => {
                const sla = p.status === 'SUBMITTED' ? slaTone(p.slaDueAt) : undefined;
                return (
                  <tr key={p.id}>
                    <th scope="row">{p.ref}{p.documentId && <> <a className="small" href={documentContentUrl(p.documentId)} target="_blank" rel="noopener">request</a></>}</th>
                    <td>{p.serviceName ?? p.serviceCode} · {p.providerName ?? p.providerCode}</td>
                    <td>{p.payerName ?? p.payerCode ?? '–'}</td>
                    <td className="num">{formatMoney(p.estimate)}</td>
                    <td><StatusBadge status={p.status} /></td>
                    <td>{sla ? <ToneBadge tone={sla.tone}>{sla.label}</ToneBadge> : p.decidedAt ? <span className="small">decided {formatDate(p.decidedAt)}</span> : '–'}</td>
                    <td className="num">{formatMoney(p.approvedAmount)}{p.approvalRef && <span className="small muted"> {p.approvalRef}</span>}</td>
                    <td>{formatDay(p.validUntil)}</td>
                    <td>
                      <div className="card-actions">
                        {canCreate && p.status === 'DRAFT' && (
                          <button type="button" className="small-button" disabled={action.busy} onClick={() => void action.run(async () => { await api.submitPreauth(p.id); reload(); return `${p.ref} submitted to the payer.`; })}>Submit</button>
                        )}
                        {canDecide && p.status === 'SUBMITTED' && (
                          <button type="button" className="secondary small-button" onClick={() => setDeciding(p)} aria-label={`Record decision for ${p.ref}`}>Decision</button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <h3>Provider invoices</h3>
      {canInvoice && form !== 'invoice' && <button type="button" className="secondary" onClick={() => setForm('invoice')}>Record invoice</button>}
      {form === 'invoice' && <InvoiceForm props={props} preauths={pas} onDone={() => { setForm(undefined); reload(); }} />}
      {invoices.loading && !invoices.data && <Loading />}
      {!!invoices.error && <ErrorAlert error={invoices.error} />}
      {invoices.data && invs.length === 0 && <Empty>No invoices.</Empty>}
      {invs.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Invoices</caption>
            <thead>
              <tr><th scope="col">Invoice</th><th scope="col">Provider</th><th scope="col">Service date</th><th scope="col" className="num">Amount</th><th scope="col">Matching</th><th scope="col">Variance</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {invs.map((inv) => (
                <tr key={inv.id}>
                  <th scope="row">{inv.invoiceNumber}{inv.documentId && <> <a className="small" href={documentContentUrl(inv.documentId)} target="_blank" rel="noopener">file</a></>}</th>
                  <td>{inv.providerName ?? inv.providerCode}</td>
                  <td>{formatDay(inv.serviceDate)}</td>
                  <td className="num">{formatMoney(inv.amount, inv.currency)}</td>
                  <td>
                    <ToneBadge tone={INVOICE_TONE[inv.status] ?? 'muted'}>{humanise(inv.status)}</ToneBadge>
                    {inv.matchedPreauthId && <span className="small muted"> {pas.find((p) => p.id === inv.matchedPreauthId)?.ref ?? ''}</span>}
                  </td>
                  <td><InvoiceVarianceCell inv={inv} preauths={pas} /></td>
                  <td>
                    {canInvoice && inv.status !== 'PAID' && (
                      <div className="card-actions">
                        {(['APPROVED', 'DISPUTED', 'PAID'] as const)
                          .filter((s) => s !== inv.status && !(s === 'PAID' && inv.status !== 'APPROVED'))
                          .map((s) => (
                            <button key={s} type="button" className="secondary small-button" disabled={action.busy} aria-label={`${s === 'APPROVED' ? 'Approve' : s === 'DISPUTED' ? 'Dispute' : 'Mark paid'} invoice ${inv.invoiceNumber}`} onClick={() => void action.run(async () => { await api.invoiceStatus(inv.id, s); reload(); return `Invoice ${inv.invoiceNumber}: ${humanise(s).toLowerCase()}.`; })}>
                              {s === 'APPROVED' ? 'Approve' : s === 'DISPUTED' ? 'Dispute' : 'Mark paid'}
                            </button>
                          ))}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {deciding && <PreauthDecisionDialog preauth={deciding} props={props} onClose={() => setDeciding(undefined)} onDone={() => { setDeciding(undefined); reload(); }} />}
    </div>
  );
}

/** `#/cases/{id}/costs` (E04-F04). */
export function CostsTab(props: CaseTabProps) {
  return (
    <div>
      <h2 className="visually-hidden">Costs</h2>
      <p className="small muted">Pre-authorisation requests go to the payer under the employee&apos;s insurer-sharing consent. Invoices are matched against the approved amount (5 % tolerance).</p>
      <CaseCostsPanel client={props.client} session={props.session} onSessionExpired={props.onSessionExpired} caseId={props.caseId} />
    </div>
  );
}
