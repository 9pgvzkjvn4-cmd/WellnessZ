import { S } from '../strings';

/** Provider report / invoice uploads: PDF, JPEG or PNG up to 10 MB (checked again by the server). */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const UPLOAD_TYPES = ['application/pdf', 'image/jpeg', 'image/png'] as const;
const EXTENSIONS: Record<string, (typeof UPLOAD_TYPES)[number]> = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png' };
/** `accept` attribute for the file inputs. */
export const UPLOAD_ACCEPT = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png';

export interface UploadCandidate {
  name: string;
  type: string;
  size: number;
}

/**
 * Validates a chosen file; returns an error message or undefined. The browser-reported MIME type wins; an empty type
 * (some platforms) falls back to the extension. A declared type outside the list is rejected even with a .pdf name.
 */
export function validateUpload(file: UploadCandidate | null | undefined): string | undefined {
  if (!file) return S.validation.fileRequired;
  const ext = file.name.toLowerCase().split('.').pop() ?? '';
  const type = file.type ? file.type.toLowerCase() : EXTENSIONS[ext];
  if (!type || !(UPLOAD_TYPES as readonly string[]).includes(type)) return S.validation.fileType;
  if (file.type && EXTENSIONS[ext] && EXTENSIONS[ext] !== type) return S.validation.fileType;
  if (file.size <= 0) return S.validation.fileEmpty;
  if (file.size > MAX_UPLOAD_BYTES) return S.validation.fileSize(MAX_UPLOAD_BYTES / (1024 * 1024));
  return undefined;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
