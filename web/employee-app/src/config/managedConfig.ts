import { Capacitor, registerPlugin } from '@capacitor/core';
import { errorName, log } from '../security/log';

/**
 * MDM Managed App Configuration.
 *
 * Keys (identical across Intune App Configuration policies, Workspace ONE UEM
 * "Application Configuration" and Jamf; iOS: com.apple.configuration.managed,
 * Android: managed configurations / app restrictions):
 */
export const MANAGED_CONFIG_KEYS = [
  'wz.apiBaseUrl',
  'wz.oidcIssuer',
  'wz.oidcClientId',
  'wz.redirectUri',
  'wz.requireBiometric',
] as const;

export type ManagedConfigKey = (typeof MANAGED_CONFIG_KEYS)[number];
export type ManagedValues = Partial<Record<ManagedConfigKey, string | boolean>>;

/**
 * Abstraction over the platform's managed-configuration store.
 * Implementations must return only the keys that are actually set by MDM.
 */
export interface ManagedConfig {
  readonly name: string;
  read(keys: readonly ManagedConfigKey[]): Promise<ManagedValues>;
}

/** No MDM (browser / unmanaged device). */
export const noManagedConfig: ManagedConfig = {
  name: 'none',
  read: async () => ({}),
};

/**
 * Plugin hook – native side.
 *
 * Expected native plugin contract (compatible with
 * `@capawesome/capacitor-managed-configurations`):
 *   ManagedConfigurations.getString({ key }) -> { value: string | null }
 *   ManagedConfigurations.getBoolean({ key }) -> { value: boolean | null }
 * Install the plugin (`npm i @capawesome/capacitor-managed-configurations && npx cap sync`)
 * and this adapter picks it up automatically. Any other MDM SDK (Intune MAM SDK,
 * Workspace ONE SDK) can be wired by calling `registerManagedConfig()` at startup.
 */
interface ManagedConfigurationsPlugin {
  getString(opts: { key: string }): Promise<{ value: string | null }>;
  getBoolean(opts: { key: string }): Promise<{ value: boolean | null }>;
}

const BOOLEAN_KEYS: ReadonlySet<ManagedConfigKey> = new Set(['wz.requireBiometric']);

export function capacitorManagedConfig(): ManagedConfig {
  const plugin = registerPlugin<ManagedConfigurationsPlugin>('ManagedConfigurations');
  return {
    name: 'capacitor-managed-configurations',
    async read(keys) {
      const out: ManagedValues = {};
      for (const key of keys) {
        try {
          const { value } = BOOLEAN_KEYS.has(key)
            ? await plugin.getBoolean({ key })
            : await plugin.getString({ key });
          if (value !== null && value !== undefined && value !== '') out[key] = value;
        } catch (e) {
          log.warn('managed-config.read-failed', { errorName: errorName(e) });
        }
      }
      return out;
    },
  };
}

let registered: ManagedConfig | undefined;

/** Register a custom MDM provider (call before `loadRuntimeConfig`). */
export function registerManagedConfig(provider: ManagedConfig): void {
  registered = provider;
}

export function resolveManagedConfig(): ManagedConfig {
  if (registered) return registered;
  if (Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('ManagedConfigurations')) {
    return capacitorManagedConfig();
  }
  return noManagedConfig;
}
