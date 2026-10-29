import { describe, expect, it } from 'vitest';
import { toCaseStatusDetail, toTeamCaseRows } from './statusOnly';

describe('status-only projection', () => {
  it('drops any non-whitelisted (clinical) fields', () => {
    const rows = toTeamCaseRows([
      {
        id: 'c1',
        caseRef: 'OH-2026-0001',
        employeeDisplayName: 'A. Pilot',
        status: 'FITNESS_REVIEW',
        fitnessStatus: 'FIT_WITH_RESTRICTIONS',
        restrictionUntil: '2026-12-01',
        updatedAt: '2026-10-01T10:00:00Z',
        disclosure: 'STATUS_ONLY',
        diagnosis: 'should never render',
        clinicalNotes: 'secret',
        description: 'secret',
      },
    ]);
    expect(rows).toHaveLength(1);
    expect(Object.keys(rows[0]!).sort()).toEqual(
      ['caseRef', 'disclosure', 'employeeDisplayName', 'fitnessStatus', 'id', 'restrictionUntil', 'status', 'updatedAt'].sort(),
    );
    expect(JSON.stringify(rows)).not.toContain('secret');
  });

  it('accepts Spring pages and skips malformed rows', () => {
    expect(toTeamCaseRows({ content: [{ id: 'x', status: 'CLOSED' }, { foo: 1 }] })).toHaveLength(1);
    expect(toTeamCaseRows(null)).toEqual([]);
  });

  it('projects case status detail without clinical fields', () => {
    const d = toCaseStatusDetail({ caseRef: 'R1', status: 'OPEN', history: [{ status: 'NEW', at: 't', note: 'secret' }], notes: 'secret' });
    expect(JSON.stringify(d)).not.toContain('secret');
    expect(d?.history).toEqual([{ status: 'NEW', at: 't' }]);
  });
});
