import { useSyncExternalStore } from 'react';

function subscribe(cb: () => void) {
  window.addEventListener('hashchange', cb);
  return () => window.removeEventListener('hashchange', cb);
}

/** Current hash path, e.g. `#/consents` → `/consents`. Empty → `/`. */
export function parseHash(hash: string): string {
  const path = hash.replace(/^#/, '').split('?')[0] ?? '';
  return path.startsWith('/') ? path : `/${path}`;
}

export function useHashRoute(): string {
  return useSyncExternalStore(subscribe, () => parseHash(window.location.hash), () => '/');
}

export function navigate(path: string): void {
  window.location.hash = path;
}
