import { useCallback, useEffect, useState } from 'react';
import { SessionExpiredError } from '../auth/AuthClient';

export interface AsyncState<T> {
  data?: T;
  error?: unknown;
  loading: boolean;
  reload: () => void;
}

/**
 * Run `fn` on mount, whenever `deps` change and on `reload()`. A SessionExpiredError is forwarded to
 * `onSessionExpired` so the shell can return to the sign-in screen. Stale data stays visible while reloading.
 */
export function useAsync<T>(fn: () => Promise<T>, onSessionExpired: () => void, deps: readonly unknown[] = []): AsyncState<T> {
  const [tick, setTick] = useState(0);
  const [result, setResult] = useState<{ key: string; data?: T; error?: unknown }>();
  const reload = useCallback(() => setTick((t) => t + 1), []);
  const key = `${tick}|${JSON.stringify(deps)}`;

  useEffect(() => {
    let cancelled = false;
    fn().then(
      (data) => {
        if (!cancelled) setResult({ key, data });
      },
      (error: unknown) => {
        if (cancelled) return;
        if (error instanceof SessionExpiredError) onSessionExpired();
        setResult({ key, error });
      },
    );
    return () => {
      cancelled = true;
    };
    // `fn` is captured per load on purpose; reloads are driven by `deps` / reload().
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const loading = result?.key !== key;
  return { data: result?.data, error: loading ? undefined : result?.error, loading, reload };
}

export interface ActionState {
  busy: boolean;
  error?: unknown;
  message?: string;
  run: (fn: () => Promise<string | void>) => Promise<boolean>;
  reset: () => void;
}

/** One mutating action at a time with error / success message state. */
export function useAction(onSessionExpired: () => void): ActionState {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [message, setMessage] = useState<string>();
  const run = useCallback(
    async (fn: () => Promise<string | void>) => {
      setBusy(true);
      setError(undefined);
      setMessage(undefined);
      try {
        const msg = await fn();
        if (msg) setMessage(msg);
        return true;
      } catch (e) {
        if (e instanceof SessionExpiredError) onSessionExpired();
        setError(e);
        return false;
      } finally {
        setBusy(false);
      }
    },
    [onSessionExpired],
  );
  const reset = useCallback(() => {
    setError(undefined);
    setMessage(undefined);
  }, []);
  return { busy, error, message, run, reset };
}
