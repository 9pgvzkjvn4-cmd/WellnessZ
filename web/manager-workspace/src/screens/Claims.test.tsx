import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { emptyClaimFilter, toClaimRows } from '../api/claims';
import type { AuthClient, Session } from '../auth/AuthClient';
import { findOne } from '../test/tree';
import { Claims, ClaimsView } from './Claims';
import { FeatureNotAvailable } from './FeatureNotAvailable';

const rows = toClaimRows([
  { claimRef: 'CLM-2026-000001', employeeId: 'E1001', displayName: 'Alice Agent', claimType: 'WORKERS_COMP', status: 'OPEN', lostTime: true, incidentDate: '2026-09-14', paidTotal: { amount: '900.00' }, notes: 'fractured wrist' },
  { claimRef: 'CLM-2026-000002', employeeId: 'E1002', displayName: 'Bob Baggage', claimType: 'EMPLOYER_LIABILITY', status: 'CLOSED', lostTime: false, incidentDate: '2026-10-01' },
]);

describe('HR claims view', () => {
  it('shows claim ref, employee, type, status, lost-time badge and incident date – nothing else', () => {
    const html = renderToStaticMarkup(<ClaimsView rows={rows} filter={emptyClaimFilter()} onFilter={vi.fn()} />);
    for (const s of ['CLM-2026-000001', 'Alice Agent', 'E1001', "Workers&#x27; compensation", 'Open', 'badge-claim-open', 'badge-lost-time', 'Employer liability', 'Closed', 'Showing 2 of 2 claims']) expect(html).toContain(s);
    expect(html.match(/badge-lost-time/g)).toHaveLength(1);
    expect(html).not.toContain('900.00');
    expect(html).not.toContain('fractured');
  });

  it('wires the filters', () => {
    const onFilter = vi.fn();
    const tree = ClaimsView({ rows, filter: emptyClaimFilter(), onFilter });
    findOne(tree, (e) => e.props.id === 'cl-status').props.onChange({ target: { value: 'OPEN' } });
    expect(onFilter).toHaveBeenLastCalledWith({ ...emptyClaimFilter(), status: 'OPEN' });
    findOne(tree, (e) => e.props.id === 'cl-type').props.onChange({ target: { value: 'THIRD_PARTY' } });
    expect(onFilter).toHaveBeenLastCalledWith({ ...emptyClaimFilter(), claimType: 'THIRD_PARTY' });
    findOne(tree, (e) => e.props.id === 'cl-lost-time').props.onChange({ target: { checked: true } });
    expect(onFilter).toHaveBeenLastCalledWith({ ...emptyClaimFilter(), lostTimeOnly: true });
    findOne(tree, (e) => e.props.id === 'cl-q').props.onChange({ target: { value: 'bob' } });
    expect(onFilter).toHaveBeenLastCalledWith({ ...emptyClaimFilter(), q: 'bob' });
    const filtered = renderToStaticMarkup(<ClaimsView rows={rows} filter={{ ...emptyClaimFilter(), openOnly: true }} onFilter={vi.fn()} />);
    expect(filtered).not.toContain('Bob Baggage');
  });

  it('empty state', () => {
    expect(renderToStaticMarkup(<ClaimsView rows={[]} filter={emptyClaimFilter()} onFilter={vi.fn()} />)).toContain('There are no claims for employees in your scope.');
  });

  it('screen shows the confidentiality hint', () => {
    const api = vi.fn(() => new Promise(() => {}));
    const client = { api } as unknown as AuthClient;
    const session: Session = { authenticated: true, name: 'Hana HR', roles: ['HR'] };
    const html = renderToStaticMarkup(<Claims client={client} session={session} onSessionExpired={vi.fn()} />);
    expect(html).toContain('No medical or cost details are shown');
    expect(html).toContain('<h1');
  });

  it('a manager who opens the route gets the not-available page', () => {
    expect(renderToStaticMarkup(<FeatureNotAvailable feature="claims" />)).toContain('only available to HR business partners');
  });
});
