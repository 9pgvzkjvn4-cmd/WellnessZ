import { useState } from 'react';
import { PROMOTION_RISKS, promotionApi } from '../../api/promotion';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { hotspots, insightValue } from '../../domain/promotion';
import type { ScreenProps } from '../types';

/** `#/promotion/insights` – anonymised unit hotspots from cases, incidents and screening results (E12). No person-level data. */
export function PromotionInsights({ client, session, onSessionExpired }: ScreenProps) {
  const data = useAsync(() => promotionApi(client).insights(), onSessionExpired);
  const [risk, setRisk] = useState('');
  const rows = hotspots(data.data ?? [], risk || undefined);
  const reported = (data.data ?? []).filter((r) => !r.suppressed);
  const plan = can(session.roles, 'promotionCampaigns');
  return (
    <section>
      <PageTitle sub="Unit-level indicators only. Units below the minimum group size are suppressed and never shown as numbers.">Health insights</PageTitle>
      {data.loading && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {data.data && (
        <div className="tiles" role="group" aria-label="Hotspots by risk">
          {PROMOTION_RISKS.map((r) => (
            <KpiTile key={r} label={humanise(r)} value={reported.filter((x) => x.risk === r).length} target="reported units" />
          ))}
        </div>
      )}
      <div className="filters inline-form">
        <Field label="Risk">{(id) => <select id={id} value={risk} onChange={(e) => setRisk(e.target.value)}><SelectOptions values={PROMOTION_RISKS} empty="All risks" /></select>}</Field>
      </div>
      {data.data && rows.length === 0 && <Empty>No insights for this risk.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Hotspots, highest value first per risk</caption>
            <thead><tr><th scope="col">Unit</th><th scope="col">Risk</th><th scope="col">Indicator</th><th scope="col" className="num">Value</th><th scope="col">Period</th>{plan && <th scope="col"><span className="visually-hidden">Actions</span></th>}</tr></thead>
            <tbody>
              {rows.map((r) =>
                r.suppressed ? (
                  <tr key={`${r.unitCode}-${r.risk}-${r.indicator}`} className="row-suppressed">
                    <th scope="row">{r.unitCode}</th>
                    <td>{humanise(r.risk)}</td>
                    <td>{r.indicator}</td>
                    <td colSpan={plan ? 3 : 2} className="suppressed">Suppressed – group too small to report</td>
                  </tr>
                ) : (
                  <tr key={`${r.unitCode}-${r.risk}-${r.indicator}`}>
                    <th scope="row">{r.unitCode}</th>
                    <td><ToneBadge tone="amber">{humanise(r.risk)}</ToneBadge></td>
                    <td>{r.indicator}</td>
                    <td className="num">{insightValue(r)}</td>
                    <td>{r.period}</td>
                    {plan && (
                      <td>
                        <a href={`#/promotion/campaigns?risk=${encodeURIComponent(r.risk)}&unit=${encodeURIComponent(r.unitCode)}&insight=${encodeURIComponent(`${r.unitCode}:${r.risk}:${r.period}`)}`}>Plan campaign</a>
                      </td>
                    )}
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
