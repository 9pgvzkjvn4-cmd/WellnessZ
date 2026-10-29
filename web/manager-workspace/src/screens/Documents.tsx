import { useState } from 'react';
import { DOCUMENT_TYPE_LABELS, documentContentUrl, toWorkspaceDocuments, WORKSPACE_DOCUMENT_TYPES, type WorkspaceDocument } from '../api/fitness';
import { describeError, formatDate } from '../components/format';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export interface DocumentsViewProps {
  docs: readonly WorkspaceDocument[];
  type: string;
  onType: (t: string) => void;
}

const fileName = (d: WorkspaceDocument) => `${(d.documentNumber ?? d.id).replace(/[^A-Za-z0-9_-]/g, '_')}.pdf`;

/** Documents shared to the manager / HR (certificates, restriction notes, RTW plans, job offers, IHR letters). Hook-free. */
export function DocumentsView({ docs, type, onType }: DocumentsViewProps) {
  const shown = type ? docs.filter((d) => d.type === type) : docs;
  return (
    <>
      <form className="filters" role="search" aria-label="Filter documents" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="doc-type">Document type</label>
          <select id="doc-type" value={type} onChange={(e) => onType(e.target.value)}>
            <option value="">All types</option>
            {WORKSPACE_DOCUMENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {DOCUMENT_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {docs.length} documents.
      </p>
      {docs.length === 0 && <p className="muted">No documents have been shared with you.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Documents shared with you</caption>
            <thead>
              <tr>
                <th scope="col">Document</th>
                <th scope="col">Type</th>
                <th scope="col">Employee</th>
                <th scope="col">Status</th>
                <th scope="col">Signed</th>
                <th scope="col">Download</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((d) => {
                const title = d.title ?? DOCUMENT_TYPE_LABELS[d.type];
                return (
                  <tr key={d.id}>
                    <th scope="row">
                      {title}
                      <span className="muted small">
                        {' '}
                        {d.documentNumber ?? ''}
                        {d.version && d.version > 1 ? ` · version ${d.version}` : ''}
                      </span>
                    </th>
                    <td>{DOCUMENT_TYPE_LABELS[d.type]}</td>
                    <td>{d.employeeId ?? '–'}</td>
                    <td>{d.status ? <StatusBadge status={d.status} /> : '–'}</td>
                    <td>{formatDate(d.signedAt)}</td>
                    <td>
                      <a href={documentContentUrl(d.id)} download={fileName(d)} aria-label={`Download PDF: ${title}`}>
                        Download PDF
                      </a>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export function Documents({ client, onSessionExpired }: ScreenProps) {
  const docs = useAsync(async () => toWorkspaceDocuments(await client.api<unknown>('/workspace/documents')), onSessionExpired);
  const [type, setType] = useState('');
  return (
    <section>
      <PageTitle>Documents</PageTitle>
      <p className="lead">Fitness certificates, restriction notes, return-to-work plans, job offers and decision letters shared with you.</p>
      <NoClinicalInfoNote>
        Only documents without clinical content can be shared with managers and HR. Downloads are logged.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={docs.reload} disabled={docs.loading}>
          Refresh
        </button>
      </div>
      {docs.loading && <Loading />}
      {!!docs.error && <Alert kind="error">{describeError(docs.error)}</Alert>}
      {docs.data && <DocumentsView docs={docs.data} type={type} onType={setType} />}
    </section>
  );
}
