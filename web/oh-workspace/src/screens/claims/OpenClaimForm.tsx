import { useState, type FormEvent } from 'react';
import { CLAIM_TYPES, claimsApi, type Claim } from '../../api/claims';
import { treatmentApi } from '../../api/treatment';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { humanise } from '../../components/format';
import { ActionFeedback, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { clinicalContentField, isClinicalContentError } from '../../domain/absence';
import { emptyClaimForm, validateClaimForm, type ClaimForm } from '../../domain/claims';
import { problemCode } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

/**
 * "Open claim" (E08-F01) for CLAIMS_COORDINATOR / CASE_MANAGER. `caseId` fixed inside the case view; on `#/claims`
 * the coordinator enters the case reference id. Notes are screened for clinical content (422 shown on the field).
 */
export function OpenClaimForm({ props, caseId: fixedCaseId, onCreated, onCancel }: { props: ScreenProps; caseId?: string; onCreated: (c: Claim) => void; onCancel?: () => void }) {
  const { client, onSessionExpired } = props;
  const payers = useAsync(() => treatmentApi(client).partnerOrganisations('INSURER,TPA').catch(() => []), onSessionExpired);
  const [caseId, setCaseId] = useState(fixedCaseId ?? '');
  const [f, setF] = useState<ClaimForm>(emptyClaimForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const err = (k: string) => errorFor(errors, `claim-${k}`);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateClaimForm(f);
    if (!caseId.trim()) errs.unshift({ field: 'claim-caseId', message: 'Enter the case id' });
    setErrors(errs);
    if (errs.length) return;
    let failed: unknown;
    let created: Claim | undefined;
    const opt = (v: string) => (v.trim() ? v.trim() : undefined);
    const ok = await action.run(async () => {
      try {
        created = await claimsApi(client).createClaim(caseId.trim(), {
          claimType: f.claimType as Claim['claimType'],
          payerCode: f.payerCode.trim(),
          policyNumber: opt(f.policyNumber),
          adjusterName: opt(f.adjusterName),
          adjusterEmail: opt(f.adjusterEmail),
          adjusterUsername: opt(f.adjusterUsername),
          lostTime: f.lostTime,
          incidentDate: opt(f.incidentDate),
          notes: opt(f.notes),
        });
        return `Claim ${created.claimRef} opened (${humanise(created.status).toLowerCase()}).${f.lostTime ? ' A nurse case manager is assigned automatically.' : ''}`;
      } catch (ex) {
        failed = ex;
        throw ex;
      }
    });
    if (ok && created) {
      onCreated(created);
      return;
    }
    if (isClinicalContentError(failed)) {
      const field = clinicalContentField(failed) ?? 'notes';
      setErrors([{ field: `claim-${field}`, message: `${humanise(field)}: remove medical details – claims staff and insurers must not see clinical information` }]);
      action.reset();
    } else if (problemCode(failed) === 'claim_exists') {
      setErrors([{ field: 'claim-claimType', message: `A ${humanise(f.claimType).toLowerCase()} claim already exists for this case` }]);
      action.reset();
    }
  }

  const text = (k: keyof ClaimForm, label: string, extra: { type?: string; hint?: string } = {}) => (
    <IdField id={`claim-${k}`} label={label} error={err(k)} hint={extra.hint}>
      {(db) => <input id={`claim-${k}`} type={extra.type ?? 'text'} aria-describedby={db} aria-invalid={!!err(k)} value={String(f[k])} onChange={(e) => setF({ ...f, [k]: e.target.value })} />}
    </IdField>
  );

  return (
    <form className="form tile" onSubmit={submit} noValidate aria-label="Open claim">
      <h3>Open claim</h3>
      <ErrorSummary errors={errors} />
      {!fixedCaseId && (
        <IdField id="claim-caseId" label="Case id" required error={errorFor(errors, 'claim-caseId')} hint="The OH case the claim relates to (from the incident / OH notification).">
          {(db) => <input id="claim-caseId" aria-describedby={db} aria-invalid={!!errorFor(errors, 'claim-caseId')} value={caseId} onChange={(e) => setCaseId(e.target.value)} />}
        </IdField>
      )}
      <div className="inline-form">
        <IdField id="claim-claimType" label="Claim type" required error={err('claimType')}>
          {(db) => (
            <select id="claim-claimType" aria-describedby={db} aria-invalid={!!err('claimType')} value={f.claimType} onChange={(e) => setF({ ...f, claimType: e.target.value })}>
              <SelectOptions values={CLAIM_TYPES} />
            </select>
          )}
        </IdField>
        <IdField id="claim-payerCode" label="Payer" required error={err('payerCode')}>
          {(db) => (
            <select id="claim-payerCode" aria-describedby={db} aria-invalid={!!err('payerCode')} value={f.payerCode} onChange={(e) => setF({ ...f, payerCode: e.target.value })}>
              <option value="">Choose…</option>
              {(payers.data ?? []).map((p) => <option key={p.code} value={p.code}>{p.name} ({humanise(p.orgType)})</option>)}
            </select>
          )}
        </IdField>
        {text('policyNumber', 'Policy number')}
        {text('incidentDate', 'Incident date', { type: 'date' })}
      </div>
      <div className="inline-form">
        {text('adjusterName', 'Adjuster name')}
        {text('adjusterEmail', 'Adjuster e-mail', { type: 'email' })}
        {text('adjusterUsername', 'Adjuster portal username')}
      </div>
      <label className="inline-row">
        <input type="checkbox" checked={f.lostTime} onChange={(e) => setF({ ...f, lostTime: e.target.checked })} /> Lost-time claim (assigns a nurse case manager within 48 h)
      </label>
      <IdField id="claim-notes" label="Notes" error={err('notes')} hint="Visible to claims staff and the payer – no diagnoses, symptoms or treatment details.">
        {(db) => <textarea id="claim-notes" rows={2} maxLength={2000} aria-describedby={db} aria-invalid={!!err('notes')} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />}
      </IdField>
      <div className="actions">
        <button type="submit" disabled={action.busy}>Open claim</button>
        {onCancel && <button type="button" className="secondary" onClick={onCancel}>Cancel</button>}
      </div>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}
