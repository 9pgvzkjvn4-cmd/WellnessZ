import { useState, type FormEvent } from 'react';
import { claimsApi, LEGAL_REQUEST_STATUSES, LEGAL_REQUEST_TYPES, legalReportUrl, type LegalRequest, type LegalRequestType } from '../../api/claims';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { daysUntil, todayIso } from '../../domain/absence';
import type { ScreenProps } from '../types';
import { claimLink } from '../treatment/util';

function dueTone(r: LegalRequest): 'red' | 'amber' | 'green' | 'muted' {
  if (r.status === 'CLOSED' || r.status === 'REPORT_READY') return 'muted';
  const d = daysUntil(r.dueDate, todayIso());
  return d < 0 ? 'red' : d <= 3 ? 'amber' : 'green';
}

/** Report entry for physicians: each submission creates a new signed MEDICO_LEGAL_REPORT version. */
function ReportEntry({ r, props, onDone }: { r: LegalRequest; props: ScreenProps; onDone: (message: string) => void }) {
  const [opinion, setOpinion] = useState('');
  const action = useAction(props.onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!opinion.trim()) return;
    let message = '';
    const ok = await action.run(async () => {
      const res = await claimsApi(props.client).submitLegalReport(r.id, opinion.trim());
      message = `Medico-legal report v${res.reports.length || 1} signed for ${r.ref}.`;
    });
    if (ok) {
      setOpinion('');
      onDone(message);
    }
  }
  return (
    <form className="form" onSubmit={submit} aria-label={`Medico-legal report for ${r.ref}`}>
      <Field label="Medical opinion" required hint="Answers the questions above. Generated as a signed medico-legal report (legal access only, never disclosed to the insurer).">
        {(id) => <textarea id={id} rows={8} maxLength={20000} required value={opinion} onChange={(e) => setOpinion(e.target.value)} />}
      </Field>
      <button type="submit" disabled={!opinion.trim() || action.busy}>{r.reports.length ? 'Sign new version' : 'Sign report'}</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** Legal requests (medico-legal report, expert opinion, IME) with report versions; actions per role. */
export function LegalRequestList({ rows, props, onChanged, showClaim = true }: { rows: LegalRequest[]; props: ScreenProps; onChanged: () => void; showClaim?: boolean }) {
  const { client, session, onSessionExpired } = props;
  const action = useAction(onSessionExpired);
  const [open, setOpen] = useState<string>();
  const [signed, setSigned] = useState<string>();
  const canReport = can(session.roles, 'legalReportWrite');
  const canClose = can(session.roles, 'legalWrite');
  if (rows.length === 0) return <Empty>No legal requests.</Empty>;
  return (
    <div>
      <ActionFeedback error={action.error} message={action.message ?? signed} />
      <ul className="plain-list">
        {rows.map((r) => (
          <li key={r.id} className="tile">
            <h3>
              {r.ref} – {humanise(r.type)} <ToneBadge tone={dueTone(r)}>{humanise(r.status)} · due {formatDay(r.dueDate)}</ToneBadge>
            </h3>
            {showClaim && r.claimId && <p className="small"><a href={claimLink(r.claimId, 'legal')}>{r.claimRef ?? 'Claim'}</a></p>}
            {r.providerCode && <p className="small">Provider: {r.providerCode}{r.referralId ? ' · IME referral created for OH to complete' : ''}</p>}
            {r.questions && <p className="pre-wrap">{r.questions}</p>}
            {r.reports.length > 0 && (
              <ul className="plain-list">
                {r.reports.map((v) => (
                  <li key={v.version}>
                    <a href={legalReportUrl(r.id, v.version)} target="_blank" rel="noopener">Report v{v.version}</a> <span className="small muted">{formatDate(v.createdAt)}{v.author ? ` · ${v.author}` : ''}</span>
                  </li>
                ))}
              </ul>
            )}
            <div className="card-actions">
              {canReport && r.type === 'MEDICO_LEGAL_REPORT' && r.status !== 'CLOSED' && (
                <button type="button" className="secondary small-button" aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? undefined : r.id)}>{open === r.id ? 'Hide report form' : 'Write report'}</button>
              )}
              {canClose && r.status !== 'CLOSED' && (
                <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void action.run(async () => { await claimsApi(client).closeLegalRequest(r.id); onChanged(); return `${r.ref} closed.`; })}>Close request</button>
              )}
            </div>
            {open === r.id && <ReportEntry r={r} props={props} onDone={(m) => { setOpen(undefined); setSigned(m); onChanged(); }} />}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** New legal request on a claim (LEGAL_COUNSEL, CLAIMS_COORDINATOR). IME / expert opinion take a provider. */
export function LegalRequestForm({ claimId, props, onDone }: { claimId: string; props: ScreenProps; onDone: () => void }) {
  const [f, setF] = useState({ type: 'MEDICO_LEGAL_REPORT' as LegalRequestType, questions: '', dueDate: '', providerCode: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(props.onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.questions.trim()) errs.push({ field: 'lr-questions', message: 'Enter the questions' });
    if (!f.dueDate) errs.push({ field: 'lr-due', message: 'Enter the due date' });
    if (f.type !== 'MEDICO_LEGAL_REPORT' && !f.providerCode.trim()) errs.push({ field: 'lr-provider', message: 'Choose the provider for the expert opinion / IME' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const r = await claimsApi(props.client).createLegalRequest(claimId, { type: f.type, questions: f.questions.trim(), dueDate: f.dueDate, ...(f.type !== 'MEDICO_LEGAL_REPORT' ? { providerCode: f.providerCode.trim().toUpperCase() } : {}) });
      return `${r.ref} created.${f.type === 'MEDICO_LEGAL_REPORT' ? ' Physicians and the CMO are notified.' : f.type === 'IME' ? ' A draft IME referral was created for OH to complete.' : ''}`;
    });
    if (ok) {
      setF({ ...f, questions: '', dueDate: '', providerCode: '' });
      onDone();
    }
  }
  const err = (k: string) => errors.find((e) => e.field === k)?.message;
  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="New legal request">
      <h3>New legal request</h3>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <Field label="Type">
          {(id) => (
            <select id={id} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as LegalRequestType })}>
              <SelectOptions values={LEGAL_REQUEST_TYPES} />
            </select>
          )}
        </Field>
        <IdField id="lr-due" label="Due" required error={err('lr-due')}>
          {(db) => <input id="lr-due" type="date" aria-describedby={db} aria-invalid={!!err('lr-due')} value={f.dueDate} onChange={(e) => setF({ ...f, dueDate: e.target.value })} />}
        </IdField>
        {f.type !== 'MEDICO_LEGAL_REPORT' && (
          <IdField id="lr-provider" label="Provider code" required error={err('lr-provider')}>
            {(db) => <input id="lr-provider" aria-describedby={db} aria-invalid={!!err('lr-provider')} value={f.providerCode} onChange={(e) => setF({ ...f, providerCode: e.target.value })} />}
          </IdField>
        )}
      </div>
      <IdField id="lr-questions" label="Questions" required error={err('lr-questions')}>
        {(db) => <textarea id="lr-questions" rows={4} maxLength={4000} aria-describedby={db} aria-invalid={!!err('lr-questions')} value={f.questions} onChange={(e) => setF({ ...f, questions: e.target.value })} />}
      </IdField>
      <button type="submit" disabled={action.busy}>Create request</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** `#/legal/requests` – physicians: open medico-legal requests and report entry. */
export function PhysicianLegalRequests(props: ScreenProps) {
  const [status, setStatus] = useState('OPEN');
  const list = useAsync(() => claimsApi(props.client).legalRequests(status || undefined), props.onSessionExpired, [status]);
  return (
    <section>
      <PageTitle sub="Medico-legal report requests from legal counsel. Reports are signed, versioned and visible to legal and physicians only.">Medico-legal requests</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={LEGAL_REQUEST_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && <LegalRequestList rows={list.data} props={props} onChanged={list.reload} showClaim={false} />}
    </section>
  );
}
