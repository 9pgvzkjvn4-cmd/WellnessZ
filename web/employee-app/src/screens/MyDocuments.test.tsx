import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { normaliseDocuments, type MyDocument } from '../api/portal';
import { portalStrings } from '../i18n/portal';
import { SESSION, stubClient } from '../test/client';
import { DOCUMENTS } from '../test/portalFixtures';
import { findAll, findOne, textOf } from '../test/tree';
import { DocumentList, MyDocuments, type DocumentListProps } from './MyDocuments';

const docs = normaliseDocuments(DOCUMENTS);

function props(over: Partial<DocumentListProps> = {}): DocumentListProps {
  return {
    t: portalStrings('en'),
    lang: 'en',
    docs,
    opened: new Set(),
    linkFor: (d: MyDocument) => `/bff/api/documents/${d.id}/content`,
    onOpen: vi.fn(),
    onAcknowledge: vi.fn(),
    ...over,
  };
}

describe('My documents', () => {
  it('renders each document with type, number, badges and BFF open/download links', () => {
    const html = renderToStaticMarkup(<DocumentList {...props()} />);
    expect(html).toContain('Fitness certificate – Ground duties');
    expect(html).toContain('DOC-2026-000101');
    expect(html).toContain('Version 2'); // d2
    expect(html).toContain('Please acknowledge');
    expect(html).toContain('Acknowledged');
    expect(html).toContain('Replaced by a newer version');
    expect(html).toContain('>New<');
    expect(html).toContain('href="/bff/api/documents/d1/content" target="_blank" rel="noopener noreferrer"');
    expect(html).toContain('download="DOC-2026-000101.pdf"');
    expect(html).toContain('href="#/rtw"');
    expect(html).toContain('href="#/job-offers"');
    expect(html).not.toContain('Old certificate</h2><span class="badge badge-doc-action');
    expect(html).not.toContain('d7'); // revoked document never listed
  });

  it('acknowledge button calls back with the document and shows the busy state', () => {
    const p = props();
    const btns = findAll(DocumentList(p), (e) => e.type === 'button' && textOf(e.props.children) === 'I have read this');
    expect(btns).toHaveLength(2); // d8 + d1
    btns[1]!.props.onClick();
    expect(p.onAcknowledge).toHaveBeenCalledWith(expect.objectContaining({ id: 'd1' }));
    const busy = findOne(DocumentList(props({ busyId: 'd1' })), (e) => e.type === 'button' && e.props.disabled === true);
    expect(textOf(busy.props.children)).toBe('Saving…');
  });

  it('shows the confirmation state once acknowledged', () => {
    const acked = docs.map((d) => (d.id === 'd1' ? { ...d, acknowledgedAt: '2026-10-29T10:00:00Z' } : d));
    const html = renderToStaticMarkup(<DocumentList {...props({ docs: acked })} />);
    expect(html).toMatch(/✓ Acknowledged .*2026/);
    expect(findAll(DocumentList(props({ docs: acked })), (e) => e.type === 'button')).toHaveLength(1); // only d8 left
  });

  it('marks a document as opened when its link is used', () => {
    const p = props();
    const link = findAll(DocumentList(p), (e) => e.type === 'a' && e.props.href === '/bff/api/documents/d5/content')[0]!;
    link.props.onClick();
    expect(p.onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 'd5' }));
    expect(renderToStaticMarkup(<DocumentList {...props({ opened: new Set(['d5']) })} />).match(/>New</g)).toHaveLength(2);
  });

  it('native mode renders an open button instead of an authenticated link', () => {
    const p = props({ linkFor: () => undefined, openingId: 'd5' });
    const tree = DocumentList(p);
    expect(findAll(tree, (e) => e.type === 'a' && String(e.props.href).includes('/content'))).toHaveLength(0);
    expect(findAll(tree, (e) => e.type === 'button' && textOf(e.props.children) === 'Opening…')).toHaveLength(1);
    findAll(tree, (e) => e.type === 'button' && textOf(e.props.children) === 'Open PDF')[0]!.props.onClick();
    expect(p.onOpen).toHaveBeenCalled();
  });

  it('shows a friendly empty state', () => {
    expect(renderToStaticMarkup(<DocumentList {...props({ docs: [] })} />)).toContain('No documents yet.');
  });

  it('renders in Arabic right-to-left and in French', () => {
    const ar = renderToStaticMarkup(<MyDocuments client={stubClient()} session={SESSION} onSessionExpired={vi.fn()} lang="ar" />);
    expect(ar).toMatch(/^<section class="portal" dir="rtl" lang="ar"><h1[^>]*>مستنداتي<\/h1>/);
    expect(ar).toContain('جارٍ التحميل…');
    const view = renderToStaticMarkup(<DocumentList {...props({ t: portalStrings('ar'), lang: 'ar' })} />);
    expect(view).toContain('لقد قرأت هذا المستند');
    expect(view).toContain('<bdi>DOC-2026-000101</bdi>');
    const fr = renderToStaticMarkup(<DocumentList {...props({ t: portalStrings('fr'), lang: 'fr' })} />);
    expect(fr).toContain('J’ai lu ce document');
    expect(fr).toContain('Ouvrir le PDF');
  });
});
