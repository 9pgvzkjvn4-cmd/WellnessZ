import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { fetchMyAdvice, normaliseAdvice } from '../api/advice';
import { ApiError } from '../auth/AuthClient';
import { AdviceList } from './MyAdvice';

describe('My advice', () => {
  it('treats 404 as an empty list and shows a friendly empty state', async () => {
    const api = vi.fn().mockRejectedValue(new ApiError(404));
    expect(await fetchMyAdvice({ api })).toEqual([]);
    expect(api).toHaveBeenCalledWith('/me/advice');
    expect(renderToStaticMarkup(<AdviceList items={[]} />)).toContain('No advice yet.');
  });

  it('propagates other errors', async () => {
    await expect(fetchMyAdvice({ api: vi.fn().mockRejectedValue(new ApiError(500)) })).rejects.toBeInstanceOf(ApiError);
  });

  it('lists advice newest first as plain text', () => {
    const items = normaliseAdvice([
      { id: 'a1', caseRef: 'CAT-2026-000042', title: 'Wound care', text: 'Keep it <b>dry</b>.', sentAt: '2026-10-27T09:00:00Z' },
      { id: 'a2', title: 'Back care', text: 'Gentle movement.', sentAt: '2026-10-28T09:00:00Z', internalNote: 'x' },
      { title: 'no id' },
    ]);
    expect(items.map((a) => a.id)).toEqual(['a2', 'a1']);
    const html = renderToStaticMarkup(<AdviceList items={items} />);
    expect(html).toContain('Wound care');
    expect(html).toContain('CAT-2026-000042');
    expect(html).toContain('Keep it &lt;b&gt;dry&lt;/b&gt;.');
    expect(html).not.toContain('<b>dry');
  });
});
