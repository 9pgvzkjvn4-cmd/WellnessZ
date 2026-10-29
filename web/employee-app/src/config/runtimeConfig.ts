import { MANAGED_CONFIG_KEYS, resolveManagedConfig, type ManagedConfig, type ManagedValues } from './managedConfig';
import { errorName, log } from '../security/log';

export interface RuntimeConfig {
  /** Core API base URL incl. `/api/v1` – used by the native (token) auth mode only. */
  apiBaseUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
  redirectUri: string;
  requireBiometric: boolean;
  /** Which source provided values, for the Profile/About screen. */
  source: { managed: string; file: boolean };
}

export const DEFAULT_CONFIG: Omit<RuntimeConfig, 'source'> = {
  apiBaseUrl: 'http://localhost:8080/api/v1',
  oidcIssuer: 'http://localhost:8180/realms/wellnessz',
  oidcClientId: 'wellnessz-mobile',
  redirectUri: 'com.wellnessz.app://callback',
  requireBiometric: false,
};

function toBool(v: unknown): boolean | undefined {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (/^(true|1|yes)$/i.test(v.trim())) return true;
    if (/^(false|0|no)$/i.test(v.trim())) return false;
  }
  return undefined;
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;
}

/**
 * Merge precedence (highest first): MDM managed config → /config.json → defaults.
 * MDM always wins so an administrator can pin tenant settings on managed devices.
 */
export function mergeConfig(fileValues: ManagedValues, managed: ManagedValues): Omit<RuntimeConfig, 'source'> {
  const pick = (k: (typeof MANAGED_CONFIG_KEYS)[number]) => managed[k] ?? fileValues[k];
  return {
    apiBaseUrl: (str(pick('wz.apiBaseUrl')) ?? DEFAULT_CONFIG.apiBaseUrl).replace(/\/+$/, ''),
    oidcIssuer: (str(pick('wz.oidcIssuer')) ?? DEFAULT_CONFIG.oidcIssuer).replace(/\/+$/, ''),
    oidcClientId: str(pick('wz.oidcClientId')) ?? DEFAULT_CONFIG.oidcClientId,
    redirectUri: str(pick('wz.redirectUri')) ?? DEFAULT_CONFIG.redirectUri,
    requireBiometric: toBool(pick('wz.requireBiometric')) ?? DEFAULT_CONFIG.requireBiometric,
  };
}

async function readConfigFile(fetchImpl: typeof fetch): Promise<ManagedValues | undefined> {
  try {
    const res = await fetchImpl('/config.json', { cache: 'no-store', credentials: 'omit' });
    if (!res.ok) return undefined;
    const json: unknown = await res.json();
    if (!json || typeof json !== 'object') return undefined;
    const out: ManagedValues = {};
    for (const k of MANAGED_CONFIG_KEYS) {
      const v = (json as Record<string, unknown>)[k];
      if (typeof v === 'string' || typeof v === 'boolean') out[k] = v;
    }
    return out;
  } catch (e) {
    log.warn('config.file-unavailable', { errorName: errorName(e) });
    return undefined;
  }
}

export async function loadRuntimeConfig(
  managedConfig: ManagedConfig = resolveManagedConfig(),
  fetchImpl: typeof fetch = fetch,
): Promise<RuntimeConfig> {
  const [file, managed] = await Promise.all([
    readConfigFile(fetchImpl),
    managedConfig.read(MANAGED_CONFIG_KEYS).catch(() => ({}) as ManagedValues),
  ]);
  return {
    ...mergeConfig(file ?? {}, managed),
    source: { managed: Object.keys(managed).length ? managedConfig.name : 'none', file: !!file },
  };
}
