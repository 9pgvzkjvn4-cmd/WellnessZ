// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { BatchView } from '../../api/deviceCapture';
import { baseSession, devices, vitalsPreview } from '../../testing/captureFixtures';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { BatchMode, CaptureKpis, SignOffScreen } from './CaptureScreens';
import { rememberPendingSignOff, resetWorkstation, setStation } from './workstation';

const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });
const B = 'b0000000-0000-4000-8000-000000000001';
const sid = (n: number) => `5e550000-0000-4000-8000-00000000000${n}`;
const batch = (over: Partial<BatchView> = {}): BatchView => ({
  batchId: B, siteCode: 'LHR', screeningEventId: 'SE-1', formCode: 'E12.SCREENING_RESULT', deviceInstanceIds: ['DEV-000004'], status: 'OPEN', operator: 'nurse1', createdAt: '2026-11-01T08:00:00Z',
  sessions: [
    { queueEntryId: `${B}:1`, sessionId: sid(1), position: 1, employeeId: 'E1001', displayName: 'Erin Example', state: 'PREVIEW', readings: 1, current: false },
    { queueEntryId: `${B}:2`, sessionId: sid(2), position: 2, employeeId: 'E1002', displayName: 'Sam Sample', state: 'PREVIEW', readings: 1, current: false },
    { queueEntryId: `${B}:3`, sessionId: sid(3), position: 3, employeeId: 'E1003', displayName: 'Pat Placeholder', state: 'LISTENING', readings: 0, current: true },
  ],
  ...over,
});

let online = true;
beforeEach(() => {
  online = true;
  Object.defineProperty(window.navigator, 'onLine', { configurable: true, get: () => online });
  setStation({ siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1' });
});
afterEach(() => {
  cleanup();
  resetWorkstation();
});

describe('Batch mode', () => {
  it('creates a queue from the station devices and the chosen employees', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-catalogue/stations': [],
      'GET /device-capture/devices': devices,
      'GET /intake/employees/lookup': [{ employeeId: 'E1001', displayName: 'Erin Example' }],
      'POST /device-capture/batches': batch(),
    });
    render(<BatchMode {...props(api, 'OH_CLINICIAN')} />);
    await user.click(await screen.findByRole('checkbox', { name: /^Vital signs monitor – Room 1 Available/ }));
    await user.type(screen.getByLabelText('Screening event id (optional)'), 'SE-1');
    await user.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('Add employees or scan badge ids.')).toBeTruthy();
    await user.type(screen.getByLabelText('Add employee'), 'Erin');
    await user.keyboard('{Enter}');
    await user.click(await screen.findByRole('button', { name: 'Erin Example (E1001)' }));
    await user.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(api.find('POST', '/device-capture/batches')).toHaveLength(1));
    expect(api.find('POST', '/device-capture/batches')[0]!.body).toEqual({ siteCode: 'LHR', formCode: 'E12.SCREENING_RESULT', deviceInstanceIds: ['DEV-000003'], screeningEventId: 'SE-1', employeeIds: ['E1001'] });
    await waitFor(() => expect(window.location.hash).toBe(`#/devices/batch/${B}`));
  });

  it('shows the queue, accepts all clean readings, skips to the next and accepts a single reading in one tap', async () => {
    const user = userEvent.setup();
    let view = batch();
    const api = mockApi({
      [`GET /device-capture/batches/${B}`]: () => view,
      [`POST /device-capture/batches/${B}/accept-all`]: () => {
        view = batch({ sessions: view.sessions.map((e) => (e.position === 1 ? { ...e, state: 'ACCEPTED' as const } : e)) });
        return { batchId: B, accepted: [sid(1)], skipped: [{ sessionId: sid(2), reason: 'flags_need_review' }], status: 'OPEN' };
      },
      [`POST /device-capture/batches/${B}/next`]: () => (view = batch({ sessions: view.sessions.map((e) => (e.position === 3 ? { ...e, state: 'CANCELLED' as const, current: false } : e)) })),
      [`GET /device-capture/sessions/${sid(1)}`]: baseSession({ sessionId: sid(1), state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview: vitalsPreview(), mode: 'BATCH' }),
      [`GET /device-capture/sessions/${sid(2)}`]: baseSession({ sessionId: sid(2), employeeName: 'Sam Sample', state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], mode: 'BATCH', preview: vitalsPreview({ flags: [{ gate: 'PLAUSIBILITY', outcome: 'WARN', code: 'outlier_systolic', message: 'Systolic differs from the last readings.' }] }) }),
      [`POST /device-capture/sessions/${sid(2)}/accept`]: { sessionId: sid(2), state: 'ACCEPTED', routed: [] },
    });
    render(<BatchMode {...props(api, 'OH_CLINICIAN')} batchId={B} />);
    const queue = await screen.findByRole('list', { name: 'Queue' });
    expect(within(queue).getAllByRole('listitem')).toHaveLength(3);
    expect(within(queue).getByText('Now')).toBeTruthy();
    expect(screen.getByText('Online')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Accept all clean readings (2)' }));
    expect(await screen.findByText(/1 clean reading\(s\) accepted; 1 need individual review \(Flags need review\)/)).toBeTruthy();
    expect(api.find('POST', `/device-capture/batches/${B}/accept-all`)[0]!.body).toEqual({ sessionIds: [sid(1), sid(2)], acknowledgedFlags: {} });
    // flagged reading: review individually, acknowledge, accept
    await user.click(within(queue).getByRole('button', { name: /Sam Sample/ }));
    expect(await screen.findByText(/excluded from "accept all"/)).toBeTruthy();
    const accept = screen.getByRole('button', { name: 'Accept' }) as HTMLButtonElement;
    expect(accept.disabled).toBe(true);
    await user.click(screen.getByLabelText('I have reviewed this warning'));
    await user.click(accept);
    await waitFor(() => expect(api.find('POST', `/device-capture/sessions/${sid(2)}/accept`)).toHaveLength(1));
    expect(api.find('POST', `/device-capture/sessions/${sid(2)}/accept`)[0]!.body).toEqual({ acknowledgedFlags: ['outlier_systolic'] });
    await user.click(screen.getByRole('button', { name: 'Skip to next' }));
    await waitFor(() => expect(api.find('POST', `/device-capture/batches/${B}/next`)).toHaveLength(1));
  });

  it('shows the offline indicator and disables server actions while offline', async () => {
    const api = mockApi({ [`GET /device-capture/batches/${B}`]: batch(), [`GET /device-capture/sessions/${sid(1)}`]: baseSession({ sessionId: sid(1), state: 'PREVIEW', preview: vitalsPreview(), actions: ['ACCEPT'] }) });
    render(<BatchMode {...props(api, 'OH_CLINICIAN')} batchId={B} />);
    await screen.findByRole('list', { name: 'Queue' });
    act(() => {
      online = false;
      window.dispatchEvent(new Event('offline'));
    });
    expect(await screen.findByText('Offline')).toBeTruthy();
    expect(screen.getByText(/stay on the tablet and are reconciled automatically/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Skip to next' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: /Accept all clean readings/ }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('ECG / ABPM sign-off', () => {
  const ecg = (over = {}) => baseSession({
    sessionId: sid(7), formCode: 'E03.DEVICE_RESULT.ECG', category: 'ECG', state: 'ACCEPTED', interpretationStatus: 'UNVERIFIED', actions: [],
    preview: vitalsPreview({ values: [{ observationId: 'o7', key: 'heartRate', display: 'Heart rate', value: 64, unit: '/min' }, { observationId: 'o7', key: 'interpretation', display: 'Device interpretation', text: 'Sinus rhythm' }], attachments: [{ archiveId: 'a7', kind: 'PDF', mediaType: 'application/pdf', sha256: 'c'.repeat(64), sizeBytes: 20480, link: '/device-engine/observations/o7/attachments/a7' }], interpretationStatus: 'UNVERIFIED', marking: 'device-sourced · MAC 2000 · 1 Nov 2026 09:30 UTC' }),
    ...over,
  });
  it('lists captures awaiting sign-off at this workstation', async () => {
    rememberPendingSignOff({ sessionId: sid(7), category: 'ECG', employeeId: 'E1001', employeeName: 'Erin Example', acceptedAt: '2026-11-01T09:31:00Z' });
    const api = mockApi({});
    render(<SignOffScreen {...props(api, 'OH_PHYSICIAN')} />);
    expect(screen.getByRole('link', { name: 'Ecg – Erin Example' }).getAttribute('href')).toBe(`#/devices/sign-off/${sid(7)}`);
  });
  it('physician signs off an unverified interpretation; the PDF is linked', async () => {
    const user = userEvent.setup();
    const api = mockApi({ [`GET /device-capture/sessions/${sid(7)}`]: ecg(), [`POST /device-capture/sessions/${sid(7)}/sign-off`]: ecg({ interpretationStatus: 'SIGNED', signedBy: 'physician1', signedAt: '2026-11-01T10:00:00Z' }) });
    render(<SignOffScreen {...props(api, 'OH_PHYSICIAN')} sessionId={sid(7)} />);
    expect(await screen.findByText('Interpretation unverified')).toBeTruthy();
    expect(screen.getByText('Sinus rhythm')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Pdf/ }).getAttribute('href')).toBe('/bff/api/device-engine/observations/o7/attachments/a7');
    await user.type(screen.getByLabelText('Physician comment (optional)'), 'Normal ECG.');
    await user.click(screen.getByRole('button', { name: 'Sign off the interpretation' }));
    await waitFor(() => expect(api.find('POST', `/device-capture/sessions/${sid(7)}/sign-off`)[0]?.body).toEqual({ note: 'Normal ECG.' }));
    expect(await screen.findByText('Signed off')).toBeTruthy();
    expect(screen.getByText(/by physician1/)).toBeTruthy();
  });
  it('non-physicians cannot sign off', async () => {
    const api = mockApi({ [`GET /device-capture/sessions/${sid(7)}`]: ecg() });
    render(<SignOffScreen {...props(api, 'OH_CLINICIAN')} sessionId={sid(7)} />);
    expect(await screen.findByText('A physician signs off this interpretation.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign off the interpretation' })).toBeNull();
  });
});

describe('Capture KPIs', () => {
  it('shows usage, minutes saved, re-test and unmatched rates against targets with paths and outcomes', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-capture/kpis': { siteCode: null, from: '2026-10-03', to: '2026-11-01', targets: { usagePct: 90, minutesSavedPerDeviceTest: 3, retestRatePct: 1, unmatchedRatePct: 0.5 }, kpis: { sessions: 40, accepted: 36, deviceCaptures: 33, usagePct: 91.67, minutesSaved: 99, minutesSavedPerDeviceTest: 3, retests: 2, retestRatePct: 5.13, observations: 50, unmatched: 1, unmatchedRatePct: 2, byPath: { DEVICE: 30, ALTERNATE_DEVICE: 3, OCR_VERIFIED: 2, MANUAL_ATTESTED: 1 }, byState: { ACCEPTED: 36, REJECTED: 1, CANCELLED: 3 } } },
    });
    render(<CaptureKpis {...props(api, 'NURSING_HEAD')} />);
    expect(await screen.findByText('91.7 %')).toBeTruthy();
    expect(screen.getByText(/Target ≥ 90 %/)).toBeTruthy();
    expect(screen.getByText('5.1 %')).toBeTruthy();
    expect(screen.getAllByText('off target').length).toBe(2);
    expect(screen.getByText('Ocr verified:', { exact: false })).toBeTruthy();
    await user.clear(screen.getByLabelText('Site'));
    await user.type(screen.getByLabelText('Site'), 'lhr');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(api.find('GET', '/device-capture/kpis?siteCode=LHR')).toHaveLength(1));
  });
  it('reports errors', async () => {
    const api = mockApi({ 'GET /device-capture/kpis': reply(403, { status: 403, detail: 'Access denied', code: 'forbidden' }) });
    render(<CaptureKpis {...props(api, 'NURSING_HEAD')} />);
    expect(await screen.findByText('Access denied')).toBeTruthy();
  });
});
