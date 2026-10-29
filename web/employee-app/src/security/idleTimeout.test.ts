import { describe, expect, it, vi } from 'vitest';
import { IDLE_TIMEOUT_MS, startIdleTimer, type IdleTimerDeps } from './idleTimeout';

function fakeDeps() {
  let now = 0;
  let tick: (() => void) | undefined;
  const target = new EventTarget();
  const deps: IdleTimerDeps = {
    now: () => now,
    setInterval: (fn) => {
      tick = fn;
      return 1;
    },
    clearInterval: () => {
      tick = undefined;
    },
    target,
  };
  return { deps, target, advance: (ms: number) => { now += ms; tick?.(); } };
}

describe('idle timeout', () => {
  it('defaults to 15 minutes', () => {
    expect(IDLE_TIMEOUT_MS).toBe(900_000);
  });

  it('fires once after the timeout without activity', () => {
    const { deps, advance } = fakeDeps();
    const onIdle = vi.fn();
    startIdleTimer(onIdle, 1000, deps);
    advance(999);
    expect(onIdle).not.toHaveBeenCalled();
    advance(1);
    advance(5000);
    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('activity resets the timer', () => {
    const { deps, advance, target } = fakeDeps();
    const onIdle = vi.fn();
    startIdleTimer(onIdle, 1000, deps);
    advance(900);
    target.dispatchEvent(new Event('keydown'));
    advance(900);
    expect(onIdle).not.toHaveBeenCalled();
    advance(100);
    expect(onIdle).toHaveBeenCalledTimes(1);
  });
});
