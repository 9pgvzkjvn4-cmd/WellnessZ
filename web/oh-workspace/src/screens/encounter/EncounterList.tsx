import { useState, type FormEvent } from 'react';
import { codingText, recordApi, type EncounterSummary, type EncounterType } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert, Empty, ErrorAlert, Field, Loading, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { navigate } from '../../routing/useHashRoute';
import type { ChartTabProps } from '../chart/chartTabProps';
import { AiBadge } from './EncounterEditor';

/** New encounter: type picker driven by `/encounter-templates`; MENTAL_HEALTH only for the psychological clinician. */
export function NewEncounter({ client, session, onSessionExpired, employeeId, caseId }: Pick<ChartTabProps, 'client' | 'session' | 'onSessionExpired' | 'employeeId' | 'caseId'>) {
  const api = recordApi(client);
  const templates = useAsync(() => api.templates(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [type, setType] = useState<EncounterType | ''>('');
  const psych = can(session.roles, 'mentalHealthEncounter');
  const list = (templates.data ?? []).filter((t) => t.type !== 'MENTAL_HEALTH' || psych);
  const chosen = list.find((t) => t.type === type);

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!chosen) return;
    let id: string | undefined;
    const ok = await action.run(async () => {
      id = (await api.createEncounter(employeeId, { type: chosen.type, ...(caseId ? { caseId } : {}), templateVersion: chosen.version })).id;
    });
    if (ok && id) navigate(`/encounters/${encodeURIComponent(id)}`);
  }

  return (
    <div className="tile">
      <h3>New encounter</h3>
      {templates.loading && <Loading label="Loading templates…" />}
      {!!templates.error && <ErrorAlert error={templates.error} />}
      <form className="form inline-form" onSubmit={create}>
        <Field label="Encounter type" required>
          {(id) => (
            <select id={id} value={type} onChange={(e) => setType(e.target.value as EncounterType | '')}>
              <option value="">Choose…</option>
              {list.map((t) => (
                <option key={`${t.type}-${t.version}`} value={t.type}>
                  {t.title} (v{t.version}{t.status === 'DRAFT' ? ', awaiting CMO approval' : ''})
                </option>
              ))}
            </select>
          )}
        </Field>
        <button type="submit" disabled={!chosen || action.busy}>
          Start encounter
        </button>
      </form>
      {!psych && <p className="small muted">Mental-health consultations are restricted and can only be started by the psychological clinician.</p>}
      <ActionFeedback error={action.error} />
    </div>
  );
}

export function EncounterTable({ rows, caption }: { rows: EncounterSummary[]; caption: string }) {
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Encounter</th>
            <th scope="col">Status</th>
            <th scope="col">Primary diagnosis</th>
            <th scope="col">Author</th>
            <th scope="col">Created</th>
            <th scope="col">Signed</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <th scope="row">
                <a href={`#/encounters/${encodeURIComponent(r.id)}`}>{r.title ?? humanise(r.type)}</a> <AiBadge on={r.aiAssisted} />
                <div className="small muted">{humanise(r.type)}</div>
              </th>
              <td><StatusBadge status={r.status} /></td>
              <td>{r.primaryDiagnosis ? codingText(r.primaryDiagnosis) : '–'}</td>
              <td>{r.author ?? '–'}</td>
              <td>{formatDate(r.createdAt)}</td>
              <td>{formatDate(r.signedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Encounters of an employee (chart tab) or of one case (case tab, filtered by `caseId`). */
export function EncounterList(props: ChartTabProps) {
  const { client, onSessionExpired, employeeId, caseId, session } = props;
  const list = useAsync(() => recordApi(client).encounters(employeeId), onSessionExpired);
  const rows = (list.data ?? []).filter((r) => !caseId || r.caseId === caseId);
  return (
    <div>
      <h2>Encounters</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No encounters{caseId ? ' for this case' : ''}.</Empty>}
      {rows.length > 0 && <EncounterTable rows={rows} caption="Encounters" />}
      {can(session.roles, 'encounterWrite') ? <NewEncounter {...props} /> : <Alert kind="info">Only clinicians can document encounters.</Alert>}
    </div>
  );
}
