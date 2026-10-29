import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { formatKpiCount, normaliseKpis } from '../api/kpi';
import { KpiTable } from './KpiTable';

// Exact backend contract shape
const body = {
  kpi: 'cases-by-status',
  groupBy: 'orgUnit',
  minGroupSize: 5,
  cells: [
    { group: 'CABIN', category: 'TRIAGE', count: 12, suppressed: false },
    { group: 'CABIN', category: 'CLOSED', count: 7, suppressed: false },
    { group: 'CARGO', category: 'TRIAGE', suppressed: true }, // no count
    // Not flagged by the server but below k → must still be suppressed by the UI
    { group: 'CARGO', category: 'CLOSED', count: 3 },
  ],
};

describe('KPI suppression rendering', () => {
  it('renders suppressed groups as "<5 – suppressed" and never shows the raw number', () => {
    const html = renderToStaticMarkup(<KpiTable result={normaliseKpis(body)} caption="Cases by status" />);
    expect(html.match(/&lt;5 – suppressed/g)).toHaveLength(2);
    expect(html).toContain('>12<');
    expect(html).toContain('>7<');
    expect(html).not.toContain('>3<');
  });

  it('maps category to the status column', () => {
    const r = normaliseKpis(body);
    expect(r.cells.map((c) => c.status)).toEqual(['TRIAGE', 'CLOSED', 'TRIAGE', 'CLOSED']);
    expect(r.cells[2]).toMatchObject({ group: 'CARGO', suppressed: true, count: null });
  });

  it('uses the server-provided k in the label', () => {
    expect(formatKpiCount({ count: null, suppressed: true }, 10)).toBe('<10 – suppressed');
  });

  it('defaults k to 5 and accepts a bare array', () => {
    const r = normaliseKpis([{ orgUnit: 'A', count: 4 }, { orgUnit: 'B', count: 5 }]);
    expect(r.minGroupSize).toBe(5);
    expect(r.cells.find((c) => c.group === 'A')).toMatchObject({ suppressed: true, count: null });
    expect(r.cells.find((c) => c.group === 'B')).toMatchObject({ suppressed: false, count: 5 });
  });

  it('shows an empty-state message without data', () => {
    expect(renderToStaticMarkup(<KpiTable result={normaliseKpis({})} caption="x" />)).toContain('No data');
  });
});
