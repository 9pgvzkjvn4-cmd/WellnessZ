import type { ReactNode } from 'react';
import { restrictionText, type FunctionalRestriction } from '../api/fitness';

/** Functional restrictions (catalogue wording, no diagnosis). */
export function RestrictionList({ items, empty = 'None' }: { items: readonly FunctionalRestriction[]; empty?: string }) {
  if (items.length === 0) return <span className="muted">{empty}</span>;
  return (
    <ul className="plain-list">
      {items.map((r, i) => (
        <li key={`${r.code ?? r.label}-${i}`}>{restrictionText(r)}</li>
      ))}
    </ul>
  );
}

/** Visible note on every increment-2 screen (E03-HLR-08: fitness / restriction outcomes only). */
export function NoClinicalInfoNote({ children }: { children?: ReactNode }) {
  return (
    <div className="banner" role="note">
      <strong>No clinical information is shown</strong>
      <span className="small">
        {children ??
          'You see fitness status and functional restrictions only. Diagnoses and medical details stay with Occupational Health.'}
      </span>
    </div>
  );
}
