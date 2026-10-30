import type { ReactNode } from 'react';

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
      <label htmlFor={p.id}>
        {p.label}
        {p.required && <span className="req" aria-hidden="true"> *</span>}
      </label>
      <p id={`${p.id}-hint`} className="muted small">
        {p.hint} {p.max - p.value.length} characters left.
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
  type?: 'text' | 'date' | 'number' | 'email' | 'datetime-local';
  required?: boolean;
  hint?: ReactNode;
  error?: string;
  inputMode?: 'numeric';
  min?: number | string;
  max?: number | string;
  maxLength?: number;
  autoComplete?: string;
}) {
  return (
    <div className={p.error ? 'field field-invalid' : 'field'}>
      <label htmlFor={p.id}>
        {p.label}
        {p.required && <span className="req" aria-hidden="true"> *</span>}
      </label>
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
        autoComplete={p.autoComplete}
        value={p.value}
        aria-required={p.required ? true : undefined}
        aria-invalid={p.error ? true : undefined}
        aria-describedby={describedBy(p.id, !!p.hint, p.error)}
        onChange={(e) => p.onChange(e.target.value)}
      />
    </div>
  );
}
