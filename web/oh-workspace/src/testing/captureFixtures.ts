/** Synthetic fixtures for the E33 capture / E31 engine screens (no real person data). */
import type { CaptureSession, DeviceChoice, Preview } from '../api/deviceCapture';

export const baseSession = (over: Partial<CaptureSession> = {}): CaptureSession => ({
  sessionId: '11111111-1111-4111-8111-111111111111', state: 'LISTENING', mode: 'FORM', employeeId: 'E1001', employeeName: 'Erin Example', formCode: 'E03.DEVICE_RESULT.VITALS',
  category: 'VITALS', deviceInstanceId: 'DEV-000003', deviceName: 'Vital signs monitor – Room 1', path: 'DEVICE', operator: 'nurse1', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1',
  openedAt: '2026-11-01T09:00:00Z', expiresAt: '2026-11-01T09:10:00Z', retestCount: 0, restricted: false, restrictedReading: false, actions: ['CANCEL', 'CHOOSE_DEVICE', 'CHOOSE_ENTRY'],
  fallbackOptions: [], flags: [], annotations: [], ...over,
});

export const vitalsPreview = (over: Partial<Preview> = {}): Preview => ({
  observationId: 'obs-1', status: 'PENDING_VERIFICATION', captureMethod: 'DEVICE', effectiveAt: '2026-11-01T09:02:00Z',
  values: [
    { observationId: 'obs-1', key: 'systolic', display: 'Systolic blood pressure', value: 128, unit: 'mm[Hg]', interpretation: 'N', loinc: '8480-6' },
    { observationId: 'obs-1', key: 'diastolic', display: 'Diastolic blood pressure', value: 82, unit: 'mm[Hg]', interpretation: 'N', loinc: '8462-4' },
    { observationId: 'obs-1', key: 'pulse', display: 'Pulse rate', value: 71, unit: '/min' },
  ],
  flags: [], acceptEnabled: true, verifyBeforePost: true, attachments: [], sequence: [],
  provenance: { captureMethod: 'DEVICE', model: 'Connex Spot', manufacturer: 'Synthetic Medical', serial: 'SN-0003', driver: 'vitals.synthetic.pcd01@1.0.0', calibrationId: 'CAL-3', calibrationDate: '2026-06-01', calibrationVerdict: 'VALID', operator: 'nurse1', associationMethod: 'SESSION', associationConfidence: 1, clockSkewMillis: 0, rawPayloadSha256: 'a'.repeat(64) },
  marking: 'device-sourced · Connex Spot · 1 Nov 2026 09:02 UTC', ...over,
});

export const devices: DeviceChoice[] = [
  { deviceInstanceId: 'DEV-000003', displayName: 'Vital signs monitor – Room 1', model: 'Connex Spot', option: 'P4', status: 'AVAILABLE' },
  { deviceInstanceId: 'DEV-000004', displayName: 'BP cuff – Room 1', model: 'BLE cuff', option: 'P3', status: 'BUSY', heldBy: 'nurse2' },
  { deviceInstanceId: 'DEV-000009', displayName: 'Vital signs monitor – Room 1 (2)', model: 'Connex Spot', option: 'P4', status: 'LOCKED_OUT', lockReason: 'Daily check due', nextAction: 'Run the daily check in Calibration & QC' },
  { deviceInstanceId: 'DEV-000010', displayName: 'Thermometer – Room 1', status: 'OFFLINE' },
];
