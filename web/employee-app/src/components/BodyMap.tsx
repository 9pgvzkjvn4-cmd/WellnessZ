import type { BodyMapRegion } from '../api/types';
import { shapeFor } from './bodyMapGeometry';

/**
 * Accessible body map (E01-BL-006).
 *
 * Regions come from `GET /intake/body-map` (`{code, label, side: FRONT|BACK}`). The SVG is a pointer shortcut and is
 * hidden from assistive technology; the list of native toggle buttons below it (`aria-pressed`) is the keyboard and
 * screen-reader alternative and works for every region – including codes the drawing does not know.
 *
 * Presentational and hook-free: the parent owns `selected` and `side`.
 */

export interface BodyMapLabels {
  bodyMap: string;
  bodyMapHint: string;
  front: string;
  back: string;
  selectedAreas: string;
  noneSelected: string;
  regionList: string;
}

export interface BodyMapProps {
  id: string;
  regions: readonly BodyMapRegion[];
  selected: readonly string[];
  side: 'FRONT' | 'BACK';
  onSideChange: (side: 'FRONT' | 'BACK') => void;
  onToggle: (code: string) => void;
  labels: BodyMapLabels;
  required?: boolean;
  error?: string;
}

export function BodyMap({ id, regions, selected, side, onSideChange, onToggle, labels, required, error }: BodyMapProps) {
  const visible = regions.filter((r) => String(r.side).toUpperCase() === side);
  const selectedLabels = regions.filter((r) => selected.includes(r.code)).map((r) => `${r.label} (${String(r.side).toUpperCase() === 'BACK' ? labels.back : labels.front})`);
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  return (
    <fieldset className="body-map" id={id} aria-describedby={error ? `${hintId} ${errorId}` : hintId} aria-invalid={error ? true : undefined}>
      <legend>
        {labels.bodyMap}
        {required && <span className="req" aria-hidden="true"> *</span>}
      </legend>
      <p id={hintId} className="muted small">
        {labels.bodyMapHint}
      </p>
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
      <div className="segmented" role="group" aria-label={labels.bodyMap}>
        {(['FRONT', 'BACK'] as const).map((s) => (
          <button key={s} type="button" className="segment" aria-pressed={side === s} onClick={() => onSideChange(s)}>
            {s === 'FRONT' ? labels.front : labels.back}
          </button>
        ))}
      </div>
      <div className="body-map-layout">
        <svg className="body-map-svg" viewBox="0 0 200 390" aria-hidden="true" focusable="false" data-side={side}>
          {visible.map((r) => {
            const shape = shapeFor(r);
            if (!shape) return null;
            const common = {
              className: selected.includes(r.code) ? 'region selected' : 'region',
              'data-code': r.code,
              onClick: () => onToggle(r.code),
            };
            return shape.kind === 'rect' ? (
              <rect key={r.code} {...common} x={shape.x} y={shape.y} width={shape.w} height={shape.h} rx={6}>
                <title>{r.label}</title>
              </rect>
            ) : (
              <ellipse key={r.code} {...common} cx={shape.cx} cy={shape.cy} rx={shape.rx} ry={shape.ry}>
                <title>{r.label}</title>
              </ellipse>
            );
          })}
        </svg>
        <ul className="region-list" aria-label={`${labels.regionList} – ${side === 'FRONT' ? labels.front : labels.back}`}>
          {visible.map((r) => (
            <li key={r.code}>
              <button type="button" className="toggle" aria-pressed={selected.includes(r.code)} data-code={r.code} onClick={() => onToggle(r.code)}>
                {r.label}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <p className="small" aria-live="polite">
        <strong>{labels.selectedAreas}:</strong> {selectedLabels.length ? selectedLabels.join(', ') : labels.noneSelected}
      </p>
    </fieldset>
  );
}
