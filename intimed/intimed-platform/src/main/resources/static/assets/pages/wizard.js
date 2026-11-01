import { h, clear, get, post, put, api, guard, toast, badge, table, card, kpi, field, input, select, button, modal, pre, fmt, tabs, download } from '../lib.js';
import { has, state } from '../app.js';

const STEPS = ['Select device', 'Connectivity option', 'Prerequisites', 'Parameters', 'Associate & bind', 'Exchange test', 'Certify & approve', 'Publish & monitor'];

export async function configsPage(main) {
  const rows = await get('/api/console/configs');
  main.append(h('h1', null, 'Guided provisioning'),
    h('p', { class: 'lead' }, 'Eight-step wizard with configuration intelligence at every step. Nothing is auto-applied in UAT/PROD; every decision is audited.'),
    h('div', { class: 'toolbar' }, has('DEVICE_ADMIN') ? button('New configuration from the catalogue', () => { location.hash = '#/catalogue'; }) : null),
    table([{ label: 'Configuration', key: 'name' }, { label: 'Model', render: c => c.modelId + ' · ' + (c.model || '') }, { label: 'Driver', key: 'driver' }, { label: 'Option', key: 'option' },
      { label: 'Status', render: c => badge(c.status) }, { label: 'Stage', key: 'stage' }, { label: 'Last test', render: c => c.lastRun ? badge(c.lastRun.status + ' ' + c.lastRun.passed + '/' + c.lastRun.total) : '—' },
      { label: 'Site', render: c => [c.site?.site, c.site?.station, c.site?.bed].filter(Boolean).join(' / ') }], rows.slice().reverse(), { onRow: c => { location.hash = '#/wizard/' + c.id; }, rowClass: () => 'click' }));
}

export async function wizard(main, [id, stepArg]) {
  let cfg = await get('/api/console/configs/' + id);
  let step = +(stepArg || firstOpen(cfg));
  const head = h('div'), body = h('div', { class: 'wiz' });
  main.append(head, body);
  const reload = async (s) => { cfg = await get('/api/console/configs/' + id); if (s) step = s; render(); };
  async function render() {
    clear(head).append(h('h1', null, cfg.name), h('p', { class: 'muted' }, cfg.modelId + ' · ', cfg.manufacturer, ' ', cfg.model, ' · ', badge(cfg.status), ' stage ', badge(cfg.stage, 'neutral'),
      cfg.blocked ? badge('POLICY BLOCK', 'bad') : null, cfg.retestReason ? badge('RE-TEST: ' + cfg.retestReason, 'warn') : null, ' · hash ', h('code', null, (cfg.hash || '').slice(0, 12))),
      tabs(STEPS.map((s, i) => [i + 1, (i + 1) + '. ' + s, stepBadge(cfg, i + 1)]), step, (s) => { step = s; history.replaceState(null, '', '#/wizard/' + id + '/' + s); render(); }));
    const left = h('div', { class: 'wiz-left' }), right = h('div', { class: 'wiz-right' });
    clear(body).append(left, right);
    try { await STEP_UI[step](left, cfg, reload); } catch (e) { left.append(h('p', { class: 'err' }, e.message)); }
    if (step <= 8) recPanel(right, cfg, step, reload);
  }
  render();
}

function firstOpen(cfg) { for (let i = 1; i <= 8; i++) if ((cfg.steps || {})[i] !== 'DONE') return i; return 8; }
function stepBadge(cfg, i) { const s = (cfg.steps || {})[i]; return s === 'DONE' ? '✓' : s === 'FAILED' || s === 'RETEST_REQUIRED' ? '!' : ''; }

async function save(cfg, step, data, reload, next = true) {
  const c = await guard(() => put('/api/console/configs/' + cfg.id + '/steps/' + step, data), 'Step ' + step + ' saved');
  if (c.blocked) toast('Saved with policy findings – resolve BLOCK/JUSTIFY items before certification', 'err');
  await reload(next ? Math.min(8, step + 1) : step);
}

// ------------------------------------------------------------------ recommendations panel
async function recPanel(root, cfg, step, reload) {
  root.append(h('h3', null, 'Configuration intelligence'), h('p', { class: 'muted small' }, 'Driver defaults → tenant context rules → de-identified knowledge base. Recommendations never decide alone.'));
  const list = h('div', { class: 'recs' });
  root.append(list);
  let recs = [];
  try { recs = await get('/api/console/configs/' + cfg.id + '/recommendations?step=' + step); } catch (e) { list.append(h('p', { class: 'muted' }, e.message)); }
  if (!recs.length) list.append(h('p', { class: 'muted' }, step === 1 && !cfg.modelId ? 'Pick a model.' : 'No recommendations for this step yet (complete the previous steps).'));
  for (const r of recs) list.append(recCard(r, reload));
  const devStage = ['LOCAL', 'DEV'].includes(cfg.stage);
  root.append(h('div', { class: 'toolbar' }, button('Accept all recommended' + (devStage ? '' : ' (blocked in ' + cfg.stage + ')'), async () => {
    await guard(() => post('/api/console/configs/' + cfg.id + '/accept-all?step=' + step), 'Accepted (development stage shortcut)'); reload();
  }, 'secondary', devStage ? {} : { disabled: true, title: 'SC-19: never auto-applied in UAT/PROD' })));
  const v = cfg.violations || [];
  root.append(h('h4', null, 'Policy findings (pre-save validation)'), v.length ? h('ul', { class: 'findings' }, v.map(x => h('li', null, badge(x.severity), ' ', h('b', null, x.rule), ' ', x.parameter, ' – ', x.message,
    x.severity === 'JUSTIFY' ? button('Justify', () => justify(cfg, x, reload), 'ghost') : null))) : h('p', { class: 'muted' }, 'No findings.'));
}

function recCard(r, reload) {
  const decided = r.status !== 'PROPOSED';
  return h('div', { class: 'rec ' + (r.policy || '').toLowerCase() },
    h('div', { class: 'rec-h' }, h('b', null, r.parameter), badge(r.rule, 'neutral'), badge(r.source, 'neutral'), badge(r.confidence), r.policy !== 'ALLOWED' ? badge(r.policy) : null),
    h('div', { class: 'rec-v' }, typeof r.value === 'object' && r.value !== null ? h('code', null, JSON.stringify(r.value).slice(0, 220)) : h('code', null, String(r.value))),
    h('p', { class: 'rec-why' }, r.why),
    decided ? badge(r.status) : h('div', { class: 'rec-a' },
      button('Accept', async () => { await guard(() => post('/api/console/recommendations/' + r.id + '/decision', { decision: 'ACCEPT' }), 'Accepted'); reload(); }, 'small', r.policy === 'BLOCKED' ? { disabled: true } : {}),
      button('Adjust', () => decide(r, 'ADJUST', reload), 'small ghost'),
      button('Override', () => decide(r, 'OVERRIDE', reload), 'small ghost')));
}

function decide(r, kind, reload) {
  const val = input({ value: typeof r.value === 'object' ? JSON.stringify(r.value) : r.value });
  const why = input({ placeholder: 'Reason (recorded in the audit trail)' });
  modal(kind === 'ADJUST' ? 'Adjust ' + r.parameter : 'Override ' + r.parameter, h('div', { class: 'form' }, kind === 'ADJUST' ? field('Value', val) : h('p', null, 'Keep the current value instead of: ', h('code', null, JSON.stringify(r.value))), field('Reason', why)),
    (close) => [button('Record decision', async () => {
      let v = val.value; try { v = JSON.parse(v); } catch { /* string */ }
      await guard(() => post('/api/console/recommendations/' + r.id + '/decision', { decision: kind, value: kind === 'ADJUST' ? v : null, reason: why.value }), 'Decision recorded'); close(); reload();
    })]);
}

function justify(cfg, x, reload) {
  const why = input({ placeholder: 'Justification' });
  modal('Justify ' + x.rule, h('div', { class: 'form' }, h('p', null, x.message), field('Justification', why)), (close) => [button('Record', async () => {
    const j = {}; j[x.rule + ':' + x.parameter] = why.value;
    if (x.rule === 'SC-07') await guard(() => post('/api/console/configs/' + cfg.id + '/posture-exception', { justification: why.value }), 'Posture exception requested – needs security co-approval');
    else await save(cfg, 5, { justifications: j }, reload, false);
    close(); reload();
  })]);
}

// ------------------------------------------------------------------ steps
const STEP_UI = {
  1: async (root, cfg, reload) => {
    const m = await get('/api/catalogue/models/' + cfg.modelId);
    const drv = select(m.drivers.map(d => [d.ref, d.ref + ' (' + d.channel + ', ' + d.options.join('/') + ')']), cfg.driver || (m.drivers[0] || {}).ref);
    root.append(card('1 · Select device', h('p', null, h('b', null, m.manufacturer + ' ' + m.model), ' – ', m.family), h('p', { class: 'muted small' }, m.use),
      h('p', null, 'Posture ', badge(m.posture.status), ' · connectivity ', m.connectivity.map(c => badge(c.option + ' ' + c.state, c.state === 'CERTIFIED_IN_LAB' ? 'ok' : 'neutral'))),
      field('Certified driver package', drv), button('Save & continue', () => save(cfg, 1, { driver: drv.value }, reload))));
  },
  2: async (root, cfg, reload) => {
    const [d, packs] = await Promise.all([get('/api/drivers/' + encodeURIComponent(cfg.driver)), get('/api/option-packs')]);
    const opts = d.manifest.options;
    let pick = cfg.option || opts[0];
    const grid = h('div', { class: 'opts' });
    const draw = () => clear(grid).append(packs.filter(p => opts.includes(p.option)).map(p => h('button', { type: 'button', class: 'opt' + (p.option === pick ? ' sel' : ''), onclick: () => { pick = p.option; draw(); } },
      h('b', null, p.option + ' · ' + p.name), h('small', null, p.how), h('div', null, badge('security ' + p.security), badge('reliability ' + p.reliability), badge('effort ' + p.effort, 'neutral'), badge(p.latency, 'neutral')), h('small', null, 'Via: ' + p.via))));
    draw();
    root.append(card('2 · Choose connectivity option', grid, button('Save & continue', () => save(cfg, 2, { option: pick }, reload))));
  },
  3: async (root, cfg, reload) => {
    const [packs, eps] = await Promise.all([get('/api/option-packs'), get('/api/security/endpoints').catch(() => [])]);
    const p = packs.find(x => x.option === cfg.option) || { prerequisites: [] };
    const checks = p.prerequisites.map(t => h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: (cfg.prerequisites || {})[t] }), ' ', t));
    const ep = select([['', '— none —'], ...eps.filter(e => e.status === 'ACTIVE').map(e => [e.id, e.name + ' (' + e.type + ', ' + e.zone + ')'])], cfg.endpointId || '');
    const site = input({ value: cfg.site?.site || '' }), station = input({ value: cfg.site?.station || '' }), bed = input({ value: cfg.site?.bed || '' }), room = input({ value: cfg.site?.room || '' });
    root.append(card('3 · Prerequisites', h('div', { class: 'checks' }, checks), field('Endpoint identity (mTLS)', ep, 'Register and enrol endpoints in Security → Endpoints'),
      h('div', { class: 'form cols' }, field('Site', site), field('Room', room), field('Station', station), field('Bed / cot', bed)),
      button('Save & continue', () => {
        const pre = {}; p.prerequisites.forEach((t, i) => pre[t] = checks[i].querySelector('input').checked);
        return save(cfg, 3, { prerequisites: pre, endpointId: ep.value || null, site: { site: site.value, room: room.value, station: station.value, bed: bed.value } }, reload);
      })));
  },
  4: async (root, cfg, reload) => {
    const packs = await get('/api/option-packs');
    const p = packs.find(x => x.option === cfg.option);
    const inputs = {};
    const form = h('div', { class: 'form cols' }, p.parameters.map(s => {
      const v = (cfg.params || {})[s.name] ?? '';
      const el = s.type === 'enum' ? select([['', '—'], ...s.values], String(v)) : input({ value: v, type: s.type === 'int' || s.type === 'number' ? 'number' : 'text', placeholder: s.type === 'secret' ? 'secret://name' : String(s.default ?? '') });
      inputs[s.name] = [el, s];
      return field(s.name + (s.required ? ' *' : ''), el, s.type === 'secret' ? 'vault reference only' : (s.min !== undefined ? s.min + '–' + s.max : null));
    }));
    root.append(card('4 · Configure parameters (' + p.option + ' ' + p.name + ')', form, button('Validate', async () => {
      const v = await guard(() => post('/api/console/configs/' + cfg.id + '/validate', { params: collect(inputs) }));
      modal('Pre-save validation', v.length ? h('ul', null, v.map(x => h('li', null, badge(x.severity), ' ', x.rule, ' ', x.message))) : h('p', null, 'No findings.'));
    }, 'secondary'), button('Save & continue', () => save(cfg, 4, { params: collect(inputs) }, reload))));
  },
  5: async (root, cfg, reload) => {
    const d = await get('/api/drivers/' + encodeURIComponent(cfg.driver));
    const keys = d.semantics.map(s => s.key);
    const method = select(['WORKLIST', 'ENCOUNTER_BED', 'SESSION', 'BADGE', 'MATCH', 'CONSENT'], cfg.association?.method || '');
    const thr = input({ type: 'number', step: '0.01', value: cfg.association?.threshold ?? 0.95 });
    const iv = input({ type: 'number', value: cfg.intervalMinutes ?? '' });
    const serials = input({ value: (cfg.expectedSerials || []).join(','), placeholder: 'device serial(s) bound to this configuration' });
    const g = cfg.qualityGates || {};
    const gates = ['calibrationLockout', 'verifyBeforePost', 'qcLotGate', 'blockImplausible'].map(k => [k, h('input', { type: 'checkbox', checked: g[k] ?? (k === 'blockImplausible') })]);
    const bindBox = h('div');
    const bindings = JSON.parse(JSON.stringify(cfg.bindings || []));
    const drawBindings = async () => {
      clear(bindBox);
      for (const b of bindings) {
        const tpl = await get('/api/templates/' + b.templateId);
        const rows = tpl.sections.flatMap(s => s.fields).map(f => {
          const sel = select([['', '— unbound —'], ...keys], b.fields[f.id] || '', { onchange: (e) => { if (e.target.value) b.fields[f.id] = e.target.value; else delete b.fields[f.id]; } });
          return h('tr', null, h('td', null, f.label, f.mandatory ? ' *' : ''), h('td', null, f.unit || f.type), h('td', null, f.code ? Object.entries(f.code).map(([k, v]) => k + ':' + v).join(' ') : ''), h('td', null, sel));
        });
        bindBox.append(h('h4', null, b.templateId + ' · ' + tpl.name), h('div', { class: 'table-wrap' }, h('table', { class: 'grid compact' }, h('thead', null, h('tr', null, ['Template field', 'Unit', 'Code', 'Driver key'].map(x => h('th', null, x)))), h('tbody', null, rows))));
      }
    };
    await drawBindings();
    const tplList = await get('/api/templates');
    const addTpl = select([['', 'Add template binding…'], ...tplList.map(t => [t.id, t.id + ' ' + t.name])], '', { onchange: (e) => { if (e.target.value && !bindings.find(b => b.templateId === e.target.value)) { bindings.push({ templateId: e.target.value, fields: {} }); drawBindings(); } } });
    root.append(card('5 · Associate & bind templates', h('div', { class: 'form cols' }, field('Association method', method), field('Auto-file threshold', thr), field('Interval minutes (continuous)', iv), field('Expected device serials', serials)),
      h('div', { class: 'checks' }, gates.map(([k, el]) => h('label', { class: 'chk' }, el, ' ', k)), g.grading ? badge('grading ' + g.grading, 'neutral') : null),
      h('h4', null, 'Template bindings (many-to-many)'), bindBox, addTpl,
      button('Save & continue', () => {
        const qg = Object.assign({}, g); gates.forEach(([k, el]) => qg[k] = el.checked);
        return save(cfg, 5, { association: { method: method.value, threshold: +thr.value }, intervalMinutes: iv.value ? +iv.value : null, expectedSerials: serials.value ? serials.value.split(',').map(s => s.trim()) : [], qualityGates: qg, bindings }, reload);
      })));
  },
  6: async (root, cfg, reload) => {
    const plan = await get('/api/console/configs/' + cfg.id + '/plan').catch(e => ({ error: e.message }));
    const out = h('div');
    root.append(card('6 · Configuration-time exchange test', plan.error ? h('p', { class: 'err' }, plan.error) : h('div', null,
      h('p', null, 'Plan: ', h('code', null, plan.driver), ' via ', badge(plan.option, 'neutral'), ' · association ', plan.association, ' · templates ', plan.templates.join(', ')),
      h('p', { class: 'muted small' }, 'The harness uses its own mTLS identity and the family simulator, sends through the real device listeners, files into sandbox template instances and seals the evidence.'),
      h('p', null, 'Negative cases: ', (plan.negativeCases || []).join(', ')),
      button('Run exchange test', async (e) => {
        e.target.disabled = true; e.target.textContent = 'Running…';
        try { const run = await post('/api/console/configs/' + cfg.id + '/test'); clear(out).append(runView(run)); toast('Exchange test ' + run.status, run.status === 'PASSED' ? 'ok' : 'err'); }
        catch (err) { toast(err.message, 'err'); }
        e.target.disabled = false; e.target.textContent = 'Run exchange test again';
        setTimeout(() => reload(6), 400);
      })), out));
    if (cfg.lastRun) { const r = await get('/api/console/runs/' + cfg.lastRun.runId); out.append(runView(r)); }
  },
  7: async (root, cfg, reload) => {
    const approvals = cfg.approvals || [];
    const summary = h('textarea', { class: 'in', rows: 3 }, cfg.certificationSummary || '');
    root.append(card('7 · Certify & approve (four-eyes)',
      h('p', null, 'Status ', badge(cfg.status), ' · last test ', cfg.lastRun ? badge(cfg.lastRun.status + ' ' + cfg.lastRun.passed + '/' + cfg.lastRun.total) : '—', ' · editors ', (cfg.editors || []).join(', ')),
      field('Certification summary (pre-written by intelligence – edit)', summary),
      h('div', { class: 'toolbar' },
        button('Save summary', () => save(cfg, 7, { certificationSummary: summary.value }, reload, false), 'secondary'),
        button('Submit for certification', async () => { await guard(() => post('/api/console/configs/' + cfg.id + '/submit'), 'Submitted – a different administrator must approve'); reload(); }),
        button('Approve', async () => { await guard(() => post('/api/console/configs/' + cfg.id + '/approve', { comment: 'Evidence reviewed' }), 'Approval recorded'); reload(); }, 'secondary'),
        button('Reject', async () => { await guard(() => post('/api/console/configs/' + cfg.id + '/reject', { reason: 'Changes requested' }), 'Rejected'); reload(); }, 'ghost')),
      h('h4', null, 'Approvals'), table([{ label: 'By', key: 'by' }, { label: 'Role', key: 'role' }, { label: 'At', key: 'at' }, { label: 'Comment', key: 'comment' }], approvals, { compact: true, empty: 'No approvals yet.' }),
      cfg.certificationId ? button('Open certification record', async () => modal('Certification record', pre(await get('/api/console/certifications/' + cfg.certificationId))), 'ghost') : null,
      cfg.postureException ? h('p', null, 'Posture exception: ', badge(cfg.postureException.approved ? 'APPROVED' : 'REQUESTED'), ' ', cfg.postureException.justification || '') : null));
  },
  8: async (root, cfg, reload) => {
    const stages = cfg.stages || {};
    root.append(card('8 · Publish & monitor',
      h('p', null, 'Current stage ', badge(cfg.stage, 'neutral'), ' · status ', badge(cfg.status), ' · publish gate: ', cfg.hash && cfg.hash === cfg.lastPassingRunHash ? badge('PASSING RUN FOR THIS HASH', 'ok') : badge('NO PASSING RUN FOR THIS HASH', 'bad')),
      h('div', { class: 'toolbar' }, ['DEV', 'UAT', 'PROD'].map(s => button('Publish to ' + s, async () => { await guard(() => post('/api/console/configs/' + cfg.id + '/publish?stage=' + s), 'Published to ' + s); reload(); }, s === 'DEV' ? 'primary' : 'secondary')),
        has('PLATFORM_SRE') ? button('SRE: approve PROD promotion', async () => { await guard(() => post('/api/console/configs/' + cfg.id + '/promotion-approval?stage=PROD'), 'Approval recorded'); reload(); }, 'ghost') : null),
      h('h4', null, 'Promotion history with diffs'), table([{ label: 'Stage', render: r => r[0] }, { label: 'At', render: r => fmt(r[1].at) }, { label: 'By', render: r => r[1].by }, { label: 'Gate', render: r => badge(r[1].gate) },
        { label: 'Changed fields', render: r => (r[1].diff || []).map(d => d.field).join(', ') || 'none' }], Object.entries(stages), { compact: true, empty: 'Not published yet.' }),
      cfg.monitoring ? h('p', null, 'Monitoring thresholds: ', h('code', null, JSON.stringify(cfg.monitoring))) : null,
      h('div', { class: 'toolbar' },
        button('Export configuration (secret-scanned)', async () => download(cfg.id + '.json', new Blob([JSON.stringify(await get('/api/console/configs/' + cfg.id + '/export'), null, 2)], { type: 'application/json' })), 'ghost'),
        button('Change → new version', async () => { const c = await guard(() => post('/api/console/configs/' + cfg.id + '/new-version')); location.hash = '#/wizard/' + c.id; }, 'ghost'),
        button('Clone to another site', async () => { const c = await guard(() => post('/api/console/configs/' + cfg.id + '/clone', { name: cfg.name + ' (clone)' })); location.hash = '#/wizard/' + c.id; }, 'ghost'),
        button('Decommission (revoke keys, remove bindings, archive)', async () => { if (confirm('Decommission ' + cfg.name + '?')) { await guard(() => post('/api/console/configs/' + cfg.id + '/decommission'), 'Decommissioned'); reload(); } }, 'danger')),
      h('h4', null, 'Change history'), table([{ label: 'At', key: 'at' }, { label: 'By', key: 'by' }, { label: 'Action', key: 'action' }, { label: 'Hash', key: 'hash' }], (cfg.history || []).slice().reverse(), { compact: true })));
  },
};

function collect(inputs) {
  const o = {};
  for (const [k, [el, s]] of Object.entries(inputs)) { if (el.value === '') continue; o[k] = s.type === 'int' || s.type === 'number' ? +el.value : el.value === 'true' ? true : el.value === 'false' ? false : el.value; }
  return o;
}

export function runView(run) {
  const res = Object.values(run.results || {});
  return h('div', { class: 'run' },
    h('p', null, badge(run.status), ' ', run.passed + '/' + run.total + ' checks · p95 ' + run.latencyP95Ms + ' ms · seal ', h('code', null, (run.seal || '').slice(0, 24) + '…'), ' · test subject ', run.testSubject),
    h('div', { class: 'toolbar' },
      button('Download evidence pack', async () => { const r = await fetch('/api/console/runs/' + run.id + '/evidence', { credentials: 'same-origin' }); download(run.id + '-evidence.zip', await r.blob()); }, 'secondary'),
      button('Verify seal', async () => { const v = await get('/api/console/runs/' + run.id + '/verify'); modal('Evidence seal verification', pre(v)); }, 'ghost'),
      button('Sandbox template instances', async () => {
        const inst = await get('/api/console/runs/' + run.id + '/instances');
        modal('Sandbox instances', h('div', null, inst.map(i => h('div', { class: 'card' }, h('h4', null, i.templateId + ' · ' + i.templateName), table([{ label: 'Field', render: f => f[0] }, { label: 'Value', render: f => fmt(f[1].value) }, { label: 'Unit', render: f => f[1].unit || '' },
          { label: 'Converted from', render: f => f[1].convertedFrom || '' }, { label: 'Flags', render: f => (f[1].flags || []).join(' ') }], Object.entries(i.fields), { compact: true }),
          h('p', { class: 'muted small' }, 'Adapters: ', (i.deliveries || []).map(d => d.adapter + ' → ' + d.ack).join(' · ')),
          h('div', { class: 'toolbar' }, ['FHIR', 'HL7V2', 'REST', 'PDF'].map(a => button(a, async () => { const r = await get('/api/instances/sandbox-instance/' + i.id + '/render/' + a); modal(a + ' rendering', a === 'HL7V2' ? pre(r.payload.replace(/\r/g, '\n')) : a === 'PDF' ? h('p', null, 'PDF ' + r.ack + ' sha256 ' + r.sha256) : pre(r.payload)); }, 'small ghost')))))));
      }, 'ghost')),
    table([{ label: 'Check', render: r => r.id + ' ' + r.name }, { label: 'Result', render: r => badge(r.status) }, { label: 'Detail', key: 'detail' },
      { label: 'Evidence', render: r => r.evidence ? button('view', () => modal(r.id + ' evidence', pre(r.evidence)), 'small ghost') : '' }], res, { compact: true }));
}

export async function runsPage(main) {
  const runs = await get('/api/console/runs');
  main.append(h('h1', null, 'Configuration-time exchange tests'), h('p', { class: 'lead' }, 'Every run: XT-01…XT-16 through the real transports, sandbox delivery, sealed evidence (SHA-256 + Ed25519).'),
    table([{ label: 'Run', key: 'id' }, { label: 'Configuration', key: 'configId' }, { label: 'Driver', key: 'driver' }, { label: 'Result', render: r => badge(r.status + ' ' + r.passed + '/' + r.total) },
      { label: 'p95 ms', key: 'latencyP95Ms' }, { label: 'Finished', key: 'finishedAt' }, { label: 'Operator', key: 'operator' }], runs.slice().reverse(), { onRow: r => { location.hash = '#/run/' + r.id; }, rowClass: () => 'click' }));
}

export async function runDetail(main, [id]) {
  const r = await get('/api/console/runs/' + id);
  main.append(h('h1', null, 'Exchange test ' + id), h('p', null, h('a', { href: '#/wizard/' + r.configId + '/6' }, 'Open configuration ' + r.configId)), runView(r));
}
