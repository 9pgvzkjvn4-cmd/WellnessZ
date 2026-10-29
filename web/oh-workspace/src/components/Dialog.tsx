import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';

const FOCUSABLE = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

/**
 * Modal dialog (role="dialog", aria-modal): focuses the first control on open, keeps Tab inside, closes on Escape and
 * returns focus to the element that opened it.
 */
export function Dialog({ title, onClose, children, describedBy }: { title: string; onClose: () => void; children: ReactNode; describedBy?: string }) {
  const titleId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector('.dialog-body')?.querySelector<HTMLElement>(FOCUSABLE) ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? ref.current)?.focus();
    return () => opener?.focus?.();
  }, []);

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.stopPropagation();
      onCloseRef.current();
      return;
    }
    if (e.key !== 'Tab' || !ref.current) return;
    const items = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    const first = items[0];
    const last = items[items.length - 1];
    if (!first || !last) return;
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={describedBy} ref={ref} tabIndex={-1} onKeyDown={onKeyDown}>
        <div className="dialog-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="link-button" onClick={onClose} aria-label="Close dialog">
            ✕
          </button>
        </div>
        <div className="dialog-body">{children}</div>
      </div>
    </div>
  );
}
