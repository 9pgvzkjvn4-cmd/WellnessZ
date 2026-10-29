import { Capacitor } from '@capacitor/core';
import type { RuntimeConfig } from '../config/runtimeConfig';
import type { AuthClient } from './AuthClient';
import { BffAuthClient } from './bffAuthClient';
import { NativeOidcAuthClient } from './nativeOidcAuthClient';

/** Native app → OIDC + PKCE; any browser (incl. installed PWA) → BFF session. */
export function createAuthClient(cfg: RuntimeConfig): AuthClient {
  return Capacitor.isNativePlatform() ? new NativeOidcAuthClient(cfg) : new BffAuthClient();
}
