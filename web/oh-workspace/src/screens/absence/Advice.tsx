import { useState, type FormEvent } from 'react';
import { absenceApi, ADVICE_FITNESS, ADVICE_STATUSES, PROGNOSES, type AdviceQuestion, type AdviceReportDraft, type AdviceRequest } from '../../api/absence';
import { documentContentUrl } from '../../api/platform';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { clinicalContentField, isClinicalContentError, slaTone, todayIso } from '../../domain/absence';
import type { ScreenProps } from '../types';

const EDITABLE = ['ACCEPTED', 'REPORT_DRAFTED', 'WITHHELD'];
const fid = (field: string) => `advice-${field.replace(/[^A-Za-z0-9_-]/g, '-')}`;

interface Draft {
  fitness: string;
  prognosis: string;
  adjustments: string;
  restrictions: string;
  expectedReturn: string;
  reviewDate: string;
  answers: Record<string, string>;
}

function initialDraft(r: AdviceRequest): Draft {
  const rep = r.report ?? {};
  return {
    fitness: rep.fitness ?? '',
    prognosis: rep.prognosis ?? '',
    adjustments: rep.adjustments ?? '',
    restrictions: rep.restrictions ?? '',
    expectedReturn: rep.expectedReturn ?? '',
    reviewDate: rep.reviewDate ?? '',
    answers: Object.fromEntries(r.questionCodes.map((q) => [q, rep.answers?.[q] ?? ''])),
  };
}

function localErrors(d: Draft, codes: string[]): FieldError[] {
  const e: FieldError[] = [];
  if (!d.fitness) e.push({ field: fid('fitness'), message: 'Choose the functional fitness' });
  if (!d.prognosis) e.push({ field: fid('prognosis'), message: 'Choose a prognosis' });
  for (const c of codes) if (!d.answers[c]?.trim()) e.push({ field: fid(`answers.${c}`), message: `Answer question ${humanise(c)}` });
  if (d.reviewDate && d.reviewDate < todayIso()) e.push({ field: fid('reviewDate'), message: 'Review date must not be in the past' });
  return e;
}

function toBody(d: Draft): AdviceReportDraft {
  return {
    fitness: d.fitness as AdviceReportDraft['fitness'],
    prognosis: d.prognosis as AdviceReportDraft['prognosis'],
    ...(d.adjustments.trim() ? { adjustments: d.adjustments.trim() } : {}),
    ...(d.restrictions.trim() ? { restrictions: d.restrictions.trim() } : {}),
    ...(d.expectedReturn ? { expectedReturn: d.expectedReturn } : {}),
    ...(d.reviewDate ? { reviewDate: d.reviewDate } : {}),
    answers: Object.fromEntries(Object.entries(d.answers).map(([k, v]) => [k, v.trim()])),
  };
}

/** Report editor: functional advice only – every text is screened server-side (422 clinical content is shown on the field). */
export function AdviceReportEditor({ req, questions, canWrite, client, onSessionExpired, onChanged }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { req: AdviceRequest; questions: AdviceQuestion[]; canWrite: boolean; onChanged: () => void }) {
  const api = absenceApi(client);
  const action = useAction(onSessionExpired);
  const [draft, setDraft] = useState<Draft>(() => initialDraft(req));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const editable = canWrite && EDITABLE.includes(req.status);
  const qText = (code: string) => questions.find((q) => q.code === code)?.text ?? humanise(code);
  const errFor = (field: string) => errors.find((e) => e.field === fid(field))?.message;

  async function save(sign: boolean) {
    const local = localErrors(draft, req.questionCodes);
    setErrors(local);
    if (local.length) return;
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        await api.saveReport(req.id, toBody(draft));
        if (sign) await api.signReport(req.id);
      } catch (e) {
        failed = e;
        throw e;
      }
      return sign ? `Report signed for ${req.ref}. The employee is asked to review it before release.` : `Draft saved for ${req.ref}.`;
    });
    if (!ok) {
      const field = clinicalContentField(failed);
      if (isClinicalContentError(failed)) {
        setErrors([{ field: fid(field ?? 'adjustments'), message: `${field ? `${humanise(field.replace(/^answers\./, ''))}: ` : ''}remove medical information – describe functional capacity and work impact only` }]);
      }
      return;
    }
    onChanged();
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    void save(false);
  }

  const text = (field: 'adjustments' | 'restrictions', label: string, hint: string) => (
    <IdField id={fid(field)} label={label} hint={hint} error={errFor(field)}>
      {(db) => <textarea id={fid(field)} rows={3} maxLength={2000} disabled={!editable} aria-describedby={db} aria-invalid={!!errFor(field)} value={draft[field]} onChange={(e) => setDraft({ ...draft, [field]: e.target.value })} />}
    </IdField>
  );

  return (
    <section className="tile" aria-labelledby="advice-editor">
      <h2 id="advice-editor">Advice report – {req.ref}</h2>
      <p className="small muted">The report is shared with the requester only after the employee consents. Do not include diagnoses, symptoms, medication or test results.</p>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={onSubmit}>
        <fieldset disabled={!editable}>
          <legend className="visually-hidden">Advice report</legend>
          <div className="inline-form">
            <IdField id={fid('fitness')} label="Functional fitness" required error={errFor('fitness')}>
              {(db) => (
                <select id={fid('fitness')} aria-describedby={db} aria-invalid={!!errFor('fitness')} value={draft.fitness} onChange={(e) => setDraft({ ...draft, fitness: e.target.value })}>
                  <SelectOptions values={ADVICE_FITNESS} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id={fid('prognosis')} label="Prognosis" required error={errFor('prognosis')}>
              {(db) => (
                <select id={fid('prognosis')} aria-describedby={db} aria-invalid={!!errFor('prognosis')} value={draft.prognosis} onChange={(e) => setDraft({ ...draft, prognosis: e.target.value })}>
                  <SelectOptions values={PROGNOSES} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id={fid('expectedReturn')} label="Expected return" error={errFor('expectedReturn')}>
              {(db) => <input id={fid('expectedReturn')} type="date" aria-describedby={db} value={draft.expectedReturn} onChange={(e) => setDraft({ ...draft, expectedReturn: e.target.value })} />}
            </IdField>
            <IdField id={fid('reviewDate')} label="Review date" error={errFor('reviewDate')}>
              {(db) => <input id={fid('reviewDate')} type="date" aria-describedby={db} value={draft.reviewDate} onChange={(e) => setDraft({ ...draft, reviewDate: e.target.value })} />}
            </IdField>
          </div>
          {text('adjustments', 'Recommended adjustments', 'e.g. phased return 4 h/day for 2 weeks, no night shifts')}
          {text('restrictions', 'Work restrictions', 'Functional limits only, e.g. no lifting above 10 kg')}
          <h3>Answers to the requester's questions</h3>
          {req.questionCodes.map((code) => {
            const f = `answers.${code}`;
            return (
              <IdField key={code} id={fid(f)} label={qText(code)} required error={errFor(f)}>
                {(db) => (
                  <textarea id={fid(f)} rows={2} maxLength={2000} aria-describedby={db} aria-invalid={!!errFor(f)} value={draft.answers[code] ?? ''} onChange={(e) => setDraft({ ...draft, answers: { ...draft.answers, [code]: e.target.value } })} />
                )}
              </IdField>
            );
          })}
        </fieldset>
        {editable && (
          <div className="actions">
            <button type="submit" className="secondary" disabled={action.busy}>Save draft</button>
            <button type="button" disabled={action.busy} onClick={() => void save(true)}>Sign report</button>
          </div>
        )}
      </form>
      {!editable && !canWrite && <p className="small muted">Only clinicians can draft and sign advice reports.</p>}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/absence/advice` – management advice requests and the OH advice report (E06-F05). */
export function AbsenceAdvice({ client, session, onSessionExpired }: ScreenProps) {
  const api = absenceApi(client);
  const [status, setStatus] = useState('');
  const list = useAsync(() => api.adviceRequests(status || undefined), onSessionExpired, [status]);
  const questions = useAsync(() => api.questions(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [selected, setSelected] = useState<string>();
  const rows = list.data ?? [];
  const current = rows.find((r) => r.id === selected);
  const canWrite = can(session.roles, 'adviceReportWrite');

  async function accept(r: AdviceRequest) {
    const ok = await action.run(async () => {
      const res = await api.acceptAdvice(r.id);
      return `${r.ref} accepted${res.caseRef ? ` – case ${res.caseRef}` : ''}.`;
    });
    if (ok) {
      list.reload();
      setSelected(r.id);
    }
  }

  return (
    <section>
      <PageTitle sub="Requests from managers and HR (5 working-day SLA). Reports contain functional advice only and are released after the employee consents.">Advice requests</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              <SelectOptions values={ADVICE_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No advice requests.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} request{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Reference</th>
                <th scope="col">Employee</th>
                <th scope="col">Requested by</th>
                <th scope="col">Questions</th>
                <th scope="col">SLA</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const sla = ['SUBMITTED', 'ACCEPTED', 'REPORT_DRAFTED'].includes(r.status) ? slaTone(r.slaDueAt) : undefined;
                return (
                  <tr key={r.id} className={r.id === selected ? 'row-selected' : undefined}>
                    <th scope="row" className="mono">{r.ref}</th>
                    <td>{r.displayName} <span className="small muted">{r.employeeId}</span></td>
                    <td className="small">{r.requestedBy}{r.requestedByRole ? ` (${humanise(r.requestedByRole)})` : ''}<br />{formatDate(r.requestedAt)}</td>
                    <td className="small">{r.questionCodes.map(humanise).join(', ')}</td>
                    <td>{sla ? <ToneBadge tone={sla.tone}>{sla.label}</ToneBadge> : '–'}</td>
                    <td><StatusBadge status={r.status} /></td>
                    <td>
                      <div className="card-actions">
                        {r.status === 'SUBMITTED' && (
                          <button type="button" className="small-button" disabled={action.busy} onClick={() => void accept(r)} aria-label={`Accept ${r.ref}`}>Accept</button>
                        )}
                        {r.status !== 'SUBMITTED' && (
                          <button type="button" className="secondary small-button" aria-pressed={r.id === selected} onClick={() => setSelected(r.id === selected ? undefined : r.id)} aria-label={`Open report for ${r.ref}`}>
                            {EDITABLE.includes(r.status) && canWrite ? 'Edit report' : 'View'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {current && (
        <>
          <Dl
            items={[
              ['Context from requester', current.context ? <span className="prewrap">{current.context}</span> : undefined],
              ['Case', current.caseId ? <a href={`#/cases/${encodeURIComponent(current.caseId)}`}>{current.caseRef ?? 'Case'}</a> : current.caseRef],
              ['Released', current.report?.releasedAt ? formatDate(current.report.releasedAt) : undefined],
              ['Review date', current.report?.reviewDate ? formatDay(current.report.reviewDate) : undefined],
              ['Employee comment', current.report?.employeeComment],
              ['Released document', current.report?.documentId ? <a href={documentContentUrl(current.report.documentId)} target="_blank" rel="noopener">Advice report (PDF)</a> : undefined],
            ]}
          />
          <AdviceReportEditor key={`${current.id}-${current.status}`} req={current} questions={questions.data ?? []} canWrite={canWrite} client={client} onSessionExpired={onSessionExpired} onChanged={list.reload} />
        </>
      )}
    </section>
  );
}
