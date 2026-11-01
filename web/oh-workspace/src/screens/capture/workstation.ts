import { useSyncExternalStore } from 'react';

/**
 * The capture station of this workstation (site + station code, e.g. LHR / LHR-OH-ROOM-1) and the capture sessions awaiting
 * physician sign-off that were opened here. Held in memory only – never in browser storage (no personal data persisted);
 * it survives navigation inside the workspace and is cleared on reload / sign-out.
 */
export interface StationChoice {
  siteCode: string;
  stationCode: string;
}

export interface PendingSignOff {
  sessionId: string;
  category: string;
  employeeId: string;
  employeeName?: string | null;
  acceptedAt: string;
}

interface State {
  station?: StationChoice;
  pending: PendingSignOff[];
}

let state: State = { pending: [] };
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export function setStation(station: StationChoice | undefined): void {
  state = { ...state, station };
  emit();
}

export function useStation(): StationChoice | undefined {
  return useSyncExternalStore(subscribe, () => state.station);
}

/** Remember an ECG / ABPM capture whose device interpretation is unverified until a physician signs it off. */
export function rememberPendingSignOff(p: PendingSignOff): void {
  state = { ...state, pending: [p, ...state.pending.filter((x) => x.sessionId !== p.sessionId)].slice(0, 50) };
  emit();
}

export function forgetPendingSignOff(sessionId: string): void {
  state = { ...state, pending: state.pending.filter((x) => x.sessionId !== sessionId) };
  emit();
}

export function usePendingSignOffs(): PendingSignOff[] {
  return useSyncExternalStore(subscribe, () => state.pending);
}

/** Test helper. */
export function resetWorkstation(): void {
  state = { pending: [] };
  emit();
}
