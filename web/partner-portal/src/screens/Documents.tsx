import { useState } from 'react';
import { documentContentUrl, documentFileName, documentTypeLabel, toSharedDocuments, type SharedDocument } from '../api/documents';
import { describeError } from '../api/errors';
import { formatDateTime } from '../components/format';
import { Alert, ConfidentialityNote, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import type { ScreenProps } from './types';

/** Hook-free table of shared documents with a download link each. */
export function DocumentTable({ docs, caption }: { docs: readonly SharedDocument[]; caption: string }) {
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{S.documents.colDocument}</th>
            <th scope="col">{S.documents.colType}</th>
            <th scope="col">{S.documents.colDate}</th>
            <th scope="col">{S.documents.colDownload}</th>
          </tr>
        </thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.id}>
              <th scope="row">
                {d.title}
                {d.documentNumber && <span className="muted small block">{d.documentNumber}</span>}
              </th>
              <td>{documentTypeLabel(d.type) || S.app.noValue}</td>
              <td>{formatDateTime(d.date)}</td>
              <td>
                <a href={documentContentUrl(d.id)} download={documentFileName(d)} aria-label={S.referral.download(d.title)}>
                  {S.documents.download}
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function DocumentsView({ docs, type, onType }: { docs: readonly SharedDocument[]; type: string; onType: (t: string) => void }) {
  const types = [...new Set(docs.map((d) => d.type).filter((t): t is string => !!t))].sort();
  const shown = type ? docs.filter((d) => d.type === type) : docs;
  if (docs.length === 0) return <p className="muted">{S.documents.empty}</p>;
  return (
    <>
      <form className="filters" role="search" aria-label="Filter documents" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="doc-type">{S.documents.type}</label>
          <select id="doc-type" value={type} onChange={(e) => onType(e.target.value)}>
            <option value="">{S.documents.allTypes}</option>
            {types.map((t) => (
              <option key={t} value={t}>
                {documentTypeLabel(t)}
              </option>
            ))}
          </select>
        </div>
      </form>
      <DocumentTable docs={shown} caption={S.documents.caption} />
    </>
  );
}

export function Documents({ client, onSessionExpired }: ScreenProps) {
  const data = useAsync(async () => toSharedDocuments(await client.api<unknown>('/partner/documents')), onSessionExpired);
  const [type, setType] = useState('');
  return (
    <section>
      <PageTitle>{S.documents.title}</PageTitle>
      <ConfidentialityNote>{S.confidentiality.documents}</ConfidentialityNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </div>
      {data.loading && !data.data && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && <DocumentsView docs={data.data} type={type} onType={setType} />}
    </section>
  );
}
