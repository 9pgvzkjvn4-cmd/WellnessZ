#!/usr/bin/env node
// SC-01 / SC-12 – MLLP authentication and robustness against malformed device messages (IEC 81001-5-1 §5.7 robustness testing,
// FDA fuzz-testing expectation): malformed, oversized and fuzzed HL7 v2 frames must be answered with AE / AR (or the connection
// closed) – never a crash, never an AA, and the listener must still accept a well-formed message afterwards (no resource
// exhaustion). At the core, /device-ingest/hl7v2 refuses unauthenticated traffic and answers AE / 400 for garbage.
import net from 'node:net';
import tls from 'node:tls';
import { randomBytes } from 'node:crypto';
import { Result, deviceHeaders, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '03_mllp_auth_and_robustness';
const args = parseArgs(`${SCRIPT}.mjs`, 'Malformed / oversized / fuzzed HL7 v2 over MLLP and at the ingest API.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-01', 'SC-12'], cfg.environment);
const VT = '\x0b';
const FS_CR = '\x1c\r';
const now = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
const VALID = `MSH|^~\\&|PACK|TEST|WZ|CORE|${now}||ORU^R01^ORU_R01|PACK-${Date.now()}|P|2.6\rPID|1||SYN-0001^^^PACK\rOBX|1|NM|8867-4^Heart rate^LN||72|/min|||||F\r`;

function exchange(frame, { timeoutMs = 8000 } = {}) {
  return new Promise((resolve) => {
    const m = cfg.mllp;
    const s = m.tls ? tls.connect({ host: m.host, port: m.port, rejectUnauthorized: false, servername: m.host })
      : net.connect({ host: m.host, port: m.port });
    let buf = '';
    const finish = (state) => { s.destroy(); resolve({ state, ack: buf }); };
    s.setTimeout(timeoutMs, () => finish(buf ? 'answered' : 'timeout'));
    s.once(m.tls ? 'secureConnect' : 'connect', () => s.write(Buffer.from(frame, 'latin1')));
    s.on('data', (d) => { buf += d.toString('latin1'); if (buf.includes('\x1c')) finish('answered'); });
    s.once('close', () => resolve({ state: buf ? 'answered' : 'closed', ack: buf }));
    s.once('error', (e) => resolve({ state: 'error', ack: buf, error: e.code }));
  });
}

const ackCode = (ack) => (/MSA\|(A[AER]|C[AER])/.exec(ack) || [])[1] || null;

if (cfg.mllp?.host && cfg.mllp?.port) {
  const cases = [
    ['garbage', `${VT}${randomBytes(64).toString('latin1')}${FS_CR}`],
    ['no-msh', `${VT}PID|1||X\r${FS_CR}`],
    ['bad-delimiters', `${VT}MSH^~|\\&PACK${FS_CR}`],
    ['truncated-frame', `${VT}MSH|^~\\&|PACK|TEST`],
    ['oversized', `${VT}${VALID}${'OBX|2|ST|X||' + 'A'.repeat(1024 * 1024) + '\r'}${FS_CR}`],
    ['nul-bytes', `${VT}MSH|^~\\&|PACK\x00\x00\x00|TEST\r${FS_CR}`],
    ['deep-repetition', `${VT}MSH|^~\\&|PACK|TEST|||${now}||ORU^R01|1|P|2.6\rOBX|1|NM|${'~'.repeat(50000)}\r${FS_CR}`],
  ];
  for (let i = 0; i < 20; i++) cases.push([`fuzz-${i}`, `${VT}${VALID.replace(/\|/g, () => (Math.random() < 0.15 ? randomBytes(1).toString('latin1') : '|'))}${FS_CR}`]);
  for (const [name, frame] of cases) {
    const res = await exchange(frame);
    const code = ackCode(res.ack);
    r.expect(`mllp:${name}`, 'malformed input answered with AE / AR or connection closed – never AA', code !== 'AA' && code !== 'CA',
      `${res.state} ${code ?? ''}`.trim());
  }
  const after = await exchange(`${VT}${VALID}${FS_CR}`);
  r.expect('mllp:still-alive', 'listener still answers a well-formed message after the robustness run', after.state === 'answered',
    `${after.state} ${ackCode(after.ack) ?? ''}`);
} else {
  r.notRun('mllp', 'MLLP robustness at the gateway listener', 'no mllp {host, port} in the targets file');
}

if (cfg.apiBase) {
  const url = `${cfg.apiBase}/device-ingest/hl7v2`;
  const anon = await http('POST', url, { headers: { 'Content-Type': 'application/hl7-v2', 'Idempotency-Key': `pack-${Date.now()}` }, body: VALID });
  r.expect('api:unauthenticated', 'ingest without device credentials → 401', anon.status === 401, `HTTP ${anon.status}`);
  if (cfg.endpoint?.token) {
    const bad = await http('POST', url, {
      headers: { ...deviceHeaders(cfg, cfg.endpoint), 'Content-Type': 'application/hl7-v2', 'Idempotency-Key': `pack-garbage-${Date.now()}` },
      body: randomBytes(256).toString('base64'),
    });
    const code = ackCode(bad.text);
    r.expect('api:garbage', 'garbage at the ingest API → AE / AR or 4xx, never AA / 5xx', bad.status < 500 && code !== 'AA', `HTTP ${bad.status} ${code ?? ''}`);
    const big = await http('POST', url, {
      headers: { ...deviceHeaders(cfg, cfg.endpoint), 'Content-Type': 'application/hl7-v2', 'Idempotency-Key': `pack-big-${Date.now()}` },
      body: VALID + 'OBX|2|ST|X||' + 'B'.repeat(2 * 1024 * 1024) + '\r', timeoutMs: 30000,
    });
    r.expect('api:oversized', 'oversized message refused or AE, service healthy', big.status !== 0 && big.status < 500, `HTTP ${big.status}`);
  } else {
    r.notRun('api:garbage', 'malformed input at the ingest API', 'endpoint credentials not configured');
  }
}
r.write(args.out);
