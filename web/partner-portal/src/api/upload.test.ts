import { describe, expect, it } from 'vitest';
import { formatBytes, MAX_UPLOAD_BYTES, validateUpload } from './upload';

const f = (name: string, type: string, size = 1000) => ({ name, type, size });

describe('upload validation', () => {
  it.each([
    ['report.pdf', 'application/pdf'],
    ['scan.JPG', 'image/jpeg'],
    ['photo.jpeg', 'image/jpeg'],
    ['invoice.png', 'image/png'],
    ['report.pdf', ''],
  ])('accepts %s (%s)', (name, type) => {
    expect(validateUpload(f(name, type))).toBeUndefined();
  });

  it.each([
    ['report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['image.gif', 'image/gif'],
    ['script.html', 'text/html'],
    ['noext', ''],
    ['fake.pdf', 'text/html'],
    ['mismatch.png', 'application/pdf'],
  ])('rejects %s (%s)', (name, type) => {
    expect(validateUpload(f(name, type))).toBe('Only PDF, JPEG or PNG files can be uploaded.');
  });

  it('enforces the 10 MB limit and rejects empty files', () => {
    expect(MAX_UPLOAD_BYTES).toBe(10_485_760);
    expect(validateUpload(f('a.pdf', 'application/pdf', MAX_UPLOAD_BYTES))).toBeUndefined();
    expect(validateUpload(f('a.pdf', 'application/pdf', MAX_UPLOAD_BYTES + 1))).toBe('The file must not be larger than 10 MB.');
    expect(validateUpload(f('a.pdf', 'application/pdf', 0))).toBe('The file is empty.');
    expect(validateUpload(undefined)).toBe('Choose a file to upload.');
  });

  it('formats sizes', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
