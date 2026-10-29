/** Shared test fixtures shaped like the API-Contract-E03-E05 examples. */
import type { FitnessDecision } from '../api/fitness';
import type { FitnessStandard, RestrictionDef } from '../api/platform';
import type { ChartSummary, ClinicalEncounter, EncounterTemplate } from '../api/record';
import type { CaseDetail } from '../api/types';

export const chartSummary = (over: Partial<ChartSummary> = {}): ChartSummary => ({
  employee: { employeeId: 'E1004', displayName: 'Rami Ramp', dateOfBirth: null, subsidiaryCode: 'GND', departmentCode: 'GROUND', jobCode: 'RAMP_AGENT', jobTitle: 'Ramp agent', locationCode: 'LHR', safetyCategory: 'AIRSIDE_DRIVER' },
  alerts: [
    { kind: 'ALLERGY', severity: 'HIGH', text: 'Penicillin – anaphylaxis' },
    { kind: 'OVERDUE_SURVEILLANCE', severity: 'MEDIUM', text: 'Audiometry overdue since 2026-09-01' },
  ],
  fitness: { status: 'FIT_WITH_RESTRICTIONS', standardCode: 'AIRSIDE_DRIVING', validUntil: '2026-12-01', reviewDate: '2026-11-15', decidedBy: 'physician1' },
  restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above 10 kg', validUntil: '2026-11-30', status: 'ACTIVE' }],
  openCases: [{ id: 'c1', caseRef: 'GND-2026-000001', status: 'ASSESSMENT', caseType: 'INJURY' }],
  surveillanceDue: [{ hazardCode: 'NOISE', programme: 'Audiometry', dueDate: '2026-09-01', overdue: true }],
  immunisations: [{ vaccine: 'Hepatitis B', date: '2024-03-01', status: 'COMPLETE' }],
  counts: { problems: 2, medications: 1, allergies: 1, results: 4, unacknowledgedAbnormal: 1, documents: 3, unsignedEncounters: 0 },
  restrictedItemsHidden: 0,
  ...over,
});

export const injuryTemplate: EncounterTemplate = {
  type: 'INJURY',
  version: 2,
  title: 'Injury consultation',
  sections: [
    {
      key: 'history',
      title: 'History',
      fields: [
        { key: 'mechanismText', label: 'How did it happen', type: 'TEXTAREA', required: true },
        { key: 'painScore', label: 'Pain score', type: 'NUMBER', required: false, unit: '0-10' },
        { key: 'handDominance', label: 'Dominant hand', type: 'SELECT', options: [{ value: 'L', label: 'Left' }, { value: 'R', label: 'Right' }] },
      ],
    },
  ],
  bodyMap: true,
  requiresDiagnosis: true,
  fitnessStandardPrompt: false,
  approvedBy: 'cmo1',
  status: 'APPROVED',
};

export const draftEncounter = (over: Partial<ClinicalEncounter> = {}): ClinicalEncounter => ({
  id: 'enc1',
  employeeId: 'E1004',
  caseId: 'c1',
  type: 'INJURY',
  status: 'DRAFT',
  title: 'Injury consultation',
  author: 'tester',
  createdAt: '2026-10-29T08:00:00Z',
  signedAt: null,
  primaryDiagnosis: null,
  aiAssisted: false,
  answers: {},
  soap: {},
  bodyMap: [],
  diagnoses: [],
  addenda: [],
  photos: [],
  signature: null,
  templateVersion: 2,
  cdsAlerts: [{ code: 'SURVEILLANCE_DUE', severity: 'INFO', message: 'Audiometry due', reference: 'Noise protocol' }],
  ...over,
});

export const standards: FitnessStandard[] = [
  { code: 'AIRSIDE_DRIVING', title: 'Airside driving', roleCategory: 'GROUND', source: 'Airport rules', validityMonths: 36, requiresSignoffRole: null, limitationCodes: [], version: 1, status: 'ACTIVE' },
  { code: 'CAA_CLASS1', title: 'Pilot – Part-MED Class 1', roleCategory: 'FLIGHT_CREW', source: 'EASA Part-MED MED.B / local CAA', referenceUrl: 'https://example.invalid/partmed', validityMonths: 12, validityMonthsOver40: 6, requiresSignoffRole: 'AME', limitationCodes: ['TML', 'VDL'], version: 3, status: 'ACTIVE' },
];

export const catalogue: RestrictionDef[] = [
  { code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', dimension: 'LIFT_KG', valueRequired: true, unit: 'kg', active: true },
  { code: 'NO_NIGHT', label: 'No night shifts', dimension: 'NIGHT', valueRequired: false, unit: null, active: true },
];

export const decision = (over: Partial<FitnessDecision> = {}): FitnessDecision => ({
  id: 'fd1',
  caseId: 'c1',
  caseRef: 'GND-2026-000001',
  employeeId: 'E1004',
  employeeName: 'Rami Ramp',
  standardCode: 'AIRSIDE_DRIVING',
  standardTitle: 'Airside driving',
  standardVersion: 1,
  outcome: 'FIT_WITH_LIMITATIONS',
  kernelStatus: 'FIT_WITH_RESTRICTIONS',
  limitationCodes: [],
  restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above 10 kg', value: 10 }],
  validFrom: '2026-10-29',
  validUntil: '2026-11-30',
  reviewDate: '2026-11-15',
  rationale: 'Wrist sprain',
  status: 'FINAL',
  decidedBy: 'physician1',
  decidedAt: '2026-10-29T09:00:00Z',
  ...over,
});

export const caseDetail = (over: Partial<CaseDetail> = {}): CaseDetail => ({
  id: 'c1',
  caseRef: 'GND-2026-000001',
  employeeId: 'E1004',
  employeeName: 'Rami Ramp',
  status: 'ASSESSMENT',
  caseType: 'INJURY',
  subsidiaryCode: 'GND',
  safetyCritical: true,
  safetyCategory: 'AIRSIDE_DRIVER',
  ...over,
});
