import { formatKpiCount, pivotKpis, type KpiResult } from '../api/kpi';
import { humanise } from './format';

export function KpiTable({ result, caption }: { result: KpiResult; caption: string }) {
  const { groups, statuses, get } = pivotKpis(result);
  if (groups.length === 0) return <p className="muted">No data available for your scope.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{humanise(result.groupBy.replace(/([a-z])([A-Z])/g, '$1_$2'))}</th>
            {statuses.map((s) => (
              <th scope="col" key={s}>
                {humanise(s)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g}>
              <th scope="row">{g}</th>
              {statuses.map((s) => {
                const cell = get(g, s);
                return (
                  <td key={s} className={cell?.suppressed ? 'suppressed' : 'num'}>
                    {formatKpiCount(cell, result.minGroupSize)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
