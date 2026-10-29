import { useState, type FormEvent } from 'react';
import { documentContentUrl } from '../../api/platform';
import { treatmentApi, type Referral } from '../../api/treatment';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { referralTimeline, referralTone, reportDueLabel } from '../../domain/treatment';
import type { ScreenProps } from '../types';
import { ProviderBadges, StarRating, Stars, StatusSteps } from './common';
import { ReferralWizard } from './ReferralWizard';
import { caseLink } from './util';

const refLink = (id: string) => `#/referrals/${encodeURIComponent(id)}`;

/** Status badge with overdue / escalation markers (shape + text). */
export function ReferralStatus({ r }: { r: Pick<Referral, 'status' | 'overdue' | 'escalated' | 'chaseCount'> }) {
  return (
    <span className="badge-row">
      <ToneBadge tone={referralTone(r)}>{humanise(r.status)}</ToneBadge>
      {r.overdue && <ToneBadge tone="red">Overdue</ToneBadge>}
      {r.escalated && <ToneBadge tone="red">Escalated</ToneBadge>}
      {r.chaseCount > 0 && <span className="small muted">chased {r.chaseCount}×</span>}
    </span>
  );
}

/** Report-quality rating 1–5 (E04-BL-015). */
export function RatingForm({ referral, props, onDone }: { referral: Pick<Referral, 'id' | 'ref' | 'rating'>; props: ScreenProps; onDone: () => void }) {
  const api = treatmentApi(props.client);
  const [rating, setRating] = useState(referral.rating ?? 0);
  const [comment, setComment] = useState('');
  const action = useAction(props.onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!rating) return;
    if (await action.run(async () => { await api.rateReferral(referral.id, rating, comment.trim() || undefined); return `Report for ${referral.ref} rated ${rating}/5.`; })) onDone();
  }
  return (
    <form className="form" onSubmit={submit} aria-label={`Rate report ${referral.ref}`}>
      <StarRating value={rating} onChange={setRating} name={`rate-${referral.id}`} legend={`Report quality for ${referral.ref}`} />
      <Field label="Comment">{(id) => <input id={id} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>
      <button type="submit" className="small-button" disabled={!rating || action.busy}>Save rating</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** Referral detail with status timeline, data set, letter / report links and actions. */
export function ReferralDetailPanel({ id, props, onChanged }: { id: string; props: ScreenProps; onChanged?: () => void }) {
  const { client, session, onSessionExpired } = props;
  const api = treatmentApi(client);
  const detail = useAsync(() => api.referral(id), onSessionExpired, [id]);
  const canWrite = can(session.roles, 'referralWrite');
  const action = useAction(onSessionExpired);
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const r = detail.data;

  const changed = () => {
    detail.reload();
    onChanged?.();
  };

  if (detail.loading && !r) return <Loading label="Loading referral…" />;
  if (detail.error) return <ErrorAlert error={detail.error} />;
  if (!r) return null;
  if (editing) return <ReferralWizard props={props} caseId={r.caseId} existing={r} onDone={() => { setEditing(false); changed(); }} />;

  const due = reportDueLabel(r);
  return (
    <section className="tile" aria-labelledby={`rd-${r.id}`}>
      <h2 id={`rd-${r.id}`}>
        {r.ref} – {r.serviceName} <ReferralStatus r={r} />
      </h2>
      <StatusSteps steps={referralTimeline(r)} label={`Status timeline for ${r.ref}`} />
      <Dl
        items={[
          ['Employee', <>{r.displayName} <span className="small muted">{r.employeeId}</span></>],
          ['Case', <a href={caseLink(r.caseId, 'referrals')}>{r.caseRef}</a>],
          ['Provider', <>{r.providerName} <ProviderBadges p={{ preferred: r.preferred, contract: r.contracted ? { ref: '', status: 'ACTIVE' } : null }} /></>],
          ['Urgency', humanise(r.urgency)],
          ['Channel', humanise(r.channel)],
          ['Appointment', formatDate(r.appointmentAt)],
          ['Report due', due ? <ToneBadge tone={due.tone}>{due.label}</ToneBadge> : formatDate(r.reportDueAt)],
          ['Reason', r.reason],
          ['Question', r.question],
          ['Rating', <Stars value={r.rating} />],
        ]}
      />
      {Object.keys(r.dataSet ?? {}).length > 0 && (
        <>
          <h3>Data set</h3>
          <Dl items={Object.entries(r.dataSet).map(([k, v]) => [humanise(k), v])} />
        </>
      )}
      <ul className="plain-list">
        {r.letterDocumentId && <li><a href={documentContentUrl(r.letterDocumentId)} target="_blank" rel="noopener">Referral letter</a></li>}
        {r.reportDocumentId && <li><a href={documentContentUrl(r.reportDocumentId)} target="_blank" rel="noopener">Provider report</a> <span className="small muted">received {formatDate(r.reportReceivedAt)}</span></li>}
        {(r.attachments ?? []).map((a) => {
          const docId = typeof a === 'string' ? a : a.documentId;
          return <li key={docId}><a href={documentContentUrl(docId)} target="_blank" rel="noopener">{typeof a === 'string' ? 'Attachment' : (a.title ?? 'Attachment')}</a></li>;
        })}
      </ul>
      {canWrite && (
        <div className="actions">
          {r.status === 'DRAFT' && (
            <>
              <button type="button" disabled={action.busy} onClick={() => void action.run(async () => { const s = await api.sendReferral(r.id); changed(); return `Referral ${s.ref} sent.`; })}>Send</button>
              <button type="button" className="secondary" onClick={() => setEditing(true)}>Edit draft</button>
            </>
          )}
          {!['CLOSED', 'CANCELLED', 'DECLINED', 'REPORT_RECEIVED'].includes(r.status) && (
            <button type="button" className="secondary" onClick={() => { setReason(''); setCancelling(true); }}>Cancel referral</button>
          )}
        </div>
      )}
      {canWrite && (r.status === 'REPORT_RECEIVED' || r.status === 'CLOSED') && <RatingForm key={r.rating ?? 0} referral={r} props={props} onDone={changed} />}
      <ActionFeedback error={action.error} message={action.message} />
      {cancelling && (
        <Dialog title={`Cancel ${r.ref}`} onClose={() => setCancelling(false)}>
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              if (!reason.trim()) return;
              void action.run(async () => { await api.cancelReferral(r.id, reason.trim()); setCancelling(false); changed(); return `${r.ref} cancelled.`; });
            }}
          >
            <Field label="Reason" required>{(fid) => <textarea id={fid} rows={2} required value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
            <div className="actions">
              <button type="submit" disabled={!reason.trim() || action.busy}>Cancel referral</button>
              <button type="button" className="secondary" onClick={() => setCancelling(false)}>Keep</button>
            </div>
          </form>
        </Dialog>
      )}
    </section>
  );
}

/** Referral table (worklist and case tab); optional selection for bulk remind. */
export function ReferralTable({ rows, caption, selected, onToggle, showCase = true }: { rows: Referral[]; caption: string; selected?: Set<string>; onToggle?: (id: string) => void; showCase?: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            {onToggle && <th scope="col"><span className="visually-hidden">Select</span></th>}
            <th scope="col">Referral</th>
            {showCase && <th scope="col">Employee / case</th>}
            <th scope="col">Service</th>
            <th scope="col">Provider</th>
            <th scope="col">Urgency</th>
            <th scope="col">Status</th>
            <th scope="col">Appointment</th>
            <th scope="col">Report</th>
            <th scope="col">Rating</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const due = reportDueLabel(r);
            return (
              <tr key={r.id} className={r.overdue ? 'row-breached' : undefined}>
                {onToggle && (
                  <td><input type="checkbox" aria-label={`Select ${r.ref}`} checked={selected?.has(r.id) ?? false} onChange={() => onToggle(r.id)} /></td>
                )}
                <th scope="row"><a href={refLink(r.id)}>{r.ref}</a></th>
                {showCase && <td>{r.displayName} <a className="small" href={caseLink(r.caseId)}>{r.caseRef}</a></td>}
                <td>{r.serviceName}</td>
                <td>{r.providerName} <ProviderBadges p={{ preferred: r.preferred, contract: r.contracted ? { ref: '', status: 'ACTIVE' } : null }} /></td>
                <td>{r.urgency === 'URGENT' ? <ToneBadge tone="red">Urgent</ToneBadge> : 'Routine'}</td>
                <td><ReferralStatus r={r} /></td>
                <td className="small">{formatDate(r.appointmentAt)}</td>
                <td>{r.reportDocumentId ? <a href={documentContentUrl(r.reportDocumentId)} target="_blank" rel="noopener">Report</a> : due ? <ToneBadge tone={due.tone}>{due.label}</ToneBadge> : '–'}</td>
                <td><Stars value={r.rating} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
