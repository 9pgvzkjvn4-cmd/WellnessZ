#!/usr/bin/env python3
"""Builds IntiMed-Demo-Guide.xlsx from docs/demo/Demo-Guide.md (how to run, personas, scenarios with Pass/Fail columns, templates)."""
import re, sys, os
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment
from openpyxl.worksheet.datavalidation import DataValidation
md = open(sys.argv[1]).read()
out = sys.argv[2]
def tables(text):
    res, cur = [], []
    for line in text.splitlines():
        if line.startswith("|"):
            cells = [c.strip().replace("**", "").replace("`", "") for c in line.strip().strip("|").split("|")]
            if all(re.fullmatch(r":?-+:?", c) for c in cells): continue
            cur.append(cells)
        elif cur:
            res.append(cur); cur = []
    if cur: res.append(cur)
    return res
T = tables(md)
wb = Workbook()
H = PatternFill("solid", fgColor="0B5FFF"); HF = Font(color="FFFFFF", bold=True)
def sheet(ws, rows, widths, extra=None):
    hdr = rows[0] + (extra or [])
    ws.append(hdr)
    for c in ws[1]: c.fill = H; c.font = HF; c.alignment = Alignment(wrap_text=True, vertical="top")
    for r in rows[1:]: ws.append(r + [""] * len(extra or []))
    for i, w in enumerate(widths): ws.column_dimensions[chr(65 + i)].width = w
    for row in ws.iter_rows(min_row=2):
        for c in row: c.alignment = Alignment(wrap_text=True, vertical="top")
    ws.freeze_panes = "A2"
ws = wb.active; ws.title = "How to run"
start = md.split("## 2.")[0]
for line in [l for l in start.splitlines() if l.strip()]:
    ws.append([re.sub(r"[*`#]", "", line)])
ws.column_dimensions["A"].width = 140
for r in ws.iter_rows():
    for c in r: c.alignment = Alignment(wrap_text=True)
sheet(wb.create_sheet("Personas"), T[0], [20, 52, 60])
s = wb.create_sheet("Scenarios"); sheet(s, T[1], [6, 34, 24, 70, 60], ["Pass / Fail", "Tester", "Notes"])
dv = DataValidation(type="list", formula1='"Pass,Fail,Blocked,Not run"'); s.add_data_validation(dv); dv.add("F2:F200")
sheet(wb.create_sheet("Template scenarios"), T[2], [22, 44, 10, 18, 60], ["Pass / Fail"])
wb.save(out)
print("written", out)
