/**
 * IntiMed reference client for TypeScript / JavaScript (IM-E07-BL-004, partial).
 *
 * - fetch-based (Node >= 18, Deno, browsers); no runtime dependencies
 * - OAuth2 client-credentials token helper with caching and early refresh
 * - typed helpers for the machine APIs: FHIR R4 (Observation, Device), harness runs, catalogue, events,
 *   and the public OpenAPI document (`/api/public/openapi.json`) for everything else
 *
 * Console-session APIs (`/api/intelligence/**`, `/api/partner/**`) need a browser session with MFA and CSRF and are not
 * wrapped here; they are described in the OpenAPI document.
 */

export interface TokenResponse {
  access_token: string;
  token_type: 'Bearer';
  expires_in: number;
  scope: string;
}

export interface ClientOptions {
  /** Base URL of the IntiMed instance, e.g. https://intimed.example.org */
  baseUrl: string;
  clientId: string;
  clientSecret: string;
  /** Space-separated scopes, e.g. "system/Observation.read system/Device.read" */
  scope?: string;
  /** Custom fetch (tests, proxies). Defaults to globalThis.fetch. */
  fetch?: typeof fetch;
  /** Allow plain http:// for localhost only (development). Default false. */
  allowInsecureLocalhost?: boolean;
}

export interface FhirCoding { system?: string; code?: string; display?: string }
export interface FhirQuantity { value?: number; unit?: string; system?: string; code?: string }
export interface FhirObservation {
  resourceType: 'Observation';
  id: string;
  status: string;
  code: { coding?: FhirCoding[]; text?: string };
  subject?: { reference?: string; identifier?: { value?: string } };
  effectiveDateTime?: string;
  valueQuantity?: FhirQuantity;
  component?: Array<{ code: { coding?: FhirCoding[] }; valueQuantity?: FhirQuantity; interpretation?: Array<{ coding?: FhirCoding[] }> }>;
  meta?: { tag?: FhirCoding[] };
}
export interface FhirDevice { resourceType: 'Device'; id: string; status: string; manufacturer?: string; deviceName?: Array<{ name: string; type: string }> }
export interface FhirBundle<T> { resourceType: 'Bundle'; type: string; total?: number; entry?: Array<{ resource: T }> }

export interface HarnessRun {
  id: string;
  configId: string;
  driver: string;
  status: 'PASSED' | 'FAILED';
  passed: number;
  total: number;
  seal: string;
  latencyP95Ms?: number;
  results: Record<string, { id: string; name: string; status: 'PASS' | 'FAIL' | 'N/A'; detail: string }>;
}

export interface CatalogueBadge { id: string; level: 'GOLD' | 'SILVER'; driver: string; grantedAt: string; harnessVersion: string; evidenceSeal: string; runId: string }
export interface CatalogueModel {
  id: string;
  manufacturer: string;
  model: string;
  family: string;
  options: string[];
  fdaCodes: string[];
  confidence: string;
  certifiedDrivers: number;
  accepted: boolean;
  badges?: CatalogueBadge[];
}

export interface PlatformEvent { id: string; type: string; time: string; data: Record<string, unknown> }

export class IntiMedError extends Error {
  constructor(public readonly status: number, message: string, public readonly body?: unknown) { super(message); }
}

/** OAuth2 client-credentials helper: caches the token and refreshes it 60 s before expiry. */
export class ClientCredentials {
  private token?: string;
  private expiresAt = 0;
  constructor(private readonly opts: ClientOptions) {}

  async accessToken(): Promise<string> {
    const now = Date.now() / 1000;
    if (this.token && now < this.expiresAt - 60) return this.token;
    const f = this.opts.fetch ?? globalThis.fetch;
    // client_secret_post (RFC 6749 §2.3.1) – the secret only ever travels in the TLS-protected body
    const body = new URLSearchParams({ grant_type: 'client_credentials', client_id: this.opts.clientId, client_secret: this.opts.clientSecret });
    if (this.opts.scope) body.set('scope', this.opts.scope);
    const r = await f(url(this.opts, '/oauth2/token'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: body.toString(),
      redirect: 'error',
    });
    if (!r.ok) throw new IntiMedError(r.status, `token request failed: HTTP ${r.status}`);
    const t = (await r.json()) as TokenResponse;
    this.token = t.access_token;
    this.expiresAt = now + t.expires_in;
    return this.token;
  }
}

function url(opts: ClientOptions, path: string): string {
  const u = new URL(path, opts.baseUrl.endsWith('/') ? opts.baseUrl : opts.baseUrl + '/');
  const local = u.hostname === 'localhost' || u.hostname === '127.0.0.1';
  if (u.protocol !== 'https:' && !(opts.allowInsecureLocalhost && local && u.protocol === 'http:')) throw new Error('IntiMed client requires https');
  return u.toString();
}

export class IntiMedClient {
  readonly credentials: ClientCredentials;
  private readonly f: typeof fetch;

  constructor(private readonly opts: ClientOptions) {
    this.credentials = new ClientCredentials(opts);
    this.f = opts.fetch ?? globalThis.fetch;
  }

  /** Authenticated JSON request against any machine API path. */
  async request<T>(method: string, path: string, body?: unknown, accept = 'application/json'): Promise<T> {
    const token = await this.credentials.accessToken();
    const headers: Record<string, string> = { Authorization: `Bearer ${token}`, Accept: accept };
    if (body !== undefined) headers['Content-Type'] = path.startsWith('/fhir/') ? 'application/fhir+json' : 'application/json';
    const r = await this.f(url(this.opts, path), { method, headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error' });
    const text = await r.text();
    const data = text ? JSON.parse(text) : undefined;
    if (!r.ok) throw new IntiMedError(r.status, (data && (data.detail || data.title)) || `HTTP ${r.status}`, data);
    return data as T;
  }

  // ---- FHIR R4 (scopes system/Observation.read, system/Device.read, system/Observation.write)
  searchObservations(params: { subject?: string; code?: string; count?: number } = {}): Promise<FhirBundle<FhirObservation>> {
    const q = new URLSearchParams();
    if (params.subject) q.set('subject', params.subject);
    if (params.code) q.set('code', params.code);
    if (params.count) q.set('_count', String(params.count));
    return this.request('GET', `/fhir/r4/Observation${q.size ? '?' + q : ''}`, undefined, 'application/fhir+json');
  }
  getObservation(id: string): Promise<FhirObservation> { return this.request('GET', `/fhir/r4/Observation/${encodeURIComponent(id)}`, undefined, 'application/fhir+json'); }
  listDevices(): Promise<FhirBundle<FhirDevice>> { return this.request('GET', '/fhir/r4/Device', undefined, 'application/fhir+json'); }
  postObservation(resource: Record<string, unknown>): Promise<Record<string, unknown>> { return this.request('POST', '/fhir/r4/Observation', resource, 'application/fhir+json'); }

  // ---- Machine API v1 (scopes harness.run, catalogue.read, events.read)
  runHarness(configId: string): Promise<HarnessRun> { return this.request('POST', '/api/v1/harness/runs', { configId }); }
  getHarnessRun(id: string): Promise<HarnessRun> { return this.request('GET', `/api/v1/harness/runs/${encodeURIComponent(id)}`); }
  searchCatalogue(q?: string): Promise<CatalogueModel[]> { return this.request('GET', `/api/v1/catalogue/models${q ? '?q=' + encodeURIComponent(q) : ''}`); }
  events(): Promise<PlatformEvent[]> { return this.request('GET', '/api/v1/events'); }

  // ---- public
  async openApi(): Promise<Record<string, unknown>> {
    const r = await this.f(url(this.opts, '/api/public/openapi.json'), { headers: { Accept: 'application/json' }, redirect: 'error' });
    if (!r.ok) throw new IntiMedError(r.status, `HTTP ${r.status}`);
    return r.json() as Promise<Record<string, unknown>>;
  }
}
