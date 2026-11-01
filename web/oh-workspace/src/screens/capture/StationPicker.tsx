import { useState, type FormEvent } from 'react';
import { ApiError, type AuthClient } from '../../auth/AuthClient';
import { captureApi } from '../../api/deviceCapture';
import { useAsync } from '../../components/useAsync';
import { setStation, useStation } from './workstation';

const CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

/**
 * Which capture station this workstation is at (devices are listed per station). Stations come from the E32 catalogue when
 * the role may read it; otherwise (e.g. collectors) the site and station codes are typed once per session.
 */
export function StationPicker({ client, onSessionExpired, idPrefix = 'station' }: { client: AuthClient; onSessionExpired: () => void; idPrefix?: string }) {
  const station = useStation();
  const [editing, setEditing] = useState(!station);
  const stations = useAsync(() => captureApi(client).stations().then((l) => l.filter((s) => s.active !== false && s.kind !== 'KIOSK')), onSessionExpired, []);
  const [site, setSite] = useState(station?.siteCode ?? '');
  const [code, setCode] = useState(station?.stationCode ?? '');
  const [error, setError] = useState<string>();
  const forbidden = stations.error instanceof ApiError && (stations.error.status === 403 || stations.error.status === 404);
  const list = stations.data ?? [];

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!CODE.test(site.trim()) || !CODE.test(code.trim())) {
      setError('Enter the site code and the station code, e.g. LHR and LHR-OH-ROOM-1.');
      return;
    }
    setError(undefined);
    setStation({ siteCode: site.trim().toUpperCase(), stationCode: code.trim().toUpperCase() });
    setEditing(false);
  }

  if (station && !editing) {
    return (
      <p className="station-line small">
        Station: <strong>{list.find((s) => s.stationCode === station.stationCode)?.name ?? station.stationCode}</strong> <span className="muted">({station.siteCode})</span>{' '}
        <button type="button" className="link-button" onClick={() => setEditing(true)}>
          Change station
        </button>
      </p>
    );
  }

  return (
    <form className="station-picker inline-form" onSubmit={submit} aria-label="Capture station">
      {list.length > 0 && !forbidden ? (
        <div className="field">
          <label htmlFor={`${idPrefix}-select`}>Capture station</label>
          <select
            id={`${idPrefix}-select`}
            value={code}
            onChange={(e) => {
              const s = list.find((x) => x.stationCode === e.target.value);
              setCode(e.target.value);
              setSite(s?.siteCode ?? '');
            }}
          >
            <option value="">Select the room you are in…</option>
            {list.map((s) => (
              <option key={s.stationCode} value={s.stationCode}>
                {s.name ?? s.stationCode} ({s.siteCode})
              </option>
            ))}
          </select>
        </div>
      ) : (
        <>
          <div className="field">
            <label htmlFor={`${idPrefix}-site`}>Site code</label>
            <input id={`${idPrefix}-site`} value={site} maxLength={64} placeholder="LHR" onChange={(e) => setSite(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor={`${idPrefix}-code`}>Station code</label>
            <input id={`${idPrefix}-code`} value={code} maxLength={64} placeholder="LHR-OH-ROOM-1" onChange={(e) => setCode(e.target.value)} />
          </div>
        </>
      )}
      <button type="submit">Use this station</button>
      {station && (
        <button type="button" className="secondary" onClick={() => setEditing(false)}>
          Keep {station.stationCode}
        </button>
      )}
      {error && (
        <p className="field-error-text" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
