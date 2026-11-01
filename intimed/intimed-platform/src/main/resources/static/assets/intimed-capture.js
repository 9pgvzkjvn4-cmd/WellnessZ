// <intimed-capture template="TPL-18" station="OH-SPIRO-1" subject="EMP-700102"> – IntiMed's embeddable 'capture from device'
// component (IM-E05-F04, integration pattern EP-1). Same-origin session; CSRF header from the XSRF-TOKEN cookie; text only.
import { h, clear, api } from './lib.js';

class IntiMedCapture extends HTMLElement {
  static get observedAttributes() { return ['template', 'station', 'subject']; }
  connectedCallback() {
    if (!this.shadowRoot) {
      const root = this.attachShadow({ mode: 'open' });
      root.append(h('link', { rel: 'stylesheet', href: '/assets/intimed-capture.css' }), h('div', { class: 'cap' }));
    }
    this.load();
  }
  attributeChangedCallback() { if (this.shadowRoot) this.load(); }
  get box() { return this.shadowRoot.querySelector('.cap'); }
  say(msg, kind = 'info') { const s = this.shadowRoot.querySelector('.status'); if (s) { s.textContent = msg; s.className = 'status ' + kind; } }

  async load() {
    const tpl = this.getAttribute('template'), station = this.getAttribute('station') || '', subject = this.getAttribute('subject') || '';
    const box = clear(this.box);
    box.append(h('div', { class: 'head' }, h('b', null, 'Capture from device'), h('span', { class: 'pill' }, 'IntiMed')), h('p', { class: 'status info', role: 'status' }, 'Station ' + station + ' · subject ' + subject));
    let devices = [];
    try { devices = await api('GET', '/api/capture/devices?templateId=' + encodeURIComponent(tpl) + '&station=' + encodeURIComponent(station)); }
    catch (e) { box.append(h('p', { class: 'err' }, e.message)); return; }
    if (!devices.length) {
      try { devices = await api('GET', '/api/capture/devices?templateId=' + encodeURIComponent(tpl)); } catch { devices = []; }
      if (devices.length) box.append(h('p', { class: 'muted' }, 'No device at this station – showing all certified devices for this template.'));
    }
    const list = h('div', { class: 'devices' });
    for (const d of devices) {
      list.append(h('div', { class: 'dev' + (d.available ? '' : ' off') },
        h('div', null, h('b', null, d.name), h('small', null, d.option + ' · ' + (d.station || d.bed || '') + (d.available ? '' : ' · unavailable: ' + d.reason))),
        ['P9', 'P10'].includes(d.option) ? h('button', { class: 'btn', type: 'button', onclick: () => this.form(d, tpl, subject) }, d.option === 'P9' ? 'Photo / OCR' : 'Manual entry')
          : h('button', { class: 'btn primary', type: 'button', disabled: d.available ? null : 'disabled', onclick: () => this.capture(d, tpl, station, subject) }, 'Capture')));
    }
    if (!devices.length) list.append(h('p', { class: 'muted' }, 'No certified device is published for this template yet.'));
    box.append(list, h('div', { class: 'actions' },
      h('button', { class: 'btn ghost', type: 'button', onclick: () => this.fallback(tpl, station, subject) }, 'Device unavailable? Fallback'),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => this.refresh(tpl, subject) }, 'Refresh')),
      h('div', { class: 'pending' }), h('div', { class: 'filed' }));
    this.refresh(tpl, subject);
  }

  async capture(d, tpl, station, subject) {
    try {
      this.say('Session started – asking the device to measure…');
      await api('POST', '/api/capture/sessions', { station: d.station || d.bed || station, subject, templateId: tpl });
      const r = await api('POST', '/api/capture/acquire', { configId: d.configId, subjectInMessage: subject });
      this.say('Device answered via ' + (r.transport || 'gateway') + ': ' + (r.status || (r.ack || '').split('\n').find(l => l.startsWith('MSA')) || 'sent'), r.status === 'DLQ' || r.status === 'REJECTED' ? 'err' : 'ok');
      setTimeout(() => this.refresh(tpl, subject), 400);
    } catch (e) { this.say(e.message, 'err'); }
  }

  async refresh(tpl, subject) {
    const pendBox = this.shadowRoot.querySelector('.pending'), filedBox = this.shadowRoot.querySelector('.filed');
    if (!pendBox) return;
    try {
      const pend = (await api('GET', '/api/capture/pending')).filter(o => !subject || o.subject?.ref === subject);
      clear(pendBox).append(pend.length ? h('h4', null, 'Waiting for your review') : null, pend.map(o => this.review(o, tpl, subject)));
      const inst = await api('GET', '/api/templates/' + encodeURIComponent(tpl) + '/instances?subject=' + encodeURIComponent(subject));
      clear(filedBox);
      if (inst.length) {
        const i = inst[0];
        filedBox.append(h('h4', null, 'Filed into the form (' + i.effectiveTime.replace('T', ' ').slice(0, 19) + ' UTC)'),
          h('table', null, h('tbody', null, Object.entries(i.fields).map(([k, v]) => h('tr', null, h('td', null, k), h('td', null, String(v.value) + (v.unit ? ' ' + v.unit : '')), h('td', { class: 'flag' }, (v.flags || []).join(' ') + (v.convertedFrom ? ' converted from ' + v.convertedFrom : '')))))),
          h('small', { class: 'muted' }, 'Provenance: ' + i.provenance.driver + ' · config hash ' + String(i.provenance.configHash).slice(0, 12) + ' · adapters ' + (i.deliveries || []).map(d => d.adapter).join(', ')));
      }
    } catch (e) { clear(pendBox).append(h('p', { class: 'err' }, e.message)); }
  }

  review(o, tpl, subject) {
    const inputs = {};
    const quality = o.status === 'HELD_QUALITY';
    return h('div', { class: 'review' },
      h('div', null, h('b', null, o.status), ' ', h('small', null, (o.holdReason || '') + ' · ' + (o.quality?.flags || []).join(' '))),
      h('table', null, h('tbody', null, (o.measurements || []).map(m => {
        const inp = h('input', { value: String(m.value), 'aria-label': m.key, disabled: quality || typeof m.value !== 'number' ? 'disabled' : null });
        inputs[m.key] = [inp, m.value];
        return h('tr', null, h('td', null, m.display || m.key), h('td', null, inp), h('td', null, m.unit || ''), h('td', { class: 'flag' }, (m.flags || []).join(' ') + (m.original ? ' (device ' + m.original.value + ' ' + m.original.unit + ')' : '')));
      }))),
      quality ? h('p', { class: 'err' }, 'Quality hold (calibration / QC lot) – cannot be overridden here; record calibration or QC first.') :
        h('div', { class: 'actions' },
          h('button', { class: 'btn primary', type: 'button', onclick: async () => {
            const corrections = {};
            for (const [k, [inp, orig]] of Object.entries(inputs)) if (typeof orig === 'number' && String(orig) !== inp.value) corrections[k] = inp.value;
            try { await api('POST', '/api/capture/observations/' + o.id + '/accept', { corrections }); this.say('Accepted and filed', 'ok'); this.refresh(tpl, subject); } catch (e) { this.say(e.message, 'err'); }
          } }, 'Accept & file'),
          h('button', { class: 'btn ghost', type: 'button', onclick: async () => { await api('POST', '/api/capture/observations/' + o.id + '/reject', { reason: 'clinician rejected' }); this.say('Rejected', 'info'); this.refresh(tpl, subject); } }, 'Reject')));
  }

  async fallback(tpl, station, subject) {
    const chain = await api('GET', '/api/capture/fallback?templateId=' + encodeURIComponent(tpl) + '&station=' + encodeURIComponent(station));
    const box = this.shadowRoot.querySelector('.pending');
    clear(box).append(h('h4', null, 'Fallback chain'), h('ol', null, chain.map(c => h('li', null, c.step.replaceAll('_', ' ').toLowerCase() + ': ' + c.label))));
  }

  async form(d, tpl, subject) {
    const box = this.shadowRoot.querySelector('.pending');
    const t = await api('GET', '/api/templates/' + encodeURIComponent(tpl));
    const fields = t.sections.flatMap(s => s.fields);
    const vals = {};
    const ocr = h('textarea', { rows: 3 }, 'OPTEC 6500  RIGHT 20/20  LEFT 20/25  BOTH 20/20  COLOR 14/14  STEREO 40');
    const att = { model: h('input', { value: d.name }), serial: h('input', { value: '' }), calibrationDate: h('input', { type: 'date' }), attestedBy: h('input', { value: 'nurse' }) };
    clear(box).append(h('h4', null, d.option === 'P9' ? 'Photo + OCR (verification required)' : 'Manual entry with device attestation'),
      d.option === 'P9' ? h('label', null, 'Recognised text', ocr) : h('div', null, fields.map(f => { vals[f.id] = h('input', { 'aria-label': f.label }); return h('label', { class: 'row' }, f.label + (f.unit ? ' (' + f.unit + ')' : ''), vals[f.id]); }),
        h('h4', null, 'Attestation'), Object.entries(att).map(([k, el]) => h('label', { class: 'row' }, k, el))),
      h('button', { class: 'btn primary', type: 'button', onclick: async () => {
        const body = d.option === 'P9' ? { text: ocr.value, ocrConfidence: 0.9, subject } : { subject, values: Object.fromEntries(Object.entries(vals).filter(([, el]) => el.value).map(([k, el]) => [k, isNaN(+el.value) ? el.value : +el.value])),
          attestation: Object.fromEntries(Object.entries(att).map(([k, el]) => [k, el.value])) };
        try { const r = await api('POST', '/api/capture/form/' + d.configId, body); this.say('Submitted: ' + r.status + (r.error ? ' – ' + r.error : ''), r.status === 'DLQ' ? 'err' : 'ok'); setTimeout(() => this.refresh(tpl, subject), 300); }
        catch (e) { this.say(e.message, 'err'); }
      } }, 'Submit for verification'));
  }
}
customElements.define('intimed-capture', IntiMedCapture);
