import type { ReactNode } from 'react';
import type { Emergency } from '../api/support';
import { telHref } from '../api/portal';
import type { WellbeingStrings } from '../i18n/wellbeing';

/**
 * Emergency guidance, always rendered first on the support screens – also while data are loading or failed to load.
 * The server's emergency text / number (signposting) is shown when available; the generic guidance is always there.
 */
export function EmergencyBanner({ t, emergency }: { t: WellbeingStrings; emergency?: Emergency }) {
  return (
    <aside className="emergency-banner" role="note" aria-labelledby="emergency-title">
      <h2 id="emergency-title" className="card-title">
        {t.emTitle}
      </h2>
      <p>
        <strong>{t.emDanger}</strong>
      </p>
      {emergency?.text && <p>{emergency.text}</p>}
      {emergency?.phone ? (
        <a className="button emergency-call large-touch" href={telHref(emergency.phone)}>
          {t.emCall(emergency.phone)}
        </a>
      ) : (
        <p>{t.emLocal}</p>
      )}
      <p className="muted small">{t.emNotMonitored}</p>
    </aside>
  );
}

/** Label / value list entry rendered only when there is a value. */
export function Fact({ label, children }: { label: string; children?: ReactNode }) {
  if (children === undefined || children === null || children === '') return null;
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}
