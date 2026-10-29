import { useState } from 'react';
import { seg } from '../../api/links';
import type { Protocol, ProtocolSummary, Urgency } from '../../api/types';
import { can } from '../../auth/roles';
import { RedFlagMark, UrgencyBadge } from '../../components/badges';
import { humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

function branchText(b?: { outcome?: string; next?: string }): string {
  if (!b) return '–';
  return b.outcome ? `→ ${humanise(b.outcome)}` : b.next ? `→ ${b.next}` : '–';
}

function ProtocolDetail({ client, code, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { code: string }) {
  const p = useAsync(() => client.api<Protocol>(`/triage/protocols/${seg(code)}`), onSessionExpired);
  if (p.loading) return <Loading />;
  if (p.error) return <ErrorAlert error={p.error} />;
  if (!p.data) return null;
  return (
    <div className="protocol-detail">
      <p className="small muted">Start: {p.data.start}</p>
      <ol className="plain-list">
        {p.data.questions.map((q) => (
          <li key={q.id} className={q.redFlag ? 'question question-redflag' : 'question'}>
            <strong>{q.id}</strong> {q.redFlag && <RedFlagMark />} {q.text} <span className="small muted">({humanise(q.type)})</span>
            <div className="small">
              {q.onYes && <>Yes {branchText(q.onYes)} · </>}
              {q.onNo && <>No {branchText(q.onNo)} · </>}
              {q.rules?.map((r, i) => (
                <span key={i}>{r.else ? 'else' : r.if} {branchText(r)} · </span>
              ))}
              {q.next && <>next → {q.next}</>}
            </div>
          </li>
        ))}
      </ol>
      <h3>Outcomes</h3>
      <ul className="plain-list">
        {Object.entries(p.data.outcomes).map(([u, o]) => (
          <li key={u}>
            <UrgencyBadge urgency={u as Urgency} /> – {o?.disposition}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Triage protocols (E02-F01) – DRAFT until the CMO approves a version. */
export function Protocols({ client, session, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => client.api<ProtocolSummary[]>('/triage/protocols'), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [open, setOpen] = useState<string>();
  const canApprove = can(session.roles, 'protocolApprove');

  async function approve(p: ProtocolSummary) {
    if (await action.run(async () => { await client.api(`/triage/protocols/${seg(p.code)}/approve`, { method: 'POST', body: { version: p.version } }); return `${p.title} v${p.version} approved.`; })) list.reload();
  }

  return (
    <section>
      <PageTitle sub="Protocol content is owned by the CMO and versioned. Draft protocols can be used but are flagged during triage.">Triage protocols</PageTitle>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && list.data.length === 0 && <Empty>No protocols.</Empty>}
      <ul className="card-list">
        {(list.data ?? []).map((p) => (
          <li key={p.code} className="card">
            <div className="card-row">
              <span>
                <strong>{p.title}</strong> <span className="mono small">{p.code}</span> v{p.version} {p.presentation && <span className="small muted">· {humanise(p.presentation)}</span>}
              </span>
              <span className="badge-row">
                <StatusBadge status={p.status} />
                <button type="button" className="link-button" aria-expanded={open === p.code} onClick={() => setOpen(open === p.code ? undefined : p.code)}>
                  {open === p.code ? 'Hide' : 'View'}
                </button>
                {canApprove && p.status !== 'APPROVED' && (
                  <button type="button" className="small-button" disabled={action.busy} onClick={() => void approve(p)}>
                    Approve v{p.version}
                  </button>
                )}
              </span>
            </div>
            {open === p.code && <ProtocolDetail client={client} code={p.code} onSessionExpired={onSessionExpired} />}
          </li>
        ))}
      </ul>
    </section>
  );
}
