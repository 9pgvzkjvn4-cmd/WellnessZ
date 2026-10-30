// Records the end-to-end demo: plays scenarios through the real demo apps (one shared session in one browser
// context), signs in as each persona, opens the screen that persona would use and takes a screenshot.
// Writes <out>/shots/*.png and <out>/manifest.json (title, caption, narration per shot) for the demo video.
// Usage: python3 demo/build.py && node demo/test/record-demo.js [outDir]
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const OUT = path.resolve(process.argv[2] || path.join(__dirname, '..', 'dist', 'recording'));
const FILES = { employee: 'employee-app.html', manager: 'manager-workspace.html', oh: 'oh-workspace.html', partner: 'partner-portal.html' };
const APP = { employee: 'Employee & Crew App', manager: 'Manager & HR Workspace', oh: 'OH Clinical Workspace', partner: 'Partner Portal' };
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });

const manifest = [];
const errors = [];
let n = 0;
let pages;
let ctx = {};

async function act(app, user, fn) {
  const r = await pages[app].evaluate(async ([u, src, c]) => {
    WZ.signIn(u);
    // eslint-disable-next-line no-new-func
    return await new Function('ctx', 'return (' + src + ')(ctx)')(c);
  }, [user, fn.toString(), ctx]);
  if (r && typeof r === 'object') Object.assign(ctx, r);
  return r;
}

async function shot(app, user, route, scenario, title, narration) {
  const p = pages[app];
  await p.evaluate(([u, r]) => {
    WZ.signIn(u);
    location.hash = '#/' + r;
  }, [user, route]);
  await p.waitForTimeout(900);
  if (app === 'partner') {
    // pass the demo MFA screen if the portal shows it
    const code = p.locator('input[autocomplete="one-time-code"], input[name="otp"], input[name="code"]');
    if (await code.count()) {
      await code.first().fill('246810');
      const btn = p.getByRole('button', { name: /verify|continue|sign in/i });
      if (await btn.count()) await btn.first().click();
      await p.waitForTimeout(600);
      await p.evaluate((r) => { location.hash = '#/' + r; }, route);
      await p.waitForTimeout(700);
    }
  }
  n += 1;
  const file = `shot-${String(n).padStart(3, '0')}.png`;
  await p.screenshot({ path: path.join(OUT, 'shots', file) });
  const u = await p.evaluate(() => (WZ.me && WZ.me()) || null);
  manifest.push({ file, scenario, app, appName: APP[app], user, userName: u && u.name, role: u && u.title, route, title, narration });
  console.log(`${file}  ${scenario}  ${APP[app]}  ${user}  #/${route}`);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  pages = {};
  for (const [k, f] of Object.entries(FILES)) {
    const p = await context.newPage();
    p.on('pageerror', (e) => errors.push(`${k}: ${e.message}`));
    p.on('console', (m) => { if (m.type() === 'error') errors.push(`${k}: ${m.text()}`); });
    await p.goto('file://' + path.join(DIST, f));
    pages[k] = p;
  }
  await pages.employee.setViewportSize({ width: 430, height: 900 });
  await pages.oh.evaluate(() => WZ.reset());
  await pages.oh.waitForTimeout(500);

  // ---------------------------------------------------------------- launcher
  const launcher = await context.newPage();
  await launcher.goto('file://' + path.join(DIST, 'index.html'));
  await launcher.waitForTimeout(600);
  n += 1;
  await launcher.screenshot({ path: path.join(OUT, 'shots', `shot-${String(n).padStart(3, '0')}.png`) });
  manifest.push({ file: `shot-${String(n).padStart(3, '0')}.png`, scenario: 'Start', app: 'launcher', appName: 'Demo launcher', route: '', title: 'The demo launcher',
    narration: 'The demo pack opens from one launcher page. Four separate applications, the employee and crew app, the manager and HR workspace, the occupational health clinical workspace and the partner portal, all read one synthetic demo data file and share one demo session in the browser. Nothing is installed and no server is needed.' });
  await launcher.close();

  // ---------------------------------------------------------------- S01
  const S01 = 'S01 · Incident to return to work';
  await act('manager', 'hse1', () => ({ s01: WZ.createReferral({ employeeId: 'E1004', source: 'EHS_INCIDENT', category: 'INJURY', concern: 'Lower back pain after lifting a 30 kg bag in the hold', location: 'Stand 12, Central Hub', consent: true }).id }));
  await shot('manager', 'hse1', 'incidents', S01, 'The safety officer reports an incident',
    'Scenario one starts in the manager and HR workspace. The health and safety officer reports a lifting injury for Rami Ramp. A case is opened in the occupational health intake queue, the nurses are notified, and Rami is told he has been referred.');
  await shot('oh', 'nurse1', 'queues?tab=intake', S01, 'The OH nurse sees it in the queue',
    'In the occupational health workspace, the nurse sees the new case in the work queue, with its service level timer, and triages it as semi-urgent using the musculoskeletal protocol.');
  await act('oh', 'nurse1', (c) => { WZ.triage(c.s01, { urgency: 'SEMI_URGENT', protocol: 'MSK_INJURY', recordable: true }); WZ.assign(c.s01, 'nurse1'); return {}; });
  await act('oh', 'physician1', (c) => { WZ.recordFitnessDecision(c.s01, { status: 'FIT_WITH_RESTRICTIONS', restrictions: ['NO_LIFT_10'] }); return {}; });
  await shot('oh', 'physician1', 'case/' + ctx.s01, S01, 'The physician records fitness with restrictions',
    'The occupational physician records the fitness decision: fit with restrictions, no lifting above ten kilograms. The full clinical timeline stays inside occupational health.');
  await act('oh', 'nurse1', (c) => ({ s01plan: WZ.createRtwPlan(c.s01, { phases: [{ duties: 'Light duties, no lifting' }, { duties: 'Normal duties' }] }).id }));
  await shot('manager', 'manager3', 'team', S01, 'The line manager sees status only',
    'The line manager sees exactly what is needed to plan the shift: fit with restrictions and the restriction itself. No diagnosis, no clinical notes. The platform never sends clinical fields to this role.');
  await act('employee', 'ramp1', (c) => { WZ.acknowledge(c.s01plan); return {}; });
  await shot('employee', 'ramp1', 'rtw', S01, 'The employee acknowledges the return-to-work plan',
    'On his phone, Rami sees his return to work plan in plain language and acknowledges it. When the manager acknowledges too, the plan becomes active.');
  await act('manager', 'manager3', (c) => { WZ.acknowledge(c.s01plan); return {}; });

  // ---------------------------------------------------------------- S02
  const S02 = 'S02 · Confidential mental-health support';
  await act('employee', 'pilot1', () => { const s = WZ.selfReferSupport({ concern: 'Poor sleep after a difficult rotation', contactPreference: 'PHONE', consent: true }); WZ.submitSupportScreen(s.id, [2, 2, 1, 2, 1, 1, 1, 1, 1]); return { s02: s.id }; });
  await shot('employee', 'pilot1', 'support', S02, 'A pilot asks for help confidentially',
    'Scenario two. A pilot asks for confidential support in the app, with emergency numbers and signposting on the same page. The screening questionnaire raises an urgent call-back. Only the named mental-health clinician will see the request.');
  await shot('oh', 'nurse1', 'employee/E1020', S02, 'A general OH nurse sees only a placeholder',
    'This is the key privacy rule. A general occupational health nurse opening the pilot\'s chart sees only that one restricted item exists. There is no reference and no content, and an attempt to open it is refused and audited.');
  await act('oh', 'psych1', (c) => { WZ.recordCallback(c.s02, null, { outcome: 'REACHED_SAFE' }); WZ.requestAmeRelief(c.s02); return {}; });
  await shot('oh', 'psych1', 'support/' + ctx.s02, S02, 'The named clinician works the case',
    'The named mental-health clinician sees the full case, records the urgent call-back, and asks the aviation medical examiner for a relief decision, passing a reference only.');
  await act('oh', 'ame1', (c) => { WZ.recordSupportFitness(c.s02, { status: 'TEMPORARILY_UNFIT' }); return {}; });
  await act('employee', 'pilot1', (c) => { WZ.shareFitnessOnly(c.s02); return {}; });
  await shot('manager', 'crewsched1', 'crew', S02, 'Crew scheduling sees the duty status only',
    'The examiner relieves the pilot from flying. Crew scheduling sees only that he is temporarily unfit and relieved, with no reason. The pilot chose to share fitness only with his manager, and that is exactly what the manager receives.');
  await shot('oh', 'dpo1', 'privacy/audit', S02, 'The DPO sees every access in the audit log',
    'The data protection officer sees every action, including the refused access attempt, in a tamper-evident audit log whose hash chain can be verified on screen.');

  // ---------------------------------------------------------------- S03
  const S03 = 'S03 · Injury, partner referral and claim';
  const caseId = await pages.oh.evaluate(() => { WZ.signIn('nurse1'); return WZ.list('cases').find((c) => c.employeeId === 'E1007' || /^ENG-\d{4}-000104$/.test(c.id)).id; });
  ctx.s03case = caseId;
  await act('oh', 'nurse1', (c) => { WZ.createTreatmentPlan(c.s03case, { goals: ['Restore grip strength'], expectedRecoveryDays: 28 }); return { s03ref: WZ.sendPartnerReferral({ caseId: c.s03case, partnerOrg: 'PHYSIOPLUS', service: 'PHYSIO_ASSESS', urgency: 'URGENT', question: 'Assess and treat hand injury', consent: true }).id }; });
  await shot('oh', 'nurse1', 'case/' + ctx.s03case, S03, 'Treatment plan and partner referral',
    'Scenario three. For an engineer\'s hand injury, the nurse builds a treatment plan and sends an electronic referral to a contracted physiotherapy partner, with the employee\'s consent.');
  await shot('partner', 'physio1', 'referrals', S03, 'The partner sees only its own referrals',
    'In the partner portal, behind multi-factor sign-in, the physiotherapy provider sees the referral in its inbox. Another partner organisation cannot see it at all.');
  await act('partner', 'physio1', (c) => { WZ.partnerAccept(c.s03ref, {}); WZ.partnerUploadReport(c.s03ref, { summary: 'Soft tissue injury, improving', recommendation: 'Light duties for two weeks', fileName: 'physio-report.pdf' }); return {}; });
  await act('oh', 'claims1', (c) => ({ s03claim: WZ.openClaim({ caseId: c.s03case, payerOrg: 'GULFTPA', lostTimeDays: 3, reserve: 2500, consent: true }).id }));
  await act('partner', 'adjuster1', (c) => { WZ.adjusterDecision(c.s03claim, { decision: 'ACCEPTED', note: 'Work-related injury confirmed' }); return {}; });
  await shot('partner', 'adjuster1', 'claim/' + ctx.s03claim, S03, 'The TPA adjuster decides the claim',
    'The partner accepts, books the appointment and uploads the report. The claims coordinator opens a workers\' compensation claim, and the third-party adjuster decides it in the same portal, seeing non-clinical documents only.');
  await shot('manager', 'hr1', 'claims', S03, 'HR sees the claim status only',
    'Human resources sees the claim status, nothing clinical and nothing financial beyond what its role needs.');

  // ---------------------------------------------------------------- S04
  const S04 = 'S04 · Drug and alcohol testing';
  await act('manager', 'damanager1', () => ({ s04: WZ.randomSelection('POOL-GND-ENG', { count: 2 }).testIds[0] }));
  await shot('manager', 'damanager1', 'testing', S04, 'An auditable random selection',
    'Scenario four. The testing programme manager runs a seeded random selection that anyone can reproduce later. The programme manager never sees a result.');
  await shot('employee', 'baggage1', 'testing', S04, 'The employee is told only where and when',
    'The selected employee receives a notice in the app saying only where and when to report. No word like drug or alcohol appears in any notification.');
  await act('oh', 'collector1', (c) => { WZ.collectSpecimen(c.s04, { seals: 'Intact' }); return {}; });
  await act('oh', 'mro1', (c) => { WZ.labResult(c.s04, { result: 'NON_NEGATIVE', analyte: 'Cannabinoids (demo)' }); WZ.mroVerdict(c.s04, { outcome: 'POSITIVE' }); return {}; });
  await shot('oh', 'mro1', 'testing/mro', S04, 'Only the MRO sees the result',
    'The collector records the specimen with an electronic chain of custody. The simulated laboratory result reaches one person only, the medical review officer, who verifies it.');
  await shot('manager', 'hr1', 'duty-status', S04, 'HR sees the duty status only',
    'The employee is removed from safety-sensitive duty and a confidential support referral opens automatically. Human resources and the manager see the duty status, never the substance or the result.');

  // ---------------------------------------------------------------- S08
  const S08 = 'S08 · Communicable disease and contact tracing';
  await act('oh', 'physician1', () => { const cd = WZ.openCommunicableCase({ employeeId: 'E1011', condition: 'Measles (suspected, demo)', exclusionDays: 5, incubationDays: 21 }); WZ.contactTrace(cd.id, { windowDays: 7 }); return { s08: cd.id }; });
  await shot('oh', 'physician1', 'communicable/' + ctx.s08, S08, 'Contact tracing from the roster',
    'Scenario eight. A suspected measles case in a flight attendant. The physician excludes him from work and traces contacts from the flight roster and shift data in seconds.');
  await shot('employee', 'crew1', 'home', S08, 'Contacts are told without naming the case',
    'A crew member who flew with him receives an exposure notice and daily check-ins, without being told who the case is.');
  await act('oh', 'physician1', (c) => { WZ.reportPublicHealth(c.s08); return {}; });

  // ---------------------------------------------------------------- S10
  const S10 = 'S10 · Employee assistance programme';
  await act('employee', 'office1', () => ({ s10: WZ.selfReferSupport({ concern: 'Work and life stress', contactPreference: 'EMAIL', consent: true }).id }));
  await act('oh', 'psych1', (c) => { WZ.addSupportNote(c.s10, 'Initial call, low risk.'); return { s10ref: WZ.sendPartnerReferral({ supportCaseId: c.s10, partnerOrg: 'EAP_WELLBEING', service: 'EAP_COUNSELLING', consent: true }).id }; });
  await shot('partner', 'eap1', 'eap', S10, 'The EAP partner sees name and contact preference only',
    'Scenario ten. The named clinician refers an employee to the employee assistance programme. The partner sees only the name and contact preference.');
  await act('partner', 'eap1', (c) => { WZ.partnerAccept(c.s10ref, {}); WZ.partnerRecordAttendance(c.s10ref, { sessions: 3 }); WZ.partnerClose(c.s10ref, { outcome: 'COMPLETED' }); return {}; });
  await shot('partner', 'eap1', 'referral/' + ctx.s10ref, S10, 'Attendance and a coded outcome – no notes',
    'The partner records attendance and closes with a coded outcome. Free text is not accepted, so no counselling notes ever come back into the platform.');
  await shot('employee', 'office1', 'privacy', S10, 'The employee sees every disclosure',
    'Finally, the employee can see in the app every time their information was shared, with whom, and on what basis. That transparency is part of the design.');

  await browser.close();
  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
  console.log(`\n${manifest.length} screenshots -> ${OUT}`);
  if (errors.length) { console.error('Console errors:\n' + errors.join('\n')); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(2); });
