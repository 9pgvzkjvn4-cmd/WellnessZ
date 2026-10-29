/**
 * TPA / insurer adjuster claims – contract §4.4 (claims where the adjuster's organisation is the payer):
 *   GET  /partner/claims?status=      [{id, claimRef, claimType, status, displayName, incidentDate, lostTime, openTasks, unreadMessages}]
 *   GET  /partner/claims/{id}         + rtw, tasks, documents (consented INSURER shares of this claim)
 *   GET  /claims/{id}/rtw             status only {fitnessStatus, restrictionUntil?, rtwPlan?{status, plannedStart, phases[], nextReview?}}
 *   GET / POST /claims/{id}/tasks     {title ≤ 200 (screened), dueDate, assigneeRole CLAIMS|NURSE_CM|ADJUSTER}
 *   POST /claims/tasks/{taskId}/complete   (assignee side – the adjuster completes ADJUSTER tasks)
 *   GET / POST /claims/{id}/messages  {text ≤ 2000} (see messages.ts)
 * Confidentiality rule 2: RTW status and milestones only, never clinical notes – the mappers keep only those fields.
 */
import type { AuthClient } from '../auth/AuthClient';
import { isValidDate, localDate } from '../components/format';
import { S } from '../strings';
import { bool, mapList, num, rec, str, type Rec } from './read';
import { seg, submit, type ActionResult, type FieldErrors } from './errors';
import { toSharedDocuments, type SharedDocument } from './documents';

export const CLAIM_STATUSES = ['NOTIFIED', 'OPEN', 'UNDER_REVIEW', 'LITIGATED', 'SETTLED', 'CLOSED'] as const;
export const ASSIGNEE_ROLES = ['ADJUSTER', 'CLAIMS', 'NURSE_CM'] as const;
export const TASK_TITLE_MAX = 200;
export const TASK_FIELDS = ['title', 'dueDate', 'assigneeRole'] as const;

export interface PartnerClaim {
  id: string;
  claimRef: string;
  claimType: string;
  status: string;
  displayName: string;
  incidentDate?: string;
  lostTime: boolean;
  openTasks: number;
  unreadMessages: number;
}

export interface RtwPhase {
  from?: string;
  to?: string;
  hoursPerDay?: number;
  dutiesSummary?: string;
}

export interface RtwStatus {
  fitnessStatus?: string;
  restrictionUntil?: string;
  rtwPlan?: { status?: string; plannedStart?: string; nextReview?: string; phases: RtwPhase[] };
}

export interface ClaimTask {
  id: string;
  title: string;
  dueDate?: string;
  assigneeRole: string;
  status: string;
  createdBy?: string;
  createdAt?: string;
  completedAt?: string;
}

export interface ClaimDetail extends PartnerClaim {
  rtw?: RtwStatus;
  tasks: ClaimTask[];
  documents: SharedDocument[];
}

function toClaim(r: Rec): PartnerClaim | undefined {
  const id = str(r.id);
  if (!id) return undefined;
  return {
    id,
    claimRef: str(r.claimRef) ?? id,
    claimType: str(r.claimType) ?? '',
    status: str(r.status) ?? '',
    displayName: str(r.displayName) ?? '',
    incidentDate: str(r.incidentDate),
    lostTime: bool(r.lostTime),
    openTasks: num(r.openTasks) ?? 0,
    unreadMessages: num(r.unreadMessages) ?? 0,
  };
}

export const toPartnerClaims = (body: unknown): PartnerClaim[] => mapList(body, toClaim);

/** RTW projection: fitness status, restriction end, plan status / dates and phase milestones only. */
export function toRtwStatus(v: unknown): RtwStatus | undefined {
  const r = rec(v);
  if (!r) return undefined;
  const plan = rec(r.rtwPlan);
  const out: RtwStatus = {
    fitnessStatus: str(r.fitnessStatus),
    restrictionUntil: str(r.restrictionUntil),
    rtwPlan: plan
      ? {
          status: str(plan.status),
          plannedStart: str(plan.plannedStart),
          nextReview: str(plan.nextReview),
          phases: mapList(plan.phases, (p) => ({ from: str(p.from), to: str(p.to), hoursPerDay: num(p.hoursPerDay), dutiesSummary: str(p.dutiesSummary) })),
        }
      : undefined,
  };
  return out.fitnessStatus || out.restrictionUntil || out.rtwPlan ? out : undefined;
}

export const toClaimTasks = (body: unknown): ClaimTask[] =>
  mapList(body, (r) => {
    const id = str(r.id);
    const title = str(r.title);
    if (!id || !title) return undefined;
    return {
      id,
      title,
      dueDate: str(r.dueDate),
      assigneeRole: str(r.assigneeRole) ?? '',
      status: str(r.status) ?? 'OPEN',
      createdBy: str(r.createdBy),
      createdAt: str(r.createdAt),
      completedAt: str(r.completedAt),
    };
  }).sort((a, b) => Number(a.status === 'DONE') - Number(b.status === 'DONE') || (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));

export function toClaimDetail(body: unknown): ClaimDetail | undefined {
  const r = rec(body);
  const claim = r && toClaim(r);
  if (!r || !claim) return undefined;
  return { ...claim, rtw: toRtwStatus(r.rtw), tasks: toClaimTasks(r.tasks), documents: toSharedDocuments(r.documents) };
}

/** The adjuster may complete only open tasks assigned to the adjuster side. */
export const canCompleteTask = (t: ClaimTask) => t.status === 'OPEN' && t.assigneeRole === 'ADJUSTER';

type Client = Pick<AuthClient, 'api'>;

export const claimPath = (id: string) => `/claims/${seg(id)}`;
export const claimMessagesPath = (id: string) => `${claimPath(id)}/messages`;

export async function loadClaims(c: Client, status: string): Promise<PartnerClaim[]> {
  return toPartnerClaims(await c.api<unknown>(`/partner/claims${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

/** Detail; when the detail carries no `rtw` the status-only `/claims/{id}/rtw` read fills it (404/204 → none). */
export async function loadClaim(c: Client, id: string): Promise<ClaimDetail | undefined> {
  const detail = toClaimDetail(await c.api<unknown>(`/partner/claims/${seg(id)}`));
  if (detail && !detail.rtw) {
    try {
      detail.rtw = toRtwStatus(await c.api<unknown>(`${claimPath(id)}/rtw`));
    } catch {
      /* RTW is optional – the claim itself loaded */
    }
  }
  return detail;
}

export const loadTasks = async (c: Client, id: string) => toClaimTasks(await c.api<unknown>(`${claimPath(id)}/tasks`));

export interface NewTask {
  title: string;
  dueDate: string;
  assigneeRole: string;
}
export const createTask = (c: Client, claimId: string, t: NewTask, failed: string): Promise<ActionResult> =>
  submit(c, `${claimPath(claimId)}/tasks`, { title: t.title.trim(), dueDate: t.dueDate, assigneeRole: t.assigneeRole }, failed, TASK_FIELDS);
export const completeTask = (c: Client, taskId: string, failed: string): Promise<ActionResult> => submit(c, `/claims/tasks/${seg(taskId)}/complete`, undefined, failed);

/** Client-side checks for a new shared task (title ≤ 200, due date today or later, known assignee side). */
export function validateTask(f: NewTask, now: Date = new Date()): FieldErrors {
  const errors: FieldErrors = {};
  if (!f.title.trim()) errors.title = S.validation.required;
  else if (f.title.length > TASK_TITLE_MAX) errors.title = S.validation.maxLength(TASK_TITLE_MAX);
  if (!f.dueDate) errors.dueDate = S.validation.required;
  else if (!isValidDate(f.dueDate)) errors.dueDate = S.validation.date;
  else if (f.dueDate < localDate(now)) errors.dueDate = S.validation.datePast;
  if (!(ASSIGNEE_ROLES as readonly string[]).includes(f.assigneeRole)) errors.assigneeRole = S.validation.required;
  return errors;
}

