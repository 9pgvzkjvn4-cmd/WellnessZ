import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import {
  URGENCIES,
  type AnswerValue,
  type Protocol,
  type ProtocolQuestion,
  type ProtocolSummary,
  type TriageMode,
  type TriageRequest,
  type TriageResult,
  type Urgency,
} from '../../api/types';
import { can } from '../../auth/roles';
import { RedFlagMark, SlaBadge, UrgencyBadge } from '../../components/badges';
import { formatDate, humanise, localToIso, toLocalInput } from '../../components/format';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Field, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { traverse, type Answers } from '../../domain/protocolRunner';
import { casePath, type CaseTabProps } from './caseTabProps';

const MODES: TriageMode[] = ['IN_PERSON', 'TELEPHONE', 'DIGITAL'];

function QuestionInput({ q, value, onChange }: { q: ProtocolQuestion; value: AnswerValue | undefined; onChange: (v: AnswerValue | undefined) => void }) {
  const name = `q-${q.id}`;
  if (q.type === 'BOOLEAN') {
    return (
      <div className="yes-no" role="radiogroup" aria-labelledby={`${name}-label`}>
        <label className="radio">
          <input type="radio" name={name} checked={value === true} onChange={() => onChange(true)} /> Yes
        </label>
        <label className="radio">
          <input type="radio" name={name} checked={value === false} onChange={() => onChange(false)} /> No
        </label>
      </div>
    );
  }
  if (q.type === 'SELECT') {
    return (
      <select aria-labelledby={`${name}-label`} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value || undefined)}>
        <option value="">Choose…</option>
        {(q.options ?? []).map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    );
  }
  return (
    <input
      type="number"
      aria-labelledby={`${name}-label`}
      min={q.min}
      max={q.max}
      step="any"
      value={typeof value === 'number' ? value : ''}
      onChange={(e) => {
        const n = e.target.valueAsNumber;
        onChange(e.target.value === '' || Number.isNaN(n) ? undefined : n);
      }}
    />
  );
}

function QuestionBlock({ q, value, onChange, active }: { q: ProtocolQuestion; value: AnswerValue | undefined; onChange: (v: AnswerValue | undefined) => void; active: boolean }) {
  const triggered = q.redFlag && value === true;
  return (
    <li className={`question ${q.redFlag ? 'question-redflag' : ''} ${triggered ? 'question-triggered' : ''} ${active ? 'question-active' : ''}`}>
      <p id={`q-${q.id}-label`} className="question-text">
        {q.redFlag && <RedFlagMark />} {q.text}
        {q.type === 'NUMBER' && (q.min !== undefined || q.max !== undefined) && (
          <span className="small muted"> ({q.min ?? '…'}–{q.max ?? '…'})</span>
        )}
      </p>
      {q.help && <p className="small muted">{q.help}</p>}
      <QuestionInput q={q} value={value} onChange={onChange} />
      {triggered && (
        <p className="redflag-stop" role="alert">
          <strong>Red flag – hard stop: EMERGENCY.</strong> Follow the emergency disposition now.
        </p>
      )}
    </li>
  );
}

function ResultPanel({ r }: { r: TriageResult }) {
  return (
    <div className={`result-panel ${r.finalUrgency === 'EMERGENCY' ? 'result-emergency' : ''}`} aria-live="polite">
      <h3>Triage result (server)</h3>
      <Dl
        items={[
          ['Final urgency', <UrgencyBadge key="f" urgency={r.finalUrgency} />],
          ['Computed urgency', <UrgencyBadge key="c" urgency={r.computedUrgency} />],
          ['Red flags', r.redFlagsTriggered.length ? r.redFlagsTriggered.join(', ') : 'None'],
          ['Disposition', r.disposition],
          ['Case type', humanise(r.caseType)],
          ['Queue', r.queueCode],
          ['Owner', r.ownerUsername ?? 'Unassigned'],
          ['SLA', <SlaBadge key="s" sla={r.sla} />],
          ['Protocol version', `v${r.protocolVersion}`],
          ['Advice', r.adviceKey],
          ['Call-back task', r.callBackTaskId ? 'Created' : undefined],
        ]}
      />
    </div>
  );
}

export function TriageTab({ client, caseId, session, refresh, onSessionExpired }: CaseTabProps) {
  const canWrite = can(session.roles, 'triageWrite');
  const protocols = useAsync(() => client.api<ProtocolSummary[]>('/triage/protocols'), onSessionExpired);
  const history = useAsync(() => client.api<TriageResult[]>(casePath(caseId, '/triage')), onSessionExpired);
  const [code, setCode] = useState('');
  const protocol = useAsync(() => (code ? client.api<Protocol>(`/triage/protocols/${seg(code)}`) : Promise.resolve(undefined)), onSessionExpired, [code]);
  const [mode, setMode] = useState<TriageMode>('IN_PERSON');
  const [answers, setAnswers] = useState<Answers>({});
  const [override, setOverride] = useState(false);
  const [overrideUrgency, setOverrideUrgency] = useState<Urgency | ''>('');
  const [overrideReason, setOverrideReason] = useState('');
  const [call, setCall] = useState({ caller: '', phone: '', startedAt: toLocalInput(), endedAt: '', consent: false, callBackAt: '' });
  const [result, setResult] = useState<TriageResult>();
  const action = useAction(onSessionExpired);

  const p = protocol.data;
  const t = p ? traverse(p, answers) : undefined;
  const byId = new Map((p?.questions ?? []).map((q) => [q.id, q]));
  const preview = t?.outcome ? p?.outcomes[t.outcome] : undefined;
  const overrideOk = !override || (!!overrideUrgency && overrideReason.trim().length >= 5);
  const callOk = mode !== 'TELEPHONE' || (call.caller.trim() && call.phone.trim() && call.startedAt && call.endedAt && call.consent);
  const ready = !!p && !!t?.outcome && !t.error && overrideOk && !!callOk;

  function chooseProtocol(c: string) {
    setCode(c);
    setAnswers({});
    setResult(undefined);
    action.reset();
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!p || !t || !ready) return;
    const body: TriageRequest = { protocolCode: p.code, protocolVersion: p.version, mode, answers: t.answersOnPath };
    if (override && overrideUrgency) {
      body.overrideUrgency = overrideUrgency;
      body.overrideReason = overrideReason.trim();
    }
    if (mode === 'TELEPHONE') {
      body.call = {
        caller: call.caller.trim(),
        phone: call.phone.trim(),
        startedAt: localToIso(call.startedAt) ?? '',
        endedAt: localToIso(call.endedAt) ?? '',
        consentToRemoteAssessment: call.consent,
        ...(call.callBackAt ? { callBackAt: localToIso(call.callBackAt) } : {}),
      };
    }
    await action.run(async () => {
      const r = await client.api<TriageResult>(casePath(caseId, '/triage'), { method: 'POST', body });
      setResult(r);
      history.reload();
      refresh();
      return 'Triage recorded.';
    });
  }

  async function sendAdvice(adviceKey: string) {
    await action.run(async () => {
      await client.api(casePath(caseId, '/advice'), { method: 'POST', body: { adviceKey } });
      return 'Advice made available to the employee (content-free notification sent).';
    });
  }

  return (
    <div className="grid-2 wide-left">
      <div>
        <h2>Protocol triage</h2>
        {!canWrite && <Alert kind="info">You can view triage history; recording triage requires a nurse, physician, AME, CMO or Nursing Head role.</Alert>}
        {!!protocols.error && <ErrorAlert error={protocols.error} />}
        <form className="form" onSubmit={submit}>
          <fieldset disabled={!canWrite}>
            <legend>Assessment</legend>
            <Field label="Protocol (presenting problem)">
              {(id) => (
                <select id={id} value={code} onChange={(e) => chooseProtocol(e.target.value)}>
                  <option value="">Choose protocol…</option>
                  {(protocols.data ?? []).map((pr) => (
                    <option key={pr.code} value={pr.code}>
                      {pr.title} (v{pr.version}{pr.status !== 'APPROVED' ? ` – ${humanise(pr.status)}` : ''})
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Mode">
              {(id) => (
                <select id={id} value={mode} onChange={(e) => setMode(e.target.value as TriageMode)}>
                  <SelectOptions values={MODES} />
                </select>
              )}
            </Field>
          </fieldset>

          {protocol.loading && code && <Loading label="Loading protocol…" />}
          {!!protocol.error && <ErrorAlert error={protocol.error} />}
          {p && p.status !== 'APPROVED' && <Alert kind="warning">Protocol v{p.version} is {humanise(p.status)} – not yet approved by the CMO.</Alert>}

          {p && t && (
            <fieldset disabled={!canWrite}>
              <legend>{p.title} – questions</legend>
              <ol className="question-list">
                {t.steps.map((s) => {
                  const q = byId.get(s.questionId);
                  return q ? <QuestionBlock key={q.id} q={q} value={answers[q.id]} active={false} onChange={(v) => setAnswers({ ...answers, [q.id]: v })} /> : null;
                })}
                {t.current && <QuestionBlock key={t.current.id} q={t.current} value={answers[t.current.id]} active onChange={(v) => setAnswers({ ...answers, [t.current!.id]: v })} />}
              </ol>
              {t.error === 'INVALID_ANSWER' && <Alert kind="error">The answer to the highlighted question is outside the allowed range.</Alert>}
              {t.error && t.error !== 'INVALID_ANSWER' && (
                <Alert kind="error">The protocol definition has a problem ({humanise(t.error)} at {t.errorAt ?? 'start'}). Report it to the CMO.</Alert>
              )}
              {t.outcome && (
                <div className={`preview ${t.outcome === 'EMERGENCY' ? 'preview-emergency' : ''}`} role="status">
                  <p>
                    Suggested urgency: <UrgencyBadge urgency={t.outcome} />
                    {t.redFlagsTriggered.length > 0 && <> · red flags: {t.redFlagsTriggered.join(', ')}</>}
                  </p>
                  {preview && <p><strong>Disposition:</strong> {preview.disposition}</p>}
                  <p className="small muted">Preview only – the server re-evaluates the protocol and records the urgency.</p>
                </div>
              )}
            </fieldset>
          )}

          {p && mode === 'TELEPHONE' && (
            <fieldset disabled={!canWrite}>
              <legend>Telephone call log</legend>
              <Field label="Caller" required>{(id) => <input id={id} value={call.caller} maxLength={120} onChange={(e) => setCall({ ...call, caller: e.target.value })} />}</Field>
              <Field label="Phone" required>{(id) => <input id={id} type="tel" value={call.phone} maxLength={40} onChange={(e) => setCall({ ...call, phone: e.target.value })} />}</Field>
              <Field label="Call started" required>{(id) => <input id={id} type="datetime-local" value={call.startedAt} onChange={(e) => setCall({ ...call, startedAt: e.target.value })} />}</Field>
              <Field label="Call ended" required>{(id) => <input id={id} type="datetime-local" value={call.endedAt} onChange={(e) => setCall({ ...call, endedAt: e.target.value })} />}</Field>
              <label className="checkbox">
                <input type="checkbox" checked={call.consent} onChange={(e) => setCall({ ...call, consent: e.target.checked })} /> Caller consents to remote assessment
                (required)
              </label>
              <Field label="Call-back time (creates a call-back task)">{(id) => <input id={id} type="datetime-local" value={call.callBackAt} onChange={(e) => setCall({ ...call, callBackAt: e.target.value })} />}</Field>
            </fieldset>
          )}

          {p && (
            <fieldset disabled={!canWrite}>
              <legend>Clinical override</legend>
              <label className="checkbox">
                <input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} /> Override the protocol urgency
              </label>
              {override && (
                <>
                  <Field label="Override urgency" required>
                    {(id) => (
                      <select id={id} value={overrideUrgency} onChange={(e) => setOverrideUrgency(e.target.value as Urgency | '')}>
                        <SelectOptions values={URGENCIES} empty="Choose…" />
                      </select>
                    )}
                  </Field>
                  <Field label="Reason for override" required hint="Minimum 5 characters; recorded with the assessment.">
                    {(id) => <textarea id={id} rows={3} maxLength={1000} value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)} />}
                  </Field>
                </>
              )}
              <button type="submit" disabled={!ready || action.busy || !canWrite}>
                Record triage
              </button>
            </fieldset>
          )}
        </form>
        <ActionFeedback error={action.error} message={action.message} />
        {result && <ResultPanel r={result} />}
        {result?.adviceKey && canWrite && (
          <button type="button" className="secondary" disabled={action.busy} onClick={() => void sendAdvice(result.adviceKey!)}>
            Make advice available to employee
          </button>
        )}
      </div>
      <div>
        <h2>Assessment history</h2>
        {history.loading && <Loading />}
        {!!history.error && <ErrorAlert error={history.error} />}
        {history.data && history.data.length === 0 && <Empty>No triage assessments yet.</Empty>}
        <ol className="history-list">
          {(history.data ?? []).map((h) => (
            <li key={h.assessmentId}>
              <UrgencyBadge urgency={h.finalUrgency} />{' '}
              {h.finalUrgency !== h.computedUrgency && <span className="small">(overridden from {humanise(h.computedUrgency)})</span>}
              <div className="small muted">
                {h.protocolCode ?? ''} v{h.protocolVersion} · {formatDate(h.assessedAt)} {h.assessedBy ? `· ${h.assessedBy}` : ''}
              </div>
              <div className="small">{h.disposition}</div>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
