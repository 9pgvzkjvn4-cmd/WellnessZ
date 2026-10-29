import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { emptyRestrictionFilter, filterTeamRestrictions, toTeamRestrictions } from '../api/fitness';
import { toTeamCaseRows } from '../api/statusOnly';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { findOne } from '../test/tree';
import { TeamCasesTable } from './TeamCases';
import { TeamRestrictionsView } from './TeamRestrictions';

const body = [
  {
    employeeId: 'E1004',
    employeeName: 'Rami Ramp',
    fitnessStatus: 'FIT_WITH_RESTRICTIONS',
    diagnosis: 'SECRET-DX',
    restrictions: [
      { label: 'No lifting above 10 kg', validUntil: '2026-11-02', status: 'EXPIRING', rationale: 'SECRET-RATIONALE', code: 'LIFT_MAX_KG' },
      { label: 'No airside driving', validUntil: '2026-10-20', status: 'LAPSED' },
      { label: 'No night shifts', validUntil: '2026-12-31', status: 'ACTIVE' },
    ],
  },
  { employeeId: 'E1001', employeeName: 'Erin Employee', fitnessStatus: 'FIT', restrictions: [], clinicalNotes: 'SECRET-NOTE' },
  { employeeName: 'no id' },
];

describe('team restrictions (E05-BL-017)', () => {
  it('keeps only the functional, status-only fields', () => {
    const rows = toTeamRestrictions(body);
    expect(rows).toHaveLength(2);
    expect(Object.keys(rows[0]!).sort()).toEqual(['employeeId', 'employeeName', 'fitnessStatus', 'restrictions']);
    expect(Object.keys(rows[0]!.restrictions[0]!).sort()).toEqual(['label', 'status', 'validUntil']);
    expect(JSON.stringify(rows)).not.toContain('SECRET');
  });

  it('renders fitness status, validity and EXPIRING / LAPSED badges – and no clinical fields', () => {
    const html = renderToStaticMarkup(<TeamRestrictionsView rows={toTeamRestrictions(body)} filter={emptyRestrictionFilter()} onFilter={vi.fn()} />);
    expect(html).toContain('Rami Ramp');
    expect(html).toContain('Fit with restrictions');
    expect(html).toContain('No lifting above 10 kg');
    expect(html).toMatch(/valid until [^<]*2026/);
    expect(html).toContain('<span class="badge badge-expiring">Expiring</span>');
    expect(html).toContain('<span class="badge badge-lapsed">Lapsed</span>');
    expect(html).not.toContain('badge-active');
    expect(html).toContain('Showing 2 of 2 employees');
    expect(html).not.toMatch(/SECRET|diagnos/i);
  });

  it('shows the visible "no clinical information" note', () => {
    const html = renderToStaticMarkup(<NoClinicalInfoNote />);
    expect(html).toContain('role="note"');
    expect(html).toContain('No clinical information is shown');
  });

  it('filters by name, fitness status and restriction status', () => {
    const rows = toTeamRestrictions(body);
    const f = emptyRestrictionFilter();
    expect(filterTeamRestrictions(rows, { ...f, q: 'erin' }).map((r) => r.employeeId)).toEqual(['E1001']);
    expect(filterTeamRestrictions(rows, { ...f, q: 'e1004' }).map((r) => r.employeeId)).toEqual(['E1004']);
    expect(filterTeamRestrictions(rows, { ...f, fitnessStatus: 'FIT' }).map((r) => r.employeeId)).toEqual(['E1001']);
    expect(filterTeamRestrictions(rows, { ...f, restrictionStatus: 'EXPIRING' }).map((r) => r.employeeId)).toEqual(['E1004']);
    expect(filterTeamRestrictions(rows, { ...f, restrictionStatus: 'ANY' }).map((r) => r.employeeId)).toEqual(['E1004']);
    expect(filterTeamRestrictions(rows, { ...f, restrictionStatus: 'CLOSED' })).toEqual([]);
  });

  it('dispatches filter changes', () => {
    const onFilter = vi.fn();
    const tree = TeamRestrictionsView({ rows: [], filter: emptyRestrictionFilter(), onFilter });
    findOne(tree, (e) => e.props.id === 'tr-status').props.onChange({ target: { value: 'LAPSED' } });
    expect(onFilter).toHaveBeenLastCalledWith({ q: '', fitnessStatus: '', restrictionStatus: 'LAPSED' });
    findOne(tree, (e) => e.props.id === 'tr-q').props.onChange({ target: { value: 'Ra' } });
    expect(onFilter).toHaveBeenLastCalledWith({ q: 'Ra', fitnessStatus: '', restrictionStatus: '' });
  });
});

describe('team cases – HR case actions', () => {
  const rows = toTeamCaseRows([{ id: 'c1', caseRef: 'OH-2026-0001', employeeDisplayName: 'Rami Ramp', status: 'MONITORING' }]);

  it('links job offers and IHR from a case row for HR', () => {
    const html = renderToStaticMarkup(<TeamCasesTable rows={rows} hr />);
    expect(html).toContain('href="#/cases/c1/job-offers"');
    expect(html).toContain('href="#/cases/c1/ihr"');
    expect(html).toContain('HR actions');
  });

  it('shows no HR actions to managers', () => {
    const html = renderToStaticMarkup(<TeamCasesTable rows={rows} hr={false} />);
    expect(html).not.toContain('job-offers');
    expect(html).not.toContain('HR actions');
  });
});
