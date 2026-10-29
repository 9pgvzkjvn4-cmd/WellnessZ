import { vi } from 'vitest';
import type { AuthClient, Session } from '../auth/AuthClient';

export const SESSION: Session = { authenticated: true, name: 'Rami', roles: ['EMPLOYEE'] };

/** AuthClient stub for rendering containers with react-dom/server (effects do not run, so `api` is never awaited). */
export function stubClient(mode: AuthClient['mode'] = 'bff'): AuthClient {
  return {
    mode,
    init: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    api: vi.fn(() => new Promise(() => {})),
    download: vi.fn(),
    directUrl: vi.fn((p: string) => (mode === 'bff' ? `/bff/api${p}` : undefined)),
  } as unknown as AuthClient;
}
