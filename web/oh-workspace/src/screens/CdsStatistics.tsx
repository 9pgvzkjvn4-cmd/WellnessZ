import { useState } from 'react';
import { recordApi } from '../api/record';
import { formatDay, humanise, isoDay } from '../components/format';
import { Empty, ErrorAlert, Field, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** `#/cds/statistics` – CDS alerts fired / overridden (E03-BL-013). Overrides always carry a reason. */
export function CdsStatistics({ client, onSessionExpired }: ScreenProps) {
  const today = new Date();
  const [range, setRange] = useState({ from: isoDay(new Date(today.getTime() - 30 * 86400000)), to: isoDay(today) });
  const [applied, setApplied] = useState(range);
  const s = useAsync(() => recordApi(client).cdsStatistics(applied.from, applied.to), onSessionExpired, [applied]);
  const d = s.data;
  return (
    <section>
      <PageTitle sub={`Alerts from ${formatDay(applied.from)} to ${formatDay(applied.to)}. Target: under 10 % overridden without a reason (a reason is mandatory).`}>CDS statistics</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
        <Field label="From">{(id) => <input id={id} type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {s.loading && <Loading />}
      {!!s.error && <ErrorAlert error={s.error} />}
      {d && (
        <>
          <div className="tiles">
            <div className="tile"><span className="tile-value">{d.fired}</span> alerts fired</div>
            <div className="tile"><span className="tile-value">{d.overridden}</span> overridden</div>
            <div className="tile"><span className="tile-value">{(d.overrideRate * (d.overrideRate <= 1 ? 100 : 1)).toFixed(1)} %</span> override rate</div>
            <div className="tile"><span className="tile-value">{d.overriddenWithoutReason}</span> overridden without reason</div>
          </div>
          {d.byCode.length === 0 ? (
            <Empty>No alerts in this period.</Empty>
          ) : (
            <div className="table-wrap">
              <table>
                <caption>By alert type</caption>
                <thead>
                  <tr>
                    <th scope="col">Alert</th>
                    <th scope="col" className="num">Fired</th>
                    <th scope="col" className="num">Overridden</th>
                  </tr>
                </thead>
                <tbody>
                  {d.byCode.map((c) => (
                    <tr key={c.code}>
                      <th scope="row">{humanise(c.code)}</th>
                      <td className="num">{c.fired ?? '–'}</td>
                      <td className="num">{c.overridden ?? '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
