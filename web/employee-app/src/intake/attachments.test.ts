import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { BffAuthClient } from '../auth/bffAuthClient';
import { addAttachments, effectiveType, MAX_FILE_BYTES, MAX_FILES, type FileLike } from './attachments';
import { initialReferralState, referralReducer } from './referralForm';
import { uploadAttachments } from './upload';

const f = (name: string, type: string, size = 1000): FileLike => ({ name, type, size });

describe('attachment validation', () => {
  it('accepts JPEG, PNG and PDF and rejects other types', () => {
    const r = addAttachments([], [f('a.jpg', 'image/jpeg'), f('b.png', 'image/png'), f('c.pdf', 'application/pdf'), f('d.gif', 'image/gif'), f('e.heic', 'image/heic'), f('f.docx', '')]);
    expect(r.files.map((x) => x.name)).toEqual(['a.jpg', 'b.png', 'c.pdf']);
    expect(r.problems).toEqual([
      { kind: 'type', name: 'd.gif' },
      { kind: 'type', name: 'e.heic' },
      { kind: 'type', name: 'f.docx' },
    ]);
  });

  it('falls back to the extension when the WebView reports no MIME type', () => {
    expect(effectiveType(f('scan.PDF', ''))).toBe('application/pdf');
    expect(addAttachments([], [f('photo.jpeg', '')]).files).toHaveLength(1);
  });

  it('rejects files over 5 MB (exactly 5 MB is allowed)', () => {
    const r = addAttachments([], [f('ok.jpg', 'image/jpeg', MAX_FILE_BYTES), f('big.jpg', 'image/jpeg', MAX_FILE_BYTES + 1)]);
    expect(r.files.map((x) => x.name)).toEqual(['ok.jpg']);
    expect(r.problems).toEqual([{ kind: 'size', name: 'big.jpg' }]);
  });

  it('allows at most 5 files in total and reports the overflow once', () => {
    const existing = [1, 2, 3, 4].map((i) => f(`${i}.png`, 'image/png'));
    const r = addAttachments(existing, [f('5.png', 'image/png'), f('6.png', 'image/png'), f('7.png', 'image/png')]);
    expect(r.files).toHaveLength(MAX_FILES);
    expect(r.problems).toEqual([{ kind: 'count', max: 5 }]);
  });

  it('keeps files only in reducer state and clears them', () => {
    let s = initialReferralState<FileLike>('en');
    s = referralReducer(s, { type: 'addFiles', files: [f('a.jpg', 'image/jpeg'), f('x.exe', 'application/octet-stream')] });
    expect(s.files).toHaveLength(1);
    expect(s.attachmentProblems).toHaveLength(1);
    s = referralReducer(s, { type: 'removeFile', index: 0 });
    expect(s.files).toEqual([]);
    s = referralReducer(s, { type: 'addFiles', files: [f('a.jpg', 'image/jpeg')] });
    expect(referralReducer(s, { type: 'clearFiles' }).files).toEqual([]);
  });
});

describe('attachment upload', () => {
  const blob = (name: string) => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/png' });

  it('posts each file as multipart part "file" to /cases/{id}/attachments and continues after a failure', async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce({ id: 'a1' })
      .mockRejectedValueOnce(new ApiError(413))
      .mockResolvedValueOnce({ id: 'a3' });
    const r = await uploadAttachments({ api }, 'c-1', [blob('1.png'), blob('2.png'), blob('3.png')]);
    expect(r).toEqual({ uploaded: 2, failed: 1 });
    expect(api).toHaveBeenCalledTimes(3);
    const [path, init] = api.mock.calls[0]!;
    expect(path).toBe('/cases/c-1/attachments');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBeInstanceOf(File);
    expect(((init.body as FormData).get('file') as File).name).toBe('1.png');
  });

  it('aborts when the session expires', async () => {
    const api = vi.fn().mockRejectedValue(new SessionExpiredError());
    await expect(uploadAttachments({ api }, 'c-1', [blob('1.png')])).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('BFF client sends FormData as multipart without a JSON content type', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 201 }));
    const form = new FormData();
    form.append('file', blob('x.png'));
    await new BffAuthClient(fetchMock as unknown as typeof fetch, () => {}).api('/cases/c/attachments', { method: 'POST', body: form });
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init.body).toBe(form);
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });
});
