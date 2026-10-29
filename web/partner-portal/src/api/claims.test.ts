import { describe, expect, it } from 'vitest';
import { mockApi, reply } from '../test/mockApi';
import { claimDetail } from '../test/fixtures';
import { canCompleteTask, createTask, loadClaim, toClaimDetail, toPartnerClaims, validateTask } from './claims';

const NOW = new Date('2026-10-29T12:00:00Z');

describe('adjuster claims mappers', () => {
  it('maps the claim list', () => {
    expect(toPartnerClaims([{ id: 'c1', claimRef: 'CLM-1', claimType: 'WORKERS_COMP', status: 'OPEN', displayName: 'A', incidentDate: '2026-10-01', lostTime: true, openTasks: 2, unreadMessages: 1, reserves: { MEDICAL: 1 } }])).toEqual([
      { id: 'c1', claimRef: 'CLM-1', claimType: 'WORKERS_COMP', status: 'OPEN', displayName: 'A', incidentDate: '2026-10-01', lostTime: true, openTasks: 2, unreadMessages: 1 },
    ]);
  });

  it('keeps RTW status and milestones only – never clinical content, money or document internals', () => {
    const d = toClaimDetail(claimDetail())!;
    expect(d.rtw).toEqual({
      fitnessStatus: 'FIT_WITH_RESTRICTIONS',
      restrictionUntil: '2026-11-30',
      rtwPlan: { status: 'ACTIVE', plannedStart: '2026-11-02', nextReview: '2026-11-16', phases: [{ from: '2026-11-02', to: '2026-11-15', hoursPerDay: 4, dutiesSummary: 'Office-based duties, no lifting' }] },
    });
    expect(d.documents).toEqual([{ id: 'd1', documentNumber: 'DOC-2026-000777', type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate', date: '2026-10-21T10:00:00Z', version: undefined }]);
    expect(JSON.stringify(d)).not.toMatch(/SECRET|reserves|1000\.00/);
  });

  it('lets the adjuster complete only open ADJUSTER tasks', () => {
    const [mine, nurse] = toClaimDetail(claimDetail())!.tasks;
    expect(canCompleteTask(mine!)).toBe(true);
    expect(canCompleteTask(nurse!)).toBe(false);
    expect(canCompleteTask({ ...mine!, status: 'DONE' })).toBe(false);
  });

  it('fills a missing rtw from /claims/{id}/rtw and tolerates its absence', async () => {
    const withRtw = mockApi({ 'GET /partner/claims/cl1': claimDetail({ rtw: undefined }), 'GET /claims/cl1/rtw': { fitnessStatus: 'FIT' } });
    expect((await loadClaim(withRtw.client, 'cl1'))?.rtw).toEqual({ fitnessStatus: 'FIT', restrictionUntil: undefined, rtwPlan: undefined });
    const without = mockApi({ 'GET /partner/claims/cl1': claimDetail({ rtw: undefined }), 'GET /claims/cl1/rtw': reply(404, { status: 404 }) });
    expect((await loadClaim(without.client, 'cl1'))?.rtw).toBeUndefined();
  });

  it('validates and posts a new task', async () => {
    expect(validateTask({ title: '', dueDate: '2026-10-01', assigneeRole: 'BOSS' }, NOW)).toEqual({
      title: 'This field is required.',
      dueDate: 'The date cannot be in the past.',
      assigneeRole: 'This field is required.',
    });
    expect(validateTask({ title: 'Call', dueDate: '2026-11-01', assigneeRole: 'CLAIMS' }, NOW)).toEqual({});
    const api = mockApi({ 'POST /claims/cl1/tasks': { id: 'tk9' } });
    await createTask(api.client, 'cl1', { title: ' Call employer ', dueDate: '2026-11-01', assigneeRole: 'NURSE_CM' }, 'x');
    expect(api.calls[0]!.body).toEqual({ title: 'Call employer', dueDate: '2026-11-01', assigneeRole: 'NURSE_CM' });
  });
});
