// Partner programme & catalogue feeds: vendor submissions (IM-E02-BL-006), certification badges (IM-E07-BL-005),
// AccessGUDID / EUDAMED review queue (IM-E02-BL-003), AI explanations behind the policy gateway (IM-E03-BL-006) and the
// OpenAPI document + reference SDKs (IM-E07-BL-004). No innerHTML: text is always inserted as text nodes.
import { h, clear, get, post, put, guard, badge, table, card, kpi, field, input, select, button, modal, pre, tabs } from '../lib.js';
import { has } from '../app.js';

const REVIEWERS = ['DEVICE_ADMIN', 'REGULATORY'];

function visibleTabs() {
  const t = [];
  if (has('PARTNER_DEV', ...REVIEWERS, 'AUDITOR')) t.push(['submissions', 'Submissions']);
  t.push(['badges', 'Badges']);
  if (has(...REVIEWERS, 'AUDITOR')) t.push(['feeds', 'Register feeds']);
  if (has('INTEGRATION_DEV', 'DEVICE_ADMIN', 'TEMPLATE_OWNER', 'PARTNER_DEV', 'REGULATORY', 'TENANT_ADMIN')) t.push(['ai', 'AI explanations']);
  t.push(['api', 'API & SDKs']);
  return t;
}

export async function partnersPage(main, [sub]) {
  const items = visibleTabs();
  const tab = items.some(([id]) => id === sub) ? sub : items[0][0];
  main.append(h('h1', null, 'Partners & registers'),
    h('p', { class: 'lead' }, 'Vendor self-service submissions with harness evidence and four-eyes certification, partner badges, UDI register feeds with reviewer approval, AI explanations with citations, and the public API description.'),
    tabs(items, tab, t => { location.hash = '#/partners/' + t; }));
  const box = h('div'); main.append(box);
  await ({ submissions, badges, feeds, ai, api }[tab] || submissions)(box);
}

// ---------------------------------------------------------------- submissions
function readFile(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] || '');
    r.onerror = () => reject(new Error('could not read ' + file.name));
    r.readAsDataURL(file);
  });
}

function parseJson(text, label) {
  try { return JSON.parse(text); } catch { throw new Error(label + ' is not valid JSON'); }
}

async function submissions(box) {
  const out = h('div');
  const load = async () => {
    const rows = await get('/api/partner/submissions');
    clear(out).append(table([
      { label: 'Submission', key: 'id' }, { label: 'Manufacturer', key: 'manufacturer' }, { label: 'Model', key: 'model' }, { label: 'Model id', key: 'modelId' },
      { label: 'Driver', key: 'driverRef' }, { label: 'Status', render: s => badge(s.status) }, { label: 'Submitted by', key: 'submittedBy' }, { label: 'Submitted', key: 'submittedAt' },
      { label: 'Badge', render: s => s.badgeId ? badge('BADGE', 'ok') : '—' }], rows, { onRow: s => detail(s.id, load), rowClass: () => 'click', empty: 'No submissions yet.' }));
  };
  if (has('PARTNER_DEV')) box.append(submitForm(load));
  box.append(card('Submissions' + (has(...REVIEWERS) ? ' (all vendors)' : ' (yours)'), out));
  await load();
}

function submitForm(reload) {
  const manufacturer = input({ maxlength: 120 }), model = input({ maxlength: 120 }), modelId = input({ maxlength: 42, placeholder: 'IMD-001 or PTN-ACME-X1' });
  const udi = input({ maxlength: 40, placeholder: 'GS1 / HIBCC DI (optional)' }), description = h('textarea', { class: 'in', rows: 2, maxlength: 2000 });
  const options = input({ placeholder: 'P2 P4' });
  const spec = h('input', { type: 'file', accept: '.txt,.md,.pdf,text/plain,text/markdown,application/pdf' });
  const sbom = h('textarea', { class: 'in mono', rows: 4, placeholder: 'CycloneDX or SPDX JSON' });
  const mds2 = h('textarea', { class: 'in mono', rows: 4, placeholder: 'MDS2 form as JSON (e.g. {"manufacturer": …, "endOfSupport": "2032-12-31", …})' });
  const pkg = h('textarea', { class: 'in mono', rows: 4, placeholder: 'Driver package JSON (manifest · transport · grammar · semantics · bindings · tests)' });
  const sig = input({ placeholder: 'Ed25519 signature (Base64) – intimed-sdk sign' });
  const go = async () => {
    const f = spec.files && spec.files[0];
    if (!f) throw new Error('choose the interface specification file');
    if (f.size > 5 * 1024 * 1024) throw new Error('interface specification exceeds 5 MB');
    const type = f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf') ? 'application/pdf' : f.name.toLowerCase().endsWith('.md') ? 'text/markdown' : 'text/plain';
    const body = {
      manufacturer: manufacturer.value.trim(), model: model.value.trim(), modelId: modelId.value.trim(), udiDi: udi.value.trim() || null, description: description.value.trim(),
      options: options.value.split(/[\s,]+/).filter(Boolean),
      interfaceSpec: { name: f.name, contentType: type, contentBase64: await readFile(f) },
      sbom: parseJson(sbom.value, 'SBOM'), mds2: parseJson(mds2.value, 'MDS2'),
      driver: { package: parseJson(pkg.value, 'Driver package'), signature: sig.value.trim() },
    };
    return post('/api/partner/submissions', body);
  };
  return card('New submission (vendor self-service)',
    h('p', { class: 'muted' }, 'The driver package must be signed with your publisher key (registered by the tenant security team). It enters the CANDIDATE channel; certification needs a passing exchange test and two different reviewers.'),
    h('div', { class: 'form cols' }, field('Manufacturer', manufacturer), field('Model', model), field('Model id', modelId, 'must be listed in the driver manifest'), field('UDI-DI', udi),
      field('Connectivity options', options), field('Interface specification', spec, 'text, Markdown or PDF · ≤ 5 MB')),
    field('Description', description), field('SBOM', sbom), field('MDS2', mds2), field('Driver package', pkg), field('Signature', sig),
    button('Submit', () => guard(go, 'Submission received').then(reload).catch(() => {})));
}

async function detail(id, reload) {
  const s = await get('/api/partner/submissions/' + encodeURIComponent(id));
  const trail = await get('/api/partner/submissions/' + encodeURIComponent(id) + '/audit').catch(() => []);
  const runId = input({ placeholder: 'run-… (passing exchange test of this driver)' }), configId = input({ placeholder: 'or configuration id to run now' });
  const reason = input({ placeholder: 'reason / comment' });
  const act = (fn, msg) => guard(fn, msg).then(() => { document.querySelectorAll('.modal-back').forEach(m => m.remove()); reload(); }).catch(() => {});
  const actions = h('div', { class: 'toolbar' });
  if (has(...REVIEWERS)) {
    if (s.status === 'SUBMITTED') actions.append(button('Start review', () => act(() => post('/api/partner/submissions/' + id + '/review'), 'In review – posture updated')));
    if (s.status === 'IN_REVIEW' || s.status === 'HARNESS_PASSED') actions.append(runId, configId, button('Record harness result', () => act(() => post('/api/partner/submissions/' + id + '/harness',
      configId.value.trim() ? { configId: configId.value.trim() } : { runId: runId.value.trim() }), 'Harness result recorded'), 'secondary'));
    if (s.status === 'HARNESS_PASSED') actions.append(button('Certify (four-eyes)', () => act(() => post('/api/partner/submissions/' + id + '/certify', { comment: reason.value }), 'Certified – badge granted')));
    if (['SUBMITTED', 'IN_REVIEW', 'HARNESS_PASSED'].includes(s.status)) actions.append(reason, button('Reject', () => act(() => post('/api/partner/submissions/' + id + '/reject', { reason: reason.value }), 'Rejected'), 'ghost'));
  }
  if (has('PARTNER_DEV') && !['REJECTED', 'WITHDRAWN'].includes(s.status)) actions.append(button('Withdraw', () => act(() => post('/api/partner/submissions/' + id + '/withdraw'), 'Withdrawn'), 'ghost'));
  modal('Submission ' + s.id, h('div', { class: 'detail' },
    h('div', { class: 'kpis' }, kpi('Status', s.status), kpi('Driver', s.driverRef, s.driverChannel), kpi('Posture', s.postureStatus || '—'), kpi('Harness', s.harnessChecks || '—', s.harnessRunId || ''),
      kpi('SBOM', s.sbom.components + ' components', s.sbom.format + ' · ' + s.sbom.vulnerabilities + ' open vulnerabilities')),
    h('p', null, h('b', null, s.manufacturer + ' ' + s.model), ' · ', s.modelId, s.newModel ? ' (new tenant model)' : '', s.udiDi ? ' · UDI-DI ' + s.udiDi : ''),
    h('p', { class: 'muted' }, 'Interface specification: ' + s.interfaceSpec.name + ' (' + s.interfaceSpec.contentType + ', ' + s.interfaceSpec.size + ' bytes, SHA-256 ' + s.interfaceSpec.sha256.slice(0, 16) + '…)'),
    s.evidenceSeal ? h('p', null, 'Evidence seal ', h('code', null, s.evidenceSeal)) : null,
    s.rejectionReason ? h('p', { class: 'err' }, 'Rejected: ' + s.rejectionReason) : null,
    actions,
    h('h4', null, 'History'),
    table([{ label: 'At', key: 'at' }, { label: 'By', key: 'by' }, { label: 'Action', key: 'action' }, { label: 'From', key: 'from' }, { label: 'To', render: x => x.to ? badge(x.to) : '—' }, { label: 'Note', key: 'note' }], s.history || [], { compact: true }),
    h('h4', null, 'Audit trail'),
    table([{ label: 'Seq', key: 'seq' }, { label: 'At', key: 'ts' }, { label: 'Actor', key: 'actor' }, { label: 'Action', key: 'action' }], trail, { compact: true })));
}

// ---------------------------------------------------------------- badges
async function badges(box) {
  const status = select([['', 'All'], 'ACTIVE', 'REVOKED'], 'ACTIVE', { 'aria-label': 'Badge status' });
  const out = h('div');
  const load = async () => {
    const rows = await get('/api/partner/badges' + (status.value ? '?status=' + status.value : ''));
    clear(out).append(h('div', { class: 'kpis' }, kpi('Badges', rows.length), kpi('Gold', rows.filter(b => b.level === 'GOLD').length), kpi('Silver', rows.filter(b => b.level === 'SILVER').length)),
      table([{ label: 'Level', render: b => badge(b.level, b.level === 'GOLD' ? 'ok' : 'warn') }, { label: 'Driver', key: 'driver' }, { label: 'Models', key: 'modelIds' },
        { label: 'Manufacturer', key: 'manufacturer' }, { label: 'Granted', key: 'grantedAt' }, { label: 'Harness', key: 'harnessVersion' }, { label: 'Run', key: 'runId' },
        { label: 'Evidence seal', render: b => h('code', { title: b.evidenceSeal }, (b.evidenceSeal || '').slice(0, 16) + '…') }, { label: 'Status', render: b => badge(b.status) },
        { label: 'Revocation', render: b => b.revocationReason || '—' },
        { label: '', render: b => b.status === 'ACTIVE' && has(...REVIEWERS) ? button('Revoke', () => {
          const reason = window.prompt('Revocation reason');
          if (reason) guard(() => post('/api/partner/badges/' + b.id + '/revoke', { reason }), 'Badge revoked').then(load).catch(() => {});
        }, 'ghost') : '' }], rows, { compact: true, empty: 'No badges.' }));
  };
  status.addEventListener('change', load);
  box.append(card('Partner certification badges – revoked automatically when a later harness run of the driver fails or the driver is withdrawn',
    h('div', { class: 'toolbar' }, status, button('Refresh', load, 'ghost')), out));
  await load();
}

// ---------------------------------------------------------------- register feeds
async function feeds(box) {
  const st = await get('/api/catalogue/feeds/status');
  const di = input({ placeholder: 'Device identifier (DI)', maxlength: 40 }), modelId = input({ placeholder: 'Catalogue model (optional, e.g. IMD-001)', maxlength: 42 });
  const q = input({ placeholder: 'EUDAMED trade name (or use DI)', maxlength: 100 });
  const result = h('div'), queue = h('div');
  const loadQueue = async () => {
    const r = await get('/api/catalogue/feeds/changes');
    const rows = r.changes.filter(c => c.type === 'UDI_RECORD' || c.status === 'PENDING_REVIEW');
    clear(queue).append(table([{ label: 'Detected', key: 'detectedAt' }, { label: 'Source', key: 'source' }, { label: 'Type', key: 'type' }, { label: 'Ref', key: 'ref' },
      { label: 'Change', render: c => c.change && c.change.record ? (c.change.previousHash ? 'CHANGED' : 'NEW') + ' · ' + (c.change.record.brandName || c.change.record.tradeName || '') : JSON.stringify(c.change) },
      { label: 'FDA code match', render: c => c.change && c.change.productCodeMatch !== undefined && c.change.productCodeMatch !== null ? badge(String(c.change.productCodeMatch).toUpperCase()) : '—' },
      { label: 'Status', render: c => badge(c.status) },
      { label: '', render: c => c.status === 'PENDING_REVIEW' && has(...REVIEWERS) ? h('span', null,
        button('Details', () => modal('Change ' + c.id, h('div', null, h('h4', null, 'New record'), pre(c.change.record || c.change), c.change.previous ? h('div', null, h('h4', null, 'Approved snapshot'), pre(c.change.previous)) : null)), 'ghost'),
        button('Approve', () => guard(() => post('/api/catalogue/feeds/changes/' + c.id, { approve: true }), 'Approved – catalogue updated').then(loadQueue).catch(() => {}), 'secondary'),
        button('Reject', () => guard(() => post('/api/catalogue/feeds/changes/' + c.id, { approve: false }), 'Rejected').then(loadQueue).catch(() => {}), 'ghost')) : '' }], rows, { compact: true, empty: 'Review queue is empty.' }));
  };
  const reviewer = has(...REVIEWERS);
  box.append(h('div', { class: 'kpis' }, kpi('AccessGUDID', st.gudid.enabled ? 'enabled' : 'disabled', st.gudid.host), kpi('EUDAMED', st.eudamed.enabled ? 'enabled' : 'disabled', st.eudamed.host),
    kpi('openFDA', st.openfda.enabled ? 'enabled' : 'disabled', st.openfda.host), kpi('Egress allow-list', st.allowedHosts.length, st.allowedHosts.join(', '))),
    reviewer ? card('Look up a device',
      h('div', { class: 'form cols' }, field('DI', di), field('Catalogue model', modelId), field('EUDAMED text search', q)),
      h('div', { class: 'toolbar' },
        button('AccessGUDID lookup', async () => { const r = await guard(() => post('/api/catalogue/feeds/gudid/lookup', { di: di.value.trim(), modelId: modelId.value.trim() || null })).catch(() => null); if (r) { clear(result).append(pre(r)); loadQueue(); } }, 'primary', { disabled: !st.gudid.enabled }),
        button('EUDAMED search', async () => { const r = await guard(() => post('/api/catalogue/feeds/eudamed/search', { di: di.value.trim() || null, q: q.value.trim() || null, modelId: modelId.value.trim() || null })).catch(() => null); if (r) { clear(result).append(pre(r)); loadQueue(); } }, 'secondary', { disabled: !st.eudamed.enabled })),
      h('p', { class: 'muted' }, 'Changes are detected by content hash against the last approved snapshot and only reach the catalogue after reviewer approval.'), result) : null,
    card('Review queue', queue));
  await loadQueue();
}

// ---------------------------------------------------------------- AI explanations
async function ai(box) {
  const p = await get('/api/intelligence/explain/policy');
  const q = h('textarea', { class: 'in', rows: 3, maxlength: 1000, placeholder: 'Ask about an uploaded interface specification, e.g. "Which field carries FEV1?"' });
  const out = h('div');
  box.append(card('Policy gateway',
    h('div', { class: 'kpis' }, kpi('Status', p.active ? 'ACTIVE' : 'OFF', 'global ' + (p.globalEnabled ? 'on' : 'off') + ' · tenant ' + (p.tenantEnabled ? 'on' : 'off')),
      kpi('Endpoint host', p.endpointHost || '—', p.hostAllowed ? 'on the residency allow-list' : 'not allow-listed'), kpi('Auth', p.auth, p.apiKeyReference || 'Entra ID token from IMDS'),
      kpi('Limits', p.maxTokens + ' tokens', p.requestsPerMinute + ' requests / minute')),
    h('ul', { class: 'tight' }, h('li', null, 'PHI is redacted before any prompt leaves the platform; prompts containing credentials are refused.'),
      h('li', null, 'Only passages retrieved by the documentation assistant are sent; every answer must cite them.'),
      h('li', null, 'Audit stores SHA-256 hashes of prompt and response – never the content.'),
      h('li', null, 'The model never decides configuration values: recommendations stay rule-based and every value is proven by the exchange test.')),
    has('TENANT_ADMIN') ? button(p.tenantEnabled ? 'Disable for this tenant' : 'Enable for this tenant', () => guard(() => put('/api/intelligence/explain/policy', { enabled: !p.tenantEnabled }), 'Tenant policy updated').then(() => { location.reload(); }).catch(() => {}), 'secondary') : null),
    card('Ask with citations', field('Question', q), button('Explain', async () => {
      const r = await guard(() => post('/api/intelligence/explain', { question: q.value })).catch(() => null);
      if (!r) return;
      clear(out).append(h('p', null, badge(r.label, 'warn')), r.explanation ? h('blockquote', { class: 'note' }, r.explanation) : h('p', { class: 'muted' }, r.note || 'No explanation.'),
        r.citationsMissing ? h('p', { class: 'err' }, 'The answer cites none of the passages – treat it as unsupported.') : null,
        table([{ label: '#', key: 'n' }, { label: 'Citation', key: 'citation' }, { label: 'Passage', key: 'passage' }, { label: 'Score', key: 'score' }], r.citations || [], { compact: true }),
        r.promptHash ? h('p', { class: 'muted small' }, 'prompt ' + r.promptHash.slice(0, 16) + '… · response ' + r.responseHash.slice(0, 16) + '… · ' + r.redactions + ' redaction(s)') : null);
    }, 'primary', { disabled: !p.active }), out));
}

// ---------------------------------------------------------------- API & SDKs
async function api(box) {
  const doc = await get('/api/public/openapi.json');
  const paths = Object.entries(doc.paths || {});
  const byTag = {};
  for (const [path, ops] of paths) for (const [method, op] of Object.entries(ops)) (byTag[op.tags[0]] = byTag[op.tags[0]] || []).push({ method: method.toUpperCase(), path, summary: op.summary, security: (op.security || []).map(s => Object.keys(s)[0]).join(', ') });
  box.append(card('OpenAPI 3.1 – generated at runtime from the deployed request mappings',
    h('p', null, h('a', { href: '/api/public/openapi.json', target: '_blank', rel: 'noopener' }, '/api/public/openapi.json'), ' · ', paths.length + ' paths'),
    h('p', { class: 'muted' }, 'Reference clients live in the repository: intimed/sdk/typescript (fetch, typed) and intimed/sdk/python (urllib, no dependencies), each with a client-credentials token helper.')),
    ...Object.entries(byTag).map(([tag, ops]) => card(tag, table([{ label: 'Method', render: o => badge(o.method, 'neutral') }, { label: 'Path', render: o => h('code', null, o.path) },
      { label: 'Summary', key: 'summary' }, { label: 'Security', key: 'security' }], ops, { compact: true }))));
}
