import '/assets/intimed-capture.js';
const params = new URLSearchParams(location.search);
const el = document.createElement('intimed-capture');
el.setAttribute('template', params.get('template') || 'TPL-18');
el.setAttribute('station', params.get('station') || 'OH-SPIRO-1');
el.setAttribute('subject', params.get('subject') || 'EMP-700102');
document.getElementById('slot').appendChild(el);
