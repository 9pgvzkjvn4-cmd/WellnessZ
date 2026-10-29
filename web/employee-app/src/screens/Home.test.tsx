import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { toMyCases } from '../api/cases';
import { parseCaseRef } from '../api/types';
import { CaseCard } from './Home';

describe('home case cards', () => {
  const cases = toMyCases([
    { id: 'c1', caseRef: 'CAT-2026-000042', status: 'TRIAGE', category: 'INJURY', fitnessStatus: 'UNFIT_PENDING', updatedAt: '2026-10-28T10:00:00Z', description: 'secret clinical text' },
    { id: 'c2', caseRef: 'OH-2026-000007', status: 'CLOSED', fitnessStatus: 'FIT_WITH_RESTRICTIONS', restrictionUntil: '2026-12-01', updatedAt: '2026-10-29T10:00:00Z' },
    { foo: 'malformed' },
  ]);

  it('keeps only display fields, newest first', () => {
    expect(cases.map((c) => c.id)).toEqual(['c2', 'c1']);
    expect(JSON.stringify(cases)).not.toContain('secret');
  });

  it('shows the case reference, unit, status and fitness status', () => {
    const html = renderToStaticMarkup(<CaseCard c={cases[1]!} />);
    expect(html).toContain('CAT-2026-000042');
    expect(html).toContain('CAT · 2026');
    expect(html).toContain('Being reviewed by OH');
    expect(html).toContain('Unfit – pending assessment');
    expect(html).toContain('Injury');
  });

  it('shows restrictions and the OH fallback unit', () => {
    const html = renderToStaticMarkup(<CaseCard c={cases[0]!} />);
    expect(html).toContain('Occupational Health · 2026');
    expect(html).toContain('Fit with restrictions');
    expect(html).toContain('Restrictions until');
  });

  it('parses the {UNIT}-{YYYY}-{NNNNNN} reference format', () => {
    expect(parseCaseRef('CAT-2026-000042')).toEqual({ unit: 'CAT', year: '2026', seq: '000042' });
    expect(parseCaseRef('OH-2026-0001')).toBeUndefined(); // legacy format
    expect(parseCaseRef(undefined)).toBeUndefined();
  });
});
