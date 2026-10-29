/** Default idle timeout for authenticated sessions (15 minutes). */
export const IDLE_TIMEOUT_MS = 15 * 60 * 1000;

const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;

export interface IdleTimerDeps {
  now: () => number;
  setInterval: (fn: () => void, ms: number) => unknown;
  clearInterval: (handle: unknown) => void;
  target: Pick<EventTarget, 'addEventListener' | 'removeEventListener'>;
}

const browserDeps = (): IdleTimerDeps => ({
  now: () => Date.now(),
  setInterval: (fn, ms) => window.setInterval(fn, ms),
  clearInterval: (h) => window.clearInterval(h as number),
  target: window,
});

/**
 * Calls `onIdle` once when no user activity has been seen for `timeoutMs`.
 * Uses wall-clock comparison (not a single long setTimeout) so that time spent
 * with the app backgrounded / device asleep also counts as idle.
 * Returns a disposer.
 */
export function startIdleTimer(
  onIdle: () => void,
  timeoutMs: number = IDLE_TIMEOUT_MS,
  deps: IdleTimerDeps = browserDeps(),
): () => void {
  let last = deps.now();
  let fired = false;
  const touch = () => {
    last = deps.now();
  };
  const check = () => {
    if (!fired && deps.now() - last >= timeoutMs) {
      fired = true;
      dispose();
      onIdle();
    }
  };
  ACTIVITY_EVENTS.forEach((e) => deps.target.addEventListener(e, touch, { passive: true }));
  deps.target.addEventListener('visibilitychange', check);
  deps.target.addEventListener('focus', check);
  const handle = deps.setInterval(check, Math.min(30_000, Math.max(1_000, timeoutMs / 10)));
  function dispose() {
    ACTIVITY_EVENTS.forEach((e) => deps.target.removeEventListener(e, touch));
    deps.target.removeEventListener('visibilitychange', check);
    deps.target.removeEventListener('focus', check);
    deps.clearInterval(handle);
  }
  return dispose;
}
