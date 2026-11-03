// Intelligence studio (IntiMed 2.0 – IM-E11…IM-E15): validation studio, device identification, auto-mapping,
// physiology-aware cohorts, reference-interval knowledge base, platform categories and drift. No innerHTML.
import { h, clear, get, post, guard, badge, table, card, kpi, field, input, select, button, pre, tabs } from '../lib.js';

const TABS = [['validate', 'Validation studio'], ['identify', 'Device identification'], ['automap', 'Auto-mapping'], ['cohorts', 'Simulator cohorts'],
  ['ranges', 'Reference ranges'], ['categories', 'Platform categories'], ['types', 'Device types & profiles'], ['drift', 'Drift']];

export async function intelligencePage(main, [sub]) {
  const tab = sub || 'validate';
  main.append(h('h1', null, 'Intelligence studio'),
    h('p', { class: 'lead' }, 'See exactly what a device sends, what IntiMed maps it to, and how it is validated and flagged for each healthcare-platform category – with age-, sex- and context-partitioned reference ranges.'),
    tabs(TABS, tab, t => { location.hash = '#/intelligence/' + t; }));
  const box = h('div'); main.append(box);
  await ({ validate, identify, automap, cohorts, ranges, categories, types, drift }[tab] || validate)(box);
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
  const payload = h('textarea', { class: 'in mono', rows: 6, placeholder: 'Paste an HL7 v2 / ASTM / XML / JSON / GDT / serial payload' });
  const out = h('div');
  const refreshTpl = () => {
    const d = drivers.find(x => x.ref === drv.value);
    clear(tpl); (d ? d.templates : []).forEach(t => tpl.append(h('option', { value: t }, t)));
  };
  drv.addEventListener('change', refreshTpl); refreshTpl();
  const run = async () => {
    const body = { driver: drv.value, templateId: tpl.value, category: cat.value || null, context: { consent: consent.checked, siteAltitudeM: Number(alt.value) } };
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
      field('Site altitude (m)', alt), field('Consent on file', consent)),
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
      { label: 'Source', render: f => f.referenceRange ? f.referenceRange.source : '—' }], r.fields, { compact: true })));
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
  const [drivers, tpls] = await Promise.all([get('/api/drivers'), get('/api/templates')]);
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

async function drift(box) {
  const out = h('div');
  const load = async () => clear(out).append(table([{ label: 'Configuration', key: 'configId' }, { label: 'Key', key: 'key' }, { label: 'Mean', key: 'mean' }, { label: 'Fleet mean', key: 'fleetMean' }, { label: 'z', key: 'z' },
    { label: 'Abnormal share', key: 'abnormalShare' }, { label: 'Advice', key: 'advice' }, { label: 'Status', render: a => badge(a.status || 'OPEN') }], await get('/api/intelligence/drift/alerts'), { compact: true, empty: 'No drift alerts.' }));
  box.append(card('Data-quality drift (AN-08) – per configuration vs. fleet baseline; a biomedical signal, never a clinical alarm',
    button('Analyse now', async () => { const r = await guard(() => post('/api/intelligence/drift/analyse')); if (r) { await load(); out.prepend(h('div', { class: 'kpis' }, kpi('Signals', r.signals.length), kpi('Alerts', r.alerts))); } })), out);
  await load();
}
