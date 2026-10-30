# -*- coding: utf-8 -*-
"""Writes docs/strategy/epics/README.md from the same data as the workbooks. Usage: python3 build_readme.py <output dir>"""
import math
import os
import sys

import strategy_data as D

OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else ".."
REFINE = {"H1": 1.5, "H2": 2.0, "H3": 2.5}
esc = lambda s: str(s).replace("|", "\\|").replace("\n", " ")

L = ["# WellnessZ epic catalogue – strategy epics E20–E31", "",
     "**Version 1.0** · 30 October 2026 · companion to the [Platform Enhancement Strategy](../Platform-Enhancement-Strategy.md)", "",
     "This folder holds the proposed epic catalogue beyond increment 5. Each new epic has a workbook in the client's epic "
     "format (same sheets, columns, review and decision dropdowns as `E09.xlsx`): *Epic Overview*, *High-Level Requirements*, "
     "*Feature List*, *Implementation Backlog* and *Legends*. The overview carries an extra row, *Evidence base (why now)*, "
     "that points to the release notes, traceability matrices and architecture open items the epic responds to.", "",
     "> Nothing in an E20+ workbook exists in the product today. Story points are indicative first-cut sizes; team-sprints apply an "
     "indicative velocity of 30 points per team per sprint and an uncertainty allowance (×1.5 H1, ×2.0 H2, ×2.5 H3). Benefits and "
     "KPI targets are indicative and must be baselined by their owners.", "",
     "## 1. Numbering", "",
     "| Range | Meaning |", "|---|---|",
     "| E01–E12, E14 | Client business epics – delivered in increments 1–5 (status *Done*, with sign-offs and connectors outstanding) |",
     "| E13, E15–E18 | Client platform epics (portal / app, integration, identity and security, analytics, master data and configuration) – partly covered by the base framework (status *Partial*), completed to production grade in H1 |",
     "| E19 | Security Hardening & Assurance – drafted as \"E15\" in `docs/security/epics/`; renumbered because E15 is the client's Integration epic. Only the ID prefix changes |",
     "| E20–E31 | New strategy epics proposed here (status *Planned*) |", "",
     "## 2. New epics", "",
     "| Epic | Name | Goal (one line) | Horizon | Team | HLR / F / BL | SP | Team-sprints | Workbook |",
     "|---|---|---|---|---|---|---|---|---|"]
tsp = tts = 0
for e in D.EPICS:
    sp = sum(b[4] for b in e["backlog"])
    ts = math.ceil(sp * REFINE[e["horizon"]] / D.VELOCITY)
    tsp += sp
    tts += ts
    fn = f"{e['id']}_{e['slug']}.xlsx"
    L.append(f"| **{e['id']}** | {esc(e['name'])} | {esc(e['one_line'])} | {e['span']} | {e['team']} | "
             f"{len(e['hlrs'])} / {len(e['features'])} / {len(e['backlog'])} | {sp} | {ts} | [{fn}]({fn}) |")
L.append(f"| | **Total** | | | | {sum(len(e['hlrs']) for e in D.EPICS)} / {sum(len(e['features']) for e in D.EPICS)} / "
         f"{sum(len(e['backlog']) for e in D.EPICS)} | **{tsp}** | **{tts}** | |")
L += ["", "## 3. Status of the existing catalogue", "",
      "| Epic | Name | Status | Delivered by | Items traced | Implemented | Sign-off pending | Partial |",
      "|---|---|---|---|---|---|---|---|"]
for (eid, name, status, by, notes, n, impl, so, part, hz) in D.EXISTING:
    f = lambda v: "–" if v is None else v
    L.append(f"| {eid} | {esc(name)} | {status} | {by} | {f(n)} | {f(impl)} | {f(so)} | {f(part)} |")
L += ["", "Counts come from the requirements traceability matrices of increments 1–5 (document 02 of each increment). "
      "*Sign-off pending* is used by increment 5 only; earlier increments count such items as partial. E05 also has one deferred training item.", "",
      "## 4. Catalogue and roadmap workbook", "",
      "[Epic-Catalogue-and-Roadmap.xlsx](Epic-Catalogue-and-Roadmap.xlsx) has five sheets:", "",
      "| Sheet | Content |", "|---|---|",
      "| Catalogue | All epics E01–E31 with category, status (Done / Partial / Planned), increment or horizon, traced-item counts, evidence and gaps, team, indicative SP and workbook |",
      "| Horizon roadmap | 12 quarters from November 2026: go-live gates, platform-epic completion, E19 and E20–E31 with milestones |",
      "| Dependencies | 37 predecessor → successor links, hard or soft, with the reason |",
      "| Capacity plan | Indicative team-sprints per epic and quarter, funded teams (6 → 5 → 4), reserve for run work, headroom and utilisation (formulas) |",
      "| KPI tree | North star, six outcomes and fifteen drivers with indicative targets, owners and contributing epics |", "",
      "## 5. Rebuilding", "",
      "The workbooks and this README are generated from one data source. From `docs/strategy/epics/build/`:", "",
      "```bash",
      "python3 -B build_epics.py ..       # E20–E31 workbooks",
      "python3 -B build_catalogue.py ..   # Epic-Catalogue-and-Roadmap.xlsx",
      "python3 -B build_readme.py ..      # this README",
      "```", "",
      "| File | Purpose |", "|---|---|",
      "| `epics_h1.py`, `epics_h23.py` | Epic content (overview, HLRs, features, backlog) |",
      "| `strategy_data.py` | Existing catalogue status, platform-epic completion effort, teams, KPI tree, velocity |",
      "| `wb_style.py` | Shared workbook styling (client layout) |",
      "| `build_epics.py`, `build_catalogue.py`, `build_readme.py` | Generators (requires `openpyxl`) |", "",
      "The builders reuse the layout code of the E15 / E19 security epic builder. Formulas (summary metrics, feature lookups, capacity "
      "and utilisation) are calculated when the workbook is opened in Excel or LibreOffice.", ""]

with open(os.path.join(OUT_DIR, "README.md"), "w", encoding="utf-8") as fh:
    fh.write("\n".join(L))
print("README written", tsp, tts)
