import { useState, type FormEvent } from 'react';
import {
  claimsApi,
  COST_CATEGORIES,
  COST_KINDS,
  SUBMISSION_CHANNELS,
  THIRD_PARTY_TYPES,
  type ClaimDetail as Claim,
  type ClaimStatus,
  type CostCategory,
  type DisclosureSet,
  type FirstReport,
  type ReportForm,
} from '../../api/claims';
import { documentContentUrl, platformApi } from '../../api/platform';
import { treatmentApi } from '../../api/treatment';
import { can, hasAnyRole, OH_ROLES } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise, localToIso, toLocalInput } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge, TabNav, type TabDef } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { clinicalContentField, isClinicalContentError, todayIso } from '../../domain/absence';
import { claimTone, deadlineCountdown, isDisclosable, nextClaimStatuses, parseRedactionPhrases, statusReasonRequired } from '../../domain/claims';
import { asMoney, CURRENCIES, formatMoney, parseAmount } from '../../domain/money';
import type { ScreenProps } from '../types';
import { CaseCostsPanel } from '../treatment/CostsTab';
import { ClaimCollaboration } from './Collaboration';
import { LegalRequestForm, LegalRequestList } from './LegalRequests';

interface TabProps extends ScreenProps {
  claim: Claim;
  reload: () => void;
}

// ---------------------------------------------------------------- overview
function StatusWorkflow({ claim, props, reload }: TabProps & { props: ScreenProps }) {
  const opts = { coordinator: can(props.session.roles, 'claimWrite'), legal: can(props.session.roles, 'legalWrite') };
  const next = nextClaimStatuses(claim.status, opts);
  const [target, setTarget] = useState<ClaimStatus>();
  const [reason, setReason] = useState('');
  const action = useAction(props.onSessionExpired);
  if (next.length === 0) return null;
  const needReason = target ? statusReasonRequired(claim.status, target) : false;
  return (
    <div>
      <div className="card-actions" role="group" aria-label="Change status">
        {next.map((s) => (
          <button key={s} type="button" className="secondary small-button" onClick={() => { setReason(''); action.reset(); setTarget(s); }}>
            {claim.status === 'CLOSED' && s === 'OPEN' ? 'Reopen' : `Move to ${humanise(s).toLowerCase()}`}
          </button>
        ))}
      </div>
      <ActionFeedback message={action.message} />
      {target && (
        <Dialog title={`${claim.claimRef}: ${humanise(claim.status)} → ${humanise(target)}`} onClose={() => setTarget(undefined)}>
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              if (needReason && !reason.trim()) return;
              void action.run(async () => { await claimsApi(props.client).setStatus(claim.id, target, reason.trim() || undefined); setTarget(undefined); reload(); return `Status changed to ${humanise(target).toLowerCase()}.`; });
            }}
          >
            <Field label="Reason" required={needReason} hint="No clinical details – the payer sees status changes.">
              {(id) => <textarea id={id} rows={2} required={needReason} value={reason} onChange={(e) => setReason(e.target.value)} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || (needReason && !reason.trim())}>Change status</button>
              <button type="button" className="secondary" onClick={() => setTarget(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </div>
  );
}

function EditClaim({ claim, props, onDone }: { claim: Claim; props: ScreenProps; onDone: () => void }) {
  const [f, setF] = useState({
    payerCode: claim.payerCode ?? '',
    policyNumber: claim.policyNumber ?? '',
    adjusterName: claim.adjuster?.name ?? '',
    adjusterEmail: claim.adjuster?.email ?? '',
    adjusterUsername: claim.adjuster?.username ?? '',
    lostTime: claim.lostTime,
    currency: asMoney(Object.values(claim.reserves ?? {})[0])?.currency ?? 'GBP',
    reserves: Object.fromEntries(COST_CATEGORIES.map((c) => [c, asMoney(claim.reserves?.[c])?.amount ?? ''])) as Record<CostCategory, string>,
  });
  const [error, setError] = useState<string>();
  const action = useAction(props.onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const reserves: Partial<Record<CostCategory, { amount: string; currency: string }>> = {};
    for (const c of COST_CATEGORIES) {
      const v = f.reserves[c].trim();
      if (!v) continue;
      const amount = parseAmount(v);
      if (!amount) return setError(`${humanise(c)} reserve: enter an amount such as 1500.00`);
      reserves[c] = { amount, currency: f.currency };
    }
    setError(undefined);
    const opt = (v: string) => (v.trim() ? v.trim() : undefined);
    const ok = await action.run(async () => {
      await claimsApi(props.client).updateClaim(claim.id, { payerCode: opt(f.payerCode), policyNumber: opt(f.policyNumber), adjusterName: opt(f.adjusterName), adjusterEmail: opt(f.adjusterEmail), adjusterUsername: opt(f.adjusterUsername), lostTime: f.lostTime, reserves });
      return 'Claim updated.';
    });
    if (ok) onDone();
  }
  return (
    <form className="form tile" onSubmit={submit} aria-label="Edit claim">
      <h3>Edit claim</h3>
      {error && <p className="field-error-text" role="alert">{error}</p>}
      <div className="inline-form">
        <Field label="Payer code">{(id) => <input id={id} value={f.payerCode} onChange={(e) => setF({ ...f, payerCode: e.target.value })} />}</Field>
        <Field label="Policy number">{(id) => <input id={id} value={f.policyNumber} onChange={(e) => setF({ ...f, policyNumber: e.target.value })} />}</Field>
        <Field label="Adjuster name">{(id) => <input id={id} value={f.adjusterName} onChange={(e) => setF({ ...f, adjusterName: e.target.value })} />}</Field>
        <Field label="Adjuster e-mail">{(id) => <input id={id} type="email" value={f.adjusterEmail} onChange={(e) => setF({ ...f, adjusterEmail: e.target.value })} />}</Field>
        <Field label="Adjuster username">{(id) => <input id={id} value={f.adjusterUsername} onChange={(e) => setF({ ...f, adjusterUsername: e.target.value })} />}</Field>
        <label className="inline-row"><input type="checkbox" checked={f.lostTime} onChange={(e) => setF({ ...f, lostTime: e.target.checked })} /> Lost time</label>
      </div>
      <fieldset>
        <legend>Reserves</legend>
        <div className="inline-form">
          <Field label="Currency">
            {(id) => (
              <select id={id} value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })}>
                {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            )}
          </Field>
          {COST_CATEGORIES.map((c) => (
            <Field key={c} label={humanise(c)}>{(id) => <input id={id} inputMode="decimal" value={f.reserves[c]} onChange={(e) => setF({ ...f, reserves: { ...f.reserves, [c]: e.target.value } })} />}</Field>
          ))}
        </div>
      </fieldset>
      <div className="actions">
        <button type="submit" disabled={action.busy}>Save</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

function SubrogationForm({ claim, props, onDone }: { claim: Claim; props: ScreenProps; onDone: () => void }) {
  const [f, setF] = useState({ thirdPartyName: '', thirdPartyType: 'CONTRACTOR', thirdPartyInsurer: '', reference: '', description: '' });
  const [descError, setDescError] = useState<string>();
  const action = useAction(props.onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!f.thirdPartyName.trim() || !f.description.trim()) return;
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await claimsApi(props.client).subrogation(claim.id, { thirdPartyName: f.thirdPartyName.trim(), thirdPartyType: f.thirdPartyType, ...(f.thirdPartyInsurer.trim() ? { thirdPartyInsurer: f.thirdPartyInsurer.trim() } : {}), ...(f.reference.trim() ? { reference: f.reference.trim() } : {}), description: f.description.trim() });
        return 'Subrogation flagged – legal has been notified.';
      } catch (ex) {
        failed = ex;
        throw ex;
      }
    });
    if (ok) onDone();
    else if (isClinicalContentError(failed)) {
      setDescError(`${humanise(clinicalContentField(failed) ?? 'description')}: describe the event and the third party only – no clinical details`);
      action.reset();
    }
  }
  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="Flag subrogation">
      <h3>Flag subrogation</h3>
      <div className="inline-form">
        <Field label="Third party" required>{(id) => <input id={id} required value={f.thirdPartyName} onChange={(e) => setF({ ...f, thirdPartyName: e.target.value })} />}</Field>
        <Field label="Type">
          {(id) => (
            <select id={id} value={f.thirdPartyType} onChange={(e) => setF({ ...f, thirdPartyType: e.target.value })}>
              <SelectOptions values={THIRD_PARTY_TYPES} />
            </select>
          )}
        </Field>
        <Field label="Third-party insurer">{(id) => <input id={id} value={f.thirdPartyInsurer} onChange={(e) => setF({ ...f, thirdPartyInsurer: e.target.value })} />}</Field>
        <Field label="Reference">{(id) => <input id={id} value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} />}</Field>
      </div>
      <IdField id={`sub-desc-${claim.id}`} label="Description" required error={descError}>
        {(db) => <textarea id={`sub-desc-${claim.id}`} rows={3} required aria-describedby={db} aria-invalid={!!descError} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />}
      </IdField>
      <div className="actions">
        <button type="submit" disabled={action.busy || !f.thirdPartyName.trim() || !f.description.trim()}>Flag subrogation</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ActionFeedback error={action.error} />
    </form>
  );
}

function OverviewTab({ claim, reload, ...props }: TabProps) {
  const [mode, setMode] = useState<'edit' | 'subrogation'>();
  const coordinator = can(props.session.roles, 'claimWrite');
  const money = can(props.session.roles, 'claimMoney');
  const done = () => {
    setMode(undefined);
    reload();
  };
  return (
    <div>
      <StatusWorkflow claim={claim} props={props} reload={reload} {...props} />
      {coordinator && !mode && (
        <div className="toolbar">
          <button type="button" className="secondary" onClick={() => setMode('edit')}>Edit claim</button>
          {!claim.subrogation && <button type="button" className="secondary" onClick={() => setMode('subrogation')}>Flag subrogation</button>}
        </div>
      )}
      {mode === 'edit' && <EditClaim claim={claim} props={props} onDone={done} />}
      {mode === 'subrogation' && <SubrogationForm claim={claim} props={props} onDone={done} />}
      <div className="grid-2">
        <section className="tile" aria-label="Claim">
          <Dl
            items={[
              ['Employee', <>{claim.displayName} <span className="small muted">{claim.employeeId}{claim.unit ? ` · ${claim.unit}` : ''}</span></>],
              ['Case', claim.caseRef ?? '–'],
              ['Type', humanise(claim.claimType)],
              ['Payer', `${claim.payerName ?? claim.payerCode ?? '–'}${claim.policyNumber ? ` · policy ${claim.policyNumber}` : ''}`],
              ['Adjuster', claim.adjuster ? [claim.adjuster.name, claim.adjuster.email, claim.adjuster.username].filter(Boolean).join(' · ') : '–'],
              ['Lost time', claim.lostTime ? 'Yes' : 'No'],
              ['Incident', formatDay(claim.incidentDate)],
              ['Notified / opened', `${formatDate(claim.notifiedAt)} / ${formatDate(claim.openedAt)}`],
              ['Settled / closed', `${formatDate(claim.settledAt)} / ${formatDate(claim.closedAt)}`],
              ['Nurse case manager', claim.nurseCaseManager ? `${claim.nurseCaseManager} (since ${formatDate(claim.nurseAssignedAt)})` : claim.lostTime ? <ToneBadge tone="amber">Not yet assigned</ToneBadge> : '–'],
              ['Open tasks', claim.openTasks ?? 0],
            ]}
          />
        </section>
        <section className="tile" aria-label="Money and flags">
          {money && (
            <>
              <h3>Reserves</h3>
              <Dl items={[...COST_CATEGORIES.filter((c) => claim.reserves?.[c] !== undefined).map((c) => [humanise(c), formatMoney(claim.reserves?.[c])] as [string, string]), ['Paid to date', formatMoney(claim.paidTotal)]]} />
            </>
          )}
          <h3>Flags</h3>
          <ul className="plain-list">
            <li>{claim.litigationHold ? <ToneBadge tone="red">Litigation hold</ToneBadge> : 'No litigation hold'}</li>
            <li>{claim.privileged ? <ToneBadge tone="amber">Privileged</ToneBadge> : 'Not privileged'}</li>
            <li>
              {claim.subrogation ? (
                <>
                  <ToneBadge tone="amber">Subrogation</ToneBadge> {claim.subrogation.thirdPartyName} ({humanise(claim.subrogation.thirdPartyType)}){claim.subrogation.thirdPartyInsurer ? ` · ${claim.subrogation.thirdPartyInsurer}` : ''}{claim.subrogation.reference ? ` · ${claim.subrogation.reference}` : ''}
                </>
              ) : (
                'No subrogation'
              )}
            </li>
          </ul>
        </section>
      </div>
      {(claim.history ?? []).length > 0 && (
        <section aria-labelledby="claim-history">
          <h3 id="claim-history">History</h3>
          <ol className="timeline">
            {(claim.history ?? []).map((h, i) => (
              <li key={`${h.status}-${h.at}-${i}`}>
                <StatusBadge status={h.status} /> <span className="small">{formatDate(h.at)}{h.by ? ` · ${h.by}` : ''}</span>
                {h.reason && <div className="small">{h.reason}</div>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- reports
function ReportCard({ report, form, props, reload }: { report: FirstReport; form?: ReportForm; props: ScreenProps; reload: () => void }) {
  const api = claimsApi(props.client);
  const [fields, setFields] = useState<Record<string, string>>(report.fields ?? {});
  const [channel, setChannel] = useState('API');
  const [ack, setAck] = useState({ ackRef: '', receivedAt: toLocalInput(), file: undefined as File | undefined });
  const [fieldError, setFieldError] = useState<{ field: string; message: string }>();
  const action = useAction(props.onSessionExpired);
  const c = deadlineCountdown(report);
  const draft = report.status === 'DRAFT' || report.status === 'LATE' || report.status === 'REJECTED';
  const labels = new Map((form?.fields ?? []).map((f) => [f.code, f]));
  const keys = [...new Set([...(form?.fields ?? []).map((f) => f.code), ...Object.keys(fields)])];

  async function save(submit: boolean) {
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await api.updateReport(report.id, fields);
        if (submit) {
          await api.submitReport(report.id, channel);
          return `${report.ref} submitted via ${humanise(channel).toLowerCase()}.`;
        }
        return `${report.ref} saved.`;
      } catch (e) {
        failed = e;
        throw e;
      }
    });
    if (ok) {
      setFieldError(undefined);
      reload();
    } else if (isClinicalContentError(failed)) {
      const f = clinicalContentField(failed)?.replace(/^fields\./, '') ?? 'description';
      setFieldError({ field: f, message: `${labels.get(f)?.label ?? humanise(f)}: state the nature of the injury only – no diagnoses beyond it` });
      action.reset();
    }
  }

  return (
    <section className="tile" aria-labelledby={`rep-${report.id}`}>
      <h3 id={`rep-${report.id}`}>
        {report.ref} – {form?.title ?? report.formCode} <StatusBadge status={report.status} /> <ToneBadge tone={c.tone}>{c.label}</ToneBadge>
      </h3>
      <p className="small">
        To {report.recipientCode}{form ? ` (${humanise(form.recipientType)}, ${form.jurisdiction})` : ''} · due {formatDate(report.dueAt)}
        {report.submittedAt && ` · submitted ${formatDate(report.submittedAt)}`}
        {report.acknowledgedAt && ` · acknowledged ${formatDate(report.acknowledgedAt)}${report.ackRef ? ` (${report.ackRef})` : ''}`}
        {report.documentId && <> · <a href={documentContentUrl(report.documentId)} target="_blank" rel="noopener">report document</a></>}
      </p>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void save(false); }} aria-label={`Fields of ${report.ref}`}>
        <fieldset disabled={!draft}>
          <legend className="small muted">Auto-populated from the incident, case and HR record – check and complete.</legend>
          <div className="fields-grid">
            {keys.map((k) => {
              const id = `rep-${report.id}-${k}`;
              const err = fieldError?.field === k ? fieldError.message : undefined;
              const long = (fields[k] ?? '').length > 60 || k.toLowerCase().includes('description');
              return (
                <IdField key={k} id={id} label={labels.get(k)?.label ?? humanise(k)} error={err} hint={labels.get(k)?.source ? `Source: ${humanise(labels.get(k)?.source)}` : undefined}>
                  {(db) =>
                    long ? (
                      <textarea id={id} rows={3} aria-describedby={db} aria-invalid={!!err} value={fields[k] ?? ''} onChange={(e) => setFields({ ...fields, [k]: e.target.value })} />
                    ) : (
                      <input id={id} aria-describedby={db} aria-invalid={!!err} value={fields[k] ?? ''} onChange={(e) => setFields({ ...fields, [k]: e.target.value })} />
                    )
                  }
                </IdField>
              );
            })}
          </div>
        </fieldset>
        {draft && (
          <div className="inline-form">
            <button type="submit" className="secondary" disabled={action.busy}>Save fields</button>
            <Field label="Channel">
              {(id) => (
                <select id={id} value={channel} onChange={(e) => setChannel(e.target.value)}>
                  <SelectOptions values={SUBMISSION_CHANNELS} />
                </select>
              )}
            </Field>
            <button type="button" disabled={action.busy} onClick={() => void save(true)}>Submit report</button>
          </div>
        )}
      </form>
      {form?.recipientType === 'INSURER' && draft && <p className="small muted">Insurer reports need the employee&apos;s insurer-sharing consent; statutory authority reports are sent under legal obligation.</p>}
      {report.status === 'SUBMITTED' && can(props.session.roles, 'claimWrite') && (
        <form
          className="form inline-form"
          aria-label={`Acknowledgement for ${report.ref}`}
          onSubmit={(e) => {
            e.preventDefault();
            if (!ack.ackRef.trim()) return;
            void action.run(async () => { await api.acknowledgeReport(report.id, ack.ackRef.trim(), localToIso(ack.receivedAt) ?? new Date().toISOString(), ack.file); reload(); return `${report.ref} acknowledged.`; });
          }}
        >
          <Field label="Acknowledgement ref" required>{(id) => <input id={id} required value={ack.ackRef} onChange={(e) => setAck({ ...ack, ackRef: e.target.value })} />}</Field>
          <Field label="Received">{(id) => <input id={id} type="datetime-local" value={ack.receivedAt} onChange={(e) => setAck({ ...ack, receivedAt: e.target.value })} />}</Field>
          <Field label="Acknowledgement file">{(id) => <input id={id} type="file" accept="application/pdf,image/*" onChange={(e) => setAck({ ...ack, file: e.target.files?.[0] })} />}</Field>
          <button type="submit" className="secondary" disabled={!ack.ackRef.trim() || action.busy}>Record acknowledgement</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function ReportsTab({ claim, reload, ...props }: TabProps) {
  const api = claimsApi(props.client);
  const forms = useAsync(() => api.reportForms(), props.onSessionExpired);
  const [formCode, setFormCode] = useState('');
  const action = useAction(props.onSessionExpired);
  const reports = claim.reports ?? [];
  return (
    <div>
      {can(props.session.roles, 'claimWrite') && (
        <div className="inline-form">
          <Field label="New report">
            {(id) => (
              <select id={id} value={formCode} onChange={(e) => setFormCode(e.target.value)}>
                <option value="">Choose form…</option>
                {(forms.data ?? []).map((f) => <option key={f.code} value={f.code}>{f.title} – {f.jurisdiction} ({f.deadlineHours} h)</option>)}
              </select>
            )}
          </Field>
          <button type="button" disabled={!formCode || action.busy} onClick={() => void action.run(async () => { const r = await api.createReport(claim.id, formCode); setFormCode(''); reload(); return `${r.ref} created – due ${formatDate(r.dueAt)}.`; })}>Create report</button>
        </div>
      )}
      <Alert kind="info">Statutory and insurer report forms are pending Legal / HSE sign-off – check deadlines with the claims mailbox if in doubt.</Alert>
      <ActionFeedback error={action.error} message={action.message} />
      {reports.length === 0 && <Empty>No reports on this claim.</Empty>}
      {reports.map((r) => <ReportCard key={`${r.id}-${r.status}`} report={r} form={forms.data?.find((f) => f.code === r.formCode)} props={props} reload={reload} />)}
    </div>
  );
}

// ---------------------------------------------------------------- disclosures
interface CandidateDoc {
  id: string;
  title: string;
  type?: string;
}

function DisclosureSetCard({ set, props, reload }: { set: DisclosureSet; props: ScreenProps; reload: () => void }) {
  const api = claimsApi(props.client);
  const oh = hasAnyRole(props.session.roles, OH_ROLES);
  const action = useAction(props.onSessionExpired);
  const [consentId, setConsentId] = useState(set.consentId ?? '');
  const [redact, setRedact] = useState<{ documentId: string; text: string; error?: string }>();
  const canSend = can(props.session.roles, 'disclosureSetWrite');

  async function applyRedaction(e: FormEvent) {
    e.preventDefault();
    if (!redact) return;
    const parsed = parseRedactionPhrases(redact.text);
    if (parsed.error) return setRedact({ ...redact, error: parsed.error });
    const ok = await action.run(async () => { await api.redact(set.id, redact.documentId, parsed.phrases); return `Redacted copy created (${parsed.phrases.length} phrase${parsed.phrases.length === 1 ? '' : 's'} masked) and swapped into the set.`; });
    if (ok) {
      setRedact(undefined);
      reload();
    }
  }

  return (
    <section className="tile" aria-labelledby={`ds-${set.id}`}>
      <h3 id={`ds-${set.id}`}>
        To {set.recipientCode} – {set.purpose} <ToneBadge tone={set.status === 'SENT' ? 'green' : set.status === 'REVOKED' ? 'muted' : 'amber'}>{humanise(set.status)}</ToneBadge>
      </h3>
      {set.sentAt && <p className="small">Sent {formatDate(set.sentAt)}{set.sentBy ? ` by ${set.sentBy}` : ''}{set.consentId ? ` · consent ${set.consentId}` : ''}{set.revokedAt ? ` · revoked ${formatDate(set.revokedAt)}` : ''}</p>}
      <ul className="plain-list">
        {set.documents.map((d) => (
          <li key={d.documentId}>
            {d.title ?? d.documentId} <span className="small muted">{humanise(d.type)}</span>
            {d.redactedFromId && <> <ToneBadge tone="green">Redacted copy</ToneBadge></>}
            {oh && set.status === 'DRAFT' && !d.redactedFromId && (
              <> <button type="button" className="link-button" onClick={() => setRedact({ documentId: d.documentId, text: '' })} aria-label={`Redact ${d.title ?? d.documentId}`}>Redact</button></>
            )}
          </li>
        ))}
      </ul>
      {redact && (
        <form className="form" onSubmit={applyRedaction} aria-label="Redaction">
          <IdField id={`rd-${set.id}`} label="Phrases to mask (one per line, max 50)" required error={redact.error} hint="Each phrase is masked wherever it appears; the redacted copy replaces the original in this set.">
            {(db) => <textarea id={`rd-${set.id}`} rows={5} aria-describedby={db} aria-invalid={!!redact.error} value={redact.text} onChange={(e) => setRedact({ ...redact, text: e.target.value, error: undefined })} />}
          </IdField>
          <div className="actions">
            <button type="submit" disabled={action.busy}>Create redacted copy</button>
            <button type="button" className="secondary" onClick={() => setRedact(undefined)}>Cancel</button>
          </div>
        </form>
      )}
      {set.status === 'DRAFT' && canSend && (
        <form
          className="form inline-form"
          aria-label={`Send set to ${set.recipientCode}`}
          onSubmit={(e) => {
            e.preventDefault();
            if (!consentId.trim()) return;
            void action.run(async () => { await api.sendSet(set.id, consentId.trim()); reload(); return `Disclosure set sent to ${set.recipientCode} and logged.`; });
          }}
        >
          <Field label="Insurer-sharing consent reference" required>{(id) => <input id={id} required value={consentId} onChange={(e) => setConsentId(e.target.value)} />}</Field>
          <button type="submit" disabled={!consentId.trim() || action.busy}>Send</button>
        </form>
      )}
      {set.status === 'SENT' && can(props.session.roles, 'disclosureRevoke') && (
        <button type="button" className="secondary" disabled={action.busy} onClick={() => void action.run(async () => { await api.revokeSet(set.id); reload(); return 'Shares revoked.'; })}>Revoke</button>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function DisclosuresTab({ claim, ...props }: TabProps) {
  const api = claimsApi(props.client);
  const oh = hasAnyRole(props.session.roles, OH_ROLES);
  const sets = useAsync(() => api.disclosureSets(claim.id), props.onSessionExpired, [claim.id]);
  const candidates = useAsync(async (): Promise<CandidateDoc[]> => {
    if (oh) {
      const docs = await platformApi(props.client).documents({ employeeId: claim.employeeId }).catch(() => []);
      return docs.map((d) => ({ id: d.id, title: d.title, type: d.type }));
    }
    const t = treatmentApi(props.client);
    const [pas, invs] = await Promise.all([t.casePreauths(claim.caseId).catch(() => []), t.caseInvoices(claim.caseId).catch(() => [])]);
    return [
      ...(claim.reports ?? []).filter((r) => r.documentId).map((r) => ({ id: r.documentId!, title: `${r.ref} ${r.formCode}`, type: 'FIRST_REPORT_OF_INJURY' })),
      ...pas.filter((p) => p.documentId).map((p) => ({ id: p.documentId!, title: `Pre-authorisation request ${p.ref}`, type: 'PREAUTH_REQUEST' })),
      ...invs.filter((i) => i.documentId).map((i) => ({ id: i.documentId!, title: `Invoice ${i.invoiceNumber}`, type: 'PROVIDER_INVOICE' })),
    ];
  }, props.onSessionExpired, [claim.id]);
  const [creating, setCreating] = useState(false);
  const [f, setF] = useState({ recipientCode: claim.payerCode ?? '', purpose: 'CLAIM', documentIds: [] as string[], manual: '' });
  const action = useAction(props.onSessionExpired);
  const allowed = (candidates.data ?? []).filter((d) => isDisclosable(d.type, oh));
  const blocked = (candidates.data ?? []).filter((d) => !isDisclosable(d.type, oh));

  async function create(e: FormEvent) {
    e.preventDefault();
    const ids = [...new Set([...f.documentIds, ...f.manual.split(/[\s,]+/).filter(Boolean)])];
    if (!f.recipientCode.trim() || ids.length === 0) return;
    const ok = await action.run(async () => { await api.createDisclosureSet(claim.id, { recipientCode: f.recipientCode.trim().toUpperCase(), purpose: f.purpose.trim() || 'CLAIM', documentIds: ids }); return 'Disclosure set created as draft.'; });
    if (ok) {
      setCreating(false);
      setF({ ...f, documentIds: [], manual: '' });
      sets.reload();
    }
  }

  return (
    <div>
      <p className="small muted">Only certificates, restriction notes, RTW plans, pre-authorisation requests, first reports, invoices and redacted copies can be disclosed (OH may add provider / external reports). Medico-legal reports, photos, restricted and privileged documents never leave.</p>
      {can(props.session.roles, 'disclosureSetWrite') && !creating && <button type="button" onClick={() => setCreating(true)}>New disclosure set</button>}
      {creating && (
        <form className="form tile" onSubmit={create} aria-label="New disclosure set">
          <h3>New disclosure set</h3>
          <div className="inline-form">
            <Field label="Recipient (payer / authority code)" required>{(id) => <input id={id} required value={f.recipientCode} onChange={(e) => setF({ ...f, recipientCode: e.target.value })} />}</Field>
            <Field label="Purpose">{(id) => <input id={id} value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })} />}</Field>
          </div>
          <fieldset>
            <legend>Documents</legend>
            {candidates.loading && <Loading />}
            {candidates.data && allowed.length === 0 && <p className="muted small">No disclosable documents found – enter document ids below.</p>}
            {allowed.map((d) => (
              <label key={d.id} className="inline-row">
                <input type="checkbox" checked={f.documentIds.includes(d.id)} onChange={(e) => setF({ ...f, documentIds: e.target.checked ? [...f.documentIds, d.id] : f.documentIds.filter((x) => x !== d.id) })} /> {d.title} <span className="small muted">{humanise(d.type)}</span>
              </label>
            ))}
            {blocked.length > 0 && <p className="small muted">{blocked.length} document{blocked.length === 1 ? '' : 's'} not disclosable to a payer {oh ? '' : '(or OH-only) '}hidden.</p>}
            <Field label="Other document ids" hint="Comma or space separated">{(id) => <input id={id} value={f.manual} onChange={(e) => setF({ ...f, manual: e.target.value })} />}</Field>
          </fieldset>
          <div className="actions">
            <button type="submit" disabled={action.busy || !f.recipientCode.trim() || (f.documentIds.length === 0 && !f.manual.trim())}>Create draft set</button>
            <button type="button" className="secondary" onClick={() => setCreating(false)}>Cancel</button>
          </div>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {sets.loading && !sets.data && <Loading />}
      {!!sets.error && <ErrorAlert error={sets.error} />}
      {sets.data && sets.data.length === 0 && <Empty>No disclosure sets.</Empty>}
      {(sets.data ?? []).map((s) => <DisclosureSetCard key={`${s.id}-${s.status}-${s.documents.length}`} set={s} props={props} reload={sets.reload} />)}
    </div>
  );
}

// ---------------------------------------------------------------- costs
function CostsTab({ claim, ...props }: TabProps) {
  const api = claimsApi(props.client);
  const ledger = useAsync(() => api.costs(claim.id), props.onSessionExpired, [claim.id]);
  const [f, setF] = useState({ category: 'MEDICAL', kind: 'PAYMENT', amount: '', currency: 'GBP', incurredOn: todayIso(), description: '', invoiceNumber: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(props.onSessionExpired);
  const rows = ledger.data ?? [];

  async function add(e: FormEvent) {
    e.preventDefault();
    const amount = parseAmount(f.amount);
    const errs: FieldError[] = [];
    if (!amount) errs.push({ field: 'cost-amount', message: 'Amount: enter an amount such as 250.00' });
    if (!f.incurredOn) errs.push({ field: 'cost-date', message: 'Enter the date incurred' });
    setErrors(errs);
    if (errs.length || !amount) return;
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await api.addCost(claim.id, { category: f.category as CostCategory, kind: f.kind as (typeof COST_KINDS)[number], amount, currency: f.currency, incurredOn: f.incurredOn, ...(f.description.trim() ? { description: f.description.trim() } : {}), ...(f.invoiceNumber.trim() ? { invoiceNumber: f.invoiceNumber.trim() } : {}) });
        return 'Cost entry added to the ledger.';
      } catch (ex) {
        failed = ex;
        throw ex;
      }
    });
    if (ok) {
      setF({ ...f, amount: '', description: '', invoiceNumber: '' });
      ledger.reload();
    } else if (isClinicalContentError(failed)) {
      setErrors([{ field: 'cost-description', message: 'Description: no clinical details – finance reads the ledger' }]);
      action.reset();
    }
  }

  const err = (k: string) => errors.find((e) => e.field === k)?.message;
  return (
    <div>
      <h3>Cost ledger</h3>
      {ledger.loading && !ledger.data && <Loading />}
      {!!ledger.error && <ErrorAlert error={ledger.error} />}
      {ledger.data && rows.length === 0 && <Empty>No cost entries.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Cost ledger</caption>
            <thead><tr><th scope="col">Date</th><th scope="col">Category</th><th scope="col">Kind</th><th scope="col" className="num">Amount</th><th scope="col">Source</th><th scope="col">Description</th></tr></thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <th scope="row">{formatDay(c.incurredOn ?? c.createdAt)}</th>
                  <td>{humanise(c.category)}</td>
                  <td>{humanise(c.kind)}</td>
                  <td className="num">{formatMoney(c.amount, c.currency)}</td>
                  <td className="small">{c.sourceType ? humanise(c.sourceType) : 'Manual'}{c.invoiceNumber ? ` · ${c.invoiceNumber}` : ''}</td>
                  <td>{c.description ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="form tile" onSubmit={add} noValidate aria-label="Add cost">
        <h3>Add cost entry</h3>
        <ErrorSummary errors={errors} />
        <div className="inline-form">
          <Field label="Category">{(id) => <select id={id} value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}><SelectOptions values={COST_CATEGORIES} /></select>}</Field>
          <Field label="Kind">{(id) => <select id={id} value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}><SelectOptions values={COST_KINDS} /></select>}</Field>
          <IdField id="cost-amount" label="Amount" required error={err('cost-amount')}>
            {(db) => <input id="cost-amount" inputMode="decimal" aria-describedby={db} aria-invalid={!!err('cost-amount')} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}
          </IdField>
          <Field label="Currency">{(id) => <select id={id} value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })}>{CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</select>}</Field>
          <IdField id="cost-date" label="Incurred on" required error={err('cost-date')}>
            {(db) => <input id="cost-date" type="date" aria-describedby={db} value={f.incurredOn} onChange={(e) => setF({ ...f, incurredOn: e.target.value })} />}
          </IdField>
          <Field label="Invoice number">{(id) => <input id={id} value={f.invoiceNumber} onChange={(e) => setF({ ...f, invoiceNumber: e.target.value })} />}</Field>
        </div>
        <IdField id="cost-description" label="Description" error={err('cost-description')}>
          {(db) => <input id="cost-description" aria-describedby={db} aria-invalid={!!err('cost-description')} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />}
        </IdField>
        <button type="submit" disabled={action.busy}>Add</button>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
      <h3>Pre-authorisations & provider invoices</h3>
      <CaseCostsPanel client={props.client} session={props.session} onSessionExpired={props.onSessionExpired} caseId={claim.caseId} />
    </div>
  );
}

// ---------------------------------------------------------------- legal
function LegalTab({ claim, reload, ...props }: TabProps) {
  const api = claimsApi(props.client);
  const legal = can(props.session.roles, 'legalWrite');
  const action = useAction(props.onSessionExpired);
  const [hold, setHold] = useState({ reason: '' });
  const [priv, setPriv] = useState({ basis: '' });
  const requests = useAsync(() => (claim.legalRequests ? Promise.resolve(claim.legalRequests) : api.legalRequests().then((l) => l.filter((r) => r.claimId === claim.id))), props.onSessionExpired, [claim.id, claim.legalRequests?.length]);

  return (
    <div>
      {legal && (
        <div className="grid-2">
          <form
            className="form tile"
            aria-label="Litigation hold"
            onSubmit={(e) => {
              e.preventDefault();
              if (!hold.reason.trim()) return;
              void action.run(async () => {
                if (claim.litigationHold) await api.releaseLegalHold(claim.id, hold.reason.trim());
                else await api.legalHold(claim.id, hold.reason.trim());
                setHold({ reason: '' });
                reload();
                return claim.litigationHold ? 'Litigation hold released.' : 'Litigation hold placed – retention of every case document is suspended.';
              });
            }}
          >
            <h3>Litigation hold {claim.litigationHold && <ToneBadge tone="red">On</ToneBadge>}</h3>
            <Field label="Reason" required>{(id) => <textarea id={id} rows={2} required value={hold.reason} onChange={(e) => setHold({ reason: e.target.value })} />}</Field>
            <button type="submit" disabled={!hold.reason.trim() || action.busy}>{claim.litigationHold ? 'Release hold' : 'Place hold'}</button>
          </form>
          <form
            className="form tile"
            aria-label="Privilege"
            onSubmit={(e) => {
              e.preventDefault();
              if (!priv.basis.trim()) return;
              void action.run(async () => { await api.privilege(claim.id, !claim.privileged, priv.basis.trim()); setPriv({ basis: '' }); reload(); return claim.privileged ? 'Privilege removed.' : 'Marked privileged – excluded from disclosure sets.'; });
            }}
          >
            <h3>Legal privilege {claim.privileged && <ToneBadge tone="amber">Privileged</ToneBadge>}</h3>
            <Field label="Basis" required>{(id) => <textarea id={id} rows={2} required value={priv.basis} onChange={(e) => setPriv({ basis: e.target.value })} />}</Field>
            <button type="submit" disabled={!priv.basis.trim() || action.busy}>{claim.privileged ? 'Remove privilege' : 'Mark privileged'}</button>
          </form>
        </div>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {can(props.session.roles, 'legalRequestCreate') && <LegalRequestForm claimId={claim.id} props={props} onDone={() => { reload(); requests.reload(); }} />}
      <h3>Legal requests</h3>
      {requests.loading && !requests.data && <Loading />}
      {!!requests.error && <ErrorAlert error={requests.error} />}
      {requests.data && <LegalRequestList rows={requests.data} props={props} onChanged={() => { reload(); requests.reload(); }} showClaim={false} />}
    </div>
  );
}

// ---------------------------------------------------------------- screen
function claimTabs(roles: readonly string[]): TabDef[] {
  return [
    { id: 'overview', label: 'Overview' },
    { id: 'reports', label: 'Reports', hidden: !can(roles, 'claimReports') },
    { id: 'disclosures', label: 'Disclosures', hidden: !can(roles, 'disclosureSetWrite') },
    { id: 'collaboration', label: 'Tasks & messages', hidden: !can(roles, 'claimCollaboration') },
    { id: 'costs', label: 'Costs', hidden: !can(roles, 'claimCostLedger') },
    { id: 'legal', label: 'Legal', hidden: !(can(roles, 'legalWrite') || can(roles, 'legalRequestCreate') || can(roles, 'legalReportWrite')) },
  ];
}

/** `#/claims/{id}[/{tab}]` – claim record (E08). No clinical content is shown here. */
export function ClaimDetailScreen(props: ScreenProps & { claimId: string; tab?: string }) {
  const { client, session, onSessionExpired, claimId } = props;
  const detail = useAsync(() => claimsApi(client).claim(claimId), onSessionExpired, [claimId]);
  const tabs = claimTabs(session.roles);
  const tab = tabs.some((t) => t.id === props.tab && !t.hidden) ? (props.tab as string) : 'overview';
  const c = detail.data;
  const tp: TabProps | undefined = c ? { client, session, onSessionExpired, claim: c, reload: detail.reload } : undefined;
  const back = can(session.roles, 'claims') ? ['#/claims', 'Claims'] : can(session.roles, 'legal') ? ['#/legal', 'Legal & litigation'] : ['#/', 'Home'];

  return (
    <section>
      <p className="breadcrumb small"><a href={back[0]}>{back[1]}</a> <span aria-hidden="true">›</span> Claim</p>
      <PageTitle sub={c ? `${c.displayName} · ${humanise(c.claimType)} · ${c.payerName ?? c.payerCode ?? ''}` : undefined}>{c?.claimRef ?? 'Claim'}</PageTitle>
      {c && (
        <div className="badge-row">
          <ToneBadge tone={claimTone(c.status)}>{humanise(c.status)}</ToneBadge>
          {c.lostTime && <ToneBadge tone="amber">Lost time</ToneBadge>}
          {c.litigationHold && <ToneBadge tone="red">Litigation hold</ToneBadge>}
          {c.privileged && <ToneBadge tone="amber">Privileged</ToneBadge>}
          {c.reportStatus && <span className="small">Report: {humanise(c.reportStatus)}</span>}
        </div>
      )}
      <TabNav tabs={tabs} current={tab} label="Claim sections" hrefFor={(id) => `#/claims/${encodeURIComponent(claimId)}/${id}`} />
      <div className="tab-panel">
        {detail.loading && !c && <Loading label="Loading claim…" />}
        {!!detail.error && <ErrorAlert error={detail.error} />}
        {tp && tab === 'overview' && <OverviewTab {...tp} />}
        {tp && tab === 'reports' && <ReportsTab {...tp} />}
        {tp && tab === 'disclosures' && <DisclosuresTab {...tp} />}
        {tp && tab === 'collaboration' && <ClaimCollaboration props={props} claimId={tp.claim.id} />}
        {tp && tab === 'costs' && <CostsTab {...tp} />}
        {tp && tab === 'legal' && <LegalTab {...tp} />}
      </div>
    </section>
  );
}
