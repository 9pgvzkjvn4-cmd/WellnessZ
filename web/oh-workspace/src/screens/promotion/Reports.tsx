import { useState } from 'react';
import { campaignReportCsvUrl, promotionApi, REPORT_GROUP_BY } from '../../api/promotion';
import { can } from '../../auth/roles';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { outcomeDelta, participationView } from '../../domain/promotion';
import type { ScreenProps } from '../types';

const num = (v: number | null | undefined) => (typeof v === 'number' && Number.isFinite(v) ? (Number.isInteger(v) ? String(v) : v.toFixed(1)) : '–');
const ARROW = { up: '▲', down: '▼', flat: '●', none: '' } as const;

/** `#/promotion/reports` – participation and outcome deltas by unit, small groups suppressed, CSV export (E12). */
export function PromotionReports({ client, session, onSessionExpired }: ScreenProps) {
  const api = promotionApi(client);
  // GET /promotion/campaigns is HEALTH_PROMOTION_LEAD only – CMO enters the campaign id instead.
  const listed = can(session.roles, 'promotionCampaigns');
  const campaigns = useAsync(() => (listed ? api.campaigns() : Promise.resolve([])), onSessionExpired, [listed]);
  const [campaignId, setCampaignId] = useState('');
  const [groupBy, setGroupBy] = useState<string>('DEPARTMENT');
  const id = campaignId.trim();
  const report = useAsync(() => (id ? api.report(id, groupBy) : Promise.resolve(undefined)), onSessionExpired, [id, groupBy]);
  const r = report.data;
  const suppressedRows = r ? r.participation.filter((p) => participationView(p).suppressed).length : 0;

  return (
    <section>
      <PageTitle sub="Aggregated participation and outcome change per unit. Groups below the minimum size are suppressed – also in the CSV.">Campaign reports</PageTitle>
      <div className="filters inline-form">
        {listed ? (
          <Field label="Campaign">
            {(fid) => (
              <select id={fid} value={campaignId} onChange={(e) => setCampaignId(e.target.value)}>
                <option value="">Choose…</option>
                {(campaigns.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
              </select>
            )}
          </Field>
        ) : (
          <Field label="Campaign id">{(fid) => <input id={fid} size={36} value={campaignId} onChange={(e) => setCampaignId(e.target.value)} />}</Field>
        )}
        <Field label="Group by">{(fid) => <select id={fid} value={groupBy} onChange={(e) => setGroupBy(e.target.value)}><SelectOptions values={REPORT_GROUP_BY} /></select>}</Field>
        {id && <a className="button secondary" href={campaignReportCsvUrl(id, groupBy)} download={`campaign-report-${groupBy.toLowerCase()}.csv`} rel="noopener">Download CSV</a>}
      </div>
      {!!campaigns.error && <ErrorAlert error={campaigns.error} />}
      {!id && <Empty>Choose a campaign to see its report.</Empty>}
      {id && report.loading && <Loading />}
      {!!report.error && <ErrorAlert error={report.error} />}
      {r && (
        <>
          <h2>Participation by {humanise(groupBy).toLowerCase()}</h2>
          {r.participation.length === 0 ? <Empty>No participation recorded yet.</Empty> : (
            <div className="table-wrap">
              <table>
                <caption>Participation{suppressedRows ? ` – ${suppressedRows} group${suppressedRows === 1 ? '' : 's'} suppressed` : ''}</caption>
                <thead><tr><th scope="col">Unit</th><th scope="col" className="num">Invited</th><th scope="col" className="num">Participated</th><th scope="col" className="num">Participation</th></tr></thead>
                <tbody>
                  {r.participation.map((p) => {
                    const v = participationView(p);
                    return v.suppressed ? (
                      <tr key={p.key} className="row-suppressed">
                        <th scope="row">{p.key}</th>
                        <td colSpan={3} className="suppressed">Suppressed – group too small to report</td>
                      </tr>
                    ) : (
                      <tr key={p.key}>
                        <th scope="row">{p.key}</th>
                        <td className="num">{v.invited}</td>
                        <td className="num">{v.participated}</td>
                        <td className="num">{v.pct}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          <h2>Outcomes</h2>
          {r.outcomes.length === 0 ? <Empty>No outcome measures yet.</Empty> : (
            <div className="table-wrap">
              <table>
                <caption>Outcome change from baseline (aggregated)</caption>
                <thead><tr><th scope="col">Metric</th><th scope="col" className="num">Baseline</th><th scope="col" className="num">Current</th><th scope="col">Change</th></tr></thead>
                <tbody>
                  {r.outcomes.map((o) => {
                    const d = outcomeDelta(o);
                    return (
                      <tr key={o.metric}>
                        <th scope="row">{humanise(o.metric)}</th>
                        <td className="num">{num(o.baseline)}</td>
                        <td className="num">{num(o.current)}</td>
                        <td>
                          {d.direction === 'none' ? '–' : (
                            <ToneBadge tone={d.tone} title={d.tone === 'green' ? 'Improvement' : d.tone === 'red' ? 'Worse than baseline' : 'No change'}>
                              <span aria-hidden="true">{ARROW[d.direction]} </span>{d.text}
                              <span className="visually-hidden">{d.tone === 'green' ? ' (improvement)' : d.tone === 'red' ? ' (worse)' : ''}</span>
                            </ToneBadge>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
