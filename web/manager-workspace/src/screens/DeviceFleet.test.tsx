import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AuthClient } from '../auth/AuthClient';
import { availabilityPct, DEVICE_FLEET_PATH, toDeviceFleet } from '../api/deviceFleet';
import { canUse, resolveFeatureRoute, workspaceNav } from '../auth/roles';
import { DeviceFleet, DeviceFleetTable } from './DeviceFleet';

const raw = {
  sites: [
    { siteCode: 'LHR', devices: 8, available: 6, lockedOut: 1, alerts: 2, deviceInstanceId: 'DEV-000003', employeeId: 'E1001', trace: 'x' },
    { siteCode: 'DXB', devices: 2, available: 2, lockedOut: 0, alerts: 0 },
    { devices: 3 },
    { siteCode: 'MAN', devices: -1, available: 'n/a' },
  ],
};

describe('device availability (GET /workspace/device-fleet, counts only)', () => {
  it('copies only the counts per site, sorted, with totals', () => {
    const d = toDeviceFleet(raw);
    expect(d.sites).toEqual([
      { siteCode: 'DXB', devices: 2, available: 2, lockedOut: 0, alerts: 0 },
      { siteCode: 'LHR', devices: 8, available: 6, lockedOut: 1, alerts: 2 },
      { siteCode: 'MAN', devices: 0, available: 0, lockedOut: 0, alerts: 0 },
    ]);
    expect(d.totals).toEqual({ devices: 10, available: 8, lockedOut: 1, alerts: 2 });
    expect(JSON.stringify(d)).not.toMatch(/DEV-|E1001|trace/);
    expect(toDeviceFleet(null).sites).toEqual([]);
    expect(availabilityPct({ devices: 8, available: 6 })).toBe(75);
    expect(availabilityPct({ devices: 0, available: 0 })).toBeUndefined();
  });

  it('renders a table with an all-sites total and an empty state', () => {
    const html = renderToStaticMarkup(<DeviceFleetTable data={toDeviceFleet(raw)} />);
    expect(html).toContain('<th scope="row">LHR</th>');
    expect(html).toContain('75%');
    expect(html).toContain('All sites');
    expect(html).toContain('class="text-warn"');
    expect(renderToStaticMarkup(<DeviceFleetTable data={toDeviceFleet({ sites: [] })} />)).toContain('No medical devices are in service');
  });

  it('is a MANAGER screen (the BFF workspace rule does not admit NURSING_HEAD)', () => {
    expect(canUse('deviceFleet', ['MANAGER'])).toBe(true);
    for (const r of ['HR', 'OH_CLINICIAN', 'NURSING_HEAD', 'SAFETY_MANAGER']) expect(canUse('deviceFleet', [r])).toBe(false);
    expect(workspaceNav(['MANAGER'])).toContainEqual({ path: '/device-fleet', label: 'Device availability' });
    expect(resolveFeatureRoute('/device-fleet', ['HR'])).toEqual({ feature: 'deviceFleet', allowed: false });
  });

  it('loads the workspace resource', () => {
    const api = vi.fn(() => new Promise(() => {}));
    const client = { mode: 'bff', init: vi.fn(), login: vi.fn(), logout: vi.fn(), api } as unknown as AuthClient;
    const html = renderToStaticMarkup(<DeviceFleet client={client} session={{ authenticated: true, roles: ['MANAGER'] }} onSessionExpired={() => {}} />);
    expect(html).toContain('Medical device availability');
    expect(html).toContain('Counts only');
    expect(DEVICE_FLEET_PATH).toBe('/workspace/device-fleet');
  });
});
