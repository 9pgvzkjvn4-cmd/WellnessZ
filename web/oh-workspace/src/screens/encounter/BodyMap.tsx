import { useState } from 'react';
import type { BodyMapMark, BodySide } from '../../api/record';
import { humanise } from '../../components/format';
import { BODY_REGIONS, setMechanism, toggleRegion } from '../../domain/encounter';

/**
 * Body map (E03-BL-005): front / back view toggle, region toggle buttons (multi-site, aria-pressed – usable without a
 * pointer), and a mechanism pick-list per marked region. Stored as `{region, side, mechanism}` coded observations.
 */
export function BodyMap({ value, onChange, mechanisms, readOnly }: { value: BodyMapMark[]; onChange: (v: BodyMapMark[]) => void; mechanisms: { code: string; label: string }[]; readOnly?: boolean }) {
  const [side, setSide] = useState<BodySide>('FRONT');
  return (
    <fieldset className="body-map" id="enc-bodymap">
      <legend>Body map</legend>
      <div className="mode-switch" role="group" aria-label="Body view">
        {(['FRONT', 'BACK'] as const).map((s) => (
          <button key={s} type="button" className={`secondary small-button${side === s ? ' mode-active' : ''}`} aria-pressed={side === s} onClick={() => setSide(s)}>
            {humanise(s)} ({value.filter((m) => m.side === s).length})
          </button>
        ))}
      </div>
      <div className={`body-regions body-${side.toLowerCase()}`} role="group" aria-label={`${humanise(side)} regions`}>
        {BODY_REGIONS[side].map((r) => {
          const on = value.some((m) => m.side === side && m.region === r);
          return (
            <button key={r} type="button" className={`region-toggle${on ? ' region-on' : ''}`} aria-pressed={on} disabled={readOnly} onClick={() => onChange(toggleRegion(value, side, r))}>
              {on && <span aria-hidden="true">● </span>}
              {humanise(r)}
            </button>
          );
        })}
      </div>
      {value.length === 0 ? (
        <p className="small muted">No sites marked.</p>
      ) : (
        <ul className="plain-list marked-sites" aria-label="Marked sites">
          {value.map((m) => {
            const id = `mech-${m.side}-${m.region}`;
            return (
              <li key={`${m.side}-${m.region}`} className="inline-row">
                <span>
                  {humanise(m.region)} <span className="small muted">({humanise(m.side)})</span>
                </span>
                <label htmlFor={id} className="visually-hidden">
                  Mechanism for {humanise(m.region)} ({humanise(m.side)})
                </label>
                <select id={id} value={m.mechanism ?? ''} disabled={readOnly} onChange={(e) => onChange(setMechanism(value, m.side, m.region, e.target.value))}>
                  <option value="">Mechanism…</option>
                  {mechanisms.map((x) => (
                    <option key={x.code} value={x.code}>{x.label}</option>
                  ))}
                </select>
                {!readOnly && (
                  <button type="button" className="link-button" onClick={() => onChange(toggleRegion(value, m.side, m.region))} aria-label={`Remove ${humanise(m.region)} (${humanise(m.side)})`}>
                    Remove
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </fieldset>
  );
}
