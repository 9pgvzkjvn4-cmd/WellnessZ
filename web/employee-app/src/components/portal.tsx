import type { ReactNode } from 'react';
import { telHref, type Contact } from '../api/portal';
import { dirFor } from '../i18n/strings';

/** Screen wrapper for the increment-2 portal screens: sets `lang` and the text direction (Arabic → `dir="rtl"`). */
export function PortalSection({ lang, className, children }: { lang: string; className?: string; children: ReactNode }) {
  return (
    <section className={className ? `portal ${className}` : 'portal'} dir={dirFor(lang)} lang={lang}>
      {children}
    </section>
  );
}

export function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="empty-state">
      <p>
        <strong>{title}</strong>
      </p>
      <p className="muted">{hint}</p>
    </div>
  );
}

/** Name, e-mail and phone as links. Values are rendered as text, never as HTML. */
export function ContactLines({ contact, callLabel, emailLabel }: { contact: Contact; callLabel: string; emailLabel: string }) {
  return (
    <span className="contact-lines">
      {contact.name && <span>{contact.name}</span>}
      {contact.phone && (
        <a href={telHref(contact.phone)} aria-label={`${callLabel} ${contact.phone}`}>
          <bdi>{contact.phone}</bdi>
        </a>
      )}
      {contact.email && (
        <a href={`mailto:${contact.email}`} aria-label={`${emailLabel} ${contact.email}`}>
          <bdi>{contact.email}</bdi>
        </a>
      )}
    </span>
  );
}

/**
 * Inline confirmation step (instead of `window.confirm`, which is blocked or unstyled in some WebViews).
 * The confirm button takes focus so keyboard and screen-reader users land on the decision.
 */
export function ConfirmPanel({
  id,
  title,
  children,
  confirmLabel,
  cancelLabel,
  busy,
  busyLabel,
  danger,
  onConfirm,
  onCancel,
}: {
  id: string;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  busy: boolean;
  busyLabel: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="confirm-panel" role="alertdialog" aria-modal="false" aria-labelledby={`${id}-title`} aria-describedby={`${id}-body`}>
      <h3 id={`${id}-title`} className="card-title">
        {title}
      </h3>
      <div id={`${id}-body`}>{children}</div>
      <div className="actions">
        <button type="button" className={danger ? 'danger' : undefined} disabled={busy} onClick={onConfirm} autoFocus>
          {busy ? busyLabel : confirmLabel}
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
          {cancelLabel}
        </button>
      </div>
    </div>
  );
}
