import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { toPartnerReferrals } from '../api/referrals';
import { referral } from '../test/fixtures';
import { ReferralInbox } from './Referrals';
import { DocumentsView } from './Documents';
import { toSharedDocuments } from '../api/documents';

const NOW = new Date('2026-10-29T12:00:00Z');

describe('referral inbox view', () => {
  const list = toPartnerReferrals([
    referral(),
    referral({ id: 'r2', ref: 'RFL-2026-000002', urgency: 'ROUTINE', status: 'SEEN', reportDueAt: '2026-10-20T00:00:00Z', patient: { displayName: 'Ana Air' } }),
  ]);

  it('renders urgency, status and overdue badges with links to the detail', () => {
    const html = renderToStaticMarkup(<ReferralInbox referrals={list} status="" onStatus={vi.fn()} overdueOnly={false} onOverdueOnly={vi.fn()} now={NOW} />);
    expect(html).toContain('href="#/referrals/r1"');
    expect(html).toContain('badge-urgent');
    expect(html).toContain('>Overdue<');
    expect(html).toContain('>New<');
    expect(html).toContain('Showing 2 of 2 referrals');
  });

  it('filters to overdue reports', () => {
    const html = renderToStaticMarkup(<ReferralInbox referrals={list} status="" onStatus={vi.fn()} overdueOnly onOverdueOnly={vi.fn()} now={NOW} />);
    expect(html).toContain('Showing 1 of 2 referrals');
    expect(html).toContain('Ana Air');
    expect(html).not.toContain('RFL-2026-000001');
  });
});

describe('shared documents view', () => {
  it('lists documents with download links and a type filter', () => {
    const docs = toSharedDocuments([
      { id: 'd1', documentNumber: 'DOC-1', type: 'REFERRAL_LETTER', title: 'Referral letter', createdAt: '2026-10-27T09:00:00Z', shares: [{ target: 'PARTNER' }], sha256: 'x' },
      { id: 'd2', type: 'PROVIDER_REPORT', title: 'Report', createdAt: '2026-10-28T09:00:00Z' },
    ]);
    const html = renderToStaticMarkup(<DocumentsView docs={docs} type="REFERRAL_LETTER" onType={vi.fn()} />);
    expect(html).toContain('href="/bff/api/documents/d1/content"');
    expect(html).toContain('download="DOC-1.pdf"');
    expect(html).not.toContain('d2/content');
    expect(html).toContain('<option value="PROVIDER_REPORT">Provider report</option>');
    expect(renderToStaticMarkup(<DocumentsView docs={[]} type="" onType={vi.fn()} />)).toContain('No documents have been shared with your organisation.');
  });
});
