/**
 * Client-side checks for the increment-3 employee / candidate forms (sick certificate upload, advice-report decision,
 * pre-employment questionnaire and documents, crew pregnancy declaration). The server re-validates everything; these
 * give fast, localisable feedback. Errors are codes – the screens map them to en / ar / fr strings.
 */
import { MAX_COMMENT, MAX_UPLOAD_BYTES, UPLOAD_TYPES, type Question } from '../api/occupational';
import { effectiveType, type FileLike } from './attachments';

export type FormErrors<K extends string = string> = Partial<Record<K, FormErrorCode>>;
export type FormErrorCode =
  | 'required'
  | 'fileType'
  | 'fileSize'
  | 'dateInvalid'
  | 'dateOrder'
  | 'dateFuture'
  | 'spanTooLong'
  | 'tooLong';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function isIsoDate(v: string | undefined): v is string {
  return !!v && ISO_DATE.test(v) && !Number.isNaN(new Date(`${v}T00:00:00Z`).getTime()) && new Date(`${v}T00:00:00Z`).toISOString().startsWith(v);
}

function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

/** Local calendar day of `now` as YYYY-MM-DD. */
export function today(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

export function fileProblem(file: FileLike | undefined): FormErrorCode | undefined {
  if (!file) return 'required';
  if (!(UPLOAD_TYPES as readonly string[]).includes(effectiveType(file))) return 'fileType';
  if (file.size > MAX_UPLOAD_BYTES) return 'fileSize';
  return undefined;
}

export interface CertificateForm {
  file?: FileLike;
  fromDate: string;
  toDate: string;
  episodeId?: string;
}

/** Sick certificate: PDF / JPEG / PNG ≤ 10 MB; from ≤ to; covers at most 1 year; starts no later than 30 days ahead. */
export function validateCertificate(f: CertificateForm, now: Date = new Date()): FormErrors<'file' | 'fromDate' | 'toDate'> {
  const e: FormErrors<'file' | 'fromDate' | 'toDate'> = {};
  const fp = fileProblem(f.file);
  if (fp) e.file = fp;
  if (!f.fromDate) e.fromDate = 'required';
  else if (!isIsoDate(f.fromDate)) e.fromDate = 'dateInvalid';
  else if (daysBetween(today(now), f.fromDate) > 30) e.fromDate = 'dateFuture';
  if (!f.toDate) e.toDate = 'required';
  else if (!isIsoDate(f.toDate)) e.toDate = 'dateInvalid';
  else if (isIsoDate(f.fromDate) && f.toDate < f.fromDate) e.toDate = 'dateOrder';
  else if (isIsoDate(f.fromDate) && daysBetween(f.fromDate, f.toDate) > 365) e.toDate = 'spanTooLong';
  return e;
}

export interface DecisionForm {
  decision?: 'CONSENT' | 'DECLINE';
  comment: string;
  declineReason: string;
}

/** Advice-report decision: a choice is required; declining needs a reason; texts ≤ 1000 characters. */
export function validateDecision(f: DecisionForm): FormErrors<'decision' | 'comment' | 'declineReason'> {
  const e: FormErrors<'decision' | 'comment' | 'declineReason'> = {};
  if (!f.decision) e.decision = 'required';
  if (f.comment.trim().length > MAX_COMMENT) e.comment = 'tooLong';
  if (f.decision === 'DECLINE') {
    if (!f.declineReason.trim()) e.declineReason = 'required';
    else if (f.declineReason.trim().length > MAX_COMMENT) e.declineReason = 'tooLong';
  }
  return e;
}

/** Questionnaire: required answers present; DATE answers valid and not in the future; texts ≤ 2000. */
export function validateQuestionnaire(questions: readonly Question[], answers: Readonly<Record<string, string>>, now: Date = new Date()): FormErrors {
  const e: FormErrors = {};
  for (const q of questions) {
    const v = (answers[q.code] ?? '').trim();
    if (!v) {
      if (q.required) e[q.code] = 'required';
      continue;
    }
    if (q.type === 'YES_NO' && v !== 'YES' && v !== 'NO') e[q.code] = 'required';
    else if (q.type === 'DATE' && !isIsoDate(v)) e[q.code] = 'dateInvalid';
    else if (q.type === 'DATE' && v > today(now)) e[q.code] = 'dateFuture';
    else if (v.length > 2000) e[q.code] = 'tooLong';
  }
  return e;
}

export function validateDocument(file: FileLike | undefined, title: string): FormErrors<'file' | 'title'> {
  const e: FormErrors<'file' | 'title'> = {};
  const fp = fileProblem(file);
  if (fp) e.file = fp;
  if (!title.trim()) e.title = 'required';
  else if (title.trim().length > 200) e.title = 'tooLong';
  return e;
}

/** Crew pregnancy declaration date: required, valid, not in the future. */
export function validateDeclaredOn(v: string, now: Date = new Date()): FormErrorCode | undefined {
  if (!v) return 'required';
  if (!isIsoDate(v)) return 'dateInvalid';
  if (v > today(now)) return 'dateFuture';
  return undefined;
}

export const hasErrors = (e: FormErrors): boolean => Object.values(e).some(Boolean);
