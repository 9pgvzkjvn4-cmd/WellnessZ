import { normaliseKpis } from '../api/kpi';
import { describeError } from '../components/format';
import { KpiTable } from '../components/KpiTable';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function Analytics({ client, onSessionExpired }: ScreenProps) {
  const kpi = useAsync(
    async () => normaliseKpis(await client.api<unknown>('/analytics/kpis/cases-by-status?groupBy=orgUnit')),
    onSessionExpired,
  );
  return (
    <section>
      <PageTitle>Analytics</PageTitle>
      <article className="tile" aria-labelledby="kpi-cases-by-status">
        <h2 id="kpi-cases-by-status">Cases by status, per organisational unit</h2>
        <p className="muted small">
          De-identified. Groups smaller than {kpi.data?.minGroupSize ?? 5} people are suppressed to protect privacy.
        </p>
        {kpi.loading && <Loading />}
        {!!kpi.error && <Alert kind="error">{describeError(kpi.error)}</Alert>}
        {kpi.data && <KpiTable result={kpi.data} caption="Cases by status per organisational unit" />}
      </article>
    </section>
  );
}
