/** Contract-shaped API fixtures (docs/increment-04/API-Contract-E04-E08.md). */

export const meProvider = { subject: 's1', username: 'partner1', displayName: 'Pat Partner', employeeId: 'P9101', orgScope: '/PARTNERS/CITYHOSP', roles: ['PARTNER_PROVIDER'], maxDisclosure: 'STATUS_ONLY' };
export const meAdjuster = { subject: 's2', username: 'adjuster1', displayName: 'Ada Adjuster', employeeId: 'X8702', orgScope: '/PARTNERS/GULFTPA', roles: ['TPA_ADJUSTER'], maxDisclosure: 'STATUS_ONLY' };

export const referral = (over: Record<string, unknown> = {}) => ({
  id: 'r1',
  ref: 'RFL-2026-000001',
  serviceName: 'Physiotherapy',
  urgency: 'URGENT',
  status: 'SENT',
  sentAt: '2026-10-27T09:00:00Z',
  reportDueAt: '2026-11-05T17:00:00Z',
  patient: { displayName: 'Rami Ramp', employeeId: 'E1004', phone: '+44 20 7946 0000' },
  reason: 'Lower back strain after lifting baggage',
  question: 'Please assess and treat; advise on lifting capacity.',
  dataSet: { bodyRegion: 'Lumbar spine', sessionsRequested: 6, imagingDone: false, nested: { x: 1 } },
  letterDocumentId: 'doc-letter',
  attachments: [{ documentId: 'doc-att', title: 'Physio intake form' }],
  // fields outside the partner contract must be dropped
  caseId: 'c-secret',
  fhir: { resourceType: 'ServiceRequest' },
  ...over,
});

export const session = (over: Record<string, unknown> = {}) => ({
  id: 't1',
  ref: 'TH-2026-000001',
  employeeId: 'E1004',
  displayName: 'Rami Ramp',
  caseId: 'c-secret',
  scheduledAt: '2026-10-29T10:00:00Z',
  durationMinutes: 30,
  mode: 'VIDEO',
  status: 'SCHEDULED',
  clinicianUsername: 'nurse1',
  providerCode: 'TELEDOC',
  crewLayover: true,
  layoverCity: 'Dubai',
  consentGiven: true,
  encounterId: 'enc-secret',
  ...over,
});

export const claimDetail = (over: Record<string, unknown> = {}) => ({
  id: 'cl1',
  claimRef: 'CLM-2026-000001',
  claimType: 'WORKERS_COMP',
  status: 'OPEN',
  displayName: 'Rami Ramp',
  incidentDate: '2026-10-01',
  lostTime: true,
  openTasks: 1,
  unreadMessages: 2,
  clinicalNotes: 'SECRET clinical note',
  diagnosis: 'SECRET diagnosis',
  reserves: { MEDICAL: { amount: '1000.00', currency: 'GBP' } },
  rtw: {
    fitnessStatus: 'FIT_WITH_RESTRICTIONS',
    restrictionUntil: '2026-11-30',
    rationale: 'SECRET rationale',
    rtwPlan: { status: 'ACTIVE', plannedStart: '2026-11-02', nextReview: '2026-11-16', phases: [{ from: '2026-11-02', to: '2026-11-15', hoursPerDay: 4, dutiesSummary: 'Office-based duties, no lifting', clinicalNote: 'SECRET' }] },
  },
  tasks: [
    { id: 'tk1', title: 'Send wage statement', dueDate: '2026-11-05', assigneeRole: 'ADJUSTER', status: 'OPEN', createdBy: 'claims1', createdAt: '2026-10-20T10:00:00Z' },
    { id: 'tk2', title: 'Arrange case conference', dueDate: '2026-11-07', assigneeRole: 'NURSE_CM', status: 'OPEN', createdBy: 'claims1', createdAt: '2026-10-20T10:00:00Z' },
  ],
  documents: [{ id: 'd1', documentNumber: 'DOC-2026-000777', type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate', signedAt: '2026-10-21T10:00:00Z', sha256: 'SECRET', shares: [{ target: 'INSURER' }] }],
  ...over,
});

export const preauth = (over: Record<string, unknown> = {}) => ({
  id: 'pa1',
  ref: 'PA-2026-000001',
  caseRef: 'CASE-2026-000010',
  displayName: 'Rami Ramp',
  payerCode: 'GULFTPA',
  serviceCode: 'IMAGING_MRI',
  providerCode: 'LDN_IMAGING',
  estimate: { amount: '850.00', currency: 'GBP' },
  status: 'SUBMITTED',
  submittedAt: '2026-10-28T09:00:00Z',
  slaDueAt: '2026-10-30T09:00:00Z',
  documentId: 'doc-pa',
  justification: 'SECRET justification',
  ...over,
});

/** EAP referral as listed for the partner (increment 5 §3.2) plus fields outside the partner data set. */
export const eapRaw = (over: Record<string, unknown> = {}) => ({
  id: 'e1',
  ref: 'EAP-2026-000001',
  displayName: 'Rami Ramp',
  contactPhone: '+44 7700 900123',
  preferredContact: 'phone',
  sessionsAuthorised: 6,
  urgency: 'PRIORITY',
  status: 'SENT',
  sentAt: '2026-10-20T09:00:00Z',
  // outside the partner data set – must be dropped
  caseId: 'sup-secret',
  concern: 'LOW_MOOD',
  risk: 'HIGH',
  screens: [{ instrument: 'PHQ9', score: 19 }],
  employeeId: 'E1004',
  ...over,
});
