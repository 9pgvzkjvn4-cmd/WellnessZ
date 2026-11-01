import { h, clear, get, post, guard, badge, table, card, field, input, select, button, modal, pre, fmt } from '../lib.js';
import '../intimed-capture.js';

export async function capturePage(main) {
  const tpls = await get('/api/templates');
  const subjects = await get('/api/context/subjects').catch(() => []);
  const tpl = select(tpls.map(t => [t.id, t.id + ' · ' + t.name]), 'TPL-18');
  const station = input({ value: 'OH-SPIRO-1', placeholder: 'station or bed' });
  const subj = select(subjects.map(s => [s.id, s.id + ' · ' + s.name]), 'EMP-700102');
  const host = h('div', { class: 'host' });
  const mount = () => {
    clear(host).append(h('div', { class: 'host-form' }, h('h3', null, 'Host product form – ', tpls.find(t => t.id === tpl.value)?.name),
      h('p', { class: 'muted small' }, 'This panel plays the host product (EHR / OH platform). The capture component below is IntiMed\'s embeddable web component (EP-1).'),
      h('intimed-capture', { template: tpl.value, station: station.value, subject: subj.value })));
  };
  [tpl, station, subj].forEach(e => e.addEventListener('change', mount));
  main.append(h('h1', null, 'Capture from device'),
    h('p', { class: 'lead' }, 'One tap for the clinician: certified, in-calibration devices at this station; preview with quality flags; accept, correct or reject; fallback when a device is unavailable.'),
    h('div', { class: 'toolbar' }, field('Template', tpl), field('Station / bed', station), field('Subject', subj)), host);
  mount();
  const recent = await get('/api/capture/recent?n=25').catch(() => []);
  main.append(card('Recent readings (live)', table([{ label: 'Received', key: 'receivedAt' }, { label: 'Status', render: o => badge(o.status) }, { label: 'Subject', render: o => o.subject?.ref || '—' },
    { label: 'Association', render: o => (o.subject?.method || '') + ' ' + (o.subject?.confidence ?? '') }, { label: 'Station', render: o => o.context?.station },
    { label: 'Values', render: o => (o.measurements || []).slice(0, 5).map(m => m.key + ' ' + m.value + (m.unit ? ' ' + m.unit : '')).join(' · ') },
    { label: 'Flags', render: o => (o.quality?.flags || []).join(' ') }], recent, { compact: true })));
}
