// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CaptureForm } from '../../api/deviceCapture';
import { baseSession, devices, vitalsPreview } from '../../testing/captureFixtures';
import { mockApi, reply, sequence, sessionFor } from '../../testing/mockApi';
import { CaptureFromDevice } from './CaptureFromDevice';
import { EmbeddedCapture } from './EmbeddedCapture';
import { resetWorkstation, setStation } from './workstation';

const SID = baseSession().sessionId;
const S = `/device-capture/sessions/${SID}`;
const noop = () => {};
const form: CaptureForm = {
  formCode: 'E03.DEVICE_RESULT.VITALS', formVersion: '1', module: 'E03', category: 'VITALS', target: 'E03.RESULT',
  fieldGroups: [{ group: 'blood-pressure', fields: [{ path: 'measurements.systolic', label: 'Systolic', type: 'QUANTITY', unit: 'mm[Hg]', required: true }, { path: 'measurements.diastolic', label: 'Diastolic', type: 'QUANTITY', unit: 'mm[Hg]', required: true }] }],
};
const routes = (extra: Record<string, unknown> = {}) => ({
  'GET /device-capture/forms/E03.DEVICE_RESULT.VITALS': form,
  'GET /device-catalogue/stations': [{ stationCode: 'LHR-OH-ROOM-1', siteCode: 'LHR', name: 'LHR OH centre – room 1', kind: 'CLINIC_ROOM', active: true }],
  'GET /device-capture/devices': devices,
  ...extra,
});
const panel = (api: ReturnType<typeof mockApi>, roles: string[] = ['OH_CLINICIAN'], over: Partial<Parameters<typeof CaptureFromDevice>[0]> = {}) => (
  <CaptureFromDevice client={api.client} session={sessionFor(...roles)} onSessionExpired={noop} employeeId="E1001" employeeName="Erin Example" formCode="E03.DEVICE_RESULT.VITALS" live={{ eventSource: null, pollMs: 15 }} {...over} />
);

beforeEach(() => setStation({ siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1' }));
afterEach(() => {
  cleanup();
  resetWorkstation();
});

describe('Capture from device – device list', () => {
  it('lists the station devices with status only (no technical settings) and opens a session on an available device', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes({ 'POST /device-capture/sessions': baseSession(), [`GET ${S}`]: baseSession() }));
    render(panel(api));
    const list = await screen.findByRole('list', { name: 'Devices at this station' });
    expect(within(list).getByText('Available')).toBeTruthy();
    expect(within(list).getByText('In use')).toBeTruthy();
    expect(within(list).getByText(/by nurse2/)).toBeTruthy();
    expect(within(list).getByText('Locked out')).toBeTruthy();
    expect(within(list).getByText('Daily check due', { exact: false })).toBeTruthy();
    expect(within(list).getByText('Offline')).toBeTruthy();
    expect(within(list).queryByText(/P4|P3/)).toBeNull();
    expect(within(list).getAllByRole('button', { name: /Start/ })).toHaveLength(3); // not for the BUSY device
    expect(api.calls[0]?.path ?? '').toBeTruthy();
    expect(api.find('GET', '/device-capture/devices')[0]!.path).toBe('/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-OH-ROOM-1');
    await user.click(within(list).getByRole('button', { name: 'Start capture' }));
    await waitFor(() => expect(api.find('POST', '/device-capture/sessions')).toHaveLength(1));
    expect(api.find('POST', '/device-capture/sessions')[0]!.body).toEqual({ formCode: 'E03.DEVICE_RESULT.VITALS', employeeId: 'E1001', deviceInstanceId: 'DEV-000003', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1' });
    expect(await screen.findByText('Waiting for the device…')).toBeTruthy();
    expect(screen.getByText(/Updating every 2 seconds/)).toBeTruthy();
  });

  it('asks for the station when the catalogue is not readable (collector) and remembers it in memory', async () => {
    resetWorkstation();
    const user = userEvent.setup();
    const api = mockApi(routes({ 'GET /device-catalogue/stations': reply(403, { status: 403, title: 'Forbidden' }) }));
    render(panel(api, ['COLLECTOR']));
    await user.type(await screen.findByLabelText('Site code'), 'lhr');
    await user.type(screen.getByLabelText('Station code'), 'lhr-oh-room-1');
    await user.click(screen.getByRole('button', { name: 'Use this station' }));
    expect(await screen.findByText(/Station:/)).toBeTruthy();
    await waitFor(() => expect(api.find('GET', '/device-capture/devices')[0]?.path).toContain('siteCode=LHR&stationCode=LHR-OH-ROOM-1'));
  });
});

describe('Capture from device – session state transitions', () => {
  it('LISTENING → PREVIEW by polling; a WARN flag must be acknowledged before accept; ACCEPTED shows the path used', async () => {
    const user = userEvent.setup();
    const warn = vitalsPreview({ flags: [{ gate: 'BP_CUFF_VALIDATION', outcome: 'WARN', code: 'cuff_size_unknown', message: 'Cuff size was not recorded.' }, { gate: 'CALIBRATION', outcome: 'PASS', code: 'ok' }] });
    const inPreview = baseSession({ state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL', 'CHOOSE_DEVICE', 'CHOOSE_ENTRY'], observationId: 'obs-1', preview: warn, flags: warn.flags });
    const accepted = baseSession({ state: 'ACCEPTED', actions: [], routedRecordType: 'RESULT', routedRecordId: 'R-77', preview: { ...warn, acceptEnabled: false } });
    const onAccepted = vi.fn();
    const api = mockApi(routes({
      'POST /device-capture/sessions': baseSession(),
      [`GET ${S}`]: sequence(baseSession(), baseSession(), inPreview, inPreview, accepted),
      [`POST ${S}/accept`]: { sessionId: SID, state: 'ACCEPTED', routedRecordType: 'RESULT', routedRecordId: 'R-77', routed: [{ observationId: 'obs-1', recordType: 'RESULT', recordId: 'R-77' }] },
    }));
    render(panel(api, ['OH_CLINICIAN'], { onAccepted }));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    expect(await screen.findByText('Reading received – check and accept')).toBeTruthy();
    expect(screen.getByText('Systolic blood pressure')).toBeTruthy();
    expect(screen.getByText('128 mmHg')).toBeTruthy();
    expect(screen.getByText('device-sourced · Connex Spot · 1 Nov 2026 09:02 UTC')).toBeTruthy();
    expect(screen.getByText('Cuff size was not recorded.')).toBeTruthy();
    const accept = screen.getByRole('button', { name: 'Accept and file' }) as HTMLButtonElement;
    expect(accept.disabled).toBe(true);
    expect(screen.getByText('Acknowledge the warning before accepting.')).toBeTruthy();
    await user.click(screen.getByLabelText('I have reviewed this warning'));
    expect(accept.disabled).toBe(false);
    await user.click(screen.getByText(/^Provenance/));
    expect(screen.getByText('a'.repeat(64))).toBeTruthy();
    await user.click(accept);
    await waitFor(() => expect(api.find('POST', `${S}/accept`)).toHaveLength(1));
    expect(api.find('POST', `${S}/accept`)[0]!.body).toEqual({ acknowledgedFlags: ['cuff_size_unknown'] });
    expect(await screen.findByText(/Path used: Device – Vital signs monitor – Room 1/)).toBeTruthy();
    expect(screen.getByText('R-77')).toBeTruthy();
    expect(onAccepted).toHaveBeenCalledWith(expect.objectContaining({ routedRecordId: 'R-77' }), expect.objectContaining({ state: 'ACCEPTED' }));
    expect(screen.getByRole('button', { name: 'Re-measure' })).toBeTruthy();
  });

  it('re-test returns to LISTENING; reject needs a reason', async () => {
    const user = userEvent.setup();
    const inPreview = baseSession({ state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview: vitalsPreview() });
    const api = mockApi(routes({
      'POST /device-capture/sessions': inPreview,
      [`GET ${S}`]: inPreview,
      [`POST ${S}/reject`]: (b: { reasonCode: string }) => (b.reasonCode === 'RETEST' ? baseSession({ retestCount: 1 }) : baseSession({ state: 'REJECTED', actions: [], rejectReason: b.reasonCode })),
    }));
    render(panel(api));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    await user.click(await screen.findByRole('button', { name: 'Re-test' }));
    expect(await screen.findByText('Waiting for the device…')).toBeTruthy();
    expect(api.find('POST', `${S}/reject`)[0]!.body).toEqual({ reasonCode: 'RETEST' });
    expect(screen.getByText(/re-tests 1/)).toBeTruthy();
  });

  it('a BLOCK flag disables posting, explains why and offers another device first', async () => {
    const user = userEvent.setup();
    const blocked = vitalsPreview({ acceptEnabled: false, flags: [{ gate: 'CALIBRATION', outcome: 'BLOCK', code: 'device_out_of_calibration', message: 'The device is out of calibration – posting is blocked.' }] });
    const s = baseSession({
      state: 'PREVIEW', actions: ['REJECT', 'RETEST', 'CANCEL', 'CHOOSE_DEVICE', 'CHOOSE_ENTRY'], preview: blocked, flags: blocked.flags,
      fallbackOptions: [{ rank: 1, path: 'ALTERNATE_DEVICE', deviceInstanceId: 'DEV-000004', label: 'BP cuff – Room 1' }, { rank: 2, path: 'OCR_VERIFIED', label: 'Photo' }, { rank: 3, path: 'MANUAL_ATTESTED', label: 'Manual' }],
    });
    const api = mockApi(routes({ 'POST /device-capture/sessions': s, [`GET ${S}`]: s, [`POST ${S}/fallback`]: baseSession({ path: 'ALTERNATE_DEVICE', deviceInstanceId: 'DEV-000004', deviceName: 'BP cuff – Room 1' }) }));
    render(panel(api));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    expect(await screen.findByText(/Posting is blocked\./)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Accept and file' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/A quality check blocks posting/)).toBeTruthy();
    // OCR and manual entry stay closed until the other device is declined
    expect(screen.queryByRole('button', { name: 'Take or upload a photo' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Enter values manually' })).toBeNull();
    expect((screen.getByLabelText('Why is the device not used?') as HTMLSelectElement).value).toBe('DEVICE_LOCKED');
    await user.click(screen.getByRole('button', { name: 'Use BP cuff – Room 1' }));
    await waitFor(() => expect(api.find('POST', `${S}/fallback`)).toHaveLength(1));
    expect(api.find('POST', `${S}/fallback`)[0]!.body).toEqual({ path: 'ALTERNATE_DEVICE', reasonCode: 'DEVICE_LOCKED', deviceInstanceId: 'DEV-000004' });
    expect(await screen.findByText(/BP cuff – Room 1 · Updating every 2 seconds/)).toBeTruthy();
  });

  it('OCR path: photo upload is mandatory, values are verified, then the preview is marked "photo verified"', async () => {
    const user = userEvent.setup();
    const offered = baseSession({ state: 'FALLBACK_OFFERED', fallbackReason: 'DEVICE_UNAVAILABLE', actions: ['CANCEL', 'CHOOSE_DEVICE', 'CHOOSE_ENTRY'], fallbackOptions: [{ rank: 1, path: 'ALTERNATE_DEVICE', deviceInstanceId: 'DEV-000004', label: 'BP cuff – Room 1' }, { rank: 2, path: 'OCR_VERIFIED', label: 'Photo' }, { rank: 3, path: 'MANUAL_ATTESTED', label: 'Manual' }] });
    const ocrPreview = baseSession({ state: 'PREVIEW', path: 'OCR_VERIFIED', actions: ['ACCEPT', 'REJECT', 'CANCEL'], preview: vitalsPreview({ captureMethod: 'OCR_VERIFIED', marking: 'photo verified' }) });
    const api = mockApi(routes({
      'POST /device-capture/sessions': offered,
      [`GET ${S}`]: offered,
      'POST /device-capture/ocr': { imageArchiveId: '22222222-2222-4222-8222-222222222222', sha256: 'b'.repeat(64), ocrEngine: true, fields: [{ key: 'systolic', value: 131, confidence: 0.95 }, { key: 'diastolic', value: 86, confidence: 0.4 }] },
      [`POST ${S}/fallback`]: ocrPreview,
    }));
    render(panel(api));
    await user.click((await screen.findAllByRole('button', { name: /Start/ }))[0]!);
    expect(await screen.findByText('Device not available: Device unavailable.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'None of these is available' }));
    await user.click(screen.getByRole('button', { name: 'Take or upload a photo' }));
    await user.click(screen.getByRole('button', { name: 'Send for verification' }));
    expect(await screen.findByText('Upload a photo of the device display')).toBeTruthy();
    expect(screen.getByText('Confirm you have checked every value against the photo')).toBeTruthy();
    expect(api.find('POST', `${S}/fallback`)).toHaveLength(0);
    await user.upload(screen.getByLabelText('Photo of the display'), new File(['synthetic'], 'display.jpg', { type: 'image/jpeg' }));
    await waitFor(() => expect(api.find('POST', '/device-capture/ocr')).toHaveLength(1));
    expect(api.find('POST', '/device-capture/ocr')[0]!.path).toBe('/device-capture/ocr?deviceInstanceId=DEV-000003&category=VITALS');
    expect(api.find('POST', '/device-capture/ocr')[0]!.body).toBeInstanceOf(FormData);
    await waitFor(() => expect((screen.getByLabelText(/^Systolic/) as HTMLInputElement).value).toBe('131'));
    expect((screen.getByLabelText(/^Diastolic/) as HTMLInputElement).value).toBe(''); // low confidence stays empty
    await user.type(screen.getByLabelText(/^Diastolic/), '86');
    await user.click(screen.getByLabelText('I have checked every value against the photo'));
    await user.click(screen.getByRole('button', { name: 'Send for verification' }));
    await waitFor(() => expect(api.find('POST', `${S}/fallback`)).toHaveLength(1));
    expect(api.find('POST', `${S}/fallback`)[0]!.body).toEqual({ path: 'OCR_VERIFIED', reasonCode: 'DEVICE_UNAVAILABLE', imageArchiveId: '22222222-2222-4222-8222-222222222222', values: { systolic: 131, diastolic: 86 }, ocrConfidence: 0.95 });
    expect(await screen.findByText('photo verified')).toBeTruthy();
    expect(screen.getByText(/Path: Photo of the display \(OCR\) – verified/)).toBeTruthy();
  });

  it('manual path needs the full attestation and records it', async () => {
    const user = userEvent.setup();
    const offered = baseSession({ state: 'FALLBACK_OFFERED', fallbackReason: 'DEVICE_LOCKED', actions: ['CANCEL', 'CHOOSE_DEVICE', 'CHOOSE_ENTRY'], fallbackOptions: [{ rank: 1, path: 'MANUAL_ATTESTED', label: 'Manual' }] });
    const api = mockApi(routes({ 'POST /device-capture/sessions': offered, [`GET ${S}`]: offered, [`POST ${S}/fallback`]: baseSession({ state: 'PREVIEW', path: 'MANUAL_ATTESTED', actions: ['ACCEPT', 'REJECT', 'CANCEL'], preview: vitalsPreview({ captureMethod: 'MANUAL_ATTESTED', marking: 'entered manually – attested' }) }) }));
    render(panel(api));
    await user.click((await screen.findAllByRole('button', { name: /Start/ }))[0]!);
    expect(await screen.findByText(/No other device of this type is available/)).toBeTruthy();
    expect(screen.getByText(/Not available for this device type/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Enter values manually' }));
    await user.type(screen.getByLabelText(/^Systolic/), '124');
    await user.click(screen.getByRole('button', { name: 'Send for verification' }));
    for (const m of ['Enter the device model', 'Enter the device serial number', 'Enter the calibration certificate id', 'Enter the calibration date', 'Attest the device and its calibration']) expect(screen.getByText(m)).toBeTruthy();
    expect(api.find('POST', `${S}/fallback`)).toHaveLength(0);
    await user.type(screen.getByLabelText('Device model'), 'Connex Spot');
    await user.type(screen.getByLabelText('Serial number'), 'SN-0009');
    await user.type(screen.getByLabelText('Calibration certificate id'), 'CAL-2026-04');
    await user.type(screen.getByLabelText('Calibration date'), '2026-04-01');
    await user.click(screen.getByLabelText(/I attest that these values/));
    await user.click(screen.getByRole('button', { name: 'Send for verification' }));
    await waitFor(() => expect(api.find('POST', `${S}/fallback`)).toHaveLength(1));
    expect(api.find('POST', `${S}/fallback`)[0]!.body).toEqual({ path: 'MANUAL_ATTESTED', reasonCode: 'DEVICE_LOCKED', values: { systolic: 124 }, attested: { model: 'Connex Spot', serial: 'SN-0009', calibrationId: 'CAL-2026-04', calibrationDate: '2026-04-01' } });
    expect(await screen.findByText('entered manually – attested')).toBeTruthy();
  });

  it('restricted reading: other OH users see "restricted reading" without values or actions', async () => {
    const r = baseSession({ state: 'PREVIEW', formCode: 'E10.BREATH_ALCOHOL', category: 'BREATH_ALCOHOL', restricted: true, restrictedReading: true, actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview: null });
    const api = mockApi(routes({ 'GET /device-capture/forms/E10.BREATH_ALCOHOL': { ...form, formCode: 'E10.BREATH_ALCOHOL', category: 'BREATH_ALCOHOL', module: 'E10' }, 'POST /device-capture/sessions': r, [`GET ${S}`]: r }));
    const user = userEvent.setup();
    render(panel(api, ['OH_CLINICIAN'], { formCode: 'E10.BREATH_ALCOHOL', orderRef: 'DA-TEST-1' }));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    expect(await screen.findByText('Restricted reading')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Accept and file' })).toBeNull();
    expect(screen.queryByText(/mmHg/)).toBeNull();
    expect(api.find('POST', '/device-capture/sessions')[0]!.body).toMatchObject({ orderRef: 'DA-TEST-1' });
  });

  it('uses server-sent events when they arrive (live) and re-reads the session on each event', async () => {
    class FakeSource {
      static last?: FakeSource;
      listeners: Record<string, ((e: MessageEvent) => void)[]> = {};
      onerror: (() => void) | null = null;
      closed = false;
      constructor(public url: string) { FakeSource.last = this; }
      addEventListener(k: string, f: (e: MessageEvent) => void) { (this.listeners[k] ??= []).push(f); }
      close() { this.closed = true; }
      emit(k: string, data: unknown) { for (const f of this.listeners[k] ?? []) f(new MessageEvent(k, { data: JSON.stringify(data) })); }
    }
    const user = userEvent.setup();
    const inPreview = baseSession({ state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview: vitalsPreview() });
    const api = mockApi(routes({ 'POST /device-capture/sessions': baseSession(), [`GET ${S}`]: sequence(baseSession(), inPreview) }));
    render(panel(api, ['OH_CLINICIAN'], { live: { eventSource: (u) => new FakeSource(u) as unknown as EventSource, pollMs: 60_000 } }));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    await waitFor(() => expect(FakeSource.last?.url).toBe(`/bff/api/device-capture/sessions/${SID}/events`));
    act(() => FakeSource.last!.emit('progress', { sessionId: SID, stage: 'RECEIVING' }));
    expect(await screen.findByText(/Receiving data \(Receiving\)/)).toBeTruthy();
    expect(screen.getByText(/· Live/)).toBeTruthy();
    act(() => FakeSource.last!.emit('preview', { sessionId: SID, status: 'PENDING_VERIFICATION' }));
    expect(await screen.findByText('Reading received – check and accept')).toBeTruthy();
  });

  it('cancel ends the session and offers a new capture', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes({ 'POST /device-capture/sessions': baseSession(), [`GET ${S}`]: baseSession(), [`POST ${S}/cancel`]: baseSession({ state: 'CANCELLED', actions: [] }) }));
    render(panel(api));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel capture' }));
    expect(await screen.findByText('Cancelled')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Start a new capture' }));
    expect(await screen.findByRole('list', { name: 'Devices at this station' })).toBeTruthy();
  });
});

describe('Embedded capture control', () => {
  it('is hidden for roles that may not capture and lets clinicians choose the measurement', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes({ 'GET /device-capture/forms/E03.DEVICE_RESULT.SPIROMETRY': { ...form, formCode: 'E03.DEVICE_RESULT.SPIROMETRY', category: 'SPIROMETRY' } }));
    const forms = [{ formCode: 'E03.DEVICE_RESULT.VITALS', label: 'Vital signs' }, { formCode: 'E03.DEVICE_RESULT.SPIROMETRY', label: 'Spirometry' }];
    const { unmount } = render(<EmbeddedCapture client={api.client} session={sessionFor('CASE_MANAGER')} onSessionExpired={noop} employeeId="E1001" forms={forms} />);
    expect(screen.queryByRole('button', { name: 'Capture from device' })).toBeNull();
    unmount();
    render(<EmbeddedCapture client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} employeeId="E1001" forms={forms} />);
    await user.click(screen.getByRole('button', { name: 'Capture from device' }));
    expect(await screen.findByRole('heading', { name: 'Capture from device – Vital signs' })).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Measurement'), 'E03.DEVICE_RESULT.SPIROMETRY');
    expect(await screen.findByRole('heading', { name: 'Capture from device – Spirometry' })).toBeTruthy();
    await waitFor(() => expect(api.find('GET', '/device-capture/devices?formCode=E03.DEVICE_RESULT.SPIROMETRY')).toHaveLength(1));
  });
});
