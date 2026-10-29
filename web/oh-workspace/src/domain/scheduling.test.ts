import { describe, expect, it } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import {
  addDays,
  addMonths,
  calibrationState,
  calibrationView,
  campaignSlotCount,
  capacityGap,
  checkInBody,
  classifyMoveError,
  dateRange,
  decodeAppointmentDrag,
  diaryWindow,
  encodeAppointmentDrag,
  encounterHandOff,
  formatWait,
  hhmm,
  layoutLanes,
  minutesOfDay,
  monthBounds,
  outOfRange,
  overlaps,
  parseEmployeeIds,
  parseTemperature,
  snapMinutes,
  utilisationTone,
  validateTemplates,
  weekDays,
  weekStart,
  zonedDay,
  zonedToUtcIso,
} from './scheduling';

describe('time-zone maths', () => {
  it('reads minutes of day in the site zone and accepts HH:MM', () => {
    expect(minutesOfDay('08:30')).toBe(510);
    expect(minutesOfDay('2026-07-01T07:30:00Z', 'Europe/London')).toBe(8 * 60 + 30); // BST
    expect(minutesOfDay('2026-12-01T07:30:00Z', 'Europe/London')).toBe(7 * 60 + 30); // GMT
    expect(minutesOfDay('2026-12-01T07:30:00Z', 'Asia/Dubai')).toBe(11 * 60 + 30);
    expect(hhmm('2026-12-01T07:05:00Z', 'UTC')).toBe('07:05');
    expect(zonedDay('2026-12-01T22:30:00Z', 'Asia/Dubai')).toBe('2026-12-02');
  });

  it('converts a wall-clock time in a zone back to UTC (incl. DST)', () => {
    expect(zonedToUtcIso('2026-07-01', 9 * 60, 'Europe/London')).toBe('2026-07-01T08:00:00.000Z');
    expect(zonedToUtcIso('2026-12-01', 9 * 60, 'Europe/London')).toBe('2026-12-01T09:00:00.000Z');
    expect(zonedToUtcIso('2026-12-01', 9 * 60, 'Asia/Dubai')).toBe('2026-12-01T05:00:00.000Z');
  });

  it('does calendar arithmetic', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(weekStart('2026-10-29')).toBe('2026-10-26'); // Thursday → Monday
    expect(weekStart('2026-11-01')).toBe('2026-10-26'); // Sunday
    expect(weekDays('2026-10-29')).toHaveLength(7);
    expect(monthBounds('2026-02')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(addMonths('2026-11', 3)).toBe('2027-02');
    expect(dateRange('2026-10-30', '2026-11-03', true)).toEqual(['2026-10-30', '2026-11-02', '2026-11-03']);
    expect(dateRange('2026-11-03', '2026-11-01')).toEqual([]);
  });
});

describe('diary layout', () => {
  it('puts overlapping appointments side by side and keeps a cluster width', () => {
    const l = layoutLanes([
      { id: 'a', start: 540, end: 570 },
      { id: 'b', start: 550, end: 580 },
      { id: 'c', start: 575, end: 600 },
      { id: 'd', start: 600, end: 620 },
    ]);
    expect(l.get('a')).toEqual({ id: 'a', lane: 0, lanes: 2 });
    expect(l.get('b')).toEqual({ id: 'b', lane: 1, lanes: 2 });
    expect(l.get('c')).toEqual({ id: 'c', lane: 0, lanes: 2 }); // reuses lane 0, same cluster as b
    expect(l.get('d')).toEqual({ id: 'd', lane: 0, lanes: 1 }); // touching end = no overlap
  });

  it('handles three-way overlap and zero-length items', () => {
    const l = layoutLanes([
      { id: 'x', start: 0, end: 60 },
      { id: 'y', start: 10, end: 20 },
      { id: 'z', start: 15, end: 15 },
    ]);
    expect([...l.values()].map((v) => v.lanes)).toEqual([3, 3, 3]);
    expect(new Set([...l.values()].map((v) => v.lane)).size).toBe(3);
  });

  it('overlap, window and snapping', () => {
    expect(overlaps({ start: 0, end: 10 }, { start: 10, end: 20 })).toBe(false);
    expect(overlaps({ start: 0, end: 11 }, { start: 10, end: 20 })).toBe(true);
    expect(diaryWindow([])).toEqual([480, 1020]);
    expect(diaryWindow([{ start: 450, end: 1090 }])).toEqual([420, 1140]);
    expect(snapMinutes(543, 5)).toBe(545);
    expect(snapMinutes(-20, 5, 480, 1020)).toBe(480);
    expect(snapMinutes(2000, 15, 480, 1020)).toBe(1005);
  });
});

describe('drag payload and move errors', () => {
  it('round-trips the drag payload and rejects junk', () => {
    expect(decodeAppointmentDrag(encodeAppointmentDrag({ id: 'a1', version: 3, grabOffset: 10 }))).toEqual({ id: 'a1', version: 3, grabOffset: 10 });
    expect(decodeAppointmentDrag('{"id":1}')).toBeUndefined();
    expect(decodeAppointmentDrag('nope')).toBeUndefined();
    expect(decodeAppointmentDrag(null)).toBeUndefined();
  });

  it('distinguishes stale (optimistic lock) from resource conflicts', () => {
    expect(classifyMoveError(new ApiError(409, { code: 'stale' })).kind).toBe('stale');
    const c = classifyMoveError(new ApiError(409, { code: 'resource_conflict', detail: 'Room AUD-1 is booked' }));
    expect(c.kind).toBe('conflict');
    expect(c.message).toContain('Room AUD-1 is booked');
    expect(classifyMoveError(new ApiError(422, { code: 'device_out_of_calibration', detail: 'Device overdue' })).kind).toBe('rule');
    expect(classifyMoveError(new Error('x')).kind).toBe('other');
  });
});

describe('calibration state badge', () => {
  const today = '2026-10-29';
  it('derives VALID / DUE_SOON / OVERDUE with a 30-day window; server value wins', () => {
    expect(calibrationState('2026-10-28', today)).toBe('OVERDUE');
    expect(calibrationState('2026-10-29', today)).toBe('DUE_SOON');
    expect(calibrationState('2026-11-28', today)).toBe('DUE_SOON');
    expect(calibrationState('2026-11-29', today)).toBe('VALID');
    expect(calibrationState(undefined, today)).toBe('UNKNOWN');
    expect(calibrationState('2020-01-01', today, 'VALID')).toBe('VALID');
  });

  it('never relies on colour alone', () => {
    expect(calibrationView('OVERDUE')).toEqual({ state: 'OVERDUE', label: 'Overdue', symbol: '■', tone: 'red' });
    expect(calibrationView('DUE_SOON').symbol).toBe('▲');
  });
});

describe('cold chain', () => {
  it('uses 2–8 °C by default', () => {
    expect(outOfRange(1.9)).toBe(true);
    expect(outOfRange(8)).toBe(false);
    expect(outOfRange(8.1)).toBe(true);
    expect(outOfRange(9, 2, 10)).toBe(false);
  });
  it('parses temperatures', () => {
    expect(parseTemperature('4,5')).toBe(4.5);
    expect(parseTemperature('-2')).toBe(-2);
    expect(parseTemperature('4.55')).toBeUndefined();
    expect(parseTemperature('abc')).toBeUndefined();
    expect(parseTemperature('99')).toBeUndefined();
  });
});

describe('queue helpers', () => {
  it('formats wait times', () => {
    expect(formatWait(undefined)).toBe('–');
    expect(formatWait(0.4)).toBe('< 1 min');
    expect(formatWait(7)).toBe('7 min');
    expect(formatWait(65)).toBe('1 h 05 min');
    expect(formatWait(-3)).toBe('–');
  });

  it('only trusts the encounter hand-off link shape', () => {
    expect(encounterHandOff('#/employees/E1004/chart/encounters', 'E1004')).toBe('#/employees/E1004/chart/encounters');
    expect(encounterHandOff('https://evil.example/x', 'E1004')).toBe('#/employees/E1004/chart/encounters');
    expect(encounterHandOff(undefined, 'E 1')).toBe('#/employees/E%201/chart/encounters');
  });

  it('builds the check-in body for each identifier', () => {
    expect(checkInBody('HQ-MC', 'badge', ' 0042 ')).toEqual({ siteCode: 'HQ-MC', badgeId: '0042' });
    expect(checkInBody('HQ-MC', 'employee', 'e1004')).toEqual({ siteCode: 'HQ-MC', employeeId: 'E1004' });
    expect(checkInBody('HQ-MC', 'appointment', 'a-1')).toEqual({ siteCode: 'HQ-MC', appointmentId: 'a-1' });
    expect(checkInBody('', 'badge', '1')).toBeUndefined();
    expect(checkInBody('HQ-MC', 'badge', '<script>')).toBeUndefined();
  });
});

describe('campaign helpers', () => {
  it('parses pasted employee ids', () => {
    const r = parseEmployeeIds('E1001\ne1002, E1001;E1003\t"E1004"\n\nbad id!  x');
    expect(r.ids).toEqual(['E1001', 'E1002', 'E1003', 'E1004', 'BAD']);
    expect(r.duplicates).toBe(1);
    expect(r.invalid).toEqual(['id!', 'x']);
    expect(r.tooMany).toBe(false);
    expect(parseEmployeeIds(Array.from({ length: 5001 }, (_, i) => `E${i + 10}`).join('\n')).tooMany).toBe(true);
  });

  it('counts slots as days × stations × slots per day', () => {
    expect(campaignSlotCount(2, '08:00', '16:00', 10, 4)).toBe(2 * 4 * 48);
    expect(campaignSlotCount(1, '16:00', '08:00', 10, 4)).toBe(0);
  });
});

describe('capacity gap colouring', () => {
  it('flags shortfall, tight and spare capacity', () => {
    expect(capacityGap({ capacityAppointments: 100, forecastDemand: 120 })).toMatchObject({ gap: -20, tone: 'short', label: 'Short by 20', symbol: '■' });
    expect(capacityGap({ capacityAppointments: 100, forecastDemand: 95 })).toMatchObject({ gap: 5, tone: 'tight' });
    expect(capacityGap({ capacityAppointments: 100, forecastDemand: 40 })).toMatchObject({ gap: 60, tone: 'ok', label: '60 spare' });
    expect(capacityGap({ capacityAppointments: 0, forecastDemand: 3 }).tone).toBe('short');
    expect(utilisationTone(50)).toBe('ok');
    expect(utilisationTone(90)).toBe('high');
    expect(utilisationTone(101)).toBe('over');
  });
});

describe('site templates', () => {
  const row = { weekday: 1, start: '08:00', end: '12:00', clinicianUsername: 'nurse1', roomCode: 'C1', appointmentTypes: ['GENERAL_OH'], telehealth: false };
  it('accepts valid rows and reports bad times, missing clinicians and overlaps', () => {
    expect(validateTemplates([row, { ...row, start: '12:00', end: '16:00' }])).toEqual([]);
    expect(validateTemplates([{ ...row, end: '07:00' }])).toEqual(['Row 1: end must be after start.']);
    expect(validateTemplates([{ ...row, start: '8am' }])).toEqual(['Row 1: times must be HH:MM.']);
    expect(validateTemplates([{ ...row, clinicianUsername: ' ' }])).toEqual(['Row 1: clinician is required.']);
    expect(validateTemplates([row, { ...row, start: '11:00', end: '13:00' }])).toEqual(['Rows 1 and 2: nurse1 has overlapping sessions on Monday.']);
  });
});
