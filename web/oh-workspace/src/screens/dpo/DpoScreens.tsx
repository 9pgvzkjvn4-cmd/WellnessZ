import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import { platformApi, RETENTION_TRIGGERS, type BreakGlassEvent } from '../../api/platform';
import { can } from '../../auth/roles';
import { ConfigTable, type FieldSpec } from '../../components/ConfigTable';
import { Dialog } from '../../components/Dialog';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

// ---------------------------------------------------------------- Break-glass reviews
/** `#/dpo/break-glass` – every emergency access must be reviewed within 24 h (E03-BL-021). */
export function BreakGlassReviews({ client, onSessionExpired }: ScreenProps) {
  const api = platformApi(client);
  const [pendingOnly, setPendingOnly] = useState(true);
  const list = useAsync(() => api.breakGlassEvents(pendingOnly ? 'PENDING' : undefined), onSessionExpired, [pendingOnly]);
  const action = useAction(onSessionExpired);
  const [reviewing, setReviewing] = useState<BreakGlassEvent>();
  const [review, setReview] = useState<{ outcome: 'APPROPRIATE' | 'INAPPROPRIATE' | ''; note: string }>({ outcome: '', note: '' });
  const [now] = useState(() => Date.now());

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!reviewing || !review.outcome || !review.note.trim()) return;
    const ev = reviewing;
    const outcome = review.outcome;
    const ok = await action.run(async () => {
      await api.reviewBreakGlass(ev.id, outcome, review.note.trim());
      return `Access by ${ev.grantedTo} reviewed as ${humanise(outcome).toLowerCase()}.`;
    });
    if (ok) {
      setReviewing(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Emergency access to restricted records. Review each access within 24 hours; inappropriate use is escalated.">Break-glass reviews</PageTitle>
      <label className="checkbox">
        <input type="checkbox" checked={pendingOnly} onChange={(e) => setPendingOnly(e.target.checked)} /> Pending reviews only
      </label>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No {pendingOnly ? 'pending ' : ''}break-glass events.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} event{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Granted to</th>
                <th scope="col">Employee record</th>
                <th scope="col">Justification</th>
                <th scope="col">Granted</th>
                <th scope="col">Review due</th>
                <th scope="col">Review</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((b) => {
                const overdue = b.review === 'PENDING' && !!b.reviewDueAt && Date.parse(b.reviewDueAt) < now;
                return (
                  <tr key={b.id} className={overdue ? 'row-breached' : undefined}>
                    <th scope="row">{b.grantedTo}</th>
                    <td className="mono">{b.employeeId}</td>
                    <td className="prewrap small">{b.reason}</td>
                    <td className="small">{formatDate(b.grantedAt)}<div className="muted">expires {formatDate(b.expiresAt)}</div></td>
                    <td className="small">
                      {formatDate(b.reviewDueAt)}{' '}
                      {overdue && <span className="badge badge-overdue"><span aria-hidden="true">■ </span>Overdue</span>}
                    </td>
                    <td>
                      <StatusBadge status={b.review} />
                      {b.reviewedBy && <div className="small muted">{b.reviewedBy} {formatDate(b.reviewedAt)}</div>}
                    </td>
                    <td>
                      {b.review === 'PENDING' && (
                        <button type="button" className="small-button" onClick={() => { setReview({ outcome: '', note: '' }); setReviewing(b); }} aria-label={`Review access by ${b.grantedTo}`}>
                          Review
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {reviewing && (
        <Dialog title={`Review emergency access by ${reviewing.grantedTo}`} onClose={() => setReviewing(undefined)}>
          <p className="small">
            Record {reviewing.employeeId}, granted {formatDate(reviewing.grantedAt)}.
          </p>
          <blockquote className="prewrap">{reviewing.reason}</blockquote>
          <form className="form" onSubmit={submit}>
            <fieldset>
              <legend>Outcome</legend>
              <label className="checkbox">
                <input type="radio" name="bg-review" checked={review.outcome === 'APPROPRIATE'} onChange={() => setReview({ ...review, outcome: 'APPROPRIATE' })} /> Appropriate
              </label>
              <label className="checkbox">
                <input type="radio" name="bg-review" checked={review.outcome === 'INAPPROPRIATE'} onChange={() => setReview({ ...review, outcome: 'INAPPROPRIATE' })} /> Inappropriate
              </label>
            </fieldset>
            <Field label="Review note" required>
              {(id) => <textarea id={id} rows={3} maxLength={2000} value={review.note} onChange={(e) => setReview({ ...review, note: e.target.value })} />}
            </Field>
            <div className="actions">
              <button type="submit" disabled={action.busy || !review.outcome || !review.note.trim()}>
                Record review
              </button>
              <button type="button" className="secondary" onClick={() => setReviewing(undefined)}>
                Cancel
              </button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Monthly report (DPO, CMO)
export function BreakGlassReport({ client, onSessionExpired }: ScreenProps) {
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7));
  const [applied, setApplied] = useState(month);
  const r = useAsync(() => platformApi(client).breakGlassReport(applied), onSessionExpired, [applied]);
  const d = r.data;
  return (
    <section>
      <PageTitle sub="Monthly break-glass report for the DPO and CMO.">Break-glass report</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); if (/^\d{4}-\d{2}$/.test(month)) setApplied(month); }}>
        <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
        <button type="submit" className="secondary">Show</button>
      </form>
      {r.loading && <Loading />}
      {!!r.error && <ErrorAlert error={r.error} />}
      {d && (
        <>
          <div className="tiles">
            <div className="tile"><span className="tile-value">{d.total}</span> accesses</div>
            <div className="tile"><span className="tile-value">{d.appropriate}</span> appropriate</div>
            <div className="tile"><span className={`tile-value${d.inappropriate > 0 ? ' text-red' : ''}`}>{d.inappropriate}</span> inappropriate</div>
            <div className="tile"><span className="tile-value">{d.pending}</span> pending</div>
            <div className="tile"><span className={`tile-value${d.overdueReviews > 0 ? ' text-red' : ''}`}>{d.overdueReviews}</span> reviews overdue</div>
          </div>
          {d.byUser.length === 0 ? (
            <Empty>No break-glass access in {d.month}.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <caption>By user</caption>
                <thead>
                  <tr>
                    <th scope="col">User</th>
                    <th scope="col" className="num">Accesses</th>
                  </tr>
                </thead>
                <tbody>
                  {d.byUser.map((u) => (
                    <tr key={u.username}>
                      <th scope="row">{u.username}</th>
                      <td className="num">{u.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Disclosure log
/** `#/dpo/disclosures` – every document share / withdrawal for one employee (E03-BL-022). */
export function DisclosureLog({ client, onSessionExpired }: ScreenProps) {
  const [emp, setEmp] = useState('');
  const [applied, setApplied] = useState('');
  const r = useAsync(() => (applied ? platformApi(client).disclosures(applied) : Promise.resolve(undefined)), onSessionExpired, [applied]);
  const rows = r.data ?? [];
  return (
    <section>
      <PageTitle sub="Which parties received an employee's documents, under which consent, and whether the consent is still active.">Disclosure log</PageTitle>
      <form className="filters inline-form" role="search" onSubmit={(e) => { e.preventDefault(); setApplied(emp.trim()); }}>
        <Field label="Employee ID">{(id) => <input id={id} required maxLength={40} value={emp} onChange={(e) => setEmp(e.target.value)} />}</Field>
        <button type="submit" className="secondary" disabled={!emp.trim()}>Show disclosures</button>
      </form>
      {applied && r.loading && <Loading />}
      {!!r.error && <ErrorAlert error={r.error} />}
      {applied && r.data && rows.length === 0 && <Empty>No disclosures for {applied}.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Disclosures for {applied}</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Recipient</th>
                <th scope="col">Document</th>
                <th scope="col">Purpose</th>
                <th scope="col">Consent</th>
                <th scope="col">Disclosed by</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((x, i) => (
                <tr key={`${x.at}-${i}`}>
                  <td>{formatDate(x.at)}</td>
                  <th scope="row">{humanise(x.recipientType)}{x.recipientName ? ` – ${x.recipientName}` : ''}</th>
                  <td>{x.documentTitle ?? '–'}</td>
                  <td>{x.purpose ?? '–'}</td>
                  <td className="mono">{x.consentId ?? '–'}</td>
                  <td>{x.disclosedBy ?? '–'}</td>
                  <td><StatusBadge status={x.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- Retention & legal hold
const RETENTION_FIELDS: FieldSpec[] = [
  { key: 'recordClass', label: 'Record class', kind: 'text', required: true },
  { key: 'retentionYears', label: 'Retention (years)', kind: 'number', required: true },
  { key: 'trigger', label: 'Counted from', kind: 'select', options: RETENTION_TRIGGERS, required: true },
  { key: 'legalBasis', label: 'Legal basis', kind: 'text' },
  { key: 'approvedBy', label: 'Approved by', kind: 'text', readOnly: true },
  { key: 'approvedAt', label: 'Approved', kind: 'text', readOnly: true },
];

function LegalHold({ client, onSessionExpired, documentId, onDone }: { client: ScreenProps['client']; onSessionExpired: () => void; documentId: string; onDone: () => void }) {
  const api = platformApi(client);
  const action = useAction(onSessionExpired);
  const [doc, setDoc] = useState(documentId);
  const [reason, setReason] = useState('');
  async function run(mode: 'set' | 'release') {
    if (!doc.trim() || !reason.trim()) return;
    const ok = await action.run(async () => {
      if (mode === 'set') await api.setLegalHold(doc.trim(), reason.trim());
      else await api.releaseLegalHold(doc.trim(), reason.trim());
      return mode === 'set' ? 'Legal hold placed – the document is excluded from retention deletion.' : 'Legal hold released.';
    });
    if (ok) {
      setReason('');
      onDone();
    }
  }
  return (
    <form className="form tile" onSubmit={(e) => { e.preventDefault(); void run('set'); }}>
      <h2>Legal hold</h2>
      <Field label="Document id" required>{(id) => <input id={id} maxLength={80} value={doc} onChange={(e) => setDoc(e.target.value)} />}</Field>
      <Field label="Reason" required>{(id) => <textarea id={id} rows={2} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      <div className="actions">
        <button type="submit" disabled={action.busy || !doc.trim() || !reason.trim()}>Place legal hold</button>
        <button type="button" className="secondary" disabled={action.busy || !doc.trim() || !reason.trim()} onClick={() => void run('release')}>
          Release legal hold
        </button>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** `#/dpo/retention` – retention schedule (DPO edits; OH_ADMIN reads), records past retention, legal hold (E03-BL-017). */
export function RetentionScreen({ client, session, onSessionExpired }: ScreenProps) {
  const isDpo = can(session.roles, 'retentionWrite');
  const due = useAsync(() => (isDpo ? platformApi(client).retentionDue() : Promise.resolve([])), onSessionExpired);
  const [holdDoc, setHoldDoc] = useState('');
  const rows = due.data ?? [];
  return (
    <section>
      <PageTitle sub="OH records are kept per local law and aviation record rules; exposure records long-term. Records on legal hold are never deleted.">Retention &amp; legal hold</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Retention schedule"
        fields={RETENTION_FIELDS}
        listPath="/documents/retention"
        updatePath={(r) => `/documents/retention/${seg(String(r.recordClass))}`}
        rowKey={(r, i) => String(r.recordClass ?? i)}
        canWrite={isDpo}
        mode="perRow"
        createMethod="PUT"
        newRow={{ recordClass: '', retentionYears: 40, trigger: 'LAST_ENCOUNTER', legalBasis: '' }}
      />
      {isDpo && (
        <>
          <h2>Past retention (not on legal hold)</h2>
          {due.loading && <Loading />}
          {!!due.error && <ErrorAlert error={due.error} />}
          {due.data && rows.length === 0 && <Empty>No records are past retention.</Empty>}
          {rows.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption>{rows.length} record{rows.length === 1 ? '' : 's'} past retention</caption>
                <thead>
                  <tr>
                    <th scope="col">Record</th>
                    <th scope="col">Class</th>
                    <th scope="col">Employee</th>
                    <th scope="col">Retention ended</th>
                    <th scope="col"><span className="visually-hidden">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => {
                    const id = r.documentId ?? r.id ?? '';
                    return (
                      <tr key={id || i}>
                        <th scope="row">{r.title ?? r.documentNumber ?? id}</th>
                        <td>{humanise(r.recordClass)}</td>
                        <td className="mono">{r.employeeId ?? '–'}</td>
                        <td>{formatDay(r.retainUntil ?? r.retentionEndsAt ?? r.dueSince)}</td>
                        <td>
                          {id && (
                            <button type="button" className="secondary small-button" onClick={() => setHoldDoc(id)}>
                              Legal hold…
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          <LegalHold key={holdDoc} client={client} onSessionExpired={onSessionExpired} documentId={holdDoc} onDone={due.reload} />
        </>
      )}
    </section>
  );
}
