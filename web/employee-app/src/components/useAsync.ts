import { useCallback, useEffect, useState } from 'react';
import { SessionExpiredError } from '../auth/AuthClient';

export interface AsyncState<T> {
  data?: T;
  error?: unknown;
  loading: boolean;
  reload: () => void;
}

/**
 * Run `fn` on mount and on `reload()`. A SessionExpiredError is forwarded to
 * `onSessionExpired` so the shell can return to the sign-in screen.
 * (`fn` is intentionally captured once per load, not tracked as a dependency.)
 */
export function useAsync<T>(fn: () => Promise<T>, onSessionExpired: () => void): AsyncState<T> {
  const [tick, setTick] = useState(0);
  const [result, setResult] = useState<{ tick: number; data?: T; error?: unknown }>();
  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    fn().then(
      (data) => {
        if (!cancelled) setResult({ tick, data });
      },
      (error: unknown) => {
        if (cancelled) return;
        if (error instanceof SessionExpiredError) onSessionExpired();
        setResult({ tick, error });
      },
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick]);

  const loading = result?.tick !== tick;
  return { data: result?.data, error: loading ? undefined : result?.error, loading, reload };
}
