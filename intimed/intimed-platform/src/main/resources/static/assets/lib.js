// IntiMed console – tiny DOM / API helpers. No innerHTML anywhere: text is always inserted as text nodes (XSS-safe),
// CSP forbids inline script and inline style attributes.
export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}
export const $ = (sel, root = document) => root.querySelector(sel);
export function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

function cookie(name) {
  const m = document.cookie.split('; ').find(c => c.startsWith(name + '='));
  return m ? decodeURIComponent(m.split('=')[1]) : null;
}
export async function api(method, path, body) {
  const headers = { 'Accept': 'application/json' };
  if (body !== undefined) headers['Content-Type'] = typeof body === 'string' ? 'text/plain' : 'application/json';
  const x = cookie('XSRF-TOKEN');
  if (x && method !== 'GET') headers['X-XSRF-TOKEN'] = x;
  const r = await fetch(path, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
  if (r.status === 401 && !path.startsWith('/api/auth')) { location.hash = '#/login'; throw new Error('Session expired – sign in again'); }
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : await r.text();
  if (!r.ok) { const e = new Error((data && (data.detail || data.title || data.error)) || ('HTTP ' + r.status)); e.status = r.status; e.data = data; throw e; }
  return data;
}
export const get = (p) => api('GET', p);
export const post = (p, b) => api('POST', p, b === undefined ? {} : b);
export const put = (p, b) => api('PUT', p, b);
export const del = (p) => api('DELETE', p);

export function toast(msg, kind = 'ok') {
  const t = h('div', { class: 'toast ' + kind, role: 'status' }, msg);
  document.body.appendChild(t);
  setTimeout(() => t.classList.add('show'), 10);
  setTimeout(() => { t.classList.remove('show'); setTimeout(() => t.remove(), 300); }, kind === 'err' ? 6500 : 3200);
}
export async function guard(fn, okMsg) {
  try { const r = await fn(); if (okMsg) toast(okMsg); return r; } catch (e) { toast(e.message, 'err'); throw e; }
}
export function badge(text, kind) { return h('span', { class: 'badge ' + (kind || kindOf(text)) }, text); }
export function kindOf(s) {
  s = String(s || '').toUpperCase();
  if (/PASS|FILED|PUBLISHED|ACTIVE|CERTIFIED|OK|GREEN|DELIVERED|VALID|ALLOWED|ACCEPTED|HIGH|DONE|RESOLVED|TRUE|GIVEN|INTACT|RECEIVED|ACCEPTABLE/.test(s) && !/INVALID|UNRESOLVED/.test(s)) return "ok";
  if (/FAIL|BLOCK|REVOKED|ISOLATED|RED|ERROR|DLQ|CRITICAL|INVALID|REJECTED|LOCKED|FALSE/.test(s)) return 'bad';
  if (/WARN|HELD|PENDING|AMBER|JUSTIFY|UNMATCHED|MEDIUM|DRAFT|TESTED|PARTIAL|OPEN|PROPOSED|RETEST|REQUIRES/.test(s)) return 'warn';
  return 'neutral';
}
export function table(cols, rows, opts = {}) {
  const t = h('table', { class: 'grid' + (opts.compact ? ' compact' : '') },
    h('thead', null, h('tr', null, cols.map(c => h('th', null, c.label)))),
    h('tbody', null, rows.length ? rows.map(r => h('tr', { class: opts.rowClass ? opts.rowClass(r) : null, onclick: opts.onRow ? () => opts.onRow(r) : null },
      cols.map(c => h('td', { class: c.cls || null }, c.render ? c.render(r) : fmt(r[c.key])))))
      : h('tr', null, h('td', { colspan: cols.length, class: 'muted' }, opts.empty || 'Nothing to show yet.'))));
  return h('div', { class: 'table-wrap' }, t);
}
export function fmt(v) {
  if (v === null || v === undefined || v === '') return '—';
  if (Array.isArray(v)) return v.join(', ');
  if (typeof v === 'object') return JSON.stringify(v);
  if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return v.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC');
  return String(v);
}
export function card(title, ...body) { return h('section', { class: 'card' }, title ? h('h3', null, title) : null, ...body); }
export function kpi(label, value, sub) { return h('div', { class: 'kpi' }, h('div', { class: 'kpi-v' }, String(value)), h('div', { class: 'kpi-l' }, label), sub ? h('div', { class: 'kpi-s' }, sub) : null); }
export function field(label, input, hint) { return h('label', { class: 'field' }, h('span', null, label), input, hint ? h('small', null, hint) : null); }
export function input(attrs) { return h('input', Object.assign({ class: 'in' }, attrs)); }
export function select(options, value, attrs) {
  return h('select', Object.assign({ class: 'in' }, attrs), options.map(o => {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    return h('option', { value: v, selected: String(v) === String(value) ? 'selected' : null }, l);
  }));
}
export function button(label, onclick, kind = 'primary', attrs = {}) { return h('button', Object.assign({ class: 'btn ' + kind, onclick, type: 'button' }, attrs), label); }
export function modal(title, body, actions) {
  const back = h('div', { class: 'modal-back', onclick: (e) => { if (e.target === back) back.remove(); } });
  const box = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, h('div', { class: 'modal-h' }, h('h3', null, title), button('×', () => back.remove(), 'ghost', { 'aria-label': 'Close' })),
    h('div', { class: 'modal-b' }, body), actions ? h('div', { class: 'modal-f' }, actions(() => back.remove())) : null);
  back.appendChild(box);
  document.body.appendChild(back);
  return back;
}
export function pre(obj) { return h('pre', { class: 'code' }, typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2)); }
export function download(name, blob) {
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 500);
}
export function tabs(items, active, onPick) {
  return h('div', { class: 'tabs', role: 'tablist' }, items.map(([id, label, extra]) => h('button', { class: 'tab' + (id === active ? ' active' : ''), role: 'tab', 'aria-selected': id === active ? 'true' : 'false', type: 'button', onclick: () => onPick(id) }, label, extra ? h('span', { class: 'tab-x' }, extra) : null)));
}
