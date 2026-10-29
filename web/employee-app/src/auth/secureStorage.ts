/**
 * Secure storage abstraction for the native refresh token.
 *
 * Production native builds MUST register a Keychain (iOS) / Keystore-backed
 * EncryptedSharedPreferences (Android) implementation, e.g.
 *   - `capacitor-secure-storage-plugin`, or
 *   - `@aparajita/capacitor-secure-storage`
 * by calling `registerSecureStorage(...)` in `src/main.tsx` (see README).
 *
 * The default is an in-memory store: the refresh token is lost when the app is
 * killed (user signs in again) – safe, just less convenient.
 * NEVER back this with localStorage / sessionStorage / IndexedDB / Preferences.
 */
export interface SecureStorage {
  readonly name: string;
  readonly persistent: boolean;
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

export class InMemorySecureStorage implements SecureStorage {
  readonly name = 'in-memory';
  readonly persistent = false;
  readonly #data = new Map<string, string>();
  async get(key: string) {
    return this.#data.get(key) ?? null;
  }
  async set(key: string, value: string) {
    this.#data.set(key, value);
  }
  async remove(key: string) {
    this.#data.delete(key);
  }
}

/**
 * Example adapter (kept as documentation, not imported, to avoid a hard dependency):
 *
 *   import { SecureStoragePlugin } from 'capacitor-secure-storage-plugin';
 *   registerSecureStorage({
 *     name: 'keychain-keystore', persistent: true,
 *     get: async (key) => { try { return (await SecureStoragePlugin.get({ key })).value; } catch { return null; } },
 *     set: async (key, value) => { await SecureStoragePlugin.set({ key, value }); },
 *     remove: async (key) => { try { await SecureStoragePlugin.remove({ key }); } catch { } },
 *   });
 */
let current: SecureStorage = new InMemorySecureStorage();

export function registerSecureStorage(impl: SecureStorage): void {
  current = impl;
}

export function getSecureStorage(): SecureStorage {
  return current;
}
