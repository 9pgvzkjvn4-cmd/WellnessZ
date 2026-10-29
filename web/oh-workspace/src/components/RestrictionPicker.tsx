import { useId, useState } from 'react';
import type { RestrictionDef } from '../api/platform';
import { restrictionLabel } from '../domain/fitness';
import { type FieldError } from './forms';
import { errorFor } from '../domain/validation';

export interface RestrictionEntry {
  code: string;
  value: string;
}

/** Restrictions from the catalogue (functional wording only) with a value where the catalogue entry needs one. */
export function RestrictionPicker({ catalogue, value, onChange, idPrefix, errors = [], label = 'Restrictions', disabled }: {
  catalogue: readonly RestrictionDef[];
  value: RestrictionEntry[];
  onChange: (v: RestrictionEntry[]) => void;
  idPrefix: string;
  errors?: readonly FieldError[];
  label?: string;
  disabled?: boolean;
}) {
  const uid = useId();
  const [pick, setPick] = useState('');
  const available = catalogue.filter((c) => c.active && !value.some((v) => v.code === c.code));
  return (
    <fieldset className="restriction-picker" id={`${idPrefix}s`} tabIndex={-1}>
      <legend>{label}</legend>
      {errorFor(errors, `${idPrefix}s`) && <p className="field-error-text small">{errorFor(errors, `${idPrefix}s`)}</p>}
      {value.length === 0 ? (
        <p className="small muted">None selected.</p>
      ) : (
        <ul className="plain-list">
          {value.map((r, i) => {
            const def = catalogue.find((c) => c.code === r.code);
            const id = `${idPrefix}-${i}`;
            const err = errorFor(errors, id);
            return (
              <li key={r.code} className="inline-row">
                <span>{restrictionLabel(def, r.code, r.value)}</span>
                {def?.valueRequired && (
                  <>
                    <label htmlFor={id} className="visually-hidden">
                      Value{def.unit ? ` (${def.unit})` : ''} for {restrictionLabel(def, r.code)}
                    </label>
                    <input
                      id={id}
                      className="value-input"
                      type="number"
                      min={0}
                      disabled={disabled}
                      value={r.value}
                      aria-invalid={!!err}
                      onChange={(e) => onChange(value.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))}
                    />
                    {def.unit && <span className="small">{def.unit}</span>}
                  </>
                )}
                {err && <span className="field-error-text small">{err}</span>}
                {!disabled && (
                  <button type="button" className="link-button" onClick={() => onChange(value.filter((_, j) => j !== i))} aria-label={`Remove ${restrictionLabel(def, r.code, r.value)}`}>
                    Remove
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {!disabled && (
        <div className="inline-row">
          <label htmlFor={uid} className="visually-hidden">
            Add restriction
          </label>
          <select id={uid} value={pick} onChange={(e) => setPick(e.target.value)}>
            <option value="">Add restriction…</option>
            {available.map((c) => (
              <option key={c.code} value={c.code}>{restrictionLabel(c, c.code)}</option>
            ))}
          </select>
          <button
            type="button"
            className="secondary small-button"
            disabled={!pick}
            onClick={() => {
              onChange([...value, { code: pick, value: '' }]);
              setPick('');
            }}
          >
            Add
          </button>
        </div>
      )}
    </fieldset>
  );
}
