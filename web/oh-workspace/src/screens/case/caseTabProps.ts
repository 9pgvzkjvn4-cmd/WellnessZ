import type { CaseDetail } from '../../api/types';
import type { ScreenProps } from '../types';

export interface CaseTabProps extends ScreenProps {
  caseId: string;
  detail: CaseDetail;
  /** Reload case detail + header after a change. */
  refresh: () => void;
}

/** `/cases/{id}` + suffix with the id URI-encoded. */
export const casePath = (caseId: string, suffix = ''): string => `/cases/${encodeURIComponent(caseId)}${suffix}`;
