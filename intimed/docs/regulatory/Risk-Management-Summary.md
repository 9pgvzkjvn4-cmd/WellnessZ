# Risk management summary (ISO 14971 – local release subset)

| Hazard | Cause | Risk control (design) | Verification |
|---|---|---|---|
| Value filed to the wrong patient | low-confidence association, stale bed binding, typed id error | thresholds 0.95/0.60, unmatched queue, ADT transfer/discharge, restricted categories without matching, harness probes isolated | XT-05, IT, unit |
| Wrong value or unit | unit mismatch, analyte factor, parsing error | UCUM conversion with block when impossible, both values in preview, plausibility holds, round-trip read-back | XT-08/XT-09, unit |
| Out-of-calibration device used | overdue calibration, failed QC | calibration lockout, QC-lot gate, two-tap checks, release by biomed | XT-14, demo TPL-05 |
| Result posted without review where review is required | OCR/manual capture, out-of-range | verify-before-post holds, clinician accept/correct/reject | XT-14, capture tests |
| Untested configuration in production | change after test | hash-bound publish gate, re-test trigger, four-eyes | kit 19, IT |
| Duplicate or replayed results | resend, replay attack | stable ids, idempotency, replay windows, AN-06 | XT-11, IT |
| Data unavailable when needed | device isolation, transport failure | fallback chain (other device → OCR → manual), gateway store-and-forward | capture fallback, sdk gateway |
| Alarm misinterpreted | alarm conveyed through IntiMed | alarms flagged, not interpreted (MDDS boundary) | classification record |

The complete risk management file (hazard log with severities/probabilities, review records) is maintained in the QMS
(deferred – IM-E10-BL-003 partial).
