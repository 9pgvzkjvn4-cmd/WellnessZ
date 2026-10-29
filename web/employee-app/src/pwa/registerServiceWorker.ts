import { errorName, log } from '../security/log';

/** Register the app-shell service worker (production builds only). */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return;
  const register = () =>
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch((e: unknown) => {
      log.warn('pwa.sw-register-failed', { errorName: errorName(e) });
    });
  if (document.readyState === 'complete') void register();
  else window.addEventListener('load', () => void register(), { once: true });
}
