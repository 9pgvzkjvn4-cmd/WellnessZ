/**
 * Increment 6 – site device availability for the manager workspace (docs/increment-06/API-Contract-E31-E34.md §18;
 * `GET /workspace/device-fleet`, E32 `FleetService.workspace`): per site the number of devices, how many are available,
 * how many are locked out and the open alerts. Counts only – the mapper copies these numbers and nothing else (no device
 * trace, no person data).
 */
export const DEVICE_FLEET_PATH = '/workspace/device-fleet';

export interface SiteDeviceCounts {
  siteCode: string;
  devices: number;
  available: number;
  lockedOut: number;
  alerts: number;
}
export interface DeviceFleetCounts {
  sites: SiteDeviceCounts[];
  totals: Omit<SiteDeviceCounts, 'siteCode'>;
}

const count = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);

export function toDeviceFleet(raw: unknown): DeviceFleetCounts {
  const list = raw && typeof raw === 'object' && Array.isArray((raw as { sites?: unknown }).sites) ? ((raw as { sites: unknown[] }).sites) : [];
  const sites: SiteDeviceCounts[] = [];
  for (const r of list) {
    if (!r || typeof r !== 'object') continue;
    const o = r as Record<string, unknown>;
    if (typeof o.siteCode !== 'string' || !o.siteCode) continue;
    sites.push({ siteCode: o.siteCode, devices: count(o.devices), available: count(o.available), lockedOut: count(o.lockedOut), alerts: count(o.alerts) });
  }
  sites.sort((a, b) => a.siteCode.localeCompare(b.siteCode));
  const totals = sites.reduce((t, s) => ({ devices: t.devices + s.devices, available: t.available + s.available, lockedOut: t.lockedOut + s.lockedOut, alerts: t.alerts + s.alerts }), { devices: 0, available: 0, lockedOut: 0, alerts: 0 });
  return { sites, totals };
}

/** Share of devices available (0–100, whole per cent); undefined without devices. */
export const availabilityPct = (s: { devices: number; available: number }): number | undefined => (s.devices > 0 ? Math.round((s.available / s.devices) * 100) : undefined);
