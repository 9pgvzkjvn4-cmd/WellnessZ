#!/usr/bin/env node
// SC-16 (and all controls) – Consolidates the JSON evidence of scripts 01–14 against the control matrix SC-01 … SC-16
// (docs/increment-06/device-security/control-mapping.csv): per control the scripts, outcomes and evidence SHA-256, the overall
// verdict (no FAIL = pass), a Markdown report for the evidence template, and – with --submit – the run record at the core
// (POST /device-security/test-pack/runs, SECURITY_OPS), which the go-live readiness gate reads.
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { PACK_DIR, PACK_VERSION, bearer, http, parseArgs, sha256 } from './lib/pack.mjs';

const SCRIPT = '15_report_builder';
const args = parseArgs(`${SCRIPT}.mjs`, 'Builds report.json / report.md from the evidence directory; --submit records the run at the core.');
const cfg = args.config;
const matrixPath = cfg.controlMatrix || resolve(PACK_DIR, '../../docs/increment-06/device-security/control-mapping.csv');

function parseCsv(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/).filter((l) => l.trim())) {
    const cells = [];
    let cur = '';
    let q = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (q && ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') q = !q;
      else if (ch === ',' && !q) { cells.push(cur); cur = ''; }
      else cur += ch;
    }
    cells.push(cur);
    rows.push(cells);
  }
  const [head, ...body] = rows;
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h, r[i] ?? ''])));
}

const matrix = existsSync(matrixPath) ? parseCsv(readFileSync(matrixPath, 'utf8')) : [];
const evidence = readdirSync(args.out).filter((f) => /^\d{2}b?_.*\.json$/.test(f) && !f.startsWith(SCRIPT))
  .map((f) => {
    const text = readFileSync(join(args.out, f), 'utf8');
    return { file: f, sha256: sha256(text), ...JSON.parse(text) };
  });

const controls = matrix.map((m) => {
  const scripts = m.scripts.split(';').map((s) => s.trim()).filter(Boolean);
  const runs = evidence.filter((e) => scripts.some((s) => e.script === s.replace(/\.(mjs|sh|ps1|py)$/, '')) || (e.controls ?? []).includes(m.control));
  const outcome = runs.some((x) => x.outcome === 'FAIL') ? 'FAIL' : runs.some((x) => x.outcome === 'PASS') ? 'PASS' : 'NOT_RUN';
  return { control: m.control, title: m.title, hlr: m.hlr, outcome, manual: m.manual_evidence || null,
    runs: runs.map((x) => ({ script: x.script, outcome: x.outcome, evidenceSha256: x.sha256 })) };
});
const failed = controls.filter((c) => c.outcome === 'FAIL').map((c) => c.control);
const report = {
  packVersion: PACK_VERSION, environment: cfg.environment || 'unspecified', generatedAt: new Date().toISOString(),
  verdict: failed.length ? 'FAIL' : 'PASS', failedControls: failed, controls,
  scripts: evidence.map((e) => ({ script: e.script, outcome: e.outcome, checks: e.checks.length, sha256: e.sha256 })),
};
const reportText = JSON.stringify(report, null, 2) + '\n';
writeFileSync(join(args.out, 'report.json'), reportText);

const md = [
  `# Device security test pack – report (${report.environment})`, '',
  `Pack ${PACK_VERSION} · generated ${report.generatedAt} · verdict **${report.verdict}**`, '',
  '| Control | Title | Outcome | Scripts (evidence SHA-256) | Manual evidence |', '|---|---|---|---|---|',
  ...controls.map((c) => `| ${c.control} | ${c.title} | ${c.outcome} | ${c.runs.map((x) => `${x.script} ${x.outcome} \`${x.evidenceSha256.slice(0, 12)}\``).join('<br>') || '–'} | ${c.manual ?? ''} |`),
  '', '## Checks', '',
  ...evidence.flatMap((e) => [`### ${e.script} – ${e.outcome}`, '', ...e.checks.map((c) => `- ${c.outcome} · ${c.id}: ${c.description}${c.detail ? ` (${c.detail})` : ''}`), '']),
].join('\n');
writeFileSync(join(args.out, 'report.md'), md + '\n');

if (args.extra.submit) {
  if (!cfg.apiBase || !cfg.secopsBearer) {
    process.stderr.write('--submit needs apiBase and secopsBearer\n');
  } else {
    const results = [];
    for (const e of evidence) for (const c of e.controls ?? []) results.push({ script: e.script, control: c, outcome: e.outcome, evidenceSha256: e.sha256 });
    const res = await http('POST', `${cfg.apiBase}/device-security/test-pack/runs`, { headers: bearer(cfg.secopsBearer),
      body: { version: PACK_VERSION, environment: (cfg.environment || 'unspecified').replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 30), results } });
    process.stdout.write(`run recorded at the core: HTTP ${res.status}${res.json?.id ? ` id=${res.json.id}` : ''}\n`);
  }
}
process.stdout.write(`${SCRIPT} ${report.verdict} controls=${controls.length} failed=${failed.join(',') || 'none'} report=${join(args.out, 'report.json')} sha256=${sha256(reportText)}\n`);
process.exitCode = failed.length ? 1 : 0;
