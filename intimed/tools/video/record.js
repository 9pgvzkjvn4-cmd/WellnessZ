// Records the two reference videos as screenshot + narration manifests from a running demo instance.
// Usage: NODE_PATH=$(npm root -g) node record.js <outDir> [baseUrl]
// Video 1 "platform": product tour.  Video 2 "demo": how to run the demo pack and execute the guided scenarios.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const OUT = path.resolve(process.argv[2] || 'recording'); const BASE = process.argv[3] || 'https://localhost:9443';
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
const manifest = { platform: [], demo: [] };
let n = 0, page, ctx, browser;

function slideHtml(title, lines, kicker) {
  const li = lines.map(l => `<li>${l}</li>`).join('');
  return `<!doctype html><html><head><style>
  body{margin:0;width:1440px;height:900px;background:linear-gradient(135deg,#0b1f3a,#0b5fff);font-family:-apple-system,Segoe UI,Roboto,sans-serif;color:#fff;display:flex;flex-direction:column;justify-content:center;padding:0 110px;box-sizing:border-box}
  .k{letter-spacing:.18em;text-transform:uppercase;font-size:18px;opacity:.8;margin-bottom:10px}h1{font-size:54px;margin:0 0 30px;line-height:1.1}ul{font-size:27px;line-height:1.5;padding-left:28px;margin:0}li{margin:6px 0}
  code{background:#ffffff22;padding:2px 8px;border-radius:6px;font-size:24px}.term{background:#0d1117;color:#c9d1d9;border-radius:12px;padding:22px 26px;font:22px/1.5 Menlo,monospace;white-space:pre;margin-top:10px}
  .brand{position:absolute;bottom:40px;right:110px;font-size:20px;opacity:.85}</style></head><body><div class="k">${kicker || 'IntiMed'}</div><h1>${title}</h1>${lines.length && lines[0].startsWith('<div') ? lines.join('') : '<ul>' + li + '</ul>'}<div class="brand">IntiMed · Intelligent Medical Device Integration</div></body></html>`;
}
async function slide(video, title, lines, narration, kicker) {
  const p = await ctx.newPage(); await p.setViewportSize({ width: 1440, height: 900 });
  await p.setContent(slideHtml(title, lines, kicker)); await shotOf(p, video, title, narration); await p.close();
}
async function shotOf(p, video, title, narration) {
  n++; const file = `shot-${String(n).padStart(3, '0')}.png`;
  await p.screenshot({ path: path.join(OUT, 'shots', file) });
  manifest[video].push({ file, title, narration: narration.replace(/\s+/g, ' ').trim() });
  console.log(video, file, title);
}
async function go(route, wait = 1300) { await page.goto(BASE + '/#/' + route); await page.waitForTimeout(wait); }
async function shot(video, title, narration, opts = {}) { if (opts.scroll) { await page.evaluate(y => window.scrollTo(0, y), opts.scroll); await page.waitForTimeout(300); } await shotOf(page, video, title, narration); }
async function api(method, p, body) {
  return page.evaluate(async ([m, p, b]) => {
    const x = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) || '=').split('=')[1]);
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': x }, body: b === undefined ? undefined : JSON.stringify(b) });
    const t = await r.text(); try { return { status: r.status, body: JSON.parse(t) }; } catch { return { status: r.status, body: t }; }
  }, [method, p, body]);
}
async function login(user, video, narrate) {
  await page.goto(BASE + '/#/login'); await page.waitForTimeout(500);
  try { await api('POST', '/api/auth/logout'); } catch {}
  await page.goto(BASE + '/#/login'); await page.waitForSelector('#u');
  await page.fill('#u', user); await page.fill('#p', 'IntiMed-Demo-2026!');
  if (narrate) await shot(video, 'Sign in', narrate);
  await page.click('button[type=submit]'); await page.waitForTimeout(900);
  if (await page.$('#c')) {
    await page.waitForSelector('.otp');
    if (narrate) await shot(video, 'Second factor', 'Privileged roles need a second factor. Codes follow RFC 6238. In the demo profile only, a demo authenticator panel shows the current code, so you can sign in without a phone. In your own environment you add the TOTP secret to an authenticator app.');
    const code = (await page.textContent('.otp')).trim(); await page.fill('#c', code); await page.click('button[type=submit]');
  }
  await page.waitForSelector('.side', { timeout: 15000 }); await page.waitForTimeout(600);
}

(async () => {
  browser = await chromium.launch(); ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } }); page = await ctx.newPage();
  // ------------------------------------------------------------------ VIDEO 1 – platform tour
  const V = 'platform';
  await slide(V, 'IntiMed – Intelligent Medical Device Integration Platform', ['Any device by brand and model → the right field of any healthcare template', 'Configuration intelligence at every wizard step', 'Configuration-time exchange testing with a publish gate and sealed evidence', 'Security as a core feature – on a MacBook today, Dev / UAT / Prod tomorrow'],
    'IntiMed connects any medical device, by brand and model, to the exact fields of whatever template a healthcare product uses. Two capabilities set it apart: configuration intelligence that recommends the right configuration at every step, and configuration-time exchange testing that proves the data lands in the right template before anything is published. Security is built into every layer.', 'Platform tour');
  await slide(V, 'One canonical pipeline, every device', ['acquire → parse → normalise → associate → validate → route → persist → acknowledge', '11 connectivity options P1–P11: serial, vendor export, BLE, HL7 MLLP, POCT1-A/ASTM, cloud API, DICOM, FHIR, OCR, manual, SDC', '26 signed declarative driver packages, 22 host-agnostic templates', 'Mutual TLS on every device port – there is no plaintext port'],
    'Every device goes through the same pipeline: acquire, parse, normalise, associate, validate, route, persist and acknowledge. Device variety lives in signed, declarative driver packages, so a new device is configuration, not code. This local release ships twenty-six certified drivers covering all eleven connectivity options and all twenty-two reference templates. Every device port requires mutual TLS.', 'Architecture');
  await login('admin.device', V, 'We sign in as Dana, the device administrator. The console is served over TLS from a local certificate authority, with a session cookie, CSRF protection and a strict content security policy.');
  await go('overview', 1800);
  await shot(V, 'Overview', 'The overview shows the platform at a glance: the universal catalogue of two hundred and twenty-five devices in twenty-four families with FDA approval counts, the certified drivers, the templates, published devices, exchange tests with sealed evidence, readings filed, and open security alerts. Recent events stream in without any patient data.');
  await go('catalogue', 1800);
  await shot(V, 'Universal catalogue', 'The universal catalogue is searchable by brand, model, inventory identifier, FDA product code or protocol, and can be filtered by family, connectivity option and care setting. It is seeded with the worldwide inventory and one hundred and thirty-two FDA product codes, and grows through reviewed register feeds and requests.');
  await page.fill('input[aria-label=Search]', 'EasyOne'); await page.waitForTimeout(900); await page.click('table.grid tbody tr'); await page.waitForTimeout(1300);
  await shot(V, 'Model detail', 'Each model shows its connectivity matrix with a verification state per option, the certified driver packages with their signature status and publisher, regulatory status per FDA product code, and the vendor security posture. Certified in lab means a signed driver with golden sessions and harness evidence exists.');
  await page.keyboard.press('Escape'); await page.click('.modal-h button'); 
  const cfgs = (await api('GET', '/api/console/configs')).body;
  const tpl01 = cfgs.find(c => c.name.startsWith('TPL-01')) || cfgs[0];
  await go('wizard/' + tpl01.id + '/2', 1800);
  await shot(V, 'Wizard – connectivity option', 'The eight-step wizard guides the administrator from device to publication. In step two the option packs compare security, reliability, effort and latency. On the right, configuration intelligence recommends the option with the rule behind it: here rule R R zero one prefers HL7 over mutual TLS because the site has a gateway in the device zone.');
  await go('wizard/' + tpl01.id + '/4', 1800);
  await shot(V, 'Wizard – parameters and recommendations', 'Every parameter comes with a recommendation: its source, whether driver default, tenant policy or knowledge base, the rule, a confidence, and the reason why. Administrators accept, adjust or override with a reason, and each decision is audited. Tenant policy blocks insecure values such as plaintext MLLP regardless of any recommendation.');
  await go('wizard/' + tpl01.id + '/5', 1800);
  await shot(V, 'Wizard – association and bindings', 'Step five binds driver outputs to template fields, many to many. The binding editor is pre-filled from the reference library and highlights unbound mandatory fields and codes without LOINC. The association method follows the setting: encounter, bed and device binding for intensive care, worklists for clinics, consent for home devices.');
  await go('wizard/' + tpl01.id + '/6', 2500);
  await shot(V, 'Exchange test results', 'The exchange test is generated from the configuration and runs through the real device listeners, with the harness probe’s own client certificate and the family simulator. It verifies all sixteen checks, from the TLS handshake and identity binding to field coverage, units, round trip, latency, negative cases, security, quality gates and provenance.', { scroll: 250 });
  await slide(V, 'Sealed evidence and the publish gate', ['Evidence pack: manifest · results · template renderings · PHI-masked traces · timings · security', 'Seal: SHA-256 over every element, signed with the harness Ed25519 key, stored read-only', 'Publish gate: no UAT/PROD without a passing run for the current configuration hash', 'Four-eyes certification; Platform SRE approval for PROD'],
    'Each run produces an evidence pack whose elements are hashed and signed, then stored read-only and attached to the certification record. The publish gate is bound to the configuration hash: any technical change after a test requires a new passing run. Certification needs a second administrator, and promotion to production needs a platform SRE.', 'Exchange harness');
  await go('wizard/' + tpl01.id + '/8', 1800);
  await shot(V, 'Publish and promotion history', 'Publication goes from development to user acceptance to production, one stage at a time, with the diff recorded at each promotion. The change history shows every step, decision and approval with the configuration hash. Configurations can be exported after secret scanning, versioned, cloned to other sites or decommissioned in one action.');
  await go('fleet', 1800);
  await shot(V, 'Fleet and calibration', 'The fleet view shows every device by site, room, station and bed, with health, stage, endpoint status, calibration due dates and dead letters. Clinicians record two-tap calibration checks; a failed check locks the device out until biomedical engineering releases it.');
  await go('templates', 1600);
  await shot(V, 'Template definitions', 'Templates are host-agnostic definitions with field semantics: codes, UCUM units, ranges, mandatory flags and verification rules. The twenty-two reference templates cover acute care, laboratory, imaging, cardiology, specialist units, remote monitoring and occupational health, and host products can import their own forms as FHIR questionnaires.');
  await login('nurse.clinic');
  await go('capture', 2500);
  await shot(V, 'Capture from device', 'For clinicians it is one tap. The capture component is a web component that any host product embeds. It lists certified, in-calibration devices at the station, asks the device to measure, previews results with quality flags, and files them into the form after acceptance, with a fallback chain when a device is unavailable.');
  await page.goto(BASE + '/host-demo/?template=TPL-17&station=OH-AUDIO-1&subject=EMP-700101'); await page.waitForTimeout(2500);
  await shotOf(page, V, 'Embedded in a host product', 'Here the same component is embedded in a reference host product, an occupational health audiogram form. The host keeps its own user interface; IntiMed contributes capture and posts back through the template adapters, with provenance showing the driver, the configuration hash and the adapters used.');
  await login('soc.analyst');
  await go('security', 1800);
  await shot(V, 'Endpoint identities', 'The security console manages per-endpoint identities. A gateway or device host receives a single-use enrolment token, generates its key locally and enrols with a certificate signing request. Every connection is re-checked against the registry, so revocation and isolation take effect immediately.');
  await go('security/audit', 1800);
  await shot(V, 'Tamper-evident audit', 'Every action is written to a hash-chained audit trail and forwarded to the SIEM sink. Verification recomputes the chain end to end; any altered, deleted or reordered record breaks it. Details are masked so no patient data reaches the audit store.');
  await go('quality', 1800);
  await shot(V, 'Traceability', 'Traceability links epics, requirements and all one hundred and seventeen backlog items to the code and tests that implement them. Fifty-five items are delivered in this local release, forty-three are partially delivered pending cloud, devices or partners, and nineteen are deferred, each with the reason and the strategy for a future release.');
  await slide(V, 'Verified', ['51 automated tests: unit, end-to-end integration over real mTLS, architecture rules', 'Security test kit: 13 scripts, 69 checks, all passing', '22 template scenarios provisioned, tested, certified, published and live', 'Single node throughput: 613 messages per second through the full pipeline'],
    'Everything shown is verified automatically: fifty-one tests including end-to-end integration over real mutual TLS, a security test kit with sixty-nine checks, twenty-two template scenarios that are provisioned, tested, certified and live, and a measured throughput of six hundred and thirteen messages per second on a single node.', 'Quality');
  // ------------------------------------------------------------------ VIDEO 2 – demo execution steps
  const D = 'demo';
  await slide(D, 'Running the IntiMed demo pack', ['Download IntiMed-Demo-Pack-macOS-AppleSilicon.zip (or -Intel.zip)', 'Unzip in Finder', 'Right-click “Start IntiMed Demo.command” → Open (first time only)', 'Follow docs/IntiMed-Demo-Guide.html – scenarios D01 to D12'],
    'This video shows how to run the IntiMed demo pack on a Mac. Download the pack for your processor, Apple silicon or Intel, and unzip it. The first time, right-click the start command and choose open, because macOS asks you to confirm a file from the internet. Then follow the demo guide.', 'Demo pack');
  await slide(D, 'The launcher', ['<div class="term">$ Start IntiMed Demo.command\nIntiMed demo pack – data in ~/IntiMed-Demo-Data\nCreated a master key in your login Keychain (service intimed-demo).\nStarting.............\nIntiMed is running: https://localhost:9443\nTrust the IntiMed demo root CA in your login keychain? [y/N] y\nSign in as admin.device / IntiMed-Demo-2026!\nThe first start auto-provisions 20 template scenarios.</div>'],
    'The launcher uses the bundled Java runtime, so nothing needs to be installed. It creates a master key in your login keychain, starts the platform with the demo profile and opens the browser. You can let it trust the local root certificate to avoid browser warnings. The first start also auto-provisions twenty template scenarios in the background.', 'Step 1 · start');
  await login('admin.device', D, 'Sign in as admin dot device with the demo password from the guide. All people in the demo are synthetic.');
  await go('demo', 1800);
  await shot(D, 'Demo control', 'Demo control shows the twenty-two template scenarios. Twenty are already published and live after the first start. Spirometry and emergency triage are left for you to provision live, in scenarios D02 to D05. The run-all button executes every scenario end to end in one to two minutes.');
  // D02 – provision spirometer live
  const c = (await api('POST', '/api/console/configs', { modelId: 'IMD-001', name: 'OH clinic spirometer', site: { site: 'OH clinic', station: 'OH-SPIRO-1' } })).body;
  await go('catalogue', 1200); await page.fill('input[aria-label=Search]', 'EasyOne'); await page.waitForTimeout(900);
  await shot(D, 'D01 – find the device', 'Scenario D01: in the device catalogue, search for EasyOne and open the model. Check the connectivity matrix, the certified driver and the posture. Then choose provision this model to start the wizard.');
  await go('wizard/' + c.id + '/1', 1600);
  await shot(D, 'D02 – step 1', 'Scenario D02, step one: the wizard proposes the certified driver package for the EasyOne export. Accept the recommendation on the right or select the driver and save.');
  await api('PUT', `/api/console/configs/${c.id}/steps/1`, { driver: 'spiro.ndd.easyone-gdt@1.0.0' });
  await api('GET', `/api/console/configs/${c.id}/recommendations?step=2`); await go('wizard/' + c.id + '/2', 1500);
  await shot(D, 'D02 – step 2', 'Step two: the only certified option for this driver is P two, the vendor software export watcher. The vendor application stays the regulated measurement system; IntiMed reads its validated GDT export.');
  for (const s of [2, 3]) { await api('GET', `/api/console/configs/${c.id}/recommendations?step=${s}`); await api('POST', `/api/console/configs/${c.id}/accept-all?step=${s}`); }
  const eps = (await api('GET', '/api/security/endpoints')).body; const gw = eps.find(e => e.id === 'ep-demo-gateway');
  await api('PUT', `/api/console/configs/${c.id}/steps/3`, { endpointId: gw ? gw.id : null });
  await go('wizard/' + c.id + '/4', 1800);
  await shot(D, 'D02 – step 4', 'Steps three and four: choose the demo clinic gateway as the endpoint identity, then review the parameters. The service account is a vault reference, never a typed password. Use validate to see the pre-save policy checks before saving.');
  await api('PUT', `/api/console/configs/${c.id}/steps/4`, {});
  for (const s of [4, 5]) { await api('GET', `/api/console/configs/${c.id}/recommendations?step=${s}`); await api('POST', `/api/console/configs/${c.id}/accept-all?step=${s}`); }
  await api('PUT', `/api/console/configs/${c.id}/steps/5`, { expectedSerials: ['EASYONE-AIR-200381'] });
  await go('wizard/' + c.id + '/5', 1800);
  await shot(D, 'D02 – step 5', 'Step five: association by worklist with session fallback, the spirometry template bound field by field, and ATS ERS grading with calibration lockout enabled. In development stages you may accept all recommendations at once; in acceptance and production each one must be decided individually.');
  await api('PUT', `/api/console/configs/${c.id}/steps/6`, {});
  const run = (await api('POST', `/api/console/configs/${c.id}/test`)).body;
  await go('wizard/' + c.id + '/6', 2200);
  await shot(D, 'D03 – exchange test', 'Scenario D03: run the exchange test. In about two seconds all fifteen applicable checks pass: TLS 1.3 with mutual authentication, identity, parsing, association, delivery into a sandbox copy of the template, coverage, units, round trip, latency, negative cases, security, quality gates, provenance and the sealed evidence pack.', { scroll: 200 });
  await page.click('text=Verify seal'); await page.waitForTimeout(900);
  await shot(D, 'D03 – evidence seal', 'Download the evidence pack and verify the seal: every element is hashed, the manifest is signed with the harness key, and the server confirms the signature and the trusted key.');
  await page.click('.modal-h button');
  await api('POST', `/api/console/configs/${c.id}/submit`);
  const self = await api('POST', `/api/console/configs/${c.id}/approve`, { comment: 'self' });
  await go('wizard/' + c.id + '/7', 1500);
  await shot(D, 'D04 – four-eyes', 'Scenario D04: submit for certification. If Dana tries to approve her own configuration the platform refuses: an editor can never approve. ' + (self.status === 403 ? 'The refusal is recorded in the audit trail.' : ''));
  await login('approver.device');
  await api('POST', `/api/console/configs/${c.id}/approve`, { comment: 'Evidence reviewed' });
  await go('wizard/' + c.id + '/7', 1500);
  await shot(D, 'D04 – approval', 'Sign in as the approver, a second device administrator, and approve. The configuration becomes certified and a certification record is created with the configuration snapshot, the evidence seal, the approvals and the full recommendation trail.');
  await login('admin.device');
  await api('POST', `/api/console/configs/${c.id}/publish?stage=DEV`); await api('POST', `/api/console/configs/${c.id}/publish?stage=UAT`);
  const prod = await api('POST', `/api/console/configs/${c.id}/publish?stage=PROD`);
  await go('wizard/' + c.id + '/8', 1500);
  await shot(D, 'D04 – publish', 'Back as Dana, publish to development and then to user acceptance. Production is refused until a platform SRE approves the promotion, the four-eyes rule between device administration and operations. Sign in as sre, approve, and publish to production.');
  await login('sre'); await api('POST', `/api/console/configs/${c.id}/promotion-approval?stage=PROD`);
  await login('admin.device'); await api('POST', `/api/console/configs/${c.id}/publish?stage=PROD`);
  await login('nurse.clinic');
  await page.goto(BASE + '/#/capture'); await page.waitForTimeout(2200);
  const dev = await page.$('intimed-capture'); 
  const btn = await page.evaluateHandle(() => document.querySelector('intimed-capture').shadowRoot.querySelector('.dev .btn.primary'));
  if (btn) { await btn.asElement().click(); await page.waitForTimeout(2500); }
  await shot(D, 'D05 – capture', 'Scenario D05: sign in as the clinic nurse, open capture for the spirometry template at station O H spiro one for employee seven zero zero one zero two, and press capture. The device answers over mutual TLS and the values are filed into the form with grading and provenance.');
  await page.goto(BASE + '/host-demo/'); await page.waitForTimeout(2500);
  await shotOf(page, D, 'D05 – host form', 'Open the reference host page: the same capture component inside a host product form. The filed FEV one, FVC, ratio and peak flow appear with their units and the provenance line.');
  await go('fleet', 1500);
  await shot(D, 'D06 – calibration lockout', 'Scenario D06: in fleet and calibration, fail the spirometer calibration check. The device is locked out and capture is no longer offered until a device administrator releases it after recalibration. Every step is audited.');
  await page.goto(BASE + '/#/capture'); await page.waitForTimeout(800);
  await shot(D, 'D07 – verification and fallback', 'Scenario D07: select the vision template. Optical character recognition proposals from a display-only device are held until the nurse verifies them, and the fallback chain offers another certified device, photo with recognition, or manual entry with device attestation.');
  await login('soc.analyst'); await go('security/alerts', 1600);
  await shot(D, 'D08 – security operations', 'Scenario D08: the security analyst registers endpoints, sees alerts for refused connections, and runs the anomaly drill. A spot-check device streaming like a monitor is isolated automatically; release is refused until every bound configuration passes a new exchange test.');
  await login('dev.integrator'); await go('integration', 1500);
  await shot(D, 'D09 – interoperability', 'Scenario D09: the integration developer creates an API client with a least-privilege scope, calls the FHIR API from the explorer, and subscribes the reference host to signed webhooks. The host verifies each signature.');
  await login('admin.device'); await go('demo', 1500);
  await shot(D, 'D10 – all template scenarios', 'Scenario D10: in demo control, run all twenty-two template scenarios. Each one goes through the wizard with recommendations, the exchange test, four-eyes certification, publication to production and a live reading filed into its template.');
  await go('quality', 1500);
  await shot(D, 'D11 – traceability', 'Scenario D11: review traceability. Filter deferred to see backlog items that do not apply to a local MacBook scope, each with the reason and the strategy for a future release, and review the regulatory classification record.');
  await slide(D, 'D12 – security test kit', ['<div class="term">$ Run Security Test Kit.command\n== 02_tls_mtls_check.py            9 checks, 0 failed\n== 03_mllp_auth_and_robustness.py  7 checks, 0 failed\n== 04_fhir_api_authz.py           11 checks, 0 failed\n== 08 · 09 · 10 · 13 · 14 · 17 · 18 · 19 · 12 …\nGenerated · 69 checks · 69 passed · 0 failed\nReport: security-test-kit/results/REPORT.md</div>'],
    'Scenario D12: run the security test kit. It checks TLS and mutual TLS on every port, MLLP robustness, API authorisation, secrets, audit integrity, encryption at rest, default credentials, certificates, rate limits, tenant isolation, recommendation guardrails and the publish gate, and writes an evidence report. Stop the demo with the stop command; reset deletes all demo data.', 'Step 12 · verify');
  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
