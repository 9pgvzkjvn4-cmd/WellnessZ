import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import {
  buildHrStage,
  emptyTeamAbsenceFilter,
  HR_STAGE_FIELDS,
  hrStagePath,
  toLtaRows,
  toTeamAbsence,
  toUnitAnalytics,
  toWorkspaceCertificates,
} from '../api/absence';
import { CLINICAL_CONTENT_MESSAGE } from '../api/referrals';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { hrStageIds } from '../components/ids';
import { findAll, findOne } from '../test/tree';
import { AnalyticsExport, CertificateTable, LtaCard, TeamAbsenceView, UnitAnalyticsTable, type LtaCardProps } from './Absence';

const team = toTeamAbsence([
  { employeeId: 'E1001', displayName: 'Alice Agent', episodes12m: 1, ohEngaged: false, certificateValidity: 'VALID' },
  { employeeId: 'E1004', displayName: 'Rami Ramp', openEpisode: { startDate: '2026-10-01', days: 28 }, episodes12m: 3, ohEngaged: true, certificateValidity: 'PENDING', reasonCategory: 'MENTAL_HEALTH' },
]);

describe('team absence view', () => {
  it('shows days absent, episode count, OH engaged and validity – no reasons', () => {
    const html = renderToStaticMarkup(<TeamAbsenceView rows={team} filter={emptyTeamAbsenceFilter()} onFilter={vi.fn()} />);
    for (const s of ['Rami Ramp', '28 days', 'OH engaged', 'Pending check', 'Valid', 'At work', '2 team members · 1 currently absent']) expect(html).toContain(s);
    expect(html).toContain('badge-oh-engaged');
    expect(html).not.toMatch(/MENTAL|reason/i);
  });

  it('wires the filter controls', () => {
    const onFilter = vi.fn();
    const tree = TeamAbsenceView({ rows: team, filter: emptyTeamAbsenceFilter(), onFilter });
    findOne(tree, (e) => e.props.id === 'ab-show').props.onChange({ target: { value: 'OH' } });
    expect(onFilter).toHaveBeenLastCalledWith({ q: '', show: 'OH' });
  });
});

const [row] = toLtaRows([
  { caseId: 'k1', caseRef: 'OH-2026-0100', displayName: 'Rami Ramp', absenceStart: '2026-08-01', weeksAbsent: 12, hrStage: 'INFORMAL_REVIEW', nextMilestone: { week: 12, type: 'HR_REVIEW', dueDate: '2026-10-24' }, adviceReleased: 2 },
]);
const card = (over: Partial<LtaCardProps> = {}): LtaCardProps => ({
  row: row!,
  canSetStage: true,
  draft: { stage: 'INFORMAL_REVIEW', note: '' },
  errors: {},
  busy: false,
  onDraft: vi.fn(),
  onSubmit: vi.fn(),
  ...over,
});

describe('long-term absence card (HR)', () => {
  it('shows weeks absent, next milestone, released advice and the HR-stage action', () => {
    const html = renderToStaticMarkup(<ul><LtaCard {...card()} /></ul>);
    for (const s of ['Rami Ramp', 'OH-2026-0100', 'HR stage: Informal review', 'HR review', '(week 12)', '2 reports', 'Update HR stage']) expect(html).toContain(s);
    for (const st of ['INFORMAL_REVIEW', 'FORMAL_REVIEW_1', 'FORMAL_REVIEW_2', 'CAPABILITY_HEARING', 'CLOSED']) expect(html).toContain(`<option value="${st}"`);
    expect(html).not.toContain('<option value="NONE"');
  });

  it('is read-only without the HR-stage permission', () => {
    const html = renderToStaticMarkup(<ul><LtaCard {...card({ canSetStage: false })} /></ul>);
    expect(html).not.toContain('Update HR stage');
    expect(html).not.toContain('<select');
  });

  it('wires stage and note', () => {
    const onDraft = vi.fn();
    const tree = LtaCard(card({ onDraft }));
    const ids = hrStageIds('k1');
    findOne(tree, (e) => e.props.id === ids.stage).props.onChange({ target: { value: 'FORMAL_REVIEW_1' } });
    expect(onDraft).toHaveBeenLastCalledWith({ stage: 'FORMAL_REVIEW_1', note: '' });
    findOne(tree, (e) => e.props.id === ids.note).props.onChange({ target: { value: 'Meeting booked' } });
    expect(onDraft).toHaveBeenLastCalledWith({ stage: 'INFORMAL_REVIEW', note: 'Meeting booked' });
    expect(findAll(tree, (e) => e.type === 'button' && e.props.type === 'submit')).toHaveLength(1);
  });

  it('posts the stage and shows a 422 clinical_content inline on the note', async () => {
    const ok = vi.fn().mockResolvedValue({});
    const body = buildHrStage({ stage: 'FORMAL_REVIEW_1', note: 'Meeting held' });
    expect(await submitAction({ api: ok }, hrStagePath('k1'), body, HR_STAGE_FIELDS, { failed: 'x' })).toMatchObject({ ok: true });
    expect(ok).toHaveBeenCalledWith('/workspace/absence/lta/k1/hr-stage', { method: 'POST', body: { stage: 'FORMAL_REVIEW_1', note: 'Meeting held' } });

    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content', field: 'note', detail: 'ECHO' }));
    const r = await submitAction({ api }, hrStagePath('k1'), body, HR_STAGE_FIELDS, { failed: 'The HR stage was not updated' });
    expect(r).toMatchObject({ ok: false, clinical: true, fieldErrors: { note: CLINICAL_CONTENT_MESSAGE } });
    expect(JSON.stringify(r)).not.toContain('ECHO');
    const html = renderToStaticMarkup(<ul><LtaCard {...card({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message })} /></ul>);
    expect(html).toMatch(/<textarea id="lta-k1-note"[^>]*aria-invalid="true"/);
    expect(html).toContain('role="alert"');
    expect(html).toContain('medical information');
  });
});

describe('certificate validity (HR)', () => {
  it('lists validity and escalation without certificate content', () => {
    const items = toWorkspaceCertificates([
      { id: 'c1', employeeId: 'E1004', displayName: 'Rami', fromDate: '2026-10-01', toDate: '2026-10-14', validity: 'INVALID', escalated: true, issuerName: 'Dr SECRET' },
    ]);
    const html = renderToStaticMarkup(<CertificateTable items={items} />);
    expect(html).toContain('Not valid');
    expect(html).toContain('Escalated – action needed');
    expect(html).not.toContain('SECRET');
    expect(renderToStaticMarkup(<CertificateTable items={[]} />)).toContain('No fit notes');
  });
});

describe('absence analytics (HR)', () => {
  it('renders suppressed units as "fewer than N – suppressed" without numbers', () => {
    const result = toUnitAnalytics([
      { key: 'GND', label: 'Ground ops', headcount: 40, episodes: 12, daysLost: 90, absenceRatePct: 3.4, frequencyRate: 0.3, avgDurationDays: 7.5, bradfordMean: 88, suppressed: false },
      { key: 'CAT', label: 'Catering', headcount: 3, episodes: 2, daysLost: 777, suppressed: true },
    ]);
    const html = renderToStaticMarkup(<UnitAnalyticsTable result={result} groupBy="DEPARTMENT" />);
    expect(html).toContain('Absence by department');
    expect(html).toContain('fewer than 5 – suppressed');
    expect(html).toContain('3.4');
    expect(html).not.toContain('777');
  });

  it('offers the monthly CSV export link for a valid month only', () => {
    const html = renderToStaticMarkup(<AnalyticsExport month="2026-09" onMonth={vi.fn()} />);
    expect(html).toContain('href="/bff/api/absence/analytics/export?month=2026-09"');
    expect(html).toContain('download="absence-pack-2026-09.csv"');
    expect(renderToStaticMarkup(<AnalyticsExport month="" onMonth={vi.fn()} />)).not.toContain('href=');
  });
});
