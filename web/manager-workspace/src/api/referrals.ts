import { ApiError, SessionExpiredError, type AuthClient } from '../auth/AuthClient';

/**
 * Manager (E01-BL-007) and HR (E01-BL-008) referrals – docs/increment-01/API-Contract-E01-E02.md §4.2.
 * Both are work-impact only: the server rejects anything that looks like a diagnosis or clinical opinion with
 * `422 code=clinical_content_not_allowed` (optionally naming the `field`).
 */

/** `GET /intake/employees/lookup?q=` row (manager/HR: own scope only, max 20). */
export interface EmployeeLookup {
  employeeId: string;
  displayName: string;
  departmentCode?: string;
  jobCode?: string;
}

export interface ManagerReferral {
  employeeId: string;
  dutiesAffected: string;
  startDate: string;
  safetyConcern: boolean;
  safetyConcernDetail?: string;
  adjustmentsTried?: string;
  questionsForOh?: string;
}

/** Request fields of ManagerReferral – a 422 `field` outside this list is shown as a general error only. */
export const MANAGER_FIELDS = ['employeeId', 'dutiesAffected', 'startDate', 'safetyConcern', 'safetyConcernDetail', 'adjustmentsTried', 'questionsForOh'] as const;
/** Request fields of HrReferral. */
export const HR_FIELDS = ['employeeId', 'absenceSummary', 'policyStage', 'absenceDaysLast12Months', 'adviceQuestions'] as const;

export const POLICY_STAGES = ['INFORMAL', 'STAGE_1', 'STAGE_2', 'FINAL'] as const;
export type PolicyStage = (typeof POLICY_STAGES)[number];
export const POLICY_STAGE_LABELS: Record<PolicyStage, string> = {
  INFORMAL: 'Informal',
  STAGE_1: 'Stage 1',
  STAGE_2: 'Stage 2',
  FINAL: 'Final stage',
};

export interface HrReferral {
  employeeId: string;
  absenceSummary: string;
  policyStage: PolicyStage;
  absenceDaysLast12Months: number;
  adviceQuestions: string[];
}

/** Contract limit for manager free text. */
export const MANAGER_TEXT_MAX = 1000;
/** Not pinned by the contract – client-side limits chosen to match the manager form. */
export const HR_SUMMARY_MAX = 1000;
export const HR_QUESTION_MAX = 500;
export const HR_MAX_QUESTIONS = 10;

export const CLINICAL_CONTENT_CODE = 'clinical_content_not_allowed';
/** Increment 3 (`ClinicalContentException.screen`, docs/increment-03 contract) reports `422 code=clinical_content`. */
export const CLINICAL_CONTENT_CODE_V3 = 'clinical_content';
export const isClinicalContentCode = (code: string | undefined) => code === CLINICAL_CONTENT_CODE || code === CLINICAL_CONTENT_CODE_V3;
export const CLINICAL_CONTENT_MESSAGE =
  'This looks like medical information (for example a diagnosis or clinical opinion). Describe only the impact on work.';
export const GUIDANCE = 'Do not include diagnoses or medical opinions – describe the impact on work only.';

export type FieldErrors = Record<string, string>;

/* ---------------- projections (only whitelisted fields are kept) ---------------- */

const str = (v: unknown) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : undefined);

export function toEmployeeLookups(body: unknown): EmployeeLookup[] {
  const list = Array.isArray(body) ? body : [];
  return list
    .map((raw): EmployeeLookup | undefined => {
      if (!raw || typeof raw !== 'object') return undefined;
      const r = raw as Record<string, unknown>;
      const employeeId = str(r.employeeId);
      if (!employeeId) return undefined;
      return { employeeId, displayName: str(r.displayName) ?? employeeId, departmentCode: str(r.departmentCode), jobCode: str(r.jobCode) };
    })
    .filter((e): e is EmployeeLookup => !!e)
    .slice(0, 20);
}

/** Referral responses: only the case reference is ever used. */
export function toCaseRef(body: unknown): string | undefined {
  return body && typeof body === 'object' ? str((body as Record<string, unknown>).caseRef) : undefined;
}

/* ---------------- validation ---------------- */

export interface ManagerReferralForm {
  employee?: EmployeeLookup;
  dutiesAffected: string;
  startDate: string;
  safetyConcern: '' | 'yes' | 'no';
  safetyConcernDetail: string;
  adjustmentsTried: string;
  questionsForOh: string;
}

export const emptyManagerForm = (): ManagerReferralForm => ({
  dutiesAffected: '',
  startDate: '',
  safetyConcern: '',
  safetyConcernDetail: '',
  adjustmentsTried: '',
  questionsForOh: '',
});

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const tooLong = (max: number) => `Please use at most ${max} characters.`;

export function validateManagerReferral(f: ManagerReferralForm): FieldErrors {
  const e: FieldErrors = {};
  if (!f.employee) e.employeeId = 'Please choose the employee.';
  if (!f.dutiesAffected.trim()) e.dutiesAffected = 'Please describe which duties are affected.';
  if (!DATE.test(f.startDate)) e.startDate = 'Please enter the date the impact started.';
  if (!f.safetyConcern) e.safetyConcern = 'Please answer whether there is a safety concern.';
  if (f.safetyConcern === 'yes' && !f.safetyConcernDetail.trim()) e.safetyConcernDetail = 'Please describe the safety concern.';
  for (const k of ['dutiesAffected', 'safetyConcernDetail', 'adjustmentsTried', 'questionsForOh'] as const) {
    if (!e[k] && f[k].length > MANAGER_TEXT_MAX) e[k] = tooLong(MANAGER_TEXT_MAX);
  }
  return e;
}

export function buildManagerReferral(f: ManagerReferralForm): ManagerReferral {
  const body: ManagerReferral = {
    employeeId: f.employee!.employeeId,
    dutiesAffected: f.dutiesAffected.trim(),
    startDate: f.startDate,
    safetyConcern: f.safetyConcern === 'yes',
  };
  if (f.safetyConcern === 'yes' && f.safetyConcernDetail.trim()) body.safetyConcernDetail = f.safetyConcernDetail.trim();
  if (f.adjustmentsTried.trim()) body.adjustmentsTried = f.adjustmentsTried.trim();
  if (f.questionsForOh.trim()) body.questionsForOh = f.questionsForOh.trim();
  return body;
}

export interface HrReferralForm {
  employee?: EmployeeLookup;
  absenceSummary: string;
  policyStage: PolicyStage | '';
  absenceDaysLast12Months: string;
  adviceQuestions: string[];
}

export const emptyHrForm = (): HrReferralForm => ({ absenceSummary: '', policyStage: '', absenceDaysLast12Months: '', adviceQuestions: [''] });

export function validateHrReferral(f: HrReferralForm): FieldErrors {
  const e: FieldErrors = {};
  if (!f.employee) e.employeeId = 'Please choose the employee.';
  if (!f.absenceSummary.trim()) e.absenceSummary = 'Please summarise the absence.';
  else if (f.absenceSummary.length > HR_SUMMARY_MAX) e.absenceSummary = tooLong(HR_SUMMARY_MAX);
  if (!f.policyStage) e.policyStage = 'Please choose the policy stage.';
  const days = f.absenceDaysLast12Months.trim();
  if (!/^\d{1,3}$/.test(days) || Number(days) > 366) e.absenceDaysLast12Months = 'Please enter a whole number of days between 0 and 366.';
  const asked = f.adviceQuestions.filter((q) => q.trim());
  if (asked.length === 0) e.adviceQuestions = 'Please add at least one question for Occupational Health.';
  if (f.adviceQuestions.length > HR_MAX_QUESTIONS) e.adviceQuestions = `Please ask at most ${HR_MAX_QUESTIONS} questions.`;
  f.adviceQuestions.forEach((q, i) => {
    if (q.length > HR_QUESTION_MAX) e[`adviceQuestions.${i}`] = tooLong(HR_QUESTION_MAX);
  });
  return e;
}

export function buildHrReferral(f: HrReferralForm): HrReferral {
  return {
    employeeId: f.employee!.employeeId,
    absenceSummary: f.absenceSummary.trim(),
    policyStage: f.policyStage as PolicyStage,
    absenceDaysLast12Months: Number(f.absenceDaysLast12Months.trim()),
    adviceQuestions: f.adviceQuestions.map((q) => q.trim()).filter(Boolean),
  };
}

/* ---------------- submit + 422 mapping ---------------- */

/**
 * Map a problem `field` onto a form error key. Accepts `dutiesAffected`, `body.dutiesAffected`,
 * `adviceQuestions[2]` / `adviceQuestions.2`. For list fields the index refers to the *sent* (non-blank) list, so
 * `sentIndexToRow` translates it back to the editor row. Unknown fields → undefined.
 */
export function problemFieldKey(field: string | undefined, allowed: readonly string[], sentIndexToRow: (i: number) => number = (i) => i): string | undefined {
  if (!field) return undefined;
  const m = /^(?:body\.|request\.)?([A-Za-z]+)(?:\[(\d+)\]|\.(\d+))?$/.exec(field.trim());
  if (!m || !allowed.includes(m[1]!)) return undefined;
  const idx = m[2] ?? m[3];
  return idx === undefined ? m[1] : `${m[1]}.${sentIndexToRow(Number(idx))}`;
}

export type SubmitResult =
  | { ok: true; caseRef?: string }
  | { ok: false; fieldErrors: FieldErrors; message: string; sessionExpired?: boolean };

/** POST a referral and translate the outcome for the form (422 clinical content → field highlight). */
export async function submitReferral(
  client: Pick<AuthClient, 'api'>,
  path: '/intake/referrals/manager' | '/intake/referrals/hr',
  body: ManagerReferral | HrReferral,
  allowedFields: readonly string[],
  sentIndexToRow?: (i: number) => number,
): Promise<SubmitResult> {
  try {
    return { ok: true, caseRef: toCaseRef(await client.api<unknown>(path, { method: 'POST', body })) };
  } catch (e) {
    if (e instanceof SessionExpiredError) return { ok: false, fieldErrors: {}, message: 'Your session has ended. Please sign in again.', sessionExpired: true };
    if (e instanceof ApiError && (e.status === 422 || e.status === 400)) {
      const clinical = isClinicalContentCode(e.problem?.code);
      const key = problemFieldKey(e.problem?.field, allowedFields, sentIndexToRow);
      const fieldMsg = clinical ? CLINICAL_CONTENT_MESSAGE : 'Please check this field.';
      return {
        ok: false,
        fieldErrors: key ? { [key]: fieldMsg } : {},
        message: clinical
          ? 'The referral was not sent because it seems to contain medical information. Please remove diagnoses or medical opinions and describe the impact on work only.'
          : (e.problem?.title ?? 'The referral could not be sent. Please check your entries.'),
      };
    }
    if (e instanceof ApiError && e.status === 403) {
      return { ok: false, fieldErrors: {}, message: 'You can only refer employees in your own area of responsibility.' };
    }
    if (e instanceof ApiError && e.status >= 500) {
      return { ok: false, fieldErrors: {}, message: 'The service is temporarily unavailable. Please try again later.' };
    }
    return { ok: false, fieldErrors: {}, message: 'The referral could not be sent. Please check your connection and try again.' };
  }
}

/** Row index of the n-th non-blank question (what the server saw) in the editor list. */
export function sentQuestionRow(questions: readonly string[]): (sentIndex: number) => number {
  const rows = questions.map((q, i) => (q.trim() ? i : -1)).filter((i) => i >= 0);
  return (sentIndex) => rows[sentIndex] ?? sentIndex;
}
