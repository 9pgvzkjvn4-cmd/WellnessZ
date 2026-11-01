import { describe, expect, it } from 'vitest';
import { can, featureForRoute, homeRoute, navFor } from './roles';

const paths = (roles: string[]) => navFor(roles).map((n) => n.path);

describe('increment 6 – device console and endpoint security gating (API-Contract-E31-E34 §2, §18)', () => {
  it('DEVICE_ADMIN: wizard, catalogue, fleet, calibration, drivers (read), governance, model requests – no approvals, no security console', () => {
    expect(homeRoute(['DEVICE_ADMIN'])).toBe('/devices/wizard');
    expect(paths(['DEVICE_ADMIN'])).toEqual(expect.arrayContaining(['/devices/wizard', '/devices/catalogue', '/devices/fleet', '/devices/calibration', '/devices/drivers', '/devices/governance', '/devices/model-requests']));
    expect(paths(['DEVICE_ADMIN']).every((p) => p.startsWith('/devices/'))).toBe(true);
    expect(paths(['DEVICE_ADMIN'])).not.toContain('/devices/approvals');
    for (const f of ['deviceConfigWrite', 'deviceAdmin', 'devicePostureWrite', 'deviceDiagnostics', 'deviceCalibrationCheck'] as const) expect(can(['DEVICE_ADMIN'], f)).toBe(true);
    for (const f of ['deviceApprove', 'deviceDriverCertify', 'deviceDriverRevoke', 'securityEndpoints', 'securityEvents'] as const) expect(can(['DEVICE_ADMIN'], f)).toBe(false);
  });

  it('DEVICE_APPROVER lands on the four-eyes queue and cannot write configurations', () => {
    expect(homeRoute(['DEVICE_APPROVER'])).toBe('/devices/approvals');
    expect(can(['DEVICE_APPROVER'], 'deviceApprove')).toBe(true);
    expect(can(['DEVICE_APPROVER'], 'deviceDriverCertify')).toBe(true);
    expect(can(['DEVICE_APPROVER'], 'deviceConfigWrite')).toBe(false);
    expect(can(['DEVICE_APPROVER'], featureForRoute('/devices/wizard/CFG-000001')!)).toBe(true);
    expect(can(['DEVICE_APPROVER'], featureForRoute('/devices/fleet/DEV-000001')!)).toBe(false);
  });

  it('INTEGRATION_TESTER: drivers (register), wizard on test sites, scoping – no fleet, no approvals', () => {
    expect(can(['INTEGRATION_TESTER'], 'deviceDriverRegister')).toBe(true);
    expect(can(['INTEGRATION_TESTER'], 'deviceConfigWrite')).toBe(true);
    expect(can(['INTEGRATION_TESTER'], 'deviceAdmin')).toBe(false);
    expect(can(['INTEGRATION_TESTER'], 'deviceModelRequestScope')).toBe(true);
    expect(paths(['INTEGRATION_TESTER'])).not.toContain('/devices/fleet');
    expect(paths(['INTEGRATION_TESTER'])).not.toContain('/devices/approvals');
  });

  it('SECURITY_OPS lands on endpoints, has the whole security group, co-approves and revokes drivers', () => {
    expect(homeRoute(['SECURITY_OPS'])).toBe('/security/devices');
    const p = paths(['SECURITY_OPS']);
    for (const r of ['/security/devices', '/security/devices/alerts', '/security/devices/events', '/security/devices/zones', '/security/devices/keys', '/security/devices/secrets', '/security/devices/assurance', '/devices/approvals', '/devices/fleet']) expect(p).toContain(r);
    expect(can(['SECURITY_OPS'], featureForRoute('/security/devices/endpoints/EP-LHR-GW-01')!)).toBe(true);
    expect(can(['SECURITY_OPS'], 'deviceDriverRevoke')).toBe(true);
    expect(can(['SECURITY_OPS'], 'deviceConfigWrite')).toBe(false);
  });

  it('console roles never get a person-data screen', () => {
    for (const role of ['DEVICE_ADMIN', 'DEVICE_APPROVER', 'INTEGRATION_TESTER', 'SECURITY_OPS']) {
      for (const r of ['/queues', '/employees/E1/chart', '/cases/c1', '/results/worklist', '/testing/mro', '/support/cases']) expect(can([role], featureForRoute(r)!)).toBe(false);
    }
  });

  it('clinicians get calibration / QC and the device test review; NURSING_HEAD also fleet and catalogue', () => {
    expect(paths(['OH_CLINICIAN'])).toEqual(expect.arrayContaining(['/devices/calibration', '/devices/test-review']));
    expect(paths(['OH_CLINICIAN'])).not.toContain('/devices/wizard');
    expect(paths(['NURSING_HEAD'])).toEqual(expect.arrayContaining(['/devices/catalogue', '/devices/fleet', '/devices/calibration']));
    expect(can(['OH_CLINICIAN'], featureForRoute('/security/devices')!)).toBe(false);
  });

  it('device sub-routes are gated like their list', () => {
    expect(featureForRoute('/devices/catalogue/MD-027')).toBe('deviceCatalogue');
    expect(featureForRoute('/devices/wizard/CFG-000001')).toBe('deviceWizard');
    expect(featureForRoute('/devices/fleet/DEV-000001')).toBe('deviceFleet');
    expect(featureForRoute('/devices/diagnostics/DEV-000001')).toBe('deviceDiagnostics');
    expect(featureForRoute('/security/devices/endpoints/EP-1')).toBe('securityEndpoints');
  });
});
