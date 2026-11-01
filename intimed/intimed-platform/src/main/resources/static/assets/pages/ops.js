import { h, clear, get, post, guard, badge, table, card, kpi, field, input, select, button, modal, pre, fmt, tabs } from '../lib.js';
import { has } from '../app.js';

export async function fleetPage(main) {
  const rows = await get('/api/console/fleet');
  const by = rows.reduce((m, r) => (m[r.health] = (m[r.health] || 0) + 1, m), {});
  main.append(h('h1', null, 'Fleet & calibration'),
    h('div', { class: 'kpis' }, kpi('Devices', rows.length), kpi('Healthy', by.GREEN || 0), kpi('Attention', by.AMBER || 0), kpi('Critical', by.RED || 0)),
    table([{ label: 'Health', render: r => badge(r.health) }, { label: 'Device', key: 'name' }, { label: 'Site / station / bed', render: r => [r.site, r.room, r.station, r.bed].filter(Boolean).join(' / ') },
      { label: 'Driver', key: 'driver' }, { label: 'Stage', key: 'stage' }, { label: 'Endpoint', render: r => r.endpoint ? r.endpoint + ' ' : '—', }, { label: 'Endpoint status', render: r => r.endpointStatus ? badge(r.endpointStatus) : '—' },
      { label: 'Calibration', render: r => h('span', null, badge(r.calibration), ' due ', r.calibrationDue || '—') }, { label: 'Dead letters', key: 'dlqOpen' },
      { label: 'Actions', render: r => h('span', null,
        button('Check ✓', () => guard(() => post('/api/console/configs/' + r.configId + '/calibration', { passed: true, note: 'two-tap check' }), 'Calibration check passed').then(() => location.reload()), 'small ghost'),
        button('Fail ✗', () => guard(() => post('/api/console/configs/' + r.configId + '/calibration', { passed: false, note: 'failed check' }), 'Device locked out').then(() => location.reload()), 'small ghost'),
        has('DEVICE_ADMIN') ? button('Release', () => guard(() => post('/api/console/configs/' + r.configId + '/calibration/release', { reason: 'Recalibrated by biomed' }), 'Released').then(() => location.reload()), 'small ghost') : null,
        has('DEVICE_ADMIN', 'INTEGRATION_DEV', 'SECURITY_ANALYST') ? button('Diagnostics', () => diagnostics(r.configId), 'small ghost') : null) }], rows));
}

async function diagnostics(id) {
  const d = await get('/api/console/configs/' + id + '/diagnostics');
  modal('Diagnostics (PHI-masked) – ' + id, h('div', null,
    h('h4', null, 'Recent payloads'), table([{ label: 'Received', key: 'receivedAt' }, { label: 'Mode', key: 'mode' }, { label: 'SHA-256', render: p => p.sha256.slice(0, 16) + '…' },
      { label: 'Masked payload', render: p => h('pre', { class: 'code small' }, p.payloadMasked.replace(/\r/g, '\n').slice(0, 600)) },
      { label: '', render: p => button('Re-send', () => guard(() => post('/api/console/raw/' + p.sha256 + '/resend'), 'Re-sent (idempotent)'), 'small ghost') }], d.payloads, { compact: true }),
    h('h4', null, 'Clock offsets corrected'), pre(d.clockOffsets), h('h4', null, 'Dead letters'), pre(d.deadLetters)));
}

export async function opsPage(main) {
  main.append(h('h1', null, 'Queues & association context'));
  const box = h('div');
  main.append(box);
  const clinical = has('CLINICIAN', 'PHYSICIAN', 'TEMPLATE_OWNER');
  if (clinical) {
    const um = await get('/api/ops/unmatched');
    const subjects = await get('/api/context/subjects');
    box.append(card('Unmatched queue', table([{ label: 'Created', key: 'createdAt' }, { label: 'Station', key: 'station' }, { label: 'Typed identifier', key: 'typedIdentifier' }, { label: 'Reason', key: 'reason' },
      { label: 'Confidence', key: 'confidence' }, { label: 'Suggestion', key: 'suggestion' }, { label: 'Resolve', render: u => {
        const sel = select(subjects.map(s => [s.id, s.id + ' ' + s.name]), u.suggestion || '');
        return h('span', null, sel, button('Assign', () => guard(() => post('/api/ops/unmatched/' + u.id + '/resolve', { subject: sel.value }), 'Resolved and filed').then(() => location.reload()), 'small'));
      } }], um, { empty: 'Unmatched queue is empty.' })));
    const beds = await get('/api/context/beds');
    const bed = input({ placeholder: 'BED-07' }), subj = select(subjects.map(s => [s.id, s.id]), ''), from = input({ placeholder: 'from bed (A02)' });
    box.append(card('ADT – encounter / bed binding (normally HL7 ADT over MLLP-TLS)', table([{ label: 'Bed', key: 'bed' }, { label: 'Subject', key: 'subject' }, { label: 'Encounter', key: 'encounter' }, { label: 'Since', key: 'since' }], beds, { compact: true }),
      h('div', { class: 'toolbar' }, bed, subj, from,
        button('A01 admit', () => guard(() => post('/api/context/adt', { event: 'A01', bed: bed.value, subject: subj.value }), 'Admitted').then(() => location.reload()), 'small'),
        button('A02 transfer', () => guard(() => post('/api/context/adt', { event: 'A02', fromBed: from.value, bed: bed.value }), 'Transferred').then(() => location.reload()), 'small ghost'),
        button('A03 discharge', () => guard(() => post('/api/context/adt', { event: 'A03', bed: bed.value }), 'Discharged').then(() => location.reload()), 'small ghost'))));
    const en = await get('/api/context/enrolments');
    box.append(card('RPM enrolments & consent', table([{ label: 'Device serial', key: 'deviceSerial' }, { label: 'Subject', key: 'subject' }, { label: 'Consent', render: e => badge(e.consent.given ? 'GIVEN' : 'WITHDRAWN') },
      { label: 'Scopes', render: e => (e.consent.scopes || []).join(' ') }, { label: 'Expires', render: e => fmt(e.consent.expires) },
      { label: '', render: e => e.consent.given ? button('Withdraw consent', () => guard(() => post('/api/context/enrolments/' + e.id + '/withdraw'), 'Consent withdrawn – further readings are refused and not stored').then(() => location.reload()), 'small ghost') : '' }], en, { compact: true })));
  }
  if (has('DEVICE_ADMIN', 'INTEGRATION_DEV')) {
    const dlq = await get('/api/ops/dlq');
    box.append(card('Dead-letter queue (triage)', table([{ label: 'Created', key: 'createdAt' }, { label: 'Stage', key: 'stage' }, { label: 'Error', key: 'error' }, { label: 'Option', key: 'option' },
      { label: 'Masked trace', render: d => h('pre', { class: 'code small' }, (d.traceMasked || '').replace(/\r/g, '\n').slice(0, 300)) },
      { label: '', render: d => h('span', null, button('Replay', () => guard(() => post('/api/ops/dlq/' + d.id + '/replay'), 'Replayed').then(() => location.reload()), 'small'),
        button('Discard', () => guard(() => post('/api/ops/dlq/' + d.id + '/discard'), 'Discarded').then(() => location.reload()), 'small ghost')) }], dlq, { empty: 'No open dead letters.' })));
  }
}

export async function templatesPage(main) {
  const tpls = await get('/api/templates');
  const box = h('div');
  main.append(h('h1', null, 'Template definitions & bindings'), h('p', { class: 'lead' }, 'Host-agnostic Template Definitions (TPL-01…TPL-22 + host imports). Bindings are many-to-many between device models and templates.'),
    h('div', { class: 'toolbar' }, has('TEMPLATE_OWNER') ? button('Import FHIR Questionnaire', importQ, 'secondary') : null), box);
  box.append(table([{ label: 'ID', key: 'id' }, { label: 'Template', key: 'name' }, { label: 'Settings', render: t => (t.settings || []).join(', ') }, { label: 'Capture', key: 'captureMode' },
    { label: 'Association', key: 'association' }, { label: 'Fields', render: t => t.sections.reduce((n, s) => n + s.fields.length, 0) }, { label: 'Adapters', render: t => (t.adapters || []).join(' ') },
    { label: 'Origin', render: t => badge(t.origin || 'REFERENCE', 'neutral') }], tpls, { onRow: t => detail(t), rowClass: () => 'click' }));
}

async function detail(t) {
  const b = await get('/api/templates/' + t.id + '/bindings');
  modal(t.id + ' · ' + t.name, h('div', null,
    t.sections.map(s => h('div', null, h('h4', null, s.label), table([{ label: 'Field', render: f => f.label + (f.mandatory ? ' *' : '') }, { label: 'Type', key: 'type' }, { label: 'Unit (UCUM)', key: 'unit' },
      { label: 'Code', render: f => f.code ? Object.entries(f.code).map(([k, v]) => h('span', null, badge(k, k === 'local' ? 'warn' : 'neutral'), ' ', v)) : '—' },
      { label: 'Range', render: f => f.range ? (f.range.min ?? '') + '–' + (f.range.max ?? '') : '' }, { label: 'Verify', render: f => f.verify ? 'yes' : '' }], s.fields, { compact: true }))),
    h('h4', null, 'Device bindings'), table([{ label: 'Configuration', key: 'name' }, { label: 'Status', render: x => badge(x.status) }, { label: 'Driver', key: 'driver' }, { label: 'Fields bound', render: x => Object.keys(x.fields).length }], b, { compact: true, empty: 'No device bound yet.' })));
}

function importQ() {
  const ta = h('textarea', { class: 'in', rows: 14 }, JSON.stringify({ resourceType: 'Questionnaire', id: 'host-pre-op', title: 'Host pre-operative check', version: '1.0.0', item: [
    { linkId: 'hr', text: 'Heart rate', type: 'decimal', required: true, code: [{ system: 'http://loinc.org', code: '8867-4' }], extension: [{ url: 'http://hl7.org/fhir/StructureDefinition/questionnaire-unit', valueCoding: { code: '/min' } }] },
    { linkId: 'spo2', text: 'SpO2', type: 'decimal', required: true, code: [{ system: 'http://loinc.org', code: '59408-5' }], extension: [{ url: 'http://hl7.org/fhir/StructureDefinition/questionnaire-unit', valueCoding: { code: '%' } }] }] }, null, 2));
  modal('Import FHIR R4 Questionnaire', h('div', { class: 'form' }, ta), (close) => [button('Import', async () => { await guard(() => post('/api/templates/import/questionnaire', JSON.parse(ta.value)), 'Template imported'); close(); location.reload(); })]);
}
