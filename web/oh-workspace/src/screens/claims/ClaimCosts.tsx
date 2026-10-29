import { useState, type FormEvent } from 'react';
import { claimsApi, COST_CATEGORIES, erpExportContentUrl, type BudgetRow, type ErpExport } from '../../api/claims';
import { COST_GROUP_BY, treatmentApi, treatmentCostsExportUrl } from '../../api/treatment';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { formatDate, humanise } from '../../components/format';
import { LineTrend, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { previousMonth, todayIso } from '../../domain/absence';
import { budgetTone, isMonth } from '../../domain/claims';
import { asMoney, CURRENCIES, formatMoney, moneyNumber, parseAmount } from '../../domain/money';
import { addDays } from '../../domain/treatment';
import type { ScreenProps } from '../types';

const Suppressed = ({ cols }: { cols: number }) => <td colSpan={cols} className="suppressed">Suppressed – group too small to report</td>;

function Rollup({ props, filter }: { props: ScreenProps; filter: { groupBy: string; from: string; to: string } }) {
  const rollup = useAsync(() => claimsApi(props.client).rollup(filter), props.onSessionExpired, [filter]);
  const treatment = useAsync(() => treatmentApi(props.client).costs(filter), props.onSessionExpired, [filter]);
  const rows = rollup.data ?? [];
  const trows = treatment.data ?? [];
  return (
    <>
      <h2>Claim costs by {humanise(filter.groupBy).toLowerCase()}</h2>
      {rollup.loading && <Loading />}
      {!!rollup.error && <ErrorAlert error={rollup.error} />}
      {rollup.data && rows.length === 0 && <Empty>No claim costs in this period.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Claim cost roll-up (all cost kinds, incl. treatment estimates and invoices)</caption>
            <thead>
              <tr>
                <th scope="col">Unit</th>
                <th scope="col" className="num">Cases</th>
                {COST_CATEGORIES.map((c) => <th key={c} scope="col" className="num">{humanise(c)}</th>)}
                <th scope="col" className="num">Total</th>
                <th scope="col" className="num">Per employee</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key} className={r.suppressed ? 'row-suppressed' : undefined}>
                  <th scope="row">{r.label}</th>
                  {r.suppressed ? (
                    <Suppressed cols={COST_CATEGORIES.length + 3} />
                  ) : (
                    <>
                      <td className="num">{r.cases ?? '–'}</td>
                      {COST_CATEGORIES.map((c) => <td key={c} className="num">{formatMoney(r[c])}</td>)}
                      <td className="num"><strong>{formatMoney(r.total)}</strong></td>
                      <td className="num">{formatMoney(r.costPerEmployee)}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h2>Treatment costs (pre-authorised vs invoiced)</h2>
      <a className="button secondary" href={treatmentCostsExportUrl(filter)} download={`treatment-costs-${filter.from}-${filter.to}.csv`} rel="noopener">Download CSV</a>
      {treatment.loading && <Loading />}
      {!!treatment.error && <ErrorAlert error={treatment.error} />}
      {treatment.data && trows.length === 0 && <Empty>No treatment costs in this period.</Empty>}
      {trows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Treatment cost roll-up</caption>
            <thead><tr><th scope="col">Unit</th><th scope="col" className="num">Cases</th><th scope="col" className="num">Estimated</th><th scope="col" className="num">Invoiced</th><th scope="col" className="num">Average per case</th></tr></thead>
            <tbody>
              {trows.map((r) => (
                <tr key={r.key} className={r.suppressed ? 'row-suppressed' : undefined}>
                  <th scope="row">{r.label}</th>
                  {r.suppressed ? (
                    <Suppressed cols={4} />
                  ) : (
                    <>
                      <td className="num">{r.cases ?? '–'}</td>
                      <td className="num">{formatMoney(r.estimated)}</td>
                      <td className="num">{formatMoney(r.invoiced)}</td>
                      <td className="num">{formatMoney(r.avgPerCase)}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function BudgetVsActual({ props, groupBy }: { props: ScreenProps; groupBy: string }) {
  const api = claimsApi(props.client);
  const [year, setYear] = useState(new Date().getFullYear());
  const data = useAsync(() => api.budgetVsActual({ year, groupBy }), props.onSessionExpired, [year, groupBy]);
  const canWrite = can(props.session.roles, 'budgetWrite');
  const action = useAction(props.onSessionExpired);
  const [edit, setEdit] = useState<{ row: BudgetRow; amount: string; currency: string; error?: string }>();
  const rows = data.data ?? [];

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!edit) return;
    const amount = parseAmount(edit.amount);
    if (!amount) return setEdit({ ...edit, error: 'Enter an amount such as 250000.00' });
    const ok = await action.run(async () => { await api.setBudget(year, edit.row.unitCode, { amount, currency: edit.currency }); return `Budget for ${edit.row.label} ${year} saved.`; });
    if (ok) {
      setEdit(undefined);
      data.reload();
    }
  }

  return (
    <section aria-labelledby="bva">
      <h2 id="bva">Budget vs actual</h2>
      <div className="filters inline-form">
        <Field label="Year">{(id) => <input id={id} type="number" min={2020} max={2100} value={year} onChange={(e) => setYear(Number(e.target.value) || year)} />}</Field>
      </div>
      <ActionFeedback error={edit ? undefined : action.error} message={action.message} />
      {data.loading && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {data.data && rows.length === 0 && <Empty>No budgets for {year}.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Budget vs actual {year}</caption>
            <thead><tr><th scope="col">Unit</th><th scope="col" className="num">Budget</th><th scope="col" className="num">Actual</th><th scope="col" className="num">Variance</th><th scope="col">Status</th><th scope="col">Trend</th>{canWrite && <th scope="col"><span className="visually-hidden">Actions</span></th>}</tr></thead>
            <tbody>
              {rows.map((r) => {
                const t = budgetTone(r.variancePct);
                return (
                  <tr key={r.unitCode}>
                    <th scope="row">{r.label}</th>
                    <td className="num">{formatMoney(r.budget)}</td>
                    <td className="num">{formatMoney(r.actual)}</td>
                    <td className="num">{formatMoney(r.variance)}</td>
                    <td><ToneBadge tone={t.tone}>{t.label}</ToneBadge></td>
                    <td className="trend-cell">
                      <LineTrend
                        labels={r.trend.map((p) => p.month)}
                        label={`Monthly actual cost for ${r.label}`}
                        series={[{ name: 'Actual', values: r.trend.map((p) => { const m = asMoney(p.actual); return m ? moneyNumber(m) : null; }), format: (v) => formatMoney(v) }]}
                      />
                    </td>
                    {canWrite && (
                      <td><button type="button" className="secondary small-button" aria-label={`Set budget for ${r.label}`} onClick={() => { action.reset(); const b = asMoney(r.budget); setEdit({ row: r, amount: b?.amount ?? '', currency: b?.currency ?? 'GBP' }); }}>Set budget</button></td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {edit && (
        <Dialog title={`Budget ${year} – ${edit.row.label}`} onClose={() => setEdit(undefined)}>
          <form className="form" onSubmit={save}>
            <Field label="Amount" required>{(id) => <input id={id} inputMode="decimal" required value={edit.amount} onChange={(e) => setEdit({ ...edit, amount: e.target.value, error: undefined })} />}</Field>
            {edit.error && <p className="field-error-text" role="alert">{edit.error}</p>}
            <Field label="Currency">{(id) => <select id={id} value={edit.currency} onChange={(e) => setEdit({ ...edit, currency: e.target.value })}>{CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</select>}</Field>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save budget</button>
              <button type="button" className="secondary" onClick={() => setEdit(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

function ErpExports({ props }: { props: ScreenProps }) {
  const api = claimsApi(props.client);
  const list = useAsync(() => api.exports(), props.onSessionExpired);
  const [month, setMonth] = useState(previousMonth());
  const action = useAction(props.onSessionExpired);
  const [rec, setRec] = useState<{ x: ErpExport; total: string; ref: string; error?: string }>();
  const recon = useAsync(() => (isMonth(month) ? api.reconciliation(month) : Promise.resolve([])), props.onSessionExpired, [month]);
  const rows = list.data ?? [];

  async function reconcile(e: FormEvent) {
    e.preventDefault();
    if (!rec) return;
    const amount = parseAmount(rec.total);
    if (!amount || !rec.ref.trim()) return setRec({ ...rec, error: 'Enter the ERP total (e.g. 10450.00) and the ERP reference' });
    const currency = asMoney(rec.x.total)?.currency ?? 'GBP';
    const ok = await action.run(async () => {
      const r = await api.reconcileExport(rec.x.id, { amount, currency }, rec.ref.trim());
      return r.status === 'RECONCILED' ? `Export ${r.month} reconciled.` : `Export ${r.month}: totals do not match (${humanise(r.status)}).`;
    });
    if (ok) {
      setRec(undefined);
      list.reload();
    }
  }

  return (
    <section aria-labelledby="erp">
      <h2 id="erp">ERP finance exports</h2>
      <p className="small muted">Monthly CSV by cost centre and category – no names. Each export carries a SHA-256 checksum and is announced to the ERP.</p>
      <div className="inline-form">
        <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
        <button type="button" disabled={!isMonth(month) || action.busy} onClick={() => void action.run(async () => { const x = await api.createExport(month); list.reload(); return `Export ${x.month} generated: ${x.rows} rows, ${formatMoney(x.total)}.`; })}>Generate export</button>
      </div>
      <ActionFeedback error={rec ? undefined : action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No exports yet.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Exports</caption>
            <thead><tr><th scope="col">Month</th><th scope="col" className="num">Rows</th><th scope="col" className="num">Total</th><th scope="col">Checksum</th><th scope="col">Status</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.id}>
                  <th scope="row">{x.month} <span className="small muted">{formatDate(x.createdAt)}</span></th>
                  <td className="num">{x.rows}</td>
                  <td className="num">{formatMoney(x.total)}</td>
                  <td className="mono small" title={x.checksum}>{x.checksum.slice(0, 12)}…</td>
                  <td><ToneBadge tone={x.status === 'RECONCILED' ? 'green' : x.status === 'MISMATCH' ? 'red' : 'amber'}>{humanise(x.status)}</ToneBadge>{x.erpReference && <span className="small muted"> {x.erpReference}</span>}</td>
                  <td>
                    <div className="card-actions">
                      <a className="button secondary small-button" href={erpExportContentUrl(x.id)} download={`erp-export-${x.month}.csv`} rel="noopener">CSV</a>
                      {x.status !== 'RECONCILED' && <button type="button" className="secondary small-button" aria-label={`Reconcile export ${x.month}`} onClick={() => { action.reset(); setRec({ x, total: '', ref: '' }); }}>Reconcile</button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>Closed-claim reconciliation {isMonth(month) ? month : ''}</h3>
      {recon.loading && <Loading />}
      {!!recon.error && <ErrorAlert error={recon.error} />}
      {recon.data && recon.data.length === 0 && <Empty>No claims closed in this month.</Empty>}
      {(recon.data ?? []).length > 0 && (
        <table>
          <caption className="visually-hidden">Closed-claim reconciliation</caption>
          <thead><tr><th scope="col">Claim</th><th scope="col" className="num">Paid</th><th scope="col" className="num">Ledger</th><th scope="col" className="num">Difference</th><th scope="col">Reconciled</th></tr></thead>
          <tbody>
            {(recon.data ?? []).map((r) => (
              <tr key={r.claimRef}>
                <th scope="row">{r.claimRef}</th>
                <td className="num">{formatMoney(r.paidTotal)}</td>
                <td className="num">{formatMoney(r.ledgerTotal)}</td>
                <td className="num">{formatMoney(r.difference)}</td>
                <td>{r.reconciled ? <ToneBadge tone="green">Yes</ToneBadge> : <ToneBadge tone="red">No</ToneBadge>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rec && (
        <Dialog title={`Reconcile ${rec.x.month}`} onClose={() => setRec(undefined)}>
          <p>Export total {formatMoney(rec.x.total)} · checksum <span className="mono small">{rec.x.checksum}</span></p>
          <form className="form" onSubmit={reconcile}>
            <Field label="ERP total" required>{(id) => <input id={id} inputMode="decimal" required value={rec.total} onChange={(e) => setRec({ ...rec, total: e.target.value, error: undefined })} />}</Field>
            <Field label="ERP reference" required>{(id) => <input id={id} required value={rec.ref} onChange={(e) => setRec({ ...rec, ref: e.target.value, error: undefined })} />}</Field>
            {rec.error && <p className="field-error-text" role="alert">{rec.error}</p>}
            <div className="actions">
              <button type="submit" disabled={action.busy}>Reconcile</button>
              <button type="button" className="secondary" onClick={() => setRec(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={action.error} />
        </Dialog>
      )}
    </section>
  );
}

/** `#/claims/costs` – FINANCE / CMO: unit roll-ups (small groups suppressed), budget vs actual with trend, ERP exports + reconcile. */
export function ClaimCostsScreen(props: ScreenProps) {
  const [f, setF] = useState({ groupBy: 'SUBSIDIARY', from: addDays(todayIso(), -365), to: todayIso() });
  const [applied, setApplied] = useState(f);
  return (
    <section>
      <PageTitle sub="Money by unit, never by person. Groups below the minimum size are suppressed.">Claim & treatment costs</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(f); }}>
        <Field label="Group by">{(id) => <select id={id} value={f.groupBy} onChange={(e) => setF({ ...f, groupBy: e.target.value })}><SelectOptions values={COST_GROUP_BY} /></select>}</Field>
        <Field label="From">{(id) => <input id={id} type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} />}</Field>
        <Field label="To">{(id) => <input id={id} type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} />}</Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      <Rollup props={props} filter={applied} />
      <BudgetVsActual props={props} groupBy={applied.groupBy} />
      {can(props.session.roles, 'erpExports') && <ErpExports props={props} />}
    </section>
  );
}
