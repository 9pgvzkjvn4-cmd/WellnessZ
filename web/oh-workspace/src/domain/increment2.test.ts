import { describe, expect, it } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import { cdsAlertsOf, isAiNotConfigured, missingOverrideReasons, overrideFieldId, sortAlerts, toOverrides } from './cds';
import { emptyAudioGrid, toDeviceResult, validateDeviceForm, type DeviceForm } from './devices';
import { addDiagnosis, BODY_REGIONS, removeDiagnosis, setMechanism, setPrimary, signBlockers, toggleRegion } from './encounter';
import { defaultValidUntil, mayDecide, maySignoff, restrictionLabel, restrictionValues, validateDecision, type DecisionForm } from './fitness';
import { parseConcepts, parseHazards, parseJobDemands } from './imports';
import { validatePlan } from './rtw';
import { catalogue, decision, injuryTemplate, standards } from '../testing/fixtures';

describe('CDS', () => {
  const alerts = [{ code: 'INTERACTION', severity: 'WARNING' }, { code: 'ALLERGY_CLASS', severity: 'CRITICAL' }];
  it('reads alerts only from 409 code=cds_alert', () => {
    expect(cdsAlertsOf(new ApiError(409, { code: 'cds_alert', alerts: [{ code: 'X' }] }))).toEqual([{ code: 'X' }]);
    expect(cdsAlertsOf(new ApiError(409, { code: 'other' }))).toBeUndefined();
    expect(cdsAlertsOf(new ApiError(422, { code: 'cds_alert' }))).toBeUndefined();
    expect(cdsAlertsOf(new Error('x'))).toBeUndefined();
  });
  it('requires a non-blank reason for every alert', () => {
    expect(missingOverrideReasons(alerts, { INTERACTION: '  ' })).toEqual(['INTERACTION', 'ALLERGY_CLASS']);
    expect(() => toOverrides(alerts, { INTERACTION: 'ok' })).toThrow(/ALLERGY_CLASS/);
    expect(toOverrides(alerts, { INTERACTION: ' a ', ALLERGY_CLASS: 'b' })).toEqual([{ alertCode: 'INTERACTION', reason: 'a' }, { alertCode: 'ALLERGY_CLASS', reason: 'b' }]);
  });
  it('sorts critical first and builds safe field ids', () => {
    expect(sortAlerts(alerts).map((a) => a.code)).toEqual(['ALLERGY_CLASS', 'INTERACTION']);
    expect(overrideFieldId('A B/C')).toBe('cds-reason-A_B_C');
  });
  it('detects 503 ai_not_configured', () => {
    expect(isAiNotConfigured(new ApiError(503, { code: 'ai_not_configured' }))).toBe(true);
    expect(isAiNotConfigured(new ApiError(503, {}))).toBe(false);
  });
});

describe('device results', () => {
  const form = (over: Partial<DeviceForm> = {}): DeviceForm => ({
    category: 'AUDIOMETRY', manual: false, deviceId: 'D', deviceModel: '', calibrationId: 'C', calibrationDate: '', performedAt: '2026-10-29T10:00', caseId: '',
    audio: emptyAudioGrid(), spiro: { fev1L: '', fvcL: '', fev1PredictedL: '', fvcPredictedL: '' },
    vision: { acuityLeft: '', acuityRight: '', binocular: '', colourPlatesCorrect: '', colourPlatesTotal: '15', corrected: false }, ecg: { rhythm: 'SINUS', heartRate: '', interpretation: 'NORMAL', qtcMs: '' }, ...over,
  });
  it('needs all 14 thresholds within -10..120 dB HL', () => {
    expect(validateDeviceForm(form())).toHaveLength(14);
    const g = emptyAudioGrid();
    for (const ear of ['left', 'right'] as const) for (const hz of Object.keys(g[ear])) g[ear][hz] = '20';
    g.left['4000'] = '130';
    expect(validateDeviceForm(form({ audio: g })).map((e) => e.field)).toEqual(['audio-left-4000']);
  });
  it('calibration is mandatory for device capture, not for manual entry', () => {
    const f = form({ category: 'ECG', calibrationId: '', ecg: { rhythm: 'SINUS', heartRate: '70', interpretation: 'NORMAL', qtcMs: '' } });
    expect(validateDeviceForm(f).map((e) => e.field)).toEqual(['calibrationId']);
    expect(validateDeviceForm({ ...f, manual: true, deviceId: '' })).toEqual([]);
    expect(toDeviceResult({ ...f, manual: true }, 'iso')).toEqual({ category: 'ECG', deviceId: null, performedAt: 'iso', measurements: { rhythm: 'SINUS', heartRate: 70, interpretation: 'NORMAL' } });
  });
  it('vision: Snellen notation and colour plates within total', () => {
    const f = form({ category: 'VISION', vision: { acuityLeft: '6/6', acuityRight: '6/9', binocular: '6/6', colourPlatesCorrect: '16', colourPlatesTotal: '15', corrected: true } });
    expect(validateDeviceForm(f).map((e) => e.field)).toEqual(['vision-colourPlatesCorrect']);
    expect(toDeviceResult({ ...f, vision: { ...f.vision, colourPlatesCorrect: '14' } }, 'iso').measurements).toEqual({ acuityLeft: '6/6', acuityRight: '6/9', binocular: '6/6', colourPlatesCorrect: 14, colourPlatesTotal: 15, corrected: true });
  });
});

describe('encounter rules', () => {
  it('sign blockers: required template fields, diagnosis for INJURY/ILLNESS/MENTAL_HEALTH, exactly one primary', () => {
    expect(signBlockers(injuryTemplate, 'INJURY', {}, []).map((e) => e.field)).toEqual(['enc-field-mechanismText', 'enc-diagnoses']);
    const tpl = { ...injuryTemplate, requiresDiagnosis: false, sections: [] };
    expect(signBlockers(tpl, 'TRAVEL', {}, [])).toEqual([]);
    expect(signBlockers(tpl, 'ILLNESS', {}, [])).toHaveLength(1);
    expect(signBlockers(tpl, 'TRAVEL', {}, [{ system: 'ICD_10', code: 'A', display: 'a', primary: false }])[0]?.message).toMatch(/exactly one diagnosis as primary/);
  });
  it('diagnosis list keeps one primary', () => {
    let l = addDiagnosis([], { system: 'ICD_10', code: 'A', display: 'a' });
    l = addDiagnosis(l, { system: 'ICD_10', code: 'B', display: 'b' });
    l = addDiagnosis(l, { system: 'ICD_10', code: 'B', display: 'b' });
    expect(l.map((d) => [d.code, d.primary])).toEqual([['A', true], ['B', false]]);
    expect(setPrimary(l, 1).map((d) => d.primary)).toEqual([false, true]);
    expect(removeDiagnosis(l, 0)).toEqual([{ system: 'ICD_10', code: 'B', display: 'b', primary: true }]);
  });
  it('body map: multi-site toggles per view with a mechanism', () => {
    expect(BODY_REGIONS.FRONT).toContain('LEFT_KNEE');
    expect(BODY_REGIONS.BACK).toContain('LOWER_BACK');
    let m = toggleRegion([], 'FRONT', 'LEFT_KNEE');
    m = toggleRegion(m, 'BACK', 'LEFT_KNEE');
    m = setMechanism(m, 'BACK', 'LEFT_KNEE', 'FALL');
    expect(m).toEqual([{ side: 'FRONT', region: 'LEFT_KNEE', mechanism: null }, { side: 'BACK', region: 'LEFT_KNEE', mechanism: 'FALL' }]);
    expect(toggleRegion(m, 'FRONT', 'LEFT_KNEE')).toHaveLength(1);
  });
});

describe('fitness rules', () => {
  const f = (over: Partial<DecisionForm> = {}): DecisionForm => ({ standardCode: 'X', outcome: 'FIT', limitationCodes: [], restrictions: [], validFrom: '2026-10-29', validUntil: '', reviewDate: '', rationale: 'r', durationOverrideReason: '', generateCertificate: true, ...over });
  it('validity defaults from the standard', () => {
    expect(defaultValidUntil('2026-10-29', 12)).toBe('2027-10-29');
    expect(defaultValidUntil('2026-10-29', null)).toBe('');
  });
  it('licensed standards need AME or CMO', () => {
    expect(mayDecide(standards[1], ['OH_CLINICIAN'])).toBe(false);
    expect(mayDecide(standards[1], ['AME'])).toBe(true);
    expect(mayDecide(standards[0], ['OH_CLINICIAN'])).toBe(true);
  });
  it('sign-off: CMO for permanently unfit, AME/CMO otherwise, only when awaiting', () => {
    expect(maySignoff(decision({ status: 'AWAITING_SIGNOFF', outcome: 'PERMANENTLY_UNFIT' }), ['AME'])).toBe(false);
    expect(maySignoff(decision({ status: 'AWAITING_SIGNOFF', outcome: 'PERMANENTLY_UNFIT' }), ['CMO'])).toBe(true);
    expect(maySignoff(decision({ status: 'AWAITING_SIGNOFF' }), ['AME'])).toBe(true);
    expect(maySignoff(decision({ status: 'FINAL' }), ['CMO'])).toBe(false);
  });
  it('decision validation', () => {
    expect(validateDecision(f(), catalogue, null)).toEqual([]);
    expect(validateDecision(f({ outcome: 'FIT_WITH_LIMITATIONS' }), catalogue, null).map((e) => e.field)).toEqual(['fd-restrictions']);
    expect(validateDecision(f({ outcome: 'FIT_WITH_LIMITATIONS', limitationCodes: ['VDL'] }), catalogue, null)).toEqual([]);
    expect(validateDecision(f({ validUntil: '2026-01-01', reviewDate: '2026-01-01' }), catalogue, null).map((e) => e.field)).toEqual(['fd-valid-until', 'fd-review']);
    expect(validateDecision(f(), catalogue, { expectedDays: 14, exceeded: true }).map((e) => e.field)).toEqual(['fd-duration-reason']);
    expect(validateDecision(f({ rationale: 'x'.repeat(4001) }), catalogue, null).map((e) => e.field)).toEqual(['fd-rationale']);
  });
  it('restriction values and labels', () => {
    expect(restrictionValues([{ code: 'LIFT_MAX_KG', value: '10' }, { code: 'NO_NIGHT', value: '' }], catalogue)).toEqual([{ code: 'LIFT_MAX_KG', value: 10 }, { code: 'NO_NIGHT' }]);
    expect(restrictionLabel(catalogue[0], 'LIFT_MAX_KG', 10)).toBe('No lifting above 10 kg');
    expect(restrictionLabel(undefined, 'UNKNOWN')).toBe('UNKNOWN');
  });
});

describe('RTW plan rules', () => {
  const p = (fromWeek: number, toWeek: number, duties = 'd', hoursPerDay = 4) => ({ fromWeek, toWeek, hoursPerDay, duties, restrictions: [] });
  it('accepts consecutive phases and rejects overlaps, bad hours and early reviews', () => {
    expect(validatePlan('2026-11-02', [p(1, 2), p(3, 4)], ['2026-11-09'], 'goal')).toEqual([]);
    expect(validatePlan('2026-11-02', [p(1, 2), p(2, 4)], ['2026-11-09'], 'goal').map((e) => e.field)).toEqual(['rtw-phase-1-from']);
    expect(validatePlan('2026-11-02', [p(3, 2, 'd', 30)], ['2026-11-01'], '').map((e) => e.field)).toEqual(['rtw-goal', 'rtw-phase-0-from', 'rtw-phase-0-hours', 'rtw-review-0']);
  });
});

describe('imports', () => {
  it('parses concept JSON and CSV (quoted display) and rejects bad rows', () => {
    expect(parseConcepts('[{"system":"LOINC","code":"1","display":"x"}]')).toEqual({ ok: true, items: [{ system: 'LOINC', code: '1', display: 'x', active: true }] });
    expect(parseConcepts('system,code,display,active\nICD_10,A,"a, b",false')).toEqual({ ok: true, items: [{ system: 'ICD_10', code: 'A', display: 'a, b', active: false }] });
    expect(parseConcepts('code,system\n1,2')).toMatchObject({ ok: false });
    expect(parseConcepts('[{"system":"LOINC"}]')).toMatchObject({ ok: false, error: 'Row 1: system, code and display are required.' });
  });
  it('parses job-demand profiles', () => {
    expect(parseJobDemands('{"jobCode":"A","title":"t","unit":"GND","demands":{}}')).toMatchObject({ ok: true });
    expect(parseJobDemands('nope')).toMatchObject({ ok: false });
  });
  it('parses hazards', () => {
    expect(parseHazards('noise, DUST:Wood dust ,')).toEqual([{ code: 'NOISE', name: 'noise' }, { code: 'DUST', name: 'Wood dust' }]);
  });
});

describe('body map API mapping', () => {
  it('splits laterality and view for the API and restores the UI codes', async () => {
    const { toApiBodyMap, fromApiBodyMap } = await import('./encounter');
    const ui = [
      { side: 'FRONT' as const, region: 'LEFT_KNEE', mechanism: 'FALL' },
      { side: 'BACK' as const, region: 'LOWER_BACK', mechanism: null },
      { side: 'FRONT' as const, region: 'EYES', mechanism: 'BURN_CHEMICAL' },
    ];
    const api = toApiBodyMap(ui);
    expect(api).toEqual([
      { region: 'KNEE', side: 'LEFT', view: 'FRONT', mechanism: 'FALL' },
      { region: 'LOWER_BACK', side: 'MIDLINE', view: 'BACK', mechanism: null },
      { region: 'EYE', side: 'MIDLINE', view: 'FRONT', mechanism: 'BURN_CHEMICAL' },
    ]);
    expect(fromApiBodyMap(api)).toEqual(ui);
  });
});

describe('SOAP section of encounter templates', () => {
  it('checks required SOAP fields against the soap object, not the answers', async () => {
    const { signBlockers } = await import('./encounter');
    const tpl = { type: 'INJURY', version: 1, title: 'Injury', bodyMap: true, requiresDiagnosis: false, fitnessStandardPrompt: false, status: 'DRAFT',
      sections: [{ key: 'soap', title: 'SOAP', fields: [{ key: 'assessment', label: 'Assessment', type: 'TEXTAREA', required: true }] }] } as never;
    expect(signBlockers(tpl, 'TRAVEL', {}, [], {}).map((e) => e.field)).toEqual(['soap-assessment']);
    expect(signBlockers(tpl, 'TRAVEL', {}, [], { assessment: 'Wrist strain' })).toEqual([]);
  });
});
