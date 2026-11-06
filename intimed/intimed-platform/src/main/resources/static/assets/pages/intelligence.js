// Intelligence studio (IntiMed 2.0 – IM-E11…IM-E15): validation studio, device identification, auto-mapping,
// physiology-aware cohorts, reference-interval knowledge base, platform categories and drift. IntiMed 2.1: derived values and
// delta flags in the studio (IM-E14-BL-010/012), interval governance (IM-E14-BL-011), trajectories (IM-E13-BL-005),
// drift acknowledge/resolve (IM-E15-BL-003), terminology adapter status (IM-E12-BL-010). No innerHTML.
import { h, clear, get, post, guard, badge, table, card, kpi, field, input, select, button, pre, tabs, modal, toast } from '../lib.js';

const TABS = [['validate', 'Validation studio'], ['identify', 'Device identification'], ['automap', 'Auto-mapping'], ['cohorts', 'Simulator cohorts'],
  ['trajectories', 'Trajectories'], ['ranges', 'Reference ranges'], ['intervals', 'Interval governance'], ['categories', 'Platform categories'],
  ['types', 'Device types & profiles'], ['drift', 'Drift']];

export async function intelligencePage(main, [sub]) {
  const tab = sub || 'validate';
  main.append(h('h1', null, 'Intelligence studio'),
    h('p', { class: 'lead' }, 'See exactly what a device sends, what IntiMed maps it to, and how it is validated and flagged for each healthcare-platform category – with age-, sex- and context-partitioned reference ranges.'),
    tabs(TABS, tab, t => { location.hash = '#/intelligence/' + t; }));
  const box = h('div'); main.append(box);
  await ({ validate, identify, automap, cohorts, trajectories, ranges, intervals, categories, types, drift }[tab] || validate)(box);
}

const FLAG_KIND = { N: 'ok', L: 'warn', H: 'warn', LL: 'bad', HH: 'bad' };
export function flagBadge(i) { return i ? badge(i.code + ' · ' + i.display, FLAG_KIND[i.code] || 'neutral') : '—'; }

async function validate(box) {
  const [drivers, cohortsList, cats] = await Promise.all([get('/api/drivers'), get('/api/intelligence/cohorts'), get('/api/intelligence/categories')]);
  const drv = select(drivers.map(d => [d.ref, d.name]), 'monitor.philips.intellivue-pcd01@1.0.0');
  const tpl = select([], '');
  const cat = select([['', '(category of the template)'], ...cats.map(c => [c.id, c.name])], '');
  const src = select([['cohort', 'Simulated cohort'], ['golden', 'Driver golden session'], ['paste', 'Pasted payload']], 'cohort');
  const prof = select(cohortsList.map(p => [p.id, p.name]), 'adult-sepsis-like');
  const age = input({ type: 'number', value: '45', step: '0.1', min: '0', max: '120' }), sex = select(['female', 'male'], 'male');
  const preg = select([['', 'not pregnant'], ['1', 'T1'], ['2', 'T2'], ['3', 'T3']], '');
  const alt = input({ type: 'number', value: '0', min: '0', max: '6000' });
  const consent = h('input', { type: 'checkbox', checked: true });
  const derivedOn = h('input', { type: 'checkbox' });
  const previous = input({ placeholder: '{"potassium":{"value":4.0,"minutesAgo":120}}', class: 'in mono' });
  const payload = h('textarea', { class: 'in mono', rows: 6, placeholder: 'Paste an HL7 v2 / ASTM / XML / JSON / GDT / serial payload' });
  const out = h('div');
  const refreshTpl = () => {
    const d = drivers.find(x => x.ref === drv.value);
    clear(tpl); (d ? d.templates : []).forEach(t => tpl.append(h('option', { value: t }, t)));
  };
  drv.addEventListener('change', refreshTpl); refreshTpl();
  const run = async () => {
    const body = { driver: drv.value, templateId: tpl.value, category: cat.value || null, context: { consent: consent.checked, siteAltitudeM: Number(alt.value) }, derivedValues: derivedOn.checked };
    if (previous.value.trim()) { try { body.previous = JSON.parse(previous.value); } catch (e) { toast('Previous values must be JSON', 'err'); return; } }
    if (src.value === 'cohort') body.profile = prof.value;
    else {
      body.demographics = { ageYears: Number(age.value), sex: sex.value, siteAltitudeM: Number(alt.value) };
      if (preg.value) Object.assign(body.demographics, { pregnant: true, trimester: Number(preg.value) });
      if (src.value === 'paste') body.payload = payload.value;
    }
    const r = await guard(() => post('/api/intelligence/validate', body));
    if (r) renderValidation(clear(out), r);
  };
  box.append(card('Input',
    h('div', { class: 'form cols' }, field('Driver (device model · option)', drv), field('Template', tpl), field('Healthcare-platform category', cat), field('Source', src),
      field('Cohort', prof, 'Demographics and scripted values from the cohort'), field('Age (years)', age, 'for golden / pasted payloads'), field('Sex', sex), field('Pregnancy', preg),
      field('Site altitude (m)', alt), field('Consent on file', consent),
      field('Derived values', derivedOn, 'eGFR (CKD-EPI 2021), BMI, anion gap, corrected Ca – labelled "derived – verify" (default OFF per configuration)'),
      field('Previous values (delta check)', previous, 'KB entry → previous value in KB unit and minutes ago')),
    field('Payload', payload), button('Validate & render', run)), out);
  run();
}

function renderValidation(out, r) {
  out.append(h('div', { class: 'kpis' }, kpi('Verdict', r.verdict), kpi('Errors', r.errors), kpi('Warnings', r.warnings), kpi('Age band', r.ageBand, r.demographics),
    kpi('Category', r.category || 'generic', r.interpretationPolicy)));
  if (r.criticalHandling) out.append(h('p', { class: 'muted' }, 'Critical values: ' + r.criticalHandling));
  out.append(card('Mapped output template – ' + r.templateId + ' ' + r.templateName,
    table([{ label: 'Field', render: f => h('span', null, f.label, f.mandatory ? ' *' : '') }, { label: 'Code', key: 'code' },
      { label: 'Value', render: f => f.present ? String(f.value) + ' ' + (f.unit || '') : h('span', { class: 'muted' }, 'not sent') },
      { label: 'Converted from', key: 'convertedFrom' },
      { label: 'Reference range', render: f => f.referenceRange ? h('span', { title: (f.referenceRange.notes || []).join('; ') }, f.referenceRange.text, h('small', { class: 'muted' }, ' ' + f.referenceRange.partition)) : '—' },
      { label: 'Flag', render: f => flagBadge(f.interpretation) }, { label: 'Grade', key: 'grade' },
      { label: 'Delta', render: f => f.delta ? h('span', { title: f.delta.threshold + ' within ' + f.delta.windowHours + ' h' }, badge(f.delta.flagged ? 'DELTA' : 'no delta', f.delta.flagged ? 'warn' : 'neutral'), ' Δ ' + f.delta.change) : '—' },
      { label: 'Source', render: f => f.referenceRange ? f.referenceRange.source : '—' }], r.fields, { compact: true })));
  if (r.derived) out.append(card('Derived values (annotation block – derived – verify)', table([{ label: 'Value', render: k => r.derived[k].name },
    { label: 'Result', render: k => r.derived[k].value + ' ' + r.derived[k].unit }, { label: 'Flag / grade', render: k => h('span', null, flagBadge(r.derived[k].interpretation), ' ', r.derived[k].grade || '') },
    { label: 'Formula', render: k => r.derived[k].formulaId + ' v' + r.derived[k].formulaVersion }, { label: 'Inputs', render: k => JSON.stringify(r.derived[k].inputs) },
    { label: 'Label', render: k => badge(r.derived[k].label, 'warn') }], Object.keys(r.derived), { compact: true })));
  else if (r.derivedValuesEnabled) out.append(h('p', { class: 'muted' }, 'Derived values enabled – no formula inputs in this payload (e.g. creatinine with known age and sex for eGFR).'));
  out.append(card('Validation layers', table([{ label: 'Layer', key: 'layer' }, { label: 'Status', render: l => badge(l.status) },
    { label: 'Findings', render: l => l.findings.length ? h('ul', { class: 'tight' }, l.findings.map(f => h('li', null, badge(f.severity), ' ', f.field, ': ', f.message))) : '—' }], r.layers, { compact: true })));
  if (r.blocked && r.blocked.length) out.append(card('Blocked (not posted)', pre(r.blocked)));
  out.append(h('div', { class: 'grid2' }, card('FHIR R4 as the host receives it', pre(r.outputs.FHIR)), card('HL7 v2.6 ORU^R01 (OBX-7 range, OBX-8 flag)', pre(r.outputs.HL7V2.split('\r').join('\n')))),
    card('Device payload (' + r.source + ')', pre(r.payload.split('\r').join('\n'))));
}

async function identify(box) {
  const drivers = await get('/api/drivers');
  const payload = h('textarea', { class: 'in mono', rows: 8, placeholder: 'Paste any device message – IntiMed identifies type, model, option and driver' });
  const sample = select(drivers.map(d => [d.ref, d.name]), drivers[0] && drivers[0].ref);
  const out = h('div');
  box.append(card('Identify a device from its payload',
    h('div', { class: 'form cols' }, field('Load a sample from', sample)),
    button('Load sample', async () => { const r = await guard(() => post('/api/intelligence/cohorts/simulate', { driver: sample.value, profile: 'adult-normal', seed: Date.now() % 1000 })); if (r) payload.value = r.payload; }, 'ghost'),
    field('Payload', payload),
    button('Identify', async () => { const r = await guard(() => post('/api/intelligence/identify', { payload: payload.value })); if (r) showId(clear(out), r); }),
    button('Run benchmark (all golden sessions)', async () => { const r = await guard(() => get('/api/intelligence/identify/benchmark')); if (r) clear(out).append(h('div', { class: 'kpis' }, kpi('Top-1 accuracy', Math.round(r.accuracy * 100) + ' %', r.top1 + '/' + r.total)),
      table([{ label: 'Driver', key: 'driver' }, { label: 'Identified as', key: 'identifiedAs' }, { label: 'Confidence', key: 'confidence' }, { label: '', render: x => badge(x.correct ? 'PASS' : 'FAIL') }], r.results, { compact: true })); }, 'ghost')), out);
}

function showId(out, r) {
  out.append(h('div', { class: 'kpis' }, kpi('Verdict', r.verdict), kpi('Confidence', Math.round(r.confidence * 100) + ' %', 'margin ' + r.margin),
    r.bestMatch ? kpi('Best match', (r.bestMatch.deviceTypes || []).join(', ') || r.bestMatch.family, r.bestMatch.name) : null));
  out.append(card('Candidates', table([{ label: 'Driver', key: 'driver' }, { label: 'Family', key: 'family' }, { label: 'Score', key: 'score' }, { label: 'Models', key: 'models' },
    { label: 'Options', key: 'options' }, { label: 'Device type', key: 'deviceTypes' }, { label: 'Evidence', render: c => h('ul', { class: 'tight' }, c.evidence.map(e => h('li', null, e))) }], r.candidates, { compact: true })));
  if (r.integrationProfile) out.append(profileCard(r.integrationProfile));
}

function profileCard(p) {
  return card('Integration profile – ' + p.manufacturer + ' ' + p.model + ' (' + p.modelId + ')',
    h('div', { class: 'kpis' }, kpi('Device type', p.deviceType.code || '—', p.deviceType.name), kpi('Data class', p.deviceType.dataClass || '—', p.deviceType.ieee11073Specialization || ''),
      kpi('Type basis', p.deviceType.basis, 'confidence ' + p.deviceType.confidence)),
    h('p', null, h('b', null, 'Vendor connectivity: '), p.vendorConnectivity), h('p', null, h('b', null, 'Vendor protocol: '), p.vendorProtocol),
    table([{ label: 'Option', key: 'option' }, { label: 'Protocol', key: 'protocol' }, { label: 'Transport', key: 'transport' }, { label: 'Grammar', key: 'grammar' }], p.mechanisms, { compact: true }),
    table([{ label: 'Driver', key: 'ref' }, { label: 'Capture', key: 'captureMode' }, { label: 'Bidirectional', key: 'bidirectional' }, { label: 'Signature', render: d => badge(d.signature) },
      { label: 'Mapping precision', render: d => Math.round(d.mappingPrecision.score * 100) + ' % (' + d.mappingPrecision.standardCoded + ' coded, ' + d.mappingPrecision.ucumUnits + ' UCUM, ' + d.mappingPrecision.referenceIntervals + ' ref. ranges / ' + d.mappingPrecision.numericKeys + ')' },
      { label: 'Templates', key: 'templates' }], p.drivers, { compact: true }));
}

async function automap(box) {
  const [drivers, tpls, term] = await Promise.all([get('/api/drivers'), get('/api/templates'), get('/api/intelligence/terminology/status').catch(() => null)]);
  if (term) box.append(h('p', { class: 'muted' }, 'Terminology server adapter ($lookup / $validate-code / $translate): ', badge(term.enabled ? 'ENABLED' : 'DISABLED', term.enabled ? 'ok' : 'neutral'),
    ' ', term.enabled ? (term.host + ' · cache ' + term.cacheEntries + ' · calls ' + term.calls) : (term.reason || ''), ' – used as an extra signal for host fields coded in systems IntiMed does not know.'));
  const drv = select(drivers.map(d => [d.ref, d.name]), 'vitals.welchallyn.csm-pcd01@1.0.0');
  const tpl = select(tpls.map(t => [t.id, t.id + ' ' + t.name]), 'TPL-02');
  const out = h('div');
  box.append(card('Suggest bindings for any template (reference, imported FHIR Questionnaire, host schema)',
    h('div', { class: 'form cols' }, field('Driver', drv), field('Template', tpl)),
    button('Suggest', async () => {
      const r = await guard(() => post('/api/intelligence/automap', { driver: drv.value, templateId: tpl.value }));
      if (!r) return;
      clear(out).append(r.againstReference ? h('div', { class: 'kpis' }, kpi('Reference bindings reproduced', r.againstReference.reproduced + '/' + r.againstReference.referenceBindings), kpi('Extra suggestions', r.againstReference.extra)) : '',
        table([{ label: 'Template field', render: s => s.label + ' (' + s.field + ')' }, { label: 'Driver key', key: 'key' }, { label: 'Confidence', key: 'confidence' }, { label: 'Status', render: s => badge(s.status === 'AUTO' ? 'ACCEPTED' : s.status === 'REVIEW' ? 'REVIEW' : 'OPEN') }, { label: 'Why', key: 'why' }], r.suggestions, { compact: true }),
        card('Binding (paste into provisioning step 5)', pre(r.binding)));
    }),
    button('Benchmark against all signed bindings', async () => { const r = await guard(() => get('/api/intelligence/automap/benchmark')); if (r) clear(out).append(h('div', { class: 'kpis' }, kpi('Recall (coded)', Math.round(r.recall * 100) + ' %', r.reproduced + '/' + r.referenceBindings), kpi('Recall (labels only)', Math.round(r.labelOnlyRecall * 100) + ' %', 'codes stripped')),
      table([{ label: 'Driver', key: 'driver' }, { label: 'Template', key: 'template' }, { label: 'Reference', key: 'reference' }, { label: 'Reproduced', key: 'reproduced' }, { label: 'Extra', key: 'extra' }], r.pairs, { compact: true })); }, 'ghost')), out);
}

async function cohorts(box) {
  const [list, drivers] = await Promise.all([get('/api/intelligence/cohorts'), get('/api/drivers')]);
  const prof = select(list.map(p => [p.id, p.name]), 'infant-hr-140');
  const drv = select(drivers.map(d => [d.ref, d.name]), 'monitor.philips.intellivue-pcd01@1.0.0');
  const arts = ['NOISE', 'SPIKE', 'DRIFT', 'DROPOUT', 'DUPLICATE', 'CLOCK_SKEW'].map(a => h('label', { class: 'chk' }, h('input', { type: 'checkbox', value: a }), ' ', a));
  const out = h('div');
  prof.addEventListener('change', async () => { const ds = await get('/api/intelligence/cohorts/' + prof.value + '/drivers'); if (ds.length) drv.value = ds[0]; });
  box.append(card('Physiology-aware simulator – ' + list.length + ' cohorts (neonate → older adult, pregnancy, altitude, fasting, occupational regulation)',
    h('div', { class: 'form cols' }, field('Cohort', prof), field('Driver', drv)), h('div', { class: 'checks' }, arts),
    button('Simulate', async () => {
      const r = await guard(() => post('/api/intelligence/cohorts/simulate', { driver: drv.value, profile: prof.value, artefacts: arts.map(a => a.querySelector('input')).filter(i => i.checked).map(i => i.value), seed: 42 }));
      if (!r) return;
      clear(out).append(h('div', { class: 'kpis' }, kpi('Expected flags met', r.expectedMatched + '/' + r.expectedTotal), kpi('Age band', r.ageBand, r.demographics), kpi('Artefacts', r.artefacts.join(', ') || 'none')),
        r.expected.length ? table([{ label: 'Concept', key: 'concept' }, { label: 'Expected', key: 'expected' }, { label: 'Actual', key: 'actual' }, { label: 'Field', key: 'field' }, { label: '', render: x => badge(x.match ? 'PASS' : 'FAIL') }], r.expected, { compact: true }) : '',
        card('Value generation', table([{ label: 'Key', key: 'key' }, { label: 'Concept', key: 'concept' }, { label: 'Basis', key: 'basis' }, { label: 'Value (KB unit)', key: 'kbValue' }, { label: 'Device value', key: 'deviceValue' }], r.generation, { compact: true })),
        r.clockSkew ? h('p', { class: 'muted' }, r.clockSkew) : '', r.duplicateOf ? h('p', { class: 'muted' }, r.duplicateOf) : '',
        h('div', { class: 'grid2' }, card('Device payload', pre(r.payload.split('\r').join('\n'))), card('Dry-run result', pre(r.dryRun))));
    }),
    button('Run all cohorts (precision report)', async () => { const r = await guard(() => get('/api/intelligence/cohorts/run-all')); if (r) clear(out).append(h('div', { class: 'kpis' }, kpi('Flag precision', Math.round(r.precision * 100) + ' %', r.matched + '/' + r.total)),
      table([{ label: 'Cohort', key: 'name' }, { label: 'Driver', key: 'driver' }, { label: 'Matched', render: x => x.matched + '/' + x.total }, { label: '', render: x => badge(x.matched === x.total ? 'PASS' : 'FAIL') }], r.cohorts, { compact: true })); }, 'ghost')), out);
}

async function ranges(box) {
  const kb = await get('/api/intelligence/reference-ranges');
  const entry = select(kb.entries.map(e => [e.id, e.name + ' (' + e.unit + ')']), 'heart-rate');
  const value = input({ type: 'number', value: '140', step: 'any' }), age = input({ type: 'number', value: '0.5', step: '0.01' }), sex = select(['male', 'female'], 'male');
  const preg = select([['', 'not pregnant'], ['1', 'T1'], ['2', 'T2'], ['3', 'T3']], ''), alt = input({ type: 'number', value: '0' }), fasting = h('input', { type: 'checkbox' });
  const out = h('div');
  box.append(h('p', { class: 'note' }, kb.disclaimer),
    card('Evaluate a value for a person',
      h('div', { class: 'form cols' }, field('Parameter', entry), field('Value', value), field('Age (years)', age, '0.02 ≈ 1 week, 0.5 = 6 months'), field('Sex', sex), field('Pregnancy', preg),
        field('Site altitude (m)', alt), field('Fasting', fasting)),
      button('Evaluate', async () => {
        const d = { ageYears: Number(age.value), sex: sex.value, siteAltitudeM: Number(alt.value), fasting: fasting.checked };
        if (preg.value) Object.assign(d, { pregnant: true, trimester: Number(preg.value) });
        const r = await guard(() => post('/api/intelligence/reference-ranges/evaluate', { entry: entry.value, value: Number(value.value), demographics: d }));
        if (r) clear(out).append(h('div', { class: 'kpis' }, kpi('Interval', r.text, r.partition), kpi('Flag', r.interpretation ? r.interpretation.code : '—', r.interpretation ? r.interpretation.display : ''), kpi('Grade', r.grade || '—'), kpi('Age band', r.ageBand)),
          r.notes.length ? h('ul', null, r.notes.map(n => h('li', null, n))) : '', h('p', { class: 'muted' }, 'Source: ' + r.source));
      }), out),
    card('Knowledge base – ' + kb.entries.length + ' parameters, ' + kb.ageBands.length + ' age bands',
      table([{ label: 'Parameter', key: 'name' }, { label: 'Unit', key: 'unit' }, { label: 'Partitions', key: 'partitions' }, { label: 'Source', key: 'source' }, { label: 'Gap note', key: 'gapNote' }], kb.entries, { compact: true })),
    card('Sources', table([{ label: 'Key', render: s => s[0] }, { label: 'Reference', render: s => s[1] }], Object.entries(kb.sources), { compact: true })));
}

async function categories(box) {
  const cats = await get('/api/intelligence/categories');
  box.append(...cats.map(c => card(c.name + ' (' + c.id + ')', h('p', null, h('b', null, 'Hosts: '), c.hosts),
    h('p', null, h('b', null, 'Templates: '), c.templates.join(', '), ' · ', h('b', null, 'Interpretation: '), c.interpretation),
    h('p', null, h('b', null, 'Validation rule pack: '), c.rules.map(r => badge(r, 'neutral'))),
    table([{ label: 'Output', key: 'format' }, { label: 'Profile', key: 'profile' }], c.outputs, { compact: true }),
    h('p', { class: 'muted' }, c.criticalHandling))));
}

async function types(box) {
  const t = await get('/api/intelligence/device-types');
  const model = input({ value: 'IMD-201' }), out = h('div');
  box.append(h('div', { class: 'kpis' }, kpi('Device types', t.types.length), kpi('Models classified', t.models), kpi('Rule-matched', t.ruleMatched), kpi('Family default', t.familyDefault)),
    card('Integration profile for a model', h('div', { class: 'form cols' }, field('Model id (IMD-…)', model)),
      button('Show profile', async () => { const p = await guard(() => get('/api/intelligence/profiles/' + encodeURIComponent(model.value.trim()))); if (p) clear(out).append(profileCard(p)); }), out),
    card('Taxonomy', table([{ label: 'Code', key: 'code' }, { label: 'Name', key: 'name' }, { label: 'Data class', key: 'dataClass' }, { label: 'IEEE 11073', key: 'ieee11073Specialization' }, { label: 'Models', key: 'models' }], t.types, { compact: true })));
}

async function trajectories(box) {
  const [list, drivers] = await Promise.all([get('/api/intelligence/cohorts'), get('/api/drivers')]);
  const trajs = list.filter(p => p.trajectory);
  const prof = select(trajs.map(p => [p.id, p.name]), trajs[0] && trajs[0].id);
  const drv = select([['', '(best covering driver)'], ...drivers.map(d => [d.ref, d.name])], '');
  const iv = input({ type: 'number', value: '30', min: '1', max: '240' }), seed = input({ type: 'number', value: '42' });
  const stream = h('input', { type: 'checkbox' }), cfg = input({ placeholder: 'published configuration id (DEVICE_ADMIN)' }), subj = input({ placeholder: 'subject id for streaming (optional)' });
  const out = h('div');
  box.append(card('Time-series physiology – keyframes interpolated piecewise-linearly with seeded noise, replayed through a driver',
    h('div', { class: 'form cols' }, field('Trajectory', prof), field('Driver', drv), field('Interval (min)', iv), field('Seed', seed),
      field('Stream into a published configuration', stream, 'live pipeline, steps re-timed to now'), field('Configuration', cfg), field('Subject', subj)),
    button('Render trajectory', async () => {
      const body = { profile: prof.value, driver: drv.value || null, intervalMinutes: Number(iv.value), seed: Number(seed.value), stream: stream.checked };
      if (stream.checked) Object.assign(body, { configId: cfg.value.trim(), subject: subj.value.trim() || null });
      const r = await guard(() => post('/api/intelligence/cohorts/trajectory', body));
      if (!r) return;
      const concepts = r.coveredConcepts;
      clear(out).append(h('div', { class: 'kpis' }, kpi('Steps', r.steps, 'every ' + r.intervalMinutes + ' min'), kpi('Driver', r.captureMode, r.driver),
        kpi('Max NEWS2 (informational)', r.summary.maxNews2), kpi('First abnormal flag', r.summary.firstAbnormalFlagMinute === undefined ? '—' : r.summary.firstAbnormalFlagMinute + ' min')),
        h('p', { class: 'note' }, r.disclaimer),
        r.notCoveredByDriver.length ? h('p', { class: 'muted' }, 'Not expressed by this driver: ' + r.notCoveredByDriver.join(', ')) : '',
        table([{ label: 'Minute', key: 'minute' }, ...concepts.map(c => ({ label: c, render: s => h('span', null, String(s.values[c] === undefined ? '—' : s.values[c]), ' ', s.flags[c] ? flagBadge({ code: s.flags[c], display: '' }) : '') })),
          { label: 'NEWS2', render: s => h('span', null, String(s.news2.score), ' ', badge(s.news2.band, s.news2.band === 'high' ? 'bad' : s.news2.band === 'low' ? 'ok' : 'warn')) }], r.series, { compact: true }),
        r.stream ? card('Streamed into ' + cfg.value, table([{ label: 'Step', key: 'step' }, { label: 'Pipeline status', render: x => badge(x.status) }, { label: 'ACK', key: 'ack' }], r.stream, { compact: true })) : '');
    })), out);
}

async function intervals(box) {
  const kb = await get('/api/intelligence/reference-ranges');
  const out = h('div'), detail = h('div');
  const csv = h('textarea', { class: 'in mono', rows: 6 });
  csv.value = 'entry,ageFrom,ageTo,sex,pregnant,trimester,low,high,criticalLow,criticalHigh,unit,source\npotassium,18,200,any,,,3.4,5.0,2.8,6.2,mmol/L,Local lab 2026';
  const od = h('textarea', { class: 'in mono', rows: 6, placeholder: 'FHIR R4 ObservationDefinition (or Bundle) JSON with qualifiedInterval' });
  const load = async () => {
    const list = await get('/api/intelligence/intervals');
    clear(out).append(table([{ label: 'Overlay', key: 'id' }, { label: 'Version', key: 'version' }, { label: 'Format', key: 'format' }, { label: 'Source', key: 'source' },
      { label: 'Status', render: o => badge(o.status) }, { label: 'Imported by', key: 'importedBy' }, { label: 'Approved by', key: 'approvedBy' },
      { label: 'Partitions verified', render: o => o.partitions.filter(p => p.verified).length + '/' + o.partitions.length },
      { label: '', render: o => button('Open', () => show(o.id), 'ghost') }], list, { compact: true, empty: 'No tenant overlay – knowledge-base defaults apply.' }));
  };
  const show = async (id) => {
    const o = await get('/api/intelligence/intervals/' + encodeURIComponent(id));
    const res = h('textarea', { class: 'in mono', rows: 3, placeholder: '≥ 20 local reference results, comma or newline separated' });
    const part = select(o.partitions.map(p => [p.entry + '#' + p.index, p.entry + ' – ' + p.partition + (p.verified ? ' ✓' : '')]), o.partitions[0] && (o.partitions[0].entry + '#' + o.partitions[0].index));
    const comment = input({ placeholder: 'approval comment' });
    const act = (path, body, msg) => async () => { if (await guard(() => post('/api/intelligence/intervals/' + encodeURIComponent(id) + path, body ? body() : {}), msg)) { await load(); await show(id); } };
    clear(detail).append(card('Overlay ' + o.id + ' – v' + o.version + ' (' + o.status + ')',
      h('p', { class: 'muted' }, 'DRAFT → VERIFIED (CLSI EP28-A3c: ≤ 10 % of ≥ 20 local results outside each partition) → APPROVED (four-eyes: REGULATORY / TENANT_ADMIN / PHYSICIAN, not the importer) → ACTIVE'),
      table([{ label: 'Entry', render: p => p.entry }, { label: 'Partition', key: 'partition' }, { label: 'Interval', render: p => { const r = o.entries[p.entry][p.index]; return (r.low ?? '—') + '–' + (r.high ?? '—') + ' (crit ' + (r.criticalLow ?? '—') + ' / ' + (r.criticalHigh ?? '—') + ')'; } },
        { label: 'EP28', render: p => badge(p.verified ? 'VERIFIED' : 'OPEN') }], o.partitions, { compact: true }),
      (o.verification || []).length ? table([{ label: 'Partition', render: v => v.entry + ' ' + v.partition }, { label: 'n', key: 'n' }, { label: 'Outside', render: v => v.outside + ' (allowed ' + v.allowedOutside + ')' },
        { label: 'Result', render: v => badge(v.pass ? 'PASS' : 'FAIL') }, { label: 'By', key: 'by' }], o.verification, { compact: true }) : '',
      h('div', { class: 'form cols' }, field('Partition', part), field('Local reference results', res), field('Comment', comment)),
      button('Verify partition (EP28)', act('/verify', () => { const [entry, index] = part.value.split('#'); return { entry, index: Number(index), results: res.value.split(/[\s,;]+/).filter(Boolean).map(Number) }; }, 'Verification recorded'), 'ghost'),
      button('Approve (four-eyes)', act('/approve', () => ({ comment: comment.value }), 'Approved'), 'ghost'),
      button('Activate', act('/activate', null, 'Overlay active – live annotation now uses it'), 'primary'),
      button('Retire', act('/retire', () => ({ reason: comment.value || 'retired' }), 'Retired'), 'ghost')));
  };
  box.append(h('p', { class: 'note' }, kb.disclaimer),
    card('Import laboratory intervals (tenant overlay – versioned, four-eyes)',
      field('CSV (' + 'entry,ageFrom,ageTo,sex,pregnant,trimester,low,high,criticalLow,criticalHigh,unit,source' + ')', csv),
      button('Import CSV', async () => { const r = await guard(() => post('/api/intelligence/intervals/import/csv', { csv: csv.value }), 'Draft overlay created'); if (r) { await load(); await show(r.id); } }),
      field('FHIR ObservationDefinition', od),
      button('Import ObservationDefinition', async () => { let j; try { j = JSON.parse(od.value); } catch (e) { toast('Invalid JSON', 'err'); return; } const r = await guard(() => post('/api/intelligence/intervals/import/observation-definition', j), 'Draft overlay created'); if (r) { await load(); await show(r.id); } }, 'ghost')),
    card('Overlays', out), detail);
  await load();
}

async function drift(box) {
  const out = h('div');
  const decide = (a, kind) => async () => {
    const cals = await guard(() => get('/api/intelligence/drift/alerts/' + encodeURIComponent(a.id) + '/calibrations'));
    const note = input({ placeholder: kind === 'ack' ? 'acknowledgement note (required)' : 'resolution note (required)' });
    const cal = select([['', '(no calibration record)'], ...(cals || []).map(c => [c.at, c.at + ' – ' + (c.passed ? 'passed' : 'failed') + ' by ' + c.by + (c.note ? ' (' + c.note + ')' : '')])], '');
    modal(kind === 'ack' ? 'Acknowledge drift alert' : 'Resolve drift alert', h('div', { class: 'form' }, field('Note', note), field('Link calibration record', cal, 'calibration checks of configuration ' + a.configId)),
      close => [button(kind === 'ack' ? 'Acknowledge' : 'Resolve', async () => {
        if (await guard(() => post('/api/intelligence/drift/alerts/' + encodeURIComponent(a.id) + '/' + kind, { note: note.value, calibrationAt: cal.value || null }), kind === 'ack' ? 'Acknowledged (SIEM forwarded)' : 'Resolved (SIEM forwarded)')) { close(); await load(); }
      }), button('Cancel', close, 'ghost')]);
  };
  const load = async () => clear(out).append(table([{ label: 'Configuration', key: 'configId' }, { label: 'Key', key: 'key' }, { label: 'Mean', key: 'mean' }, { label: 'Fleet mean', key: 'fleetMean' }, { label: 'z', key: 'z' },
    { label: 'Abnormal share', key: 'abnormalShare' }, { label: 'Advice', key: 'advice' }, { label: 'Status', render: a => h('span', { title: a.resolutionNote || a.acknowledgementNote || '' }, badge(a.status || 'OPEN'), a.resolution === 'AUTO' ? ' auto' : '') },
    { label: 'Calibration', render: a => a.calibration ? a.calibration.at + (a.calibration.passed ? ' ✓' : ' ✗') : '—' },
    { label: '', render: a => h('span', null, (a.status || 'OPEN') === 'OPEN' ? button('Acknowledge', decide(a, 'ack'), 'ghost') : '', (a.status || 'OPEN') !== 'RESOLVED' ? button('Resolve', decide(a, 'resolve'), 'ghost') : '') }],
    await get('/api/intelligence/drift/alerts'), { compact: true, empty: 'No drift alerts.' }));
  box.append(card('Data-quality drift (AN-08) – per configuration vs. fleet baseline; a biomedical signal, never a clinical alarm',
    button('Analyse now', async () => { const r = await guard(() => post('/api/intelligence/drift/analyse')); if (r) { await load(); out.prepend(h('div', { class: 'kpis' }, kpi('Signals', r.signals.length), kpi('Alerts', r.alerts))); } })), out);
  await load();
}
