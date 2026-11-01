import { useId, useState } from 'react';
import type { AuthClient, Session } from '../../auth/AuthClient';
import type { AcceptResult, CaptureSession } from '../../api/deviceCapture';
import { can } from '../../auth/roles';
import { CaptureFromDevice } from './CaptureFromDevice';

export interface CaptureFormChoice {
  formCode: string;
  label: string;
}

/**
 * The "Capture from device" control as it appears inside an existing OH form (E03 results, E07 surveillance completion, E10
 * collection, E12 screening results): a toggle that opens the capture panel, with a measurement chooser when the host form
 * covers several device categories. Rendered only for roles that may capture. Must be placed outside the host's own <form>.
 */
export function EmbeddedCapture({ client, session, onSessionExpired, employeeId, employeeName, forms, caseId, orderRef, onAccepted, startOpen = false, buttonLabel = 'Capture from device' }: {
  client: AuthClient;
  session: Session;
  onSessionExpired: () => void;
  employeeId: string;
  employeeName?: string;
  forms: CaptureFormChoice[];
  caseId?: string;
  orderRef?: string;
  onAccepted?: (r: AcceptResult, s: CaptureSession) => void;
  startOpen?: boolean;
  buttonLabel?: string;
}) {
  const [open, setOpen] = useState(startOpen);
  const [formCode, setFormCode] = useState(forms[0]?.formCode ?? '');
  const id = useId();
  if (!can(session.roles, 'deviceCapture') || forms.length === 0) return null;
  const chosen = forms.find((f) => f.formCode === formCode) ?? forms[0]!;
  return (
    <div className="embedded-capture">
      <button type="button" className="secondary capture-toggle" aria-expanded={open} aria-controls={`${id}-panel`} onClick={() => setOpen(!open)}>
        <span aria-hidden="true">⇄ </span>
        {open ? 'Close device capture' : buttonLabel}
      </button>
      {open && (
        <div id={`${id}-panel`}>
          {forms.length > 1 && (
            <div className="field capture-form-choice">
              <label htmlFor={`${id}-form`}>Measurement</label>
              <select id={`${id}-form`} value={chosen.formCode} onChange={(e) => setFormCode(e.target.value)}>
                {forms.map((f) => (
                  <option key={f.formCode} value={f.formCode}>{f.label}</option>
                ))}
              </select>
            </div>
          )}
          <CaptureFromDevice
            client={client}
            session={session}
            onSessionExpired={onSessionExpired}
            employeeId={employeeId}
            employeeName={employeeName}
            formCode={chosen.formCode}
            caseId={caseId}
            orderRef={orderRef}
            title={`Capture from device – ${chosen.label}`}
            onAccepted={onAccepted}
          />
        </div>
      )}
    </div>
  );
}
