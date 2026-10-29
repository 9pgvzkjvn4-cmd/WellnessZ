import type { ReactNode } from 'react';
import { S } from '../strings';

/** Hook-free form controls with consistent error / hint wiring (aria-invalid + aria-describedby). */

export function FieldError({ id, error }: { id: string; error?: string }) {
  return error ? (
    <p id={`${id}-error`} className="field-error">
      {error}
    </p>
  ) : null;
}

const describedBy = (id: string, hint: boolean, error?: string) =>
  [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined;

function Label({ id, label, required }: { id: string; label: string; required?: boolean }) {
  return (
    <label htmlFor={id}>
      {label}
      {required && (
        <span className="req" aria-hidden="true">
          {' '}
          *
        </span>
      )}
    </label>
  );
}

export function TextAreaField(p: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  max: number;
  required?: boolean;
  hint?: ReactNode;
  error?: string;
  rows?: number;
}) {
  return (
    <div className={p.error ? 'field field-invalid' : 'field'}>
      <Label id={p.id} label={p.label} required={p.required} />
      <p id={`${p.id}-hint`} className="muted small">
        {p.hint} {S.form.charsLeft(p.max - p.value.length)}
      </p>
      <FieldError id={p.id} error={p.error} />
      <textarea
        id={p.id}
        rows={p.rows ?? 3}
        maxLength={p.max}
        autoComplete="off"
        value={p.value}
        aria-required={p.required ? true : undefined}
        aria-invalid={p.error ? true : undefined}
        aria-describedby={describedBy(p.id, true, p.error)}
        onChange={(e) => p.onChange(e.target.value)}
      />
    </div>
  );
}

export function InputField(p: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: 'text' | 'date' | 'datetime-local';
  required?: boolean;
  hint?: ReactNode;
  error?: string;
  inputMode?: 'decimal' | 'numeric';
  min?: string;
  max?: string;
  maxLength?: number;
}) {
  return (
    <div className={p.error ? 'field field-invalid' : 'field'}>
      <Label id={p.id} label={p.label} required={p.required} />
      {p.hint && (
        <p id={`${p.id}-hint`} className="muted small">
          {p.hint}
        </p>
      )}
      <FieldError id={p.id} error={p.error} />
      <input
        id={p.id}
        type={p.type ?? 'text'}
        inputMode={p.inputMode}
        min={p.min}
        max={p.max}
        maxLength={p.maxLength}
        autoComplete="off"
        value={p.value}
        aria-required={p.required ? true : undefined}
        aria-invalid={p.error ? true : undefined}
        aria-describedby={describedBy(p.id, !!p.hint, p.error)}
        onChange={(e) => p.onChange(e.target.value)}
      />
    </div>
  );
}

export function SelectField(p: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: readonly { value: string; label: string }[];
  required?: boolean;
  error?: string;
}) {
  return (
    <div className={p.error ? 'field field-invalid' : 'field'}>
      <Label id={p.id} label={p.label} required={p.required} />
      <FieldError id={p.id} error={p.error} />
      <select
        id={p.id}
        value={p.value}
        aria-required={p.required ? true : undefined}
        aria-invalid={p.error ? true : undefined}
        aria-describedby={describedBy(p.id, false, p.error)}
        onChange={(e) => p.onChange(e.target.value)}
      >
        {p.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/** File picker; reports the chosen `File` (or undefined when cleared). */
export function FileField(p: {
  id: string;
  label: string;
  accept: string;
  onChange: (f: File | undefined) => void;
  required?: boolean;
  hint?: ReactNode;
  error?: string;
}) {
  return (
    <div className={p.error ? 'field field-invalid' : 'field'}>
      <Label id={p.id} label={p.label} required={p.required} />
      {p.hint && (
        <p id={`${p.id}-hint`} className="muted small">
          {p.hint}
        </p>
      )}
      <FieldError id={p.id} error={p.error} />
      <input
        id={p.id}
        type="file"
        accept={p.accept}
        aria-required={p.required ? true : undefined}
        aria-invalid={p.error ? true : undefined}
        aria-describedby={describedBy(p.id, !!p.hint, p.error)}
        onChange={(e) => p.onChange(e.target.files?.[0] ?? undefined)}
      />
    </div>
  );
}
