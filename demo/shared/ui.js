/*
 * WellnessZ DEMO PACK - shared UI helpers (window.UI).
 * Plain DOM, no framework. Pairs with shared/ui.css. See DEVELOPER-NOTES.md.
 */
(function (root) {
  'use strict';
  var doc = root.document;

  // ---------------------------------------------------------------- escaping and templating
  function esc(x) { return String(x == null ? '' : x).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function Raw(s) { this.s = s; }
  Raw.prototype.toString = function () { return this.s; };
  /** Mark trusted HTML so h`` does not escape it. */
  function raw(s) { return new Raw(s == null ? '' : String(s)); }
  function val(v) {
    if (v == null || v === false) return '';
    if (v instanceof Raw) return v.s;
    if (Array.isArray(v)) return v.map(val).join('');
    return esc(v);
  }
  /** Tagged template: interpolations are escaped unless wrapped in UI.raw() or are arrays of h`` results. */
  function h(strings) {
    var out = strings[0];
    for (var i = 1; i < arguments.length; i++) out += val(arguments[i]) + strings[i];
    return raw(out);
  }

  // ---------------------------------------------------------------- dates
  var DF = { day: 'numeric', month: 'short', year: 'numeric' };
  function toDate(x) { if (!x) return null; return new Date(x.length <= 10 ? x + 'T00:00:00' : x); }
  function fmtDate(x) { var d = toDate(x); return d ? d.toLocaleDateString('en-GB', DF) : '—'; }
  function fmtDateTime(x) { var d = toDate(x); return d ? d.toLocaleDateString('en-GB', DF) + ', ' + d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) : '—'; }
  function fmtTime(x) { var d = toDate(x); return d ? d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) : ''; }
  function fmtRelative(x) {
    var d = toDate(x); if (!d) return '';
    var mins = Math.round((Date.now() - d.getTime()) / 60000);
    if (Math.abs(mins) < 1) return 'just now';
    if (mins > 0 && mins < 60) return mins + ' min ago';
    if (mins > 0 && mins < 1440) return Math.round(mins / 60) + ' h ago';
    if (mins > 0 && mins < 10080) return Math.round(mins / 1440) + ' days ago';
    return fmtDate(x);
  }
  function isoToday(offset) { var d = new Date(); d.setDate(d.getDate() + (offset || 0)); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }

  // ---------------------------------------------------------------- badges
  var TONES = {
    fitness: { FIT: 'success', FIT_WITH_RESTRICTIONS: 'warn', TEMPORARILY_UNFIT: 'danger', PERMANENTLY_UNFIT: 'danger', UNDER_REVIEW: 'info', NOT_ASSESSED: 'neutral', UNFIT_PENDING: 'danger' },
    caseStatus: { INTAKE: 'info', TRIAGE: 'info', ASSESSMENT: 'info', FITNESS_REVIEW: 'warn', TREATMENT: 'info', CLAIMS: 'info', MONITORING: 'success', CLOSED: 'neutral' },
    urgency: { EMERGENCY: 'danger', URGENT: 'danger', SEMI_URGENT: 'warn', ROUTINE: 'neutral', ADVICE: 'neutral' },
    duty: { AVAILABLE: 'success', REMOVED: 'danger', RETURN_PENDING: 'warn', RELIEVED: 'warn', EXCLUDED: 'danger' },
    rtw: { DRAFT: 'neutral', ISSUED: 'warn', ACTIVE: 'info', COMPLETED: 'success', CANCELLED: 'neutral' },
    referral: { SENT: 'info', ACCEPTED: 'info', APPOINTMENT_SET: 'success', IN_PROGRESS: 'info', REPORT_RECEIVED: 'success', DECLINED: 'danger', CLOSED: 'neutral', CONSENT_WITHDRAWN: 'danger' },
    claim: { OPEN: 'info', SUBMITTED: 'info', UNDER_REVIEW: 'warn', MORE_INFO: 'warn', ACCEPTED: 'success', DENIED: 'danger', CLOSED: 'neutral' },
    support: { OPEN: 'restricted', SCREENED: 'restricted', ACTIVE: 'restricted', REFERRED: 'restricted', RELIEF: 'warn', CLOSED: 'neutral' },
    surveillance: { NOT_DUE: 'success', DUE: 'warn', OVERDUE: 'danger', BOOKED: 'info', FOLLOW_UP: 'warn', COMPLETED: 'success' },
    testMasked: { NOTIFIED: 'danger', COLLECTED: 'info', COMPLETED: 'success', CLOSED: 'neutral' },
    test: { REQUIRED: 'warn', NOTIFIED: 'warn', DISPATCHED: 'warn', COLLECTED: 'info', IN_LAB: 'info', MRO_REVIEW: 'warn', VERIFIED_NEGATIVE: 'success', VERIFIED_POSITIVE: 'danger', REFUSAL: 'danger', CANCELLED: 'neutral', WINDOW_MISSED: 'neutral' },
    advice: { REQUESTED: 'info', AWAITING_EMPLOYEE_REVIEW: 'warn', RELEASED: 'success', WITHHELD: 'neutral', CHANGES_REQUESTED: 'warn' },
    contact: { MONITORING: 'warn', SYMPTOMATIC: 'danger', CLEARED: 'success' },
    consent: { GIVEN: 'success', WITHDRAWN: 'neutral' },
    campaign: { PLANNED: 'neutral', ACTIVE: 'success', CLOSED: 'neutral' },
    travel: { REQUESTED: 'info', COMPLETED: 'success', CANCELLED: 'neutral' },
    absenceCert: { NOT_REQUIRED: 'neutral', REQUIRED: 'warn', RECEIVED: 'info', VALIDATED: 'success', REJECTED: 'danger' }
  };
  /** A badge with an explicit tone: info | success | warn | danger | neutral | restricted. */
  function badge(text, tone) { return h`<span class="wz-badge ${tone || 'neutral'}">${text}</span>`; }
  /** A status badge using the engine's labels: UI.status('fitness', 'FIT'). */
  function status(kind, code) {
    var text = root.WZ ? root.WZ.label(kind, code) : code;
    var tone = (TONES[kind] && TONES[kind][code]) || 'neutral';
    return badge(text, tone);
  }

  // ---------------------------------------------------------------- components
  function empty(title, text, actionHtml, icon) {
    return h`<div class="wz-empty"><span class="wz-empty-icon" aria-hidden="true">${icon || '○'}</span><strong>${title}</strong>${text ? h`<span>${text}</span>` : ''}${actionHtml ? h`<div class="wz-actions" style="justify-content:center">${actionHtml}</div>` : ''}</div>`;
  }
  function alert(tone, title, body) { return h`<div class="wz-alert ${tone || ''}" role="${tone === 'danger' || tone === 'emergency' ? 'alert' : 'note'}">${title ? h`<div class="wz-alert-title">${title}</div>` : ''}${body || ''}</div>`; }
  function dl(pairs) { return h`<dl class="wz-dl">${pairs.filter(Boolean).map(function (p) { return h`<dt>${p[0]}</dt><dd>${p[1] == null || p[1] === '' ? '—' : p[1]}</dd>`; })}</dl>`; }
  /**
   * table(columns, rows, opts): columns = [{ label, key | render(row), className }].
   * opts.empty: text shown when there are no rows; opts.caption: accessible caption.
   */
  function table(columns, rows, opts) {
    opts = opts || {};
    if (!rows || !rows.length) return empty(opts.empty || 'Nothing to show', opts.emptyText || '');
    return h`<div class="wz-table-wrap"><table class="wz-table">${opts.caption ? h`<caption class="wz-sr-only">${opts.caption}</caption>` : ''}<thead><tr>${columns.map(function (c) { return h`<th scope="col" class="${c.className || ''}">${c.label}</th>`; })}</tr></thead><tbody>${rows.map(function (r) {
      return h`<tr>${columns.map(function (c) { var v = c.render ? c.render(r) : r[c.key]; return h`<td class="${c.className || ''}">${v == null || v === '' ? '—' : v}</td>`; })}</tr>`;
    })}</tbody></table></div>`;
  }
  function field(o) {
    var id = o.id || ('f-' + o.name);
    var input;
    var common = 'id="' + esc(id) + '" name="' + esc(o.name) + '"' + (o.required ? ' required aria-required="true"' : '') + (o.hint ? ' aria-describedby="' + esc(id) + '-hint"' : '');
    if (o.type === 'select') input = '<select ' + common + '>' + (o.placeholder ? '<option value="">' + esc(o.placeholder) + '</option>' : '') + (o.options || []).map(function (op) { op = typeof op === 'string' ? { value: op, label: op } : op; return '<option value="' + esc(op.value) + '"' + (String(op.value) === String(o.value) ? ' selected' : '') + '>' + esc(op.label) + '</option>'; }).join('') + '</select>';
    else if (o.type === 'textarea') input = '<textarea ' + common + (o.maxlength ? ' maxlength="' + o.maxlength + '"' : '') + '>' + esc(o.value || '') + '</textarea>';
    else input = '<input type="' + esc(o.type || 'text') + '" ' + common + ' value="' + esc(o.value == null ? '' : o.value) + '"' + (o.min ? ' min="' + esc(o.min) + '"' : '') + (o.max ? ' max="' + esc(o.max) + '"' : '') + (o.autocomplete ? ' autocomplete="' + esc(o.autocomplete) + '"' : '') + '>';
    return raw('<div class="wz-field"><label for="' + esc(id) + '">' + esc(o.label) + (o.optional ? ' <span class="wz-muted">(optional)</span>' : '') + '</label>' + (o.hint ? '<span class="wz-hint" id="' + esc(id) + '-hint">' + esc(o.hint) + '</span>' : '') + input + '</div>');
  }
  function checkbox(o) {
    var id = o.id || ('c-' + o.name);
    return raw('<div class="wz-check"><input type="checkbox" id="' + esc(id) + '" name="' + esc(o.name) + '" value="' + esc(o.value || 'on') + '"' + (o.checked ? ' checked' : '') + (o.required ? ' required' : '') + '><label for="' + esc(id) + '">' + val(o.label) + '</label></div>');
  }
  function radios(o) {
    return raw('<fieldset class="wz-fieldset"><legend class="wz-legend">' + esc(o.legend) + '</legend>' + (o.hint ? '<span class="wz-hint">' + esc(o.hint) + '</span>' : '') + '<div class="wz-radio-row">' + o.options.map(function (op, i) {
      op = typeof op === 'string' ? { value: op, label: op } : op;
      return '<label><input type="radio" name="' + esc(o.name) + '" value="' + esc(op.value) + '"' + (String(op.value) === String(o.value) || (o.value == null && i === 0 && o.defaultFirst) ? ' checked' : '') + (o.required ? ' required' : '') + '> ' + esc(op.label) + '</label>';
    }).join('') + '</div></fieldset>');
  }
  /** Collect a form into an object (checkboxes become booleans, repeated names become arrays). */
  function formData(form) {
    var o = {};
    Array.prototype.forEach.call(form.elements, function (el) {
      if (!el.name || el.disabled) return;
      if (el.type === 'checkbox') { if (form.querySelectorAll('[name="' + el.name + '"]').length > 1) { o[el.name] = o[el.name] || []; if (el.checked) o[el.name].push(el.value); } else o[el.name] = el.checked; return; }
      if (el.type === 'radio') { if (el.checked) o[el.name] = el.value; return; }
      if (el.type === 'file') { o[el.name] = el.files && el.files[0] ? el.files[0] : null; return; }
      o[el.name] = el.value;
    });
    return o;
  }

  // ---------------------------------------------------------------- toast and modal
  function toast(message, tone) {
    if (!doc) return;
    var box = doc.querySelector('.wz-toasts');
    if (!box) { box = doc.createElement('div'); box.className = 'wz-toasts'; box.setAttribute('role', 'status'); box.setAttribute('aria-live', 'polite'); doc.body.appendChild(box); }
    var t = doc.createElement('div'); t.className = 'wz-toast ' + (tone || ''); t.textContent = message;
    box.appendChild(t);
    setTimeout(function () { t.remove(); }, tone === 'danger' ? 7000 : 4000);
  }
  /**
   * modal({ title, body (html), actions: [{ label, tone, value, onClick(close, dialogEl) }], onClose })
   * Returns { el, close }. Uses <dialog> (focus trap, Esc to close).
   */
  function modal(o) {
    var d = doc.createElement('dialog'); d.className = 'wz-modal';
    var tid = 'm-' + Math.random().toString(36).slice(2);
    d.setAttribute('aria-labelledby', tid);
    d.innerHTML = '<div class="wz-modal-head"><h2 id="' + tid + '">' + esc(o.title) + '</h2><button type="button" class="wz-close" aria-label="Close">×</button></div><div class="wz-modal-body">' + val(o.body) + '</div>' +
      ((o.actions && o.actions.length) ? '<div class="wz-modal-foot">' + o.actions.map(function (a, i) { return '<button type="button" class="wz-btn ' + esc(a.tone || (i === o.actions.length - 1 ? '' : 'secondary')) + '" data-i="' + i + '">' + esc(a.label) + '</button>'; }).join('') + '</div>' : '');
    doc.body.appendChild(d);
    function close(v) { if (d.open) d.close(); d.remove(); if (o.onClose) o.onClose(v); }
    d.addEventListener('cancel', function (e) { e.preventDefault(); close(); });
    d.querySelector('.wz-close').addEventListener('click', function () { close(); });
    d.addEventListener('click', function (e) {
      var b = e.target.closest('[data-i]'); if (!b) return;
      var a = o.actions[+b.getAttribute('data-i')];
      if (a.onClick) { var r = a.onClick(close, d); if (r !== false && a.closes !== false && !a.keepOpen) close(a.value); }
      else close(a.value);
    });
    if (d.showModal) d.showModal(); else d.setAttribute('open', '');
    var first = d.querySelector('input,select,textarea,.wz-modal-foot button'); if (first) first.focus();
    return { el: d, close: close };
  }
  function confirm(title, text, okLabel, tone) {
    return new Promise(function (resolve) {
      modal({ title: title, body: h`<p>${text}</p>`, onClose: function (v) { resolve(v === true); }, actions: [{ label: 'Cancel', tone: 'secondary', value: false }, { label: okLabel || 'Confirm', tone: tone || '', value: true }] });
    });
  }

  // ---------------------------------------------------------------- errors
  var FRIENDLY = {
    forbidden: 'You are not allowed to do this with your current role.', not_found: 'This record is not available to you.', consent_required: 'Consent is needed before continuing.',
    invalid_state: 'This step is not possible at the moment.', validation: 'Please check the form.', slot_taken: 'That slot has just been taken – please choose another.',
    consent_withdrawn: 'The employee has withdrawn consent.', storage_full: 'The browser could not save the demo session.'
  };
  function errorMessage(e) { if (!e) return 'Something went wrong.'; if (e.name === 'WZError') return e.message || FRIENDLY[e.code] || e.code; return 'Something went wrong: ' + (e.message || e); }
  /** Run fn; on error show a toast. Returns the result or undefined. */
  function attempt(fn, successMessage) {
    try { var r = fn(); if (successMessage) toast(successMessage, 'success'); return r; }
    catch (e) { toast(errorMessage(e), 'danger'); if (root.console) console.warn(e); return undefined; }
  }

  // ---------------------------------------------------------------- routing
  /**
   * router(routes, render): routes = { 'home': fn(params), 'case/:id': fn(params) }.
   * Calls render(html) with the route output. Hash routing (#/home) works on file://.
   */
  function router(routes, opts) {
    opts = opts || {};
    var patterns = Object.keys(routes).map(function (k) { return { key: k, parts: k.split('/') }; });
    function current() { return (root.location.hash || '').replace(/^#\/?/, '') || opts.default || 'home'; }
    function match(path) {
      var segs = path.split('?')[0].split('/');
      for (var i = 0; i < patterns.length; i++) {
        var p = patterns[i]; if (p.parts.length !== segs.length) continue;
        var params = {}, ok = true;
        for (var j = 0; j < segs.length; j++) { if (p.parts[j][0] === ':') params[p.parts[j].slice(1)] = decodeURIComponent(segs[j]); else if (p.parts[j] !== segs[j]) { ok = false; break; } }
        if (ok) return { key: p.key, params: params };
      }
      return null;
    }
    return { current: current, match: match, go: go };
  }
  function go(route) { if (('#/' + route) === root.location.hash) root.dispatchEvent(new HashChangeEvent('hashchange')); else root.location.hash = '#/' + route; }

  /** Event delegation: on(container, 'click', '[data-act]', fn(event, matchedEl)). */
  function on(container, type, selector, fn) {
    container.addEventListener(type, function (e) { var el = e.target.closest(selector); if (el && container.contains(el)) fn(e, el); });
  }

  /** Standard app header (brand, app name, user, notifications button). */
  function header(o) {
    return h`<header class="wz-header"><div class="wz-header-in">
      <a class="wz-brand" href="#/home"><span class="wz-logo" aria-hidden="true">Z</span>WellnessZ ${o.appName ? h`<span class="wz-app-name">${o.appName}</span>` : ''}</a>
      <span class="wz-header-sp"></span>
      ${o.orgChip ? h`<span class="wz-org-chip">${o.orgChip}</span>` : ''}
      ${o.bell != null ? h`<a class="wz-bell" href="${o.bellHref || '#/home'}" aria-label="Notifications, ${o.bell} unread"><span aria-hidden="true">🔔</span>${o.bell ? h`<span class="wz-count">${o.bell}</span>` : ''}</a>` : ''}
      ${o.user ? h`<span class="wz-header-user">${o.user.name}<small>${o.user.title || ''}</small></span>` : ''}
    </div></header>`;
  }
  function nav(items, active) {
    return h`<nav class="wz-nav" aria-label="Main"><div class="wz-nav-in">${items.map(function (it) { return h`<a href="#/${it.route}" ${raw(it.route === active ? 'aria-current="page"' : '')}>${it.label}${it.count ? h` <span class="wz-badge danger">${it.count}</span>` : ''}</a>`; })}</div></nav>`;
  }

  root.UI = {
    esc: esc, raw: raw, h: h, fmtDate: fmtDate, fmtDateTime: fmtDateTime, fmtTime: fmtTime, fmtRelative: fmtRelative, isoToday: isoToday,
    badge: badge, status: status, TONES: TONES, empty: empty, alert: alert, dl: dl, table: table, field: field, checkbox: checkbox, radios: radios, formData: formData,
    toast: toast, modal: modal, confirm: confirm, errorMessage: errorMessage, attempt: attempt, router: router, go: go, on: on, header: header, nav: nav
  };
})(typeof window !== 'undefined' ? window : globalThis);
