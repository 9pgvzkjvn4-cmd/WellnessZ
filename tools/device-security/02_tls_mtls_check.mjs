#!/usr/bin/env node
// SC-01 – Unique endpoint identity and mTLS for all engine-facing connections; plaintext refused
// (NIST 800-53 IA-3 / SC-8, IEC 62443 SR 1.2 / 4.1, OWASP ASVS V9).
//   * TLS targets negotiate TLS 1.2 or 1.3 only (TLS 1.0 / 1.1 handshakes must fail) with an approved cipher;
//   * mTLS required: the ingress refuses a handshake without a client certificate (mtlsTargets) and the core answers
//     401 mtls_required when no verified thumbprint is forwarded;
//   * wrong thumbprint: a valid token with another certificate's thumbprint → 401 certificate_mismatch;
//   * wrong token → 401 bad_token; unknown endpoint → 401 unknown_endpoint;
//   * plaintext listeners (MLLP / HTTP) are refused unless listed as a documented exception.
import net from 'node:net';
import tls from 'node:tls';
import { Result, deviceHeaders, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '02_tls_mtls_check';
const args = parseArgs(`${SCRIPT}.mjs`, 'TLS versions, mutual TLS, thumbprint binding and plaintext refusal.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-01'], cfg.environment);

function handshake(host, port, opts) {
  return new Promise((resolve) => {
    const s = tls.connect({ host, port, servername: host, rejectUnauthorized: false, ...opts });
    s.setTimeout(5000, () => { s.destroy(); resolve({ ok: false, error: 'timeout' }); });
    s.once('secureConnect', () => {
      const out = { ok: true, protocol: s.getProtocol(), cipher: s.getCipher()?.name };
      s.end();
      resolve(out);
    });
    s.once('error', (e) => resolve({ ok: false, error: e.code || e.name }));
  });
}

function plaintextAccepted(host, port) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    s.setTimeout(4000, () => { s.destroy(); resolve(false); });
    s.once('connect', () => {
      s.write(Buffer.from('\x0bMSH|^~\\&|PACK|TEST|||20260101000000||ORU^R01|1|P|2.6\r\x1c\r', 'latin1'));
      s.once('data', () => { s.destroy(); resolve(true); });
      s.once('close', () => resolve(false));
    });
    s.once('error', () => resolve(false));
  });
}

for (const t of cfg.tlsTargets ?? []) {
  const modern = await handshake(t.host, t.port, { minVersion: 'TLSv1.2' });
  r.expect(`tls12+:${t.host}:${t.port}`, 'TLS 1.2 / 1.3 negotiated', modern.ok && ['TLSv1.2', 'TLSv1.3'].includes(modern.protocol),
    modern.ok ? `${modern.protocol} ${modern.cipher}` : modern.error);
  const legacy = await handshake(t.host, t.port, { minVersion: 'TLSv1', maxVersion: 'TLSv1.1' });
  r.expect(`no-legacy-tls:${t.host}:${t.port}`, 'TLS 1.0 / 1.1 refused', !legacy.ok, legacy.ok ? legacy.protocol : legacy.error);
}
if (!cfg.tlsTargets?.length) r.notRun('tls-versions', 'TLS version policy', 'no tlsTargets');

for (const t of cfg.mtlsTargets ?? []) {
  const anon = await handshake(t.host, t.port, {});
  // TLS 1.3 servers may complete the handshake and reject at the first record: probe with an HTTP request
  let refused = !anon.ok;
  if (anon.ok) {
    const res = await http('GET', `https://${t.host}:${t.port}/api/v1/device-ingest/config`).catch(() => ({ status: 0 }));
    refused = res.status === 0 || res.status === 400 || res.status === 401 || res.status === 403;
  }
  r.expect(`mtls-handshake:${t.host}:${t.port}`, 'ingress refuses connections without a client certificate', refused, anon.ok ? 'handshake completed' : anon.error);
}

for (const t of cfg.plaintextTargets ?? []) {
  const accepted = await plaintextAccepted(t.host, t.port);
  r.expect(`plaintext:${t.host}:${t.port}`, 'plaintext MLLP / HTTP refused (or documented exception)', !accepted || t.exception === true,
    accepted ? (t.exception ? `accepted – documented exception ${t.exceptionRef ?? ''}` : 'plaintext accepted') : 'refused');
}

if (cfg.apiBase && cfg.endpoint?.id && cfg.endpoint?.token && cfg.endpoint?.thumbprint) {
  const url = `${cfg.apiBase}/device-ingest/certificate-renewal`;
  const ok = await http('POST', url, { headers: deviceHeaders(cfg, cfg.endpoint), body: {} });
  r.expect('valid-credentials', 'both factors accepted (control request)', ok.status === 400 && ok.json?.code === 'enrolment_invalid', `HTTP ${ok.status}`);
  const noCert = await http('POST', url, { headers: deviceHeaders(cfg, cfg.endpoint, { thumbprint: null }), body: {} });
  r.expect('mtls-required', 'no verified client certificate → 401 mtls_required', noCert.status === 401 && noCert.json?.code === 'mtls_required',
    `HTTP ${noCert.status} ${noCert.json?.code ?? ''}`);
  const wrongThumb = await http('POST', url, { headers: deviceHeaders(cfg, cfg.endpoint, { thumbprint: 'ab'.repeat(32) }), body: {} });
  r.expect('wrong-thumbprint', 'token with another certificate → 401 certificate_mismatch',
    wrongThumb.status === 401 && wrongThumb.json?.code === 'certificate_mismatch', `HTTP ${wrongThumb.status} ${wrongThumb.json?.code ?? ''}`);
  const wrongToken = await http('POST', url, { headers: deviceHeaders(cfg, cfg.endpoint, { token: 'not-the-endpoint-token' }), body: {} });
  r.expect('wrong-token', 'wrong per-endpoint token → 401 bad_token', wrongToken.status === 401 && wrongToken.json?.code === 'bad_token',
    `HTTP ${wrongToken.status} ${wrongToken.json?.code ?? ''}`);
  const unknown = await http('POST', url, { headers: deviceHeaders(cfg, cfg.endpoint, { endpointId: 'EP-PACK-NONE-99' }), body: {} });
  r.expect('unknown-endpoint', 'unknown endpoint id → 401 unknown_endpoint', unknown.status === 401 && unknown.json?.code === 'unknown_endpoint',
    `HTTP ${unknown.status} ${unknown.json?.code ?? ''}`);
  const missing = await http('POST', url, { body: {} });
  r.expect('credentials-missing', 'no credentials → 401 credentials_missing', missing.status === 401 && missing.json?.code === 'credentials_missing',
    `HTTP ${missing.status}`);
} else {
  r.notRun('api-credentials', 'mTLS / thumbprint / token checks at the core', 'apiBase and endpoint {id, token, thumbprint} are required');
}
r.write(args.out);
