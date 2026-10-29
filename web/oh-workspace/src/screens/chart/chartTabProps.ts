import type { ScreenProps } from '../types';

/** Props shared by every chart tab (and reused by panels embedded in the case view). */
export interface ChartTabProps extends ScreenProps {
  employeeId: string;
  /** Clinician write access (UI gating; the server enforces). */
  canWrite: boolean;
  /** Reload the chart summary after a change. */
  refreshSummary: () => void;
  /** Case context when embedded in the case view. */
  caseId?: string;
}
