import { useEffect, useState } from 'react';
import { lotInput } from '../../domain/surveillance';

/** Current time, re-rendered every `intervalMs` (live countdowns). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(t);
  }, [intervalMs]);
  return now;
}

/** Lot body for the E07-style administration (`lotBarcode` when scanned, else `lotNumber`); undefined when invalid. */
export function lotBody(raw: string): { lotNumber?: string; lotBarcode?: string } | undefined {
  const p = lotInput(raw);
  if (p.error) return undefined;
  return p.lotBarcode ? { lotBarcode: p.lotBarcode } : { lotNumber: p.lotNumber };
}

export const yesNo = (v: boolean | null | undefined): string => (v === true ? 'Yes' : v === false ? 'No' : '–');
