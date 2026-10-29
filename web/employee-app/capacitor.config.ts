import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor 8 configuration for the WellnessZ native (iOS/Android) app.
 * Native projects are NOT committed by default – generate them with
 *   npx cap add ios && npx cap add android
 * See README.md ("Native builds").
 */
const config: CapacitorConfig = {
  appId: 'com.wellnessz.app',
  appName: 'WellnessZ',
  webDir: 'dist',
  // Never load remote content into the WebView; the app shell is bundled.
  server: {
    androidScheme: 'https',
    cleartext: false,
  },
  ios: {
    contentInset: 'automatic',
    // Disallow link previews / 3D-touch peeking of PHI screens.
    allowsLinkPreview: false,
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    // CapacitorHttp stays disabled: API calls use window.fetch + CORS so the
    // same AuthClient code path is exercised on web and native.
    CapacitorHttp: { enabled: false },
  },
};

export default config;
