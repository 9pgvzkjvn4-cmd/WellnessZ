// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { baseSession, vitalsPreview } from '../../testing/captureFixtures';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { TestingCollection } from '../testing/Collection';
import { resetWorkstation, setStation } from './workstation';

const noop = () => {};
beforeEach(() => setStation({ siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-3' }));
afterEach(() => {
  cleanup();
  resetWorkstation();
});

describe('Capture embedded in the E10 collection form (breath alcohol, restricted)', () => {
  it('the collector captures the breath reading for the open test; the accepted value fills the e-CCF read-only', async () => {
    const user = userEvent.setup();
    const S = baseSession({ formCode: 'E10.BREATH_ALCOHOL', category: 'BREATH_ALCOHOL', restricted: true, orderRef: 'T-1', deviceInstanceId: 'DEV-000005', deviceName: 'Breath alcohol tester – Room 3', operator: 'tester' });
    let cur = S;
    const preview = vitalsPreview({ values: [{ observationId: 'o5', key: 'brac', display: 'Alcohol [Mass/volume] in Breath', value: 0.021, unit: 'g/(210.L)' }], marking: 'device-sourced · Alcotest 7510 · 1 Nov 2026 10:00 UTC' });
    const api = mockApi({
      'GET /testing/requirements': [{ id: 'T-1', ref: 'DA-2026-0001', employeeId: 'E1004', displayName: 'Robin Random', reason: 'RANDOM', panels: ['ALCOHOL_BREATH'], status: 'NOTIFIED', dueAt: '2026-11-01T12:00:00Z', siteCode: 'LHR' }],
      'GET /device-capture/forms/E10.BREATH_ALCOHOL': { formCode: 'E10.BREATH_ALCOHOL', formVersion: '1', module: 'E10', category: 'BREATH_ALCOHOL', target: 'E10.BREATH_ALCOHOL', fieldGroups: [] },
      'GET /device-catalogue/stations': [],
      'GET /device-capture/devices': [{ deviceInstanceId: 'DEV-000005', displayName: 'Breath alcohol tester – Room 3', status: 'AVAILABLE' }],
      'POST /device-capture/sessions': S,
      [`GET /device-capture/sessions/${S.sessionId}`]: () => {
        if (cur === S) cur = { ...S, state: 'PREVIEW', actions: ['ACCEPT', 'REJECT', 'RETEST', 'CANCEL'], preview };
        return cur;
      },
      [`POST /device-capture/sessions/${S.sessionId}/accept`]: () => {
        cur = { ...cur, state: 'ACCEPTED', actions: [], routedRecordType: 'DA_TEST_READING', routedRecordId: 'T-1' };
        return { sessionId: S.sessionId, state: 'ACCEPTED', routedRecordType: 'DA_TEST_READING', routedRecordId: 'T-1', routed: [] };
      },
    });
    render(<TestingCollection client={api.client} session={sessionFor('COLLECTOR')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Start collection DA-2026-0001' }));
    await user.click(screen.getByRole('button', { name: 'Capture breath reading from device' }));
    await user.click(await screen.findByRole('button', { name: 'Start capture' }));
    expect(api.find('POST', '/device-capture/sessions')[0]!.body).toMatchObject({ formCode: 'E10.BREATH_ALCOHOL', employeeId: 'E1004', orderRef: 'T-1', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-3' });
    await user.click(await screen.findByRole('button', { name: 'Accept and file' }, { timeout: 4000 }));
    const input = (await screen.findByLabelText(/Breath screen result/)) as HTMLInputElement;
    await waitFor(() => expect(input.value).toBe('0.021'));
    expect(input.readOnly).toBe(true);
    expect(screen.getByText(/Filled from the device \(device-sourced · Alcotest 7510/)).toBeTruthy();
  });
});
