import { useState } from 'react';
import { REFERRAL_STATUSES, treatmentApi } from '../../api/treatment';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';
import { ReferralDetailPanel, ReferralTable } from './Referrals';

/** `#/referrals` – worklist with status badges, overdue / mine filters and bulk remind (E04-F02). */
export function ReferralWorklist({ client, onSessionExpired }: ScreenProps) {
  const api = treatmentApi(client);
  const [filter, setFilter] = useState({ status: '', overdue: false, mine: false, serviceCode: '' });
  const list = useAsync(() => api.referrals(filter), onSessionExpired, [filter]);
  const services = useAsync(() => api.services(), onSessionExpired);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };

  async function remind() {
    const ids = [...selected].slice(0, 200);
    if (await action.run(async () => { const r = await api.remind(ids); return `Reminder sent for ${r.reminded} referral${r.reminded === 1 ? '' : 's'}.`; })) {
      setSelected(new Set());
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="External referrals across your cases. Overdue reports are chased automatically; after two chases the referral escalates to the referring clinician.">Referrals</PageTitle>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
              <SelectOptions values={REFERRAL_STATUSES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Service">
          {(id) => (
            <select id={id} value={filter.serviceCode} onChange={(e) => setFilter({ ...filter, serviceCode: e.target.value })}>
              <option value="">All</option>
              {(services.data ?? []).map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
            </select>
          )}
        </Field>
        <label className="inline-row"><input type="checkbox" checked={filter.overdue} onChange={(e) => setFilter({ ...filter, overdue: e.target.checked })} /> Overdue only</label>
        <label className="inline-row"><input type="checkbox" checked={filter.mine} onChange={(e) => setFilter({ ...filter, mine: e.target.checked })} /> My referrals</label>
      </div>
      <div className="toolbar">
        <button type="button" className="secondary" disabled={selected.size === 0 || action.busy} onClick={() => void remind()}>
          Remind provider{selected.size ? ` (${selected.size})` : ''}
        </button>
        {rows.length > 0 && (
          <button type="button" className="link-button" onClick={() => setSelected(selected.size === rows.length ? new Set() : new Set(rows.map((r) => r.id)))}>
            {selected.size === rows.length ? 'Clear selection' : 'Select all'}
          </button>
        )}
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No referrals match.</Empty>}
      {rows.length > 0 && <ReferralTable rows={rows} caption={`${rows.length} referral${rows.length === 1 ? '' : 's'}`} selected={selected} onToggle={toggle} />}
    </section>
  );
}

/** `#/referrals/{id}`. */
export function ReferralScreen(props: ScreenProps & { referralId: string }) {
  return (
    <section>
      <p className="breadcrumb small"><a href="#/referrals">Referrals</a> <span aria-hidden="true">›</span> Referral</p>
      <PageTitle>Referral</PageTitle>
      <ReferralDetailPanel id={props.referralId} props={props} />
    </section>
  );
}

