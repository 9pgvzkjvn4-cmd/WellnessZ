import { useState, type FormEvent } from 'react';
import {
  documentContentUrl,
  DOCUMENT_TYPES,
  mergeFieldSpec,
  NON_CLINICAL_TYPES,
  platformApi,
  SHARE_TARGETS,
  type DocumentMeta,
  type DocumentTemplate,
  type ShareTarget,
} from '../../api/platform';
import type { AuthClient } from '../../auth/AuthClient';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { formatBytes, formatDate, humanise } from '../../components/format';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ChartTabProps } from '../chart/chartTabProps';

const MAX_SCAN = 10 * 1024 * 1024;
const SCAN_TYPES = ['application/pdf', 'image/jpeg', 'image/png'];

/** Merge-field form generated from the template's `mergeFields`. */
function MergeFieldsForm({ template, values, onChange, errors, prefix }: { template: DocumentTemplate; values: Record<string, string>; onChange: (v: Record<string, string>) => void; errors: FieldError[]; prefix: string }) {
  const specs = template.mergeFields.map(mergeFieldSpec);
  if (specs.length === 0) return <p className="small muted">This template has no merge fields.</p>;
  return (
    <div className="form-grid">
      {specs.map((f) => {
        const id = `${prefix}-${f.key}`;
        const long = /note|text|duties|reason|summary|advice|body/i.test(f.key);
        return (
          <div key={f.key} className={long ? 'span-all' : undefined}>
            <IdField id={id} label={humanise(f.label.replace(/([a-z])([A-Z])/g, '$1_$2'))} required={f.required} error={errorFor(errors, id)}>
              {(d) =>
                long ? (
                  <textarea id={id} rows={3} maxLength={4000} value={values[f.key] ?? ''} aria-describedby={d} aria-invalid={!!errorFor(errors, id)} onChange={(e) => onChange({ ...values, [f.key]: e.target.value })} />
                ) : (
                  <input id={id} type={/date/i.test(f.key) ? 'date' : 'text'} maxLength={500} value={values[f.key] ?? ''} aria-describedby={d} aria-invalid={!!errorFor(errors, id)} onChange={(e) => onChange({ ...values, [f.key]: e.target.value })} />
                )
              }
            </IdField>
          </div>
        );
      })}
    </div>
  );
}

function missingFields(t: DocumentTemplate, values: Record<string, string>, prefix: string): FieldError[] {
  return t.mergeFields
    .map(mergeFieldSpec)
    .filter((f) => f.required && !(values[f.key] ?? '').trim())
    .map((f) => ({ field: `${prefix}-${f.key}`, message: `Enter ${humanise(f.label.replace(/([a-z])([A-Z])/g, '$1_$2')).toLowerCase()}.` }));
}

/** Generate a document from a template (E03-BL-015). */
export function GenerateDocument({ client, onSessionExpired, employeeId, caseId, onGenerated, templateFilter }: { client: AuthClient; onSessionExpired: () => void; employeeId: string; caseId?: string; onGenerated: (d: DocumentMeta) => void; templateFilter?: (t: DocumentTemplate) => boolean }) {
  const api = platformApi(client);
  const templates = useAsync(() => api.documentTemplates(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [code, setCode] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<FieldError[]>([]);
  const list = (templates.data ?? []).filter((t) => (templateFilter ? templateFilter(t) : true));
  const t = list.find((x) => x.code === code);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!t) {
      setErrors([{ field: 'gen-template', message: 'Choose a template.' }]);
      return;
    }
    const errs = missingFields(t, values, 'gen');
    setErrors(errs);
    if (errs.length) return;
    let doc: DocumentMeta | undefined;
    const ok = await action.run(async () => {
      doc = await api.generateDocument({ templateCode: t.code, employeeId, ...(caseId ? { caseId } : {}), fields: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim()])) });
      return `${doc.title} generated as a draft (${doc.documentNumber ?? 'PDF rendered'}).`;
    });
    if (ok && doc) {
      setValues({});
      setCode('');
      onGenerated(doc);
    }
  }

  return (
    <div className="tile">
      <h3>Generate from template</h3>
      {!!templates.error && <ErrorAlert error={templates.error} />}
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        <IdField id="gen-template" label="Template" required error={errorFor(errors, 'gen-template')}>
          {(d) => (
            <select id="gen-template" value={code} aria-describedby={d} onChange={(e) => { setCode(e.target.value); setValues({}); setErrors([]); }}>
              <option value="">Choose…</option>
              {list.map((x) => (
                <option key={x.code} value={x.code}>
                  {x.title}{x.containsClinical ? ' (clinical)' : ''}
                </option>
              ))}
            </select>
          )}
        </IdField>
        {t && (
          <>
            <p className="small muted">
              Audience: {humanise(t.audience ?? 'OH')} · {t.containsClinical ? 'Contains clinical content – cannot be shared with managers or HR.' : 'No clinical content – shareable with managers / HR.'}
            </p>
            <MergeFieldsForm template={t} values={values} onChange={setValues} errors={errors} prefix="gen" />
          </>
        )}
        <button type="submit" disabled={action.busy}>
          Generate document
        </button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}

/** Scan / upload an external document (E03-BL-016): PDF/JPEG/PNG ≤ 10 MB, text extracted and type auto-classified when omitted. */
export function ScanUpload({ client, onSessionExpired, employeeId, caseId, onUploaded }: { client: AuthClient; onSessionExpired: () => void; employeeId: string; caseId?: string; onUploaded: (d: DocumentMeta) => void }) {
  const action = useAction(onSessionExpired);
  const [file, setFile] = useState<File>();
  const [type, setType] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [inputKey, setInputKey] = useState(0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!file) errs.push({ field: 'scan-file', message: 'Choose a file to upload.' });
    else if (!SCAN_TYPES.includes(file.type)) errs.push({ field: 'scan-file', message: 'Files must be PDF, JPEG or PNG.' });
    else if (file.size > MAX_SCAN) errs.push({ field: 'scan-file', message: `The file is ${formatBytes(file.size)}; the limit is 10 MB.` });
    setErrors(errs);
    if (errs.length || !file) return;
    let doc: DocumentMeta | undefined;
    const ok = await action.run(async () => {
      doc = await platformApi(client).scan(file, file.name, employeeId, caseId, type || undefined);
      return `Uploaded as ${humanise(doc.type)} ${doc.documentNumber ?? ''}. Text is indexed for search.`;
    });
    if (ok && doc) {
      setFile(undefined);
      setType('');
      setInputKey((k) => k + 1);
      onUploaded(doc);
    }
  }

  return (
    <div className="tile">
      <h3>Scan / upload external document</h3>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        <IdField id="scan-file" label="File (PDF, JPEG or PNG, max 10 MB)" required error={errorFor(errors, 'scan-file')}>
          {(d) => <input key={inputKey} id="scan-file" type="file" accept="application/pdf,image/jpeg,image/png" aria-describedby={d} onChange={(e) => setFile(e.target.files?.[0])} />}
        </IdField>
        <IdField id="scan-type" label="Document type" hint="Leave empty to classify automatically.">
          {(d) => (
            <select id="scan-type" value={type} aria-describedby={d} onChange={(e) => setType(e.target.value)}>
              <SelectOptions values={DOCUMENT_TYPES} empty="Classify automatically" />
            </select>
          )}
        </IdField>
        <button type="submit" disabled={action.busy}>
          Upload
        </button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}

function ShareDialog({ doc, template, onClose, onShare, busy, error }: { doc: DocumentMeta; template?: DocumentTemplate; onClose: () => void; onShare: (b: { target: ShareTarget; partnerOrg?: string; consentId?: string }) => void; busy: boolean; error?: unknown }) {
  const clinical = template ? template.containsClinical : !NON_CLINICAL_TYPES.includes(doc.type);
  const [target, setTarget] = useState<ShareTarget>('EMPLOYEE');
  const [partnerOrg, setPartnerOrg] = useState('');
  const [consentId, setConsentId] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const allowed = SHARE_TARGETS.filter((t) => !(clinical && (t === 'MANAGER' || t === 'HR')));

  function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (target === 'PARTNER') {
      if (!partnerOrg.trim()) errs.push({ field: 'share-partner', message: 'Enter the partner organisation.' });
      if (!consentId.trim()) errs.push({ field: 'share-consent', message: 'A partner share needs the employee’s consent ID.' });
    }
    setErrors(errs);
    if (errs.length) return;
    onShare({ target, ...(target === 'PARTNER' ? { partnerOrg: partnerOrg.trim(), consentId: consentId.trim() } : {}) });
  }

  return (
    <Dialog title={`Share ${doc.title}`} onClose={onClose}>
      <p className="small">Every share is written to the disclosure log and is visible to the employee.</p>
      {clinical && <p className="alert alert-info">This document contains clinical content: it can be shared with the employee or a partner (with consent) only – never with a manager or HR.</p>}
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        <IdField id="share-target" label="Share with" required>
          {() => (
            <select id="share-target" value={target} onChange={(e) => setTarget(e.target.value as ShareTarget)}>
              <SelectOptions values={allowed} />
            </select>
          )}
        </IdField>
        {target === 'PARTNER' && (
          <>
            <IdField id="share-partner" label="Partner organisation" required error={errorFor(errors, 'share-partner')}>
              {(d) => <input id="share-partner" maxLength={200} value={partnerOrg} aria-describedby={d} aria-invalid={!!errorFor(errors, 'share-partner')} onChange={(e) => setPartnerOrg(e.target.value)} />}
            </IdField>
            <IdField id="share-consent" label="Consent ID" required hint="The employee's active consent for this partner." error={errorFor(errors, 'share-consent')}>
              {(d) => <input id="share-consent" maxLength={80} value={consentId} aria-describedby={d} aria-invalid={!!errorFor(errors, 'share-consent')} onChange={(e) => setConsentId(e.target.value)} />}
            </IdField>
          </>
        )}
        <div className="actions">
          <button type="submit" disabled={busy}>Share document</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={error} />
    </Dialog>
  );
}

function NewVersionDialog({ doc, template, onClose, onSubmit, busy, error }: { doc: DocumentMeta; template?: DocumentTemplate; onClose: () => void; onSubmit: (fields: Record<string, string>) => void; busy: boolean; error?: unknown }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<FieldError[]>([]);
  function submit(e: FormEvent) {
    e.preventDefault();
    const errs = template ? missingFields(template, values, 'nv') : [];
    setErrors(errs);
    if (!errs.length) onSubmit(Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim()])));
  }
  return (
    <Dialog title={`New version of ${doc.title}`} onClose={onClose}>
      <p className="small">A new draft version is created; the current version becomes superseded when the new one is signed.</p>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        {template ? <MergeFieldsForm template={template} values={values} onChange={setValues} errors={errors} prefix="nv" /> : <p className="small muted">This document was not generated from a template.</p>}
        <div className="actions">
          <button type="submit" disabled={busy || !template}>Create new version</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
      <ActionFeedback error={error} />
    </Dialog>
  );
}

/** Documents for an employee (chart tab) or a case (case tab): list, full-text search, generate, sign, version, share, scan. */
export function DocumentsPanel({ client, session, onSessionExpired, employeeId, caseId }: ChartTabProps) {
  const api = platformApi(client);
  const docs = useAsync(() => api.documents(caseId ? { caseId, ...(employeeId ? { employeeId } : {}) } : { employeeId }), onSessionExpired);
  const templates = useAsync(() => api.documentTemplates(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [q, setQ] = useState('');
  const [hits, setHits] = useState<{ document: DocumentMeta; snippet?: string }[]>();
  const [sharing, setSharing] = useState<DocumentMeta>();
  const [versioning, setVersioning] = useState<DocumentMeta>();
  const [panel, setPanel] = useState<'generate' | 'scan'>();
  const canSign = can(session.roles, 'documentSign');
  const tplFor = (d: DocumentMeta) => (templates.data ?? []).find((t) => t.code === d.templateCode);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (q.trim().length < 2) return;
    await action.run(async () => setHits(await api.searchDocuments(employeeId, q.trim())));
  }

  async function sign(d: DocumentMeta) {
    const ok = await action.run(async () => {
      const s = await api.signDocument(d.id);
      return `${s.title} signed. SHA-256 ${s.sha256 ?? ''}`.trim();
    });
    if (ok) docs.reload();
  }

  async function share(d: DocumentMeta, body: { target: ShareTarget; partnerOrg?: string; consentId?: string }) {
    const ok = await action.run(async () => {
      await api.share(d.id, body);
      return `${d.title} shared with ${humanise(body.target).toLowerCase()}${body.partnerOrg ? ` (${body.partnerOrg})` : ''}.`;
    });
    if (ok) {
      setSharing(undefined);
      docs.reload();
    }
  }

  async function revoke(d: DocumentMeta, shareId: string, target: string) {
    if (await action.run(async () => { await api.revokeShare(d.id, shareId); return `Share with ${humanise(target).toLowerCase()} revoked.`; })) docs.reload();
  }

  async function newVersion(d: DocumentMeta, fields: Record<string, string>) {
    const ok = await action.run(async () => {
      const n = await api.newVersion(d.id, fields);
      return `Version ${n.version ?? ''} created as a draft.`;
    });
    if (ok) {
      setVersioning(undefined);
      docs.reload();
    }
  }

  const rows = docs.data ?? [];
  return (
    <div>
      <h2>Documents</h2>
      <form className="form inline-form" role="search" onSubmit={search}>
        <Field label="Search document text">{(id) => <input id={id} type="search" value={q} minLength={2} onChange={(e) => setQ(e.target.value)} />}</Field>
        <button type="submit" className="secondary" disabled={q.trim().length < 2 || action.busy}>
          Search
        </button>
        {hits && (
          <button type="button" className="link-button" onClick={() => { setHits(undefined); setQ(''); }}>
            Clear search
          </button>
        )}
      </form>
      {hits && (
        <div aria-live="polite">
          <h3>Search results ({hits.length})</h3>
          {hits.length === 0 ? (
            <p className="muted">No documents contain that text.</p>
          ) : (
            <ul className="plain-list">
              {hits.map((h) => (
                <li key={h.document.id}>
                  <a href={documentContentUrl(h.document.id)} target="_blank" rel="noopener">{h.document.title}</a> <span className="small muted">{humanise(h.document.type)}</span>
                  {h.snippet && <div className="small snippet">…{h.snippet}…</div>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {docs.loading && <Loading />}
      {!!docs.error && <ErrorAlert error={docs.error} />}
      {docs.data && rows.length === 0 && <Empty>No documents.</Empty>}
      {rows.length > 0 && (
        <ul className="card-list">
          {rows.map((d) => (
            <li key={d.id} className="card">
              <strong>{d.title}</strong> <span className="small mono">{d.documentNumber}</span> <StatusBadge status={d.status} /> {d.version && d.version > 1 && <span className="badge badge-muted">v{d.version}</span>}{' '}
              {d.legalHold && <span className="badge badge-warning">Legal hold</span>}
              <div className="small">
                {humanise(d.type)} · {d.createdBy} {formatDate(d.createdAt)}
                {d.size ? ` · ${formatBytes(d.size)}` : ''}
              </div>
              {d.signedAt && (
                <div className="small">
                  Signed by {d.signedBy} {formatDate(d.signedAt)} · SHA-256 <span className="mono break">{d.sha256}</span>
                </div>
              )}
              {(d.shares ?? []).length > 0 && (
                <ul className="plain-list small" aria-label={`Shares of ${d.title}`}>
                  {(d.shares ?? []).map((s) => (
                    <li key={s.id}>
                      Shared with {humanise(s.target).toLowerCase()}
                      {s.partnerOrg ? ` (${s.partnerOrg})` : ''} {formatDate(s.sharedAt)}{' '}
                      {s.revokedAt ? (
                        <span className="muted">– revoked {formatDate(s.revokedAt)}</span>
                      ) : (
                        <button type="button" className="link-button" disabled={action.busy} onClick={() => void revoke(d, s.id, s.target)}>
                          Revoke
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              <div className="card-actions">
                <a className="button secondary small-button" href={documentContentUrl(d.id)} target="_blank" rel="noopener">
                  Download PDF
                </a>
                {canSign && d.status === 'DRAFT' && (
                  <button type="button" className="small-button" disabled={action.busy} onClick={() => void sign(d)}>
                    Sign
                  </button>
                )}
                {d.templateCode && d.status !== 'REVOKED' && (
                  <button type="button" className="secondary small-button" onClick={() => setVersioning(d)}>
                    New version
                  </button>
                )}
                {d.status === 'SIGNED' && (
                  <button type="button" className="secondary small-button" onClick={() => setSharing(d)}>
                    Share
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      <div className="actions">
        <button type="button" className="secondary" aria-expanded={panel === 'generate'} onClick={() => setPanel(panel === 'generate' ? undefined : 'generate')}>
          Generate document
        </button>
        <button type="button" className="secondary" aria-expanded={panel === 'scan'} onClick={() => setPanel(panel === 'scan' ? undefined : 'scan')}>
          Scan / upload
        </button>
      </div>
      {panel === 'generate' && <GenerateDocument client={client} onSessionExpired={onSessionExpired} employeeId={employeeId} caseId={caseId} onGenerated={() => docs.reload()} />}
      {panel === 'scan' && <ScanUpload client={client} onSessionExpired={onSessionExpired} employeeId={employeeId} caseId={caseId} onUploaded={() => docs.reload()} />}
      {sharing && <ShareDialog doc={sharing} template={tplFor(sharing)} busy={action.busy} error={action.error} onClose={() => setSharing(undefined)} onShare={(b) => void share(sharing, b)} />}
      {versioning && <NewVersionDialog doc={versioning} template={tplFor(versioning)} busy={action.busy} error={action.error} onClose={() => setVersioning(undefined)} onSubmit={(f) => void newVersion(versioning, f)} />}
    </div>
  );
}
