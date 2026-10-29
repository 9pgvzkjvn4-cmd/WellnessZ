import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { bulkRows, bulkSummary } from '../domain/bulk';
import { BulkResultList } from './BulkResultList';

const refs: Record<string, string> = { c1: 'CAT-2026-000001', c2: 'FLT-2026-000002', c3: 'OH-2026-000003' };

describe('bulk action results', () => {
  it('maps succeeded / failed per item in the requested order', () => {
    const rows = bulkRows(['c1', 'c2', 'c3'], { succeeded: ['c3', 'c1'], failed: [{ caseId: 'c2', reason: 'Case is closed' }] }, (id) => refs[id]);
    expect(rows).toEqual([
      { caseId: 'c1', caseRef: 'CAT-2026-000001', ok: true },
      { caseId: 'c2', caseRef: 'FLT-2026-000002', ok: false, reason: 'Case is closed' },
      { caseId: 'c3', caseRef: 'OH-2026-000003', ok: true },
    ]);
    expect(bulkSummary(rows)).toBe('2 of 3 succeeded, 1 failed');
  });

  it('treats ids missing from the response as failed and falls back to the id', () => {
    const rows = bulkRows(['c1', 'x9'], { succeeded: ['c1'], failed: [] }, (id) => refs[id]);
    expect(rows[1]).toEqual({ caseId: 'x9', caseRef: 'x9', ok: false, reason: 'No result returned' });
  });

  it('tolerates a partial body', () => {
    expect(bulkRows(['c1'], {} as never, () => undefined)[0]?.ok).toBe(false);
  });

  it('renders each item with symbol + text, and a summary', () => {
    const rows = bulkRows(['c1', 'c2'], { succeeded: ['c1'], failed: [{ caseId: 'c2', reason: 'Not permitted' }] }, (id) => refs[id]);
    const html = renderToStaticMarkup(<BulkResultList action="Reassign" rows={rows} />);
    expect(html).toContain('Reassign:</strong> 1 of 2 succeeded, 1 failed');
    expect(html).toContain('CAT-2026-000001</span> – Done');
    expect(html).toContain('FLT-2026-000002</span> – Failed: Not permitted');
    expect(html).toContain('bulk-result-partial');
    expect(html).toContain('role="status"');
  });

  it('renders an all-success result and nothing for no rows', () => {
    const html = renderToStaticMarkup(<BulkResultList action="Remind" rows={bulkRows(['c1'], { succeeded: ['c1'], failed: [] }, (id) => refs[id])} />);
    expect(html).toContain('1 of 1 succeeded');
    expect(html).toContain('bulk-result-ok');
    expect(renderToStaticMarkup(<BulkResultList action="Remind" rows={[]} />)).toBe('');
  });
});
