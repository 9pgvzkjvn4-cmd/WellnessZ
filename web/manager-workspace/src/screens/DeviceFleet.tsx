import { DEVICE_FLEET_PATH, availabilityPct, toDeviceFleet, type DeviceFleetCounts } from '../api/deviceFleet';
import { describeError } from '../components/format';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** Site device availability table (counts only). Hook-free. */
export function DeviceFleetTable({ data }: { data: DeviceFleetCounts }) {
  if (data.sites.length === 0) return <p className="muted">No medical devices are in service at your sites yet.</p>;
  const row = (label: string, s: DeviceFleetCounts['totals'], total = false) => {
    const pct = availabilityPct(s);
    return (
      <tr key={label} className={total ? 'total-row' : undefined}>
        <th scope="row">{label}</th>
        <td>{s.devices}</td>
        <td>
          {s.available}
          {pct !== undefined && (
            <>
              {' '}
              <meter min={0} max={100} value={pct} aria-hidden="true" /> <span className="muted small">{pct}%</span>
            </>
          )}
        </td>
        <td className={s.lockedOut > 0 ? 'text-warn' : undefined}>{s.lockedOut}</td>
        <td>{s.alerts}</td>
      </tr>
    );
  };
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Medical device availability per site</caption>
        <thead>
          <tr>
            <th scope="col">Site</th>
            <th scope="col">Devices in service</th>
            <th scope="col">Available</th>
            <th scope="col">Locked out (check or calibration due)</th>
            <th scope="col">Open alerts</th>
          </tr>
        </thead>
        <tbody>
          {data.sites.map((s) => row(s.siteCode, s))}
          {data.sites.length > 1 && row('All sites', data.totals, true)}
        </tbody>
      </table>
    </div>
  );
}

/** `#/device-fleet` (MANAGER) – how many medical devices are available at each site (counts only, no device or person detail). */
export function DeviceFleet({ client, onSessionExpired }: ScreenProps) {
  const data = useAsync(async () => toDeviceFleet(await client.api<unknown>(DEVICE_FLEET_PATH)), onSessionExpired);
  return (
    <section>
      <PageTitle>Medical device availability</PageTitle>
      <p className="lead">How many occupational-health devices (for example blood-pressure monitors, spirometers and audiometers) are in service and ready to use at each site. Counts only – Occupational Health manages the devices.</p>
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && <DeviceFleetTable data={data.data} />}
    </section>
  );
}
