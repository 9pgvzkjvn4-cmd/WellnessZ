import type { CalibrationState, ClinicSite } from '../../api/scheduling';
import { calibrationState, calibrationView } from '../../domain/scheduling';
import { Field } from '../../components/ui';
import './scheduling.css';

/** Calibration state badge – colour + shape symbol + text (never colour alone). */
export function CalibrationBadge({ state, dueOn, today }: { state?: CalibrationState | null; dueOn?: string | null; today: string }) {
  const v = calibrationView(calibrationState(dueOn, today, state));
  return (
    <span className={`cal-badge cal-${v.tone}`} title={dueOn ? `Calibration due ${dueOn}` : undefined}>
      <span aria-hidden="true">{v.symbol}</span>
      {v.label}
    </span>
  );
}

/** Clinic site selector. */
export function SiteSelect({ sites, value, onChange, label = 'Site', allowAll }: { sites: ClinicSite[] | undefined; value: string; onChange: (code: string) => void; label?: string; allowAll?: boolean }) {
  return (
    <Field label={label}>
      {(id) => (
        <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
          {allowAll && <option value="">All sites</option>}
          {!allowAll && !value && <option value="">Choose a site…</option>}
          {(sites ?? []).map((s) => (
            <option key={s.code} value={s.code}>
              {s.name} ({s.code})
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

/** Simple in-page tabs (buttons with aria-selected) for sub-views that do not need their own route. */
export function LocalTabs<T extends string>({ tabs, current, onChange, label }: { tabs: readonly { id: T; label: string }[]; current: T; onChange: (t: T) => void; label: string }) {
  return (
    <div className="local-tabs" role="group" aria-label={label}>
      {tabs.map((t) => (
        <button key={t.id} type="button" aria-pressed={t.id === current} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}
