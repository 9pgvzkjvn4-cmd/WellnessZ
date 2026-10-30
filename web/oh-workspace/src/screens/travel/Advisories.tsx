import { useState, type FormEvent } from 'react';
import { ADVISORY_BODY_MAX, ADVISORY_SEVERITIES, travelApi, type Advisory } from '../../api/travel';
import { can } from '../../auth/roles';
import { ErrorSummary, IdField } from '../../components/forms';
import { formatDate, formatDay } from '../../components/format';
import { KpiTile, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import type { FieldError } from '../../domain/devices';
import { ackRateView, codes, validateAdvisory, type AdvisoryForm } from '../../domain/travel';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

const SEVERITY_TONE = { INFO: 'muted', WARNING: 'amber', CRITICAL: 'red' } as const;
const emptyForm = (): AdvisoryForm => ({ title: '', destinationCode: '', baseCode: '', roles: '', severity: 'INFO', body: '', validFrom: todayIso(), validUntil: '' });

function AuthorForm({ client, onSessionExpired, onCreated }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { onCreated: (a: Advisory) => void }) {
  const api = travelApi(client);
  const destinations = useAsync(() => api.destinations(), onSessionExpired);
  const [f, setF] = useState<AdvisoryForm>(emptyForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs = validateAdvisory(f, ADVISORY_BODY_MAX);
    setErrors(errs);
    if (errs.length) return;
    let created: Advisory | undefined;
    const ok = await action.run(async () => {
      created = await api.createAdvisory({
        title: f.title.trim(),
        ...(f.destinationCode ? { destinationCode: f.destinationCode } : {}),
        ...(f.baseCode.trim() ? { baseCode: f.baseCode.trim().toUpperCase() } : {}),
        roles: codes(f.roles),
        severity: f.severity as 'INFO',
        body: f.body.trim(),
        validFrom: f.validFrom,
        validUntil: f.validUntil,
      });
      return `Advisory published${typeof created.targeted === 'number' ? ` to ${created.targeted} people` : ''}.`;
    });
    if (ok && created) {
      setF(emptyForm());
      onCreated(created);
    }
  }
  return (
    <details className="card">
      <summary>Author an advisory</summary>
      <form className="form" onSubmit={submit} noValidate>
        <ErrorSummary errors={errors} />
        <IdField id="adv-title" label="Title" required error={errorFor(errors, 'adv-title')}>{(db) => <input id="adv-title" maxLength={200} aria-describedby={db} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />}</IdField>
        <fieldset>
          <legend>Targeting</legend>
          <p className="small muted">Crew rostered to the destination in the validity window, plus staff at the base with the chosen roles.</p>
          <div className="inline-form">
            <IdField id="adv-destination" label="Destination" error={errorFor(errors, 'adv-destination')}>
              {(db) => (
                <select id="adv-destination" aria-describedby={db} value={f.destinationCode} onChange={(e) => setF({ ...f, destinationCode: e.target.value })}>
                  <option value="">None</option>
                  {(destinations.data ?? []).map((d) => <option key={d.countryCode} value={d.countryCode}>{d.name}</option>)}
                </select>
              )}
            </IdField>
            <IdField id="adv-base" label="Base (optional)" hint="Location code, e.g. LHR">{(db) => <input id="adv-base" size={8} aria-describedby={db} value={f.baseCode} onChange={(e) => setF({ ...f, baseCode: e.target.value })} />}</IdField>
            <IdField id="adv-roles" label="Roles" hint="Comma separated, e.g. CABIN_CREW, PILOT" error={errorFor(errors, 'adv-roles')}>{(db) => <input id="adv-roles" aria-describedby={db} value={f.roles} onChange={(e) => setF({ ...f, roles: e.target.value })} />}</IdField>
          </div>
        </fieldset>
        <div className="inline-form">
          <IdField id="adv-severity" label="Severity" required error={errorFor(errors, 'adv-severity')}>{(db) => <select id="adv-severity" aria-describedby={db} value={f.severity} onChange={(e) => setF({ ...f, severity: e.target.value })}><SelectOptions values={ADVISORY_SEVERITIES} /></select>}</IdField>
          <IdField id="adv-from" label="Valid from" required error={errorFor(errors, 'adv-from')}>{(db) => <input id="adv-from" type="date" aria-describedby={db} value={f.validFrom} onChange={(e) => setF({ ...f, validFrom: e.target.value })} />}</IdField>
          <IdField id="adv-until" label="Valid until" required error={errorFor(errors, 'adv-until')}>{(db) => <input id="adv-until" type="date" aria-describedby={db} value={f.validUntil} onChange={(e) => setF({ ...f, validUntil: e.target.value })} />}</IdField>
        </div>
        <IdField id="adv-body" label="Advisory text" required hint={`${f.body.length} / ${ADVISORY_BODY_MAX} characters`} error={errorFor(errors, 'adv-body')}>
          {(db) => <textarea id="adv-body" rows={6} aria-describedby={db} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} />}
        </IdField>
        <button type="submit" disabled={action.busy}>Publish advisory</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </details>
  );
}

function Acknowledgements({ client, onSessionExpired, advisory }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { advisory: Advisory }) {
  const acks = useAsync(() => travelApi(client).acknowledgements(advisory.id), onSessionExpired, [advisory.id]);
  const a = acks.data;
  const v = ackRateView(a);
  return (
    <section className="card" aria-labelledby="ack-title">
      <h2 id="ack-title">{advisory.title}</h2>
      <p className="prewrap small">{advisory.body}</p>
      {acks.loading && <Loading />}
      {!!acks.error && <ErrorAlert error={acks.error} />}
      {a && (
        <>
          <div className="tiles" role="group" aria-label="Acknowledgement">
            <KpiTile label="Acknowledgement rate" value={<ToneBadge tone={v.tone}>{v.label}</ToneBadge>} />
            <KpiTile label="Pending" value={a.pending.length} />
          </div>
          {a.pending.length > 0 && (
            <details>
              <summary>Not yet acknowledged ({a.pending.length})</summary>
              <ul className="plain-list">
                {a.pending.map((p) => {
                  const key = typeof p === 'string' ? p : p.employeeId;
                  return <li key={key}>{typeof p === 'string' ? p : `${p.displayName ?? ''} (${p.employeeId})`}</li>;
                })}
              </ul>
            </details>
          )}
        </>
      )}
    </section>
  );
}

/** `#/advisories` – health advisories: authoring with targeting (physicians) and acknowledgement rates (E11-F05). */
export function Advisories({ client, session, onSessionExpired }: ScreenProps) {
  const list = useAsync(() => travelApi(client).advisories(), onSessionExpired);
  const [selected, setSelected] = useState<string>();
  const advisory = list.data?.find((a) => a.id === selected);
  return (
    <section>
      <PageTitle sub="Advisories reach crew rostered to the destination and base staff in the chosen roles; employees acknowledge them in the employee app.">Health advisories</PageTitle>
      {can(session.roles, 'advisoryWrite') && <AuthorForm client={client} onSessionExpired={onSessionExpired} onCreated={(a) => { list.reload(); setSelected(a.id); }} />}
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && list.data.length === 0 && <Empty>No advisories.</Empty>}
      {list.data && list.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Advisories</caption>
            <thead><tr><th scope="col">Title</th><th scope="col">Severity</th><th scope="col">Target</th><th scope="col">Valid</th><th scope="col">Published</th><th scope="col">Acknowledged</th></tr></thead>
            <tbody>
              {list.data.map((a) => {
                const v = typeof a.targeted === 'number' ? ackRateView({ targeted: a.targeted, acknowledged: a.acknowledged ?? 0, ratePct: a.ratePct ?? NaN }) : undefined;
                return (
                  <tr key={a.id} aria-selected={a.id === selected}>
                    <th scope="row"><button type="button" className="link-button" onClick={() => setSelected(a.id)}>{a.title}</button></th>
                    <td><ToneBadge tone={SEVERITY_TONE[a.severity] ?? 'muted'}>{a.severity}</ToneBadge></td>
                    <td className="small">{[a.destinationCode, a.baseCode, a.roles.join(', ')].filter(Boolean).join(' · ') || '–'}</td>
                    <td className="small">{formatDay(a.validFrom)} – {formatDay(a.validUntil)}</td>
                    <td className="small">{formatDate(a.publishedAt)}</td>
                    <td>{v ? <ToneBadge tone={v.tone}>{v.label}</ToneBadge> : '–'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {advisory && <Acknowledgements client={client} onSessionExpired={onSessionExpired} advisory={advisory} />}
    </section>
  );
}
