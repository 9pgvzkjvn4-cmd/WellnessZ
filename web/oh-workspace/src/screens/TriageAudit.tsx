import { useState, type FormEvent } from 'react';
import { buildQuery, seg } from '../api/links';
import type { Urgency } from '../api/types';
import { can } from '../auth/roles';
import { UrgencyBadge } from '../components/badges';
import { formatDate, isoDay } from '../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../components/ui';
import { useAction, useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

interface AuditSample {
  id: string;
  assessmentId?: string;
  caseId?: string;
  caseRef?: string;
  protocolCode?: string;
  computedUrgency?: Urgency;
  finalUrgency?: Urgency;
  assessedBy?: string;
  assessedAt?: string;
  reviewed?: boolean;
  agree?: boolean | null;
  comment?: string | null;
}

function ReviewRow({ s, onReview, busy }: { s: AuditSample; onReview: (agree: boolean, comment: string) => void; busy: boolean }) {
  const [comment, setComment] = useState('');
  return (
    <li className="card">
      <div className="card-row">
        <span>
          {s.caseId ? <a href={`#/cases/${encodeURIComponent(s.caseId)}/triage`}>{s.caseRef ?? 'Case'}</a> : s.caseRef} · {s.protocolCode}
        </span>
        <span className="small muted">
          {s.assessedBy} · {formatDate(s.assessedAt)}
        </span>
      </div>
      <p className="small">
        Nurse urgency <UrgencyBadge urgency={s.finalUrgency} /> (protocol <UrgencyBadge urgency={s.computedUrgency} />)
      </p>
      {s.reviewed ? (
        <p className="small">
          Reviewed: <strong>{s.agree ? 'Agree' : 'Disagree'}</strong> {s.comment ? `– ${s.comment}` : ''}
        </p>
      ) : (
        <div className="card-actions">
          <label htmlFor={`rv-${s.id}`} className="visually-hidden">Review comment</label>
          <input id={`rv-${s.id}`} placeholder="Comment" maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} />
          <button type="button" className="small-button" disabled={busy} onClick={() => onReview(true, comment)}>
            Agree
          </button>
          <button type="button" className="secondary small-button" disabled={busy || !comment.trim()} onClick={() => onReview(false, comment)}>
            Disagree
          </button>
        </div>
      )}
    </li>
  );
}

/** E02-BL-016 triage quality audit: sample, physician review, agreement report. */
export function TriageAudit({ client, session, onSessionExpired }: ScreenProps) {
  const canSample = can(session.roles, 'triageAuditSample');
  const samples = useAsync(() => client.api<AuditSample[]>('/triage/audit/samples'), onSessionExpired);
  const report = useAsync(() => client.api<{ reviewed: number; agreementRate: number }>('/triage/audit/report'), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [range, setRange] = useState(() => {
    const to = new Date();
    const from = new Date(to.getTime() - 30 * 86_400_000);
    return { from: isoDay(from), to: isoDay(to), percent: 10 };
  });

  async function sample(e: FormEvent) {
    e.preventDefault();
    if (await action.run(async () => { await client.api(`/triage/audit/sample${buildQuery(range)}`, { method: 'POST' }); return 'Sample created.'; })) samples.reload();
  }

  async function review(s: AuditSample, agree: boolean, comment: string) {
    if (await action.run(async () => { await client.api(`/triage/audit/samples/${seg(s.id)}/review`, { method: 'POST', body: { agree, comment: comment.trim() } }); return 'Review saved.'; })) {
      samples.reload();
      report.reload();
    }
  }

  const rate = report.data?.agreementRate;
  const pct = rate === undefined ? undefined : rate <= 1 ? Math.round(rate * 1000) / 10 : rate;

  return (
    <section>
      <PageTitle sub="KPI: physician agrees with nurse triage urgency ≥ 90 %.">Triage quality audit</PageTitle>
      <div className="tiles">
        <div className="tile">
          <h2>Reviewed</h2>
          <p className="tile-value">{report.data?.reviewed ?? '–'}</p>
        </div>
        <div className="tile">
          <h2>Agreement</h2>
          <p className={`tile-value ${pct !== undefined && pct < 90 ? 'text-red' : ''}`}>{pct === undefined ? '–' : `${pct} %`}</p>
          {pct !== undefined && <p className="small">{pct >= 90 ? '✓ Meets target (≥ 90 %)' : '✕ Below target (90 %)'}</p>}
        </div>
      </div>
      {!!report.error && <ErrorAlert error={report.error} />}
      {canSample && (
        <form className="form inline-form" onSubmit={sample}>
          <Field label="From">{(id) => <input id={id} type="date" required value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
          <Field label="To">{(id) => <input id={id} type="date" required value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
          <Field label="Percent">{(id) => <input id={id} type="number" min={1} max={100} required value={range.percent} onChange={(e) => setRange({ ...range, percent: e.target.valueAsNumber || 10 })} />}</Field>
          <button type="submit" disabled={action.busy}>
            Draw sample
          </button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      <h2>Samples</h2>
      {samples.loading && <Loading />}
      {!!samples.error && <ErrorAlert error={samples.error} />}
      {samples.data && samples.data.length === 0 && <Empty>No samples.</Empty>}
      <ul className="card-list">
        {(samples.data ?? []).map((s) => (
          <ReviewRow key={s.id} s={s} busy={action.busy} onReview={(a, c) => void review(s, a, c)} />
        ))}
      </ul>
    </section>
  );
}
