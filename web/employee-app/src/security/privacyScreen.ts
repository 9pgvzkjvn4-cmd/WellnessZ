/**
 * Best-effort privacy screen for web/PWA: obscure the UI when the page is hidden
 * so OS task-switcher snapshots are less likely to show health information.
 *
 * On native builds this is NOT sufficient (snapshots may be taken before the
 * WebView repaints). Install a native privacy-screen plugin, e.g.
 * `@capacitor-community/privacy-screen` (FLAG_SECURE on Android, snapshot
 * overlay on iOS) – see README "Privacy screen". Intune App Protection policies
 * can additionally block screenshots.
 */
export function installPrivacyScreen(doc: Document = document): () => void {
  const update = () => {
    doc.body.classList.toggle('wz-obscured', doc.visibilityState === 'hidden');
  };
  doc.addEventListener('visibilitychange', update);
  window.addEventListener('pagehide', update);
  return () => {
    doc.removeEventListener('visibilitychange', update);
    window.removeEventListener('pagehide', update);
  };
}
