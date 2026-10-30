import { describe, expect, it } from 'vitest';
import {
  actionView,
  CHECKLISTS,
  ergonomicScore,
  flagMeasure,
  flagMeasures,
  hotspots,
  insightValue,
  isAbnormal,
  list,
  outcomeDelta,
  parseMeasures,
  participationView,
  promotionCampaignBody,
  ragLabel,
  ragTone,
  responseFlags,
  timelineView,
  validateAssessment,
  validatePromotionCampaign,
  validateScreeningEvent,
  type PromotionCampaignForm,
} from './promotion';

describe('screening threshold flags', () => {
  it('flags high / low / normal at the configured cut-offs', () => {
    expect(flagMeasure('systolic', 139)).toBe('NORMAL');
    expect(flagMeasure('systolic', 140)).toBe('HIGH');
    expect(flagMeasure('systolic', 85)).toBe('LOW');
    expect(flagMeasure('bmi', 18.4)).toBe('LOW');
    expect(flagMeasure('bmi', 30)).toBe('HIGH');
    expect(flagMeasure('cholesterolMmol', 6.4)).toBe('NORMAL');
    expect(flagMeasure('wellbeingScore', 40)).toBe('LOW');
    expect(flagMeasure('wellbeingScore', 41)).toBe('NORMAL');
    expect(flagMeasure('mskScore', 7)).toBe('HIGH');
  });

  it('flags a measure set and detects an abnormal result', () => {
    const f = flagMeasures({ systolic: 152, diastolic: 84, bmi: 24.2 });
    expect(f.map((x) => [x.measure, x.flag])).toEqual([['systolic', 'HIGH'], ['diastolic', 'NORMAL'], ['bmi', 'NORMAL']]);
    expect(f[0]?.label).toBe('Systolic BP 152 mmHg');
    expect(isAbnormal(f)).toBe(true);
    expect(isAbnormal(flagMeasures({ diastolic: 80 }))).toBe(false);
  });

  it('parses the form: empty fields skipped, ranges and BP plausibility checked', () => {
    expect(parseMeasures({ systolic: '128', diastolic: '82', bmi: '24,5', waistCm: '' })).toEqual({ measures: { systolic: 128, diastolic: 82, bmi: 24.5 }, errors: [] });
    expect(parseMeasures({}).errors.map((e) => e.field)).toEqual(['m-first']);
    expect(parseMeasures({ systolic: '400' }).errors[0]?.field).toBe('m-systolic');
    expect(parseMeasures({ systolic: 'abc' }).errors[0]?.field).toBe('m-systolic');
    expect(parseMeasures({ systolic: '80', diastolic: '90' }).errors.map((e) => e.field)).toEqual(['m-diastolic']);
  });

  it('reads server flags as strings or objects', () => {
    expect(responseFlags({ flags: ['systolic', { measure: 'bmi', flag: 'HIGH' }] })).toEqual(['systolic', 'bmi high']);
    expect(responseFlags({})).toEqual([]);
  });
});

describe('ergonomic score and RAG', () => {
  const items = CHECKLISTS.DSE_WORKSTATION!;
  const all = (v: number) => Object.fromEntries(items.map((i) => [i.key, v]));

  it('sums 0..3 item scores into a percentage and RAG', () => {
    expect(ergonomicScore(all(0), items)).toEqual({ total: 0, max: 24, pct: 0, scored: 8, rag: 'GREEN' });
    expect(ergonomicScore(all(1), items)).toMatchObject({ total: 8, pct: 33, rag: 'GREEN' });
    expect(ergonomicScore(all(2), items)).toMatchObject({ total: 16, pct: 67, rag: 'RED' });
    expect(ergonomicScore({ ...all(1), SCREEN_HEIGHT: 2 }, items)).toMatchObject({ total: 9, pct: 38, rag: 'AMBER' });
    expect(ergonomicScore(all(3), items)).toMatchObject({ pct: 100, rag: 'RED' });
  });

  it('a single high-risk item raises green to amber; unscored / invalid items are ignored', () => {
    expect(ergonomicScore({ SCREEN_HEIGHT: 3 }, items)).toMatchObject({ total: 3, scored: 1, pct: 13, rag: 'AMBER' });
    expect(ergonomicScore({ SCREEN_HEIGHT: 7, CHAIR_ADJUSTMENT: -1 }, items)).toMatchObject({ total: 0, scored: 0, rag: 'GREEN' });
    expect(ergonomicScore({}, [])).toMatchObject({ max: 0, pct: 0 });
  });

  it('RAG labels and tones', () => {
    expect(ragTone('RED')).toBe('red');
    expect(ragTone('AMBER')).toBe('amber');
    expect(ragTone('GREEN')).toBe('green');
    expect(ragTone(undefined)).toBe('muted');
    expect(ragLabel('RED')).toBe('Red – high risk');
  });

  it('action due / overdue state', () => {
    expect(actionView({ status: 'DONE', dueDate: '2026-01-01' }, '2026-10-30')).toEqual({ tone: 'green', label: 'Done', overdue: false });
    expect(actionView({ status: 'OPEN', dueDate: '2026-10-27' }, '2026-10-30')).toEqual({ tone: 'red', label: 'Overdue 3 d', overdue: true });
    expect(actionView({ status: 'OPEN', dueDate: '2026-10-30' }, '2026-10-30')).toMatchObject({ tone: 'amber', label: 'Due today' });
    expect(actionView({ status: 'OPEN', dueDate: '2026-11-06' }, '2026-10-30')).toMatchObject({ tone: 'amber', label: 'Due in 7 d' });
    expect(actionView({ status: 'OPEN', dueDate: '2026-12-01' }, '2026-10-30').tone).toBe('muted');
  });

  it('validates the assessment form incl. a fully scored checklist', () => {
    const ok = { type: 'DSE_WORKSTATION', unitCode: 'FIN', jobCode: 'ACCOUNTANT', employeeId: '', location: 'HQ 3rd floor', checklist: all(1), findings: '' };
    expect(validateAssessment(ok)).toEqual([]);
    const errs = validateAssessment({ ...ok, unitCode: '', location: ' ', employeeId: 'bob', checklist: { SCREEN_HEIGHT: 1 } });
    expect(errs.map((e) => e.field)).toEqual(['ea-unit', 'ea-location', 'ea-employee', 'ea-item-SCREEN_DISTANCE']);
    expect(errs[3]?.message).toMatch(/7 missing/);
  });
});

describe('campaign planner', () => {
  const form = (over: Partial<PromotionCampaignForm> = {}): PromotionCampaignForm => ({
    title: 'Back care', risk: 'MSK', objective: 'Reduce MSK complaints', targetUnits: 'ramp, cargo ramp', insightRef: '', budgetAmount: '2500', currency: 'gbp',
    startDate: '2026-11-01', endDate: '2027-01-31', owners: 'Pat Promo, ergo1', kpis: [{ metric: 'participation_pct', target: '60' }, { metric: '', target: '' }], status: 'PLANNED', ...over,
  });

  it('validates objective, units, budget, timeline, owners and KPIs', () => {
    expect(validatePromotionCampaign(form())).toEqual([]);
    expect(validatePromotionCampaign(form({ targetUnits: ' ', budgetAmount: '12.345', currency: 'POUND', owners: '' })).map((e) => e.field)).toEqual(['pc-units', 'pc-budget', 'pc-currency', 'pc-owners']);
    expect(validatePromotionCampaign(form({ endDate: '2026-10-01' })).map((e) => e.field)).toEqual(['pc-end']);
    expect(validatePromotionCampaign(form({ kpis: [{ metric: '', target: '' }] })).map((e) => e.field)).toEqual(['pc-kpi-0-metric']);
    expect(validatePromotionCampaign(form({ kpis: [{ metric: 'x', target: 'abc' }] })).map((e) => e.field)).toEqual(['pc-kpi-0-target']);
  });

  it('builds the contract body (money as a 2-decimal string, codes upper-cased, empty KPIs dropped)', () => {
    expect(promotionCampaignBody(form())).toEqual({
      title: 'Back care', risk: 'MSK', objective: 'Reduce MSK complaints', targetUnits: ['RAMP', 'CARGO'], budget: { amount: '2500.00', currency: 'GBP' },
      startDate: '2026-11-01', endDate: '2027-01-31', owners: ['Pat Promo', 'ergo1'], kpis: [{ metric: 'participation_pct', target: 60 }], status: 'PLANNED',
    });
    expect(promotionCampaignBody(form({ budgetAmount: '99.5', insightRef: 'RAMP:MSK:2026-Q3' }))).toMatchObject({ budget: { amount: '99.50' }, insightRef: 'RAMP:MSK:2026-Q3' });
    expect(list('a; b,a')).toEqual(['A', 'B']);
  });

  it('timeline position', () => {
    expect(timelineView('2026-11-01', '2026-11-10', '2026-10-30')).toEqual({ label: 'Starts in 2 d', pct: 0 });
    expect(timelineView('2026-11-01', '2026-11-10', '2026-11-05')).toEqual({ label: 'Day 5 of 10', pct: 50 });
    expect(timelineView('2026-11-01', '2026-11-10', '2026-11-11')).toEqual({ label: 'Ended', pct: 100 });
  });

  it('validates screening events', () => {
    expect(validateScreeningEvent({ type: 'BIOMETRIC', siteCode: 'lhr-t5', date: '2026-11-03' })).toEqual([]);
    expect(validateScreeningEvent({ type: '', siteCode: ' ', date: '' }).map((e) => e.field)).toEqual(['se-type', 'se-site', 'se-date']);
  });
});

describe('insights and reports (suppressed)', () => {
  it('insight values never show a suppressed number', () => {
    expect(insightValue({ value: 12.34, suppressed: false })).toBe('12.3');
    expect(insightValue({ value: 7, suppressed: false })).toBe('7');
    expect(insightValue({ value: 3, suppressed: true })).toBe('Suppressed');
    expect(insightValue({ value: null, suppressed: false })).toBe('Suppressed');
  });

  it('hotspots: reported first, per risk, highest value first; filter by risk', () => {
    const rows = [
      { unitCode: 'A', risk: 'MSK' as const, indicator: 'cases', value: 2, period: 'Q3', suppressed: false },
      { unitCode: 'B', risk: 'MSK' as const, indicator: 'cases', value: null, period: 'Q3', suppressed: true },
      { unitCode: 'C', risk: 'MSK' as const, indicator: 'cases', value: 9, period: 'Q3', suppressed: false },
      { unitCode: 'D', risk: 'HEARING' as const, indicator: 'shifts', value: 4, period: 'Q3', suppressed: false },
    ];
    expect(hotspots(rows).map((r) => r.unitCode)).toEqual(['D', 'C', 'A', 'B']);
    expect(hotspots(rows, 'HEARING').map((r) => r.unitCode)).toEqual(['D']);
  });

  it('participation rows: suppression by flag or group < k, percentage from the server or derived', () => {
    expect(participationView({ key: 'RAMP', invited: 40, participated: 22, pct: 55, suppressed: false })).toEqual({ suppressed: false, invited: '40', participated: '22', pct: '55.0 %' });
    expect(participationView({ key: 'RAMP', invited: 40, participated: 10, suppressed: false }).pct).toBe('25.0 %');
    expect(participationView({ key: 'CAT', invited: 4, participated: 4, suppressed: false })).toEqual({ suppressed: true, invited: '<5', participated: '–', pct: 'Suppressed' });
    expect(participationView({ key: 'CAT', suppressed: true }).suppressed).toBe(true);
  });

  it('outcome deltas know which direction is an improvement', () => {
    expect(outcomeDelta({ metric: 'mean_systolic', baseline: 138, current: 134.6, delta: -3.4 })).toEqual({ text: '−3.4', tone: 'green', direction: 'down' });
    expect(outcomeDelta({ metric: 'mean_bmi', baseline: 27, current: 27.5 })).toEqual({ text: '+0.5', tone: 'red', direction: 'up' });
    expect(outcomeDelta({ metric: 'wellbeing_score', baseline: 48, current: 51 })).toEqual({ text: '+3', tone: 'green', direction: 'up' });
    expect(outcomeDelta({ metric: 'steps', baseline: 5000, current: 5000 })).toEqual({ text: '±0', tone: 'muted', direction: 'flat' });
    expect(outcomeDelta({ metric: 'x' })).toEqual({ text: '–', tone: 'muted', direction: 'none' });
  });
});
