import { h, clear, get, post, put, guard, badge, table, card, kpi, field, input, select, button, modal, pre, fmt } from '../lib.js';
import { has } from '../app.js';

export async function catalogue(main) {
  const [stats, fams] = await Promise.all([get('/api/catalogue/stats'), get('/api/catalogue/families')]);
  const q = input({ placeholder: 'Search brand, model, IMD id, FDA code, protocol…', 'aria-label': 'Search' });
  const fam = select([['', 'All families'], ...fams.map(f => [f.family, f.family + ' (' + f.entries + ')'])], '', { 'aria-label': 'Family' });
  const opt = select([['', 'All options'], ...['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7', 'P8', 'P9', 'P10', 'P11'].map(o => [o, o])], '', { 'aria-label': 'Connectivity option' });
  const set = select([['', 'All settings'], 'ACUTE', 'OPD', 'OH', 'LAB', 'IMG', 'RPM', 'SPEC'], '', { 'aria-label': 'Setting' });
  const out = h('div');
  const run = async () => {
    const p = new URLSearchParams({ q: q.value, family: fam.value, option: opt.value, setting: set.value, limit: 250 });
    const rows = await get('/api/catalogue/models?' + p);
    clear(out).append(h('p', { class: 'muted' }, rows.length + ' models'), table([
      { label: 'ID', key: 'id' }, { label: 'Model', key: 'model' }, { label: 'Manufacturer', key: 'manufacturer' }, { label: 'Family', key: 'family' },
      { label: 'Options', render: r => r.options.join(' ') }, { label: 'FDA', render: r => r.fdaCodes.join(' ') }, { label: 'Confidence', render: r => badge(r.confidence) },
      { label: 'Certified drivers', render: r => r.certifiedDrivers ? badge(r.certifiedDrivers + ' certified', 'ok') : '—' }], rows, { onRow: r => detail(r.id), rowClass: () => 'click' }));
  };
  [q, fam, opt, set].forEach(e => e.addEventListener(e.tagName === 'INPUT' ? 'input' : 'change', () => run()));
  main.append(h('h1', null, 'Universal device catalogue'),
    h('div', { class: 'kpis' }, kpi('Devices (seed)', stats.devices), kpi('Families', stats.families), kpi('FDA product codes', stats.fdaCodes),
      kpi('510(k) clearances', stats.k510.toLocaleString()), kpi('Current listings', stats.listings.toLocaleString()), kpi('PMAs', stats.pma.toLocaleString(), stats.snapshot)),
    h('div', { class: 'toolbar' }, q, fam, opt, set, button('Request a model', requestModel, 'secondary')), out);
  run();
}

async function detail(id) {
  const m = await get('/api/catalogue/models/' + id);
  const body = h('div', { class: 'detail' },
    h('p', null, h('b', null, m.manufacturer), ' · ', m.family, ' · settings ', m.settings.join(', ')),
    h('p', { class: 'muted' }, m.use),
    h('h4', null, 'Connectivity matrix (verification state)'),
    table([{ label: 'Option', key: 'option' }, { label: 'State', render: c => badge(c.state, c.state === 'CERTIFIED_IN_LAB' ? 'ok' : c.state === 'UNVERIFIED' ? 'bad' : 'warn') },
      { label: 'Confidence', key: 'confidence' }, { label: 'Basis', key: 'basis' }], m.connectivity, { compact: true }),
    h('h4', null, 'Certified driver packages'),
    table([{ label: 'Driver', key: 'ref' }, { label: 'Channel', render: d => badge(d.channel) }, { label: 'Signature', render: d => badge(d.signature) }, { label: 'Publisher', key: 'publisher' },
      { label: 'Options', render: d => d.options.join(' ') }, { label: 'Templates', render: d => d.templates.join(' ') }], m.drivers, { compact: true, empty: 'No certified driver yet – use the driver SDK or request the model.' }),
    h('h4', null, 'Regulatory status (FDA, openFDA snapshot 19 Oct 2026)'),
    table([{ label: 'Code', key: 'code' }, { label: 'Classification', key: 'name' }, { label: 'Class', key: 'deviceClass' }, { label: 'Regulation', key: 'regulation' },
      { label: '510(k)', key: 'k510Total' }, { label: '2021–26', key: 'k510Recent' }, { label: 'Listings', key: 'listings' }, { label: 'PMA', key: 'pma' }], m.fda, { compact: true }),
    h('h4', null, 'Vendor security posture (MDS2 / SBOM / EOL)'),
    h('p', null, badge(m.posture.status), ' MDS2 ', badge(m.posture.mds2), ' SBOM ', badge(m.posture.sbom), m.posture.eol ? ' EOL ' + m.posture.eol : ''),
    m.recalls.length ? h('div', null, h('h4', null, 'Recalls & safety communications'), table([{ label: 'Title', key: 'title' }, { label: 'Source', key: 'source' }, { label: 'Status', render: r => badge(r.status) },
      { label: '', render: r => button('Acknowledge', () => guard(() => post('/api/catalogue/recalls/' + r.id + '/ack', { note: 'reviewed' }), 'Acknowledged'), 'ghost') }], m.recalls, { compact: true })) : null,
    h('p', { class: 'muted small' }, 'Protocol / software: ', m.protocol, ' · Templates: ', m.templates, ' · Inventory sprint ', m.sprint));
  modal(m.id + ' · ' + m.model, body, (close) => [
    has('SECURITY_ANALYST', 'CISO_DPO') ? button('Record posture', () => posture(m.id), 'secondary') : null,
    has('DEVICE_ADMIN') && m.drivers.length ? button('Provision this model', async () => {
      const c = await guard(() => post('/api/console/configs', { modelId: m.id, name: m.model }));
      close(); location.hash = '#/wizard/' + c.id;
    }) : null]);
}

function posture(id) {
  const mds2 = select(['RECEIVED', 'MISSING', 'REQUESTED'], 'RECEIVED');
  const sbom = select(['RECEIVED', 'MISSING', 'REQUESTED'], 'RECEIVED');
  const eol = input({ type: 'date' });
  const vulns = input({ type: 'number', value: 0, min: 0 });
  modal('Vendor posture – ' + id, h('div', { class: 'form' }, field('MDS2 form', mds2), field('SBOM', sbom), field('End of life / support', eol), field('Known exploitable vulnerabilities', vulns)),
    (close) => [button('Save', async () => { await guard(() => put('/api/catalogue/models/' + id + '/posture', { mds2: mds2.value, sbom: sbom.value, eol: eol.value || null, knownVulnerabilities: +vulns.value }), 'Posture recorded'); close(); })]);
}

function requestModel() {
  const man = input(), mod = input(), udi = input(), opt = select(['', 'P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7', 'P8', 'P9', 'P10', 'P11'], ''), why = input();
  modal('Request a model', h('div', { class: 'form' }, field('Manufacturer', man), field('Model', mod), field('UDI-DI (optional)', udi), field('Preferred option', opt), field('Clinical use / reason', why)),
    (close) => [button('Submit request', async () => { await guard(() => post('/api/catalogue/requests', { manufacturer: man.value, model: mod.value, udi: udi.value, option: opt.value, reason: why.value }), 'Request submitted'); close(); })]);
}
