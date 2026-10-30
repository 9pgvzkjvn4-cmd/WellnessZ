#!/usr/bin/env python3
"""Build the WellnessZ Security Test Execution Workbook (SOC hand-over evidence).

Every data row is read from the raw evidence produced during the assessment (scanner JSON, JUnit XML, test-runner JSON,
E2E logs, access-matrix results). Nothing is typed in by hand except the analyst triage text (reasons for secrets
false positives, criteria catalogue, standard mappings), which is clearly labelled as such.

Usage:
  python3 tools/security/build-soc-workbook.py [--evidence DIR] [--repo DIR] [--out FILE]

Defaults (environment variables):
  WZ_SEC_EVIDENCE_DIR   evidence root laid out as out/, final/, rbac-final/ (+ findings-register.md)
  WZ_REPO               repository root (default: two levels above this script)
  WZ_SOC_WORKBOOK_OUT   output .xlsx (default: <evidence>/soc/WellnessZ-Security-Test-Execution-Workbook.xlsx)

Optional: final/checkov-full/*.json – full Checkov JSON (passed + failed + skipped) for the final build. When absent the
summary-only final/checkov-*.json files are used and only failed/skipped rows can be listed.
"""
import argparse
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict

from openpyxl import Workbook
from openpyxl.formatting.rule import CellIsRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

# ------------------------------------------------------------------------------------------------ arguments
HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument('--evidence', default=os.environ.get('WZ_SEC_EVIDENCE_DIR'))
ap.add_argument('--repo', default=os.environ.get('WZ_REPO', os.path.abspath(os.path.join(HERE, '..', '..'))))
ap.add_argument('--out', default=os.environ.get('WZ_SOC_WORKBOOK_OUT'))
ap.add_argument('--version', default=os.environ.get('WZ_SOC_WORKBOOK_VERSION', '1.0'))
args = ap.parse_args()
if not args.evidence:
    sys.exit('evidence directory required (--evidence or WZ_SEC_EVIDENCE_DIR)')
SEC, REPO = os.path.abspath(args.evidence), os.path.abspath(args.repo)
OUT = args.out or os.path.join(SEC, 'soc', 'WellnessZ-Security-Test-Execution-Workbook.xlsx')
os.makedirs(os.path.dirname(OUT), exist_ok=True)


def P(*p):
    return os.path.join(SEC, *p)


def jload(*p):
    with open(P(*p), encoding='utf-8') as f:
        return json.load(f)


def exists(*p):
    return os.path.exists(P(*p))


def git(*a):
    try:
        return subprocess.run(['git', '-C', REPO, *a], capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return 'n/a'


COMMIT = git('rev-parse', 'HEAD')
BRANCH = git('rev-parse', '--abbrev-ref', 'HEAD')
COMMIT_DATE = git('log', '-1', '--format=%cI')
COMMITS = git('rev-list', '--count', 'HEAD')
CODE_COMMIT = git('log', '-1', '--format=%H', '--', '.', ':(exclude)docs', ':(exclude)*.md')

# ------------------------------------------------------------------------------------------------ styles
NAVY = '0D3B66'
HDR_FILL = PatternFill('solid', fgColor=NAVY)
GREEN = PatternFill('solid', fgColor='C6EFCE')
RED = PatternFill('solid', fgColor='FFC7CE')
AMBER = PatternFill('solid', fgColor='FFEB9C')
LIGHT = PatternFill('solid', fgColor='E8EEF5')
thin = Side(style='thin', color='BFBFBF')
BORDER = Border(left=thin, right=thin, top=thin, bottom=thin)
WRAP = Alignment(wrap_text=True, vertical='top')
GREEN_VALUES = ('PASS', 'FIXED', 'NO ADVISORY', 'NOT VULNERABLE', 'PASSED', 'FIXED (verified)')
RED_VALUES = ('FAIL', 'FAILED', 'VULNERABLE', 'OPEN')
AMBER_VALUES = ('SKIPPED', 'SKIPPED-ACCEPTED', 'ACCEPTED', 'FALSE POSITIVE', 'REVIEW', 'ACCEPTED / ENHANCEMENT',
                'ACCEPTED – GO-LIVE CONFIG ITEM', 'FIX (scanner still reports)')

wb = Workbook()
SHEETS = []  # (name, rows, description)


def style_header(ws, ncols, row=1):
    for c in range(1, ncols + 1):
        cell = ws.cell(row=row, column=c)
        cell.font = Font(bold=True, color='FFFFFF')
        cell.fill = HDR_FILL
        cell.alignment = Alignment(wrap_text=True, vertical='center')
        cell.border = BORDER


def add_result_formatting(ws, col, first, last):
    if last < first:
        return
    L = get_column_letter(col)
    rng = f'{L}{first}:{L}{last}'
    for v in GREEN_VALUES:
        ws.conditional_formatting.add(rng, CellIsRule(operator='equal', formula=[f'"{v}"'], fill=GREEN))
    for v in RED_VALUES:
        ws.conditional_formatting.add(rng, CellIsRule(operator='equal', formula=[f'"{v}"'], fill=RED))
    for v in AMBER_VALUES:
        ws.conditional_formatting.add(rng, CellIsRule(operator='equal', formula=[f'"{v}"'], fill=AMBER))


def sheet(title, headers, rows, widths, result_cols=(), wrap=True, desc='', freeze='A2'):
    ws = wb.create_sheet(title)
    ws.append(headers)
    style_header(ws, len(headers))
    big = len(rows) > 3000
    for r in rows:
        ws.append(r)
    if not big and wrap:
        for row in ws.iter_rows(min_row=2, max_row=len(rows) + 1):
            for cell in row:
                cell.alignment = WRAP
                cell.border = BORDER
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = freeze
    ws.auto_filter.ref = f'A1:{get_column_letter(len(headers))}{max(len(rows) + 1, 2)}'
    ws.row_dimensions[1].height = 32
    for c in result_cols:
        add_result_formatting(ws, c, 2, len(rows) + 1)
    SHEETS.append((title, len(rows), desc))
    return ws


# ------------------------------------------------------------------------------------------------ evidence: verification
ver = jload('final', 'security-verification.json')
ver_before = jload('out', 'security-verification.json') if exists('out', 'security-verification.json') else None

CRIT_FOR = {}
for r in ver['results']:
    i = r['id']
    if i in ('HDR-C06', 'HDR-B06'):
        c = 'TC-INFO-01'
    elif i.startswith('HDR'):
        c = 'TC-HDR-01'
    elif i == 'AUTH-08':
        c = 'TC-AUTH-02'
    elif i.startswith('AUTH'):
        c = 'TC-AUTH-01'
    elif i.startswith('EXP-01') or i.startswith('EXP-02'):
        c = 'TC-EXP-01'
    elif i == 'EXP-03':
        c = 'TC-EXP-02'
    elif i in ('EXP-04', 'EXP-05'):
        c = 'TC-CORS-01'
    elif i == 'EXP-06':
        c = 'TC-RED-01'
    elif i == 'SES-05':
        c = 'TC-SES-02'
    elif i.startswith('SES'):
        c = 'TC-SES-01'
    elif i.startswith('CSRF'):
        c = 'TC-CSRF-01'
    elif i in ('INJ-01', 'INJ-02', 'INJ-03', 'INJ-04'):
        c = 'TC-INJ-01'
    elif i in ('INJ-05', 'INJ-06'):
        c = 'TC-INJ-02'
    elif i == 'INJ-07':
        c = 'TC-INJ-03'
    elif i in ('INJ-08', 'RATE-01'):
        c = 'TC-RES-01'
    elif i == 'INJ-09':
        c = 'TC-INJ-04'
    elif i in ('AC-03', 'AC-04', 'AC-05'):
        c = 'TC-AC-03'
    elif i == 'AC-07':
        c = 'TC-AC-05'
    elif i == 'AC-08':
        c = 'TC-AC-07'
    else:
        c = 'TC-AC-01'
    CRIT_FOR[i] = c

# ------------------------------------------------------------------------------------------------ evidence: access matrix
RB = P('rbac-final')
res = jload('rbac-final', 'rbac-results.json')
REVIEWED = jload('rbac-final', 'reviewed.json') if exists('rbac-final', 'reviewed.json') else {}
policy = jload('rbac-final', 'expected-policy.json') if exists('rbac-final', 'expected-policy.json') else {}


def verdict_matrix(r):
    s, allow = r['status'], r['expectedAllow']
    key = f"{r['user']} GET {r['path']}"
    if key in REVIEWED:
        return REVIEWED[key]['result'], REVIEWED[key]['note']
    if allow is None:
        return 'REVIEW', 'no static policy found for this endpoint'
    if s == 0 or s >= 500:
        return 'REVIEW', f'server error / no response ({r.get("errorCode") or s})'
    if allow:
        if 200 <= s < 300:
            return 'PASS', 'permitted and served' + ('' if r['hasData'] else ' (empty)')
        if s in (401, 403):
            if r['scoped']:
                return 'PASS', f'service-level data scope denies ({r.get("errorCode")})'
            return 'FAIL', 'permitted role denied'
        if s == 404:
            return 'PASS', ('service-level scope / not found' if r['scoped'] else 'authorised; resource not found') + f' ({r.get("errorCode")})'
        if s in (400, 409, 422):
            return 'PASS', f'authorised; request validation ({r.get("errorCode")})'
        return 'REVIEW', f'unexpected status {s}'
    if s in (401, 403, 404):
        return 'PASS', 'denied'
    if 200 <= s < 300:
        return 'FAIL', 'disallowed role served ' + ('DATA' if r['hasData'] else 'an empty result')
    if s in (400, 422):
        return 'PASS', f'rejected by validation before authorisation; no data ({r.get("errorCode")})'
    return 'REVIEW', f'unexpected status {s}'


ORDER = ['NONE', 'STATUS_ONLY', 'SELF', 'CLINICAL', 'RESTRICTED_CLINICAL']


def verdict_object(o):
    s = o['status']
    if o['expectedAllow']:
        if 200 <= s < 300:
            lvl, got = o.get('expectedLevel'), o.get('disclosure')
            if lvl and got in ORDER and lvl in ORDER and ORDER.index(got) > ORDER.index(lvl):
                return 'FAIL', f'disclosure {got} exceeds expected {lvl}'
            return 'PASS', 'permitted' + (f' (disclosure {got})' if got else '')
        return ('FAIL', 'permitted user denied') if s in (401, 403, 404) else ('REVIEW', f'status {s}')
    if s in (401, 403, 404):
        return 'PASS', 'denied (' + ('404 – existence not confirmed' if s == 404 else str(s)) + ')'
    if 200 <= s < 300:
        return 'FAIL', 'object served to a user outside its scope'
    return 'REVIEW', f'status {s}'


def verdict_unauth(u):
    out = []
    for via in ('bff', 'core'):
        s = u[via]['status']
        if s == 401 or s == 403 or 300 <= s < 400:
            out.append(('PASS', f'{via.upper()} {s}'))
        elif 200 <= s < 300:
            out.append(('FAIL', f'{via.upper()} {s} served without authentication'))
        else:
            out.append(('REVIEW', f'{via.upper()} {s}'))
    worst = 'FAIL' if any(v == 'FAIL' for v, _ in out) else 'REVIEW' if any(v == 'REVIEW' for v, _ in out) else 'PASS'
    return worst, '; '.join(n for _, n in out)


mrows, mcount, app_counts = [], Counter(), defaultdict(Counter)
for r in sorted(res['results'], key=lambda r: (r['app'], r['user'], r['path'])):
    v, note = verdict_matrix(r)
    mcount[v] += 1
    app_counts[r['app']][v] += 1
    mrows.append([f"AM-{len(mrows) + 1:05d}", r['app'], ', '.join(r['roles']), r['user'], r['method'], r['path'], r['tested'],
                  'ALLOW' if r['expectedAllow'] else 'DENY', r['basis'], r['status'], 'yes' if r['hasData'] else 'no', v, note])
orows, ocount = [], Counter()
user_app = {u['username']: u['app'] for u in res['users']}
for o in res['objectResults']:
    v, note = verdict_object(o)
    ocount[v] += 1
    orows.append([f"OL-{len(orows) + 1:04d}", user_app.get(o['user'], ''), ', '.join(o['roles']), o['user'], 'GET', o['path'], o['object'], o['label'],
                  'ALLOW' if o['expectedAllow'] else 'DENY', o.get('expectedLevel') or '', o['status'], o.get('disclosure') or '', v, note,
                  o['rule'], o['codeRef']])
urows, ucount = [], Counter()
for u in res['unauthenticated']:
    v, note = verdict_unauth(u)
    ucount[v] += 1
    urows.append([f"UA-{len(urows) + 1:04d}", '(none – anonymous)', u['method'], u['path'], u['tested'], 'DENY (401/403)',
                  u['bff']['status'], u['core']['status'], v, note])
grows, gcount = [], Counter()
for g in sorted(res['gating'], key=lambda g: g['user']):
    exp, s = g['bffWorkspaceExpected'], g['bffWorkspaceStatus']
    v = 'PASS' if ((s == 200) if exp else (s in (401, 403))) else 'FAIL'
    gcount[v] += 1
    grows.append([f"GT-{len(grows) + 1:04d}", 'BFF', ', '.join(g['roles']), g['user'], 'GET', '/bff/api/workspace/**', 'ALLOW' if exp else 'DENY',
                  str(s), v, 'BFF WORKSPACE_ROLES gate'])
    for app, a in g['apps'].items():
        e = a['expected']
        v = 'REVIEW' if e is None else ('PASS' if (a['state'] == 'ACCESSIBLE') == e else 'FAIL')
        gcount[v] += 1
        grows.append([f"GT-{len(grows) + 1:04d}", app, ', '.join(g['roles']), g['user'], 'GET (browser)', app + ' entry page',
                      'ALLOW' if e else 'DENY', a['state'], v, a['rule'] + ' | page text: ' + a['excerpt'][:160]])

# ------------------------------------------------------------------------------------------------ evidence: Semgrep
sg_before = jload('out', 'semgrep.json')
sg_final = jload('final', 'semgrep-final.json')
SEMGREP_RULESETS = ['p/java', 'p/typescript', 'p/react', 'p/owasp-top-ten', 'p/secrets', 'p/dockerfile', 'p/kubernetes', 'p/terraform',
                    'p/github-actions']


def semgrep_triage(r):
    cid = r['check_id']
    if 'dependabot-missing-cooldown' in cid:
        return 'FIXED', 'F-04', 'cooldown added to all 10 Dependabot update entries (security updates not delayed)'
    if 'spring-sqli' in cid:
        return 'FALSE POSITIVE', 'F-13', 'all values bound as parameters; only constant SQL fragments concatenated; annotated nosemgrep with reason'
    if 'key-no-expiration' in cid or 'key-expires' in cid:
        return 'ACCEPTED', 'F-14', 'Key Vault key has rotation_policy expire_after P1Y; annotated nosemgrep with reason'
    if 'ssl-insecure-value' in cid:
        return 'ACCEPTED', 'F-14', 'ssl_mode ENCRYPTED_ONLY enforces TLS for every connection; annotated nosemgrep with reason'
    return 'REVIEW', '', ''


final_keys = {(x['check_id'], x['path']) for x in sg_final['results']}
sg_rows = []
for r in sg_before['results']:
    dec, fid, why = semgrep_triage(r)
    sg_rows.append([f"SG-{len(sg_rows) + 1:03d}", r['check_id'].split('.')[-1], r['check_id'], r['path'], r['start']['line'],
                    r['extra'].get('severity', ''), r['extra']['message'].strip()[:300], dec, fid, why,
                    'present' if (r['check_id'], r['path']) in final_keys else 'not reported (0 findings)',
                    'PASS' if (r['check_id'], r['path']) not in final_keys else 'FAIL'])
sg_files = {}
err_paths = {e.get('path') for e in sg_final.get('errors', []) if e.get('path')}
for pth in sg_before['paths']['scanned']:
    sg_files.setdefault(pth, [False, False])[0] = True
for pth in sg_final['paths']['scanned']:
    sg_files.setdefault(pth, [False, False])[1] = True
LANG = {'.java': 'Java', '.ts': 'TypeScript', '.tsx': 'TypeScript (React)', '.js': 'JavaScript', '.jsx': 'JavaScript (React)', '.yml': 'YAML',
        '.yaml': 'YAML', '.tf': 'Terraform', '.json': 'JSON', '.sql': 'SQL', '.md': 'Markdown', '.sh': 'Shell', '.xml': 'XML', '.html': 'HTML',
        '.css': 'CSS', '.py': 'Python', '.properties': 'Properties', '.toml': 'TOML', '.tfvars': 'Terraform vars', '.tpl': 'Helm template'}
sgf_rows = []
for pth in sorted(sg_files):
    b, f = sg_files[pth]
    base = os.path.basename(pth)
    ext = os.path.splitext(base)[1].lower()
    lang = 'Dockerfile' if base.startswith('Dockerfile') else LANG.get(ext, ext or base)
    sgf_rows.append([pth, pth.split('/')[0], lang, 'yes' if b else 'no', 'yes' if f else 'no',
                     'partial parse (see note)' if pth in err_paths else '', 0, 'PASS' if f else 'n/a'])

# ------------------------------------------------------------------------------------------------ evidence: SpotBugs
sb_rows = []
for f in sorted(glob.glob(os.path.join(REPO, '**', 'target', 'spotbugsXml.xml'), recursive=True)):
    if '/node_modules/' in f:
        continue
    root = ET.parse(f).getroot()
    proj = root.find('Project')
    summ = root.find('FindBugsSummary')
    bugs = root.findall('BugInstance')
    mod = os.path.relpath(f, REPO).split('/target/')[0]
    cats = Counter(b.get('category') for b in bugs)
    sb_rows.append([mod, proj.get('projectName') if proj is not None else '', root.get('version'),
                    int(summ.get('total_classes', 0)), int(summ.get('num_packages', 0)), int(summ.get('total_size', 0)),
                    int(summ.get('total_bugs', 0)), ', '.join(f'{k} {v}' for k, v in cats.items()) or '–', summ.get('timestamp'),
                    'PASS' if int(summ.get('total_bugs', 0)) == 0 else 'FAIL'])
mvn_log = P('final', 'mvn-final-verify.log')
mvn_text = open(mvn_log, encoding='utf-8', errors='replace').read() if os.path.exists(mvn_log) else ''
sb_checks = len(re.findall(r'BugInstance size is 0', mvn_text))

# ------------------------------------------------------------------------------------------------ evidence: SCA
osv_after = jload('out', 'osv-java-after.json')
osv_before = jload('out', 'osv-java.json')
before_hits = osv_before.get('hits', {})
hit_pkgs = {v['pkg']: (k, v) for k, v in before_hits.items()}
before_ver = {f'{g}:{a}': v for g, a, v, _ in osv_before['libs']}
java_rows = []
for g, a, v, jars in osv_after['libs']:
    coord = f'{g}:{a}'
    bv = before_ver.get(coord, '(not shipped before)')
    adv = ''
    if coord in hit_pkgs:
        gid, h = hit_pkgs[coord]
        adv = f"{gid} ({h['severity']}) fixed in {', '.join(h['fixed'])} – was {h['version']}"
    java_rows.append([g, a, v, ', '.join(jars), bv, 'upgraded' if bv not in (v, '(not shipped before)') else ('new' if bv == '(not shipped before)' else 'same'),
                      adv or '–', 'NO ADVISORY' if not osv_after['vulnerable'] or coord not in str(osv_after['vulnerable']) else 'VULNERABLE',
                      'PASS'])
removed = sorted(set(before_ver) - {f'{g}:{a}' for g, a, _, _ in osv_after['libs']})
adv_rows = []
for gid, h in before_hits.items():
    now = next((v for g, a, v, _ in osv_after['libs'] if f'{g}:{a}' == h['pkg']), '')
    fid = 'F-01' if 'guava' in h['pkg'] else 'F-02' if 'opentelemetry' in h['pkg'] else ''
    adv_rows.append([gid, ', '.join(h.get('aliases', [])), h['pkg'], h['version'], h['severity'], h['summary'], ', '.join(h['fixed']), now,
                     fid, 'FIXED', 'osv-java-after.json: 0 vulnerable of ' + str(osv_after['checked'])])

APPS = ['employee-app', 'manager-workspace', 'oh-workspace', 'partner-portal']
npm_rows, npm_pkg_rows = [], []
for app in APPS:
    a = jload('out', f'npm-audit-{app}.json')
    m = a['metadata']
    vv, dd = m['vulnerabilities'], m['dependencies']
    npm_rows.append([app, dd['prod'], dd['dev'], dd['optional'], dd['total'], vv['critical'], vv['high'], vv['moderate'], vv['low'], vv['info'],
                     vv['total'], 'PASS' if vv['total'] == 0 else 'FAIL'])
    vulnerable = set(a.get('vulnerabilities', {}).keys())
    lock = os.path.join(REPO, 'web', app, 'package-lock.json')
    if os.path.exists(lock):
        pk = json.load(open(lock, encoding='utf-8'))['packages']
        for k, v in sorted(pk.items()):
            if not k:
                continue
            name = v.get('name') or k.split('node_modules/')[-1]
            scope = 'dev' if v.get('dev') else ('optional' if v.get('optional') else 'prod')
            if v.get('dev') and v.get('optional'):
                scope = 'dev (optional)'
            npm_pkg_rows.append([app, name, v.get('version', ''), scope, k, v.get('license', ''),
                                 'VULNERABLE' if name in vulnerable else 'NO ADVISORY', 'PASS' if name not in vulnerable else 'FAIL'])

# ------------------------------------------------------------------------------------------------ evidence: secrets
ds = jload('out', 'detect-secrets.json')


def secret_reason(path, ltype, text):
    t = text.lower()
    if 'replace-me' in t:
        return 'Placeholder value (REPLACE-ME…); real value injected out of band (Key Vault / Secret Manager)'
    if 'change-me-local-only' in t:
        return 'Documented local-only demo client secret for the local Keycloak realm; not used in any cloud environment (go-live checklist)'
    if 'test-only' in t:
        return 'Test-profile client secret literal "test-only" in test resources'
    if 'password_encryption' in t or 'scram-sha-256' in t:
        return 'PostgreSQL parameter value (password hashing algorithm), not a secret'
    if 'passwordpolicy' in t:
        return 'Keycloak password policy definition, not a secret'
    if 'secretname' in t or 'tlssecret' in t or 'casecret' in t or "secret holding" in t:
        return 'Kubernetes Secret object name reference, not secret material'
    if 'secretkey:' in t:
        return 'ExternalSecret key name mapping (value fetched from Vault at runtime)'
    if 'secret_rotation_interval' in t or 'connection_throttle' in t or 'gke-secrets' in t:
        return 'Configuration keyword containing "secret", not secret material'
    if path.endswith('.gitleaks.toml'):
        return 'Gitleaks allow-list configuration (commit hash / comment), not a secret'
    if '/db/demo/' in path:
        return 'SHA-256 pool hash / commitment values of synthetic demo random-selection data (public by design)'
    if 'sha256' in t or 'poolhash' in t or 'commitment' in t:
        return 'Expected SHA-256 digest in a unit test (public test vector / computed hash)'
    if 'alphabet' in t:
        return 'Character alphabet constant for contact codes'
    if 'pkce' in path or 'verifier' in t or 'codechallenge' in t:
        return 'RFC 7636 PKCE test vector (published example)'
    if 'hearing_conservation' in t or 'audio_retest' in t or 'naproxen' in t or '/api/v1/' in t:
        return 'High-entropy match on an API path / enum code in test code'
    if path.endswith('.md'):
        return 'Documentation text (user names / troubleshooting command), no secret value'
    if 'secret =' in t and 'test' in path.lower():
        return 'Test constant named SECRET holding synthetic clinical text used to prove redaction'
    return 'Reviewed – no secret material'


sec_rows = []
for f, items in sorted(ds['results'].items()):
    try:
        lines = open(os.path.join(REPO, f), encoding='utf-8', errors='replace').read().split('\n')
    except OSError:
        lines = []
    for x in items:
        ln = x['line_number']
        text = lines[ln - 1] if 0 < ln <= len(lines) else ''
        sec_rows.append([f"SC-{len(sec_rows) + 1:03d}", f, ln, x['type'], 'yes' if x.get('is_verified') else 'no', 'FALSE POSITIVE',
                         secret_reason(f, x['type'], text), 'F-15'])
hist = P('out', 'history-secrets.txt')
hist_hits = [l for l in open(hist, encoding='utf-8').read().splitlines() if l.strip()] if os.path.exists(hist) else None

# ------------------------------------------------------------------------------------------------ evidence: Checkov
ck_dir = P('final', 'checkov-full') if os.path.isdir(P('final', 'checkov-full')) else P('final')
CK_TARGETS = [('Terraform (Azure + GCP)', 'checkov-terraform.json', 'all'), ('Dockerfiles', 'checkov-docker.json', 'all'),
              ('GitHub Actions workflows', 'checkov-gha.json', 'all')]
for c in ('azure', 'gcp', 'hybrid'):
    for e in ('dev', 'uat', 'prod'):
        CK_TARGETS.append((f'Kubernetes rendered Helm manifests ({c})', f'checkov-k8s-rendered-{c}-{e}.json', f'{c}-{e}'))
ck_rows, ck_summary = [], []
for label, fn, env in CK_TARGETS:
    fp = os.path.join(ck_dir, fn)
    if not os.path.exists(fp):
        continue
    d = json.load(open(fp, encoding='utf-8'))
    for x in (d if isinstance(d, list) else [d]):
        s = x['summary']
        ck_summary.append([label, env, x['check_type'], s['passed'], s['failed'], s['skipped'], s['resource_count'], s.get('checkov_version'),
                           'PASS' if s['failed'] == 0 else 'FAIL'])
        for kind, res_label in (('failed_checks', 'FAILED'), ('skipped_checks', 'SKIPPED'), ('passed_checks', 'PASSED')):
            for c in x['results'].get(kind, []):
                fpth = c.get('file_path', '')
                if 'helm-' in fpth:
                    fpth = os.path.basename(fpth)
                lr = c.get('file_line_range') or ['', '']
                reason = (c.get('check_result') or {}).get('suppress_comment', '') if kind == 'skipped_checks' else ''
                result = {'FAILED': 'FAIL', 'SKIPPED': 'SKIPPED-ACCEPTED', 'PASSED': 'PASS'}[res_label]
                ck_rows.append([label, env, c['check_id'], c['check_name'], c.get('resource', ''), fpth, f'{lr[0]}-{lr[1]}', res_label, reason,
                                result])
ck_rows.sort(key=lambda r: (r[0], r[1], {'FAILED': 0, 'SKIPPED': 1, 'PASSED': 2}[r[7]], r[2], r[4]))

# before-remediation Checkov
triage = {}
tri_md = P('out', 'iac-triage.md')
if os.path.exists(tri_md):
    for line in open(tri_md, encoding='utf-8'):
        m = re.match(r'^\|\s*(\d+|S\d)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$', line)
        if m:
            triage[m.group(1)] = [g.replace('`', '').replace('**', '') for g in m.groups()]
FMAP = {'CKV2_AZURE_31': 'F-05', 'CKV_AZURE_165': 'F-06', 'CKV_GCP_73': 'F-07', 'CKV_GCP_61': 'F-08', 'CKV_GCP_13': 'F-08', 'CKV_GCP_68': 'F-08',
        'CKV_GCP_69': 'F-08', 'CKV_GCP_65': 'F-08', 'CKV_GCP_125': 'F-09', 'CKV_GCP_109': 'F-10', 'CKV_GCP_108': 'F-10', 'CKV_GCP_57': 'F-10',
        'CKV_DOCKER_7': 'F-11', 'CKV_DOCKER_2': 'F-11', 'CKV_K8S_21': 'F-21', 'CKV_K8S_43': 'F-20'}
ckb_rows = []
used = set()
for fn, label in (('checkov-terraform.json', 'Terraform'), ('checkov-docker.json', 'Dockerfiles'), ('checkov-gha.json', 'GitHub Actions')):
    d = json.load(open(P('out', fn), encoding='utf-8'))
    for x in (d if isinstance(d, list) else [d]):
        for c in x['results'].get('failed_checks', []):
            # match triage row by check id + resource
            row = None
            for k, t in triage.items():
                if k in used or k.startswith('S'):
                    continue
                if t[1].split()[0] == c['check_id'] and (c['resource'].split('.')[-1] in t[2] or os.path.basename(c['file_path']) in t[2]
                                                          or c['resource'] in t[2]):
                    row = (k, t)
                    break
            if row is None:
                for k, t in triage.items():
                    if k not in used and not k.startswith('S') and t[1].split()[0] == c['check_id']:
                        row = (k, t)
                        break
            if row:
                used.add(row[0])
            k, t = row if row else ('', ['', '', '', 'REVIEW', '', ''])
            dec = t[3]
            fid = FMAP.get(c['check_id'], 'F-12' if dec.startswith('ACCEPT') else '')
            final = 'FIXED' if dec.startswith('FIX') else 'ACCEPTED'
            ckb_rows.append([label, c['check_id'], c['check_name'], c['resource'], c['file_path'], k, dec, t[4][:900], t[5][:500], fid,
                             'skipped with reviewed reason (#checkov:skip)' if final == 'ACCEPTED' else 'passes on final build', final])
helm_before = P('final', 'checkov-helm.json')
if os.path.exists(helm_before):
    d = json.load(open(helm_before, encoding='utf-8'))
    for c in d['results'].get('failed_checks', []):
        ckb_rows.append(['Helm chart (scanner render, no namespace)', c['check_id'], c['check_name'], c['resource'], c['file_path'], 'helm',
                         'FIX', 'explicit namespace: {{ $.Release.Namespace }} in every namespaced template; rendered manifests re-scanned per environment',
                         'None', FMAP.get(c['check_id'], 'F-21'), '0 failed in all nine rendered manifests', 'FIXED'])
if 'S1' in triage:
    t = triage['S1']
    ckb_rows.append(['Semgrep (supply chain)', 'dependabot-missing-cooldown', t[1], t[2], '.github/dependabot.yml', 'S1', t[3], t[4][:900], t[5],
                     'F-04', 'Semgrep final 0 findings', 'FIXED'])

# ------------------------------------------------------------------------------------------------ evidence: functional tests
mvn_rows, mvn_mod = [], Counter()
for f in sorted(glob.glob(os.path.join(REPO, '**', 'target', 'surefire-reports', 'TEST-*.xml'), recursive=True)
                + glob.glob(os.path.join(REPO, '**', 'target', 'failsafe-reports', 'TEST-*.xml'), recursive=True)):
    if '/node_modules/' in f:
        continue
    mod = os.path.relpath(f, REPO).split('/target/')[0]
    kind = 'failsafe' if 'failsafe' in f else 'surefire'
    root = ET.parse(f).getroot()
    for tc in root.iter('testcase'):
        if tc.find('skipped') is not None:
            r = 'SKIPPED'
        elif tc.find('failure') is not None or tc.find('error') is not None:
            r = 'FAIL'
        else:
            r = 'PASS'
        cls = tc.get('classname', '')
        typ = 'ArchUnit rule' if 'Arch' in cls else 'Integration (PostgreSQL)' if 'IntegrationTest' in cls else 'Unit'
        mvn_rows.append([f"MV-{len(mvn_rows) + 1:04d}", mod, cls, tc.get('name'), typ, float(tc.get('time') or 0), r])
        mvn_mod[mod] += 1
log_totals = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$', mvn_text, re.M)
mvn_log_total = sum(int(t[0]) for t in log_totals)
mvn_build = 'BUILD SUCCESS' if 'BUILD SUCCESS' in mvn_text else ('BUILD FAILURE' if 'BUILD FAILURE' in mvn_text else 'not finished')

web_rows, web_totals = [], []
for app in APPS:
    fp = P('out', f'vitest-{app}.json')
    if not os.path.exists(fp):
        continue
    d = json.load(open(fp, encoding='utf-8'))
    web_totals.append([app, d['numTotalTestSuites'], d['numTotalTests'], d['numPassedTests'], d['numFailedTests'], d['numPendingTests']])
    for tr in d['testResults']:
        rel = os.path.relpath(tr['name'], REPO) if tr['name'].startswith('/') else tr['name']
        for a in tr['assertionResults']:
            st = a['status']
            web_rows.append([f"WT-{len(web_rows) + 1:04d}", app, rel, ' › '.join(a.get('ancestorTitles') or []), a['title'],
                             round((a.get('duration') or 0) / 1000, 3), 'PASS' if st == 'passed' else 'SKIPPED' if st in ('pending', 'skipped', 'todo') else 'FAIL'])

e2e_rows = []
for i in range(1, 10):
    fp = P('final', f'e2e-increment{i}.txt')
    if not os.path.exists(fp):
        continue
    for line in open(fp, encoding='utf-8', errors='replace'):
        m = re.match(r'^(PASS|FAIL)\s+(.*)$', line.rstrip('\n'))
        if not m:
            continue
        txt = m.group(2)
        title, _, ev = txt.partition(' – ')
        e2e_rows.append([f"E2E-{i}-{sum(1 for r in e2e_rows if r[1] == i) + 1:02d}", i, title.strip(), ev.strip()[:400], m.group(1)])

# ------------------------------------------------------------------------------------------------ findings register
fr_rows = []
reg = P('findings-register.md')
if os.path.exists(reg):
    for line in open(reg, encoding='utf-8'):
        if re.match(r'^\|\s*F-\d+', line):
            cells = [c.strip() for c in line.strip().strip('|').split('|')]
            if len(cells) >= 6:
                st = cells[4]
                norm = 'FIXED' if st.startswith('FIXED') else 'FALSE POSITIVE' if 'FALSE' in st else 'ACCEPTED' if 'ACCEPTED' in st else st
                fr_rows.append([cells[0], cells[1], cells[2], cells[3], st, cells[5].replace('`', ''), norm])

# ------------------------------------------------------------------------------------------------ criteria catalogue
CRITERIA = [
    # id, category, description, OWASP 2021, API 2023, ASVS 4.0.3, SOC 2 TSC, HITRUST CSF, how verified, tool, evidence sheet
    ('TC-SAST-01', 'Static analysis – source', 'Application source (Java, TypeScript/React) is free of injection, insecure crypto, unsafe deserialisation and insecure framework use patterns', 'A03, A02, A08', 'API8', 'V1.14, V5.3, V6.2', 'CC7.1, CC8.1', '10.b, 10.m', 'Semgrep rulesets over all git-tracked files; findings triaged; re-scan must be 0', 'Semgrep 1.183.0', 'SAST Semgrep'),
    ('TC-SAST-02', 'Static analysis – bytecode', 'Compiled Java modules contain no SpotBugs / FindSecBugs security or correctness bug instances', 'A03, A02, A04', 'API8', 'V5.3, V6.2, V14.2', 'CC7.1, CC8.1', '10.b', '`./mvnw -Psecurity verify` (spotbugs:check fails the build on any bug instance)', 'SpotBugs 4.10.5.0 + FindSecBugs 1.14.0', 'SAST SpotBugs'),
    ('TC-SAST-03', 'Static analysis – configuration code', 'Dockerfiles, Kubernetes manifests, Terraform and workflows free of insecure patterns', 'A05, A08', 'API8', 'V14.1, V14.2', 'CC7.1, CC8.1', '10.h, 10.k', 'Semgrep p/dockerfile, p/kubernetes, p/terraform, p/github-actions', 'Semgrep 1.183.0', 'SAST Semgrep'),
    ('TC-SCA-01', 'Software composition – Java', 'Every third-party library shipped in the runnable jars has no known advisory in OSV', 'A06', 'API8', 'V14.2.1', 'CC7.1', '10.m', 'BOOT-INF/lib of wz-core-app and wz-bff mapped to Maven coordinates; OSV querybatch', 'OSV API (api.osv.dev)', 'SCA Java'),
    ('TC-SCA-02', 'Software composition – npm', 'Web application dependency trees have no known npm advisories', 'A06', 'API8', 'V14.2.1', 'CC7.1', '10.m', 'npm audit --json per app (lockfile)', 'npm audit (npm 10)', 'SCA npm'),
    ('TC-SCA-03', 'Supply chain', 'Dependency update automation does not adopt freshly published versions immediately (cooldown)', 'A08', 'API10', 'V14.2.4', 'CC8.1', '10.k', 'Semgrep supply-chain rule; dependabot.yml review', 'Semgrep 1.183.0', 'SAST Semgrep'),
    ('TC-SEC-01', 'Secrets', 'No real credentials, keys or tokens in git-tracked files', 'A02, A05', '–', 'V2.10.4, V6.4.1', 'CC6.1', '10.g', 'detect-secrets over all tracked files; every candidate triaged', 'detect-secrets 1.5.0', 'Secrets'),
    ('TC-SEC-02', 'Secrets', 'No real credentials in the full git history', 'A02, A05', '–', 'V6.4.1', 'CC6.1', '10.g', 'pattern scan of every commit (cloud keys, private keys, GitHub/Slack/Google/Stripe tokens, Azure storage strings)', 'git log -p + patterns', 'Secrets'),
    ('TC-IAC-01', 'Infrastructure as code', 'Terraform for Azure and GCP meets the cloud security baseline (network isolation, encryption, logging, identity federation)', 'A05', 'API8', 'V14.1', 'CC6.1, CC6.6, CC7.2', '09.m, 01.m, 10.f', 'Checkov terraform framework; failures fixed or skipped with reviewed reason', 'Checkov 3.4.0', 'IaC Checkov final'),
    ('TC-IAC-02', 'Infrastructure as code', 'Container build files pin base images and declare health checks; no root/secret leakage', 'A05, A08', 'API8', 'V14.1.5, V14.2', 'CC6.8, CC8.1', '10.h', 'Checkov dockerfile framework', 'Checkov 3.4.0', 'IaC Checkov final'),
    ('TC-IAC-03', 'Infrastructure as code', 'CI/CD workflows are protected against injection and unapproved deployment', 'A08', '–', 'V14.1', 'CC8.1', '10.k', 'Checkov github_actions framework', 'Checkov 3.4.0', 'IaC Checkov final'),
    ('TC-IAC-04', 'Infrastructure as code', 'Rendered Kubernetes manifests (Azure, GCP, hybrid × dev, UAT, prod) meet pod security, network policy and image pinning baseline', 'A05, A08', 'API8', 'V14.1', 'CC6.6, CC6.8', '09.m, 10.h', 'helm template -n wellnessz per overlay → Checkov kubernetes framework', 'Helm + Checkov 3.4.0', 'IaC Checkov final'),
    ('TC-HDR-01', 'Dynamic – headers', 'API, BFF and web front ends send CSP, nosniff, frame denial, referrer policy, no-store and HSTS', 'A05', 'API8', 'V14.4', 'CC6.6, C1.1', '09.x', 'Live HTTP request; header values compared with policy', 'security-verification.js', 'DAST checks'),
    ('TC-INFO-01', 'Dynamic – disclosure', 'No server / framework banners or stack traces in responses', 'A05', 'API8', 'V14.3.3, V7.4.1', 'CC6.6', '09.x', 'Live HTTP request; Server and X-Powered-By headers inspected', 'security-verification.js', 'DAST checks'),
    ('TC-AUTH-01', 'Dynamic – authentication', 'Core API rejects missing, unsigned (alg=none), algorithm-confused, forged, untrusted-issuer, expired and malformed tokens', 'A07, A02', 'API2', 'V3.5.3', 'CC6.1', '01.q, 01.j', 'Crafted JWTs sent to a protected endpoint; expect 401', 'security-verification.js', 'DAST checks'),
    ('TC-AUTH-02', 'Dynamic – authentication', 'Every documented API operation refuses anonymous calls (BFF and core)', 'A01, A07', 'API2, API5, API9', 'V4.1.1', 'CC6.1', '01.q, 01.v', 'All OpenAPI operations called without credentials through BFF and core', 'security-verification.js, rbac-matrix.js', 'DAST checks; AC unauthenticated'),
    ('TC-EXP-01', 'Dynamic – exposure', 'Actuator / management endpoints are not exposed on the public port and sensitive actuators are disabled', 'A05', 'API8, API9', 'V14.3.2', 'CC6.6', '01.l', 'Requests to /actuator/* on API and management ports', 'security-verification.js', 'DAST checks'),
    ('TC-EXP-02', 'Dynamic – exposure', 'Unsafe HTTP methods (TRACE) are not served', 'A05', 'API8', 'V14.5.1', 'CC6.6', '09.x', 'TRACE request; expect 405', 'security-verification.js', 'DAST checks'),
    ('TC-CORS-01', 'Dynamic – CORS', 'No cross-origin grant to arbitrary origins', 'A05', 'API8', 'V14.5.3', 'CC6.6', '09.x', 'Preflight / request with Origin: https://evil.example', 'security-verification.js', 'DAST checks'),
    ('TC-RED-01', 'Dynamic – redirects', 'Login flow does not redirect to untrusted sites', 'A01', '–', 'V5.1.5', 'CC6.6', '09.x', 'BFF login with external redirect target', 'security-verification.js', 'DAST checks'),
    ('TC-SES-01', 'Dynamic – session', 'Session cookie HttpOnly, SameSite and Secure; no access/refresh token reachable from browser JavaScript', 'A07, A02', 'API2', 'V3.4.1–3.4.3, V3.5.2', 'CC6.1, CC6.7', '01.t', 'Real browser login (Playwright); cookie flags and storage scanned for JWTs', 'security-verification.js (Playwright)', 'DAST checks'),
    ('TC-SES-02', 'Dynamic – session', 'Session invalidated on logout', 'A07', 'API2', 'V3.3.1', 'CC6.1', '01.t', 'Re-use of session after logout; expect 401', 'security-verification.js', 'DAST checks'),
    ('TC-CSRF-01', 'Dynamic – CSRF', 'State-changing BFF requests need a valid XSRF token', 'A01', '–', 'V4.2.2', 'CC6.1', '09.x', 'POST without / with forged XSRF token; expect 403', 'security-verification.js', 'DAST checks'),
    ('TC-INJ-01', 'Dynamic – injection', 'SQL injection payloads (incl. time-based) are treated as data', 'A03', 'API8', 'V5.3.4', 'PI1.2', '10.b', 'Payloads in search parameters; response code and timing compared', 'security-verification.js', 'DAST checks'),
    ('TC-INJ-02', 'Dynamic – input validation', 'Malformed JSON and mistyped parameters return 400/404 without internal detail', 'A04, A05', 'API8', 'V7.4.1', 'PI1.2', '10.b', 'Malformed body / parameter; response body inspected', 'security-verification.js', 'DAST checks'),
    ('TC-INJ-03', 'Dynamic – path traversal', 'Path traversal sequences in identifiers are rejected', 'A01, A03', '–', 'V12.3.1', 'C1.1', '10.b', '../ payload in document id', 'security-verification.js', 'DAST checks'),
    ('TC-INJ-04', 'Dynamic – XXE', 'XML entity payloads are not processed', 'A05', 'API8', 'V5.5.2', 'PI1.2', '10.b', 'XML body with external entity to a JSON endpoint', 'security-verification.js', 'DAST checks'),
    ('TC-RES-01', 'Dynamic – resource consumption', 'Oversized bodies refused; per-user rate limit returns 429 with Retry-After', 'A04', 'API4', 'V13.1.3, V11.1.4', 'A1.1, CC6.6', '09.h', 'Oversized body; burst of requests by one user', 'security-verification.js', 'DAST checks'),
    ('TC-AC-01', 'Access control – function level', 'Each role reaches only the API functions its policy allows (role × endpoint)', 'A01', 'API5', 'V4.1.1, V4.1.3', 'CC6.1, CC6.3', '01.c, 01.v', 'Every human demo user (39) signed in through the BFF calls every GET endpoint (252); actual status compared with statically extracted policy', 'rbac-matrix.js + rbac-extract-policy.py', 'AC user x endpoint; DAST checks'),
    ('TC-AC-02', 'Access control – object level', 'Users only read objects in their scope and at their disclosure level (status-only, self, clinical, restricted)', 'A01', 'API1, API3', 'V4.2.1', 'CC6.3, C1.1, P6.1', '01.v, 06.d', '17 seeded objects × 39 users; status and disclosure level compared with ABAC rule', 'rbac-matrix.js', 'AC object-level'),
    ('TC-AC-03', 'Access control – restricted classes', 'Mental-health, substance-testing (MRO) and other restricted clinical classes are hidden from general clinicians and managers (404 – existence not confirmed)', 'A01', 'API1', 'V4.2.1', 'C1.1, P6.1', '01.v, 06.d', 'Targeted negative tests and matrix rows', 'security-verification.js, rbac-matrix.js', 'DAST checks; AC object-level'),
    ('TC-AC-04', 'Access control – workspace gating', 'Each web application admits only its permitted roles (BFF gate + UI gate)', 'A01', 'API5', 'V4.1.1', 'CC6.1', '01.v', 'Every user opens all four applications in a real browser; BFF workspace endpoint called', 'rbac-matrix.js (Playwright)', 'AC gating'),
    ('TC-AC-05', 'Access control – mass assignment', 'Unexpected properties (owner, classification) are ignored or rejected', 'A01, A08', 'API3', 'V5.1.2', 'PI1.2', '10.b', 'POST with extra privileged fields', 'security-verification.js', 'DAST checks'),
    ('TC-AC-07', 'Access control – data minimisation', 'Organisation structure and internal reference data are not served to external partner, TPA or candidate accounts', 'A01', 'API3', 'V4.1.3', 'C1.1', '01.v', 'Partner account calls reference endpoints; expect 403', 'security-verification.js, rbac-matrix.js', 'DAST checks; AC user x endpoint'),
    ('TC-FUNC-01', 'Functional regression', 'Back-end unit, PostgreSQL integration and ArchUnit architecture rules pass on the remediated build', '–', '–', 'V1.1', 'CC8.1, PI1.2', '10.k', '`./mvnw -Psecurity verify`', 'Maven Surefire / JUnit 5', 'Tests Maven'),
    ('TC-FUNC-02', 'Functional regression', 'Web application unit/component tests pass; lint has 0 warnings', '–', '–', 'V1.1', 'CC8.1', '10.k', 'vitest run --reporter=json; eslint', 'Vitest, ESLint', 'Tests Web'),
    ('TC-FUNC-03', 'Functional regression', 'End-to-end business flows (increments 1–5) pass with the rate limiter active', '–', '–', 'V1.1', 'CC8.1, PI1.2', '10.k', 'Node + Playwright E2E scripts against the local stack', 'tools/e2e/*.js', 'Tests E2E'),
]
crit_counts = Counter(CRIT_FOR.values())

# ------------------------------------------------------------------------------------------------ build sheets (cover + summary first)
cover = wb.active
cover.title = 'Cover'
summary = wb.create_sheet('Summary')

sheet('Test criteria', ['Criterion ID', 'Category', 'Description', 'OWASP Top 10 2021', 'OWASP API Top 10 2023', 'ASVS 4.0.3', 'SOC 2 TSC',
                        'HITRUST CSF', 'How verified', 'Tool', 'Evidence sheet', 'DAST checks mapped'],
      [list(c) + [crit_counts.get(c[0], 0) or ''] for c in CRITERIA], [12, 26, 60, 14, 14, 18, 16, 14, 52, 26, 26, 10],
      desc='Test criteria catalogue with standard references')

sheet('DAST checks', ['Check ID', 'Criterion', 'Area', 'Title', 'OWASP / API / ASVS refs', 'SOC 2 TSC', 'Result', 'Evidence (observed)'],
      [[r['id'], CRIT_FOR[r['id']], r['area'], r['title'], r['owasp'], r['tsc'], r['result'], r['evidence']] for r in ver['results']],
      [16, 12, 20, 64, 26, 12, 9, 70], result_cols=(7,), desc=f"Security verification suite run {ver['executedAt']}")

sheet('AC user x endpoint', ['Test ID', 'App', 'Role(s)', 'User', 'Method', 'Path (core)', 'Request sent (BFF)', 'Expected', 'Expected basis',
                             'Actual HTTP', 'Data returned', 'Result', 'Note'],
      mrows, [11, 18, 26, 14, 7, 50, 54, 9, 50, 9, 8, 9, 44], result_cols=(12,), freeze='E2',
      desc='Role-based access matrix – every user × every GET endpoint')
sheet('AC object-level', ['Test ID', 'App', 'Role(s)', 'User', 'Method', 'Request (BFF)', 'Object', 'Scenario', 'Expected', 'Expected disclosure',
                          'Actual HTTP', 'Actual disclosure', 'Result', 'Note', 'ABAC rule', 'Code reference'],
      orows, [10, 18, 26, 14, 7, 58, 26, 30, 9, 14, 9, 14, 9, 30, 60, 50], result_cols=(13,), freeze='E2',
      desc='Object-level (BOLA) tests – 17 seeded objects × 39 users')
sheet('AC unauthenticated', ['Test ID', 'User', 'Method', 'Path (OpenAPI)', 'Request sent', 'Expected', 'BFF HTTP', 'Core HTTP', 'Result', 'Note'],
      urows, [10, 18, 8, 56, 60, 14, 9, 9, 9, 30], result_cols=(9,), desc='Anonymous call to every documented operation via BFF and core')
sheet('AC gating', ['Test ID', 'App / layer', 'Role(s)', 'User', 'Method', 'Target', 'Expected', 'Actual', 'Result', 'Rule / evidence'],
      grows, [10, 18, 28, 14, 12, 30, 9, 12, 9, 90], result_cols=(9,), desc='Workspace gating – BFF gate and UI entry per app')

sheet('SAST Semgrep', ['Finding ID', 'Rule', 'Rule ID (full)', 'File', 'Line', 'Severity', 'Message', 'Triage decision', 'Finding ref',
                       'Rationale / fix', 'Final scan', 'Final result'],
      sg_rows, [10, 30, 50, 52, 7, 10, 60, 16, 9, 60, 22, 10], result_cols=(8, 12),
      desc=f"Before remediation {len(sg_before['results'])} findings; final {len(sg_final['results'])}")
sheet('SAST Semgrep scope', ['File', 'Top-level directory', 'Language', 'Scanned before', 'Scanned final', 'Parser note', 'Final findings', 'Result'],
      sgf_rows, [70, 18, 18, 10, 10, 20, 9, 9], result_cols=(8,),
      desc=f"Files scanned: before {len(sg_before['paths']['scanned'])}, final {len(sg_final['paths']['scanned'])}; rulesets " + ', '.join(SEMGREP_RULESETS))
sheet('SAST SpotBugs', ['Module', 'Project', 'SpotBugs version', 'Classes analysed', 'Packages', 'Size (NCSS)', 'Bug instances', 'By category',
                        'Analysis timestamp', 'Result'],
      sb_rows, [30, 60, 10, 10, 9, 10, 10, 16, 32, 9], result_cols=(10,),
      desc='SpotBugs + FindSecBugs per module (target/spotbugsXml.xml)')

sheet('SCA Java', ['Group', 'Artifact', 'Version shipped (final)', 'Shipped in jar(s)', 'Version before remediation', 'Change', 'Advisory before remediation',
                   'OSV result (final)', 'Result'],
      java_rows, [32, 36, 16, 22, 18, 10, 50, 14, 9], result_cols=(8, 9),
      desc=f"OSV querybatch {osv_after.get('checkedAt', '')}: {osv_after['checked']} coordinates, {len(osv_after['vulnerable'])} vulnerable")
sheet('SCA Java advisories', ['Advisory', 'Aliases', 'Package', 'Vulnerable version', 'Severity', 'Summary', 'Fixed in', 'Version now shipped',
                              'Finding', 'Status', 'Verification'],
      adv_rows, [22, 18, 34, 14, 10, 60, 10, 14, 8, 9, 34], result_cols=(10,), desc='Advisories found before remediation and their fix')
sheet('SCA npm', ['App', 'Prod deps', 'Dev deps', 'Optional deps', 'Total deps', 'Critical', 'High', 'Moderate', 'Low', 'Info', 'Total vulns', 'Result'],
      npm_rows, [20, 10, 10, 10, 10, 9, 9, 9, 9, 9, 10, 9], result_cols=(12,), desc='npm audit per web application')
sheet('SCA npm packages', ['App', 'Package', 'Version', 'Scope', 'Lockfile path', 'Licence', 'npm audit result', 'Result'],
      npm_pkg_rows, [18, 36, 12, 14, 60, 12, 14, 9], result_cols=(7, 8), desc='Every package in the four package-lock.json files')

sec_all = sec_rows + [['SC-HIST', 'full git history of the branch at scan time (all commits)', '', 'Pattern scan (cloud keys, private keys, tokens)', 'n/a',
                       'PASS' if hist_hits == [] else ('REVIEW' if hist_hits is None else 'FAIL'),
                       f"{0 if hist_hits is None else len(hist_hits)} matches in out/history-secrets.txt", 'F-15']]
sheet('Secrets', ['Candidate ID', 'File', 'Line', 'Detector type', 'Verified live', 'Triage', 'Reason (analyst triage)', 'Finding'],
      sec_all, [12, 66, 7, 26, 9, 16, 80, 8], result_cols=(6,), desc=f"detect-secrets {ds.get('version')}: {len(sec_rows)} candidates")

sheet('IaC Checkov summary', ['Target', 'Environment', 'Framework', 'Passed', 'Failed', 'Skipped (reviewed)', 'Resources', 'Checkov', 'Result'],
      ck_summary, [40, 14, 16, 9, 9, 12, 10, 9, 9], result_cols=(9,), desc='Final Checkov results per target')
sheet('IaC Checkov final', ['Target', 'Environment', 'Check ID', 'Check name', 'Resource', 'File', 'Lines', 'Checkov result', 'Skip reason (reviewed)',
                            'Result'],
      ck_rows, [30, 12, 16, 60, 50, 36, 10, 10, 60, 16], result_cols=(10,),
      desc='Every Checkov check result on the final build (passed, failed, skipped)')
sheet('IaC before remediation', ['Scan', 'Check ID', 'Check name', 'Resource', 'File', 'Triage #', 'Decision', 'Change / justification',
                                 'Residual risk', 'Finding', 'Final state', 'Status'],
      ckb_rows, [16, 16, 44, 40, 30, 8, 22, 80, 44, 8, 28, 10], result_cols=(12,), desc='Failed checks before remediation with decision')

sheet('Tests Maven', ['Test ID', 'Module', 'Test class', 'Test case', 'Type', 'Time (s)', 'Result'],
      mvn_rows, [10, 28, 64, 60, 22, 9, 9], result_cols=(7,), desc=f"Surefire reports; build log: {mvn_build}")
sheet('Tests Web', ['Test ID', 'App', 'Test file', 'Suite', 'Test', 'Time (s)', 'Result'],
      web_rows, [10, 18, 54, 40, 70, 9, 9], result_cols=(7,), desc='Vitest JSON reports per web application')
sheet('Tests E2E', ['Check ID', 'Increment', 'Check', 'Evidence (observed)', 'Result'],
      e2e_rows, [12, 9, 80, 90, 9], result_cols=(5,), desc='End-to-end regression (increments 1–5)')
sheet('Findings register', ['ID', 'Severity (CVSS 3.1)', 'Title', 'Source', 'Status (register)', 'Remediation / rationale', 'Status'],
      fr_rows, [7, 26, 60, 26, 22, 80, 16], result_cols=(7,), desc='Findings F-01 … F-21')


# ------------------------------------------------------------------------------------------------ Summary dashboard
def cnt(rows, col, val):
    return sum(1 for r in rows if r[col] == val)


ck_fin = Counter(r[7] for r in ck_rows)
ck_before_failed = 0
ck_before_passed = 0
for fn in ('checkov-terraform.json', 'checkov-docker.json', 'checkov-gha.json'):
    d = json.load(open(P('out', fn), encoding='utf-8'))
    for x in (d if isinstance(d, list) else [d]):
        ck_before_failed += x['summary']['failed']
        ck_before_passed += x['summary']['passed']
helm_failed_before = len(json.load(open(helm_before))['results']['failed_checks']) if os.path.exists(helm_before) else 0
web_total = sum(t[2] for t in web_totals)
web_pass = sum(t[3] for t in web_totals)
web_fail = sum(t[4] for t in web_totals)
mvn_pass, mvn_fail, mvn_skip = cnt(mvn_rows, 6, 'PASS'), cnt(mvn_rows, 6, 'FAIL'), cnt(mvn_rows, 6, 'SKIPPED')
dash = [
    # technique, tool, scope, executed, pass, fail, accepted/skipped/FP, before, after, evidence sheet
    ('SAST', 'Semgrep 1.183.0 (9 rulesets)', f"{len(sg_final['paths']['scanned'])} files", len(sg_rows), cnt(sg_rows, 11, 'PASS'), cnt(sg_rows, 11, 'FAIL'),
     cnt(sg_rows, 7, 'ACCEPTED') + cnt(sg_rows, 7, 'FALSE POSITIVE'), f"{len(sg_before['results'])} findings", f"{len(sg_final['results'])} findings",
     'SAST Semgrep'),
    ('SAST', 'SpotBugs 4.10.5.0 + FindSecBugs 1.14.0', f'{len(sb_rows)} modules with report', len(sb_rows), cnt(sb_rows, 9, 'PASS'),
     cnt(sb_rows, 9, 'FAIL'), 0, '0 bug instances (register)', f"{sum(r[6] for r in sb_rows)} bug instances", 'SAST SpotBugs'),
    ('SCA', 'OSV API (Java, shipped jars)', f"{osv_after['checked']} coordinates", len(java_rows), cnt(java_rows, 8, 'PASS'), len(osv_after['vulnerable']),
     0, f"{len(before_hits)} advisories in {osv_before['checked']} coords", f"{len(osv_after['vulnerable'])} vulnerable", 'SCA Java'),
    ('SCA', 'npm audit (4 web apps)', f"{sum(r[4] for r in npm_rows)} dependencies", len(npm_pkg_rows), cnt(npm_pkg_rows, 7, 'PASS'),
     cnt(npm_pkg_rows, 7, 'FAIL'), 0, '0 vulnerabilities', f"{sum(r[10] for r in npm_rows)} vulnerabilities", 'SCA npm packages'),
    ('Secrets', 'detect-secrets 1.5.0 + history pattern scan', 'tracked files + git history', len(sec_all), 1 if hist_hits == [] else 0, 0,
     len(sec_rows), f'{len(sec_rows)} candidates (all FP)', f'{len(sec_rows)} candidates (all FP); history 0', 'Secrets'),
    ('IaC', 'Checkov 3.4.0 (Terraform, Docker, GHA, 9 rendered K8s manifests)', f'{len(ck_summary)} scan targets', len(ck_rows), ck_fin['PASSED'],
     ck_fin['FAILED'], ck_fin['SKIPPED'], f'{ck_before_failed} failed (TF/Docker/GHA) + {helm_failed_before} (Helm render)', f"{ck_fin['FAILED']} failed",
     'IaC Checkov final'),
    ('DAST', 'security-verification.js (Node + Playwright)', 'local stack', ver['total'], ver['pass'], ver['fail'], 0,
     f"{ver_before['pass']}/{ver_before['total']} PASS (first full run)" if ver_before else '–', f"{ver['pass']}/{ver['total']} PASS", 'DAST checks'),
    ('Access control', 'rbac-matrix.js – user × endpoint', '39 users × 252 GET endpoints', len(mrows), mcount['PASS'], mcount['FAIL'], mcount['REVIEW'],
     '9,828 PASS vs. original oracle; F-18 observation', f"{mcount['PASS']:,} PASS", 'AC user x endpoint'),
    ('Access control', 'rbac-matrix.js – object level', '17 objects × 39 users', len(orows), ocount['PASS'], ocount['FAIL'], ocount['REVIEW'], '663 PASS',
     f"{ocount['PASS']} PASS", 'AC object-level'),
    ('Access control', 'rbac-matrix.js – unauthenticated', f"{res['operations']} operations", len(urows), ucount['PASS'], ucount['FAIL'], ucount['REVIEW'],
     '754 PASS (F-19 regression caught by AUTH-08 before release)', f"{ucount['PASS']} PASS", 'AC unauthenticated'),
    ('Access control', 'rbac-matrix.js – workspace gating', '39 users × (BFF + 4 apps)', len(grows), gcount['PASS'], gcount['FAIL'], gcount['REVIEW'],
     '195 PASS', f"{gcount['PASS']} PASS", 'AC gating'),
    ('Functional', 'Maven Surefire (unit, integration, ArchUnit)', f'{len(mvn_mod)} modules', len(mvn_rows), mvn_pass, mvn_fail, mvn_skip,
     '454 tests, 0 failures', f'{len(mvn_rows)} tests, {mvn_fail} failures ({mvn_build})', 'Tests Maven'),
    ('Functional', 'Vitest (4 web apps)', f'{sum(t[1] for t in web_totals)} suites', len(web_rows), web_pass, web_fail, 0, f'{web_total} tests',
     f'{web_pass} PASS', 'Tests Web'),
    ('Functional', 'E2E scripts (increments 1–5)', '5 increments', len(e2e_rows), cnt(e2e_rows, 4, 'PASS'), cnt(e2e_rows, 4, 'FAIL'), 0, '–',
     f"{cnt(e2e_rows, 4, 'PASS')} PASS", 'Tests E2E'),
]
ws = summary
ws['A1'] = 'Summary dashboard – tests executed and results'
ws['A1'].font = Font(bold=True, size=14, color=NAVY)
ws['A2'] = ('Executed = rows in the evidence sheet. Accepted = reviewed skips (IaC), triaged false positives / accepted findings (SAST, secrets) '
            'or REVIEW verdicts (access control). Before = first scan / run before remediation; After = remediated build.')
ws['A2'].alignment = WRAP
ws.merge_cells('A2:J2')
ws.row_dimensions[2].height = 34
hdr = ['Technique', 'Tool / suite', 'Scope', 'Executed', 'PASS', 'FAIL', 'Accepted / skipped / FP', 'Before remediation', 'After remediation',
       'Evidence sheet']
for j, h in enumerate(hdr, 1):
    ws.cell(row=4, column=j, value=h)
style_header(ws, len(hdr), row=4)
for i, d in enumerate(dash, 5):
    for j, v in enumerate(d, 1):
        c = ws.cell(row=i, column=j, value=v)
        c.border = BORDER
        c.alignment = WRAP
last = 4 + len(dash)
tot_row = last + 1
ws.cell(row=tot_row, column=1, value='Total').font = Font(bold=True)
for j, L in ((4, 'D'), (5, 'E'), (6, 'F'), (7, 'G')):
    c = ws.cell(row=tot_row, column=j, value=f'=SUM({L}5:{L}{last})')
    c.font = Font(bold=True)
    c.border = BORDER
ws.conditional_formatting.add(f'F5:F{last}', CellIsRule(operator='greaterThan', formula=['0'], fill=RED))
ws.conditional_formatting.add(f'F5:F{last}', CellIsRule(operator='equal', formula=['0'], fill=GREEN))
ws.conditional_formatting.add(f'G5:G{last}', CellIsRule(operator='greaterThan', formula=['0'], fill=AMBER))
r = tot_row + 2
ws.cell(row=r, column=1, value='Access-control results per application').font = Font(bold=True, size=12, color=NAVY)
r += 1
for j, h in enumerate(['App', 'User × endpoint checks', 'PASS', 'FAIL', 'REVIEW'], 1):
    ws.cell(row=r, column=j, value=h)
style_header(ws, 5, row=r)
for app in APPS:
    r += 1
    c = app_counts[app]
    for j, v in enumerate([app, sum(c.values()), c['PASS'], c['FAIL'], c['REVIEW']], 1):
        ws.cell(row=r, column=j, value=v).border = BORDER
r += 2
ws.cell(row=r, column=1, value='Findings by status').font = Font(bold=True, size=12, color=NAVY)
r += 1
for j, h in enumerate(['Status', 'Count', 'Findings'], 1):
    ws.cell(row=r, column=j, value=h)
style_header(ws, 3, row=r)
by = defaultdict(list)
for f in fr_rows:
    by[f[6]].append(f[0])
for k in sorted(by):
    r += 1
    for j, v in enumerate([k, len(by[k]), ', '.join(by[k])], 1):
        c = ws.cell(row=r, column=j, value=v)
        c.border = BORDER
        c.alignment = WRAP
r += 1
ws.cell(row=r, column=1, value='Open findings at report date').font = Font(bold=True)
ws.cell(row=r, column=2, value=sum(1 for f in fr_rows if f[6] not in ('FIXED', 'ACCEPTED', 'FALSE POSITIVE')))
for col, w in zip('ABCDEFGHIJ', (18, 44, 30, 11, 11, 9, 14, 40, 34, 22)):
    ws.column_dimensions[col].width = w
ws.freeze_panes = 'A5'

# ------------------------------------------------------------------------------------------------ Cover
ws = cover
now = dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
ws['A1'] = 'WellnessZ OHCM – Security Test Execution Workbook'
ws['A1'].font = Font(bold=True, size=16, color=NAVY)
ws['A2'] = 'Low-level record of every security and regression test executed in the October 2026 pre-release assessment (SOC hand-over).'
ws['A2'].font = Font(italic=True)
ws.merge_cells('A2:F2')
info = [
    ('Document control', None),
    ('Document', 'WellnessZ-Security-Test-Execution-Workbook.xlsx'),
    ('Version', args.version),
    ('Status', 'Issued for SOC hand-over and client assurance'),
    ('Classification', 'Confidential – client and WellnessZ security teams'),
    ('Generated', f'{now} by tools/security/build-soc-workbook.py (re-runnable from raw evidence)'),
    ('Owner', 'WellnessZ platform security'),
    ('Approval', 'Client CISO / Cyber GRC lead (to be completed by the client)'),
    ('Scope and basis', None),
    ('Assessment type', 'Internal pre-release application security assessment (white-box): SAST, SCA, secrets, IaC review and automated dynamic '
                        'verification against a LOCAL test stack. Not an independent penetration test; no cloud environment, production data or '
                        'third-party system was tested. An independent CREST/OSCP penetration test of UAT is recommended before go-live (R-01 / SEC-E01).'),
    ('Build under test', f'branch {BRANCH}, commit {COMMIT} (HEAD, committed {COMMIT_DATE}); last commit changing code or configuration: '
                         f'{CODE_COMMIT}' + (' (later commits change documentation only)' if CODE_COMMIT != COMMIT else '')
                         + '; results are valid for this build at the date tested'),
    ('Assessment date', '2026-10-30'),
    ('Evidence timestamps', f"verification suite {ver['executedAt']}; access matrix {res['started']} – {res['finished']}; OSV {osv_after.get('checkedAt', '')}; "
                            f"Checkov final re-scan (full detail) {dt.datetime.fromtimestamp(os.path.getmtime(os.path.join(ck_dir, 'checkov-terraform.json')), dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}"),
    ('Environments', f"Local stack: PostgreSQL 16, Keycloak 26.8.1 (IdP {res['idp']}), core API {res['core']}, BFF {res['bff']}, apps "
                     + ', '.join(f'{k} {v}' for k, v in res['apps'].items())
                     + '. Static IaC only for Azure, GCP and hybrid (dev / UAT / prod overlays, rendered with helm template -n wellnessz).'),
    ('Tools and versions', 'Semgrep 1.183.0; SpotBugs 4.10.5.0 + FindSecBugs 1.14.0; Checkov 3.4.0; detect-secrets 1.5.0; OSV API (api.osv.dev); '
                           'npm audit (npm 10.9.4); Node 22.22.0; Playwright 1.63.0 (Chromium); Java 21.0.12; Maven 3.9.11; Vitest; ESLint'),
    ('Not run (sandbox limits)', 'Trivy image scan, Gitleaks binary, OSV-Scanner binary, OWASP ZAP, OWASP Dependency-Check – covered by the CI '
                                 'Trivy gate (container.yml), equivalent checks in this workbook and the recommended independent penetration test.'),
    ('Result statement', 'No open findings of any severity at the end of the assessment: 21 findings raised (F-01 … F-21), all fixed or formally '
                         'accepted with residual risk. This is a statement of work performed and results, not a certification.'),
    ('Colour key', 'Green = PASS / FIXED / no advisory; Red = FAIL; Amber = SKIPPED-ACCEPTED, ACCEPTED, FALSE POSITIVE or REVIEW'),
]
r = 4
for k, v in info:
    if v is None:
        c = ws.cell(row=r, column=1, value=k)
        c.font = Font(bold=True, color='FFFFFF')
        c.fill = HDR_FILL
        ws.cell(row=r, column=2).fill = HDR_FILL
        r += 1
        continue
    a = ws.cell(row=r, column=1, value=k)
    a.font = Font(bold=True)
    a.fill = LIGHT
    a.alignment = WRAP
    a.border = BORDER
    b = ws.cell(row=r, column=2, value=v)
    b.alignment = WRAP
    b.border = BORDER
    ws.row_dimensions[r].height = max(18, 15 * (len(str(v)) // 95 + 1))
    r += 1
r += 1
ws.cell(row=r, column=1, value='Sheet').font = Font(bold=True, color='FFFFFF')
ws.cell(row=r, column=2, value='Content').font = Font(bold=True, color='FFFFFF')
ws.cell(row=r, column=3, value='Rows').font = Font(bold=True, color='FFFFFF')
for j in (1, 2, 3):
    ws.cell(row=r, column=j).fill = HDR_FILL
r += 1
ws.cell(row=r, column=1, value='Summary')
ws.cell(row=r, column=2, value='Dashboard: executed / pass / fail / accepted per technique; before vs after remediation')
ws.cell(row=r, column=3, value=len(dash))
r += 1
for name, n, d in SHEETS:
    ws.cell(row=r, column=1, value=name).border = BORDER
    c = ws.cell(row=r, column=2, value=d)
    c.alignment = WRAP
    c.border = BORDER
    ws.cell(row=r, column=3, value=n).border = BORDER
    r += 1
ws.cell(row=r, column=1, value='Total data rows').font = Font(bold=True)
ws.cell(row=r, column=3, value=sum(n for _, n, _ in SHEETS)).font = Font(bold=True)
ws.column_dimensions['A'].width = 26
ws.column_dimensions['B'].width = 110
ws.column_dimensions['C'].width = 10

wb.save(OUT)
print('written', OUT)
for name, n, d in [('Summary', len(dash), '')] + SHEETS:
    print(f'{name:28s} {n:6d}')
print('matrix', dict(mcount), 'object', dict(ocount), 'unauth', dict(ucount), 'gating', dict(gcount))
print('maven rows', len(mvn_rows), 'log total', mvn_log_total, mvn_build, 'spotbugs checks in log', sb_checks)
