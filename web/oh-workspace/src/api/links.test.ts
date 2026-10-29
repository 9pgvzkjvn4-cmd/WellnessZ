import { describe, expect, it } from 'vitest';
import { attachmentUrl, buildQuery, isIsoDate, queuePath, reconciliationExportUrl } from './links';

describe('query building', () => {
  it('omits empty values and encodes the rest', () => {
    expect(buildQuery({ a: 1, b: '', c: null, d: undefined, e: 'x y&z', f: false })).toBe('?a=1&e=x%20y%26z&f=false');
    expect(buildQuery({})).toBe('');
  });
});

describe('CSV export link', () => {
  it('builds the reconciliation export URL through the BFF', () => {
    expect(reconciliationExportUrl({ from: '2026-10-01', to: '2026-10-28' })).toBe('/bff/api/intake/reconciliation/export?from=2026-10-01&to=2026-10-28');
  });

  it('omits missing dates', () => {
    expect(reconciliationExportUrl({ from: '2026-10-01' })).toBe('/bff/api/intake/reconciliation/export?from=2026-10-01');
    expect(reconciliationExportUrl({})).toBe('/bff/api/intake/reconciliation/export');
  });

  it('cannot be used to inject extra parameters', () => {
    expect(reconciliationExportUrl({ from: '2026-10-01&unit=X', to: '' })).toBe('/bff/api/intake/reconciliation/export?from=2026-10-01%26unit%3DX');
  });

  it('builds attachment download links with encoded segments', () => {
    expect(attachmentUrl('c/1', 'a 2')).toBe('/bff/api/cases/c%2F1/attachments/a%202');
  });

  it('validates ISO dates', () => {
    expect(isIsoDate('2026-10-29')).toBe(true);
    expect(isIsoDate('29/10/2026')).toBe(false);
    expect(isIsoDate(undefined)).toBe(false);
  });
});

describe('queue path', () => {
  it('includes paging and only the filters that are set', () => {
    expect(queuePath('UNASSIGNED', 2, { urgency: 'URGENT', subsidiary: '', caseType: undefined, safetyCritical: 'true', q: 'erin' })).toBe(
      '/queues/UNASSIGNED?page=2&size=50&urgency=URGENT&safetyCritical=true&q=erin',
    );
  });
});
