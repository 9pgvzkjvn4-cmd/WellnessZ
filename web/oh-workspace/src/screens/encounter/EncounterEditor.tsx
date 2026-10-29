import { useState, type FormEvent } from 'react';
import { documentContentUrl, platformApi } from '../../api/platform';
import { codingText, recordApi, type AnswerValue, type BodyMapMark, type ClinicalEncounter, type Diagnosis, type EncounterTemplate, type EncounterUpdate, type Soap } from '../../api/record';
import { can } from '../../auth/roles';
import { CdsAlertList } from '../../components/CdsOverride';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, errorFor, type FieldError } from '../../components/forms';
import { formatBytes, formatDate, humanise } from '../../components/format';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { ActionFeedback, Alert, Dl, ErrorAlert, Loading, PageTitle, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { addDiagnosis, removeDiagnosis, setPrimary, signBlockers } from '../../domain/encounter';
import type { ScreenProps } from '../types';
import { AiAssistantPanel } from './AiAssistantPanel';
import { BodyMap } from './BodyMap';
import { TemplateFields } from './TemplateFields';

const MAX_PHOTO = 10 * 1024 * 1024;
const PHOTO_TYPES = ['image/jpeg', 'image/png'];

interface Draft {
  answers: Record<string, AnswerValue>;
  soap: Soap;
  bodyMap: BodyMapMark[];
  diagnoses: Diagnosis[];
  fitnessStandardCode: string;
  aiAssisted: boolean;
}

const draftOf = (e: ClinicalEncounter): Draft => ({
  answers: e.answers ?? {},
  soap: e.soap ?? {},
  bodyMap: e.bodyMap ?? [],
  diagnoses: e.diagnoses ?? [],
  fitnessStandardCode: e.fitnessStandardCode ?? e.fitnessStandard?.code ?? '',
  aiAssisted: !!e.aiAssisted,
});

export function AiBadge({ on }: { on?: boolean }) {
  if (!on) return null;
  return (
    <span className="badge badge-ai" title="Parts of this note were drafted by the AI documentation assistant and reviewed by the clinician">
      <span aria-hidden="true">✦ </span>AI-assisted
    </span>
  );
}

/** Photo upload with mandatory consent (E03-BL-007): JPEG/PNG ≤ 10 MB. */
function PhotoUpload({ client, onSessionExpired, encounterId, onUploaded }: { client: ScreenProps['client']; onSessionExpired: () => void; encounterId: string; onUploaded: () => void }) {
  const action = useAction(onSessionExpired);
  const [file, setFile] = useState<File>();
  const [region, setRegion] = useState('');
  const [consent, setConsent] = useState(false);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [inputKey, setInputKey] = useState(0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!file) errs.push({ field: 'photo-file', message: 'Choose a photo.' });
    else if (!PHOTO_TYPES.includes(file.type)) errs.push({ field: 'photo-file', message: 'Photos must be JPEG or PNG.' });
    else if (file.size > MAX_PHOTO) errs.push({ field: 'photo-file', message: `The photo is ${formatBytes(file.size)}; the limit is 10 MB.` });
    if (!consent) errs.push({ field: 'photo-consent', message: 'Confirm the employee consented to the photo.' });
    setErrors(errs);
    if (errs.length || !file) return;
    const ok = await action.run(async () => {
      await recordApi(client).uploadPhoto(encounterId, file, file.name, region.trim() || undefined);
      return 'Photo added to the encounter.';
    });
    if (ok) {
      setFile(undefined);
      setConsent(false);
      setRegion('');
      setInputKey((k) => k + 1);
      onUploaded();
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorSummary errors={errors} />
      <IdField id="photo-file" label="Photo (JPEG or PNG, max 10 MB)" required error={errorFor(errors, 'photo-file')}>
        {(d) => <input key={inputKey} id="photo-file" type="file" accept="image/jpeg,image/png" capture="environment" aria-describedby={d} onChange={(e) => setFile(e.target.files?.[0])} />}
      </IdField>
      <IdField id="photo-region" label="Body region">
        {() => <input id="photo-region" maxLength={80} value={region} onChange={(e) => setRegion(e.target.value)} />}
      </IdField>
      <div className={`field${errorFor(errors, 'photo-consent') ? ' field-error' : ''}`}>
        {errorFor(errors, 'photo-consent') && <span className="field-error-text small">{errorFor(errors, 'photo-consent')}</span>}
        <label className="checkbox">
          <input id="photo-consent" type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} /> The employee consented to this photo being taken and stored in the OH record
        </label>
      </div>
      <button type="submit" className="secondary" disabled={action.busy}>
        Upload photo
      </button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** `#/encounters/{id}` – template-driven encounter note (E03-F02) with SOAP, body map, coding, CDS, signing. */
export function EncounterEditor({ client, session, onSessionExpired, encounterId }: ScreenProps & { encounterId: string }) {
  const api = recordApi(client);
  const enc = useAsync(() => api.encounter(encounterId), onSessionExpired);
  const templates = useAsync(() => api.templates(), onSessionExpired);
  const mechanisms = useAsync(() => api.mechanisms(), onSessionExpired);
  const standards = useAsync(() => platformApi(client).fitnessStandards(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [draft, setDraft] = useState<Draft>();
  const [loadedId, setLoadedId] = useState<string>();
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [dirty, setDirty] = useState(false);
  const [addendum, setAddendum] = useState('');
  const [eie, setEie] = useState<string>();

  const e = enc.data;
  // Re-initialise the editable draft whenever a fresh server copy arrives (adjusting state during render).
  const versionKey = e ? `${e.id}|${e.status}|${e.signature?.sha256 ?? ''}|${e.addenda?.length ?? 0}|${e.photos?.length ?? 0}|${e.aiAssisted ? 1 : 0}` : undefined;
  if (e && versionKey !== loadedId) {
    setLoadedId(versionKey);
    setDraft(draftOf(e));
    setDirty(false);
  }

  const template: EncounterTemplate | undefined = e
    ? (templates.data ?? []).find((t) => t.type === e.type && (e.templateVersion == null || t.version === e.templateVersion)) ?? (templates.data ?? []).find((t) => t.type === e.type)
    : undefined;
  const canWrite = can(session.roles, 'encounterWrite');
  const isAuthor = !!e && (!e.author || !session.username || e.author === session.username);
  const editable = !!e && e.status === 'DRAFT' && canWrite && isAuthor;
  const d = draft;

  function patch(p: Partial<Draft>) {
    if (!d) return;
    setDraft({ ...d, ...p });
    setDirty(true);
  }

  function body(): EncounterUpdate {
    const x = d as Draft;
    return { answers: x.answers, soap: x.soap, bodyMap: x.bodyMap, diagnoses: x.diagnoses, fitnessStandardCode: x.fitnessStandardCode || null, ...(x.aiAssisted ? { aiAssisted: true } : {}) };
  }

  async function save() {
    if (!d) return;
    setErrors([]);
    const ok = await action.run(async () => {
      await api.saveEncounter(encounterId, body());
      return 'Draft saved.';
    });
    if (ok) {
      setDirty(false);
      enc.reload();
    }
  }

  async function sign() {
    if (!d || !e) return;
    const errs = signBlockers(template, e.type, d.answers, d.diagnoses);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.saveEncounter(encounterId, body());
      const signed = await api.signEncounter(encounterId);
      return `Encounter signed. Signature hash ${signed.signature?.sha256 ?? ''}`.trim();
    });
    if (ok) enc.reload();
  }

  async function submitAddendum(ev: FormEvent) {
    ev.preventDefault();
    if (!addendum.trim()) return;
    const ok = await action.run(async () => {
      await api.addAddendum(encounterId, addendum.trim());
      return 'Addendum signed and added.';
    });
    if (ok) {
      setAddendum('');
      enc.reload();
    }
  }

  async function submitEie(ev: FormEvent) {
    ev.preventDefault();
    if (!eie?.trim()) return;
    const ok = await action.run(async () => {
      await api.enteredInError(encounterId, eie.trim());
      return 'Encounter marked as entered in error.';
    });
    if (ok) {
      setEie(undefined);
      enc.reload();
    }
  }

  const standard = (standards.data ?? []).find((s) => s.code === d?.fitnessStandardCode);

  return (
    <section>
      <p className="breadcrumb small">
        {e && (
          <>
            <a href={`#/employees/${encodeURIComponent(e.employeeId)}/chart/encounters`}>OH chart {e.employeeId}</a> <span aria-hidden="true">›</span>{' '}
          </>
        )}
        Encounter
      </p>
      <PageTitle sub={e ? `${humanise(e.type)} · ${e.author ?? ''} · ${formatDate(e.createdAt)}${template ? ` · template v${template.version}` : ''}` : undefined}>
        {e?.title ?? template?.title ?? 'Encounter'}
      </PageTitle>
      {enc.loading && !e && <Loading label="Loading encounter…" />}
      {!!enc.error && <ErrorAlert error={enc.error} />}
      {e && d && (
        <>
          <div className="case-header-badges">
            <StatusBadge status={e.status} />
            <AiBadge on={d.aiAssisted} />
            {e.classification && <span className="badge badge-muted">{humanise(e.classification)}</span>}
            {e.caseId && <a href={`#/cases/${encodeURIComponent(e.caseId)}`}>Open case</a>}
          </div>
          {e.status === 'ENTERED_IN_ERROR' && <Alert kind="warning">This encounter was marked as entered in error and is kept for audit only.</Alert>}
          {e.status === 'SIGNED' && e.signature && (
            <div className="signature" aria-label="Signature">
              <strong>Signed</strong> by {e.signature.signedBy} on {formatDate(e.signature.signedAt)}
              <div className="small">
                SHA-256: <span className="mono break">{e.signature.sha256}</span>
              </div>
              <p className="small muted">Signed notes are locked. Corrections are made with an addendum.</p>
            </div>
          )}
          {e.status === 'DRAFT' && !editable && <Alert kind="info">Only the author can edit this draft.</Alert>}
          {template?.status === 'DRAFT' && <Alert kind="warning">This template version is awaiting CMO approval.</Alert>}
          <ErrorSummary errors={errors} title="The note cannot be signed yet" />
          <ActionFeedback error={action.error} message={action.message} />

          <div className="grid-2 wide-left">
            <div>
              <form
                className="form"
                noValidate
                onSubmit={(ev) => {
                  ev.preventDefault();
                  void save();
                }}
              >
                {template ? (
                  <TemplateFields template={template} answers={d.answers} onChange={(a) => patch({ answers: a })} readOnly={!editable} errors={errors} client={client} onSessionExpired={onSessionExpired} />
                ) : (
                  templates.loading && <Loading label="Loading template…" />
                )}
                <fieldset className="template-section">
                  <legend>SOAP note</legend>
                  {(['subjective', 'objective', 'assessment', 'plan'] as const).map((k) => (
                    <div className="field" key={k}>
                      <label htmlFor={`soap-${k}`}>{humanise(k)}</label>
                      <textarea id={`soap-${k}`} rows={3} maxLength={8000} disabled={!editable} value={d.soap[k] ?? ''} onChange={(ev) => patch({ soap: { ...d.soap, [k]: ev.target.value } })} />
                    </div>
                  ))}
                </fieldset>
                {(template?.bodyMap ?? e.type === 'INJURY') && <BodyMap value={d.bodyMap} onChange={(b) => patch({ bodyMap: b })} mechanisms={mechanisms.data ?? []} readOnly={!editable} />}

                <fieldset className="template-section" id="enc-diagnoses" tabIndex={-1}>
                  <legend>Diagnoses</legend>
                  {errorFor(errors, 'enc-diagnoses') && <p className="field-error-text small">{errorFor(errors, 'enc-diagnoses')}</p>}
                  {d.diagnoses.length === 0 ? (
                    <p className="small muted">No coded diagnosis.</p>
                  ) : (
                    <ul className="plain-list" aria-label="Coded diagnoses">
                      {d.diagnoses.map((x, i) => (
                        <li key={`${x.system}-${x.code}`} className="inline-row">
                          <label className="checkbox">
                            <input type="radio" name="primary-dx" checked={x.primary} disabled={!editable} onChange={() => patch({ diagnoses: setPrimary(d.diagnoses, i) })} /> Primary
                          </label>
                          <span>{codingText(x)}</span>
                          {editable && (
                            <button type="button" className="link-button" onClick={() => patch({ diagnoses: removeDiagnosis(d.diagnoses, i) })} aria-label={`Remove diagnosis ${x.display}`}>
                              Remove
                            </button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  {editable && <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label="Add diagnosis" onSelect={(c) => patch({ diagnoses: addDiagnosis(d.diagnoses, c) })} />}
                </fieldset>

                {(template?.fitnessStandardPrompt || e.fitnessStandard) && (
                  <IdField id="enc-standard" label="Applicable fitness standard" hint="Links the decision to the rule (E03-BL-014).">
                    {(dd) => (
                      <select id="enc-standard" aria-describedby={dd} disabled={!editable} value={d.fitnessStandardCode} onChange={(ev) => patch({ fitnessStandardCode: ev.target.value })}>
                        <option value="">Choose…</option>
                        {(standards.data ?? []).map((s) => (
                          <option key={s.code} value={s.code}>{s.title} (v{s.version ?? 1})</option>
                        ))}
                      </select>
                    )}
                  </IdField>
                )}

                {editable && (
                  <div className="actions sticky-actions">
                    <button type="submit" className="secondary" disabled={action.busy}>
                      Save draft
                    </button>
                    <button type="button" disabled={action.busy} onClick={() => void sign()}>
                      Sign note
                    </button>
                    {dirty && <span className="small muted" role="status">Unsaved changes</span>}
                  </div>
                )}
              </form>

              {e.status === 'SIGNED' && (
                <section aria-labelledby="addenda-title">
                  <h2 id="addenda-title">Addenda</h2>
                  {(e.addenda ?? []).length === 0 ? (
                    <p className="small muted">No addenda.</p>
                  ) : (
                    <ol className="history-list">
                      {e.addenda.map((a) => (
                        <li key={a.id}>
                          <p className="prewrap">{a.text}</p>
                          <span className="small muted">Signed by {a.author} · {formatDate(a.signedAt)}</span>
                        </li>
                      ))}
                    </ol>
                  )}
                  {canWrite && (
                    <form className="form" onSubmit={submitAddendum}>
                      <div className="field">
                        <label htmlFor="addendum">New addendum</label>
                        <textarea id="addendum" rows={3} maxLength={4000} value={addendum} onChange={(ev) => setAddendum(ev.target.value)} />
                      </div>
                      <button type="submit" className="secondary" disabled={!addendum.trim() || action.busy}>
                        Sign and add addendum
                      </button>
                    </form>
                  )}
                </section>
              )}
            </div>

            <aside className="side-panel">
              <section className="tile" aria-labelledby="cds-title">
                <h2 id="cds-title">Decision support</h2>
                <CdsAlertList alerts={e.cdsAlerts ?? []} />
                {(e.fitnessStandard || standard) && (
                  <Dl
                    items={[
                      ['Fitness standard', (e.fitnessStandard ?? standard)?.title],
                      ['Code', (e.fitnessStandard ?? standard)?.code],
                      [
                        'Reference',
                        (e.fitnessStandard ?? standard)?.referenceUrl ? (
                          <a href={(e.fitnessStandard ?? standard)?.referenceUrl} target="_blank" rel="noopener noreferrer">
                            Open standard
                          </a>
                        ) : undefined,
                      ],
                    ]}
                  />
                )}
              </section>
              <section className="tile" aria-labelledby="photos-title">
                <h2 id="photos-title">Photos</h2>
                {(e.photos ?? []).length === 0 ? (
                  <p className="small muted">No photos.</p>
                ) : (
                  <ul className="plain-list">
                    {e.photos.map((p) => (
                      <li key={p.documentId}>
                        <a href={documentContentUrl(p.documentId)} target="_blank" rel="noopener">
                          Photo {p.bodyRegion ? `– ${p.bodyRegion}` : ''}
                        </a>{' '}
                        <span className="small muted">{formatDate(p.takenAt)}</span>
                      </li>
                    ))}
                  </ul>
                )}
                {canWrite && e.status !== 'ENTERED_IN_ERROR' && <PhotoUpload client={client} onSessionExpired={onSessionExpired} encounterId={encounterId} onUploaded={enc.reload} />}
              </section>
              {editable && (
                <AiAssistantPanel
                  client={client}
                  onSessionExpired={onSessionExpired}
                  encounterId={encounterId}
                  onInsert={(soap) => patch({ soap: { ...d.soap, ...Object.fromEntries(Object.entries(soap).filter(([, v]) => !!v)) }, aiAssisted: true })}
                />
              )}
              {e.status !== 'ENTERED_IN_ERROR' && (isAuthor || can(session.roles, 'encounterEnteredInErrorAny')) && canWrite && (
                <button type="button" className="danger-button" onClick={() => setEie('')}>
                  Mark as entered in error
                </button>
              )}
            </aside>
          </div>
          {eie !== undefined && (
            <Dialog title="Mark encounter as entered in error" onClose={() => setEie(undefined)}>
              <p>The note stays in the record for audit but is excluded from clinical views.</p>
              <form className="form" onSubmit={submitEie}>
                <div className="field">
                  <label htmlFor="eie-reason">Reason</label>
                  <textarea id="eie-reason" rows={3} required maxLength={1000} value={eie} onChange={(ev) => setEie(ev.target.value)} />
                </div>
                <div className="actions">
                  <button type="submit" className="danger-button" disabled={!eie.trim() || action.busy}>
                    Confirm
                  </button>
                  <button type="button" className="secondary" onClick={() => setEie(undefined)}>
                    Cancel
                  </button>
                </div>
              </form>
            </Dialog>
          )}
        </>
      )}
    </section>
  );
}
