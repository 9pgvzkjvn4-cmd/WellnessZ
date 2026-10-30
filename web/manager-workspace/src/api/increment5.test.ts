import { describe, expect, it, vi } from 'vitest';
import { ApiError, type AuthClient } from '../auth/AuthClient';
import { CLINICAL_CONTENT_MESSAGE } from './referrals';
import { submitAction } from './submit';
import {
  buildForCause,
  countDutyStatus,
  emptyForCauseForm,
  filterDutyStatus,
  FOR_CAUSE_FIELDS,
  FOR_CAUSE_PATH,
  localToIso,
  requestPhase,
  toDutyStatusRows,
  toForCauseRows,
  toggleObservation,
  validateForCause,
  type ForCauseForm,
} from './testing';
import {
  actionState,
  canMarkErgonomicDone,
  canMarkPsychosocialDone,
  ergonomicActionPath,
  ragFor,
  reviewOverdue,
  sortActions,
  toErgonomicActions,
  toParticipation,
  toSharedOutcomes,
  toUnitAssessment,
  type UnitAction,
} from './unit';

const NOW = new Date(2026, 9, 30, 10, 0, 0); // 30 Oct 2026 10:00 local

const filled = (over: Partial<ForCauseForm> = {}): ForCauseForm => ({
  employeeId: 'E1004',
  observedAt: '2026-10-30T08:15',
  location: 'Stand 5',
  observations: ['SLURRED_SPEECH'],
  description: 'Unsteady while driving the tug, slurred speech when spoken to.',
  secondObserverName: 'Dana Duty',
  ...over,
});

describe('for-cause request (E10 §4.2)', () => {
  it('needs employee, time (not in the future), location, ≥ 1 observation, description and a second observer', () => {
    expect(Object.keys(validateForCause(emptyForCauseForm(), NOW)).sort()).toEqual(['description', 'employeeId', 'location', 'observations', 'observedAt', 'secondObserverName']);
    expect(validateForCause(filled(), NOW)).toEqual({});
    expect(validateForCause(filled({ observations: [] }), NOW)).toEqual({ observations: 'Tick at least one observation.' });
    expect(validateForCause(filled({ observedAt: '2026-10-30T12:00' }), NOW)).toEqual({ observedAt: 'The observation cannot be in the future.' });
    expect(validateForCause(filled({ description: 'x'.repeat(2001) }), NOW).description).toMatch(/2000/);
  });

  it('builds exactly the contract body with UTC time and the checklist in contract order', () => {
    const body = buildForCause(filled({ observations: ['OTHER', 'ODOUR_OF_ALCOHOL'], location: '  Stand 5 ', employeeName: 'Rami Ramp' }));
    expect(Object.keys(body).sort()).toEqual([...FOR_CAUSE_FIELDS].sort());
    expect(body.observations).toEqual(['ODOUR_OF_ALCOHOL', 'OTHER']);
    expect(body.location).toBe('Stand 5');
    expect(body.observedAt).toBe(new Date('2026-10-30T08:15').toISOString());
    expect(localToIso('nonsense')).toBeUndefined();
    expect(toggleObservation(['DROWSINESS'], 'DROWSINESS')).toEqual([]);
  });

  it('posts to the workspace endpoint; a clinical-content rejection highlights the description', async () => {
    const api = vi.fn().mockRejectedValueOnce(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'description', title: 'x', detail: 'echo' }));
    const client = { api } as unknown as AuthClient;
    const res = await submitAction(client, FOR_CAUSE_PATH, buildForCause(filled()), FOR_CAUSE_FIELDS, { failed: 'The request was not sent' });
    expect(api).toHaveBeenCalledWith('/workspace/testing/for-cause', { method: 'POST', body: expect.objectContaining({ employeeId: 'E1004' }) });
    expect(res).toMatchObject({ ok: false, fieldErrors: { description: CLINICAL_CONTENT_MESSAGE } });
  });

  it('own requests are status only – every outcome collapses to a neutral phase; observations are dropped', () => {
    for (const st of ['VERIFIED_POSITIVE', 'VERIFIED_NEGATIVE', 'REFUSAL', 'MRO_REVIEW', 'IN_LAB', 'COLLECTED', 'WINDOW_MISSED']) expect(requestPhase(st)).toBe('WITH_PROGRAMME');
    expect(requestPhase('NOTIFIED')).toBe('REQUESTED');
    expect(requestPhase('DISPATCHED')).toBe('COLLECTOR_DISPATCHED');
    expect(requestPhase('CANCELLED')).toBe('CANCELLED');
    const rows = toForCauseRows([
      { id: 't1', ref: 'DAT-2026-000001', displayName: 'Rami Ramp', status: 'VERIFIED_POSITIVE', createdAt: '2026-10-29T08:00:00Z', observations: ['ADMISSION'], description: 'secret', verdict: 'POSITIVE', results: [{ analyte: 'THC' }] },
      { id: 't2', ref: 'DAT-2026-000002', displayName: 'Bob Baggage', status: 'DISPATCHED', createdAt: '2026-10-30T08:00:00Z' },
    ]);
    expect(rows.map((r) => r.ref)).toEqual(['DAT-2026-000002', 'DAT-2026-000001']);
    expect(JSON.stringify(rows)).not.toMatch(/POSITIVE|THC|secret|ADMISSION/);
  });
});

describe('duty status for HR / crew scheduling (E10 §4.4)', () => {
  const rows = toDutyStatusRows([
    { employeeId: 'E1', displayName: 'Ann Able', dutyStatus: 'AVAILABLE', since: '2026-09-01' },
    { employeeId: 'E2', displayName: 'Ben Bay', dutyStatus: 'removed', since: '2026-10-29', reason: 'POST_INCIDENT', verdict: 'POSITIVE', caseId: 'k1' },
    { employeeId: 'E3', displayName: 'Cy Crew', dutyStatus: 'RETURN_PENDING' },
    { employeeId: 'E4', displayName: 'Dee', dutyStatus: 'SUSPENDED_FOR_POSITIVE' },
  ]);

  it('keeps employee, status and since only; unknown statuses are dropped, removed first', () => {
    expect(rows.map((r) => r.employeeId)).toEqual(['E2', 'E3', 'E1']);
    expect(Object.keys(rows[0]!).sort()).toEqual(['displayName', 'dutyStatus', 'employeeId', 'since']);
    expect(JSON.stringify(rows)).not.toMatch(/POST_INCIDENT|POSITIVE|k1/);
  });

  it('filters and counts', () => {
    expect(countDutyStatus(rows)).toEqual({ AVAILABLE: 1, REMOVED: 1, RETURN_PENDING: 1 });
    expect(filterDutyStatus(rows, 'cy', '').map((r) => r.employeeId)).toEqual(['E3']);
    expect(filterDutyStatus(rows, '', 'AVAILABLE').map((r) => r.employeeId)).toEqual(['E1']);
  });
});

describe('psychosocial unit view (E09 §3.5, §3.1)', () => {
  it('maps the assessment with factor scores / RAG and actions; most recent assessment wins', () => {
    const a = toUnitAssessment([
      { id: 'old', unitCode: 'RAMP', assessedOn: '2025-10-01', factors: [] },
      {
        id: 'pa1',
        unitCode: 'RAMP',
        assessedOn: '2026-06-01',
        reviewDate: '2026-10-01',
        factors: [
          { factor: 'WORKLOAD', likelihood: 4, severity: 4, controls: 'Extra staff at peaks' },
          { factor: 'SUPPORT', likelihood: 2, severity: 2, rag: 'GREEN' },
        ],
        actions: [{ id: 'x1', title: 'Peak staffing plan', owner: 'hse1', dueDate: '2026-10-15', status: 'OPEN' }],
        supportCaseRate: 3.2,
      },
    ])!;
    expect(a.id).toBe('pa1');
    expect(a.factors[0]).toMatchObject({ factor: 'WORKLOAD', score: 16, rag: 'RED' });
    expect(a.factors[1]!.rag).toBe('GREEN');
    expect(a.actions[0]).toMatchObject({ id: 'x1', assessmentId: 'pa1', canComplete: false });
    expect(reviewOverdue(a, NOW)).toBe(true);
    expect(JSON.stringify(a)).not.toContain('supportCaseRate');
    expect(ragFor(8)).toBe('AMBER');
    expect(toUnitAssessment({})).toBeUndefined();
  });

  it('accepts {assessment, actions}; marking done only where the server allows it', () => {
    const a = toUnitAssessment({ assessment: { id: 'pa2', assessedOn: '2026-06-01', factors: [] }, actions: [{ id: 'y', title: 'Debrief', status: 'OPEN', canComplete: true }, { id: 'z', title: 'Survey', status: 'OPEN' }] })!;
    expect(a.actions.map(canMarkPsychosocialDone)).toEqual([true, false]);
  });

  it('shared outcomes keep only employee, fitness, adjustments and when', () => {
    const rows = toSharedOutcomes([
      { employeeId: 'E1', displayName: 'Ann', fitness: 'FIT_WITH_LIMITATIONS', adjustments: 'No night shifts for 4 weeks', sharedAt: '2026-10-20T10:00:00Z', concern: 'ANXIETY', risk: 'HIGH', score: 18, band: 'SEVERE', caseRef: 'SUP-2026-000001' },
      { employeeId: 'E2', displayName: 'Ben' },
    ]);
    expect(rows).toEqual([{ employeeId: 'E1', displayName: 'Ann', fitness: 'FIT_WITH_LIMITATIONS', adjustments: 'No night shifts for 4 weeks', sharedAt: '2026-10-20T10:00:00Z' }]);
  });
});

describe('campaign participation (E12 – suppressed small cells)', () => {
  it('suppressed rows keep no figures, also when the invited count is below the minimum group', () => {
    const p = toParticipation({
      minGroupSize: 5,
      rows: [
        { campaignId: 'c1', title: 'Move more', invited: 40, participated: 22, pct: 55 },
        { campaignId: 'c2', title: 'Hearing check', suppressed: true, invited: 3, participated: 1, pct: 33.3 },
        { campaignId: 'c3', title: 'Sleep', invited: 4, participated: 4 },
        { campaignId: 'c4', title: 'Back care', invited: 10, participated: 3 },
      ],
    });
    expect(p.rows[0]).toMatchObject({ suppressed: false, pct: 55 });
    expect(p.rows[1]).toEqual({ key: 'c2', title: 'Hearing check', unitCode: undefined, period: undefined, suppressed: true });
    expect(p.rows[2]!.suppressed).toBe(true);
    expect(p.rows[3]!.pct).toBe(30);
    expect(toParticipation([{ title: 'X', invited: 9, participated: 9 }]).minGroupSize).toBe(5);
  });
});

describe('ergonomic actions (E12 §6)', () => {
  const actions = toErgonomicActions([
    { id: 'a1', assessmentId: 'ea1', title: 'Raise monitor', owner: 'Mia', dueDate: '2026-10-20', status: 'OPEN', assessmentType: 'DSE_WORKSTATION', location: 'Ops room', findings: 'neck pain reported', employeeId: 'E9' },
    { id: 'a2', assessmentId: 'ea1', title: 'Order chair', dueDate: '2026-11-05', status: 'OPEN' },
    { id: 'a3', title: 'Trolley', dueDate: '2027-01-30', status: 'OPEN' },
    { id: 'a4', assessmentId: 'ea2', title: 'Signage', dueDate: '2026-09-01', status: 'DONE' },
  ]);

  it('due / overdue states and order; findings and employee are dropped', () => {
    expect(actions.map((a) => actionState(a, NOW))).toEqual(['OVERDUE', 'DUE_SOON', 'OPEN', 'DONE']);
    expect(sortActions([...actions].reverse(), NOW).map((a) => a.id)).toEqual(['a1', 'a2', 'a3', 'a4']);
    expect(actions[0]!.context).toBe('Dse workstation · Ops room');
    expect(JSON.stringify(actions)).not.toMatch(/neck|E9/);
  });

  it('the manager may mark open actions done when the assessment is known', () => {
    expect(actions.map(canMarkErgonomicDone)).toEqual([true, true, false, false]);
    expect(ergonomicActionPath(actions[0] as UnitAction)).toBe('/ergonomics/assessments/ea1/actions/a1');
  });
});
