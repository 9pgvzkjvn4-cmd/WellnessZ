import { useState, type FormEvent } from 'react';
import { FITNESS_OUTCOMES, fitnessApi, type FitnessDecision, type FitnessOutcome } from '../../api/fitness';
import { platformApi } from '../../api/platform';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { humanise, isoDay } from '../../components/format';
import { RestrictionPicker } from '../../components/RestrictionPicker';
import { ActionFeedback, Alert, Dl, Empty, ErrorAlert, Loading, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { defaultValidUntil, mayDecide, maySignoff, RATIONALE_MAX, restrictionValues, validateDecision, type DecisionForm } from '../../domain/fitness';
import { DecisionHistory, SignoffDialog } from '../fitness/DecisionParts';
import type { CaseTabProps } from './caseTabProps';
import { FitnessTab } from './StatusTabs';

const blank = (): DecisionForm => ({
  standardCode: '', outcome: '', limitationCodes: [], restrictions: [], validFrom: isoDay(new Date()), validUntil: '', reviewDate: '', rationale: '', durationOverrideReason: '', generateCertificate: true,
});

/**
 * E05-F01 fitness decision against a role standard: outcome, limitation codes, catalogue restrictions with values,
 * validity / review, OH-only rationale, duration comparison (E05-BL-020) with override reason, history and sign-off.
 * The increment-1 quick status form remains available below.
 */
export function FitnessDecisionTab(props: CaseTabProps) {
  const { client, session, onSessionExpired, caseId, refresh } = props;
  const fapi = fitnessApi(client);
  const papi = platformApi(client);
  const standards = useAsync(() => papi.fitnessStandards(), onSessionExpired);
  const catalogue = useAsync(() => papi.restrictions(), onSessionExpired);
  const decisions = useAsync(() => fapi.decisions(caseId), onSessionExpired);
  const duration = useAsync(() => fapi.durationComparison(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [f, setF] = useState<DecisionForm>(blank());
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [signing, setSigning] = useState<FitnessDecision>();
  const canDecide = can(session.roles, 'fitnessDecide');
  const std = (standards.data ?? []).find((s) => s.code === f.standardCode);
  const allowed = mayDecide(std, session.roles);
  const dur = duration.data ?? null;
  const cat = catalogue.data ?? [];

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateDecision(f, cat, dur);
    setErrors(errs);
    if (errs.length || !f.outcome) return;
    let d: FitnessDecision | undefined;
    const ok = await action.run(async () => {
      d = await fapi.decide(caseId, {
        standardCode: f.standardCode,
        outcome: f.outcome as FitnessOutcome,
        limitationCodes: f.limitationCodes,
        restrictions: restrictionValues(f.restrictions, cat),
        validFrom: f.validFrom,
        ...(f.validUntil ? { validUntil: f.validUntil } : {}),
        ...(f.reviewDate ? { reviewDate: f.reviewDate } : {}),
        rationale: f.rationale.trim(),
        ...(f.durationOverrideReason.trim() ? { durationOverrideReason: f.durationOverrideReason.trim() } : {}),
        generateCertificate: f.generateCertificate,
      });
      return d.status === 'AWAITING_SIGNOFF'
        ? `Decision recorded and awaiting ${d.outcome === 'PERMANENTLY_UNFIT' ? 'CMO' : (std?.requiresSignoffRole ?? 'AME')} sign-off. Status stays “under review” until then.`
        : `Decision recorded: ${humanise(d.outcome)}.${d.certificateDocumentId ? ' A draft fitness certificate (no diagnosis) was generated.' : ''}`;
    });
    if (ok) {
      setF(blank());
      decisions.reload();
      refresh();
    }
  }

  async function signoff(d: FitnessDecision, approve: boolean, note: string) {
    const ok = await action.run(async () => {
      await fapi.signoff(d.id, approve, note);
      return approve ? 'Decision signed off.' : 'Decision rejected.';
    });
    if (ok) {
      setSigning(undefined);
      decisions.reload();
      refresh();
    }
  }

  const history = decisions.data ?? [];
  const awaiting = history.filter((d) => d.status === 'AWAITING_SIGNOFF');
  const err = (id: string) => errorFor(errors, id);

  return (
    <div>
      <div className="grid-2 wide-left">
        <div>
          <h2>Fitness decision</h2>
          {awaiting.length > 0 && <Alert kind="warning">{awaiting.length} decision{awaiting.length === 1 ? ' is' : 's are'} awaiting sign-off.</Alert>}
          {!canDecide && <Alert kind="info">Fitness decisions are recorded by OH nurses (non-licensed standards), physicians, AMEs and the CMO.</Alert>}
          <ErrorSummary errors={errors} />
          <ActionFeedback error={action.error} message={action.message} />
          {!!(standards.error || catalogue.error) && <ErrorAlert error={standards.error ?? catalogue.error} />}
          <form className="form" onSubmit={submit} noValidate>
            <fieldset disabled={!canDecide}>
              <legend className="visually-hidden">Record fitness decision</legend>
              <div className="form-grid">
                <IdField id="fd-standard" label="Fitness standard" required error={err('fd-standard')}>
                  {(d) => (
                    <select
                      id="fd-standard"
                      value={f.standardCode}
                      aria-describedby={d}
                      aria-invalid={!!err('fd-standard')}
                      onChange={(e) => {
                        const s = (standards.data ?? []).find((x) => x.code === e.target.value);
                        setF({ ...f, standardCode: e.target.value, limitationCodes: [], validUntil: defaultValidUntil(f.validFrom, s?.validityMonths) });
                      }}
                    >
                      <option value="">Choose…</option>
                      {(standards.data ?? []).map((s) => (
                        <option key={s.code} value={s.code}>{s.title}</option>
                      ))}
                    </select>
                  )}
                </IdField>
                <IdField id="fd-outcome" label="Outcome" required error={err('fd-outcome')}>
                  {(d) => (
                    <select id="fd-outcome" value={f.outcome} aria-describedby={d} aria-invalid={!!err('fd-outcome')} onChange={(e) => setF({ ...f, outcome: e.target.value as FitnessOutcome })}>
                      <SelectOptions values={FITNESS_OUTCOMES} empty="Choose…" />
                    </select>
                  )}
                </IdField>
              </div>
              {std && (
                <div className="standard-ref">
                  <Dl
                    items={[
                      ['Source', std.source],
                      ['Validity', std.validityMonths ? `${std.validityMonths} months${std.validityMonthsOver40 ? ` (${std.validityMonthsOver40} months over 40)` : ''}` : undefined],
                      ['Sign-off', std.requiresSignoffRole ? `${std.requiresSignoffRole} required` : 'Not required'],
                      ['Version', std.version],
                      ['Reference', std.referenceUrl ? <a href={std.referenceUrl} target="_blank" rel="noopener noreferrer">Open standard</a> : undefined],
                    ]}
                  />
                </div>
              )}
              {std && !allowed && <Alert kind="error">This is a licensed standard: only an AME or the CMO can record the decision.</Alert>}
              {f.outcome === 'PERMANENTLY_UNFIT' && <Alert kind="warning">Permanently unfit needs CMO sign-off; the status stays “under review” until then.</Alert>}
              {std && std.limitationCodes.length > 0 && (
                <fieldset>
                  <legend>Limitation codes</legend>
                  <div className="checkbox-group">
                    {std.limitationCodes.map((c) => (
                      <label key={c} className="checkbox">
                        <input type="checkbox" checked={f.limitationCodes.includes(c)} onChange={(e) => setF({ ...f, limitationCodes: e.target.checked ? [...f.limitationCodes, c] : f.limitationCodes.filter((x) => x !== c) })} /> {c}
                      </label>
                    ))}
                  </div>
                </fieldset>
              )}
              <RestrictionPicker catalogue={cat} value={f.restrictions} onChange={(r) => setF({ ...f, restrictions: r })} idPrefix="fd-restriction" errors={errors} label="Restrictions (functional wording – shared with the manager)" />
              <div className="form-grid">
                <IdField id="fd-valid-from" label="Valid from" required error={err('fd-valid-from')}>
                  {(d) => <input id="fd-valid-from" type="date" value={f.validFrom} aria-describedby={d} onChange={(e) => setF({ ...f, validFrom: e.target.value, validUntil: f.validUntil || defaultValidUntil(e.target.value, std?.validityMonths) })} />}
                </IdField>
                <IdField id="fd-valid-until" label="Valid until" hint="Defaults from the standard's validity." error={err('fd-valid-until')}>
                  {(d) => <input id="fd-valid-until" type="date" value={f.validUntil} aria-describedby={d} aria-invalid={!!err('fd-valid-until')} onChange={(e) => setF({ ...f, validUntil: e.target.value })} />}
                </IdField>
                <IdField id="fd-review" label="Review date" error={err('fd-review')}>
                  {(d) => <input id="fd-review" type="date" value={f.reviewDate} aria-describedby={d} aria-invalid={!!err('fd-review')} onChange={(e) => setF({ ...f, reviewDate: e.target.value })} />}
                </IdField>
              </div>
              <IdField id="fd-rationale" label="Clinical rationale (OH only – never shared)" required hint={`${f.rationale.length} / ${RATIONALE_MAX}`} error={err('fd-rationale')}>
                {(d) => <textarea id="fd-rationale" rows={4} maxLength={RATIONALE_MAX} value={f.rationale} aria-describedby={d} aria-invalid={!!err('fd-rationale')} onChange={(e) => setF({ ...f, rationale: e.target.value })} />}
              </IdField>
              {dur?.exceeded && (
                <IdField id="fd-duration-reason" label="Duration override reason" required error={err('fd-duration-reason')}>
                  {(d) => <textarea id="fd-duration-reason" rows={2} maxLength={1000} value={f.durationOverrideReason} aria-describedby={d} aria-invalid={!!err('fd-duration-reason')} onChange={(e) => setF({ ...f, durationOverrideReason: e.target.value })} />}
                </IdField>
              )}
              <label className="checkbox">
                <input type="checkbox" checked={f.generateCertificate} onChange={(e) => setF({ ...f, generateCertificate: e.target.checked })} /> Generate a draft fitness certificate (no diagnosis) when final
              </label>
              <button type="submit" disabled={action.busy || !allowed}>
                Record decision
              </button>
            </fieldset>
          </form>
        </div>
        <aside className="side-panel">
          <section className="tile" aria-labelledby="dur-title">
            <h2 id="dur-title">Duration guideline</h2>
            {duration.loading && <Loading />}
            {!!duration.error && <ErrorAlert error={duration.error} />}
            {!duration.loading && !duration.error && (!dur || dur.expectedDays == null) && <p className="small muted">No guideline for {dur?.icdCode ? `diagnosis ${dur.icdCode}` : 'this case (no primary diagnosis coded)'}.</p>}
            {dur && dur.expectedDays != null && (
              <>
                <Dl
                  items={[
                    ['Diagnosis', dur.icdCode],
                    ['Guideline', dur.guidelineTitle],
                    ['Expected', `${dur.expectedDays} days`],
                    ['Actual', dur.actualDays != null ? `${dur.actualDays} days` : undefined],
                    ['Variance', dur.varianceDays != null ? `${dur.varianceDays > 0 ? '+' : ''}${dur.varianceDays} days` : undefined],
                    ['Source', dur.source],
                  ]}
                />
                {dur.exceeded ? (
                  <p className="badge badge-overdue">
                    <span aria-hidden="true">▲ </span>Guideline exceeded – override reason required
                  </p>
                ) : (
                  <p className="badge badge-fit">
                    <span aria-hidden="true">✓ </span>Within guideline
                  </p>
                )}
              </>
            )}
          </section>
        </aside>
      </div>
      <h2>History</h2>
      {decisions.loading && <Loading />}
      {!!decisions.error && <ErrorAlert error={decisions.error} />}
      {decisions.data && history.length === 0 && <Empty>No decisions yet.</Empty>}
      {history.length > 0 && <DecisionHistory decisions={history} onSignoff={setSigning} canSignoff={(d) => maySignoff(d, session.roles)} />}
      {signing && <SignoffDialog decision={signing} busy={action.busy} error={action.error} onClose={() => setSigning(undefined)} onSubmit={(a, n) => void signoff(signing, a, n)} />}
      <details className="legacy">
        <summary>Quick status update without a standard (increment 1)</summary>
        <FitnessTab {...props} />
      </details>
    </div>
  );
}
