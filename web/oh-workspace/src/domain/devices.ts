import type { DeviceCategory, DeviceResult } from '../api/record';

/** Audiometry grid frequencies (Hz) per contract E03 §4.4. */
export const AUDIO_FREQUENCIES = ['500', '1000', '2000', '3000', '4000', '6000', '8000'] as const;
export type Ear = 'left' | 'right';
export type AudioGrid = Record<Ear, Record<string, string>>;

export const emptyAudioGrid = (): AudioGrid => ({
  left: Object.fromEntries(AUDIO_FREQUENCIES.map((f) => [f, ''])),
  right: Object.fromEntries(AUDIO_FREQUENCIES.map((f) => [f, ''])),
});

export interface DeviceForm {
  category: DeviceCategory;
  manual: boolean;
  deviceId: string;
  deviceModel: string;
  calibrationId: string;
  calibrationDate: string;
  performedAt: string;
  caseId: string;
  audio: AudioGrid;
  spiro: { fev1L: string; fvcL: string; fev1PredictedL: string; fvcPredictedL: string };
  vision: { acuityLeft: string; acuityRight: string; binocular: string; colourPlatesCorrect: string; colourPlatesTotal: string; corrected: boolean };
  ecg: { rhythm: string; heartRate: string; interpretation: string; qtcMs: string };
}

export interface FieldError {
  field: string;
  message: string;
}

const num = (v: string): number | undefined => (v.trim() === '' || Number.isNaN(Number(v)) ? undefined : Number(v));
const ACUITY = /^\d{1,2}\/\d{1,3}$/;

/** Client-side checks (the server re-validates): calibration ID is mandatory for device capture, not for manual entry. */
export function validateDeviceForm(f: DeviceForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.manual) {
    if (!f.deviceId.trim()) errs.push({ field: 'deviceId', message: 'Enter the device ID (or choose manual entry).' });
    if (!f.calibrationId.trim()) errs.push({ field: 'calibrationId', message: 'Calibration ID is required for device capture.' });
  }
  if (!f.performedAt) errs.push({ field: 'performedAt', message: 'Enter when the test was performed.' });
  if (f.category === 'AUDIOMETRY') {
    for (const ear of ['left', 'right'] as const) {
      for (const hz of AUDIO_FREQUENCIES) {
        const v = f.audio[ear][hz] ?? '';
        const n = num(v);
        if (v.trim() === '') errs.push({ field: `audio-${ear}-${hz}`, message: `Enter the ${ear} ear threshold at ${hz} Hz.` });
        else if (n === undefined || n < -10 || n > 120) errs.push({ field: `audio-${ear}-${hz}`, message: `${ear === 'left' ? 'Left' : 'Right'} ${hz} Hz must be between -10 and 120 dB HL.` });
      }
    }
  }
  if (f.category === 'SPIROMETRY') {
    for (const k of ['fev1L', 'fvcL'] as const) {
      const n = num(f.spiro[k]);
      if (n === undefined || n <= 0 || n > 10) errs.push({ field: `spiro-${k}`, message: `${k === 'fev1L' ? 'FEV1' : 'FVC'} must be a volume in litres (0–10).` });
    }
  }
  if (f.category === 'VISION') {
    for (const k of ['acuityLeft', 'acuityRight', 'binocular'] as const) {
      if (!ACUITY.test(f.vision[k].trim())) errs.push({ field: `vision-${k}`, message: 'Use Snellen notation, e.g. 6/6.' });
    }
    const c = num(f.vision.colourPlatesCorrect);
    const t = num(f.vision.colourPlatesTotal);
    if (c === undefined || t === undefined || c < 0 || t <= 0 || c > t) errs.push({ field: 'vision-colourPlatesCorrect', message: 'Colour plates correct must be between 0 and the total.' });
  }
  if (f.category === 'ECG') {
    const hr = num(f.ecg.heartRate);
    if (hr === undefined || hr < 20 || hr > 250) errs.push({ field: 'ecg-heartRate', message: 'Heart rate must be 20–250 bpm.' });
    if (!f.ecg.rhythm.trim()) errs.push({ field: 'ecg-rhythm', message: 'Enter the rhythm.' });
  }
  return errs;
}

/** Form → `DeviceResult` body. Manual entry sends `deviceId: null` and omits calibration. */
export function toDeviceResult(f: DeviceForm, performedAtIso: string): DeviceResult {
  let measurements: Record<string, unknown>;
  switch (f.category) {
    case 'AUDIOMETRY':
      measurements = {
        left: Object.fromEntries(AUDIO_FREQUENCIES.map((hz) => [hz, Number(f.audio.left[hz])])),
        right: Object.fromEntries(AUDIO_FREQUENCIES.map((hz) => [hz, Number(f.audio.right[hz])])),
      };
      break;
    case 'SPIROMETRY':
      measurements = {
        fev1L: Number(f.spiro.fev1L),
        fvcL: Number(f.spiro.fvcL),
        ...(num(f.spiro.fev1PredictedL) !== undefined ? { fev1PredictedL: Number(f.spiro.fev1PredictedL) } : {}),
        ...(num(f.spiro.fvcPredictedL) !== undefined ? { fvcPredictedL: Number(f.spiro.fvcPredictedL) } : {}),
      };
      break;
    case 'VISION':
      measurements = {
        acuityLeft: f.vision.acuityLeft.trim(),
        acuityRight: f.vision.acuityRight.trim(),
        binocular: f.vision.binocular.trim(),
        colourPlatesCorrect: Number(f.vision.colourPlatesCorrect),
        colourPlatesTotal: Number(f.vision.colourPlatesTotal),
        corrected: f.vision.corrected,
      };
      break;
    default:
      measurements = {
        rhythm: f.ecg.rhythm.trim(),
        heartRate: Number(f.ecg.heartRate),
        interpretation: f.ecg.interpretation,
        ...(num(f.ecg.qtcMs) !== undefined ? { qtcMs: Number(f.ecg.qtcMs) } : {}),
      };
  }
  return {
    category: f.category,
    deviceId: f.manual ? null : f.deviceId.trim(),
    ...(f.manual ? {} : { deviceModel: f.deviceModel.trim() || undefined, calibrationId: f.calibrationId.trim(), calibrationDate: f.calibrationDate || undefined }),
    performedAt: performedAtIso,
    ...(f.caseId.trim() ? { caseId: f.caseId.trim() } : {}),
    measurements,
  };
}
