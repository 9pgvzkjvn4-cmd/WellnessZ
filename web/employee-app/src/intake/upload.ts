import { SessionExpiredError, type AuthClient } from '../auth/AuthClient';
import { errorName, log } from '../security/log';

/**
 * Upload attachments one by one after the case exists: multipart `POST /cases/{id}/attachments` with part `file`.
 * A failed file does not stop the others; a lost session aborts. The caller drops the File objects afterwards.
 */
export async function uploadAttachments(
  client: Pick<AuthClient, 'api'>,
  caseId: string,
  files: readonly Blob[],
  names: readonly string[] = [],
): Promise<{ uploaded: number; failed: number }> {
  let uploaded = 0;
  let failed = 0;
  for (const [i, file] of files.entries()) {
    const form = new FormData();
    const name = names[i] ?? (file as File).name ?? `attachment-${i + 1}`;
    form.append('file', file, name);
    try {
      await client.api(`/cases/${encodeURIComponent(caseId)}/attachments`, { method: 'POST', body: form });
      uploaded++;
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      log.warn('intake.attachment-upload-failed', { errorName: errorName(e) });
      failed++;
    }
  }
  return { uploaded, failed };
}
