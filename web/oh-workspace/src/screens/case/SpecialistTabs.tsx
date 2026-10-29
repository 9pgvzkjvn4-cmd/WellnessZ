import { useState, type FormEvent } from 'react';
import { CREW_STANDARDS, FITNESS_OUTCOMES, FOOD_EXCLUSION_REASONS, fitnessApi, IHR_TYPES, type FitnessDecision, type FitnessOutcome, type IhrAssessment } from '../../api/fitness';
import { documentContentUrl } from '../../api/platform';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Loading, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { DecisionHistory } from '../fitness/DecisionParts';
import type { CaseTabProps } from './caseTabProps';

// ---------------------------------------------------------------- Food handler (E05-F05)
const FOOD_CHECKLIST: { key: string; label: string }[] = [
  { key: 'diarrhoeaOrVomiting48h', label: 'Diarrhoea or vomiting in the last 48 hours' },
  { key: 'feverOrJaundice', label: 'Fever or jaundice' },
  { key: 'infectedSkinLesionsHandsFaceArms', label: 'Infected skin lesions on hands, face or arms' },
  { key: 'dischargeEyesEarsNose', label: 'Discharge from eyes, ears or nose' },
  { key: 'householdContactWithCase', label: 'Household contact with a case of a notifiable gastrointestinal infection' },
  { key: 'recentTravelHighRisk', label: 'Recent travel to a high-risk area with symptoms' },
];

/** Food-handler exclusion checklist → EXCLUDED (hygiene notified, status only), return request, clearance. */
export function FoodHandlerTab({ client, session, onSessionExpired, caseId, detail, refresh }: CaseTabProps) {
  const api = fitnessApi(client);
  const status = useAsync(() => api.foodEpisodes(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [ex, setEx] = useState({ reasonCategory: '', excludedFrom: isoDay(new Date()), expectedReturn: '', checklist: {} as Record<string, boolean> });
  const [clear, setClear] = useState({ symptomFree48h: false, clearanceSamples: '', note: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const canClear = can(session.roles, 'foodHandlerClear');
  const episodes = status.data ?? [];
  // Current episode: an open one (excluded / return pending) if any, otherwise the most recent.
  const s = episodes.find((x) => x.status !== 'FIT_TO_HANDLE_FOOD') ?? episodes[episodes.length - 1];

  async function exclude(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!ex.reasonCategory) errs.push({ field: 'fh-reason', message: 'Choose the exclusion reason (OH only).' });
    if (!ex.excludedFrom) errs.push({ field: 'fh-from', message: 'Enter the exclusion start date.' });
    if (ex.expectedReturn && ex.expectedReturn < ex.excludedFrom) errs.push({ field: 'fh-return', message: 'Expected return must be after the exclusion start.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.foodExclude(caseId, {
        reasonCategory: ex.reasonCategory,
        excludedFrom: ex.excludedFrom,
        ...(ex.expectedReturn ? { expectedReturn: ex.expectedReturn } : {}),
        checklistAnswers: Object.fromEntries(FOOD_CHECKLIST.map((q) => [q.key, ex.checklist[q.key] === true])),
      });
      return 'Excluded from food handling. Catering hygiene has been notified (status and dates only).';
    });
    if (ok) {
      status.reload();
      refresh();
    }
  }

  async function requestReturn() {
    if (await action.run(async () => { await api.foodReturnRequest(caseId); return 'Return requested – clearance is pending.'; })) status.reload();
  }

  async function doClear(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!clear.symptomFree48h) errs.push({ field: 'fh-symptom-free', message: 'Clearance requires the employee to be symptom-free for 48 hours.' });
    if (!clear.note.trim()) errs.push({ field: 'fh-note', message: 'Enter a clearance note.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const r = await api.foodClear(caseId, { criteria: { symptomFree48h: true, ...(clear.clearanceSamples.trim() ? { clearanceSamples: clear.clearanceSamples.trim() } : {}) }, note: clear.note.trim() });
      return `Cleared to handle food.${(r.clearanceDocumentId ?? r.documentId) ? ' A food-handler clearance document was generated.' : ''}`;
    });
    if (ok) {
      status.reload();
      refresh();
    }
  }

  const err = (id: string) => errorFor(errors, id);
  return (
    <div>
      <h2>Food-handler fitness</h2>
      <p className="small muted">{detail.employeeName}: catering hygiene sees status and dates only – never the reason.</p>
      {status.loading && <Loading />}
      {!!status.error && <ErrorAlert error={status.error} />}
      <p>
        Current status: {s ? <StatusBadge status={s.status} /> : <span className="muted">No food-handler record</span>}
        {s?.excludedFrom && <span className="small"> · excluded from {formatDay(s.excludedFrom)}</span>}
        {s?.expectedReturn && <span className="small"> · expected return {formatDay(s.expectedReturn)}</span>}
        {s?.clearedAt && <span className="small"> · cleared {formatDate(s.clearedAt)}</span>}
      </p>
      {episodes.length > 1 && (
        <div className="table-wrap">
          <table>
            <caption>Episodes on this case</caption>
            <thead>
              <tr>
                <th scope="col">Status</th>
                <th scope="col">Reason (OH only)</th>
                <th scope="col">Excluded from</th>
                <th scope="col">Expected return</th>
                <th scope="col">Cleared</th>
              </tr>
            </thead>
            <tbody>
              {episodes.map((x, i) => (
                <tr key={x.id ?? i}>
                  <td><StatusBadge status={x.status} /></td>
                  <td>{humanise(x.reasonCategory)}</td>
                  <td>{formatDay(x.excludedFrom)}</td>
                  <td>{formatDay(x.expectedReturn)}</td>
                  <td>{formatDate(x.clearedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <ErrorSummary errors={errors} />
      <ActionFeedback error={action.error} message={action.message} />
      <div className="grid-2">
        <form className="form tile" onSubmit={exclude} noValidate>
          <h3>Exclusion checklist</h3>
          <fieldset>
            <legend>Symptoms and exposures</legend>
            {FOOD_CHECKLIST.map((q) => (
              <label key={q.key} className="checkbox">
                <input type="checkbox" checked={ex.checklist[q.key] === true} onChange={(e) => setEx({ ...ex, checklist: { ...ex.checklist, [q.key]: e.target.checked } })} /> {q.label}
              </label>
            ))}
          </fieldset>
          <IdField id="fh-reason" label="Reason category (OH only)" required error={err('fh-reason')}>
            {(d) => (
              <select id="fh-reason" value={ex.reasonCategory} aria-describedby={d} aria-invalid={!!err('fh-reason')} onChange={(e) => setEx({ ...ex, reasonCategory: e.target.value })}>
                <SelectOptions values={FOOD_EXCLUSION_REASONS} empty="Choose…" />
              </select>
            )}
          </IdField>
          <IdField id="fh-from" label="Excluded from" required error={err('fh-from')}>
            {(d) => <input id="fh-from" type="date" value={ex.excludedFrom} aria-describedby={d} onChange={(e) => setEx({ ...ex, excludedFrom: e.target.value })} />}
          </IdField>
          <IdField id="fh-return" label="Expected return" error={err('fh-return')}>
            {(d) => <input id="fh-return" type="date" value={ex.expectedReturn} aria-describedby={d} onChange={(e) => setEx({ ...ex, expectedReturn: e.target.value })} />}
          </IdField>
          <button type="submit" className="danger-button" disabled={action.busy || s?.status === 'EXCLUDED'}>
            Exclude from food handling
          </button>
        </form>
        <div className="tile">
          <h3>Return to food handling</h3>
          <button type="button" className="secondary" disabled={action.busy || s?.status !== 'EXCLUDED'} onClick={() => void requestReturn()}>
            Request return (clearance pending)
          </button>
          <form className="form" onSubmit={doClear} noValidate>
            <fieldset disabled={!canClear}>
              <legend>Clearance</legend>
              {!canClear && <p className="small muted">Clearance is given by an OH nurse or physician.</p>}
              <div className={`field${err('fh-symptom-free') ? ' field-error' : ''}`}>
                {err('fh-symptom-free') && <span className="field-error-text small">{err('fh-symptom-free')}</span>}
                <label className="checkbox">
                  <input id="fh-symptom-free" type="checkbox" checked={clear.symptomFree48h} onChange={(e) => setClear({ ...clear, symptomFree48h: e.target.checked })} /> Symptom-free for at least 48 hours
                </label>
              </div>
              <IdField id="fh-samples" label="Clearance samples (if required)">
                {() => <input id="fh-samples" maxLength={500} value={clear.clearanceSamples} onChange={(e) => setClear({ ...clear, clearanceSamples: e.target.value })} />}
              </IdField>
              <IdField id="fh-note" label="Note" required error={err('fh-note')}>
                {(d) => <textarea id="fh-note" rows={2} maxLength={1000} value={clear.note} aria-describedby={d} onChange={(e) => setClear({ ...clear, note: e.target.value })} />}
              </IdField>
              <button type="submit" disabled={action.busy}>
                Clear to handle food
              </button>
            </fieldset>
          </form>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Crew (E05-F04)
/** AME crew assessment with EASA limitation codes → FitnessDecision + CAA-format crew medical report. */
export function CrewTab({ client, session, onSessionExpired, caseId, refresh }: CaseTabProps) {
  const api = fitnessApi(client);
  const codes = useAsync(() => api.limitationCodes(), onSessionExpired);
  const decisions = useAsync(() => api.decisions(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const today = isoDay(new Date());
  const [f, setF] = useState({ standardCode: 'CAA_CLASS1', outcome: '' as FitnessOutcome | '', limitationCodes: [] as string[], validFrom: today, validUntil: '', reviewDate: '', examinationDate: today, rationale: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [result, setResult] = useState<FitnessDecision>();
  const canAssess = can(session.roles, 'crewAssess');
  const applicable = (codes.data ?? []).filter((c) => c.appliesTo.includes(f.standardCode));

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.outcome) errs.push({ field: 'crew-outcome', message: 'Choose the outcome.' });
    if (!f.examinationDate) errs.push({ field: 'crew-exam', message: 'Enter the examination date.' });
    if (f.validUntil && f.validUntil < f.validFrom) errs.push({ field: 'crew-until', message: '“Valid until” must be after “valid from”.' });
    if (f.outcome === 'FIT_WITH_LIMITATIONS' && f.limitationCodes.length === 0) errs.push({ field: 'crew-codes', message: 'Fit with limitations needs at least one limitation code.' });
    if (!f.rationale.trim()) errs.push({ field: 'crew-rationale', message: 'Enter the rationale (OH only).' });
    setErrors(errs);
    if (errs.length || !f.outcome) return;
    const ok = await action.run(async () => {
      const d = await api.crewAssessment(caseId, {
        standardCode: f.standardCode as (typeof CREW_STANDARDS)[number],
        outcome: f.outcome as FitnessOutcome,
        limitationCodes: f.limitationCodes,
        validFrom: f.validFrom,
        ...(f.validUntil ? { validUntil: f.validUntil } : {}),
        ...(f.reviewDate ? { reviewDate: f.reviewDate } : {}),
        examinationDate: f.examinationDate,
        rationale: f.rationale.trim(),
      });
      setResult(d);
      return `Crew assessment recorded (${humanise(d.outcome)}). Roster status is sent to crew scheduling (status only).`;
    });
    if (ok) {
      decisions.reload();
      refresh();
    }
  }

  const crewHistory = (decisions.data ?? []).filter((d) => (CREW_STANDARDS as readonly string[]).includes(d.standardCode));
  const err = (id: string) => errorFor(errors, id);
  return (
    <div>
      <h2>Crew aeromedical assessment</h2>
      {!canAssess && <Alert kind="info">Crew assessments are recorded by an AME or the CMO.</Alert>}
      <ErrorSummary errors={errors} />
      <ActionFeedback error={action.error} message={action.message} />
      {result?.certificateDocumentId && (
        <p>
          <a href={documentContentUrl(result.certificateDocumentId)} target="_blank" rel="noopener">
            Open the {f.standardCode === 'CABIN_CREW' ? 'cabin crew' : 'crew'} medical report (CAA format)
          </a>
        </p>
      )}
      <form className="form" onSubmit={submit} noValidate>
        <fieldset disabled={!canAssess}>
          <legend className="visually-hidden">Crew assessment</legend>
          <div className="form-grid">
            <IdField id="crew-standard" label="Standard" required>
              {() => (
                <select id="crew-standard" value={f.standardCode} onChange={(e) => setF({ ...f, standardCode: e.target.value, limitationCodes: [] })}>
                  <SelectOptions values={CREW_STANDARDS} />
                </select>
              )}
            </IdField>
            <IdField id="crew-outcome" label="Outcome" required error={err('crew-outcome')}>
              {(d) => (
                <select id="crew-outcome" value={f.outcome} aria-describedby={d} aria-invalid={!!err('crew-outcome')} onChange={(e) => setF({ ...f, outcome: e.target.value as FitnessOutcome })}>
                  <SelectOptions values={FITNESS_OUTCOMES} empty="Choose…" />
                </select>
              )}
            </IdField>
            <IdField id="crew-exam" label="Examination date" required error={err('crew-exam')}>
              {(d) => <input id="crew-exam" type="date" value={f.examinationDate} aria-describedby={d} onChange={(e) => setF({ ...f, examinationDate: e.target.value })} />}
            </IdField>
            <IdField id="crew-from" label="Valid from" required>
              {() => <input id="crew-from" type="date" value={f.validFrom} onChange={(e) => setF({ ...f, validFrom: e.target.value })} />}
            </IdField>
            <IdField id="crew-until" label="Valid until" error={err('crew-until')}>
              {(d) => <input id="crew-until" type="date" value={f.validUntil} aria-describedby={d} onChange={(e) => setF({ ...f, validUntil: e.target.value })} />}
            </IdField>
            <IdField id="crew-review" label="Review date">
              {() => <input id="crew-review" type="date" value={f.reviewDate} onChange={(e) => setF({ ...f, reviewDate: e.target.value })} />}
            </IdField>
          </div>
          <fieldset id="crew-codes" tabIndex={-1}>
            <legend>EASA limitation codes</legend>
            {err('crew-codes') && <p className="field-error-text small">{err('crew-codes')}</p>}
            {codes.loading && <Loading />}
            {!!codes.error && <ErrorAlert error={codes.error} />}
            {applicable.length === 0 && !codes.loading && <p className="small muted">No limitation codes for this standard.</p>}
            <div className="checkbox-group">
              {applicable.map((c) => (
                <label key={c.code} className="checkbox">
                  <input type="checkbox" checked={f.limitationCodes.includes(c.code)} onChange={(e) => setF({ ...f, limitationCodes: e.target.checked ? [...f.limitationCodes, c.code] : f.limitationCodes.filter((x) => x !== c.code) })} />{' '}
                  <span className="mono">{c.code}</span> {c.label} {c.operational && <span className="badge badge-muted" title="Sent to rostering">Operational</span>}
                </label>
              ))}
            </div>
          </fieldset>
          <IdField id="crew-rationale" label="Rationale (OH only)" required error={err('crew-rationale')}>
            {(d) => <textarea id="crew-rationale" rows={3} maxLength={4000} value={f.rationale} aria-describedby={d} aria-invalid={!!err('crew-rationale')} onChange={(e) => setF({ ...f, rationale: e.target.value })} />}
          </IdField>
          <button type="submit" disabled={action.busy}>
            Record crew assessment
          </button>
        </fieldset>
      </form>
      <h3>Crew decisions</h3>
      {!!decisions.error && <ErrorAlert error={decisions.error} />}
      {decisions.data && crewHistory.length === 0 ? <Empty>No crew decisions.</Empty> : crewHistory.length > 0 && <DecisionHistory decisions={crewHistory} />}
    </div>
  );
}

// ---------------------------------------------------------------- IHR / redeployment (E05-F07)
function IhrCard({ a, roles, username, busy, onCmo, onAppeal, onAppealDecision }: {
  a: IhrAssessment;
  roles: readonly string[];
  username?: string;
  busy: boolean;
  onCmo: (b: { approve: boolean; decisionText: string; note: string }) => void;
  onAppeal: (b: { grounds: string; secondOpinionBy?: string }) => void;
  onAppealDecision: (b: { upheld: boolean; decisionText: string }) => void;
}) {
  const [cmo, setCmo] = useState({ approve: true, decisionText: '', note: '' });
  const [appeal, setAppeal] = useState({ grounds: '', secondOpinionBy: '' });
  const [ad, setAd] = useState({ upheld: false, decisionText: '' });
  const isCmo = roles.includes('CMO');
  const sameAsAssessor = !!username && a.assessedBy === username;
  return (
    <article className="tile" aria-label={`${humanise(a.type)} assessment`}>
      <h3>
        {humanise(a.type)} <StatusBadge status={a.status} />
      </h3>
      <Dl
        items={[
          ['Findings (OH only)', a.findings ? <span className="prewrap">{a.findings}</span> : undefined],
          ['Recommendation', a.recommendation],
          ['Policy', a.policyReference],
          ['Assessed', a.assessedBy ? `${a.assessedBy} ${formatDate(a.assessedAt)}` : undefined],
          ['Decision (shared)', a.decisionText],
          ['Decided', a.decidedBy ? `${a.decidedBy} ${formatDate(a.decidedAt)}` : undefined],
          ['Appeal window until', formatDay(a.appealDeadline)],
          ['Appeal grounds', a.appealGrounds],
          ['Appeal decision due', formatDay(a.appealDueAt)],
          ['Appeal outcome', a.appealUpheld == null ? undefined : a.appealUpheld ? 'Upheld' : 'Not upheld'],
        ]}
      />
      {a.status === 'AWAITING_CMO' && isCmo && (
        <form className="form" onSubmit={(e) => { e.preventDefault(); if (cmo.decisionText.trim()) onCmo({ ...cmo, decisionText: cmo.decisionText.trim(), note: cmo.note.trim() }); }}>
          <h4>CMO decision</h4>
          <label className="checkbox"><input type="radio" name={`cmo-${a.id}`} checked={cmo.approve} onChange={() => setCmo({ ...cmo, approve: true })} /> Approve</label>
          <label className="checkbox"><input type="radio" name={`cmo-${a.id}`} checked={!cmo.approve} onChange={() => setCmo({ ...cmo, approve: false })} /> Do not approve</label>
          <div className="field">
            <label htmlFor={`cmo-text-${a.id}`}>Decision text (functional – shared with HR and the employee)</label>
            <textarea id={`cmo-text-${a.id}`} rows={3} maxLength={2000} value={cmo.decisionText} onChange={(e) => setCmo({ ...cmo, decisionText: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor={`cmo-note-${a.id}`}>Internal note</label>
            <input id={`cmo-note-${a.id}`} maxLength={1000} value={cmo.note} onChange={(e) => setCmo({ ...cmo, note: e.target.value })} />
          </div>
          <button type="submit" disabled={busy || !cmo.decisionText.trim()}>Record CMO decision</button>
        </form>
      )}
      {a.status === 'DECIDED' && (
        <form className="form" onSubmit={(e) => { e.preventDefault(); if (appeal.grounds.trim()) onAppeal({ grounds: appeal.grounds.trim(), ...(appeal.secondOpinionBy.trim() ? { secondOpinionBy: appeal.secondOpinionBy.trim() } : {}) }); }}>
          <h4>Appeal (on behalf of the employee)</h4>
          <div className="field">
            <label htmlFor={`appeal-grounds-${a.id}`}>Grounds</label>
            <textarea id={`appeal-grounds-${a.id}`} rows={3} maxLength={2000} value={appeal.grounds} onChange={(e) => setAppeal({ ...appeal, grounds: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor={`appeal-second-${a.id}`}>Second opinion by (optional)</label>
            <input id={`appeal-second-${a.id}`} maxLength={200} value={appeal.secondOpinionBy} onChange={(e) => setAppeal({ ...appeal, secondOpinionBy: e.target.value })} />
          </div>
          <button type="submit" className="secondary" disabled={busy || !appeal.grounds.trim()}>Lodge appeal</button>
        </form>
      )}
      {a.status === 'APPEALED' && isCmo && (
        sameAsAssessor ? (
          <p className="small muted">The appeal must be decided by a CMO other than the assessing physician.</p>
        ) : (
          <form className="form" onSubmit={(e) => { e.preventDefault(); if (ad.decisionText.trim()) onAppealDecision({ upheld: ad.upheld, decisionText: ad.decisionText.trim() }); }}>
            <h4>Appeal decision</h4>
            <label className="checkbox"><input type="radio" name={`ad-${a.id}`} checked={ad.upheld} onChange={() => setAd({ ...ad, upheld: true })} /> Appeal upheld</label>
            <label className="checkbox"><input type="radio" name={`ad-${a.id}`} checked={!ad.upheld} onChange={() => setAd({ ...ad, upheld: false })} /> Appeal not upheld</label>
            <div className="field">
              <label htmlFor={`ad-text-${a.id}`}>Decision text (functional)</label>
              <textarea id={`ad-text-${a.id}`} rows={3} maxLength={2000} value={ad.decisionText} onChange={(e) => setAd({ ...ad, decisionText: e.target.value })} />
            </div>
            <button type="submit" disabled={busy || !ad.decisionText.trim()}>Record appeal decision</button>
          </form>
        )
      )}
    </article>
  );
}

/** Ill-health retirement / redeployment assessment, CMO decision, appeal, appeal decision. */
export function IhrTab({ client, session, onSessionExpired, caseId }: CaseTabProps) {
  const api = fitnessApi(client);
  const list = useAsync(() => api.ihrList(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [f, setF] = useState({ type: 'REDEPLOYMENT', findings: '', recommendation: '', policyReference: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const canAssess = can(session.roles, 'ihrAssess');

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.findings.trim()) errs.push({ field: 'ihr-findings', message: 'Enter the findings (OH only).' });
    if (!f.recommendation.trim()) errs.push({ field: 'ihr-recommendation', message: 'Enter the recommendation.' });
    if (!f.policyReference.trim()) errs.push({ field: 'ihr-policy', message: 'Enter the policy reference.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.ihrCreate(caseId, { type: f.type, findings: f.findings.trim(), recommendation: f.recommendation.trim(), policyReference: f.policyReference.trim() });
      return 'Assessment sent to the CMO for a decision.';
    });
    if (ok) {
      setF({ type: 'REDEPLOYMENT', findings: '', recommendation: '', policyReference: '' });
      list.reload();
    }
  }

  async function run(fn: () => Promise<unknown>, msg: string) {
    if (await action.run(async () => { await fn(); return msg; })) list.reload();
  }

  const rows = list.data ?? [];
  return (
    <div>
      <h2>Ill-health retirement and redeployment</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {list.data && rows.length === 0 && <Empty>No assessments.</Empty>}
      {rows.map((a) => (
        <IhrCard
          key={a.id}
          a={a}
          roles={session.roles}
          username={session.username}
          busy={action.busy}
          onCmo={(b) => void run(() => api.ihrCmoDecision(a.id, b), 'CMO decision recorded; the decision letter was generated and the 28-day appeal window started.')}
          onAppeal={(b) => void run(() => api.ihrAppeal(a.id, b), 'Appeal lodged; a decision is due within 30 days.')}
          onAppealDecision={(b) => void run(() => api.ihrAppealDecision(a.id, b), 'Appeal decision recorded.')}
        />
      ))}
      {canAssess && (
        <div className="tile">
          <h3>New assessment</h3>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={create} noValidate>
            <IdField id="ihr-type" label="Type" required>
              {() => (
                <select id="ihr-type" value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
                  <SelectOptions values={IHR_TYPES} />
                </select>
              )}
            </IdField>
            <IdField id="ihr-findings" label="Findings (OH only)" required error={errorFor(errors, 'ihr-findings')}>
              {(d) => <textarea id="ihr-findings" rows={4} maxLength={4000} value={f.findings} aria-describedby={d} onChange={(e) => setF({ ...f, findings: e.target.value })} />}
            </IdField>
            <IdField id="ihr-recommendation" label="Recommendation" required error={errorFor(errors, 'ihr-recommendation')}>
              {(d) => <textarea id="ihr-recommendation" rows={2} maxLength={2000} value={f.recommendation} aria-describedby={d} onChange={(e) => setF({ ...f, recommendation: e.target.value })} />}
            </IdField>
            <IdField id="ihr-policy" label="Policy reference" required error={errorFor(errors, 'ihr-policy')}>
              {(d) => <input id="ihr-policy" maxLength={200} value={f.policyReference} aria-describedby={d} onChange={(e) => setF({ ...f, policyReference: e.target.value })} />}
            </IdField>
            <button type="submit" disabled={action.busy}>Send to CMO</button>
          </form>
        </div>
      )}
    </div>
  );
}
