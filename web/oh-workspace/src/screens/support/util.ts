/** Link helpers for the E09 screens (kept out of component modules for fast refresh). */
export const supportCaseLink = (id: string): string => `#/support/cases/${encodeURIComponent(id)}`;
export const cismLink = (id: string): string => `#/cism/${encodeURIComponent(id)}`;
