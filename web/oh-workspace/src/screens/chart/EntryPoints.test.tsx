// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { Queues } from '../Queues';
import { EmployeeChartSearch } from './EmployeeChartSearch';

afterEach(cleanup);

describe('chart entry points', () => {
  it('queue rows link the employee to the OH chart', async () => {
    const api = mockApi({
      'GET /queues/counts': { MY: 1 },
      'GET /queues/views': [],
      'GET /queues/MY': { items: [{ id: 'c1', caseRef: 'GND-2026-000001', employeeId: 'E1004', employeeName: 'Rami Ramp', status: 'TRIAGE' }], total: 1, page: 0, size: 50 },
    });
    render(<Queues client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} />);
    expect((await screen.findByRole('link', { name: 'Rami Ramp' })).getAttribute('href')).toBe('#/employees/E1004/chart');
  });

  it('employee search (intake lookup) lists charts to open', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /intake/employees/lookup': [{ employeeId: 'E1004', displayName: 'Rami Ramp', departmentCode: 'GROUND', jobCode: 'RAMP_AGENT' }] });
    render(<EmployeeChartSearch client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} />);
    await user.type(screen.getByLabelText(/Find employee/), 'Rami');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect((await screen.findByRole('link', { name: 'Rami Ramp (E1004)' })).getAttribute('href')).toBe('#/employees/E1004/chart');
    expect(api.calls[0]?.path).toBe('/intake/employees/lookup?q=Rami');
  });
});
