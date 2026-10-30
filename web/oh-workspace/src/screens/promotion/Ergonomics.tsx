import { useState, type FormEvent } from 'react';
import { DEMAND_DIMENSIONS } from '../../api/platform';
import { ASSESSMENT_TYPES, promotionApi, type ErgoAction, type ErgoAssessment } from '../../api/promotion';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatBytes, formatDate, formatDay, humanise } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { FieldError } from '../../domain/devices';
import { ACTION_CLOSE_KPI_DAYS, actionView, addDaysIso, CHECKLISTS, ergonomicScore, ragLabel, ragTone, SCORE_LABELS, validateAssessment, type AssessmentForm } from '../../domain/promotion';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

type Props = Pick<ScreenProps, 'client' | 'onSessionExpired'>;
const MAX_PHOTO_BYTES = 10 * 1024 * 1024;
const emptyForm = (): AssessmentForm => ({ type: 'DSE_WORKSTATION', unitCode: '', jobCode: '', employeeId: '', location: '', checklist: {}, findings: '' });

function NewAssessment({ client, onSessionExpired, onCreated }: Props & { onCreated: (a: ErgoAssessment) => void }) {
  const [f, setF] = useState<AssessmentForm>(emptyForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const items = CHECKLISTS[f.type] ?? [];
  const score = ergonomicScore(f.checklist, items);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateAssessment(f);
    setErrors(errs);
    if (errs.length) return;
    const checklist: Record<string, number> = {};
    for (const i of items) checklist[i.key] = f.checklist[i.key]!;
    let created: ErgoAssessment | undefined;
    const ok = await action.run(async () => {
      created = await promotionApi(client).createAssessment({
        type: f.type as 'TASK',
        unitCode: f.unitCode.trim().toUpperCase(),
        jobCode: f.jobCode.trim().toUpperCase(),
        ...(f.employeeId.trim() ? { employeeId: f.employeeId.trim().toUpperCase() } : {}),
        location: f.location.trim(),
        checklist,
        ...(f.findings.trim() ? { findings: f.findings.trim() } : {}),
      });
    });
    if (ok && created) {
      setF(emptyForm());
      onCreated(created);
    }
  }

  return (
    <details className="card">
      <summary>New ergonomic assessment</summary>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <div className="inline-form">
          <IdField id="ea-type" label="Type" required error={errorFor(errors, 'ea-type')}>{(db) => <select id="ea-type" aria-describedby={db} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value, checklist: {} })}><SelectOptions values={ASSESSMENT_TYPES} /></select>}</IdField>
          <IdField id="ea-unit" label="Unit" required error={errorFor(errors, 'ea-unit')}>{(db) => <input id="ea-unit" size={10} aria-describedby={db} value={f.unitCode} onChange={(e) => setF({ ...f, unitCode: e.target.value })} />}</IdField>
          <IdField id="ea-job" label="Job code" required error={errorFor(errors, 'ea-job')}>{(db) => <input id="ea-job" size={14} aria-describedby={db} value={f.jobCode} onChange={(e) => setF({ ...f, jobCode: e.target.value })} />}</IdField>
          <IdField id="ea-location" label="Location" required error={errorFor(errors, 'ea-location')}>{(db) => <input id="ea-location" size={16} aria-describedby={db} value={f.location} onChange={(e) => setF({ ...f, location: e.target.value })} />}</IdField>
          <IdField id="ea-employee" label="Employee ID (optional)" error={errorFor(errors, 'ea-employee')}>{(db) => <input id="ea-employee" size={8} aria-describedby={db} value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} />}</IdField>
        </div>
        <fieldset>
          <legend>Checklist – score each item 0 (no risk) to 3 (high risk)</legend>
          <div className="table-wrap">
            <table>
              <thead><tr><th scope="col">Item</th>{SCORE_LABELS.map((l) => <th key={l} scope="col">{l}</th>)}</tr></thead>
              <tbody>
                {items.map((i) => (
                  <tr key={i.key} className={errorFor(errors, `ea-item-${i.key}`) ? 'row-breached' : undefined}>
                    <th scope="row" id={`ea-item-label-${i.key}`}>{i.label}</th>
                    {[0, 1, 2, 3].map((v) => (
                      <td key={v}>
                        <input
                          id={v === 0 ? `ea-item-${i.key}` : undefined}
                          type="radio"
                          name={`ea-${i.key}`}
                          aria-label={`${i.label}: ${SCORE_LABELS[v]}`}
                          checked={f.checklist[i.key] === v}
                          onChange={() => setF({ ...f, checklist: { ...f.checklist, [i.key]: v } })}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p aria-live="polite">
            Score {score.total} / {score.max} ({score.pct} %, {score.scored} of {items.length} scored) – preview <ToneBadge tone={ragTone(score.rag)}>{ragLabel(score.rag)}</ToneBadge>
          </p>
        </fieldset>
        <IdField id="ea-findings" label="Findings (optional)" hint="Workplace findings only – no health details." error={errorFor(errors, 'ea-findings')}>
          {(db) => <textarea id="ea-findings" rows={3} maxLength={4000} aria-describedby={db} value={f.findings} onChange={(e) => setF({ ...f, findings: e.target.value })} />}
        </IdField>
        <button type="submit" disabled={action.busy}>Save assessment</button>
      </form>
      <ActionFeedback error={action.error} />
    </details>
  );
}

function JobDemandPanel({ a }: { a: ErgoAssessment }) {
  const p = a.jobDemandProfile;
  return (
    <section aria-labelledby="jd-title">
      <h3 id="jd-title">Job-demand profile</h3>
      {!p ? <Empty>No job-demand profile for {a.jobCode}.</Empty> : (
        <>
          <p className="small">{p.title} ({p.jobCode}) · {p.unit}{p.validatedBy ? ` · validated by ${p.validatedBy} ${formatDay(p.validatedAt)}` : ''}</p>
          <ul className="plain-list">
            {DEMAND_DIMENSIONS.filter((d) => p.demands[d] !== undefined).map((d) => {
              const v = p.demands[d];
              return <li key={d}>{humanise(d)}: <strong>{typeof v === 'boolean' ? (v ? 'Yes' : 'No') : v}</strong></li>;
            })}
            {p.sitting && <li>Sitting: <strong>{humanise(p.sitting)}</strong></li>}
          </ul>
        </>
      )}
    </section>
  );
}

function Photos({ client, onSessionExpired, a, canUpload, onUploaded }: Props & { a: ErgoAssessment; canUpload: boolean; onUploaded: () => void }) {
  const [error, setError] = useState<string>();
  const action = useAction(onSessionExpired);
  async function upload(file: File | undefined) {
    if (!file) return;
    if (!/^image\/(jpeg|png|webp|heic)$/.test(file.type)) return setError('Choose a JPEG, PNG, WebP or HEIC image.');
    if (file.size > MAX_PHOTO_BYTES) return setError(`The photo is ${formatBytes(file.size)} – the limit is ${formatBytes(MAX_PHOTO_BYTES)}.`);
    setError(undefined);
    const ok = await action.run(async () => {
      await promotionApi(client).uploadPhoto(a.id, file, file.name);
      return `${file.name} uploaded.`;
    });
    if (ok) onUploaded();
  }
  return (
    <section aria-labelledby="ph-title">
      <h3 id="ph-title">Photos</h3>
      {(a.photos ?? []).length === 0 ? <p className="small muted">No photos.</p> : (
        <ul className="plain-list">{(a.photos ?? []).map((p) => <li key={p.id}>{p.fileName ?? 'Photo'} <span className="small muted">{formatDate(p.uploadedAt)}</span></li>)}</ul>
      )}
      {canUpload && (
        <IdField id="ea-photo" label="Upload a workstation / task photo" hint="Non-clinical image of the workplace. Avoid faces where possible." error={error}>
          {(db) => <input id="ea-photo" type="file" accept="image/jpeg,image/png,image/webp,image/heic" capture="environment" aria-describedby={db} disabled={action.busy} onChange={(e) => { void upload(e.target.files?.[0]); e.target.value = ''; }} />}
        </IdField>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function Actions({ client, onSessionExpired, a, onChanged }: Props & { a: ErgoAssessment; onChanged: () => void }) {
  const [f, setF] = useState({ title: '', owner: '', dueDate: addDaysIso(30) });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const actions = [...(a.actions ?? [])].sort((x, y) => Number(x.status === 'DONE') - Number(y.status === 'DONE') || x.dueDate.localeCompare(y.dueDate));
  async function add(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.title.trim()) errs.push({ field: 'act-title', message: 'Enter the action.' });
    if (!f.owner.trim()) errs.push({ field: 'act-owner', message: 'Name the owner.' });
    if (!f.dueDate) errs.push({ field: 'act-due', message: 'Enter the due date.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await promotionApi(client).createAction(a.id, { title: f.title.trim(), owner: f.owner.trim(), dueDate: f.dueDate, status: 'OPEN' });
      return 'Action added.';
    });
    if (ok) {
      setF({ title: '', owner: '', dueDate: addDaysIso(30) });
      onChanged();
    }
  }
  async function setStatus(x: ErgoAction, status: 'OPEN' | 'DONE') {
    const ok = await action.run(async () => {
      await promotionApi(client).updateAction(a.id, x.id, { title: x.title, owner: x.owner, dueDate: x.dueDate, status });
      return status === 'DONE' ? `${x.title} closed.` : `${x.title} reopened.`;
    });
    if (ok) onChanged();
  }
  return (
    <section aria-labelledby="act-title-h">
      <h3 id="act-title-h">Actions</h3>
      <p className="small muted">KPI: actions closed within {ACTION_CLOSE_KPI_DAYS} days. Owners are reminded before the due date.</p>
      {actions.length === 0 ? <Empty>No actions yet.</Empty> : (
        <div className="table-wrap">
          <table>
            <caption>Actions</caption>
            <thead><tr><th scope="col">Action</th><th scope="col">Owner</th><th scope="col">Due</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Change</span></th></tr></thead>
            <tbody>
              {actions.map((x) => {
                const v = actionView(x);
                return (
                  <tr key={x.id} className={v.overdue ? 'row-breached' : undefined}>
                    <th scope="row">{x.title}</th>
                    <td>{x.owner}</td>
                    <td>{formatDay(x.dueDate)}</td>
                    <td><ToneBadge tone={v.tone}>{v.label}</ToneBadge></td>
                    <td>
                      <button type="button" className="small-button secondary" disabled={action.busy} onClick={() => void setStatus(x, x.status === 'DONE' ? 'OPEN' : 'DONE')}>
                        {x.status === 'DONE' ? `Reopen ${x.title}` : `Close ${x.title}`}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <form className="form inline-form" onSubmit={add} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="act-title" label="New action" required error={errorFor(errors, 'act-title')}>{(db) => <input id="act-title" size={30} maxLength={300} aria-describedby={db} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />}</IdField>
        <IdField id="act-owner" label="Owner" required error={errorFor(errors, 'act-owner')}>{(db) => <input id="act-owner" size={14} aria-describedby={db} value={f.owner} onChange={(e) => setF({ ...f, owner: e.target.value })} />}</IdField>
        <IdField id="act-due" label="Due" required error={errorFor(errors, 'act-due')}>{(db) => <input id="act-due" type="date" aria-describedby={db} value={f.dueDate} onChange={(e) => setF({ ...f, dueDate: e.target.value })} />}</IdField>
        <button type="submit" className="secondary" disabled={action.busy}>Add action</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function AssessmentDetail({ client, session, onSessionExpired, id }: ScreenProps & { id: string }) {
  const detail = useAsync(() => promotionApi(client).assessment(id), onSessionExpired, [id]);
  const a = detail.data;
  return (
    <section className="card" aria-labelledby="ea-detail">
      <h2 id="ea-detail">{a ? `${a.ref ?? 'Assessment'} – ${humanise(a.type)}, ${a.location}` : 'Assessment'}</h2>
      {detail.loading && !a && <Loading />}
      {!!detail.error && <ErrorAlert error={detail.error} />}
      {a && (
        <>
          <div className="grid-2">
            <Dl items={[
              ['Unit', a.unitCode],
              ['Job', a.jobCode],
              ['Employee', a.employeeId],
              ['Assessed', `${formatDate(a.createdAt)}${a.assessor ? ` by ${a.assessor}` : ''}`],
              ['Findings', a.findings ? <span className="prewrap">{a.findings}</span> : '–'],
            ]} />
            <div className="tiles" role="group" aria-label="Risk">
              <KpiTile label="Risk score" value={a.riskScore} />
              <KpiTile label="Rating" value={<ToneBadge tone={ragTone(a.rag)}>{ragLabel(a.rag)}</ToneBadge>} />
            </div>
          </div>
          <details>
            <summary>Checklist scores</summary>
            <ul className="plain-list">
              {(CHECKLISTS[a.type] ?? Object.keys(a.checklist).map((key) => ({ key, label: humanise(key) }))).map((i) => <li key={i.key}>{i.label}: {a.checklist[i.key] ?? '–'}</li>)}
            </ul>
          </details>
          <div className="grid-2">
            <JobDemandPanel a={a} />
            <Photos client={client} onSessionExpired={onSessionExpired} a={a} canUpload={can(session.roles, 'ergonomicsPhotos')} onUploaded={detail.reload} />
          </div>
          <Actions client={client} onSessionExpired={onSessionExpired} a={a} onChanged={detail.reload} />
        </>
      )}
    </section>
  );
}

/** `#/ergonomics` – assessments with checklist scoring, photos, job-demand profile and actions (E12, ERGONOMIST / HSE_OFFICER). */
export function Ergonomics(props: ScreenProps) {
  const { client, onSessionExpired } = props;
  const list = useAsync(() => promotionApi(client).assessments(), onSessionExpired);
  const [selected, setSelected] = useState<string>();
  const rows = [...(list.data ?? [])].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  const overdue = rows.reduce((n, a) => n + (a.actions ?? []).filter((x) => actionView(x).overdue).length, 0);
  return (
    <section>
      <PageTitle sub="DSE workstation, manual-handling and task assessments; actions are tracked to closure.">Ergonomics</PageTitle>
      <NewAssessment client={client} onSessionExpired={onSessionExpired} onCreated={(a) => { list.reload(); setSelected(a.id); }} />
      <div className="tiles" role="group" aria-label="Ergonomics KPIs">
        <KpiTile label="Red assessments" value={rows.filter((a) => a.rag === 'RED').length} />
        <KpiTile label="Open actions" value={rows.reduce((n, a) => n + (a.actions ?? []).filter((x) => x.status === 'OPEN').length, 0)} />
        <KpiTile label="Overdue actions" value={overdue} status={overdue > 0 ? 'MISSED' : undefined} />
      </div>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No assessments yet.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Assessments, latest first</caption>
            <thead><tr><th scope="col">Assessment</th><th scope="col">Unit</th><th scope="col">Job</th><th scope="col">Location</th><th scope="col">Rating</th><th scope="col" className="num">Open actions</th></tr></thead>
            <tbody>
              {rows.map((a) => {
                const od = (a.actions ?? []).filter((x) => actionView(x).overdue).length;
                return (
                  <tr key={a.id} aria-selected={a.id === selected} className={od > 0 ? 'row-breached' : undefined}>
                    <th scope="row"><button type="button" className="link-button" onClick={() => setSelected(a.id)}>{a.ref ?? humanise(a.type)}</button> <span className="small muted">{humanise(a.type)}</span></th>
                    <td>{a.unitCode}</td>
                    <td>{a.jobCode}</td>
                    <td>{a.location}</td>
                    <td><ToneBadge tone={ragTone(a.rag)}>{ragLabel(a.rag)}</ToneBadge></td>
                    <td className="num">{(a.actions ?? []).filter((x) => x.status === 'OPEN').length}{od > 0 && <span className="text-red"> ({od} overdue)</span>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {selected && <AssessmentDetail key={selected} {...props} id={selected} />}
    </section>
  );
}
