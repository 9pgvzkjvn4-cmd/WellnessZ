/*
 * WellnessZ DEVICE DEMO PACK – app kit (window.DXUI): the shell, routing, re-render rules, status badges,
 * the JSON-Schema form renderer used by the provisioning wizard, and the pipeline visualiser.
 * Builds on window.UI (shared/ui.js) and window.DX (shared/engine.js).
 */
(function (root) {
  'use strict';
  var UI = root.UI, h = UI.h, raw = UI.raw;
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  /** Deterministic UTC date-time (the demo clock is UTC). */
  function dt(iso) { if (!iso) return '—'; var d = new Date(iso); if (isNaN(d)) return iso; return d.getUTCDate() + ' ' + MON[d.getUTCMonth()] + ' ' + d.getUTCFullYear() + ', ' + String(d.getUTCHours()).padStart(2, '0') + ':' + String(d.getUTCMinutes()).padStart(2, '0'); }
  function d(iso) { if (!iso) return '—'; var x = new Date(iso.length <= 10 ? iso + 'T00:00:00Z' : iso); return x.getUTCDate() + ' ' + MON[x.getUTCMonth()] + ' ' + x.getUTCFullYear(); }
  function short(hash, n) { return hash ? String(hash).slice(0, n || 12) + '…' : '—'; }

  var TONE = {
    config: { DRAFT: 'neutral', AWAITING_APPROVAL: 'warn', CERTIFIED: 'info', PUBLISHED: 'success', QUARANTINED: 'danger', DECOMMISSIONED: 'neutral' },
    endpoint: { ACTIVE: 'success', PENDING_ENROLMENT: 'warn', ISOLATED: 'danger', REVOKED: 'neutral' },
    message: { PENDING_VERIFICATION: 'warn', ROUTED: 'success', TEST_FORM: 'info', BLOCKED: 'danger', UNMATCHED: 'warn', DEAD_LETTER: 'danger', REJECTED: 'danger', DUPLICATE: 'neutral', DISCARDED: 'neutral' },
    gate: { PASS: 'success', WARN: 'warn', BLOCK: 'danger', INFO: 'info' },
    health: { HEALTHY: 'success', LOCKED_OUT: 'danger', QUARANTINED: 'danger', SILENT: 'warn', AWAITING_ENROLMENT: 'warn' },
    verdict: { OK: 'success', DUE: 'danger', FAILED: 'danger' },
    step: { VALID: 'success', INVALID: 'danger', IN_PROGRESS: 'warn', NOT_STARTED: 'neutral' },
    severity: { LOW: 'neutral', MEDIUM: 'warn', HIGH: 'danger', CRITICAL: 'danger' },
    prereq: { DONE: 'success', NOT_APPLICABLE: 'neutral', OPEN: 'warn' },
    dl: { OPEN: 'danger', REPLAYED: 'success', DISCARDED: 'neutral' }, um: { OPEN: 'warn', RESOLVED: 'success', DISCARDED: 'neutral' },
    test: { PASSED: 'success', FAILED: 'danger' }
  };
  function label(code) { return String(code == null ? '—' : code).replace(/_/g, ' ').toLowerCase().replace(/^\w/, function (c) { return c.toUpperCase(); }); }
  function badge(kind, code, text) { return UI.badge(text || label(code), (TONE[kind] && TONE[kind][code]) || 'neutral'); }

  // ---------------------------------------------------------------- shell and routing
  var state = { dirty: false, route: null };
  function shell(o) {
    var u = root.DX.currentUser;
    return h`${UI.header({ appName: o.appName, user: u ? { name: u.name, title: u.title } : null, orgChip: o.chip })}
      <div class="wz-layout"><nav class="wz-side" aria-label="${o.appName}">${o.nav.map(function (g) {
        return h`${g.group ? h`<h4>${g.group}</h4>` : ''}${g.items.map(function (it) { return h`<a href="#/${it.route}" data-nav="${it.route}" ${raw(o.active === it.route.split('/')[0] ? 'aria-current="page"' : '')}>${it.label}${it.count ? h` <span class="wz-badge ${it.tone || 'danger'}">${it.count}</span>` : ''}</a>`; })}`;
      })}</nav><main class="wz-main" id="main" tabindex="-1">${o.content}</main></div>`;
  }
  /**
   * start({ app, appName, chip, nav() → groups, routes: { 'name': fn(params) → html, 'name/:id': … }, defaultRoute, after(route, params) })
   */
  function start(o) {
    root.DX.init({ app: o.app });
    var mount = document.getElementById('app');
    var router = UI.router(o.routes, { default: o.defaultRoute });
    function render(evt) {
      var path = router.current(), m = router.match(path) || router.match(o.defaultRoute);
      var ae = document.activeElement;
      if (evt && (evt.type === 'change' || evt.type === 'sync') && state.dirty && ae && ae.closest && ae.closest('#main form[data-keep]')) return; // do not wipe a form in progress
      var key = m.key, params = m.params; var q = {};
      (path.split('?')[1] || '').split('&').forEach(function (kv) { if (kv) { var p = kv.split('='); q[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || ''); } });
      params.query = q;
      var content;
      try { content = o.routes[key](params); }
      catch (e) { if (root.console) console.warn(e); content = UI.alert('danger', 'This screen could not be shown', h`<p>${e.message}</p>`); }
      var y = root.scrollY;
      mount.innerHTML = String(shell({ appName: o.appName, chip: o.chip, nav: o.nav(), active: key.split('/')[0], content: content }));
      if (state.route === path) root.scrollTo(0, y); else { state.route = path; root.scrollTo(0, 0); }
      if (o.after) o.after(key, params);
    }
    root.addEventListener('hashchange', function () { state.dirty = false; render(); });
    root.DX.subscribe(function (evt) { render(evt); });
    document.addEventListener('input', function (e) { if (e.target.closest && e.target.closest('form[data-keep]')) state.dirty = true; });
    render();
    state.render = function () { state.dirty = false; render(); };
    return { render: state.render };
  }
  function clean() { state.dirty = false; }

  // ---------------------------------------------------------------- errors on forms
  function attempt(fn, ok, form) {
    try { var r = fn(); clearErrors(form); if (ok) UI.toast(typeof ok === 'function' ? ok(r) : ok, 'success'); state.dirty = false; if (state.render) state.render(); return r; }
    catch (e) {
      if (form && e.errors) showErrors(form, e.errors);
      UI.toast(e.name === 'DXError' ? e.message : 'Something went wrong: ' + e.message, 'danger');
      if (root.console && e.name !== 'DXError') console.warn(e);
      var box = form && form.querySelector('[data-errors]');
      if (box) box.innerHTML = String(UI.alert('danger', e.code === 'validation' ? 'Please correct the highlighted fields' : label(e.code), h`<p>${e.message}</p>`));
      return undefined;
    }
  }
  function clearErrors(form) { if (!form) return; form.querySelectorAll('.dx-field-error').forEach(function (x) { x.remove(); }); form.querySelectorAll('.has-error').forEach(function (x) { x.classList.remove('has-error'); }); var b = form.querySelector('[data-errors]'); if (b) b.innerHTML = ''; }
  function showErrors(form, errors) {
    clearErrors(form);
    errors.forEach(function (er) {
      var f = er.field.replace(/^\(root\)$/, '').replace(/\[\d+\].*$/, '');
      var el = form.querySelector('[data-path="' + f + '"]') || form.querySelector('[name="' + f + '"]') || form.querySelector('[data-path="' + f.split('.')[0] + '"]');
      if (!el) return;
      var wrap = el.closest('.wz-field') || el.parentNode; wrap.classList.add('has-error');
      var msg = document.createElement('span'); msg.className = 'dx-field-error'; msg.setAttribute('data-error-code', er.code); msg.textContent = er.message + ' (' + er.code + ')'; wrap.appendChild(msg);
    });
  }

  // ---------------------------------------------------------------- JSON Schema form (option packs)
  function getPath(o, p) { return p.split('.').reduce(function (x, k) { return x == null ? undefined : x[k]; }, o); }
  function setPath(o, p, v) { var ks = p.split('.'), x = o; ks.slice(0, -1).forEach(function (k) { if (!x[k] || typeof x[k] !== 'object') x[k] = {}; x = x[k]; }); x[ks[ks.length - 1]] = v; }
  function fieldId(p) { return 'pf-' + p.replace(/[^\w]/g, '-'); }
  function hint(sc) {
    var bits = [];
    if (sc.enum) return '';
    if (sc.pattern) bits.push('format ' + sc.pattern.replace(/^\^|\$$/g, '').slice(0, 60));
    if (sc.minimum != null || sc.maximum != null) bits.push((sc.minimum != null ? sc.minimum : '…') + '–' + (sc.maximum != null ? sc.maximum : '…'));
    if (sc.maxLength) bits.push('max ' + sc.maxLength + ' characters');
    if (sc.default !== undefined && typeof sc.default !== 'object') bits.push('default ' + sc.default);
    return bits.join(' · ');
  }
  function schemaFields(schema, values, prefix, opts) {
    var props = schema.properties || {}, req = schema.required || [];
    return Object.keys(props).map(function (k) {
      var sc = props[k], path = prefix ? prefix + '.' + k : k, v = getPath(values, path), id = fieldId(path), required = req.indexOf(k) >= 0;
      var lab = k + (required ? ' *' : ''), hn = hint(sc);
      if (sc.type === 'object' && sc.properties && !sc.default) return h`<fieldset class="dx-fieldset" data-group="${path}"><legend>${lab}</legend><div class="dx-form-grid">${schemaFields(sc, values, path, opts)}</div></fieldset>`;
      var input;
      if (sc.format === 'secret') {
        var set = opts.secrets && opts.secrets[path];
        input = h`<input type="password" id="${id}" data-path="${path}" data-type="secret" autocomplete="new-password" placeholder="${set ? 'stored as ' + set.ref + ' – leave empty to keep' : 'write-only – stored as a devsec: reference'}">`;
        hn = 'write-only secret – never shown again';
      } else if (sc.enum) {
        input = h`<select id="${id}" data-path="${path}" data-type="${sc.type}">${required ? '' : h`<option value="">—</option>`}${sc.enum.map(function (e) { return h`<option value="${e}" ${raw(String(e) === String(v) ? 'selected' : '')}>${e}</option>`; })}</select>`;
      } else if (sc.const !== undefined) {
        input = h`<input type="text" id="${id}" data-path="${path}" data-type="string" value="${sc.const}" readonly>`;
      } else if (sc.type === 'boolean') {
        return h`<div class="wz-field"><label class="wz-check" for="${id}"><input type="checkbox" id="${id}" data-path="${path}" data-type="boolean" ${raw(v ? 'checked' : '')}> ${lab}</label></div>`;
      } else if (sc.type === 'integer' || sc.type === 'number') {
        input = h`<input type="number" id="${id}" data-path="${path}" data-type="${sc.type}" step="${sc.type === 'integer' ? '1' : 'any'}" value="${v == null ? '' : v}">`;
      } else if (sc.type === 'array' && sc.items && (sc.items.type === 'string' || sc.items.type === 'number' || sc.items.type === 'integer')) {
        input = h`<textarea id="${id}" data-path="${path}" data-type="list-${sc.items.type}" rows="2">${(v || []).join('\n')}</textarea>`; hn = 'one value per line' + (sc.items.enum ? ': ' + sc.items.enum.join(', ') : '');
      } else if (sc.type === 'array' || sc.type === 'object') {
        input = h`<textarea id="${id}" data-path="${path}" data-type="json" rows="3">${v == null ? '' : JSON.stringify(v, null, 1)}</textarea>`; hn = 'JSON';
      } else {
        input = h`<input type="text" id="${id}" data-path="${path}" data-type="string" value="${v == null ? '' : v}">`;
      }
      return h`<div class="wz-field"><label for="${id}">${lab}</label>${hn ? h`<span class="wz-hint">${hn}</span>` : ''}${input}</div>`;
    });
  }
  /** Collect the form back into { values, secrets, errors }. */
  function collectSchema(container) {
    var values = {}, secrets = {}, errors = [];
    container.querySelectorAll('[data-path]').forEach(function (el) {
      var p = el.getAttribute('data-path'), t = el.getAttribute('data-type'), v;
      if (t === 'boolean') { setPath(values, p, el.checked); return; }
      var s = (el.value || '').trim();
      if (t === 'secret') { if (s) secrets[p] = s; return; }
      if (s === '') return;
      if (t === 'integer' || t === 'number') { v = Number(s); if (!isFinite(v)) { errors.push({ field: p, code: 'type', message: 'must be a number' }); return; } }
      else if (t === 'list-string') v = s.split(/\n/).map(function (x) { return x.trim(); }).filter(Boolean);
      else if (t === 'list-number' || t === 'list-integer') v = s.split(/\n/).map(Number);
      else if (t === 'json') { try { v = JSON.parse(s); } catch (e) { errors.push({ field: p, code: 'json', message: 'is not valid JSON' }); return; } }
      else v = s;
      setPath(values, p, v);
    });
    return { values: values, secrets: secrets, errors: errors };
  }

  // ---------------------------------------------------------------- pipeline visualiser
  var STAGE_TONE = { OK: 'ok', WARN: 'warn', HELD: 'held', BLOCKED: 'blocked', FAILED: 'failed', SKIPPED: 'skipped' };
  function pipeline(result, opts) {
    opts = opts || {};
    if (!result || !result.stages) return '';
    var stages = result.stages;
    var order = ['ACQUIRE', 'PARSE', 'NORMALISE', 'ASSOCIATE', 'VALIDATE', 'ROUTE', 'PERSIST'];
    var seen = {}; stages.forEach(function (s) { seen[s.stage] = s.status; });
    var flow = h`<div class="dx-flow" data-test="flow">${order.map(function (n, i) { var st = seen[n]; var cls = !st ? '' : st === 'OK' ? 'ok' : st === 'WARN' || st === 'HELD' || st === 'SKIPPED' ? 'warn' : 'bad'; return h`${i ? h`<i>→</i>` : ''}<span class="${cls}" title="${st || 'not reached'}">${n.toLowerCase()}</span>`; })}<i>→</i><span class="${result.status === 'ROUTED' || result.status === 'PENDING_VERIFICATION' || result.status === 'TEST_FORM' ? 'ok' : result.status === 'DUPLICATE' ? 'warn' : 'bad'}" data-test="final-status">${result.status}</span></div>`;
    return h`${flow}<div class="dx-pipeline" data-test="pipeline">${stages.map(function (s) {
      return h`<div class="dx-stage ${STAGE_TONE[s.status] || ''}" data-stage="${s.stage}" data-status="${s.status}">
        <div class="dx-stage-name">${s.stage}<small>${s.status}</small></div>
        <div><div>${s.summary}</div>${stageDetail(s, opts)}</div></div>`;
    })}</div>`;
  }
  function stageDetail(s, opts) {
    if (!s.data) return '';
    if (s.stage === 'ACQUIRE') return h`<div class="wz-small wz-muted">Raw payload SHA-256</div><div class="dx-hash" data-test="sha256">${s.data.sha256}</div>${s.data.hmac ? h`<div class="wz-small">HMAC: ${s.data.hmac}</div>` : ''}`;
    if (s.stage === 'PARSE') {
      var rec = s.data.records && s.data.records[0];
      return h`<details ${raw(opts.open ? 'open' : '')}><summary>Parsed identity and values</summary>${rec && rec.annotations ? bytes(rec.annotations) : ''}${s.data.hl7 ? h`<div class="wz-small">Segments: ${s.data.hl7.join(' · ')}</div>` : ''}<pre class="dx-code">${JSON.stringify(s.data.records.map(function (r) { return { identity: r.identity, values: r.values }; }), null, 1)}</pre></details>`;
    }
    if (s.stage === 'NORMALISE') return h`<details ${raw(opts.openAll ? 'open' : '')}><summary>Canonical components (LOINC / UCUM)</summary>${components(s.data.components)}${s.data.predicted ? h`<p class="wz-small">Predicted (${s.data.predicted.method}): FEV1 ${s.data.predicted.fev1PredictedL} L, FVC ${s.data.predicted.fvcPredictedL} L – age ${s.data.predicted.age}, height ${s.data.predicted.heightCm} cm, sex ${s.data.predicted.sex}</p>` : ''}</details>`;
    if (s.stage === 'ASSOCIATE') return s.data.candidates && s.data.candidates.length ? h`<details><summary>Candidates (${s.data.candidates.length})</summary>${UI.table([{ label: 'Method', key: 'method' }, { label: 'Employee', key: 'employeeId' }, { label: 'Confidence', render: function (c) { return c.confidence.toFixed(2); } }, { label: 'Detail', key: 'detail' }], s.data.candidates)}</details>` : '';
    if (s.stage === 'VALIDATE') return h`<ul class="wz-list">${s.data.map(function (f) { return h`<li data-gate="${f.gate}" data-outcome="${f.outcome}">${badge('gate', f.outcome)} <code>${f.gate}</code>${f.code ? h` <code class="wz-muted">${f.code}</code>` : ''} – ${f.message}</li>`; })}</ul>`;
    if (s.stage === 'ROUTE' && s.data.fields) return h`<div class="wz-small">${Object.keys(s.data.fields).map(function (k) { return k + ' = ' + s.data.fields[k]; }).join(' · ')}</div>`;
    if (s.stage === 'PERSIST') return h`<details><summary>FHIR R4 Observation + Provenance</summary><pre class="dx-code">${JSON.stringify({ observation: s.data.observation, provenance: s.data.provenance }, null, 1)}</pre></details>`;
    return '';
  }
  function components(list) {
    return UI.table([{ label: 'Key', render: function (c) { return h`<code>${c.key}</code>`; } }, { label: 'Display', key: 'display' }, { label: 'MDC', render: function (c) { return c.mdc ? h`<code class="wz-small">${c.mdc}</code>` : '—'; } },
      { label: 'LOINC', render: function (c) { return c.loinc ? h`<code>${c.loinc}</code>` : '—'; } }, { label: 'Value', className: 'num', render: function (c) { return h`<strong>${String(c.value)}</strong> ${c.ucum || ''}${c.percentPredicted ? h` <span class="wz-muted">(${c.percentPredicted} % pred.)</span>` : ''}`; } },
      { label: 'Conversion', render: function (c) { return c.converted ? h`${c.original.value} ${c.original.unit} → ${c.ucum}` : c.derived ? 'derived' : '—'; } }, { label: 'Ref.', render: function (c) { return c.interpretation && c.interpretation !== 'N' ? UI.badge(c.interpretation, 'warn') : (c.referenceRange || '—'); } }], list || []);
  }
  function bytes(dec) {
    var list = dec.annotations || dec;
    return h`<div class="wz-small wz-muted">GATT bytes (little-endian; SFLOAT = 4-bit exponent + 12-bit mantissa)</div><div class="dx-bytes" data-test="gatt-bytes">${list.map(function (a) { return h`<div class="dx-byte"><code>${a.hex.toUpperCase()}</code><small>${a.field}</small><small>${a.type} = ${String(a.value)}${a.unit ? ' ' + a.unit : ''}</small></div>`; })}</div>`;
  }
  function hl7Table(rawText) {
    var segs = String(rawText).split(/\r\n|\r|\n/).filter(Boolean);
    return h`<table class="wz-table dx-hl7"><tbody>${segs.map(function (l) { var p = l.split('|'); return h`<tr><td>${p[0]}</td><td>${p.slice(1).map(function (f, i) { return f ? (p[0] === 'MSH' ? i + 2 : i + 1) + '=' + f : ''; }).filter(Boolean).join('  |  ')}</td></tr>`; })}</tbody></table>`;
  }
  function tokenModal(title, token, note, extra) {
    UI.modal({ title: title, body: h`<p>${note}</p><div class="dx-token" data-test="one-time-token">${token}</div>${extra || ''}<p class="wz-small wz-muted">This value is shown once and is never displayed again. Only its hash is stored.</p>`, actions: [{ label: 'I have recorded it', tone: '' }] });
  }

  root.DXUI = { dt: dt, d: d, short: short, label: label, badge: badge, TONE: TONE, shell: shell, start: start, clean: clean, attempt: attempt, showErrors: showErrors, clearErrors: clearErrors,
    schemaFields: schemaFields, collectSchema: collectSchema, getPath: getPath, setPath: setPath, pipeline: pipeline, components: components, bytes: bytes, hl7Table: hl7Table, tokenModal: tokenModal, state: state };
})(window);
