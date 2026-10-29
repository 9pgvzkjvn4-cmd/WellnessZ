import { describe, expect, it } from 'vitest';
import type { ManagedConfig } from './managedConfig';
import { DEFAULT_CONFIG, loadRuntimeConfig, mergeConfig } from './runtimeConfig';

describe('runtime config', () => {
  it('MDM managed values override config.json, which overrides defaults', () => {
    const merged = mergeConfig(
      { 'wz.apiBaseUrl': 'https://file.example/api/v1/', 'wz.oidcClientId': 'file-client' },
      { 'wz.apiBaseUrl': 'https://mdm.example/api/v1', 'wz.requireBiometric': 'true' },
    );
    expect(merged.apiBaseUrl).toBe('https://mdm.example/api/v1');
    expect(merged.oidcClientId).toBe('file-client');
    expect(merged.requireBiometric).toBe(true);
    expect(merged.redirectUri).toBe(DEFAULT_CONFIG.redirectUri);
  });

  it('loads /config.json and reports the managed source', async () => {
    const managed: ManagedConfig = { name: 'test-mdm', read: async () => ({ 'wz.oidcIssuer': 'https://idp.example/' }) };
    const fetchImpl = (async () =>
      new Response(JSON.stringify({ 'wz.oidcClientId': 'c1', 'wz.requireBiometric': false }), {
        headers: { 'Content-Type': 'application/json' },
      })) as typeof fetch;
    const cfg = await loadRuntimeConfig(managed, fetchImpl);
    expect(cfg.oidcIssuer).toBe('https://idp.example');
    expect(cfg.oidcClientId).toBe('c1');
    expect(cfg.source).toEqual({ managed: 'test-mdm', file: true });
  });

  it('falls back to defaults when config.json is missing', async () => {
    const cfg = await loadRuntimeConfig(
      { name: 'none', read: async () => ({}) },
      (async () => new Response('nope', { status: 404 })) as typeof fetch,
    );
    expect(cfg.apiBaseUrl).toBe(DEFAULT_CONFIG.apiBaseUrl);
    expect(cfg.source).toEqual({ managed: 'none', file: false });
  });
});
