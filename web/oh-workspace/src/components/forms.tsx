import { useEffect, useRef, type ReactNode } from 'react';
import type { FieldError } from '../domain/devices';

export type { FieldError };

/**
 * GOV.UK-style error summary: shown above a form after a failed client-side check, focused so screen readers announce
 * it, each entry links to (and focuses) the field. Links do not change the hash route.
 */
export function ErrorSummary({ errors, title = 'There is a problem' }: { errors: readonly FieldError[]; title?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const key = errors.map((e) => `${e.field}:${e.message}`).join('|');
  useEffect(() => {
    if (key) ref.current?.focus();
  }, [key]);
  if (errors.length === 0) return null;
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={ref} aria-labelledby="error-summary-title">
      <h2 id="error-summary-title" className="error-summary-title">{title}</h2>
      <ul>
        {errors.map((e) => (
          <li key={`${e.field}-${e.message}`}>
            <a
              href={`#${e.field}`}
              onClick={(ev) => {
                ev.preventDefault();
                document.getElementById(e.field)?.focus();
              }}
            >
              {e.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** First error message for a field id. */
export const errorFor = (errors: readonly FieldError[], field: string): string | undefined => errors.find((e) => e.field === field)?.message;

/**
 * Field with a caller-chosen id (so the error summary can link to it). `children(describedBy)` renders the control;
 * pass `aria-describedby={describedBy}` and `aria-invalid={!!error}` on it.
 */
export function IdField({ id, label, required, hint, error, children }: { id: string; label: ReactNode; required?: boolean; hint?: string; error?: string; children: (describedBy: string | undefined) => ReactNode }) {
  const describedBy = [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`field${error ? ' field-error' : ''}`}>
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {error && (
        <span id={`${id}-error`} className="field-error-text small">
          <span className="visually-hidden">Error: </span>
          {error}
        </span>
      )}
      {children(describedBy)}
      {hint && (
        <span id={`${id}-hint`} className="hint small">
          {hint}
        </span>
      )}
    </div>
  );
}
