/**
 * Documents shared with the caller's organisation – `GET /partner/documents` (PARTNER_PROVIDER or TPA_ADJUSTER;
 * contract §2.3) and the `documents` of `GET /partner/claims/{id}`; content via `GET /documents/{id}/content`.
 * Only display metadata is kept: shares, signer, hashes, employee / case ids are dropped.
 */
import { downloadName, humanise } from '../components/format';
import { mapList, num, str } from './read';
import { seg } from './errors';

export interface SharedDocument {
  id: string;
  documentNumber?: string;
  type?: string;
  title: string;
  date?: string;
  version?: number;
}

export const DOCUMENT_TYPE_LABELS: Record<string, string> = {
  REFERRAL_LETTER: 'Referral letter',
  PROVIDER_REPORT: 'Provider report',
  PROVIDER_INVOICE: 'Provider invoice',
  PREAUTH_REQUEST: 'Pre-authorisation request',
  FIRST_REPORT_OF_INJURY: 'First report of injury',
  FITNESS_CERTIFICATE: 'Fitness certificate',
  RESTRICTION_NOTE: 'Restriction note',
  RTW_PLAN: 'Return-to-work plan',
  REDACTED_COPY: 'Redacted copy',
  EXTERNAL_REPORT: 'External report',
  INSURER_ACKNOWLEDGEMENT: 'Insurer acknowledgement',
};

export const documentTypeLabel = (type?: string) => (type ? (DOCUMENT_TYPE_LABELS[type] ?? humanise(type)) : '');

export const toSharedDocuments = (body: unknown): SharedDocument[] =>
  mapList(body, (r) => {
    const id = str(r.id) ?? str(r.documentId);
    if (!id) return undefined;
    const type = str(r.type);
    return {
      id,
      documentNumber: str(r.documentNumber),
      type,
      title: str(r.title) ?? (documentTypeLabel(type) || id),
      date: str(r.signedAt) ?? str(r.sharedAt) ?? str(r.createdAt),
      version: num(r.version),
    };
  }).sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));

/** Same-origin download URL (cookie session; GET needs no CSRF header; the server audits each download). */
export const documentContentUrl = (documentId: string) => `/bff/api/documents/${seg(documentId)}/content`;
export const documentFileName = (d: Pick<SharedDocument, 'id' | 'documentNumber'>) => downloadName(d.documentNumber ?? d.id);
