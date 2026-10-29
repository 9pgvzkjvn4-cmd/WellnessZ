/** Contract-shaped payloads (docs/increment-02/API-Contract-E03-E05.md) for the portal tests. */

export const DOCUMENTS = [
  { id: 'd1', documentNumber: 'DOC-2026-000101', type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate – Ground duties', status: 'SIGNED', version: 1, signedAt: '2026-10-20T08:00:00Z', acknowledgedAt: null, contentType: 'application/pdf', sha256: 'abc', employeeId: 'E1004' },
  { id: 'd2', documentNumber: 'DOC-2026-000102', type: 'RESTRICTION_NOTE', title: 'Restriction note', status: 'SIGNED', version: 2, signedAt: '2026-10-21T08:00:00Z', acknowledgedAt: '2026-10-22T09:30:00Z' },
  { id: 'd3', documentNumber: 'DOC-2026-000103', type: 'RTW_PLAN', title: 'Return-to-work plan', status: 'SIGNED', signedAt: '2026-10-23T08:00:00Z' },
  { id: 'd4', documentNumber: 'DOC-2026-000104', type: 'JOB_OFFER', title: 'Alternative duty offer', status: 'SIGNED', createdAt: '2026-10-24T08:00:00Z' },
  { id: 'd5', documentNumber: 'DOC-2026-000105', type: 'RESULT_LETTER', title: 'Audiometry result letter', status: 'SIGNED', signedAt: '2026-10-25T08:00:00Z' },
  { id: 'd6', documentNumber: 'DOC-2026-000099', type: 'FITNESS_CERTIFICATE', title: 'Old certificate', status: 'SUPERSEDED', signedAt: '2026-09-01T08:00:00Z' },
  { id: 'd7', type: 'FOOD_HANDLER_CLEARANCE', title: 'Food-handler clearance', status: 'REVOKED' },
  { id: 'd8', type: 'FOOD_HANDLER_CLEARANCE', status: 'SIGNED', signedAt: '2026-10-26T08:00:00Z' },
  { title: 'no id' },
];

export const RTW_PLANS = [
  {
    id: 'p1',
    caseId: 'c1',
    caseRef: 'GND-2026-000042',
    employeeId: 'E1004',
    employeeName: 'Rami Ramp',
    status: 'ACTIVE',
    startDate: '2026-11-02',
    goal: 'Back to full ramp duties',
    phases: [
      { fromWeek: 3, toWeek: 4, hoursPerDay: 8, duties: 'Ramp duties without heavy bags', restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 15 }] },
      { fromWeek: 1, toWeek: 2, hoursPerDay: 4, duties: 'Baggage reconciliation office', restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 5 }, { code: 'NO_AIRSIDE_DRIVING', label: 'No airside driving' }] },
    ],
    reviews: [
      { id: 'r2', dueDate: '2026-11-24', completedAt: null },
      { id: 'r1', dueDate: '2026-11-10', completedAt: '2026-11-10T10:00:00Z', outcome: 'ON_TRACK', note: 'internal' },
    ],
    employeeAck: null,
    managerAck: { at: '2026-10-30T10:00:00Z', by: 'mgr1', comment: 'ok' },
    documentId: 'd3',
    createdBy: 'nurse1',
    nextReviewDate: '2026-11-24',
  },
  {
    id: 'p2',
    caseRef: 'GND-2026-000041',
    status: 'ACTIVE',
    startDate: '2026-09-01',
    phases: [{ fromWeek: 1, toWeek: 1, hoursPerDay: 6, duties: 'Office', restrictions: [] }],
    reviews: [],
    employeeAck: { at: '2026-09-01T09:00:00Z', agree: true, comment: 'Thanks' },
    contactName: 'Nora Nurse',
    contactEmail: 'oh@example.com',
  },
];

export const JOB_OFFERS = [
  {
    id: 'o1',
    caseId: 'c1',
    employeeId: 'E1004',
    jobCode: 'GND-OFFICE',
    jobTitle: 'Baggage reconciliation agent',
    duties: 'Reconcile baggage manifests',
    location: 'Terminal 1, Ops building',
    shift: 'Day shift 08:00–16:00',
    hoursPerWeek: 30,
    payBasis: 'PROTECTED_PAY',
    payNote: 'Ramp allowance continues for 8 weeks',
    restrictions: [{ code: 'LIFT_MAX_KG', value: 5 }],
    startDate: '2026-11-02',
    endDate: '2026-12-14',
    contactName: 'Hana HR',
    contactEmail: 'hr@example.com',
    respondBy: '2026-10-31',
    status: 'ISSUED',
    issuedAt: '2026-10-28T08:00:00Z',
  },
  {
    id: 'o0',
    jobTitle: 'Cargo admin',
    payBasis: 'SAME_PAY',
    restrictions: [],
    startDate: '2026-06-01',
    status: 'ACCEPTED',
    issuedAt: '2026-05-20T08:00:00Z',
    respondedAt: '2026-05-21T08:00:00Z',
    comment: 'Happy to help',
  },
];

export const DISCLOSURES = [
  { at: '2026-10-20T08:05:00Z', recipientType: 'MANAGER', recipientName: 'Mona Manager', documentTitle: 'Fitness certificate', purpose: 'FITNESS_STATUS', consentId: null, status: 'ACTIVE' },
  { at: '2026-10-25T08:05:00Z', recipientType: 'PARTNER', recipientName: 'PhysioPlus Clinic', documentTitle: 'Referral letter', purpose: 'TREATMENT_SHARING_PARTNER', consentId: 'k1', status: 'ACTIVE' },
  { at: '2026-10-01T08:05:00Z', recipientType: 'PARTNER', recipientName: 'Old Clinic', documentTitle: 'Result letter', purpose: 'Physiotherapy follow-up', consentId: 'k0', status: 'REVOKED' },
];

export const CONSENTS = [
  { id: 'k1', purpose: 'TREATMENT_SHARING_PARTNER', status: 'GRANTED', grantedAt: '2026-10-01T08:00:00Z' },
  { id: 'k0', purpose: 'EAP_REFERRAL', status: 'WITHDRAWN', grantedAt: '2026-01-01T08:00:00Z', withdrawnAt: '2026-10-02T08:00:00Z' },
];
