/**
 * Increment 2 – E05 fitness for duty / restrictions / RTW and E03 documents for managers and HR
 * (docs/increment-02/API-Contract-E03-E05.md §3.2, §5.2, §5.3, §5.6).
 *
 * Confidentiality (E03-HLR-08): managers and HR see fitness / restriction status and functional restrictions only.
 * As in `statusOnly.ts`, every projection below copies ONLY whitelisted fields, so a clinical field returned by a
 * misconfigured backend (diagnosis, rationale, findings, notes, …) is dropped before rendering.
 */
import { BFF_API_PREFIX } from '../auth/bffAuthClient';

export const s = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : typeof v === 'number' ? String(v) : undefined);
export const n = (v: unknown): number | undefined =>
  typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined;
const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
/** Plain array or Spring page `{content:[…]}`. */
export const list = (body: unknown): unknown[] => (Array.isArray(body) ? body : Array.isArray(obj(body)?.content) ? (obj(body)!.content as unknown[]) : []);
const defined = <T>(x: T | undefined): x is T => x !== undefined;

/* ---------------- restrictions (functional wording only) ---------------- */

export const RESTRICTION_STATUSES = ['ACTIVE', 'EXPIRING', 'LAPSED', 'CLOSED'] as const;

/** A functional restriction as shown to managers/HR. */
export interface FunctionalRestriction {
  code?: string;
  label: string;
  value?: string;
}

/** Fill the catalogue placeholder: "No lifting above {value} kg" + 10 → "No lifting above 10 kg". */
export function restrictionText(r: FunctionalRestriction): string {
  if (r.label.includes('{value}')) return r.label.replace(/\{value\}/g, r.value ?? '…');
  return r.value && !r.label.includes(r.value) ? `${r.label} (${r.value})` : r.label;
}

export function toFunctionalRestrictions(v: unknown): FunctionalRestriction[] {
  return (Array.isArray(v) ? v : [])
    .map((raw) => {
      const r = obj(raw);
      const label = s(r?.label) ?? s(r?.code);
      return r && label ? { code: s(r.code), label, value: s(r.value) } : undefined;
    })
    .filter(defined);
}

/** `GET /workspace/team-restrictions` row. */
export interface TeamRestrictionRow {
  employeeId: string;
  employeeName: string;
  fitnessStatus?: string;
  restrictions: { label: string; validUntil?: string; status?: string }[];
}

export function toTeamRestrictions(body: unknown): TeamRestrictionRow[] {
  return list(body)
    .map((raw): TeamRestrictionRow | undefined => {
      const r = obj(raw);
      const employeeId = s(r?.employeeId);
      if (!r || !employeeId) return undefined;
      return {
        employeeId,
        employeeName: s(r.employeeName) ?? s(r.employeeDisplayName) ?? employeeId,
        fitnessStatus: s(r.fitnessStatus),
        restrictions: (Array.isArray(r.restrictions) ? r.restrictions : [])
          .map((x) => {
            const e = obj(x);
            const label = s(e?.label);
            return e && label ? { label, validUntil: s(e.validUntil), status: s(e.status) } : undefined;
          })
          .filter(defined),
      };
    })
    .filter(defined);
}

export interface TeamRestrictionFilter {
  q: string;
  fitnessStatus: string;
  /** '' = all rows; 'ANY' = rows with any current restriction; otherwise a RestrictionInstanceStatus. */
  restrictionStatus: '' | 'ANY' | (typeof RESTRICTION_STATUSES)[number];
}

export const emptyRestrictionFilter = (): TeamRestrictionFilter => ({ q: '', fitnessStatus: '', restrictionStatus: '' });

export function filterTeamRestrictions(rows: readonly TeamRestrictionRow[], f: TeamRestrictionFilter): TeamRestrictionRow[] {
  const q = f.q.trim().toLowerCase();
  return rows.filter((r) => {
    if (q && !r.employeeName.toLowerCase().includes(q) && !r.employeeId.toLowerCase().includes(q)) return false;
    if (f.fitnessStatus && r.fitnessStatus !== f.fitnessStatus) return false;
    if (f.restrictionStatus === 'ANY') return r.restrictions.some((x) => x.status !== 'CLOSED' && x.status !== 'LAPSED');
    if (f.restrictionStatus) return r.restrictions.some((x) => x.status === f.restrictionStatus);
    return true;
  });
}

/* ---------------- modified duty proposals (E05-BL-006) ---------------- */

export const PROPOSAL_STATUSES = ['PROPOSED', 'ACCEPTED', 'DECLINED', 'ESCALATED_TO_HR', 'WITHDRAWN'] as const;
/** Statuses shown under "History" (one request each – the contract only documents `?status=`). */
export const DECIDED_PROPOSAL_STATUSES = ['ACCEPTED', 'DECLINED', 'ESCALATED_TO_HR', 'WITHDRAWN'] as const;
export const DECLINE_REASONS = ['NO_SUITABLE_WORK', 'OPERATIONAL', 'SAFETY', 'COST', 'OTHER'] as const;
export type DeclineReason = (typeof DECLINE_REASONS)[number];
export const DECLINE_REASON_LABELS: Record<DeclineReason, string> = {
  NO_SUITABLE_WORK: 'No suitable work available',
  OPERATIONAL: 'Operational reasons',
  SAFETY: 'Safety reasons',
  COST: 'Cost',
  OTHER: 'Other',
};

export interface DutyProposal {
  id: string;
  caseId?: string;
  caseRef?: string;
  employeeName: string;
  jobCode?: string;
  jobTitle?: string;
  duties?: string;
  restrictions: FunctionalRestriction[];
  hoursPerDay?: number;
  startDate?: string;
  endDate?: string;
  status: string;
  proposedAt?: string;
  decidedAt?: string;
  reasonCode?: string;
  note?: string;
}

export function toDutyProposals(body: unknown): DutyProposal[] {
  return list(body)
    .map((raw): DutyProposal | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const status = s(r?.status);
      if (!r || !id || !status) return undefined;
      return {
        id,
        caseId: s(r.caseId),
        caseRef: s(r.caseRef),
        employeeName: s(r.employeeName) ?? s(r.employeeId) ?? '–',
        jobCode: s(r.jobCode),
        jobTitle: s(r.jobTitle),
        duties: s(r.duties),
        restrictions: toFunctionalRestrictions(r.restrictions),
        hoursPerDay: n(r.hoursPerDay),
        startDate: s(r.startDate),
        endDate: s(r.endDate),
        status,
        proposedAt: s(r.proposedAt),
        decidedAt: s(r.decidedAt),
        reasonCode: s(r.reasonCode),
        note: s(r.note),
      };
    })
    .filter(defined);
}

export const DECISION_NOTE_MAX = 500;
export const DECISION_FIELDS = ['accept', 'reasonCode', 'note'] as const;

export interface DutyDecisionDraft {
  reasonCode: DeclineReason | '';
  note: string;
}
export const emptyDecisionDraft = (): DutyDecisionDraft => ({ reasonCode: '', note: '' });

export function validateDutyDecision(accept: boolean, d: DutyDecisionDraft): Record<string, string> {
  const e: Record<string, string> = {};
  if (!accept && !d.reasonCode) e.reasonCode = 'Please choose a reason for declining.';
  if (d.note.length > DECISION_NOTE_MAX) e.note = `Please use at most ${DECISION_NOTE_MAX} characters.`;
  return e;
}

/** `POST /duty-proposals/{id}/decision` body – reasonCode only when declining. */
export function buildDutyDecision(accept: boolean, d: DutyDecisionDraft): { accept: boolean; reasonCode?: DeclineReason; note?: string } {
  const body: { accept: boolean; reasonCode?: DeclineReason; note?: string } = { accept };
  if (!accept && d.reasonCode) body.reasonCode = d.reasonCode;
  if (d.note.trim()) body.note = d.note.trim();
  return body;
}

/* ---------------- RTW plans (E05-BL-007) ---------------- */

export interface RtwPhase {
  fromWeek?: number;
  toWeek?: number;
  hoursPerDay?: number;
  duties?: string;
  restrictions: FunctionalRestriction[];
}

export interface RtwPlan {
  id: string;
  caseRef?: string;
  employeeName: string;
  status: string;
  startDate?: string;
  goal?: string;
  phases: RtwPhase[];
  /** Review dates and outcome only – review notes are not shown in the workspace. */
  reviews: { id?: string; dueDate?: string; completedAt?: string; outcome?: string }[];
  /** Employee acknowledgement status only (the employee's own comment is not shown). */
  employeeAck?: { at?: string; agree?: boolean };
  managerAck?: { at?: string; by?: string; comment?: string };
  nextReviewDate?: string;
  documentId?: string;
}

export function toRtwPlans(body: unknown): RtwPlan[] {
  return list(body)
    .map((raw): RtwPlan | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const status = s(r?.status);
      if (!r || !id || !status) return undefined;
      const ea = obj(r.employeeAck);
      const ma = obj(r.managerAck);
      return {
        id,
        caseRef: s(r.caseRef),
        employeeName: s(r.employeeName) ?? s(r.employeeId) ?? '–',
        status,
        startDate: s(r.startDate),
        goal: s(r.goal),
        phases: (Array.isArray(r.phases) ? r.phases : [])
          .map((p) => {
            const e = obj(p);
            return e
              ? { fromWeek: n(e.fromWeek), toWeek: n(e.toWeek), hoursPerDay: n(e.hoursPerDay), duties: s(e.duties), restrictions: toFunctionalRestrictions(e.restrictions) }
              : undefined;
          })
          .filter(defined),
        reviews: (Array.isArray(r.reviews) ? r.reviews : [])
          .map((v) => {
            const e = obj(v);
            return e ? { id: s(e.id), dueDate: s(e.dueDate), completedAt: s(e.completedAt), outcome: s(e.outcome) } : undefined;
          })
          .filter(defined),
        employeeAck: ea && (s(ea.at) || typeof ea.agree === 'boolean') ? { at: s(ea.at), agree: typeof ea.agree === 'boolean' ? ea.agree : undefined } : undefined,
        managerAck: ma && s(ma.at) ? { at: s(ma.at), by: s(ma.by), comment: s(ma.comment) } : undefined,
        nextReviewDate: s(r.nextReviewDate),
        documentId: s(r.documentId),
      };
    })
    .filter(defined);
}

export const ACK_COMMENT_MAX = 500;
export const ACK_FIELDS = ['comment'] as const;

export function validateAckComment(comment: string): Record<string, string> {
  return comment.length > ACK_COMMENT_MAX ? { comment: `Please use at most ${ACK_COMMENT_MAX} characters.` } : {};
}
export const buildAck = (comment: string): { comment?: string } => (comment.trim() ? { comment: comment.trim() } : {});

/** Manager acknowledgement is possible for ACTIVE plans not yet acknowledged by a manager. */
export const needsManagerAck = (p: RtwPlan) => p.status === 'ACTIVE' && !p.managerAck;

/* ---------------- documents (E03-F06, shared to manager / HR) ---------------- */

/**
 * Document types a manager/HR may see. The contract lists FITNESS_CERTIFICATE, RESTRICTION_NOTE,
 * FOOD_HANDLER_CLEARANCE, RTW_PLAN, JOB_OFFER as manager/HR-shareable; IHR_DECISION_LETTER carries the CMO's
 * functional `decisionText` that HR is entitled to (§5.7). Any other type (result letters, medical reports,
 * external reports, photos) is dropped client-side even if returned.
 */
export const WORKSPACE_DOCUMENT_TYPES = ['FITNESS_CERTIFICATE', 'RESTRICTION_NOTE', 'FOOD_HANDLER_CLEARANCE', 'RTW_PLAN', 'JOB_OFFER', 'IHR_DECISION_LETTER'] as const;
export const DOCUMENT_TYPE_LABELS: Record<(typeof WORKSPACE_DOCUMENT_TYPES)[number], string> = {
  FITNESS_CERTIFICATE: 'Fitness certificate',
  RESTRICTION_NOTE: 'Restriction note',
  FOOD_HANDLER_CLEARANCE: 'Food handler clearance',
  RTW_PLAN: 'Return-to-work plan',
  JOB_OFFER: 'Job offer',
  IHR_DECISION_LETTER: 'Ill-health / redeployment decision letter',
};

export interface WorkspaceDocument {
  id: string;
  documentNumber?: string;
  type: (typeof WORKSPACE_DOCUMENT_TYPES)[number];
  title?: string;
  employeeId?: string;
  version?: number;
  status?: string;
  contentType?: string;
  signedAt?: string;
  createdAt?: string;
  acknowledgedAt?: string;
}

export function toWorkspaceDocuments(body: unknown): WorkspaceDocument[] {
  return list(body)
    .map((raw): WorkspaceDocument | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const type = s(r?.type);
      if (!r || !id || !type || !(WORKSPACE_DOCUMENT_TYPES as readonly string[]).includes(type)) return undefined;
      return {
        id,
        documentNumber: s(r.documentNumber),
        type: type as WorkspaceDocument['type'],
        title: s(r.title),
        employeeId: s(r.employeeId),
        version: n(r.version),
        status: s(r.status),
        contentType: s(r.contentType),
        signedAt: s(r.signedAt),
        createdAt: s(r.createdAt),
        acknowledgedAt: s(r.acknowledgedAt),
      };
    })
    .filter(defined);
}

/**
 * Download URL (`GET /documents/{id}/content` via the BFF). A same-origin GET navigation carries the session cookie
 * and needs no CSRF header; the server audits the download.
 */
export const documentContentUrl = (id: string) => `${BFF_API_PREFIX}/documents/${encodeURIComponent(id)}/content`;
