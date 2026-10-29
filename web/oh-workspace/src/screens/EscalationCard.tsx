import { useState } from 'react';
import { seg } from '../api/links';
import type { Escalation } from '../api/types';
import { can } from '../auth/roles';
import { SlaBadge } from '../components/badges';
import { formatDate, humanise } from '../components/format';
import { ActionFeedback, StatusBadge } from '../components/ui';
import { useAction } from '../components/useAsync';
import { slaFromDue } from '../domain/sla';
import type { ScreenProps } from './types';

/** One escalation with acknowledge / decide actions for OH_PHYSICIAN, AME, CMO. */
export function EscalationCard({ client, session, onSessionExpired, escalation: e, onChanged, showCase }: ScreenProps & { escalation: Escalation; onChanged: () => void; showCase?: boolean }) {
  const action = useAction(onSessionExpired);
  const [decision, setDecision] = useState('');
  const canDecide = can(session.roles, 'escalationDecide');
  const open = !e.decidedAt && e.status !== 'DECIDED' && e.status !== 'CLOSED';

  async function acknowledge() {
    if (await action.run(async () => { await client.api(`/escalations/${seg(e.id)}/acknowledge`, { method: 'POST' }); return 'Acknowledged.'; })) onChanged();
  }
  async function decide() {
    if (await action.run(async () => { await client.api(`/escalations/${seg(e.id)}/decision`, { method: 'POST', body: { decision: decision.trim() } }); return 'Decision recorded on the case timeline.'; })) {
      setDecision('');
      onChanged();
    }
  }

  return (
    <div className={`card ${e.level === 'EMERGENCY_SERVICES' ? 'card-emergency' : ''}`}>
      <div className="card-row">
        <span>
          <strong>{humanise(e.level)}</strong> · {humanise(e.reasonCode)}
          {showCase && e.caseRef && (
            <>
              {' '}· <a href={`#/cases/${encodeURIComponent(e.caseId)}/escalation`}>{e.caseRef}</a>
            </>
          )}
        </span>
        <span className="badge-row">
          <StatusBadge status={e.status} />
          {open && <SlaBadge sla={e.sla ?? slaFromDue(e.dueAt)} />}
        </span>
      </div>
      {e.note && <p className="small prewrap">{e.note}</p>}
      <p className="small muted">
        Raised {formatDate(e.raisedAt)} {e.raisedBy ? `by ${e.raisedBy}` : ''}
        {e.acknowledgedAt ? ` · acknowledged ${formatDate(e.acknowledgedAt)} by ${e.acknowledgedBy ?? '–'}` : ''}
      </p>
      {e.decision && (
        <p className="small">
          <strong>Decision:</strong> {e.decision} <span className="muted">({formatDate(e.decidedAt)}{e.decidedBy ? `, ${e.decidedBy}` : ''})</span>
        </p>
      )}
      {canDecide && open && (
        <div className="card-actions">
          {!e.acknowledgedAt && (
            <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void acknowledge()}>
              Acknowledge
            </button>
          )}
          <label htmlFor={`dec-${e.id}`} className="visually-hidden">Decision</label>
          <input id={`dec-${e.id}`} placeholder="Decision…" maxLength={2000} value={decision} onChange={(ev) => setDecision(ev.target.value)} />
          <button type="button" className="small-button" disabled={action.busy || !decision.trim()} onClick={() => void decide()}>
            Record decision
          </button>
        </div>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}
