import { useEffect, useRef, type ReactNode } from 'react';
import { humanise } from './format';

/** Page heading that receives focus on route change (screen-reader friendly). */
export function PageTitle({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1}>
      {children}
    </h1>
  );
}

export function Alert({ kind, children }: { kind: 'error' | 'info' | 'success'; children: ReactNode }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <p className="muted" role="status" aria-live="polite">
      {label}
    </p>
  );
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return <span className={`badge badge-${status.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>{label ?? humanise(status)}</span>;
}


/** Confidentiality hint shown at the top of every partner screen. */
export function ConfidentialityNote({ children }: { children: ReactNode }) {
  return (
    <aside className="banner banner-confidential" aria-label="Confidentiality">
      <strong>Confidential</strong>
      <span className="small">{children}</span>
    </aside>
  );
}
