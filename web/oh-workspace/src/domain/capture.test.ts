import { describe, expect, it } from 'vitest';
import type { CaptureForm, CaptureSession, FallbackOption } from '../api/deviceCapture';
import { baseSession } from '../testing/captureFixtures';
import {
  acceptState, acceptedValue, displayValue, entryFields, fallbackSteps, isFinal, isWaiting, ocrPrefill, pathUsed, stepAllowed, surveillanceCaptureForms, toApiValues,
  validateManual, validateOcr,
} from './capture';


const preview = (flags: CaptureSession['flags'], acceptEnabled = true) => ({
  observationId: 'o1', status: 'PENDING_VERIFICATION', captureMethod: 'DEVICE', values: [], flags, acceptEnabled, verifyBeforePost: true, attachments: [], sequence: [],
});

const form: CaptureForm = {
  formCode: 'E03.DEVICE_RESULT.VITALS', formVersion: '1', module: 'E03', category: 'VITALS', target: 'E03.RESULT',
  fieldGroups: [
    { group: 'blood-pressure', fields: [{ path: 'measurements.systolic', label: 'Systolic', type: 'QUANTITY', unit: 'mm[Hg]', required: true }, { path: 'measurements.diastolic', label: 'Diastolic', type: 'QUANTITY', unit: 'mm[Hg]', required: true }, { path: 'measurements.validationListed', label: 'Validated device', type: 'BOOLEAN', required: false }] },
    { group: 'vital-signs', fields: [{ path: 'measurements.pulse', label: 'Pulse', type: 'QUANTITY', unit: '/min', required: false }, { path: 'measurements.position', label: 'Position', type: 'CODE', required: false }] },
    { group: 'template-X', fields: [{ path: 'measurements.systolic', label: 'dup', type: 'QUANTITY', required: false }] },
  ],
};

describe('capture session rules', () => {
  it('knows final and waiting states', () => {
    expect(isFinal('ACCEPTED')).toBe(true);
    expect(isFinal('EXPIRED')).toBe(true);
    expect(isFinal('PREVIEW')).toBe(false);
    expect(isWaiting('LISTENING')).toBe(true);
    expect(isWaiting('PREVIEW')).toBe(false);
  });

  it('accept needs PREVIEW, the server action, no BLOCK flag and every WARN acknowledged', () => {
    expect(acceptState(baseSession(), new Set()).enabled).toBe(false);
    const warn = baseSession({ state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview: preview([{ gate: 'CLOCK_SKEW', outcome: 'WARN', code: 'clock_skew_corrected' }, { gate: 'PLAUSIBILITY', outcome: 'PASS', code: 'ok' }]) });
    expect(acceptState(warn, new Set())).toEqual({ enabled: false, reason: 'Acknowledge the warning before accepting.' });
    expect(acceptState(warn, new Set(['clock_skew_corrected'])).enabled).toBe(true);
    const block = baseSession({ state: 'PREVIEW', actions: ['REJECT', 'RETEST', 'CANCEL'], preview: preview([{ gate: 'CALIBRATION', outcome: 'BLOCK', code: 'device_out_of_calibration' }], false) });
    expect(acceptState(block, new Set(['device_out_of_calibration'])).enabled).toBe(false);
    expect(acceptState(block, new Set()).reason).toMatch(/blocks posting/);
    const restricted = baseSession({ state: 'PREVIEW', restricted: true, restrictedReading: true, actions: ['ACCEPT'] });
    expect(acceptState(restricted, new Set()).reason).toMatch(/Restricted reading/);
  });

  it('reports the path used', () => {
    expect(pathUsed(baseSession())).toBe('Device – Vital signs monitor – Room 1');
    expect(pathUsed(baseSession({ path: 'ALTERNATE_DEVICE', deviceName: 'BP cuff – Room 1' }))).toBe('Another certified device – BP cuff – Room 1');
    expect(pathUsed(baseSession({ path: 'OCR_VERIFIED' }))).toBe('Photo of the display (OCR) – verified');
    expect(pathUsed(baseSession({ path: 'MANUAL_ATTESTED' }))).toBe('Manual entry – device and calibration attested');
  });
});

describe('fall-back chain order (another device → OCR → manual)', () => {
  const opts: FallbackOption[] = [
    { rank: 3, path: 'MANUAL_ATTESTED', label: 'Enter manually' },
    { rank: 1, path: 'ALTERNATE_DEVICE', deviceInstanceId: 'DEV-000004', label: 'BP cuff – Room 1' },
    { rank: 2, path: 'OCR_VERIFIED', label: 'Photo' },
  ];
  it('groups and orders what the server offers', () => {
    const s = fallbackSteps(opts);
    expect(s.alternates.map((a) => a.deviceInstanceId)).toEqual(['DEV-000004']);
    expect(s.ocr?.rank).toBe(2);
    expect(s.manual?.rank).toBe(3);
  });
  it('opens OCR only after the alternate devices are declined, manual only after OCR too', () => {
    const s = fallbackSteps(opts);
    expect(stepAllowed(s, 'ALTERNATE_DEVICE', new Set())).toBe(true);
    expect(stepAllowed(s, 'OCR_VERIFIED', new Set())).toBe(false);
    expect(stepAllowed(s, 'MANUAL_ATTESTED', new Set())).toBe(false);
    expect(stepAllowed(s, 'OCR_VERIFIED', new Set(['ALTERNATE_DEVICE']))).toBe(true);
    expect(stepAllowed(s, 'MANUAL_ATTESTED', new Set(['ALTERNATE_DEVICE']))).toBe(false);
    expect(stepAllowed(s, 'MANUAL_ATTESTED', new Set(['ALTERNATE_DEVICE', 'OCR_VERIFIED']))).toBe(true);
  });
  it('skips steps that are not on offer (restricted: no OCR; no other device)', () => {
    const s = fallbackSteps([{ rank: 1, path: 'MANUAL_ATTESTED', label: 'Enter manually' }]);
    expect(stepAllowed(s, 'ALTERNATE_DEVICE', new Set())).toBe(false);
    expect(stepAllowed(s, 'OCR_VERIFIED', new Set())).toBe(false);
    expect(stepAllowed(s, 'MANUAL_ATTESTED', new Set())).toBe(true);
    expect(stepAllowed(fallbackSteps([]), 'MANUAL_ATTESTED', new Set())).toBe(false);
  });
});

describe('fall-back entry validation', () => {
  const fields = entryFields(form);
  it('flattens fields without template duplicates and strips the binding prefix', () => {
    expect(fields.map((f) => f.path)).toEqual(['measurements.systolic', 'measurements.diastolic', 'measurements.validationListed', 'measurements.pulse', 'measurements.position']);
    expect(toApiValues(fields, { systolic: '128', diastolic: '82,5', validationListed: true, position: ' SITTING ', pulse: '' })).toEqual({ systolic: 128, diastolic: 82.5, validationListed: true, position: 'SITTING' });
  });
  it('OCR needs the photo, at least one value and the verification confirmation', () => {
    expect(validateOcr(fields, { values: {}, verified: false }).map((e) => e.id)).toEqual(['ocr-photo', 'entry-values', 'ocr-verified']);
    expect(validateOcr(fields, { imageArchiveId: 'img1', values: { systolic: 'abc' }, verified: true }).map((e) => e.id)).toEqual(['entry-systolic']);
    expect(validateOcr(fields, { imageArchiveId: 'img1', values: { systolic: '120' }, verified: true })).toEqual([]);
  });
  it('manual entry needs model, serial, calibration id and a past calibration date plus the attestation', () => {
    const ok = { values: { systolic: '120' }, model: 'Connex Spot', serial: 'SN-1', calibrationId: 'CAL-9', calibrationDate: '2026-10-01', attested: true };
    expect(validateManual(fields, ok, '2026-11-01')).toEqual([]);
    expect(validateManual(fields, { ...ok, calibrationDate: '2026-12-01' }, '2026-11-01').map((e) => e.message)).toEqual(['The calibration date cannot be in the future']);
    expect(validateManual(fields, { values: {}, model: '', serial: '', calibrationId: '', calibrationDate: '', attested: false }, '2026-11-01').map((e) => e.id)).toEqual([
      'entry-values', 'manual-model', 'manual-serial', 'manual-calibration-id', 'manual-calibration-date', 'manual-attested',
    ]);
  });
  it('prefills OCR values only at or above 0.90 confidence', () => {
    expect(ocrPrefill([{ key: 'systolic', value: 131, confidence: 0.95 }, { key: 'diastolic', value: 88, confidence: 0.5 }, { key: 'pulse', value: null, confidence: 0.99 }])).toEqual({ systolic: '131' });
  });
});

describe('helpers', () => {
  it('displays values with readable units', () => {
    expect(displayValue({ value: 128, unit: 'mm[Hg]' })).toBe('128 mmHg');
    expect(displayValue({ value: 36.8, unit: 'Cel' })).toBe('36.8 °C');
    expect(displayValue({ value: 0.81, unit: '1' })).toBe('0.81');
    expect(displayValue({ text: '6/6' })).toBe('6/6');
    expect(displayValue({ value: 0.042, unit: 'g/(210.L)' })).toBe('0.042 g/210 L');
  });
  it('orders surveillance forms by programme', () => {
    expect(surveillanceCaptureForms('NOISE Hearing conservation')[0]!.formCode).toBe('E03.DEVICE_RESULT.AUDIOMETRY');
    expect(surveillanceCaptureForms('RESP Respirable dust – spirometry')[0]!.formCode).toBe('E03.DEVICE_RESULT.SPIROMETRY');
  });
  it('reads an accepted value for the host form', () => {
    const s = baseSession({ state: 'ACCEPTED', preview: { ...preview([]), values: [{ observationId: 'o1', key: 'breathScreenResult', value: 0.05, unit: 'g/(210.L)' }] } });
    expect(acceptedValue(s, ['brac', 'breathScreenResult'])).toBe(0.05);
    expect(acceptedValue(undefined, ['x'])).toBeUndefined();
  });
});
