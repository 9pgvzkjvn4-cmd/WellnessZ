# WellnessZ OHCM – Demo Pack

Standalone, offline demo of the four WellnessZ applications with **synthetic data only** (all people and organisations
are fictional). No server, installation or internet connection is needed.

## Contents (download pack)

| File | What it is |
|---|---|
| `index.html` | Launcher: opens the four apps, shows the demo session, reset / export / import |
| `employee-app.html` | Employee & Crew App (mobile-first) |
| `manager-workspace.html` | Manager & HR Workspace (status only) |
| `oh-workspace.html` | OH Clinical Workspace |
| `partner-portal.html` | Partner Portal (providers, EAP, TPA adjusters) |
| `demo-data.js` | **The demo data file** – the synthetic starting data loaded by all four apps |
| `WellnessZ-Demo-Guide.xlsx` | End-to-end demo guide: 10 scenarios, 73 steps, demo users, expected results, pass/fail column, rules checklist |
| `WellnessZ-Demo-Pack-End-to-End.mp4` (+ `.srt`) | Video walkthrough of six scenarios recorded from these apps |

## How to run

1. Keep all files in **one folder**.
2. Double-click `index.html` in **Google Chrome or Microsoft Edge**.
3. Open each application in its own tab. All four share **one demo session** (browser storage key
   `wellnessz-demo-session`): a referral made in one app appears immediately in the others.
4. Use the **demo toolbar** at the top of each app to switch user (only that app's users are listed), pick a scenario
   for "next step" hints, and **Export** the session to `wellnessz-demo-session.json`, **Import** it again, or
   **Reset** to the starting data in `demo-data.js`.
5. Follow `WellnessZ-Demo-Guide.xlsx` (one sheet per scenario).

The rules shown are the production rules, simulated in the browser: managers and HR see status only; mental-health and
drug-and-alcohol results are restricted classes; partners see only their own organisation and nothing after consent
withdrawal; small groups are suppressed; every action is written to a hash-chained audit log.

The demo is not the production platform: there is no real authentication, and data stays in the browser. Do not enter
real personal data.

## For developers

Source in `demo/src` (apps), `demo/shared` (engine, design system), `demo/data/demo-data.js` (seed) and
`demo/scenarios.json`. Build and test:

```bash
python3 demo/build.py && node demo/test/engine.test.js && node demo/test/smoke.js
```

```bash
for t in oh-workspace manager-workspace partner-portal; do node demo/test/$t.test.js; done
```

```bash
python3 demo/tools/build_demo_guide.py && node demo/test/record-demo.js
```

The engine API is documented in `demo/DEVELOPER-NOTES.md`.
