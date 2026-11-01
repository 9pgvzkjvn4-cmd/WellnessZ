#!/usr/bin/env python3
"""Builds WellnessZ-Device-Integration-Demo-Guide.xlsx from demo-devices/scenarios.json and the demo data file.

usage: python3 demo-devices/tools/build_guide.py [out.xlsx]      (default: demo-devices/dist/WellnessZ-Device-Integration-Demo-Guide.xlsx)
Formatted like demo/tools/build_demo_guide.py: how to run, scenarios, demo users, one sheet per scenario (steps, expected
results, Pass / Fail), the connectivity options reference P1–P10 and the published demo devices.
"""
import json
import os
import subprocess
import sys

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

PACK = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PACK, 'dist', 'WellnessZ-Device-Integration-Demo-Guide.xlsx')

APP_FILE = {'console': 'device-console.html', 'lab': 'integration-lab.html', 'security': 'security-console.html'}
APP_NAME = {'console': 'Device Console', 'lab': 'Integration Lab', 'security': 'Security Console'}
APP_FILL = {'console': 'E6F0FA', 'lab': 'EAF4E4', 'security': 'FCEFE6'}

NAVY = '0D3B66'
HEAD = Font(bold=True, color='FFFFFF')
HEAD_FILL = PatternFill('solid', fgColor=NAVY)
TITLE = Font(bold=True, size=16, color=NAVY)
SUB = Font(bold=True, size=12, color=NAVY)
WRAP = Alignment(wrap_text=True, vertical='top')
THIN = Border(bottom=Side(style='thin', color='DBE3EE'))


def seed():
    js = ("global.window={};require(process.argv[1]);"
          "process.stdout.write(JSON.stringify(window.WZ_DEVICE_DEMO_SEED))")
    return json.loads(subprocess.check_output(['node', '-e', js, os.path.join(PACK, 'data', 'device-demo-data.js')]))


def table(ws, row, headers, rows, widths):
    for c, h in enumerate(headers, 1):
        cell = ws.cell(row=row, column=c, value=h)
        cell.font, cell.fill, cell.alignment = HEAD, HEAD_FILL, WRAP
    for r in rows:
        row += 1
        for c, v in enumerate(r, 1):
            cell = ws.cell(row=row, column=c, value=v)
            cell.alignment, cell.border = WRAP, THIN
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    return row


def fmt_data(d):
    if not d:
        return ''
    return '\n'.join(f'{k}: {v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)}' for k, v in d.items())


def main():
    sc = json.load(open(os.path.join(PACK, 'scenarios.json'), encoding='utf-8'))['scenarios']
    s = seed()
    users = {u['username']: u for u in s['users']}
    wb = Workbook()

    ws = wb.active
    ws.title = 'How to run the demo'
    ws['A1'] = 'WellnessZ Medical Device Integration Engine (E31–E34) – demo guide'
    ws['A1'].font = TITLE
    ws['A2'] = (f"Demo data version {s['meta']['version']}, demo clock {s['meta']['demoNow']}. People are synthetic; device brands and "
                f"models are entries of the client inventory ({len(s['catalogue'])} models); driver packages ({len(s['drivers'])}) and golden "
                f"datasets ({len(s['golden'])}) are the engine's own resources.")
    ws['A2'].alignment = WRAP
    lines = [
        ('Files', 'index.html (launcher), device-console.html, integration-lab.html, security-console.html and device-demo-data.js – keep all five files '
                  'in the same folder. This guide is WellnessZ-Device-Integration-Demo-Guide.xlsx.'),
        ('Browser', 'Google Chrome or Microsoft Edge (current version). Double-click index.html; no server, installation or internet connection is needed.'),
        ('One shared session', 'The three apps share one demo session stored in the browser (localStorage key "wellnessz-device-demo-session"). Open each app '
                               'in its own tab: a configuration published in the Device Console appears at once in the Integration Lab and the Security Console.'),
        ('Demo data file', 'device-demo-data.js holds the synthetic employees, users, sites and stations, the 133-model catalogue, the P1–P10 option packs, '
                           'the signed driver packages, the golden datasets (real sample payloads), published configurations, endpoints, calibration records '
                           'and a dead letter. Toolbar: Export saves wellnessz-device-demo-session.json, Import loads it, Reset returns to the starting data.'),
        ('Signing in', 'Use the user switcher in the demo toolbar at the top of each app. Only the users of that app are listed (sheet "Demo users").'),
        ('Guided mode', 'Pick a scenario in the toolbar ("Scenarios") to get a "next step" hint telling you which app and user to use; steps complete '
                        'automatically when the right user performs the action. Each scenario sheet lists the same steps with the expected result.'),
        ('Deterministic', 'A demo clock starts at the seeded time and advances one minute per action, so identifiers, times and hash chains are the same '
                          'on every run. Reset before each scenario for a clean run.'),
        ('What is real', 'Parsing and decoding run in the browser on the real formats: HL7 v2 segments and ACK, BLE GATT values with IEEE-11073 SFLOAT / '
                         'FLOAT, POCT1-A and MUSE XML, CSV, GDT, FHIR R4, DICOM PS3.18 JSON, serial frames; SHA-256, HMAC-SHA256 and UUIDv5 are '
                         'computed; driver signatures are verified with WebCrypto Ed25519 (labelled SIMULATED if the browser lacks Ed25519).'),
        ('Limitations', 'A browser demonstration of the rules, not the production platform: no real devices, network, mTLS or PKI; the proof of possession '
                        'at enrolment is a SHA-256 stand-in; GLI-2012 predicted values are an approximation pending clinical verification. Do not enter '
                        'real personal data.'),
    ]
    r = 4
    for k, v in lines:
        ws.cell(row=r, column=1, value=k).font = SUB
        ws.cell(row=r, column=2, value=v).alignment = WRAP
        r += 1
    ws.column_dimensions['A'].width = 22
    ws.column_dimensions['B'].width = 125

    ws = wb.create_sheet('Scenarios')
    rows = []
    for x in sc:
        apps = sorted({st['app'] for st in x['steps']}, key=list(APP_FILE).index)
        rows.append([x['code'], x['title'], x['summary'], ', '.join(APP_NAME[a] for a in apps),
                     '\n'.join(f"{p['user']} – {p['role']}" for p in x['personas']), len(x['steps']), f"{x.get('minutes', len(x['steps']))} min", x['code'] + ' sheet'])
    table(ws, 1, ['ID', 'Scenario', 'What it shows', 'Apps used', 'Personas (sign in as)', 'Steps', 'Approx. duration', 'Details'], rows, [6, 44, 70, 34, 44, 7, 12, 11])
    ws.freeze_panes = 'A2'

    ws = wb.create_sheet('Demo users')
    rows = [[u['username'], u['name'], ', '.join(u['roles']), ', '.join(APP_NAME[a] for a in u['apps']), u.get('title', ''), u.get('employeeId', '')] for u in s['users']]
    last = table(ws, 1, ['Username', 'Name', 'Roles', 'App(s)', 'Title', 'Staff id'], rows, [18, 18, 36, 40, 42, 10])
    ws.cell(row=last + 2, column=1, value='Synthetic employees (device subjects)').font = SUB
    table(ws, last + 3, ['Employee id', 'Name', 'Sex', 'Date of birth', 'Height cm', 'Site', 'Job', 'Badge'],
          [[e['id'], e['name'], e['sex'], e['dob'], e['heightCm'], e['site'], e['job'], e['badge']] for e in s['employees']], [18, 18, 36, 40, 42, 10, 26, 14])
    ws.freeze_panes = 'A2'

    for x in sc:
        ws = wb.create_sheet(x['code'])
        ws['A1'] = f"{x['code']} – {x['title']}"
        ws['A1'].font = TITLE
        ws['A2'] = x['summary']
        ws['A2'].alignment = WRAP
        ws['A3'] = 'Personas: ' + '; '.join(f"{p['user']} ({p['role']}, {APP_NAME[p['app']]})" for p in x['personas'])
        ws['A3'].alignment = WRAP
        ws.merge_cells('A2:I2')
        ws.merge_cells('A3:I3')
        ws.row_dimensions[2].height = 45
        rows = []
        for n, st in enumerate(x['steps'], 1):
            u = users.get(st['user'], {})
            action = st['action_label'] + ('' if st['action'] == 'view' else f"\n(engine action: {st['action']})")
            rows.append([n, APP_NAME[st['app']], APP_FILE[st['app']], f"{st['user']} – {u.get('name', '')}", action, st['expected'], fmt_data(st.get('data')), '', ''])
        last = table(ws, 5, ['Step', 'App', 'File', 'Sign in as', 'Action', 'Expected result', 'Test data used', 'Pass / Fail', 'Notes'], rows, [6, 18, 22, 26, 46, 64, 40, 11, 30])
        for rr in range(6, last + 1):
            app = x['steps'][rr - 6]['app']
            for cc in (2, 3):
                ws.cell(row=rr, column=cc).fill = PatternFill('solid', fgColor=APP_FILL[app])
        dv = DataValidation(type='list', formula1='"Pass,Fail,Blocked,Not run"', allow_blank=True)
        ws.add_data_validation(dv)
        dv.add(f'H6:H{last}')
        ws.freeze_panes = 'A6'

    ws = wb.create_sheet('Connectivity options P1-P10')
    rows = []
    for o in s['optionPacks']['options']:
        ps = o['parameterSchema']
        models = [m for m in s['catalogue'] if o['option'] in m['options']]
        params = ', '.join(k + (' *' if k in ps.get('required', []) else '') for k in ps['properties'])
        rows.append([o['option'], o['name'], o['howItWorks'], ', '.join(o['typicalDevices']), o['securityLevel'], o['reliability'], o['effort'],
                     o.get('endpointKind') or 'none', ', '.join(o['prerequisites']), params, len(models),
                     ', '.join(sorted({d['packageId'] for d in s['drivers'] if o['option'] in d['options']})) or '—',
                     '\n'.join(f"{g['step']}. {g['text']}" for g in o['stepGuide'])])
    last = table(ws, 1, ['Option', 'Name', 'How it works', 'Typical devices', 'Security', 'Reliability', 'Effort', 'Endpoint kind', 'Prerequisites (wizard step 3)',
                         'Parameters (wizard step 4; * required)', 'Catalogue models', 'Demo driver packages', 'Step guide'], rows,
                 [7, 26, 60, 40, 10, 11, 9, 18, 36, 60, 10, 34, 80])
    ws.cell(row=last + 2, column=1, value='Common items (all options): ' + ', '.join(
        k + (' *' if k in s['optionPacks']['commonSchema'].get('required', []) else '') for k in s['optionPacks']['commonSchema']['properties'])).alignment = WRAP
    ws.freeze_panes = 'C2'

    ws = wb.create_sheet('Demo devices')
    cat = {m['id']: m for m in s['catalogue']}
    rows = [[c['instanceId'], c['id'], cat[c['modelCode']]['manufacturer'] + ' ' + cat[c['modelCode']]['model'], c['modelCode'], c['option'], c['siteCode'] + ' / ' + c['stationCode'],
             c['endpointId'] or '—', c['driver']['packageId'] + '@' + c['driver']['version'], c['status'] + (' (paused)' if c.get('paused') else ''), ', '.join(c['associationMethods']) or '—']
            for c in s['configurations']]
    last = table(ws, 1, ['Instance', 'Configuration', 'Model', 'Inventory id', 'Option', 'Site / station', 'Endpoint', 'Driver', 'Status', 'Association'], rows,
                 [12, 13, 46, 11, 8, 24, 16, 36, 22, 36])
    ws.cell(row=last + 2, column=1, value='Endpoints').font = SUB
    table(ws, last + 3, ['Endpoint', 'Kind', 'Site', 'Zone', 'Status', 'Devices'],
          [[e['endpointId'], e['kind'], e['siteCode'], e['zone'], e['status'], ', '.join(e['deviceInstanceIds'])] for e in s['endpoints']], [12, 13, 46, 11, 8, 24])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    wb.save(OUT)
    print('written', OUT, len(sc), 'scenarios', sum(len(x['steps']) for x in sc), 'steps', len(s['users']), 'users', len(s['optionPacks']['options']), 'options')


if __name__ == '__main__':
    main()
