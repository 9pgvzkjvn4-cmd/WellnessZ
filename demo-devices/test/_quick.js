const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 1400, height: 900 } });
  const errs = [];
  for (const f of ['index.html', 'device-console.html', 'integration-lab.html', 'security-console.html']) {
    const p = await ctx.newPage(); p.on('pageerror', e => errs.push(f + ': ' + e.message)); p.on('console', m => { if (m.type() === 'error') errs.push(f + ': ' + m.text()); });
    await p.goto('file://' + path.resolve('/home/user/WellnessZ/demo-devices/dist/' + f)); await p.waitForTimeout(600);
    const routes = { 'device-console.html': ['catalogue', 'model/MD-037', 'configurations', 'wizard/CFG-000004?step=4', 'wizard/CFG-000004?step=5', 'wizard/CFG-000004?step=6', 'wizard/CFG-000010?step=7', 'certificate/CFG-000003', 'calibration', 'fleet', 'options', 'approvals', 'wizard/CFG-000009?step=4', 'wizard/CFG-000003?step=3'],
      'integration-lab.html': ['simulators', 'messages', 'capture', 'unmatched', 'dead-letters', 'conformance'], 'security-console.html': ['endpoints', 'zones', 'signatures', 'anomaly', 'incidents', 'events'] }[f] || [];
    for (const r of routes) { await p.evaluate(r => location.hash = '#/' + r, r); await p.waitForTimeout(200); const t = await p.locator('#main').innerText().catch(() => ''); if (/could not be shown/.test(t)) errs.push(f + ' #' + r + ': ' + t.slice(0, 300)); }
  }
  console.log(errs.length ? errs.join('\n') : 'no errors'); await b.close();
})();
