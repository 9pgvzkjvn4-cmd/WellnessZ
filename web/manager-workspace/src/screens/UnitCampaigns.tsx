import { CAMPAIGNS_PATH, toParticipation, type UnitParticipation } from '../api/unit';
import { describeError } from '../components/format';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** Unit participation per campaign; suppressed rows show no figures. Hook-free. */
export function ParticipationTable({ data }: { data: UnitParticipation }) {
  if (data.rows.length === 0) return <p className="muted">There are no health campaigns for your unit.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Participation of your unit in health campaigns</caption>
        <thead>
          <tr>
            <th scope="col">Campaign</th>
            <th scope="col">Period</th>
            <th scope="col">Invited</th>
            <th scope="col">Took part</th>
            <th scope="col">Participation</th>
          </tr>
        </thead>
        <tbody>
          {data.rows.map((r) => (
            <tr key={r.key}>
              <th scope="row">
                {r.title}
                {r.unitCode && <span className="muted small"> · {r.unitCode}</span>}
              </th>
              <td>{r.period ?? '–'}</td>
              {r.suppressed ? (
                <td colSpan={3} className="muted">
                  Fewer than {data.minGroupSize} – suppressed
                </td>
              ) : (
                <>
                  <td>{r.invited ?? '–'}</td>
                  <td>{r.participated ?? '–'}</td>
                  <td>
                    {r.pct !== undefined ? (
                      <>
                        <meter min={0} max={100} value={r.pct} aria-hidden="true" /> {r.pct}%
                      </>
                    ) : (
                      '–'
                    )}
                  </td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** `#/campaigns` (MANAGER) – unit participation % in health-promotion campaigns (aggregated, small cells suppressed). */
export function UnitCampaigns({ client, onSessionExpired }: ScreenProps) {
  const data = useAsync(async () => toParticipation(await client.api<unknown>(CAMPAIGNS_PATH)), onSessionExpired);
  return (
    <section>
      <PageTitle>Health campaigns</PageTitle>
      <p className="lead">How many people in your unit take part in health-promotion campaigns. Figures are anonymous and aggregated.</p>
      <div className="banner" role="note">
        <strong>Anonymous figures only</strong>
        <span className="small">You never see who took part. Groups smaller than {data.data?.minGroupSize ?? 5} people are suppressed to protect privacy.</span>
      </div>
      {data.loading && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && <ParticipationTable data={data.data} />}
    </section>
  );
}
