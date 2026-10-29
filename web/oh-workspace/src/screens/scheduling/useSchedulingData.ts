import { useState } from 'react';
import { schedulingApi, type AppointmentType, type ClinicSite } from '../../api/scheduling';
import type { AuthClient } from '../../auth/AuthClient';
import { useAsync, type AsyncState } from '../../components/useAsync';

/** Active clinic sites (§2.2) plus the selected site code (first active site by default). */
export function useSites(client: AuthClient, onSessionExpired: () => void): { sites: AsyncState<ClinicSite[]>; siteCode: string; setSiteCode: (s: string) => void; site?: ClinicSite } {
  const sites = useAsync(() => schedulingApi(client).clinicSites().then((l) => (l ?? []).filter((s) => s.active !== false)), onSessionExpired);
  const [chosen, setSiteCode] = useState('');
  const siteCode = chosen || sites.data?.[0]?.code || '';
  return { sites, siteCode, setSiteCode, site: sites.data?.find((s) => s.code === siteCode) };
}

/** Appointment types (§5.1) – active only. */
export function useAppointmentTypes(client: AuthClient, onSessionExpired: () => void): AsyncState<AppointmentType[]> {
  return useAsync(() => schedulingApi(client).appointmentTypes().then((l) => (l ?? []).filter((t) => t.active !== false)), onSessionExpired);
}
