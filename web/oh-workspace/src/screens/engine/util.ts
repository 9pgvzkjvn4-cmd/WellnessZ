import { ApiError } from '../../auth/AuthClient';

export const isForbidden = (e: unknown): boolean => e instanceof ApiError && e.status === 403;

export function pct(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '–';
  return `${(v * 100).toFixed(digits)} %`;
}
