/*
 * WellnessZ DEMO PACK - demo engine (the offline "backend").
 *
 * One shared demo session lives in localStorage under `wellnessz-demo-session`. All four
 * apps opened from the same folder in the same browser read and write that one session;
 * the browser 'storage' event keeps every open tab in sync.
 *
 * Public API: window.WZ (see demo/DEVELOPER-NOTES.md). Runs in the browser and in Node
 * (for demo/test/engine.test.js); in Node an in-memory storage is used unless a
 * localStorage shim is present on globalThis.
 *
 * All data is synthetic. Rules mirror the real platform in simplified form:
 *  - managers / HR / rostering see status only (never clinical detail);
 *  - RESTRICTED_CLINICAL (mental health, substance) is visible to the named psych clinician
 *    or the MRO only; other OH users get a placeholder count and an audited DENIED;
 *  - partners see only their own organisation's records;
 *  - aggregates are small-cell suppressed.
 */
(function (root) {
  'use strict';

  var VERSION = '1.0.0';
  var SCHEMA = 1;
  var KEY = 'wellnessz-demo-session';
  var FORMAT = 'wellnessz-demo-session';

  var WZ = {};

  var APPS = {
    employee: { file: 'employee-app.html', title: 'Employee & Crew App', short: 'Employee app' },
    manager: { file: 'manager-workspace.html', title: 'Manager & HR Workspace', short: 'Manager workspace' },
    oh: { file: 'oh-workspace.html', title: 'OH Clinical Workspace', short: 'OH workspace' },
    partner: { file: 'partner-portal.html', title: 'Partner Portal', short: 'Partner portal' },
    launcher: { file: 'index.html', title: 'Demo launcher', short: 'Launcher' }
  };

  // =====================================================================================
  // Errors
  // =====================================================================================
  function WZError(code, message, detail) {
    var e = new Error(message || code);
    e.name = 'WZError'; e.code = code; e.detail = detail || null;
    return e;
  }
  function fail(code, message, detail) { throw WZError(code, message, detail); }
  function ensure(cond, code, message) { if (!cond) fail(code, message); }

  // =====================================================================================
  // Small utilities
  // =====================================================================================
  function clone(x) { return x === undefined ? undefined : JSON.parse(JSON.stringify(x)); }
  function pad(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
  function nowIso() { return new Date().toISOString(); }
  function isoDate(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1, 2) + '-' + pad(d.getDate(), 2); }
  function today() { return isoDate(new Date()); }
  function addDays(dateStr, n) {
    var d = dateStr ? new Date(dateStr.length <= 10 ? dateStr + 'T00:00:00' : dateStr) : new Date();
    d.setDate(d.getDate() + n); return isoDate(d);
  }
  function daysBetween(a, b) {
    var da = new Date((a.length <= 10 ? a + 'T00:00:00' : a)), db = new Date((b.length <= 10 ? b + 'T00:00:00' : b));
    return Math.round((db - da) / 86400000);
  }
  function fnv(str) {
    var h = 0x811c9dc5;
    for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0; }
    return ('0000000' + h.toString(16)).slice(-8);
  }
  function uniq(arr) { var o = []; arr.forEach(function (x) { if (o.indexOf(x) < 0) o.push(x); }); return o; }
  function byId(arr, id, key) { key = key || 'id'; for (var i = 0; i < (arr || []).length; i++) if (arr[i][key] === id) return arr[i]; return null; }

  // =====================================================================================
  // Storage
  // =====================================================================================
  var memory = {};
  var memStore = {
    getItem: function (k) { return Object.prototype.hasOwnProperty.call(memory, k) ? memory[k] : null; },
    setItem: function (k, v) { memory[k] = String(v); },
    removeItem: function (k) { delete memory[k]; }
  };
  function store() {
    try { if (root.localStorage) return root.localStorage; } catch (e) { /* blocked */ }
    return memStore;
  }

  var cache = { raw: null, state: null };
  var listeners = [];
  var ctx = { app: null };

  function seedState() {
    var seed = root.WZ_DEMO_SEED;
    if (!seed) fail('no_seed', 'demo-data.js was not loaded. Open the app from the demo folder that contains demo-data.js.');
    var s = clone(seed);
    s.meta.seedVersion = seed.meta.version;
    s.meta.engineVersion = VERSION;
    s.meta.schema = SCHEMA;
    s.meta.sessionId = 'S-' + Date.now().toString(36).toUpperCase();
    s.meta.createdAt = nowIso();
    s.meta.updatedAt = s.meta.createdAt;
    s.meta.rev = 0;
    s.signedIn = {};
    s.scenario = { id: null, step: 0, completed: [] };
    // initial audit hash chain
    var prev = '00000000';
    (s.audit || []).forEach(function (a) { a.prevHash = prev; a.hash = fnv(prev + JSON.stringify([a.seq, a.at, a.actor, a.action, a.entityId, a.outcome])); prev = a.hash; });
    return s;
  }

  function readRaw() { try { return store().getItem(KEY); } catch (e) { return null; } }
  function writeRaw(raw) {
    try { store().setItem(KEY, raw); }
    catch (e) { fail('storage_full', 'The browser refused to save the demo session (' + e.message + ').'); }
  }

  /** Current session state (seeded on first use). Callers must not mutate it directly. */
  function session() {
    var raw = readRaw();
    if (raw && raw === cache.raw && cache.state) return cache.state;
    var st = null;
    if (raw) { try { st = JSON.parse(raw); } catch (e) { st = null; } }
    if (!st || !st.meta || st.meta.schema !== SCHEMA) {
      st = seedState();
      raw = JSON.stringify(st);
      writeRaw(raw);
    }
    cache.raw = raw; cache.state = st;
    return st;
  }

  function persist(st, silent) {
    st.meta.rev = (st.meta.rev || 0) + 1;
    st.meta.updatedAt = nowIso();
    var raw = JSON.stringify(st);
    writeRaw(raw);
    cache.raw = raw; cache.state = st;
    if (!silent) emit({ type: 'change', rev: st.meta.rev });
  }

  /** Run fn against a fresh copy of the latest state and persist the result atomically. */
  function mutate(fn, silent) {
    session();
    var st = JSON.parse(cache.raw);
    var result = fn(st);
    persist(st, silent);
    return result;
  }

  function emit(evt) {
    listeners.slice().forEach(function (fn) { try { fn(evt || { type: 'change' }); } catch (e) { if (root.console) console.error(e); } });
  }

  if (root.addEventListener) {
    root.addEventListener('storage', function (e) {
      if (e.key === KEY || e.key === null) { cache.raw = null; cache.state = null; emit({ type: 'sync' }); }
    });
  }

  // =====================================================================================
  // Labels
  // =====================================================================================
  var LABELS = {
    caseStatus: { INTAKE: 'Received', TRIAGE: 'Awaiting triage', ASSESSMENT: 'Being assessed', FITNESS_REVIEW: 'Fitness review', TREATMENT: 'In treatment', CLAIMS: 'Claim in progress', MONITORING: 'Monitoring', CLOSED: 'Closed' },
    fitness: { FIT: 'Fit', FIT_WITH_RESTRICTIONS: 'Fit with restrictions', TEMPORARILY_UNFIT: 'Temporarily unfit', PERMANENTLY_UNFIT: 'Permanently unfit', UNDER_REVIEW: 'Under review', NOT_ASSESSED: 'Not yet assessed', UNFIT_PENDING: 'Unfit – pending' },
    duty: { AVAILABLE: 'Available', REMOVED: 'Removed from safety-sensitive duty', RETURN_PENDING: 'Return to duty pending', RELIEVED: 'Relieved from duties', EXCLUDED: 'Excluded (infection control)' },
    urgency: { EMERGENCY: 'Emergency', URGENT: 'Urgent', SEMI_URGENT: 'Semi-urgent', ROUTINE: 'Routine', ADVICE: 'Advice' },
    category: { INJURY: 'Injury', ILLNESS: 'Illness', DECREASE_IN_FITNESS: 'Decrease in fitness', PSYCHOLOGICAL: 'Confidential support', SUBSTANCE: 'Confidential', EXPOSURE: 'Exposure', OTHER: 'Other health concern' },
    source: { EHS_INCIDENT: 'EHS incident', SELF_REFERRAL: 'Self-referral', MANAGER_REFERRAL: 'Manager referral', HR_REFERRAL: 'HR referral', WALK_IN: 'Walk-in', EMERGENCY: 'Emergency', ABSENCE_TRIGGER: 'Absence trigger', SURVEILLANCE_TRIGGER: 'Surveillance', FITNESS_DECLARATION: 'Fitness declaration', DA_TEST: 'Testing programme', SYSTEM_TRIGGER: 'System', PARTNER_REFERRAL: 'Partner' },
    rtw: { DRAFT: 'Draft', ISSUED: 'Awaiting acknowledgement', ACTIVE: 'Active', COMPLETED: 'Completed', CANCELLED: 'Cancelled' },
    referral: { SENT: 'Sent', ACCEPTED: 'Accepted', APPOINTMENT_SET: 'Appointment set', IN_PROGRESS: 'In progress', REPORT_RECEIVED: 'Report received', DECLINED: 'Declined', CLOSED: 'Closed', CONSENT_WITHDRAWN: 'Consent withdrawn' },
    claim: { OPEN: 'Open', SUBMITTED: 'Submitted to TPA', UNDER_REVIEW: 'Under review', MORE_INFO: 'More information requested', ACCEPTED: 'Accepted', DENIED: 'Denied', CLOSED: 'Closed' },
    support: { OPEN: 'Received', SCREENED: 'Screened', ACTIVE: 'In support', REFERRED: 'Referred to EAP', RELIEF: 'Relief from duties', CLOSED: 'Closed' },
    sharing: { NOTHING: 'Nothing (default)', FITNESS_ONLY: 'Fitness only', FITNESS_AND_ADJUSTMENTS: 'Fitness and adjustments' },
    test: { REQUIRED: 'Required', NOTIFIED: 'Notified', DISPATCHED: 'Collector dispatched', COLLECTED: 'Collected', IN_LAB: 'In laboratory', MRO_REVIEW: 'MRO review', VERIFIED_NEGATIVE: 'Verified negative', VERIFIED_POSITIVE: 'Verified positive', REFUSAL: 'Refusal', CANCELLED: 'Cancelled', WINDOW_MISSED: 'Window missed' },
    testMasked: { NOTIFIED: 'Please attend', COLLECTED: 'With the testing programme', COMPLETED: 'Completed', CLOSED: 'Closed' },
    surveillance: { NOT_DUE: 'Up to date', DUE: 'Due', OVERDUE: 'Overdue', BOOKED: 'Booked', FOLLOW_UP: 'Follow-up', COMPLETED: 'Completed' },
    absenceCert: { NOT_REQUIRED: 'Not required', REQUIRED: 'Certificate required', RECEIVED: 'Received – awaiting check', VALIDATED: 'Validated', REJECTED: 'Rejected' },
    advice: { REQUESTED: 'Requested', AWAITING_EMPLOYEE_REVIEW: 'With employee for review', RELEASED: 'Released', WITHHELD: 'Withheld by employee', CHANGES_REQUESTED: 'Changes requested' },
    contact: { MONITORING: 'Monitoring', SYMPTOMATIC: 'Symptoms reported', CLEARED: 'Cleared' },
    travel: { REQUESTED: 'Requested', COMPLETED: 'Completed', CANCELLED: 'Cancelled' },
    campaign: { PLANNED: 'Planned', ACTIVE: 'Active', CLOSED: 'Closed' },
    consent: { GIVEN: 'Given', WITHDRAWN: 'Withdrawn' },
    consentType: { OH_PROCESSING: 'Occupational health assessment', PARTNER_TREATMENT: 'Sharing with a treatment partner', TPA_DISCLOSURE: 'Sharing with the claims administrator (TPA)',
      EAP_REFERRAL: 'Referral to the employee assistance programme', SUPPORT_SERVICE: 'Confidential support service', CAMPAIGN_PARTICIPATION: 'Health campaign participation',
      CAMPAIGN_REMINDERS: 'Health campaign reminders by e-mail', TELEHEALTH_CONSULTATION: 'Telehealth consultation' },
    eapOutcome: { COMPLETED: 'Completed', DID_NOT_ENGAGE: 'Did not engage', ONWARD_REFERRAL: 'Onward referral', EMPLOYEE_WITHDREW: 'Employee withdrew' },
    slot: { OH_CONSULT: 'Occupational health consultation', SURVEILLANCE: 'Health surveillance', AME_EXAM: 'Aeromedical examination', TRAVEL: 'Travel health clinic', SCREENING: 'Health check', EXTERNAL: 'Partner appointment' },
    contactPreference: { PHONE: 'Phone', EMAIL: 'E-mail', IN_PERSON: 'In person' }
  };
  function label(kind, code) { var m = LABELS[kind]; return (m && m[code]) || (code == null ? '—' : String(code).replace(/_/g, ' ').toLowerCase().replace(/^\w/, function (c) { return c.toUpperCase(); })); }

  // =====================================================================================
  // Roles and permissions
  // =====================================================================================
  var OH_TEAM = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'CASE_MANAGER', 'PSYCH_CLINICIAN'];
  var CLINICIANS = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'];

  var ROLE_PERMISSIONS = {
    EMPLOYEE: ['referral.self', 'self.read', 'rtw.acknowledge', 'appointment.book', 'absence.record', 'certificate.upload', 'advice.release',
      'support.self', 'peer.request', 'travel.request', 'advisory.acknowledge', 'contact.checkin', 'campaign.join', 'consent.manage'],
    MANAGER: ['referral.manager', 'team.status', 'rtw.acknowledge', 'absence.record', 'advice.request', 'testing.forCause', 'ergonomics.view', 'campaign.unitReport', 'surveillance.compliance'],
    HR: ['referral.hr', 'team.status', 'absence.record', 'absence.earlyIntervention', 'advice.request', 'claim.status', 'testing.dutyStatus', 'campaign.unitReport', 'surveillance.compliance'],
    CREW_SCHEDULING: ['crew.status', 'testing.dutyStatus'],
    SAFETY_MANAGER: ['safety.reports', 'testing.forCause', 'testing.stats'],
    HSE_OFFICER: ['referral.ehs', 'incident.status'],
    OH_CLINICIAN: ['case.read', 'case.triage', 'case.assign', 'case.note', 'case.close', 'restrictions.set', 'rtw.create', 'appointment.book', 'appointment.manage',
      'surveillance.record', 'treatment.plan', 'partner.refer', 'advice.report', 'travel.consult', 'cd.manage', 'screening.record', 'certificate.validate', 'breakglass.request', 'advisory.report', 'absence.earlyIntervention'],
    OH_PHYSICIAN: ['case.read', 'case.triage', 'case.assign', 'case.note', 'case.close', 'fitness.decide', 'restrictions.set', 'rtw.create', 'treatment.plan', 'partner.refer',
      'advice.report', 'advisory.publish', 'advisory.report', 'cd.manage', 'breakglass.request'],
    AME: ['case.read', 'case.triage', 'case.note', 'fitness.decide', 'fitness.decide.aviation', 'restrictions.set', 'support.relief', 'advisory.publish', 'advisory.report', 'breakglass.request'],
    CMO: ['case.read', 'fitness.decide', 'fitness.decide.aviation', 'advisory.publish', 'advisory.report', 'breakglass.request', 'testing.stats'],
    NURSING_HEAD: ['case.read', 'case.triage', 'case.assign', 'case.close', 'breakglass.request'],
    CASE_MANAGER: ['case.read', 'case.assign', 'case.note', 'case.close', 'rtw.create', 'advice.report', 'absence.earlyIntervention', 'claim.open', 'treatment.read', 'certificate.validate'],
    PSYCH_CLINICIAN: ['support.clinical', 'support.callback', 'partner.refer.eap', 'support.fitness', 'peer.referrals'],
    MRO: ['testing.results', 'testing.mro', 'testing.lab', 'testing.returnToDuty'],
    DA_PROGRAMME_MANAGER: ['testing.select', 'testing.programme', 'testing.stats'],
    COLLECTOR: ['testing.collect'],
    CLAIMS_COORDINATOR: ['claim.open', 'claim.manage', 'claim.message'],
    TPA_ADJUSTER: ['claim.adjust', 'claim.message', 'partner.portal'],
    PARTNER_PROVIDER: ['partner.referrals', 'partner.portal'],
    EAP_PARTNER: ['partner.eap'],
    HEALTH_PROMOTION_LEAD: ['campaign.manage', 'campaign.report', 'ergonomics.manage', 'promotion.insights'],
    ERGONOMIST: ['ergonomics.manage'],
    PEER_SUPPORT_VOLUNTEER: ['peer.volunteer'],
    DPO: ['audit.read', 'disclosure.read', 'breakglass.review', 'consent.read'],
    AUDITOR: ['audit.read']
  };

  function hasRole(u, r) { return !!u && (u.roles || []).indexOf(r) >= 0; }
  function anyRole(u, rs) { return !!u && rs.some(function (r) { return hasRole(u, r); }); }
  function isOH(u) { return anyRole(u, OH_TEAM); }
  function permsOf(u) {
    var p = [];
    (u && u.roles || []).forEach(function (r) { (ROLE_PERMISSIONS[r] || []).forEach(function (x) { if (p.indexOf(x) < 0) p.push(x); }); });
    return p;
  }
  /** Role-permission check. `user` may be a user object or a username. */
  function can(user, permission) {
    var u = typeof user === 'string' ? userByName(session(), user) : user;
    if (!u) return false;
    return permsOf(u).indexOf(permission) >= 0;
  }

  // =====================================================================================
  // Lookups
  // =====================================================================================
  function userByName(s, username) { return byId(s.users, username, 'username'); }
  function userForEmployee(s, empId) { for (var i = 0; i < s.users.length; i++) if (s.users[i].employeeId === empId && s.users[i].apps.indexOf('employee') >= 0) return s.users[i]; for (i = 0; i < s.users.length; i++) if (s.users[i].employeeId === empId) return s.users[i]; return null; }
  function emp(s, id) { return byId(s.employees, id); }
  function empName(s, id) { var e = emp(s, id); return e ? e.name : id; }
  function userName(s, username) { var u = userByName(s, username); return u ? u.name : (username === 'system' ? 'System' : username); }
  function job(s, code) { return byId(s.jobCodes, code, 'code'); }
  function safetyRoleOf(s, e) { var j = job(s, e.jobCode); return j ? byId(s.safetyRoles, j.safetyRole, 'code') : null; }
  function isCrew(s, e) { var r = safetyRoleOf(s, e); return !!r && (r.code === 'PILOT' || r.code === 'CABIN_CREW'); }
  function unitCode(s, orgUnit) { var u = byId(s.orgUnits, orgUnit); return u ? u.code : 'GRP'; }
  function orgName(s, code) { var o = byId(s.partnerOrgs, code, 'code'); return o ? o.name : code; }
  function managerUserOf(s, empId) {
    var e = emp(s, empId); if (!e || !e.managerId) return null;
    for (var i = 0; i < s.users.length; i++) if (s.users[i].employeeId === e.managerId && hasRole(s.users[i], 'MANAGER')) return s.users[i];
    return null;
  }
  /** Line manager's display name (employee record), whether or not they have a demo user. */
  function managerNameOf(s, empId) { var e = emp(s, empId); return e && e.managerId ? empName(s, e.managerId) : null; }
  function usersWithRole(s, role) { return s.users.filter(function (u) { return hasRole(u, role); }); }

  // =====================================================================================
  // Identity
  // =====================================================================================
  function currentUser() {
    var s = session();
    var un = s.signedIn && ctx.app ? s.signedIn[ctx.app] : null;
    if (!un && ctx.app && ctx.app !== 'launcher') {
      var list = s.users.filter(function (u) { return u.apps.indexOf(ctx.app) >= 0; });
      un = list.length ? list[0].username : null;
    }
    if (!un && ctx.user) un = ctx.user;
    return un ? clone(userByName(s, un)) : null;
  }
  function requireUser() { var u = currentUser(); if (!u) fail('unauthenticated', 'Nobody is signed in.'); return u; }

  function users(app) {
    var s = session();
    return clone(s.users.filter(function (u) { return !app || u.apps.indexOf(app) >= 0; }).map(function (u) {
      var o = clone(u); o.roleLabel = u.roles.join(' · '); return o;
    }));
  }

  function signIn(username) {
    var s = session();
    var u = userByName(s, username);
    ensure(u, 'not_found', 'Unknown demo user ' + username);
    if (ctx.app && ctx.app !== 'launcher' && u.apps.indexOf(ctx.app) < 0) fail('forbidden', u.name + ' is not a user of the ' + APPS[ctx.app].title + '.');
    ctx.user = username;
    mutate(function (st) {
      st.signedIn = st.signedIn || {};
      if (ctx.app) st.signedIn[ctx.app] = username;
      writeAudit(st, username, 'SIGN_IN', 'app', ctx.app || 'node', 'ALLOWED', u.roles.join(','));
    });
    return clone(u);
  }
  function signOut() { if (!ctx.app) { ctx.user = null; return; } mutate(function (st) { if (st.signedIn) delete st.signedIn[ctx.app]; }); ctx.user = null; }

  // =====================================================================================
  // Audit, notifications, disclosures, ids
  // =====================================================================================
  function writeAudit(s, actor, action, entity, entityId, outcome, detail) {
    s.audit = s.audit || [];
    var last = s.audit[s.audit.length - 1];
    var prev = last ? last.hash || '00000000' : '00000000';
    var a = { seq: (last ? last.seq : 0) + 1, at: nowIso(), actor: actor, action: action, entity: entity, entityId: entityId, outcome: outcome || 'ALLOWED', detail: detail || '' };
    a.prevHash = prev; a.hash = fnv(prev + JSON.stringify([a.seq, a.at, a.actor, a.action, a.entityId, a.outcome]));
    s.audit.push(a);
    if (s.audit.length > 2000) s.audit.splice(0, s.audit.length - 2000);
    return a;
  }
  /** Audit a read-side event (e.g. DENIED) without re-rendering, de-duplicated for 60 s. */
  function auditRead(actor, action, entity, entityId, outcome, detail) {
    var s = session();
    var cutoff = Date.now() - 60000;
    for (var i = s.audit.length - 1; i >= 0 && i >= s.audit.length - 50; i--) {
      var a = s.audit[i];
      if (a.actor === actor && a.action === action && a.entityId === entityId && Date.parse(a.at) > cutoff) return;
    }
    mutate(function (st) { writeAudit(st, actor, action, entity, entityId, outcome, detail); }, true);
  }

  function nextId(s, key, prefix, width) {
    s.counters = s.counters || {};
    s.counters[key] = (s.counters[key] || 0) + 1;
    return prefix + '-' + new Date().getFullYear() + '-' + pad(s.counters[key], width || 4);
  }

  function notify(s, usernames, title, body, link, kind) {
    uniq(usernames.filter(Boolean)).forEach(function (un) {
      s.notifications.push({ id: nextId(s, 'NTF', 'NTF', 4), to: un, at: nowIso(), title: title, body: body || '', link: link || null, read: false, kind: kind || 'INFO' });
    });
  }
  function notifyRole(s, role, title, body, link, kind) { notify(s, usersWithRole(s, role).map(function (u) { return u.username; }), title, body, link, kind); }
  function notifyEmployee(s, empId, title, body, route, kind) {
    var u = userForEmployee(s, empId);
    if (u) notify(s, [u.username], title, body, { app: 'employee', route: route || 'home' }, kind);
    else s.notifications.push({ id: nextId(s, 'NTF', 'NTF', 4), to: 'emp:' + empId, at: nowIso(), title: title, body: body || '', link: { app: 'employee', route: route || 'home' }, read: false, kind: kind || 'INFO' });
  }
  function notifyManager(s, empId, title, body, route) {
    var m = managerUserOf(s, empId);
    if (m) notify(s, [m.username], title, body, { app: 'manager', route: route || 'team' });
  }
  function partnerUsers(s, org) { return s.users.filter(function (u) { return u.partnerOrg === org; }).map(function (u) { return u.username; }); }

  function disclose(s, empId, recipient, what, basis) {
    s.disclosures.push({ id: nextId(s, 'DSC', 'DSC', 4), at: nowIso(), employeeId: empId, recipient: recipient, what: what, basis: basis });
  }
  function addTimeline(c, by, type, text, vis) {
    c.timeline = c.timeline || [];
    c.timeline.push({ at: nowIso(), by: by, type: type, text: text, vis: vis || 'STATUS' });
    c.updatedAt = nowIso();
  }
  function addDocument(s, d) {
    var doc = { id: nextId(s, 'DOC', 'DOC', 4), createdAt: nowIso(), sharedWith: [], visibleToEmployee: true, classification: 'STATUS' };
    for (var k in d) doc[k] = d[k];
    s.documents.push(doc); return doc;
  }

  // =====================================================================================
  // Scope and restricted-class rules
  // =====================================================================================
  function isRestrictedCase(c) { return c.classification === 'RESTRICTED_CLINICAL' || c.category === 'PSYCHOLOGICAL' || c.category === 'SUBSTANCE'; }
  function hasBreakGlass(s, u, empId) {
    var t = Date.now();
    return (s.breakGlass || []).some(function (g) { return g.username === u.username && g.employeeId === empId && Date.parse(g.expiresAt) > t; });
  }
  function isNamedClinician(s, u) {
    var named = (s.config && s.config.namedClinicians) || [];
    return hasRole(u, 'PSYCH_CLINICIAN') && (named.length === 0 || named.indexOf(u.username) >= 0);
  }
  function isMro(s, u) { return hasRole(u, 'MRO') || (hasRole(u, 'OH_PHYSICIAN') && ((s.config.actingMroPhysicians || []).indexOf(u.username) >= 0)); }
  /** Can `u` read restricted content about this case / employee? Returns 'FULL', 'BREAK_GLASS' or null. */
  function restrictedAccess(s, u, category, empId) {
    if (category === 'PSYCHOLOGICAL' && isNamedClinician(s, u)) return 'FULL';
    if (category === 'SUBSTANCE' && isMro(s, u)) return 'FULL';
    if (anyRole(u, CLINICIANS) && hasBreakGlass(s, u, empId)) return 'BREAK_GLASS';
    return null;
  }
  function inOrgScope(u, e) { return !!u.orgScope && (e.orgUnit === u.orgScope || e.orgUnit.indexOf(u.orgScope + '/') === 0 || u.orgScope === '/AIRLINE'); }
  /** Employees whose status the user may see (managers: direct reports; HR: org scope; crew scheduling: crew). */
  function statusScope(s, u) {
    if (hasRole(u, 'HR')) return s.employees.filter(function (e) { return inOrgScope(u, e); });
    if (hasRole(u, 'MANAGER')) return s.employees.filter(function (e) { return e.managerId === u.employeeId; });
    if (hasRole(u, 'CREW_SCHEDULING')) return s.employees.filter(function (e) { return isCrew(s, e); });
    return [];
  }
  function inStatusScope(s, u, empId) { return statusScope(s, u).some(function (e) { return e.id === empId; }); }
  function isSelf(u, empId) { return !!u && !!empId && u.employeeId === empId; }

  // =====================================================================================
  // Projections (views)
  // =====================================================================================
  function activeRestrictions(c) { return (c.restrictions || []).filter(function (r) { return !r.until || r.until >= today(); }); }
  function nextStepFor(s, c) {
    if (c.status === 'CLOSED') return 'No further action';
    if (c.status === 'INTAKE' || c.status === 'TRIAGE') return 'Occupational Health will review your referral';
    var rtw = c.rtwPlanId && byId(s.rtwPlans, c.rtwPlanId);
    if (rtw && rtw.status === 'ISSUED') return 'Acknowledge the return-to-work plan';
    var ap = s.appointments.filter(function (a) { return a.caseId === c.id && a.status === 'BOOKED' && a.start >= nowIso(); })[0];
    if (ap) return 'Appointment on ' + ap.start.slice(0, 10);
    if (c.fitness && c.fitness.reviewDate) return 'Fitness review by ' + c.fitness.reviewDate;
    return 'Occupational Health will contact you';
  }
  function caseStatusView(s, c) {
    var e = emp(s, c.employeeId) || {};
    var rtw = c.rtwPlanId ? byId(s.rtwPlans, c.rtwPlanId) : null;
    return {
      id: c.id, view: 'STATUS', employeeId: c.employeeId, employeeName: e.name, orgUnit: e.orgUnit,
      typeLabel: 'Occupational health case', status: c.status, statusLabel: label('caseStatus', c.status),
      fitnessStatus: (c.fitness && c.fitness.status) || 'NOT_ASSESSED', fitnessLabel: label('fitness', (c.fitness && c.fitness.status) || 'NOT_ASSESSED'),
      reviewDate: (c.fitness && c.fitness.reviewDate) || null,
      restrictions: activeRestrictions(c).map(function (r) { return { text: r.text, until: r.until }; }),
      rtwPlanId: c.rtwPlanId || null, rtwStatus: rtw ? rtw.status : null,
      openedAt: c.openedAt, updatedAt: c.updatedAt,
      timeline: (c.timeline || []).filter(function (t) { return t.vis === 'STATUS'; }).map(function (t) { return { at: t.at, type: t.type, text: t.text }; })
    };
  }
  function caseSelfView(s, c) {
    var v = caseStatusView(s, c);
    v.view = 'SELF';
    v.typeLabel = label('category', c.category);
    v.category = c.category; v.source = c.source; v.sourceLabel = label('source', c.source);
    v.workImpact = c.workImpact; v.nextStep = nextStepFor(s, c);
    v.closedAt = c.closedAt || null; v.closeReason = c.closeReason || null;
    v.timeline = (c.timeline || []).filter(function (t) { return t.vis === 'STATUS' || t.vis === 'SELF'; }).map(function (t) { return { at: t.at, type: t.type, text: t.text, by: t.by === c.employeeId ? 'You' : 'Occupational Health' }; });
    return v;
  }
  function caseFullView(s, c, basis) {
    var v = clone(c);
    var e = emp(s, c.employeeId) || {};
    v.view = 'FULL'; v.accessBasis = basis || 'ROLE';
    v.employeeName = e.name; v.orgUnit = e.orgUnit; v.jobTitle = (job(s, e.jobCode) || {}).title; v.site = e.site;
    v.safetyRole = (safetyRoleOf(s, e) || {}).code || null;
    v.statusLabel = label('caseStatus', c.status); v.typeLabel = label('category', c.category);
    v.fitnessLabel = label('fitness', (c.fitness && c.fitness.status) || 'NOT_ASSESSED');
    v.ownerName = c.owner ? userName(s, c.owner) : null;
    v.timeline = (c.timeline || []).filter(function (t) { return t.vis !== 'RESTRICTED' || isRestrictedCase(c); }).map(function (t) { var x = clone(t); x.byName = userName(s, t.by); return x; });
    v.placeholderRestricted = isRestrictedCase(c) ? 0 : restrictedCount(s, c.employeeId);
    return v;
  }
  function restrictedCount(s, empId) {
    var n = s.supportCases.filter(function (x) { return x.employeeId === empId && x.status !== 'CLOSED'; }).length;
    n += s.cases.filter(function (c) { return c.employeeId === empId && c.category === 'SUBSTANCE' && c.status !== 'CLOSED'; }).length;
    return n;
  }

  /** Visible projection of a kernel case for user u, or null. */
  function projectCase(s, u, c, opts) {
    if (!u || !c) return null;
    if (isSelf(u, c.employeeId) && !isOH(u)) return caseSelfView(s, c);
    if (isRestrictedCase(c)) {
      var acc = restrictedAccess(s, u, c.category, c.employeeId);
      if (acc) return caseFullView(s, c, acc);
      if (opts && opts.audit && isOH(u)) auditRead(u.username, 'READ_RESTRICTED', 'case', c.id, 'DENIED', 'Not the named clinician / MRO');
      return null;
    }
    if (isOH(u)) return caseFullView(s, c);
    if (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, c.employeeId)) return caseStatusView(s, c);
    if (hasRole(u, 'HSE_OFFICER') && c.source === 'EHS_INCIDENT') { var v = caseStatusView(s, c); v.linkedIncident = c.linkedIncident; v.recordable = c.recordable; return v; }
    if (hasRole(u, 'CLAIMS_COORDINATOR') && s.claims.some(function (cl) { return cl.caseId === c.id; })) return caseStatusView(s, c);
    return null;
  }

  function employeeView(s, u, e) {
    var j = job(s, e.jobCode) || {}; var sr = safetyRoleOf(s, e) || {};
    var base = { id: e.id, name: e.name, orgUnit: e.orgUnit, unitName: (byId(s.orgUnits, e.orgUnit) || {}).name, site: e.site, jobCode: e.jobCode, jobTitle: j.title,
      safetyRole: sr.code, safetyCritical: !!sr.safetyCritical, managerId: e.managerId, managerName: e.managerId ? empName(s, e.managerId) : null };
    var status = { fitnessStatus: e.fitness.status, fitnessLabel: label('fitness', e.fitness.status), fitnessSince: e.fitness.since, dutyStatus: e.dutyStatus, dutyLabel: label('duty', e.dutyStatus), dutySince: e.dutySince };
    var restr = [];
    s.cases.forEach(function (c) { if (c.employeeId === e.id && c.status !== 'CLOSED') activeRestrictions(c).forEach(function (r) { restr.push({ text: r.text, until: r.until }); }); });
    if (isSelf(u, e.id) || isOH(u)) { Object.assign(base, status); base.restrictions = restr; base.email = e.email; base.language = e.language; base.shift = e.shift; return base; }
    if (anyRole(u, ['MANAGER', 'HR', 'CREW_SCHEDULING']) && inStatusScope(s, u, e.id)) { Object.assign(base, status); base.restrictions = restr; return base; }
    if (anyRole(u, ['DA_PROGRAMME_MANAGER', 'COLLECTOR', 'MRO', 'HEALTH_PROMOTION_LEAD', 'CLAIMS_COORDINATOR', 'DPO', 'ERGONOMIST'])) return base;
    return null;
  }

  function rtwView(s, u, p) {
    var e = emp(s, p.employeeId) || {};
    var c = byId(s.cases, p.caseId);
    var ok = isSelf(u, p.employeeId) || isOH(u) || (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, p.employeeId));
    if (!ok || (c && isRestrictedCase(c) && !isSelf(u, p.employeeId) && !restrictedAccess(s, u, c.category, p.employeeId))) return null;
    var v = clone(p); v.employeeName = e.name; v.statusLabel = label('rtw', p.status);
    v.restrictions = c ? activeRestrictions(c).map(function (r) { return { text: r.text, until: r.until }; }) : [];
    return v;
  }

  function referralView(s, u, r) {
    var e = emp(s, r.employeeId) || {};
    var svc = byId(s.serviceCatalogue, r.service, 'code') || {};
    var consent = r.consentId ? byId(s.consents, r.consentId) : null;
    var withdrawn = consent && consent.status === 'WITHDRAWN';
    var base = { id: r.id, kind: r.kind, partnerOrg: r.partnerOrg, partnerName: orgName(s, r.partnerOrg), service: r.service, serviceName: svc.name,
      urgency: r.urgency, status: withdrawn ? 'CONSENT_WITHDRAWN' : r.status, statusLabel: label('referral', withdrawn ? 'CONSENT_WITHDRAWN' : r.status),
      sentAt: r.sentAt, acceptedAt: r.acceptedAt, appointmentAt: r.appointmentAt, reportDue: r.reportDue, sessions: r.sessions, closedAt: r.closedAt };
    if (u.partnerOrg) {
      if (u.partnerOrg !== r.partnerOrg) return null;
      if (withdrawn) { base.employeeName = 'Withheld – consent withdrawn'; return base; }
      base.employeeName = e.name;
      if (r.kind === 'EAP') { base.contactPreference = (byId(s.supportCases, r.supportCaseId) || {}).contactPreference; base.outcome = r.outcome || null; return base; }
      base.question = r.question; base.dataSet = r.dataSet; base.report = r.report; base.jobTitle = (job(s, e.jobCode) || {}).title;
      base.reportOverdue = !r.report && r.reportDue && r.reportDue < today();
      return base;
    }
    if (r.kind === 'EAP') {
      if (!isNamedClinician(s, u) && !isSelf(u, r.employeeId)) return null;
      base.employeeName = e.name; base.supportCaseId = r.supportCaseId; base.outcome = r.outcome || null; return base;
    }
    if (isOH(u) || hasRole(u, 'CASE_MANAGER')) { var v = clone(r); Object.assign(v, base); v.employeeName = e.name; v.caseId = r.caseId; return v; }
    if (isSelf(u, r.employeeId)) { base.employeeName = e.name; base.caseId = r.caseId; return base; }
    return null;
  }

  function claimView(s, u, c) {
    var e = emp(s, c.employeeId) || {};
    if (hasRole(u, 'TPA_ADJUSTER')) {
      if (u.partnerOrg !== c.payerOrg) return null;
      var consent = byId(s.consents, c.consentId);
      var v = clone(c); v.employeeName = e.name; v.statusLabel = label('claim', c.status);
      if (consent && consent.status === 'WITHDRAWN') v.documents = [];
      delete v.caseId; return v;
    }
    if (anyRole(u, ['CLAIMS_COORDINATOR', 'CASE_MANAGER', 'CMO'])) { var w = clone(c); w.employeeName = e.name; w.payerName = orgName(s, c.payerOrg); w.statusLabel = label('claim', c.status); return w; }
    if (hasRole(u, 'HR') && inStatusScope(s, u, c.employeeId)) return { id: c.id, employeeId: c.employeeId, employeeName: e.name, type: c.type, status: c.status, statusLabel: label('claim', c.status), openedAt: c.openedAt, rtwStatus: c.rtwStatus };
    if (isSelf(u, c.employeeId)) return { id: c.id, caseId: c.caseId, type: c.type, status: c.status, statusLabel: label('claim', c.status), openedAt: c.openedAt };
    return null;
  }

  function supportView(s, u, x, opts) {
    if (isSelf(u, x.employeeId)) {
      return { id: x.id, view: 'SELF', status: x.status, statusLabel: label('support', x.status), openedAt: x.openedAt, sharing: x.sharing, sharingLabel: label('sharing', x.sharing),
        sharingAt: x.sharingAt, contactPreference: x.contactPreference, urgentOpen: x.urgentItems.some(function (i) { return i.status === 'OPEN'; }),
        eapReferral: x.eapReferralId ? referralView(s, u, byId(s.referrals, x.eapReferralId)) : null,
        lastScreen: x.screens.length ? { at: x.screens[x.screens.length - 1].at, band: x.screens[x.screens.length - 1].band } : null };
    }
    var acc = restrictedAccess(s, u, 'PSYCHOLOGICAL', x.employeeId);
    if (acc) { var v = clone(x); v.view = 'FULL'; v.accessBasis = acc; v.employeeName = empName(s, x.employeeId); v.statusLabel = label('support', x.status); v.readOnly = acc === 'BREAK_GLASS'; return v; }
    if (opts && opts.audit && isOH(u)) auditRead(u.username, 'READ_RESTRICTED', 'support', x.id, 'DENIED', 'Not the named clinician');
    return null;
  }

  function maskTestStatus(st) {
    if (['REQUIRED', 'NOTIFIED', 'DISPATCHED'].indexOf(st) >= 0) return 'NOTIFIED';
    if (['COLLECTED', 'IN_LAB', 'MRO_REVIEW'].indexOf(st) >= 0) return 'COLLECTED';
    if (['VERIFIED_NEGATIVE', 'VERIFIED_POSITIVE', 'REFUSAL'].indexOf(st) >= 0) return 'COMPLETED';
    return 'CLOSED';
  }
  function testView(s, u, t) {
    var e = emp(s, t.employeeId) || {};
    if (isMro(s, u)) { var v = clone(t); v.employeeName = e.name; v.statusLabel = label('test', t.status); return v; }
    if (isSelf(u, t.employeeId)) {
      var m = maskTestStatus(t.status);
      return { id: t.id, view: 'SELF', maskedStatus: m, statusLabel: label('testMasked', m), notice: m === 'NOTIFIED' ? t.notice : null, notifiedAt: t.notifiedAt };
    }
    if (hasRole(u, 'COLLECTOR')) {
      var mineCollected = t.specimen && t.specimen.collector === u.username;
      if (['REQUIRED', 'NOTIFIED', 'DISPATCHED', 'COLLECTED'].indexOf(t.status) < 0 && !mineCollected) return null;
      if (['REQUIRED', 'NOTIFIED', 'DISPATCHED', 'COLLECTED'].indexOf(t.status) < 0) return { id: t.id, employeeId: t.employeeId, employeeName: e.name, reason: t.reason, panel: t.panel, status: t.status === 'IN_LAB' ? 'IN_LAB' : 'COLLECTED', statusLabel: t.status === 'IN_LAB' ? label('test', 'IN_LAB') : 'Handed over', specimen: t.specimen };
      return { id: t.id, employeeId: t.employeeId, employeeName: e.name, reason: t.reason, panel: t.panel, status: t.status, statusLabel: label('test', t.status), notice: t.notice, specimen: t.specimen };
    }
    if (hasRole(u, 'DA_PROGRAMME_MANAGER')) {
      return { id: t.id, employeeId: t.employeeId, employeeName: e.name, reason: t.reason, panel: t.panel, selectionId: t.selectionId, notifiedAt: t.notifiedAt,
        maskedStatus: maskTestStatus(t.status), statusLabel: label('testMasked', maskTestStatus(t.status)) };
    }
    if (anyRole(u, ['MANAGER', 'SAFETY_MANAGER']) && t.requestedBy === u.username) {
      return { id: t.id, employeeName: e.name, reason: t.reason, maskedStatus: maskTestStatus(t.status), statusLabel: label('testMasked', maskTestStatus(t.status)), requestedAt: t.requestedAt };
    }
    return null;
  }

  function adviceView(s, u, a) {
    var e = emp(s, a.employeeId) || {};
    var v = { id: a.id, employeeId: a.employeeId, employeeName: e.name, caseId: a.caseId, requestedBy: a.requestedBy, requestedByName: userName(s, a.requestedBy), requestedAt: a.requestedAt,
      questions: a.questions, status: a.status, statusLabel: label('advice', a.status), releasedAt: a.releasedAt || null };
    if (isOH(u) || isSelf(u, a.employeeId)) { v.report = a.report; v.employeeReview = a.employeeReview || null; return v; }
    if (anyRole(u, ['MANAGER', 'HR']) && (inStatusScope(s, u, a.employeeId) || a.requestedBy === u.username)) { if (a.status === 'RELEASED') v.report = a.report; return v; }
    return null;
  }

  // ---------------------------------------------------------------------- collection router
  function scoped(s, u, name, filter) {
    filter = filter || {};
    var out;
    switch (name) {
      case 'employees':
        out = s.employees.map(function (e) { return employeeView(s, u, e); }).filter(Boolean);
        if (filter.q) { var q = filter.q.toLowerCase(); out = out.filter(function (e) { return (e.name + ' ' + e.id).toLowerCase().indexOf(q) >= 0; }); }
        break;
      case 'cases':
        out = s.cases.map(function (c) { return projectCase(s, u, c); }).filter(Boolean);
        if (filter.employeeId) out = out.filter(function (c) { return c.employeeId === filter.employeeId; });
        if (filter.open) out = out.filter(function (c) { return c.status !== 'CLOSED'; });
        if (filter.queue) out = out.filter(function (c) { return c.queue === filter.queue; });
        if (filter.status) out = out.filter(function (c) { return c.status === filter.status; });
        if (filter.owner) out = out.filter(function (c) { return c.owner === filter.owner; });
        out.sort(function (a, b) { return (b.updatedAt || '').localeCompare(a.updatedAt || ''); });
        break;
      case 'team':
        out = statusScope(s, u).map(function (e) {
          var v = employeeView(s, u, e);
          var openCase = s.cases.filter(function (c) { return c.employeeId === e.id && c.status !== 'CLOSED' && !isRestrictedCase(c); })[0];
          var abs = s.absences.filter(function (a) { return a.employeeId === e.id && (!a.end || a.end >= today()) && a.start <= today(); })[0];
          var rtw = s.rtwPlans.filter(function (p) { return p.employeeId === e.id && (p.status === 'ISSUED' || p.status === 'ACTIVE'); })[0];
          v.openCaseId = openCase ? openCase.id : null; v.caseStatus = openCase ? openCase.status : null; v.caseStatusLabel = openCase ? label('caseStatus', openCase.status) : null;
          v.absent = !!abs; v.absentSince = abs ? abs.start : null;
          v.rtwPlanId = rtw ? rtw.id : null; v.rtwStatus = rtw ? rtw.status : null; v.rtwManagerAcked = rtw ? !!rtw.ack.manager : null;
          return v;
        });
        break;
      case 'crewStatus':
        if (!can(u, 'crew.status') && !hasRole(u, 'HR')) return [];
        out = s.employees.filter(function (e) { return isCrew(s, e); }).map(function (e) {
          var duties = s.roster.filter(function (r) { return r.employeeId === e.id && r.date >= today(); }).sort(function (a, b) { return a.date.localeCompare(b.date); });
          var unfit = ['TEMPORARILY_UNFIT', 'PERMANENTLY_UNFIT', 'UNFIT_PENDING'].indexOf(e.fitness.status) >= 0 || ['REMOVED', 'RELIEVED', 'EXCLUDED', 'RETURN_PENDING'].indexOf(e.dutyStatus) >= 0;
          var rosterStatus = e.dutyStatus !== 'AVAILABLE' || unfit ? 'UNFIT' : e.fitness.status === 'FIT_WITH_RESTRICTIONS' ? 'FIT_WITH_LIMITATIONS' : e.fitness.status === 'UNDER_REVIEW' ? 'PENDING' : 'FIT';
          return { employeeId: e.id, name: e.name, jobTitle: (job(s, e.jobCode) || {}).title, base: e.site, rosterStatus: rosterStatus, since: e.dutyStatus !== 'AVAILABLE' ? e.dutySince : e.fitness.since,
            nextDuties: duties.slice(0, 3).map(function (d) { return { date: d.date, duty: d.duty, to: d.to }; }), conflict: rosterStatus === 'UNFIT' && duties.length > 0 };
        });
        break;
      case 'dutyStatus':
        if (!can(u, 'testing.dutyStatus')) return [];
        out = (hasRole(u, 'HR') ? statusScope(s, u) : s.employees.filter(function (e) { return isCrew(s, e); })).map(function (e) {
          return { employeeId: e.id, displayName: e.name, unit: (byId(s.orgUnits, e.orgUnit) || {}).name, dutyStatus: e.dutyStatus, dutyLabel: label('duty', e.dutyStatus), since: e.dutySince };
        });
        if (filter.notAvailable) out = out.filter(function (r) { return r.dutyStatus !== 'AVAILABLE'; });
        break;
      case 'sharedOutcomes':
        out = s.supportCases.filter(function (x) { return x.sharing !== 'NOTHING' && x.status !== 'CLOSED' && hasRole(u, 'MANAGER') && inStatusScope(s, u, x.employeeId); }).map(function (x) {
          var e = emp(s, x.employeeId);
          return { employeeId: x.employeeId, employeeName: e.name, fitness: e.fitness.status, fitnessLabel: label('fitness', e.fitness.status), adjustments: x.sharing === 'FITNESS_AND_ADJUSTMENTS' ? x.sharedAdjustments : null, sharedAt: x.sharingAt };
        });
        break;
      case 'rtwPlans': out = s.rtwPlans.map(function (p) { return rtwView(s, u, p); }).filter(Boolean); break;
      case 'absences':
        out = s.absences.filter(function (a) { return isSelf(u, a.employeeId) || isOH(u) || (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, a.employeeId)); }).map(function (a) {
          var v = clone(a); v.employeeName = empName(s, a.employeeId); v.certificateLabel = a.certificate ? label('absenceCert', a.certificate.status) : 'None'; v.open = !a.end || a.end >= today(); return v;
        });
        out.sort(function (a, b) { return b.start.localeCompare(a.start); });
        break;
      case 'absenceTriggers':
      case 'earlyInterventions':
        out = (s[name] || []).filter(function (x) { return anyRole(u, ['HR', 'CASE_MANAGER', 'OH_CLINICIAN']) && (isOH(u) || inStatusScope(s, u, x.employeeId)); }).map(function (x) { var v = clone(x); v.employeeName = empName(s, x.employeeId); return v; });
        break;
      case 'adviceRequests': out = s.adviceRequests.map(function (a) { return adviceView(s, u, a); }).filter(Boolean); break;
      case 'surveillance':
        out = s.surveillance.filter(function (x) { return isSelf(u, x.employeeId) || isOH(u) || (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, x.employeeId)); }).map(function (x) {
          var p = byId(s.surveillanceProgrammes, x.programme, 'code') || {};
          var v = { id: x.id, employeeId: x.employeeId, employeeName: empName(s, x.employeeId), programme: x.programme, programmeName: p.name, dueDate: x.dueDate, status: x.status, statusLabel: label('surveillance', x.status), appointmentId: x.appointmentId, slotType: p.slotType };
          if (isOH(u) || isSelf(u, x.employeeId)) { v.lastDone = x.lastDone; v.lastOutcome = x.lastOutcome; }
          if (isOH(u)) v.results = clone(x.results);
          return v;
        });
        if (filter.due) out = out.filter(function (x) { return ['DUE', 'OVERDUE', 'FOLLOW_UP'].indexOf(x.status) >= 0; });
        out.sort(function (a, b) { return a.dueDate.localeCompare(b.dueDate); });
        break;
      case 'clinicSlots':
        out = s.clinicSlots.filter(function (x) { return x.start >= nowIso() && (filter.all || !x.bookedBy) && (!filter.type || x.type === filter.type) && (!filter.site || x.site === filter.site); })
          .map(function (x) { var v = clone(x); v.clinicianName = userName(s, x.clinician); if (!isOH(u)) delete v.bookedBy; return v; });
        break;
      case 'appointments':
        out = s.appointments.filter(function (a) { return isSelf(u, a.employeeId) || isOH(u) || hasRole(u, 'CLINIC_RECEPTION'); }).map(function (a) { var v = clone(a); v.employeeName = empName(s, a.employeeId); v.clinicianName = a.external ? a.clinician : userName(s, a.clinician); return v; });
        out.sort(function (a, b) { return a.start.localeCompare(b.start); });
        break;
      case 'treatmentPlans':
        out = s.treatmentPlans.filter(function (p) { return isOH(u) || isSelf(u, p.employeeId); }).map(function (p) { var v = clone(p); v.employeeName = empName(s, p.employeeId); return v; });
        break;
      case 'referrals': out = s.referrals.map(function (r) { return referralView(s, u, r); }).filter(Boolean); if (filter.kind) out = out.filter(function (r) { return r.kind === filter.kind; }); break;
      case 'claims': out = s.claims.map(function (c) { return claimView(s, u, c); }).filter(Boolean); break;
      case 'supportCases': out = s.supportCases.map(function (x) { return supportView(s, u, x); }).filter(Boolean); break;
      case 'peerRequests':
        if (!hasRole(u, 'PEER_SUPPORT_VOLUNTEER')) return [];
        out = s.peerRequests.filter(function (p) { return p.volunteer === u.username; }).map(clone);
        break;
      case 'tests': out = s.testing.tests.map(function (t) { return testView(s, u, t); }).filter(Boolean); break;
      case 'testPools':
        if (!anyRole(u, ['DA_PROGRAMME_MANAGER', 'MRO'])) return [];
        out = s.testing.pools.map(function (p) { var v = clone(p); v.memberNames = p.members.map(function (m) { return empName(s, m); }); return v; });
        break;
      case 'testSelections':
        if (!hasRole(u, 'DA_PROGRAMME_MANAGER')) return [];
        out = s.testing.selections.map(function (x) { var v = clone(x); v.selectedNames = x.selected.map(function (m) { return empName(s, m); }); return v; });
        break;
      case 'travelConsultations':
        out = s.travelConsultations.filter(function (t) { return isSelf(u, t.employeeId) || isOH(u); }).map(function (t) { var v = clone(t); v.employeeName = empName(s, t.employeeId); v.destinationName = (byId(s.destinations, t.destination, 'code') || {}).name; v.statusLabel = label('travel', t.status); return v; });
        break;
      case 'advisories':
        out = s.advisories.map(function (a) {
          var mine = u.employeeId && a.recipients.indexOf(u.employeeId) >= 0;
          if (!mine && !isOH(u) && !anyRole(u, ['MANAGER', 'CREW_SCHEDULING', 'HR'])) return null;
          var v = { id: a.id, title: a.title, destination: a.destination, destinationName: (byId(s.destinations, a.destination, 'code') || {}).name, severity: a.severity, publishedAt: a.publishedAt, publishedByName: userName(s, a.publishedBy), body: a.body, targetRule: a.targetRule };
          if (mine) { v.forMe = true; v.acknowledgedAt = a.acks[u.employeeId] || null; }
          return v;
        }).filter(Boolean);
        if (filter.mine) out = out.filter(function (a) { return a.forMe; });
        break;
      case 'cdCases':
        out = s.cdCases.filter(function () { return isOH(u); }).map(function (c) { var v = clone(c); v.employeeName = empName(s, c.employeeId); v.contactCount = s.contacts.filter(function (x) { return x.cdCaseId === c.id; }).length; return v; });
        break;
      case 'contacts':
        out = s.contacts.filter(function (x) { return isOH(u) || isSelf(u, x.employeeId); }).map(function (x) {
          if (isSelf(u, x.employeeId) && !isOH(u)) return { id: x.id, exposure: x.exposure, exposureDate: x.exposureDate, notifiedAt: x.notifiedAt, status: x.status, statusLabel: label('contact', x.status), monitorUntil: x.monitorUntil, checkIns: clone(x.checkIns), advice: x.advice || null };
          var v = clone(x); v.employeeName = empName(s, x.employeeId); v.statusLabel = label('contact', x.status); return v;
        });
        if (filter.cdCaseId) out = out.filter(function (x) { return x.cdCaseId === filter.cdCaseId; });
        break;
      case 'campaigns':
        out = s.campaigns.map(function (c) {
          var v = { id: c.id, title: c.title, type: c.type, status: c.status, statusLabel: label('campaign', c.status), description: c.description, start: c.start, end: c.end, events: clone(c.events), units: clone(c.units), screeningType: c.screeningType };
          if (u.employeeId) { v.invited = c.invited.indexOf(u.employeeId) >= 0; v.joined = c.joined.indexOf(u.employeeId) >= 0; v.declined = c.declined.indexOf(u.employeeId) >= 0; v.myEventId = (c.bookings || {})[u.employeeId] || null; }
          if (anyRole(u, ['HEALTH_PROMOTION_LEAD'])) { v.invitedCount = c.invited.length; v.joinedCount = c.joined.length; }
          return v;
        });
        if (filter.mine) out = out.filter(function (c) { return c.invited || c.joined; });
        break;
      case 'screeningResults':
        out = s.screeningResults.filter(function (r) { return isSelf(u, r.employeeId) || anyRole(u, CLINICIANS); }).map(function (r) { var v = clone(r); v.employeeName = empName(s, r.employeeId); v.campaignTitle = (byId(s.campaigns, r.campaignId) || {}).title; return v; });
        break;
      case 'ergonomicActions':
        out = s.ergonomicActions.filter(function (a) {
          if (anyRole(u, ['ERGONOMIST', 'HEALTH_PROMOTION_LEAD']) || isOH(u)) return true;
          if (hasRole(u, 'MANAGER')) { var me = emp(s, u.employeeId); return me && a.unit === me.orgUnit; }
          if (hasRole(u, 'HR')) return true;
          return false;
        }).map(clone);
        break;
      case 'notifications': out = notificationsFor(s, u); break;
      case 'audit':
        if (!can(u, 'audit.read')) return [];
        out = s.audit.slice().reverse().map(function (a) { var v = clone(a); v.actorName = userName(s, a.actor); return v; });
        break;
      case 'disclosures':
        out = s.disclosures.filter(function (d) { return can(u, 'disclosure.read') || isSelf(u, d.employeeId); }).map(function (d) { var v = clone(d); v.employeeName = empName(s, d.employeeId); return v; });
        out.sort(function (a, b) { return b.at.localeCompare(a.at); });
        break;
      case 'consents':
        out = s.consents.filter(function (c) { return isSelf(u, c.employeeId) || can(u, 'consent.read') || isOH(u); }).map(function (c) { var v = clone(c); v.employeeName = empName(s, c.employeeId); v.partnerName = c.partnerOrg ? orgName(s, c.partnerOrg) : null; return v; });
        break;
      case 'documents':
        out = s.documents.filter(function (d) {
          if (isSelf(u, d.employeeId)) return d.visibleToEmployee;
          if (isOH(u)) return d.classification !== 'RESTRICTED_CLINICAL' || restrictedAccess(s, u, 'PSYCHOLOGICAL', d.employeeId);
          return (d.sharedWith || []).indexOf(u.username) >= 0;
        }).map(function (d) { var v = clone(d); v.employeeName = empName(s, d.employeeId); v.byName = userName(s, d.by); return v; });
        out.sort(function (a, b) { return b.createdAt.localeCompare(a.createdAt); });
        break;
      case 'breakGlass':
        out = s.breakGlass.filter(function (g) { return can(u, 'breakglass.review') || g.username === u.username; }).map(function (g) { var v = clone(g); v.employeeName = empName(s, g.employeeId); v.userName = userName(s, g.username); return v; });
        break;
      case 'roster':
        if (!isOH(u) && !hasRole(u, 'CREW_SCHEDULING') && !u.employeeId) return [];
        out = s.roster.filter(function (r) { return isOH(u) || hasRole(u, 'CREW_SCHEDULING') || r.employeeId === u.employeeId; }).map(clone);
        break;
      default:
        fail('unknown_collection', 'Unknown collection ' + name);
    }
    if (filter.employeeId && name !== 'cases') out = out.filter(function (x) { return !x.employeeId || x.employeeId === filter.employeeId; });
    return out;
  }

  function notificationsFor(s, u) {
    return s.notifications.filter(function (n) { return n.to === u.username || (u.employeeId && n.to === 'emp:' + u.employeeId && u.apps.indexOf('employee') >= 0); })
      .slice().sort(function (a, b) { return b.at.localeCompare(a.at); }).map(clone);
  }

  /** List a collection as the current user may see it. */
  function list(name, filter) { var u = requireUser(); return scoped(session(), u, name, filter); }

  /** Get one item by id as the current user may see it (null if not visible; restricted denials are audited). */
  function get(name, id) {
    var s = session(), u = requireUser();
    if (name === 'cases') return projectCase(s, u, byId(s.cases, id), { audit: true });
    if (name === 'supportCases') { var x = byId(s.supportCases, id); return x ? supportView(s, u, x, { audit: true }) : null; }
    if (name === 'tests') { var t = byId(s.testing.tests, id); if (!t) return null; var v = testView(s, u, t); if (!v && hasRole(u, 'DA_PROGRAMME_MANAGER')) auditRead(u.username, 'READ_RESULT', 'test', id, 'DENIED', 'Programme manager never sees results'); return v; }
    if (name === 'employees') { var e = emp(s, id); return e ? employeeView(s, u, e) : null; }
    if (name === 'referrals' && u.partnerOrg) {
      var rr = byId(s.referrals, id);
      if (!rr) return null;
      var rv = referralView(s, u, rr);
      if (!rv) auditRead(u.username, 'READ_REFERRAL', 'referral', id, 'DENIED', 'Other partner organisation');
      else auditRead(u.username, 'READ_REFERRAL', 'referral', id, rv.status === 'CONSENT_WITHDRAWN' ? 'DENIED' : 'ALLOWED',
        rv.status === 'CONSENT_WITHDRAWN' ? 'Consent withdrawn – no personal data shown' : 'Partner read under consent');
      return rv;
    }
    if (name === 'peerRequests') return byId(scoped(s, u, name), id, 'code');
    var all = scoped(s, u, name);
    return byId(all, id) || null;
  }

  /** Reference data (catalogues). */
  var REF = ['orgUnits', 'sites', 'jobCodes', 'safetyRoles', 'partnerOrgs', 'serviceCatalogue', 'restrictionCatalogue', 'triageProtocols', 'surveillanceProgrammes', 'destinations'];
  function ref(name) {
    var s = session();
    if (REF.indexOf(name) >= 0) return clone(s[name]);
    if (name === 'config') return clone(s.config);
    if (name === 'testPolicies') return clone(s.testing.policies);
    if (name === 'meta') return clone(s.meta);
    fail('unknown_reference', 'Unknown reference list ' + name);
  }

  function placeholderCount(employeeId) {
    var s = session(), u = requireUser();
    if (!isOH(u)) return 0;
    var n = restrictedCount(s, employeeId);
    if (n > 0) auditRead(u.username, 'SUPPORT_PLACEHOLDER_READ', 'employee', employeeId, 'ALLOWED', n + ' restricted');
    return n;
  }

  function me() {
    var s = session(), u = requireUser();
    if (!u.employeeId) return null;
    var e = emp(s, u.employeeId);
    return e ? employeeView(s, u, e) : null;
  }

  // =====================================================================================
  // Tasks
  // =====================================================================================
  function tasks() {
    var s = session(), u = requireUser(), out = [];
    function add(kind, title, detail, app, route, priority, due) { out.push({ kind: kind, title: title, detail: detail || '', link: { app: app, route: route }, priority: priority || 'NORMAL', due: due || null }); }
    var e = u.employeeId;
    if (e && u.apps.indexOf('employee') >= 0) {
      s.rtwPlans.forEach(function (p) { if (p.employeeId === e && p.status === 'ISSUED' && !p.ack.employee) add('RTW_ACK', 'Acknowledge your return-to-work plan', p.id, 'employee', 'rtw', 'HIGH'); });
      s.adviceRequests.forEach(function (a) { if (a.employeeId === e && a.status === 'AWAITING_EMPLOYEE_REVIEW') add('ADVICE_REVIEW', 'Review the advice report before it is released', a.id, 'employee', 'documents', 'HIGH'); });
      s.advisories.forEach(function (a) { if (a.recipients.indexOf(e) >= 0 && !a.acks[e]) add('ADVISORY_ACK', 'Read and acknowledge: ' + a.title, a.id, 'employee', 'travel', 'HIGH'); });
      s.surveillance.forEach(function (x) { if (x.employeeId === e && (x.status === 'DUE' || x.status === 'OVERDUE')) add('SURVEILLANCE_BOOK', 'Book your ' + ((byId(s.surveillanceProgrammes, x.programme, 'code') || {}).name || 'health check'), (x.status === 'OVERDUE' ? 'Overdue since ' : 'Due by ') + x.dueDate, 'employee', 'appointments', x.status === 'OVERDUE' ? 'HIGH' : 'NORMAL', x.dueDate); });
      s.contacts.forEach(function (c) { if (c.employeeId === e && c.status === 'MONITORING' && !c.checkIns.some(function (k) { return k.date === today(); })) add('CONTACT_CHECKIN', 'Daily health check-in', 'Until ' + c.monitorUntil, 'employee', 'travel', 'HIGH'); });
      s.testing.tests.forEach(function (t) { if (t.employeeId === e && maskTestStatus(t.status) === 'NOTIFIED') add('TEST_NOTICE', 'Workplace testing notice – please attend', t.notice ? t.notice.location : '', 'employee', 'testing', 'URGENT'); });
      s.campaigns.forEach(function (c) { if (c.status === 'ACTIVE' && c.invited.indexOf(e) >= 0 && c.joined.indexOf(e) < 0 && c.declined.indexOf(e) < 0) add('CAMPAIGN', 'You are invited: ' + c.title, c.id, 'employee', 'campaigns'); });
      s.absences.forEach(function (a) { if (a.employeeId === e && a.certificate && a.certificate.status === 'REQUIRED') add('CERTIFICATE', 'Upload your medical certificate', a.id, 'employee', 'absence', 'HIGH'); });
    }
    if (hasRole(u, 'MANAGER')) {
      s.rtwPlans.forEach(function (p) { if (p.status === 'ISSUED' && !p.ack.manager && inStatusScope(s, u, p.employeeId)) add('RTW_ACK', 'Acknowledge an adjusted-duties plan', empName(s, p.employeeId), 'manager', 'rtw', 'HIGH'); });
    }
    if (hasRole(u, 'HR')) {
      s.earlyInterventions.forEach(function (x) { if (x.status === 'OPEN' && inStatusScope(s, u, x.employeeId)) add('EARLY_INTERVENTION', 'Absence trigger – early intervention', empName(s, x.employeeId), 'manager', 'absence', 'HIGH'); });
    }
    if (isOH(u) && !hasRole(u, 'PSYCH_CLINICIAN')) {
      s.cases.forEach(function (c) { if ((c.status === 'INTAKE' || c.status === 'TRIAGE') && !isRestrictedCase(c)) add('TRIAGE', 'Triage ' + c.id, empName(s, c.employeeId), 'oh', 'case/' + c.id, 'HIGH'); });
      s.cases.forEach(function (c) { if (c.owner === u.username && c.status !== 'CLOSED' && !isRestrictedCase(c)) add('MY_CASE', 'My case ' + c.id, label('caseStatus', c.status), 'oh', 'case/' + c.id); });
      s.travelConsultations.forEach(function (t) { if (t.status === 'REQUESTED') add('TRAVEL', 'Travel consultation request', empName(s, t.employeeId), 'oh', 'travel', 'NORMAL'); });
      s.referrals.forEach(function (r) { if (r.kind === 'TREATMENT' && r.status === 'REPORT_RECEIVED' && r.sentBy === u.username) add('PARTNER_REPORT', 'Partner report received', r.id, 'oh', 'referrals'); });
    }
    if (isNamedClinician(s, u)) {
      s.supportCases.forEach(function (x) { x.urgentItems.forEach(function (i) { if (i.status === 'OPEN') add('URGENT_CALLBACK', 'Urgent call-back', x.id, 'oh', 'support/' + x.id, 'URGENT'); }); if (x.status === 'OPEN') add('SUPPORT_NEW', 'New confidential support request', x.id, 'oh', 'support/' + x.id, 'HIGH'); });
    }
    if (isMro(s, u)) s.testing.tests.forEach(function (t) { if (t.status === 'MRO_REVIEW') add('MRO_REVIEW', 'Result awaiting MRO review', t.id, 'oh', 'testing/mro', 'HIGH'); });
    if (hasRole(u, 'COLLECTOR')) s.testing.tests.forEach(function (t) { if (['REQUIRED', 'NOTIFIED', 'DISPATCHED'].indexOf(t.status) >= 0) add('COLLECT', 'Collection required', empName(s, t.employeeId), 'oh', 'testing/collection', 'HIGH'); });
    if (u.partnerOrg) {
      s.referrals.forEach(function (r) { if (r.partnerOrg === u.partnerOrg && r.status === 'SENT') add('REFERRAL_NEW', 'New referral to accept', r.id, 'partner', r.kind === 'EAP' ? 'eap' : 'referrals', 'HIGH'); });
      s.claims.forEach(function (c) { if (c.payerOrg === u.partnerOrg && (c.status === 'SUBMITTED' || c.status === 'UNDER_REVIEW')) add('CLAIM_DECISION', 'Claim awaiting decision', c.id, 'partner', 'claims', 'HIGH'); });
    }
    if (hasRole(u, 'CLAIMS_COORDINATOR')) s.claims.forEach(function (c) { if (c.status === 'MORE_INFO') add('CLAIM_INFO', 'TPA requested more information', c.id, 'oh', 'claims', 'HIGH'); });
    return out;
  }

  // =====================================================================================
  // Small-cell suppression
  // =====================================================================================
  function minK() { try { return session().config.minGroupSize || 5; } catch (e) { return 5; } }
  function isSmallCell(n, k) { k = k || minK(); return n > 0 && n < k; }
  /**
   * Suppress small cells in a series. Each cell is an object with a numeric `field` (default 'count').
   * Cells with 1..k-1 are suppressed (value null, suppressed:true). If exactly one cell was suppressed
   * and `complementary` is on (default), the next smallest non-zero cell is suppressed too so the hidden
   * value cannot be recovered from a total.
   */
  function suppressCells(cells, opts) {
    opts = opts || {}; var k = opts.k || minK(); var f = opts.field || 'count'; var hide = opts.hide || [f];
    var out = clone(cells);
    var idx = [];
    out.forEach(function (c, i) { if (isSmallCell(c[f], k)) idx.push(i); });
    if (idx.length === 1 && opts.complementary !== false) {
      var best = -1;
      out.forEach(function (c, i) { if (idx.indexOf(i) < 0 && c[f] > 0 && (best < 0 || c[f] < out[best][f])) best = i; });
      if (best >= 0) idx.push(best);
    }
    idx.forEach(function (i) { out[i].suppressed = true; hide.forEach(function (h) { out[i][h] = null; }); });
    out.forEach(function (c) { if (!c.suppressed) c.suppressed = false; });
    return out;
  }
  /** A rate is suppressed when fewer than k are in the denominator, or 1..k-1 are in the numerator. */
  function suppressRate(numerator, denominator, k) {
    k = k || minK();
    if (denominator < k || isSmallCell(numerator, k)) return { suppressed: true, numerator: null, denominator: null, pct: null };
    return { suppressed: false, numerator: numerator, denominator: denominator, pct: denominator ? Math.round(1000 * numerator / denominator) / 10 : 0 };
  }

  // =====================================================================================
  // Reports (aggregates, always suppressed)
  // =====================================================================================
  function report(name, params) {
    var s = session(), u = requireUser(); params = params || {};
    var k = s.config.minGroupSize;
    if (name === 'campaign') {
      var c = byId(s.campaigns, params.campaignId); ensure(c, 'not_found', 'No such campaign');
      ensure(anyRole(u, ['HEALTH_PROMOTION_LEAD', 'MANAGER', 'HR']) || isOH(u), 'forbidden', 'Not allowed');
      var units = uniq(c.invited.map(function (id) { return emp(s, id).orgUnit; }));
      if (hasRole(u, 'MANAGER') && !hasRole(u, 'HEALTH_PROMOTION_LEAD')) { var mine = emp(s, u.employeeId); units = units.filter(function (x) { return mine && x === mine.orgUnit; }); }
      var rows = units.map(function (unit) {
        var inv = c.invited.filter(function (id) { return emp(s, id).orgUnit === unit; }).length;
        var joined = c.joined.filter(function (id) { return emp(s, id).orgUnit === unit; }).length;
        var r = suppressRate(joined, inv, k);
        return { unit: unit, unitName: (byId(s.orgUnits, unit) || {}).name, invited: r.suppressed ? null : inv, participated: r.numerator, pct: r.pct, suppressed: r.suppressed };
      });
      var abnormal = s.screeningResults.filter(function (r) { return r.campaignId === c.id && r.outcome === 'ABNORMAL'; }).length;
      var screened = s.screeningResults.filter(function (r) { return r.campaignId === c.id; }).length;
      return { campaignId: c.id, title: c.title, k: k, rows: rows, screening: suppressCells([{ key: 'screened', count: screened }, { key: 'abnormal', count: abnormal }], { k: k }) };
    }
    if (name === 'advisory') {
      var a = byId(s.advisories, params.advisoryId); ensure(a, 'not_found', 'No such advisory');
      ensure(isOH(u) || anyRole(u, ['MANAGER', 'CREW_SCHEDULING', 'HR']), 'forbidden', 'Not allowed');
      var acked = Object.keys(a.acks).length, total = a.recipients.length;
      var rr = suppressRate(acked, total, k);
      var out = { advisoryId: a.id, title: a.title, recipients: total, acknowledged: rr.numerator, ratePct: rr.pct, suppressed: rr.suppressed, k: k };
      if (isOH(u)) out.pending = a.recipients.filter(function (id) { return !a.acks[id]; }).map(function (id) { return { employeeId: id, name: empName(s, id) }; });
      return out;
    }
    if (name === 'testing') {
      ensure(can(u, 'testing.stats') || isMro(s, u), 'forbidden', 'Not allowed');
      var groups = {};
      s.testing.tests.forEach(function (t) { var e = emp(s, t.employeeId); var g = isCrew(s, e) ? 'Aircrew' : 'Ground & engineering'; var key = t.reason + '|' + g; groups[key] = (groups[key] || 0) + 1; });
      var cells = Object.keys(groups).map(function (key) { var p = key.split('|'); return { reason: p[0], roleGroup: p[1], count: groups[key] }; });
      return { k: k, cells: suppressCells(cells, { k: k }), total: s.testing.tests.length };
    }
    if (name === 'peer') {
      ensure(anyRole(u, ['PEER_SUPPORT_COORDINATOR', 'SAFETY_MANAGER', 'PEER_SUPPORT_VOLUNTEER']), 'forbidden', 'Not allowed');
      var by = {};
      s.peerRequests.forEach(function (p) { (p.contacts.length ? p.contacts : [{ category: 'NOT_YET_CONTACTED' }]).forEach(function (c) { var key = p.base + '|' + c.category; by[key] = (by[key] || 0) + 1; }); });
      return { k: k, cells: suppressCells(Object.keys(by).map(function (key) { var p = key.split('|'); return { base: p[0], category: p[1], count: by[key] }; }), { k: k }) };
    }
    if (name === 'absence') {
      ensure(anyRole(u, ['HR', 'MANAGER']) || isOH(u), 'forbidden', 'Not allowed');
      var per = {};
      s.absences.forEach(function (ab) { var e = emp(s, ab.employeeId); if (hasRole(u, 'MANAGER') && !inStatusScope(s, u, e.id)) return; per[e.orgUnit] = (per[e.orgUnit] || 0) + 1; });
      return { k: k, cells: suppressCells(Object.keys(per).map(function (unit) { return { unit: unit, unitName: (byId(s.orgUnits, unit) || {}).name, count: per[unit] }; }), { k: k }) };
    }
    if (name === 'supportRate') {
      ensure(anyRole(u, ['SAFETY_MANAGER', 'HEALTH_PROMOTION_LEAD', 'HR']) || isOH(u), 'forbidden', 'Not allowed');
      var units2 = {};
      s.employees.forEach(function (e) { units2[e.orgUnit] = units2[e.orgUnit] || { staff: 0, cases: 0 }; units2[e.orgUnit].staff++; });
      s.supportCases.forEach(function (x) { var e = emp(s, x.employeeId); units2[e.orgUnit].cases++; });
      return { k: k, rows: Object.keys(units2).map(function (unit) { var r = units2[unit]; var sr = suppressRate(r.cases, r.staff, k); return { unit: unit, unitName: (byId(s.orgUnits, unit) || {}).name, per100: sr.suppressed ? null : Math.round(1000 * r.cases / r.staff) / 10, suppressed: sr.suppressed }; }) };
    }
    fail('unknown_report', 'Unknown report ' + name);
  }

  // =====================================================================================
  // Action framework
  // =====================================================================================
  var ACTIONS = {};
  /**
   * Define an action. `perm` is a permission string, an array (any of), or null (checked inside).
   * The body runs inside one atomic mutate(); it returns a value that is cloned for the caller.
   */
  function defineAction(name, perm, body) {
    ACTIONS[name] = perm;
    WZ[name] = function () {
      var args = Array.prototype.slice.call(arguments);
      var u = requireUser();
      if (perm) {
        var perms = Array.isArray(perm) ? perm : [perm];
        if (!perms.some(function (p) { return can(u, p); })) {
          mutate(function (st) { writeAudit(st, u.username, name.toUpperCase(), 'action', name, 'DENIED', 'Missing permission ' + perms.join('|')); }, true);
          fail('forbidden', u.name + ' is not allowed to ' + name + '.');
        }
      }
      var result;
      try {
        result = mutate(function (st) {
          var su = userByName(st, u.username);
          var r = body.apply(null, [st, su].concat(args));
          advanceScenarioOn(st, name, su);
          return r;
        });
      } catch (e) {
        if (e && e.code === 'forbidden') mutate(function (st) { writeAudit(st, u.username, name.toUpperCase(), 'action', name, 'DENIED', e.message); }, true);
        throw e;
      }
      return clone(result);
    };
  }
  function getCase(s, id) { var c = byId(s.cases, id); ensure(c, 'not_found', 'Case ' + id + ' not found'); return c; }
  function ohCanTouch(s, u, c) {
    if (isRestrictedCase(c)) { var a = restrictedAccess(s, u, c.category, c.employeeId); ensure(a === 'FULL', 'not_found', 'Case ' + c.id + ' not found'); }
  }
  function newCaseId(s, empId) { var e = emp(s, empId); s.counters.case = (s.counters.case || 0) + 1; return unitCode(s, e.orgUnit) + '-' + new Date().getFullYear() + '-' + pad(s.counters.case, 6); }

  function openKernelCase(s, by, o) {
    var e = emp(s, o.employeeId); ensure(e, 'not_found', 'Employee not found');
    var restricted = o.category === 'PSYCHOLOGICAL' || o.category === 'SUBSTANCE';
    var c = {
      id: newCaseId(s, o.employeeId), employeeId: o.employeeId, category: o.category || 'OTHER',
      type: o.type || ({ INJURY: 'INJURY', ILLNESS: 'ILLNESS', DECREASE_IN_FITNESS: 'FITNESS', PSYCHOLOGICAL: 'MENTAL_HEALTH', SUBSTANCE: 'SUBSTANCE', EXPOSURE: 'EXPOSURE' }[o.category] || 'OTHER'),
      source: o.source, classification: restricted ? 'RESTRICTED_CLINICAL' : 'CLINICAL',
      status: 'INTAKE', urgency: null, queue: restricted ? (o.category === 'SUBSTANCE' ? 'MRO' : 'MENTAL_HEALTH') : 'INTAKE',
      owner: restricted ? (o.category === 'SUBSTANCE' ? 'mro1' : (s.config.namedClinicians[0] || null)) : null,
      openedAt: nowIso(), updatedAt: nowIso(), recordable: null,
      summary: o.summary || '', workImpact: o.workImpact || '', linkedIncident: o.incidentRef || null,
      incident: o.incident || null, triage: null, fitness: { status: 'NOT_ASSESSED' }, restrictions: [], rtwPlanId: null, tasks: [], timeline: []
    };
    s.cases.push(c);
    addTimeline(c, by, 'REFERRAL', o.timelineText || (label('source', o.source) + ' received.'), restricted ? 'STATUS' : 'STATUS');
    return c;
  }

  // =====================================================================================
  // Actions – intake, triage, fitness, RTW
  // =====================================================================================
  defineAction('createReferral', ['referral.self', 'referral.manager', 'referral.hr', 'referral.ehs'], function (s, u, o) {
    o = o || {};
    var src = o.source || (hasRole(u, 'HSE_OFFICER') ? 'EHS_INCIDENT' : hasRole(u, 'HR') ? 'HR_REFERRAL' : hasRole(u, 'MANAGER') ? 'MANAGER_REFERRAL' : 'SELF_REFERRAL');
    var empId = o.employeeId || (src === 'SELF_REFERRAL' ? u.employeeId : null);
    ensure(empId && emp(s, empId), 'validation', 'Choose the employee');
    if (src === 'SELF_REFERRAL') { ensure(can(u, 'referral.self') && isSelf(u, empId), 'forbidden', 'You can only refer yourself'); ensure(o.consent === true, 'consent_required', 'Please confirm your consent to Occupational Health processing.'); }
    if (src === 'MANAGER_REFERRAL') ensure(can(u, 'referral.manager') && inStatusScope(s, u, empId), 'forbidden', 'Managers can refer only their own team');
    if (src === 'HR_REFERRAL') ensure(can(u, 'referral.hr') && inStatusScope(s, u, empId), 'forbidden', 'Out of HR scope');
    if (src === 'EHS_INCIDENT') ensure(can(u, 'referral.ehs'), 'forbidden', 'Only the HSE officer reports EHS incidents');
    ensure((o.concern || o.workImpact || '').trim().length > 0, 'validation', 'Describe the concern');
    var category = o.category || (src === 'EHS_INCIDENT' ? 'INJURY' : 'OTHER');
    var c = openKernelCase(s, u.username, {
      employeeId: empId, category: category, source: src,
      // Managers and HR give work-impact facts only; their text is not treated as clinical history.
      summary: src === 'SELF_REFERRAL' || src === 'EHS_INCIDENT' ? (o.concern || '') : '',
      workImpact: o.workImpact || (src === 'MANAGER_REFERRAL' || src === 'HR_REFERRAL' ? o.concern : ''),
      incidentRef: o.incidentRef || (src === 'EHS_INCIDENT' ? nextId(s, 'EHS', 'EHS', 4) : null),
      incident: src === 'EHS_INCIDENT' ? { date: o.incidentDate || today(), location: o.location || '', bodyPart: o.bodyPart || '' } : null,
      timelineText: label('source', src) + ' received' + (src === 'EHS_INCIDENT' ? ' (incident ' + (o.incidentRef || '') + ')' : '') + '.'
    });
    if (src === 'EHS_INCIDENT' && !o.incidentRef) c.timeline[0].text = 'Case opened from EHS incident ' + c.linkedIncident + '.';
    if (src === 'SELF_REFERRAL') {
      s.consents.push({ id: nextId(s, 'CON', 'CON', 4), employeeId: empId, type: 'OH_PROCESSING', scope: 'Occupational health assessment of my referral ' + c.id, status: 'GIVEN', at: nowIso() });
    } else {
      notifyEmployee(s, empId, 'You have been referred to Occupational Health', 'A ' + label('source', src).toLowerCase() + ' was made. OH will contact you. Your manager only sees work-relevant status.', 'cases');
    }
    if (c.classification === 'RESTRICTED_CLINICAL') notify(s, s.config.namedClinicians, 'New confidential referral', 'A restricted-class referral is waiting.', { app: 'oh', route: 'support' }, 'URGENT');
    else notifyRole(s, 'OH_CLINICIAN', 'New case in intake queue', label('source', src) + ' – ' + c.id, { app: 'oh', route: 'case/' + c.id });
    writeAudit(s, u.username, 'CREATE_REFERRAL', 'case', c.id, 'ALLOWED', src);
    return projectCase(s, u, c);
  });

  defineAction('triage', 'case.triage', function (s, u, caseId, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    ensure(['INTAKE', 'TRIAGE', 'ASSESSMENT'].indexOf(c.status) >= 0, 'invalid_state', 'Case is not awaiting triage');
    var urg = o.urgency || 'ROUTINE';
    ensure(LABELS.urgency[urg], 'validation', 'Unknown urgency');
    var proto = o.protocol ? byId(s.triageProtocols, o.protocol, 'code') : null;
    c.triage = { protocol: o.protocol || null, urgency: urg, queue: o.queue || (proto && proto.queue) || 'OH_NURSE', by: u.username, at: nowIso(), answers: o.answers || {} };
    c.urgency = urg; c.queue = c.triage.queue; c.status = 'ASSESSMENT';
    if (o.recordable !== undefined) c.recordable = !!o.recordable;
    if (c.fitness.status === 'NOT_ASSESSED') c.fitness.status = 'UNDER_REVIEW';
    addTimeline(c, u.username, 'TRIAGE', 'Triaged ' + label('urgency', urg) + ' – ' + c.queue.replace(/_/g, ' ').toLowerCase() + ' queue.', 'STATUS');
    if (o.note) addTimeline(c, u.username, 'NOTE', o.note, 'OH');
    notifyEmployee(s, c.employeeId, 'Your referral has been reviewed', 'Occupational Health has reviewed your referral ' + c.id + '.', 'cases');
    writeAudit(s, u.username, 'TRIAGE', 'case', c.id, 'ALLOWED', urg);
    return caseFullView(s, c);
  });

  defineAction('assign', 'case.assign', function (s, u, caseId, username) {
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    var a = userByName(s, username || u.username);
    ensure(a && isOH(a), 'validation', 'Assignee must be an OH team member');
    c.owner = a.username;
    addTimeline(c, u.username, 'ASSIGN', 'Assigned to ' + a.name + '.', 'OH');
    if (a.username !== u.username) notify(s, [a.username], 'Case assigned to you', c.id + ' – ' + empName(s, c.employeeId), { app: 'oh', route: 'case/' + c.id });
    writeAudit(s, u.username, 'ASSIGN', 'case', c.id, 'ALLOWED', a.username);
    return caseFullView(s, c);
  });

  defineAction('addCaseNote', 'case.note', function (s, u, caseId, text, vis) {
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    ensure(text && text.trim(), 'validation', 'Note is empty');
    addTimeline(c, u.username, 'NOTE', text.trim(), vis === 'SELF' ? 'SELF' : 'OH');
    writeAudit(s, u.username, 'CASE_NOTE', 'case', c.id, 'ALLOWED', '');
    return caseFullView(s, c);
  });

  defineAction('recordFitnessDecision', 'fitness.decide', function (s, u, caseId, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    var e = emp(s, c.employeeId); var sr = safetyRoleOf(s, e) || {};
    ensure(LABELS.fitness[o.status] && ['NOT_ASSESSED', 'UNDER_REVIEW'].indexOf(o.status) < 0, 'validation', 'Choose a fitness outcome');
    if (sr.code === 'PILOT' || sr.code === 'CABIN_CREW') ensure(can(u, 'fitness.decide.aviation'), 'forbidden', 'Aviation fitness decisions for crew are made by an AME.');
    c.fitness = { status: o.status, decidedBy: u.username, decidedAt: nowIso(), reviewDate: o.reviewDate || null, note: o.note || '' };
    if (o.restrictions) c.restrictions = normaliseRestrictions(s, o.restrictions, o.reviewDate);
    if (o.status === 'FIT') c.restrictions = [];
    e.fitness = { status: o.status, since: today(), decidedBy: u.username };
    if (o.status === 'TEMPORARILY_UNFIT' && sr.safetyCritical && e.dutyStatus === 'AVAILABLE') { e.dutyStatus = 'RELIEVED'; e.dutySince = today(); }
    if ((o.status === 'FIT' || o.status === 'FIT_WITH_RESTRICTIONS') && e.dutyStatus === 'RELIEVED') { e.dutyStatus = 'AVAILABLE'; e.dutySince = today(); }
    c.status = o.status === 'FIT' ? 'MONITORING' : (c.status === 'TREATMENT' ? 'TREATMENT' : 'FITNESS_REVIEW');
    addTimeline(c, u.username, 'FITNESS', 'Fitness decision: ' + label('fitness', o.status) + (o.reviewDate ? ' (review by ' + o.reviewDate + ')' : '') + '.', 'STATUS');
    if (o.note) addTimeline(c, u.username, 'NOTE', o.note, 'OH');
    var mgr = managerUserOf(s, c.employeeId);
    addDocument(s, { employeeId: c.employeeId, caseId: c.id, type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate – ' + label('fitness', o.status).toLowerCase(), by: u.username, classification: 'STATUS', sharedWith: mgr ? [mgr.username] : [] });
    notifyEmployee(s, c.employeeId, 'Fitness decision recorded', 'Your fitness for work: ' + label('fitness', o.status) + '.', 'fitness');
    if (mgr) notifyManager(s, c.employeeId, 'Fitness status updated', e.name + ': ' + label('fitness', o.status) + ' (status only).', 'team');
    if (managerNameOf(s, c.employeeId)) disclose(s, c.employeeId, managerNameOf(s, c.employeeId) + ' (line manager)', 'Fitness status' + (c.restrictions.length ? ' and functional restrictions' : '') + ' (no clinical detail)', 'Fitness for work');
    if (isCrew(s, e)) { notifyRole(s, 'CREW_SCHEDULING', 'Crew roster status changed', e.name + ' – ' + label('fitness', o.status) + '.', { app: 'manager', route: 'crew' }); disclose(s, c.employeeId, 'Crew scheduling', 'Roster fitness status only', 'Safety-critical status feed'); }
    writeAudit(s, u.username, 'FITNESS_DECISION', 'case', c.id, 'ALLOWED', o.status);
    return caseFullView(s, c);
  });

  function normaliseRestrictions(s, list, until) {
    return (list || []).map(function (r) {
      if (typeof r === 'string') r = { code: r };
      var cat = r.code ? byId(s.restrictionCatalogue, r.code, 'code') : null;
      return { code: r.code || 'CUSTOM', text: r.text || (cat ? cat.text : r.code), until: r.until || until || null };
    });
  }

  defineAction('setRestrictions', 'restrictions.set', function (s, u, caseId, restrictions, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    c.restrictions = normaliseRestrictions(s, restrictions, o.until);
    var e = emp(s, c.employeeId);
    if (c.restrictions.length && e.fitness.status === 'FIT') { e.fitness = { status: 'FIT_WITH_RESTRICTIONS', since: today(), decidedBy: u.username }; c.fitness.status = 'FIT_WITH_RESTRICTIONS'; }
    addTimeline(c, u.username, 'RESTRICTIONS', c.restrictions.length ? 'Work restrictions updated: ' + c.restrictions.map(function (r) { return r.text; }).join('; ') + '.' : 'Work restrictions removed.', 'STATUS');
    notifyEmployee(s, c.employeeId, 'Your work restrictions changed', 'See My fitness for the current restrictions.', 'fitness');
    notifyManager(s, c.employeeId, 'Team member restrictions updated', e.name + ' – functional restrictions updated (no clinical detail).', 'team');
    if (managerNameOf(s, c.employeeId)) disclose(s, c.employeeId, managerNameOf(s, c.employeeId) + ' (line manager)', 'Functional work restrictions', 'Fitness for work');
    writeAudit(s, u.username, 'SET_RESTRICTIONS', 'case', c.id, 'ALLOWED', c.restrictions.length + ' restrictions');
    return caseFullView(s, c);
  });

  defineAction('createRtwPlan', 'rtw.create', function (s, u, caseId, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    ensure(Array.isArray(o.phases) && o.phases.length, 'validation', 'Add at least one phase');
    var p = { id: nextId(s, 'RTW', 'RTW', 4), caseId: c.id, employeeId: c.employeeId, status: 'ISSUED', createdBy: u.username, createdAt: nowIso(),
      startDate: o.startDate || addDays(today(), 1), reviewDate: o.reviewDate || addDays(today(), 21), goal: o.goal || 'Return to full duties.',
      phases: o.phases.map(function (ph, i) { return { week: ph.week || i + 1, hours: ph.hours || 'Normal hours', duties: ph.duties || '' }; }), ack: { employee: null, manager: null } };
    s.rtwPlans.push(p);
    c.rtwPlanId = p.id;
    addTimeline(c, u.username, 'RTW', 'Return-to-work plan ' + p.id + ' issued for acknowledgement.', 'STATUS');
    var mgr = managerUserOf(s, c.employeeId);
    addDocument(s, { employeeId: c.employeeId, caseId: c.id, type: 'RTW_PLAN', title: 'Return-to-work plan ' + p.id, by: u.username, classification: 'STATUS', sharedWith: mgr ? [mgr.username] : [] });
    notifyEmployee(s, c.employeeId, 'Your return-to-work plan is ready', 'Please read and acknowledge plan ' + p.id + '.', 'rtw', 'ACTION');
    notifyManager(s, c.employeeId, 'Return-to-work plan to acknowledge', empName(s, c.employeeId) + ' – plan ' + p.id + '.', 'rtw');
    if (managerNameOf(s, c.employeeId)) disclose(s, c.employeeId, managerNameOf(s, c.employeeId) + ' (line manager)', 'Return-to-work plan (duties and hours)', 'Fitness for work');
    writeAudit(s, u.username, 'CREATE_RTW_PLAN', 'rtw', p.id, 'ALLOWED', c.id);
    return rtwView(s, u, p);
  });

  defineAction('acknowledge', 'rtw.acknowledge', function (s, u, rtwPlanId, o) {
    var p = byId(s.rtwPlans, rtwPlanId); ensure(p, 'not_found', 'Plan not found');
    ensure(p.status === 'ISSUED' || p.status === 'ACTIVE', 'invalid_state', 'Plan is not awaiting acknowledgement');
    if (isSelf(u, p.employeeId)) { ensure(!p.ack.employee, 'invalid_state', 'Already acknowledged'); p.ack.employee = nowIso(); }
    else if (hasRole(u, 'MANAGER') && inStatusScope(s, u, p.employeeId)) { ensure(!p.ack.manager, 'invalid_state', 'Already acknowledged'); p.ack.manager = nowIso(); }
    else fail('forbidden', 'Only the employee or their line manager acknowledges this plan');
    if (o && o.comment) p.comments = (p.comments || []).concat([{ at: nowIso(), by: u.username, text: o.comment }]);
    var c = byId(s.cases, p.caseId);
    var who = isSelf(u, p.employeeId) ? 'Employee' : 'Line manager';
    if (p.ack.employee && p.ack.manager) { p.status = 'ACTIVE'; }
    if (c) {
      addTimeline(c, u.username, 'RTW_ACK', who + ' acknowledged plan ' + p.id + (p.status === 'ACTIVE' ? ' – plan now active.' : '.'), 'STATUS');
      if (c.owner) notify(s, [c.owner], 'RTW plan acknowledged', who + ' acknowledged ' + p.id + '.', { app: 'oh', route: 'case/' + c.id });
    }
    writeAudit(s, u.username, 'RTW_ACKNOWLEDGE', 'rtw', p.id, 'ALLOWED', who);
    return rtwView(s, u, p);
  });

  defineAction('closeCase', 'case.close', function (s, u, caseId, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    ensure(c.status !== 'CLOSED', 'invalid_state', 'Case already closed');
    var e = emp(s, c.employeeId); var sr = safetyRoleOf(s, e) || {};
    if (sr.safetyCritical) ensure(['UNDER_REVIEW', 'UNFIT_PENDING', 'NOT_ASSESSED'].indexOf(c.fitness.status) < 0 || c.status === 'INTAKE', 'closure_guard', 'Safety-critical role: record a fitness decision before closing.');
    var sup = c.supportCaseId ? byId(s.supportCases, c.supportCaseId) : null;
    ensure(!sup || !sup.urgentItems.some(function (i) { return i.status === 'OPEN'; }), 'urgent_open', 'An urgent item is still open');
    c.status = 'CLOSED'; c.closedAt = nowIso(); c.closeReason = o.reason || 'Case completed';
    if (c.rtwPlanId) { var p = byId(s.rtwPlans, c.rtwPlanId); if (p && p.status !== 'CANCELLED') p.status = 'COMPLETED'; }
    s.treatmentPlans.forEach(function (tp) { if (tp.caseId === c.id && tp.status === 'ACTIVE') tp.status = 'COMPLETED'; });
    addTimeline(c, u.username, 'CLOSED', 'Case closed: ' + c.closeReason + '.', 'STATUS');
    notifyEmployee(s, c.employeeId, 'Your case has been closed', c.id + ': ' + c.closeReason + '.', 'cases');
    writeAudit(s, u.username, 'CLOSE_CASE', 'case', c.id, 'ALLOWED', c.closeReason);
    return caseFullView(s, c);
  });

  defineAction('breakGlass', 'breakglass.request', function (s, u, employeeId, justification) {
    ensure(justification && justification.trim().length >= 10, 'validation', 'Give a justification (at least 10 characters)');
    var g = { id: nextId(s, 'BG', 'BG', 4), username: u.username, employeeId: employeeId, justification: justification, at: nowIso(), expiresAt: new Date(Date.now() + s.config.breakGlassHours * 3600000).toISOString(), reviewed: false };
    s.breakGlass.push(g);
    notifyRole(s, 'DPO', 'Break-glass access used', u.name + ' opened restricted records of ' + empName(s, employeeId) + '.', { app: 'oh', route: 'privacy/break-glass' }, 'URGENT');
    writeAudit(s, u.username, 'BREAK_GLASS', 'employee', employeeId, 'ALLOWED', justification);
    return g;
  });

  defineAction('reviewBreakGlass', 'breakglass.review', function (s, u, grantId, o) {
    var g = byId(s.breakGlass, grantId); ensure(g, 'not_found', 'Grant not found');
    g.reviewed = true; g.reviewedBy = u.username; g.reviewOutcome = (o && o.outcome) || 'APPROPRIATE'; g.reviewedAt = nowIso();
    writeAudit(s, u.username, 'BREAK_GLASS_REVIEW', 'breakGlass', g.id, 'ALLOWED', g.reviewOutcome);
    return g;
  });

  // =====================================================================================
  // Actions – appointments
  // =====================================================================================
  defineAction('bookAppointment', 'appointment.book', function (s, u, o) {
    o = o || {};
    var slot = byId(s.clinicSlots, o.slotId); ensure(slot, 'not_found', 'Slot not found');
    ensure(!slot.bookedBy, 'slot_taken', 'This slot has just been taken. Choose another.');
    var empId = o.employeeId || u.employeeId;
    if (!isOH(u)) ensure(isSelf(u, empId), 'forbidden', 'You can only book for yourself');
    ensure(emp(s, empId), 'validation', 'Employee not found');
    var a = { id: nextId(s, 'APT', 'APT', 4), employeeId: empId, slotId: slot.id, site: slot.site, start: slot.start, type: slot.type, clinician: slot.clinician,
      status: 'BOOKED', reason: o.reason || label('slot', slot.type), caseId: o.caseId || null, surveillanceId: o.surveillanceId || null, external: false, bookedBy: u.username, bookedAt: nowIso() };
    slot.bookedBy = empId; slot.appointmentId = a.id;
    s.appointments.push(a);
    if (o.surveillanceId) { var x = byId(s.surveillance, o.surveillanceId); if (x) { x.status = 'BOOKED'; x.appointmentId = a.id; } }
    if (o.caseId) { var c = byId(s.cases, o.caseId); if (c) addTimeline(c, u.username, 'APPOINTMENT', 'Appointment booked for ' + slot.start.slice(0, 10) + '.', 'STATUS'); }
    notify(s, [slot.clinician], 'Appointment booked', empName(s, empId) + ' – ' + slot.start.slice(0, 16).replace('T', ' ') + ' (' + slot.site + ')', { app: 'oh', route: 'appointments' });
    if (!isSelf(u, empId)) notifyEmployee(s, empId, 'Appointment booked', 'An appointment was booked for you on ' + slot.start.slice(0, 10) + '.', 'appointments');
    writeAudit(s, u.username, 'BOOK_APPOINTMENT', 'appointment', a.id, 'ALLOWED', slot.id);
    return a;
  });

  defineAction('cancelAppointment', 'appointment.book', function (s, u, appointmentId, reason) {
    var a = byId(s.appointments, appointmentId); ensure(a, 'not_found', 'Appointment not found');
    if (!isOH(u)) ensure(isSelf(u, a.employeeId), 'forbidden', 'Not your appointment');
    ensure(a.status === 'BOOKED', 'invalid_state', 'Appointment is not active');
    a.status = 'CANCELLED'; a.cancelReason = reason || '';
    var slot = byId(s.clinicSlots, a.slotId); if (slot) { slot.bookedBy = null; slot.appointmentId = null; }
    s.surveillance.forEach(function (x) { if (x.appointmentId === a.id) { x.appointmentId = null; x.status = x.dueDate < today() ? 'OVERDUE' : 'DUE'; } });
    if (a.clinician && !a.external) notify(s, [a.clinician], 'Appointment cancelled', empName(s, a.employeeId) + ' – ' + a.start.slice(0, 10), { app: 'oh', route: 'appointments' });
    writeAudit(s, u.username, 'CANCEL_APPOINTMENT', 'appointment', a.id, 'ALLOWED', reason || '');
    return a;
  });

  // =====================================================================================
  // Actions – absence, early intervention, advice
  // =====================================================================================
  function evaluateAbsenceTriggers(s, empId) {
    var fired = [];
    var mine = s.absences.filter(function (a) { return a.employeeId === empId; });
    s.config.absenceTriggerRules.forEach(function (rule) {
      var hit = false, detail = '';
      if (rule.spells) {
        var since = addDays(today(), -Math.round(rule.months * 30.4));
        var n = mine.filter(function (a) { return a.start >= since; }).length;
        if (n >= rule.spells) { hit = true; detail = n + ' spells in the last ' + rule.months + ' months'; }
      }
      if (rule.days) {
        mine.forEach(function (a) { var len = daysBetween(a.start, a.end || today()) + 1; if (len >= rule.days) { hit = true; detail = len + ' days in one spell'; } });
      }
      var exists = s.absenceTriggers.some(function (t) { return t.employeeId === empId && t.rule === rule.code && t.status !== 'DISMISSED' && Date.parse(t.firedAt) > Date.now() - 90 * 86400000; });
      if (hit && !exists) {
        var t = { id: nextId(s, 'TRG', 'TRG', 4), employeeId: empId, rule: rule.code, firedAt: nowIso(), detail: detail, status: 'OPEN', earlyInterventionId: null };
        s.absenceTriggers.push(t);
        var ei = { id: nextId(s, 'EI', 'EI', 4), employeeId: empId, triggerId: t.id, status: 'OPEN', raisedBy: 'system', raisedAt: nowIso(), caseId: null, adviceRequestId: null, note: rule.name };
        s.earlyInterventions.push(ei); t.earlyInterventionId = ei.id;
        notifyRole(s, 'HR', 'Absence trigger reached', empName(s, empId) + ' – ' + rule.name + '.', { app: 'manager', route: 'absence' }, 'ACTION');
        notifyRole(s, 'CASE_MANAGER', 'Early-intervention queue', empName(s, empId) + ' – ' + rule.name + '.', { app: 'oh', route: 'absence' });
        fired.push(t);
      }
    });
    return fired;
  }

  defineAction('recordAbsence', 'absence.record', function (s, u, o) {
    o = o || {};
    var empId = o.employeeId || u.employeeId;
    ensure(emp(s, empId), 'validation', 'Employee not found');
    ensure(isSelf(u, empId) || (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, empId)), 'forbidden', 'You cannot record absence for this person');
    ensure(o.start, 'validation', 'Start date is required');
    var lenDays = o.end ? daysBetween(o.start, o.end) + 1 : 1;
    var a = { id: nextId(s, 'ABS', 'ABS', 4), employeeId: empId, start: o.start, end: o.end || null, reasonCategory: o.reasonCategory || 'Illness – short term',
      certificate: { status: lenDays > 7 || o.certificateRequired ? 'REQUIRED' : 'NOT_REQUIRED' }, source: isSelf(u, empId) ? 'SELF' : hasRole(u, 'MANAGER') ? 'MANAGER' : 'HR', recordedBy: u.username, recordedAt: nowIso(), note: o.note || '' };
    s.absences.push(a);
    if (!isSelf(u, empId)) notifyEmployee(s, empId, 'Absence recorded', 'Your absence from ' + o.start + ' was recorded.', 'absence');
    else notifyManager(s, empId, 'Team member reported absence', empName(s, empId) + ' – from ' + o.start + '.', 'absence');
    var fired = evaluateAbsenceTriggers(s, empId);
    writeAudit(s, u.username, 'RECORD_ABSENCE', 'absence', a.id, 'ALLOWED', fired.length ? 'trigger ' + fired[0].rule : '');
    var v = clone(a); v.triggers = fired.map(function (t) { return { id: t.id, rule: t.rule, detail: t.detail }; }); return v;
  });

  defineAction('endAbsence', 'absence.record', function (s, u, absenceId, endDate) {
    var a = byId(s.absences, absenceId); ensure(a, 'not_found', 'Absence not found');
    ensure(isSelf(u, a.employeeId) || (anyRole(u, ['MANAGER', 'HR']) && inStatusScope(s, u, a.employeeId)), 'forbidden', 'Not allowed');
    a.end = endDate || today();
    writeAudit(s, u.username, 'END_ABSENCE', 'absence', a.id, 'ALLOWED', a.end);
    return a;
  });

  defineAction('uploadCertificate', 'certificate.upload', function (s, u, absenceId, o) {
    o = o || {};
    var a = byId(s.absences, absenceId); ensure(a, 'not_found', 'Absence not found');
    ensure(isSelf(u, a.employeeId), 'forbidden', 'Not your absence');
    ensure(o.fileName, 'validation', 'Choose a file');
    a.certificate = { status: 'RECEIVED', fileName: o.fileName, from: o.from || a.start, to: o.to || a.end, issuer: o.issuer || '', uploadedAt: nowIso() };
    addDocument(s, { employeeId: a.employeeId, type: 'MEDICAL_CERTIFICATE', title: 'Medical certificate (' + o.fileName + ')', by: u.username, classification: 'CLINICAL', visibleToEmployee: true });
    notifyRole(s, 'CASE_MANAGER', 'Certificate received', empName(s, a.employeeId) + ' – ' + o.fileName, { app: 'oh', route: 'absence' });
    writeAudit(s, u.username, 'UPLOAD_CERTIFICATE', 'absence', a.id, 'ALLOWED', o.fileName);
    return a;
  });

  defineAction('validateCertificate', 'certificate.validate', function (s, u, absenceId, status) {
    var a = byId(s.absences, absenceId); ensure(a && a.certificate, 'not_found', 'No certificate');
    ensure(status === 'VALIDATED' || status === 'REJECTED', 'validation', 'VALIDATED or REJECTED');
    a.certificate.status = status; a.certificate.checkedBy = u.username; a.certificate.checkedAt = nowIso();
    notifyEmployee(s, a.employeeId, 'Certificate ' + status.toLowerCase(), 'Your medical certificate was ' + status.toLowerCase() + '.', 'absence');
    writeAudit(s, u.username, 'VALIDATE_CERTIFICATE', 'absence', a.id, 'ALLOWED', status);
    return a;
  });

  defineAction('raiseEarlyIntervention', 'absence.earlyIntervention', function (s, u, idOrEmployee, o) {
    o = o || {};
    var ei = byId(s.earlyInterventions, idOrEmployee);
    if (!ei) {
      var trg = byId(s.absenceTriggers, idOrEmployee);
      if (trg) ei = byId(s.earlyInterventions, trg.earlyInterventionId);
    }
    if (!ei) {
      ensure(emp(s, idOrEmployee), 'not_found', 'Early-intervention item or employee not found');
      ei = { id: nextId(s, 'EI', 'EI', 4), employeeId: idOrEmployee, triggerId: null, status: 'OPEN', raisedBy: u.username, raisedAt: nowIso(), caseId: null, adviceRequestId: null, note: 'Raised manually' };
      s.earlyInterventions.push(ei);
    }
    ensure(ei.status === 'OPEN', 'invalid_state', 'Early intervention already actioned');
    if (hasRole(u, 'HR')) ensure(inStatusScope(s, u, ei.employeeId), 'forbidden', 'Out of HR scope');
    ei.raisedBy = u.username; ei.note = o.note || ei.note;
    if (o.referToOh === false) { ei.status = 'MONITOR'; writeAudit(s, u.username, 'EARLY_INTERVENTION', 'ei', ei.id, 'ALLOWED', 'monitor'); return ei; }
    var c = openKernelCase(s, u.username, { employeeId: ei.employeeId, category: 'OTHER', type: 'ABSENCE', source: 'ABSENCE_TRIGGER', workImpact: o.workImpact || ei.note, timelineText: 'Early-intervention referral from absence trigger.' });
    c.queue = 'CASE_MANAGEMENT'; c.status = 'TRIAGE'; c.owner = usersWithRole(s, 'CASE_MANAGER').filter(function (x) { return x.roles.length === 1; }).map(function (x) { return x.username; })[0] || null;
    var ar = { id: nextId(s, 'MAR', 'MAR', 4), employeeId: ei.employeeId, caseId: c.id, requestedBy: u.username, requestedAt: nowIso(),
      questions: o.questions && o.questions.length ? o.questions : ['Is there an underlying health reason for the absences?', 'Are adjustments recommended?', 'Is the employee fit for their role?'],
      status: 'REQUESTED', report: null };
    s.adviceRequests.push(ar);
    ei.status = 'REFERRED'; ei.caseId = c.id; ei.adviceRequestId = ar.id;
    var trg2 = ei.triggerId && byId(s.absenceTriggers, ei.triggerId); if (trg2) trg2.status = 'ACTIONED';
    if (c.owner) notify(s, [c.owner], 'Early-intervention referral', empName(s, ei.employeeId) + ' – advice requested (' + ar.id + ').', { app: 'oh', route: 'case/' + c.id }, 'ACTION');
    notifyEmployee(s, ei.employeeId, 'Referral to Occupational Health', 'HR has asked OH for advice to support you at work. OH will contact you; you will see the report before it is released.', 'cases');
    writeAudit(s, u.username, 'EARLY_INTERVENTION', 'ei', ei.id, 'ALLOWED', c.id);
    var v = clone(ei); v.caseId = c.id; v.adviceRequestId = ar.id; return v;
  });

  defineAction('requestAdvice', 'advice.request', function (s, u, o) {
    o = o || {};
    ensure(o.employeeId && inStatusScope(s, u, o.employeeId), 'forbidden', 'Out of scope');
    var c = o.caseId ? getCase(s, o.caseId) : openKernelCase(s, u.username, { employeeId: o.employeeId, category: 'OTHER', source: hasRole(u, 'HR') ? 'HR_REFERRAL' : 'MANAGER_REFERRAL', workImpact: o.workImpact || '', timelineText: 'Management advice requested.' });
    var ar = { id: nextId(s, 'MAR', 'MAR', 4), employeeId: o.employeeId, caseId: c.id, requestedBy: u.username, requestedAt: nowIso(), questions: o.questions || [], status: 'REQUESTED', report: null };
    s.adviceRequests.push(ar);
    notifyRole(s, 'CASE_MANAGER', 'Management advice requested', empName(s, o.employeeId) + ' – ' + ar.id, { app: 'oh', route: 'case/' + c.id });
    writeAudit(s, u.username, 'REQUEST_ADVICE', 'advice', ar.id, 'ALLOWED', c.id);
    return adviceView(s, u, ar);
  });

  defineAction('writeAdviceReport', 'advice.report', function (s, u, adviceId, o) {
    o = o || {};
    var ar = byId(s.adviceRequests, adviceId); ensure(ar, 'not_found', 'Advice request not found');
    ensure(['REQUESTED', 'CHANGES_REQUESTED'].indexOf(ar.status) >= 0, 'invalid_state', 'Report already written');
    ensure(o.workImpact || o.adjustments, 'validation', 'Write the work-impact advice');
    ar.report = { by: u.username, at: nowIso(), fitness: o.fitness || emp(s, ar.employeeId).fitness.status, likelyReturn: o.likelyReturn || '', adjustments: o.adjustments || '', workImpact: o.workImpact || '', review: o.review || null, answers: o.answers || [] };
    ar.status = 'AWAITING_EMPLOYEE_REVIEW';
    var c = byId(s.cases, ar.caseId);
    if (c) { addTimeline(c, u.username, 'ADVICE', 'Advice report written; with the employee for review before release.', 'STATUS'); if (c.status === 'TRIAGE' || c.status === 'INTAKE') c.status = 'ASSESSMENT'; }
    notifyEmployee(s, ar.employeeId, 'Please review your advice report', 'OH has written advice for your manager/HR. You can review it before it is released.', 'documents', 'ACTION');
    writeAudit(s, u.username, 'WRITE_ADVICE_REPORT', 'advice', ar.id, 'ALLOWED', '');
    return adviceView(s, u, ar);
  });

  defineAction('releaseAdvice', ['advice.release', 'advice.report'], function (s, u, adviceId, o) {
    o = o || {};
    var ar = byId(s.adviceRequests, adviceId); ensure(ar, 'not_found', 'Advice request not found');
    ensure(ar.status === 'AWAITING_EMPLOYEE_REVIEW', 'invalid_state', 'Report is not awaiting review');
    var decision = o.decision || 'RELEASE';
    if (!isSelf(u, ar.employeeId)) { ensure(isOH(u) && o.onBehalf, 'forbidden', 'Only the employee releases the report (OH may release after the review window with onBehalf)'); decision = 'RELEASE'; }
    ar.employeeReview = { decision: decision, at: nowIso(), comment: o.comment || '', by: u.username };
    var c = byId(s.cases, ar.caseId);
    if (decision === 'RELEASE') {
      ar.status = 'RELEASED'; ar.releasedAt = nowIso();
      var mgr = managerUserOf(s, ar.employeeId);
      var to = uniq([ar.requestedBy].concat(mgr ? [mgr.username] : []));
      addDocument(s, { employeeId: ar.employeeId, caseId: ar.caseId, type: 'ADVICE_REPORT', title: 'Management advice report ' + ar.id, by: ar.report.by, classification: 'STATUS', sharedWith: to });
      notify(s, to, 'Advice report released', empName(s, ar.employeeId) + ' – ' + ar.id, { app: 'manager', route: 'advice' });
      disclose(s, ar.employeeId, to.map(function (x) { return userName(s, x); }).join(', '), 'Management advice report (work impact, adjustments)', isSelf(u, ar.employeeId) ? 'Employee release' : 'Review window expired');
      if (c) addTimeline(c, u.username, 'ADVICE', 'Advice report released to ' + to.map(function (x) { return userName(s, x); }).join(', ') + '.', 'STATUS');
    } else if (decision === 'REQUEST_CHANGES') { ar.status = 'CHANGES_REQUESTED'; if (ar.report) notify(s, [ar.report.by], 'Employee requested changes', ar.id + ': ' + (o.comment || ''), { app: 'oh', route: 'case/' + ar.caseId }); }
    else { ar.status = 'WITHHELD'; notify(s, [ar.requestedBy], 'Advice report withheld', 'The employee did not consent to release ' + ar.id + '. OH will advise on fitness status only.', { app: 'manager', route: 'advice' }); }
    writeAudit(s, u.username, 'RELEASE_ADVICE', 'advice', ar.id, 'ALLOWED', decision);
    return adviceView(s, u, ar);
  });

  // =====================================================================================
  // Actions – surveillance
  // =====================================================================================
  defineAction('inviteSurveillance', 'surveillance.record', function (s, u, enrolmentId) {
    var x = byId(s.surveillance, enrolmentId); ensure(x, 'not_found', 'Enrolment not found');
    x.invitedAt = nowIso();
    notifyEmployee(s, x.employeeId, 'Health surveillance due', ((byId(s.surveillanceProgrammes, x.programme, 'code') || {}).name || 'Health check') + ' due by ' + x.dueDate + '. Please book an appointment.', 'appointments', 'ACTION');
    writeAudit(s, u.username, 'SURVEILLANCE_INVITE', 'surveillance', x.id, 'ALLOWED', '');
    return x;
  });

  defineAction('recordSurveillanceResult', 'surveillance.record', function (s, u, enrolmentId, o) {
    o = o || {};
    var x = byId(s.surveillance, enrolmentId); ensure(x, 'not_found', 'Enrolment not found');
    ensure(o.outcome === 'NORMAL' || o.outcome === 'ABNORMAL', 'validation', 'Outcome must be NORMAL or ABNORMAL');
    var prog = byId(s.surveillanceProgrammes, x.programme, 'code');
    x.results.push({ at: nowIso(), by: u.username, outcome: o.outcome, summary: o.summary || '' });
    x.lastDone = today(); x.lastOutcome = o.outcome;
    var ap = x.appointmentId && byId(s.appointments, x.appointmentId); if (ap) ap.status = 'ATTENDED';
    var caseId = null;
    if (o.outcome === 'NORMAL') { x.status = 'NOT_DUE'; x.dueDate = addDays(today(), Math.round(prog.intervalMonths * 30.4)); x.appointmentId = null; }
    else {
      x.status = 'FOLLOW_UP';
      var c = openKernelCase(s, u.username, { employeeId: x.employeeId, category: 'OTHER', type: 'SURVEILLANCE_FOLLOW_UP', source: 'SURVEILLANCE_TRIGGER', summary: o.summary || 'Abnormal surveillance result', timelineText: 'Opened from an abnormal ' + prog.name.toLowerCase() + ' result.' });
      c.status = 'TRIAGE'; c.queue = 'OH_NURSE'; caseId = c.id;
    }
    addDocument(s, { employeeId: x.employeeId, type: 'SURVEILLANCE_RESULT', title: prog.name + ' – result letter', by: u.username, classification: 'CLINICAL', visibleToEmployee: true });
    notifyEmployee(s, x.employeeId, 'Your health surveillance result', prog.name + ': ' + (o.outcome === 'NORMAL' ? 'no concerns. Next due ' + x.dueDate + '.' : 'OH will follow up with you.'), 'documents');
    writeAudit(s, u.username, 'SURVEILLANCE_RESULT', 'surveillance', x.id, 'ALLOWED', o.outcome);
    var v = clone(x); v.followUpCaseId = caseId; return v;
  });

  // =====================================================================================
  // Actions – treatment, partner referrals, claims
  // =====================================================================================
  defineAction('createTreatmentPlan', 'treatment.plan', function (s, u, caseId, o) {
    o = o || {};
    var c = getCase(s, caseId); ohCanTouch(s, u, c);
    ensure(o.goals && o.goals.length, 'validation', 'Add at least one goal');
    var tp = { id: nextId(s, 'TP', 'TP', 4), caseId: c.id, employeeId: c.employeeId, status: 'ACTIVE', createdBy: u.username, createdAt: nowIso(),
      goals: o.goals, interventions: o.interventions || [], expectedRecoveryDays: o.expectedRecoveryDays || 28,
      followUps: (o.followUps || [addDays(today(), 7), addDays(today(), 21)]).map(function (d) { return typeof d === 'string' ? { date: d, status: 'PLANNED', note: '' } : d; }) };
    s.treatmentPlans.push(tp);
    c.treatmentPlanId = tp.id; c.status = 'TREATMENT';
    addTimeline(c, u.username, 'TREATMENT', 'Treatment plan ' + tp.id + ' started (expected recovery ' + tp.expectedRecoveryDays + ' days).', 'SELF');
    notifyEmployee(s, c.employeeId, 'Your treatment plan', 'OH has set up a treatment plan with follow-up dates.', 'cases');
    writeAudit(s, u.username, 'CREATE_TREATMENT_PLAN', 'treatment', tp.id, 'ALLOWED', c.id);
    return tp;
  });

  defineAction('sendPartnerReferral', ['partner.refer', 'partner.refer.eap'], function (s, u, o) {
    o = o || {};
    var org = byId(s.partnerOrgs, o.partnerOrg, 'code'); ensure(org, 'validation', 'Choose a partner organisation');
    var isEap = org.type === 'EAP';
    var empId, caseId = null, supId = null;
    if (isEap) {
      ensure(can(u, 'partner.refer.eap') && isNamedClinician(s, u), 'forbidden', 'EAP referrals are made by the named mental-health clinician');
      var sup = byId(s.supportCases, o.supportCaseId); ensure(sup, 'not_found', 'Support case not found');
      ensure(sup.status !== 'CLOSED', 'case_closed', 'Support case is closed');
      empId = sup.employeeId; supId = sup.id;
    } else {
      ensure(can(u, 'partner.refer'), 'forbidden', 'Not allowed');
      var c = getCase(s, o.caseId); ohCanTouch(s, u, c); empId = c.employeeId; caseId = c.id;
    }
    ensure(o.consent === true, 'consent_required', 'Record the employee\'s consent to share the referral data set with ' + org.name + '.');
    var svc = byId(s.serviceCatalogue, o.service || org.services[0], 'code') || { code: o.service, reportDueDays: 14 };
    var con = { id: nextId(s, 'CON', 'CON', 4), employeeId: empId, type: isEap ? 'EAP_REFERRAL' : 'PARTNER_TREATMENT', scope: (isEap ? 'Refer me to ' : 'Share referral data set with ') + org.name, partnerOrg: org.code, status: 'GIVEN', at: nowIso(), recordedBy: u.username };
    s.consents.push(con);
    var r = { id: nextId(s, 'RFL', 'RFL', 4), kind: isEap ? 'EAP' : 'TREATMENT', caseId: caseId, supportCaseId: supId, employeeId: empId, partnerOrg: org.code, service: svc.code,
      urgency: o.urgency || 'ROUTINE', status: 'SENT', sentBy: u.username, sentAt: nowIso(), question: isEap ? 'Short-term counselling' : (o.question || ''),
      dataSet: isEap ? ['Name and contact preference only'] : (o.dataSet || ['Referral question', 'Work demands (job profile)']), consentId: con.id,
      acceptedAt: null, appointmentAt: null, reportDue: addDays(today(), svc.reportDueDays || 14), report: null, sessions: 0, closedAt: null };
    s.referrals.push(r);
    if (isEap) { var sp = byId(s.supportCases, supId); sp.eapReferralId = r.id; sp.status = 'REFERRED'; sp.notes.push({ at: nowIso(), by: u.username, text: 'EAP referral ' + r.id + ' sent.' }); }
    else { var cc = byId(s.cases, caseId); addTimeline(cc, u.username, 'REFERRAL_OUT', 'Referred to ' + org.name + ' (' + (svc.name || svc.code) + ').', 'SELF'); }
    notify(s, partnerUsers(s, org.code), isEap ? 'New EAP referral' : 'New referral', r.id + ' – ' + (svc.name || svc.code), { app: 'partner', route: isEap ? 'eap' : 'referrals' }, 'ACTION');
    disclose(s, empId, org.name, r.dataSet.join(', '), 'Consent ' + con.id);
    writeAudit(s, u.username, 'SEND_PARTNER_REFERRAL', 'referral', r.id, 'ALLOWED', org.code);
    return referralView(s, u, r);
  });

  function partnerReferral(s, u, id) {
    var r = byId(s.referrals, id); ensure(r, 'not_found', 'Referral not found');
    ensure(u.partnerOrg && u.partnerOrg === r.partnerOrg, 'not_found', 'Referral not found');
    var con = byId(s.consents, r.consentId);
    ensure(!con || con.status !== 'WITHDRAWN', 'consent_withdrawn', 'The employee withdrew consent; this referral is no longer available.');
    return r;
  }

  defineAction('partnerAccept', ['partner.referrals', 'partner.eap'], function (s, u, referralId, o) {
    o = o || {};
    var r = partnerReferral(s, u, referralId);
    ensure(r.status === 'SENT' || r.status === 'ACCEPTED', 'invalid_state', 'Referral already accepted');
    r.acceptedAt = r.acceptedAt || nowIso(); r.status = o.appointmentAt ? 'APPOINTMENT_SET' : 'ACCEPTED';
    if (o.appointmentAt) {
      r.appointmentAt = o.appointmentAt;
      s.appointments.push({ id: nextId(s, 'APT', 'APT', 4), employeeId: r.employeeId, slotId: null, site: r.partnerOrg, start: o.appointmentAt, type: 'EXTERNAL', clinician: orgName(s, r.partnerOrg), status: 'BOOKED', reason: (byId(s.serviceCatalogue, r.service, 'code') || {}).name || 'Partner appointment', caseId: r.caseId, external: true });
      notifyEmployee(s, r.employeeId, 'Appointment with ' + orgName(s, r.partnerOrg), 'Your appointment is on ' + o.appointmentAt.slice(0, 16).replace('T', ' ') + '.', 'appointments');
    }
    if (r.kind === 'EAP' && !o.appointmentAt) r.status = 'ACCEPTED';
    var c = r.caseId && byId(s.cases, r.caseId);
    if (c) addTimeline(c, u.username, 'PARTNER', orgName(s, r.partnerOrg) + ' accepted referral ' + r.id + '.', 'SELF');
    notify(s, [r.sentBy], 'Referral accepted', r.id + ' – ' + orgName(s, r.partnerOrg), { app: 'oh', route: r.kind === 'EAP' ? 'support/' + r.supportCaseId : 'referrals' });
    writeAudit(s, u.username, 'PARTNER_ACCEPT', 'referral', r.id, 'ALLOWED', r.status);
    return referralView(s, u, r);
  });

  defineAction('partnerDecline', ['partner.referrals', 'partner.eap'], function (s, u, referralId, reason) {
    var r = partnerReferral(s, u, referralId);
    ensure(r.status === 'SENT', 'invalid_state', 'Only new referrals can be declined');
    r.status = 'DECLINED'; r.declineReason = reason || 'No capacity';
    notify(s, [r.sentBy], 'Referral declined', r.id + ': ' + r.declineReason, { app: 'oh', route: 'referrals' }, 'ACTION');
    writeAudit(s, u.username, 'PARTNER_DECLINE', 'referral', r.id, 'ALLOWED', r.declineReason);
    return referralView(s, u, r);
  });

  defineAction('partnerUploadReport', 'partner.referrals', function (s, u, referralId, o) {
    o = o || {};
    var r = partnerReferral(s, u, referralId);
    ensure(r.kind !== 'EAP', 'forbidden', 'EAP partners report attendance and closure only');
    ensure(['ACCEPTED', 'APPOINTMENT_SET', 'IN_PROGRESS', 'REPORT_RECEIVED'].indexOf(r.status) >= 0, 'invalid_state', 'Accept the referral first');
    ensure(o.summary, 'validation', 'Add a report summary');
    r.report = { at: nowIso(), by: u.username, summary: o.summary, recommendation: o.recommendation || '', fileName: o.fileName || 'report.pdf' };
    r.status = 'REPORT_RECEIVED'; r.sessions = o.sessions != null ? o.sessions : r.sessions;
    addDocument(s, { employeeId: r.employeeId, caseId: r.caseId, type: 'PARTNER_REPORT', title: orgName(s, r.partnerOrg) + ' report (' + r.id + ')', by: u.username, classification: 'CLINICAL', visibleToEmployee: false });
    var c = r.caseId && byId(s.cases, r.caseId);
    if (c) addTimeline(c, u.username, 'REPORT', 'Partner report received from ' + orgName(s, r.partnerOrg) + '.', 'SELF');
    notify(s, [r.sentBy].concat(c && c.owner ? [c.owner] : []), 'Partner report received', r.id + ' – ' + orgName(s, r.partnerOrg), { app: 'oh', route: c ? 'case/' + c.id : 'referrals' }, 'ACTION');
    writeAudit(s, u.username, 'PARTNER_UPLOAD_REPORT', 'referral', r.id, 'ALLOWED', r.report.fileName);
    return referralView(s, u, r);
  });

  defineAction('partnerRecordAttendance', 'partner.eap', function (s, u, referralId, o) {
    o = o || {};
    var r = partnerReferral(s, u, referralId);
    ensure(r.kind === 'EAP', 'invalid_state', 'Attendance is recorded on EAP referrals');
    ensure(['ACCEPTED', 'APPOINTMENT_SET', 'IN_PROGRESS'].indexOf(r.status) >= 0, 'invalid_state', 'Referral is not active');
    r.sessions = o.sessions != null ? o.sessions : (r.sessions || 0) + 1; r.status = 'IN_PROGRESS'; r.lastAttendance = o.date || today();
    writeAudit(s, u.username, 'EAP_ATTENDANCE', 'referral', r.id, 'ALLOWED', String(r.sessions));
    return referralView(s, u, r);
  });

  defineAction('partnerClose', ['partner.referrals', 'partner.eap'], function (s, u, referralId, o) {
    o = o || {};
    var r = partnerReferral(s, u, referralId);
    ensure(r.status !== 'CLOSED' && r.status !== 'DECLINED', 'invalid_state', 'Referral already closed');
    var outcomes = ['COMPLETED', 'DID_NOT_ENGAGE', 'ONWARD_REFERRAL', 'EMPLOYEE_WITHDREW'];
    ensure(outcomes.indexOf(o.outcome) >= 0, 'validation', 'Choose a closure outcome');
    r.status = 'CLOSED'; r.closedAt = nowIso(); r.outcome = o.outcome; // no free text by design (EAP)
    if (r.kind === 'EAP') {
      var sup = byId(s.supportCases, r.supportCaseId);
      if (sup && sup.status === 'REFERRED') sup.status = 'ACTIVE';
      if (sup) sup.notes.push({ at: nowIso(), by: 'system', text: 'EAP closed: ' + label('eapOutcome', o.outcome) + ' after ' + r.sessions + ' session(s).' });
      notify(s, sup ? [sup.namedClinician] : [r.sentBy], 'EAP referral closed', r.id + ' – ' + o.outcome.replace(/_/g, ' ').toLowerCase(), { app: 'oh', route: 'support/' + r.supportCaseId });
      notifyEmployee(s, r.employeeId, 'Your EAP sessions have ended', 'Your support worker will check in with you.', 'support');
    } else {
      notify(s, [r.sentBy], 'Referral closed', r.id, { app: 'oh', route: 'referrals' });
    }
    writeAudit(s, u.username, 'PARTNER_CLOSE', 'referral', r.id, 'ALLOWED', o.outcome);
    return referralView(s, u, r);
  });

  defineAction('openClaim', 'claim.open', function (s, u, o) {
    o = o || {};
    var c = getCase(s, o.caseId); ensure(!isRestrictedCase(c), 'not_found', 'Case not found');
    ensure(c.category === 'INJURY' || c.category === 'EXPOSURE' || c.category === 'ILLNESS', 'validation', 'Claims are opened for work-related injury, exposure or illness');
    ensure(!s.claims.some(function (x) { return x.caseId === c.id && x.status !== 'CLOSED'; }), 'invalid_state', 'A claim is already open for this case');
    var payer = byId(s.partnerOrgs, o.payerOrg || 'GULFTPA', 'code'); ensure(payer && payer.type === 'TPA', 'validation', 'Choose a TPA / insurer');
    ensure(o.consent === true, 'consent_required', 'Record the employee\'s consent to share claim documents with the TPA.');
    var con = { id: nextId(s, 'CON', 'CON', 4), employeeId: c.employeeId, type: 'TPA_DISCLOSURE', scope: 'Share claim documents (non-clinical) with ' + payer.name, partnerOrg: payer.code, status: 'GIVEN', at: nowIso(), recordedBy: u.username };
    s.consents.push(con);
    s.counters.CLM = (s.counters.CLM || 0) + 1;
    var cl = { id: 'CLM-' + new Date().getFullYear() + '-' + pad(s.counters.CLM, 6), caseId: c.id, employeeId: c.employeeId, type: o.type || 'WORKERS_COMPENSATION', payerOrg: payer.code,
      status: 'SUBMITTED', openedBy: u.username, openedAt: nowIso(), incidentDate: (c.incident && c.incident.date) || c.openedAt.slice(0, 10), lostTimeDays: o.lostTimeDays || 0,
      reserve: o.reserve || 0, currency: 'GBP', decision: null, rtwStatus: c.rtwPlanId ? 'Return-to-work plan in place' : label('fitness', c.fitness.status), consentId: con.id,
      documents: ['First report of injury', 'Fitness status (no clinical detail)'].concat(o.documents || []), messages: [] };
    s.claims.push(cl);
    if (c.status !== 'CLOSED') c.status = c.status === 'TREATMENT' ? 'TREATMENT' : 'CLAIMS';
    addTimeline(c, u.username, 'CLAIM', 'Workers\' compensation claim ' + cl.id + ' opened and submitted to ' + payer.name + '.', 'SELF');
    notify(s, partnerUsers(s, payer.code), 'New claim submitted', cl.id + ' – ' + empName(s, c.employeeId), { app: 'partner', route: 'claims' }, 'ACTION');
    notifyEmployee(s, c.employeeId, 'A claim was opened for your injury', 'Claim ' + cl.id + ' was submitted with your consent.', 'cases');
    disclose(s, c.employeeId, payer.name, cl.documents.join(', '), 'Consent ' + con.id);
    writeAudit(s, u.username, 'OPEN_CLAIM', 'claim', cl.id, 'ALLOWED', payer.code);
    return claimView(s, u, cl);
  });

  defineAction('claimMessage', 'claim.message', function (s, u, claimId, text) {
    var cl = byId(s.claims, claimId); ensure(cl, 'not_found', 'Claim not found');
    if (hasRole(u, 'TPA_ADJUSTER')) ensure(u.partnerOrg === cl.payerOrg, 'not_found', 'Claim not found');
    ensure(text && text.trim(), 'validation', 'Message is empty');
    cl.messages.push({ at: nowIso(), by: u.username, text: text.trim() });
    var to = hasRole(u, 'TPA_ADJUSTER') ? [cl.openedBy] : partnerUsers(s, cl.payerOrg);
    notify(s, to, 'New message on ' + cl.id, text.trim().slice(0, 80), hasRole(u, 'TPA_ADJUSTER') ? { app: 'oh', route: 'claims' } : { app: 'partner', route: 'claims' });
    writeAudit(s, u.username, 'CLAIM_MESSAGE', 'claim', cl.id, 'ALLOWED', '');
    return claimView(s, u, cl);
  });

  defineAction('adjusterDecision', 'claim.adjust', function (s, u, claimId, o) {
    o = o || {};
    var cl = byId(s.claims, claimId); ensure(cl && cl.payerOrg === u.partnerOrg, 'not_found', 'Claim not found');
    ensure(['SUBMITTED', 'UNDER_REVIEW', 'MORE_INFO'].indexOf(cl.status) >= 0, 'invalid_state', 'Claim is not awaiting a decision');
    ensure(['ACCEPTED', 'DENIED', 'MORE_INFO', 'UNDER_REVIEW'].indexOf(o.decision) >= 0, 'validation', 'Decision must be ACCEPTED, DENIED, MORE_INFO or UNDER_REVIEW');
    cl.status = o.decision;
    if (o.decision !== 'UNDER_REVIEW') cl.decision = { outcome: o.decision, at: nowIso(), by: u.username, note: o.note || '', reserve: o.reserve != null ? o.reserve : cl.reserve };
    if (o.reserve != null) cl.reserve = o.reserve;
    var c = byId(s.cases, cl.caseId);
    if (c) addTimeline(c, u.username, 'CLAIM', 'Claim ' + cl.id + ': ' + label('claim', o.decision) + '.', 'SELF');
    notify(s, uniq([cl.openedBy].concat(usersWithRole(s, 'CLAIMS_COORDINATOR').map(function (x) { return x.username; }))), 'TPA decision: ' + label('claim', o.decision), cl.id + (o.note ? ' – ' + o.note : ''), { app: 'oh', route: 'claims' }, 'ACTION');
    notifyRole(s, 'HR', 'Claim status updated', empName(s, cl.employeeId) + ' – ' + label('claim', o.decision) + ' (status only).', { app: 'manager', route: 'claims' });
    if (o.decision === 'ACCEPTED' || o.decision === 'DENIED') notifyEmployee(s, cl.employeeId, 'Claim decision', 'Your claim ' + cl.id + ' was ' + o.decision.toLowerCase() + '.', 'cases');
    writeAudit(s, u.username, 'ADJUSTER_DECISION', 'claim', cl.id, 'ALLOWED', o.decision);
    return claimView(s, u, cl);
  });

  defineAction('closeClaim', 'claim.manage', function (s, u, claimId, reason) {
    var cl = byId(s.claims, claimId); ensure(cl, 'not_found', 'Claim not found');
    cl.status = 'CLOSED'; cl.closedAt = nowIso(); cl.closeReason = reason || 'Settled';
    writeAudit(s, u.username, 'CLOSE_CLAIM', 'claim', cl.id, 'ALLOWED', cl.closeReason);
    return claimView(s, u, cl);
  });

  // =====================================================================================
  // Actions – confidential support (restricted class)
  // =====================================================================================
  function ownSupport(s, u, id) { var x = byId(s.supportCases, id); ensure(x && isSelf(u, x.employeeId), 'not_found', 'Support case not found'); return x; }
  function clinicianSupport(s, u, id) { var x = byId(s.supportCases, id); ensure(x && restrictedAccess(s, u, 'PSYCHOLOGICAL', x.employeeId) === 'FULL', 'not_found', 'Support case not found'); return x; }

  defineAction('selfReferSupport', 'support.self', function (s, u, o) {
    o = o || {};
    ensure(o.consent === true, 'consent_required', 'Please confirm you agree to be contacted by the confidential support service.');
    var x = { id: nextId(s, 'SUP', 'SUP', 4), employeeId: u.employeeId, kernelCaseId: null, status: 'OPEN', risk: 'LOW', source: 'SELF_REFERRAL', namedClinician: s.config.namedClinicians[0] || null,
      openedAt: nowIso(), sharing: 'NOTHING', sharingAt: null, sharedAdjustments: null, contactPreference: o.contactPreference || 'PHONE',
      concern: o.concern || '', screens: [], urgentItems: [], notes: [], eapReferralId: null, consentId: null };
    var con = { id: nextId(s, 'CON', 'CON', 4), employeeId: u.employeeId, type: 'SUPPORT_SERVICE', scope: 'Confidential support from the named mental-health clinician', status: 'GIVEN', at: nowIso() };
    s.consents.push(con); x.consentId = con.id;
    s.supportCases.push(x);
    notify(s, s.config.namedClinicians, 'New confidential support request', x.id + ' (details in the support caseload)', { app: 'oh', route: 'support/' + x.id }, 'ACTION');
    writeAudit(s, u.username, 'SUPPORT_SELF_REFERRAL', 'support', x.id, 'ALLOWED', '');
    var screen = Array.isArray(o.screen) ? screenInto(s, u, x, o.screen) : null;
    var result = supportView(s, u, x);
    result.screen = screen;
    return result;
  });

  function screenInto(s, u, x, answers) {
    ensure(answers.length === 9 && answers.every(function (a) { return a >= 0 && a <= 3; }), 'validation', 'Answer all 9 questions (0–3)');
    var score = answers.reduce(function (a, b) { return a + b; }, 0);
    var item9 = answers[8];
    var band = score >= 20 ? 'SEVERE' : score >= 15 ? 'MODERATELY_SEVERE' : score >= 10 ? 'MODERATE' : score >= 5 ? 'MILD' : 'MINIMAL';
    x.screens.push({ at: nowIso(), score: score, item9: item9, band: band });
    if (x.status === 'OPEN') x.status = 'SCREENED';
    var urgent = item9 > 0 || score >= s.config.supportScreen.urgentScore;
    x.risk = urgent ? 'URGENT' : score >= 15 ? 'HIGH' : score >= 10 ? 'MODERATE' : 'LOW';
    var item = null;
    if (urgent) {
      item = { id: x.id + '-U' + (x.urgentItems.length + 1), at: nowIso(), reason: item9 > 0 ? 'Screening answer indicates risk of self-harm' : 'Severe screening score', status: 'OPEN', target: 'Call back within 1 hour' };
      x.urgentItems.push(item);
      notify(s, [x.namedClinician], 'URGENT call-back required', x.id + ' – screening escalated', { app: 'oh', route: 'support/' + x.id }, 'URGENT');
    }
    writeAudit(s, u.username, 'SUPPORT_SCREEN', 'support', x.id, 'ALLOWED', urgent ? 'urgent' : band);
    return { score: score, band: band, urgent: urgent, urgentItemId: item ? item.id : null, emergencyNumber: s.config.emergencyNumber };
  }

  defineAction('submitSupportScreen', 'support.self', function (s, u, supportCaseId, answers) {
    var x = ownSupport(s, u, supportCaseId);
    ensure(x.status !== 'CLOSED', 'case_closed', 'Support case is closed');
    return screenInto(s, u, x, answers || []);
  });

  defineAction('urgentCallback', 'support.self', function (s, u, supportCaseId, o) {
    o = o || {};
    var x = ownSupport(s, u, supportCaseId);
    ensure(x.status !== 'CLOSED', 'case_closed', 'Support case is closed');
    var open = x.urgentItems.filter(function (i) { return i.status === 'OPEN'; })[0];
    if (!open) {
      open = { id: x.id + '-U' + (x.urgentItems.length + 1), at: nowIso(), reason: 'Employee requested an urgent call-back', status: 'OPEN', target: 'Call back within 1 hour' };
      x.urgentItems.push(open);
    }
    open.phone = o.phone || open.phone || 'on file'; open.requestedAt = nowIso();
    x.risk = 'URGENT';
    notify(s, [x.namedClinician], 'URGENT call-back requested', x.id, { app: 'oh', route: 'support/' + x.id }, 'URGENT');
    writeAudit(s, u.username, 'URGENT_CALLBACK_REQUEST', 'support', x.id, 'ALLOWED', '');
    return { urgentItemId: open.id, target: open.target, emergencyNumber: s.config.emergencyNumber };
  });

  defineAction('recordCallback', 'support.callback', function (s, u, supportCaseId, itemId, o) {
    o = o || {};
    var x = clinicianSupport(s, u, supportCaseId);
    var it = byId(x.urgentItems, itemId) || x.urgentItems.filter(function (i) { return i.status === 'OPEN'; })[0];
    ensure(it && it.status === 'OPEN', 'invalid_state', 'No open urgent item');
    ensure(['REACHED_SAFE', 'NO_ANSWER', 'EMERGENCY_SERVICES'].indexOf(o.outcome) >= 0, 'validation', 'Choose an outcome');
    it.attempts = (it.attempts || []).concat([{ at: nowIso(), by: u.username, outcome: o.outcome }]);
    if (o.outcome !== 'NO_ANSWER') { it.status = o.outcome; it.closedAt = nowIso(); }
    if (o.outcome === 'REACHED_SAFE' && ['OPEN', 'SCREENED'].indexOf(x.status) >= 0) x.status = 'ACTIVE';
    if (o.outcome === 'REACHED_SAFE') x.risk = 'HIGH';
    x.notes.push({ at: nowIso(), by: u.username, text: 'Urgent call-back: ' + o.outcome.replace(/_/g, ' ').toLowerCase() + (o.note ? ' – ' + o.note : '') });
    writeAudit(s, u.username, 'URGENT_CALLBACK', 'support', x.id, 'ALLOWED', o.outcome);
    return supportView(s, u, x);
  });

  defineAction('addSupportNote', 'support.clinical', function (s, u, supportCaseId, text) {
    var x = clinicianSupport(s, u, supportCaseId);
    ensure(x.status !== 'CLOSED', 'case_closed', 'Support case is closed');
    ensure(text && text.trim(), 'validation', 'Note is empty');
    x.notes.push({ at: nowIso(), by: u.username, text: text.trim() });
    if (['OPEN', 'SCREENED'].indexOf(x.status) >= 0) x.status = 'ACTIVE';
    writeAudit(s, u.username, 'SUPPORT_NOTE', 'support', x.id, 'ALLOWED', '');
    return supportView(s, u, x);
  });

  defineAction('recordSupportFitness', ['support.fitness', 'support.relief'], function (s, u, supportCaseId, o) {
    o = o || {};
    var x = byId(s.supportCases, supportCaseId); ensure(x, 'not_found', 'Support case not found');
    // Documented exception: the AME may act on relief / fitness without reading the case content.
    ensure(isNamedClinician(s, u) || hasRole(u, 'AME'), 'not_found', 'Support case not found');
    ensure(['FIT', 'FIT_WITH_RESTRICTIONS', 'TEMPORARILY_UNFIT'].indexOf(o.status) >= 0, 'validation', 'Choose a fitness outcome');
    var e = emp(s, x.employeeId); var sr = safetyRoleOf(s, e) || {};
    if (o.status === 'TEMPORARILY_UNFIT' && (sr.code === 'PILOT' || sr.code === 'CABIN_CREW')) ensure(hasRole(u, 'AME'), 'forbidden', 'Relief of crew from flying duties is decided by the AME (the clinician contributes).');
    e.fitness = { status: o.status, since: today(), decidedBy: u.username };
    if (o.status === 'TEMPORARILY_UNFIT' && sr.safetyCritical) { e.dutyStatus = 'RELIEVED'; e.dutySince = today(); x.status = 'RELIEF'; }
    else if (e.dutyStatus === 'RELIEVED') { e.dutyStatus = 'AVAILABLE'; e.dutySince = today(); if (x.status === 'RELIEF') x.status = 'ACTIVE'; }
    x.fitnessContribution = { status: o.status, at: nowIso(), by: u.username, reviewDate: o.reviewDate || null };
    x.notes.push({ at: nowIso(), by: u.username, text: 'Fitness recorded: ' + label('fitness', o.status) + '.' });
    if (isCrew(s, e)) notifyRole(s, 'CREW_SCHEDULING', 'Crew roster status changed', e.name + ' – ' + label('fitness', o.status) + ' (no reason given).', { app: 'manager', route: 'crew' });
    notifyEmployee(s, x.employeeId, 'Fitness status recorded', 'Your fitness for work: ' + label('fitness', o.status) + '. Your manager sees this only if you choose to share it.', 'support');
    writeAudit(s, u.username, 'SUPPORT_FITNESS', 'support', x.id, 'ALLOWED', o.status);
    return { supportCaseId: x.id, fitness: o.status };
  });

  defineAction('setSupportSharing', 'support.self', function (s, u, supportCaseId, level, adjustments) {
    var x = ownSupport(s, u, supportCaseId);
    ensure(LABELS.sharing[level], 'validation', 'Choose NOTHING, FITNESS_ONLY or FITNESS_AND_ADJUSTMENTS');
    if (level === 'FITNESS_AND_ADJUSTMENTS') {
      ensure(adjustments && adjustments.trim(), 'validation', 'Describe the adjustments to share');
      ensure(!/(depress|anxi|suicid|self.?harm|medicat|diagnos|therap|counsel|ptsd|bipolar|psych)/i.test(adjustments), 'clinical_content', 'Please describe practical adjustments only, without health details.');
    }
    x.sharing = level; x.sharingAt = nowIso(); x.sharedAdjustments = level === 'FITNESS_AND_ADJUSTMENTS' ? adjustments.trim() : null;
    var mgr = managerUserOf(s, x.employeeId);
    if (level !== 'NOTHING' && mgr) {
      notify(s, [mgr.username], 'A team member shared a work outcome with you', empName(s, x.employeeId) + ' shared ' + label('sharing', level).toLowerCase() + '.', { app: 'manager', route: 'shared-outcomes' });
      disclose(s, x.employeeId, mgr.name + ' (line manager)', label('sharing', level) + ' (employee choice)', 'Employee sharing choice');
    }
    writeAudit(s, u.username, 'SUPPORT_SHARING', 'support', x.id, 'ALLOWED', level);
    return supportView(s, u, x);
  });

  defineAction('shareFitnessOnly', 'support.self', function (s, u, supportCaseId) {
    var x = ownSupport(s, u, supportCaseId);
    x.sharing = 'FITNESS_ONLY'; x.sharingAt = nowIso(); x.sharedAdjustments = null;
    var mgr = managerUserOf(s, x.employeeId);
    if (mgr) {
      notify(s, [mgr.username], 'A team member shared a work outcome with you', empName(s, x.employeeId) + ' shared fitness only.', { app: 'manager', route: 'shared-outcomes' });
      disclose(s, x.employeeId, mgr.name + ' (line manager)', 'Fitness status only (employee choice)', 'Employee sharing choice');
    }
    writeAudit(s, u.username, 'SUPPORT_SHARING', 'support', x.id, 'ALLOWED', 'FITNESS_ONLY');
    return supportView(s, u, x);
  });

  defineAction('closeSupportCase', 'support.clinical', function (s, u, supportCaseId, reason) {
    var x = clinicianSupport(s, u, supportCaseId);
    ensure(!x.urgentItems.some(function (i) { return i.status === 'OPEN'; }), 'urgent_open', 'Close the urgent item first');
    ensure(x.status !== 'RELIEF', 'relief_active', 'Relief from duties is still active');
    x.status = 'CLOSED'; x.closedAt = nowIso(); x.closeReason = reason || 'Support completed';
    x.notes.push({ at: nowIso(), by: u.username, text: 'Closed: ' + x.closeReason });
    notifyEmployee(s, x.employeeId, 'Your support case is closed', 'You can come back to the service at any time.', 'support');
    writeAudit(s, u.username, 'SUPPORT_CLOSE', 'support', x.id, 'ALLOWED', x.closeReason);
    return supportView(s, u, x);
  });

  defineAction('peerRequest', 'peer.request', function (s, u, o) {
    o = o || {};
    ensure(o.alias && o.alias.trim(), 'validation', 'Choose an alias');
    var A = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    function blk() { var t = ''; for (var i = 0; i < 4; i++) t += A[Math.floor(Math.random() * A.length)]; return t; }
    var code = 'PS-' + blk() + '-' + blk();
    var vols = usersWithRole(s, 'PEER_SUPPORT_VOLUNTEER');
    // Pseudonymous: no employee id is stored on the request, and the audit entry carries no code.
    s.peerRequests.push({ code: code, alias: o.alias.trim(), language: o.language || 'en', base: o.base || 'HUB', status: 'OPEN', volunteer: vols.length ? vols[0].username : null, createdAt: nowIso(), callbackPhone: o.callbackPhone || '', contacts: [] });
    if (vols.length) notify(s, [vols[0].username], 'New peer support request', 'Alias ' + o.alias.trim() + ' – ' + code, { app: 'oh', route: 'peer-support' }, 'ACTION');
    writeAudit(s, 'pseudonymous', 'PEER_REQUEST', 'peer', '—', 'ALLOWED', '');
    return { code: code, alias: o.alias.trim() };
  });

  defineAction('peerLogContact', 'peer.volunteer', function (s, u, code, o) {
    o = o || {};
    var p = byId(s.peerRequests, code, 'code'); ensure(p && p.volunteer === u.username, 'not_found', 'Request not found');
    p.contacts.push({ at: nowIso(), category: o.category || 'GENERAL', outcome: o.outcome || 'SUPPORTED' });
    p.status = o.outcome === 'REFERRED' ? 'REFERRED' : 'CONTACTED';
    writeAudit(s, u.username, 'PEER_CONTACT', 'peer', '—', 'ALLOWED', o.category || '');
    return clone(p);
  });

  // =====================================================================================
  // Actions – drug & alcohol testing
  // =====================================================================================
  /** Reproducible PRNG (mulberry32); its state lives in the session so selections replay identically after a reset. */
  function rand(s) {
    var a = (s.testing.rng = (s.testing.rng + 0x6D2B79F5) >>> 0);
    var t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  defineAction('randomSelection', 'testing.select', function (s, u, poolId, o) {
    o = o || {};
    var pool = byId(s.testing.pools, poolId); ensure(pool, 'not_found', 'Pool not found');
    var count = Math.max(1, Math.min(o.count || 2, pool.members.length));
    var eligible = pool.members.filter(function (m) { var e = emp(s, m); return e && e.dutyStatus === 'AVAILABLE'; });
    var picked = [];
    while (picked.length < count && eligible.length) { var i = Math.floor(rand(s) * eligible.length); picked.push(eligible.splice(i, 1)[0]); }
    var sel = { id: nextId(s, 'SEL', 'SEL', 4), poolId: pool.id, at: nowIso(), by: u.username, count: count, selected: picked, method: 'Seeded random draw (demo, reproducible)' };
    s.testing.selections.push(sel);
    var pol = byId(s.testing.policies, pool.policy, 'code') || { noticeMinutes: 120 };
    var deadline = new Date(Date.now() + pol.noticeMinutes * 60000);
    picked.forEach(function (empId) {
      var e = emp(s, empId);
      var loc = e.site === 'HUB' ? 'Hub clinic, Building 7, room 2' : (byId(s.sites, e.site, 'code') || {}).name + ' – crew room';
      var t = { id: nextId(s, 'DAT', 'DAT', 4), employeeId: empId, reason: 'RANDOM', selectionId: sel.id, status: 'NOTIFIED', panel: o.panel || 'Drug panel (urine) + breath alcohol',
        notice: { location: loc, window: 'Report by ' + pad(deadline.getHours(), 2) + ':' + pad(deadline.getMinutes(), 2) + ' today' }, notifiedAt: nowIso(), specimen: null, lab: null, verdict: null, followUps: [] };
      s.testing.tests.push(t);
      notifyEmployee(s, empId, 'Workplace testing notice', 'Please attend ' + t.notice.location + '. ' + t.notice.window + '. Bring photo ID.', 'testing', 'URGENT');
    });
    notifyRole(s, 'COLLECTOR', 'New collections required', picked.length + ' collection(s) from ' + pool.name, { app: 'oh', route: 'testing/collection' }, 'ACTION');
    writeAudit(s, u.username, 'RANDOM_SELECTION', 'selection', sel.id, 'ALLOWED', pool.id + ' x' + picked.length);
    var v = clone(sel); v.selectedNames = picked.map(function (m) { return empName(s, m); });
    v.testIds = s.testing.tests.filter(function (t) { return t.selectionId === sel.id; }).map(function (t) { return t.id; });
    return v;
  });

  defineAction('forCauseRequest', 'testing.forCause', function (s, u, o) {
    o = o || {};
    ensure(o.employeeId && emp(s, o.employeeId), 'validation', 'Choose the employee');
    if (hasRole(u, 'MANAGER')) ensure(inStatusScope(s, u, o.employeeId), 'forbidden', 'Managers request tests only for their own team');
    ensure(o.observations && o.observations.length >= 2, 'validation', 'Record at least two specific observations');
    var t = { id: nextId(s, 'DAT', 'DAT', 4), employeeId: o.employeeId, reason: 'FOR_CAUSE', selectionId: null, status: 'DISPATCHED', panel: 'Breath alcohol + drug panel (urine)',
      notice: { location: o.location || 'Supervisor\'s office – collector dispatched', window: 'Immediately' }, notifiedAt: nowIso(), requestedBy: u.username, requestedAt: nowIso(), observations: o.observations, specimen: null, lab: null, verdict: null, followUps: [] };
    s.testing.tests.push(t);
    notifyRole(s, 'COLLECTOR', 'For-cause collection – dispatch now', empName(s, o.employeeId), { app: 'oh', route: 'testing/collection' }, 'URGENT');
    writeAudit(s, u.username, 'FOR_CAUSE_REQUEST', 'test', t.id, 'ALLOWED', '');
    return testView(s, u, t);
  });

  defineAction('collectSpecimen', 'testing.collect', function (s, u, testId, o) {
    o = o || {};
    var t = byId(s.testing.tests, testId); ensure(t, 'not_found', 'Test not found');
    ensure(['REQUIRED', 'NOTIFIED', 'DISPATCHED'].indexOf(t.status) >= 0, 'invalid_state', 'Test is not awaiting collection');
    if (o.refusal) {
      t.specimen = { collectedAt: nowIso(), collector: u.username, refusal: o.refusal };
      t.status = 'MRO_REVIEW';
    } else {
      t.specimen = { id: o.specimenId || ('SPC-' + Math.floor(10000 + rand(s) * 89999)), ccf: o.ccf || ('CCF-' + Math.floor(50000 + rand(s) * 49999)), collectedAt: nowIso(), collector: u.username, seals: o.seals || 'Intact', temperatureOk: o.temperatureOk !== false, breathAlcohol: o.breathAlcohol || '0.00' };
      t.status = o.breathOnly ? 'MRO_REVIEW' : 'IN_LAB';
      t.custody = [{ at: nowIso(), by: u.username, event: 'COLLECTED' }, { at: nowIso(), by: 'TOXLAB', event: o.breathOnly ? 'N/A' : 'LAB_RECEIVED' }];
    }
    if (t.status === 'MRO_REVIEW') notifyRole(s, 'MRO', 'Result awaiting MRO review', t.id, { app: 'oh', route: 'testing/mro' }, 'ACTION');
    writeAudit(s, u.username, 'COLLECT_SPECIMEN', 'test', t.id, 'ALLOWED', t.status);
    return testView(s, u, t);
  });

  defineAction('labResult', 'testing.lab', function (s, u, testId, o) {
    o = o || {};
    var t = byId(s.testing.tests, testId); ensure(t, 'not_found', 'Test not found');
    ensure(t.status === 'IN_LAB' || t.status === 'COLLECTED', 'invalid_state', 'Specimen is not in the laboratory');
    ensure(o.result === 'NEGATIVE' || o.result === 'NON_NEGATIVE', 'validation', 'NEGATIVE or NON_NEGATIVE');
    t.lab = { at: nowIso(), result: o.result, analyte: o.analyte || null, lab: 'TOXLAB' };
    t.status = 'MRO_REVIEW';
    (t.custody = t.custody || []).push({ at: nowIso(), by: 'TOXLAB', event: 'RESULT_REPORTED' });
    notifyRole(s, 'MRO', 'Result awaiting MRO review', t.id, { app: 'oh', route: 'testing/mro' }, 'ACTION');
    writeAudit(s, 'TOXLAB', 'LAB_RESULT', 'test', t.id, 'ALLOWED', 'reported (simulated by ' + u.username + ')');
    return testView(s, u, t);
  });

  defineAction('mroVerdict', 'testing.mro', function (s, u, testId, o) {
    o = o || {};
    var t = byId(s.testing.tests, testId); ensure(t, 'not_found', 'Test not found');
    ensure(t.status === 'MRO_REVIEW', 'invalid_state', 'Test is not in MRO review');
    var map = { NEGATIVE: 'VERIFIED_NEGATIVE', NEGATIVE_LEGITIMATE_PRESCRIPTION: 'VERIFIED_NEGATIVE', POSITIVE: 'VERIFIED_POSITIVE', POSITIVE_DILUTE: 'VERIFIED_POSITIVE', REFUSAL_ADULTERATED: 'REFUSAL', REFUSAL_SUBSTITUTED: 'REFUSAL', CANCELLED: 'CANCELLED' };
    ensure(map[o.outcome], 'validation', 'Unknown MRO outcome');
    t.verdict = { at: nowIso(), by: u.username, outcome: o.outcome, note: o.note || '' };
    t.status = map[o.outcome];
    var e = emp(s, t.employeeId);
    var result = { testId: t.id, status: t.status };
    if (t.status === 'VERIFIED_POSITIVE' || t.status === 'REFUSAL') {
      e.dutyStatus = 'REMOVED'; e.dutySince = today();
      e.fitness = { status: 'UNFIT_PENDING', since: today(), decidedBy: u.username };
      var c = openKernelCase(s, u.username, { employeeId: e.id, category: 'SUBSTANCE', source: 'DA_TEST', summary: 'Verified ' + (t.status === 'REFUSAL' ? 'refusal' : 'positive') + ' (' + t.reason.toLowerCase().replace('_', '-') + ' test).', workImpact: 'Removed from safety-sensitive duty.', timelineText: 'Opened from the testing programme.' });
      c.status = 'ASSESSMENT'; c.testId = t.id; c.fitness = { status: 'UNFIT_PENDING', decidedBy: u.username, decidedAt: nowIso() };
      var sup = { id: nextId(s, 'SUP', 'SUP', 4), employeeId: e.id, kernelCaseId: c.id, status: 'OPEN', risk: 'MODERATE', source: 'PROGRAMME_REFERRAL', namedClinician: s.config.namedClinicians[0] || null,
        openedAt: nowIso(), sharing: 'NOTHING', sharingAt: null, sharedAdjustments: null, contactPreference: 'PHONE', concern: 'Programme referral from the substance testing pathway.', screens: [], urgentItems: [], notes: [], eapReferralId: null, consentId: null };
      s.supportCases.push(sup);
      t.followUps = [{ due: addDays(today(), 30), status: 'PLANNED' }, { due: addDays(today(), 90), status: 'PLANNED' }];
      // Status only to HR, crew scheduling and the line manager – never the reason or the result.
      notifyRole(s, 'HR', 'Duty status change', e.name + ': ' + label('duty', 'REMOVED') + '.', { app: 'manager', route: 'duty-status' }, 'ACTION');
      if (isCrew(s, e)) notifyRole(s, 'CREW_SCHEDULING', 'Duty status change', e.name + ': unavailable for duty.', { app: 'manager', route: 'crew' }, 'ACTION');
      notifyManager(s, e.id, 'Duty status change', e.name + ': ' + label('duty', 'REMOVED') + ' (no reason given).', 'duty-status');
      notify(s, s.config.namedClinicians, 'Programme referral to support', sup.id, { app: 'oh', route: 'support/' + sup.id });
      disclose(s, e.id, 'HR, line manager' + (isCrew(s, e) ? ', crew scheduling' : ''), 'Duty status only (removed from safety-sensitive duty)', 'Safety-critical duty status');
      result.kernelCaseId = c.id; result.supportCaseId = sup.id;
    }
    notifyEmployee(s, t.employeeId, 'Your workplace test', t.status === 'VERIFIED_NEGATIVE' ? 'Your test is complete. No further action.' : 'The medical review officer will contact you.', 'testing');
    writeAudit(s, u.username, 'MRO_VERDICT', 'test', t.id, 'ALLOWED', o.outcome);
    return result;
  });

  defineAction('returnToDuty', 'testing.returnToDuty', function (s, u, employeeId, o) {
    o = o || {};
    var e = emp(s, employeeId); ensure(e, 'not_found', 'Employee not found');
    ensure(e.dutyStatus === 'REMOVED' || e.dutyStatus === 'RETURN_PENDING', 'invalid_state', 'Employee is not removed from duty');
    e.dutyStatus = o.final ? 'AVAILABLE' : 'RETURN_PENDING'; e.dutySince = today();
    if (o.final) e.fitness = { status: 'FIT', since: today(), decidedBy: u.username };
    notifyRole(s, 'HR', 'Duty status change', e.name + ': ' + label('duty', e.dutyStatus) + '.', { app: 'manager', route: 'duty-status' });
    notifyManager(s, e.id, 'Duty status change', e.name + ': ' + label('duty', e.dutyStatus) + '.', 'duty-status');
    writeAudit(s, u.username, 'RETURN_TO_DUTY', 'employee', e.id, 'ALLOWED', e.dutyStatus);
    return { employeeId: e.id, dutyStatus: e.dutyStatus };
  });

  // =====================================================================================
  // Actions – travel health, advisories, communicable disease
  // =====================================================================================
  defineAction('travelConsultation', 'travel.request', function (s, u, o) {
    o = o || {};
    var d = byId(s.destinations, o.destination, 'code'); ensure(d, 'validation', 'Choose a destination');
    ensure(o.departDate, 'validation', 'Departure date is required');
    var t = { id: nextId(s, 'TRV', 'TRV', 4), employeeId: u.employeeId, destination: d.code, departDate: o.departDate, purpose: o.purpose || 'Rotation / layover', status: 'REQUESTED', requestedAt: nowIso(), consultedBy: null, consultedAt: null, recommendations: [], certificateId: null, notes: o.notes || '' };
    s.travelConsultations.push(t);
    notifyRole(s, 'OH_CLINICIAN', 'Travel consultation request', empName(s, u.employeeId) + ' – ' + d.name + ' on ' + o.departDate, { app: 'oh', route: 'travel' }, 'ACTION');
    writeAudit(s, u.username, 'TRAVEL_REQUEST', 'travel', t.id, 'ALLOWED', d.code);
    var v = clone(t); v.destinationName = d.name; v.suggested = clone(d); return v;
  });

  defineAction('completeTravelConsultation', 'travel.consult', function (s, u, consultId, o) {
    o = o || {};
    var t = byId(s.travelConsultations, consultId); ensure(t, 'not_found', 'Consultation not found');
    ensure(t.status === 'REQUESTED', 'invalid_state', 'Consultation already completed');
    var d = byId(s.destinations, t.destination, 'code');
    t.status = 'COMPLETED'; t.consultedBy = u.username; t.consultedAt = nowIso();
    t.recommendations = o.recommendations || d.vaccines.concat(d.advice);
    t.vaccinesGiven = o.vaccinesGiven || [];
    if (o.certificate !== false) {
      var doc = addDocument(s, { employeeId: t.employeeId, type: 'TRAVEL_CERTIFICATE', title: 'Travel health certificate – ' + d.name.split(' (')[0], by: u.username, classification: 'CLINICAL', visibleToEmployee: true });
      t.certificateId = doc.id;
    }
    notifyEmployee(s, t.employeeId, 'Travel health advice ready', 'Your advice for ' + d.name.split(' (')[0] + ' is ready' + (t.certificateId ? ' with a travel health certificate' : '') + '.', 'travel');
    writeAudit(s, u.username, 'TRAVEL_CONSULT', 'travel', t.id, 'ALLOWED', d.code);
    return t;
  });

  defineAction('publishAdvisory', 'advisory.publish', function (s, u, o) {
    o = o || {};
    var d = byId(s.destinations, o.destination, 'code'); ensure(d, 'validation', 'Choose a destination');
    ensure(o.title && o.body, 'validation', 'Title and message are required');
    var within = o.withinDays || 14;
    var until = addDays(today(), within);
    var recips = uniq(s.roster.filter(function (r) { return r.to === d.code && r.date >= today() && r.date <= until; }).map(function (r) { return r.employeeId; }));
    var a = { id: nextId(s, 'ADV', 'ADV', 4), title: o.title, destination: d.code, severity: o.severity || 'MEDIUM', publishedBy: u.username, publishedAt: nowIso(), body: o.body,
      targetRule: 'Crew rostered to ' + d.code + ' in the next ' + within + ' days', recipients: recips, acks: {} };
    s.advisories.push(a);
    recips.forEach(function (id) { notifyEmployee(s, id, 'Health advisory: ' + o.title, 'This applies to your upcoming rotation to ' + d.name.split(' (')[0] + '. Please read and acknowledge.', 'travel', 'ACTION'); });
    writeAudit(s, u.username, 'PUBLISH_ADVISORY', 'advisory', a.id, 'ALLOWED', recips.length + ' recipients');
    var v = clone(a); v.recipientCount = recips.length; return v;
  });

  defineAction('acknowledgeAdvisory', 'advisory.acknowledge', function (s, u, advisoryId) {
    var a = byId(s.advisories, advisoryId); ensure(a, 'not_found', 'Advisory not found');
    ensure(a.recipients.indexOf(u.employeeId) >= 0, 'forbidden', 'This advisory is not addressed to you');
    if (!a.acks[u.employeeId]) a.acks[u.employeeId] = nowIso();
    writeAudit(s, u.username, 'ACKNOWLEDGE_ADVISORY', 'advisory', a.id, 'ALLOWED', '');
    return { advisoryId: a.id, acknowledgedAt: a.acks[u.employeeId] };
  });

  defineAction('openCommunicableCase', 'cd.manage', function (s, u, o) {
    o = o || {};
    var e = emp(s, o.employeeId); ensure(e, 'validation', 'Choose the employee');
    ensure(o.condition, 'validation', 'Condition is required');
    var kc = openKernelCase(s, u.username, { employeeId: e.id, category: 'ILLNESS', source: 'SYSTEM_TRIGGER', summary: o.condition + (o.onsetDate ? ' (onset ' + o.onsetDate + ')' : ''), workImpact: 'Excluded from work (infection control).', timelineText: 'Communicable-disease case opened.' });
    kc.status = 'ASSESSMENT'; kc.urgency = 'URGENT'; kc.queue = 'PHYSICIAN'; kc.owner = u.username;
    var excl = o.exclusionDays || 5;
    var cd = { id: nextId(s, 'CDC', 'CDC', 4), employeeId: e.id, condition: o.condition, status: 'OPEN', openedBy: u.username, openedAt: nowIso(), onsetDate: o.onsetDate || today(),
      exclusion: { from: today(), until: addDays(today(), excl), basis: o.exclusionBasis || 'Infectious period' }, kernelCaseId: kc.id, traced: false, publicHealthReported: false, incubationDays: o.incubationDays || 14 };
    s.cdCases.push(cd);
    e.dutyStatus = 'EXCLUDED'; e.dutySince = today();
    kc.fitness = { status: 'TEMPORARILY_UNFIT', decidedBy: u.username, decidedAt: nowIso(), reviewDate: cd.exclusion.until };
    e.fitness = { status: 'TEMPORARILY_UNFIT', since: today(), decidedBy: u.username };
    notifyEmployee(s, e.id, 'Please stay off work', 'Occupational Health has excluded you from work until ' + cd.exclusion.until + '. OH will call you.', 'cases', 'URGENT');
    if (isCrew(s, e)) notifyRole(s, 'CREW_SCHEDULING', 'Crew unavailable', e.name + ': unavailable until ' + cd.exclusion.until + ' (no reason given).', { app: 'manager', route: 'crew' }, 'ACTION');
    notifyManager(s, e.id, 'Team member unavailable', e.name + ': excluded from work until ' + cd.exclusion.until + ' (no reason given).', 'team');
    writeAudit(s, u.username, 'OPEN_CD_CASE', 'cd', cd.id, 'ALLOWED', '');
    var v = clone(cd); v.employeeName = e.name; return v;
  });

  defineAction('contactTrace', 'cd.manage', function (s, u, cdCaseId, o) {
    o = o || {};
    var started = Date.now();
    var cd = byId(s.cdCases, cdCaseId); ensure(cd, 'not_found', 'Case not found');
    var windowDays = o.windowDays || 7;
    var from = addDays(cd.onsetDate || today(), -windowDays);
    var duties = s.roster.filter(function (r) { return r.employeeId === cd.employeeId && r.date >= from && r.date <= today(); });
    var found = {};
    duties.forEach(function (d) {
      s.roster.forEach(function (r) {
        if (r.employeeId !== cd.employeeId && r.duty === d.duty && r.date === d.date && !found[r.employeeId]) found[r.employeeId] = { duty: d.duty, date: d.date };
      });
    });
    var created = [];
    Object.keys(found).forEach(function (empId) {
      if (s.contacts.some(function (c) { return c.cdCaseId === cd.id && c.employeeId === empId; })) return;
      var f = found[empId];
      var c = { id: nextId(s, 'CON-T', 'CTC', 4), cdCaseId: cd.id, employeeId: empId, exposure: f.duty.indexOf('SHIFT:') === 0 ? 'Shared shift ' + f.duty.slice(6) : 'Same crew on ' + f.duty, exposureDate: f.date,
        notifiedAt: nowIso(), status: 'MONITORING', checkIns: [], monitorUntil: addDays(f.date, cd.incubationDays || 14),
        advice: 'Check your temperature daily and report any fever, rash, cough or vomiting. You can keep working while well.' };
      s.contacts.push(c); created.push(c);
      // The contact is told about the exposure, never who the case is.
      notifyEmployee(s, empId, 'Possible exposure to an infectious illness', 'You were on duty (' + c.exposure + ', ' + f.date + ') with someone who now has an infectious illness. Please complete a daily check-in until ' + c.monitorUntil + '.', 'travel', 'URGENT');
    });
    cd.traced = true; cd.tracedAt = nowIso(); cd.tracedBy = u.username;
    var kc = byId(s.cases, cd.kernelCaseId); if (kc) addTimeline(kc, u.username, 'CONTACT_TRACE', created.length + ' contact(s) identified from roster and shift data.', 'OH');
    writeAudit(s, u.username, 'CONTACT_TRACE', 'cd', cd.id, 'ALLOWED', created.length + ' contacts');
    return { cdCaseId: cd.id, contactsCreated: created.length, contacts: created.map(function (c) { return { id: c.id, employeeId: c.employeeId, employeeName: empName(s, c.employeeId), exposure: c.exposure, exposureDate: c.exposureDate }; }), elapsedMs: Date.now() - started, targetHours: 4 };
  });

  defineAction('contactCheckIn', 'contact.checkin', function (s, u, contactId, o) {
    o = o || {};
    var c = byId(s.contacts, contactId); ensure(c && isSelf(u, c.employeeId), 'not_found', 'Not found');
    ensure(c.status === 'MONITORING' || c.status === 'SYMPTOMATIC', 'invalid_state', 'Monitoring has ended');
    c.checkIns = c.checkIns.filter(function (k) { return k.date !== today(); });
    c.checkIns.push({ date: today(), at: nowIso(), symptoms: !!o.symptoms, note: o.note || '' });
    if (o.symptoms) {
      c.status = 'SYMPTOMATIC';
      notifyRole(s, 'OH_CLINICIAN', 'Contact reports symptoms', empName(s, c.employeeId) + ' (' + c.id + ')', { app: 'oh', route: 'communicable' }, 'URGENT');
    }
    writeAudit(s, u.username, 'CONTACT_CHECKIN', 'contact', c.id, 'ALLOWED', o.symptoms ? 'symptoms' : 'well');
    return { id: c.id, status: c.status, checkIns: c.checkIns };
  });

  defineAction('clearContact', 'cd.manage', function (s, u, contactId) {
    var c = byId(s.contacts, contactId); ensure(c, 'not_found', 'Not found');
    c.status = 'CLEARED';
    notifyEmployee(s, c.employeeId, 'Monitoring ended', 'You no longer need to complete daily check-ins.', 'travel');
    writeAudit(s, u.username, 'CLEAR_CONTACT', 'contact', c.id, 'ALLOWED', '');
    return c;
  });

  defineAction('reportPublicHealth', 'cd.manage', function (s, u, cdCaseId) {
    var cd = byId(s.cdCases, cdCaseId); ensure(cd, 'not_found', 'Case not found');
    cd.publicHealthReported = nowIso();
    writeAudit(s, u.username, 'PUBLIC_HEALTH_REPORT', 'cd', cd.id, 'ALLOWED', 'Local health protection team (demo)');
    return cd;
  });

  // =====================================================================================
  // Actions – health promotion
  // =====================================================================================
  defineAction('createCampaign', 'campaign.manage', function (s, u, o) {
    o = o || {};
    ensure(o.title, 'validation', 'Title is required');
    var c = { id: nextId(s, 'CMP', 'CMP', 4), title: o.title, type: o.type || 'SCREENING', status: 'PLANNED', owner: u.username, createdAt: nowIso(), description: o.description || '',
      units: [], start: o.start || today(), end: o.end || addDays(today(), 30), screeningType: o.screeningType || null, events: o.events || [], invited: [], joined: [], declined: [] };
    s.campaigns.push(c);
    writeAudit(s, u.username, 'CREATE_CAMPAIGN', 'campaign', c.id, 'ALLOWED', '');
    return c;
  });

  defineAction('campaignInvite', 'campaign.manage', function (s, u, campaignId, o) {
    o = o || {};
    var c = byId(s.campaigns, campaignId); ensure(c, 'not_found', 'Campaign not found');
    ensure(c.status !== 'CLOSED', 'invalid_state', 'Campaign is closed');
    var units = o.units || c.units;
    ensure(units && units.length, 'validation', 'Choose at least one unit');
    var added = 0;
    s.employees.forEach(function (e) {
      if (units.some(function (unit) { return e.orgUnit === unit; }) && c.invited.indexOf(e.id) < 0) {
        c.invited.push(e.id); added++;
        // honours a withdrawn reminder consent: invitation is in-app only
        notifyEmployee(s, e.id, 'You are invited: ' + c.title, c.description, 'campaigns');
      }
    });
    c.units = uniq(c.units.concat(units)); c.status = 'ACTIVE';
    writeAudit(s, u.username, 'CAMPAIGN_INVITE', 'campaign', c.id, 'ALLOWED', added + ' invited');
    return { campaignId: c.id, invitedAdded: added, invitedTotal: c.invited.length };
  });

  defineAction('joinCampaign', 'campaign.join', function (s, u, campaignId, o) {
    o = o || {};
    var c = byId(s.campaigns, campaignId); ensure(c, 'not_found', 'Campaign not found');
    ensure(c.status === 'ACTIVE', 'invalid_state', 'Campaign is not open');
    ensure(c.invited.indexOf(u.employeeId) >= 0 || o.selfEnrol, 'forbidden', 'You are not invited to this campaign');
    if (c.invited.indexOf(u.employeeId) < 0) c.invited.push(u.employeeId);
    if (c.joined.indexOf(u.employeeId) < 0) c.joined.push(u.employeeId);
    c.declined = c.declined.filter(function (x) { return x !== u.employeeId; });
    c.bookings = c.bookings || {};
    if (o.eventId) { ensure(byId(c.events, o.eventId), 'validation', 'Unknown event'); c.bookings[u.employeeId] = o.eventId; }
    s.consents.push({ id: nextId(s, 'CON', 'CON', 4), employeeId: u.employeeId, type: 'CAMPAIGN_PARTICIPATION', scope: 'Take part in ' + c.title + ' (results to me and OH only)', status: 'GIVEN', at: nowIso() });
    writeAudit(s, u.username, 'JOIN_CAMPAIGN', 'campaign', c.id, 'ALLOWED', o.eventId || '');
    return { campaignId: c.id, joined: true, eventId: o.eventId || null };
  });

  defineAction('declineCampaign', 'campaign.join', function (s, u, campaignId) {
    var c = byId(s.campaigns, campaignId); ensure(c, 'not_found', 'Campaign not found');
    if (c.declined.indexOf(u.employeeId) < 0) c.declined.push(u.employeeId);
    c.joined = c.joined.filter(function (x) { return x !== u.employeeId; });
    writeAudit(s, u.username, 'DECLINE_CAMPAIGN', 'campaign', c.id, 'ALLOWED', '');
    return { campaignId: c.id, declined: true };
  });

  defineAction('screeningResult', 'screening.record', function (s, u, o) {
    o = o || {};
    var c = byId(s.campaigns, o.campaignId); ensure(c, 'not_found', 'Campaign not found');
    ensure(o.employeeId && c.joined.indexOf(o.employeeId) >= 0, 'validation', 'The employee has not joined this campaign');
    var m = o.measures || {};
    var flags = [];
    if (m.systolic >= 140 || m.diastolic >= 90) flags.push('Raised blood pressure');
    if (m.cholesterol >= 6.5) flags.push('Raised cholesterol');
    if (m.bmi >= 35) flags.push('BMI 35 or above');
    var outcome = o.outcome || (flags.length ? 'ABNORMAL' : 'NORMAL');
    var letter = addDocument(s, { employeeId: o.employeeId, type: 'SCREENING_LETTER', title: c.title + ' – your results', by: u.username, classification: 'CLINICAL', visibleToEmployee: true,
      body: outcome === 'NORMAL' ? 'Your results are within the healthy range. Keep up the good work.' : 'Some results need a follow-up: ' + flags.join(', ') + '. An OH nurse will contact you within 48 hours to arrange a review. This is not an emergency.' });
    var r = { id: nextId(s, 'SCR', 'SCR', 4), campaignId: c.id, employeeId: o.employeeId, at: nowIso(), by: u.username, measures: m, flags: flags, outcome: outcome, letterId: letter.id, followUpCaseId: null };
    if (outcome === 'ABNORMAL') {
      var kc = openKernelCase(s, u.username, { employeeId: o.employeeId, category: 'OTHER', type: 'SURVEILLANCE_FOLLOW_UP', source: 'SYSTEM_TRIGGER', summary: c.title + ': ' + flags.join(', ') + '.', timelineText: 'Follow-up opened from ' + c.title + ' screening (due within 48 hours).' });
      kc.status = 'TRIAGE'; kc.queue = 'OH_NURSE'; kc.dueBy = new Date(Date.now() + 48 * 3600000).toISOString();
      r.followUpCaseId = kc.id;
      notifyRole(s, 'OH_CLINICIAN', 'Screening follow-up (48 h)', empName(s, o.employeeId) + ' – ' + kc.id, { app: 'oh', route: 'case/' + kc.id }, 'ACTION');
    }
    s.screeningResults.push(r);
    notifyEmployee(s, o.employeeId, 'Your ' + c.title + ' results', outcome === 'NORMAL' ? 'Your result letter is ready.' : 'Your result letter is ready. OH will contact you to arrange a follow-up.', 'campaigns');
    writeAudit(s, u.username, 'SCREENING_RESULT', 'screening', r.id, 'ALLOWED', outcome);
    return r;
  });

  defineAction('addErgonomicAction', 'ergonomics.manage', function (s, u, o) {
    o = o || {};
    ensure(o.unit && o.action, 'validation', 'Unit and action are required');
    var a = { id: nextId(s, 'ERG', 'ERG', 4), unit: o.unit, area: o.area || '', assessment: o.assessment || 'Task', rag: o.rag || 'AMBER', finding: o.finding || '', action: o.action, owner: o.owner || '', due: o.due || addDays(today(), 30), status: 'OPEN', createdBy: u.username };
    s.ergonomicActions.push(a);
    s.users.filter(function (x) { return hasRole(x, 'MANAGER') && (emp(s, x.employeeId) || {}).orgUnit === o.unit; }).forEach(function (m) { notify(s, [m.username], 'New ergonomic action for your unit', a.action, { app: 'manager', route: 'ergonomics' }); });
    writeAudit(s, u.username, 'ERGONOMIC_ACTION', 'ergonomics', a.id, 'ALLOWED', a.rag);
    return a;
  });

  defineAction('updateErgonomicAction', null, function (s, u, id, o) {
    o = o || {};
    var a = byId(s.ergonomicActions, id); ensure(a, 'not_found', 'Action not found');
    var mgrOk = hasRole(u, 'MANAGER') && (emp(s, u.employeeId) || {}).orgUnit === a.unit;
    ensure(can(u, 'ergonomics.manage') || mgrOk, 'forbidden', 'Not allowed');
    if (o.status) a.status = o.status;
    if (o.note) a.note = o.note;
    writeAudit(s, u.username, 'ERGONOMIC_UPDATE', 'ergonomics', a.id, 'ALLOWED', a.status);
    return a;
  });

  // =====================================================================================
  // Actions – consent, notifications
  // =====================================================================================
  defineAction('giveConsent', 'consent.manage', function (s, u, o) {
    o = o || {};
    ensure(o.type, 'validation', 'Consent type is required');
    var c = { id: nextId(s, 'CON', 'CON', 4), employeeId: u.employeeId, type: o.type, scope: o.scope || o.type, partnerOrg: o.partnerOrg || null, status: 'GIVEN', at: nowIso() };
    s.consents.push(c);
    writeAudit(s, u.username, 'GIVE_CONSENT', 'consent', c.id, 'ALLOWED', o.type);
    return c;
  });

  defineAction('withdrawConsent', 'consent.manage', function (s, u, consentId) {
    var c = byId(s.consents, consentId); ensure(c && isSelf(u, c.employeeId), 'not_found', 'Consent not found');
    ensure(c.status === 'GIVEN', 'invalid_state', 'Already withdrawn');
    c.status = 'WITHDRAWN'; c.withdrawnAt = nowIso();
    var affected = [];
    s.referrals.forEach(function (r) { if (r.consentId === c.id && r.status !== 'CLOSED') { affected.push(r.id); notify(s, partnerUsers(s, r.partnerOrg).concat([r.sentBy]), 'Consent withdrawn', r.id + ' – access to the referral data set has stopped.', { app: 'partner', route: 'referrals' }, 'ACTION'); } });
    s.claims.forEach(function (cl) { if (cl.consentId === c.id) { affected.push(cl.id); notify(s, partnerUsers(s, cl.payerOrg).concat([cl.openedBy]), 'Consent withdrawn', cl.id + ' – shared documents are no longer available.', { app: 'partner', route: 'claims' }, 'ACTION'); } });
    writeAudit(s, u.username, 'WITHDRAW_CONSENT', 'consent', c.id, 'ALLOWED', affected.join(','));
    return { consent: c, affected: affected };
  });

  function markNotificationRead(id) {
    var u = requireUser();
    mutate(function (s) { s.notifications.forEach(function (n) { if (n.id === id && (n.to === u.username || n.to === 'emp:' + u.employeeId)) n.read = true; }); });
  }
  function markAllRead() {
    var u = requireUser();
    mutate(function (s) { s.notifications.forEach(function (n) { if (n.to === u.username || n.to === 'emp:' + u.employeeId) n.read = true; }); });
  }

  // =====================================================================================
  // Session lifecycle: reset, export, import
  // =====================================================================================
  function reset() {
    var st = seedState();
    var raw = JSON.stringify(st);
    writeRaw(raw); cache.raw = raw; cache.state = st;
    emit({ type: 'reset' });
    return clone(st.meta);
  }
  function exportData() {
    var s = session();
    return { format: FORMAT, exportedAt: nowIso(), engineVersion: VERSION, schema: SCHEMA, seedVersion: s.meta.seedVersion, state: s };
  }
  function exportSession() {
    var data = JSON.stringify(exportData(), null, 1);
    if (root.document && root.Blob && root.URL) {
      var blob = new Blob([data], { type: 'application/json' });
      var a = root.document.createElement('a');
      a.href = URL.createObjectURL(blob); a.download = 'wellnessz-demo-session.json';
      root.document.body.appendChild(a); a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500);
    }
    return data;
  }
  function loadImported(obj) {
    if (typeof obj === 'string') obj = JSON.parse(obj);
    ensure(obj && obj.format === FORMAT && obj.state && obj.state.meta, 'invalid_file', 'This is not a WellnessZ demo session file.');
    ensure(obj.state.meta.schema === SCHEMA, 'invalid_file', 'This session file was made by an incompatible demo version (schema ' + obj.state.meta.schema + ').');
    var st = obj.state;
    st.meta.importedAt = nowIso();
    var raw = JSON.stringify(st);
    writeRaw(raw); cache.raw = raw; cache.state = st;
    emit({ type: 'import' });
    return clone(st.meta);
  }
  /** Import a session from a File (browser), a JSON string or an object. Returns a Promise of the session meta. */
  function importSession(file) {
    return new Promise(function (resolve, reject) {
      try {
        if (file && typeof file === 'object' && root.FileReader && file instanceof root.Blob) {
          var r = new FileReader();
          r.onload = function () { try { resolve(loadImported(String(r.result))); } catch (e) { reject(e); } };
          r.onerror = function () { reject(WZError('invalid_file', 'Could not read the file')); };
          r.readAsText(file);
        } else resolve(loadImported(file));
      } catch (e) { reject(e); }
    });
  }

  function sessionInfo() {
    var s = session(); var raw = cache.raw || '';
    return { sessionId: s.meta.sessionId, seedVersion: s.meta.seedVersion, engineVersion: VERSION, schema: SCHEMA, createdAt: s.meta.createdAt, updatedAt: s.meta.updatedAt,
      rev: s.meta.rev, sizeKb: Math.round(raw.length / 102.4) / 10, storageKey: KEY, counts: { cases: s.cases.length, notifications: s.notifications.length, audit: s.audit.length } };
  }

  // =====================================================================================
  // Scenarios
  // =====================================================================================
  function scenarios() { return clone(root.WZ_SCENARIOS && root.WZ_SCENARIOS.scenarios ? root.WZ_SCENARIOS.scenarios : (root.WZ_SCENARIOS || [])); }
  function scenarioState() {
    var s = session(); var sc = s.scenario || { id: null, step: 0 };
    var all = scenarios(); var def = byId(all, sc.id);
    if (!def) return { active: false, completed: (sc.completed || []).slice() };
    var step = def.steps[sc.step] || null;
    return { active: true, id: def.id, title: def.title, stepIndex: sc.step, stepCount: def.steps.length, step: step, done: !step, completed: (sc.completed || []).slice() };
  }
  function startScenario(id) { ensure(byId(scenarios(), id), 'not_found', 'Unknown scenario'); mutate(function (s) { s.scenario = { id: id, step: 0, completed: (s.scenario && s.scenario.completed) || [] }; }); return scenarioState(); }
  function stopScenario() { mutate(function (s) { s.scenario = { id: null, step: 0, completed: (s.scenario && s.scenario.completed) || [] }; }); }
  function stepScenario(delta) {
    mutate(function (s) {
      var def = byId(scenarios(), s.scenario && s.scenario.id); if (!def) return;
      s.scenario.step = Math.max(0, Math.min(def.steps.length, s.scenario.step + (delta == null ? 1 : delta)));
      if (s.scenario.step >= def.steps.length && s.scenario.completed.indexOf(def.id) < 0) s.scenario.completed.push(def.id);
    });
    return scenarioState();
  }
  function advanceScenarioOn(s, actionName, u) {
    if (!s.scenario || !s.scenario.id) return;
    var def = byId(scenarios(), s.scenario.id); if (!def) return;
    var step = def.steps[s.scenario.step];
    if (step && step.action === actionName && (!step.user || step.user === u.username)) {
      s.scenario.step++;
      if (s.scenario.step >= def.steps.length && s.scenario.completed.indexOf(def.id) < 0) s.scenario.completed.push(def.id);
    }
  }

  // =====================================================================================
  // Demo toolbar (DOM)
  // =====================================================================================
  var TOOLBAR_CSS = [
    '#wz-demo-toolbar{position:sticky;top:0;z-index:1000;background:#20262d;color:#f1f4f7;font:13px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;border-bottom:3px solid #ffbf47}',
    '#wz-demo-toolbar .wzt-row{display:flex;flex-wrap:wrap;align-items:center;gap:6px 10px;padding:6px 12px;max-width:1400px;margin:0 auto}',
    '#wz-demo-toolbar .wzt-pill{background:#ffbf47;color:#1b1b1b;font-weight:700;border-radius:999px;padding:1px 9px;letter-spacing:.04em}',
    '#wz-demo-toolbar label{display:flex;align-items:center;gap:6px}',
    '#wz-demo-toolbar select,#wz-demo-toolbar button,#wz-demo-toolbar a.wzt-btn{font:inherit;color:#f1f4f7;background:#323b45;border:1px solid #56626e;border-radius:6px;padding:3px 9px;cursor:pointer;text-decoration:none;min-height:28px}',
    '#wz-demo-toolbar select{max-width:260px}',
    '#wz-demo-toolbar button:hover,#wz-demo-toolbar a.wzt-btn:hover{background:#3f4a56}',
    '#wz-demo-toolbar :focus-visible{outline:3px solid #ffbf47;outline-offset:1px}',
    '#wz-demo-toolbar .wzt-sp{flex:1}',
    '#wz-demo-toolbar .wzt-hint{background:#2b333c;border-top:1px solid #3c4752;padding:6px 12px}',
    '#wz-demo-toolbar .wzt-hint-in{max-width:1400px;margin:0 auto;display:flex;flex-wrap:wrap;gap:6px 12px;align-items:center}',
    '#wz-demo-toolbar .wzt-here{color:#9ee2b8;font-weight:600}',
    '#wz-demo-toolbar .wzt-else{color:#ffd98a;font-weight:600}',
    '#wz-demo-toolbar .wzt-panel{background:#262d35;border-top:1px solid #3c4752;padding:8px 12px}',
    '#wz-demo-toolbar .wzt-panel-in{max-width:1400px;margin:0 auto}',
    '#wz-demo-toolbar .wzt-panel table{border-collapse:collapse}#wz-demo-toolbar .wzt-panel td{padding:1px 10px 1px 0;vertical-align:top}',
    '#wz-demo-toolbar .wzt-steps{margin:6px 0 0;padding-left:20px}#wz-demo-toolbar .wzt-steps li{margin:2px 0}#wz-demo-toolbar .wzt-steps li.cur{color:#ffd98a;font-weight:600}#wz-demo-toolbar .wzt-steps li.done{color:#9fb0bf;text-decoration:line-through}',
    '#wz-demo-toolbar .wzt-msg{color:#ffd98a}',
    '@media (max-width:640px){#wz-demo-toolbar .wzt-row{padding:6px 8px}#wz-demo-toolbar select{max-width:170px}#wz-demo-toolbar .wzt-hide-sm{display:none}}'
  ].join('\n');

  function esc(x) { return String(x == null ? '' : x).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

  function mountToolbar(opts) {
    opts = opts || {};
    var doc = root.document; if (!doc) return null;
    if (opts.app) ctx.app = opts.app;
    if (!doc.getElementById('wz-demo-toolbar-css')) { var st = doc.createElement('style'); st.id = 'wz-demo-toolbar-css'; st.textContent = TOOLBAR_CSS; doc.head.appendChild(st); }
    var bar = doc.getElementById('wz-demo-toolbar');
    if (!bar) { bar = doc.createElement('div'); bar.id = 'wz-demo-toolbar'; bar.setAttribute('role', 'region'); bar.setAttribute('aria-label', 'Demo controls'); doc.body.insertBefore(bar, doc.body.firstChild); }
    var panel = null; var msg = '';
    var fileInput = doc.createElement('input'); fileInput.type = 'file'; fileInput.accept = '.json,application/json'; fileInput.hidden = true;
    fileInput.addEventListener('change', function () {
      var f = fileInput.files && fileInput.files[0]; if (!f) return;
      importSession(f).then(function (m) { msg = 'Session imported (' + m.sessionId + ').'; render(); }).catch(function (e) { msg = 'Import failed: ' + e.message; render(); });
      fileInput.value = '';
    });
    function appLink(app) { return (APPS[app] || {}).file || '#'; }
    function render() {
      var app = ctx.app; var cu = currentUser(); var us = app && app !== 'launcher' ? users(app) : [];
      var sc = scenarioState(); var info = sessionInfo();
      var h = '<div class="wzt-row"><span class="wzt-pill">DEMO</span><strong class="wzt-hide-sm">' + esc((APPS[app] || {}).title || 'WellnessZ') + '</strong>';
      if (us.length) {
        h += '<label>Signed in as <select data-wzt="user" aria-label="Switch demo user">' + us.map(function (u) { return '<option value="' + esc(u.username) + '"' + (cu && cu.username === u.username ? ' selected' : '') + '>' + esc(u.name) + ' — ' + esc(u.title) + '</option>'; }).join('') + '</select></label>';
      }
      h += '<button type="button" data-wzt="scenario" aria-expanded="' + (panel === 'scenario') + '">Scenarios' + (sc.active ? ' (' + Math.min(sc.stepIndex + 1, sc.stepCount) + '/' + sc.stepCount + ')' : '') + '</button>';
      h += '<span class="wzt-sp"></span>';
      h += '<button type="button" data-wzt="info" aria-expanded="' + (panel === 'info') + '">Session</button>';
      h += '<button type="button" data-wzt="export" title="Download wellnessz-demo-session.json">Export</button>';
      h += '<button type="button" data-wzt="import" title="Load a wellnessz-demo-session.json file">Import</button>';
      h += '<button type="button" data-wzt="reset" title="Reset all apps to the demo seed">Reset</button>';
      if (app !== 'launcher') h += '<a class="wzt-btn" href="index.html" data-wzt="launcher">Launcher</a>';
      h += '</div>';
      if (sc.active) {
        var stp = sc.step;
        h += '<div class="wzt-hint" aria-live="polite"><div class="wzt-hint-in">';
        if (!stp) h += '<span class="wzt-here">Scenario complete: ' + esc(sc.title) + '</span><button type="button" data-wzt="stop">Close scenario</button>';
        else {
          var here = stp.app === app;
          var su = stp.user ? (session().users.filter(function (x) { return x.username === stp.user; })[0] || {}).name : '';
          h += '<span><strong>Next step ' + (sc.stepIndex + 1) + '/' + sc.stepCount + ':</strong> ' + esc(stp.action_label || stp.action) + '</span>';
          h += here ? '<span class="wzt-here">here, as ' + esc(su || stp.user) + '</span>' : '<span class="wzt-else">in the ' + esc((APPS[stp.app] || {}).title) + ' as ' + esc(su || stp.user) + '</span> <a class="wzt-btn" href="' + appLink(stp.app) + '" target="_blank" rel="noopener">Open ' + esc((APPS[stp.app] || {}).short) + '</a>';
          if (here && cu && stp.user && cu.username !== stp.user) h += '<button type="button" data-wzt="become" data-user="' + esc(stp.user) + '">Switch to ' + esc(su) + '</button>';
          h += '<span>Expect: ' + esc(stp.expected) + '</span>';
          h += '<button type="button" data-wzt="back" aria-label="Previous step">◀</button><button type="button" data-wzt="next">Mark done ▶</button>';
        }
        h += '</div></div>';
      }
      if (panel === 'scenario') {
        var all = scenarios();
        h += '<div class="wzt-panel"><div class="wzt-panel-in"><label>Scenario <select data-wzt="pick"><option value="">— choose a scenario —</option>' + all.map(function (x) { return '<option value="' + esc(x.id) + '"' + (sc.id === x.id ? ' selected' : '') + '>' + esc(x.title) + (sc.completed.indexOf(x.id) >= 0 ? ' ✓' : '') + '</option>'; }).join('') + '</select></label>';
        if (sc.active) {
          var def = byId(all, sc.id);
          h += ' <button type="button" data-wzt="stop">Stop</button><ol class="wzt-steps">' + def.steps.map(function (s2, i) { return '<li class="' + (i < sc.stepIndex ? 'done' : i === sc.stepIndex ? 'cur' : '') + '">' + esc((APPS[s2.app] || {}).short) + ' · ' + esc(s2.user) + ' — ' + esc(s2.action_label || s2.action) + '</li>'; }).join('') + '</ol>';
        } else if (!all.length) h += ' <span class="wzt-msg">No scenarios loaded (run demo/build.py).</span>';
        h += '</div></div>';
      }
      if (panel === 'info') {
        h += '<div class="wzt-panel"><div class="wzt-panel-in"><table><tbody>' +
          '<tr><td>Session</td><td>' + esc(info.sessionId) + ' · revision ' + info.rev + ' · ' + info.sizeKb + ' kB</td></tr>' +
          '<tr><td>Seed / engine</td><td>demo-data ' + esc(info.seedVersion) + ' · engine ' + esc(info.engineVersion) + ' · schema ' + info.schema + '</td></tr>' +
          '<tr><td>Created / updated</td><td>' + esc(info.createdAt) + ' / ' + esc(info.updatedAt) + '</td></tr>' +
          '<tr><td>Storage</td><td>localStorage key <code>' + esc(info.storageKey) + '</code> – shared by all apps opened from this folder in this browser.</td></tr>' +
          '</tbody></table><p>All people and data are synthetic.</p></div></div>';
      }
      if (msg) h += '<div class="wzt-hint"><div class="wzt-hint-in"><span class="wzt-msg" role="status">' + esc(msg) + '</span></div></div>';
      bar.innerHTML = h;
      bar.appendChild(fileInput);
    }
    bar.addEventListener('change', function (e) {
      var t = e.target.getAttribute && e.target.getAttribute('data-wzt');
      if (t === 'user') { try { signIn(e.target.value); msg = ''; } catch (err) { msg = err.message; render(); } }
      if (t === 'pick') { if (e.target.value) startScenario(e.target.value); else stopScenario(); }
    });
    bar.addEventListener('click', function (e) {
      var el = e.target.closest && e.target.closest('[data-wzt]'); if (!el) return;
      var t = el.getAttribute('data-wzt');
      if (t === 'scenario' || t === 'info') { panel = panel === t ? null : t; render(); }
      else if (t === 'export') { exportSession(); msg = 'Downloaded wellnessz-demo-session.json'; render(); }
      else if (t === 'import') fileInput.click();
      else if (t === 'reset') { if (!root.confirm || root.confirm('Reset the demo? All changes in every app are discarded (export first if you want to keep them).')) { reset(); msg = 'Demo reset to the seed data.'; render(); } }
      else if (t === 'next') stepScenario(1);
      else if (t === 'back') stepScenario(-1);
      else if (t === 'stop') stopScenario();
      else if (t === 'become') { try { signIn(el.getAttribute('data-user')); } catch (err) { msg = err.message; render(); } }
    });
    subscribe(function () { render(); });
    render();
    return bar;
  }

  // =====================================================================================
  // Init and public API
  // =====================================================================================
  function subscribe(fn) { listeners.push(fn); return function () { listeners = listeners.filter(function (x) { return x !== fn; }); }; }

  function init(opts) {
    opts = opts || {};
    ctx.app = opts.app || ctx.app || null;
    session();
    if (ctx.app && ctx.app !== 'launcher') {
      var s = session();
      var cur = s.signedIn && s.signedIn[ctx.app];
      if (!cur || !userByName(s, cur) || userByName(s, cur).apps.indexOf(ctx.app) < 0) {
        var first = s.users.filter(function (u) { return u.apps.indexOf(ctx.app) >= 0; })[0];
        if (first) mutate(function (st) { st.signedIn = st.signedIn || {}; st.signedIn[ctx.app] = first.username; }, true);
      }
    }
    if (opts.toolbar !== false && root.document && root.document.body) mountToolbar({ app: ctx.app });
    return WZ;
  }

  Object.assign(WZ, {
    version: VERSION, SCHEMA: SCHEMA, STORAGE_KEY: KEY, APPS: APPS, LABELS: LABELS, ROLE_PERMISSIONS: ROLE_PERMISSIONS, OH_TEAM: OH_TEAM,
    init: init, session: session, save: function () { var s = session(); persist(JSON.parse(cache.raw)); return s; },
    reset: reset, exportSession: exportSession, exportData: exportData, importSession: importSession, sessionInfo: sessionInfo,
    subscribe: subscribe,
    users: users, signIn: signIn, signOut: signOut,
    can: can, permissions: function (u) { return permsOf(typeof u === 'string' ? userByName(session(), u) : (u || currentUser())); },
    list: list, get: get, ref: ref, me: me, placeholderCount: placeholderCount, tasks: tasks, report: report,
    notifications: function () { return notificationsFor(session(), requireUser()); }, markNotificationRead: markNotificationRead, markAllRead: markAllRead,
    unreadCount: function () { return notificationsFor(session(), requireUser()).filter(function (n) { return !n.read; }).length; },
    suppressCells: suppressCells, suppressRate: suppressRate, isSmallCell: isSmallCell,
    label: label, maskTestStatus: maskTestStatus,
    scenarios: scenarios, scenarioState: scenarioState, startScenario: startScenario, stopScenario: stopScenario, stepScenario: stepScenario,
    mountToolbar: mountToolbar, actions: function () { return clone(ACTIONS); },
    Error: WZError, _setApp: function (app) { ctx.app = app; }
  });
  Object.defineProperty(WZ, 'currentUser', { get: currentUser, enumerable: true });
  Object.defineProperty(WZ, 'app', { get: function () { return ctx.app; }, enumerable: true });

  root.WZ = WZ;
  if (typeof module !== 'undefined' && module.exports) module.exports = WZ;
})(typeof window !== 'undefined' ? window : globalThis);
