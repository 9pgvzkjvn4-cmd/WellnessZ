import { useEffect, useId, useRef, type ReactNode } from 'react';
import { errorInfo, humanise } from './format';

/** Page heading that receives focus on route change (screen-reader friendly). */
export function PageTitle({ children, sub }: { children: ReactNode; sub?: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return (
    <div className="page-title">
      <h1 ref={ref} tabIndex={-1}>
        {children}
      </h1>
      {sub && <p className="lead">{sub}</p>}
    </div>
  );
}

export function Alert({ kind, children }: { kind: 'error' | 'info' | 'success' | 'warning'; children: ReactNode }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

/** Problem-detail aware error: shows `detail` and the correlationId (for the service desk). */
export function ErrorAlert({ error }: { error: unknown }) {
  if (error === undefined || error === null) return null;
  const i = errorInfo(error);
  return (
    <Alert kind="error">
      <span>{i.message}</span>
      {(i.correlationId || i.code) && (
        <span className="small problem-meta">
          {i.code && <>Code: <span className="mono">{i.code}</span> </>}
          {i.correlationId && <>Reference: <span className="mono">{i.correlationId}</span></>}
        </span>
      )}
    </Alert>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <p className="muted" role="status" aria-live="polite">
      {label}
    </p>
  );
}

export function ActionFeedback({ error, message }: { error?: unknown; message?: string }) {
  return (
    <div aria-live="polite">
      {error !== undefined && <ErrorAlert error={error} />}
      {message && <Alert kind="success">{message}</Alert>}
    </div>
  );
}

export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="muted">–</span>;
  return <span className={`badge badge-${status.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>{humanise(status)}</span>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="muted empty">{children}</p>;
}

/** Access restricted inline panel (UI gating is convenience only – the server enforces). */
export function Restricted({ what = 'this screen' }: { what?: string }) {
  return (
    <section className="restricted">
      <h1>Access restricted</h1>
      <p>Your roles do not include access to {what}. If you need it, ask your Nursing Head or OH administrator.</p>
    </section>
  );
}

export interface TabDef {
  id: string;
  label: string;
  hidden?: boolean;
}

/** Link-based tabs (hash routes) – each tab is a real link so it can be bookmarked. */
export function TabNav({ tabs, current, hrefFor, label }: { tabs: TabDef[]; current: string; hrefFor: (id: string) => string; label: string }) {
  return (
    <nav className="tabs" aria-label={label}>
      <ul>
        {tabs
          .filter((t) => !t.hidden)
          .map((t) => (
            <li key={t.id}>
              <a href={hrefFor(t.id)} aria-current={t.id === current ? 'page' : undefined}>
                {t.label}
              </a>
            </li>
          ))}
      </ul>
    </nav>
  );
}

/** Labelled form field wrapper. */
export function Field({ label, children, hint, required }: { label: string; children: (id: string) => ReactNode; hint?: string; required?: boolean }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {children(id)}
      {hint && <span className="hint small">{hint}</span>}
    </div>
  );
}

export function SelectOptions({ values, empty }: { values: readonly string[]; empty?: string }) {
  return (
    <>
      {empty !== undefined && <option value="">{empty}</option>}
      {values.map((v) => (
        <option key={v} value={v}>
          {humanise(v)}
        </option>
      ))}
    </>
  );
}

export function Dl({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="details">
      {items.map(([k, v]) => (
        <div key={k} className="details-row">
          <dt>{k}</dt>
          <dd>{v === undefined || v === null || v === '' ? '–' : v}</dd>
        </div>
      ))}
    </dl>
  );
}
