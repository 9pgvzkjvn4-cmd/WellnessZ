import { useState, type FormEvent } from 'react';
import { recordApi, type ConceptImport, type EncounterTemplate } from '../../api/record';
import { can } from '../../auth/roles';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { parseConcepts } from '../../domain/imports';
import type { ScreenProps } from '../types';

/** `#/admin/encounter-templates` – the eight versioned templates; the CMO approves DRAFT versions (E03-BL-004). */
export function EncounterTemplatesAdmin({ client, session, onSessionExpired }: ScreenProps) {
  const api = recordApi(client);
  const list = useAsync(() => api.templates(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const canApprove = can(session.roles, 'templateApprove');

  async function approve(t: EncounterTemplate) {
    if (await action.run(async () => { await api.approveTemplate(t.type); return `${t.title} v${t.version} approved.`; })) list.reload();
  }

  const rows = list.data ?? [];
  return (
    <section>
      <PageTitle sub="Templates define the mandatory fields and coded pick-lists of each encounter type. New versions need CMO approval.">Encounter templates</PageTitle>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No templates.</Empty>}
      <ul className="card-list">
        {rows.map((t) => (
          <li key={`${t.type}-${t.version}`} className="card">
            <strong>{t.title}</strong> <span className="small">({humanise(t.type)}, v{t.version})</span> <StatusBadge status={t.status} />
            <div className="small muted">
              {t.sections.length} sections · {t.sections.reduce((n, s) => n + s.fields.length, 0)} fields · {t.sections.reduce((n, s) => n + s.fields.filter((f) => f.required).length, 0)} mandatory
              {t.bodyMap ? ' · body map' : ''}
              {t.requiresDiagnosis ? ' · diagnosis required' : ''}
              {t.fitnessStandardPrompt ? ' · fitness standard' : ''}
              {t.approvedBy ? ` · approved by ${t.approvedBy}` : ''}
            </div>
            <details>
              <summary>Show fields</summary>
              {t.sections.map((s) => (
                <div key={s.key}>
                  <h4>{s.title}</h4>
                  <ul className="plain-list small">
                    {s.fields.map((f) => (
                      <li key={f.key}>
                        {f.label} – {humanise(f.type)}
                        {f.codeSystem ? ` (${humanise(f.codeSystem)})` : ''}
                        {f.required ? ' · required' : ''}
                        {f.options && f.options.length > 0 ? ` · ${f.options.map((o) => o.label).join(' / ')}` : ''}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </details>
            {canApprove && t.status === 'DRAFT' && (
              <button type="button" className="small-button" disabled={action.busy} onClick={() => void approve(t)}>
                Approve v{t.version}
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** `#/admin/terminology` – loaded terminology releases (licence) and the annual concept import (OH_ADMIN). */
export function TerminologyAdmin({ client, onSessionExpired }: ScreenProps) {
  const api = recordApi(client);
  const releases = useAsync(() => api.releases(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<ConceptImport[]>();
  const [parseError, setParseError] = useState<string>();

  function check(t: string) {
    setText(t);
    const r = parseConcepts(t);
    if (r.ok) {
      setPreview(r.items);
      setParseError(undefined);
    } else {
      setPreview(undefined);
      setParseError(t.trim() ? r.error : undefined);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!preview) return;
    const items = preview;
    const ok = await action.run(async () => {
      const r = await api.importConcepts(items);
      return `${r.upserted} concepts upserted, ${r.deactivated} deactivated.`;
    });
    if (ok) {
      setText('');
      setPreview(undefined);
      releases.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="SNOMED CT, ICD-10/11, LOINC and ODH releases. Production loads the licensed national editions.">Terminology releases</PageTitle>
      {releases.loading && <Loading />}
      {!!releases.error && <ErrorAlert error={releases.error} />}
      {releases.data && releases.data.length === 0 && <Empty>No releases loaded.</Empty>}
      {(releases.data ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Loaded releases</caption>
            <thead>
              <tr>
                <th scope="col">System</th>
                <th scope="col">Version</th>
                <th scope="col">Loaded</th>
                <th scope="col">Licence</th>
              </tr>
            </thead>
            <tbody>
              {(releases.data ?? []).map((r) => (
                <tr key={`${r.system}-${r.version}`}>
                  <th scope="row">{humanise(r.system)}</th>
                  <td className="mono">{r.version}</td>
                  <td>{r.loadedBy} {formatDate(r.loadedAt)}</td>
                  <td>{r.licence ?? '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="form tile" onSubmit={submit}>
        <h2>Import concepts</h2>
        <p className="small muted">JSON array of {'{system, code, display, active}'} or CSV with the header system,code,display,active. Concepts missing from a full release can be sent with active=false.</p>
        <div className="field">
          <label htmlFor="term-file">Release file</label>
          <input id="term-file" type="file" accept=".json,.csv,application/json,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) void f.text().then(check); }} />
        </div>
        <div className="field">
          <label htmlFor="term-text">Concepts</label>
          <textarea id="term-text" className="mono" rows={8} value={text} onChange={(e) => check(e.target.value)} />
        </div>
        {parseError && <p className="alert alert-error" role="alert">{parseError}</p>}
        {preview && (
          <p role="status">
            {preview.length} concepts ready ({[...new Set(preview.map((c) => c.system))].join(', ')}; {preview.filter((c) => !c.active).length} inactive).
          </p>
        )}
        <button type="submit" disabled={!preview || action.busy}>
          Import
        </button>
        <ActionFeedback error={action.error} message={action.message} />
      </form>
    </section>
  );
}
