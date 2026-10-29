import { useState, type FormEvent } from 'react';
import { BREAK_GLASS_MIN_REASON, platformApi, type BreakGlassGrant } from '../../api/platform';
import type { AuthClient } from '../../auth/AuthClient';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, errorFor, type FieldError } from '../../components/forms';
import { formatDate } from '../../components/format';
import { ActionFeedback, Alert } from '../../components/ui';
import { useAction } from '../../components/useAsync';

/**
 * E03-BL-021 break-glass: shown when the chart reports hidden restricted items. Justification ≥ 20 characters,
 * explicit warning that the DPO is alerted; on success the caller reloads the chart.
 */
export function BreakGlass({
  client,
  onSessionExpired,
  employeeId,
  hidden,
  canUse,
  onGranted,
}: {
  client: AuthClient;
  onSessionExpired: () => void;
  employeeId: string;
  hidden: number;
  canUse: boolean;
  onGranted: (g: BreakGlassGrant) => void;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [grant, setGrant] = useState<BreakGlassGrant>();
  const action = useAction(onSessionExpired);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const len = reason.trim().length;
    const errs: FieldError[] = len < BREAK_GLASS_MIN_REASON ? [{ field: 'bg-reason', message: `Enter a justification of at least ${BREAK_GLASS_MIN_REASON} characters (${len} so far).` }] : [];
    setErrors(errs);
    if (errs.length) return;
    let granted: BreakGlassGrant | undefined;
    const ok = await action.run(async () => {
      granted = await platformApi(client).breakGlass(employeeId, reason.trim());
    });
    if (ok && granted) {
      setGrant(granted);
      setOpen(false);
      setReason('');
      onGranted(granted);
    }
  }

  if (grant)
    return (
      <Alert kind="warning">
        Emergency access granted until <strong>{formatDate(grant.expiresAt)}</strong>. The Data Protection Officer has been alerted and will review this access.
      </Alert>
    );
  if (hidden <= 0) return null;

  return (
    <div className="break-glass">
      <Alert kind="info">
        <strong>{hidden} restricted item{hidden === 1 ? '' : 's'} hidden</strong> (mental-health or psychological records visible only to the psychological clinician).
      </Alert>
      {canUse && (
        <button type="button" className="danger-button" onClick={() => setOpen(true)}>
          Emergency access (break-glass)
        </button>
      )}
      {open && (
        <Dialog title="Emergency access (break-glass)" onClose={() => setOpen(false)} describedBy="bg-warning">
          <div id="bg-warning" className="alert alert-warning" role="note">
            <strong>Use only in a genuine emergency.</strong> The Data Protection Officer is alerted immediately, your access is logged, it expires
            after 4 hours and it is reviewed within 24 hours. Inappropriate use is a disciplinary matter.
          </div>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <IdField id="bg-reason" label="Justification" required hint={`At least ${BREAK_GLASS_MIN_REASON} characters – ${reason.trim().length} entered.`} error={errorFor(errors, 'bg-reason')}>
              {(d) => <textarea id="bg-reason" rows={4} maxLength={1000} value={reason} aria-describedby={d} aria-invalid={!!errorFor(errors, 'bg-reason')} onChange={(e) => setReason(e.target.value)} />}
            </IdField>
            <div className="actions">
              <button type="submit" className="danger-button" disabled={action.busy}>
                Confirm emergency access
              </button>
              <button type="button" className="secondary" onClick={() => setOpen(false)}>
                Cancel
              </button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </div>
  );
}
