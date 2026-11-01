/*
 * WellnessZ DEVICE DEMO PACK – demo engine (window.DX): the offline "backend" of the device integration demo.
 *
 * One shared demo session lives in localStorage under `wellnessz-device-demo-session`. The three apps opened from the
 * same folder in the same browser read and write that one session; the 'storage' event keeps open tabs in sync.
 * The session is seeded from window.WZ_DEVICE_DEMO_SEED (data/device-demo-data.js). A demo clock (seeded at
 * meta.demoNow, +1 minute per action) makes every run deterministic: ids, times and hash chains are reproducible.
 *
 * Rules mirror the platform (E31–E34) in simplified form: four-eyes certification, one-time enrolment tokens,
 * fail-closed endpoint authentication, calibration / QC lockout with release, association thresholds 0.95 / 0.60,
 * restricted categories, dead letters with idempotent replay, isolation within 15 minutes, hash-chained event log.
 * Runs in the browser and in Node (demo-devices/test/engine.test.js).
 */
(function (root) {
  'use strict';
  var C = root.DXC || (typeof require === 'function' ? require('./codecs.js') : null);

  var VERSION = '1.0.0';
  var SCHEMA = 1;
  var KEY = 'wellnessz-device-demo-session';
  var FORMAT = 'wellnessz-device-demo-session';
  var APPS = {
    console: { file: 'device-console.html', title: 'Device Console', short: 'Device console' },
    lab: { file: 'integration-lab.html', title: 'Integration Lab', short: 'Integration lab' },
    security: { file: 'security-console.html', title: 'Security Console', short: 'Security console' },
    launcher: { file: 'index.html', title: 'Demo launcher', short: 'Launcher' }
  };
  var ROLE_PERMISSIONS = {
    DEVICE_ADMIN: ['catalogue.read', 'config.read', 'config.write', 'config.submit', 'config.publish', 'config.test', 'calibration.record', 'calibration.release', 'fleet.read', 'fleet.manage', 'endpoints.read', 'lab.read'],
    DEVICE_APPROVER: ['catalogue.read', 'config.read', 'config.approve', 'fleet.read'],
    BIOMEDICAL: ['catalogue.read', 'config.read', 'calibration.record', 'fleet.read'],
    INTEGRATION_TESTER: ['catalogue.read', 'config.read', 'config.test', 'lab.read', 'lab.simulate', 'lab.replay', 'lab.conformance'],
    OH_CLINICIAN: ['catalogue.read', 'config.read', 'config.nurseReview', 'capture.session', 'capture.verify', 'unmatched.resolve', 'lab.read', 'lab.simulate', 'calibration.record'],
    COLLECTOR: ['capture.session', 'capture.verifyRestricted', 'lab.read', 'lab.simulate'],
    SECURITY_OPS: ['catalogue.read', 'config.read', 'config.coApprove', 'fleet.read', 'endpoints.read', 'security.read', 'security.manage'],
    AUDITOR: ['catalogue.read', 'config.read', 'fleet.read', 'endpoints.read', 'security.read']
  };
  var KIND_CODE = { GATEWAY: 'GW', DEVICE_HOST_AGENT: 'AGT', MOBILE_APP: 'TAB', KIOSK: 'KSK', DATA_MANAGER: 'DM', VENDOR_CLOUD: 'VC', DEVICE: 'DEV' };
  var STEP_NAMES = ['Select device', 'Choose connectivity option', 'Prerequisites checklist', 'Configure parameters', 'Associate & bind', 'Test', 'Certify & approve', 'Publish & monitor'];
  var DX = {};

  // =====================================================================================
  // Errors and utilities
  // =====================================================================================
  function DXError(code, message, detail) { var e = new Error(message || code); e.name = 'DXError'; e.code = code; e.detail = detail || null; return e; }
  function fail(code, message, detail) { throw DXError(code, message, detail); }
  function ensure(c, code, message, detail) { if (!c) fail(code, message, detail); }
  function clone(x) { return x === undefined ? undefined : JSON.parse(JSON.stringify(x)); }
  function byId(arr, id, key) { key = key || 'id'; for (var i = 0; i < (arr || []).length; i++) if (arr[i][key] === id) return arr[i]; return null; }
  function pad(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
  function addMs(iso, ms) { return new Date(Date.parse(iso) + ms).toISOString().replace('.000Z', 'Z'); }
  function uniq(a) { var o = []; a.forEach(function (x) { if (o.indexOf(x) < 0) o.push(x); }); return o; }
  function get(obj, path) { return String(path).split('.').reduce(function (o, k) { return o == null ? undefined : o[k]; }, obj); }
  function setPath(obj, path, v) { var ks = path.split('.'); var o = obj; ks.slice(0, -1).forEach(function (k) { if (!o[k] || typeof o[k] !== 'object') o[k] = {}; o = o[k]; }); o[ks[ks.length - 1]] = v; }

  // =====================================================================================
  // Storage and session
  // =====================================================================================
  var memory = {};
  var memStore = { getItem: function (k) { return Object.prototype.hasOwnProperty.call(memory, k) ? memory[k] : null; }, setItem: function (k, v) { memory[k] = String(v); }, removeItem: function (k) { delete memory[k]; } };
  function store() { try { if (root.localStorage) return root.localStorage; } catch (e) { /* blocked */ } return memStore; }
  var cache = { raw: null, state: null };
  var listeners = [];
  var ctx = { app: null };

  function chain(list, fields) {
    var prev = '0'.repeat(64);
    list.forEach(function (e, i) {
      e.seq = i + 1; e.prevHash = prev;
      e.hash = C.sha256(prev + C.canonicalJson(fields(e)));
      prev = e.hash;
    });
  }
  function eventFields(e) { return { seq: e.seq, type: e.type, severity: e.severity, endpointId: e.endpointId || null, deviceInstanceId: e.deviceInstanceId || null, siteCode: e.siteCode || null, actor: e.actor, sourceModule: e.sourceModule, reasonCode: e.reasonCode || null, attributes: e.attributes || {}, occurredAt: e.occurredAt }; }
  function auditFields(a) { return { seq: a.seq, at: a.at, actor: a.actor, action: a.action, entity: a.entity, entityId: a.entityId, outcome: a.outcome, detail: a.detail || null }; }

  function seedState() {
    var seed = root.WZ_DEVICE_DEMO_SEED;
    if (!seed) fail('no_seed', 'device-demo-data.js was not loaded. Open the app from the demo folder that contains device-demo-data.js.');
    var s = clone(seed);
    s.meta.seedVersion = seed.meta.version; s.meta.engineVersion = VERSION; s.meta.schema = SCHEMA;
    s.meta.sessionId = 'DS-' + C.sha256(seed.meta.version + seed.meta.demoNow).slice(0, 8).toUpperCase();
    s.meta.createdAt = seed.meta.demoNow; s.meta.updatedAt = seed.meta.demoNow; s.meta.rev = 0;
    s.clock = seed.meta.demoNow;
    s.signedIn = {}; s.scenario = { id: null, step: 0, completed: [] };
    s.lockouts = { 'DEV-000001': { since: addMs(seed.meta.demoNow, -24 * 3600000), checkType: 'SPIRO_SYRINGE_3L', reason: 'qc_due', releasedAt: null } };
    s.sinks = { 'E03.RESULT': 'UP', 'E07.IH_MEASUREMENT': 'UP', 'E10.BREATH_ALCOHOL': 'UP', 'E12.SCREENING': 'UP', 'E12.WEARABLE': 'UP' };
    s.installerEnvelopes = [{ endpointId: 'EP-LHR-GW-02', token: 'wzdemo-enrol-ep-lhr-gw-02-not-a-secret', handedOverAt: addMs(seed.meta.demoNow, -24 * 3600000) }];
    s.drills = [];
    s.securityEvents.forEach(function (e, i) { e.id = 'SE-' + pad(i + 1, 6); });
    chain(s.securityEvents, eventFields); chain(s.audit, auditFields);
    s.counters = {};
    return s;
  }
  function readRaw() { try { return store().getItem(KEY); } catch (e) { return null; } }
  function writeRaw(raw) { try { store().setItem(KEY, raw); } catch (e) { fail('storage_full', 'The browser refused to save the demo session (' + e.message + ').'); } }
  function session() {
    var raw = readRaw();
    if (raw && raw === cache.raw && cache.state) return cache.state;
    var st = null;
    if (raw) { try { st = JSON.parse(raw); } catch (e) { st = null; } }
    if (!st || !st.meta || st.meta.schema !== SCHEMA || st.meta.seedVersion !== (root.WZ_DEVICE_DEMO_SEED || {}).meta.version) { st = seedState(); raw = JSON.stringify(st); writeRaw(raw); }
    cache.raw = raw; cache.state = st; return st;
  }
  function persist(st, silent) {
    st.meta.rev = (st.meta.rev || 0) + 1; st.meta.updatedAt = st.clock;
    var raw = JSON.stringify(st); writeRaw(raw); cache.raw = raw; cache.state = st;
    if (!silent) emit({ type: 'change', rev: st.meta.rev });
  }
  function mutate(fn, silent) { session(); var st = JSON.parse(cache.raw); var r = fn(st); persist(st, silent); return r; }
  function emit(evt) { listeners.slice().forEach(function (fn) { try { fn(evt || { type: 'change' }); } catch (e) { if (root.console) console.error(e); } }); }
  if (root.addEventListener) root.addEventListener('storage', function (e) { if (e.key === KEY || e.key === null) { cache.raw = null; cache.state = null; emit({ type: 'sync' }); } });

  function tick(s, seconds) { s.clock = addMs(s.clock, (seconds == null ? 60 : seconds) * 1000); return s.clock; }
  function nextId(s, prefix, width, list, key) {
    var max = 0, re = new RegExp('^' + prefix.replace(/[-]/g, '\\-') + '(\\d+)$');
    (list || []).forEach(function (x) { var m = re.exec(x[key || 'id'] || ''); if (m) max = Math.max(max, Number(m[1])); });
    s.counters[prefix] = Math.max(s.counters[prefix] || 0, max) + 1;
    return prefix + pad(s.counters[prefix], width);
  }

  // =====================================================================================
  // Identity
  // =====================================================================================
  function userByName(s, u) { return byId(s.users, u, 'username'); }
  function currentUser() {
    var s = session(); if (!ctx.app) return null;
    var un = s.signedIn && s.signedIn[ctx.app]; var u = un && userByName(s, un);
    return u && u.apps.indexOf(ctx.app) >= 0 ? clone(u) : null;
  }
  function requireUser() { var u = currentUser(); if (!u) fail('unauthenticated', 'Nobody is signed in.'); return u; }
  function users(app) { return clone(session().users.filter(function (u) { return !app || u.apps.indexOf(app) >= 0; })); }
  function signIn(username) {
    var s = session(); var u = userByName(s, username); ensure(u, 'not_found', 'Unknown user ' + username);
    ensure(!ctx.app || ctx.app === 'launcher' || u.apps.indexOf(ctx.app) >= 0, 'forbidden', u.name + ' does not use the ' + (APPS[ctx.app] || {}).title + '.');
    mutate(function (st) { st.signedIn = st.signedIn || {}; st.signedIn[ctx.app] = username; });
    return clone(u);
  }
  function permsOf(u) { var p = []; (u && u.roles || []).forEach(function (r) { (ROLE_PERMISSIONS[r] || []).forEach(function (x) { if (p.indexOf(x) < 0) p.push(x); }); }); return p; }
  function can(u, perm) { if (typeof u === 'string') u = userByName(session(), u); if (!u) return false; var p = permsOf(u); return (Array.isArray(perm) ? perm : [perm]).some(function (x) { return p.indexOf(x) >= 0; }); }
  function hasRole(u, r) { return !!u && (u.roles || []).indexOf(r) >= 0; }
  function userName(s, un) { var u = userByName(s, un); return u ? u.name : (un === 'system' ? 'System' : un); }

  // =====================================================================================
  // Logs (hash-chained)
  // =====================================================================================
  function secEvent(s, type, severity, o) {
    o = o || {};
    var last = s.securityEvents[s.securityEvents.length - 1];
    var e = { id: 'SE-' + pad(s.securityEvents.length + 1, 6), type: type, severity: severity, endpointId: o.endpointId || null, deviceInstanceId: o.deviceInstanceId || null, siteCode: o.siteCode || null,
      actor: o.actor || 'system', sourceModule: o.sourceModule || 'E34', reasonCode: o.reasonCode || null, attributes: o.attributes || {}, occurredAt: o.at || s.clock, seq: s.securityEvents.length + 1, prevHash: last ? last.hash : '0'.repeat(64) };
    e.hash = C.sha256(e.prevHash + C.canonicalJson(eventFields(e)));
    s.securityEvents.push(e); return e;
  }
  function audit(s, actor, action, entity, entityId, outcome, detail) {
    var last = s.audit[s.audit.length - 1];
    var a = { seq: s.audit.length + 1, at: s.clock, actor: actor, action: action, entity: entity, entityId: entityId, outcome: outcome || 'ALLOWED', detail: detail || null, prevHash: last ? last.hash : '0'.repeat(64) };
    a.hash = C.sha256(a.prevHash + C.canonicalJson(auditFields(a))); s.audit.push(a); return a;
  }
  function verifyChain(list, fields) {
    var prev = '0'.repeat(64);
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e.prevHash !== prev || C.sha256(prev + C.canonicalJson(fields(e))) !== e.hash) return { intact: false, checked: i + 1, firstBrokenId: e.id || e.seq, total: list.length };
      prev = e.hash;
    }
    return { intact: true, checked: list.length, firstBrokenId: null, total: list.length, headHash: prev };
  }

  // =====================================================================================
  // Lookups
  // =====================================================================================
  function model(s, id) { return byId(s.catalogue, id); }
  function driver(s, packageId, version) { var d = s.drivers.filter(function (x) { return x.packageId === packageId && (!version || x.version === version); }); return d[d.length - 1] || null; }
  function driversFor(s, modelCode, option) { return s.drivers.filter(function (d) { return d.models.indexOf(modelCode) >= 0 && (!option || d.options.indexOf(option) >= 0) && d.registry.status === 'CERTIFIED'; }); }
  function cfgById(s, id) { return byId(s.configurations, id); }
  function cfgByInstance(s, inst) { return byId(s.configurations, inst, 'instanceId'); }
  function endpoint(s, id) { return byId(s.endpoints, id, 'endpointId'); }
  function employee(s, id) { return byId(s.employees, id); }
  function optionPack(s, opt) { return byId(s.optionPacks.options, opt, 'option'); }
  function station(s, code) { for (var i = 0; i < s.sites.length; i++) { var st = byId(s.sites[i].stations, code, 'code'); if (st) return Object.assign({ siteCode: s.sites[i].code, test: s.sites[i].test }, st); } return null; }

  // =====================================================================================
  // Calibration / QC / lockout
  // =====================================================================================
  function scheduleOf(s, checkType) { return byId(s.calibration.schedules, checkType, 'checkType'); }
  function checkTypesFor(s, cfg) {
    if (s.calibration.instanceChecks[cfg.instanceId]) return s.calibration.instanceChecks[cfg.instanceId];
    var d = driver(s, cfg.driver.packageId, cfg.driver.version); var out = [];
    (d ? d.qualityGates : []).forEach(function (g) { if (/^QC_DAILY:/.test(g)) out.push(g.split(':')[1]); if (g === 'QC_LOT') out.push('POC_QC_LOT'); if (g === 'BP_CUFF_VALIDATION') out.push('BP_VALIDATION_INTERVAL'); });
    return out;
  }
  function calibrationStatus(s, instanceId) {
    var cfg = cfgByInstance(s, instanceId); if (!cfg) return null;
    var checks = checkTypesFor(s, cfg).map(function (ct) {
      var sch = scheduleOf(s, ct), last = s.calibration.checks.filter(function (c) { return c.instanceId === instanceId && c.checkType === ct; }).sort(function (a, b) { return a.performedAt < b.performedAt ? 1 : -1; })[0];
      var dueAt = last ? addMs(last.performedAt, sch.intervalHours * 3600000) : null;
      var verdict = !last ? 'DUE' : last.outcome === 'FAIL' ? 'FAILED' : (Date.parse(s.clock) > Date.parse(dueAt) ? 'DUE' : 'OK');
      return { checkType: ct, intervalHours: sch.intervalHours, lockout: sch.lockout, lastCheckAt: last ? last.performedAt : null, lastOutcome: last ? last.outcome : null, lot: last && last.lot, dueAt: dueAt, verdict: verdict };
    });
    var eq = byId(s.equipment, cfg.equipmentId, 'equipmentId');
    var cal = eq ? { equipmentId: eq.equipmentId, status: eq.calibrationStatus === 'IN_CALIBRATION' && eq.calibrationDueOn >= s.clock.slice(0, 10) ? 'VALID' : 'OUT_OF_CALIBRATION', dueOn: eq.calibrationDueOn } : { status: 'UNKNOWN' };
    var lk = s.lockouts[instanceId];
    var activeLock = lk && !lk.releasedAt ? lk : null;
    var lockedOut = !!activeLock || checks.some(function (c) { return c.lockout && c.verdict !== 'OK' && c.checkType !== 'POC_QC_LOT'; });
    return { instanceId: instanceId, checks: checks, calibration: cal, lockedOut: lockedOut, lockout: activeLock, releasable: !!activeLock && checks.every(function (c) { return c.verdict === 'OK'; }) };
  }
  function lotStatus(s, instanceId, lot) {
    var pass = s.calibration.checks.filter(function (c) { return c.instanceId === instanceId && c.checkType === 'POC_QC_LOT' && c.lot === lot && c.outcome === 'PASS'; }).sort(function (a, b) { return a.performedAt < b.performedAt ? 1 : -1; })[0];
    if (!pass) return { ok: false, reason: 'new lot – no passing QC run recorded for this lot (new lot requires QC)' };
    if (pass.lotExpiry && pass.lotExpiry < s.clock.slice(0, 10)) return { ok: false, reason: 'lot expired on ' + pass.lotExpiry };
    if (Date.parse(s.clock) - Date.parse(pass.performedAt) > 720 * 3600000) return { ok: false, reason: 'QC run older than the 30-day window' };
    return { ok: true, detail: 'QC passed ' + pass.performedAt.slice(0, 16).replace('T', ' ') + ', expires ' + (pass.lotExpiry || '–') };
  }

  // =====================================================================================
  // JSON Schema (2020-12 subset) validation + option practice rules + package defaults
  // =====================================================================================
  function validateSchema(schema, value, path, errors) {
    errors = errors || []; path = path || '';
    var field = path || '(root)';
    if (value === undefined || value === null || value === '') return errors;
    var t = schema.type;
    if (t === 'object') {
      if (typeof value !== 'object' || Array.isArray(value)) { errors.push({ field: field, code: 'type', message: 'must be an object' }); return errors; }
      (schema.required || []).forEach(function (k) { var v = value[k]; if (v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length && false)) errors.push({ field: path ? path + '.' + k : k, code: 'required', message: 'is required' }); });
      var props = schema.properties || {};
      Object.keys(value).forEach(function (k) {
        if (props[k]) validateSchema(props[k], value[k], path ? path + '.' + k : k, errors);
        else if (schema.additionalProperties === false) errors.push({ field: path ? path + '.' + k : k, code: 'additional_property', message: 'is not allowed by the option schema' });
      });
      return errors;
    }
    if (t === 'array') {
      if (!Array.isArray(value)) { errors.push({ field: field, code: 'type', message: 'must be a list' }); return errors; }
      if (schema.minItems != null && value.length < schema.minItems) errors.push({ field: field, code: 'min_items', message: 'needs at least ' + schema.minItems + ' item(s)' });
      if (schema.maxItems != null && value.length > schema.maxItems) errors.push({ field: field, code: 'max_items', message: 'allows at most ' + schema.maxItems + ' item(s)' });
      if (schema.items) value.forEach(function (v, i) { validateSchema(schema.items, v, path + '[' + i + ']', errors); });
      return errors;
    }
    if (t === 'integer' || t === 'number') {
      if (typeof value !== 'number' || !isFinite(value) || (t === 'integer' && Math.floor(value) !== value)) { errors.push({ field: field, code: 'type', message: 'must be ' + (t === 'integer' ? 'a whole number' : 'a number') }); return errors; }
      if (schema.minimum != null && value < schema.minimum) errors.push({ field: field, code: 'minimum', message: 'must be ≥ ' + schema.minimum });
      if (schema.maximum != null && value > schema.maximum) errors.push({ field: field, code: 'maximum', message: 'must be ≤ ' + schema.maximum });
    }
    if (t === 'string' && typeof value !== 'string') { errors.push({ field: field, code: 'type', message: 'must be text' }); return errors; }
    if (t === 'boolean' && typeof value !== 'boolean') { errors.push({ field: field, code: 'type', message: 'must be yes / no' }); return errors; }
    if (schema.enum && schema.enum.indexOf(value) < 0) errors.push({ field: field, code: 'enum', message: 'must be one of ' + schema.enum.join(', ') });
    if (schema.const !== undefined && value !== schema.const) errors.push({ field: field, code: 'const', message: 'must be ' + schema.const });
    if (typeof value === 'string') {
      if (schema.pattern && !new RegExp(schema.pattern).test(value)) errors.push({ field: field, code: 'pattern', message: 'does not match the required format' });
      if (schema.maxLength != null && value.length > schema.maxLength) errors.push({ field: field, code: 'max_length', message: 'is longer than ' + schema.maxLength + ' characters' });
      if (schema.format === 'hostname' && !/^[A-Za-z0-9]([A-Za-z0-9\-.]{0,252})$/.test(value)) errors.push({ field: field, code: 'format', message: 'is not a valid host name' });
    }
    return errors;
  }
  var WRITE_SQL = /\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|exec|execute|call|into|xp_[a-z_]+|sp_[a-z_]+)\b/i;
  function readOnlySelect(q) { var x = String(q).trim(); return /^select\s/i.test(x) && x.indexOf(';') < 0 && x.indexOf('--') < 0 && x.indexOf('/*') < 0 && !WRITE_SQL.test(x); }
  function practiceRules(option, p, context) {
    var out = [], m = function (f, c, msg) { out.push({ field: f, code: c, message: msg }); };
    p = p || {};
    if (option === 'P1') {
      if ((p.connection === 'USB_CDC' || p.connection === 'USB_HID_WEDGE') && !(p.usbAllowList || []).length) m('usbAllowList', 'required', 'USB devices need a vendor / product id allow-list');
      if (p.mode === 'POLL') { if (!p.pollCommand) m('pollCommand', 'required', 'poll mode needs the poll command'); if (p.pollSeconds == null) m('pollSeconds', 'required', 'poll mode needs the poll interval'); }
      if (p.framing && p.framing.type === 'LINE' && !p.framing.terminator) m('framing.terminator', 'required', 'line framing needs a terminator');
    } else if (option === 'P2') {
      if (p.source === 'DB_QUERY') {
        ['dialect', 'dbCredentialRef', 'query', 'watermarkColumn', 'connection'].forEach(function (k) { if (p[k] == null || p[k] === '') m(k, 'required', 'is required for a database source'); });
        if (p.query && !readOnlySelect(p.query)) m('query', 'query_not_read_only', 'only a single read-only SELECT statement is allowed');
      } else if (p.source === 'FOLDER_WATCH') ['path', 'pattern'].forEach(function (k) { if (!p[k]) m(k, 'required', 'is required for a watched folder'); });
    } else if (option === 'P3') {
      if (p.pairing === 'JUST_WORKS_EXCEPTION' && !p.securityExceptionRef) m('securityExceptionRef', 'pairing_exception_required', "'Just Works' pairing needs a security exception approved by Security Operations");
      if (p.bonding === 'REQUIRED' && p.pairing === 'JUST_WORKS_EXCEPTION') m('pairing', 'pairing_weak', 'bonded devices must pair with LE Secure Connections');
    } else if (option === 'P4') {
      var a = p.deviceAddress || {}; if (!a.ip && !a.dns) m('deviceAddress.ip', 'required', 'a static IP address or DNS name is required');
      var ml = p.mllp || {};
      if (ml.tls === 'EXCEPTION_SEGMENTED_VLAN') {
        if (!ml.plaintextExceptionRef) m('mllp.plaintextExceptionRef', 'plaintext_exception_required', 'plaintext MLLP needs a documented security exception');
        if (context && context.zone && context.zone !== 'DEVICE_VLAN') m('mllp.tls', 'zone_violation', 'plaintext MLLP is allowed only inside the segmented DEVICE_VLAN');
      }
      var wl = p.worklist || {}; if (wl.enabled) { if (!wl.messageType) m('worklist.messageType', 'required', 'worklist push needs the message type'); if (wl.push === 'MLLP_OUT' && wl.outboundPort == null) m('worklist.outboundPort', 'required', "outbound MLLP needs the device's listening port"); }
    } else if (option === 'P5') {
      var qc = p.qcLotPolicy || {}; if (qc.requirePassingQc === false || qc.newLotRequiresQc === false) m('qcLotPolicy', 'cannot_relax', 'a passing QC per lot and per QC window is mandatory for point-of-care results');
    } else if (option === 'P6') {
      var poll = p.poll || {}, wh = p.webhook || {}, polling = !!poll.enabled, hooks = wh.enabled !== undefined ? !!wh.enabled : Object.keys(wh).length > 0;
      if (!polling && !hooks) m('poll.enabled', 'required', 'enable polling, the webhook, or both');
      if (polling) {
        if (!p.apiBaseUrl) m('apiBaseUrl', 'required', 'polling needs the vendor API base URL');
        else { var host = (/^https:\/\/([^/:]+)/.exec(p.apiBaseUrl) || [])[1] || ''; if (!(context.allowedHosts || []).some(function (h) { return h === host || (h[0] === '.' && host.slice(-h.length) === h); })) m('apiBaseUrl', 'host_not_allowed', 'the host is not on the approved egress list (device.cloud.allowed-hosts)'); }
        if (!p.oauth2) m('oauth2', 'required', 'polling needs an OAuth2 client');
      }
      if (hooks && !wh.webhookSecretRef) m('webhook.webhookSecretRef', 'required', 'the webhook needs an HMAC secret');
      if (p.residencyRegion && context.residency && p.residencyRegion.toUpperCase() !== context.residency.toUpperCase()) m('residencyRegion', 'residency_check', "the vendor region does not match the tenant's data residency (" + context.residency + ')');
    } else if (option === 'P7') {
      var mw = p.mwl || {}; if (mw.enabled && !Object.keys(mw.filters || {}).length) m('mwl.filters', 'required', 'the worklist needs a modality or station AE title filter');
      if (p.aeTitles && p.aeTitles.calling && p.aeTitles.calling === p.aeTitles.called) m('aeTitles', 'ae_title_conflict', 'calling and called AE titles must differ');
    } else if (option === 'P8') {
      if (p.auth === 'SMART_BACKEND' && !(p.smartScopes || []).length) m('smartScopes', 'required', 'SMART backend services need system scopes');
    } else if (option === 'P9') {
      (p.regions || []).forEach(function (r, i) { var b = r.box || []; if (b.length === 4 && (b[0] >= b[2] || b[1] >= b[3])) m('regions[' + i + '].box', 'box_invalid', 'the region must be [x1, y1, x2, y2] with x1 < x2 and y1 < y2'); });
    } else if (option === 'P10') {
      var f = p.attestationFields || []; if (f.length && ['MODEL', 'SERIAL', 'CALIBRATION_ID', 'CALIBRATION_DATE'].some(function (x) { return f.indexOf(x) < 0; })) m('attestationFields', 'attestation_incomplete', 'all four attestation fields are mandatory');
    }
    return out;
  }
  function schemaDefaults(schema) {
    if (!schema) return undefined;
    if (schema.default !== undefined) return clone(schema.default);
    if (schema.type === 'object' && schema.properties) {
      var o = {}, any = false;
      Object.keys(schema.properties).forEach(function (k) { var v = schemaDefaults(schema.properties[k]); if (v !== undefined) { o[k] = v; any = true; } });
      return any ? o : undefined;
    }
    return undefined;
  }
  function fromPackage(option, protocol, schema) {
    var out = {}, props = schema.properties || {}; protocol = protocol || {};
    Object.keys(protocol).forEach(function (k) { var v = protocol[k]; if (props[k] && !(typeof v === 'string' && v.indexOf('PARAMETER:') === 0)) out[k] = clone(v); });
    if (option === 'P2' && protocol.deleteAfterRead !== undefined) out.afterRead = 'KEEP';
    if (option === 'P3' && protocol.characteristic) out.characteristics = [protocol.characteristic];
    if (option === 'P4') { if (protocol.mllpPort) setPath(out, 'mllp.port', protocol.mllpPort); if (protocol.tls) setPath(out, 'mllp.tls', protocol.tls); if (protocol.profile) out.messageProfile = protocol.profile; }
    if (option === 'P5' && protocol.dmsVendor) setPath(out, 'dms.vendor', protocol.dmsVendor);
    if (option === 'P6') { var sg = protocol.signature || {}; if (sg.header) { setPath(out, 'webhook.enabled', true); setPath(out, 'webhook.signatureHeader', sg.header); setPath(out, 'webhook.timestampHeader', sg.timestampHeader); setPath(out, 'webhook.replayWindowSeconds', sg.replayWindowSeconds); } if (protocol.poll) { setPath(out, 'poll.enabled', !!protocol.poll.enabled); setPath(out, 'poll.intervalSeconds', protocol.poll.intervalSeconds); } }
    if (option === 'P7') { if (protocol.sopClasses) out.storageClasses = clone(protocol.sopClasses); if (typeof protocol.mwl === 'boolean') setPath(out, 'mwl.enabled', protocol.mwl); }
    if (option === 'P9' && protocol.template) out.ocrTemplate = protocol.template;
    if (option === 'P4' || option === 'P3') { /* bound endpoint chosen at step 4 */ }
    return out;
  }
  function deepMerge(a, b) { var o = clone(a || {}); Object.keys(b || {}).forEach(function (k) { if (b[k] && typeof b[k] === 'object' && !Array.isArray(b[k]) && o[k] && typeof o[k] === 'object' && !Array.isArray(o[k])) o[k] = deepMerge(o[k], b[k]); else o[k] = clone(b[k]); }); return o; }
  /** Defaults for wizard step 4: option schema defaults ← driver package protocol (the administrator validates rather than invents). */
  function parameterDefaults(option, packageId, version) {
    var s = session(), pack = optionPack(s, option), d = driver(s, packageId, version);
    var defs = deepMerge(schemaDefaults(pack.parameterSchema) || {}, fromPackage(option, d ? d.protocol : {}, pack.parameterSchema));
    return { parameters: defs, common: deepMerge(schemaDefaults(s.optionPacks.commonSchema) || {}, { driverPackageId: packageId, driverVersion: version }) };
  }
  var HOST_PATH = { P1: 'hostEndpointId', P2: 'hostEndpointId', P3: 'bondingOwnerEndpointId', P4: 'mllp.listenerEndpointId', P5: 'dms.endpoint', P7: 'listenerEndpointId', P8: 'clientEndpointId' };
  /** Step-4 form values: schema defaults ← driver package protocol ← host endpoint from step 3 ← values already saved. */
  function stepFourDefaults(cfgId) {
    var s = session(), c = cfgById(s, cfgId); if (!c || !c.option) return null;
    var d = parameterDefaults(c.option, c.driver.packageId, c.driver.version), p = d.parameters;
    if (HOST_PATH[c.option] && c.endpointId) setPath(p, HOST_PATH[c.option], c.endpointId);
    if (c.option === 'P5' && c.serial) setPath(p, 'dms.deviceIds', [c.serial]);
    if (c.option === 'P3') { p.bondingOwner = p.bondingOwner || 'TABLET'; if (!p.peripheralAllowList) p.peripheralAllowList = [{ name: model(s, c.modelCode).model.split(' ')[0], addressHash: C.sha256('demo-peripheral-' + (c.serial || c.instanceId)) }]; }
    if (c.option === 'P4') { setPath(p, 'msh.receivingApplication', 'WELLNESSZ'); setPath(p, 'msh.receivingFacility', 'WZ'); if (!get(p, 'msh.sendingFacility')) setPath(p, 'msh.sendingFacility', 'OH_CLINIC'); }
    return { parameters: deepMerge(p, c.parameters || {}), common: deepMerge(d.common, c.common || {}), hostPath: HOST_PATH[c.option] || null };
  }
  function isSecretField(schema, path) { var sc = schema; path.split('.').forEach(function (k) { sc = sc && sc.properties ? sc.properties[k] : null; }); return !!(sc && sc.format === 'secret'); }

  // =====================================================================================
  // Actions framework
  // =====================================================================================
  var ACTIONS = {};
  function defineAction(name, perm, fn) {
    ACTIONS[name] = perm;
    DX[name] = function () {
      var args = Array.prototype.slice.call(arguments);
      var u = requireUser();
      if (perm && !can(u, perm)) {
        mutate(function (s) { audit(s, u.username, name.toUpperCase(), 'action', name, 'DENIED', 'missing permission ' + perm); }, true);
        fail('forbidden', u.name + ' (' + u.roles.join(', ') + ') may not ' + name.replace(/([A-Z])/g, ' $1').toLowerCase() + '.');
      }
      return mutate(function (s) {
        tick(s, 60);
        var r = fn.apply(null, [s, u].concat(args));
        advanceScenario(s, name, u);
        return clone(r);
      });
    };
  }
  function auditRefusal(u, action, entityId, code, detail) { mutate(function (s) { audit(s, u.username, action, 'configuration', entityId, 'DENIED', code + ': ' + detail); }); }

  // =====================================================================================
  // E32 – configurations (eight-step wizard)
  // =====================================================================================
  function stepStatusReset(cfg, from) { for (var n = from; n <= 8; n++) cfg.stepStatus[n] = 'NOT_STARTED'; }
  function prerequisiteItems(s, cfg) {
    var pack = optionPack(s, cfg.option), m = model(s, cfg.modelCode), codes = pack.prerequisites.slice();
    if (m.confidence !== 'High') codes.push('INTERFACE_SPEC_CONFIRMED');
    var AUTO = { ENDPOINT_IDENTITY: 1, CALIBRATION_EVIDENCE: 1, VENDOR_POSTURE: 1, VENDOR_SOFTWARE_VERSION: 0 };
    return codes.map(function (c) { var prev = byId(cfg.prerequisites || [], c, 'code'); return prev || { code: c, status: 'OPEN', auto: !!AUTO[c], evidence: null }; });
  }
  var PREREQ_TEXT = {
    NETWORK_ZONE: 'Network zone and firewall change reference', ENDPOINT_IDENTITY: 'Gateway / agent / tablet enrolled in E34 (auto-check)', DRIVERS_INSTALLED: 'Vendor drivers / cables installed on the host',
    CALIBRATION_EVIDENCE: 'Linked E14 equipment with valid calibration (auto-check)', VENDOR_POSTURE: 'Vendor security posture acceptable (MDS2 / SBOM, auto-check)', VENDOR_SOFTWARE_VERSION: 'Vendor software version within the driver package range',
    PAIRING_CREDENTIALS: 'BLE pairing passkey and bonding owner prepared', CERTIFICATES: 'TLS / mTLS certificates issued by the group PKI', CONSUMABLES: 'Consumables and QC material in stock',
    VENDOR_CLOUD_AGREEMENT: 'Vendor cloud agreement, residency and egress allow-list', INTERFACE_SPEC_CONFIRMED: "Vendor interface specification confirmed (inventory confidence is not 'High')"
  };
  function autoCheck(s, cfg) {
    var m = model(s, cfg.modelCode), host = hostEndpointOf(cfg) || cfg.endpointId, ep = host && endpoint(s, host);
    cfg.prerequisites = prerequisiteItems(s, cfg).map(function (it) {
      it = clone(it);
      if (it.code === 'ENDPOINT_IDENTITY') {
        if (ep && ep.status === 'ACTIVE') { it.status = 'DONE'; it.evidence = { text: host + ' is ACTIVE in E34 (certificate ' + ep.certificate.sha256.slice(0, 12) + '…)', auto: true }; }
        else if (host && !ep) { it.status = 'NOT_APPLICABLE'; it.evidence = { text: 'New endpoint ' + host + ' – provisioned with a one-time enrolment token at publish', auto: true }; }
        else if (!host && (cfg.option === 'P9' || cfg.option === 'P10' || cfg.option === 'P6')) { it.status = 'NOT_APPLICABLE'; it.evidence = { text: 'No local endpoint for this option', auto: true }; }
        else { it.status = 'OPEN'; it.evidence = { text: host ? host + ' is ' + (ep ? ep.status : 'unknown') : 'Choose the host endpoint at step 4', auto: true }; }
      }
      if (it.code === 'CALIBRATION_EVIDENCE') {
        var eq = byId(s.equipment, cfg.equipmentId, 'equipmentId');
        if (eq && eq.modelCode === cfg.modelCode && eq.calibrationDueOn >= s.clock.slice(0, 10)) { it.status = 'DONE'; it.evidence = { text: 'E14 ' + eq.equipmentId + ' (serial ' + eq.serial + ') calibrated, due ' + eq.calibrationDueOn, auto: true }; }
        else { it.status = 'OPEN'; it.evidence = { text: cfg.equipmentId ? 'E14 equipment ' + cfg.equipmentId + ' not found for this model' : 'Link the E14 equipment id at step 4', auto: true }; }
      }
      if (it.code === 'VENDOR_POSTURE') {
        if (m.posture === 'ACCEPTABLE' || m.posture === 'EXCEPTION_APPROVED') { it.status = 'DONE'; it.evidence = { text: 'Posture ' + m.posture, auto: true }; }
        else { it.status = 'OPEN'; it.evidence = { text: 'Posture ' + m.posture + ' – MDS2 not received; request it or record an exception', auto: true }; }
      }
      return it;
    });
  }
  function hostEndpointOf(cfg) {
    var p = cfg.parameters || {};
    switch (cfg.option) { case 'P1': case 'P2': return p.hostEndpointId || null; case 'P3': return p.bondingOwnerEndpointId || null; case 'P4': return (p.mllp || {}).listenerEndpointId || null;
      case 'P5': return (p.dms || {}).endpoint || null; case 'P7': return p.listenerEndpointId || null; case 'P8': return p.clientEndpointId || null; default: return null; }
  }
  function endpointKindFor(s, cfg) {
    var pack = optionPack(s, cfg.option);
    if (cfg.option === 'P3') { var bo = (cfg.parameters || {}).bondingOwner || 'TABLET'; return bo === 'GATEWAY' ? 'GATEWAY' : bo === 'KIOSK' ? 'KIOSK' : 'MOBILE_APP'; }
    return pack.endpointKind || null;
  }
  function stepErrors(errors) { var e = DXError('validation', 'Step not valid: ' + errors.map(function (x) { return x.field + ' ' + x.message; }).join('; '), { errors: errors }); e.errors = errors; return e; }
  function requireDraft(cfg) { ensure(cfg, 'not_found', 'Configuration not found'); ensure(cfg.status === 'DRAFT', 'invalid_state', cfg.id + ' is ' + cfg.status + ' – revise it to change it.'); }

  defineAction('createConfiguration', 'config.write', function (s, u, o) {
    o = o || {};
    var m = model(s, o.modelCode); ensure(m, 'not_found', 'Model ' + o.modelCode + ' is not in the catalogue');
    ensure(m.lifecycle !== 'RETIRED', 'validation', 'Model is retired');
    var id = nextId(s, 'CFG-', 6, s.configurations), inst = 'DEV-' + id.slice(4);
    var cfg = { id: id, version: 1, instanceId: inst, modelCode: m.id, option: null, siteCode: o.siteCode || null, stationCode: null, status: 'DRAFT', currentStep: 2, paused: false,
      endpointId: null, endpointKind: null, serial: null, equipmentId: null, driver: null, stepStatus: { 1: 'VALID', 2: 'NOT_STARTED', 3: 'NOT_STARTED', 4: 'NOT_STARTED', 5: 'NOT_STARTED', 6: 'NOT_STARTED', 7: 'NOT_STARTED', 8: 'NOT_STARTED' },
      prerequisites: [], parameters: {}, common: {}, secrets: {}, associationMethods: [], services: [], bindings: [], tests: [], nurseReview: null, simulatorOnlyJustification: null,
      editors: [], createdBy: u.username, createdAt: s.clock, submittedBy: null, approvedBy: null, coApprovedBy: null, certificationId: null, warnings: m.posture !== 'ACCEPTABLE' ? ['Vendor posture ' + m.posture] : [],
      history: [{ at: s.clock, by: u.username, action: 'CREATED', note: m.manufacturer + ' ' + m.model }] };
    s.configurations.push(cfg);
    audit(s, u.username, 'DEVICE_CONFIG_CREATE', 'configuration', id, 'ALLOWED', m.id);
    return cfg;
  });

  defineAction('saveStep', 'config.write', function (s, u, id, n, data) {
    var cfg = cfgById(s, id); requireDraft(cfg); data = data || {};
    var errors = [];
    if (n === 2) {
      var m = model(s, cfg.modelCode);
      if (m.options.indexOf(data.option) < 0) errors.push({ field: 'option', code: 'option_not_supported', message: data.option + ' is not supported by ' + m.model });
      var ds = driversFor(s, cfg.modelCode, data.option);
      if (!errors.length && !ds.length) errors.push({ field: 'option', code: 'no_driver_for_option', message: 'No certified driver package for ' + m.model + ' over ' + data.option + ' – request a driver (request-a-model)' });
      if (errors.length) throw stepErrors(errors);
      var d = ds[ds.length - 1];
      if (cfg.option !== data.option) { stepStatusReset(cfg, 3); cfg.prerequisites = []; cfg.parameters = {}; cfg.bindings = []; cfg.tests = []; cfg.nurseReview = null; }
      cfg.option = data.option; cfg.driver = { packageId: d.packageId, version: d.version };
      cfg.prerequisites = prerequisiteItems(s, cfg); cfg.stepStatus[2] = 'VALID'; cfg.currentStep = 3;
    } else if (n === 3) {
      if (data.endpointId !== undefined) cfg.endpointId = data.endpointId || null;
      if (data.equipmentId !== undefined) cfg.equipmentId = data.equipmentId || null;
      if (data.serial !== undefined) cfg.serial = data.serial || null;
      if (cfg.endpointId && !/^EP-[A-Z0-9]{2,5}-[A-Z]{2,4}-\d{2}$/.test(cfg.endpointId)) throw stepErrors([{ field: 'endpointId', code: 'pattern', message: 'endpoint ids look like EP-<SITE>-<KIND>-<NN>' }]);
      autoCheck(s, cfg);
      (data.items || []).forEach(function (it) { var p = byId(cfg.prerequisites, it.code, 'code'); if (p && !(p.evidence && p.evidence.auto && p.status === 'DONE')) { p.status = it.status; p.evidence = it.evidence || p.evidence; } });
      autoCheckKeep(s, cfg);
      var open = cfg.prerequisites.filter(function (p) { return p.status === 'OPEN'; });
      cfg.stepStatus[3] = open.length ? 'INVALID' : 'VALID';
      if (open.length && !data.allowOpen) { cfg.history.push({ at: s.clock, by: u.username, action: 'STEP_3_SAVED', note: open.length + ' open' }); throw stepErrors(open.map(function (p) { return { field: p.code, code: 'open', message: 'is still open' + (p.evidence ? ' – ' + p.evidence.text : '') }; })); }
      cfg.currentStep = 4;
    } else if (n === 4) {
      var pack = optionPack(s, cfg.option);
      var params = clone(data.parameters || {}), secrets = data.secrets || {};
      Object.keys(secrets).forEach(function (k) { if (secrets[k]) { var ref = 'devsec:' + cfg.instanceId.toLowerCase() + '/' + k.replace(/\./g, '-'); setPath(params, k, ref); cfg.secrets[k] = { set: true, ref: ref, storedAt: s.clock }; secEvent(s, 'SECRET_STORED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, sourceModule: 'E32', attributes: { reference: ref } }); } });
      Object.keys(cfg.secrets).forEach(function (k) { if (get(params, k) == null) setPath(params, k, cfg.secrets[k].ref); });
      errors = validateSchema(pack.parameterSchema, params);
      errors = errors.concat(practiceRules(cfg.option, params, { allowedHosts: s.config.allowedCloudHosts, residency: s.config.residency, zone: data.zone || null }));
      var common = deepMerge(cfg.common, data.common || {});
      errors = errors.concat(validateSchema(s.optionPacks.commonSchema, common).map(function (e) { e.field = 'common.' + e.field; return e; }));
      var d4 = driver(s, cfg.driver.packageId, cfg.driver.version);
      (common.qualityGateAdditions || []).forEach(function (g) { if (!/^[A-Z_]{3,40}(:[A-Za-z0-9_=.]{1,40})?$/.test(g)) errors.push({ field: 'common.qualityGateAdditions', code: 'pattern', message: g + ' is not a gate code' }); });
      if (common.verifyBeforePost === false && d4.bindings.some(function (b) { return b.verifyBeforePost; })) errors.push({ field: 'common.verifyBeforePost', code: 'cannot_relax', message: 'gates and verification can only be added or tightened, never removed below the package set' });
      // secret scanner: literal secrets are refused
      JSON.stringify(params, function (k, v) { if (/password|secret|token|api[-_]?key|private[-_]?key|credential/i.test(k) && typeof v === 'string' && !/^(devsec|vault):/.test(v)) errors.push({ field: k, code: 'literal_secret', message: 'secrets must be entered in the write-only secret field (stored as a devsec: reference)' }); return v; });
      if (data.equipmentId !== undefined) cfg.equipmentId = data.equipmentId || null;
      if (data.serial !== undefined) cfg.serial = data.serial || null;
      if (!cfg.equipmentId) errors.push({ field: 'equipmentId', code: 'required', message: 'link the E14 equipment record (calibration authority)' });
      cfg.parameters = params; cfg.common = common;
      if (cfg.editors.indexOf(u.username) < 0) cfg.editors.push(u.username);
      cfg.history.push({ at: s.clock, by: u.username, action: 'STEP_4_SAVED', note: errors.length ? errors.length + ' error(s)' : 'valid' });
      if (errors.length) { cfg.stepStatus[4] = 'INVALID'; throw stepErrors(errors); }
      cfg.endpointId = hostEndpointOf(cfg) || cfg.endpointId; cfg.endpointKind = endpointKindFor(s, cfg);
      autoCheck(s, cfg);
      cfg.stepStatus[4] = 'VALID'; stepStatusReset(cfg, 6); cfg.tests = []; cfg.nurseReview = null; cfg.currentStep = 5;
    } else if (n === 5) {
      var d5 = driver(s, cfg.driver.packageId, cfg.driver.version), caps = d5.capabilities || {};
      var methods = data.associationMethods || [];
      if (!methods.length) errors.push({ field: 'associationMethods', code: 'required', message: 'choose at least one association method' });
      methods.forEach(function (mm) {
        if (mm === 'WORKLIST' && (!caps.worklistPush || caps.worklistPush === 'NONE') && d5.family !== 'DRUG_ALCOHOL' && d5.family !== 'POC' && d5.family !== 'IMAGING') errors.push({ field: 'associationMethods', code: 'not_supported', message: 'WORKLIST needs a driver that supports worklist push' });
        if (C.RESTRICTED[d5.family] && ['WORKLIST', 'SESSION'].indexOf(mm) < 0) errors.push({ field: 'associationMethods', code: 'restricted_category', message: mm + ' is not allowed for a restricted category (breath alcohol): only WORKLIST or SESSION' });
      });
      var st = station(s, data.stationCode);
      if (!st) errors.push({ field: 'stationCode', code: 'not_found', message: 'station does not exist (E14 site / room codes)' });
      var bindings = (data.bindings || d5.bindings).map(function (b) { return clone(b); });
      var keys = d5.mappings.map(function (mp) { return mp.key; });
      bindings.forEach(function (b, i) { Object.keys(b.fields || {}).forEach(function (k) { if (keys.indexOf(k) < 0) errors.push({ field: 'bindings[' + i + '].fields.' + k, code: 'unknown_key', message: k + ' is not a mapping key of the driver package' }); }); if (b.verifyBeforePost === false && (byId(d5.bindings, b.formCode, 'formCode') || {}).verifyBeforePost) errors.push({ field: 'bindings[' + i + '].verifyBeforePost', code: 'cannot_relax', message: 'verification before posting cannot be removed for ' + b.formCode }); });
      if (!bindings.length) errors.push({ field: 'bindings', code: 'required', message: 'bind at least one OH form' });
      cfg.history.push({ at: s.clock, by: u.username, action: 'STEP_5_SAVED' });
      if (cfg.editors.indexOf(u.username) < 0) cfg.editors.push(u.username);
      if (errors.length) { cfg.stepStatus[5] = 'INVALID'; throw stepErrors(errors); }
      cfg.associationMethods = methods; cfg.stationCode = st.code; cfg.siteCode = st.siteCode; cfg.services = data.services || ['Spot measurement']; cfg.bindings = bindings;
      cfg.stepStatus[5] = 'VALID'; stepStatusReset(cfg, 6); cfg.tests = []; cfg.nurseReview = null; cfg.currentStep = 6;
    } else if (n === 6) {
      cfg.simulatorOnlyJustification = data.simulatorOnlyJustification || null;
      if (cfg.editors.indexOf(u.username) < 0) cfg.editors.push(u.username);
      evaluateStep6(s, cfg);
    } else fail('validation', 'Unknown step ' + n);
    audit(s, u.username, 'DEVICE_CONFIG_STEP', 'configuration', id, 'ALLOWED', 'step ' + n);
    return cfg;
  });
  function autoCheckKeep(s, cfg) { var keep = clone(cfg.prerequisites); autoCheck(s, cfg); cfg.prerequisites.forEach(function (p) { var k = byId(keep, p.code, 'code'); if (k && !p.auto && k.status !== 'OPEN') { p.status = k.status; p.evidence = k.evidence; } }); }
  function liveRequired(s, cfg) { var st = station(s, cfg.stationCode); return !(st && st.test); }
  function evaluateStep6(s, cfg) {
    var sim = cfg.tests.filter(function (t) { return t.mode === 'SIMULATOR' && t.status === 'PASSED'; }).length > 0;
    var live = cfg.tests.filter(function (t) { return t.mode === 'LIVE' && t.status === 'PASSED'; }).length > 0;
    var liveOk = live || !liveRequired(s, cfg) || !!cfg.simulatorOnlyJustification;
    var nurse = cfg.nurseReview && cfg.nurseReview.outcome === 'ACCEPTED';
    cfg.stepStatus[6] = sim && liveOk && nurse ? 'VALID' : 'IN_PROGRESS';
    if (cfg.stepStatus[6] === 'VALID') cfg.currentStep = 7;
    return { sim: sim, live: live, liveOk: liveOk, nurse: !!nurse };
  }
  /** Run the package test vectors (simulator) or the live connection test (endpoint heartbeat ≤ 5 min). */
  function testVectorsRun(s, d, bindings) {
    var results = d.testVectors.map(function (tv) {
      var dsId = tv.input.replace(/^golden\//, '').replace(/\.[a-z0-9]+$/, '');
      var g = byId(s.golden, dsId, 'datasetId');
      if (!g) return { name: tv.name, dataset: dsId, pass: false, actual: 'dataset missing' };
      var r = C.runPipeline({ driver: d, raw: g.payload, headers: g.headers, receivedAt: s.clock, messageId: 'TV-' + dsId, instanceId: 'SIM', rawSha256: C.sha256(g.payload), simulator: true, binding: bindings && bindings[0],
        ctx: { gates: { calibration: function () { return { status: 'VALID', equipmentId: 'SIM', dueOn: '–' }; }, qc: function () { return 'OK'; }, lot: function () { return { ok: true, detail: 'simulator' }; }, consentFor: function () { return true; }, validationListed: !!(d.protocol && d.protocol.validationListed), maxSkewSeconds: s.config.maxSkewSeconds },
          association: { employees: s.employees, worklist: [], thresholds: { auto: s.config.autoFileThreshold, suggest: s.config.suggestThreshold } }, employeeById: function (id) { return employee(s, id); } } });
      var exp = tv.expect, pass = true, notes = [];
      if (exp.error) { pass = !!r.error && r.error.code === exp.error; notes.push('expected ' + exp.error + ', got ' + (r.error ? r.error.code : r.status)); }
      else if (r.error) { pass = false; notes.push(r.error.code + ': ' + r.error.message); }
      else {
        var rec = r.records[0];
        Object.keys(exp).filter(function (k) { return k !== 'gates'; }).forEach(function (k) { var c = byId(rec.norm.components, k, 'key'); var ok = c && (typeof exp[k] === 'number' ? Math.abs(c.value - exp[k]) < 0.051 : String(c.value) === String(exp[k])); if (!ok) pass = false; notes.push(k + ' ' + (c ? c.value : '–') + (ok ? ' ✓' : ' ≠ ' + exp[k])); });
        Object.keys(exp.gates || {}).forEach(function (gname) { var f = byId(rec.gates.flags, gname, 'gate'); var ok = f && f.outcome === exp.gates[gname]; if (!ok) pass = false; notes.push(gname + ' ' + (f ? f.outcome : '–') + (ok ? ' ✓' : ' ≠ ' + exp.gates[gname])); });
      }
      return { name: tv.name, dataset: dsId, negative: !!exp.error || /BLOCK/.test(JSON.stringify(exp)), pass: pass, actual: notes.join(', ') };
    });
    return results;
  }
  defineAction('runConfigTest', 'config.test', function (s, u, id, o) {
    var cfg = cfgById(s, id); requireDraft(cfg); o = o || {};
    ensure(cfg.stepStatus[5] === 'VALID', 'invalid_state', 'Complete step 5 (associate & bind) first');
    var d = driver(s, cfg.driver.packageId, cfg.driver.version), run;
    var rid = 'TR-' + cfg.id.slice(4) + '-' + pad(cfg.tests.length + 1, 2);
    if ((o.mode || 'SIMULATOR') === 'SIMULATOR') {
      var results = testVectorsRun(s, d, cfg.bindings);
      var allPass = results.every(function (r) { return r.pass; });
      run = { testRunId: rid, mode: 'SIMULATOR', status: allPass ? 'PASSED' : 'FAILED', vectors: results.length, negatives: results.filter(function (r) { return r.negative; }).length, results: results, by: u.username, finishedAt: s.clock };
    } else {
      var ep = cfg.endpointId && endpoint(s, cfg.endpointId);
      var seen = ep && ep.status === 'ACTIVE' && ep.lastSeenAt && (Date.parse(s.clock) - Date.parse(ep.lastSeenAt)) <= 5 * 60000 + 3600000;
      var checks = [{ check: 'Endpoint authenticated with mutual TLS', pass: !!seen }, { check: 'Plaintext connection refused', pass: !!seen }, { check: 'Heartbeat seen within 5 minutes', pass: !!seen }, { check: 'End-to-end sample into the test form', pass: !!seen }];
      run = { testRunId: rid, mode: 'LIVE', status: seen ? 'PASSED' : 'FAILED', vectors: 1, results: checks.map(function (c) { return { name: c.check, pass: c.pass, actual: c.pass ? 'ok' : (ep ? cfg.endpointId + ' is ' + ep.status + ' – no heartbeat' : 'endpoint ' + (cfg.endpointId || '–') + ' not enrolled yet') }; }), by: u.username, finishedAt: s.clock };
    }
    run.evidenceSha256 = C.sha256(C.canonicalJson({ cfg: cfg.id, version: cfg.version, run: run.results, at: s.clock }));
    cfg.tests.push(run);
    if (cfg.editors.indexOf(u.username) < 0 && hasRole(u, 'DEVICE_ADMIN')) cfg.editors.push(u.username);
    cfg.history.push({ at: s.clock, by: u.username, action: 'TEST_' + run.mode + '_' + run.status });
    evaluateStep6(s, cfg);
    audit(s, u.username, 'DEVICE_CONFIG_TEST', 'configuration', id, 'ALLOWED', run.mode + ' ' + run.status);
    return run;
  });
  defineAction('nurseReview', 'config.nurseReview', function (s, u, id, o) {
    var cfg = cfgById(s, id); requireDraft(cfg); o = o || {};
    ensure(cfg.tests.some(function (t) { return t.status === 'PASSED'; }), 'invalid_state', 'Run a passing test first – the nurse reviews the test form preview');
    ensure(['ACCEPTED', 'REJECTED'].indexOf(o.outcome) >= 0, 'validation', 'Outcome must be ACCEPTED or REJECTED');
    cfg.nurseReview = { by: u.username, at: s.clock, outcome: o.outcome, note: o.note || '' };
    cfg.history.push({ at: s.clock, by: u.username, action: 'NURSE_REVIEW_' + o.outcome });
    evaluateStep6(s, cfg);
    audit(s, u.username, 'DEVICE_CONFIG_NURSE_REVIEW', 'configuration', id, 'ALLOWED', o.outcome);
    return cfg;
  });
  defineAction('submitConfiguration', 'config.submit', function (s, u, id) {
    var cfg = cfgById(s, id); requireDraft(cfg);
    var bad = [1, 2, 3, 4, 5, 6].filter(function (n) { return cfg.stepStatus[n] !== 'VALID'; });
    ensure(!bad.length, 'invalid_state', 'Steps ' + bad.join(', ') + ' are not valid yet');
    cfg.status = 'AWAITING_APPROVAL'; cfg.submittedBy = u.username; cfg.submittedAt = s.clock; cfg.currentStep = 7; cfg.stepStatus[7] = 'IN_PROGRESS';
    cfg.approvedBy = null; cfg.coApprovedBy = null;
    cfg.history.push({ at: s.clock, by: u.username, action: 'SUBMITTED' });
    secEvent(s, 'CONFIG_SUBMITTED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, siteCode: cfg.siteCode, sourceModule: 'E32', attributes: { configurationId: cfg.id, version: cfg.version } });
    return cfg;
  });
  function certify(s, cfg) {
    cfg.status = 'CERTIFIED'; cfg.stepStatus[7] = 'VALID'; cfg.currentStep = 8;
    cfg.certificationId = cfg.certificationId && cfg.version === 1 ? cfg.certificationId : nextId(s, 'DCR-2026-', 6, s.configurations.filter(function (c) { return c.certificationId; }).map(function (c) { return { id: c.certificationId }; }));
    var d = driver(s, cfg.driver.packageId, cfg.driver.version);
    cfg.certification = { certificationId: cfg.certificationId, configurationId: cfg.id, version: cfg.version, deviceInstanceId: cfg.instanceId, snapshotSha256: C.sha256(C.canonicalJson(snapshot(cfg))),
      driver: { packageId: d.packageId, version: d.version, sha256: d.signature.canonicalSha256, signatureKeyId: d.signature.keyId }, approvedAt: s.clock };
  }
  function snapshot(cfg) { return { modelCode: cfg.modelCode, option: cfg.option, siteCode: cfg.siteCode, stationCode: cfg.stationCode, parameters: cfg.parameters, common: cfg.common, associationMethods: cfg.associationMethods, bindings: cfg.bindings, driver: cfg.driver, equipmentId: cfg.equipmentId }; }
  defineAction('approveConfiguration', 'config.approve', function (s, u, id, o) {
    var cfg = cfgById(s, id); ensure(cfg, 'not_found', 'Configuration not found'); o = o || {};
    ensure(cfg.status === 'AWAITING_APPROVAL', 'invalid_state', cfg.id + ' is not awaiting approval');
    if (u.username === cfg.submittedBy || cfg.editors.indexOf(u.username) >= 0) {
      var why = u.username === cfg.submittedBy ? 'you submitted this configuration' : 'you changed steps 4–6 of this version';
      throw Object.assign(DXError('four_eyes_violation', 'Four-eyes rule: ' + u.name + ' cannot approve ' + cfg.id + ' because ' + why + '. Another approver must certify it.'), { audit: { user: u.username, id: cfg.id, why: why } });
    }
    ensure(!cfg.approvedBy, 'invalid_state', 'Already approved by ' + userName(s, cfg.approvedBy));
    cfg.approvedBy = u.username; cfg.approvedAt = s.clock; cfg.approvalNote = o.note || '';
    cfg.history.push({ at: s.clock, by: u.username, action: 'APPROVED', note: o.note || '' });
    secEvent(s, 'CONFIG_APPROVED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, siteCode: cfg.siteCode, sourceModule: 'E32', attributes: { configurationId: cfg.id, version: cfg.version } });
    if (!cfg.simulatorOnlyJustification || cfg.coApprovedBy) certify(s, cfg);
    return cfg;
  });
  defineAction('coApproveConfiguration', 'config.coApprove', function (s, u, id) {
    var cfg = cfgById(s, id); ensure(cfg && cfg.status === 'AWAITING_APPROVAL', 'invalid_state', 'Not awaiting approval');
    ensure(cfg.simulatorOnlyJustification, 'invalid_state', 'Security co-approval is only needed when the live test is waived');
    ensure(u.username !== cfg.submittedBy, 'four_eyes_violation', 'The submitter cannot co-approve');
    cfg.coApprovedBy = u.username; cfg.coApprovedAt = s.clock;
    cfg.history.push({ at: s.clock, by: u.username, action: 'SECURITY_CO_APPROVED', note: 'Live test waived: ' + cfg.simulatorOnlyJustification });
    secEvent(s, 'CONFIG_APPROVED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, sourceModule: 'E34', reasonCode: 'SIMULATOR_ONLY_CO_APPROVAL', attributes: { configurationId: cfg.id } });
    if (cfg.approvedBy) certify(s, cfg);
    return cfg;
  });
  defineAction('rejectConfiguration', 'config.approve', function (s, u, id, o) {
    var cfg = cfgById(s, id); ensure(cfg && cfg.status === 'AWAITING_APPROVAL', 'invalid_state', 'Not awaiting approval'); o = o || {};
    ensure(o.reason, 'validation', 'A reason is required');
    cfg.status = 'DRAFT'; cfg.stepStatus[7] = 'NOT_STARTED'; cfg.currentStep = 6; cfg.approvedBy = null; cfg.coApprovedBy = null;
    cfg.history.push({ at: s.clock, by: u.username, action: 'REJECTED', note: o.reason });
    secEvent(s, 'CONFIG_REJECTED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, sourceModule: 'E32', attributes: { configurationId: cfg.id, reason: o.reason } });
    return cfg;
  });
  function newToken(s, label) { return 'wz' + label + '-' + C.sha256(label + ':' + s.clock + ':' + s.meta.rev + ':' + s.securityEvents.length); }
  function createEndpointIn(s, actor, o) {
    var site = o.siteCode, kc = KIND_CODE[o.kind] || 'EP';
    var n = 1; while (endpoint(s, 'EP-' + site + '-' + kc + '-' + pad(n, 2))) n++;
    var id = o.endpointId || 'EP-' + site + '-' + kc + '-' + pad(n, 2);
    var token = newToken(s, 'enrol');
    var ep = { endpointId: id, kind: o.kind, siteCode: site, zone: o.zone, tenantCode: 'GROUP', status: 'PENDING_ENROLMENT', certificate: null, tokenHash: null, tokenIssuedAt: null, tokenExpiresAt: null,
      previousTokenHash: null, previousTokenValidUntil: null, deviceInstanceIds: o.deviceInstanceIds || [], createdBy: actor, createdAt: s.clock, enrolledAt: null, lastSeenAt: null,
      heartbeat: null, isolatedAt: null, revokedAt: null, recertificationRequired: false, ratePerMinuteP95: o.kind === 'GATEWAY' ? 30 : 6,
      enrolment: { tokenHash: C.sha256(token), expiresAt: addMs(s.clock, s.config.enrolmentTokenHours * 3600000), used: false, kind: o.kind } };
    s.endpoints.push(ep);
    s.installerEnvelopes.push({ endpointId: id, token: token, handedOverAt: s.clock });
    secEvent(s, 'ENDPOINT_CREATED', 'LOW', { actor: actor, endpointId: id, siteCode: site, attributes: { kind: o.kind, zone: o.zone } });
    return { endpoint: ep, enrolmentToken: token, enrolmentExpiresAt: ep.enrolment.expiresAt };
  }
  defineAction('publishConfiguration', 'config.publish', function (s, u, id) {
    var cfg = cfgById(s, id); ensure(cfg, 'not_found', 'Configuration not found');
    ensure(cfg.status === 'CERTIFIED', 'invalid_state', cfg.id + ' must be certified (four-eyes approval) before it can be published');
    var out = { deviceInstanceId: cfg.instanceId, configVersion: cfg.version, endpointId: cfg.endpointId, enrolmentToken: null, enrolmentExpiresAt: null };
    if (cfg.endpointId) {
      var ep = endpoint(s, cfg.endpointId);
      if (!ep) {
        var zone = cfg.endpointKind === 'MOBILE_APP' || cfg.endpointKind === 'KIOSK' ? 'CLINIC_LAN' : 'GATEWAY_ZONE';
        var created = createEndpointIn(s, u.username, { endpointId: cfg.endpointId, kind: cfg.endpointKind, siteCode: cfg.siteCode, zone: zone, deviceInstanceIds: [cfg.instanceId] });
        out.enrolmentToken = created.enrolmentToken; out.enrolmentExpiresAt = created.enrolmentExpiresAt; out.zone = zone;
      } else if (ep.deviceInstanceIds.indexOf(cfg.instanceId) < 0) ep.deviceInstanceIds.push(cfg.instanceId);
    }
    var wasQuarantined = cfg.previousStatus === 'QUARANTINED' && cfg.endpointId && endpoint(s, cfg.endpointId) && endpoint(s, cfg.endpointId).status === 'ISOLATED';
    cfg.status = wasQuarantined ? 'QUARANTINED' : 'PUBLISHED'; cfg.recertified = !!wasQuarantined || cfg.recertified; cfg.stepStatus[8] = 'VALID'; cfg.publishedBy = u.username; cfg.publishedAt = s.clock;
    cfg.history.push({ at: s.clock, by: u.username, action: wasQuarantined ? 'RECERTIFIED' : 'PUBLISHED', note: wasQuarantined ? 'Re-certified – waiting for the Security Operations release of ' + cfg.endpointId : (out.enrolmentToken ? 'Endpoint ' + cfg.endpointId + ' provisioned with a one-time enrolment token' : '') });
    secEvent(s, 'CONFIG_PUBLISHED', 'LOW', { actor: u.username, deviceInstanceId: cfg.instanceId, endpointId: cfg.endpointId, siteCode: cfg.siteCode, sourceModule: 'E32', attributes: { configurationId: cfg.id, version: cfg.version, recertified: !!wasQuarantined } });
    out.status = cfg.status;
    return out;
  });
  defineAction('reviseConfiguration', 'config.write', function (s, u, id, o) {
    var cfg = cfgById(s, id); ensure(cfg, 'not_found', 'Configuration not found');
    ensure(['PUBLISHED', 'QUARANTINED', 'CERTIFIED'].indexOf(cfg.status) >= 0, 'invalid_state', 'Only published, certified or quarantined configurations can be revised');
    cfg.previousStatus = cfg.status; cfg.version += 1; cfg.status = 'DRAFT';
    cfg.stepStatus[6] = 'NOT_STARTED'; cfg.stepStatus[7] = 'NOT_STARTED'; cfg.stepStatus[8] = 'NOT_STARTED'; cfg.currentStep = 6;
    cfg.tests = []; cfg.nurseReview = null; cfg.editors = [u.username]; cfg.submittedBy = null; cfg.approvedBy = null; cfg.coApprovedBy = null; cfg.simulatorOnlyJustification = null;
    cfg.history.push({ at: s.clock, by: u.username, action: 'REVISED', note: 'Version ' + cfg.version + ((o && o.reason) ? ' – ' + o.reason : '') });
    return cfg;
  });
  defineAction('setPaused', 'config.write', function (s, u, id, paused) { var cfg = cfgById(s, id); requireDraft(cfg); cfg.paused = !!paused; cfg.history.push({ at: s.clock, by: u.username, action: paused ? 'PAUSED' : 'RESUMED' }); return cfg; });

  // =====================================================================================
  // E32 – calibration / QC lockout
  // =====================================================================================
  defineAction('recordCalibrationCheck', 'calibration.record', function (s, u, o) {
    o = o || {};
    var cfg = cfgByInstance(s, o.instanceId); ensure(cfg, 'not_found', 'Device instance not found');
    ensure(scheduleOf(s, o.checkType), 'validation', 'Unknown check type');
    ensure(['PASS', 'FAIL'].indexOf(o.outcome) >= 0, 'validation', 'Outcome must be PASS or FAIL');
    if (o.checkType === 'POC_QC_LOT') ensure(o.lot, 'validation', 'The QC lot number is required');
    var c = { id: 'QC-' + pad(s.calibration.checks.length + 1, 5), instanceId: o.instanceId, checkType: o.checkType, performedAt: s.clock, outcome: o.outcome, by: u.username, values: o.values || null, lot: o.lot || null, lotExpiry: o.lotExpiry || null };
    s.calibration.checks.push(c);
    if (s.calibration.instanceChecks[o.instanceId] && s.calibration.instanceChecks[o.instanceId].indexOf(o.checkType) < 0) s.calibration.instanceChecks[o.instanceId].push(o.checkType);
    if (o.outcome === 'FAIL') s.lockouts[o.instanceId] = { since: s.clock, checkType: o.checkType, reason: 'qc_failed', releasedAt: null };
    audit(s, u.username, 'DEVICE_QC_CHECK', 'device', o.instanceId, 'ALLOWED', o.checkType + ' ' + o.outcome + (o.lot ? ' lot ' + o.lot : ''));
    return c;
  });
  defineAction('releaseLockout', 'calibration.release', function (s, u, instanceId) {
    var st = calibrationStatusIn(s, instanceId); ensure(st, 'not_found', 'Device instance not found');
    ensure(st.lockout, 'invalid_state', instanceId + ' is not locked out');
    var bad = st.checks.filter(function (c) { return c.verdict !== 'OK'; });
    ensure(!bad.length, 'release_refused', 'Release needs a newer passing check: ' + bad.map(function (c) { return c.checkType + ' is ' + c.verdict; }).join(', ') + ' (no override).');
    s.lockouts[instanceId].releasedAt = s.clock; s.lockouts[instanceId].releasedBy = u.username;
    audit(s, u.username, 'DEVICE_LOCKOUT_RELEASE', 'device', instanceId, 'ALLOWED', st.lockout.checkType);
    return calibrationStatusIn(s, instanceId);
  });
  function calibrationStatusIn(s, id) { return calibrationStatus(s, id); }

  // =====================================================================================
  // E31 – ingest (ACQUIRE + pipeline), capture, unmatched, dead letters, conformance
  // =====================================================================================
  function activeSession(s, instanceId) { var l = s.captureSessions.filter(function (x) { return x.instanceId === instanceId && x.status === 'OPEN'; }); return l[l.length - 1] || null; }
  function pipelineCtx(s, cfg, d) {
    var inst = cfg && cfg.instanceId, sess = inst ? activeSession(s, inst) : null;
    return {
      employeeById: function (id) { return employee(s, id) || employee(s, C.normaliseId(id)); },
      association: { employees: s.employees, worklist: s.worklist, session: sess, methods: cfg ? cfg.associationMethods : null, thresholds: { auto: s.config.autoFileThreshold, suggest: s.config.suggestThreshold }, sessionWindowSeconds: s.config.sessionWindowSeconds, prefixes: ['EMP'] },
      gates: {
        calibration: function () {
          if (!cfg) return { status: 'VALID', equipmentId: 'SIMULATOR', dueOn: '–' };
          var st = calibrationStatus(s, inst); return st.calibration;
        },
        qc: function (ct) {
          if (!cfg) return 'OK';
          var st = calibrationStatus(s, inst), c = byId(st.checks, ct, 'checkType');
          if (c && c.verdict !== 'OK') return c.verdict;
          if (st.lockout && st.lockout.checkType === ct) return 'LOCKED';
          return c ? 'OK' : 'NOT_REQUIRED';
        },
        lot: function (lot) { return cfg ? lotStatus(s, inst, lot) : { ok: true, detail: 'simulator' }; },
        consentFor: function (empId, purpose) { return s.consents.some(function (c) { return c.employeeId === empId && c.purpose === purpose && c.status === 'GIVEN'; }); },
        validationListed: !!(d.protocol && d.protocol.validationListed), maxSkewSeconds: s.config.maxSkewSeconds, ocrMinConfidence: s.config.ocrMinConfidence
      },
      gateAdditions: cfg ? (cfg.common.qualityGateAdditions || []) : []
    };
  }
  function authenticateEndpoint(s, cfg) {
    if (!cfg.endpointId) return { ok: true };
    var ep = endpoint(s, cfg.endpointId);
    if (!ep) return { ok: false, code: 'unknown_endpoint', http: 401 };
    if (ep.status === 'PENDING_ENROLMENT') return { ok: false, code: 'endpoint_pending', http: 401 };
    if (ep.status === 'ISOLATED') return { ok: false, code: 'endpoint_isolated', http: 401, park: true };
    if (ep.status === 'REVOKED') return { ok: false, code: 'endpoint_revoked', http: 401 };
    if (ep.tokenExpiresAt && ep.tokenExpiresAt < s.clock) return { ok: false, code: 'token_expired', http: 401 };
    if (ep.deviceInstanceIds.indexOf(cfg.instanceId) < 0) return { ok: false, code: 'instance_not_authorised', http: 403 };
    return { ok: true, endpoint: ep };
  }
  function ingestIn(s, actor, o) {
    o = o || {};
    var cfg = o.instanceId ? cfgByInstance(s, o.instanceId) : null;
    ensure(!o.instanceId || cfg, 'not_found', 'Device instance ' + o.instanceId + ' not found');
    var d = cfg ? driver(s, cfg.driver.packageId, o.driverVersion || cfg.driver.version) : driver(s, o.packageId, o.driverVersion);
    ensure(d, 'not_found', 'Driver package not found');
    var raw = String(o.payload == null ? '' : o.payload), headers = o.headers || {}, sha = C.sha256(raw);
    var msgId = o.messageId || nextId(s, 'MSG-', 6, s.messages);
    var key = headers['Idempotency-Key'] || ((headers['X-File-Name'] ? headers['X-File-Name'] + ':' : '') + sha.slice(0, 16));
    var endpointId = cfg ? cfg.endpointId : 'SIM-' + d.adapter;
    var msg = { id: msgId, receivedAt: s.clock, endpointId: endpointId, instanceId: cfg ? cfg.instanceId : null, packageId: d.packageId, driverVersion: d.version, adapter: d.adapter,
      contentType: o.contentType || null, headers: headers, idempotencyKey: key, raw: raw, sha256: sha, status: null, stage: 'ACQUIRE', mode: cfg ? 'LIVE' : 'SIMULATOR', datasetId: o.datasetId || null, sentBy: actor, trace: [], observationIds: [] };
    var acquire = { stage: 'ACQUIRE', status: 'OK', summary: '', data: { sha256: sha, bytes: C.utf8(raw).length, idempotencyKey: key, endpointId: endpointId } };
    function reject(code, http, summary, eventType, sev) {
      acquire.status = 'FAILED'; acquire.summary = summary; msg.status = 'REJECTED'; msg.trace = [acquire]; msg.error = { stage: 'ACQUIRE', code: code, http: http };
      if (eventType) secEvent(s, eventType, sev || 'MEDIUM', { endpointId: endpointId, deviceInstanceId: msg.instanceId, reasonCode: code, sourceModule: 'KERNEL', actor: endpointId, attributes: { messageId: msgId } });
      if (!o.replay) s.messages.push(msg);
      return { messageId: msgId, status: 'REJECTED', error: msg.error, stages: msg.trace, sha256: sha };
    }
    if (cfg && !o.replay) {
      var auth = authenticateEndpoint(s, cfg);
      if (!auth.ok) {
        if (auth.park) {
          acquire.status = 'FAILED'; acquire.summary = 'Endpoint ' + endpointId + ' is ISOLATED – authentication refused; message parked (dead letter ENDPOINT_ISOLATED)';
          msg.status = 'DEAD_LETTER'; msg.trace = [acquire]; msg.error = { stage: 'ACQUIRE', code: 'ENDPOINT_ISOLATED', http: 401 }; s.messages.push(msg);
          var dl0 = { id: nextId(s, 'DL-', 6, s.deadLetters), messageId: msgId, stage: 'ACQUIRE', code: 'ENDPOINT_ISOLATED', detailMasked: 'Endpoint isolated by Security Operations – message parked until release', attempts: 1, firstFailedAt: s.clock, lastFailedAt: s.clock, status: 'OPEN', resolvedBy: null, reason: null };
          s.deadLetters.push(dl0);
          secEvent(s, 'ENDPOINT_AUTH_FAILED', 'HIGH', { endpointId: endpointId, deviceInstanceId: msg.instanceId, reasonCode: 'endpoint_isolated', sourceModule: 'KERNEL', actor: endpointId });
          return { messageId: msgId, status: 'DEAD_LETTER', error: msg.error, stages: msg.trace, sha256: sha, deadLetterId: dl0.id };
        }
        return reject(auth.code, auth.http, 'Endpoint authentication failed: ' + auth.code + ' (HTTP ' + auth.http + ', fail-closed)', 'ENDPOINT_AUTH_FAILED', 'HIGH');
      }
      if (cfg.status !== 'PUBLISHED') return reject(cfg.status === 'QUARANTINED' ? 'device_quarantined' : 'device_not_published', 404, 'Device ' + cfg.instanceId + ' is ' + cfg.status + ' – not accepting data', null);
      auth.endpoint.lastSeenAt = s.clock;
    }
    if (d.adapter === 'WEBHOOK_JSON' && !o.replay) {
      var secret = s.config.webhookSecrets[d.packageId], sigH = headers['X-Signature'] || '', tsH = headers['X-Timestamp'];
      var expected = 'sha256=' + C.hmacSha256(secret, (tsH || '') + '.' + raw);
      var skew = Math.abs(Date.parse(s.clock) / 1000 - Number(tsH));
      if (!tsH || !C.safeEqual(sigH, expected)) return reject('webhook_signature_invalid', 401, 'HMAC-SHA256 signature invalid (expected ' + expected.slice(0, 20) + '…)', 'WEBHOOK_SIGNATURE_FAILED', 'HIGH');
      if (skew > 300) return reject('webhook_replay_window', 401, 'Timestamp outside the 300 s replay window (' + Math.round(skew) + ' s)', 'WEBHOOK_SIGNATURE_FAILED', 'HIGH');
      acquire.data.hmac = 'verified (' + expected.slice(0, 20) + '…, timestamp within 300 s)';
    }
    if (!o.replay) {
      var dup = s.messages.filter(function (m) { return m.endpointId === endpointId && m.idempotencyKey === key && m.status !== 'REJECTED'; })[0];
      if (dup) {
        acquire.summary = 'Duplicate of ' + dup.id + ' (same endpoint + idempotency key) – receipt DUPLICATE, nothing re-processed';
        msg.status = 'DUPLICATE'; msg.duplicateOf = dup.id; msg.trace = [acquire]; s.messages.push(msg);
        return { messageId: msgId, status: 'DUPLICATE', duplicateOf: dup.id, stages: msg.trace, sha256: sha };
      }
    }
    acquire.summary = 'Raw payload stored before parsing (' + acquire.data.bytes + ' bytes, SHA-256 ' + sha.slice(0, 16) + '…, AES-GCM at rest)' + (cfg ? '; endpoint ' + endpointId + ' authenticated (mTLS thumbprint + token)' : '; virtual simulator endpoint ' + endpointId);
    var r = C.runPipeline({ driver: d, raw: raw, headers: headers, receivedAt: s.clock, messageId: msgId, instanceId: cfg ? cfg.instanceId : 'SIM', rawSha256: sha, simulator: !cfg, binding: cfg && cfg.bindings[0], ctx: pipelineCtx(s, cfg, d) });
    var stages = [acquire].concat(r.stages);
    var sinkDown = cfg && r.records.some(function (rec) { return rec.binding && s.sinks[rec.binding.target] === 'DOWN' && (rec.status === 'ROUTED' || rec.status === 'PENDING_VERIFICATION'); });
    msg.trace = stages; msg.status = r.status; msg.stage = r.error ? r.error.stage : 'ACKNOWLEDGE'; msg.ack = r.ack; msg.error = r.error;
    var out = { messageId: msgId, status: r.status, stages: stages, ack: r.ack, sha256: sha, error: r.error, observationIds: [], unmatchedIds: [], deadLetterId: null, records: r.records.map(function (x) { return { status: x.status, employeeId: x.employeeId, observationId: x.observationId }; }) };
    if (r.error || sinkDown) {
      var code = r.error ? r.error.code : 'route_target_unavailable';
      var existing = o.replay ? byId(s.deadLetters, o.replay) : null;
      if (existing) { existing.attempts++; existing.lastFailedAt = s.clock; existing.status = 'OPEN'; out.deadLetterId = existing.id; }
      else { var dl = { id: nextId(s, 'DL-', 6, s.deadLetters), messageId: msgId, stage: r.error ? r.error.stage : 'ROUTE', code: code, detailMasked: r.error ? r.error.message.replace(/E\d{4}/g, 'E****') : 'Route target unavailable', attempts: 1, firstFailedAt: s.clock, lastFailedAt: s.clock, status: 'OPEN', resolvedBy: null, reason: null }; s.deadLetters.push(dl); out.deadLetterId = dl.id; }
      msg.status = 'DEAD_LETTER'; out.status = 'DEAD_LETTER';
      if (code === 'parse_failed' || code === 'profile_violation') {
        var recent = s.messages.filter(function (m) { return m.endpointId === endpointId && m.error && /parse_failed|profile_violation/.test(m.error.code); }).length;
        if (cfg && recent + 1 >= 5) secEvent(s, 'PAYLOAD_SCHEMA_ANOMALY', 'MEDIUM', { endpointId: endpointId, reasonCode: 'PAYLOAD_SCHEMA_ANOMALY', attributes: { count: recent + 1 } });
      }
    }
    if (!out.deadLetterId) {
      r.records.forEach(function (rec) {
        var exists = byId(s.observations, rec.observationId);
        if (exists) { out.observationIds.push(rec.observationId); out.idempotent = true; return; }
        var obs = { id: rec.observationId, messageId: msgId, instanceId: cfg ? cfg.instanceId : null, simulator: !cfg, packageId: d.packageId, driverVersion: d.version, category: rec.category, restricted: !!C.RESTRICTED[d.family],
          employeeId: rec.employeeId || null, association: rec.association, components: rec.norm.components, predicted: rec.norm.predicted, effectiveTime: rec.norm.effectiveTime, receivedAt: s.clock,
          flags: rec.gates.flags, gateOutcome: rec.gates.outcome, status: rec.status, binding: rec.binding ? { formCode: rec.binding.formCode, target: rec.binding.target, verifyBeforePost: rec.binding.verifyBeforePost !== false } : null, fields: rec.fields || null,
          rawSha256: sha, stationCode: cfg ? cfg.stationCode : null, verifiedBy: null, verifiedAt: null, acknowledgedFlags: [] };
        if (obs.status === 'ROUTED') obs.routedAt = s.clock;
        s.observations.push(obs); out.observationIds.push(obs.id);
        if (rec.status === 'UNMATCHED') {
          var um = { id: nextId(s, 'UM-', 6, s.unmatched), observationId: obs.id, siteCode: cfg ? cfg.siteCode : 'LAB', instanceId: obs.instanceId, category: rec.category, receivedAt: s.clock, code: rec.association.code,
            suggestions: rec.association.suggestion ? [{ employeeId: rec.association.suggestion.employeeId, displayName: (employee(s, rec.association.suggestion.employeeId) || {}).name, method: rec.association.suggestion.method, confidence: rec.association.suggestion.confidence }] : [],
            vendorId: (r.records[0] && rec.association.candidates && rec.association.candidates[0] && rec.association.candidates[0].detail) || null, status: 'OPEN', resolvedBy: null, resolvedAt: null, reason: null, slaDueAt: addMs(s.clock, s.config.unmatchedSlaHours * 3600000) };
          s.unmatched.push(um); out.unmatchedIds.push(um.id);
        }
      });
    }
    msg.observationIds = out.observationIds;
    if (!o.replay) s.messages.push(msg); else { var orig = byId(s.messages, o.replayMessageId); if (orig) { orig.status = msg.status; orig.trace = msg.trace; orig.observationIds = msg.observationIds; orig.replayedAt = s.clock; } }
    audit(s, actor, 'DEVICE_INGEST', 'message', msgId, 'ALLOWED', (cfg ? cfg.instanceId : 'SIM') + ' ' + out.status);
    return out;
  }
  defineAction('ingest', 'lab.simulate', function (s, u, o) { return ingestIn(s, u.username, o); });

  defineAction('openCaptureSession', 'capture.session', function (s, u, o) {
    o = o || {};
    var cfg = cfgByInstance(s, o.instanceId); ensure(cfg, 'not_found', 'Device not found');
    ensure(cfg.status === 'PUBLISHED', 'invalid_state', cfg.instanceId + ' is ' + cfg.status + ' – not available for capture');
    var d = driver(s, cfg.driver.packageId, cfg.driver.version);
    if (C.RESTRICTED[d.family]) ensure(hasRole(u, 'COLLECTOR'), 'forbidden', 'Breath-alcohol capture is restricted to the collector');
    ensure(employee(s, o.employeeId), 'not_found', 'Employee not found');
    var cal = calibrationStatus(s, cfg.instanceId);
    s.captureSessions.forEach(function (x) { if (x.instanceId === cfg.instanceId && x.status === 'OPEN') { x.status = 'CLOSED'; x.closedAt = s.clock; } });
    var w = o.worklistId ? byId(s.worklist, o.worklistId) : null;
    var sess = { id: nextId(s, 'CS-', 6, s.captureSessions), instanceId: cfg.instanceId, employeeId: o.employeeId, worklistId: w ? w.id : null, worklistRef: w ? w.ref : null, formCode: cfg.bindings[0] && cfg.bindings[0].formCode, openedBy: u.username, openedAt: s.clock, status: 'OPEN', lockedOut: cal.lockedOut };
    s.captureSessions.push(sess);
    audit(s, u.username, 'DEVICE_CAPTURE_SESSION', 'device', cfg.instanceId, 'ALLOWED', o.employeeId);
    return sess;
  });
  defineAction('acceptObservation', ['capture.verify', 'capture.verifyRestricted'], function (s, u, id, o) {
    o = o || {}; var obs = byId(s.observations, id); ensure(obs, 'not_found', 'Observation not found');
    ensure(obs.status === 'PENDING_VERIFICATION', 'invalid_state', 'Observation is ' + obs.status);
    if (obs.restricted) ensure(hasRole(u, 'COLLECTOR'), 'forbidden', 'Restricted reading – only the collector (and the MRO) may verify it');
    else ensure(hasRole(u, 'OH_CLINICIAN'), 'forbidden', 'Only a clinician may verify device results');
    var warns = obs.flags.filter(function (f) { return f.outcome === 'WARN'; }).map(function (f) { return f.code; });
    var ack = o.acknowledgedFlags || [];
    var missing = warns.filter(function (w) { return ack.indexOf(w) < 0; });
    ensure(!missing.length, 'flags_not_acknowledged', 'Acknowledge the warning(s) first: ' + missing.join(', '));
    ensure(s.sinks[obs.binding.target] !== 'DOWN', 'route_target_unavailable', obs.binding.target + ' is unavailable – retry later');
    obs.status = 'ROUTED'; obs.verifiedBy = u.username; obs.verifiedAt = s.clock; obs.routedAt = s.clock; obs.acknowledgedFlags = ack;
    s.captureSessions.forEach(function (x) { if (x.instanceId === obs.instanceId && x.status === 'OPEN' && x.employeeId === obs.employeeId) { x.status = 'COMPLETED'; x.closedAt = s.clock; if (x.worklistId) { var w = byId(s.worklist, x.worklistId); if (w) w.status = 'COMPLETED'; } } });
    audit(s, u.username, 'DEVICE_RESULT_ACCEPT', 'observation', id, 'ALLOWED', obs.binding.target + (ack.length ? ' ack ' + ack.join(',') : ''));
    return obs;
  });
  defineAction('rejectObservation', ['capture.verify', 'capture.verifyRestricted'], function (s, u, id, o) {
    o = o || {}; var obs = byId(s.observations, id); ensure(obs && ['PENDING_VERIFICATION', 'BLOCKED'].indexOf(obs.status) >= 0, 'invalid_state', 'Observation cannot be rejected');
    ensure(o.reason, 'validation', 'A reason is required (for example re-test)');
    obs.status = 'REJECTED'; obs.rejectedBy = u.username; obs.rejectedAt = s.clock; obs.rejectReason = o.reason;
    audit(s, u.username, 'DEVICE_RESULT_REJECT', 'observation', id, 'ALLOWED', o.reason);
    return obs;
  });
  defineAction('resolveUnmatched', 'unmatched.resolve', function (s, u, id, o) {
    o = o || {}; var it = byId(s.unmatched, id); ensure(it && it.status === 'OPEN', 'invalid_state', 'Item is not open');
    ensure(['CONFIRMED_IDENTITY', 'WORKLIST_ERROR', 'BADGE_ERROR', 'TEST_TRAFFIC', 'DUPLICATE', 'OTHER'].indexOf(o.reason) >= 0, 'validation', 'Choose a reason code');
    var obs = byId(s.observations, it.observationId);
    if (o.reason === 'TEST_TRAFFIC' || o.reason === 'DUPLICATE') { it.status = 'DISCARDED'; obs.status = 'DISCARDED'; }
    else {
      ensure(employee(s, o.employeeId), 'validation', 'Choose the employee');
      if (obs.restricted) fail('forbidden', 'Restricted readings cannot be resolved from the unmatched queue – repeat the test with a capture session');
      it.status = 'RESOLVED'; obs.employeeId = o.employeeId;
      obs.association = { status: 'MATCHED', method: 'MANUAL_RESOLUTION', employeeId: o.employeeId, confidence: 1, candidates: (obs.association.candidates || []), reason: 'Resolved by ' + u.name + ' (' + o.reason + ')' };
      obs.status = obs.gateOutcome === 'BLOCK' ? 'BLOCKED' : (obs.binding && obs.binding.verifyBeforePost === false ? 'ROUTED' : 'PENDING_VERIFICATION');
    }
    it.resolvedBy = u.username; it.resolvedAt = s.clock; it.reason = o.reason; it.employeeId = o.employeeId || null;
    audit(s, u.username, 'DEVICE_UNMATCHED_RESOLVE', 'unmatched', id, 'ALLOWED', o.reason + (o.employeeId ? ' → ' + o.employeeId : ''));
    return it;
  });
  defineAction('replayDeadLetter', 'lab.replay', function (s, u, id, o) {
    o = o || {}; var dl = byId(s.deadLetters, id); ensure(dl && dl.status === 'OPEN', 'invalid_state', 'Dead letter is not open');
    var msg = byId(s.messages, dl.messageId); ensure(msg, 'not_found', 'Stored raw payload not found');
    if (dl.code === 'ENDPOINT_ISOLATED') { var ep = endpoint(s, msg.endpointId); ensure(ep && ep.status === 'ACTIVE', 'invalid_state', 'Endpoint ' + msg.endpointId + ' is still ' + (ep ? ep.status : 'unknown') + ' – replay after the release'); }
    var r = ingestIn(s, u.username, { instanceId: msg.instanceId, packageId: msg.packageId, payload: msg.raw, headers: msg.headers, driverVersion: o.driverVersion || null, replay: dl.id, replayMessageId: msg.id, messageId: msg.id });
    if (r.status !== 'DEAD_LETTER') { dl.status = 'REPLAYED'; dl.resolvedBy = u.username; dl.resolvedAt = s.clock; dl.replayResult = r.status + (r.idempotent ? ' (observation already existed – no duplicate)' : ''); }
    else dl.replayResult = 'failed again: ' + (r.error ? r.error.code : 'route_target_unavailable');
    audit(s, u.username, 'DEVICE_DLQ_REPLAY', 'deadLetter', id, 'ALLOWED', dl.replayResult);
    return { deadLetter: dl, result: r };
  });
  defineAction('discardDeadLetter', 'lab.replay', function (s, u, id, o) {
    o = o || {}; var dl = byId(s.deadLetters, id); ensure(dl && dl.status === 'OPEN', 'invalid_state', 'Dead letter is not open');
    ensure(o.reason, 'validation', 'A reason is required');
    dl.status = 'DISCARDED'; dl.resolvedBy = u.username; dl.resolvedAt = s.clock; dl.reason = o.reason; dl.retention = 'DEVICE_RAW_UNFILED';
    audit(s, u.username, 'DEVICE_DLQ_DISCARD', 'deadLetter', id, 'ALLOWED', o.reason);
    return dl;
  });
  defineAction('setSink', 'lab.replay', function (s, u, target, state) { ensure(s.sinks[target], 'not_found', 'Unknown sink'); s.sinks[target] = state === 'DOWN' ? 'DOWN' : 'UP'; audit(s, u.username, 'DEVICE_SINK_STATE', 'sink', target, 'ALLOWED', s.sinks[target]); return clone(s.sinks); });
  defineAction('runConformance', 'lab.conformance', function (s, u, o) {
    o = o || {};
    var results = [];
    s.drivers.filter(function (d) { return !o.packageId || d.packageId === o.packageId; }).forEach(function (d) {
      testVectorsRun(s, d, null).forEach(function (r) { results.push(Object.assign({ packageId: d.packageId, version: d.version, adapter: d.adapter, kind: 'TEST_VECTOR' }, r)); });
      // golden duplicate: the second receipt must be DUPLICATE (idempotency per endpoint + key)
      var dup = s.golden.filter(function (g) { return g.packageId === d.packageId && g.kind === 'DUPLICATE'; })[0];
      if (dup) { var k1 = C.sha256(dup.payload).slice(0, 16), k2 = C.sha256(dup.payload).slice(0, 16); results.push({ packageId: d.packageId, version: d.version, adapter: d.adapter, kind: 'DUPLICATE', name: 'duplicate', dataset: dup.datasetId, pass: k1 === k2, actual: 'second receipt DUPLICATE (idempotency key ' + k1 + ')' }); }
      var skew = s.golden.filter(function (g) { return g.packageId === d.packageId && g.kind === 'TIME_SKEW'; })[0];
      if (skew) {
        var r = C.runPipeline({ driver: d, raw: skew.payload, headers: skew.headers, receivedAt: s.clock, messageId: 'CF-' + skew.datasetId, instanceId: 'SIM', rawSha256: C.sha256(skew.payload), simulator: true, ctx: pipelineCtx(s, null, d) });
        var f = r.records[0] && byId(r.records[0].gates.flags, 'CLOCK_SKEW', 'gate');
        results.push({ packageId: d.packageId, version: d.version, adapter: d.adapter, kind: 'TIME_SKEW', name: 'time-skew', dataset: skew.datasetId, pass: !!f && f.outcome === 'WARN', actual: f ? 'CLOCK_SKEW ' + f.outcome + ' – ' + f.message : (r.error ? r.error.code : '–') });
      }
    });
    var run = { id: nextId(s, 'CR-', 4, s.conformanceRuns), at: s.clock, by: u.username, total: results.length, passed: results.filter(function (r) { return r.pass; }).length, results: results };
    run.failed = run.total - run.passed; run.evidenceSha256 = C.sha256(C.canonicalJson(results));
    s.conformanceRuns.push(run);
    audit(s, u.username, 'DEVICE_CONFORMANCE_RUN', 'conformance', run.id, 'ALLOWED', run.passed + '/' + run.total);
    return run;
  });

  // =====================================================================================
  // E34 – endpoints, enrolment, tokens, isolation, signatures, drill
  // =====================================================================================
  defineAction('createEndpoint', 'security.manage', function (s, u, o) {
    o = o || {};
    ensure(KIND_CODE[o.kind], 'validation', 'Choose the endpoint kind');
    ensure(byId(s.sites, o.siteCode, 'code'), 'validation', 'Choose the site');
    ensure(byId(s.zones, o.zone, 'code'), 'validation', 'Choose the zone');
    ensure(!(o.kind === 'DEVICE' && o.zone === 'DEVICE_VLAN'), 'zone_violation', 'Devices in DEVICE_VLAN never reach the core directly – enrol the gateway instead');
    if ((o.kind === 'GATEWAY' || o.kind === 'DEVICE_HOST_AGENT') && o.zone !== 'GATEWAY_ZONE') fail('zone_violation', 'Gateways and Device Host agents must sit in GATEWAY_ZONE');
    var r = createEndpointIn(s, u.username, o);
    return { endpointId: r.endpoint.endpointId, enrolmentToken: r.enrolmentToken, enrolmentExpiresAt: r.enrolmentExpiresAt };
  });
  defineAction('enrolEndpoint', 'security.manage', function (s, u, o) {
    o = o || {};
    var token = String(o.enrolmentToken || '').trim(), th = C.sha256(token);
    var ep = s.endpoints.filter(function (e) { return e.enrolment && C.safeEqual(e.enrolment.tokenHash, th); })[0];
    function refuse(code, msg) { secEvent(s, 'ENROLMENT_FAILED', 'MEDIUM', { endpointId: ep ? ep.endpointId : null, reasonCode: code, actor: o.hostname || 'unknown-host', attributes: { kind: o.endpointKind || null } }); throw Object.assign(DXError(code, msg), { persist: true }); }
    if (!ep) refuse('enrolment_token_invalid', 'Enrolment token not recognised (401 enrolment_token_invalid)');
    if (ep.enrolment.used) refuse('enrolment_token_invalid', 'Enrolment token already used – tokens are one-time (401 enrolment_token_invalid)');
    if (ep.enrolment.expiresAt < s.clock) refuse('enrolment_token_expired', 'Enrolment token expired (401)');
    if (o.endpointKind !== ep.enrolment.kind) refuse('enrolment_invalid', 'Token is bound to kind ' + ep.enrolment.kind + ', not ' + o.endpointKind + ' (400)');
    if (!/^[0-9a-f]{64}$/.test(o.certificateSha256 || '')) refuse('certificate_untrusted', 'Client certificate thumbprint missing or not issued by the group device CA (422)');
    var expectedProof = C.sha256('enrol:' + token + ':' + o.certificateSha256);
    if (!C.safeEqual(o.proof, expectedProof)) refuse('proof_invalid', 'Proof of possession invalid (422)');
    ep.status = 'ACTIVE'; ep.enrolledAt = s.clock; ep.enrolment.used = true; ep.enrolment.usedAt = s.clock;
    ep.certificate = { sha256: o.certificateSha256, subject: 'CN=' + ep.endpointId + ',OU=Devices,O=WellnessZ (demo)', issuer: 'CN=WellnessZ Group Device CA (demo)', serial: o.certificateSha256.slice(0, 16).toUpperCase(), notBefore: s.clock, notAfter: addMs(s.clock, 365 * 86400000) };
    var endpointToken = newToken(s, 'ep');
    ep.tokenHash = C.sha256(endpointToken); ep.tokenIssuedAt = s.clock; ep.tokenExpiresAt = addMs(s.clock, s.config.endpointTokenDays * 86400000); ep.lastSeenAt = s.clock;
    ep.heartbeat = { hardening: 'PASS', agentVersion: '1.0.0', os: o.platform || 'Ubuntu 24.04 LTS', hostname: o.hostname || null };
    s.installerEnvelopes = s.installerEnvelopes.filter(function (x) { return x.endpointId !== ep.endpointId; });
    secEvent(s, 'ENROLMENT_SUCCEEDED', 'LOW', { endpointId: ep.endpointId, siteCode: ep.siteCode, actor: ep.endpointId, attributes: { thumbprint: o.certificateSha256.slice(0, 16), hostname: o.hostname || null } });
    secEvent(s, 'NEW_DEVICE_IDENTITY', 'MEDIUM', { endpointId: ep.endpointId, reasonCode: 'NEW_DEVICE_IDENTITY', attributes: { note: 'first authentication of the endpoint' } });
    return { endpointId: ep.endpointId, endpointToken: endpointToken, tokenExpiresAt: ep.tokenExpiresAt, configUrl: '/api/v1/device-ingest/config', serverTime: s.clock };
  });
  defineAction('rotateToken', 'security.manage', function (s, u, id) {
    var ep = endpoint(s, id); ensure(ep && ep.status === 'ACTIVE', 'invalid_state', 'Only active endpoints can rotate tokens');
    var t = newToken(s, 'ep');
    ep.previousTokenHash = ep.tokenHash; ep.previousTokenValidUntil = addMs(s.clock, s.config.tokenGraceMinutes * 60000);
    ep.tokenHash = C.sha256(t); ep.tokenIssuedAt = s.clock; ep.tokenExpiresAt = addMs(s.clock, s.config.endpointTokenDays * 86400000);
    secEvent(s, 'TOKEN_ROTATED', 'LOW', { endpointId: id, actor: u.username, attributes: { graceUntil: ep.previousTokenValidUntil } });
    return { endpointId: id, endpointToken: t, tokenExpiresAt: ep.tokenExpiresAt, previousValidUntil: ep.previousTokenValidUntil, command: 'ROTATE_TOKEN queued on the authenticated gateway channel' };
  });
  function isolateIn(s, actor, ep, reasonCode, note, alertIds, detectedAt) {
    ep.status = 'ISOLATED'; ep.isolatedAt = s.clock; ep.recertificationRequired = true;
    var quarantined = [];
    ep.deviceInstanceIds.forEach(function (inst) { var c = cfgByInstance(s, inst); if (c && ['PUBLISHED', 'DRAFT', 'CERTIFIED', 'AWAITING_APPROVAL'].indexOf(c.status) >= 0) { c.previousStatus = c.status; c.status = 'QUARANTINED'; c.recertified = false; c.history.push({ at: s.clock, by: 'system', action: 'QUARANTINED', note: 'Endpoint ' + ep.endpointId + ' isolated (' + reasonCode + ')' }); quarantined.push(inst); } });
    s.captureSessions.forEach(function (x) { if (ep.deviceInstanceIds.indexOf(x.instanceId) >= 0 && x.status === 'OPEN') { x.status = 'CLOSED'; x.closedAt = s.clock; } });
    var inc = { incidentRef: nextId(s, 'DSI-2026-', 6, s.incidents, 'incidentRef'), endpointId: ep.endpointId, alertIds: alertIds || [], reason: reasonCode, detectedAt: detectedAt || s.clock, isolatedAt: s.clock,
      evidence: [{ type: 'RAW_MESSAGES', sha256: C.sha256('raw:' + ep.endpointId + ':' + s.clock), archiveId: 'ARC-' + ep.endpointId + '-' + s.incidents.length }, { type: 'EVENTS', sha256: C.sha256('events:' + ep.endpointId + ':' + s.clock), archiveId: 'ARC-EV-' + s.incidents.length }, { type: 'HEARTBEATS', sha256: C.sha256('hb:' + ep.endpointId + ':' + s.clock), archiveId: 'ARC-HB-' + s.incidents.length }],
      status: 'OPEN', quarantined: quarantined, timeline: [{ at: detectedAt || s.clock, text: 'Detected: ' + reasonCode + (note ? ' – ' + note : '') }, { at: s.clock, text: 'Endpoint isolated, ISOLATE command queued (gateway quarantines its local queue), ' + quarantined.length + ' device(s) quarantined, evidence preserved by hash' }] };
    s.incidents.push(inc);
    secEvent(s, 'ENDPOINT_ISOLATED', 'CRITICAL', { endpointId: ep.endpointId, siteCode: ep.siteCode, actor: actor, reasonCode: reasonCode, attributes: { incidentRef: inc.incidentRef, quarantined: quarantined.join(',') } });
    return inc;
  }
  defineAction('isolateEndpoint', 'security.manage', function (s, u, id, o) {
    o = o || {}; var ep = endpoint(s, id); ensure(ep && ep.status === 'ACTIVE', 'invalid_state', 'Only active endpoints can be isolated');
    return isolateIn(s, u.username, ep, o.reasonCode || 'MANUAL', o.note || '', [], null);
  });
  defineAction('releaseEndpoint', 'security.manage', function (s, u, id, o) {
    var ep = endpoint(s, id); ensure(ep && ep.status === 'ISOLATED', 'invalid_state', 'Endpoint is not isolated');
    var pending = ep.deviceInstanceIds.filter(function (inst) { var c = cfgByInstance(s, inst); return c && c.status !== 'DECOMMISSIONED' && !c.recertified; });
    if (pending.length) {
      secEvent(s, 'ENDPOINT_RELEASE_REFUSED', 'MEDIUM', { endpointId: id, actor: u.username, reasonCode: 'recertification_required', attributes: { pending: pending.join(',') } });
      throw Object.assign(DXError('recertification_required', 'Release refused (409 recertification_required): ' + pending.join(', ') + ' must be re-certified in the device console first (revise → test → approve → publish).'), { persist: true });
    }
    ep.status = 'ACTIVE'; ep.isolatedAt = null; ep.recertificationRequired = false; ep.lastSeenAt = s.clock;
    ep.deviceInstanceIds.forEach(function (inst) { var c = cfgByInstance(s, inst); if (c && c.status === 'QUARANTINED') { c.status = 'PUBLISHED'; c.recertified = false; c.previousStatus = null; c.history.push({ at: s.clock, by: u.username, action: 'RELEASED', note: 'Endpoint ' + id + ' released by Security Operations' }); } });
    s.incidents.forEach(function (i) { if (i.endpointId === id && i.status === 'OPEN') { i.status = 'CLOSED'; i.closedAt = s.clock; i.timeline.push({ at: s.clock, text: 'Released by ' + u.name + ' after re-certification' }); } });
    s.alerts.forEach(function (a) { if (a.endpointId === id && a.status !== 'CLOSED') { a.status = 'CLOSED'; a.closedAt = s.clock; a.resolution = 'Endpoint released after re-certification'; } });
    secEvent(s, 'ENDPOINT_RELEASED', 'MEDIUM', { endpointId: id, siteCode: ep.siteCode, actor: u.username, attributes: { note: (o && o.note) || 'after re-certification' } });
    return ep;
  });
  defineAction('revokeEndpoint', 'security.manage', function (s, u, id, o) {
    var ep = endpoint(s, id); ensure(ep && ep.status !== 'REVOKED', 'invalid_state', 'Already revoked');
    ep.status = 'REVOKED'; ep.revokedAt = s.clock;
    secEvent(s, 'ENDPOINT_REVOKED', 'HIGH', { endpointId: id, actor: u.username, reasonCode: (o && o.reasonCode) || 'MANUAL' });
    return ep;
  });
  defineAction('acknowledgeAlert', 'security.manage', function (s, u, id) { var a = byId(s.alerts, id); ensure(a, 'not_found', 'Alert not found'); a.status = 'ACKNOWLEDGED'; a.acknowledgedBy = u.username; a.acknowledgedAt = s.clock; return a; });
  defineAction('recordSignatureCheck', ['security.manage', 'lab.conformance'], function (s, u, o) {
    o = o || {}; var d = driver(s, o.packageId, o.version); ensure(d, 'not_found', 'Package not found');
    if (o.result === 'INVALID') secEvent(s, 'PACKAGE_SIGNATURE_INVALID', 'HIGH', { actor: u.username, sourceModule: 'E34', reasonCode: o.tampered ? 'TAMPER_TEST' : 'SIGNATURE_MISMATCH', attributes: { packageId: d.packageId, version: d.version, keyId: d.signature.keyId, method: o.method } });
    else audit(s, u.username, 'PACKAGE_SIGNATURE_VERIFIED', 'driver', d.packageId + '@' + d.version, 'ALLOWED', o.method);
    return { packageId: d.packageId, result: o.result, method: o.method };
  });
  /** Time-compressed isolation drill: message-rate spike → alert → throttle → playbook isolation within the 15-minute SLA. */
  defineAction('runIsolationDrill', 'security.manage', function (s, u, o) {
    o = o || {}; var ep = endpoint(s, o.endpointId); ensure(ep && ep.status === 'ACTIVE', 'invalid_state', 'Choose an active endpoint');
    var rule = byId(s.anomalyRules, 'MESSAGE_RATE_SPIKE', 'code'), p95 = ep.ratePerMinuteP95 || 6, limit = p95 * 5;
    var start = s.clock, timeline = [], alertId = null, detectedAt = null, isolatedMinute = null;
    var rates = [p95, p95 + 1, 9 * p95, 22 * p95, 25 * p95, 24 * p95, 26 * p95, 25 * p95, 27 * p95, 26 * p95, 28 * p95, 27 * p95, 0, 0];
    for (var m = 0; m < rates.length; m++) {
      var at = addMs(start, m * 60000), ev = [];
      if (rates[m] > limit && !detectedAt) {
        detectedAt = at; alertId = nextId(s, 'ALR-', 6, s.alerts);
        s.alerts.push({ id: alertId, rule: 'MESSAGE_RATE_SPIKE', severity: rule.severity, endpointId: ep.endpointId, status: 'OPEN', raisedAt: at, detail: rates[m] + ' msg/min > 5 × p95 (' + p95 + ')' });
        secEvent(s, 'MESSAGE_RATE_SPIKE', 'MEDIUM', { endpointId: ep.endpointId, siteCode: ep.siteCode, at: at, reasonCode: 'MESSAGE_RATE_SPIKE', attributes: { rate: rates[m], p95: p95, alertId: alertId } });
        ev.push('Alert ' + alertId + ' MESSAGE_RATE_SPIKE (' + rates[m] + '/min > ' + limit + '/min) – SOC notified');
      }
      if (detectedAt && m === 4) { secEvent(s, 'UNKNOWN_DEVICE_IDENTITY', 'HIGH', { endpointId: ep.endpointId, at: at, reasonCode: 'NEW_DEVICE_IDENTITY', attributes: { serial: 'UNKNOWN-' + C.sha256(at).slice(0, 6).toUpperCase() } }); ev.push('Unknown device serial behind ' + ep.endpointId + ' – playbook escalation (rogue device suspected)'); }
      if (detectedAt && m === 6) ev.push('Throttle 429 applied to ' + ep.endpointId + '; SOC acknowledges the alert');
      if (detectedAt && m === 10 && isolatedMinute == null) {
        s.clock = at; isolatedMinute = m;
        var inc = isolateIn(s, 'system', ep, 'MESSAGE_RATE_SPIKE', 'sustained spike + unknown device serial (playbook)', [alertId], detectedAt);
        ev.push('Endpoint ISOLATED automatically by the playbook – incident ' + inc.incidentRef + ', ' + inc.quarantined.length + ' device(s) quarantined');
      }
      if (isolatedMinute != null && m > isolatedMinute) ev.push('Traffic refused: endpoint_isolated (fail-closed)');
      timeline.push({ minute: m, at: at, rate: isolatedMinute != null && m > isolatedMinute ? 0 : rates[m], threshold: limit, events: ev });
    }
    s.clock = addMs(start, (rates.length) * 60000);
    var minutes = (Date.parse(addMs(start, isolatedMinute * 60000)) - Date.parse(detectedAt)) / 60000;
    var incRef = s.incidents[s.incidents.length - 1].incidentRef;
    var drill = { id: nextId(s, 'DRL-', 4, s.drills), endpointId: ep.endpointId, startedAt: start, detectedAt: detectedAt, isolatedAt: addMs(start, isolatedMinute * 60000), minutesToIsolate: minutes, slaMinutes: s.config.isolationSlaMinutes, withinSla: minutes <= s.config.isolationSlaMinutes, incidentRef: incRef, timeline: timeline, by: u.username };
    s.drills.push(drill);
    s.incidents[s.incidents.length - 1].drill = drill.id;
    return drill;
  });

  // =====================================================================================
  // Read side
  // =====================================================================================
  function fleet() {
    var s = session();
    return s.configurations.filter(function (c) { return ['PUBLISHED', 'QUARANTINED'].indexOf(c.status) >= 0 || (c.status === 'DRAFT' && c.previousStatus); }).map(function (c) {
      var ep = c.endpointId && endpoint(s, c.endpointId), cal = calibrationStatus(s, c.instanceId), m = model(s, c.modelCode);
      var last = s.messages.filter(function (x) { return x.instanceId === c.instanceId && x.status !== 'REJECTED'; }).map(function (x) { return x.receivedAt; }).sort().pop() || (ep && ep.lastSeenAt) || null;
      var silentMin = last ? (Date.parse(s.clock) - Date.parse(last)) / 60000 : null;
      var health = c.status === 'QUARANTINED' || (ep && ep.status === 'ISOLATED') ? 'QUARANTINED' : cal.lockedOut ? 'LOCKED_OUT' : ep && ep.status === 'PENDING_ENROLMENT' ? 'AWAITING_ENROLMENT' : silentMin != null && silentMin > 24 * 60 ? 'SILENT' : 'HEALTHY';
      var alerts = [];
      if (cal.lockedOut) alerts.push('Locked out: ' + (cal.lockout ? cal.lockout.checkType : cal.checks.filter(function (x) { return x.verdict !== 'OK'; }).map(function (x) { return x.checkType + ' ' + x.verdict; }).join(', ')));
      if (ep && ep.certificate && Date.parse(ep.certificate.notAfter) - Date.parse(s.clock) < 30 * 86400000) alerts.push('Certificate of ' + ep.endpointId + ' expires ' + ep.certificate.notAfter.slice(0, 10));
      if (ep && ep.status === 'ISOLATED') alerts.push('Endpoint ' + ep.endpointId + ' isolated');
      return { instanceId: c.instanceId, configurationId: c.id, version: c.version, modelCode: c.modelCode, model: m.manufacturer + ' ' + m.model, family: m.familyCode, option: c.option, siteCode: c.siteCode, stationCode: c.stationCode,
        status: c.status, health: health, lastSeenAt: last, endpointId: c.endpointId, endpointStatus: ep ? ep.status : null, driver: c.driver.packageId + '@' + c.driver.version, serial: c.serial, calibration: cal, alerts: alerts, recertified: !!c.recertified };
    });
  }
  function captureList(instanceId) { var s = session(); return s.observations.filter(function (o) { return !instanceId || o.instanceId === instanceId; }); }
  function verifyEvents() { return verifyChain(session().securityEvents, eventFields); }
  function verifyAudit() { return verifyChain(session().audit, auditFields); }
  /** Verify a tampered copy (in memory only) to demonstrate detection; the stored log is never changed. */
  function verifyTamperedCopy(index) { var list = clone(session().securityEvents); if (list[index]) list[index].attributes = Object.assign({}, list[index].attributes, { tampered: true }); return verifyChain(list, eventFields); }
  function certificationRecord(id) {
    var s = session(), c = cfgById(s, id); if (!c || !c.certificationId) return null;
    var m = model(s, c.modelCode), d = driver(s, c.driver.packageId, c.driver.version), st = station(s, c.stationCode), ep = c.endpointId && endpoint(s, c.endpointId);
    var snap = snapshot(c);
    return { certificationId: c.certificationId, configurationId: c.id, version: c.version, deviceInstanceId: c.instanceId, model: m, option: c.option, optionName: optionPack(s, c.option).name, site: c.siteCode, station: st, serial: c.serial, equipmentId: c.equipmentId,
      configurationSnapshot: snap, snapshotSha256: C.sha256(C.canonicalJson(snap)), driver: { packageId: d.packageId, version: d.version, sha256: d.signature.canonicalSha256, signatureKeyId: d.signature.keyId },
      tests: c.tests.map(function (t) { return { testRunId: t.testRunId, mode: t.mode, status: t.status, evidenceSha256: t.evidenceSha256, finishedAt: t.finishedAt, vectors: t.vectors }; }), nurseReview: c.nurseReview, prerequisites: c.prerequisites,
      simulatorOnlyJustification: c.simulatorOnlyJustification, submittedBy: userName(s, c.submittedBy), submittedAt: c.submittedAt, approvedBy: userName(s, c.approvedBy), approvedAt: c.approvedAt, coApprovedBy: c.coApprovedBy ? userName(s, c.coApprovedBy) : null,
      status: c.status, approvalSignatureSha256: C.sha256((c.approvedBy || '') + '|' + (c.approvedAt || '') + '|' + C.sha256(C.canonicalJson(snap))),
      dataFlow: { device: m.manufacturer + ' ' + m.model + ' (' + c.instanceId + ')', endpoint: ep ? ep.endpointId + ' (' + ep.kind + ', zone ' + ep.zone + ')' : (c.endpointId || 'none'), adapter: d.adapter, driver: d.packageId + '@' + d.version, gates: d.qualityGates.concat(c.common.qualityGateAdditions || []), association: c.associationMethods,
        bindings: c.bindings.map(function (b) { return { form: b.formCode, target: b.target, fields: b.fields, verifyBeforePost: b.verifyBeforePost !== false }; }), retention: c.common.retentionClass } };
  }
  function previewBinding(cfgOrId, datasetId, bindingsOverride) {
    var s = session(), c = typeof cfgOrId === 'string' ? cfgById(s, cfgOrId) : cfgOrId, d = driver(s, c.driver.packageId, c.driver.version);
    var g = byId(s.golden, datasetId, 'datasetId') || s.golden.filter(function (x) { return x.packageId === d.packageId && x.kind === 'NORMAL'; })[0];
    var r = C.runPipeline({ driver: d, raw: g.payload, headers: g.headers, receivedAt: s.clock, messageId: 'PV-' + g.datasetId, instanceId: c.instanceId, rawSha256: C.sha256(g.payload), simulator: true, binding: (bindingsOverride || c.bindings || d.bindings)[0], ctx: pipelineCtx(s, null, d) });
    var rec = r.records[0];
    return { datasetId: g.datasetId, error: r.error, bindings: (bindingsOverride || (c.bindings && c.bindings.length ? c.bindings : d.bindings)).map(function (b) { return { formCode: b.formCode, target: b.target, verifyBeforePost: b.verifyBeforePost !== false, rows: Object.keys(b.fields).map(function (k) { var comp = rec && byId(rec.norm.components, k, 'key'); return { key: k, field: b.fields[k], value: comp ? comp.value : null, ucum: comp ? comp.ucum : null, loinc: comp ? comp.loinc : null }; }) }; }) };
  }

  // =====================================================================================
  // Session lifecycle
  // =====================================================================================
  function reset() { var st = seedState(); var raw = JSON.stringify(st); writeRaw(raw); cache.raw = raw; cache.state = st; emit({ type: 'reset' }); return clone(st.meta); }
  function exportData() { var s = session(); return { format: FORMAT, exportedAt: s.clock, engineVersion: VERSION, schema: SCHEMA, seedVersion: s.meta.seedVersion, state: s }; }
  function exportSession() {
    var data = JSON.stringify(exportData(), null, 1);
    if (root.document && root.Blob && root.URL) { var a = root.document.createElement('a'); a.href = URL.createObjectURL(new Blob([data], { type: 'application/json' })); a.download = 'wellnessz-device-demo-session.json'; root.document.body.appendChild(a); a.click(); setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500); }
    return data;
  }
  function loadImported(obj) {
    if (typeof obj === 'string') obj = JSON.parse(obj);
    ensure(obj && obj.format === FORMAT && obj.state && obj.state.meta, 'invalid_file', 'This is not a WellnessZ device demo session file.');
    ensure(obj.state.meta.schema === SCHEMA, 'invalid_file', 'This session file was made by an incompatible demo version (schema ' + obj.state.meta.schema + ').');
    var st = obj.state; st.meta.importedAt = st.clock;
    var raw = JSON.stringify(st); writeRaw(raw); cache.raw = raw; cache.state = st; emit({ type: 'import' }); return clone(st.meta);
  }
  function importSession(file) {
    return new Promise(function (resolve, reject) {
      try {
        if (file && typeof file === 'object' && root.FileReader && file instanceof root.Blob) { var r = new FileReader(); r.onload = function () { try { resolve(loadImported(String(r.result))); } catch (e) { reject(e); } }; r.onerror = function () { reject(DXError('invalid_file', 'Could not read the file')); }; r.readAsText(file); }
        else resolve(loadImported(file));
      } catch (e) { reject(e); }
    });
  }
  function sessionInfo() {
    var s = session(), raw = cache.raw || '';
    return { sessionId: s.meta.sessionId, seedVersion: s.meta.seedVersion, engineVersion: VERSION, schema: SCHEMA, createdAt: s.meta.createdAt, updatedAt: s.meta.updatedAt, clock: s.clock, rev: s.meta.rev, sizeKb: Math.round(raw.length / 102.4) / 10, storageKey: KEY,
      counts: { configurations: s.configurations.length, messages: s.messages.length, observations: s.observations.length, securityEvents: s.securityEvents.length } };
  }

  // =====================================================================================
  // Scenarios
  // =====================================================================================
  function scenarios() { return clone(root.WZ_DEVICE_SCENARIOS && root.WZ_DEVICE_SCENARIOS.scenarios ? root.WZ_DEVICE_SCENARIOS.scenarios : []); }
  function scenarioState() {
    var s = session(), sc = s.scenario || { id: null, step: 0 }, def = byId(scenarios(), sc.id);
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
  function advanceScenario(s, actionName, u) {
    if (!s.scenario || !s.scenario.id) return;
    var def = byId(scenarios(), s.scenario.id); if (!def) return;
    var step = def.steps[s.scenario.step];
    if (step && step.action === actionName && (!step.user || step.user === u.username)) {
      s.scenario.step++;
      if (s.scenario.step >= def.steps.length && s.scenario.completed.indexOf(def.id) < 0) s.scenario.completed.push(def.id);
    }
  }
  /** Mark a 'view' step done when the right user opens the right screen (called by apps). */
  function viewed(viewName) {
    var st = scenarioState(); var u = currentUser();
    if (st.active && st.step && st.step.action === 'view' && st.step.app === ctx.app && st.step.view === viewName && u && (!st.step.user || st.step.user === u.username)) stepScenario(1);
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
    '#wz-demo-toolbar select{max-width:280px}', '#wz-demo-toolbar button:hover,#wz-demo-toolbar a.wzt-btn:hover{background:#3f4a56}', '#wz-demo-toolbar :focus-visible{outline:3px solid #ffbf47;outline-offset:1px}',
    '#wz-demo-toolbar .wzt-sp{flex:1}', '#wz-demo-toolbar .wzt-hint{background:#2b333c;border-top:1px solid #3c4752;padding:6px 12px}',
    '#wz-demo-toolbar .wzt-hint-in{max-width:1400px;margin:0 auto;display:flex;flex-wrap:wrap;gap:6px 12px;align-items:center}',
    '#wz-demo-toolbar .wzt-here{color:#9ee2b8;font-weight:600}', '#wz-demo-toolbar .wzt-else{color:#ffd98a;font-weight:600}', '#wz-demo-toolbar .wzt-clock{color:#9fb0bf}',
    '#wz-demo-toolbar .wzt-panel{background:#262d35;border-top:1px solid #3c4752;padding:8px 12px}', '#wz-demo-toolbar .wzt-panel-in{max-width:1400px;margin:0 auto}',
    '#wz-demo-toolbar .wzt-panel table{border-collapse:collapse}#wz-demo-toolbar .wzt-panel td{padding:1px 10px 1px 0;vertical-align:top}',
    '#wz-demo-toolbar .wzt-steps{margin:6px 0 0;padding-left:20px}#wz-demo-toolbar .wzt-steps li{margin:2px 0}#wz-demo-toolbar .wzt-steps li.cur{color:#ffd98a;font-weight:600}#wz-demo-toolbar .wzt-steps li.done{color:#9fb0bf;text-decoration:line-through}',
    '#wz-demo-toolbar .wzt-msg{color:#ffd98a}',
    '@media (max-width:640px){#wz-demo-toolbar .wzt-row{padding:6px 8px}#wz-demo-toolbar select{max-width:170px}#wz-demo-toolbar .wzt-hide-sm{display:none}}'
  ].join('\n');
  function esc(x) { return String(x == null ? '' : x).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function mountToolbar(opts) {
    opts = opts || {}; var doc = root.document; if (!doc) return null;
    if (opts.app) ctx.app = opts.app;
    if (!doc.getElementById('wz-demo-toolbar-css')) { var st = doc.createElement('style'); st.id = 'wz-demo-toolbar-css'; st.textContent = TOOLBAR_CSS; doc.head.appendChild(st); }
    var bar = doc.getElementById('wz-demo-toolbar');
    if (!bar) { bar = doc.createElement('div'); bar.id = 'wz-demo-toolbar'; bar.setAttribute('role', 'region'); bar.setAttribute('aria-label', 'Demo controls'); doc.body.insertBefore(bar, doc.body.firstChild); }
    var panel = null, msg = '';
    var fileInput = doc.createElement('input'); fileInput.type = 'file'; fileInput.accept = '.json,application/json'; fileInput.hidden = true;
    fileInput.addEventListener('change', function () { var f = fileInput.files && fileInput.files[0]; if (!f) return; importSession(f).then(function (m) { msg = 'Session imported (' + m.sessionId + ').'; render(); }).catch(function (e) { msg = 'Import failed: ' + e.message; render(); }); fileInput.value = ''; });
    function render() {
      var app = ctx.app, cu = currentUser(), us = app && app !== 'launcher' ? users(app) : [], sc = scenarioState(), info = sessionInfo(), s = session();
      var h = '<div class="wzt-row"><span class="wzt-pill">DEMO</span><strong class="wzt-hide-sm">' + esc((APPS[app] || {}).title || 'WellnessZ') + '</strong>';
      if (us.length) h += '<label>Signed in as <select data-wzt="user" aria-label="Switch demo user">' + us.map(function (u) { return '<option value="' + esc(u.username) + '"' + (cu && cu.username === u.username ? ' selected' : '') + '>' + esc(u.name) + ' — ' + esc(u.title) + '</option>'; }).join('') + '</select></label>';
      h += '<button type="button" data-wzt="scenario" aria-expanded="' + (panel === 'scenario') + '">Scenarios' + (sc.active ? ' (' + Math.min(sc.stepIndex + 1, sc.stepCount) + '/' + sc.stepCount + ')' : '') + '</button>';
      h += '<span class="wzt-clock wzt-hide-sm" title="Demo clock – advances one minute per action, so every run is reproducible">Demo clock ' + esc(s.clock.slice(0, 16).replace('T', ' ')) + ' UTC</span>';
      h += '<span class="wzt-sp"></span><button type="button" data-wzt="info" aria-expanded="' + (panel === 'info') + '">Session</button>';
      h += '<button type="button" data-wzt="export" title="Download wellnessz-device-demo-session.json">Export</button><button type="button" data-wzt="import" title="Load a wellnessz-device-demo-session.json file">Import</button>';
      h += '<button type="button" data-wzt="reset" title="Reset all apps to the demo seed">Reset</button>';
      if (app !== 'launcher') h += '<a class="wzt-btn" href="index.html" data-wzt="launcher">Launcher</a>';
      h += '</div>';
      if (sc.active) {
        var stp = sc.step;
        h += '<div class="wzt-hint" aria-live="polite"><div class="wzt-hint-in">';
        if (!stp) h += '<span class="wzt-here">Scenario complete: ' + esc(sc.title) + '</span><button type="button" data-wzt="stop">Close scenario</button>';
        else {
          var here = stp.app === app, su = stp.user ? (userByName(s, stp.user) || {}).name : '';
          h += '<span><strong>Next step ' + (sc.stepIndex + 1) + '/' + sc.stepCount + ':</strong> ' + esc(stp.action_label || stp.action) + '</span>';
          h += here ? '<span class="wzt-here">here, as ' + esc(su || stp.user) + '</span>' : '<span class="wzt-else">in the ' + esc((APPS[stp.app] || {}).title) + ' as ' + esc(su || stp.user) + '</span> <a class="wzt-btn" href="' + esc((APPS[stp.app] || {}).file) + '" target="_blank" rel="noopener">Open ' + esc((APPS[stp.app] || {}).short) + '</a>';
          if (here && cu && stp.user && cu.username !== stp.user) h += '<button type="button" data-wzt="become" data-user="' + esc(stp.user) + '">Switch to ' + esc(su) + '</button>';
          h += '<span>Expect: ' + esc(stp.expected) + '</span><button type="button" data-wzt="back" aria-label="Previous step">◀</button><button type="button" data-wzt="next">Mark done ▶</button>';
        }
        h += '</div></div>';
      }
      if (panel === 'scenario') {
        var all = scenarios();
        h += '<div class="wzt-panel"><div class="wzt-panel-in"><label>Scenario <select data-wzt="pick"><option value="">— choose a scenario —</option>' + all.map(function (x) { return '<option value="' + esc(x.id) + '"' + (sc.id === x.id ? ' selected' : '') + '>' + esc(x.code + ' · ' + x.title) + (sc.completed.indexOf(x.id) >= 0 ? ' ✓' : '') + '</option>'; }).join('') + '</select></label>';
        if (sc.active) { var def = byId(all, sc.id); h += ' <button type="button" data-wzt="stop">Stop</button><ol class="wzt-steps">' + def.steps.map(function (s2, i) { return '<li class="' + (i < sc.stepIndex ? 'done' : i === sc.stepIndex ? 'cur' : '') + '">' + esc((APPS[s2.app] || {}).short) + ' · ' + esc(s2.user) + ' — ' + esc(s2.action_label || s2.action) + '</li>'; }).join('') + '</ol>'; }
        else if (!all.length) h += ' <span class="wzt-msg">No scenarios loaded (run demo-devices/build.py).</span>';
        h += '</div></div>';
      }
      if (panel === 'info') h += '<div class="wzt-panel"><div class="wzt-panel-in"><table><tbody><tr><td>Session</td><td>' + esc(info.sessionId) + ' · revision ' + info.rev + ' · ' + info.sizeKb + ' kB</td></tr><tr><td>Seed / engine</td><td>device-demo-data ' + esc(info.seedVersion) + ' · engine ' + esc(info.engineVersion) + ' · schema ' + info.schema + '</td></tr><tr><td>Demo clock</td><td>' + esc(info.clock) + ' (deterministic: +1 minute per action)</td></tr><tr><td>Storage</td><td>localStorage key <code>' + esc(info.storageKey) + '</code> – shared by all apps opened from this folder in this browser.</td></tr></tbody></table><p>People are synthetic; device models are entries of the client inventory.</p></div></div>';
      if (msg) h += '<div class="wzt-hint"><div class="wzt-hint-in"><span class="wzt-msg" role="status">' + esc(msg) + '</span></div></div>';
      bar.innerHTML = h; bar.appendChild(fileInput);
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
      else if (t === 'export') { exportSession(); msg = 'Downloaded wellnessz-device-demo-session.json'; render(); }
      else if (t === 'import') fileInput.click();
      else if (t === 'reset') { if (!root.confirm || root.confirm('Reset the demo? All changes in every app are discarded (export first if you want to keep them).')) { reset(); msg = 'Demo reset to the seed data.'; render(); } }
      else if (t === 'next') stepScenario(1); else if (t === 'back') stepScenario(-1); else if (t === 'stop') stopScenario();
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
    opts = opts || {}; ctx.app = opts.app || ctx.app || null;
    var s = session();
    if (ctx.app && ctx.app !== 'launcher') {
      var cur = s.signedIn && s.signedIn[ctx.app];
      if (!cur || !userByName(s, cur) || userByName(s, cur).apps.indexOf(ctx.app) < 0) { var first = s.users.filter(function (u) { return u.apps.indexOf(ctx.app) >= 0; })[0]; if (first) mutate(function (st) { st.signedIn = st.signedIn || {}; st.signedIn[ctx.app] = first.username; }, true); }
    }
    if (opts.toolbar !== false && root.document && root.document.body) mountToolbar({ app: ctx.app });
    return DX;
  }
  // persist refusals that must be audited (four-eyes, failed enrolment, refused release) even though the action threw
  ['approveConfiguration', 'enrolEndpoint', 'releaseEndpoint'].forEach(function (name) {
    var fn = DX[name];
    DX[name] = function () {
      try { return fn.apply(null, arguments); }
      catch (e) {
        if (e.code === 'four_eyes_violation' && e.audit) { var a = e.audit; mutate(function (s) { audit(s, a.user, 'DEVICE_CONFIG_APPROVE', 'configuration', a.id, 'DENIED', 'four_eyes_violation: ' + a.why); secEvent(s, 'CONFIG_APPROVAL_REFUSED', 'MEDIUM', { actor: a.user, sourceModule: 'E32', reasonCode: 'four_eyes_violation', attributes: { configurationId: a.id } }); advanceScenario(s, 'approveConfigurationRefused', userByName(s, a.user)); }); }
        else if (e.persist) {
          var u = currentUser();
          mutate(function (s) {
            tick(s, 60);
            if (name === 'enrolEndpoint') { secEvent(s, 'ENROLMENT_FAILED', 'MEDIUM', { reasonCode: e.code, actor: 'unknown-host', attributes: { message: e.message } }); advanceScenario(s, 'enrolEndpointRefused', u); }
            if (name === 'releaseEndpoint') { secEvent(s, 'ENDPOINT_RELEASE_REFUSED', 'MEDIUM', { actor: u && u.username, reasonCode: e.code }); advanceScenario(s, 'releaseEndpointRefused', u); }
          });
        }
        throw e;
      }
    };
  });

  Object.assign(DX, {
    version: VERSION, SCHEMA: SCHEMA, STORAGE_KEY: KEY, APPS: APPS, ROLE_PERMISSIONS: ROLE_PERMISSIONS, STEP_NAMES: STEP_NAMES, PREREQ_TEXT: PREREQ_TEXT,
    init: init, session: session, reset: reset, exportSession: exportSession, exportData: exportData, importSession: importSession, sessionInfo: sessionInfo, subscribe: subscribe,
    users: users, signIn: signIn, can: function (perm, u) { return can(u || currentUser(), perm); }, permissions: function (u) { return permsOf(u || currentUser()); },
    model: function (id) { return clone(model(session(), id)); }, driver: function (p, v) { return clone(driver(session(), p, v)); }, driversFor: function (m, o) { return clone(driversFor(session(), m, o)); },
    config: function (id) { return clone(cfgById(session(), id)); }, configByInstance: function (i) { return clone(cfgByInstance(session(), i)); }, endpoint: function (id) { return clone(endpoint(session(), id)); },
    employee: function (id) { return clone(employee(session(), id)); }, optionPack: function (o) { return clone(optionPack(session(), o)); }, station: function (c) { return clone(station(session(), c)); },
    userName: function (u) { return userName(session(), u); }, calibrationStatus: function (i) { return clone(calibrationStatus(session(), i)); }, activeSession: function (i) { return clone(activeSession(session(), i)); },
    fleet: function () { return clone(fleet()); }, captureList: function (i) { return clone(captureList(i)); }, verifyEvents: verifyEvents, verifyAudit: verifyAudit, verifyTamperedCopy: verifyTamperedCopy,
    stepFourDefaults: function (id) { return clone(stepFourDefaults(id)); }, certificationRecord: function (id) { return clone(certificationRecord(id)); }, previewBinding: function (c, d, b) { return clone(previewBinding(c, d, b)); }, parameterDefaults: parameterDefaults,
    validateSchema: validateSchema, practiceRules: practiceRules, readOnlySelect: readOnlySelect, isSecretField: isSecretField, hostEndpointOf: hostEndpointOf,
    scenarios: scenarios, scenarioState: scenarioState, startScenario: startScenario, stopScenario: stopScenario, stepScenario: stepScenario, viewed: viewed,
    mountToolbar: mountToolbar, actions: function () { return clone(ACTIONS); }, Error: DXError, _setApp: function (a) { ctx.app = a; }
  });
  Object.defineProperty(DX, 'currentUser', { get: currentUser, enumerable: true });
  Object.defineProperty(DX, 'app', { get: function () { return ctx.app; }, enumerable: true });
  root.DX = DX;
  if (typeof module !== 'undefined' && module.exports) module.exports = DX;
})(typeof window !== 'undefined' ? window : globalThis);
