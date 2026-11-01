import { h, $, clear, api, get, post, toast, guard, badge, table, card, kpi, field, input, select, button, modal, pre, tabs, fmt } from './lib.js';
import { catalogue } from './pages/catalogue.js';
import { configsPage, wizard, runsPage, runDetail } from './pages/wizard.js';
import { fleetPage, opsPage, templatesPage } from './pages/ops.js';
import { capturePage } from './pages/capture.js';
import { securityPage } from './pages/security.js';
import { integrationPage, tenantPage } from './pages/integration.js';

export const state = { me: null, info: null };

const NAV = [
  ['overview', 'Overview', null],
  ['catalogue', 'Device catalogue', null],
  ['configs', 'Provisioning', ['DEVICE_ADMIN', 'TEMPLATE_OWNER', 'AUDITOR', 'INTEGRATION_DEV', 'SECURITY_ANALYST', 'PLATFORM_SRE']],
  ['runs', 'Exchange tests', ['DEVICE_ADMIN', 'TEMPLATE_OWNER', 'AUDITOR', 'INTEGRATION_DEV']],
  ['fleet', 'Fleet & calibration', ['DEVICE_ADMIN', 'CLINICIAN', 'PLATFORM_SRE', 'SECURITY_ANALYST']],
  ['templates', 'Templates & bindings', null],
  ['capture', 'Capture (clinician)', ['CLINICIAN', 'PHYSICIAN', 'TEMPLATE_OWNER']],
  ['ops', 'Queues & context', ['CLINICIAN', 'PHYSICIAN', 'DEVICE_ADMIN', 'INTEGRATION_DEV']],
  ['security', 'Security', ['SECURITY_ANALYST', 'DEVICE_ADMIN', 'CISO_DPO', 'PLATFORM_SRE', 'AUDITOR', 'TENANT_ADMIN']],
  ['integration', 'Integration & APIs', ['INTEGRATION_DEV', 'PARTNER_DEV', 'TENANT_ADMIN', 'DEVICE_ADMIN']],
  ['tenant', 'Tenant & analytics', ['TENANT_ADMIN', 'PLATFORM_SRE', 'DEVICE_ADMIN']],
  ['quality', 'Traceability & quality', null],
  ['demo', 'Demo control', null],
];

export const has = (...roles) => state.me && state.me.roles.some(r => roles.includes(r));

async function boot() {
  try { state.info = await get('/api/public/info'); } catch { state.info = { environment: 'LOCAL' }; }
  try { await get('/api/auth/csrf'); state.me = await get('/api/auth/me'); } catch { state.me = null; }
  window.addEventListener('hashchange', route);
  route();
}

function route() {
  const hash = location.hash.replace(/^#\/?/, '') || 'overview';
  const [page, ...rest] = hash.split('/');
  if (!state.me || page === 'login') return loginView();
  shell(page, rest);
}

function shell(page, rest) {
  const root = clear($('#app'));
  const main = h('main', { id: 'main', class: 'main', tabindex: '-1' });
  const nav = h('nav', { class: 'side', 'aria-label': 'Main' },
    h('div', { class: 'brand' }, h('img', { src: '/favicon.svg', alt: '', width: 28, height: 28 }), h('div', null, h('b', null, 'IntiMed'), h('small', null, 'Device integration platform'))),
    NAV.filter(([, , roles]) => !roles || has(...roles)).map(([id, label]) => h('a', { href: '#/' + id, class: 'nav' + (id === page ? ' active' : '') }, label)));
  const top = h('header', { class: 'top' },
    h('div', { class: 'env' }, badge(state.info.environment || 'LOCAL', 'env'), h('span', null, state.me.tenantName)),
    h('div', { class: 'who' }, h('span', null, state.me.displayName), h('small', null, state.me.roleLabels.join(' · ')),
      button('Sign out', async () => { await post('/api/auth/logout').catch(() => {}); state.me = null; location.hash = '#/login'; location.reload(); }, 'ghost')));
  root.append(h('a', { class: 'skip', href: '#main' }, 'Skip to content'), nav, h('div', { class: 'col' }, top, main));
  const pages = { overview, catalogue, configs: configsPage, wizard, runs: runsPage, run: runDetail, fleet: fleetPage, templates: templatesPage, capture: capturePage,
    ops: opsPage, security: securityPage, integration: integrationPage, tenant: tenantPage, quality, demo };
  const fn = pages[page] || overview;
  Promise.resolve(fn(main, rest)).catch(e => { main.append(card('Error', h('p', { class: 'err' }, e.message))); });
}

function loginView() {
  const root = clear($('#app'));
  const user = input({ id: 'u', autocomplete: 'username', required: true });
  const pw = input({ id: 'p', type: 'password', autocomplete: 'current-password', required: true });
  const msg = h('p', { class: 'err', role: 'alert' });
  const box = h('form', { class: 'login', onsubmit: async (e) => {
    e.preventDefault(); msg.textContent = '';
    try {
      await get('/api/auth/csrf');
      const r = await post('/api/auth/login', { username: user.value.trim(), password: pw.value });
      if (r.mfaRequired) return mfaView(r.demoAuthenticator);
      state.me = r.user; location.hash = '#/overview'; route();
    } catch (err) { msg.textContent = err.message; }
  } },
    h('img', { src: '/favicon.svg', alt: '', width: 48, height: 48 }), h('h1', null, 'IntiMed'), h('p', { class: 'muted' }, 'Intelligent Medical Device Integration Platform · LOCAL'),
    field('Username', user), field('Password', pw), msg, h('button', { class: 'btn primary wide', type: 'submit' }, 'Sign in'),
    h('p', { class: 'muted small' }, 'Demo personas: see the Demo Guide (admin.device, approver.device, nurse.clinic, soc.analyst, sre …). Privileged roles require a TOTP second factor.'));
  root.append(h('div', { class: 'login-wrap' }, box));
  user.focus();
}

function mfaView(demoAuth) {
  const root = clear($('#app'));
  const code = input({ id: 'c', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, pattern: '[0-9]{6}' });
  const msg = h('p', { class: 'err', role: 'alert' });
  const helper = h('div', { class: 'demo-auth' });
  if (demoAuth) {
    const show = async () => { const r = await get('/api/auth/demo-mfa'); clear(helper).append(h('small', null, r.label), h('div', { class: 'otp' }, r.code), button('Use code', () => { code.value = r.code; }, 'ghost')); };
    show(); const t = setInterval(() => { if (!document.body.contains(helper)) clearInterval(t); else show().catch(() => {}); }, 10000);
  }
  root.append(h('div', { class: 'login-wrap' }, h('form', { class: 'login', onsubmit: async (e) => {
    e.preventDefault();
    try { const r = await post('/api/auth/mfa', { code: code.value.trim() }); state.me = r.user; location.hash = '#/overview'; route(); } catch (err) { msg.textContent = err.message; }
  } }, h('h1', null, 'Second factor'), h('p', { class: 'muted' }, 'Enter the 6-digit code from your authenticator app (RFC 6238 TOTP).'), field('Authentication code', code), msg,
    h('button', { class: 'btn primary wide', type: 'submit' }, 'Verify'), helper)));
  code.focus();
}

async function overview(main) {
  const o = await get('/api/overview');
  const cs = o.configurations || {};
  main.append(h('h1', null, 'Overview'),
    h('p', { class: 'lead' }, 'Any device by brand and model → configuration intelligence → configuration-time exchange test → the right template field. Security wraps every layer.'),
    h('div', { class: 'kpis' },
      kpi('Catalogue devices', o.catalogue.devices, o.catalogue.families + ' families · ' + o.catalogue.fdaCodes + ' FDA codes'),
      kpi('Certified drivers', o.drivers, 'signed Ed25519 packages'),
      kpi('Templates', o.templates, 'TPL-01…TPL-22 + host imports'),
      kpi('Published devices', cs.PUBLISHED || 0, Object.entries(cs).map(([k, v]) => k + ' ' + v).join(' · ') || 'none yet'),
      kpi('Exchange tests', o.runs, o.runsPassed + ' passed · evidence sealed'),
      kpi('Readings filed', o.observationsFiled, o.held + ' held · ' + o.unmatchedOpen + ' unmatched · ' + o.dlqOpen + ' dead letters'),
      kpi('Security alerts', o.alertsOpen, o.endpoints + ' endpoint identities'),
      kpi('Certifications', o.certifications, 'four-eyes records')),
    h('div', { class: 'two' },
      card('How IntiMed works (architecture §5.2)', h('ol', { class: 'steps' }, ['Scope & map', 'Select device & connectivity option (P1–P11)', 'Recommend (configuration intelligence RR-01…RR-16)', 'Driver / adapter (signed package)',
        'Configure & associate', 'Exchange test (XT-01…XT-16, sealed evidence)', 'Certify (four-eyes) & publish DEV → UAT → PROD', 'Go live & monitor', 'Maintain & re-certify'].map(s => h('li', null, s)))),
      card('Recent events', table([{ label: 'Time', key: 'time' }, { label: 'Event', render: e => badge(e.type, 'neutral') }, { label: 'Data', render: e => fmt(e.data) }], o.events || [], { compact: true }))));
}

async function demo(main) {
  main.append(h('h1', null, 'Demo control'), h('p', { class: 'lead' }, 'One end-to-end scenario per reference template (TPL-01…TPL-22): wizard with recommendations → exchange test (real mTLS transports, sealed evidence) → four-eyes → publish DEV → UAT → PROD → live reading into the host template.'));
  const box = h('div');
  main.append(box);
  const load = async () => {
    let s;
    try { s = await get('/api/demo/status'); } catch (e) { clear(box).append(card('Demo profile', h('p', null, e.message))); return; }
    clear(box).append(
      h('div', { class: 'toolbar' }, h('span', null, 'Progress: ', h('b', null, s.progress)),
        button('Run all 22 scenarios', () => guard(() => post('/api/demo/run-all?includeGuided=true'), 'Scenario run started – refreshing…').then(() => setTimeout(load, 4000))),
        button(s.streaming ? 'Stop live streaming' : 'Start live streaming (continuous devices)', () => guard(() => post('/api/demo/streaming?on=' + !s.streaming)).then(load), 'secondary'),
        button('Refresh', load, 'ghost')),
      table([{ label: 'Template', key: 'template' }, { label: 'Driver', key: 'driver' }, { label: 'Status', render: r => badge(r.status) },
        { label: 'Exchange test', render: r => r.run ? h('a', { href: '#/run/' + r.run }, r.checks + ' ' + (r.runStatus || '')) : '—' },
        { label: 'Stage', key: 'stage' }, { label: 'Live reading', render: r => r.live ? badge((r.live.status || r.live.transport || 'sent')) : '—' },
        { label: 'Filed instances', render: r => r.live ? String(r.live.templateInstances) : '—' },
        { label: '', render: r => button('Run', () => guard(() => post('/api/demo/scenarios/' + r.template), r.template + ' done').then(load), 'ghost') }], s.scenarios));
  };
  await load();
  try {
    const p = await get('/api/demo/personas');
    main.append(card('Demo personas (password in the Demo Guide)', table([{ label: 'Username', key: 'username' }, { label: 'Name', key: 'name' }, { label: 'Tenant', key: 'tenant' }, { label: 'Roles', key: 'roles' }], p, { compact: true })));
  } catch { /* not demo */ }
}

async function quality(main) {
  const [items, sum, cls] = await Promise.all([get('/api/quality/backlog'), get('/api/quality/summary'), get('/api/quality/classification')]);
  let filter = 'ALL';
  const body = h('div');
  const render = () => {
    const rows = items.filter(b => filter === 'ALL' || b.status === filter || b.id.startsWith(filter));
    clear(body).append(table([{ label: 'Backlog ID', key: 'id' }, { label: 'Title', key: 'title' }, { label: 'SP', key: 'sp' }, { label: 'Sprint', key: 'sprint' }, { label: 'Phase', key: 'phase' },
      { label: 'Status', render: b => badge(b.status) }, { label: 'Increment', key: 'increment' }, { label: 'Evidence / reason', render: b => b.evidence || b.reason || '' }], rows, { compact: true }));
  };
  const counts = items.reduce((m, b) => (m[b.status] = (m[b.status] || 0) + 1, m), {});
  const sp = items.reduce((m, b) => (m[b.status] = (m[b.status] || 0) + b.sp, m), {});
  main.append(h('h1', null, 'Traceability & quality'),
    h('div', { class: 'kpis' }, kpi('Backlog items', items.length, '1,395 SP in the requirement pack'), kpi('Delivered locally', counts.DELIVERED || 0, (sp.DELIVERED || 0) + ' SP'),
      kpi('Partially (local scope)', counts.PARTIAL || 0, (sp.PARTIAL || 0) + ' SP'), kpi('Deferred (cloud / org / P3)', counts.DEFERRED || 0, (sp.DEFERRED || 0) + ' SP')),
    h('div', { class: 'toolbar' }, select(['ALL', 'DELIVERED', 'PARTIAL', 'DEFERRED', 'IM-E01', 'IM-E02', 'IM-E03', 'IM-E04', 'IM-E05', 'IM-E06', 'IM-E07', 'IM-E08', 'IM-E09', 'IM-E10'], filter, { onchange: (e) => { filter = e.target.value; render(); }, 'aria-label': 'Filter' })),
    body,
    card('Regulatory classification record (IM-E10)', pre(cls)));
  render();
}

boot();
