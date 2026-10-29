// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, reply, sequence, sessionFor } from '../../testing/mockApi';
import { DeviceResultForm } from './DeviceResultForm';
import { OrderForm } from './OrderForm';
import { ResultsTab } from './ResultsTab';
import { ResultsWorklist } from './ResultsWorklist';

afterEach(cleanup);

const abnormal = {
  id: 'r1', orderId: null, employeeId: 'E1004', employeeName: 'Rami Ramp', category: 'AUDIOMETRY', code: { system: 'LOINC', code: '28615-3', display: 'Audiogram' }, value: null, unit: null, flag: 'ABNORMAL',
  status: 'FINAL', effectiveAt: '2026-10-25T08:00:00Z', receivedAt: '2026-10-25T08:00:00Z', source: 'DEVICE', calibrationId: 'CAL-1', ageHours: 98, overdue: true, detail: { sts: { left: true, right: false }, baselineId: 'b1' },
};

describe('DeviceResultForm', () => {
  it('audiometry grid has L/R × 500–8000 Hz and calibration ID is required for device capture', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'POST /employees/E1004/results/device': { ...abnormal, flag: 'ABNORMAL' } });
    render(<DeviceResultForm client={api.client} onSessionExpired={() => {}} employeeId="E1004" onSaved={() => {}} />);
    const grid = screen.getByRole('table', { name: 'Hearing thresholds (dB HL)' });
    expect(within(grid).getAllByRole('spinbutton')).toHaveLength(14);
    expect(within(grid).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Ear', '500 Hz', '1 kHz', '2 kHz', '3 kHz', '4 kHz', '6 kHz', '8 kHz']);

    await user.type(screen.getByLabelText(/^Device ID/), 'AUD-7');
    const values = ['10', '10', '15', '25', '35', '40', '30'];
    for (const ear of ['Left', 'Right']) for (const [i, hz] of ['500', '1000', '2000', '3000', '4000', '6000', '8000'].entries()) await user.type(screen.getByLabelText(`${ear} ear ${hz} Hz (dB HL)`), values[i]!);
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Calibration ID is required/);
    expect(api.calls).toHaveLength(0);

    await user.type(screen.getByLabelText(/^Calibration ID/), 'CAL-2026-10');
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    await waitFor(() => expect(api.calls).toHaveLength(1));
    const body = api.calls[0]?.body as Record<string, unknown>;
    expect(body).toMatchObject({ category: 'AUDIOMETRY', deviceId: 'AUD-7', calibrationId: 'CAL-2026-10' });
    expect(body.measurements).toEqual({
      left: { 500: 10, 1000: 10, 2000: 15, 3000: 25, 4000: 35, 6000: 40, 8000: 30 },
      right: { 500: 10, 1000: 10, 2000: 15, 3000: 25, 4000: 35, 6000: 40, 8000: 30 },
    });
    expect(await screen.findByText('Abnormal')).toBeTruthy();
  });

  it('manual spirometry entry needs no calibration and sends deviceId null', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'POST /employees/E1004/results/device': { ...abnormal, category: 'SPIROMETRY', flag: 'NORMAL' } });
    render(<DeviceResultForm client={api.client} onSessionExpired={() => {}} employeeId="E1004" caseId="c1" onSaved={() => {}} />);
    await user.selectOptions(screen.getByLabelText('Test'), 'SPIROMETRY');
    await user.click(screen.getByRole('checkbox', { name: /Manual entry/ }));
    expect(screen.queryByLabelText(/^Calibration ID/)).toBeNull();
    await user.type(screen.getByLabelText('FEV1 (L)'), '3.2');
    await user.type(screen.getByLabelText('FVC (L)'), '4.1');
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    await waitFor(() => expect(api.calls).toHaveLength(1));
    expect(api.calls[0]?.body).toMatchObject({ category: 'SPIROMETRY', deviceId: null, caseId: 'c1', measurements: { fev1L: 3.2, fvcL: 4.1 } });
    expect((api.calls[0]?.body as Record<string, unknown>).calibrationId).toBeUndefined();
  });

  it('vision and ECG validate their inputs', async () => {
    const user = userEvent.setup();
    const api = mockApi({});
    render(<DeviceResultForm client={api.client} onSessionExpired={() => {}} employeeId="E1004" onSaved={() => {}} />);
    await user.click(screen.getByRole('checkbox', { name: /Manual entry/ }));
    await user.selectOptions(screen.getByLabelText('Test'), 'VISION');
    await user.type(screen.getByLabelText('Acuity left'), '6/6');
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    const s = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(s).getAllByText('Use Snellen notation, e.g. 6/6.')).toHaveLength(2);
    await user.selectOptions(screen.getByLabelText('Test'), 'ECG');
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Heart rate must be 20–250 bpm/);
  });
});

describe('OrderForm', () => {
  it('places an order set; duplicate-test CDS needs an override reason', async () => {
    const user = userEvent.setup();
    const placed: string[] = [];
    const api = mockApi({
      'GET /order-sets': [{ code: 'NOISE_SURVEILLANCE', title: 'Noise surveillance', items: [{ category: 'AUDIOMETRY', code: { system: 'LOINC', code: '28615-3', display: 'Audiogram' } }] }],
      'POST /employees/E1004/orders': sequence(reply(409, { code: 'cds_alert', alerts: [{ code: 'DUPLICATE_TEST', severity: 'WARNING', message: 'Audiogram done 12 days ago' }] }), { id: 'o1', placerOrderNumber: 'ORD-2026-000045' }),
    });
    render(<OrderForm client={api.client} onSessionExpired={() => {}} employeeId="E1004" caseId="c1" onPlaced={() => placed.push('x')} />);
    await user.selectOptions(await screen.findByLabelText(/^Order set/), 'NOISE_SURVEILLANCE');
    expect(screen.getByText(/Audiogram \(LOINC 28615-3\)/)).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Priority'), 'URGENT');
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    const panel = await screen.findByRole('region', { name: 'Clinical decision support alerts' });
    await user.type(within(panel).getByLabelText(/Override reason for Duplicate test/), 'Repeat requested by audiologist');
    await user.click(screen.getByRole('button', { name: 'Place order with overrides' }));
    await waitFor(() => expect(placed).toHaveLength(1));
    expect(api.find('POST', '/employees/E1004/orders')[1]?.body).toEqual({
      caseId: 'c1', category: 'AUDIOMETRY', orderSetCode: 'NOISE_SURVEILLANCE', priority: 'URGENT', overrides: [{ alertCode: 'DUPLICATE_TEST', reason: 'Repeat requested by audiologist' }],
    });
    expect(await screen.findByText('Order ORD-2026-000045 placed.')).toBeTruthy();
  });
});

describe('ResultsTab', () => {
  it('lists results with flags and STS detail, filters by category, cancels an order; gates order entry by role', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/results': [abnormal],
      'GET /employees/E1004/results?category=SPIROMETRY': [],
      'GET /employees/E1004/orders': [{ id: 'o1', placerOrderNumber: 'ORD-1', employeeId: 'E1004', category: 'LAB', items: [{ system: 'LOINC', code: '718-7', display: 'Haemoglobin' }], priority: 'ROUTINE', status: 'QUEUED' }],
      'POST /orders/o1/cancel': {},
    });
    const { unmount } = render(<ResultsTab client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" canWrite refreshSummary={() => {}} />);
    expect(await screen.findByText(/Standard threshold shift: left ear/)).toBeTruthy();
    expect(screen.getByText('Abnormal')).toBeTruthy();
    expect(screen.getByText(/Overdue/)).toBeTruthy();
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    await waitFor(() => expect(api.find('POST', '/orders/o1/cancel')).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Category'), 'SPIROMETRY');
    expect(await screen.findByText('No results.')).toBeTruthy();
    unmount();
    render(<ResultsTab client={api.client} session={sessionFor('CASE_MANAGER')} onSessionExpired={() => {}} employeeId="E1004" canWrite={false} refreshSummary={() => {}} />);
    await screen.findAllByText('Abnormal');
    expect(screen.queryByRole('button', { name: 'New order' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Enter device result' })).toBeTruthy();
  });
});

describe('ResultsWorklist', () => {
  it('shows the overdue badge, acknowledges with action + note and generates a result letter', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /results/worklist': [abnormal],
      'GET /results/worklist?overdue=true': [abnormal],
      'POST /results/r1/acknowledge': { ...abnormal, acknowledgedAt: 'now' },
      'POST /results/r1/letter': { id: 'doc5', documentNumber: 'DOC-2026-000123', type: 'RESULT_LETTER', title: 'Result letter', employeeId: 'E1004', status: 'DRAFT' },
    });
    render(<ResultsWorklist client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText(/Overdue · 98 h/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Acknowledge Audiogram for Rami Ramp' }));
    const dialog = screen.getByRole('dialog', { name: 'Acknowledge Audiogram' });
    await user.selectOptions(within(dialog).getByLabelText(/Action/), 'REFER');
    expect((within(dialog).getByRole('button', { name: 'Acknowledge result' }) as HTMLButtonElement).disabled).toBe(true); // note required for an action
    await user.type(within(dialog).getByLabelText(/Note/), 'Refer to ENT');
    await user.click(within(dialog).getByRole('button', { name: 'Acknowledge result' }));
    await waitFor(() => expect(api.find('POST', '/results/r1/acknowledge')[0]?.body).toEqual({ action: 'REFER', note: 'Refer to ENT' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await user.click(screen.getByRole('button', { name: 'Generate result letter for Audiogram' }));
    expect((await screen.findByRole('link', { name: /Open result letter DOC-2026-000123/ })).getAttribute('href')).toBe('/bff/api/documents/doc5/content');

    await user.click(screen.getByRole('checkbox', { name: 'Overdue only' }));
    await waitFor(() => expect(api.calls.some((c) => c.path === '/results/worklist?overdue=true')).toBe(true));
  });
});
