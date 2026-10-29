import { useState } from 'react';
import type { DuplicateCandidate } from '../../api/types';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

export function DuplicatesTab({ client, caseId, detail, refresh, onSessionExpired }: CaseTabProps) {
  const candidates = useAsync(() => client.api<DuplicateCandidate[]>(casePath(caseId, '/duplicates')), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const merged = detail.status === 'MERGED';

  async function merge(c: DuplicateCandidate) {
    const reason = (reasons[c.caseId] ?? '').trim();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/merge'), { method: 'POST', body: { intoCaseId: c.caseId, reason } });
      return `${detail.caseRef} merged into ${c.caseRef}. Sources, links and attachments were kept on ${c.caseRef}.`;
    });
    if (ok) refresh();
  }

  async function link(c: DuplicateCandidate) {
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/links'), { method: 'POST', body: { type: 'CASE', ref: c.caseRef } });
      return `Linked to ${c.caseRef}.`;
    });
    if (ok) refresh();
  }

  return (
    <div>
      <h2>Possible duplicates</h2>
      <p className="small muted">Merging closes this case as MERGED and keeps its sources, links and attachments on the target case. Use “Link” when both cases should stay open.</p>
      {candidates.loading && <Loading />}
      {!!candidates.error && <ErrorAlert error={candidates.error} />}
      {candidates.data && candidates.data.length === 0 && <Empty>No duplicate candidates found.</Empty>}
      <ActionFeedback error={action.error} message={action.message} />
      <ul className="card-list">
        {(candidates.data ?? []).map((c) => (
          <li key={c.caseId} className="card">
            <div className="card-row">
              <span>
                <a href={`#/cases/${encodeURIComponent(c.caseId)}`}>{c.caseRef}</a> <StatusBadge status={c.status} /> <span className="small muted">created {formatDate(c.createdAt)}</span>
              </span>
              <span className="badge badge-muted">Match score {c.score}</span>
            </div>
            <p className="small">
              Reasons: {c.reasons.map((r) => humanise(r)).join(' · ') || '–'}
            </p>
            {!merged && (
              <div className="card-actions">
                <label htmlFor={`merge-${c.caseId}`} className="visually-hidden">Reason for merging into {c.caseRef}</label>
                <input
                  id={`merge-${c.caseId}`}
                  placeholder="Reason for merge (required)"
                  maxLength={500}
                  value={reasons[c.caseId] ?? ''}
                  onChange={(e) => setReasons({ ...reasons, [c.caseId]: e.target.value })}
                />
                <button type="button" className="small-button" disabled={action.busy || !(reasons[c.caseId] ?? '').trim()} onClick={() => void merge(c)}>
                  Merge into {c.caseRef}
                </button>
                <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void link(c)}>
                  Link instead
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
