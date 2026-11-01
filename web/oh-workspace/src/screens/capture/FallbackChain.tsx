import { useState, type FormEvent } from 'react';
import type { AuthClient } from '../../auth/AuthClient';
import { FALLBACK_REASONS, captureApi, type CaptureForm, type CaptureSession, type FallbackReason, type FormField } from '../../api/deviceCapture';
import { ErrorSummary } from '../../components/forms';
import { humanise, isoDay } from '../../components/format';
import { ActionFeedback, SelectOptions } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import {
  entryFields, fallbackSteps, ocrPrefill, stepAllowed, toApiValues, validateManual, validateOcr, valueKey, type EntryError, type EntryValues,
} from '../../domain/capture';

type Step = 'choose' | 'OCR_VERIFIED' | 'MANUAL_ATTESTED';

function defaultReason(s: CaptureSession): FallbackReason {
  if (s.fallbackReason && (FALLBACK_REASONS as readonly string[]).includes(s.fallbackReason)) return s.fallbackReason as FallbackReason;
  if (s.state === 'PREVIEW') return 'DEVICE_LOCKED';
  return s.state === 'FALLBACK_OFFERED' ? 'DEVICE_UNAVAILABLE' : 'CONNECTION_FAILED';
}

function EntryFields({ fields, values, onChange, errors, idPrefix }: { fields: FormField[]; values: EntryValues; onChange: (v: EntryValues) => void; errors: EntryError[]; idPrefix: string }) {
  return (
    <div className="form-grid">
      {fields.map((f) => {
        const k = valueKey(f.path);
        const id = `${idPrefix}-${k}`;
        const err = errors.find((e) => e.id === `entry-${k}`)?.message;
        if (f.type === 'BOOLEAN') {
          return (
            <label key={k} className="checkbox" htmlFor={id}>
              <input id={id} type="checkbox" checked={values[k] === true} onChange={(e) => onChange({ ...values, [k]: e.target.checked })} /> {f.label}
            </label>
          );
        }
        return (
          <div key={k} className={`field${err ? ' field-error' : ''}`}>
            <label htmlFor={id}>
              {f.label}
              {f.unit && f.unit !== '1' && <span className="muted"> ({f.unit.replace(/\[|\]/g, '')})</span>}
            </label>
            <input
              id={id}
              inputMode={f.type === 'QUANTITY' ? 'decimal' : undefined}
              maxLength={f.type === 'QUANTITY' ? 12 : 100}
              value={typeof values[k] === 'string' ? (values[k] as string) : ''}
              aria-invalid={!!err}
              onChange={(e) => onChange({ ...values, [k]: e.target.value })}
            />
            {err && <span className="field-error-text">{err}</span>}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Fall-back chain (contract §14.3): (1) another certified device at the station, (2) photo of the display with OCR and mandatory
 * verification, (3) manual entry with device and calibration attestation. Only the options the server offers are shown, in that
 * order; a later step opens once the earlier ones are unavailable or declined. The session records the path and the reason.
 */
export function FallbackChain({ client, onSessionExpired, session, form, onUpdated }: { client: AuthClient; onSessionExpired: () => void; session: CaptureSession; form?: CaptureForm; onUpdated: (s: CaptureSession) => void }) {
  const api = captureApi(client);
  const action = useAction(onSessionExpired);
  const steps = fallbackSteps(session.fallbackOptions);
  const [declined, setDeclined] = useState<Set<string>>(new Set());
  const [step, setStep] = useState<Step>('choose');
  const [reason, setReason] = useState<FallbackReason>(defaultReason(session));
  const [errors, setErrors] = useState<EntryError[]>([]);
  const fields = entryFields(form);
  const [values, setValues] = useState<EntryValues>({});
  const [ocr, setOcr] = useState<{ imageArchiveId?: string; sha256?: string; engine?: boolean; confidence?: number; fileName?: string }>({});
  const [verified, setVerified] = useState(false);
  const [att, setAtt] = useState({ model: '', serial: '', calibrationId: '', calibrationDate: '', attested: false });
  const today = isoDay(new Date());
  const id = (s: string) => `fb-${session.sessionId.slice(0, 8)}-${s}`;

  if (session.fallbackOptions.length === 0) {
    return <p className="small muted">No other capture path is available for this form at this station.</p>;
  }

  const decline = (p: string) => setDeclined(new Set([...declined, p]));

  async function alternate(deviceInstanceId: string) {
    await action.run(async () => {
      onUpdated(await api.fallback(session.sessionId, { path: 'ALTERNATE_DEVICE', reasonCode: reason, deviceInstanceId }));
    });
  }

  async function upload(file: File | undefined) {
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
      setErrors([{ id: 'ocr-photo', message: 'The photo must be 10 MB or smaller' }]);
      return;
    }
    await action.run(async () => {
      const r = await api.ocr(file, file.name, { deviceInstanceId: session.deviceInstanceId ?? undefined, category: session.category });
      const used = r.fields.filter((f) => (f.confidence ?? 0) >= 0.9);
      setOcr({ imageArchiveId: r.imageArchiveId, sha256: r.sha256, engine: r.ocrEngine, fileName: file.name, confidence: used.length ? Math.min(...used.map((f) => f.confidence ?? 0)) : undefined });
      setValues({ ...ocrPrefill(r.fields), ...values });
      setVerified(false);
      return r.ocrEngine
        ? `Photo uploaded. ${used.length} value(s) read with high confidence – check every value against the photo.`
        : 'Photo kept as evidence. Text recognition is not available – type the values from the photo.';
    });
  }

  async function submitOcr(e: FormEvent) {
    e.preventDefault();
    const errs = validateOcr(fields, { imageArchiveId: ocr.imageArchiveId, values, verified });
    setErrors(errs);
    if (errs.length) return;
    await action.run(async () => {
      onUpdated(await api.fallback(session.sessionId, {
        path: 'OCR_VERIFIED', reasonCode: reason, imageArchiveId: ocr.imageArchiveId, values: toApiValues(fields, values) as Record<string, number | string>,
        ...(ocr.confidence !== undefined ? { ocrConfidence: Math.round(ocr.confidence * 100) / 100 } : {}),
      }));
    });
  }

  async function submitManual(e: FormEvent) {
    e.preventDefault();
    const errs = validateManual(fields, { values, ...att }, today);
    setErrors(errs);
    if (errs.length) return;
    await action.run(async () => {
      onUpdated(await api.fallback(session.sessionId, {
        path: 'MANUAL_ATTESTED', reasonCode: reason, values: toApiValues(fields, values) as Record<string, number | string>,
        attested: { model: att.model.trim(), serial: att.serial.trim(), calibrationId: att.calibrationId.trim(), calibrationDate: att.calibrationDate },
      }));
    });
  }

  const reasonField = (
    <div className="field">
      <label htmlFor={id('reason')}>Why is the device not used?</label>
      <select id={id('reason')} value={reason} onChange={(e) => setReason(e.target.value as FallbackReason)}>
        <SelectOptions values={FALLBACK_REASONS} />
      </select>
    </div>
  );

  return (
    <section className="fallback-chain" aria-labelledby={id('h')}>
      <h4 id={id('h')}>Other ways to capture this measurement</h4>
      {step === 'choose' && (
        <>
          {reasonField}
          <ol className="fallback-steps">
            <li className={stepAllowed(steps, 'ALTERNATE_DEVICE', declined) && !declined.has('ALTERNATE_DEVICE') ? 'step-open' : 'step-done'}>
              <strong>1. Another certified device</strong>
              {steps.alternates.length === 0 ? (
                <p className="small muted">No other device of this type is available at this station.</p>
              ) : declined.has('ALTERNATE_DEVICE') ? (
                <p className="small muted">Skipped.</p>
              ) : (
                <ul className="plain-list">
                  {steps.alternates.map((o) => (
                    <li key={o.deviceInstanceId ?? o.label}>
                      <button type="button" disabled={action.busy} onClick={() => void alternate(o.deviceInstanceId ?? '')}>
                        Use {o.label}
                      </button>
                    </li>
                  ))}
                  {(steps.ocr || steps.manual) && (
                    <li>
                      <button type="button" className="link-button" onClick={() => decline('ALTERNATE_DEVICE')}>
                        None of these is available
                      </button>
                    </li>
                  )}
                </ul>
              )}
            </li>
            <li className={stepAllowed(steps, 'OCR_VERIFIED', declined) && !declined.has('OCR_VERIFIED') ? 'step-open' : 'step-done'}>
              <strong>2. Photo of the device display</strong> <span className="small muted">every value is verified by you</span>
              {!steps.ocr ? (
                <p className="small muted">Not available for this device type.</p>
              ) : declined.has('OCR_VERIFIED') ? (
                <p className="small muted">Skipped.</p>
              ) : stepAllowed(steps, 'OCR_VERIFIED', declined) ? (
                <div className="actions">
                  <button type="button" onClick={() => { setErrors([]); setStep('OCR_VERIFIED'); }}>
                    Take or upload a photo
                  </button>
                  {steps.manual && (
                    <button type="button" className="link-button" onClick={() => decline('OCR_VERIFIED')}>
                      Photo not possible
                    </button>
                  )}
                </div>
              ) : (
                <p className="small muted">Available when no other device can be used.</p>
              )}
            </li>
            <li className={stepAllowed(steps, 'MANUAL_ATTESTED', declined) ? 'step-open' : 'step-done'}>
              <strong>3. Manual entry with attestation</strong>
              {!steps.manual ? (
                <p className="small muted">Not available for this form{session.restricted ? ' (restricted reading – collector only)' : ''}.</p>
              ) : stepAllowed(steps, 'MANUAL_ATTESTED', declined) ? (
                <div className="actions">
                  <button type="button" onClick={() => { setErrors([]); setStep('MANUAL_ATTESTED'); }}>
                    Enter values manually
                  </button>
                </div>
              ) : (
                <p className="small muted">Available when neither another device nor a photo can be used.</p>
              )}
            </li>
          </ol>
        </>
      )}

      {step === 'OCR_VERIFIED' && (
        <form className="form" onSubmit={submitOcr} noValidate aria-label="Photo of the device display">
          <ErrorSummary errors={errors.map((e) => ({ field: e.id, message: e.message }))} />
          {reasonField}
          <div className={`field${errors.some((e) => e.id === 'ocr-photo') ? ' field-error' : ''}`}>
            <label htmlFor="ocr-photo">Photo of the display</label>
            <input id="ocr-photo" type="file" accept="image/png,image/jpeg" capture="environment" onChange={(e) => void upload(e.target.files?.[0])} />
            {ocr.imageArchiveId && <span className="small muted">Kept as evidence: {ocr.fileName} · SHA-256 <span className="mono">{ocr.sha256?.slice(0, 12)}…</span></span>}
          </div>
          <EntryFields fields={fields} values={values} onChange={setValues} errors={errors} idPrefix="ocr" />
          <label className="checkbox" htmlFor="ocr-verified">
            <input id="ocr-verified" type="checkbox" checked={verified} onChange={(e) => setVerified(e.target.checked)} /> I have checked every value against the photo
          </label>
          <div className="actions">
            <button type="submit" disabled={action.busy}>Send for verification</button>
            <button type="button" className="secondary" onClick={() => setStep('choose')}>Back</button>
          </div>
        </form>
      )}

      {step === 'MANUAL_ATTESTED' && (
        <form className="form" onSubmit={submitManual} noValidate aria-label="Manual entry with attestation">
          <ErrorSummary errors={errors.map((e) => ({ field: e.id, message: e.message }))} />
          {reasonField}
          <EntryFields fields={fields} values={values} onChange={setValues} errors={errors} idPrefix="manual" />
          <fieldset>
            <legend>Device used for the measurement</legend>
            <div className="form-grid">
              {([
                ['manual-model', 'Device model', 'model'],
                ['manual-serial', 'Serial number', 'serial'],
                ['manual-calibration-id', 'Calibration certificate id', 'calibrationId'],
              ] as const).map(([fid, label, key]) => (
                <div key={fid} className={`field${errors.some((e) => e.id === fid) ? ' field-error' : ''}`}>
                  <label htmlFor={fid}>{label}</label>
                  <input id={fid} maxLength={key === 'model' ? 120 : 80} value={att[key]} onChange={(e) => setAtt({ ...att, [key]: e.target.value })} />
                </div>
              ))}
              <div className={`field${errors.some((e) => e.id === 'manual-calibration-date') ? ' field-error' : ''}`}>
                <label htmlFor="manual-calibration-date">Calibration date</label>
                <input id="manual-calibration-date" type="date" max={today} value={att.calibrationDate} onChange={(e) => setAtt({ ...att, calibrationDate: e.target.value })} />
              </div>
            </div>
          </fieldset>
          <label className="checkbox" htmlFor="manual-attested">
            <input id="manual-attested" type="checkbox" checked={att.attested} onChange={(e) => setAtt({ ...att, attested: e.target.checked })} /> I attest that these values were read from the device above and that it was within its calibration period.
          </label>
          <div className="actions">
            <button type="submit" disabled={action.busy}>Send for verification</button>
            <button type="button" className="secondary" onClick={() => setStep('choose')}>Back</button>
          </div>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      <p className="small muted">Reason recorded: {humanise(reason)}</p>
    </section>
  );
}
