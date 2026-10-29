import { describe, expect, it } from 'vitest';
import {
  emptyComplianceFilter,
  emptyPreEmploymentForm,
  filterCompliance,
  isStartAtRisk,
  normalisePreEmployment,
  PRE_EMPLOYMENT_FIELDS,
  summariseCompliance,
  toComplianceRows,
  toOnboardingRows,
  validatePreEmployment,
  type PreEmploymentForm,
} from './surveillance';

const TODAY = '2026-10-29';

describe('team surveillance compliance (E07 §4.2)', () => {
  const rows = toComplianceRows([
    { employeeId: 'E1001', displayName: 'Alice', programmeTitle: 'Noise (audiometry)', status: 'CURRENT', nextDueOn: '2027-05-01', escalated: false },
    { employeeId: 'E1007', displayName: 'Sam', programmeTitle: 'Night work', status: 'OVERDUE', nextDueOn: '2026-09-01', escalated: true, results: 'SECRET' },
    { employeeId: 'E1008', displayName: 'Fiona', programmeTitle: 'Forklift medical', status: 'DUE', nextDueOn: '2026-11-15', escalated: false },
    { employeeId: 'E1009', displayName: 'Cole', programmeTitle: 'Food handler', status: 'EXEMPT' },
    { employeeId: 'E1006', displayName: 'Ellie', programmeTitle: 'Working at height', status: 'OVERDUE', nextDueOn: '2026-10-01' },
  ]);

  it('keeps CURRENT / DUE / OVERDUE rows, status fields only, overdue and escalated first', () => {
    expect(rows.map((r) => r.employeeId)).toEqual(['E1007', 'E1006', 'E1008', 'E1001']);
    expect(rows[0]).toEqual({ employeeId: 'E1007', displayName: 'Sam', programmeTitle: 'Night work', status: 'OVERDUE', nextDueOn: '2026-09-01', escalated: true });
    expect(JSON.stringify(rows)).not.toMatch(/SECRET|EXEMPT/);
  });

  it('filters by text, status and escalation', () => {
    expect(filterCompliance(rows, emptyComplianceFilter())).toHaveLength(4);
    expect(filterCompliance(rows, { q: 'forklift', status: '', escalatedOnly: false }).map((r) => r.employeeId)).toEqual(['E1008']);
    expect(filterCompliance(rows, { q: '', status: 'OVERDUE', escalatedOnly: false })).toHaveLength(2);
    expect(filterCompliance(rows, { q: '', status: '', escalatedOnly: true }).map((r) => r.employeeId)).toEqual(['E1007']);
  });

  it('summarises compliance', () => {
    expect(summariseCompliance(rows)).toEqual({ total: 4, current: 1, due: 1, overdue: 2, escalated: 1, compliancePct: 25 });
    expect(summariseCompliance([]).compliancePct).toBeNull();
  });
});

describe('pre-employment (E07 §4.3)', () => {
  it('maps the onboarding clearance list (status only) sorted by proposed start', () => {
    const rows = toOnboardingRows([
      { id: 'p1', offerRef: 'OFF-2', candidateName: 'Carl Candidate', jobTitle: 'Security screener', proposedStart: '2026-11-20', hrClearance: 'FIT', updatedAt: '2026-10-28T10:00:00Z', panel: ['NOISE'], questionnaire: 'SECRET' },
      { id: 'p2', offerRef: 'OFF-1', candidateName: 'Dana', proposedStart: '2026-11-02', hrClearance: 'WEIRD' },
      { offerRef: 'no-id' },
    ]);
    expect(rows.map((r) => r.id)).toEqual(['p2', 'p1']);
    expect(rows[0]!.hrClearance).toBe('PENDING');
    expect(rows[1]).toEqual({
      id: 'p1',
      offerRef: 'OFF-2',
      candidateName: 'Carl Candidate',
      jobTitle: 'Security screener',
      proposedStart: '2026-11-20',
      hrClearance: 'FIT',
      updatedAt: '2026-10-28T10:00:00Z',
    });
    expect(JSON.stringify(rows)).not.toMatch(/SECRET|panel/);
  });

  it('flags pending clearances whose start is within a week', () => {
    expect(isStartAtRisk({ hrClearance: 'PENDING', proposedStart: '2026-11-05' }, TODAY)).toBe(true);
    expect(isStartAtRisk({ hrClearance: 'PENDING', proposedStart: '2026-11-06' }, TODAY)).toBe(false);
    expect(isStartAtRisk({ hrClearance: 'FIT', proposedStart: '2026-10-30' }, TODAY)).toBe(false);
  });

  const complete: PreEmploymentForm = {
    offerRef: 'OFF-2026-0042',
    candidateId: 'C9001',
    candidateName: ' Carl Candidate ',
    email: 'carl@example.com',
    jobCode: 'security_screener',
    subsidiaryCode: 'grp',
    departmentCode: 'SEC',
    locationCode: 'lhr',
    proposedStart: '2026-11-20',
  };

  it('requires every field of the manual trigger', () => {
    const e = validatePreEmployment(emptyPreEmploymentForm(), TODAY);
    expect(Object.keys(e).sort()).toEqual([...PRE_EMPLOYMENT_FIELDS].sort());
    expect(validatePreEmployment(complete, TODAY)).toEqual({});
  });

  it('validates e-mail, codes and the start date', () => {
    expect(validatePreEmployment({ ...complete, email: 'nope' }, TODAY)).toHaveProperty('email');
    expect(validatePreEmployment({ ...complete, jobCode: 'SECURITY SCREENER' }, TODAY)).toHaveProperty('jobCode');
    expect(validatePreEmployment({ ...complete, offerRef: 'OFF 1' }, TODAY)).toHaveProperty('offerRef');
    expect(validatePreEmployment({ ...complete, proposedStart: '2026-10-28' }, TODAY)).toHaveProperty('proposedStart');
    expect(validatePreEmployment({ ...complete, proposedStart: TODAY }, TODAY)).toEqual({});
    expect(validatePreEmployment({ ...complete, candidateName: 'x'.repeat(201) }, TODAY)).toHaveProperty('candidateName');
  });

  it('normalises the request body (trimmed, codes upper-case)', () => {
    expect(normalisePreEmployment(complete)).toEqual({
      offerRef: 'OFF-2026-0042',
      candidateId: 'C9001',
      candidateName: 'Carl Candidate',
      email: 'carl@example.com',
      jobCode: 'SECURITY_SCREENER',
      subsidiaryCode: 'GRP',
      departmentCode: 'SEC',
      locationCode: 'LHR',
      proposedStart: '2026-11-20',
    });
  });
});
