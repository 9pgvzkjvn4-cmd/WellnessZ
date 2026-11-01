/**
 * Pure rules of the "Capture from device" panel (contract E31-E34 §14.1–14.3). The server is authoritative – these helpers
 * only decide what the panel enables and validate fall-back entries before they are sent.
 */
import type { CaptureForm, CapturePath, CaptureSession, CaptureState, FallbackOption, FlagView, FormField } from '../api/deviceCapture';
import { FINAL_STATES } from '../api/deviceCapture';

export const isFinal = (state: CaptureState | undefined): boolean => !!state && FINAL_STATES.includes(state);
/** States in which the panel waits for the device (live progress, SSE / polling). */
export const isWaiting = (state: CaptureState | undefined): boolean => state === 'LISTENING' || state === 'FALLBACK_OFFERED' || state === 'FALLBACK_ENTRY';

export const blockFlags = (flags: readonly FlagView[]): FlagView[] => flags.filter((f) => f.outcome === 'BLOCK');
export const warnFlags = (flags: readonly FlagView[]): FlagView[] => flags.filter((f) => f.outcome === 'WARN');
/** The acknowledgement key the accept call expects: the flag code (unique per observation round). */
export const flagKey = (f: FlagView): string => f.code;

/** Flags of the current preview (falls back to the session flags). */
export function previewFlags(s: CaptureSession): FlagView[] {
  return s.preview?.flags ?? s.flags ?? [];
}

/**
 * Accept is possible only in PREVIEW, when the server enables it (no BLOCK flag, undecided round), the reading is visible to
 * the user and every WARN flag has been acknowledged.
 */
export function acceptState(s: CaptureSession, acknowledged: ReadonlySet<string>): { enabled: boolean; reason?: string } {
  if (s.restrictedReading) return { enabled: false, reason: 'Restricted reading – only the collector of the session or the MRO can verify it.' };
  if (s.state !== 'PREVIEW' || !s.preview) return { enabled: false, reason: 'Waiting for a reading.' };
  const flags = previewFlags(s);
  if (blockFlags(flags).length > 0 || !s.preview.acceptEnabled || !s.actions.includes('ACCEPT')) {
    return { enabled: false, reason: blockFlags(flags).length > 0 ? 'A quality check blocks posting – re-test or use another capture path.' : 'This reading cannot be accepted.' };
  }
  const missing = warnFlags(flags).filter((f) => !acknowledged.has(flagKey(f)));
  if (missing.length > 0) return { enabled: false, reason: `Acknowledge ${missing.length === 1 ? 'the warning' : `${missing.length} warnings`} before accepting.` };
  return { enabled: true };
}

export const PATH_LABEL: Record<CapturePath, string> = {
  DEVICE: 'Device',
  ALTERNATE_DEVICE: 'Another certified device',
  OCR_VERIFIED: 'Photo of the display (OCR) – verified',
  MANUAL_ATTESTED: 'Manual entry – device and calibration attested',
};

/** "Which path was used" text for the session (contract §14.1 final record). */
export function pathUsed(s: Pick<CaptureSession, 'path' | 'deviceName' | 'deviceInstanceId'>): string {
  const p = s.path ?? 'DEVICE';
  if (p === 'DEVICE' || p === 'ALTERNATE_DEVICE') return `${PATH_LABEL[p]}${s.deviceName ? ` – ${s.deviceName}` : s.deviceInstanceId ? ` – ${s.deviceInstanceId}` : ''}`;
  return PATH_LABEL[p];
}

export interface FallbackSteps {
  alternates: FallbackOption[];
  ocr?: FallbackOption;
  manual?: FallbackOption;
}

/** The chain in the order the contract mandates: another device, then OCR, then manual entry (only what the server offers). */
export function fallbackSteps(options: readonly FallbackOption[]): FallbackSteps {
  const sorted = [...options].sort((a, b) => a.rank - b.rank);
  return {
    alternates: sorted.filter((o) => o.path === 'ALTERNATE_DEVICE'),
    ocr: sorted.find((o) => o.path === 'OCR_VERIFIED'),
    manual: sorted.find((o) => o.path === 'MANUAL_ATTESTED'),
  };
}

/**
 * A later step of the chain may be chosen only once the earlier ones are exhausted or declined: OCR when no other device is
 * available (or the user declined it), manual entry when neither another device nor OCR is available (or both declined).
 */
export function stepAllowed(steps: FallbackSteps, step: 'ALTERNATE_DEVICE' | 'OCR_VERIFIED' | 'MANUAL_ATTESTED', declined: ReadonlySet<string>): boolean {
  const altDone = steps.alternates.length === 0 || declined.has('ALTERNATE_DEVICE');
  const ocrDone = !steps.ocr || declined.has('OCR_VERIFIED');
  if (step === 'ALTERNATE_DEVICE') return steps.alternates.length > 0;
  if (step === 'OCR_VERIFIED') return !!steps.ocr && altDone;
  return !!steps.manual && altDone && ocrDone;
}

/** Field key as the fall-back API expects it (form paths without the `measurements.` / `measures.` prefix). */
export const valueKey = (path: string): string => path.replace(/^(measurements|measures)\./, '');

/** Fields that can be typed in a fall-back entry (flat list, de-duplicated across groups / templates). */
export function entryFields(form: CaptureForm | undefined): FormField[] {
  if (!form) return [];
  const seen = new Set<string>();
  const out: FormField[] = [];
  for (const g of form.fieldGroups) {
    if (g.group.startsWith('template-')) continue;
    for (const f of g.fields) {
      const k = valueKey(f.path);
      if (seen.has(k)) continue;
      seen.add(k);
      out.push(f);
    }
  }
  return out;
}

export type EntryValues = Record<string, string | boolean>;
export interface EntryError {
  id: string;
  message: string;
}

/** Converts typed entry values into the API body (QUANTITY → number, BOOLEAN → flag, others trimmed text); empty values omitted. */
export function toApiValues(fields: readonly FormField[], values: EntryValues): Record<string, number | string | boolean> {
  const out: Record<string, number | string | boolean> = {};
  for (const f of fields) {
    const k = valueKey(f.path);
    const v = values[k];
    if (v === undefined || v === '') continue;
    if (f.type === 'BOOLEAN') {
      if (v === true) out[k] = true;
      continue;
    }
    if (typeof v !== 'string') continue;
    if (f.type === 'QUANTITY') {
      const n = Number(v.replace(',', '.'));
      if (Number.isFinite(n)) out[k] = n;
    } else if (v.trim()) out[k] = v.trim().slice(0, 100);
  }
  return out;
}

export function validateEntryValues(fields: readonly FormField[], values: EntryValues): EntryError[] {
  const errs: EntryError[] = [];
  for (const f of fields) {
    const k = valueKey(f.path);
    const v = values[k];
    if (f.type === 'QUANTITY' && typeof v === 'string' && v.trim() !== '' && !Number.isFinite(Number(v.replace(',', '.')))) {
      errs.push({ id: `entry-${k}`, message: `${f.label} must be a number` });
    }
  }
  if (Object.keys(toApiValues(fields, values)).length === 0 && errs.length === 0) errs.push({ id: 'entry-values', message: 'Enter at least one value' });
  return errs;
}

export interface OcrEntry {
  imageArchiveId?: string;
  values: EntryValues;
  verified: boolean;
}

/** OCR fall-back: the photo is mandatory evidence and every value must be checked against it (verification is mandatory). */
export function validateOcr(fields: readonly FormField[], e: OcrEntry): EntryError[] {
  const errs: EntryError[] = [];
  if (!e.imageArchiveId) errs.push({ id: 'ocr-photo', message: 'Upload a photo of the device display' });
  errs.push(...validateEntryValues(fields, e.values));
  if (!e.verified) errs.push({ id: 'ocr-verified', message: 'Confirm you have checked every value against the photo' });
  return errs;
}

export interface ManualEntry {
  values: EntryValues;
  model: string;
  serial: string;
  calibrationId: string;
  calibrationDate: string;
  attested: boolean;
}

/** Manual fall-back: model, serial, calibration id and date (not in the future) and the attestation statement are mandatory. */
export function validateManual(fields: readonly FormField[], e: ManualEntry, today: string): EntryError[] {
  const errs: EntryError[] = [...validateEntryValues(fields, e.values)];
  if (!e.model.trim()) errs.push({ id: 'manual-model', message: 'Enter the device model' });
  if (!e.serial.trim()) errs.push({ id: 'manual-serial', message: 'Enter the device serial number' });
  if (!e.calibrationId.trim()) errs.push({ id: 'manual-calibration-id', message: 'Enter the calibration certificate id' });
  if (!/^\d{4}-\d{2}-\d{2}$/.test(e.calibrationDate)) errs.push({ id: 'manual-calibration-date', message: 'Enter the calibration date' });
  else if (e.calibrationDate > today) errs.push({ id: 'manual-calibration-date', message: 'The calibration date cannot be in the future' });
  if (!e.attested) errs.push({ id: 'manual-attested', message: 'Attest the device and its calibration' });
  return errs;
}

/** Prefill OCR values: only fields at or above the minimum confidence (0.90); the rest stays empty for the clinician. */
export function ocrPrefill(fields: readonly { key: string; value?: number | null; confidence?: number | null }[], min = 0.9): EntryValues {
  const out: EntryValues = {};
  for (const f of fields) if (f.value !== null && f.value !== undefined && (f.confidence ?? 0) >= min) out[f.key] = String(f.value);
  return out;
}

/** Display a component value with its unit (UCUM brackets removed for reading). */
export function displayValue(v: { value?: number | null; text?: string | null; unit?: string | null }): string {
  const unit = v.unit && v.unit !== '1' ? ` ${v.unit.replace('g/(210.L)', 'g/210 L').replace(/\[|\]/g, '').replace('Cel', '°C').replace('/min', '/min')}` : '';
  if (v.value !== null && v.value !== undefined) return `${v.value}${unit}`;
  return v.text ?? '–';
}

/** Human session state for the live region. */
export const STATE_TEXT: Record<CaptureState, string> = {
  LISTENING: 'Waiting for the device…',
  PREVIEW: 'Reading received – check and accept',
  FALLBACK_OFFERED: 'Device unavailable – choose another capture path',
  FALLBACK_ENTRY: 'Fall-back entry in progress',
  ACCEPTED: 'Accepted and filed',
  REJECTED: 'Rejected – values not filed',
  EXPIRED: 'Session expired – open a new one',
  CANCELLED: 'Capture cancelled – nothing was filed',
};

/** Bound forms of the E03 device-result capture (contract §11 / §14.2) in the order shown to clinicians. */
export const E03_CAPTURE_FORMS: { formCode: string; label: string }[] = [
  { formCode: 'E03.DEVICE_RESULT.VITALS', label: 'Vital signs (blood pressure, pulse, SpO2, temperature)' },
  { formCode: 'E03.DEVICE_RESULT.BODY_COMPOSITION', label: 'Height, weight and body composition' },
  { formCode: 'E03.DEVICE_RESULT.AUDIOMETRY', label: 'Audiometry' },
  { formCode: 'E03.DEVICE_RESULT.SPIROMETRY', label: 'Spirometry' },
  { formCode: 'E03.DEVICE_RESULT.VISION', label: 'Vision' },
  { formCode: 'E03.DEVICE_RESULT.ECG', label: 'ECG (12-lead)' },
  { formCode: 'E03.DEVICE_RESULT.ABPM_HOLTER', label: 'ABPM / Holter' },
  { formCode: 'E03.DEVICE_RESULT.POC', label: 'Point-of-care test' },
  { formCode: 'E03.DEVICE_RESULT.FIT_TEST', label: 'Respirator fit test' },
  { formCode: 'E03.DEVICE_RESULT.ERGONOMICS', label: 'Grip strength / hand-arm' },
];

/** Surveillance programmes: audiometry and spirometry first, ordered by what the programme name suggests. */
export function surveillanceCaptureForms(programme: string): { formCode: string; label: string }[] {
  const audio = { formCode: 'E03.DEVICE_RESULT.AUDIOMETRY', label: 'Audiometry' };
  const spiro = { formCode: 'E03.DEVICE_RESULT.SPIROMETRY', label: 'Spirometry' };
  return /SPIRO|LUNG|RESP|DUST|ASTHMA|ISOCYAN/i.test(programme) && !/NOISE|HEAR|AUDIO/i.test(programme) ? [spiro, audio] : [audio, spiro];
}

/** First numeric value of an accepted capture for one of the given keys (fills the host form read-only). */
export function acceptedValue(s: CaptureSession | undefined, keys: readonly string[]): number | undefined {
  const v = s?.preview?.values.find((x) => keys.includes(x.key) && x.value !== null && x.value !== undefined);
  return v?.value ?? undefined;
}
