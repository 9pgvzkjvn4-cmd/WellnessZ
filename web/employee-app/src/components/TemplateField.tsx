import type { AnswerValue, Option, TemplateField as Field } from '../api/types';
import { fieldId } from '../intake/referralForm';

/**
 * Renders one non-structural template field (TEXT, TEXTAREA, SELECT, MULTISELECT, BOOLEAN, DATE).
 * BODY_MAP and SYMPTOMS are rendered by the form itself because they map to `bodyParts` / `symptoms`.
 * Unknown types fall back to a text input so a newer template never blocks a referral.
 * Hook-free and controlled.
 */
export interface TemplateFieldProps {
  field: Field;
  value: AnswerValue | undefined;
  onChange: (value: AnswerValue) => void;
  error?: string;
  labels: { yes: string; no: string; select: string };
}

function Label({ field }: { field: Field }) {
  return (
    <>
      {field.label}
      {field.required && <span className="req" aria-hidden="true"> *</span>}
    </>
  );
}

export function TemplateField({ field, value, onChange, error, labels }: TemplateFieldProps) {
  const id = fieldId(field.key);
  const errorId = `${id}-error`;
  const aria = {
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? errorId : undefined,
    'aria-required': field.required ? true : undefined,
  } as const;
  const errorEl = error ? (
    <p id={errorId} className="field-error">
      {error}
    </p>
  ) : null;
  const options: Option[] = field.options ?? [];
  const maxLength = field.maxLength ?? undefined;

  switch (field.type) {
    case 'BOOLEAN':
      return (
        <fieldset className="field" id={id} {...aria}>
          <legend>
            <Label field={field} />
          </legend>
          {errorEl}
          <div className="inline-options">
            {[true, false].map((b) => (
              <label key={String(b)} className="radio">
                <input type="radio" name={id} checked={value === b} onChange={() => onChange(b)} />
                {b ? labels.yes : labels.no}
              </label>
            ))}
          </div>
        </fieldset>
      );
    case 'MULTISELECT': {
      const arr = Array.isArray(value) ? value : [];
      return (
        <fieldset className="field" id={id} {...aria}>
          <legend>
            <Label field={field} />
          </legend>
          {errorEl}
          <div className="chips">
            {options.map((o) => (
              <label key={o.value} className="chip">
                <input
                  type="checkbox"
                  value={o.value}
                  checked={arr.includes(o.value)}
                  onChange={() => onChange(arr.includes(o.value) ? arr.filter((v) => v !== o.value) : [...arr, o.value])}
                />
                {o.label}
              </label>
            ))}
          </div>
        </fieldset>
      );
    }
    case 'SELECT':
      return (
        <div className="field">
          <label htmlFor={id}>
            <Label field={field} />
          </label>
          {errorEl}
          <select id={id} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} {...aria}>
            <option value="">{labels.select}</option>
            {options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      );
    case 'TEXTAREA':
      return (
        <div className="field">
          <label htmlFor={id}>
            <Label field={field} />
          </label>
          {errorEl}
          <textarea
            id={id}
            rows={3}
            maxLength={maxLength}
            autoComplete="off"
            value={typeof value === 'string' ? value : ''}
            onChange={(e) => onChange(e.target.value)}
            {...aria}
          />
        </div>
      );
    case 'DATE':
      return (
        <div className="field">
          <label htmlFor={id}>
            <Label field={field} />
          </label>
          {errorEl}
          <input id={id} type="date" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} {...aria} />
        </div>
      );
    default:
      return (
        <div className="field">
          <label htmlFor={id}>
            <Label field={field} />
          </label>
          {errorEl}
          <input
            id={id}
            type="text"
            maxLength={maxLength}
            autoComplete="off"
            value={typeof value === 'string' ? value : ''}
            onChange={(e) => onChange(e.target.value)}
            {...aria}
          />
        </div>
      );
  }
}
