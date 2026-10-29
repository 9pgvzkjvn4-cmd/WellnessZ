/**
 * cases-by-status KPI (GET /analytics/kpis/cases-by-status?groupBy=orgUnit).
 *
 * Backend contract:
 *   { "kpi": "cases-by-status", "groupBy": "orgUnit", "minGroupSize": 5,
 *     "cells": [ { "group": "CABIN", "category": "OPEN",   "count": 12, "suppressed": false },
 *                { "group": "CARGO", "category": "TRIAGE", "suppressed": true } ] }
 * `category` is the case status; suppressed cells carry no count (null/absent).
 * Parsing stays tolerant (groups/rows/data or a bare array; status|category;
 * count|value|n). As defence in depth the UI also treats any count below k as suppressed.
 */
export interface KpiCell {
  group: string;
  status: string;
  count: number | null;
  suppressed: boolean;
}

export interface KpiResult {
  groupBy: string;
  minGroupSize: number;
  cells: KpiCell[];
}

export const DEFAULT_MIN_GROUP = 5;
const TOTAL = 'TOTAL';

const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : undefined);

export function normaliseKpis(body: unknown): KpiResult {
  const obj = (body && typeof body === 'object' && !Array.isArray(body) ? body : {}) as Record<string, unknown>;
  const k = num(obj.minGroupSize) ?? num(obj.k) ?? num(obj.kAnonymity) ?? DEFAULT_MIN_GROUP;
  const list: unknown[] = Array.isArray(body)
    ? body
    : ((Array.isArray(obj.cells) && obj.cells) ||
      (Array.isArray(obj.groups) && obj.groups) || (Array.isArray(obj.rows) && obj.rows) || (Array.isArray(obj.data) && obj.data) || []);
  const cells = list
    .map((raw): KpiCell | undefined => {
      if (!raw || typeof raw !== 'object') return undefined;
      const r = raw as Record<string, unknown>;
      const group = str(r.group) ?? str(r.orgUnit) ?? str(r.groupKey) ?? str(r.key);
      if (!group) return undefined;
      const count = num(r.count) ?? num(r.value) ?? num(r.n) ?? null;
      const suppressed = r.suppressed === true || count === null || count < k;
      return { group, status: str(r.category) ?? str(r.status) ?? TOTAL, count: suppressed ? null : count, suppressed };
    })
    .filter((c): c is KpiCell => !!c);
  return { groupBy: str(obj.groupBy) ?? 'orgUnit', minGroupSize: k, cells };
}

/** Display text for a cell – suppressed groups never reveal a number. */
export function formatKpiCount(cell: Pick<KpiCell, 'count' | 'suppressed'> | undefined, k: number): string {
  if (!cell) return '–';
  if (cell.suppressed || cell.count === null) return `<${k} – suppressed`;
  return cell.count.toLocaleString();
}

/** Pivot into groups × statuses. */
export function pivotKpis(result: KpiResult): { groups: string[]; statuses: string[]; get: (g: string, s: string) => KpiCell | undefined } {
  const groups = [...new Set(result.cells.map((c) => c.group))].sort();
  const statuses = [...new Set(result.cells.map((c) => c.status))];
  const idx = new Map(result.cells.map((c) => [`${c.group}\u0000${c.status}`, c]));
  return { groups, statuses, get: (g, s) => idx.get(`${g}\u0000${s}`) };
}
