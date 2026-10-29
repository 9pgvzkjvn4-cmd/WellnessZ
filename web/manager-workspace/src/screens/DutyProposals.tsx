import { useState } from 'react';
import {
  buildDutyDecision,
  DECISION_FIELDS,
  DECISION_NOTE_MAX,
  DECIDED_PROPOSAL_STATUSES,
  DECLINE_REASON_LABELS,
  DECLINE_REASONS,
  emptyDecisionDraft,
  toDutyProposals,
  validateDutyDecision,
  type DeclineReason,
  type DutyDecisionDraft,
  type DutyProposal,
} from '../api/fitness';
import { GUIDANCE, type FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { FieldError, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { decisionIds } from '../components/ids';
import { describeError, formatDate, formatDay, humanise, idPart } from '../components/format';
import { NoClinicalInfoNote, RestrictionList } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

const period = (from?: string, to?: string) => (from ? `${formatDay(from)} – ${to ? formatDay(to) : 'open-ended'}` : '–');

export interface ProposalCardProps {
  proposal: DutyProposal;
  draft: DutyDecisionDraft;
  errors: FieldErrors;
  message?: string;
  busy: boolean;
  onDraft: (d: DutyDecisionDraft) => void;
  onDecide: (accept: boolean) => void;
}

/** E05-BL-006 – one pending modified-duty proposal with Accept / Decline. Hook-free. */
export function ProposalCard({ proposal: p, draft, errors, message, busy, onDraft, onDecide }: ProposalCardProps) {
  const ids = decisionIds(p.id);
  const headingId = `dp-${idPart(p.id)}-title`;
  return (
    <li className="card">
      <article aria-labelledby={headingId}>
        <h2 id={headingId}>
          {p.employeeName}
          {p.caseRef && <span className="muted small"> · {p.caseRef}</span>}
        </h2>
        <dl className="details">
          <dt>Role</dt>
          <dd>
            {p.jobTitle ?? '–'}
            {p.jobCode && <span className="muted small"> ({p.jobCode})</span>}
          </dd>
          <dt>Duties</dt>
          <dd>{p.duties ?? '–'}</dd>
          <dt>Hours per day</dt>
          <dd>{p.hoursPerDay ?? '–'}</dd>
          <dt>Dates</dt>
          <dd>{period(p.startDate, p.endDate)}</dd>
          <dt>Restrictions to respect</dt>
          <dd>
            <RestrictionList items={p.restrictions} />
          </dd>
          <dt>Proposed</dt>
          <dd>{formatDate(p.proposedAt)}</dd>
        </dl>
        <form className="form" noValidate onSubmit={(e) => e.preventDefault()} aria-label={`Decision for ${p.employeeName}`}>
          {message && <Alert kind="error">{message}</Alert>}
          <div className={errors.reasonCode ? 'field field-invalid' : 'field'}>
            <label htmlFor={ids.reasonCode}>Reason for declining</label>
            <p id={`${ids.reasonCode}-hint`} className="muted small">
              Required when you decline.
            </p>
            <FieldError id={ids.reasonCode} error={errors.reasonCode} />
            <select
              id={ids.reasonCode}
              value={draft.reasonCode}
              aria-invalid={errors.reasonCode ? true : undefined}
              aria-describedby={errors.reasonCode ? `${ids.reasonCode}-hint ${ids.reasonCode}-error` : `${ids.reasonCode}-hint`}
              onChange={(e) => onDraft({ ...draft, reasonCode: e.target.value as DeclineReason | '' })}
            >
              <option value="">Select…</option>
              {DECLINE_REASONS.map((r) => (
                <option key={r} value={r}>
                  {DECLINE_REASON_LABELS[r]}
                </option>
              ))}
            </select>
          </div>
          <TextAreaField
            id={ids.note}
            label="Note for Occupational Health (optional)"
            max={DECISION_NOTE_MAX}
            value={draft.note}
            onChange={(v) => onDraft({ ...draft, note: v })}
            hint={GUIDANCE}
            error={errors.note}
            rows={2}
          />
          <div className="actions">
            <button type="button" disabled={busy} onClick={() => onDecide(true)}>
              Accept
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => onDecide(false)}>
              Decline
            </button>
          </div>
        </form>
      </article>
    </li>
  );
}

/** Decided proposals. Hook-free. */
export function ProposalHistory({ items }: { items: readonly DutyProposal[] }) {
  if (items.length === 0) return <p className="muted">No decided proposals yet.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Decided modified-duty proposals</caption>
        <thead>
          <tr>
            <th scope="col">Employee</th>
            <th scope="col">Role</th>
            <th scope="col">Dates</th>
            <th scope="col">Outcome</th>
            <th scope="col">Reason</th>
            <th scope="col">Decided</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => (
            <tr key={p.id}>
              <th scope="row">
                {p.employeeName}
                {p.caseRef && <span className="muted small"> {p.caseRef}</span>}
              </th>
              <td>{p.jobTitle ?? p.jobCode ?? '–'}</td>
              <td>{period(p.startDate, p.endDate)}</td>
              <td>
                <StatusBadge status={p.status} />
              </td>
              <td>{p.reasonCode ? (DECLINE_REASON_LABELS[p.reasonCode as DeclineReason] ?? humanise(p.reasonCode)) : '–'}</td>
              <td>{formatDate(p.decidedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

async function loadProposals(client: ScreenProps['client']) {
  const q = (status: string) => client.api<unknown>(`/workspace/duty-proposals?status=${status}`).then(toDutyProposals);
  const [pending, ...decided] = await Promise.all([q('PROPOSED'), ...DECIDED_PROPOSAL_STATUSES.map(q)]);
  const history = decided.flat().sort((a, b) => (b.decidedAt ?? b.proposedAt ?? '').localeCompare(a.decidedAt ?? a.proposedAt ?? ''));
  return { pending, history };
}

export function DutyProposals({ client, onSessionExpired }: ScreenProps) {
  const data = useAsync(() => loadProposals(client), onSessionExpired);
  const [drafts, setDrafts] = useState<Record<string, DutyDecisionDraft>>({});
  const [errors, setErrors] = useState<Record<string, FieldErrors>>({});
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string>();
  const [done, setDone] = useState<string>();

  async function decide(p: DutyProposal, accept: boolean) {
    const draft = drafts[p.id] ?? emptyDecisionDraft();
    const ids = decisionIds(p.id);
    setDone(undefined);
    const v = validateDutyDecision(accept, draft);
    setErrors((e) => ({ ...e, [p.id]: v }));
    setMessages((m) => ({ ...m, [p.id]: '' }));
    if (Object.keys(v).length) {
      focusFirstError([v.reasonCode ? ids.reasonCode : '', v.note ? ids.note : ''].filter(Boolean));
      return;
    }
    setBusy(p.id);
    const r = await submitAction(client, `/duty-proposals/${encodeURIComponent(p.id)}/decision`, buildDutyDecision(accept, draft), DECISION_FIELDS, {
      failed: 'The decision was not saved',
    });
    setBusy(undefined);
    if (r.ok) {
      setDone(`${accept ? 'Accepted' : 'Declined'} the modified duty for ${p.employeeName}. Occupational Health has been informed.`);
      data.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    setErrors((e) => ({ ...e, [p.id]: r.fieldErrors }));
    setMessages((m) => ({ ...m, [p.id]: r.message }));
    focusFirstError([r.fieldErrors.reasonCode ? ids.reasonCode : '', r.fieldErrors.note ? ids.note : ''].filter(Boolean));
  }

  return (
    <section>
      <PageTitle>Modified duty requests</PageTitle>
      <p className="lead">
        Occupational Health suggests modified duties that respect the employee’s restrictions. Confirm whether you can offer
        them, or decline with a reason. If every proposal for a case is declined, HR is asked to help.
      </p>
      <NoClinicalInfoNote />
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          Refresh
        </button>
      </div>
      {done && <Alert kind="success">{done}</Alert>}
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && (
        <>
          <h2>Waiting for your decision</h2>
          {data.data.pending.length === 0 ? (
            <p className="muted">No modified duty requests are waiting for you.</p>
          ) : (
            <ul className="card-list">
              {data.data.pending.map((p) => (
                <ProposalCard
                  key={p.id}
                  proposal={p}
                  draft={drafts[p.id] ?? emptyDecisionDraft()}
                  errors={errors[p.id] ?? {}}
                  message={messages[p.id] || undefined}
                  busy={busy === p.id}
                  onDraft={(d) => {
                    setDrafts((all) => ({ ...all, [p.id]: d }));
                    setErrors((e) => ({ ...e, [p.id]: {} }));
                  }}
                  onDecide={(accept) => void decide(p, accept)}
                />
              ))}
            </ul>
          )}
          <h2 className="section-gap">History</h2>
          <ProposalHistory items={data.data.history} />
        </>
      )}
    </section>
  );
}
