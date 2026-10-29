import { describe, expect, it } from 'vitest';
import type { Question } from '../api/occupational';
import { hasErrors, isIsoDate, validateCertificate, validateDeclaredOn, validateDecision, validateDocument, validateQuestionnaire } from './occupationalForms';

const NOW = new Date(2026, 9, 29, 12, 0);
const pdf = { name: 'note.pdf', type: 'application/pdf', size: 1000 };

describe('sick certificate form', () => {
  it('requires a file and dates', () => {
    expect(validateCertificate({ fromDate: '', toDate: '' }, NOW)).toEqual({ file: 'required', fromDate: 'required', toDate: 'required' });
  });
  it('accepts PDF / JPEG / PNG ≤ 10 MB (type from the extension when empty)', () => {
    expect(validateCertificate({ file: { name: 'x.heic', type: 'image/heic', size: 10 }, fromDate: '2026-10-27', toDate: '2026-10-31' }, NOW)).toEqual({ file: 'fileType' });
    expect(validateCertificate({ file: { ...pdf, size: 11 * 1024 * 1024 }, fromDate: '2026-10-27', toDate: '2026-10-31' }, NOW)).toEqual({ file: 'fileSize' });
    expect(validateCertificate({ file: { name: 'photo.JPG', type: '', size: 10 }, fromDate: '2026-10-27', toDate: '2026-10-31' }, NOW)).toEqual({});
  });
  it('checks date order, span and far-future starts', () => {
    expect(validateCertificate({ file: pdf, fromDate: '2026-10-27', toDate: '2026-10-26' }, NOW)).toEqual({ toDate: 'dateOrder' });
    expect(validateCertificate({ file: pdf, fromDate: '2025-01-01', toDate: '2026-10-26' }, NOW)).toEqual({ toDate: 'spanTooLong' });
    expect(validateCertificate({ file: pdf, fromDate: '2027-01-01', toDate: '2027-01-02' }, NOW)).toEqual({ fromDate: 'dateFuture' });
    expect(validateCertificate({ file: pdf, fromDate: '2026-02-30', toDate: '2026-03-01' }, NOW)).toEqual({ fromDate: 'dateInvalid' });
  });
});

describe('advice decision form', () => {
  it('needs a choice; declining needs a reason; limits length', () => {
    expect(validateDecision({ comment: '', declineReason: '' })).toEqual({ decision: 'required' });
    expect(validateDecision({ decision: 'CONSENT', comment: '', declineReason: '' })).toEqual({});
    expect(validateDecision({ decision: 'DECLINE', comment: '', declineReason: ' ' })).toEqual({ declineReason: 'required' });
    expect(validateDecision({ decision: 'CONSENT', comment: 'x'.repeat(1001), declineReason: '' })).toEqual({ comment: 'tooLong' });
    expect(hasErrors(validateDecision({ decision: 'DECLINE', comment: '', declineReason: 'Dates wrong' }))).toBe(false);
  });
});

describe('pre-employment questionnaire and documents', () => {
  const qs: Question[] = [
    { code: 'Q1', text: 'Back problems?', type: 'YES_NO', required: true },
    { code: 'Q2', text: 'Last tetanus', type: 'DATE', required: false },
    { code: 'Q3', text: 'Anything else', type: 'TEXT', required: false },
  ];
  it('flags missing required answers and bad dates', () => {
    expect(validateQuestionnaire(qs, {}, NOW)).toEqual({ Q1: 'required' });
    expect(validateQuestionnaire(qs, { Q1: 'maybe' }, NOW)).toEqual({ Q1: 'required' });
    expect(validateQuestionnaire(qs, { Q1: 'NO', Q2: '2027-01-01' }, NOW)).toEqual({ Q2: 'dateFuture' });
    expect(validateQuestionnaire(qs, { Q1: 'YES', Q2: '2020-13-01' }, NOW)).toEqual({ Q2: 'dateInvalid' });
    expect(validateQuestionnaire(qs, { Q1: 'YES', Q2: '2020-05-01', Q3: 'ok' }, NOW)).toEqual({});
  });
  it('requires a document title and a valid file', () => {
    expect(validateDocument(undefined, '')).toEqual({ file: 'required', title: 'required' });
    expect(validateDocument(pdf, 'Vaccination record')).toEqual({});
  });
});

describe('pregnancy declaration date', () => {
  it('is required, valid and not in the future', () => {
    expect(validateDeclaredOn('', NOW)).toBe('required');
    expect(validateDeclaredOn('2026-10-30', NOW)).toBe('dateFuture');
    expect(validateDeclaredOn('2026-10-29', NOW)).toBeUndefined();
    expect(isIsoDate('2026-02-29')).toBe(false);
    expect(isIsoDate('2028-02-29')).toBe(true);
  });
});
