import { useState, type FormEvent } from 'react';
import { codingText, ORDER_CATEGORIES, recordApi, type CdsAlert, type Coding, type OrderCategory, type OrderRequest } from '../../api/record';
import type { AuthClient } from '../../auth/AuthClient';
import { CdsOverridePanel, overrideFieldId } from '../../components/CdsOverride';
import { ErrorSummary, IdField, errorFor, type FieldError } from '../../components/forms';
import { humanise } from '../../components/format';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { ActionFeedback, ErrorAlert, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { cdsAlertsOf, missingOverrideReasons, toOverrides } from '../../domain/cds';

/**
 * Order entry (E03-F04): an order set or individual coded items, priority and an optional clinical note. Duplicate-test
 * CDS (`409 cds_alert`) is handled like medications: a reason per alert, then resubmit with overrides.
 */
export function OrderForm({ client, onSessionExpired, employeeId, caseId, onPlaced }: { client: AuthClient; onSessionExpired: () => void; employeeId: string; caseId?: string; onPlaced: () => void }) {
  const api = recordApi(client);
  const sets = useAsync(() => api.orderSets(), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [mode, setMode] = useState<'SET' | 'ITEMS'>('SET');
  const [setCode, setSetCode] = useState('');
  const [category, setCategory] = useState<OrderCategory>('LAB');
  const [items, setItems] = useState<Coding[]>([]);
  const [priority, setPriority] = useState<'ROUTINE' | 'URGENT'>('ROUTINE');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [alerts, setAlerts] = useState<CdsAlert[]>();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const chosenSet = (sets.data ?? []).find((s) => s.code === setCode);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (mode === 'SET' && !chosenSet) errs.push({ field: 'order-set', message: 'Choose an order set.' });
    if (mode === 'ITEMS' && items.length === 0) errs.push({ field: 'order-items', message: 'Add at least one test.' });
    if (alerts) for (const c of missingOverrideReasons(alerts, reasons)) errs.push({ field: overrideFieldId(c), message: `Give an override reason for ${humanise(c)}.` });
    setErrors(errs);
    if (errs.length) return;
    const body: OrderRequest = {
      ...(caseId ? { caseId } : {}),
      category: mode === 'SET' && chosenSet ? (chosenSet.items[0]?.category ?? 'LAB') : category,
      ...(mode === 'SET' ? { orderSetCode: setCode } : { items }),
      priority,
      ...(note.trim() ? { clinicalNote: note.trim() } : {}),
      ...(alerts ? { overrides: toOverrides(alerts, reasons) } : {}),
    };
    let blocked: CdsAlert[] | undefined;
    let ref = '';
    const ok = await action.run(async () => {
      try {
        ref = (await api.placeOrder(employeeId, body)).placerOrderNumber;
      } catch (err) {
        blocked = cdsAlertsOf(err);
        if (blocked) return;
        throw err;
      }
      return `Order ${ref} placed.`;
    });
    if (blocked) {
      setAlerts(blocked);
      setReasons({});
      return;
    }
    if (ok) {
      setItems([]);
      setSetCode('');
      setNote('');
      setAlerts(undefined);
      onPlaced();
    }
  }

  const clearAlerts = () => {
    setAlerts(undefined);
    setReasons({});
  };

  return (
    <div className="tile">
      <h3>New order</h3>
      {!!sets.error && <ErrorAlert error={sets.error} />}
      <ErrorSummary errors={errors} />
      <form className="form" onSubmit={submit} noValidate>
        <div className="mode-switch" role="group" aria-label="Order by">
          {(['SET', 'ITEMS'] as const).map((m) => (
            <button key={m} type="button" className={`secondary small-button${mode === m ? ' mode-active' : ''}`} aria-pressed={mode === m} onClick={() => { setMode(m); clearAlerts(); }}>
              {m === 'SET' ? 'Order set' : 'Individual tests'}
            </button>
          ))}
        </div>
        {mode === 'SET' ? (
          <IdField id="order-set" label="Order set" required error={errorFor(errors, 'order-set')}>
            {(d) => (
              <select id="order-set" value={setCode} aria-describedby={d} onChange={(e) => { setSetCode(e.target.value); clearAlerts(); }}>
                <option value="">Choose…</option>
                {(sets.data ?? []).map((s) => (
                  <option key={s.code} value={s.code}>{s.title}</option>
                ))}
              </select>
            )}
          </IdField>
        ) : (
          <>
            <IdField id="order-category" label="Category">
              {() => (
                <select id="order-category" value={category} onChange={(e) => setCategory(e.target.value as OrderCategory)}>
                  <SelectOptions values={ORDER_CATEGORIES} />
                </select>
              )}
            </IdField>
            <div id="order-items" tabIndex={-1}>
              <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label="Add test (LOINC)" system="LOINC" onSelect={(c) => { setItems(items.some((i) => i.code === c.code) ? items : [...items, c]); clearAlerts(); }} />
              {errorFor(errors, 'order-items') && <p className="field-error-text small">{errorFor(errors, 'order-items')}</p>}
            </div>
          </>
        )}
        {(mode === 'SET' ? (chosenSet?.items.map((i) => i.code) ?? []) : items).length > 0 && (
          <ul className="plain-list" aria-label="Tests in this order">
            {(mode === 'SET' ? (chosenSet?.items.map((i) => i.code) ?? []) : items).map((c) => (
              <li key={`${c.system}-${c.code}`}>
                {codingText(c)}{' '}
                {mode === 'ITEMS' && (
                  <button type="button" className="link-button" onClick={() => setItems(items.filter((i) => i.code !== c.code))} aria-label={`Remove ${c.display}`}>
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
        <IdField id="order-priority" label="Priority">
          {() => (
            <select id="order-priority" value={priority} onChange={(e) => setPriority(e.target.value as 'ROUTINE' | 'URGENT')}>
              <SelectOptions values={['ROUTINE', 'URGENT']} />
            </select>
          )}
        </IdField>
        <IdField id="order-note" label="Clinical note for the laboratory">
          {() => <textarea id="order-note" rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}
        </IdField>
        {alerts && <CdsOverridePanel alerts={alerts} reasons={reasons} onChange={setReasons} errors={errors} />}
        <button type="submit" disabled={action.busy}>
          {alerts ? 'Place order with overrides' : 'Place order'}
        </button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </div>
  );
}
