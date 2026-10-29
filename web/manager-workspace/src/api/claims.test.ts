import { describe, expect, it } from 'vitest';
import { CLAIM_ROW_FIELDS, CLAIM_STATUSES, CLAIM_TYPES, claimStatusLabel, claimTypeLabel, emptyClaimFilter, filterClaims, isOpenClaim, summariseClaims, toClaimRows } from './claims';

const RAW = [
  {
    claimRef: 'CLM-2026-000001',
    employeeId: 'E1001',
    displayName: 'Alice Agent',
    claimType: 'WORKERS_COMP',
    status: 'OPEN',
    lostTime: true,
    incidentDate: '2026-09-14',
    // never shown to HR, even if a misconfigured backend sends it:
    id: 'claim-uuid',
    caseId: 'case-uuid',
    caseRef: 'AIR-2026-000004',
    unit: 'OPS',
    payerCode: 'GULFTPA',
    payerName: 'Gulf TPA',
    policyNumber: 'POL-77',
    adjuster: { name: 'Adam Adjuster', email: 'adam@tpa.example' },
    reserves: { MEDICAL: { amount: '1200.00', currency: 'GBP' } },
    paidTotal: { amount: '900.00', currency: 'GBP' },
    nurseCaseManager: 'nurse1',
    litigationHold: true,
    privileged: true,
    diagnosis: 'Lumbar disc prolapse',
    notes: 'MRI shows L4/5',
  },
  { claimRef: 'CLM-2026-000002', employeeId: 'E1002', displayName: 'Bob Baggage', claimType: 'EMPLOYER_LIABILITY', status: 'CLOSED', lostTime: false, incidentDate: '2026-10-01' },
  { claimRef: 'CLM-2026-000003', employeeId: 'E1003', claimType: 'GROUP_MEDICAL', status: 'NOTIFIED', incidentDate: '2026-10-20' },
  { claimRef: 'CLM-2026-000004', employeeId: 'E1004', displayName: 'Dee', claimType: 'THIRD_PARTY', status: 'LITIGATED', lostTime: 'yes', incidentDate: '2026-05-02' },
  { employeeId: 'E1005', status: 'OPEN' }, // no reference
  { claimRef: 'CLM-2026-000006' }, // no employee
];

describe('HR claim status (E08 §4.1 GET /workspace/claims)', () => {
  const rows = toClaimRows(RAW);

  it('keeps only the allowed fields and drops everything else', () => {
    expect(rows[1]).toEqual({ claimRef: 'CLM-2026-000001', employeeId: 'E1001', displayName: 'Alice Agent', claimType: 'WORKERS_COMP', status: 'OPEN', lostTime: true, incidentDate: '2026-09-14' });
    for (const r of rows) expect(Object.keys(r).every((k) => (CLAIM_ROW_FIELDS as readonly string[]).includes(k))).toBe(true);
    const json = JSON.stringify(rows);
    for (const secret of ['claim-uuid', 'case-uuid', 'AIR-2026', 'GULFTPA', 'Gulf TPA', 'POL-77', 'Adam', '1200.00', '900.00', 'nurse1', 'Lumbar', 'MRI', 'litigationHold', 'privileged', 'reserves', 'OPS']) {
      expect(json).not.toContain(secret);
    }
  });

  it('drops rows without a claim reference or employee; falls back to the employee id for the name', () => {
    expect(rows.map((r) => r.claimRef)).toEqual(['CLM-2026-000003', 'CLM-2026-000001', 'CLM-2026-000004', 'CLM-2026-000002']);
    expect(rows[0]!.displayName).toBe('E1003');
    expect(rows.find((r) => r.claimRef === 'CLM-2026-000004')!.lostTime).toBe(false); // strict boolean only
    expect(toClaimRows({ content: [RAW[1]] })).toHaveLength(1);
    expect(toClaimRows(null)).toEqual([]);
  });

  it('filters by text, status, type, lost time and open', () => {
    expect(filterClaims(rows, emptyClaimFilter())).toHaveLength(4);
    expect(filterClaims(rows, { ...emptyClaimFilter(), q: 'baggage' }).map((r) => r.claimRef)).toEqual(['CLM-2026-000002']);
    expect(filterClaims(rows, { ...emptyClaimFilter(), q: '000004' })).toHaveLength(1);
    expect(filterClaims(rows, { ...emptyClaimFilter(), status: 'OPEN' })).toHaveLength(1);
    expect(filterClaims(rows, { ...emptyClaimFilter(), claimType: 'GROUP_MEDICAL' })).toHaveLength(1);
    expect(filterClaims(rows, { ...emptyClaimFilter(), lostTimeOnly: true }).map((r) => r.employeeId)).toEqual(['E1001']);
    expect(filterClaims(rows, { ...emptyClaimFilter(), openOnly: true })).toHaveLength(3);
  });

  it('summarises and labels', () => {
    expect(summariseClaims(rows)).toEqual({ total: 4, open: 3, lostTime: 1, closed: 1 });
    expect(isOpenClaim({ status: 'SETTLED' })).toBe(false);
    CLAIM_STATUSES.forEach((st) => expect(claimStatusLabel(st)).not.toBe(st));
    CLAIM_TYPES.forEach((ty) => expect(claimTypeLabel(ty)).not.toBe(ty));
    expect(claimStatusLabel('REOPENED')).toBe('Reopened');
  });
});
