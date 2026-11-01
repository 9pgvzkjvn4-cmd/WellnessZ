/**
 * Pure logic of the E32 provisioning wizard (contract E31-E34 §13.2 / §13.3): step navigation, forms generated from the
 * option-pack JSON Schema (validated defaults, write-only secrets), client-side step validations mirroring the server's
 * `step_invalid` rules, mapping of server findings to form fields, the four-eyes rule and the `module_disabled` state.
 * The server stays authoritative – these checks only save a round trip and point at the right field.
 */
import { ApiError } from '../auth/AuthClient';
import type { Configuration, JsonSchema, OptionComparison, PrerequisiteItem, StepStatus, WizardStep } from '../api/devices';
import type { FieldError } from './devices';

export const STEP_NAMES = ['Select device', 'Choose connectivity option', 'Prerequisites checklist', 'Configure parameters', 'Associate & bind', 'Test', 'Certify & approve', 'Publish & monitor'] as const;
export const FINAL_STATUSES = ['DECOMMISSIONED', 'SUPERSEDED'] as const;
export const LIVE_STATUSES = ['PUBLISHED', 'SUSPENDED', 'QUARANTINED'] as const;

export function stepOf(cfg: Pick<Configuration, 'steps'>, n: number): WizardStep {
  return cfg.steps.find((s) => s.step === n) ?? { step: n, name: STEP_NAMES[n - 1] ?? `Step ${n}`, status: 'NOT_STARTED' };
}

/** Text + symbol per step status – the stepper never relies on colour alone. */
export function stepStatusView(status: StepStatus | undefined): { label: string; symbol: string; tone: 'green' | 'amber' | 'red' | 'muted' } {
  switch (status) {
    case 'VALID': return { label: 'Complete', symbol: '✓', tone: 'green' };
    case 'IN_PROGRESS': return { label: 'In progress', symbol: '◐', tone: 'amber' };
    case 'INVALID': return { label: 'Needs attention', symbol: '!', tone: 'red' };
    default: return { label: 'Not started', symbol: '○', tone: 'muted' };
  }
}

/** A step may be opened once reached (or worked on); later steps stay locked until the earlier ones are valid. */
export function canOpenStep(cfg: Pick<Configuration, 'steps' | 'currentStep'>, n: number): boolean {
  if (n <= Math.max(1, cfg.currentStep)) return true;
  return stepOf(cfg, n).status !== 'NOT_STARTED';
}

/** Step to show first: the current step (bounded 1–8). */
export function initialStep(cfg: Pick<Configuration, 'currentStep'>): number {
  return Math.min(8, Math.max(1, cfg.currentStep || 1));
}

export function isFinal(status: string): boolean {
  return (FINAL_STATUSES as readonly string[]).includes(status);
}
export function isLive(status: string): boolean {
  return (LIVE_STATUSES as readonly string[]).includes(status);
}
/** Step writes are accepted only on an unpaused DRAFT. */
export function isEditable(cfg: Pick<Configuration, 'status' | 'paused'>): boolean {
  return cfg.status === 'DRAFT' && !cfg.paused;
}

/** `503 module_disabled` (e.g. the integration engine E31 is not deployed yet) or the runner returned nothing. */
export function isModuleUnavailable(e: unknown): boolean {
  return e instanceof ApiError && e.status === 503;
}

export function problemCode(e: unknown): string | undefined {
  return e instanceof ApiError ? e.problem?.code : undefined;
}

// ------------------------------------------------------------------ server findings → form fields

/** Turns `errors[{field, message}]` of a 422 into error-summary entries; `idFor` maps a server field to a control id. */
export function serverFindings(e: unknown, idFor: (field: string) => string | undefined, fallbackId: string): FieldError[] {
  if (!(e instanceof ApiError) || !e.problem?.errors?.length) return [];
  return e.problem.errors.map((f) => ({ field: idFor(f.field) ?? fallbackId, message: f.message }));
}

// ------------------------------------------------------------------ path helpers

export type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v);

export function getAt(obj: unknown, path: string): unknown {
  let cur: unknown = obj;
  for (const p of path.split('.')) {
    if (!isObj(cur)) return undefined;
    cur = cur[p];
  }
  return cur;
}

/** Immutable set; `undefined` removes the key (and empty parents). */
export function setAt(obj: Obj, path: string, value: unknown): Obj {
  const [head, ...rest] = path.split('.');
  if (!head) return obj;
  const out: Obj = { ...obj };
  if (rest.length === 0) {
    if (value === undefined) delete out[head];
    else out[head] = value;
    return out;
  }
  const child = setAt(isObj(out[head]) ? (out[head] as Obj) : {}, rest.join('.'), value);
  if (Object.keys(child).length === 0) delete out[head];
  else out[head] = child;
  return out;
}

/** Deep merge of plain objects (arrays and scalars of `b` win). */
export function deepMerge(a: Obj, b: Obj): Obj {
  const out: Obj = { ...a };
  for (const [k, v] of Object.entries(b)) out[k] = isObj(v) && isObj(out[k]) ? deepMerge(out[k] as Obj, v) : v;
  return out;
}

// ------------------------------------------------------------------ JSON-Schema form generation

export type FieldKind = 'enum' | 'text' | 'integer' | 'number' | 'boolean' | 'list' | 'multi' | 'json' | 'const' | 'secret';

export interface SchemaField {
  /** Dot path inside the parameters / common object, e.g. `mllp.port`. */
  path: string;
  label: string;
  kind: FieldKind;
  schema: JsonSchema;
  /** Required within its parent object. */
  required: boolean;
  /** Every ancestor object is required too (so the field is required unconditionally). */
  alwaysRequired: boolean;
  /** Top-level section the field belongs to ('' = top level). */
  group: string;
  hint?: string;
}

/** `listenerEndpointId` → "Listener endpoint id"; `msh.sendingApplication` → "Sending application". */
export function labelFor(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').toLowerCase().trim();
  const fixed = words.replace(/\b(id|ip|dns|tls|mllp|msh|ae|oauth2|url|usb|vid|pid|ble|dms|qc|ocr|rssi|mwl|smart|fhir|hl7|uuid|csv|db|ack)\b/g, (w) =>
    ({ id: 'ID', ip: 'IP', dns: 'DNS', tls: 'TLS', mllp: 'MLLP', msh: 'MSH', ae: 'AE', oauth2: 'OAuth2', url: 'URL', usb: 'USB', vid: 'VID', pid: 'PID', ble: 'BLE', dms: 'DMS', qc: 'QC', ocr: 'OCR', rssi: 'RSSI', mwl: 'MWL', smart: 'SMART', fhir: 'FHIR', hl7: 'HL7', uuid: 'UUID', csv: 'CSV', db: 'DB', ack: 'ACK' })[w] ?? w);
  return fixed.charAt(0).toUpperCase() + fixed.slice(1);
}

function hintFor(s: JsonSchema): string | undefined {
  const parts: string[] = [];
  if (s.minimum !== undefined && s.maximum !== undefined) parts.push(`${s.minimum}–${s.maximum}`);
  else if (s.minimum !== undefined) parts.push(`at least ${s.minimum}`);
  else if (s.maximum !== undefined) parts.push(`at most ${s.maximum}`);
  if (s.maxLength !== undefined) parts.push(`max ${s.maxLength} characters`);
  if (s.format === 'iana-time-zone') parts.push('IANA time zone, e.g. Europe/London');
  if (s.format === 'hostname') parts.push('host name');
  if (s.type === 'array' && s.items?.type === 'string' && !s.items.enum) parts.push('one per line');
  if (s.type === 'array' && s.items?.type === 'object') parts.push('JSON array');
  return parts.length ? parts.join(' · ') : undefined;
}

function kindOf(s: JsonSchema): FieldKind {
  if (s.format === 'secret') return 'secret';
  if (s.const !== undefined) return 'const';
  if (s.enum) return 'enum';
  switch (s.type) {
    case 'boolean': return 'boolean';
    case 'integer': return 'integer';
    case 'number': return 'number';
    case 'array': return s.items?.type === 'string' && s.items.enum ? 'multi' : s.items?.type === 'object' ? 'json' : 'list';
    default: return 'text';
  }
}

/** Flattens an object schema into form fields (nested objects become dot paths grouped by their top-level key). */
export function flattenSchema(schema: JsonSchema | undefined, prefix = '', group = '', parentAlways = true): SchemaField[] {
  if (!schema?.properties) return [];
  const out: SchemaField[] = [];
  const required = new Set(schema.required ?? []);
  for (const [key, s] of Object.entries(schema.properties)) {
    const path = prefix ? `${prefix}.${key}` : key;
    const req = required.has(key);
    if (s.type === 'object' && s.properties) {
      out.push(...flattenSchema(s, path, group || key, parentAlways && req));
      continue;
    }
    out.push({ path, label: labelFor(key), kind: kindOf(s), schema: s, required: req, alwaysRequired: parentAlways && req, group, hint: hintFor(s) });
  }
  return out;
}

/** Secret field paths (`format: "secret"`). */
export function secretPaths(schema: JsonSchema | undefined): string[] {
  return flattenSchema(schema).filter((f) => f.kind === 'secret').map((f) => f.path);
}

export type FormState = Record<string, string | boolean | string[]>;

/** Editable string state from a value object (defaults merged with saved values). */
export function toFormState(fields: SchemaField[], values: Obj): FormState {
  const st: FormState = {};
  for (const f of fields) {
    if (f.kind === 'secret') continue;
    const v = getAt(values, f.path);
    if (f.kind === 'boolean') st[f.path] = v === true;
    else if (f.kind === 'multi') st[f.path] = Array.isArray(v) ? v.map(String) : [];
    else if (f.kind === 'list') st[f.path] = Array.isArray(v) ? v.map(String).join('\n') : '';
    else if (f.kind === 'json') st[f.path] = v === undefined ? '' : JSON.stringify(v, null, 2);
    else if (f.kind === 'const') st[f.path] = String(f.schema.const);
    else st[f.path] = v === undefined || v === null ? '' : String(v);
  }
  return st;
}

const filled = (v: string | boolean | string[] | undefined): boolean =>
  typeof v === 'boolean' ? false : Array.isArray(v) ? v.length > 0 : v !== undefined && v.trim() !== '';

/** Whether any field of the object at `group` path has a value (an optional object that is being configured). */
function objectInUse(fields: SchemaField[], st: FormState, objectPath: string): boolean {
  return fields.some((f) => f.path.startsWith(`${objectPath}.`) && (filled(st[f.path]) || st[f.path] === true));
}

function parentPath(path: string): string {
  const i = path.lastIndexOf('.');
  return i < 0 ? '' : path.slice(0, i);
}

/** Client-side validation against the schema; `idFor(path)` yields the control id for the error summary. */
export function validateSchemaForm(fields: SchemaField[], st: FormState, idFor: (path: string) => string, secretsSet: (path: string) => boolean = () => false): FieldError[] {
  const errors: FieldError[] = [];
  for (const f of fields) {
    const raw = st[f.path];
    const parent = parentPath(f.path);
    const mustHave = f.required && (f.alwaysRequired || (parent !== '' && objectInUse(fields, st, parent)));
    const id = idFor(f.path);
    if (f.kind === 'secret') {
      if (mustHave && !secretsSet(f.path)) errors.push({ field: id, message: `${f.label} is required (stored write-only in the vault)` });
      continue;
    }
    if (f.kind === 'boolean' || f.kind === 'const') continue;
    if (!filled(raw)) {
      if (mustHave) errors.push({ field: id, message: `${f.label} is required` });
      continue;
    }
    const s = f.schema;
    if (f.kind === 'integer' || f.kind === 'number' || (f.kind === 'enum' && (s.type === 'integer' || s.type === 'number'))) {
      const n = Number(raw);
      if (!Number.isFinite(n) || (s.type === 'integer' && !Number.isInteger(n))) {
        errors.push({ field: id, message: `${f.label} must be ${s.type === 'integer' ? 'a whole number' : 'a number'}` });
        continue;
      }
      if ((s.minimum !== undefined && n < s.minimum) || (s.maximum !== undefined && n > s.maximum)) {
        errors.push({ field: id, message: `${f.label} must be between ${s.minimum ?? '–∞'} and ${s.maximum ?? '∞'}` });
      }
      continue;
    }
    if (f.kind === 'text' && typeof raw === 'string') {
      if (s.maxLength !== undefined && raw.length > s.maxLength) errors.push({ field: id, message: `${f.label} must be at most ${s.maxLength} characters` });
      else if (s.pattern && !safeRegex(s.pattern).test(raw)) errors.push({ field: id, message: `${f.label} is not in the expected format` });
      continue;
    }
    if (f.kind === 'list' && typeof raw === 'string') {
      const items = splitList(raw);
      if (s.maxItems !== undefined && items.length > s.maxItems) errors.push({ field: id, message: `${f.label}: at most ${s.maxItems} entries` });
      const pat = s.items?.pattern;
      if (pat && items.some((i) => !safeRegex(pat).test(i))) errors.push({ field: id, message: `${f.label}: an entry is not in the expected format` });
      continue;
    }
    if (f.kind === 'json' && typeof raw === 'string') {
      try {
        const v: unknown = JSON.parse(raw);
        if (!Array.isArray(v)) errors.push({ field: id, message: `${f.label} must be a JSON array` });
      } catch {
        errors.push({ field: id, message: `${f.label} is not valid JSON` });
      }
    }
  }
  return errors;
}

function safeRegex(p: string): RegExp {
  try {
    return new RegExp(p, 'u');
  } catch {
    return /.*/;
  }
}

export function splitList(raw: string): string[] {
  return raw.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
}

/** Typed value object from the form (empty values omitted, secrets never included). */
export function fromFormState(fields: SchemaField[], st: FormState): Obj {
  let out: Obj = {};
  for (const f of fields) {
    if (f.kind === 'secret') continue;
    const raw = st[f.path];
    let v: unknown;
    if (f.kind === 'boolean') v = raw === true;
    else if (f.kind === 'const') v = f.schema.const;
    else if (f.kind === 'multi') v = Array.isArray(raw) && raw.length ? raw : undefined;
    else if (!filled(raw) || typeof raw !== 'string') v = undefined;
    else if (f.kind === 'integer' || f.kind === 'number' || (f.kind === 'enum' && (f.schema.type === 'integer' || f.schema.type === 'number'))) v = Number(raw);
    else if (f.kind === 'list') v = splitList(raw);
    else if (f.kind === 'json') {
      try {
        v = JSON.parse(raw);
      } catch {
        v = undefined;
      }
    } else v = raw;
    if (v !== undefined) out = setAt(out, f.path, v);
  }
  return out;
}

/** Saved parameters with `{set:true}` secret placeholders → the set of secret paths already stored. */
export function storedSecrets(saved: Obj | undefined, paths: string[], names: Obj | undefined): Set<string> {
  const set = new Set<string>();
  for (const p of paths) {
    const v = saved ? getAt(saved, p) : undefined;
    if ((isObj(v) && v.set === true) || (names && isObj(names[p]))) set.add(p);
  }
  return set;
}

/** Control id for a schema path inside a form ('p' parameters, 'c' common). */
export const fieldId = (form: 'p' | 'c', path: string): string => `cfg-${form}-${path.replace(/[^A-Za-z0-9]+/g, '-')}`;

/** Server field (`parameters.mllp.port`, `common.calibrationSchedule[0].lockout`, `secrets.x`) → control id. */
export function step4FieldId(server: string): string | undefined {
  const clean = server.replace(/\[.*$/, '');
  if (clean.startsWith('parameters.')) return fieldId('p', clean.slice('parameters.'.length));
  if (clean.startsWith('common.')) return fieldId('c', clean.slice('common.'.length));
  if (clean.startsWith('secrets.')) return fieldId('p', clean.slice('secrets.'.length));
  return undefined;
}

// ------------------------------------------------------------------ step validations (client side)

export function validateStep1(modelCode: string): FieldError[] {
  return modelCode.trim() ? [] : [{ field: 'wiz-model', message: 'Choose a device model from the catalogue' }];
}

export function validateStep2(option: string, rows: OptionComparison[]): FieldError[] {
  if (!option) return [{ field: 'wiz-option-first', message: 'Choose a connectivity option' }];
  const row = rows.find((r) => r.option === option);
  if (!row) return [{ field: 'wiz-option-first', message: 'The model does not support this connectivity option' }];
  if (!row.driverAvailable) return [{ field: `wiz-option-${option}`, message: `No registered or certified driver package exists for ${option}` }];
  return [];
}

export const prereqId = (code: string, part: 'status' | 'reference') => `wiz-prereq-${code}-${part}`;

/** Manual items marked DONE need evidence (reference, document or note), as the server requires. */
export function validateStep3(items: PrerequisiteItem[]): FieldError[] {
  const errors: FieldError[] = [];
  for (const i of items) {
    if (i.kind === 'AUTO') continue;
    const ev = i.evidence ?? {};
    if (i.status === 'DONE' && !(ev.reference?.trim() || ev.text?.trim() || ev.documentId?.trim())) {
      errors.push({ field: prereqId(i.code, 'reference'), message: `${i.label}: record the evidence (reference, document or note)` });
    }
  }
  return errors;
}

/** Items still blocking the step (required and neither DONE nor NOT_APPLICABLE). */
export function openPrerequisites(items: PrerequisiteItem[]): PrerequisiteItem[] {
  return items.filter((i) => i.required !== false && i.status !== 'DONE' && i.status !== 'NOT_APPLICABLE');
}

export interface BindingDraft {
  formCode: string;
  include: boolean;
  target?: string;
  formVersion?: string;
  category?: string;
  verifyBeforePost: boolean;
  /** Locked on by the package / option (cannot be relaxed). */
  verifyLocked: boolean;
  fields: { key: string; formField: string }[];
}

export interface Step5Form {
  associationMethods: string[];
  siteCode: string;
  stationCode: string;
  roomCode: string;
  services: string;
  bindings: BindingDraft[];
}

export function validateStep5(f: Step5Form): FieldError[] {
  const errors: FieldError[] = [];
  if (f.associationMethods.length === 0) errors.push({ field: 'wiz-assoc-WORKLIST', message: 'Choose at least one association method' });
  if (!f.stationCode) errors.push({ field: 'wiz-station', message: 'Choose the station' });
  const included = f.bindings.filter((b) => b.include);
  if (included.length === 0) errors.push({ field: f.bindings[0] ? `wiz-bind-${f.bindings[0].formCode}` : 'wiz-station', message: 'Bind the results to at least one form' });
  for (const b of included) {
    if (!b.fields.some((x) => x.formField.trim())) errors.push({ field: `wiz-bind-${b.formCode}`, message: `${b.formCode}: map at least one result to a form field` });
  }
  return errors;
}

export function step5Body(f: Step5Form) {
  const bindings = f.bindings.filter((b) => b.include).map((b) => ({
    formCode: b.formCode,
    ...(b.formVersion ? { formVersion: b.formVersion } : {}),
    ...(b.target ? { target: b.target } : {}),
    ...(b.category ? { category: b.category } : {}),
    verifyBeforePost: b.verifyBeforePost,
    fields: Object.fromEntries(b.fields.filter((x) => x.formField.trim()).map((x) => [x.key, x.formField.trim()])),
  }));
  return {
    associationMethods: f.associationMethods,
    siteCode: f.siteCode,
    stationCode: f.stationCode,
    ...(f.roomCode.trim() ? { roomCode: f.roomCode.trim() } : {}),
    services: splitList(f.services),
    formCodes: bindings.map((b) => b.formCode),
    bindings,
  };
}

/** Step-6 server findings (`simulator_test_required`, `live_test_required`, `nurse_review_required`) as a checklist. */
export function step6Checklist(cfg: Configuration): { code: string; label: string; met: boolean }[] {
  const open = new Set((stepOf(cfg, 6).errors ?? []).map((e) => e.code));
  const started = stepOf(cfg, 6).status !== 'NOT_STARTED';
  const lastSim = [...cfg.tests].reverse().find((t) => t.mode === 'SIMULATOR');
  const lastLive = [...cfg.tests].reverse().find((t) => t.mode === 'LIVE');
  return [
    { code: 'simulator_test_required', label: 'Passing simulator run including the negative test vectors', met: started ? !open.has('simulator_test_required') : lastSim?.status === 'PASSED' },
    { code: 'live_test_required', label: 'Passing live run at a production station (or a simulator-only justification)', met: started ? !open.has('live_test_required') : lastLive?.status === 'PASSED' || !!cfg.simulatorOnlyJustification },
    { code: 'nurse_review_required', label: 'Test-form result accepted by an OH nurse', met: started ? !open.has('nurse_review_required') : cfg.nurseReview?.outcome === 'ACCEPTED' },
  ];
}

// ------------------------------------------------------------------ four-eyes

/**
 * Four-eyes (contract §13.2 step 7): the approver is neither the submitter nor anyone who changed steps 4–6 of this
 * version, and a second approval comes from a different person. Returns the reason the user may not approve, if any.
 */
export function fourEyesBlock(cfg: Configuration, username: string | undefined): string | undefined {
  if (!username) return undefined;
  if (cfg.submittedBy === username) return 'You submitted this configuration – another approver must approve it (four-eyes).';
  const editors = new Set<string>();
  for (const n of [4, 5]) {
    const by = stepOf(cfg, n).updatedBy;
    if (by) editors.add(by);
  }
  cfg.tests.forEach((t) => t.by && editors.add(t.by));
  if (editors.has(username)) return 'You changed or tested this configuration – another approver must approve it (four-eyes).';
  if ((cfg.approvals ?? []).some((a) => a.by === username)) return 'You have already approved – the second approval must come from a different person.';
  return undefined;
}

/** Whether the simulator-only justification needs the SECURITY_OPS co-approval. */
export function needsSecurityCoApproval(cfg: Configuration): boolean {
  return !!cfg.simulatorOnlyJustification || getAt(stepOf(cfg, 7).data, 'requiresSecurityCoApproval') === true;
}

/** The approval role this user would contribute (or undefined when they cannot approve). */
export function approvalRoleFor(cfg: Configuration, roles: readonly string[]): 'DEVICE_APPROVER' | 'SECURITY_OPS' | undefined {
  const given = cfg.approvals ?? [];
  if (roles.includes('DEVICE_APPROVER') && !given.some((a) => a.role === 'DEVICE_APPROVER')) return 'DEVICE_APPROVER';
  if (roles.includes('SECURITY_OPS') && needsSecurityCoApproval(cfg) && !given.some((a) => a.role === 'SECURITY_OPS')) return 'SECURITY_OPS';
  return undefined;
}
