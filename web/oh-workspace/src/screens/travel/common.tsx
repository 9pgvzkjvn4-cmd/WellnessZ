import type { KeyboardEvent, Ref } from 'react';
import { formatDay } from '../../components/format';
import { lotInput } from '../../domain/surveillance';

/**
 * Lot input shared by the travel consultation and the campaign fast entry (same pattern as the E07 immunisation fast
 * entry): keyboard-wedge scanners type the GS1 string then Enter – the parsed lot / expiry is shown live.
 */
export function LotField({ id, label = 'Lot – scan the vial barcode or type the lot number', value, onChange, inputRef, onEnter, error }: {
  id: string;
  label?: string;
  value: string;
  onChange: (v: string) => void;
  inputRef?: Ref<HTMLInputElement>;
  onEnter?: () => void;
  error?: string;
}) {
  const parsed = value.trim() ? lotInput(value) : undefined;
  const problem = parsed?.error ?? error;
  return (
    <div className={`field${problem ? ' field-error' : ''}`}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        ref={inputRef}
        autoComplete="off"
        spellCheck={false}
        maxLength={80}
        aria-describedby={`${id}-status`}
        aria-invalid={!!problem}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e: KeyboardEvent<HTMLInputElement>) => {
          if (e.key === 'Enter' && onEnter) {
            e.preventDefault();
            onEnter();
          }
        }}
      />
      <span id={`${id}-status`} className={`small ${problem ? 'field-error-text' : 'hint'}`} aria-live="polite">
        {problem ?? (parsed?.lotNumber ? `Lot ${parsed.lotNumber}${parsed.expiry ? ` · expires ${formatDay(parsed.expiry)}` : ''}` : 'GS1 “(17)YYMMDD(10)LOT” or plain lot number')}
      </span>
    </div>
  );
}
