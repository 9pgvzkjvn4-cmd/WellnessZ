/*
 * WellnessZ DEVICE DEMO PACK – codecs and the client-side integration pipeline (window.DXC).
 *
 * Everything here is computed, nothing is canned: HL7 v2 segment parsing and ACK, Bluetooth GATT decoding with
 * IEEE-11073 16-bit SFLOAT / 32-bit FLOAT, a small XML parser with an XPath subset (POCT1-A, MUSE), RFC 4180 CSV,
 * GDT (xDT) line parsing with length checks, JSON pointer, a FHIRPath subset, DICOM PS3.18 JSON, serial regex frames,
 * SHA-256 / SHA-1 / HMAC-SHA256 / UUIDv5, MDC → LOINC / UCUM normalisation with unit conversion, association with
 * confidence thresholds, the quality-gate catalogue and FHIR R4 Observation / Provenance shaping. The driver package
 * (wellnessz.device-driver/1) drives the parsing, exactly as in the engine.
 *
 * Pure functions; runs in the browser and in Node (demo-devices/test/*.test.js).
 */
(function (root) {
  'use strict';

  // =====================================================================================
  // Bytes, hashes, HMAC, UUIDv5
  // =====================================================================================
  function utf8(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) { var c2 = str.charCodeAt(i + 1); if (c2 >= 0xdc00 && c2 <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00); i++; } }
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }
  function toBytes(x) { return typeof x === 'string' ? utf8(x) : Array.prototype.slice.call(x); }
  function hex(bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16); return s; }
  function fromHex(h) {
    h = String(h).replace(/\s+/g, '');
    if (h.length % 2 || /[^0-9a-fA-F]/.test(h)) throw perr('parse_failed', 'Characteristic value is not valid hexadecimal');
    var out = []; for (var i = 0; i < h.length; i += 2) out.push(parseInt(h.substr(i, 2), 16)); return out;
  }
  var K256 = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
  function pad64(bytes) {
    var m = bytes.slice(), bitLen = bytes.length * 8;
    m.push(0x80); while (m.length % 64 !== 56) m.push(0);
    var hi = Math.floor(bitLen / 0x100000000), lo = bitLen >>> 0;
    m.push((hi >>> 24) & 255, (hi >>> 16) & 255, (hi >>> 8) & 255, hi & 255, (lo >>> 24) & 255, (lo >>> 16) & 255, (lo >>> 8) & 255, lo & 255);
    return m;
  }
  function sha256Bytes(input) {
    var m = pad64(toBytes(input));
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19], w = new Array(64);
    for (var o = 0; o < m.length; o += 64) {
      for (var i = 0; i < 16; i++) w[i] = (m[o + 4 * i] << 24) | (m[o + 4 * i + 1] << 16) | (m[o + 4 * i + 2] << 8) | m[o + 4 * i + 3];
      for (i = 16; i < 64; i++) {
        var a0 = w[i - 15], a1 = w[i - 2];
        var s0 = ((a0 >>> 7) | (a0 << 25)) ^ ((a0 >>> 18) | (a0 << 14)) ^ (a0 >>> 3);
        var s1 = ((a1 >>> 17) | (a1 << 15)) ^ ((a1 >>> 19) | (a1 << 13)) ^ (a1 >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (i = 0; i < 64; i++) {
        var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        var t1 = (h + S1 + ((e & f) ^ (~e & g)) + K256[i] + w[i]) | 0;
        var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        var t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0; H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    var out = []; H.forEach(function (x) { out.push((x >>> 24) & 255, (x >>> 16) & 255, (x >>> 8) & 255, x & 255); }); return out;
  }
  function sha256(input) { return hex(sha256Bytes(input)); }
  function sha1Bytes(input) {
    var m = pad64(toBytes(input));
    var H = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0], w = new Array(80);
    for (var o = 0; o < m.length; o += 64) {
      for (var i = 0; i < 16; i++) w[i] = (m[o + 4 * i] << 24) | (m[o + 4 * i + 1] << 16) | (m[o + 4 * i + 2] << 8) | m[o + 4 * i + 3];
      for (i = 16; i < 80; i++) { var x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]; w[i] = (x << 1) | (x >>> 31); }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4];
      for (i = 0; i < 80; i++) {
        var f, k;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5a827999; } else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; } else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc; } else { f = b ^ c ^ d; k = 0xca62c1d6; }
        var t = (((a << 5) | (a >>> 27)) + f + e + k + w[i]) | 0; e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = t;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0; H[4] = (H[4] + e) | 0;
    }
    var out = []; H.forEach(function (x) { out.push((x >>> 24) & 255, (x >>> 16) & 255, (x >>> 8) & 255, x & 255); }); return out;
  }
  function hmacSha256(key, msg) {
    var k = toBytes(key); if (k.length > 64) k = sha256Bytes(k); while (k.length < 64) k.push(0);
    var ipad = k.map(function (b) { return b ^ 0x36; }), opad = k.map(function (b) { return b ^ 0x5c; });
    return hex(sha256Bytes(opad.concat(sha256Bytes(ipad.concat(toBytes(msg))))));
  }
  /** Constant-time comparison of two hex strings (as the authenticator does). */
  function safeEqual(a, b) { a = String(a || ''); b = String(b || ''); var r = a.length ^ b.length; for (var i = 0; i < Math.max(a.length, b.length); i++) r |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0); return r === 0; }
  var NS_OBSERVATION = '6f1c5e0a-3b1d-5a7e-9c51-2d0e4f8a9b10';
  function uuidv5(name, ns) {
    var nsb = fromHex((ns || NS_OBSERVATION).replace(/-/g, ''));
    var h = sha1Bytes(nsb.concat(utf8(name))).slice(0, 16);
    h[6] = (h[6] & 0x0f) | 0x50; h[8] = (h[8] & 0x3f) | 0x80;
    var s = hex(h); return s.slice(0, 8) + '-' + s.slice(8, 12) + '-' + s.slice(12, 16) + '-' + s.slice(16, 20) + '-' + s.slice(20);
  }
  function canonicalJson(v) {
    if (Array.isArray(v)) return '[' + v.map(canonicalJson).join(',') + ']';
    if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map(function (k) { return JSON.stringify(k) + ':' + canonicalJson(v[k]); }).join(',') + '}';
    return JSON.stringify(v);
  }
  function b64ToBytes(b64) {
    var A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', out = [], buf = 0, bits = 0;
    for (var i = 0; i < b64.length; i++) { var v = A.indexOf(b64[i]); if (v < 0) continue; buf = (buf << 6) | v; bits += 6; if (bits >= 8) { bits -= 8; out.push((buf >> bits) & 255); } }
    return out;
  }

  // =====================================================================================
  // Errors
  // =====================================================================================
  function perr(code, message, detail) { var e = new Error(message); e.name = 'PipelineError'; e.code = code; e.detail = detail || null; return e; }

  // =====================================================================================
  // HL7 v2 (ER7)
  // =====================================================================================
  function parseHl7(raw) {
    var text = String(raw).replace(/^\u000b/, '').replace(/\u001c\r?$/, '');
    var lines = text.split(/\r\n|\r|\n/).filter(function (l) { return l.trim().length; });
    if (!lines.length || lines[0].slice(0, 3) !== 'MSH') throw perr('parse_failed', 'Message does not start with an MSH segment');
    var fs = lines[0].charAt(3), enc = lines[0].slice(4, 8);
    var comp = enc.charAt(0) || '^', rep = enc.charAt(1) || '~', sub = enc.charAt(3) || '&';
    var segs = lines.map(function (l, i) {
      if (!/^[A-Z][A-Z0-9]{2}/.test(l)) throw perr('parse_failed', 'Segment ' + (i + 1) + ' has no valid segment id');
      var parts = l.split(fs);
      var name = parts[0], fields;
      if (name === 'MSH') fields = [null, fs].concat(parts.slice(1)); else fields = [null].concat(parts.slice(1));
      return { name: name, fields: fields, raw: l };
    });
    var msh = segs[0];
    return { segments: segs, delimiters: { field: fs, component: comp, repetition: rep, subcomponent: sub, escape: enc.charAt(2) },
      messageType: (msh.fields[9] || '').split(comp).slice(0, 2).join('^'), controlId: msh.fields[10] || '', version: msh.fields[12] || '',
      sendingApplication: (msh.fields[3] || '').split(comp)[0], sendingFacility: (msh.fields[4] || '').split(comp)[0] };
  }
  function hl7Value(msg, seg, spec) {
    // spec "3.1" or "5" (field.component[.subcomponent]); first repetition only
    var p = spec.split('.').map(Number);
    var f = seg.fields[p[0]]; if (f == null) return null;
    f = f.split(msg.delimiters.repetition)[0];
    if (p.length === 1) return f === '' ? null : f;
    var c = f.split(msg.delimiters.component)[p[1] - 1]; if (c == null) return null;
    if (p.length === 2) return c === '' ? null : c;
    var s = c.split(msg.delimiters.subcomponent)[p[2] - 1]; return s == null || s === '' ? null : s;
  }
  function hl7Select(msg, selector) {
    // "PID-3.1" | "OBR-7" | "OBX[OBX-3.1=150021]/OBX-5"
    var m = /^([A-Z][A-Z0-9]{2})(?:\[([A-Z0-9]{3})-([\d.]+)=([^\]]+)\])?(?:\/([A-Z0-9]{3})-([\d.]+)|-([\d.]+))$/.exec(selector);
    if (!m) throw perr('driver_invalid', 'Unsupported HL7 selector ' + selector);
    var segName = m[1], spec = m[6] || m[7];
    var cand = msg.segments.filter(function (s) { return s.name === segName; });
    if (m[2]) {
      var want = m[4].split('^')[0];
      cand = cand.filter(function (s) { return hl7Value(msg, s, m[3]) === want; });
    }
    return cand.length ? hl7Value(msg, cand[0], spec) : null;
  }
  function hl7Ack(msg, code, text, at) {
    var d = msg && msg.delimiters ? msg.delimiters : { field: '|', component: '^' };
    var f = d.field, ts = (at || '').replace(/[-:T]/g, '').slice(0, 14);
    return ['MSH' + f + '^~\\&' + f + 'WELLNESSZ' + f + 'WZ' + f + ((msg && msg.sendingApplication) || '') + f + ((msg && msg.sendingFacility) || '') + f + ts + f + f + 'ACK^R01^ACK' + f + 'ACK' + ((msg && msg.controlId) || '') + f + 'P' + f + ((msg && msg.version) || '2.6'),
      'MSA' + f + code + f + ((msg && msg.controlId) || '') + (text ? f + text : '')].join('\r');
  }

  // =====================================================================================
  // Bluetooth GATT – IEEE 11073-20601 SFLOAT / FLOAT, little-endian structures
  // =====================================================================================
  function sfloatDecode(raw16) {
    if (raw16 === 0x07FF) return NaN; if (raw16 === 0x0800) return null; if (raw16 === 0x07FE) return Infinity; if (raw16 === 0x0802) return -Infinity; if (raw16 === 0x0801) return null;
    var mant = raw16 & 0x0FFF; if (mant >= 0x0800) mant -= 0x1000;
    var exp = (raw16 >> 12) & 0x0F; if (exp >= 0x08) exp -= 0x10;
    return round(mant * Math.pow(10, exp), Math.max(0, -exp));
  }
  function sfloatEncode(value, exponent) {
    var exp = exponent == null ? (Number.isInteger(value) ? 0 : -1) : exponent;
    var mant = Math.round(value / Math.pow(10, exp));
    while ((mant > 2047 || mant < -2048) && exp < 7) { exp++; mant = Math.round(value / Math.pow(10, exp)); }
    return ((exp & 0x0F) << 12) | (mant & 0x0FFF);
  }
  function floatDecode(raw32) {
    var mant = raw32 & 0x00FFFFFF; if (mant === 0x007FFFFF) return NaN; if (mant === 0x00800000) return null;
    if (mant >= 0x00800000) mant -= 0x01000000;
    var exp = (raw32 >>> 24) & 0xFF; if (exp >= 0x80) exp -= 0x100;
    return round(mant * Math.pow(10, exp), Math.max(0, -exp));
  }
  function floatEncode(value, exponent) {
    var exp = exponent == null ? -1 : exponent; var mant = Math.round(value / Math.pow(10, exp));
    return (((exp & 0xFF) << 24) | (mant & 0x00FFFFFF)) >>> 0;
  }
  function parseSelectorOpts(sel) {
    var parts = sel.split(':'), o = { type: parts[0] };
    parts.slice(1).forEach(function (p) { var kv = p.split('='); o[kv[0]] = kv[1] === undefined ? true : (isNaN(Number(kv[1])) ? kv[1] : Number(kv[1])); });
    return o;
  }
  var GATT_SIZE = { UINT8: 1, UINT16: 2, SFLOAT: 2, FLOAT: 4, DATETIME: 7, FLAG: 0 };
  function decodeGattValue(driver, bytes) {
    var fields = driver.parse.fields, off = 0, flags = 0, out = {}, ann = [];
    fields.forEach(function (fd, idx) {
      var o = parseSelectorOpts(fd.selector);
      if (o.type === 'FLAG') { out[fd.key] = { value: !!(flags & (1 << o.bit)), unit: null }; return; }
      if (o.flagBit != null && !(flags & (1 << o.flagBit))) return;
      var size = GATT_SIZE[o.type]; if (size == null) throw perr('driver_invalid', 'Unsupported GATT selector ' + fd.selector);
      if (off + size > bytes.length) throw perr('parse_failed', 'Characteristic value truncated: ' + fd.key + ' needs ' + size + ' byte(s) at offset ' + off + ' but only ' + bytes.length + ' byte(s) received');
      var b = bytes.slice(off, off + size), v, unit = fd.unit || null, rawHex = hex(b);
      var alt = o.unitFlagBit != null && (flags & (1 << o.unitFlagBit));
      if (o.type === 'UINT8') v = b[0];
      else if (o.type === 'UINT16') v = b[0] | (b[1] << 8);
      else if (o.type === 'SFLOAT') v = sfloatDecode(b[0] | (b[1] << 8));
      else if (o.type === 'FLOAT') v = floatDecode((b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)) >>> 0);
      else if (o.type === 'DATETIME') {
        var y = b[0] | (b[1] << 8);
        if (y < 1582 || b[2] < 1 || b[2] > 12 || b[3] < 1 || b[3] > 31 || b[4] > 23 || b[5] > 59 || b[6] > 59) throw perr('parse_failed', 'Invalid GATT date time at offset ' + off);
        v = y + '-' + pad2(b[2]) + '-' + pad2(b[3]) + 'T' + pad2(b[4]) + ':' + pad2(b[5]) + ':' + pad2(b[6]) + 'Z';
      }
      if (idx === 0) flags = v;
      if (typeof v === 'number' && (o.type === 'UINT16' || o.type === 'UINT8') && (o.scale || o.altScale)) v = round(v * (alt && o.altScale ? o.altScale : (o.scale || 1)), 4);
      if (alt && o.altUnit) unit = o.altUnit;
      out[fd.key] = { value: v, unit: unit };
      ann.push({ offset: off, length: size, hex: rawHex, field: fd.key, type: o.type + (o.scale ? ' ×' + (alt && o.altScale ? o.altScale : o.scale) : ''), value: v, unit: unit });
      off += size;
    });
    return { values: out, annotations: ann, flags: flags, consumed: off, length: bytes.length };
  }

  // =====================================================================================
  // XML (small, strict) and an XPath subset
  // =====================================================================================
  function decodeEntities(s) { return s.replace(/&(lt|gt|amp|quot|apos|#\d+|#x[0-9a-fA-F]+);/g, function (m, e) { return e === 'lt' ? '<' : e === 'gt' ? '>' : e === 'amp' ? '&' : e === 'quot' ? '"' : e === 'apos' ? "'" : e[1] === 'x' ? String.fromCharCode(parseInt(e.slice(2), 16)) : String.fromCharCode(parseInt(e.slice(1), 10)); }); }
  function parseXml(text) {
    var s = String(text), i = 0, rootEl = null, stack = [];
    function err(msg) { throw perr('parse_failed', 'XML not well-formed: ' + msg); }
    while (i < s.length) {
      var lt = s.indexOf('<', i);
      var txt = lt < 0 ? s.slice(i) : s.slice(i, lt);
      if (txt.trim()) { if (!stack.length) err('text outside the root element'); stack[stack.length - 1].text += decodeEntities(txt); }
      if (lt < 0) break;
      if (s.startsWith('<?', lt)) { var e1 = s.indexOf('?>', lt); if (e1 < 0) err('unterminated declaration'); i = e1 + 2; continue; }
      if (s.startsWith('<!--', lt)) { var e2 = s.indexOf('-->', lt); if (e2 < 0) err('unterminated comment'); i = e2 + 3; continue; }
      if (s.startsWith('<![CDATA[', lt)) { var e3 = s.indexOf(']]>', lt); if (e3 < 0 || !stack.length) err('bad CDATA'); stack[stack.length - 1].text += s.slice(lt + 9, e3); i = e3 + 3; continue; }
      var gt = s.indexOf('>', lt); if (gt < 0) err('unterminated tag at offset ' + lt);
      var tag = s.slice(lt + 1, gt);
      if (tag[0] === '/') {
        var name = tag.slice(1).trim(), top = stack.pop();
        if (!top || top.name !== name) err('closing tag </' + name + '> does not match <' + (top ? top.name : '?') + '>');
        i = gt + 1; continue;
      }
      var selfClose = /\/\s*$/.test(tag); if (selfClose) tag = tag.replace(/\/\s*$/, '');
      var nm = /^([A-Za-z_][\w.\-:]*)/.exec(tag); if (!nm) err('invalid tag name at offset ' + lt);
      var el = { name: nm[1], attrs: {}, children: [], text: '', parent: stack.length ? stack[stack.length - 1] : null };
      var rest = tag.slice(nm[1].length), am, are = /\s+([A-Za-z_][\w.\-:]*)\s*=\s*("([^"]*)"|'([^']*)')/g, consumed = 0;
      while ((am = are.exec(rest))) { el.attrs[am[1]] = decodeEntities(am[3] != null ? am[3] : am[4]); consumed += am[0].length; }
      if (rest.replace(/\s+/g, '').length && rest.trim().length && consumed === 0 && rest.trim()) err('malformed attributes in <' + el.name + '>');
      if (el.parent) el.parent.children.push(el); else { if (rootEl) err('more than one root element'); rootEl = el; }
      if (!selfClose) stack.push(el);
      i = gt + 1;
    }
    if (stack.length) err('element <' + stack[stack.length - 1].name + '> is not closed (truncated document)');
    if (!rootEl) err('no root element');
    return rootEl;
  }
  function textOf(el) { return (el.text + el.children.map(textOf).join('')).trim(); }
  function xpathNodes(rootEl, path, context) {
    var steps = path.split('/'), nodes;
    if (path[0] === '/') { steps.shift(); var first = steps.shift(); var fm = stepParts(first); nodes = rootEl.name === fm.name && predOk(rootEl, fm.pred) ? [rootEl] : []; }
    else nodes = [context || rootEl];
    for (var k = 0; k < steps.length; k++) {
      var st = steps[k]; if (!st) continue;
      if (st[0] === '@') return nodes.map(function (n) { return n.attrs[st.slice(1)]; }).filter(function (v) { return v != null; });
      if (st === '..') { nodes = nodes.map(function (n) { return n.parent; }).filter(Boolean); continue; }
      var sp = stepParts(st), next = [];
      nodes.forEach(function (n) { n.children.forEach(function (c) { if (c.name === sp.name && predOk(c, sp.pred)) next.push(c); }); });
      nodes = next;
    }
    return nodes.map(textOf);
  }
  function stepParts(st) { var m = /^([^\[]+)(?:\[@([\w.\-]+)='([^']*)'\])?$/.exec(st); return { name: m ? m[1] : st, pred: m && m[2] ? { attr: m[2], value: m[3] } : null }; }
  function predOk(n, p) { return !p || n.attrs[p.attr] === p.value; }
  function xpath(rootEl, expr, context) {
    var m;
    if ((m = /^concat\((.*)\)$/.exec(expr))) return splitArgs(m[1]).map(function (a) { return /^'.*'$/.test(a) ? a.slice(1, -1) : (xpathNodes(rootEl, a, context)[0] || ''); }).join('');
    if ((m = /^string-join\((.*),\s*'([^']*)'\)$/.exec(expr))) return xpathNodes(rootEl, m[1], context).join(m[2]);
    var r = xpathNodes(rootEl, expr, context); return r.length ? r[0] : null;
  }
  function splitArgs(s) { var out = [], cur = '', q = false; for (var i = 0; i < s.length; i++) { var c = s[i]; if (c === "'") q = !q; if (c === ',' && !q) { out.push(cur.trim()); cur = ''; } else cur += c; } if (cur.trim()) out.push(cur.trim()); return out; }

  // =====================================================================================
  // CSV (RFC 4180) and GDT (xDT)
  // =====================================================================================
  function parseCsv(text, delim) {
    delim = delim || ','; var rows = [], row = [], cur = '', q = false, i = 0, s = String(text);
    while (i < s.length) {
      var c = s[i];
      if (q) { if (c === '"') { if (s[i + 1] === '"') { cur += '"'; i += 2; continue; } q = false; i++; continue; } cur += c; i++; continue; }
      if (c === '"' && cur === '') { q = true; i++; continue; }
      if (c === delim) { row.push(cur); cur = ''; i++; continue; }
      if (c === '\r' || c === '\n') { row.push(cur); cur = ''; if (row.some(function (x) { return x !== ''; })) rows.push(row); row = []; if (c === '\r' && s[i + 1] === '\n') i++; i++; continue; }
      cur += c; i++;
    }
    if (q) throw perr('parse_failed', 'CSV: unterminated quoted field in row ' + (rows.length + 1));
    if (cur !== '' || row.length) { row.push(cur); if (row.some(function (x) { return x !== ''; })) rows.push(row); }
    if (rows.length < 2) throw perr('parse_failed', 'CSV: a header row and at least one data row are required');
    var header = rows[0].map(function (h) { return h.trim(); });
    return rows.slice(1).map(function (r, n) {
      if (r.length !== header.length) throw perr('parse_failed', 'CSV: row ' + (n + 2) + ' has ' + r.length + ' fields, header has ' + header.length);
      var o = {}; header.forEach(function (h, k) { o[h] = r[k]; }); return o;
    });
  }
  function parseGdt(text) {
    var lines = String(text).split(/\r\n|\n|\r/).filter(function (l) { return l.length; });
    var out = lines.map(function (l, n) {
      if (!/^\d{7}/.test(l)) throw perr('parse_failed', 'GDT line ' + (n + 1) + ' does not start with a 3-digit length and a 4-digit field id');
      var len = Number(l.slice(0, 3)), field = l.slice(3, 7), content = l.slice(7);
      var actual = utf8(l).length + 2;
      if (len !== actual) throw perr('parse_failed', 'GDT line ' + (n + 1) + ' (field ' + field + '): declared length ' + len + ' ≠ actual ' + actual);
      return { field: field, content: content, length: len };
    });
    if (!out.length || out[0].field !== '8000') throw perr('parse_failed', 'GDT record must start with field 8000 (record type)');
    return out;
  }
  function gdtSelect(lines, sel) {
    // "3000" | "3102+3101" | "8410=FEV1/8420"
    var m = /^(\d{4})=([^/]+)\/(\d{4})$/.exec(sel);
    if (m) {
      for (var i = 0; i < lines.length; i++) if (lines[i].field === m[1] && lines[i].content === m[2]) {
        for (var j = i + 1; j < lines.length && lines[j].field !== m[1]; j++) if (lines[j].field === m[3]) return lines[j].content;
        return null;
      }
      return null;
    }
    var parts = sel.split('+').map(function (f) { var l = lines.filter(function (x) { return x.field === f; })[0]; return l ? l.content : null; });
    if (parts.some(function (p) { return p == null; })) return null;
    return parts.join(sel.indexOf('+') >= 0 && /^3/.test(sel) ? ' ' : '');
  }

  // =====================================================================================
  // Times
  // =====================================================================================
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function round(v, d) { var f = Math.pow(10, d == null ? 2 : d); return Math.round(v * f) / f; }
  /** Parse a device time with a Java-style pattern; local times are taken as UTC in this demo (Europe/London = UTC in late October). */
  function parseTime(value, format) {
    if (value == null || value === '') return null;
    var v = String(value).trim(), m;
    if (!format || format === 'ISO' || format === 'ISO_OFFSET' || format === 'GATT_DATETIME') { var t = Date.parse(v); return isNaN(t) ? null : new Date(t).toISOString(); }
    var map = { yyyy: 'Y', MM: 'M', dd: 'D', HH: 'h', mm: 'm', ss: 's' };
    var f = format.replace(/'T'/g, 'T'), re = '', order = [], k = 0;
    while (k < f.length) {
      var tok = ['yyyy', 'MM', 'dd', 'HH', 'mm', 'ss'].filter(function (t2) { return f.startsWith(t2, k); })[0];
      if (tok) { re += tok === 'yyyy' ? '(\\d{4})' : '(\\d{2})'; order.push(map[tok]); k += tok.length; continue; }
      if (f[k] === 'Z') { re += '([+-]\\d{4}|Z)?'; order.push('z'); k++; continue; }
      re += f[k].replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); k++;
    }
    m = new RegExp('^' + re + '$').exec(v.replace(/\.\d+$/, ''));
    if (!m) return null;
    var p = { Y: 1970, M: 1, D: 1, h: 0, m: 0, s: 0, z: null };
    order.forEach(function (o, n) { p[o] = o === 'z' ? m[n + 1] : Number(m[n + 1]); });
    var ms = Date.UTC(p.Y, p.M - 1, p.D, p.h, p.m, p.s);
    if (p.z && p.z !== 'Z') { var sign = p.z[0] === '-' ? -1 : 1; ms -= sign * (Number(p.z.slice(1, 3)) * 60 + Number(p.z.slice(3, 5))) * 60000; }
    return new Date(ms).toISOString();
  }
  function hl7Ts(iso) { return iso.replace(/[-:]/g, '').replace('T', '').slice(0, 14); }

  // =====================================================================================
  // Driver-driven PARSE
  // =====================================================================================
  function jsonPointer(obj, ptr) { if (ptr === '' || ptr === '/') return obj; var parts = ptr.split('/').slice(1); var cur = obj; for (var i = 0; i < parts.length; i++) { if (cur == null) return undefined; cur = cur[parts[i].replace(/~1/g, '/').replace(/~0/g, '~')]; } return cur; }
  function parseJson(raw) { try { return JSON.parse(raw); } catch (e) { throw perr('parse_failed', 'JSON not well-formed: ' + e.message); } }
  function typed(fd, v, key) {
    if (v == null || v === '') return null;
    if (fd.type === 'QUANTITY') { var n = typeof v === 'number' ? v : Number(String(v).trim()); if (!isFinite(n)) throw perr('parse_failed', 'Field ' + key + ': "' + v + '" is not a number'); return n; }
    if (fd.type === 'BOOLEAN') return v === true || v === 'true' || v === '1' || v === 1;
    if (Array.isArray(v)) return v.join(',');
    return typeof v === 'object' ? JSON.stringify(v) : String(v);
  }
  function fhirSelect(obs, sel) {
    var m = /^Observation\.where\(code\.coding\.code='([^']+)'\)\.(.+)$/.exec(sel);
    var path = sel.replace(/^Observation\./, '');
    if (m) { obs = obs.filter(function (o) { return (o.code && o.code.coding || []).some(function (c) { return c.code === m[1]; }); }); path = m[2]; }
    for (var i = 0; i < obs.length; i++) { var cur = obs[i]; path.split('.').forEach(function (p) { cur = cur == null ? undefined : (Array.isArray(cur) ? cur[0] && cur[0][p] : cur[p]); }); if (cur !== undefined) return cur; }
    return undefined;
  }
  function dicomValue(ds, tag) {
    var el = ds[tag]; if (!el || !el.Value || !el.Value.length) return null;
    var v = el.Value[0]; if (el.vr === 'PN' && v && typeof v === 'object') return v.Alphabetic || null; return v;
  }

  /**
   * PARSE stage: raw payload → records [{ identity, values {key: {value, unit}} , annotations? }].
   * Throws PipelineError parse_failed | profile_violation.
   */
  function parse(driver, raw, opts) {
    opts = opts || {};
    var p = driver.parse, records = [], extra = {};
    function finish(identity, getField, ann) {
      var values = {};
      p.fields.forEach(function (fd) {
        var v = getField(fd);
        var t = typed(fd, v, fd.key);
        if (t == null) { if (fd.required) throw perr(driver.adapter === 'DICOMWEB' || driver.adapter === 'FHIR' ? 'profile_violation' : 'parse_failed', 'Required field ' + fd.key + ' is missing'); return; }
        values[fd.key] = { value: t, unit: fd.unit || null };
      });
      records.push({ identity: identity, values: values, annotations: ann || null });
    }
    switch (p.type) {
      case 'HL7_SEGMENTS': {
        var msg = parseHl7(raw); extra.hl7 = msg;
        if (!/^ORU\^R01/.test(msg.messageType)) throw perr('profile_violation', 'Message type ' + msg.messageType + ' is not ORU^R01');
        if (p.identity.employee && /^PID/.test(p.identity.employee) && !msg.segments.some(function (s) { return s.name === 'PID'; })) throw perr('parse_failed', 'ORU^R01 without PID segment');
        if (!msg.segments.some(function (s) { return s.name === 'OBX'; })) throw perr('parse_failed', 'ORU^R01 without OBX segments');
        var id = {};
        Object.keys(p.identity).forEach(function (k) { var sel = p.identity[k]; id[k] = typeof sel === 'string' ? hl7Select(msg, sel) : hl7Select(msg, sel.selector); });
        if (id.name) id.name = id.name.split('^').reverse().join(' ').trim();
        if (p.identity.name && /^PID-5$/.test(p.identity.name)) { var pid = msg.segments.filter(function (s) { return s.name === 'PID'; })[0]; var nm = pid && pid.fields[5] ? pid.fields[5].split(msg.delimiters.component) : []; id.name = (nm[1] || '') + ' ' + (nm[0] || ''); id.name = id.name.trim(); }
        if (p.identity.effectiveTime) id.effectiveTime = parseTime(hl7Select(msg, p.identity.effectiveTime.selector), p.identity.effectiveTime.format);
        finish(id, function (fd) { return hl7Select(msg, fd.selector); });
        break;
      }
      case 'GATT_STRUCT': {
        var env = parseJson(raw);
        if (!env || !Array.isArray(env.records) || !env.records.length) throw perr('parse_failed', 'BLE relay envelope without records');
        env.records.forEach(function (r) {
          if (driver.protocol && driver.protocol.characteristic && r.characteristic && r.characteristic.toUpperCase() !== driver.protocol.characteristic.toUpperCase()) throw perr('profile_violation', 'Characteristic ' + r.characteristic + ' is not ' + driver.protocol.characteristic);
          var dec = decodeGattValue(driver, fromHex(r.value));
          var idn = { deviceSerial: r.peripheral && r.peripheral.name, peripheralHash: r.peripheral && r.peripheral.addressHash, badge: r.badge || null,
            effectiveTime: dec.values.timestamp ? parseTime(dec.values.timestamp.value) : parseTime(r.receivedAt) };
          finish(idn, function (fd) { return dec.values[fd.key] && fd.type ? dec.values[fd.key].value : undefined; }, dec);
          // GATT carries per-field units (alternate unit flag) – keep them
          var rec = records[records.length - 1]; Object.keys(rec.values).forEach(function (k) { if (dec.values[k]) rec.values[k].unit = dec.values[k].unit; });
        });
        break;
      }
      case 'POCT1A_XPATH':
      case 'XPATH': {
        var doc = parseXml(raw);
        var ident = {};
        Object.keys(p.identity).forEach(function (k) { var sel = p.identity[k]; ident[k] = typeof sel === 'string' ? xpath(doc, sel) : xpath(doc, sel.selector); });
        if (p.identity.effectiveTime) ident.effectiveTime = parseTime(xpath(doc, p.identity.effectiveTime.selector), p.identity.effectiveTime.format);
        var recs = p.records ? xpathNodesEl(doc, p.records) : [null];
        if (p.records && !recs.length) throw perr('parse_failed', 'No ' + p.records + ' record in the message');
        recs.forEach(function (ctxEl) { finish(Object.assign({}, ident), function (fd) { return fd.selector[0] === '/' ? xpath(doc, fd.selector) : xpath(doc, fd.selector, ctxEl); }); });
        extra.xmlRoot = doc.name;
        break;
      }
      case 'CSV_COLUMNS': {
        var rows = parseCsv(raw, driver.protocol && driver.protocol.csvDelimiter);
        rows.forEach(function (row) {
          var idc = {};
          Object.keys(p.identity).forEach(function (k) { var sel = p.identity[k]; idc[k] = typeof sel === 'string' ? (row[sel] || null) : null; });
          if (p.identity.effectiveTime) idc.effectiveTime = parseTime(row[p.identity.effectiveTime.selector], p.identity.effectiveTime.format);
          finish(idc, function (fd) { return row[fd.selector]; });
        });
        break;
      }
      case 'GDT_FIELDS': {
        var lines = parseGdt(raw); extra.gdt = lines;
        var idg = {};
        Object.keys(p.identity).forEach(function (k) { var sel = p.identity[k]; idg[k] = typeof sel === 'string' ? gdtSelect(lines, sel) : null; });
        if (p.identity.effectiveTime) idg.effectiveTime = parseTime(gdtSelect(lines, p.identity.effectiveTime.selector), p.identity.effectiveTime.format);
        if (idg.dateOfBirth && /^\d{8}$/.test(idg.dateOfBirth)) idg.dateOfBirth = idg.dateOfBirth.slice(4) + '-' + idg.dateOfBirth.slice(2, 4) + '-' + idg.dateOfBirth.slice(0, 2);
        if (idg.sex) idg.sex = idg.sex === '1' ? 'M' : idg.sex === '2' ? 'F' : idg.sex;
        var orderRef = gdtSelect(lines, '8310'); if (orderRef) idg.orderRef = orderRef;
        finish(idg, function (fd) { return gdtSelect(lines, fd.selector); });
        break;
      }
      case 'JSON_POINTER': {
        var obj = parseJson(raw), idj = {};
        Object.keys(p.identity).forEach(function (k) { var sel = p.identity[k]; idj[k] = typeof sel === 'string' ? jsonPointer(obj, sel) : null; });
        if (p.identity.effectiveTime) idj.effectiveTime = parseTime(jsonPointer(obj, p.identity.effectiveTime.selector), p.identity.effectiveTime.format);
        finish(idj, function (fd) { return jsonPointer(obj, fd.selector); });
        break;
      }
      case 'FHIR_PATH': {
        var res = parseJson(raw);
        if (!res || (res.resourceType !== 'Bundle' && res.resourceType !== 'Observation')) throw perr('profile_violation', 'Resource type must be Bundle or Observation');
        var obs = res.resourceType === 'Bundle' ? (res.entry || []).map(function (e) { return e.resource; }).filter(function (r) { return r && r.resourceType === 'Observation'; }) : [res];
        if (!obs.length) throw perr('profile_violation', 'Bundle without Observation entries');
        obs.forEach(function (o, n) {
          if (!o.code || !o.code.coding || !o.code.coding.length) throw perr('profile_violation', 'Observation ' + (n + 1) + ' has no code');
          if (['final', 'amended', 'preliminary'].indexOf(o.status) < 0) throw perr('profile_violation', 'Observation ' + (n + 1) + ' status ' + o.status + ' not accepted');
          var q = o.valueQuantity; if (q && (q.system !== 'http://unitsofmeasure.org' || !q.code)) throw perr('profile_violation', 'Observation ' + (n + 1) + ': Quantity without a UCUM unit (system ' + q.system + ')');
        });
        var idf = { employee: fhirSelect(obs, p.identity.employee) || null, effectiveTime: parseTime(fhirSelect(obs, p.identity.effectiveTime.selector)) };
        finish(idf, function (fd) { return fhirSelect(obs, fd.selector); });
        break;
      }
      case 'DICOM_JSON': {
        var arr = parseJson(raw); if (!Array.isArray(arr) || !arr.length) throw perr('profile_violation', 'PS3.18 JSON must be a non-empty array of datasets');
        arr.forEach(function (ds) {
          var idd = { employee: dicomValue(ds, p.identity.employee), name: (dicomValue(ds, p.identity.name) || '').split('^').reverse().join(' '), orderRef: dicomValue(ds, p.identity.orderRef) };
          var ts = p.identity.effectiveTime.selector.split('+').map(function (t) { return String(dicomValue(ds, t) || ''); });
          idd.effectiveTime = parseTime(ts[0] + (ts[1] || '').split('.')[0], p.identity.effectiveTime.format);
          finish(idd, function (fd) { return dicomValue(ds, fd.selector); });
        });
        break;
      }
      case 'REGEX_LINES': {
        var envs = parseJson(raw);
        if (!envs || !Array.isArray(envs.records) || !envs.records.length) throw perr('parse_failed', 'Serial envelope without records');
        envs.records.forEach(function (r) {
          var text = String(r.data || ''), lns = text.split(/\r\n|\n|\r/), found = {}, hits = 0;
          p.fields.forEach(function (fd) {
            var re = new RegExp(fd.selector);
            for (var k = 0; k < lns.length; k++) { var mm = re.exec(lns[k]); if (mm) { found[fd.key] = mm.groups ? mm.groups.v : mm[1]; hits++; break; } }
          });
          if (!hits) throw perr('parse_failed', 'Serial frame matches none of the driver fields');
          var ids = { deviceSerial: found[p.identity.deviceSerial] || null, effectiveTime: parseTime(r.receivedAt), port: r.port };
          finish(ids, function (fd) { return found[fd.key]; });
        });
        break;
      }
      case 'FORM': {
        var form = parseJson(raw);
        if (!form || typeof form.values !== 'object' || !form.values) throw perr('parse_failed', 'Form capture without values');
        var idm = { employee: form.employeeId || null, effectiveTime: parseTime(form.capturedAt), operator: form.operator || null, method: form.method,
          ocrConfidence: form.ocrConfidence == null ? null : form.ocrConfidence, attestation: form.attestation || null,
          attested: { model: form.attestedModel || null, serial: form.attestedSerial || null, calibrationId: form.attestedCalibrationId || null, calibrationDate: form.attestedCalibrationDate || null } };
        finish(idm, function (fd) { return form.values[fd.selector || fd.key]; });
        break;
      }
      default: throw perr('driver_invalid', 'Unsupported parse type ' + p.type);
    }
    return { records: records, extra: extra };
  }
  function xpathNodesEl(rootEl, path) {
    var steps = path.split('/').filter(Boolean); if (steps.shift() !== rootEl.name) return [];
    var nodes = [rootEl]; steps.forEach(function (st) { var nx = []; nodes.forEach(function (n) { n.children.forEach(function (c) { if (c.name === st) nx.push(c); }); }); nodes = nx; }); return nodes;
  }

  // =====================================================================================
  // NORMALISE – MDC → LOINC / UCUM, unit conversion, decimals, predicted values, clock skew
  // =====================================================================================
  var UCUM_CONVERT = {
    'kPa>mm[Hg]': function (v) { return v * 7.50062; }, '[degF]>Cel': function (v) { return (v - 32) * 5 / 9; }, '[lb_av]>kg': function (v) { return v * 0.45359237; },
    'm>cm': function (v) { return v * 100; }, '[in_i]>cm': function (v) { return v * 2.54; }, 'g/dL>mmol/L': function (v) { return v * 0.6206; }
  };
  var DEFAULT_DECIMALS = { cm: 0, kg: 1, Cel: 1, 'mm[Hg]': 0, '/min': 0, '%': 1, L: 2, 'L/s': 2, 'dB[HL]': 0, ms: 0, dB: 1, 'g/(210.L)': 3, 'kg/m2': 1, 'ug/dL': 1 };
  var REF = {
    systolic: { high: 139, low: 90, text: '90–139 mm[Hg]' }, diastolic: { high: 89, low: 60, text: '60–89 mm[Hg]' }, pulse: { high: 100, low: 50, text: '50–100 /min' }, heartRate: { high: 100, low: 50, text: '50–100 /min' },
    spo2: { low: 94, text: '≥ 94 %' }, temperatureC: { high: 37.8, low: 35.5, text: '35.5–37.8 Cel' }, hba1c: { high: 5.9, text: '< 6.0 %' }, bll: { high: 5, text: '< 5 ug/dL' },
    qtcMs: { high: 460, text: '< 460 ms' }, brac: { high: 0.019, text: '< 0.020 g/210 L' }, lex8h: { high: 85, text: 'upper action value 85 dB(A)' }, bmi: { high: 29.9, low: 18.5, text: '18.5–29.9 kg/m2' }
  };
  function ageAt(dob, at) { if (!dob) return null; var d = new Date(dob + 'T00:00:00Z'), t = new Date(at || Date.now()); var a = t.getUTCFullYear() - d.getUTCFullYear(); if (t.getUTCMonth() < d.getUTCMonth() || (t.getUTCMonth() === d.getUTCMonth() && t.getUTCDate() < d.getUTCDate())) a--; return a; }
  /** GLI-2012 (Caucasian) predicted FEV1 / FVC – simplified: spline terms omitted. APPROXIMATION pending clinical verification (E31-BL-013). */
  function gliPredicted(sex, ageYears, heightCm) {
    var lh = Math.log(heightCm), la = Math.log(ageYears);
    var male = sex === 'M';
    var fev1 = Math.exp(male ? -10.3420 + 2.2196 * lh + 0.0574 * la : -9.6987 + 2.1211 * lh - 0.0270 * la);
    var fvc = Math.exp(male ? -11.2281 + 2.4135 * lh + 0.0715 * la : -10.4030 + 2.2633 * lh + 0.0234 * la);
    return { fev1PredictedL: round(fev1, 2), fvcPredictedL: round(fvc, 2), fev1LlnL: round(fev1 * 0.80, 2), fvcLlnL: round(fvc * 0.80, 2), method: 'GLI-2012 (approximation – spline terms omitted, pending verification E31-BL-013)' };
  }
  function normalise(driver, record, ctx) {
    ctx = ctx || {};
    var components = [], notes = [];
    driver.mappings.forEach(function (mp) {
      var f = record.values[mp.key]; if (!f) return;
      var v = f.value, unit = f.unit, converted = false, original = { value: v, unit: unit };
      if (typeof v === 'number' && mp.ucum && unit && unit !== mp.ucum) {
        var conv = UCUM_CONVERT[unit + '>' + mp.ucum] || (mp.convert && mp.convert.fromUnit === unit ? function (x) { return x * mp.convert.factor; } : null);
        if (!conv) throw perr('unit_unconvertible', 'Cannot convert ' + mp.key + ' from ' + unit + ' to ' + mp.ucum);
        v = conv(v); converted = true; unit = mp.ucum;
        notes.push(mp.key + ': ' + original.value + ' ' + original.unit + ' → ' + round(v, 2) + ' ' + unit);
      }
      if (typeof v === 'number') { var dec = mp.decimals != null ? mp.decimals : DEFAULT_DECIMALS[mp.ucum || unit]; if (dec != null) v = round(v, dec); }
      var ref = REF[mp.key] || null, interp = null;
      if (ref && typeof v === 'number') interp = ref.high != null && v > ref.high ? 'H' : ref.low != null && v < ref.low ? 'L' : 'N';
      components.push({ key: mp.key, display: mp.display || mp.key, mdc: mp.mdc || null, loinc: mp.loinc || null, ucum: mp.ucum || unit || null, value: v,
        original: converted ? original : null, converted: converted, referenceRange: ref ? ref.text : null, interpretation: interp, plausible: mp.plausible || null });
    });
    var wC = components.filter(function (c) { return c.key === 'weightKg'; })[0], hC = components.filter(function (c) { return c.key === 'heightCm'; })[0];
    if (wC && hC && !components.some(function (c) { return c.key === 'bmi'; })) {
      components.push({ key: 'bmi', display: 'Body mass index (derived)', mdc: 'MDC_RATIO_MASS_BODY_LEN_SQ', loinc: '39156-5', ucum: 'kg/m2', value: round(wC.value / Math.pow(hC.value / 100, 2), 1), original: null, converted: false, derived: true, referenceRange: REF.bmi.text, interpretation: null, plausible: { min: 10, max: 80 } });
      notes.push('BMI derived from weight and height');
    }
    var effective = record.identity.effectiveTime || ctx.receivedAt || null, skew = Number((ctx.headers || {}).edgeClockOffsetSeconds || 0), corrected = null;
    if (effective && skew) { corrected = new Date(Date.parse(effective) - skew * 1000).toISOString(); notes.push('Clock skew ' + skew + ' s: effective time ' + effective + ' → ' + corrected); }
    var predicted = null;
    if (driver.family === 'SPIROMETRY') {
      var emp = ctx.employee || null, sex = record.identity.sex || (emp && emp.sex), dob = record.identity.dateOfBirth || (emp && emp.dob);
      var h = Number(record.identity.heightCm || (emp && emp.heightCm) || 0), age = ageAt(dob, effective);
      if (sex && h && age) {
        predicted = gliPredicted(sex, age, h);
        var fev = components.filter(function (c) { return c.key === 'fev1L'; })[0], fvc = components.filter(function (c) { return c.key === 'fvcL'; })[0];
        if (fev) fev.percentPredicted = round(fev.value / predicted.fev1PredictedL * 100, 0);
        if (fvc) fvc.percentPredicted = round(fvc.value / predicted.fvcPredictedL * 100, 0);
        predicted.age = age; predicted.heightCm = h; predicted.sex = sex;
      }
    }
    return { components: components, effectiveTime: corrected || effective, deviceEffectiveTime: effective, skewSeconds: skew, notes: notes, predicted: predicted };
  }

  // =====================================================================================
  // ASSOCIATE – worklist → session → badge → vendor id (exact / normalised / fuzzy)
  // =====================================================================================
  var RESTRICTED = { DRUG_ALCOHOL: 'BREATH_ALCOHOL' };
  var CATEGORY = { VITALS: 'VITALS', SPIROMETRY: 'SPIROMETRY', AUDIOMETRY: 'AUDIOMETRY', CARDIOLOGY: 'ECG', POC: 'POC', DRUG_ALCOHOL: 'BREATH_ALCOHOL', RESP_ERGONOMICS: 'FIT_TEST',
    EXPOSURE: 'EXPOSURE', WEARABLE: 'WEARABLE', IMAGING: 'IMAGING', VISION: 'VISION' };
  function normaliseId(id, prefixes) {
    var s = String(id || '').trim().toUpperCase().replace(/[\s\-_]/g, '');
    (prefixes || ['EMP']).forEach(function (p) { if (s.indexOf(p) === 0 && s.length > p.length + 1 && /\d/.test(s[p.length])) s = 'E' + s.slice(p.length); });
    var m = /^([A-Z]+)0*(\d+)$/.exec(s); return m ? m[1] + m[2] : s;
  }
  function jaroWinkler(a, b) {
    a = a.toLowerCase(); b = b.toLowerCase(); if (a === b) return 1; var md = Math.max(0, Math.floor(Math.max(a.length, b.length) / 2) - 1), am = [], bm = [], m = 0, t = 0, k = 0;
    for (var i = 0; i < a.length; i++) for (var j = Math.max(0, i - md); j < Math.min(b.length, i + md + 1); j++) if (!bm[j] && a[i] === b[j]) { am[i] = bm[j] = true; m++; break; }
    if (!m) return 0; for (i = 0; i < a.length; i++) if (am[i]) { while (!bm[k]) k++; if (a[i] !== b[k]) t++; k++; }
    var jaro = (m / a.length + m / b.length + (m - t / 2) / m) / 3, l = 0; while (l < 4 && a[l] === b[l]) l++; return jaro + l * 0.1 * (1 - jaro);
  }
  /**
   * ctx: { employees[], worklist[], session {employeeId, worklistId, openedAt}|null, methods[]|null (configured), restricted bool,
   *        category, effectiveTime, receivedAt, thresholds {auto, suggest}, prefixes }
   */
  function associate(record, ctx) {
    var cands = [], idn = record.identity, emps = ctx.employees || [], th = ctx.thresholds || { auto: 0.95, suggest: 0.60 };
    var allowed = function (m) { if (ctx.restricted && ['WORKLIST', 'SESSION', 'MANUAL_RESOLUTION'].indexOf(m) < 0) return false; return !ctx.methods || !ctx.methods.length || ctx.methods.indexOf(m) >= 0; };
    var byId = function (id) { return emps.filter(function (e) { return e.id === id; })[0]; };
    if (ctx.noSubject) return { status: 'NOT_APPLICABLE', method: null, employeeId: null, confidence: null, candidates: [], reason: 'Area / group measurement – no person to associate (routed to the exposure group)' };
    // 1 WORKLIST
    if (allowed('WORKLIST')) {
      var refs = [idn.orderRef, ctx.session && ctx.session.worklistRef].filter(Boolean);
      (ctx.worklist || []).forEach(function (w) {
        if (w.status !== 'OPEN' || refs.indexOf(w.ref) < 0) return;
        var conf = idn.employee ? (normaliseId(idn.employee) === normaliseId(w.employeeId) ? 1.0 : 0.5) : 0.90;
        if (ctx.session && ctx.session.worklistRef === w.ref && ctx.session.employeeId === w.employeeId) conf = 1.0;
        cands.push({ method: 'WORKLIST', employeeId: w.employeeId, confidence: conf, detail: 'Open ' + w.module + ' order ' + w.ref });
      });
    }
    // 2 SESSION
    if (allowed('SESSION') && ctx.session) {
      var age = (Date.parse(ctx.receivedAt) - Date.parse(ctx.session.openedAt)) / 1000;
      if (age >= -60 && age <= (ctx.sessionWindowSeconds || 900)) {
        var conflict = idn.employee && normaliseId(idn.employee) !== normaliseId(ctx.session.employeeId);
        cands.push({ method: 'SESSION', employeeId: ctx.session.employeeId, confidence: conflict ? 0.80 : 1.0, detail: 'Capture session opened by ' + ctx.session.openedBy + ' ' + Math.round(age) + ' s earlier', conflict: !!conflict });
      }
    }
    // 3 BADGE
    if (allowed('BADGE') && idn.badge) { var eb = emps.filter(function (e) { return e.badge === idn.badge; })[0]; if (eb) cands.push({ method: 'BADGE', employeeId: eb.id, confidence: 0.98, detail: 'Badge ' + idn.badge }); }
    // 4 VENDOR_ID_MATCH
    if (allowed('VENDOR_ID_MATCH') && (idn.employee || idn.name)) {
      var ex = idn.employee && byId(String(idn.employee).trim());
      if (ex) cands.push({ method: 'VENDOR_ID_MATCH', employeeId: ex.id, confidence: 0.97, detail: 'Exact employee id ' + idn.employee });
      else {
        var nm = idn.employee && emps.filter(function (e) { return normaliseId(e.id) === normaliseId(idn.employee, ctx.prefixes); })[0];
        if (nm) cands.push({ method: 'VENDOR_ID_MATCH', employeeId: nm.id, confidence: 0.92, detail: 'Employee id "' + idn.employee + '" after normalisation (case, leading zeros, prefixes)' });
        else if (idn.name && idn.dateOfBirth) {
          emps.forEach(function (e) { var jw = jaroWinkler(idn.name, e.name); if (jw >= 0.92 && e.dob === idn.dateOfBirth) cands.push({ method: 'VENDOR_ID_MATCH', employeeId: e.id, confidence: 0.85, detail: 'Fuzzy name ' + round(jw, 2) + ' + date of birth' }); });
        }
      }
    }
    cands.sort(function (a, b) { return b.confidence - a.confidence; });
    var best = cands[0] || null;
    if (best && best.conflict) return { status: 'UNMATCHED', code: 'association_conflict', method: best.method, employeeId: null, confidence: best.confidence, candidates: cands, suggestion: null, reason: 'Session employee differs from the employee id in the message' };
    var rival = best && cands.filter(function (c) { return c.employeeId !== best.employeeId && best.confidence - c.confidence < 0.05; })[0];
    if (best && rival) return { status: 'UNMATCHED', code: 'ambiguous', method: best.method, employeeId: null, confidence: best.confidence, candidates: cands, suggestion: null, reason: 'Two candidates within 0.05' };
    if (best && best.confidence >= th.auto) return { status: 'MATCHED', method: best.method, employeeId: best.employeeId, confidence: best.confidence, candidates: cands, reason: best.detail };
    if (best && best.confidence >= th.suggest) return { status: 'UNMATCHED', code: 'below_threshold', method: best.method, employeeId: null, confidence: best.confidence, candidates: cands, suggestion: best, reason: 'Confidence ' + best.confidence + ' below auto-file threshold ' + th.auto };
    return { status: 'UNMATCHED', code: 'no_candidate', method: null, employeeId: null, confidence: best ? best.confidence : 0, candidates: cands, suggestion: null,
      reason: ctx.restricted ? 'Restricted category: only a worklist test id or an open capture session may associate' : 'No candidate employee (no worklist, session, badge or vendor id)' };
  }

  // =====================================================================================
  // VALIDATE – quality-gate catalogue
  // =====================================================================================
  var OSHA_FAST = { OSHA_FAST_FFR: ['BENDING', 'TALKING', 'HEAD_SIDE_TO_SIDE', 'HEAD_UP_DOWN'] };
  function gradeFromTrials(fev1, fvc, n) {
    if (!n || n < 1) return 'F';
    function spread(s) { var a = String(s || '').split(';').map(Number).filter(isFinite).sort(function (x, y) { return y - x; }); return a.length >= 2 ? a[0] - a[1] : null; }
    var r = Math.max(spread(fev1) || 0, spread(fvc) || 0);
    if (n >= 3 && r <= 0.150) return 'A'; if (n >= 2 && r <= 0.150) return 'B'; if (n >= 2 && r <= 0.200) return 'C'; if (n >= 2 && r <= 0.250) return 'D'; return 'E';
  }
  var GRADE_ORDER = 'ABCDEF';
  /**
   * gates(list, input): input = { driver, record, norm, ctx: { calibration(instance) → {status}, qc(checkType) → 'OK'|'DUE'|'FAILED'|'NOT_REQUIRED', lot(lot) → {ok, reason},
   *   consent(purpose) → bool, validationListed bool, maxSkewSeconds } }
   */
  function runGates(list, input) {
    var rec = input.record, norm = input.norm, ctx = input.ctx || {}, flags = [];
    var val = function (k) { var c = norm.components.filter(function (x) { return x.key === k; })[0]; return c ? c.value : (rec.values[k] ? rec.values[k].value : null); };
    function add(gate, outcome, code, message) { flags.push({ gate: gate, outcome: outcome, code: code || null, message: message }); }
    var seen = {};
    list.forEach(function (g) {
      var name = g.split(':')[0], param = parseSelectorOpts(g);
      if (seen[g]) return; seen[g] = true;
      switch (name) {
        case 'CALIBRATION': {
          var c = ctx.calibration ? ctx.calibration() : { status: 'UNKNOWN' };
          if (c.status === 'OUT_OF_CALIBRATION') add(name, 'BLOCK', 'device_out_of_calibration', 'This device is out of calibration (E14). Posting is blocked until it is calibrated.');
          else if (c.status === 'OUT_OF_SERVICE') add(name, 'BLOCK', 'device_out_of_service', 'This device is out of service.');
          else if (c.status === 'LOCKED_OUT') add(name, 'BLOCK', 'device_locked_out', c.message || 'The device is locked out until a passing check is recorded.');
          else if (c.status === 'UNKNOWN') add(name, 'WARN', 'device_not_registered', 'Device not linked to the equipment register.');
          else add(name, 'PASS', null, 'Calibration in date (E14 ' + (c.equipmentId || '') + ', due ' + (c.dueOn || '') + ')');
          break;
        }
        case 'QC_DAILY': {
          var ct = String(g.split(':')[1] || '');
          var st = ctx.qc ? ctx.qc(ct) : 'NOT_REQUIRED';
          if (st === 'DUE') add(name, 'BLOCK', 'qc_due', 'The ' + ct.replace(/_/g, ' ').toLowerCase() + ' check is overdue – the device is locked out. Record a passing check, then release.');
          else if (st === 'FAILED') add(name, 'BLOCK', 'qc_failed', 'The last ' + ct + ' check failed – the device is locked out.');
          else if (st === 'LOCKED') add(name, 'BLOCK', 'device_locked_out', 'A passing ' + ct.replace(/_/g, ' ').toLowerCase() + ' check is recorded – the device stays locked out until the device administrator releases it.');
          else add(name, 'PASS', null, ct + ' ' + (st === 'OK' ? 'in date' : 'not required'));
          break;
        }
        case 'QC_LOT': {
          var lot = val('qcLot'); var r = ctx.lot ? ctx.lot(lot) : { ok: true };
          if (!lot) add(name, 'BLOCK', 'qc_lot_invalid', 'No reagent / QC lot in the message.');
          else if (!r.ok) add(name, 'BLOCK', 'qc_lot_invalid', 'Lot ' + lot + ': ' + r.reason);
          else add(name, 'PASS', null, 'Lot ' + lot + ' in date with a passing QC run (' + (r.detail || '') + ')');
          break;
        }
        case 'ATS_ERS_GRADE': {
          var min = param.MIN || 'C', grade = val('sessionGrade'), n = val('acceptableTrials');
          if (!grade) grade = gradeFromTrials(val('fev1Trials'), val('fvcTrials'), n);
          var computed = gradeFromTrials(val('fev1Trials'), val('fvcTrials'), n);
          if (grade === 'F' || n === 0) add(name, 'BLOCK', 'grade_f', 'Grade F – no acceptable manoeuvre. Repeat the test.');
          else if (GRADE_ORDER.indexOf(grade) > GRADE_ORDER.indexOf(min)) add(name, 'WARN', 'grade_below_minimum', 'Session grade ' + grade + ' below the minimum ' + min + ' – re-test suggested.');
          else add(name, 'PASS', null, 'Session grade ' + grade + ' (device); computed from trials: ' + computed);
          break;
        }
        case 'PREDICTED_VALUES':
          if (norm.predicted) add(name, 'INFO', null, 'Predicted FEV1 ' + norm.predicted.fev1PredictedL + ' L, FVC ' + norm.predicted.fvcPredictedL + ' L (' + norm.predicted.method + ')');
          else add(name, 'WARN', 'predicted_unavailable', 'Height, age or sex missing – predicted values not computed.');
          break;
        case 'AUDIO_TEST_RETEST': {
          var tol = param.TOLERANCE_DB || 5, dl = Math.abs(val('left.1000') - val('retest1000Left')), dr = Math.abs(val('right.1000') - val('retest1000Right'));
          if (isNaN(dl) || isNaN(dr)) add(name, 'WARN', 'retest_missing', '1 kHz re-test missing.');
          else if (dl > tol || dr > tol) add(name, 'WARN', 'retest_invalid', '1 kHz re-test differs by ' + Math.max(dl, dr) + ' dB (> ' + tol + ' dB) – re-test prompt.');
          else add(name, 'PASS', null, '1 kHz re-test within ±' + tol + ' dB');
          break;
        }
        case 'AUDIO_AMBIENT_NOISE':
          if (val('ambientNoiseFlag') === true) add(name, 'WARN', 'ambient_noise_exceeded', 'Ambient noise exceeded during the test (ANSI S3.1).'); else add(name, 'PASS', null, 'Ambient noise within limits');
          break;
        case 'BP_CUFF_VALIDATION': {
          if (!ctx.validationListed) add(name, 'WARN', 'bp_device_not_validated', 'Device model has no ISO 81060-2 validation flag in the catalogue.');
          else if (!val('cuffSize')) add(name, 'WARN', 'cuff_size_unknown', 'Validated device (ISO 81060-2); cuff size not reported – confirm the cuff size.');
          else add(name, 'PASS', null, 'Validated device (ISO 81060-2), cuff ' + val('cuffSize'));
          break;
        }
        case 'FIT_EXERCISES_COMPLETE': {
          var proto = val('protocol'), need = OSHA_FAST[proto] || [], have = String(val('exercises') || '').split(',');
          var miss = need.filter(function (x) { return have.indexOf(x) < 0; });
          if (miss.length) add(name, 'BLOCK', 'fit_test_incomplete', 'Protocol ' + proto + ' incomplete – missing ' + miss.join(', ') + ' (OSHA App. A).'); else add(name, 'PASS', null, 'All ' + need.length + ' exercises of ' + proto + ' present');
          break;
        }
        case 'PLAUSIBILITY': {
          var bad = [], cross = [];
          norm.components.forEach(function (c) { if (c.plausible && typeof c.value === 'number' && (c.value < c.plausible.min || c.value > c.plausible.max)) bad.push(c.key + ' ' + c.value + ' outside ' + c.plausible.min + '–' + c.plausible.max); });
          if (val('systolic') != null && val('diastolic') != null && val('systolic') <= val('diastolic')) cross.push('systolic ≤ diastolic');
          if (val('fev1L') != null && val('fvcL') != null && val('fev1L') > val('fvcL')) cross.push('FEV1 > FVC');
          if (val('spo2') != null && val('spo2') > 100) cross.push('SpO2 > 100');
          if (bad.length || cross.length) add(name, 'BLOCK', 'implausible_' + (bad.length ? bad[0].split(' ')[0] : 'cross_check'), 'Implausible value: ' + bad.concat(cross).join('; ') + '.');
          else add(name, 'PASS', null, norm.components.filter(function (c) { return c.plausible; }).length + ' value(s) within plausible ranges; cross-checks passed');
          break;
        }
        case 'CLOCK_SKEW': {
          var sk = Math.abs(norm.skewSeconds || 0), max = ctx.maxSkewSeconds || 120;
          if (sk > max * 10) add(name, 'BLOCK', 'clock_skew_excessive', 'Clock skew ' + sk + ' s – held for review.');
          else if (sk > max) add(name, 'WARN', 'clock_skew_corrected', 'Edge clock ' + sk + ' s off – effective time corrected.');
          else add(name, 'PASS', null, 'Clock within ±' + max + ' s');
          break;
        }
        case 'CONSENT':
          if (ctx.consent && ctx.consent(input.driver.protocol && input.driver.protocol.consentPurpose || 'WEARABLE_DATA')) add(name, 'PASS', null, 'Active WEARABLE_DATA consent');
          else add(name, 'BLOCK', 'consent_missing', 'No active wearable-data consent – data discarded (DEVICE_RAW_UNFILED).');
          break;
        case 'ATTESTATION': {
          var a = rec.identity.attested || {};
          if (!a.model || !a.serial || !a.calibrationId || !a.calibrationDate) add(name, 'BLOCK', 'attestation_incomplete', 'Manual entry needs the attested device model, serial and calibration id / date.');
          else add(name, 'PASS', null, 'Attested ' + a.model + ' (' + a.serial + '), calibration ' + a.calibrationId);
          break;
        }
        case 'OCR_CONFIDENCE': {
          var cf = rec.identity.ocrConfidence;
          add(name, 'INFO', cf != null && cf < (ctx.ocrMinConfidence || 0.9) ? 'ocr_low_confidence' : null, cf != null && cf < (ctx.ocrMinConfidence || 0.9) ? 'OCR confidence ' + cf + ' below 0.90 – value typed by the clinician; verification mandatory.' : 'OCR confidence ' + cf + '; verification mandatory.');
          break;
        }
        case 'VERIFY_BEFORE_POST': add(name, 'INFO', null, 'Clinician verification required before posting'); break;
        default: add(name, 'INFO', null, 'Gate ' + g + ' not evaluated in the demo');
      }
    });
    var worst = flags.some(function (f) { return f.outcome === 'BLOCK'; }) ? 'BLOCK' : flags.some(function (f) { return f.outcome === 'WARN'; }) ? 'WARN' : 'PASS';
    return { flags: flags, outcome: worst };
  }

  // =====================================================================================
  // FHIR shaping
  // =====================================================================================
  function toFhir(o) {
    var comps = o.components.filter(function (c) { return typeof c.value === 'number' || typeof c.value === 'string' || typeof c.value === 'boolean'; });
    var obs = { resourceType: 'Observation', id: o.observationId, status: o.status === 'ROUTED' ? 'final' : 'preliminary',
      category: [{ coding: [{ system: 'http://terminology.hl7.org/CodeSystem/observation-category', code: o.category === 'VITALS' ? 'vital-signs' : o.category === 'IMAGING' ? 'imaging' : 'exam' }] }],
      code: { text: o.category + ' panel (device)' }, subject: o.employeeId ? { identifier: { system: 'urn:wellnessz:employee', value: o.employeeId } } : undefined,
      effectiveDateTime: o.effectiveTime, device: { reference: 'Device/' + o.instanceId },
      component: comps.map(function (c) {
        var cc = { code: { coding: [].concat(c.loinc && c.loinc.indexOf('LOCAL:') !== 0 ? [{ system: 'http://loinc.org', code: c.loinc, display: c.display }] : [{ system: 'urn:wellnessz:local', code: (c.loinc || c.key).replace('LOCAL:', '') }]).concat(c.mdc ? [{ system: 'urn:iso:std:iso:11073:10101', code: c.mdc }] : []) } };
        if (typeof c.value === 'number') cc.valueQuantity = { value: c.value, unit: c.ucum, system: 'http://unitsofmeasure.org', code: c.ucum };
        else if (typeof c.value === 'boolean') cc.valueBoolean = c.value; else cc.valueString = c.value;
        if (c.interpretation && c.interpretation !== 'N') cc.interpretation = [{ coding: [{ system: 'http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation', code: c.interpretation }] }];
        return cc;
      }) };
    var prov = { resourceType: 'Provenance', target: [{ reference: 'Observation/' + o.observationId }], recorded: o.receivedAt,
      agent: [{ type: { text: 'device' }, who: { reference: 'Device/' + o.instanceId } }, { type: { text: 'driver' }, who: { display: o.packageId + '@' + o.driverVersion } }],
      entity: [{ role: 'source', what: { identifier: { system: 'urn:wellnessz:raw-sha256', value: o.rawSha256 } } }] };
    return { observation: obs, provenance: prov };
  }

  // =====================================================================================
  // The pipeline (PARSE → NORMALISE → ASSOCIATE → VALIDATE → ROUTE → PERSIST) – ACQUIRE is done by the engine
  // =====================================================================================
  /**
   * run({ driver, raw, headers, receivedAt, messageId, instanceId, binding, simulator, ctx }) → result with per-stage output.
   * ctx: association + gate context (see associate / runGates), plus employeeById(id).
   */
  function runPipeline(o) {
    var ctx = o.ctx || {}, stages = [], driver = o.driver, t = o.receivedAt;
    function stage(name, status, summary, data) { stages.push({ stage: name, status: status, summary: summary, data: data || null }); }
    var result = { stages: stages, status: null, error: null, records: [], ack: null };
    var parsed;
    try {
      parsed = parse(driver, o.raw);
      var hl7 = parsed.extra.hl7;
      stage('PARSE', 'OK', parsed.records.length + ' record(s) parsed by ' + driver.adapter + ' / ' + driver.parse.type + (hl7 ? ' – ' + hl7.messageType + ' control id ' + hl7.controlId + ', ' + hl7.segments.length + ' segments' : ''),
        { records: parsed.records.map(function (r) { return { identity: r.identity, values: r.values, annotations: r.annotations && r.annotations.annotations }; }), hl7: hl7 ? hl7.segments.map(function (s) { return s.name; }) : null });
      if (hl7) result.ack = hl7Ack(hl7, 'AA', null, t);
    } catch (e) {
      stage('PARSE', 'FAILED', e.code + ': ' + e.message);
      result.status = 'DEAD_LETTER'; result.error = { stage: 'PARSE', code: e.code || 'parse_failed', message: e.message };
      if (driver.adapter === 'HL7V2') { var m0 = null; try { m0 = parseHl7(o.raw); } catch (x) { /* unparseable MSH */ } result.ack = hl7Ack(m0, 'AE', e.message, t); }
      if (driver.adapter === 'POCT1A') result.ack = '<ACK.R01><HDR><HDR.control_id V="' + o.messageId + '"/></HDR><ACK><ACK.type_cd V="AE"/><ACK.note_txt V="' + e.message.replace(/[<>"&]/g, '') + '"/></ACK></ACK.R01>';
      return result;
    }
    var statuses = [];
    parsed.records.forEach(function (rec, idx) {
      var r = { index: idx };
      result.records.push(r);
      var emp = rec.identity.employee && ctx.employeeById ? ctx.employeeById(String(rec.identity.employee).trim()) : null;
      try {
        r.norm = normalise(driver, rec, { receivedAt: t, headers: o.headers, employee: emp });
      } catch (e) {
        stage('NORMALISE', 'FAILED', e.code + ': ' + e.message); r.status = 'DEAD_LETTER'; statuses.push('DEAD_LETTER');
        result.error = { stage: 'NORMALISE', code: e.code, message: e.message }; return;
      }
      stage('NORMALISE', 'OK', r.norm.components.length + ' component(s) mapped to LOINC / UCUM' + (r.norm.notes.length ? ' – ' + r.norm.notes.join('; ') : ''), { components: r.norm.components, predicted: r.norm.predicted, effectiveTime: r.norm.effectiveTime });
      var category = CATEGORY[driver.family] || driver.family;
      r.association = associate(rec, Object.assign({}, ctx.association || {}, { receivedAt: t, category: category, restricted: !!RESTRICTED[driver.family], noSubject: driver.family === 'EXPOSURE' }));
      if (r.association.status === 'MATCHED' && !emp && ctx.employeeById) {
        emp = ctx.employeeById(r.association.employeeId);
        if (driver.family === 'SPIROMETRY') r.norm = normalise(driver, rec, { receivedAt: t, headers: o.headers, employee: emp });
      }
      var as = r.association;
      stage('ASSOCIATE', as.status === 'MATCHED' ? 'OK' : as.status === 'NOT_APPLICABLE' ? 'SKIPPED' : 'HELD',
        as.status === 'MATCHED' ? as.method + ' → ' + as.employeeId + ' (confidence ' + as.confidence.toFixed(2) + ')' : as.status === 'NOT_APPLICABLE' ? as.reason : 'UNMATCHED – ' + as.reason + (as.suggestion ? '; suggestion ' + as.suggestion.employeeId + ' (' + as.suggestion.confidence.toFixed(2) + ')' : ''), as);
      var gateList = ((o.binding && o.binding.gates) || driver.qualityGates || []).concat(ctx.gateAdditions || []);
      if (gateList.indexOf('CLOCK_SKEW') < 0) gateList = gateList.concat(['CLOCK_SKEW']);
      r.gates = runGates(gateList, { driver: driver, record: rec, norm: r.norm, ctx: Object.assign({}, ctx.gates || {}, { consent: ctx.gates && ctx.gates.consentFor ? function (p) { return ctx.gates.consentFor(as.employeeId || rec.identity.employee, p); } : null }) });
      stage('VALIDATE', r.gates.outcome === 'BLOCK' ? 'BLOCKED' : r.gates.outcome === 'WARN' ? 'WARN' : 'OK',
        r.gates.flags.map(function (f) { return f.gate + ' ' + f.outcome; }).join(' · '), r.gates.flags);
      var binding = o.binding || driver.bindings[0];
      r.observationId = uuidv5(o.messageId + '#' + idx + '#' + Object.keys(binding.fields || {}).sort().join(','));
      r.category = category; r.employeeId = as.employeeId; r.binding = binding;
      if (as.status === 'UNMATCHED') { r.status = 'UNMATCHED'; stage('ROUTE', 'HELD', 'Not routed – placed in the unmatched queue (never auto-filed below ' + ((ctx.association && ctx.association.thresholds && ctx.association.thresholds.auto) || 0.95) + ')'); }
      else if (r.gates.outcome === 'BLOCK') { r.status = 'BLOCKED'; var bf = r.gates.flags.filter(function (f) { return f.outcome === 'BLOCK'; })[0]; stage('ROUTE', 'HELD', 'Blocked by ' + bf.gate + ': ' + bf.message); }
      else {
        var vbp = binding.verifyBeforePost !== false;
        r.status = o.simulator ? 'TEST_FORM' : (vbp ? 'PENDING_VERIFICATION' : 'ROUTED');
        r.fields = {}; Object.keys(binding.fields || {}).forEach(function (k) { var c = r.norm.components.filter(function (x) { return x.key === k; })[0]; if (c) r.fields[binding.fields[k]] = c.value; });
        stage('ROUTE', 'OK', o.simulator ? 'Dry run – delivered to the test form ' + binding.formCode + ' (nothing written to the record)' : (vbp ? 'Waiting for clinician verification in ' + binding.formCode + ' (' + binding.target + ')' : 'Auto-routed to ' + binding.target + ' (' + binding.formCode + ')'), { target: binding.target, formCode: binding.formCode, fields: r.fields, verifyBeforePost: vbp });
      }
      r.fhir = toFhir({ observationId: r.observationId, status: r.status, category: category, employeeId: as.employeeId, effectiveTime: r.norm.effectiveTime, instanceId: o.instanceId,
        components: r.norm.components, receivedAt: t, packageId: driver.packageId, driverVersion: driver.version, rawSha256: o.rawSha256 });
      stage('PERSIST', 'OK', 'Observation ' + r.observationId.slice(0, 8) + '… (' + r.fhir.observation.status + ') + Provenance (raw SHA-256 ' + String(o.rawSha256).slice(0, 12) + '…), retention DEVICE_RAW_PAYLOAD', { observation: r.fhir.observation, provenance: r.fhir.provenance });
      statuses.push(r.status);
    });
    var order = ['DEAD_LETTER', 'BLOCKED', 'UNMATCHED', 'PENDING_VERIFICATION', 'TEST_FORM', 'ROUTED'];
    result.status = order.filter(function (s) { return statuses.indexOf(s) >= 0; })[0] || 'ROUTED';
    return result;
  }

  // =====================================================================================
  // Simulators – generate the real wire formats
  // =====================================================================================
  function le16(v) { return [v & 255, (v >> 8) & 255]; }
  function le32(v) { return [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255]; }
  function gattDateTime(iso) { var d = new Date(iso); return le16(d.getUTCFullYear()).concat([d.getUTCMonth() + 1, d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds()]); }
  function bleEnvelope(service, characteristic, bytes, at, extra) {
    return JSON.stringify({ records: [Object.assign({ peripheral: { addressHash: sha256('demo-peripheral-' + characteristic), name: 'BLE-DEVICE', bondId: 'bond-01' }, service: service, characteristic: characteristic, encoding: 'HEX', value: hex(bytes).toUpperCase(), receivedAt: at }, extra || {})] }, null, 1);
  }
  var SIM = {
    hl7Vitals: function (v) {
      var ts = hl7Ts(v.at) + '+0000', c = v.controlId || 'MSG' + Date.parse(v.at) % 100000;
      var nm = (v.name || 'Employee Erin').split(' ');
      var seg = ['MSH|^~\\&|' + (v.sendingApp || 'CSM^WelchAllyn') + '|' + (v.facility || 'LHR-OH-ROOM-1') + '|WELLNESSZ|WZ|' + ts + '||ORU^R01^ORU_R01|' + c + '|P|2.6|||AL|NE||8859/1|||IHE_PCD_001^IHE PCD^1.3.6.1.4.1.19376.1.6.1.1.1^ISO'];
      if (!v.omitPid) seg.push('PID|||' + v.employeeId + '^^^WZ^PI||' + nm.slice(1).join(' ') + '^' + nm[0] + '||' + String(v.dob || '').replace(/-/g, '') + '|' + (v.sex || ''));
      seg.push('OBR|1|' + c + '^CSM|' + c + '^CSM|182777000^monitoring of patient^SCT|||' + ts);
      seg.push('OBX|1||69837^MDC_DEV_METER_PHYSIO_MULTI_PARAM_MDS^MDC|1.0.0.0|||||||R|||' + ts + '||||' + (v.serial || 'CSM-100-4411') + '^^0013A2FFFE001122^EUI-64');
      var n = 2; function obx(code, mdc, sub, val, ucode, umdc) { if (val === '' || val == null) return; seg.push('OBX|' + (n++) + '|NM|' + code + '^' + mdc + '^MDC|' + sub + '|' + val + '|' + ucode + '^' + umdc + '^MDC|||||R|||' + ts); }
      obx('150021', 'MDC_PRESS_BLD_NONINV_SYS', '1.1.1.1', v.systolic, '266016', 'MDC_DIM_MMHG'); obx('150022', 'MDC_PRESS_BLD_NONINV_DIA', '1.1.1.2', v.diastolic, '266016', 'MDC_DIM_MMHG');
      obx('149530', 'MDC_PULS_RATE_NON_INV', '1.1.2.1', v.pulse, '264864', 'MDC_DIM_BEAT_PER_MIN'); obx('150456', 'MDC_PULS_OXIM_SAT_O2', '1.1.3.1', v.spo2, '262688', 'MDC_DIM_PERCENT');
      obx('150364', 'MDC_TEMP_BODY', '1.1.4.1', v.temperature, '268192', 'MDC_DIM_DEGC');
      if (v.cuff) seg.push('OBX|' + (n++) + '|ST|CUFF_SIZE^Cuff size^L|1.1.1.9|' + v.cuff + '||||||R');
      return seg.join('\r');
    },
    hl7Ecg: function (v) {
      var ts = hl7Ts(v.at), c = v.controlId || 'ECG' + Date.parse(v.at) % 10000;
      return ['MSH|^~\\&|MUSE|LHR-OH-ROOM-2|WELLNESSZ|WZ|' + ts + '||ORU^R01^ORU_R01|' + c + '|P|2.5', 'PID|||' + v.employeeId + '^^^WZ^PI||Employee^Erin||19850412|F',
        'OBR|1|ECG-' + c + '^MUSE||11524-6^EKG study^LN|||' + ts, 'OBX|1|ST|DEVICE_SERIAL^Device serial^L||MAC5500-77120||||||F', 'OBX|2|NM|8867-4^Heart rate^LN||' + v.heartRate + '|/min^^UCUM|||||F',
        'OBX|3|NM|8625-6^PR interval^LN||' + v.prMs + '|ms^^UCUM|||||F', 'OBX|4|NM|8633-0^QRS duration^LN||' + v.qrsMs + '|ms^^UCUM|||||F', 'OBX|5|NM|8636-3^QTc interval^LN||' + v.qtcMs + '|ms^^UCUM|||||F',
        'OBX|6|ST|18844-1^ECG interpretation^LN||' + (v.interpretation || 'Sinus rhythm (device, unverified)') + '||||||F'].join('\r');
    },
    bleBloodPressure: function (v) {
      var kpa = v.unit === 'kPa', flags = (kpa ? 1 : 0) | 2 | 4;
      var enc = function (x) { return le16(sfloatEncode(kpa ? round(x / 7.50062, 1) : x, kpa ? -1 : 0)); };
      var bytes = [flags].concat(enc(v.systolic), enc(v.diastolic), enc(v.map != null ? v.map : Math.round((v.systolic + 2 * v.diastolic) / 3)), gattDateTime(v.at), le16(sfloatEncode(v.pulse, 0)));
      if (v.truncate) bytes = bytes.slice(0, 3);
      return bleEnvelope('0x1810', '0x2A35', bytes, v.at, v.badge ? { badge: v.badge } : null);
    },
    blePulseOximeter: function (v) { var bytes = [1].concat(le16(sfloatEncode(v.spo2, 0)), le16(sfloatEncode(v.pulse, 0)), gattDateTime(v.at)); return bleEnvelope('0x1822', '0x2A5E', bytes, v.at); },
    bleThermometer: function (v) { var f = v.unit === 'F'; var bytes = [(f ? 1 : 0) | 2 | 4].concat(le32(floatEncode(v.temperature, -1)), gattDateTime(v.at), [2]); return bleEnvelope('0x1809', '0x2A1C', bytes, v.at); },
    bleScale: function (v) {
      var bytes = [2 | 8].concat(le16(Math.round(v.weightKg / 0.005)), gattDateTime(v.at), le16(Math.round(v.weightKg / Math.pow(v.heightCm / 100, 2) * 10)), le16(Math.round(v.heightCm * 10)));
      return bleEnvelope('0x181D', '0x2A9D', bytes, v.at);
    },
    poct1a: function (v) {
      var at = v.at.replace('Z', '+00:00').replace('.000', '');
      return '<?xml version="1.0" encoding="UTF-8"?>\n<OBS.R01><HDR><HDR.control_id V="' + (v.controlId || 1001) + '"/><HDR.version_id V="POCT1"/><HDR.creation_dttm V="' + at + '"/><HDR.device_id V="' + (v.deviceId || 'AFN2-55120') + '"/></HDR><OPR><OPR.operator_id V="' + (v.operator || 'nurse1') + '"/></OPR><PT><PT.patient_id V="' + v.employeeId + '"/><OBS><OBS.observation_id V="HbA1c"/><OBS.value V="' + v.value + '" U="%"/><OBS.observation_dttm V="' + at + '"/><REG><REG.reagent_lot_number V="' + v.lot + '"/></REG></OBS></PT></OBS.R01>\n';
    },
    gdtSpirometry: function (v) {
      function line(field, content) { var len = 3 + 4 + utf8(String(content)).length + 2; return String(len).padStart(3, '0') + field + content; }
      var d = new Date(v.at), date = pad2(d.getUTCDate()) + pad2(d.getUTCMonth() + 1) + d.getUTCFullYear(), time = pad2(d.getUTCHours()) + pad2(d.getUTCMinutes()) + pad2(d.getUTCSeconds());
      var dob = v.dob.split('-'); var L = [line('8000', '6310'), line('8100', '00000'), line('9206', '3'), line('3000', v.employeeId), line('3101', v.last), line('3102', v.first), line('3103', dob[2] + dob[1] + dob[0]), line('3110', v.sex === 'M' ? '1' : '2'), line('3622', v.heightCm)];
      if (v.orderRef) L.push(line('8310', v.orderRef));
      L.push(line('6200', date), line('6201', time), line('8402', 'SPIRO'));
      [['FEV1', v.fev1, 'L'], ['FVC', v.fvc, 'L'], ['FEV1%FVC', round(v.fev1 / v.fvc * 100, 1), '%'], ['PEF', v.pef, 'L/s'], ['FEF2575', v.fef2575, 'L/s']].forEach(function (x) { L.push(line('8410', x[0]), line('8420', x[1].toFixed ? (x[0] === 'FEV1%FVC' ? x[1].toFixed(1) : x[1].toFixed(2)) : x[1]), line('8421', x[2])); });
      L.push(line('8410', 'QUALITY'), line('8420', v.grade), line('8410', 'NACCEPT'), line('8420', v.nAccept), line('8410', 'FEV1TRIALS'), line('8420', v.fev1Trials), line('8421', 'L'), line('8410', 'FVCTRIALS'), line('8420', v.fvcTrials), line('8421', 'L'));
      return L.join('\r\n') + '\r\n';
    },
    csvAudiogram: function (v) {
      var hdr = 'EmployeeNo,AudiometerSerial,TestDateTime,Sex,Age,L500,L1000,L2000,L3000,L4000,L6000,L8000,R500,R1000,R2000,R3000,R4000,R6000,R8000,L1000Retest,R1000Retest,AmbientExceeded,Transducer,Operator';
      return hdr + '\r\n' + [v.employeeId, 'CCA-7781', v.at.slice(0, 19), v.sex, v.age].concat(v.left, v.right, [v.retestL, v.retestR, v.ambient ? 1 : 0, 'TDH39', 'nurse1']).join(',') + '\r\n';
    },
    csvDosimeter: function (v) { return 'DosimeterSerial,StartTime,LEX8h,Peak,Location\r\n' + v.rows.map(function (r) { return [r.serial, v.at.slice(0, 19), r.lex8h, r.peak, r.location].join(','); }).join('\r\n') + '\r\n'; },
    serialFc20: function (v) { return JSON.stringify({ records: [{ port: 'COM3', receivedAt: v.at, encoding: 'TEXT', data: 'TEST#' + String(v.testNo).padStart(4, '0') + '\r\nRESULT ' + Number(v.brac).toFixed(3) + ' g/210L\r\nTYPE ' + v.type + '\r\nCAL ' + v.cal + '\r\nSN ' + v.serial + '\r\n' }] }, null, 1); },
    fhirWearable: function (v) {
      function ob(code, display, value, unit) { return { resource: { resourceType: 'Observation', status: 'final', category: [{ coding: [{ system: 'http://terminology.hl7.org/CodeSystem/observation-category', code: 'activity' }] }], code: { coding: [{ system: 'http://loinc.org', code: code, display: display }] }, subject: { identifier: { system: 'urn:wellnessz:employee', value: v.employeeId } }, effectivePeriod: { start: v.date + 'T00:00:00Z', end: v.date + 'T23:59:59Z' }, valueQuantity: { value: value, unit: unit, system: v.badUcum ? 'urn:vendor' : 'http://unitsofmeasure.org', code: unit } } }; }
      return JSON.stringify({ resourceType: 'Bundle', type: 'collection', entry: [ob('55423-8', 'Number of steps', v.steps, '{steps}'), ob('93832-4', 'Sleep duration', v.sleep, 'min')] }, null, 1);
    },
    tsiWebhook: function (v, secret) {
      var body = JSON.stringify({ subject: { employeeNumber: v.employeeId }, instrument: { serial: 'PC8048-2201' }, completedAt: v.at, overallFitFactor: v.fitFactor, pass: v.fitFactor >= 100, protocol: 'OSHA_FAST_FFR', respirator: { model: v.respirator || '3M 8210', size: v.size || 'M' }, exercises: v.exercises }, null, 1);
      var ts = String(Math.floor(Date.parse(v.at) / 1000));
      var sig = hmacSha256(v.wrongSecret ? 'wrong-secret' : secret, ts + '.' + body);
      return { body: body, headers: { 'X-Timestamp': ts, 'X-Signature': 'sha256=' + sig } };
    },
    dicomJson: function (v) {
      var d = new Date(v.at);
      return JSON.stringify([{ '00100020': { vr: 'LO', Value: [v.employeeId] }, '00100010': { vr: 'PN', Value: [{ Alphabetic: v.patientName || 'Employee^Erin' }] }, '00080050': { vr: 'SH', Value: [v.accession] },
        '00080020': { vr: 'DA', Value: [d.getUTCFullYear() + pad2(d.getUTCMonth() + 1) + pad2(d.getUTCDate())] }, '00080030': { vr: 'TM', Value: [pad2(d.getUTCHours()) + pad2(d.getUTCMinutes()) + pad2(d.getUTCSeconds()) + '.000'] },
        '00080060': { vr: 'CS', Value: [v.modality || 'DX'] }, '00180015': { vr: 'CS', Value: ['CHEST'] }, '00080016': { vr: 'UI', Value: ['1.2.840.10008.5.1.4.1.1.1.1'] },
        '0020000D': { vr: 'UI', Value: ['1.2.826.0.1.3680043.10.511.3.' + (10000 + Date.parse(v.at) % 90000)] } }], null, 1);
    }
  };
  /** Annotated byte view of a BLE payload produced by decodeGattValue. */
  function describeGatt(driver, raw) { var env = parseJson(raw); return decodeGattValue(driver, fromHex(env.records[0].value)); }

  var DXC = {
    sha256: sha256, sha1: function (x) { return hex(sha1Bytes(x)); }, hmacSha256: hmacSha256, safeEqual: safeEqual, uuidv5: uuidv5, canonicalJson: canonicalJson, utf8: utf8, hex: hex, fromHex: fromHex, b64ToBytes: b64ToBytes,
    parseHl7: parseHl7, hl7Select: hl7Select, hl7Ack: hl7Ack, sfloatDecode: sfloatDecode, sfloatEncode: sfloatEncode, floatDecode: floatDecode, floatEncode: floatEncode, decodeGattValue: decodeGattValue, describeGatt: describeGatt,
    parseXml: parseXml, xpath: xpath, parseCsv: parseCsv, parseGdt: parseGdt, gdtSelect: gdtSelect, parseTime: parseTime, jsonPointer: jsonPointer,
    parse: parse, normalise: normalise, associate: associate, runGates: runGates, gradeFromTrials: gradeFromTrials, gliPredicted: gliPredicted, normaliseId: normaliseId, jaroWinkler: jaroWinkler,
    toFhir: toFhir, runPipeline: runPipeline, SIM: SIM, CATEGORY: CATEGORY, RESTRICTED: RESTRICTED, perr: perr, round: round
  };
  root.DXC = DXC;
  if (typeof module !== 'undefined' && module.exports) module.exports = DXC;
})(typeof window !== 'undefined' ? window : globalThis);
