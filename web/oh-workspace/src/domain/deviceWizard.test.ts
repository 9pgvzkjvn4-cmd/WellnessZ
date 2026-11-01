import { describe, expect, it } from 'vitest';
import type { Configuration, JsonSchema, OptionComparison, PrerequisiteItem, WizardStep } from '../api/devices';
import { ApiError, toProblem } from '../auth/AuthClient';
import {
  approvalRoleFor, canOpenStep, deepMerge, fieldId, flattenSchema, fourEyesBlock, fromFormState, getAt, initialStep, isEditable, isModuleUnavailable,
  labelFor, needsSecurityCoApproval, openPrerequisites, secretPaths, serverFindings, setAt, splitList, step4FieldId, step5Body, step6Checklist, stepOf,
  stepStatusView, storedSecrets, toFormState, validateSchemaForm, validateStep1, validateStep2, validateStep3, validateStep5, type Step5Form,
} from './deviceWizard';
import { plural, toneFor } from './deviceConsole';

const P4: JsonSchema = {
  type: 'object',
  required: ['deviceAddress', 'mllp', 'ackMode'],
  properties: {
    deviceAddress: { type: 'object', properties: { ip: { type: 'string', pattern: '^((25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})$' }, dns: { type: 'string', format: 'hostname' } } },
    mllp: {
      type: 'object',
      required: ['listenerEndpointId', 'port', 'tls'],
      properties: {
        listenerEndpointId: { type: 'string', pattern: '^[A-Z0-9][A-Z0-9\\-]{2,60}$' },
        port: { type: 'integer', minimum: 1024, maximum: 65535, default: 2575 },
        tls: { type: 'string', enum: ['REQUIRED', 'EXCEPTION_SEGMENTED_VLAN'], default: 'REQUIRED' },
        clientCertRequired: { type: 'boolean', default: true },
      },
    },
    ackMode: { type: 'string', enum: ['ORIGINAL'], default: 'ORIGINAL' },
    serialServer: { type: 'object', required: ['host', 'authRef'], properties: { host: { type: 'string', format: 'hostname' }, authRef: { type: 'string', format: 'secret' } } },
    characteristics: { type: 'array', maxItems: 2, items: { type: 'string', pattern: '^0x[0-9A-F]{4}$' } },
    attestationFields: { type: 'array', items: { type: 'string', enum: ['MODEL', 'SERIAL'] } },
    calibrationSchedule: { type: 'array', items: { type: 'object', properties: { checkType: { type: 'string' } } } },
    verification: { type: 'string', const: 'MANDATORY' },
  },
};

const step = (n: number, over: Partial<WizardStep> = {}): WizardStep => ({ step: n, name: `Step ${n}`, status: 'NOT_STARTED', ...over });
const config = (over: Partial<Configuration> = {}): Configuration => ({
  configurationId: 'CFG-000012', version: 1, deviceInstanceId: 'DEV-000012', status: 'DRAFT', paused: false, currentStep: 1, modelCode: 'MD-027',
  steps: [1, 2, 3, 4, 5, 6, 7, 8].map((n) => step(n)), tests: [], ...over,
});

describe('path helpers', () => {
  it('gets, sets (immutably) and removes nested values', () => {
    const a = { mllp: { port: 2575 } };
    const b = setAt(a, 'mllp.tls', 'REQUIRED');
    expect(a).toEqual({ mllp: { port: 2575 } });
    expect(getAt(b, 'mllp.tls')).toBe('REQUIRED');
    expect(setAt(setAt({}, 'x.y', 1), 'x.y', undefined)).toEqual({});
    expect(getAt(undefined, 'a.b')).toBeUndefined();
  });
  it('deep-merges saved values over defaults', () => {
    expect(deepMerge({ mllp: { port: 2575, tls: 'REQUIRED' }, a: [1] }, { mllp: { port: 3000 }, a: [2] })).toEqual({ mllp: { port: 3000, tls: 'REQUIRED' }, a: [2] });
  });
  it('splits lists on commas and new lines', () => {
    expect(splitList('a, b\nc,,')).toEqual(['a', 'b', 'c']);
  });
});

describe('schema → form', () => {
  const fields = flattenSchema(P4);
  it('flattens nested objects into dot paths grouped by top-level key with kinds', () => {
    const by = Object.fromEntries(fields.map((f) => [f.path, f]));
    expect(by['mllp.port']).toMatchObject({ kind: 'integer', group: 'mllp', required: true, alwaysRequired: true });
    expect(by['deviceAddress.ip']).toMatchObject({ kind: 'text', alwaysRequired: false });
    expect(by['serialServer.authRef']).toMatchObject({ kind: 'secret', required: true, alwaysRequired: false });
    expect(by['mllp.tls']?.kind).toBe('enum');
    expect(by['mllp.clientCertRequired']?.kind).toBe('boolean');
    expect(by['characteristics']?.kind).toBe('list');
    expect(by['attestationFields']?.kind).toBe('multi');
    expect(by['calibrationSchedule']?.kind).toBe('json');
    expect(by['verification']?.kind).toBe('const');
    expect(by['mllp.port']?.hint).toBe('1024–65535');
    expect(secretPaths(P4)).toEqual(['serialServer.authRef']);
  });
  it('labels camel-case keys in British English with known acronyms', () => {
    expect(labelFor('listenerEndpointId')).toBe('Listener endpoint ID');
    expect(labelFor('clientCertRequired')).toBe('Client cert required');
    expect(labelFor('tlsVersion')).toBe('TLS version');
  });
  it('round-trips defaults through the editable state with types restored', () => {
    const st = toFormState(fields, { mllp: { port: 2575, tls: 'REQUIRED', clientCertRequired: true }, characteristics: ['0x2A35'], attestationFields: ['MODEL'], calibrationSchedule: [{ checkType: 'X' }] });
    expect(st['mllp.port']).toBe('2575');
    expect(st['characteristics']).toBe('0x2A35');
    const out = fromFormState(fields, { ...st, 'mllp.listenerEndpointId': 'EP-LHR-GW-01', 'deviceAddress.ip': '' });
    expect(out).toEqual({ mllp: { port: 2575, tls: 'REQUIRED', clientCertRequired: true, listenerEndpointId: 'EP-LHR-GW-01' }, characteristics: ['0x2A35'], attestationFields: ['MODEL'], calibrationSchedule: [{ checkType: 'X' }], verification: 'MANDATORY' });
  });
  it('validates required (incl. optional objects in use), pattern, range, integer, list and JSON', () => {
    const id = (p: string) => fieldId('p', p);
    const st = toFormState(fields, { ackMode: 'ORIGINAL', mllp: { port: 80, tls: 'REQUIRED' } });
    const errs = validateSchemaForm(fields, { ...st, 'deviceAddress.ip': '999.1.1.1', 'characteristics': '0x2A35\n0x2A36\nbad', 'calibrationSchedule': '{', 'serialServer.host': 'ts.local' }, id);
    const msgs = Object.fromEntries(errs.map((e) => [e.field, e.message]));
    expect(msgs[id('mllp.port')]).toMatch(/between 1024 and 65535/);
    expect(msgs[id('mllp.listenerEndpointId')]).toMatch(/is required/);
    expect(msgs[id('deviceAddress.ip')]).toMatch(/expected format/);
    expect(msgs[id('characteristics')]).toMatch(/at most 2/);
    expect(msgs[id('calibrationSchedule')]).toMatch(/not valid JSON/);
    expect(msgs[id('serialServer.authRef')]).toMatch(/required \(stored write-only/);
    // the optional serial-server object is not in use → its required fields are not enforced
    const ok = validateSchemaForm(fields, { ...st, 'mllp.port': '2575', 'mllp.listenerEndpointId': 'EP-LHR-GW-01', 'deviceAddress.dns': 'csm1.lhr.local' }, id);
    expect(ok).toEqual([]);
    // a required object whose members are all optional needs at least one of them
    const none = validateSchemaForm(fields, { ...st, 'mllp.port': '2575', 'mllp.listenerEndpointId': 'EP-LHR-GW-01' }, id);
    expect(none).toEqual([{ field: id('deviceAddress.ip'), message: 'Device address: enter at least one of IP, DNS' }]);
    expect(validateSchemaForm(fields, { ...st, 'mllp.port': '2575.5', 'mllp.listenerEndpointId': 'EP-1X' }, id).map((e) => e.message)).toContain('Port must be a whole number');
  });
  it('treats a stored secret ({set:true}) as present', () => {
    const stored = storedSecrets({ serialServer: { authRef: { set: true } } }, ['serialServer.authRef'], undefined);
    expect(stored.has('serialServer.authRef')).toBe(true);
    const st = { ...toFormState(fields, { ackMode: 'ORIGINAL', mllp: { port: 2575, tls: 'REQUIRED', listenerEndpointId: 'EP-LHR-GW-01' } }), 'serialServer.host': 'ts.local', 'deviceAddress.ip': '10.40.10.21' };
    expect(validateSchemaForm(fields, st, (p) => p, (p) => stored.has(p))).toEqual([]);
    expect(storedSecrets(undefined, ['a.b'], { 'a.b': { set: true } }).has('a.b')).toBe(true);
  });
  it('maps server step-4 fields to the generated control ids', () => {
    expect(step4FieldId('parameters.mllp.port')).toBe('cfg-p-mllp-port');
    expect(step4FieldId('common.calibrationSchedule[0].intervalHours')).toBe('cfg-c-calibrationSchedule');
    expect(step4FieldId('secrets.serialServer.authRef')).toBe('cfg-p-serialServer-authRef');
    expect(step4FieldId('parameters')).toBeUndefined();
  });
});

describe('wizard navigation and step validations', () => {
  it('opens reached steps only and starts at the current step', () => {
    const cfg = config({ currentStep: 3, steps: [step(1, { status: 'VALID' }), step(2, { status: 'VALID' }), step(3, { status: 'IN_PROGRESS' })] });
    expect(canOpenStep(cfg, 3)).toBe(true);
    expect(canOpenStep(cfg, 4)).toBe(false);
    expect(initialStep(cfg)).toBe(3);
    expect(initialStep(config({ currentStep: 0 }))).toBe(1);
    expect(stepOf(cfg, 8).status).toBe('NOT_STARTED');
    expect(isEditable(cfg)).toBe(true);
    expect(isEditable({ ...cfg, paused: true })).toBe(false);
    expect(isEditable({ ...cfg, status: 'PUBLISHED' })).toBe(false);
  });
  it('describes every step status with text and a symbol (not colour alone)', () => {
    expect(stepStatusView('VALID')).toEqual({ label: 'Complete', symbol: '✓', tone: 'green' });
    expect(stepStatusView('INVALID').label).toBe('Needs attention');
    expect(stepStatusView(undefined).symbol).toBe('○');
  });
  it('step 1 needs a model; step 2 a supported option with a driver', () => {
    expect(validateStep1('')).toHaveLength(1);
    expect(validateStep1('MD-027')).toEqual([]);
    const rows: OptionComparison[] = [{ option: 'P4', name: 'HL7', driverAvailable: true }, { option: 'P10', name: 'Manual', driverAvailable: false }];
    expect(validateStep2('', rows)[0]?.message).toBe('Choose a connectivity option');
    expect(validateStep2('P1', rows)[0]?.message).toMatch(/does not support/);
    expect(validateStep2('P10', rows)[0]).toEqual({ field: 'wiz-option-P10', message: 'No registered or certified driver package exists for P10' });
    expect(validateStep2('P4', rows)).toEqual([]);
  });
  it('step 3: manual items marked done need evidence; automatic items are not checked client-side', () => {
    const items: PrerequisiteItem[] = [
      { code: 'NETWORK_ZONE', label: 'Network zone', kind: 'MANUAL', status: 'DONE' },
      { code: 'CERTIFICATES', label: 'TLS certificates', kind: 'MANUAL', status: 'DONE', evidence: { reference: 'CHG-1' } },
      { code: 'VENDOR_POSTURE', label: 'Posture', kind: 'AUTO', status: 'OPEN' },
      { code: 'CONSUMABLES', label: 'Consumables', kind: 'MANUAL', status: 'NOT_APPLICABLE' },
    ];
    expect(validateStep3(items)).toEqual([{ field: 'wiz-prereq-NETWORK_ZONE-reference', message: 'Network zone: record the evidence (reference, document or note)' }]);
    expect(openPrerequisites(items).map((i) => i.code)).toEqual(['VENDOR_POSTURE']);
  });
  it('step 5: association method, station and at least one mapped binding; body only carries included bindings', () => {
    const f: Step5Form = {
      associationMethods: [], siteCode: 'LHR', stationCode: '', roomCode: ' ', services: 'Vitals, Pre-employment',
      bindings: [
        { formCode: 'E03.DEVICE_RESULT.VITALS', include: true, target: 'E03.RESULT', category: 'VITALS', verifyBeforePost: true, verifyLocked: true, fields: [{ key: 'systolic', formField: '' }] },
        { formCode: 'E12.SCREENING_RESULT', include: false, verifyBeforePost: true, verifyLocked: false, fields: [{ key: 'systolic', formField: 'sys' }] },
      ],
    };
    expect(validateStep5(f).map((e) => e.field)).toEqual(['wiz-assoc-WORKLIST', 'wiz-station', 'wiz-bind-E03.DEVICE_RESULT.VITALS']);
    const ok: Step5Form = { ...f, associationMethods: ['SESSION'], stationCode: 'LHR-OH-ROOM-1', bindings: [{ ...f.bindings[0]!, fields: [{ key: 'systolic', formField: ' bpSystolic ' }, { key: 'diastolic', formField: '' }] }, f.bindings[1]!] };
    expect(validateStep5(ok)).toEqual([]);
    expect(step5Body(ok)).toEqual({
      associationMethods: ['SESSION'], siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1', services: ['Vitals', 'Pre-employment'], formCodes: ['E03.DEVICE_RESULT.VITALS'],
      bindings: [{ formCode: 'E03.DEVICE_RESULT.VITALS', target: 'E03.RESULT', category: 'VITALS', verifyBeforePost: true, fields: { systolic: 'bpSystolic' } }],
    });
  });
  it('step 6 checklist follows the server findings', () => {
    const cfg = config({ steps: [step(6, { status: 'IN_PROGRESS', errors: [{ field: 'nurseReview', code: 'nurse_review_required', message: 'x' }] })], tests: [{ testRunId: 't', mode: 'SIMULATOR', status: 'PASSED' }] });
    expect(step6Checklist(cfg).map((c) => c.met)).toEqual([true, true, false]);
    expect(step6Checklist(config({ simulatorOnlyJustification: 'bench', nurseReview: { outcome: 'ACCEPTED' } })).map((c) => c.met)).toEqual([false, true, true]);
  });
});

describe('four-eyes', () => {
  const awaiting = config({
    status: 'AWAITING_APPROVAL', submittedBy: 'deviceadmin1',
    steps: [step(4, { status: 'VALID', updatedBy: 'deviceadmin1' }), step(5, { status: 'VALID', updatedBy: 'inttester1' })],
    tests: [{ testRunId: 't1', mode: 'SIMULATOR', status: 'PASSED', by: 'inttester2' }],
  });
  it('blocks the submitter, step editors and testers; allows another approver', () => {
    expect(fourEyesBlock(awaiting, 'deviceadmin1')).toMatch(/You submitted/);
    expect(fourEyesBlock(awaiting, 'inttester1')).toMatch(/changed or tested/);
    expect(fourEyesBlock(awaiting, 'inttester2')).toMatch(/changed or tested/);
    expect(fourEyesBlock(awaiting, 'deviceapprover1')).toBeUndefined();
    expect(fourEyesBlock(awaiting, undefined)).toBeUndefined();
    expect(fourEyesBlock({ ...awaiting, approvals: [{ role: 'DEVICE_APPROVER', by: 'deviceapprover1', at: 'x' }] }, 'deviceapprover1')).toMatch(/already approved/);
  });
  it('works out the approval role (security co-approval only with a simulator-only justification)', () => {
    expect(approvalRoleFor(awaiting, ['DEVICE_APPROVER'])).toBe('DEVICE_APPROVER');
    expect(approvalRoleFor(awaiting, ['SECURITY_OPS'])).toBeUndefined();
    const waived = { ...awaiting, simulatorOnlyJustification: 'bench only', approvals: [{ role: 'DEVICE_APPROVER', by: 'deviceapprover1', at: 'x' }] };
    expect(needsSecurityCoApproval(waived)).toBe(true);
    expect(approvalRoleFor(waived, ['SECURITY_OPS'])).toBe('SECURITY_OPS');
    expect(approvalRoleFor(waived, ['DEVICE_APPROVER'])).toBeUndefined();
  });
});

describe('problems', () => {
  it('keeps the field findings of a 422 and maps them to controls', () => {
    const p = toProblem({ status: 422, code: 'step_invalid', detail: 'Step 4 is not valid', errors: [{ field: 'parameters.mllp.port', code: 'maximum', message: 'too big' }, { bogus: true }, 'x'] });
    expect(p.errors).toEqual([{ field: 'parameters.mllp.port', code: 'maximum', message: 'too big' }]);
    const e = new ApiError(422, p);
    expect(serverFindings(e, step4FieldId, 'fallback')).toEqual([{ field: 'cfg-p-mllp-port', message: 'Port too big' }]);
    const bare = new ApiError(422, toProblem({ errors: [{ field: 'parameters.deviceAddress', message: 'is required' }, { field: 'step3', message: 'Prerequisites checklist is not complete' }] }));
    expect(serverFindings(bare, () => undefined, 'f').map((x) => x.message)).toEqual(['Device address is required', 'Prerequisites checklist is not complete']);
    expect(serverFindings(new ApiError(409, { code: 'four_eyes_violation' }), step4FieldId, 'x')).toEqual([]);
    expect(toProblem({ status: 400 }).errors).toBeUndefined();
  });
  it('recognises the module_disabled / unavailable state', () => {
    expect(isModuleUnavailable(new ApiError(503, { code: 'module_disabled' }))).toBe(true);
    expect(isModuleUnavailable(new ApiError(422, {}))).toBe(false);
    expect(isModuleUnavailable(new Error('x'))).toBe(false);
  });
  it('console tones and plurals', () => {
    expect(toneFor('QUARANTINED')).toBe('red');
    expect(toneFor('nonsense')).toBe('muted');
    expect(toneFor(undefined)).toBe('muted');
    expect(plural(1, 'device')).toBe('1 device');
    expect(plural(3, 'entry', 'entries')).toBe('3 entries');
  });
});
