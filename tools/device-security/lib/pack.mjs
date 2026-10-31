// WellnessZ device security test pack – shared helpers (Node 18+ standard library only, no dependencies).
//
// Rules of engagement (docs/increment-06/device-security/test-plan.md): the scripts are DEFENSIVE checks against the
// organisation's own systems. Every script refuses to run without the explicit flag --authorised and a targets file.
// Secrets are never written into the targets file: use "env:NAME" values, which are read from the environment.
import { createHash, createSign, X509Certificate } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PACK_VERSION = '1.0.0';
export const PACK_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function usage(script, text) {
  return `Usage: node ${script} --authorised --targets <targets.json> [--out <dir>]\n\n${text}\n`;
}

/** Parses the common arguments; exits with code 2 when the authorisation flag or the targets file is missing. */
export function parseArgs(script, description) {
  const argv = process.argv.slice(2);
  const args = { authorised: false, targets: null, out: null, extra: {} };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--authorised') args.authorised = true;
    else if (a === '--targets') args.targets = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--help' || a === '-h') {
      process.stdout.write(usage(script, description));
      process.exit(0);
    } else if (a.startsWith('--')) args.extra[a.slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
  }
  if (!args.authorised || !args.targets) {
    process.stderr.write('Refusing to run: --authorised and --targets are required (defensive checks of your own systems only).\n');
    process.stderr.write(usage(script, description));
    process.exit(2);
  }
  const raw = JSON.parse(readFileSync(args.targets, 'utf8'));
  args.config = resolveEnv(raw);
  args.out = args.out || process.env.WZ_PACK_OUT || join(process.cwd(), 'device-security-evidence');
  mkdirSync(args.out, { recursive: true });
  return args;
}

/** Replaces "env:NAME" strings by the environment value (undefined when not set). */
export function resolveEnv(value) {
  if (Array.isArray(value)) return value.map(resolveEnv);
  if (value && typeof value === 'object') {
    const o = {};
    for (const [k, v] of Object.entries(value)) o[k] = resolveEnv(v);
    return o;
  }
  if (typeof value === 'string' && value.startsWith('env:')) return process.env[value.slice(4)];
  return value;
}

/** Collects checks and writes the JSON evidence file plus one summary line. */
export class Result {
  constructor(script, controls, environment) {
    this.data = {
      script,
      controls,
      packVersion: PACK_VERSION,
      environment: environment || 'unspecified',
      startedAt: new Date().toISOString(),
      finishedAt: null,
      outcome: 'NOT_RUN',
      checks: [],
    };
  }

  /** outcome PASS | FAIL | NOT_RUN; detail must never contain secrets or personal data. */
  check(id, description, outcome, detail) {
    this.data.checks.push({ id, description, outcome, detail: detail === undefined ? null : redact(String(detail)) });
    return outcome;
  }

  pass(id, description, detail) { return this.check(id, description, 'PASS', detail); }
  fail(id, description, detail) { return this.check(id, description, 'FAIL', detail); }
  notRun(id, description, detail) { return this.check(id, description, 'NOT_RUN', detail); }
  expect(id, description, ok, detail) { return this.check(id, description, ok ? 'PASS' : 'FAIL', detail); }

  write(outDir) {
    const c = this.data.checks;
    this.data.outcome = c.some((x) => x.outcome === 'FAIL') ? 'FAIL' : c.some((x) => x.outcome === 'PASS') ? 'PASS' : 'NOT_RUN';
    this.data.finishedAt = new Date().toISOString();
    const body = JSON.stringify(this.data, null, 2);
    const file = join(outDir, `${this.data.script}.json`);
    writeFileSync(file, body + '\n');
    const sha = sha256(body + '\n');
    writeFileSync(file + '.sha256', `${sha}  ${this.data.script}.json\n`);
    const counts = ['PASS', 'FAIL', 'NOT_RUN'].map((o) => `${o.toLowerCase()}=${c.filter((x) => x.outcome === o).length}`).join(' ');
    process.stdout.write(`${this.data.script} ${this.data.controls.join(',')} ${this.data.outcome} ${counts} evidence=${file} sha256=${sha}\n`);
    process.exitCode = this.data.outcome === 'FAIL' ? 1 : 0;
    return file;
  }
}

/** Masks anything that looks like a token, bearer value, private key or e-mail address in evidence text. */
export function redact(text) {
  return text
    .replace(/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, '«private key»')
    .replace(/\b(Bearer|DeviceToken)\s+[A-Za-z0-9._~+/=-]{8,}/g, '$1 «redacted»')
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '«e-mail»')
    .slice(0, 2000);
}

export function sha256(data) {
  return createHash('sha256').update(data).digest('hex');
}

/** fetch wrapper: never throws for HTTP errors; returns {status, headers, json, text, error}. */
export async function http(method, url, { headers = {}, body, timeoutMs = 15000 } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      headers: body !== undefined && typeof body !== 'string' ? { 'Content-Type': 'application/json', ...headers } : headers,
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
      signal: ctrl.signal,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = null; }
    return { status: res.status, headers: res.headers, json, text };
  } catch (e) {
    return { status: 0, headers: new Headers(), json: null, text: '', error: e.name };
  } finally {
    clearTimeout(timer);
  }
}

export function bearer(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Headers of an authenticated device / gateway call (the dev stack has no mTLS ingress: the thumbprint header is sent directly). */
export function deviceHeaders(cfg, endpoint, overrides = {}) {
  const h = {};
  const certHeader = cfg.certificateHeader || 'X-Client-Cert-Sha256';
  const id = overrides.endpointId ?? endpoint?.id;
  const token = overrides.token ?? endpoint?.token;
  const thumb = overrides.thumbprint === null ? null : overrides.thumbprint ?? endpoint?.thumbprint;
  if (id) h['X-Device-Endpoint'] = id;
  if (token) h.Authorization = `DeviceToken ${token}`;
  if (thumb) h[certHeader] = thumb;
  return h;
}

/** Loads a dev-CA client certificate (tools/device/dev-ca.sh) and its private key for enrolment / proof of possession. */
export function loadClient(certPath, keyPath) {
  if (!certPath || !keyPath || !existsSync(certPath) || !existsSync(keyPath)) return null;
  const pem = readFileSync(certPath, 'utf8');
  const key = readFileSync(keyPath, 'utf8');
  const thumbprint = createHash('sha256').update(new X509Certificate(pem).raw).digest('hex');
  return {
    pem,
    thumbprint,
    sign(message) {
      const s = createSign('SHA256');
      s.update(message);
      return s.sign(key).toString('base64');
    },
  };
}

/** Creates an endpoint as SECURITY_OPS and enrols it with a dev-CA certificate; returns {id, token, thumbprint} or {error}. */
export async function provisionTestEndpoint(cfg, { kind = 'GATEWAY', site = 'LHR', allowedCidrs, deviceInstanceIds } = {}) {
  const client = loadClient(cfg.devCa?.clientCert, cfg.devCa?.clientKey);
  if (!cfg.apiBase || !cfg.secopsBearer || !client) return { error: 'apiBase, secopsBearer and devCa.clientCert / clientKey are required' };
  const created = await http('POST', `${cfg.apiBase}/device-security/endpoints`, {
    headers: bearer(cfg.secopsBearer),
    body: { kind, siteCode: site, ...(allowedCidrs ? { allowedCidrs } : {}), ...(deviceInstanceIds ? { deviceInstanceIds } : {}) },
  });
  if (created.status !== 201) return { error: `endpoint creation answered ${created.status}` };
  const { endpointId, enrolmentToken } = created.json;
  const enrolled = await enrol(cfg, client, enrolmentToken, kind);
  if (enrolled.status !== 201) return { error: `enrolment answered ${enrolled.status} ${enrolled.json?.code ?? ''}`, id: endpointId };
  return { id: endpointId, token: enrolled.json.endpointToken, thumbprint: client.thumbprint, enrolmentToken, client };
}

export async function enrol(cfg, client, enrolmentToken, kind, thumbprintHeader) {
  const certHeader = cfg.certificateHeader || 'X-Client-Cert-Sha256';
  const thumb = thumbprintHeader === undefined ? client.thumbprint : thumbprintHeader;
  return http('POST', `${cfg.apiBase}/device-enrolment/enrol`, {
    headers: thumb ? { [certHeader]: thumb } : {},
    body: {
      enrolmentToken,
      endpointKind: kind,
      certificatePem: client.pem,
      proof: client.sign(`enrol:${enrolmentToken}:${client.thumbprint}`),
      hostname: 'security-test-pack',
      platform: { os: process.platform, osVersion: 'n/a', agentVersion: `pack-${PACK_VERSION}` },
      hardening: { baseline: 'n/a', status: 'PASS' },
    },
  });
}

export function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
