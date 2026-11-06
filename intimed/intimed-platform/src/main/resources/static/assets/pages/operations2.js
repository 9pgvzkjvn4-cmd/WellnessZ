// Operations & commercial (IM-E04-BL-007 regression trends, IM-E04-BL-005 shadow sign-off, IM-E09-BL-003 monthly report,
// IM-E09-BL-005 health, IM-E02-BL-007/IM-E09-BL-004 benchmarks, IM-E09-BL-002 billing, IM-E09-BL-006 branding,
// IM-E09-BL-001 trials). No innerHTML; no inline styles (CSP) – all DOM built with h().
import { h, clear, get, post, put, guard, badge, table, card, kpi, field, input, select, button, pre, tabs, download } from '../lib.js';
import { has, applyBranding } from '../app.js';

const TABS = [
  ['regression', 'Regression', ['DEVICE_ADMIN', 'PLATFORM_SRE', 'TENANT_ADMIN', 'TEMPLATE_OWNER', 'INTEGRATION_DEV', 'REGULATORY', 'AUDITOR']],
  ['shadow', 'Shadow sign-off', ['TEMPLATE_OWNER', 'CLINICIAN', 'PHYSICIAN', 'REGULATORY', 'AUDITOR', 'DEVICE_ADMIN']],
  ['report', 'Monthly report', ['TENANT_ADMIN', 'PLATFORM_SRE', 'REGULATORY', 'AUDITOR']],
  ['health', 'Health & benchmarks', null],
  ['billing', 'Billing', ['TENANT_ADMIN']],
  ['branding', 'Branding', ['TENANT_ADMIN']],
  ['trials', 'Trials', ['PLATFORM_SRE']],
];

export async function operations2Page(main, [sub]) {
  const visible = TABS.filter(([, , roles]) => !roles || has(...roles));
  const tab = visible.some(([id]) => id === sub) ? sub : (visible[0] || ['health'])[0];
  main.append(h('h1', null, 'Operations & commercial'),
    h('p', { class: 'lead' }, 'Nightly regression of published configurations, shadow-run discrepancy review with four-eyes clinical sign-off, executive reporting, tenant health, privacy-preserving benchmarks, billing exports, branding and trials.'),
    tabs(visible.map(([id, label]) => [id, label]), tab, t => { location.hash = '#/operations/' + t; }));
  const box = h('div'); main.append(box);
  await ({ regression, shadow, report, health, billing, branding, trials }[tab] || health)(box);
}

const pct = v => (v === undefined || v === null ? '—' : v + ' %');

// ---------------------------------------------------------------- regression (IM-E04-BL-007)
async function regression(box) {
  const out = h('div');
  const load = async () => {
    const t = await get('/api/regression/trends');
    clear(out).append(h('div', { class: 'kpis' }, kpi('Regression runs', t.runs), kpi('Pass rate', pct(t.passRatePct)), kpi('Nightly job', t.enabled ? 'ENABLED' : 'DISABLED', 'cron intimed.regression.cron')),
      table([{ label: 'Configuration', render: c => (c.name || c.configId) }, { label: 'Status', render: c => badge(c.configStatus || '—') },
        { label: 'Runs', key: 'runs' }, { label: 'Pass rate', render: c => pct(c.passRatePct) }, { label: 'Latest p95', render: c => c.latestP95Ms === undefined ? '—' : c.latestP95Ms + ' ms' },
        { label: 'Mean p95', render: c => c.meanP95Ms === undefined ? '—' : c.meanP95Ms + ' ms' }, { label: 'Last', render: c => badge(c.lastStatus) }, { label: 'Trend', render: c => badge(c.trend, 'neutral') },
        { label: 'History', render: c => h('span', { class: 'mono' }, c.points.slice(-20).map(p => p.status === 'PASSED' ? '●' : '✕').join('')) }], t.configs,
      { compact: true, empty: 'No regression run yet – the nightly job runs every published configuration at 02:30.' }));
  };
  box.append(card('Scheduled site regression suites',
    h('p', { class: 'muted' }, 'Every published configuration is re-tested nightly with the configuration-time exchange test as the system actor. A failing run raises a regression.failed event and an audit record.'),
    has('DEVICE_ADMIN', 'PLATFORM_SRE') ? button('Run regression now', () => guard(() => post('/api/regression/run'), 'Regression suite finished').then(load)) : null,
    button('Refresh', load, 'ghost')), out);
  await load();
}

// ---------------------------------------------------------------- shadow sign-off (IM-E04-BL-005)
async function shadow(box) {
  const configs = (await get('/api/console/configs')).filter(c => c.shadow);
  if (!configs.length) { box.append(card('Shadow runs', h('p', { class: 'muted' }, 'No configuration is in a shadow run. Shadow runs start when a configuration with shadowDays is published.'))); return; }
  const pick = select(configs.map(c => [c.id, c.name + ' · ' + c.id + (c.shadow.signedOff ? ' (signed off)' : '')]), configs[0].id);
  const tol = input({ type: 'number', value: '5', min: '0', max: '50', step: '0.5' });
  const out = h('div');
  const load = async () => {
    clear(out);
    const r = await guard(() => get('/api/shadow/' + encodeURIComponent(pick.value) + '/report?tolerancePct=' + encodeURIComponent(tol.value)));
    out.append(h('div', { class: 'kpis' }, kpi('Agreement', pct(r.agreementPct), r.agreed + '/' + r.pairs + ' pairs'), kpi('Shadow instances', r.shadowInstances),
      kpi('Shadow until', r.shadow.until ? r.shadow.until.slice(0, 10) : '—'), kpi('Sign-off', r.shadow.signedOff ? 'SIGNED OFF' : (r.readyForSignOff ? 'READY' : 'OPEN'), r.shadow.signedOffBy || '')),
      card('Discrepancy report (per field × device)', table([{ label: 'Field', key: 'field' }, { label: 'Device', key: 'device' }, { label: 'Unit', key: 'unit' }, { label: 'Pairs', key: 'pairs' },
        { label: 'Agreement', render: x => badge(pct(x.agreementPct), x.agreementPct >= 95 ? 'ok' : x.agreementPct >= 80 ? 'warn' : 'bad') }, { label: 'Mean |Δ|', key: 'meanAbsDiff' },
        { label: 'Max |Δ|', key: 'maxAbsDiff' }, { label: 'Unit mismatches', key: 'unitMismatches' }, { label: 'Missing automated', key: 'missingAutomated' }], r.rows, { compact: true, empty: 'No manual reference values recorded yet.' }),
        h('p', { class: 'muted small' }, r.note)));
    if (has('TEMPLATE_OWNER', 'CLINICIAN', 'PHYSICIAN')) {
      const inst = await get('/api/shadow/' + encodeURIComponent(pick.value) + '/instances').catch(() => []);
      const rows = [];
      inst.slice(-50).forEach(i => Object.entries(i.fields || {}).forEach(([f, v]) => rows.push({ instance: i.id, effectiveTime: i.effectiveTime, device: i.deviceSerial, field: f, value: v.value, unit: v.unit })));
      out.append(card('Record manual reference values', table([{ label: 'Time', key: 'effectiveTime' }, { label: 'Device', key: 'device' }, { label: 'Field', key: 'field' },
        { label: 'Automated', render: r2 => String(r2.value) + ' ' + (r2.unit || '') },
        { label: 'Manual entry', render: r2 => {
          if (!has('TEMPLATE_OWNER', 'CLINICIAN')) return '—';
          const v = input({ 'aria-label': 'Manual value for ' + r2.field, size: 8 });
          return h('span', null, v, button('Save', () => guard(() => post('/api/shadow/' + encodeURIComponent(pick.value) + '/manual', { instanceId: r2.instance, field: r2.field, value: v.value, unit: r2.unit }), 'Recorded'), 'ghost small'));
        } }], rows, { compact: true, empty: 'No shadow instances yet.' })));
    }
    if (has('TEMPLATE_OWNER') && !r.shadow.signedOff) {
      const comment = h('textarea', { class: 'in', rows: 2, maxlength: 1000, placeholder: 'Clinical sign-off comment' });
      out.append(card('Clinical sign-off (four-eyes)', h('p', { class: 'muted' }, 'The configuration creator cannot sign off. Sign-off ends the shadow run – live readings are then filed into the host template.'),
        comment, button('Sign off shadow run', () => guard(() => post('/api/shadow/' + encodeURIComponent(pick.value) + '/sign-off', { comment: comment.value, tolerancePct: Number(tol.value) }), 'Shadow run signed off').then(load))));
    }
  };
  box.append(card('Shadow-mode discrepancy review', h('div', { class: 'form cols' }, field('Configuration', pick), field('Tolerance (± %)', tol)), button('Show report', load)), out);
  await load();
}

// ---------------------------------------------------------------- monthly report (IM-E09-BL-003)
async function report(box) {
  const month = input({ type: 'month', value: new Date().toISOString().slice(0, 7) });
  const out = h('div');
  const load = async () => {
    const r = await guard(() => get('/api/reports/monthly?month=' + encodeURIComponent(month.value)));
    clear(out).append(h('p', null, badge(r.status), ' ', r.tenantName, ' · ', r.month),
      h('div', { class: 'kpis' }, kpi('Capture rate', pct(r.readings.captureRatePct), r.readings.filed + ' filed / ' + r.readings.received + ' received'),
        kpi('Clinician-verified', r.readings.verifiedByClinician, pct(r.dataQuality.verifiedRatePct)), kpi('Est. time saved', r.readings.estimatedHoursSaved + ' h', r.readings.estimatedMinutesSaved + ' min'),
        kpi('Dead letters', pct(r.dataQuality.dlqRatePct), r.readings.deadLetters + ' messages'), kpi('Unmatched', pct(r.dataQuality.unmatchedRatePct)), kpi('Held', pct(r.dataQuality.heldRatePct)),
        kpi('Calibration compliance', pct(r.calibration.compliancePct), r.calibration.dueThisMonth + ' due this month'), kpi('Fleet healthy', pct(r.fleet.healthyPct), r.fleet.GREEN + ' green · ' + r.fleet.AMBER + ' amber · ' + r.fleet.RED + ' red'),
        kpi('Regression pass rate', pct(r.regression.passRatePct), r.regression.runs + ' runs')),
      h('div', { class: 'two' }, card('Metered usage', table([{ label: 'Meter', render: x => x[0] }, { label: 'Quantity', render: x => String(x[1]) }], Object.entries(r.metering), { compact: true })),
        card('Filed readings per day', table([{ label: 'Day', render: x => x[0] }, { label: 'Filed', render: x => String(x[1]) }], Object.entries(r.dailyFiled), { compact: true }))),
      h('p', { class: 'muted small' }, r.note));
  };
  const csv = async () => {
    const text = await guard(async () => {
      const r = await fetch('/api/reports/monthly.csv?month=' + encodeURIComponent(month.value), { headers: { Accept: 'text/csv' }, credentials: 'same-origin' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    });
    download('intimed-report-' + month.value + '.csv', new Blob([text], { type: 'text/csv' }));
  };
  box.append(card('Monthly executive report', h('div', { class: 'toolbar' }, field('Month', month), button('Generate', load), button('Download CSV', csv, 'ghost'))), out);
  await load();
}

// ---------------------------------------------------------------- health & benchmarks (IM-E09-BL-005, IM-E02-BL-007 / IM-E09-BL-004)
async function health(box) {
  if (has('TENANT_ADMIN', 'PLATFORM_SRE', 'DEVICE_ADMIN')) {
    const s = await get('/api/tenant/health');
    box.append(card('Tenant health score', h('div', { class: 'kpis' }, kpi('Health score', s.score + ' / 100', s.grade)),
      table([{ label: 'Factor', key: 'factor' }, { label: 'Points', render: f => f.points + ' / ' + f.maxPoints }, { label: 'Explanation', key: 'explanation' }], s.factors, { compact: true })));
  }
  const b = await get('/api/benchmarks');
  const peers = p => typeof p === 'string' ? badge(p === 'SUPPRESSED' ? 'SUPPRESSED (< ' + b.k + ' orgs)' : 'NOT OPTED IN', 'neutral') : null;
  box.append(card('Benchmarks – your organisation vs anonymised peers',
    h('p', { class: 'muted' }, b.note),
    has('TENANT_ADMIN') ? button(b.optedIn ? 'Stop contributing (opt out)' : 'Contribute de-identified aggregates (opt in)',
      () => guard(() => put('/api/benchmarks/opt-in', { optIn: !b.optedIn }), 'Saved').then(() => location.reload()), 'secondary') : null,
    table([{ label: 'Dimension', key: 'dimension' }, { label: 'Group', key: 'key' },
      { label: 'Your configs', render: r => r.own ? String(r.own.configs) : '—' }, { label: 'Your certified', render: r => r.own ? pct(r.own.certifiedRatePct) : '—' },
      { label: 'Peer certified', render: r => peers(r.peers) || pct(r.peers.certifiedRatePct) }, { label: 'Your p95', render: r => r.own && r.own.meanHarnessP95Ms !== undefined ? r.own.meanHarnessP95Ms + ' ms' : '—' },
      { label: 'Peer p95', render: r => peers(r.peers) || (r.peers.meanHarnessP95Ms !== undefined ? r.peers.meanHarnessP95Ms + ' ms' : '—') },
      { label: 'Your DLQ', render: r => r.own ? pct(r.own.dlqRatePct) : '—' }, { label: 'Peer DLQ', render: r => peers(r.peers) || pct(r.peers.dlqRatePct) },
      { label: 'Peers', render: r => typeof r.peers === 'object' ? r.peers.organisations : '—' }], b.rows, { compact: true })));
}

// ---------------------------------------------------------------- billing (IM-E09-BL-002)
async function billing(box) {
  const month = input({ type: 'month', value: new Date().toISOString().slice(0, 7) });
  const out = h('div');
  const invoice = h('textarea', { class: 'in mono', rows: 8, placeholder: '{"month":"2026-11","lines":[{"meter":"observations.filed","qty":120},{"meter":"harness.runs","day":"2026-11-03","qty":4}]}' });
  const recOut = h('div');
  const exp = async () => {
    const e = await guard(() => get('/api/billing/export?month=' + encodeURIComponent(month.value)));
    clear(out).append(h('div', { class: 'kpis' }, kpi('Rows', e.rows), kpi('Algorithm', e.algorithm, e.keyId)),
      table([{ label: 'Meter', render: x => x[0] }, { label: 'Total', render: x => String(x[1]) }], Object.entries(e.totals), { compact: true }),
      h('p', { class: 'muted small' }, 'CSV signature: ', h('code', null, e.signatures.csv)), h('p', { class: 'muted small' }, 'JSON lines signature: ', h('code', null, e.signatures.jsonl)),
      button('Download CSV', () => download('intimed-metering-' + e.month + '.csv', new Blob([e.csv], { type: 'text/csv' })), 'ghost'),
      button('Download JSON lines', () => download('intimed-metering-' + e.month + '.jsonl', new Blob([e.jsonl], { type: 'application/x-ndjson' })), 'ghost'),
      button('Download signatures', () => download('intimed-metering-' + e.month + '.sig.json', new Blob([JSON.stringify({ month: e.month, algorithm: e.algorithm, keyId: e.keyId, signatures: e.signatures }, null, 2)], { type: 'application/json' })), 'ghost'));
  };
  const rec = async () => {
    let body;
    try { body = JSON.parse(invoice.value); } catch { clear(recOut).append(h('p', { class: 'err' }, 'Invoice lines must be valid JSON')); return; }
    const r = await guard(() => post('/api/billing/reconcile', body));
    clear(recOut).append(h('p', null, badge(r.status), ' ', r.matched + ' matched · ' + r.differenceCount + ' differences'),
      table([{ label: 'Meter', key: 'meter' }, { label: 'Day', key: 'day' }, { label: 'Invoiced', key: 'invoiced' }, { label: 'Metered', key: 'metered' }, { label: 'Delta', key: 'delta' }, { label: 'Kind', render: d => badge(d.kind, 'warn') }], r.differences, { compact: true, empty: 'No differences.' }));
  };
  box.append(card('Metering export (HMAC-SHA256 signed)', h('div', { class: 'toolbar' }, field('Month', month), button('Export', exp))), out,
    card('Reconcile an invoice', invoice, button('Reconcile', rec)), recOut);
}

// ---------------------------------------------------------------- branding (IM-E09-BL-006)
async function branding(box) {
  const b = await get('/api/tenant/branding');
  const name = input({ value: b.productName, maxlength: 60 });
  const primary = input({ type: 'color', value: b.primaryColor }), accent = input({ type: 'color', value: b.accentColor });
  const email = input({ type: 'email', value: b.supportEmail || '' }), url = input({ type: 'url', value: b.supportUrl || '', placeholder: 'https://' });
  const file = h('input', { type: 'file', accept: 'image/png,image/svg+xml', class: 'in' });
  const preview = h('div', { class: 'brand-preview' });
  let logo = b.logo || '';
  const showLogo = () => { clear(preview); if (logo) preview.append(h('img', { src: logo, alt: 'Logo preview', height: 48 })); };
  file.addEventListener('change', () => {
    const f = file.files[0];
    if (!f) return;
    if (f.size > 64 * 1024) { guard(() => Promise.reject(new Error('Logo exceeds 64 KiB'))).catch(() => {}); return; }
    const rd = new FileReader();
    rd.onload = () => {
      logo = String(rd.result);
      showLogo();
    };
    rd.readAsDataURL(f);
  });
  showLogo();
  box.append(card('White-label branding',
    h('p', { class: 'muted' }, 'Colours become CSS custom properties for the console and the capture component (--im-primary, --im-accent). Logos: PNG or SVG (no script), at most 64 KiB.'),
    h('div', { class: 'form cols' }, field('Product name', name), field('Primary colour', primary), field('Accent colour', accent), field('Support e-mail', email), field('Support URL', url), field('Logo', file, 'PNG or SVG ≤ 64 KiB')),
    preview,
    button('Save branding', async () => {
      const r = await guard(() => put('/api/tenant/branding', { productName: name.value, primaryColor: primary.value, accentColor: accent.value, supportEmail: email.value, supportUrl: url.value, logo }), 'Branding saved');
      applyBranding(r);
    }),
    button('Remove logo', () => { logo = ''; showLogo(); }, 'ghost')));
}

// ---------------------------------------------------------------- trials (IM-E09-BL-001)
async function trials(box) {
  const out = h('div');
  const id = input({ placeholder: 'trial-clinic-01', pattern: '[a-z0-9][a-z0-9-]{2,40}' }), name = input({ placeholder: 'Clinic name' });
  const days = input({ type: 'number', value: '30', min: '1', max: '90' }), grace = input({ type: 'number', value: '14', min: '0', max: '60' });
  const residency = select(['eu-west', 'eu-central', 'uk-south', 'us-east', 'me-central', 'ap-southeast', 'client-hosted'], 'eu-west');
  const load = async () => {
    const l = await get('/api/trials');
    clear(out).append(table([{ label: 'Tenant', key: 'id' }, { label: 'Name', key: 'name' }, { label: 'Status', render: t => badge(t.status) }, { label: 'Expires', key: 'expiresAt' },
      { label: 'Grace (days)', key: 'graceDays' }, { label: 'Simulator-only', render: t => t.simulatorOnly ? 'yes' : 'no' },
      { label: '', render: t => t.status === 'OFFBOARDED' ? '' : button('Extend 14 days', () => guard(() => post('/api/trials/' + encodeURIComponent(t.id) + '/extend', { days: 14 }), 'Extended').then(load), 'ghost small') }], l, { compact: true, empty: 'No trial tenants.' }));
  };
  box.append(card('Create a trial tenant', h('p', { class: 'muted' }, 'Trial tenants are simulator-only, receive a synthetic sandbox subject and the simulator golden sessions; they are suspended on expiry and offboarded (crypto-shred) after the grace period.'),
    h('div', { class: 'form cols' }, field('Tenant id', id), field('Name', name), field('Residency', residency), field('Length (days)', days), field('Grace period (days)', grace)),
    button('Create trial', () => guard(() => post('/api/trials', { id: id.value.trim(), name: name.value.trim(), residency: residency.value, days: Number(days.value), graceDays: Number(grace.value) }), 'Trial created').then(load)),
    button('Run expiry sweep now', () => guard(() => post('/api/trials/sweep'), 'Sweep done').then(r => { load(); if (r && r.length) out.prepend(pre(r)); }), 'secondary')), out);
  await load();
}
