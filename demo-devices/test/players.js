// API-level implementations of every scenario step (the UI tests drive the same steps through the screens).
// Each step function is self-contained – (DX, C, c) => partial context – so it can be serialised into a browser page
// (record-demo.js) or run in Node (engine.test.js). Steps never hard-code generated ids: they look records up by
// model, station or status ("ID-agnostic").
'use strict';

const enrolProof = (C, token, thumb) => C.sha256('enrol:' + token + ':' + thumb);

const P = {
  'S01-ble-bp-p3': [
    (DX) => ({ cfg: DX.createConfiguration({ modelCode: 'MD-037' }).id }),
    (DX, C, c) => { DX.saveStep(c.cfg, 2, { option: 'P3' }); },
    (DX, C, c) => { const cfg = DX.config(c.cfg); DX.saveStep(c.cfg, 3, { endpointId: 'EP-LHR-TAB-02', equipmentId: 'BP-LHR-07', serial: 'UA651-77344', items: cfg.prerequisites.filter((p) => !p.auto).map((p) => ({ code: p.code, status: 'DONE', evidence: { text: 'Checked on site' } })) }); },
    (DX, C, c) => { const d = DX.stepFourDefaults(c.cfg); DX.saveStep(c.cfg, 4, { parameters: d.parameters, common: d.common }); },
    (DX, C, c) => { DX.saveStep(c.cfg, 5, { associationMethods: ['SESSION', 'BADGE'], stationCode: 'LHR-OH-ROOM-2' }); },
    (DX, C, c) => ({ sim: DX.runConfigTest(c.cfg, { mode: 'SIMULATOR' }).status }),
    (DX, C, c) => { DX.saveStep(c.cfg, 6, { simulatorOnlyJustification: 'New tablet EP-LHR-TAB-02 is enrolled at publish; live check at first use' }); },
    (DX, C, c) => { DX.nurseReview(c.cfg, { outcome: 'ACCEPTED', note: 'Preview fields correct' }); },
    (DX, C, c) => { DX.submitConfiguration(c.cfg); },
    (DX, C, c) => { DX.approveConfiguration(c.cfg, { note: 'Bindings and test evidence checked' }); },
    (DX, C, c) => { DX.coApproveConfiguration(c.cfg); },
    (DX, C, c) => { const r = DX.publishConfiguration(c.cfg); return { token: r.enrolmentToken, instance: r.deviceInstanceId }; },
    (DX, C, c) => { const thumb = C.sha256('demo-cert:EP-LHR-TAB-02:new'); DX.enrolEndpoint({ enrolmentToken: c.token, endpointKind: 'MOBILE_APP', certificateSha256: thumb, proof: C.sha256('enrol:' + c.token + ':' + thumb), hostname: 'tab-lhr-02' }); },
    (DX, C, c) => { DX.openCaptureSession({ instanceId: c.instance, employeeId: 'E1004' }); },
    (DX, C, c) => { const r = DX.ingest({ instanceId: c.instance, payload: C.SIM.bleBloodPressure({ at: DX.session().clock, systolic: 128, diastolic: 82, pulse: 71 }) }); return { obs: r.observationIds[0], status: r.status }; },
    (DX, C, c) => { DX.acceptObservation(c.obs, { acknowledgedFlags: ['cuff_size_unknown'] }); }
  ],
  'S02-hl7-vitals-p4': [
    (DX) => ({ cfg: DX.createConfiguration({ modelCode: 'MD-027' }).id }),
    (DX, C, c) => { DX.saveStep(c.cfg, 2, { option: 'P4' }); },
    (DX, C, c) => { const cfg = DX.config(c.cfg); DX.saveStep(c.cfg, 3, { endpointId: 'EP-LHR-GW-01', equipmentId: 'VS-LHR-03', serial: 'CSM-100077', items: cfg.prerequisites.filter((p) => !p.auto).map((p) => ({ code: p.code, status: 'DONE', evidence: { text: 'Firewall change CHG-4471' } })) }); },
    (DX, C, c) => {
      const d = DX.stepFourDefaults(c.cfg); const p = JSON.parse(JSON.stringify(d.parameters));
      p.deviceAddress = { ip: '10.20.30.47' }; p.msh.sendingApplication = 'CONNEX';
      const bad = JSON.parse(JSON.stringify(p)); bad.mllp.tls = 'EXCEPTION_SEGMENTED_VLAN';
      let refused = null; try { DX.saveStep(c.cfg, 4, { parameters: bad, common: d.common }); } catch (e) { refused = (e.errors || []).map((x) => x.code).join(','); }
      DX.saveStep(c.cfg, 4, { parameters: p, common: d.common });
      return { refused };
    },
    (DX, C, c) => { DX.saveStep(c.cfg, 5, { associationMethods: ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'], stationCode: 'LHR-OH-ROOM-2' }); },
    (DX, C, c) => ({ sim: DX.runConfigTest(c.cfg, { mode: 'SIMULATOR' }).status }),
    (DX, C, c) => ({ live: DX.runConfigTest(c.cfg, { mode: 'LIVE' }).status }),
    (DX, C, c) => { DX.nurseReview(c.cfg, { outcome: 'ACCEPTED' }); },
    (DX, C, c) => { DX.submitConfiguration(c.cfg); },
    (DX, C, c) => { DX.approveConfiguration(c.cfg, {}); },
    (DX, C, c) => ({ instance: DX.publishConfiguration(c.cfg).deviceInstanceId }),
    (DX, C, c) => { const r = DX.ingest({ instanceId: c.instance, payload: C.SIM.hl7Vitals({ at: DX.session().clock, employeeId: 'E1003', name: 'Rami Ramp', dob: '1990-07-02', sex: 'M', systolic: 131, diastolic: 84, pulse: 71, spo2: 97, temperature: 36.6, cuff: 'ADULT', facility: 'LHR-OH-ROOM-2', serial: 'CSM-100077' }) }); return { status: r.status, ack: r.ack }; },
    (DX, C, c) => { const g = DX.session().golden.find((x) => x.datasetId === 'vitals/csm-pcd01/sys-400'); return { blocked: DX.ingest({ instanceId: c.instance, payload: g.payload, datasetId: g.datasetId }).status }; }
  ],
  'S03-poct-p5-qc-lot': [
    (DX, C) => ({ s1: DX.ingest({ instanceId: 'DEV-000006', payload: C.SIM.poct1a({ at: DX.session().clock, employeeId: 'E1001', value: 5.6, lot: 'AF-LOT-2026-10', controlId: 2001 }) }).status }),
    (DX, C) => ({ s2: DX.ingest({ instanceId: 'DEV-000006', payload: C.SIM.poct1a({ at: DX.session().clock, employeeId: 'E1001', value: 6.1, lot: 'AF-LOT-2026-11', controlId: 2002 }) }).status }),
    (DX) => { DX.recordCalibrationCheck({ instanceId: 'DEV-000006', checkType: 'POC_QC_LOT', outcome: 'PASS', lot: 'AF-LOT-2026-11', lotExpiry: '2027-02-28' }); },
    (DX, C) => { const r = DX.ingest({ instanceId: 'DEV-000006', payload: C.SIM.poct1a({ at: DX.session().clock, employeeId: 'E1001', value: 6.1, lot: 'AF-LOT-2026-11', controlId: 2003 }) }); return { s3: r.status, obs: r.observationIds[0] }; },
    (DX, C, c) => { DX.acceptObservation(c.obs, {}); }
  ],
  'S04-spiro-p2-lockout': [
    (DX) => { DX.viewed('calibration'); return { locked: DX.calibrationStatus('DEV-000001').lockedOut }; },
    (DX, C) => { const e = DX.employee('E1002'); return { s1: DX.ingest({ instanceId: 'DEV-000001', payload: C.SIM.gdtSpirometry({ at: DX.session().clock, employeeId: e.id, last: 'Pilot', first: 'Paul', dob: e.dob, sex: e.sex, heightCm: e.heightCm, fev1: 3.88, fvc: 4.95, pef: 9.2, fef2575: 3.9, grade: 'A', nAccept: 3, fev1Trials: '3.88;3.84;3.80', fvcTrials: '4.95;4.90;4.86' }), headers: { 'X-File-Name': 'E1002_a.gdt' } }).status }; },
    (DX) => { DX.recordCalibrationCheck({ instanceId: 'DEV-000001', checkType: 'SPIRO_SYRINGE_3L', outcome: 'PASS', values: { volumeL: 3.02, errorPct: 0.7 } }); },
    (DX) => { DX.releaseLockout('DEV-000001'); },
    (DX, C) => { const e = DX.employee('E1002'); const r = DX.ingest({ instanceId: 'DEV-000001', payload: C.SIM.gdtSpirometry({ at: DX.session().clock, employeeId: e.id, last: 'Pilot', first: 'Paul', dob: e.dob, sex: e.sex, heightCm: e.heightCm, orderRef: 'ORD-2026-000101', fev1: 3.91, fvc: 4.97, pef: 9.3, fef2575: 3.9, grade: 'A', nAccept: 3, fev1Trials: '3.91;3.86;3.82', fvcTrials: '4.97;4.91;4.88' }), headers: { 'X-File-Name': 'E1002_b.gdt' } }); return { s2: r.status, obs: r.observationIds[0] }; },
    (DX, C, c) => { DX.acceptObservation(c.obs, {}); }
  ],
  'S05-unmatched': [
    (DX) => { const g = DX.session().golden.find((x) => x.datasetId === 'vitals/csm-pcd01/unknown-employee'); const r = DX.ingest({ instanceId: 'DEV-000003', payload: g.payload, datasetId: g.datasetId }); return { status: r.status, um: r.unmatchedIds[0], obs: r.observationIds[0] }; },
    (DX, C, c) => { DX.resolveUnmatched(c.um, { employeeId: 'E1001', reason: 'CONFIRMED_IDENTITY' }); },
    (DX, C, c) => { DX.acceptObservation(c.obs, {}); }
  ],
  'S06-dead-letter-replay': [
    (DX) => { const g = DX.session().golden.find((x) => x.datasetId === 'vitals/csm-pcd01/missing-pid'); const r = DX.ingest({ instanceId: 'DEV-000003', payload: g.payload, datasetId: g.datasetId }); return { dl: r.deadLetterId, ack: r.ack, status: r.status }; },
    (DX, C, c) => { DX.discardDeadLetter(c.dl, { reason: 'Vendor confirmed a test message without patient' }); },
    (DX) => { const dl = DX.session().deadLetters.find((d) => d.code === 'route_target_unavailable' && d.status === 'OPEN'); const r = DX.replayDeadLetter(dl.id, {}); return { replay: r.result.status, obs: r.result.observationIds[0] }; },
    (DX, C, c) => { DX.acceptObservation(c.obs, {}); }
  ],
  'S07-breath-alcohol-p1': [
    (DX) => { DX.viewed('configuration:DEV-000005'); },
    (DX, C) => ({ s1: DX.ingest({ instanceId: 'DEV-000005', payload: C.SIM.serialFc20({ at: DX.session().clock, brac: 0, type: 'SCREEN', testNo: 51, cal: '2026-10-25', serial: 'FC20-55021' }) }).status }),
    (DX) => { DX.openCaptureSession({ instanceId: 'DEV-000005', employeeId: 'E1003', worklistId: 'DAT-2026-000042' }); },
    (DX, C) => { const r = DX.ingest({ instanceId: 'DEV-000005', payload: C.SIM.serialFc20({ at: DX.session().clock, brac: 0, type: 'SCREEN', testNo: 52, cal: '2026-10-25', serial: 'FC20-55021' }) }); return { s2: r.status, obs: r.observationIds[0] }; },
    (DX) => { DX.viewed('capture'); },
    (DX, C, c) => { DX.acceptObservation(c.obs, {}); }
  ],
  'S08-four-eyes': [
    (DX) => { const cfg = DX.session().configurations.find((x) => x.modelCode === 'MD-045' && x.option === 'P2' && x.status === 'AWAITING_APPROVAL'); let refused = null; try { DX.approveConfiguration(cfg.id, {}); } catch (e) { refused = e.code; } return { cfg: cfg.id, refused }; },
    (DX, C, c) => { DX.approveConfiguration(c.cfg, { note: 'Independent review' }); },
    (DX) => { DX.viewed('certificate'); },
    (DX, C, c) => { DX.publishConfiguration(c.cfg); }
  ],
  'S09-gateway-enrolment': [
    (DX) => { const r = DX.createEndpoint({ kind: 'GATEWAY', siteCode: 'DXB', zone: 'GATEWAY_ZONE' }); return { ep: r.endpointId, token: r.enrolmentToken }; },
    (DX, C, c) => { const thumb = C.sha256('demo-cert:' + c.ep); DX.enrolEndpoint({ enrolmentToken: c.token, endpointKind: 'GATEWAY', certificateSha256: thumb, proof: C.sha256('enrol:' + c.token + ':' + thumb), hostname: 'gw-dxb-02' }); return { thumb }; },
    (DX, C, c) => { let code = null; try { DX.enrolEndpoint({ enrolmentToken: c.token, endpointKind: 'GATEWAY', certificateSha256: c.thumb, proof: C.sha256('enrol:' + c.token + ':' + c.thumb) }); } catch (e) { code = e.code; } return { reuse: code }; },
    (DX, C, c) => ({ rotated: !!DX.rotateToken(c.ep).endpointToken }),
    (DX) => { DX.viewed('events'); return { chain: DX.verifyEvents().intact }; }
  ],
  'S10-isolation-drill': [
    (DX) => ({ drill: DX.runIsolationDrill({ endpointId: 'EP-LHR-TAB-01' }).minutesToIsolate }),
    (DX, C) => { const r = DX.ingest({ instanceId: 'DEV-000004', payload: C.SIM.bleBloodPressure({ at: DX.session().clock, systolic: 126, diastolic: 80, pulse: 66, badge: 'BDG-71005' }) }); return { parked: r.status, dl: r.deadLetterId }; },
    (DX) => { let code = null; try { DX.releaseEndpoint('EP-LHR-TAB-01'); } catch (e) { code = e.code; } return { refusedRelease: code }; },
    (DX) => ({ cfg: DX.reviseConfiguration(DX.configByInstance('DEV-000004').id, { reason: 'Re-certification after isolation' }).id }),
    (DX, C, c) => { DX.runConfigTest(c.cfg, { mode: 'SIMULATOR' }); },
    (DX, C, c) => { DX.saveStep(c.cfg, 6, { simulatorOnlyJustification: 'Endpoint isolated – live check after the security release' }); },
    (DX, C, c) => { DX.nurseReview(c.cfg, { outcome: 'ACCEPTED' }); },
    (DX, C, c) => { DX.submitConfiguration(c.cfg); },
    (DX, C, c) => { DX.approveConfiguration(c.cfg, {}); },
    (DX, C, c) => { DX.coApproveConfiguration(c.cfg); },
    (DX, C, c) => ({ pub: DX.publishConfiguration(c.cfg).status }),
    (DX) => { DX.releaseEndpoint('EP-LHR-TAB-01'); },
    (DX, C, c) => { const r = DX.replayDeadLetter(c.dl, {}); return { replay: r.result.status }; },
    (DX) => { DX.viewed('events'); return { chain: DX.verifyEvents().intact }; }
  ]
};

module.exports = { PLAYERS: P, enrolProof };
