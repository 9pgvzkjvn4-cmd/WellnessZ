import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Capacitor } from '@capacitor/core';
import { App } from './App';
import { createAuthClient } from './auth/createAuthClient';
import { loadRuntimeConfig } from './config/runtimeConfig';
import { registerServiceWorker } from './pwa/registerServiceWorker';
import { installPrivacyScreen } from './security/privacyScreen';
import './styles.css';

/*
 * Native plugin hooks – register production implementations here, BEFORE the
 * config is loaded, e.g.:
 *   registerSecureStorage(keychainKeystoreAdapter);          // ./auth/secureStorage
 *   registerBiometricVerifier(biometricAuthAdapter);         // ./auth/biometric
 *   registerManagedConfig(intuneOrWorkspaceOneAdapter);      // ./config/managedConfig
 * See README.md → "Native builds" and "MDM".
 */

async function bootstrap() {
  installPrivacyScreen();
  const config = await loadRuntimeConfig();
  const client = createAuthClient(config);
  if (!Capacitor.isNativePlatform()) registerServiceWorker();

  const root = document.getElementById('root');
  if (!root) throw new Error('root element missing');
  createRoot(root).render(
    <StrictMode>
      <App client={client} config={config} />
    </StrictMode>,
  );
}

void bootstrap();
