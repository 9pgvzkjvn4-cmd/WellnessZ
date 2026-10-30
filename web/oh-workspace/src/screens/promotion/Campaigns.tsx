import { useState, type FormEvent } from 'react';
import { PROMOTION_CAMPAIGN_STATUSES, PROMOTION_RISKS, promotionApi, type PromotionCampaign } from '../../api/promotion';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import { promotionCampaignBody, timelineView, validatePromotionCampaign, type PromotionCampaignForm } from '../../domain/promotion';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

function hashParams(): URLSearchParams {
  return new URLSearchParams(typeof window === 'undefined' ? '' : (window.location.hash.split('?')[1] ?? ''));
}

function toForm(c?: PromotionCampaign): PromotionCampaignForm {
  if (!c) {
    const p = hashParams();
    return { title: '', risk: p.get('risk') ?? '', objective: '', targetUnits: p.get('unit') ?? '', insightRef: p.get('insight') ?? '', budgetAmount: '', currency: 'GBP', startDate: todayIso(), endDate: '', owners: '', kpis: [{ metric: 'participation_pct', target: '' }], status: 'PLANNED' };
  }
  return {
    title: c.title, risk: c.risk, objective: c.objective, targetUnits: c.targetUnits.join(', '), insightRef: c.insightRef ?? '', budgetAmount: c.budget.amount, currency: c.budget.currency,
    startDate: c.startDate, endDate: c.endDate, owners: c.owners.join(', '), kpis: c.kpis.length ? c.kpis.map((k) => ({ metric: k.metric, target: String(k.target) })) : [{ metric: '', target: '' }], status: c.status,
  };
}

const STATUS_TONE = { PLANNED: 'muted', ACTIVE: 'green', COMPLETED: 'green', CANCELLED: 'muted' } as const;

function CampaignEditor({ client, onSessionExpired, campaign, onSaved, onCancel }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { campaign?: PromotionCampaign; onSaved: (c: PromotionCampaign) => void; onCancel: () => void }) {
  const [f, setF] = useState<PromotionCampaignForm>(() => toForm(campaign));
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const setKpi = (i: number, patch: Partial<{ metric: string; target: string }>) => setF({ ...f, kpis: f.kpis.map((k, j) => (j === i ? { ...k, ...patch } : k)) });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validatePromotionCampaign(f);
    setErrors(errs);
    if (errs.length) return;
    let saved: PromotionCampaign | undefined;
    const api = promotionApi(client);
    const ok = await action.run(async () => {
      saved = campaign ? await api.updateCampaign(campaign.id, promotionCampaignBody(f)) : await api.createCampaign(promotionCampaignBody(f));
    });
    if (ok && saved) onSaved(saved);
  }

  return (
    <form className="form card" onSubmit={submit} noValidate aria-labelledby="pc-form-title">
      <h2 id="pc-form-title">{campaign ? `Edit ${campaign.title}` : 'Plan a campaign'}</h2>
      <ErrorSummary errors={errors} />
      <div className="inline-form">
        <IdField id="pc-title" label="Title" required error={errorFor(errors, 'pc-title')}>{(db) => <input id="pc-title" size={30} maxLength={200} aria-describedby={db} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />}</IdField>
        <IdField id="pc-risk" label="Risk area" required error={errorFor(errors, 'pc-risk')}>{(db) => <select id="pc-risk" aria-describedby={db} value={f.risk} onChange={(e) => setF({ ...f, risk: e.target.value })}><SelectOptions values={PROMOTION_RISKS} empty="Choose…" /></select>}</IdField>
        <Field label="Status">{(id) => <select id={id} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><SelectOptions values={PROMOTION_CAMPAIGN_STATUSES} /></select>}</Field>
      </div>
      <IdField id="pc-objective" label="Objective" required error={errorFor(errors, 'pc-objective')} hint="No personal or clinical details – the text is shown to staff and the campaign app.">
        {(db) => <textarea id="pc-objective" rows={3} maxLength={2000} aria-describedby={db} value={f.objective} onChange={(e) => setF({ ...f, objective: e.target.value })} />}
      </IdField>
      <div className="inline-form">
        <IdField id="pc-units" label="Target units" required hint="Comma separated unit codes" error={errorFor(errors, 'pc-units')}>{(db) => <input id="pc-units" aria-describedby={db} value={f.targetUnits} onChange={(e) => setF({ ...f, targetUnits: e.target.value })} />}</IdField>
        <IdField id="pc-insight" label="Insight reference (optional)">{(db) => <input id="pc-insight" aria-describedby={db} value={f.insightRef} onChange={(e) => setF({ ...f, insightRef: e.target.value })} />}</IdField>
      </div>
      <fieldset>
        <legend>Budget and timeline</legend>
        <div className="inline-form">
          <IdField id="pc-budget" label="Budget" required error={errorFor(errors, 'pc-budget')}>{(db) => <input id="pc-budget" inputMode="decimal" size={10} aria-describedby={db} value={f.budgetAmount} onChange={(e) => setF({ ...f, budgetAmount: e.target.value })} />}</IdField>
          <IdField id="pc-currency" label="Currency" required error={errorFor(errors, 'pc-currency')}>{(db) => <input id="pc-currency" size={4} maxLength={3} aria-describedby={db} value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value.toUpperCase() })} />}</IdField>
          <IdField id="pc-start" label="Start" required error={errorFor(errors, 'pc-start')}>{(db) => <input id="pc-start" type="date" aria-describedby={db} value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} />}</IdField>
          <IdField id="pc-end" label="End" required error={errorFor(errors, 'pc-end')}>{(db) => <input id="pc-end" type="date" aria-describedby={db} value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} />}</IdField>
        </div>
      </fieldset>
      <IdField id="pc-owners" label="Owners" required hint="Comma separated names or usernames" error={errorFor(errors, 'pc-owners')}>{(db) => <input id="pc-owners" aria-describedby={db} value={f.owners} onChange={(e) => setF({ ...f, owners: e.target.value })} />}</IdField>
      <fieldset>
        <legend>KPIs</legend>
        {f.kpis.map((k, i) => (
          <div key={i} className="inline-form" role="group" aria-label={`KPI ${i + 1}`}>
            <IdField id={`pc-kpi-${i}-metric`} label="Metric" error={errorFor(errors, `pc-kpi-${i}-metric`)}>{(db) => <input id={`pc-kpi-${i}-metric`} aria-describedby={db} value={k.metric} onChange={(e) => setKpi(i, { metric: e.target.value })} />}</IdField>
            <IdField id={`pc-kpi-${i}-target`} label="Target" error={errorFor(errors, `pc-kpi-${i}-target`)}>{(db) => <input id={`pc-kpi-${i}-target`} inputMode="decimal" size={8} aria-describedby={db} value={k.target} onChange={(e) => setKpi(i, { target: e.target.value })} />}</IdField>
            {f.kpis.length > 1 && <button type="button" className="link-button" onClick={() => setF({ ...f, kpis: f.kpis.filter((_, j) => j !== i) })}>Remove KPI {i + 1}</button>}
          </div>
        ))}
        <button type="button" className="secondary" onClick={() => setF({ ...f, kpis: [...f.kpis, { metric: '', target: '' }] })}>Add KPI</button>
      </fieldset>
      <ActionFeedback error={action.error} />
      <div className="actions">
        <button type="submit" disabled={action.busy}>{campaign ? 'Save changes' : 'Save campaign'}</button>
        <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** `#/promotion/campaigns` – campaign planner: objective, target units, budget, timeline, owners, KPIs, publish (E12). */
export function PromotionCampaigns({ client, session, onSessionExpired }: ScreenProps) {
  const api = promotionApi(client);
  const list = useAsync(() => api.campaigns(), onSessionExpired);
  const [editing, setEditing] = useState<PromotionCampaign | 'new' | undefined>(() => (hashParams().get('risk') ? 'new' : undefined));
  const [selected, setSelected] = useState<string>();
  const publish = useAction(onSessionExpired);
  const write = can(session.roles, 'promotionCampaigns');
  const rows = [...(list.data ?? [])].sort((a, b) => a.startDate.localeCompare(b.startDate));
  const current = rows.find((c) => c.id === selected);

  return (
    <section>
      <PageTitle sub="Plan campaigns from insights; publishing sends them to the campaign app and invites the targeted staff.">Campaign planner</PageTitle>
      {write && !editing && <button type="button" onClick={() => setEditing('new')}>Plan a campaign</button>}
      {editing && (
        <CampaignEditor
          key={editing === 'new' ? 'new' : editing.id}
          client={client}
          onSessionExpired={onSessionExpired}
          campaign={editing === 'new' ? undefined : editing}
          onCancel={() => setEditing(undefined)}
          onSaved={(c) => { setEditing(undefined); setSelected(c.id); list.reload(); }}
        />
      )}
      <ActionFeedback error={publish.error} message={publish.message} />
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No campaigns planned.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Campaign portfolio</caption>
            <thead><tr><th scope="col">Campaign</th><th scope="col">Risk</th><th scope="col">Units</th><th scope="col" className="num">Budget</th><th scope="col">Timeline</th><th scope="col">Status</th></tr></thead>
            <tbody>
              {rows.map((c) => {
                const t = timelineView(c.startDate, c.endDate);
                return (
                  <tr key={c.id} aria-selected={c.id === selected}>
                    <th scope="row"><button type="button" className="link-button" onClick={() => setSelected(c.id)}>{c.title}</button></th>
                    <td>{humanise(c.risk)}</td>
                    <td className="small">{c.targetUnits.join(', ')}</td>
                    <td className="num">{c.budget.currency} {c.budget.amount}</td>
                    <td className="small">
                      {formatDay(c.startDate)} – {formatDay(c.endDate)}
                      <div className="meter" aria-hidden="true"><span className="meter-fill" style={{ width: `${t.pct}%` }} /></div>
                      <span>{t.label}</span>
                    </td>
                    <td><ToneBadge tone={STATUS_TONE[c.status] ?? 'muted'}>{humanise(c.status)}</ToneBadge>{c.publishedAt && <span className="small muted"> published</span>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {current && (
        <section className="card" aria-labelledby="pc-detail">
          <h2 id="pc-detail">{current.title}</h2>
          <Dl items={[
            ['Objective', <span className="prewrap">{current.objective}</span>],
            ['Risk', humanise(current.risk)],
            ['Target units', current.targetUnits.join(', ')],
            ['Insight', current.insightRef],
            ['Budget', `${current.budget.currency} ${current.budget.amount}`],
            ['Timeline', `${formatDay(current.startDate)} – ${formatDay(current.endDate)}`],
            ['Owners', current.owners.join(', ')],
            ['KPIs', <ul className="plain-list">{current.kpis.map((k) => <li key={k.metric}>{k.metric}: target {k.target}</li>)}</ul>],
            ['Status', <StatusBadge status={current.status} />],
            ['Published', formatDate(current.publishedAt)],
          ]} />
          {write && (
            <div className="actions">
              <button type="button" className="secondary" onClick={() => setEditing(current)}>Edit {current.title}</button>
              {!current.publishedAt && current.status !== 'CANCELLED' && current.status !== 'COMPLETED' && (
                <button type="button" disabled={publish.busy} onClick={() => void publish.run(async () => {
                  await api.publish(current.id);
                  list.reload();
                  return `${current.title} published – invitations sent to the targeted staff.`;
                })}>
                  Publish {current.title}
                </button>
              )}
            </div>
          )}
        </section>
      )}
    </section>
  );
}
