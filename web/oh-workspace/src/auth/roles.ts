/**
 * UI role gating for the OH workspace. Convenience only – wz-core-app enforces every rule server-side.
 * Role names follow API-Contract-E01-E02 §1 and API-Contract-E03-E05 §1 (normalised: upper-case, no ROLE_ prefix).
 * Confidentiality: MANAGER, HR, HYGIENE_MANAGER never get a screen here; DPO gets the privacy area only
 * (no clinical read); CREW_SCHEDULING gets the status-only roster-feed screen only.
 * Increment 4 (API-Contract-E04-E08 §1, rule 3): CLAIMS_COORDINATOR, FINANCE, LEGAL_COUNSEL and PROCUREMENT get claims /
 * finance / legal / provider-network screens only – never a case, chart, referral, telehealth or medication screen.
 * PARTNER_PROVIDER and TPA_ADJUSTER use the partner portal.
 */

/** "OH roles" as defined in the contract. */
export const OH_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'CASE_MANAGER', 'PSYCH_CLINICIAN'] as const;
export const RECEPTION_ROLES = ['CLINIC_RECEPTION', 'FIRST_AIDER'] as const;
export const ADMIN_ROLES = ['OH_ADMIN', 'NURSING_HEAD'] as const;
export const SENIOR_CLINICIAN_ROLES = ['OH_PHYSICIAN', 'AME', 'CMO'] as const;
export const TRIAGE_WRITE_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'] as const;
export const FITNESS_WRITE_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'PSYCH_CLINICIAN'] as const;

/** "Clinicians" = OH roles minus CASE_MANAGER (E03-E05 §1). */
export const CLINICIAN_ROLES = ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD', 'PSYCH_CLINICIAN'] as const;
/** "Physicians" = OH_PHYSICIAN, AME, CMO. */
export const PHYSICIAN_ROLES = ['OH_PHYSICIAN', 'AME', 'CMO'] as const;

const OH = [...OH_ROLES];
const CLINICIANS = [...CLINICIAN_ROLES];
const PHYSICIANS = [...PHYSICIAN_ROLES];
/** Physicians + OH_CLINICIAN (orders, result worklist, fitness decisions on non-licensed standards, food-handler clearance). */
const PHYSICIANS_AND_NURSES = [...PHYSICIANS, 'OH_CLINICIAN'];
/** Increment-4 non-clinical roles (never read the OH clinical record). */
export const NON_CLINICAL_CLAIMS_ROLES = ['CLAIMS_COORDINATOR', 'FINANCE', 'LEGAL_COUNSEL', 'PROCUREMENT'] as const;

/** Every UI capability and the roles that unlock it. */
export const FEATURES = {
  // E02-F04 queues & case
  queues: OH,
  caseView: OH,
  employeeTimeline: OH,
  triageWrite: [...TRIAGE_WRITE_ROLES],
  recordabilityWrite: ['OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'],
  fitnessWrite: [...FITNESS_WRITE_ROLES],
  bulkReassign: ['NURSING_HEAD'],
  escalationDecide: [...SENIOR_CLINICIAN_ROLES],
  conferenceSchedule: ['CMO'],
  escalations: OH,
  handovers: OH,
  // E01 intake
  suggestions: OH,
  reconciliation: [...OH, 'HSE_OFFICER'],
  reconciliationExceptions: ['HSE_OFFICER', 'NURSING_HEAD'],
  incidentCases: [...OH, 'HSE_OFFICER'],
  dlq: ['OH_ADMIN', 'PLATFORM_ADMIN'],
  intakeDashboard: ['NURSING_HEAD', 'OH_ADMIN', 'CMO'],
  // E01-F03 reception / first aid
  reception: [...RECEPTION_ROLES, ...OH],
  encounterList: [...OH, 'CLINIC_RECEPTION'],
  encounterConvert: OH,
  // E02-F03 caseload & administration
  caseload: OH,
  caseloadRebalance: ['NURSING_HEAD'],
  admin: [...ADMIN_ROLES],
  adminWrite: [...ADMIN_ROLES],
  adminOnlyWrite: ['OH_ADMIN'],
  protocols: [...OH, 'OH_ADMIN'],
  protocolApprove: ['CMO'],
  triageAudit: ['NURSING_HEAD', 'CMO', 'OH_PHYSICIAN'],
  triageAuditSample: ['NURSING_HEAD'],
  // E03-F01 chart, E03-F02 encounters, E03-F03 terminology
  employeeChart: OH,
  chartWrite: CLINICIANS,
  breakGlass: CLINICIANS,
  encounters: OH,
  encounterWrite: CLINICIANS,
  mentalHealthEncounter: ['PSYCH_CLINICIAN'],
  encounterEnteredInErrorAny: ['CMO'],
  unsignedNotes: CLINICIANS,
  unsignedNotesAll: ['NURSING_HEAD', 'CMO'],
  // E03-F04 orders & results, E03-F05 CDS
  orderWrite: PHYSICIANS_AND_NURSES,
  deviceResults: OH,
  resultsWorklist: PHYSICIANS_AND_NURSES,
  cdsStatistics: ['CMO', 'NURSING_HEAD'],
  // E03-F06 documents
  documents: OH,
  documentSign: CLINICIANS,
  documentScan: [...OH, 'CLINIC_RECEPTION'],
  // E05-F01 … F07 (case tabs and work screens)
  fitnessDecide: PHYSICIANS_AND_NURSES,
  fitnessSignoff: ['AME', 'CMO'],
  activeRestrictions: OH,
  restrictionsDashboard: ['NURSING_HEAD', 'CMO'],
  rtwOverdue: OH,
  crewAssess: ['AME', 'CMO'],
  crewDashboard: ['CMO'],
  /** Status-only roster feed (contract §5.4 `/crew/roster-feed…`); CREW_SCHEDULING gets this screen and nothing else. */
  rosterReconciliation: ['CMO', 'OH_ADMIN', 'CREW_SCHEDULING'],
  foodHandlerClear: PHYSICIANS_AND_NURSES,
  ihrAssess: PHYSICIANS,
  ihrCmo: ['CMO'],
  // E18 master data & E03 configuration
  fitnessStandardsAdmin: ['OH_ADMIN', 'CMO'],
  restrictionCatalogue: ['OH_ADMIN', 'NURSING_HEAD'],
  jobDemandsAdmin: ['OH_ADMIN', 'NURSING_HEAD'],
  jobDemandsImport: ['OH_ADMIN'],
  encounterTemplates: OH,
  templateApprove: ['CMO'],
  terminologyAdmin: ['OH_ADMIN'],
  // E03-F08 privacy (DPO area)
  breakGlassReviews: ['DPO'],
  breakGlassReport: ['DPO', 'CMO'],
  disclosureLog: ['DPO'],
  retention: ['DPO', 'OH_ADMIN'],
  retentionWrite: ['DPO'],
  // E06 absence & sickness (API-Contract-E06-E07-E14 §3). Reception scans / validates certificates (no content shown).
  absenceQueue: OH,
  absenceLta: OH,
  absenceCertificates: [...OH, 'CLINIC_RECEPTION'],
  certificateVerify: OH,
  absenceAdvice: OH,
  adviceReportWrite: CLINICIANS,
  absenceRules: [...OH, 'OH_ADMIN'],
  absenceRulesWrite: ['OH_ADMIN'],
  absenceAnalytics: ['CMO', 'NURSING_HEAD', 'OH_ADMIN'],
  absenceAnalyticsExport: ['CMO', 'NURSING_HEAD'],
  absenceFeed: ['OH_ADMIN', 'NURSING_HEAD'],
  absenceFeedRun: ['OH_ADMIN'],
  absenceMismatches: ['OH_ADMIN'],
  // E07 medical surveillance (§4). HSE_OFFICER: exposure + compliance; AUDITOR: compliance / audit packs; CREW_LICENSING: crew status.
  surveillanceProgrammes: [...OH, 'OH_ADMIN'],
  programmeWrite: ['OH_ADMIN', 'CMO', 'NURSING_HEAD'],
  programmeActivate: ['CMO', 'OH_PHYSICIAN'],
  programmeRetire: ['CMO'],
  dueRecalculate: ['OH_ADMIN'],
  surveillanceDue: [...OH, 'CLINIC_SCHEDULER'],
  enrolmentComplete: CLINICIANS,
  enrolmentExempt: PHYSICIANS,
  surveillanceFollowUps: OH,
  followUpReview: PHYSICIANS,
  preEmployment: OH,
  preEmploymentOutcome: CLINICIANS,
  crewCertificates: ['AME', 'CMO', 'CREW_LICENSING'],
  crewExam: ['AME'],
  crewImport: ['AME'],
  crewAuthorityReport: ['AME', 'CMO'],
  immunisation: [...OH, 'OH_ADMIN'],
  immunisationDue: OH,
  immunisationAdminister: CLINICIANS,
  immunisationRequirementWrite: ['OH_ADMIN', 'NURSING_HEAD'],
  exposure: [...OH, 'HSE_OFFICER', 'OH_ADMIN'],
  segWrite: ['HSE_OFFICER', 'OH_ADMIN'],
  ihImport: ['HSE_OFFICER'],
  cosmicDose: ['AME', 'CMO'],
  cosmicDoseImport: ['AME'],
  surveillanceCompliance: [...OH, 'HSE_OFFICER', 'AUDITOR', 'OH_ADMIN'],
  complianceOverdue: OH,
  auditPacks: ['CMO', 'NURSING_HEAD', 'AUDITOR', 'HSE_OFFICER'],
  rai: OH,
  raiComplete: CLINICIANS,
  raiWaive: PHYSICIANS,
  // E14 scheduling & clinic operations (API-Contract-E06-E07-E14 §5). Schedulers / reception / biomedical: names + type only.
  schedulingDiary: [...CLINICIANS, 'CLINIC_SCHEDULER'],
  schedulingBook: [...OH, 'CLINIC_SCHEDULER'],
  schedulingAttendance: [...OH, 'CLINIC_SCHEDULER', 'CLINIC_RECEPTION'],
  telehealthLaunch: CLINICIANS,
  campaigns: ['CLINIC_SCHEDULER', 'NURSING_HEAD', 'OH_ADMIN'],
  campaignManage: ['CLINIC_SCHEDULER', 'NURSING_HEAD'],
  checkInKiosk: ['CLINIC_RECEPTION'],
  queueBoard: ['CLINIC_RECEPTION', ...CLINICIANS],
  queueCallNext: CLINICIANS,
  equipment: ['BIOMEDICAL', 'OH_ADMIN', ...CLINICIANS],
  equipmentWrite: ['BIOMEDICAL', 'OH_ADMIN'],
  calibrationWrite: ['BIOMEDICAL'],
  calibrationDue: ['BIOMEDICAL', 'OH_ADMIN', 'NURSING_HEAD'],
  coldChain: ['BIOMEDICAL', ...CLINICIANS],
  excursionAcknowledge: CLINICIANS,
  capacity: ['NURSING_HEAD', 'CMO', 'OH_ADMIN'],
  serviceCatalogue: ['CLINIC_SCHEDULER', 'OH_ADMIN', 'NURSING_HEAD', 'CMO'],
  appointmentTypesWrite: ['OH_ADMIN', 'NURSING_HEAD'],
  chargingUsage: ['OH_ADMIN', 'CMO'],
  siteTemplatesWrite: ['OH_ADMIN', 'NURSING_HEAD', 'CLINIC_SCHEDULER'],
  // E04 treatment coordination & partner referrals (API-Contract-E04-E08 §3). Case tabs use caseView (OH roles).
  treatmentWrite: CLINICIANS,
  medicationAdminister: CLINICIANS,
  referrals: OH,
  referralWrite: CLINICIANS,
  varianceAlerts: OH,
  treatmentKpis: ['CMO', 'NURSING_HEAD'],
  providers: [...OH, 'PROCUREMENT', 'CLAIMS_COORDINATOR'],
  providerEdit: ['PROCUREMENT'],
  providerContracts: ['PROCUREMENT'],
  providerScorecards: ['CMO', 'NURSING_HEAD', 'PROCUREMENT'],
  scorecardRecompute: ['PROCUREMENT', 'CMO'],
  networkGaps: ['PROCUREMENT', 'CMO', 'OH_ADMIN'],
  medications: OH,
  formularyWrite: ['NURSING_HEAD'],
  stockReceive: ['NURSING_HEAD', 'OH_CLINICIAN'],
  medicationUsage: ['NURSING_HEAD', 'CMO'],
  telehealth: OH,
  telehealthWrite: CLINICIANS,
  outcomeWrite: CLINICIANS,
  preauthCreate: [...CLINICIANS, 'CLAIMS_COORDINATOR'],
  preauthDecide: ['CLAIMS_COORDINATOR'],
  preauthList: ['CLAIMS_COORDINATOR'],
  invoiceWrite: ['CLAIMS_COORDINATOR', 'FINANCE'],
  treatmentCosts: ['FINANCE', 'CMO', 'CLAIMS_COORDINATOR'],
  // E08 claims coordination (§4)
  claims: ['CLAIMS_COORDINATOR'],
  claimDetail: ['CLAIMS_COORDINATOR', 'NURSING_HEAD', 'CMO', 'LEGAL_COUNSEL'],
  claimCreate: ['CLAIMS_COORDINATOR', 'CASE_MANAGER'],
  claimWrite: ['CLAIMS_COORDINATOR'],
  claimReports: ['CLAIMS_COORDINATOR'],
  claimCollaboration: ['CLAIMS_COORDINATOR', 'CASE_MANAGER'],
  claimMoney: ['CLAIMS_COORDINATOR', 'FINANCE', 'CMO'],
  claimCostLedger: ['CLAIMS_COORDINATOR'],
  disclosureSetWrite: ['CLAIMS_COORDINATOR', ...OH],
  disclosureRedact: OH,
  disclosureRevoke: ['CLAIMS_COORDINATOR', 'DPO'],
  claimCosts: ['FINANCE', 'CMO'],
  budgetWrite: ['FINANCE'],
  erpExports: ['FINANCE'],
  claimReconciliation: ['FINANCE', 'CLAIMS_COORDINATOR'],
  nurseAssignment: ['NURSING_HEAD', 'CLAIMS_COORDINATOR'],
  legal: ['LEGAL_COUNSEL'],
  legalWrite: ['LEGAL_COUNSEL'],
  legalRequestCreate: ['LEGAL_COUNSEL', 'CLAIMS_COORDINATOR'],
  legalReportWrite: PHYSICIANS,
  legalRequestsPhysician: PHYSICIANS,
  /** GET /legal-requests[/{id}] – full questions and report versions (claims coordinators see summaries only). */
  legalRequestRead: ['LEGAL_COUNSEL', ...PHYSICIANS],
  claimDisclosureReport: ['DPO'],
} satisfies Record<string, readonly string[]>;

export type Feature = keyof typeof FEATURES;

function norm(r: string): string {
  return r.trim().toUpperCase().replace(/^ROLE_/, '');
}

export function hasAnyRole(roles: readonly string[] | undefined, allowed: readonly string[]): boolean {
  if (!roles) return false;
  return roles.some((r) => allowed.includes(norm(r)));
}

export function can(roles: readonly string[] | undefined, feature: Feature): boolean {
  return hasAnyRole(roles, FEATURES[feature]);
}

export function isOhRole(roles: readonly string[] | undefined): boolean {
  return hasAnyRole(roles, OH);
}

/** Reception / first-aid staff without any OH role see only the reception screen. */
export function isReceptionOnly(roles: readonly string[] | undefined): boolean {
  return hasAnyRole(roles, RECEPTION_ROLES) && !isOhRole(roles);
}

/** Anyone with at least one screen in this workspace. */
export function hasWorkspaceAccess(roles: readonly string[] | undefined): boolean {
  return (Object.keys(FEATURES) as Feature[]).some((f) => can(roles, f));
}

export interface NavItem {
  path: string;
  label: string;
  feature: Feature;
  group: 'Work' | 'Record' | 'Fitness' | 'Intake' | 'Reception' | 'Team' | 'Configuration' | 'Privacy' | 'Clinic' | 'Absence' | 'Surveillance' | 'Claims' | 'Treatment';
}

export const NAV: readonly NavItem[] = [
  { path: '/queues', label: 'Work queues', feature: 'queues', group: 'Work' },
  { path: '/escalations', label: 'Escalations', feature: 'escalations', group: 'Work' },
  { path: '/handovers', label: 'Handovers', feature: 'handovers', group: 'Work' },
  { path: '/reception', label: 'Walk-in & first aid', feature: 'reception', group: 'Reception' },
  { path: '/intake/suggestions', label: 'Incident suggestions', feature: 'suggestions', group: 'Intake' },
  { path: '/intake/reconciliation', label: 'Reconciliation', feature: 'reconciliation', group: 'Intake' },
  { path: '/intake/incidents', label: 'Incident → cases', feature: 'incidentCases', group: 'Intake' },
  { path: '/intake/dashboard', label: 'Intake dashboard', feature: 'intakeDashboard', group: 'Intake' },
  { path: '/intake/dlq', label: 'Dead-letter queue', feature: 'dlq', group: 'Intake' },
  { path: '/caseload', label: 'Caseload', feature: 'caseload', group: 'Team' },
  { path: '/triage-audit', label: 'Triage audit', feature: 'triageAudit', group: 'Team' },
  { path: '/cds/statistics', label: 'CDS statistics', feature: 'cdsStatistics', group: 'Team' },
  { path: '/employees', label: 'Employee chart', feature: 'employeeChart', group: 'Record' },
  { path: '/encounters/unsigned', label: 'Unsigned notes', feature: 'unsignedNotes', group: 'Record' },
  { path: '/results/worklist', label: 'Results worklist', feature: 'resultsWorklist', group: 'Record' },
  { path: '/documents/scan', label: 'Scan documents', feature: 'documentScan', group: 'Record' },
  { path: '/fitness/signoff', label: 'Fitness sign-off', feature: 'fitnessSignoff', group: 'Fitness' },
  { path: '/restrictions', label: 'Active restrictions', feature: 'activeRestrictions', group: 'Fitness' },
  { path: '/rtw/overdue', label: 'RTW overdue reviews', feature: 'rtwOverdue', group: 'Fitness' },
  { path: '/crew/dashboard', label: 'Crew dashboard', feature: 'crewDashboard', group: 'Fitness' },
  { path: '/crew/roster-feed', label: 'Roster feed', feature: 'rosterReconciliation', group: 'Fitness' },
  // E06 absence & sickness
  { path: '/absence/queue', label: 'Early intervention', feature: 'absenceQueue', group: 'Absence' },
  { path: '/absence/lta', label: 'Long-term absence', feature: 'absenceLta', group: 'Absence' },
  { path: '/absence/certificates', label: 'Medical certificates', feature: 'absenceCertificates', group: 'Absence' },
  { path: '/absence/advice', label: 'Advice requests', feature: 'absenceAdvice', group: 'Absence' },
  { path: '/absence/analytics', label: 'Absence analytics', feature: 'absenceAnalytics', group: 'Absence' },
  { path: '/absence/rules', label: 'Trigger rules', feature: 'absenceRules', group: 'Absence' },
  { path: '/absence/feed', label: 'Absence feed', feature: 'absenceFeed', group: 'Absence' },
  // E07 medical surveillance
  { path: '/surveillance/due', label: 'Due list', feature: 'surveillanceDue', group: 'Surveillance' },
  { path: '/surveillance/follow-ups', label: 'Follow-ups', feature: 'surveillanceFollowUps', group: 'Surveillance' },
  { path: '/surveillance/rai', label: 'Return after illness', feature: 'rai', group: 'Surveillance' },
  { path: '/surveillance/pre-employment', label: 'Pre-employment', feature: 'preEmployment', group: 'Surveillance' },
  { path: '/surveillance/crew', label: 'Crew medicals', feature: 'crewCertificates', group: 'Surveillance' },
  { path: '/surveillance/immunisation', label: 'Immunisation', feature: 'immunisation', group: 'Surveillance' },
  { path: '/surveillance/exposure', label: 'Exposure registers', feature: 'exposure', group: 'Surveillance' },
  { path: '/surveillance/compliance', label: 'Compliance & audit', feature: 'surveillanceCompliance', group: 'Surveillance' },
  { path: '/surveillance/programmes', label: 'Programmes', feature: 'surveillanceProgrammes', group: 'Surveillance' },
  { path: '/admin/protocols', label: 'Triage protocols', feature: 'protocols', group: 'Configuration' },
  { path: '/admin/assignment-rules', label: 'Assignment rules', feature: 'admin', group: 'Configuration' },
  { path: '/admin/sla', label: 'SLA policies', feature: 'admin', group: 'Configuration' },
  { path: '/admin/intake-rules', label: 'Intake rules', feature: 'admin', group: 'Configuration' },
  { path: '/admin/notifications', label: 'Notification matrix', feature: 'admin', group: 'Configuration' },
  { path: '/admin/recordability-mapping', label: 'Recordability mapping', feature: 'admin', group: 'Configuration' },
  { path: '/admin/fitness-standards', label: 'Fitness standards', feature: 'fitnessStandardsAdmin', group: 'Configuration' },
  { path: '/admin/restrictions', label: 'Restriction catalogue', feature: 'restrictionCatalogue', group: 'Configuration' },
  { path: '/admin/job-demands', label: 'Job-demand profiles', feature: 'jobDemandsAdmin', group: 'Configuration' },
  { path: '/admin/encounter-templates', label: 'Encounter templates', feature: 'encounterTemplates', group: 'Configuration' },
  { path: '/admin/terminology', label: 'Terminology releases', feature: 'terminologyAdmin', group: 'Configuration' },
  { path: '/dpo/break-glass', label: 'Break-glass reviews', feature: 'breakGlassReviews', group: 'Privacy' },
  { path: '/dpo/break-glass-report', label: 'Break-glass report', feature: 'breakGlassReport', group: 'Privacy' },
  { path: '/dpo/disclosures', label: 'Disclosure log', feature: 'disclosureLog', group: 'Privacy' },
  { path: '/dpo/retention', label: 'Retention & legal hold', feature: 'retention', group: 'Privacy' },
  // E14 scheduling & clinic operations
  { path: '/scheduling/diary', label: 'Clinic diary', feature: 'schedulingDiary', group: 'Clinic' },
  { path: '/scheduling/campaigns', label: 'Campaigns', feature: 'campaigns', group: 'Clinic' },
  { path: '/scheduling/reception', label: 'Check-in kiosk', feature: 'checkInKiosk', group: 'Clinic' },
  { path: '/scheduling/queue', label: 'Queue board', feature: 'queueBoard', group: 'Clinic' },
  { path: '/scheduling/equipment', label: 'Equipment & cold chain', feature: 'equipment', group: 'Clinic' },
  { path: '/scheduling/capacity', label: 'Capacity & utilisation', feature: 'capacity', group: 'Clinic' },
  { path: '/scheduling/catalogue', label: 'Service catalogue', feature: 'serviceCatalogue', group: 'Clinic' },
  // Increment 4 – E08 claims (listed before treatment so claims staff land on their worklist)
  { path: '/claims', label: 'Claims', feature: 'claims', group: 'Claims' },
  { path: '/claims/reports', label: 'Report deadlines', feature: 'claimReports', group: 'Claims' },
  { path: '/claims/preauthorisations', label: 'Pre-authorisations', feature: 'preauthList', group: 'Claims' },
  { path: '/claims/costs', label: 'Claim & treatment costs', feature: 'claimCosts', group: 'Claims' },
  { path: '/claims/nurse-assignment', label: 'Nurse assignment', feature: 'nurseAssignment', group: 'Claims' },
  { path: '/legal', label: 'Legal & litigation', feature: 'legal', group: 'Claims' },
  { path: '/legal/requests', label: 'Medico-legal requests', feature: 'legalRequestsPhysician', group: 'Claims' },
  { path: '/claims/disclosures', label: 'Claim disclosures', feature: 'claimDisclosureReport', group: 'Privacy' },
  // Increment 4 – E04 treatment coordination
  { path: '/referrals', label: 'Referrals', feature: 'referrals', group: 'Treatment' },
  { path: '/treatment/variance', label: 'Recovery variance', feature: 'varianceAlerts', group: 'Treatment' },
  { path: '/telehealth', label: 'Telehealth', feature: 'telehealth', group: 'Treatment' },
  { path: '/treatment/medications', label: 'Clinic medications', feature: 'medications', group: 'Treatment' },
  { path: '/providers', label: 'Provider network', feature: 'providers', group: 'Treatment' },
  { path: '/providers/scorecards', label: 'Provider scorecards', feature: 'providerScorecards', group: 'Treatment' },
  { path: '/providers/gaps', label: 'Network gaps', feature: 'networkGaps', group: 'Treatment' },
  { path: '/treatment/kpis', label: 'Treatment KPIs', feature: 'treatmentKpis', group: 'Treatment' },
];

export function navFor(roles: readonly string[] | undefined): NavItem[] {
  return NAV.filter((n) => can(roles, n.feature));
}

/** Landing route: reception-only staff land on reception, everyone else on the first screen they may use. */
export function homeRoute(roles: readonly string[] | undefined): string {
  if (isReceptionOnly(roles)) return '/reception';
  return navFor(roles)[0]?.path ?? '/restricted';
}

/** Feature required to open a route (undefined = no such route). */
export function featureForRoute(route: string): Feature | undefined {
  const nav = NAV.find((n) => n.path === route);
  if (nav) return nav.feature;
  if (/^\/cases\/[^/]+(\/[a-z-]+)?$/.test(route)) return 'caseView';
  if (/^\/employees\/[^/]+\/chart(\/[a-z-]+)?$/.test(route)) return 'employeeChart';
  if (/^\/encounters\/[^/]+$/.test(route)) return 'encounters';
  if (/^\/employees\/[^/]+\/timeline$/.test(route)) return 'employeeTimeline';
  if (/^\/intake\/incidents(\/[^/]+)?$/.test(route)) return 'incidentCases';
  if (/^\/scheduling\/campaigns\/[^/]+$/.test(route)) return 'campaigns';
  if (/^\/absence\/lta\/[^/]+$/.test(route)) return 'absenceLta';
  if (/^\/surveillance\/programmes\/[^/]+$/.test(route)) return 'surveillanceProgrammes';
  if (/^\/referrals\/[^/]+$/.test(route)) return 'referrals';
  if (/^\/providers\/[^/]+$/.test(route)) return 'providers';
  if (/^\/claims\/[^/]+(\/[a-z-]+)?$/.test(route)) return 'claimDetail';
  return undefined;
}
