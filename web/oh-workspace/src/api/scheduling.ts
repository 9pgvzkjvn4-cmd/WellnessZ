/**
 * E14 – Scheduling, clinic operations & resource management (API-Contract-E06-E07-E14 §5, §2.2 clinic sites).
 * Confidentiality: schedulers / reception / biomedical staff get names + appointment type only – none of these
 * payloads carries clinical content, and the UI never puts personal data into URLs beyond ids the contract requires.
 */
import type { AuthClient } from '../auth/AuthClient';
import { bffLink, buildQuery, seg } from './links';

// ---------------------------------------------------------------- enums (§5)
export const APPOINTMENT_STATUSES = ['BOOKED', 'CHECKED_IN', 'IN_PROGRESS', 'ATTENDED', 'NO_SHOW', 'CANCELLED'] as const;
export type AppointmentStatus = (typeof APPOINTMENT_STATUSES)[number];
export const RESOURCE_TYPES = ['CLINICIAN', 'ROOM', 'DEVICE'] as const;
export type ResourceType = (typeof RESOURCE_TYPES)[number];
export const ROOM_TYPES = ['CONSULT', 'AUDIO_BOOTH', 'TREATMENT', 'VACCINATION', 'TELEHEALTH'] as const;
export type RoomType = (typeof ROOM_TYPES)[number];
export const CAMPAIGN_TYPES = ['SURVEILLANCE', 'VACCINATION', 'MOBILE_CLINIC'] as const;
export type CampaignType = (typeof CAMPAIGN_TYPES)[number];
export const CAMPAIGN_STATUSES = ['PLANNED', 'OPEN', 'IN_PROGRESS', 'CLOSED'] as const;
export type CampaignStatus = (typeof CAMPAIGN_STATUSES)[number];
export const EQUIPMENT_CATEGORIES = ['AUDIOMETER', 'SPIROMETER', 'ECG', 'VISION_SCREENER', 'VACCINE_FRIDGE', 'OTHER'] as const;
export type EquipmentCategory = (typeof EQUIPMENT_CATEGORIES)[number];
export const EQUIPMENT_STATUSES = ['IN_SERVICE', 'OUT_OF_SERVICE', 'RETIRED'] as const;
export type EquipmentStatus = (typeof EQUIPMENT_STATUSES)[number];
export const CALIBRATION_STATES = ['VALID', 'DUE_SOON', 'OVERDUE'] as const;
export type CalibrationState = (typeof CALIBRATION_STATES)[number];
export const EXCURSION_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'CLOSED'] as const;
export type ExcursionStatus = (typeof EXCURSION_STATUSES)[number];
export const WAITLIST_STATUSES = ['WAITING', 'OFFERED', 'BOOKED', 'CANCELLED'] as const;
export type WaitlistStatus = (typeof WAITLIST_STATUSES)[number];
export const SOURCE_TYPES = ['SURVEILLANCE_INVITATION', 'PRE_EMPLOYMENT', 'IMMUNISATION', 'CREW_RENEWAL', 'ABSENCE_CONTACT', 'RTW_REVIEW', 'MANUAL'] as const;
export type SourceType = (typeof SOURCE_TYPES)[number];
export const QUEUE_ENTRY_STATUSES = ['WAITING', 'CALLED', 'IN_PROGRESS'] as const;
export type QueueEntryStatus = (typeof QUEUE_ENTRY_STATUSES)[number];
export const SITE_TYPES = ['HQ_MEDICAL_CENTRE', 'AIRSIDE_POST', 'CATERING_ROOM', 'CARGO_ENG_CLINIC', 'OUTSTATION', 'MOBILE'] as const;

// ---------------------------------------------------------------- master data (§2.2)
export interface ClinicSite {
  code: string;
  name: string;
  siteType?: string;
  locationCode?: string;
  timeZone?: string;
  address?: string;
  active?: boolean;
}

// ---------------------------------------------------------------- §5.1 types, catalogue, resources, templates
export interface AppointmentType {
  code: string;
  name: string;
  durationMinutes: number;
  clinicianRoles?: string[];
  roomType?: RoomType | null;
  deviceCategory?: EquipmentCategory | null;
  telehealth?: boolean;
  selfBookable?: boolean;
  chargeCode?: string | null;
  internalPrice?: number | null;
  active?: boolean;
}

export interface Resource {
  id: string;
  siteCode: string;
  type: ResourceType;
  code: string;
  name: string;
  username?: string | null;
  roomType?: RoomType | null;
  equipmentId?: string | null;
  active?: boolean;
}

export interface DiaryTemplate {
  weekday: number;
  start: string;
  end: string;
  clinicianUsername: string;
  roomCode?: string | null;
  appointmentTypes: string[];
  telehealth: boolean;
}

export interface ChargingUsage {
  subsidiaryCode: string;
  chargeCode: string;
  service: string;
  appointments: number;
  amount: number;
}

// ---------------------------------------------------------------- §5.2 diary & booking
export interface DiarySession {
  clinicianUsername: string;
  clinicianName?: string;
  roomCode?: string | null;
  /** "08:00" (template style) or an ISO timestamp – both are accepted by the diary layout. */
  start: string;
  end: string;
  appointmentTypes?: string[];
  telehealth?: boolean;
}

export interface Appointment {
  id: string;
  employeeId: string;
  displayName?: string;
  appointmentType: string;
  appointmentTypeName?: string;
  siteCode: string;
  startsAt: string;
  endsAt: string;
  status: AppointmentStatus;
  clinicianUsername?: string | null;
  roomCode?: string | null;
  deviceId?: string | null;
  campaignId?: string | null;
  caseId?: string | null;
  sourceType?: SourceType | null;
  ticket?: string | null;
  checkedInAt?: string | null;
  calledAt?: string | null;
  telehealth?: boolean;
  version: number;
}

export interface Diary {
  siteCode: string;
  date: string;
  sessions: DiarySession[];
  appointments: Appointment[];
}

export interface FreeSlot {
  startsAt: string;
  endsAt: string;
  clinicianUsername: string;
  roomCode?: string | null;
  deviceId?: string | null;
}

export interface BookingRequest {
  employeeId: string;
  appointmentType: string;
  siteCode: string;
  startsAt: string;
  clinicianUsername?: string;
  roomCode?: string;
  deviceId?: string;
  caseId?: string;
  adminNote?: string;
  sourceType?: SourceType;
  sourceRef?: string;
}

export interface MoveRequest {
  startsAt: string;
  clinicianUsername?: string;
  roomCode?: string;
  version: number;
}

export interface TelehealthLink {
  joinUrl: string;
  documentationLink?: string;
}

export interface WaitlistEntry {
  id?: string;
  employeeId: string;
  displayName?: string;
  appointmentType: string;
  siteCode: string;
  earliest: string;
  latest: string;
  priority: number;
  status?: WaitlistStatus;
  createdAt?: string;
}

// ---------------------------------------------------------------- §5.3 campaigns
export interface CampaignInput {
  name: string;
  campaignType: CampaignType;
  siteCode: string;
  appointmentType: string;
  days: string[];
  dayStart: string;
  dayEnd: string;
  slotMinutes: number;
  stations: number;
  deviceKit: string[];
  staff: string[];
  targetCount: number;
}

export interface CampaignPerformance {
  slots: number;
  booked: number;
  attended: number;
  noShow: number;
  utilisationPct: number;
  attendancePct: number;
}

export interface Campaign extends CampaignInput {
  id: string;
  status: CampaignStatus;
  createdAt?: string;
  createdBy?: string;
  performance?: CampaignPerformance;
}

// ---------------------------------------------------------------- §5.4 check-in & queue
export interface CheckInRequest {
  siteCode: string;
  badgeId?: string;
  employeeId?: string;
  appointmentId?: string;
}

export interface CheckInTicket {
  appointmentId: string;
  ticket: string;
  appointmentTypeName?: string;
  position?: number;
  estimatedWaitMinutes?: number;
}

export interface QueueEntry {
  ticket: string;
  appointmentTypeName?: string;
  status: QueueEntryStatus;
  checkedInAt?: string;
  waitMinutes?: number;
  roomCode?: string | null;
}

export interface QueueBoard {
  siteCode: string;
  averageWaitMinutesToday?: number;
  entries: QueueEntry[];
}

export interface CallNextResult {
  appointmentId: string;
  ticket: string;
  employeeId: string;
  displayName?: string;
  appointmentType?: string;
  caseId?: string | null;
  encounterLink?: string;
}

// ---------------------------------------------------------------- §5.5 equipment & cold chain
export interface Equipment {
  id: string;
  assetTag: string;
  deviceId?: string | null;
  category: EquipmentCategory;
  model?: string;
  serialNumber?: string;
  siteCode: string;
  status: EquipmentStatus;
  calibrationIntervalMonths?: number | null;
  lastCalibratedOn?: string | null;
  calibrationDueOn?: string | null;
  calibrationState?: CalibrationState | null;
  lastCertificateNumber?: string | null;
  minTempC?: number | null;
  maxTempC?: number | null;
  lastReading?: { temperatureC: number; recordedAt: string } | null;
  openExcursions?: number;
}

export type EquipmentInput = Pick<Equipment, 'assetTag' | 'category' | 'siteCode'> &
  Partial<Pick<Equipment, 'deviceId' | 'model' | 'serialNumber' | 'calibrationIntervalMonths' | 'lastCalibratedOn' | 'minTempC' | 'maxTempC' | 'status'>>;

export interface CalibrationInput {
  performedOn: string;
  vendor: string;
  certificateNumber: string;
  result: 'PASS' | 'FAIL';
  nextDueOn?: string;
  documentBase64?: string;
}

export interface CalibrationRecord extends CalibrationInput {
  id?: string;
  recordedBy?: string;
  recordedAt?: string;
}

export interface TemperatureReading {
  recordedAt: string;
  temperatureC: number;
  source: 'MANUAL' | 'IOT';
  excursion: boolean;
}

export interface Excursion {
  id: string;
  equipmentId: string;
  assetTag?: string;
  siteCode?: string;
  startedAt: string;
  endedAt?: string | null;
  minC?: number | null;
  maxC?: number | null;
  status: ExcursionStatus;
  action?: string | null;
  acknowledgedBy?: string | null;
}

// ---------------------------------------------------------------- §5.6 capacity
export interface CapacityRow {
  siteCode: string;
  siteName?: string;
  month: string;
  capacityAppointments: number;
  bookedAppointments: number;
  forecastDemand: number;
  demandBySource?: Record<string, number>;
  gap?: number;
  utilisationPct?: number;
}

export interface WhatIfRequest {
  siteCode: string;
  extraClinicianSessionsPerWeek: number;
  extraCampaignDays: number;
  from: string;
  to: string;
}

export interface UtilisationRow {
  key: string;
  label: string;
  sessionsMinutes: number;
  bookedMinutes: number;
  utilisationPct: number;
  noShowRatePct?: number;
  averageWaitMinutes?: number;
}

// ---------------------------------------------------------------- client
export function schedulingApi(c: AuthClient) {
  return {
    clinicSites: () => c.api<ClinicSite[]>('/masterdata/clinic-sites'),

    appointmentTypes: () => c.api<AppointmentType[]>('/scheduling/appointment-types'),
    saveAppointmentType: (t: AppointmentType) => c.api<AppointmentType>(`/scheduling/appointment-types/${seg(t.code)}`, { method: 'PUT', body: t }),
    serviceCatalogue: () => c.api<AppointmentType[]>('/scheduling/service-catalogue'),
    resources: (siteCode: string) => c.api<Resource[]>(`/scheduling/sites/${seg(siteCode)}/resources`),
    templates: (siteCode: string) => c.api<DiaryTemplate[]>(`/scheduling/sites/${seg(siteCode)}/templates`),
    saveTemplates: (siteCode: string, rows: DiaryTemplate[]) => c.api<DiaryTemplate[]>(`/scheduling/sites/${seg(siteCode)}/templates`, { method: 'PUT', body: rows }),
    chargingUsage: (month: string) => c.api<ChargingUsage[]>(`/scheduling/charging/usage${buildQuery({ month })}`),

    diary: (q: { siteCode: string; date: string; clinician?: string }) => c.api<Diary>(`/scheduling/diary${buildQuery(q)}`),
    slots: (q: { type: string; siteCode: string; from: string; to: string; clinician?: string }) => c.api<FreeSlot[]>(`/scheduling/slots${buildQuery(q)}`),
    appointments: (q: { siteCode?: string; date?: string; employeeId?: string; status?: string }) => c.api<Appointment[]>(`/scheduling/appointments${buildQuery(q)}`),
    book: (b: BookingRequest) => c.api<Appointment>('/scheduling/appointments', { method: 'POST', body: b }),
    move: (id: string, b: MoveRequest) => c.api<Appointment>(`/scheduling/appointments/${seg(id)}/move`, { method: 'PUT', body: b }),
    cancel: (id: string, reason: string) => c.api<Appointment>(`/scheduling/appointments/${seg(id)}/cancel`, { method: 'POST', body: { reason } }),
    attendance: (id: string, status: 'ATTENDED' | 'NO_SHOW') => c.api<Appointment>(`/scheduling/appointments/${seg(id)}/attendance`, { method: 'POST', body: { status } }),
    telehealth: (id: string) => c.api<TelehealthLink>(`/scheduling/appointments/${seg(id)}/telehealth`, { method: 'POST', body: {} }),
    start: (id: string) => c.api<Appointment>(`/scheduling/appointments/${seg(id)}/start`, { method: 'POST', body: {} }),
    complete: (id: string) => c.api<Appointment>(`/scheduling/appointments/${seg(id)}/complete`, { method: 'POST', body: {} }),
    waitlist: () => c.api<WaitlistEntry[]>('/scheduling/waitlist'),
    addToWaitlist: (w: Omit<WaitlistEntry, 'id' | 'status' | 'displayName' | 'createdAt'>) => c.api<WaitlistEntry>('/scheduling/waitlist', { method: 'POST', body: w }),

    campaigns: () => c.api<Campaign[]>('/scheduling/campaigns'),
    campaign: (id: string) => c.api<Campaign>(`/scheduling/campaigns/${seg(id)}`),
    createCampaign: (b: CampaignInput) => c.api<Campaign>('/scheduling/campaigns', { method: 'POST', body: b }),
    generateSlots: (id: string) => c.api<{ slots: number }>(`/scheduling/campaigns/${seg(id)}/generate-slots`, { method: 'POST', body: {} }),
    bulkBook: (id: string, employeeIds: string[]) => c.api<{ booked: number; waitlisted: number }>(`/scheduling/campaigns/${seg(id)}/book`, { method: 'POST', body: { employeeIds } }),
    campaignStatus: (id: string, status: Exclude<CampaignStatus, 'PLANNED'>) => c.api<Campaign>(`/scheduling/campaigns/${seg(id)}/status`, { method: 'POST', body: { status } }),
    campaignAttendance: (id: string, rows: { appointmentId: string; status: 'ATTENDED' | 'NO_SHOW' }[]) =>
      c.api<unknown>(`/scheduling/campaigns/${seg(id)}/attendance`, { method: 'POST', body: rows }),

    checkIn: (b: CheckInRequest) => c.api<CheckInTicket>('/scheduling/check-in', { method: 'POST', body: b }),
    queue: (siteCode: string) => c.api<QueueBoard>(`/scheduling/queue${buildQuery({ siteCode })}`),
    callNext: (siteCode: string, roomCode?: string) => c.api<CallNextResult>('/scheduling/queue/call-next', { method: 'POST', body: roomCode ? { siteCode, roomCode } : { siteCode } }),

    equipment: (q: { siteCode?: string; category?: string } = {}) => c.api<Equipment[]>(`/scheduling/equipment${buildQuery(q)}`),
    registerEquipment: (b: EquipmentInput) => c.api<Equipment>('/scheduling/equipment', { method: 'POST', body: b }),
    updateEquipment: (id: string, b: EquipmentInput) => c.api<Equipment>(`/scheduling/equipment/${seg(id)}`, { method: 'PUT', body: b }),
    calibrations: (id: string) => c.api<CalibrationRecord[]>(`/scheduling/equipment/${seg(id)}/calibrations`),
    recordCalibration: (id: string, b: CalibrationInput) => c.api<CalibrationRecord>(`/scheduling/equipment/${seg(id)}/calibrations`, { method: 'POST', body: b }),
    equipmentStatus: (id: string, status: EquipmentStatus, reason: string) => c.api<Equipment>(`/scheduling/equipment/${seg(id)}/status`, { method: 'POST', body: { status, reason } }),
    calibrationDue: (withinDays = 30) => c.api<Equipment[]>(`/scheduling/equipment/calibration-due${buildQuery({ withinDays })}`),
    temperatures: (id: string, q: { from?: string; to?: string } = {}) => c.api<TemperatureReading[]>(`/scheduling/equipment/${seg(id)}/temperatures${buildQuery(q)}`),
    recordTemperature: (id: string, temperatureC: number, recordedAt?: string) =>
      c.api<TemperatureReading>(`/scheduling/equipment/${seg(id)}/temperatures`, { method: 'POST', body: recordedAt ? { temperatureC, recordedAt } : { temperatureC } }),
    excursions: (status?: ExcursionStatus) => c.api<Excursion[]>(`/scheduling/excursions${buildQuery({ status })}`),
    acknowledgeExcursion: (id: string, action: string) => c.api<Excursion>(`/scheduling/excursions/${seg(id)}/acknowledge`, { method: 'POST', body: { action } }),
    closeExcursion: (id: string) => c.api<Excursion>(`/scheduling/excursions/${seg(id)}/close`, { method: 'POST', body: {} }),

    capacity: (q: { from: string; to: string; siteCode?: string }) => c.api<CapacityRow[]>(`/scheduling/capacity${buildQuery(q)}`),
    whatIf: (b: WhatIfRequest) => c.api<CapacityRow[]>('/scheduling/capacity/what-if', { method: 'POST', body: b }),
    utilisation: (q: { from: string; to: string; groupBy: 'SITE' | 'CLINICIAN' }) => c.api<UtilisationRow[]>(`/scheduling/utilisation${buildQuery(q)}`),
  };
}

export type SchedulingApi = ReturnType<typeof schedulingApi>;

/** Finance export (`text/csv`) – plain browser download through the BFF. */
export const chargingExportUrl = (month: string): string => bffLink(`/scheduling/charging/export${buildQuery({ month })}`);
