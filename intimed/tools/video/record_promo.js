// Records the IntiMed 2.0 promotional videos (one per user group) as screenshot + narration manifests from a running
// demo instance. Compose with: python3 compose.py <outDir> <voice.onnx> <videosDir>
// Usage: NODE_PATH=$(npm root -g) node record_promo.js <outDir> [baseUrl]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const OUT = path.resolve(process.argv[2] || 'promo'); const BASE = process.argv[3] || 'https://localhost:9443';
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
const NAMES = { exec: 'IntiMed-2.0-for-Executives', clin: 'IntiMed-2.0-for-Clinicians', biomed: 'IntiMed-2.0-for-Device-Administrators-and-Biomed',
  sec: 'IntiMed-2.0-for-Security-and-CISO', dev: 'IntiMed-2.0-for-Developers-and-Partners', qa: 'IntiMed-2.0-for-Quality-and-Regulatory' };
const manifest = { _names: NAMES, exec: [], clin: [], biomed: [], sec: [], dev: [], qa: [] };
let n = 0, page, ctx, browser;
const ACCENT = { exec: '#0b5fff', clin: '#0d7a6a', biomed: '#6a3fd1', sec: '#b42318', dev: '#0b7285', qa: '#8a5a00' };

function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;'); }
function slideHtml(video, title, lines, kicker) {
  const body = lines.length && lines[0].startsWith('<') ? lines.join('') : '<ul>' + lines.map(l => `<li>${esc(l)}</li>`).join('') + '</ul>';
  return `<!doctype html><html><head><style>
  body{margin:0;width:1440px;height:900px;background:linear-gradient(135deg,#0b1f3a 0%,${ACCENT[video]} 120%);font-family:-apple-system,Segoe UI,Roboto,sans-serif;color:#fff;display:flex;flex-direction:column;justify-content:center;padding:0 110px;box-sizing:border-box}
  .k{letter-spacing:.18em;text-transform:uppercase;font-size:18px;opacity:.85;margin-bottom:10px}h1{font-size:56px;margin:0 0 30px;line-height:1.1}ul{font-size:28px;line-height:1.5;padding-left:28px;margin:0}li{margin:6px 0}
  .big{display:flex;gap:28px;margin-top:10px}.big div{background:#ffffff1c;border-radius:16px;padding:22px 26px;min-width:200px}.big b{display:block;font-size:52px}.big span{font-size:20px;opacity:.9}
  .term{background:#0d1117;color:#c9d1d9;border-radius:12px;padding:22px 26px;font:21px/1.5 Menlo,monospace;white-space:pre;margin-top:10px}
  .brand{position:absolute;bottom:40px;right:110px;font-size:20px;opacity:.85}.aud{position:absolute;top:40px;right:110px;font-size:18px;background:#ffffff22;border-radius:99px;padding:6px 16px}</style></head>
  <body><div class="aud">${esc(NAMES[video].replace('IntiMed-2.0-for-', 'For ').replace(/-/g, ' '))}</div><div class="k">${esc(kicker || 'IntiMed 2.0')}</div><h1>${esc(title)}</h1>${body}<div class="brand">IntiMed 2.0 · Intelligent Medical Device Integration</div></body></html>`;
}
function big(items) { return '<div class="big">' + items.map(([v, l]) => `<div><b>${esc(v)}</b><span>${esc(l)}</span></div>`).join('') + '</div>'; }
async function slide(video, title, lines, narration, kicker) {
  const p = await ctx.newPage(); await p.setViewportSize({ width: 1440, height: 900 });
  await p.setContent(slideHtml(video, title, lines, kicker)); await shotOf(p, video, title, narration); await p.close();
}
async function shotOf(p, video, title, narration) {
  n++; const file = `shot-${String(n).padStart(3, '0')}.png`;
  await p.screenshot({ path: path.join(OUT, 'shots', file) });
  manifest[video].push({ file, title, narration: narration.replace(/\s+/g, ' ').trim() });
  console.log(video, file, title);
}
async function go(route, wait = 1500) { await page.goto(BASE + '/#/' + route); await page.waitForTimeout(wait); await page.evaluate(() => window.scrollTo(0, 0)); }
async function shot(video, title, narration, scroll) {
  if (scroll !== undefined) { await page.evaluate(y => window.scrollTo(0, y), scroll); await page.waitForTimeout(300); }
  await shotOf(page, video, title, narration);
}
async function scrollTo(selectorText) {
  await page.evaluate(t => { const el = [...document.querySelectorAll('main h3, main h1')].find(e => e.textContent.includes(t)); if (el) el.scrollIntoView({ block: 'start' }); window.scrollBy(0, -70); }, selectorText);
  await page.waitForTimeout(300);
}
const sel = (label) => page.locator(`main label.field:has(> span:text-is("${label}")) select`);
async function pick(label, value) { await sel(label).selectOption(value); await page.waitForTimeout(250); }
async function click(text) { await page.locator('main button', { hasText: text }).first().click(); await page.waitForTimeout(2200); }
async function api(method, p, body) {
  return page.evaluate(async ([m, p, b]) => {
    const x = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) || '=').split('=')[1]);
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': x }, body: b === undefined ? undefined : JSON.stringify(b) });
    const t = await r.text(); try { return { status: r.status, body: JSON.parse(t) }; } catch { return { status: r.status, body: t }; }
  }, [method, p, body]);
}
async function login(user) {
  await page.goto(BASE + '/#/login'); await page.waitForTimeout(400);
  try { await api('POST', '/api/auth/logout'); } catch {}
  await page.goto(BASE + '/#/login'); await page.waitForSelector('#u');
  await page.fill('#u', user); await page.fill('#p', 'IntiMed-Demo-2026!');
  await page.click('button[type=submit]'); await page.waitForTimeout(900);
  if (await page.$('#c')) { await page.waitForSelector('.otp'); const code = (await page.textContent('.otp')).trim(); await page.fill('#c', code); await page.click('button[type=submit]'); }
  await page.waitForSelector('.side', { timeout: 15000 }); await page.waitForTimeout(500);
}
async function validate(driver, tpl, profile, category) {
  await go('intelligence/validate', 2500);
  await pick('Driver (device model · option)', driver); await pick('Template', tpl);
  if (category) await pick('Healthcare-platform category', category);
  await pick('Source', 'cohort'); await pick('Cohort', profile);
  await click('Validate & render'); await page.waitForTimeout(800);
}

(async () => {
  browser = await chromium.launch(); ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } }); page = await ctx.newPage();
  // ------------------------------------------------------------------ preparation (state shown in several videos)
  await login('admin.device');
  await api('POST', '/api/demo/drift-drill');

  // ================================================================== EXECUTIVES
  let V = 'exec';
  await slide(V, 'Every device. Every template. Proven before go-live.', ['Medical device data still reaches records by hand in too many places', 'Each new device is a custom interface project', 'IntiMed 2.0 turns device integration into configuration – with intelligence and evidence'],
    'Medical device data still reaches health records by hand in too many places, and every new device becomes its own interface project. IntiMed two point zero turns device integration into configuration, adds clinical and integration intelligence, and proves every integration before it goes live.', 'For executives');
  await slide(V, 'What the platform covers today', [big([['225', 'device models catalogued'], ['26', 'signed drivers'], ['11', 'connectivity options'], ['22', 'host templates']])],
    'The local release catalogues two hundred and twenty-five device models across twenty-four families, ships twenty-six signed drivers that cover all eleven connectivity options, and fills twenty-two host-agnostic templates, from the ICU flowsheet to occupational health.', 'Scope');
  await go('overview', 1800);
  await shot(V, 'One console for the whole lifecycle', 'One console covers the whole lifecycle: find the device, provision it with recommendations, run the exchange test, certify with four eyes, publish, and monitor.');
  await validate('monitor.philips.intellivue-pcd01@1.0.0', 'TPL-01', 'adult-sepsis-like');
  await scrollTo('Mapped output template');
  await shot(V, 'New in 2.0: clinical intelligence', 'New in version two: every number that reaches the record carries the right reference range for the person, by age group, sex, pregnancy and context, and a standard flag. Here a sixty-four year old shows high heart rate, high respiratory rate, fever and low oxygen saturation – exactly as the host receives it.');
  await slide(V, 'Measured, not promised', [big([['100 %', 'device identification (golden sessions)'], ['100 %', 'auto-mapping recall'], ['100 %', 'cohort flag precision'], ['69/69', 'security checks']])],
    'The intelligence is measured, not promised. On the shipped content, device identification, auto-mapping and cohort flag precision all reach one hundred percent in automated tests, and the security test kit passes all sixty-nine checks. Third-party devices will score lower, which is why low-confidence suggestions always go to a human.', 'Evidence');
  await go('demo', 1800);
  await shot(V, 'Twenty templates live in minutes', 'In the demo, twenty template scenarios provision themselves end to end in about a minute: recommendations, exchange test, certification, production and a live reading. The last two are left for you to run live.');
  await slide(V, 'Why it matters', ['Fewer manual transcriptions – ≥ 1 minute saved per acute reading, ≥ 3 minutes per occupational-health test (pilot targets)', 'Onboarding a device model becomes configuration plus evidence, not a project', 'Security and regulatory evidence produced as a by-product', 'Roadmap: interval governance, host demographics, terminology server, ML drift'],
    'Why it matters: fewer manual transcriptions, with pilot targets of at least one minute saved per acute reading and three minutes per occupational health test. Onboarding a device becomes configuration plus evidence, and security and regulatory evidence comes as a by-product. The roadmap adds interval governance, host demographics, a terminology server and machine-learning drift detection.', 'Value');
  await slide(V, 'Try it on a MacBook today', ['One universal demo pack – Apple silicon and Intel', 'Double-click to start · synthetic data only', 'Guided scenarios for every audience'],
    'You can try it on a MacBook today. One universal demo pack runs on Apple silicon and Intel, starts with a double click, and uses synthetic data only.', 'Next step');

  // ================================================================== CLINICIANS
  V = 'clin';
  await slide(V, 'Device values in your form – with the right range for your patient', ['No re-typing from the device screen', 'Reference ranges for the patient’s age group, sex and pregnancy', 'Standard flags: N · L · H · LL · HH', 'You stay in control: verify-before-post where it matters'],
    'IntiMed puts device values straight into your form, with the right reference range for your patient: by age group, sex and pregnancy. Flags use the same codes as laboratory reports, and you stay in control wherever verification matters.', 'For clinicians');
  await validate('monitor.philips.intellivue-pcd01@1.0.0', 'TPL-01', 'infant-hr-140');
  await scrollTo('Mapped output template');
  await shot(V, 'A 6-month-old with a heart rate of 140', 'A six-month-old infant with a heart rate of one hundred and forty. For an infant, that is normal: the range shown is the infant band, and the flag is N.');
  await validate('monitor.philips.intellivue-pcd01@1.0.0', 'TPL-01', 'child-hr-140');
  await scrollTo('Mapped output template');
  await shot(V, 'The same 140 in an 8-year-old', 'The same value in an eight-year-old is high for age, so the flag is H. The value did not change; the person did. Every range shows its partition and its source.');
  await go('intelligence/ranges', 2200);
  await pick('Parameter', 'haemoglobin');
  await page.fill('main label.field:has(> span:text-is("Value")) input', '10.8'); await page.fill('main label.field:has(> span:text-is("Age (years)")) input', '29');
  await pick('Sex', 'female'); await pick('Pregnancy', '2'); await click('Evaluate');
  await shot(V, 'Pregnancy changes the interval', 'Pregnancy changes the interval too. A haemoglobin of ten point eight in the second trimester is normal under the WHO twenty twenty-four thresholds, while the same value in a non-pregnant woman would be flagged low.');
  await validate('vitals.welchallyn.csm-pcd01@1.0.0', 'TPL-02', 'adult-hypertensive-crisis', 'PRIMARY_CARE');
  await scrollTo('Mapped output template');
  await shot(V, 'Grades you already use', 'Grades you already use appear next to the flag – for example the ACC A H A blood-pressure category. A reading of one eighty-six over one twelve is flagged as critical high and graded hypertensive crisis.');
  await login('nurse.icu'); await go('capture', 2000);
  await pick('Template', 'TPL-01');
  await page.locator('main label.field:has(> span:text-is("Station / bed")) input').fill('BED-07');
  await page.locator('main label.field:has(> span:text-is("Station / bed")) input').dispatchEvent('change');
  await pick('Subject', 'MRN-100231'); await page.waitForTimeout(1500);
  await page.locator('intimed-capture button.btn.primary').first().click(); await page.waitForTimeout(3500);
  await page.evaluate(() => { const el = document.querySelector('intimed-capture'); if (el) el.scrollIntoView({ block: 'start' }); window.scrollBy(0, -60); });
  await shot(V, 'Capture at the point of care', 'At the point of care, the ICU nurse picks the flowsheet for bed seven and presses capture. The monitor answers over a secure connection and the values are filed into the form, each with the range for this patient and its flag – here a systolic of one hundred and twenty is flagged H and graded elevated. Values from screens or paper are held until you verify them.');
  await slide(V, 'Flags inform – they never alarm', ['IntiMed raises no alarms and makes no diagnosis', 'Critical flags (LL/HH) go to your host’s own notification workflow', 'Default ranges are replaced by your laboratory’s verified ranges before clinical use'],
    'Flags inform, they never alarm. IntiMed makes no diagnosis; critical flags travel to your host system’s own workflow, and your organisation replaces the default ranges with its laboratory’s verified ranges before clinical use.', 'Safety');

  // ================================================================== DEVICE ADMINISTRATORS / BIOMED
  V = 'biomed';
  await slide(V, 'Know exactly what every device is – and how it connects', ['Identify device type, model, option and driver from one message', 'Integration profile per model: protocol, transport, security, gateway', 'Physiology-aware simulators before the device arrives', 'Drift alerts for calibration and probe problems'],
    'For device administrators and biomedical engineers, IntiMed two point zero knows exactly what each device is and how it connects. It identifies the device from a single message, publishes an integration profile per model, simulates realistic patients before the device arrives, and raises drift alerts for calibration or probe problems.', 'For device administrators & biomed');
  await login('admin.device');
  await go('catalogue', 2000);
  await shot(V, 'Universal catalogue', 'The catalogue lists every model with its family, connectivity options, F D A product codes, security posture and certification status per option.');
  await go('intelligence/identify', 2200);
  await pick('Load a sample from', 'anaesthesia.ge.aisys-pcd01@1.0.0'); await click('Load sample'); await click('Identify');
  await shot(V, 'Paste a message – get the device', 'Paste any captured message. IntiMed identifies the driver, model, connectivity option and device type, with a confidence score and the evidence: grammar, keys recovered, sending application and serial-number pattern.');
  await scrollTo('Integration profile');
  await shot(V, 'Precise integration profile', 'The integration profile shows the device type with its IEEE eleven-oh-seven-three specialisation and data class, every connectivity mechanism with protocol, transport and grammar, and the mapping precision of each signed driver.');
  await go('intelligence/types', 2000);
  await shot(V, 'A device-type taxonomy for the whole fleet', 'Forty-one device types classify all two hundred and twenty-five catalogue models, each with its basis and confidence, so fleet reports, rules and templates can work by device type.');
  await go('intelligence/cohorts', 2200);
  await pick('Cohort', 'neonatal-hypothermia'); await page.waitForTimeout(800);
  await page.locator('main label.chk', { hasText: 'NOISE' }).locator('input').check();
  await click('Simulate');
  await shot(V, 'Simulate the patients you will see', 'The cohort simulator renders realistic payloads in the device’s own protocol and units – here a two-day-old neonate with a low skin temperature on the incubator – with optional noise, spikes, drift, dropouts, duplicates and clock skew, and checks the expected flags end to end.');
  await go('runs', 2000);
  await shot(V, 'Exchange test with XT-17', 'Every exchange test now includes XT seventeen, clinical reference validation, next to transport security, coverage, units, round trip and quality gates. The results are sealed in the evidence pack.');
  await go('intelligence/drift', 2000);
  await shot(V, 'Drift: a data-quality signal', 'Drift monitoring compares each configuration with its fleet and with the patient’s reference range. In the drill, a hospital-at-home oximeter probe drifts and two alerts are raised for the biomedical team – a data-quality signal, never a clinical alarm.');

  // ================================================================== SECURITY / CISO
  V = 'sec';
  await slide(V, 'Intelligence without new attack surface', ['Mutual TLS on every device port – no plaintext port exists', 'Signed driver packages, local PKI, encrypted PHI at rest', 'Intelligence APIs: role-checked, size-limited, audited by payload hash', 'Dry runs persist nothing; identities are masked'],
    'For security leaders: IntiMed two point zero adds intelligence without adding attack surface. Every device port requires mutual TLS, drivers are signed, patient data is encrypted at rest, and the new intelligence APIs are role-checked, size-limited and audited by payload hash only. Dry runs persist nothing and mask identities.', 'For security & CISO');
  await login('soc.analyst');
  await go('security', 2000);
  await shot(V, 'Security console', 'The security console shows endpoints with certificate status, alerts, the anomaly rules A N one to A N eight, and the audit chain.');
  await go('security/alerts', 1800);
  await shot(V, 'Detect, isolate, re-certify', 'Anomalies such as a spot-check device streaming like a monitor are isolated automatically, and release is refused until every bound configuration passes a new exchange test.');
  await go('security/audit', 1800);
  await shot(V, 'Tamper-evident audit', 'Every action, including each identification and validation in the intelligence studio, is written to an H MAC-chained audit trail that a S I E M can ingest. The chain verifies as intact.');
  await slide(V, 'Security test kit', ['<div class="term">$ Run Security Test Kit.command\n== 02 TLS / mTLS on every port         PASS\n== 03 MLLP auth & robustness           PASS\n== 04 FHIR API authorisation           PASS\n== 08 secrets · 09 audit · 10 at-rest  PASS\n== 14 rate limits · 17 tenant isolation PASS\n== 18 guardrails · 19 publish gate     PASS\nGenerated · 69 checks · 69 passed · 0 failed</div>'],
    'The security test kit ships in the demo pack. It checks TLS and mutual TLS on every port, MLLP robustness, API authorisation, secrets, audit integrity, encryption at rest, rate limits, tenant isolation, recommendation guardrails and the publish gate. Version two passes all sixty-nine checks.', 'Verify yourself');
  await slide(V, 'Privacy by design in 2.0', ['Birth date and sex stored only in the encrypted subject registry', 'Studio shows masked identity; no payload in audit records', 'Reference-range flags switchable per configuration', 'Regulatory impact assessment documented'],
    'Privacy by design continues in version two: birth date and sex live only in the encrypted subject registry, the studio shows masked identities, audit records never contain payloads, and flagging can be switched off per configuration.', 'Privacy');

  // ================================================================== DEVELOPERS / PARTNERS
  V = 'dev';
  await slide(V, 'Standards in, standards out', ['FHIR R4 Observation with referenceRange + interpretation', 'HL7 v2.6 ORU with OBX-7 range and OBX-8 flag', 'REST, webhooks (HMAC), OAuth2 client credentials (ES256)', 'Declarative signed drivers, SDK CLI, cohort simulator for CI'],
    'For developers and partners: standards in, standards out. FHIR observations now carry reference range and interpretation, HL7 version two carries OBX seven and OBX eight, and everything is reachable through OAuth two protected APIs and signed webhooks.', 'For developers & partners');
  await login('dev.integrator');
  await validate('lab.roche.cobas-astm@1.0.0', 'TPL-06', 'diabetic-hyperglycaemia', 'LIS');
  await scrollTo('FHIR R4');
  await shot(V, 'Exactly what your host receives', 'The validation studio shows exactly what your host receives: the FHIR bundle with reference range and interpretation, and the HL7 message with the range in OBX seven and the flag in OBX eight.');
  await go('intelligence/automap', 2000);
  await pick('Driver', 'vitals.welchallyn.csm-pcd01@1.0.0'); await pick('Template', 'TPL-02'); await click('Suggest');
  await shot(V, 'Auto-mapping with explanations', 'Bring your own template – a FHIR questionnaire or a host schema – and the auto-mapper proposes bindings with a confidence and an explanation: code match, unit compatibility and label similarity. Anything below zero point five stays unbound for a human.');
  await go('intelligence/cohorts', 2000);
  await click('Run all cohorts');
  await shot(V, 'Use the cohort simulator in CI', 'Use the cohort simulator in your pipeline: thirty-three cohorts from neonate to older adult run through the real drivers and pipeline, and the precision report shows every expected flag met.');
  await go('integration', 1800);
  await shot(V, 'APIs and webhooks', 'Create a least-privilege API client, call the FHIR API from the explorer, and subscribe to signed webhooks. The new intelligence endpoints live under slash api slash intelligence.');
  await slide(V, 'Build a driver, not an interface', ['<div class="term">$ java -jar intimed-platform.jar sdk validate my-driver.json\n$ java -jar intimed-platform.jar sdk test my-driver.json\n$ java -jar intimed-platform.jar sdk simulate my-driver.json\n$ curl -X POST /api/intelligence/validate \\\n    -d \'{"driver":"…","templateId":"TPL-02","profile":"infant-hr-140"}\'</div>'],
    'New devices are declarative driver packages: manifest, transport, grammar, semantics, bindings, tests. Validate, test and simulate them with the SDK, then call the validation API with any cohort to see the result.', 'SDK');

  // ================================================================== QUALITY / REGULATORY
  V = 'qa';
  await slide(V, 'Evidence you can audit', ['Every requirement traced to code and tests', 'Every reference interval cited – WHO, PALS, ACC/AHA, AASM, CFR', 'XT-17 sealed in the evidence pack before publication', 'Regulatory impact of flagging documented'],
    'For quality and regulatory teams, IntiMed two point zero produces evidence you can audit: requirements traced to code and tests, every reference interval cited, clinical reference validation sealed in each evidence pack, and the regulatory impact of flagging documented.', 'For quality & regulatory');
  await login('regulatory');
  await go('quality', 2000);
  await shot(V, 'Traceability and delivery status', 'The traceability view lists every backlog item with status, evidence or the reason it is deferred and the strategy to deliver it, plus the classification record.');
  await go('intelligence/ranges', 2200);
  await shot(V, 'Cited reference intervals', 'Each of the thirty-three parameters shows its partitions and its source. The disclaimer is explicit: these are defaults for integration validation, to be replaced by locally verified intervals following C L S I E P twenty-eight before clinical use.');
  await scrollTo('Knowledge base');
  await shot(V, 'Knowledge base and gaps', 'Where no interval exists for a partition, for example paediatric creatinine, IntiMed shows a gap note instead of guessing.');
  await go('intelligence/categories', 2000);
  await shot(V, 'Category rule packs', 'Eight healthcare-platform categories define the validation rule pack and the interpretation policy: laboratory results must carry a range, remote monitoring requires consent, occupational health reports regulatory consequences, and wellness stays informational.');
  await slide(V, 'Regulatory position', ['Flags are informational – no diagnosis, no alarm, no notification', 'Switchable per configuration (referenceRangeFlagging=false)', 'May move the product beyond MDDS in some markets (FDA CDS 2022, EU MDR Rule 11)', 'Assessment + local interval verification required before production'],
    'The regulatory position is stated plainly: flags are informational and switchable per configuration. Because flagging may move the product beyond the medical device data system category in some markets, a documented assessment and local interval verification are required before production use.', 'Classification');
  await slide(V, 'Verified', [big([['92', 'automated tests'], ['33', 'cohort flag cases'], ['17', 'exchange checks'], ['69', 'security checks']])],
    'Verified by ninety-two automated tests including thirty-three cohort flag cases, seventeen exchange checks per configuration, and sixty-nine security checks – all passing in this release.', 'Verification');

  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
