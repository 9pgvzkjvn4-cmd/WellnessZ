import { useState, type FormEvent } from 'react';
import { treatmentApi, type FormularyItem } from '../../api/treatment';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Alert, ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { previousMonth, todayIso } from '../../domain/absence';
import { isMonth } from '../../domain/claims';
import { isIsoDay, stockFlags } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';
import { csv } from './util';

type Tab = 'stock' | 'formulary' | 'usage';

function FormularyTable({ props }: { props: ScreenProps }) {
  const api = treatmentApi(props.client);
  const list = useAsync(() => api.formulary(), props.onSessionExpired);
  const canWrite = can(props.session.roles, 'formularyWrite');
  const action = useAction(props.onSessionExpired);
  const [edit, setEdit] = useState<FormularyItem & { routeText: string; reorderText: string }>();

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!edit) return;
    const { routeText, reorderText, ...item } = edit;
    const ok = await action.run(async () => {
      await api.saveFormularyItem({ ...item, route: csv(routeText).map((r) => r.toUpperCase()), reorderLevel: reorderText.trim() ? Number(reorderText) : undefined });
      return `${item.name} saved.`;
    });
    if (ok) {
      setEdit(undefined);
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      <ActionFeedback error={action.error} message={action.message} />
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Clinic formulary</caption>
            <thead>
              <tr><th scope="col">Item</th><th scope="col">Form / strength</th><th scope="col">Routes</th><th scope="col">SNOMED</th><th scope="col">Stock tracked</th><th scope="col" className="num">Reorder level</th><th scope="col">Active</th>{canWrite && <th scope="col"><span className="visually-hidden">Actions</span></th>}</tr>
            </thead>
            <tbody>
              {rows.map((i) => (
                <tr key={i.code}>
                  <th scope="row">{i.name} <span className="small muted mono">{i.code}</span></th>
                  <td>{[i.form, i.strength].filter(Boolean).join(' ') || '–'}</td>
                  <td>{i.route.map(humanise).join(', ')}</td>
                  <td className="mono small">{i.snomedCode ?? '–'}</td>
                  <td>{i.stockTracked ? 'Yes' : 'No'}</td>
                  <td className="num">{i.reorderLevel ?? '–'}</td>
                  <td>{i.active ? <ToneBadge tone="green">Active</ToneBadge> : <ToneBadge tone="muted">Inactive</ToneBadge>}</td>
                  {canWrite && (
                    <td><button type="button" className="secondary small-button" aria-label={`Edit ${i.name}`} onClick={() => setEdit({ ...i, routeText: i.route.join(', '), reorderText: i.reorderLevel !== undefined ? String(i.reorderLevel) : '' })}>Edit</button></td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {edit && (
        <form className="form tile" onSubmit={save} aria-label={`Edit ${edit.name}`}>
          <h3>Edit {edit.name}</h3>
          <div className="inline-form">
            <Field label="Name">{(id) => <input id={id} value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} />}</Field>
            <Field label="Routes" hint="Comma separated, e.g. ORAL, TOPICAL">{(id) => <input id={id} value={edit.routeText} onChange={(e) => setEdit({ ...edit, routeText: e.target.value })} />}</Field>
            <Field label="Reorder level">{(id) => <input id={id} inputMode="numeric" value={edit.reorderText} onChange={(e) => setEdit({ ...edit, reorderText: e.target.value })} />}</Field>
            <label className="inline-row"><input type="checkbox" checked={edit.stockTracked} onChange={(e) => setEdit({ ...edit, stockTracked: e.target.checked })} /> Stock tracked</label>
            <label className="inline-row"><input type="checkbox" checked={edit.active} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} /> Active</label>
          </div>
          <div className="actions">
            <button type="submit" disabled={action.busy}>Save</button>
            <button type="button" className="secondary" onClick={() => setEdit(undefined)}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}

function StockView({ props }: { props: ScreenProps }) {
  const api = treatmentApi(props.client);
  const [site, setSite] = useState('');
  const [applied, setApplied] = useState('');
  const list = useAsync(() => api.stock(applied || undefined), props.onSessionExpired, [applied]);
  const formulary = useAsync(() => api.formulary(), props.onSessionExpired);
  const canReceive = can(props.session.roles, 'stockReceive');
  const action = useAction(props.onSessionExpired);
  const [f, setF] = useState({ itemCode: '', batchNumber: '', expiry: '', quantity: '', siteCode: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [onlyFlagged, setOnlyFlagged] = useState(false);
  const today = todayIso();
  const rows = (list.data ?? []).filter((r) => !onlyFlagged || stockFlags(r, today).length > 0);

  async function receive(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.itemCode) errs.push({ field: 'st-item', message: 'Choose the item' });
    if (!f.batchNumber.trim()) errs.push({ field: 'st-batch', message: 'Enter the batch number' });
    if (!isIsoDay(f.expiry)) errs.push({ field: 'st-expiry', message: 'Enter the expiry date' });
    else if (f.expiry < today) errs.push({ field: 'st-expiry', message: 'This batch has already expired – do not receive it' });
    if (!/^\d+$/.test(f.quantity) || Number(f.quantity) < 1) errs.push({ field: 'st-qty', message: 'Quantity: whole number ≥ 1' });
    if (!f.siteCode.trim()) errs.push({ field: 'st-site', message: 'Enter the clinic site' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await api.receiveStock({ itemCode: f.itemCode, batchNumber: f.batchNumber.trim(), expiry: f.expiry, quantity: Number(f.quantity), siteCode: f.siteCode.trim().toUpperCase() });
      return `Batch ${f.batchNumber.trim()} received (${f.quantity}).`;
    });
    if (ok) {
      setF({ ...f, batchNumber: '', expiry: '', quantity: '' });
      list.reload();
    }
  }

  const err = (k: string) => errorFor(errors, k);
  return (
    <div>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setApplied(site.trim().toUpperCase()); }}>
        <Field label="Clinic site">{(id) => <input id={id} value={site} onChange={(e) => setSite(e.target.value)} placeholder="All sites" />}</Field>
        <button type="submit" className="secondary">Show</button>
        <label className="inline-row"><input type="checkbox" checked={onlyFlagged} onChange={(e) => setOnlyFlagged(e.target.checked)} /> Only expired / expiring / below reorder</label>
      </form>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No stock.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Stock by batch</caption>
            <thead>
              <tr><th scope="col">Item</th><th scope="col">Batch</th><th scope="col">Expiry</th><th scope="col" className="num">Quantity</th><th scope="col">Site</th><th scope="col">Flags</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const flags = stockFlags(r, today);
                return (
                  <tr key={`${r.itemCode}-${r.batchNumber}-${r.siteCode}`} className={flags.some((x) => x.tone === 'red') ? 'row-breached' : undefined}>
                    <th scope="row">{r.name ?? r.itemCode}</th>
                    <td className="mono">{r.batchNumber}</td>
                    <td>{formatDay(r.expiry)}</td>
                    <td className="num">{r.quantity}</td>
                    <td>{r.siteCode}</td>
                    <td><span className="badge-row">{flags.length ? flags.map((x) => <ToneBadge key={x.label} tone={x.tone}>{x.label}</ToneBadge>) : <ToneBadge tone="green">OK</ToneBadge>}</span></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {canReceive && (
        <form className="form tile" onSubmit={receive} noValidate aria-label="Receive batch">
          <h3>Receive batch</h3>
          <ErrorSummary errors={errors} />
          <div className="inline-form">
            <IdField id="st-item" label="Item" required error={err('st-item')}>
              {(db) => (
                <select id="st-item" aria-describedby={db} aria-invalid={!!err('st-item')} value={f.itemCode} onChange={(e) => setF({ ...f, itemCode: e.target.value })}>
                  <option value="">Choose…</option>
                  {(formulary.data ?? []).filter((i) => i.active && i.stockTracked).map((i) => <option key={i.code} value={i.code}>{i.name}</option>)}
                </select>
              )}
            </IdField>
            <IdField id="st-batch" label="Batch number" required error={err('st-batch')}>
              {(db) => <input id="st-batch" aria-describedby={db} aria-invalid={!!err('st-batch')} value={f.batchNumber} onChange={(e) => setF({ ...f, batchNumber: e.target.value })} />}
            </IdField>
            <IdField id="st-expiry" label="Expiry" required error={err('st-expiry')}>
              {(db) => <input id="st-expiry" type="date" aria-describedby={db} aria-invalid={!!err('st-expiry')} value={f.expiry} onChange={(e) => setF({ ...f, expiry: e.target.value })} />}
            </IdField>
            <IdField id="st-qty" label="Quantity" required error={err('st-qty')}>
              {(db) => <input id="st-qty" inputMode="numeric" aria-describedby={db} aria-invalid={!!err('st-qty')} value={f.quantity} onChange={(e) => setF({ ...f, quantity: e.target.value })} />}
            </IdField>
            <IdField id="st-site" label="Clinic site" required error={err('st-site')}>
              {(db) => <input id="st-site" aria-describedby={db} aria-invalid={!!err('st-site')} value={f.siteCode} onChange={(e) => setF({ ...f, siteCode: e.target.value })} />}
            </IdField>
          </div>
          <button type="submit" disabled={action.busy}>Receive</button>
          <ActionFeedback error={action.error} message={action.message} />
        </form>
      )}
    </div>
  );
}

function UsageView({ props }: { props: ScreenProps }) {
  const [month, setMonth] = useState(previousMonth());
  const valid = isMonth(month);
  const usage = useAsync(() => (valid ? treatmentApi(props.client).usage(month) : Promise.resolve([])), props.onSessionExpired, [month]);
  const rows = usage.data ?? [];
  return (
    <div>
      <div className="filters inline-form">
        <Field label="Month">{(id) => <input id={id} type="month" value={month} onChange={(e) => setMonth(e.target.value)} />}</Field>
      </div>
      {usage.loading && <Loading />}
      {!!usage.error && <ErrorAlert error={usage.error} />}
      {usage.data && rows.length === 0 && <Empty>No administrations in {month}.</Empty>}
      {rows.length > 0 && (
        <table>
          <caption>Medication usage {month}</caption>
          <thead><tr><th scope="col">Item</th><th scope="col">Site</th><th scope="col" className="num">Administrations</th></tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.itemCode}-${r.siteCode ?? i}`}>
                <th scope="row">{r.name ?? r.itemCode}</th>
                <td>{r.siteCode ?? 'All'}</td>
                <td className="num">{r.administrations ?? '–'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** `#/treatment/medications` – formulary, stock with expired / below-reorder flags, receive batch, monthly usage (E04-F05). */
export function MedicationsScreen(props: ScreenProps) {
  const [tab, setTab] = useState<Tab>('stock');
  const eps = useAsync(() => treatmentApi(props.client).eprescribing().catch(() => undefined), props.onSessionExpired);
  const tabs = [
    { id: 'stock', label: 'Stock' },
    { id: 'formulary', label: 'Formulary' },
    { id: 'usage', label: 'Monthly usage', hidden: !can(props.session.roles, 'medicationUsage') },
  ];
  return (
    <section>
      <PageTitle sub="Clinic-administered medicines. Administration itself is recorded inside the case (Treatment tab) with allergy and batch checks.">Clinic medications</PageTitle>
      {eps.data && !eps.data.enabled && (
        <Alert kind="info">
          E-prescribing is not enabled{eps.data.route ? ` (route under review: ${eps.data.route})` : ''}.{eps.data.decisionMemo ? ` ${eps.data.decisionMemo}` : ''}
        </Alert>
      )}
      <div className="mode-switch" role="group" aria-label="Section">
        {tabs.filter((t) => !t.hidden).map((t) => (
          <button key={t.id} type="button" className={`secondary small-button${tab === t.id ? ' mode-active' : ''}`} aria-pressed={tab === t.id} onClick={() => setTab(t.id as Tab)}>{t.label}</button>
        ))}
      </div>
      {tab === 'stock' && <StockView props={props} />}
      {tab === 'formulary' && <FormularyTable props={props} />}
      {tab === 'usage' && <UsageView props={props} />}
    </section>
  );
}

