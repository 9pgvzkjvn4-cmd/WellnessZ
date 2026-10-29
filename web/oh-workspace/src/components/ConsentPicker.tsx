import type { ActiveConsent } from '../api/treatment';
import { formatDate, humanise } from './format';
import { Alert, ErrorAlert, Loading } from './ui';
import type { AsyncState } from './useAsync';

export const NO_ACTIVE_CONSENT = 'No active consent – ask the employee to grant it in the employee app';

/**
 * Radio list of the employee's active disclosure consents (purpose + granted date). There is deliberately no free-text
 * fallback: without an active consent the user is told to have the employee grant it in the employee app.
 * `id` goes on the fieldset (focusable) so an error-summary link can move focus to the picker.
 */
export function ConsentPicker({ id, legend, consents, value, onChange, error, hint }: { id: string; legend: string; consents: AsyncState<ActiveConsent[]>; value: string; onChange: (consentId: string) => void; error?: string; hint?: string }) {
  const list = consents.data ?? [];
  const stale = !!value && !!consents.data && !list.some((c) => c.id === value);
  const describedBy = [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined;
  return (
    <fieldset id={id} tabIndex={-1} className={`consent-picker${error ? ' field-error' : ''}`} aria-describedby={describedBy} aria-invalid={error ? true : undefined}>
      <legend>
        {legend}
        <span aria-hidden="true"> *</span>
      </legend>
      {error && (
        <span id={`${id}-error`} className="field-error-text small">
          <span className="visually-hidden">Error: </span>
          {error}
        </span>
      )}
      {hint && <span id={`${id}-hint`} className="hint small">{hint}</span>}
      {consents.loading && !consents.data && <Loading label="Loading consents…" />}
      {!!consents.error && <ErrorAlert error={consents.error} />}
      {consents.data && list.length === 0 && <Alert kind="warning">{NO_ACTIVE_CONSENT}</Alert>}
      {stale && <p className="small muted">The consent recorded earlier is no longer active – choose one of the active consents.</p>}
      {list.map((c) => (
        <label key={c.id} className="inline-row">
          <input type="radio" name={`${id}-radio`} value={c.id} checked={value === c.id} onChange={() => onChange(c.id)} /> {humanise(c.purpose)}{' '}
          <span className="small muted">
            granted {formatDate(c.grantedAt)}
            {c.scope ? ` · ${c.scope}` : ''}
          </span>
        </label>
      ))}
    </fieldset>
  );
}
