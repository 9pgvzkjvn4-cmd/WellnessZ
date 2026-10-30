import { useEffect, useState } from 'react';

/** Current time, re-rendered every `intervalMs` (countdowns: call-back-by, offer SLA, collection windows). */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(t);
  }, [intervalMs]);
  return now;
}
