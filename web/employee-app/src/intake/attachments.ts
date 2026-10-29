/**
 * Client-side attachment rules (contract §3 `POST /cases/{id}/attachments`: JPEG/PNG/PDF, ≤ 5 MB, max 5 per case).
 * The server enforces the same rules; this only gives fast feedback. Files are held in memory until they are
 * uploaded after case creation and are dropped afterwards (success or failure) – never persisted on the device.
 */
export const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'application/pdf'] as const;
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const MAX_FILES = 5;
/** `accept` attribute for the file picker. */
export const ACCEPT = 'image/jpeg,image/png,application/pdf,.jpg,.jpeg,.png,.pdf';

/** Minimal shape so validation is testable without a DOM `File`. */
export interface FileLike {
  name: string;
  type: string;
  size: number;
}

export type AttachmentProblem =
  | { kind: 'type'; name: string }
  | { kind: 'size'; name: string }
  | { kind: 'count'; max: number };

const EXT_TYPES: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', pdf: 'application/pdf' };

/** MIME type of a file; some WebViews report an empty type, then the extension decides. */
export function effectiveType(f: FileLike): string {
  if (f.type) return f.type.toLowerCase();
  const ext = f.name.toLowerCase().split('.').pop() ?? '';
  return EXT_TYPES[ext] ?? '';
}

/**
 * Add `incoming` files to `current`. Invalid files (type/size) are rejected individually; once the total reaches
 * MAX_FILES the remaining files are rejected with a single `count` problem.
 */
export function addAttachments<T extends FileLike>(
  current: readonly T[],
  incoming: readonly T[],
): { files: T[]; problems: AttachmentProblem[] } {
  const files = [...current];
  const problems: AttachmentProblem[] = [];
  let overflow = false;
  for (const f of incoming) {
    if (!(ALLOWED_TYPES as readonly string[]).includes(effectiveType(f))) {
      problems.push({ kind: 'type', name: f.name });
    } else if (f.size > MAX_FILE_BYTES) {
      problems.push({ kind: 'size', name: f.name });
    } else if (files.length >= MAX_FILES) {
      overflow = true;
    } else {
      files.push(f);
    }
  }
  if (overflow) problems.push({ kind: 'count', max: MAX_FILES });
  return { files, problems };
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
