import { useState, type FormEvent } from 'react';
import { attachmentUrl } from '../../api/links';
import { WAITING_STATES, type CaseLink, type WaitingState } from '../../api/types';
import { formatBytes, formatDate, formatDay, humanise } from '../../components/format';
import { ActionFeedback, Dl, Field, SelectOptions } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

const LINK_TYPES: CaseLink['type'][] = ['INCIDENT', 'CASE', 'CLAIM', 'SURVEILLANCE', 'ENCOUNTER'];

function renderValue(v: unknown): string {
  if (v === null || v === undefined || v === '') return '–';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (Array.isArray(v)) return v.map((x) => renderValue(x)).join(', ');
  if (typeof v === 'object') return Object.entries(v as Record<string, unknown>).map(([k, x]) => `${humanise(k)}: ${renderValue(x)}`).join('; ');
  return String(v);
}

export function OverviewTab({ client, caseId, detail: d, refresh, onSessionExpired }: CaseTabProps) {
  const action = useAction(onSessionExpired);
  const [linkType, setLinkType] = useState<CaseLink['type']>('INCIDENT');
  const [linkRef, setLinkRef] = useState('');
  const [waiting, setWaiting] = useState<WaitingState>(d.waitingState ?? 'NONE');
  const [waitingNote, setWaitingNote] = useState('');

  async function addLink(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/links'), { method: 'POST', body: { type: linkType, ref: linkRef.trim() } });
      return 'Link added.';
    });
    if (ok) {
      setLinkRef('');
      refresh();
    }
  }

  async function saveWaiting(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/waiting-state'), { method: 'POST', body: { waitingState: waiting, ...(waitingNote.trim() ? { note: waitingNote.trim() } : {}) } });
      return waiting === 'NONE' ? 'Waiting state cleared – SLA clock resumed.' : 'Waiting state set – SLA clock paused.';
    });
    if (ok) {
      setWaitingNote('');
      refresh();
    }
  }

  const intake = d.intakeDetails && typeof d.intakeDetails === 'object' ? Object.entries(d.intakeDetails) : [];

  return (
    <div className="grid-2">
      <div>
        <h2>Employee (HRIS)</h2>
        {d.hrisOverride && (
          <div className="alert alert-warning" role="status">
            <strong>HRIS data overridden</strong> – one or more identity fields were changed manually and are flagged for review.
          </div>
        )}
        <Dl
          items={[
            ['Employee', `${d.employeeName} (${d.employeeId})`],
            ['Subsidiary', d.subsidiaryCode],
            ['Department', d.departmentCode],
            ['Job', d.jobCode],
            ['Location', d.locationCode],
            ['Cost centre', d.costCentre],
            ['Shift', d.shift],
            ['Language', d.language],
            ['Safety category', d.safetyCategory ? humanise(d.safetyCategory) : 'Not safety-critical'],
          ]}
        />
        <h2>Case</h2>
        <Dl
          items={[
            ['Category', humanise(d.category)],
            ['Case type', humanise(d.caseType)],
            ['Source', humanise(d.source)],
            ['Incident', d.incidentRef],
            ['Occurred on', formatDay(d.occurredOn)],
            ['Body part', d.bodyPart ? humanise(d.bodyPart) : undefined],
            ['Work-related', d.workRelated ? humanise(d.workRelated) : undefined],
            ['Recordability', humanise(d.recordability)],
            ['Fitness', d.fitnessStatus ? humanise(d.fitnessStatus) : undefined],
            ['Restriction until', formatDay(d.restrictionUntil)],
            ['Template', d.templateCode],
            ['Created', formatDate(d.createdAt)],
            ['Updated', formatDate(d.updatedAt)],
          ]}
        />
        {d.clinicalSummary && (
          <>
            <h2>Clinical summary</h2>
            <p className="prewrap">{d.clinicalSummary}</p>
          </>
        )}
      </div>
      <div>
        <h2>Intake details</h2>
        {intake.length === 0 ? <p className="muted">No intake answers recorded.</p> : <Dl items={intake.map(([k, v]) => [humanise(k), renderValue(v)])} />}

        <h2>Attachments</h2>
        {(d.attachments ?? []).length === 0 ? (
          <p className="muted">No attachments.</p>
        ) : (
          <ul className="plain-list">
            {(d.attachments ?? []).map((a) => (
              <li key={a.id}>
                <a href={attachmentUrl(caseId, a.id)} download={a.fileName} rel="noopener">
                  {a.fileName}
                </a>{' '}
                <span className="small muted">
                  {a.contentType} · {formatBytes(a.size)} · {formatDate(a.uploadedAt)}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="small muted">Downloads are audited.</p>

        <h2>Links</h2>
        {(d.links ?? []).length === 0 ? (
          <p className="muted">No linked records.</p>
        ) : (
          <ul className="plain-list">
            {(d.links ?? []).map((l) => (
              <li key={`${l.type}-${l.ref}`}>
                <span className="badge badge-muted">{humanise(l.type)}</span> <span className="mono">{l.ref}</span>{' '}
                <span className="small muted">{formatDate(l.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
        <form className="form inline-form" onSubmit={addLink}>
          <Field label="Link type">
            {(id) => (
              <select id={id} value={linkType} onChange={(e) => setLinkType(e.target.value as CaseLink['type'])}>
                <SelectOptions values={LINK_TYPES} />
              </select>
            )}
          </Field>
          <Field label="Reference">{(id) => <input id={id} value={linkRef} required maxLength={64} onChange={(e) => setLinkRef(e.target.value)} />}</Field>
          <button type="submit" className="secondary" disabled={action.busy || !linkRef.trim()}>
            Add link
          </button>
        </form>

        <h2>Waiting state</h2>
        <form className="form inline-form" onSubmit={saveWaiting}>
          <Field label="Waiting state" hint="The SLA clock pauses while awaiting external input or the employee.">
            {(id) => (
              <select id={id} value={waiting} onChange={(e) => setWaiting(e.target.value as WaitingState)}>
                <SelectOptions values={WAITING_STATES} />
              </select>
            )}
          </Field>
          <Field label="Note (optional)">{(id) => <input id={id} value={waitingNote} maxLength={500} onChange={(e) => setWaitingNote(e.target.value)} />}</Field>
          <button type="submit" className="secondary" disabled={action.busy}>
            Update
          </button>
        </form>
        <ActionFeedback error={action.error} message={action.message} />
        <p>
          <a href={`#/employees/${encodeURIComponent(d.employeeId)}/timeline`}>Employee timeline →</a>
        </p>
      </div>
    </div>
  );
}
