import { useState, type FormEvent } from 'react';
import { DEVICE_CATEGORIES, recordApi, type DeviceCategory } from '../../api/record';
import type { AuthClient } from '../../auth/AuthClient';
import { ErrorSummary, IdField, errorFor, type FieldError } from '../../components/forms';
import { humanise, localToIso, toLocalInput } from '../../components/format';
import { ActionFeedback, SelectOptions } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import { AUDIO_FREQUENCIES, emptyAudioGrid, toDeviceResult, validateDeviceForm, type DeviceForm } from '../../domain/devices';
import { ResultFlag } from './ResultFlag';

const initial = (caseId?: string): DeviceForm => ({
  category: 'AUDIOMETRY',
  manual: false,
  deviceId: '',
  deviceModel: '',
  calibrationId: '',
  calibrationDate: '',
  performedAt: toLocalInput(),
  caseId: caseId ?? '',
  audio: emptyAudioGrid(),
  spiro: { fev1L: '', fvcL: '', fev1PredictedL: '', fvcPredictedL: '' },
  vision: { acuityLeft: '', acuityRight: '', binocular: '', colourPlatesCorrect: '', colourPlatesTotal: '15', corrected: false },
  ecg: { rhythm: 'SINUS', heartRate: '', interpretation: 'NORMAL', qtcMs: '' },
});

/**
 * Device result entry (E03-BL-011): audiometry grid (left / right × 500–8000 Hz, dB HL), spirometry, vision, ECG.
 * Device capture requires a calibration ID; manual entry sends `deviceId: null`. The server sets the flag (e.g. STS).
 */
export function DeviceResultForm({ client, onSessionExpired, employeeId, caseId, onSaved }: { client: AuthClient; onSessionExpired: () => void; employeeId: string; caseId?: string; onSaved: () => void }) {
  const action = useAction(onSessionExpired);
  const [f, setF] = useState<DeviceForm>(initial(caseId));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [lastFlag, setLastFlag] = useState<string | null>();
  const err = (id: string) => errorFor(errors, id);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateDeviceForm(f);
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      const r = await recordApi(client).deviceResult(employeeId, toDeviceResult(f, localToIso(f.performedAt) ?? new Date().toISOString()));
      setLastFlag(r.flag ?? null);
      return `${humanise(f.category)} result saved.`;
    });
    if (ok) {
      setF({ ...initial(caseId), category: f.category, manual: f.manual, deviceId: f.deviceId, deviceModel: f.deviceModel, calibrationId: f.calibrationId, calibrationDate: f.calibrationDate });
      onSaved();
    }
  }

  const numInput = (id: string, value: string, onChange: (v: string) => void, label: string, extra: { min?: number; max?: number; step?: string } = {}) => (
    <IdField id={id} label={label} error={err(id)}>
      {(d) => <input id={id} type="number" inputMode="decimal" value={value} aria-describedby={d} aria-invalid={!!err(id)} {...extra} onChange={(e) => onChange(e.target.value)} />}
    </IdField>
  );

  return (
    <div className="tile">
      <h3>Device result entry</h3>
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        <div className="form-grid">
          <IdField id="dev-category" label="Test">
            {() => (
              <select id="dev-category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value as DeviceCategory })}>
                <SelectOptions values={DEVICE_CATEGORIES} />
              </select>
            )}
          </IdField>
          <IdField id="performedAt" label="Performed at" required error={err('performedAt')}>
            {(d) => <input id="performedAt" type="datetime-local" value={f.performedAt} aria-describedby={d} onChange={(e) => setF({ ...f, performedAt: e.target.value })} />}
          </IdField>
        </div>
        <label className="checkbox">
          <input type="checkbox" checked={f.manual} onChange={(e) => setF({ ...f, manual: e.target.checked })} /> Manual entry (no device interface – calibration not required)
        </label>
        {!f.manual && (
          <div className="form-grid">
            <IdField id="deviceId" label="Device ID" required error={err('deviceId')}>
              {(d) => <input id="deviceId" maxLength={80} value={f.deviceId} aria-describedby={d} aria-invalid={!!err('deviceId')} onChange={(e) => setF({ ...f, deviceId: e.target.value })} />}
            </IdField>
            <IdField id="deviceModel" label="Device model">
              {() => <input id="deviceModel" maxLength={80} value={f.deviceModel} onChange={(e) => setF({ ...f, deviceModel: e.target.value })} />}
            </IdField>
            <IdField id="calibrationId" label="Calibration ID" required error={err('calibrationId')}>
              {(d) => <input id="calibrationId" maxLength={80} value={f.calibrationId} aria-describedby={d} aria-invalid={!!err('calibrationId')} onChange={(e) => setF({ ...f, calibrationId: e.target.value })} />}
            </IdField>
            <IdField id="calibrationDate" label="Calibration date">
              {() => <input id="calibrationDate" type="date" value={f.calibrationDate} onChange={(e) => setF({ ...f, calibrationDate: e.target.value })} />}
            </IdField>
          </div>
        )}

        {f.category === 'AUDIOMETRY' && (
          <div className="table-wrap">
            <table className="audiogram-grid">
              <caption>Hearing thresholds (dB HL)</caption>
              <thead>
                <tr>
                  <th scope="col">Ear</th>
                  {AUDIO_FREQUENCIES.map((hz) => (
                    <th key={hz} scope="col">{Number(hz) >= 1000 ? `${Number(hz) / 1000} kHz` : `${hz} Hz`}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(['left', 'right'] as const).map((ear) => (
                  <tr key={ear}>
                    <th scope="row">{ear === 'left' ? 'Left' : 'Right'}</th>
                    {AUDIO_FREQUENCIES.map((hz) => {
                      const id = `audio-${ear}-${hz}`;
                      return (
                        <td key={hz}>
                          <input
                            id={id}
                            className="grid-input"
                            type="number"
                            inputMode="numeric"
                            min={-10}
                            max={120}
                            step={5}
                            aria-label={`${ear === 'left' ? 'Left' : 'Right'} ear ${hz} Hz (dB HL)`}
                            aria-invalid={!!err(id)}
                            value={f.audio[ear][hz] ?? ''}
                            onChange={(e) => setF({ ...f, audio: { ...f.audio, [ear]: { ...f.audio[ear], [hz]: e.target.value } } })}
                          />
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {f.category === 'SPIROMETRY' && (
          <div className="form-grid">
            {numInput('spiro-fev1L', f.spiro.fev1L, (v) => setF({ ...f, spiro: { ...f.spiro, fev1L: v } }), 'FEV1 (L)', { step: '0.01', min: 0 })}
            {numInput('spiro-fvcL', f.spiro.fvcL, (v) => setF({ ...f, spiro: { ...f.spiro, fvcL: v } }), 'FVC (L)', { step: '0.01', min: 0 })}
            {numInput('spiro-fev1PredictedL', f.spiro.fev1PredictedL, (v) => setF({ ...f, spiro: { ...f.spiro, fev1PredictedL: v } }), 'FEV1 predicted (L)', { step: '0.01', min: 0 })}
            {numInput('spiro-fvcPredictedL', f.spiro.fvcPredictedL, (v) => setF({ ...f, spiro: { ...f.spiro, fvcPredictedL: v } }), 'FVC predicted (L)', { step: '0.01', min: 0 })}
          </div>
        )}
        {f.category === 'VISION' && (
          <>
            <div className="form-grid">
              {(['acuityLeft', 'acuityRight', 'binocular'] as const).map((k) => (
                <IdField key={k} id={`vision-${k}`} label={k === 'acuityLeft' ? 'Acuity left' : k === 'acuityRight' ? 'Acuity right' : 'Binocular'} hint="Snellen, e.g. 6/6" error={err(`vision-${k}`)}>
                  {(d) => <input id={`vision-${k}`} maxLength={7} value={f.vision[k]} aria-describedby={d} aria-invalid={!!err(`vision-${k}`)} onChange={(e) => setF({ ...f, vision: { ...f.vision, [k]: e.target.value } })} />}
                </IdField>
              ))}
              {numInput('vision-colourPlatesCorrect', f.vision.colourPlatesCorrect, (v) => setF({ ...f, vision: { ...f.vision, colourPlatesCorrect: v } }), 'Colour plates correct', { min: 0 })}
              {numInput('vision-colourPlatesTotal', f.vision.colourPlatesTotal, (v) => setF({ ...f, vision: { ...f.vision, colourPlatesTotal: v } }), 'Colour plates total', { min: 1 })}
            </div>
            <label className="checkbox">
              <input type="checkbox" checked={f.vision.corrected} onChange={(e) => setF({ ...f, vision: { ...f.vision, corrected: e.target.checked } })} /> With correction (glasses / lenses)
            </label>
          </>
        )}
        {f.category === 'ECG' && (
          <div className="form-grid">
            <IdField id="ecg-rhythm" label="Rhythm" error={err('ecg-rhythm')}>
              {(d) => <input id="ecg-rhythm" maxLength={40} value={f.ecg.rhythm} aria-describedby={d} onChange={(e) => setF({ ...f, ecg: { ...f.ecg, rhythm: e.target.value } })} />}
            </IdField>
            {numInput('ecg-heartRate', f.ecg.heartRate, (v) => setF({ ...f, ecg: { ...f.ecg, heartRate: v } }), 'Heart rate (bpm)', { min: 20, max: 250 })}
            <IdField id="ecg-interpretation" label="Interpretation">
              {() => (
                <select id="ecg-interpretation" value={f.ecg.interpretation} onChange={(e) => setF({ ...f, ecg: { ...f.ecg, interpretation: e.target.value } })}>
                  <SelectOptions values={['NORMAL', 'BORDERLINE', 'ABNORMAL']} />
                </select>
              )}
            </IdField>
            {numInput('ecg-qtcMs', f.ecg.qtcMs, (v) => setF({ ...f, ecg: { ...f.ecg, qtcMs: v } }), 'QTc (ms)', { min: 200, max: 700 })}
          </div>
        )}
        <button type="submit" disabled={action.busy}>
          Save result
        </button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {lastFlag !== undefined && (
        <p aria-live="polite">
          Result flag: <ResultFlag flag={lastFlag} />
        </p>
      )}
    </div>
  );
}
