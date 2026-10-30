#!/usr/bin/env python3
"""Static extraction of the expected access-control policy of the WellnessZ core API and BFF.

Parses every Spring controller under platform/, modules/ and apps/ (main sources only), resolves
@PreAuthorize SpEL expressions including String constants (Roles.*, Spel.*, Patterns.*, local OH ...),
combines them with the URL rules of ResourceServerSecurityConfig and the BFF workspace gating, and writes
expected-policy.json.
"""
import json
import os
import re
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(__file__))

ALL_ROLES = ['EMPLOYEE', 'MANAGER', 'HR', 'OH_CLINICIAN', 'OH_ADMIN', 'CASE_MANAGER', 'PSYCH_CLINICIAN', 'AUDITOR',
             'PLATFORM_ADMIN', 'INTEGRATION', 'PARTNER_PROVIDER', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD',
             'HSE_OFFICER', 'CREW_SCHEDULING', 'HYGIENE_MANAGER', 'CLINIC_RECEPTION', 'FIRST_AIDER', 'DPO',
             'CLINIC_SCHEDULER', 'BIOMEDICAL', 'CREW_LICENSING', 'CANDIDATE', 'CLAIMS_COORDINATOR', 'TPA_ADJUSTER',
             'LEGAL_COUNSEL', 'FINANCE', 'PROCUREMENT', 'PEER_SUPPORT_VOLUNTEER', 'PEER_SUPPORT_COORDINATOR',
             'SAFETY_MANAGER', 'DA_PROGRAMME_MANAGER', 'MRO', 'COLLECTOR', 'HEALTH_PROMOTION_LEAD', 'ERGONOMIST']


def strip_comments(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.index('"""', i + 3) + 3
            else:
                j = i + 1
                while src[j] != '"':
                    j += 2 if src[j] == '\\' else 1
                j += 1
            out.append(src[i:j]); i = j
        elif c == "'" and i + 2 < n:
            j = i + 1
            while src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.index('*/', i + 2) + 2
            out.append(' ' * 0); i = j
        else:
            out.append(c); i += 1
    return ''.join(out)


def balanced(src, start):
    """src[start] == '(' -> index after matching ')'."""
    depth, i = 0, start
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            while src[i] != '"':
                i += 2 if src[i] == '\\' else 1
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced')


# ---------------------------------------------------------------- files and constants
files = []
for root in ('platform', 'modules', 'apps'):
    for dp, dn, fn in os.walk(os.path.join(REPO, root)):
        if '/src/test/' in dp + '/' or '/target' in dp or 'node_modules' in dp:
            continue
        for f in fn:
            if f.endswith('.java') and '/src/main/java' in dp:
                files.append(os.path.join(dp, f))

info = {}           # path -> dict(pkg, imports, classes, src)
consts = {}         # 'pkg.Class.NAME' -> raw expression
class_index = {}    # 'pkg.Class' -> path
CONST_RE = re.compile(r'static\s+final\s+String\s+(\w+)\s*=\s*([^;]+);', re.S)
for p in files:
    raw = open(p, encoding='utf-8').read()
    src = strip_comments(raw)
    pkg = (re.search(r'^\s*package\s+([\w.]+);', src, re.M) or [None, ''])[1]
    imports = dict((m.group(1).split('.')[-1], m.group(1)) for m in re.finditer(r'^\s*import\s+([\w.]+);', src, re.M))
    static_imports = dict((m.group(1).split('.')[-1], m.group(1)) for m in re.finditer(r'^\s*import\s+static\s+([\w.]+);', src, re.M))
    top = os.path.basename(p)[:-5]
    # nested classes: crude – map every 'class X' / 'interface X' / 'record X' to pkg.X (and pkg.Top.X)
    cls_names = re.findall(r'\b(?:class|interface|enum|record)\s+(\w+)', src)
    for c in set(cls_names):
        class_index.setdefault(pkg + '.' + c, p)
    info[p] = dict(pkg=pkg, imports=imports, static_imports=static_imports, top=top, src=src, raw=raw)
    # constants: attribute each to the nearest preceding class declaration
    decls = [(m.start(), m.group(1)) for m in re.finditer(r'\b(?:class|interface)\s+(\w+)', src)]
    for m in CONST_RE.finditer(src):
        owner = top
        for pos, name in decls:
            if pos < m.start():
                owner = name
        consts[f'{pkg}.{owner}.{m.group(2 - 1)}'] = m.group(2).strip()
        consts.setdefault(f'{pkg}.{top}.{m.group(1)}', m.group(2).strip())


def resolve_ref(ref, p, owner_cls, seen=()):
    """Resolve a constant reference (NAME or Cls.NAME or a.b.Cls.NAME) used in file p."""
    i = info[p]
    parts = ref.split('.')
    cands = []
    if len(parts) == 1:
        cands += [f"{i['pkg']}.{owner_cls}.{ref}", f"{i['pkg']}.{i['top']}.{ref}"]
        if ref in i['static_imports']:
            cands.append(i['static_imports'][ref])
    else:
        cls, name = parts[-2], parts[-1]
        if cls in i['imports']:
            cands.append(i['imports'][cls] + '.' + name)
        cands.append(f"{i['pkg']}.{cls}.{name}")
        cands.append('.'.join(parts))
        # nested: Outer.Inner.NAME where Outer imported
        if len(parts) >= 3 and parts[0] in i['imports']:
            cands.append(i['imports'][parts[0]].rsplit('.', 1)[0] + '.' + '.'.join(parts[1:]))
    for c in cands:
        if c in consts and c not in seen:
            cpkg = c.rsplit('.', 2)[0]
            cowner = c.rsplit('.', 2)[1]
            cpath = class_index.get(cpkg + '.' + cowner) or p
            return evaluate(consts[c], cpath, cowner, seen + (c,))
    # known framework constants
    if ref.endswith('_VALUE'):
        return '<' + ref + '>'
    return None


def evaluate(expr, p, owner_cls, seen=()):
    """Evaluate a Java String expression of literals, constants and '+'."""
    expr = expr.strip()
    out, i = [], 0
    tokens = re.findall(r'"(?:[^"\\]|\\.)*"|[\w.]+|\+|\(|\)', expr)
    for t in tokens:
        if t in ('+', '(', ')'):
            continue
        if t.startswith('"'):
            out.append(bytes(t[1:-1], 'utf-8').decode('unicode_escape'))
        else:
            v = resolve_ref(t, p, owner_cls, seen)
            if v is None:
                return None
            out.append(v)
    return ''.join(out)


def eval_paths(arg, p, owner):
    """Annotation argument -> list of paths."""
    if arg is None:
        return ['']
    arg = arg.strip()
    m = re.search(r'\b(?:value|path)\s*=\s*(\{[^}]*\}|[^,]+)', arg)
    if m:
        arg = m.group(1)
    elif re.match(r'^\w+\s*=', arg):
        return ['']
    else:
        # positional first argument (may be array)
        if arg.startswith('{'):
            arg = arg[:arg.index('}') + 1]
        else:
            arg = re.split(r',\s*\w+\s*=', arg)[0]
    if arg.startswith('{'):
        items = [x for x in re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)', arg.strip('{} ')) if x.strip()]
    else:
        items = [arg]
    res = []
    for it in items:
        v = evaluate(it, p, owner)
        res.append(v if v is not None else '<?' + it.strip() + '>')
    return res or ['']


ANN_RE = re.compile(r'@(\w+(?:\.\w+)*)')


def parse_annotations(src, pos):
    """Parse consecutive annotations from pos; returns (list of (name,arg), end)."""
    anns = []
    while True:
        m = re.compile(r'\s*@(\w+(?:\.\w+)*)').match(src, pos)
        if not m or m.group(1) == 'interface':
            return anns, pos
        end = m.end()
        arg = None
        k = end
        while k < len(src) and src[k] in ' \t\r\n':
            k += 1
        if k < len(src) and src[k] == '(':
            close = balanced(src, k)
            arg = src[k + 1:close - 1]
            end = close
        anns.append((m.group(1), arg))
        pos = end


MAPPING = {'GetMapping': 'GET', 'PostMapping': 'POST', 'PutMapping': 'PUT', 'PatchMapping': 'PATCH',
           'DeleteMapping': 'DELETE', 'RequestMapping': None}


def spel_roles(spel):
    """Best-effort SpEL -> (roles set or 'ANY_AUTHENTICATED' / 'PERMIT_ALL', conditional flag)."""
    if spel is None:
        return None, False
    s = spel
    roles = set()
    for m in re.finditer(r"has(?:Any)?(?:Role|Authority)\(([^)]*)\)", s):
        for r in re.findall(r"'([^']+)'", m.group(1)):
            roles.add(r.replace('ROLE_', ''))
    any_auth = 'isAuthenticated()' in s or 'isFullyAuthenticated()' in s
    permit = 'permitAll' in s
    rest = re.sub(r"has(?:Any)?(?:Role|Authority)\([^)]*\)|isAuthenticated\(\)|isFullyAuthenticated\(\)|permitAll\(\)?|\bor\b|\|\||[()\s]", '', s)
    conditional = bool(rest) or ' and ' in s or '&&' in s
    if permit:
        return 'PERMIT_ALL', conditional
    if any_auth and not roles:
        return 'ANY_AUTHENTICATED', conditional
    if any_auth and roles:
        return 'ANY_AUTHENTICATED', True
    return sorted(roles), conditional


# data-scope heuristics on method bodies / controller sources
SCOPE_PATTERNS = [
    (r'RESTRICTED_CLINICAL|restrictedClass|canSeeRestricted', 'RESTRICTED_CLINICAL class (psych clinician / subject / break-glass only)'),
    (r'requirePartner|partnerOrg|PartnerScope|partnerScope|organisationCode|providerCode', 'Partner organisation binding (token org_scope /PARTNERS/<org>)'),
    (r'\borgScope\b|\bOrgScope\b|inScope\(|assertInScope|scopePath|org_scope', 'Organisation scope (token org_scope subtree)'),
    (r'policy\.(?:require|decide)|PolicyEngine|AccessPolicy\.Action', 'ABAC PolicyEngine decision (self / clinician / status-only / deny)'),
    (r'(?:user|me|current|principal)\w*\.employeeId\(\)|ownEmployee|selfOnly|requireSelf|subjectEmployeeId', 'Own record only (token employee_id)'),
    (r'statusOnly|STATUS_ONLY', 'Status-only disclosure for line management / HR'),
    (r'breakGlass|BreakGlass', 'Break-glass grant considered'),
    (r'consent', 'Consent-gated disclosure'),
]


def method_body(src, pos):
    """From the method signature start, return body text (balanced braces) or ''."""
    b = src.find('{', pos)
    semi = src.find(';', pos)
    if b < 0 or (0 <= semi < b):
        return ''
    depth, i = 0, b
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            while src[i] != '"':
                i += 2 if src[i] == '\\' else 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[b:i + 1]
        i += 1
    return src[b:]


endpoints = []
controllers = 0
for p, i in sorted(info.items()):
    src = i['src']
    if not re.search(r'@(Rest)?Controller\b', src):
        continue
    controllers += 1
    rel = os.path.relpath(p, REPO)
    module = rel.split('/')[1]
    # class-level annotations
    cm = re.search(r'((?:\s*@\w+(?:\.\w+)*(?:\s*\((?:[^()]|\([^()]*\))*\))?)+)\s*(?:public\s+|final\s+|abstract\s+)*class\s+(\w+)', src)
    cls_name = cm.group(2)
    canns, _ = parse_annotations(src, cm.start(1))
    base_paths = ['']
    cls_pre = None
    for n, a in canns:
        if n == 'RequestMapping':
            base_paths = eval_paths(a, p, cls_name)
        if n == 'PreAuthorize':
            cls_pre = evaluate(a, p, cls_name) or '<?' + a + '>'
    ctrl_scope = sorted({label for pat, label in SCOPE_PATTERNS if re.search(pat, src)})
    body_start = src.find('{', cm.end())
    pos = body_start + 1
    # walk annotation blocks inside the class body
    for m in re.finditer(r'(?m)^\s*@(?:Get|Post|Put|Patch|Delete|Request)Mapping\b', src[body_start:]):
        start = body_start + m.start()
        # back up to the first annotation of this block
        blk = start
        while True:
            prev = src.rfind('\n', 0, blk - 1)
            line = src[prev + 1:blk].strip()
            if line.startswith('@') or (line.endswith(')') and not line.endswith(');') and '@' in src[prev + 1:blk] and not re.search(r'\b(public|private|protected)\b', line)) or line.startswith('"') or line.startswith('+') or line.endswith(','):
                blk = prev + 1
                if prev < body_start:
                    break
            else:
                break
        anns, after = parse_annotations(src, blk)
        names = [a[0] for a in anns]
        if not any(n in MAPPING for n in names):
            continue
        sig = re.match(r'\s*(?:public|protected|private)?\s*(?:[\w<>\[\],.? ]+?)\s+(\w+)\s*\(', src[after:])
        mname = sig.group(1) if sig else '?'
        body = method_body(src, after)
        pre, methods, paths, produces = None, [], [''], ''
        for n, a in anns:
            if n == 'PreAuthorize':
                pre = evaluate(a, p, cls_name) or '<unresolved:' + a.strip() + '>'
            if n in MAPPING:
                paths = eval_paths(a, p, cls_name)
                if MAPPING[n]:
                    methods = [MAPPING[n]]
                else:
                    methods = re.findall(r'RequestMethod\.(\w+)', a or '') or ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
        effective = pre or cls_pre
        roles, cond = spel_roles(effective)
        scope = sorted({label for pat, label in SCOPE_PATTERNS if re.search(pat, body)})
        for bp in base_paths:
            for sp in paths:
                path = (bp.rstrip('/') + ('/' if sp and not sp.startswith('/') else '') + sp) or '/'
                for meth in methods:
                    endpoints.append(dict(method=meth, path=path, module=module, controller=cls_name, handler=mname,
                                          source=f'{rel}', preAuthorize=effective, methodLevel=bool(pre),
                                          roles=roles, conditional=cond, scopeRules=scope, controllerScopeHints=ctrl_scope))


# ---------------------------------------------------------------- service-level guards (unannotated handlers)
ROLE_ARRAYS = {'OH_TEAM': ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'CASE_MANAGER', 'PSYCH_CLINICIAN'],
               'FITNESS_DECIDERS': ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'PSYCH_CLINICIAN'],
               'CLINICIANS': ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'PSYCH_CLINICIAN'],
               'PHYSICIANS': ['OH_PHYSICIAN', 'AME', 'CMO']}
by_simple = {}
for path_, i_ in info.items():
    by_simple.setdefault(i_['top'], []).append(path_)


def role_tokens(text, src):
    out = set()
    for t in re.findall(r'(?:Roles\.)?([A-Z][A-Z_]+)|"([A-Z_]+)"', text):
        name = t[0] or t[1]
        if name in ALL_ROLES:
            out.add(name)
        elif name in ROLE_ARRAYS:
            out.update(ROLE_ARRAYS[name])
        else:
            m = re.search(r'\b' + name + r'\s*=\s*(?:List|Set)\.of\(([^;]*)\);', src)
            if m:
                out |= role_tokens(m.group(1), src)
    return out


GUARD_RE = re.compile(r'if\s*\(\s*!\s*\(?\s*((?:[^{;]|\([^)]*\))*?)\)\s*\{?\s*throw\s+ApiException\.forbidden', re.S)


def guards_in(body, src, depth=0):
    roles = set()
    for m in GUARD_RE.finditer(body):
        if 'hasRole' in m.group(1) or 'hasAnyRole' in m.group(1):
            roles |= role_tokens(m.group(1), src)
    if depth < 1:
        for h in set(re.findall(r'\b(\w+)\(\s*(?:user|u|me|current)\s*[,)]', body)):
            hm = re.search(r'(?:private|static|public|protected)[\w<>\s,]*\s' + h + r'\s*\(', src)
            if hm:
                roles |= guards_in(method_body(src, hm.end()), src, depth + 1)
    return roles


def service_guard(ctrl_path, body):
    src = info[ctrl_path]['src']
    roles, where = set(), []
    for fld, meth in set(re.findall(r'\b(\w+)\.(\w+)\(', body)):
        tm = re.search(r'(?:private|protected)?\s*final\s+([\w.]+)(?:<[^;]*>)?\s+' + fld + r'\s*;', src)
        if not tm:
            continue
        typ = tm.group(1).split('.')[-1]
        for sp in by_simple.get(typ, []):
            ss = info[sp]['src']
            for mm in re.finditer(r'(?:public|protected)\s+[\w<>\[\],.? ]+\s' + meth + r'\s*\(', ss):
                r = guards_in(method_body(ss, mm.end()), ss)
                if r:
                    roles |= r
                    where.append(f'{typ}.{meth}')
    return sorted(roles), where


ctrl_by_name = {i_['top']: pth for pth, i_ in info.items()}
for e in endpoints:
    if e['preAuthorize'] is None and not e['source'].startswith('apps/wz-bff'):
        cp = os.path.join(REPO, e['source'])
        src = info[cp]['src']
        hm = re.search(r'\s' + e['handler'] + r'\s*\(', src)
        body = method_body(src, hm.end()) if hm else ''
        r, where = service_guard(cp, body)
        e['serviceGuardRoles'] = r
        e['serviceGuardAt'] = where
        local = guards_in(body, src)
        if local:
            e['serviceGuardRoles'] = sorted(set(r) | local)
            e['serviceGuardAt'] = where + [e['controller'] + '.' + e['handler']]
        e['scopeRules'] = sorted(set(e['scopeRules']) | {label for pat, label in SCOPE_PATTERNS if re.search(pat, body)})

# ---------------------------------------------------------------- URL-level rules (core + BFF)
core_url_rules = [
    {'pattern': 'OPTIONS /api/**', 'rule': 'permitAll'},
    {'pattern': '/v3/api-docs/**, /swagger-ui/**, /error', 'rule': 'permitAll'},
    {'pattern': '/api/v1/integration/**', 'rule': "hasAnyRole('INTEGRATION','PLATFORM_ADMIN','OH_ADMIN')"},
    {'pattern': '/api/v1/audit/**', 'rule': "hasAnyRole('AUDITOR','PLATFORM_ADMIN')"},
    {'pattern': '/api/**', 'rule': 'authenticated'},
    {'pattern': 'any other request', 'rule': 'denyAll'},
]
bff_src = open(os.path.join(REPO, 'apps/wz-bff/src/main/java/com/wellnessz/bff/BffSecurityConfig.java')).read()
wr = re.search(r'WORKSPACE_ROLES\s*=\s*List\.of\(([^;]*)\);', bff_src, re.S).group(1)
WORKSPACE_ROLES = re.findall(r'"(\w+)"', strip_comments(wr))
proxy_src = open(os.path.join(REPO, 'apps/wz-bff/src/main/java/com/wellnessz/bff/ApiProxyController.java')).read()
cw = re.search(r'CORE_WORKSPACE_RESOURCES\s*=\s*java\.util\.List\.of\(([^;]*)\);', proxy_src, re.S).group(1)
CORE_WORKSPACE = re.findall(r'"([^"]+)"', strip_comments(cw))
core_sec = open(os.path.join(REPO, 'platform/wz-security/src/main/java/com/wellnessz/security/config/ResourceServerSecurityConfig.java')).read()


def url_roles(path):
    if path.startswith('/api/v1/integration/'):
        return ['INTEGRATION', 'PLATFORM_ADMIN', 'OH_ADMIN']
    if path.startswith('/api/v1/audit/') or path == '/api/v1/audit':
        return ['AUDITOR', 'PLATFORM_ADMIN']
    return None


def bff_path(path):
    """Core path -> BFF path; None when not reachable through the BFF (/bff/api/x -> /api/v1/x)."""
    if not path.startswith('/api/v1/'):
        return None
    return '/bff/api/' + path[len('/api/v1/'):]


for e in endpoints:
    r = e['roles']
    ur = url_roles(e['path'])
    eff = r
    notes = []
    if ur:
        notes.append('URL rule ' + ','.join(ur))
        if r == 'ANY_AUTHENTICATED' or r is None:
            eff = ur
        elif isinstance(r, list):
            eff = sorted(set(r) & set(ur))
    if r is None and not ur:
        if e.get('serviceGuardRoles'):
            eff = e['serviceGuardRoles']
            notes.append('no @PreAuthorize – programmatic role guard in ' + ', '.join(e['serviceGuardAt']))
        else:
            eff = 'ANY_AUTHENTICATED'
            notes.append('no @PreAuthorize – any authenticated user; data scope / ABAC enforced in service')
    e['coreAllowed'] = eff
    bp = bff_path(e['path'])
    e['bffPath'] = bp
    rel = e['path'][len('/api/v1'):] if e['path'].startswith('/api/v1') else e['path']
    if rel.startswith('/workspace/') or rel == '/workspace':
        e['bffGate'] = 'WORKSPACE_ROLES'
        if isinstance(eff, list):
            e['bffAllowed'] = sorted(set(eff) & set(WORKSPACE_ROLES))
            lost = sorted(set(eff) - set(WORKSPACE_ROLES) - {'INTEGRATION'})
            if lost:
                notes.append('BFF workspace gate removes core-permitted roles: ' + ','.join(lost))
        else:
            e['bffAllowed'] = sorted(WORKSPACE_ROLES)
    else:
        e['bffGate'] = 'authenticated'
        e['bffAllowed'] = eff
    e['notes'] = notes
    e['dataScope'] = sorted(set(e['scopeRules']) | ({'Own record only (token employee_id)'} if re.search(r'/me(/|$)', rel) else set())
                            | ({'Partner organisation binding (token org_scope /PARTNERS/<org>)'} if rel.startswith('/partner') else set())
                            | ({'Organisation scope, status-only disclosure (line management / HR)'} if rel.startswith('/workspace') else set()))

# role catalogue (descriptions from Roles.java javadoc where present)
roles_src = open(os.path.join(REPO, 'platform/wz-common/src/main/java/com/wellnessz/common/model/Roles.java')).read()
role_desc = {}
for m in re.finditer(r'/\*\*\s*(.*?)\s*\*/\s*public static final String (\w+) =', roles_src, re.S):
    role_desc[m.group(2)] = re.sub(r'\s*\*\s*', ' ', m.group(1)).strip()

policy = dict(
    generatedFrom=REPO, controllers=controllers, endpointCount=len(endpoints),
    coreUrlRules=core_url_rules, bffRules=[
        {'pattern': '/bff/session, /error', 'rule': 'permitAll'},
        {'pattern': '/bff/api/workspace/**', 'rule': 'hasAnyRole(' + ','.join(WORKSPACE_ROLES) + ')'},
        {'pattern': '/bff/api/**', 'rule': 'authenticated'},
        {'pattern': 'any other request', 'rule': 'denyAll'}],
    workspaceRoles=WORKSPACE_ROLES, coreWorkspaceResources=CORE_WORKSPACE,
    mfaRequiredRoles=['PARTNER_PROVIDER', 'TPA_ADJUSTER'],
    mfaNote='Production default strips PARTNER_PROVIDER / TPA_ADJUSTER unless amr/acr proves MFA; the local profile sets WZ_MFA_REQUIRED_ROLES empty.',
    roleDescriptions=role_desc, allRoles=ALL_ROLES, endpoints=endpoints)
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, 'expected-policy.json'), 'w') as f:
    json.dump(policy, f, indent=1)
unres = [e for e in endpoints if (e['preAuthorize'] or '').startswith('<') or any('<?' in x for x in [e['path']])]
print(f'controllers={controllers} endpoints={len(endpoints)} unresolved={len(unres)} conditional={sum(e["conditional"] for e in endpoints)}')
for e in unres[:30]:
    print('UNRESOLVED', e['method'], e['path'], e['preAuthorize'], e['source'])
