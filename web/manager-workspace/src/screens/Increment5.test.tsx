import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { emptyForCauseForm, OBSERVATION_LABELS, toDutyStatusRows, toForCauseRows } from '../api/testing';
import { toErgonomicActions, toParticipation, toSharedOutcomes, toUnitAssessment } from '../api/unit';
import type { AuthClient, Session } from '../auth/AuthClient';
import { ActionTable } from '../components/UnitActions';
import { findAll, findOne } from '../test/tree';
import { Ergonomics } from './Ergonomics';
import { FeatureNotAvailable } from './FeatureNotAvailable';
import { AssessmentView, Psychosocial, SharedOutcomesTable } from './Psychosocial';
import { DutyStatusView, ForCauseFormView, MyRequestsTable, Testing } from './Testing';
import { ParticipationTable, UnitCampaigns } from './UnitCampaigns';

const NOW = new Date(2026, 9, 30, 10, 0, 0);
const client = { api: vi.fn(() => new Promise(() => {})) } as unknown as AuthClient;
const session = (roles: string[]): Session => ({ authenticated: true, name: 'Test', roles });

describe('testing screen', () => {
  it('for-cause form: checklist of every observation, explanation where it goes, second observer', () => {
    const onChange = vi.fn();
    const tree = ForCauseFormView({ form: emptyForCauseForm(), errors: { observations: 'Tick at least one observation.' }, busy: false, employeePicker: <p>picker</p>, onChange, onSubmit: vi.fn(), maxObservedAt: '2026-10-30T10:00' });
    const html = renderToStaticMarkup(tree);
    for (const l of Object.values(OBSERVATION_LABELS)) expect(html).toContain(l.replace(/'/g, '&#x27;'));
    expect(html).toContain('drug &amp; alcohol testing programme only');
    expect(html).toContain('never a');
    expect(html).toContain('Second observer');
    expect(html).toContain('Tick at least one observation.');
    expect(html).toContain('type="datetime-local"');
    findOne(tree, (e) => e.props.id === 'fc-obs-DROWSINESS').props.onChange();
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ observations: ['DROWSINESS'] }));
  });

  it('my requests show the neutral phase only', () => {
    const html = renderToStaticMarkup(<MyRequestsTable rows={toForCauseRows([{ id: 't', ref: 'DAT-2026-000001', displayName: 'Rami Ramp', status: 'VERIFIED_POSITIVE' }])} />);
    expect(html).toContain('With the testing programme');
    expect(html).not.toMatch(/positive|negative|refus/i);
  });

  it('duty-status list: status and since, filters wired, no reasons', () => {
    const rows = toDutyStatusRows([
      { employeeId: 'E2', displayName: 'Ben Bay', dutyStatus: 'REMOVED', since: '2026-10-29', reason: 'RANDOM' },
      { employeeId: 'E1', displayName: 'Ann Able', dutyStatus: 'AVAILABLE' },
    ]);
    const onStatus = vi.fn();
    const tree = DutyStatusView({ rows, q: '', status: '', onQ: vi.fn(), onStatus });
    const html = renderToStaticMarkup(tree);
    expect(html).toContain('Removed from safety-sensitive duty');
    expect(html).toContain('badge-duty-removed');
    expect(html).not.toContain('RANDOM');
    findOne(tree, (e) => e.props.id === 'ds-status').props.onChange({ target: { value: 'REMOVED' } });
    expect(onStatus).toHaveBeenCalledWith('REMOVED');
  });

  it('shows the sections by role', () => {
    const mgr = renderToStaticMarkup(<Testing client={client} session={session(['MANAGER'])} onSessionExpired={vi.fn()} />);
    expect(mgr).toContain('Request a for-cause test');
    expect(mgr).not.toContain('Safety-sensitive duty status');
    const crew = renderToStaticMarkup(<Testing client={client} session={session(['CREW_SCHEDULING'])} onSessionExpired={vi.fn()} />);
    expect(crew).toContain('Safety-sensitive duty status');
    expect(crew).not.toContain('Request a for-cause test');
    const safety = renderToStaticMarkup(<Testing client={client} session={session(['SAFETY_MANAGER'])} onSessionExpired={vi.fn()} />);
    expect(safety).toContain('Request a for-cause test');
  });
});

describe('psychosocial screen', () => {
  const assessment = toUnitAssessment({
    id: 'pa1',
    unitCode: 'RAMP',
    assessedOn: '2026-06-01',
    reviewDate: '2027-06-01',
    factors: [{ factor: 'SHIFT_FATIGUE', likelihood: 3, severity: 3, controls: 'Fatigue risk management' }],
    actions: [
      { id: 'x1', title: 'Rest-room upgrade', status: 'OPEN', dueDate: '2026-10-01' },
      { id: 'x2', title: 'Shift review', status: 'OPEN', dueDate: '2026-12-31', canComplete: true },
    ],
  })!;

  it('renders factors with RAG and actions; only server-allowed actions get "Mark done"', () => {
    const onMarkDone = vi.fn();
    const html = renderToStaticMarkup(<AssessmentView assessment={assessment} now={NOW} onMarkDone={onMarkDone} />);
    expect(html).toContain('Shift work and fatigue');
    expect(html).toContain('Medium risk (9)');
    expect(html).toContain('Overdue');
    const table = ActionTable({ caption: 'c', actions: assessment.actions, now: NOW, canMarkDone: (a) => a.canComplete, onMarkDone });
    const buttons = findAll(table, (e) => e.type === 'button');
    expect(buttons).toHaveLength(1);
    buttons[0]!.props.onClick();
    expect(onMarkDone).toHaveBeenCalledWith(expect.objectContaining({ id: 'x2' }));
  });

  it('shared outcomes: fitness and adjustments only, with the explanation', () => {
    const html = renderToStaticMarkup(<SharedOutcomesTable rows={toSharedOutcomes([{ employeeId: 'E1', displayName: 'Ann', fitness: 'FIT_WITH_LIMITATIONS', concern: 'LOW_MOOD' }])} />);
    expect(html).toContain('Fit with limitations');
    expect(html).toContain('Not shared');
    expect(html).not.toContain('LOW_MOOD');
    const screen = renderToStaticMarkup(<Psychosocial client={client} session={session(['MANAGER'])} onSessionExpired={vi.fn()} />);
    expect(screen).toContain('chose to share it with you');
    expect(screen).toContain('never');
  });
});

describe('campaigns and ergonomics screens', () => {
  it('suppressed rows read "fewer than N – suppressed" and show no figures', () => {
    const data = toParticipation({ minGroupSize: 5, rows: [{ campaignId: 'c1', title: 'Move more', invited: 40, participated: 22, pct: 55 }, { campaignId: 'c2', title: 'Hearing', suppressed: true, invited: 3, participated: 1 }] });
    const html = renderToStaticMarkup(<ParticipationTable data={data} />);
    expect(html).toContain('Fewer than 5 – suppressed');
    expect(html).toContain('55%');
    expect(html).not.toContain('>3<');
    expect(renderToStaticMarkup(<UnitCampaigns client={client} session={session(['MANAGER'])} onSessionExpired={vi.fn()} />)).toContain('You never see who took part');
  });

  it('ergonomic actions show due / overdue and a "Mark done" button for open actions', () => {
    const actions = toErgonomicActions([
      { id: 'a1', assessmentId: 'ea1', title: 'Raise monitor', dueDate: '2026-10-20', status: 'OPEN' },
      { id: 'a2', assessmentId: 'ea1', title: 'Signage', dueDate: '2026-09-01', status: 'DONE' },
    ]);
    const tree = ActionTable({ caption: 'Ergonomic actions', actions, now: NOW, canMarkDone: (a) => a.status === 'OPEN', onMarkDone: vi.fn() });
    const html = renderToStaticMarkup(tree);
    expect(html).toContain('badge-action-overdue');
    expect(html).toContain('badge-action-done');
    expect(findAll(tree, (e) => e.type === 'button')).toHaveLength(1);
    expect(renderToStaticMarkup(<Ergonomics client={client} session={session(['MANAGER'])} onSessionExpired={vi.fn()} />)).toContain('within 60 days');
  });

  it('not-available page names who may use the increment-5 screens', () => {
    expect(renderToStaticMarkup(<FeatureNotAvailable feature="psychosocial" />)).toContain('line managers');
    expect(renderToStaticMarkup(<FeatureNotAvailable feature="testing" />)).toContain('crew scheduling');
  });
});
