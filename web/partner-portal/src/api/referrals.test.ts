import { describe, expect, it } from 'vitest';
import { mockApi, reply } from '../test/mockApi';
import { referral } from '../test/fixtures';
import {
  buildAppointment,
  buildInvoice,
  invoiceForm,
  isOverdue,
  loadReferral,
  referralActions,
  reportDueState,
  reportForm,
  setAppointment,
  sortInbox,
  toPartnerReferrals,
  validateDecline,
  validateReport,
} from './referrals';

const NOW = new Date('2026-10-29T12:00:00Z');

describe('partner referral mapper', () => {
  it('keeps the partner contract fields and drops everything else', () => {
    const [r] = toPartnerReferrals([referral()]);
    expect(r).toMatchObject({
      id: 'r1',
      ref: 'RFL-2026-000001',
      serviceName: 'Physiotherapy',
      urgency: 'URGENT',
      status: 'SENT',
      patient: { displayName: 'Rami Ramp', employeeId: 'E1004', phone: '+44 20 7946 0000' },
      letterDocumentId: 'doc-letter',
      attachments: [{ documentId: 'doc-att', title: 'Physio intake form' }],
    });
    expect(r!.dataSet).toEqual([
      { key: 'bodyRegion', value: 'Lumbar spine' },
      { key: 'sessionsRequested', value: '6' },
      { key: 'imagingDone', value: 'No' },
    ]);
    expect(JSON.stringify(r)).not.toMatch(/c-secret|ServiceRequest/);
  });

  it('tolerates junk and missing optional fields', () => {
    expect(toPartnerReferrals({ not: 'a list' })).toEqual([]);
    const list = toPartnerReferrals([null, 5, { ref: 'no id' }, { id: 'x' }]);
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ id: 'x', ref: 'x', urgency: 'ROUTINE', status: 'SENT', attachments: [], history: [], dataSet: [] });
  });

  it('sorts history oldest first', () => {
    const [r] = toPartnerReferrals([referral({ history: [{ status: 'ACCEPTED', at: '2026-10-28T10:00:00Z', by: 'partner1' }, { status: 'SENT', at: '2026-10-27T09:00:00Z' }, { status: 'X' }] })]);
    expect(r!.history.map((h) => h.status)).toEqual(['SENT', 'ACCEPTED']);
  });
});

describe('report due / overdue', () => {
  const r = (over: Record<string, unknown>) => toPartnerReferrals([referral(over)])[0]!;
  it('derives overdue from reportDueAt while a report is awaited', () => {
    expect(isOverdue(r({ reportDueAt: '2026-10-28T12:00:00Z', status: 'SEEN' }), NOW)).toBe(true);
    expect(isOverdue(r({ reportDueAt: '2026-10-28T12:00:00Z', status: 'REPORT_RECEIVED' }), NOW)).toBe(false);
    expect(isOverdue(r({ reportDueAt: '2026-11-28T12:00:00Z', status: 'SEEN' }), NOW)).toBe(false);
    expect(isOverdue(r({ reportDueAt: undefined }), NOW)).toBe(false);
  });
  it('prefers the server flag', () => {
    expect(isOverdue(r({ overdue: true, reportDueAt: '2027-01-01T00:00:00Z' }), NOW)).toBe(true);
    expect(isOverdue(r({ overdue: false, reportDueAt: '2020-01-01T00:00:00Z' }), NOW)).toBe(false);
  });
  it('flags reports due within 48 h', () => {
    expect(reportDueState(r({ reportDueAt: '2026-10-30T12:00:00Z' }), NOW)).toBe('due-soon');
    expect(reportDueState(r({ reportDueAt: '2026-11-30T12:00:00Z' }), NOW)).toBe('none');
    expect(reportDueState(r({ reportDueAt: '2026-10-01T12:00:00Z' }), NOW)).toBe('overdue');
  });
  it('orders the inbox urgent, then overdue, then by due date', () => {
    const list = toPartnerReferrals([
      referral({ id: 'a', urgency: 'ROUTINE', reportDueAt: '2026-11-01T00:00:00Z' }),
      referral({ id: 'b', urgency: 'ROUTINE', reportDueAt: '2026-10-01T00:00:00Z' }),
      referral({ id: 'c', urgency: 'URGENT', reportDueAt: '2026-12-01T00:00:00Z' }),
    ]);
    expect(sortInbox(list, NOW).map((x) => x.id)).toEqual(['c', 'b', 'a']);
  });
});

describe('referral actions by status', () => {
  it.each([
    ['SENT', ['accept', 'decline']],
    ['ACCEPTED', ['decline', 'appointment']],
    ['SCHEDULED', ['appointment', 'seen', 'report']],
    ['SEEN', ['report', 'invoice']],
    ['REPORT_RECEIVED', ['invoice']],
    ['CANCELLED', []],
    ['DECLINED', []],
  ])('%s → %j', (status, expected) => {
    const a = referralActions(status);
    expect(Object.entries(a).filter(([, v]) => v).map(([k]) => k)).toEqual(expected);
  });
});

describe('partner action forms', () => {
  it('converts the appointment to ISO UTC and requires a future time', () => {
    const { body } = buildAppointment({ appointmentAt: '2026-11-03T09:30', location: ' Clinic 2 ', instructions: '' }, NOW);
    expect(body).toEqual({ appointmentAt: new Date(2026, 10, 3, 9, 30).toISOString(), location: 'Clinic 2' });
    expect(buildAppointment({ appointmentAt: '2020-01-01T09:00', location: '', instructions: '' }, NOW).errors).toEqual({ appointmentAt: 'The appointment must be in the future.' });
    expect(buildAppointment({ appointmentAt: '', location: '', instructions: '' }, NOW).errors).toEqual({ appointmentAt: 'This field is required.' });
    expect(buildAppointment({ appointmentAt: '2026-02-30T09:00', location: '', instructions: '' }, NOW).errors.appointmentAt).toBe('Enter a valid date and time.');
  });

  it('requires a decline reason', () => {
    expect(validateDecline('  ')).toEqual({ reason: 'This field is required.' });
    expect(validateDecline('No capacity')).toEqual({});
  });

  it('validates report uploads', () => {
    expect(validateReport(undefined, '')).toEqual({ file: 'Choose a file to upload.' });
    expect(validateReport({ name: 'r.pdf', type: 'application/pdf', size: 10 }, 'x'.repeat(2001))).toEqual({ summary: 'Use at most 2000 characters.' });
  });

  it('validates invoice metadata', () => {
    const file = { name: 'inv.pdf', type: 'application/pdf', size: 100 };
    const ok = buildInvoice(file, { invoiceNumber: ' INV-77 ', amount: '1,250.5', currency: 'GBP', serviceDate: '2026-10-28' }, NOW);
    expect(ok.request).toEqual({ invoiceNumber: 'INV-77', amount: { amount: '1250.50', currency: 'GBP' }, serviceDate: '2026-10-28' });
    const bad = buildInvoice({ ...file, size: 11 * 1024 * 1024 }, { invoiceNumber: '', amount: '-1', currency: '', serviceDate: '2027-01-01' }, NOW);
    expect(Object.keys(bad.errors).sort()).toEqual(['amount', 'currency', 'file', 'invoiceNumber', 'serviceDate']);
    expect(bad.errors.serviceDate).toBe('The service date cannot be in the future.');
  });

  it('builds the multipart bodies', () => {
    const file = new File(['%PDF'], 'report.pdf', { type: 'application/pdf' });
    const rf = reportForm(file, '  seen and treated ');
    expect((rf.get('file') as File).name).toBe('report.pdf');
    expect(rf.get('summary')).toBe('seen and treated');
    expect(reportForm(file, ' ').has('summary')).toBe(false);
    const inv = invoiceForm(file, { invoiceNumber: 'INV-1', amount: { amount: '99.00', currency: 'EUR' }, serviceDate: '2026-10-20' });
    expect([...inv.keys()]).toEqual(['file', 'invoiceNumber', 'amount', 'currency', 'serviceDate']);
    expect(inv.get('amount')).toBe('99.00');
    expect(inv.get('currency')).toBe('EUR');
  });
});

describe('referral API calls', () => {
  it('reads the detail from the partner list and reports absence as undefined', async () => {
    const api = mockApi({ 'GET /partner/referrals': [referral(), referral({ id: 'r2', ref: 'RFL-2' })] });
    expect((await loadReferral(api.client, 'r2'))?.ref).toBe('RFL-2');
    expect(await loadReferral(api.client, 'other-org')).toBeUndefined();
  });

  it('posts the appointment and maps a screened field', async () => {
    const api = mockApi({ 'POST /partner/referrals/r%2F1/appointment': reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'instructions' }) });
    const res = await setAppointment(api.client, 'r/1', { appointmentAt: '2026-11-03T09:30:00.000Z', instructions: 'x' }, 'Not saved');
    expect(api.calls[0]).toMatchObject({ method: 'POST', path: '/partner/referrals/r%2F1/appointment', body: { appointmentAt: '2026-11-03T09:30:00.000Z', instructions: 'x' } });
    expect(res).toMatchObject({ ok: false, fieldErrors: { instructions: expect.stringMatching(/medical/) } });
  });
});
