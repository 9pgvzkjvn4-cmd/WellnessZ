import { useState, type FormEvent } from 'react';
import type { FitnessDecision } from '../../api/fitness';
import { documentContentUrl } from '../../api/platform';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ActionFeedback, StatusBadge } from '../../components/ui';

export function SignoffDialog({ decision, onClose, onSubmit, busy, error }: { decision: FitnessDecision; onClose: () => void; onSubmit: (approve: boolean, note: string) => void; busy: boolean; error?: unknown }) {
  const [approve, setApprove] = useState<'yes' | 'no'>('yes');
  const [note, setNote] = useState('');
  function submit(e: FormEvent) {
    e.preventDefault();
    if (approve === 'no' && !note.trim()) return;
    onSubmit(approve === 'yes', note.trim());
  }
  return (
    <Dialog title={`Sign-off: ${decision.standardTitle ?? humanise(decision.standardCode)}`} onClose={onClose}>
      <p>
        {decision.employeeName ?? decision.employeeId} · {decision.caseRef} · outcome <strong>{humanise(decision.outcome)}</strong> · decided by {decision.decidedBy} {formatDate(decision.decidedAt)}
      </p>
      {decision.rationale && <p className="prewrap small">{decision.rationale}</p>}
      <form className="form" onSubmit={submit}>
        <fieldset>
          <legend>Decision</legend>
          <label className="checkbox">
            <input type="radio" name="signoff" checked={approve === 'yes'} onChange={() => setApprove('yes')} /> Approve
          </label>
          <label className="checkbox">
            <input type="radio" name="signoff" checked={approve === 'no'} onChange={() => setApprove('no')} /> Reject
          </label>
        </fieldset>
        <div className="field">
          <label htmlFor="signoff-note">Note{approve === 'no' ? ' (required when rejecting)' : ''}</label>
          <textarea id="signoff-note" rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="actions">
          <button type="submit" disabled={busy || (approve === 'no' && !note.trim())}>
            {approve === 'yes' ? 'Approve decision' : 'Reject decision'}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
      <ActionFeedback error={error} />
    </Dialog>
  );
}

/** Decision history with sign-off status, validity, restrictions and the certificate. */
export function DecisionHistory({ decisions, onSignoff, canSignoff }: { decisions: FitnessDecision[]; onSignoff?: (d: FitnessDecision) => void; canSignoff?: (d: FitnessDecision) => boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>Decision history</caption>
        <thead>
          <tr>
            <th scope="col">Standard</th>
            <th scope="col">Outcome</th>
            <th scope="col">Status / sign-off</th>
            <th scope="col">Validity</th>
            <th scope="col">Restrictions &amp; limitations</th>
            <th scope="col">Decided</th>
          </tr>
        </thead>
        <tbody>
          {decisions.map((d) => (
            <tr key={d.id}>
              <th scope="row">
                {d.standardTitle ?? humanise(d.standardCode)}
                {d.standardVersion != null && <div className="small muted">v{d.standardVersion}</div>}
              </th>
              <td>
                {humanise(d.outcome)}
                {d.kernelStatus && <div className="small muted">Status: {humanise(d.kernelStatus)}</div>}
              </td>
              <td>
                <StatusBadge status={d.status} />
                {d.signoffAt && (
                  <div className="small">
                    {d.status === 'REJECTED' ? 'Rejected' : 'Signed off'} by {d.signoffBy} {formatDate(d.signoffAt)}
                    {d.signoffNote ? ` – ${d.signoffNote}` : ''}
                  </div>
                )}
                {onSignoff && d.status === 'AWAITING_SIGNOFF' && (canSignoff?.(d) ?? true) && (
                  <button type="button" className="small-button" onClick={() => onSignoff(d)}>
                    Sign off
                  </button>
                )}
              </td>
              <td className="small">
                {formatDay(d.validFrom)} – {formatDay(d.validUntil)}
                <div>Review {formatDay(d.reviewDate)}</div>
              </td>
              <td className="small">
                {d.restrictions.map((r) => r.label).join('; ') || '–'}
                {d.limitationCodes.length > 0 && <div>Limitations: {d.limitationCodes.join(', ')}</div>}
              </td>
              <td className="small">
                {d.decidedBy} {formatDate(d.decidedAt)}
                {d.certificateDocumentId && (
                  <div>
                    <a href={documentContentUrl(d.certificateDocumentId)} target="_blank" rel="noopener">
                      Certificate / report
                    </a>
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
