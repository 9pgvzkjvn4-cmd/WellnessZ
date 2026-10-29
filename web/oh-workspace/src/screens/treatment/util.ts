/** Small display helpers shared by the increment-4 screens (kept out of component modules for fast refresh). */
export const caseLink = (caseId: string, tab?: string): string => `#/cases/${encodeURIComponent(caseId)}${tab ? `/${tab}` : ''}`;
export const claimLink = (claimId: string, tab?: string): string => `#/claims/${encodeURIComponent(claimId)}${tab ? `/${tab}` : ''}`;
export const pct = (v: number | undefined | null, d = 0): string => (typeof v === 'number' && Number.isFinite(v) ? `${v.toFixed(d)} %` : '–');
export const num = (v: number | undefined | null, d = 1): string => (typeof v === 'number' && Number.isFinite(v) ? v.toFixed(d) : '–');
export const csv = (v: string): string[] => v.split(',').map((s) => s.trim()).filter(Boolean);
