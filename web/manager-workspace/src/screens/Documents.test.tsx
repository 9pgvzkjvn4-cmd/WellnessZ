import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { documentContentUrl, toWorkspaceDocuments } from '../api/fitness';
import { findOne } from '../test/tree';
import { DocumentsView } from './Documents';

const body = [
  { id: 'd1', documentNumber: 'DOC-2026-000123', type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate – Rami Ramp', employeeId: 'E1004', version: 1, status: 'SIGNED', signedAt: '2026-10-28T10:00:00Z', signedBy: 'dr1', sha256: 'abc', shares: [{ target: 'MANAGER' }] },
  { id: 'd2', documentNumber: 'DOC-2026-000124', type: 'RTW_PLAN', title: 'RTW plan', version: 2, status: 'SIGNED' },
  { id: 'd3', type: 'IHR_DECISION_LETTER', title: 'Decision letter' },
  { id: 'd4', type: 'RESULT_LETTER', title: 'SECRET audiogram result' },
  { id: 'd5', type: 'EXTERNAL_REPORT', title: 'SECRET hospital report' },
  { id: 'd6', type: 'CREW_MEDICAL_REPORT', title: 'SECRET crew report' },
];

describe('documents shared to managers / HR', () => {
  it('keeps only shareable, non-clinical document types and metadata', () => {
    const docs = toWorkspaceDocuments(body);
    expect(docs.map((d) => d.id)).toEqual(['d1', 'd2', 'd3']);
    expect(JSON.stringify(docs)).not.toMatch(/SECRET|dr1|abc|shares/);
  });

  it('renders a PDF download link per document via the BFF', () => {
    const html = renderToStaticMarkup(<DocumentsView docs={toWorkspaceDocuments(body)} type="" onType={vi.fn()} />);
    expect(html).toContain(`href="${documentContentUrl('d1')}"`);
    expect(documentContentUrl('d/1')).toBe('/bff/api/documents/d%2F1/content');
    expect(html).toContain('download="DOC-2026-000123.pdf"');
    expect(html).toContain('aria-label="Download PDF: Fitness certificate – Rami Ramp"');
    expect(html).toContain('version 2');
    expect(html).toContain('Ill-health / redeployment decision letter');
    expect(html).not.toContain('SECRET');
  });

  it('filters by document type', () => {
    const docs = toWorkspaceDocuments(body);
    const html = renderToStaticMarkup(<DocumentsView docs={docs} type="RTW_PLAN" onType={vi.fn()} />);
    expect(html).toContain('Showing 1 of 3 documents');
    expect(html).not.toContain('DOC-2026-000123');
    const onType = vi.fn();
    findOne(DocumentsView({ docs, type: '', onType }), (e) => e.props.id === 'doc-type').props.onChange({ target: { value: 'JOB_OFFER' } });
    expect(onType).toHaveBeenCalledWith('JOB_OFFER');
  });

  it('shows an empty state', () => {
    expect(renderToStaticMarkup(<DocumentsView docs={[]} type="" onType={vi.fn()} />)).toContain('No documents have been shared with you');
  });
});
