import { useState, type FormEvent } from 'react';
import { treatmentApi, type StockRow } from '../../api/treatment';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorInfo, formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Alert, Empty, ErrorAlert, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { isAllergyAlert, problemCode, usableBatches } from '../../domain/treatment';
import { errorFor } from '../../domain/validation';
import type { CaseTabProps } from '../case/caseTabProps';

interface Form {
  itemCode: string;
  dose: string;
  route: string;
  siteCode: string;
  batchNumber: string;
}
const EMPTY: Form = { itemCode: '', dose: '', route: '', siteCode: '', batchNumber: '' };

function validate(f: Form): FieldError[] {
  const e: FieldError[] = [];
  if (!f.itemCode) e.push({ field: 'adm-item', message: 'Choose the formulary item' });
  if (!f.dose.trim()) e.push({ field: 'adm-dose', message: 'Enter the dose' });
  if (!f.route) e.push({ field: 'adm-route', message: 'Choose the route' });
  if (!f.siteCode.trim()) e.push({ field: 'adm-site', message: 'Enter the clinic site' });
  if (!f.batchNumber.trim()) e.push({ field: 'adm-batch', message: 'Enter the batch number' });
  return e;
}

/**
 * Clinic medication administration inside the case (E04-F05): formulary item, dose, route, batch (FEFO list from stock),
 * site. A 422 `allergy_alert` shows the alert and asks for an override reason before re-submitting.
 */
export function AdministerPanel({ client, session, onSessionExpired, caseId }: CaseTabProps) {
  const api = treatmentApi(client);
  const canWrite = can(session.roles, 'medicationAdminister');
  const formulary = useAsync(() => (canWrite ? api.formulary() : Promise.resolve([])), onSessionExpired);
  const history = useAsync(() => api.administrations(caseId), onSessionExpired);
  const [form, setForm] = useState<Form>(EMPTY);
  const stock = useAsync(() => (canWrite && form.siteCode.trim().length >= 2 ? api.stock(form.siteCode.trim()) : Promise.resolve<StockRow[]>([])), onSessionExpired, [form.siteCode.trim()]);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [allergy, setAllergy] = useState<string>();
  const [override, setOverride] = useState('');
  const action = useAction(onSessionExpired);
  const items = (formulary.data ?? []).filter((i) => i.active);
  const item = items.find((i) => i.code === form.itemCode);
  const batches = usableBatches(stock.data ?? [], form.itemCode, form.siteCode.trim());

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validate(form);
    if (allergy && !override.trim()) errs.push({ field: 'adm-override', message: 'Enter the reason for overriding the allergy alert' });
    setErrors(errs);
    if (errs.length) return;
    let failed: unknown;
    const ok = await action.run(async () => {
      try {
        const a = await api.administer(caseId, { itemCode: form.itemCode, dose: form.dose.trim(), route: form.route, batchNumber: form.batchNumber.trim(), siteCode: form.siteCode.trim(), ...(allergy ? { overrideAllergyReason: override.trim() } : {}) });
        return `${a.name ?? item?.name ?? form.itemCode} ${a.dose} administered (batch ${a.batchNumber}) and added to the medication list.`;
      } catch (err) {
        failed = err;
        throw err;
      }
    });
    if (ok) {
      setForm({ ...EMPTY, siteCode: form.siteCode });
      setAllergy(undefined);
      setOverride('');
      history.reload();
      stock.reload();
      return;
    }
    if (isAllergyAlert(failed)) {
      setAllergy(errorInfo(failed).message);
      action.reset();
    } else if (problemCode(failed) === 'batch_expired' || problemCode(failed) === 'batch_unknown') {
      setErrors([{ field: 'adm-batch', message: problemCode(failed) === 'batch_expired' ? 'This batch has expired – choose another batch' : 'Unknown batch for this item and site' }]);
    }
  }

  const err = (f: string) => errorFor(errors, f);
  const rows = history.data ?? [];

  return (
    <section className="tile" aria-labelledby="administer">
      <h2 id="administer">Clinic medication</h2>
      {canWrite && (
        <>
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={submit} noValidate>
            <div className="inline-form">
              <IdField id="adm-item" label="Formulary item" required error={err('adm-item')}>
                {(db) => (
                  <select id="adm-item" aria-describedby={db} aria-invalid={!!err('adm-item')} value={form.itemCode} onChange={(e) => {
                    const it = items.find((i) => i.code === e.target.value);
                    setForm({ ...form, itemCode: e.target.value, route: it?.route.length === 1 ? it.route[0]! : '', batchNumber: '' });
                    setAllergy(undefined);
                  }}>
                    <option value="">Choose…</option>
                    {items.map((i) => (
                      <option key={i.code} value={i.code}>{i.name}{i.strength ? ` ${i.strength}` : ''}{i.form ? ` (${i.form})` : ''}</option>
                    ))}
                  </select>
                )}
              </IdField>
              <IdField id="adm-dose" label="Dose" required error={err('adm-dose')}>
                {(db) => <input id="adm-dose" aria-describedby={db} aria-invalid={!!err('adm-dose')} value={form.dose} placeholder={item?.unit ? `e.g. 1 ${item.unit}` : ''} onChange={(e) => setForm({ ...form, dose: e.target.value })} />}
              </IdField>
              <IdField id="adm-route" label="Route" required error={err('adm-route')}>
                {(db) => (
                  <select id="adm-route" aria-describedby={db} aria-invalid={!!err('adm-route')} value={form.route} onChange={(e) => setForm({ ...form, route: e.target.value })}>
                    <option value="">Choose…</option>
                    {(item?.route ?? []).map((r) => <option key={r} value={r}>{humanise(r)}</option>)}
                  </select>
                )}
              </IdField>
              <IdField id="adm-site" label="Clinic site" required error={err('adm-site')}>
                {(db) => <input id="adm-site" aria-describedby={db} aria-invalid={!!err('adm-site')} value={form.siteCode} onChange={(e) => setForm({ ...form, siteCode: e.target.value.toUpperCase(), batchNumber: '' })} />}
              </IdField>
              <IdField id="adm-batch" label="Batch" required error={err('adm-batch')} hint={item?.stockTracked ? 'Soonest-expiring usable batch first; expired batches are not offered.' : undefined}>
                {(db) =>
                  item?.stockTracked && batches.length > 0 ? (
                    <select id="adm-batch" aria-describedby={db} aria-invalid={!!err('adm-batch')} value={form.batchNumber} onChange={(e) => setForm({ ...form, batchNumber: e.target.value })}>
                      <option value="">Choose…</option>
                      {batches.map((b) => <option key={`${b.batchNumber}-${b.siteCode}`} value={b.batchNumber}>{b.batchNumber} – exp {formatDay(b.expiry)} – {b.quantity} left</option>)}
                    </select>
                  ) : (
                    <input id="adm-batch" aria-describedby={db} aria-invalid={!!err('adm-batch')} value={form.batchNumber} onChange={(e) => setForm({ ...form, batchNumber: e.target.value })} />
                  )
                }
              </IdField>
            </div>
            {allergy && (
              <div className="alert alert-warning" role="alert">
                <strong>Allergy alert:</strong> {allergy}
                <IdField id="adm-override" label="Override reason" required error={err('adm-override')} hint="Recorded with the administration and audited.">
                  {(db) => <textarea id="adm-override" rows={2} maxLength={500} aria-describedby={db} aria-invalid={!!err('adm-override')} value={override} onChange={(e) => setOverride(e.target.value)} />}
                </IdField>
              </div>
            )}
            <div className="actions">
              <button type="submit" disabled={action.busy}>{allergy ? 'Override alert and administer' : 'Record administration'}</button>
              {allergy && <button type="button" className="secondary" onClick={() => { setAllergy(undefined); setOverride(''); }}>Do not administer</button>}
            </div>
          </form>
          <ActionFeedback error={action.error} message={action.message} />
        </>
      )}
      {!!formulary.error && <ErrorAlert error={formulary.error} />}
      <h3>Given in this case</h3>
      {history.loading && !history.data && <Loading />}
      {!!history.error && <ErrorAlert error={history.error} />}
      {history.data && rows.length === 0 && <Empty>No medication administered in this case.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Administrations</caption>
            <thead>
              <tr><th scope="col">When</th><th scope="col">Item</th><th scope="col">Dose</th><th scope="col">Route</th><th scope="col">Batch</th><th scope="col">By</th><th scope="col">Allergy override</th></tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <th scope="row">{formatDate(a.administeredAt)}</th>
                  <td>{a.name ?? a.itemCode}</td>
                  <td>{a.dose}</td>
                  <td>{humanise(a.route)}</td>
                  <td className="mono">{a.batchNumber}{a.expiry ? <span className="small muted"> exp {formatDay(a.expiry)}</span> : ''}</td>
                  <td>{a.administeredBy ?? '–'}</td>
                  <td>{a.allergyOverride ? <ToneBadge tone="amber">{typeof a.allergyOverride === 'string' ? a.allergyOverride : 'Overridden'}</ToneBadge> : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!canWrite && <Alert kind="info">Only clinicians record administrations.</Alert>}
    </section>
  );
}
