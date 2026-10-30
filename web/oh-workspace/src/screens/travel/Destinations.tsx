import { useState, type FormEvent } from 'react';
import { DESTINATION_SOURCES, travelApi, type Destination } from '../../api/travel';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso, daysUntil } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

const lines = (v: string) => v.split('\n').map((s) => s.trim()).filter(Boolean);

function DestinationEditor({ d, onSave, onClose, busy }: { d: Destination; onSave: (d: Destination) => void; onClose: () => void; busy: boolean }) {
  const [f, setF] = useState({ name: d.name, risks: d.risks.join('\n'), vaccines: d.recommendedVaccines.join(', '), prophylaxis: d.prophylaxis.join('\n'), advice: d.advice, source: d.source, reviewedOn: d.reviewedOn ?? todayIso() });
  const [errors, setErrors] = useState<FieldError[]>([]);
  function submit(e: FormEvent) {
    e.preventDefault();
    const errs: FieldError[] = [];
    if (!f.name.trim()) errs.push({ field: 'dest-name', message: 'Enter the destination name.' });
    if (!f.advice.trim()) errs.push({ field: 'dest-advice', message: 'Enter the advice text.' });
    if (f.advice.length > 4000) errs.push({ field: 'dest-advice', message: 'Advice can be at most 4000 characters.' });
    if (!f.reviewedOn) errs.push({ field: 'dest-reviewed', message: 'Enter the review date.' });
    setErrors(errs);
    if (errs.length) return;
    onSave({
      countryCode: d.countryCode,
      name: f.name.trim(),
      risks: lines(f.risks),
      recommendedVaccines: [...new Set(f.vaccines.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()).filter(Boolean))],
      prophylaxis: lines(f.prophylaxis),
      advice: f.advice.trim(),
      source: f.source,
      reviewedOn: f.reviewedOn,
    });
  }
  return (
    <Dialog title={`Edit ${d.name} (${d.countryCode})`} onClose={onClose}>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="dest-name" label="Name" required error={errorFor(errors, 'dest-name')}>{(db) => <input id="dest-name" aria-describedby={db} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</IdField>
        <IdField id="dest-risks" label="Risks (one per line)">{(db) => <textarea id="dest-risks" aria-describedby={db} rows={4} value={f.risks} onChange={(e) => setF({ ...f, risks: e.target.value })} />}</IdField>
        <IdField id="dest-vaccines" label="Recommended vaccines (codes)" hint="Comma separated E07 vaccine codes, e.g. HEPA, TYPHOID">{(db) => <input id="dest-vaccines" aria-describedby={db} value={f.vaccines} onChange={(e) => setF({ ...f, vaccines: e.target.value })} />}</IdField>
        <IdField id="dest-prophylaxis" label="Prophylaxis (one per line)">{(db) => <textarea id="dest-prophylaxis" aria-describedby={db} rows={2} value={f.prophylaxis} onChange={(e) => setF({ ...f, prophylaxis: e.target.value })} />}</IdField>
        <IdField id="dest-advice" label="Advice" required error={errorFor(errors, 'dest-advice')}>{(db) => <textarea id="dest-advice" aria-describedby={db} rows={5} maxLength={4000} value={f.advice} onChange={(e) => setF({ ...f, advice: e.target.value })} />}</IdField>
        <div className="inline-form">
          <Field label="Source">{(id) => <select id={id} value={f.source} onChange={(e) => setF({ ...f, source: e.target.value as Destination['source'] })}><SelectOptions values={DESTINATION_SOURCES} /></select>}</Field>
          <IdField id="dest-reviewed" label="Reviewed on" required error={errorFor(errors, 'dest-reviewed')}>{(db) => <input id="dest-reviewed" type="date" aria-describedby={db} value={f.reviewedOn} onChange={(e) => setF({ ...f, reviewedOn: e.target.value })} />}</IdField>
        </div>
        <div className="actions">
          <button type="submit" disabled={busy}>Save destination</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}

/** Content older than a year is flagged for review. */
function reviewTone(reviewedOn?: string | null) {
  if (!reviewedOn) return { tone: 'amber' as const, label: 'Never reviewed' };
  const age = -daysUntil(reviewedOn);
  return age > 365 ? { tone: 'amber' as const, label: `Reviewed ${formatDay(reviewedOn)} – due` } : { tone: 'green' as const, label: `Reviewed ${formatDay(reviewedOn)}` };
}

/** `#/travel/destinations` – destination risk content (in-house seed; licensed source pending E11-BL-002); physicians edit. */
export function TravelDestinations({ client, session, onSessionExpired }: ScreenProps) {
  const api = travelApi(client);
  const list = useAsync(() => api.destinations(), onSessionExpired);
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<string>();
  const [editing, setEditing] = useState<Destination>();
  const action = useAction(onSessionExpired);
  const canEdit = can(session.roles, 'travelDestinationWrite');
  const rows = (list.data ?? []).filter((d) => !q.trim() || `${d.countryCode} ${d.name}`.toLowerCase().includes(q.trim().toLowerCase())).sort((a, b) => a.name.localeCompare(b.name));
  const current = list.data?.find((d) => d.countryCode === selected);

  async function save(d: Destination) {
    const ok = await action.run(async () => {
      await api.saveDestination(d);
      return `${d.name} saved.`;
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    }
  }

  return (
    <section>
      <PageTitle sub="Risk content per destination: risks, recommended vaccines, prophylaxis and advice. Shown to employees as advice only.">Travel destinations</PageTitle>
      <div className="filters inline-form">
        <Field label="Search destination">{(id) => <input id={id} type="search" value={q} onChange={(e) => setQ(e.target.value)} />}</Field>
      </div>
      <ActionFeedback error={action.error} message={action.message} />
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No destinations match.</Empty>}
      <div className="grid-2">
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <caption>{rows.length} destination{rows.length === 1 ? '' : 's'}</caption>
              <thead><tr><th scope="col">Destination</th><th scope="col">Vaccines</th><th scope="col">Source</th><th scope="col">Review</th></tr></thead>
              <tbody>
                {rows.map((d) => {
                  const r = reviewTone(d.reviewedOn);
                  return (
                    <tr key={d.countryCode} aria-selected={d.countryCode === selected}>
                      <th scope="row">
                        <button type="button" className="link-button" onClick={() => setSelected(d.countryCode)}>{d.name} ({d.countryCode})</button>
                      </th>
                      <td className="small">{d.recommendedVaccines.join(', ') || '–'}</td>
                      <td>{humanise(d.source)}</td>
                      <td><ToneBadge tone={r.tone}>{r.label}</ToneBadge></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {current && (
          <section className="card" aria-labelledby="dest-detail">
            <h2 id="dest-detail">{current.name} ({current.countryCode})</h2>
            <Dl items={[
              ['Risks', current.risks.length ? <ul className="plain-list">{current.risks.map((r) => <li key={r}>{r}</li>)}</ul> : 'None listed'],
              ['Recommended vaccines', current.recommendedVaccines.join(', ') || 'None'],
              ['Prophylaxis', current.prophylaxis.join('; ') || 'None'],
              ['Advice', <span className="prewrap">{current.advice}</span>],
              ['Source', humanise(current.source)],
              ['Reviewed on', formatDay(current.reviewedOn)],
            ]} />
            {canEdit && <button type="button" className="secondary" onClick={() => setEditing(current)}>Edit {current.name}</button>}
          </section>
        )}
      </div>
      {editing && <DestinationEditor d={editing} busy={action.busy} onSave={(d) => void save(d)} onClose={() => setEditing(undefined)} />}
    </section>
  );
}
