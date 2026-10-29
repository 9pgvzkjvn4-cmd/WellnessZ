import { useState, type FormEvent } from 'react';
import { fitnessApi, type JobMatch } from '../../api/fitness';
import { platformApi } from '../../api/platform';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { errorFor } from '../../domain/validation';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { RestrictionPicker, type RestrictionEntry } from '../../components/RestrictionPicker';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { restrictionValues } from '../../domain/fitness';
import type { CaseTabProps } from './caseTabProps';

const DUTIES_MAX = 1000;

/**
 * E05-F02 restrictions & modified duties: restrictions (pre-filled from the latest final decision) matched against
 * job-demand profiles (`/fitness/match`), propose a modified duty to the manager, follow proposals and decisions.
 */
export function RestrictionsDutiesTab({ client, onSessionExpired, caseId, detail }: CaseTabProps) {
  const fapi = fitnessApi(client);
  const catalogue = useAsync(() => platformApi(client).restrictions(), onSessionExpired);
  const decisions = useAsync(() => fapi.decisions(caseId), onSessionExpired);
  const proposals = useAsync(() => fapi.proposals(caseId), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [restr, setRestr] = useState<RestrictionEntry[]>();
  const [unit, setUnit] = useState(detail.subsidiaryCode ?? '');
  const [matches, setMatches] = useState<JobMatch[]>();
  const [proposing, setProposing] = useState<JobMatch>();
  const [p, setP] = useState({ duties: '', hoursPerDay: '', startDate: isoDay(new Date()), endDate: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);

  const latest = (decisions.data ?? []).find((d) => d.status === 'FINAL');
  const current: RestrictionEntry[] = restr ?? (latest?.restrictions ?? []).map((r) => ({ code: r.code, value: r.value == null ? '' : String(r.value) }));
  const cat = catalogue.data ?? [];

  async function match(e: FormEvent) {
    e.preventDefault();
    await action.run(async () => {
      setMatches(await fapi.match(detail.employeeId, restrictionValues(current, cat), unit.trim() || undefined));
    });
  }

  async function propose(e: FormEvent) {
    e.preventDefault();
    if (!proposing) return;
    const errs: FieldError[] = [];
    if (!p.duties.trim()) errs.push({ field: 'dp-duties', message: 'Describe the modified duties (functional wording).' });
    if (!p.startDate) errs.push({ field: 'dp-start', message: 'Enter the start date.' });
    if (p.endDate && p.endDate < p.startDate) errs.push({ field: 'dp-end', message: 'End date must be after the start date.' });
    if (p.hoursPerDay && (Number(p.hoursPerDay) <= 0 || Number(p.hoursPerDay) > 24)) errs.push({ field: 'dp-hours', message: 'Hours per day must be between 0 and 24.' });
    setErrors(errs);
    if (errs.length) return;
    const ok = await action.run(async () => {
      await fapi.propose(caseId, {
        jobCode: proposing.jobCode,
        duties: p.duties.trim(),
        ...(p.hoursPerDay ? { hoursPerDay: Number(p.hoursPerDay) } : {}),
        startDate: p.startDate,
        ...(p.endDate ? { endDate: p.endDate } : {}),
      });
      return `Modified duty “${proposing.title}” proposed – the manager has a task to confirm or decline.`;
    });
    if (ok) {
      setProposing(undefined);
      setP({ duties: '', hoursPerDay: '', startDate: isoDay(new Date()), endDate: '' });
      proposals.reload();
    }
  }

  const props = proposals.data ?? [];
  return (
    <div>
      <h2>Restrictions and job-demand matching</h2>
      {!!(catalogue.error || decisions.error) && <ErrorAlert error={catalogue.error ?? decisions.error} />}
      <p className="small muted">{latest ? `Pre-filled from the decision of ${formatDay(latest.validFrom)} (${humanise(latest.outcome)}).` : 'No final fitness decision yet – choose restrictions to explore suitable duties.'}</p>
      <form className="form" onSubmit={match}>
        <RestrictionPicker catalogue={cat} value={current} onChange={setRestr} idPrefix="match-restriction" />
        <Field label="Unit (optional)" hint="Limit suggestions to a subsidiary / unit, e.g. GND">
          {(id) => <input id={id} maxLength={20} value={unit} onChange={(e) => setUnit(e.target.value)} />}
        </Field>
        <button type="submit" className="secondary" disabled={action.busy}>
          Match job demands
        </button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {matches && (
        <div aria-live="polite">
          <h3>Suggested duties ({matches.filter((m) => m.compatible).length} compatible)</h3>
          {matches.length === 0 ? (
            <p className="muted">No job-demand profiles found.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Ranked job matches</caption>
                <thead>
                  <tr>
                    <th scope="col">Job</th>
                    <th scope="col">Unit</th>
                    <th scope="col">Compatible</th>
                    <th scope="col">Conflicts</th>
                    <th scope="col">Score</th>
                    <th scope="col"><span className="visually-hidden">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {matches.map((m) => (
                    <tr key={m.jobCode}>
                      <th scope="row">
                        {m.title} <span className="mono small">{m.jobCode}</span>
                        {m.sameDepartment && <div className="small">Same department</div>}
                      </th>
                      <td>{m.unit ?? '–'}</td>
                      <td>
                        {m.compatible ? (
                          <span className="badge badge-fit"><span aria-hidden="true">✓ </span>Compatible</span>
                        ) : (
                          <span className="badge badge-warning"><span aria-hidden="true">✕ </span>Conflicts</span>
                        )}
                      </td>
                      <td className="small">{m.conflicts.length === 0 ? '–' : m.conflicts.map((c) => `${humanise(c.dimension)}: demand ${String(c.demand)} vs restriction ${String(c.restriction)}`).join('; ')}</td>
                      <td>{m.score ?? '–'}</td>
                      <td>
                        <button type="button" className="secondary small-button" onClick={() => { setProposing(m); setErrors([]); }} aria-label={`Propose ${m.title} as modified duty`}>
                          Propose
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
      {proposing && (
        <div className="tile">
          <h3>Propose modified duty: {proposing.title}</h3>
          {!proposing.compatible && <p className="alert alert-warning">This job conflicts with the restrictions – describe how the conflicting demands are removed.</p>}
          <ErrorSummary errors={errors} />
          <form className="form" onSubmit={propose} noValidate>
            <IdField id="dp-duties" label="Duties (functional wording – no diagnosis)" required hint={`${p.duties.length} / ${DUTIES_MAX}. Clinical text is rejected by the server.`} error={errorFor(errors, 'dp-duties')}>
              {(d) => <textarea id="dp-duties" rows={3} maxLength={DUTIES_MAX} value={p.duties} aria-describedby={d} aria-invalid={!!errorFor(errors, 'dp-duties')} onChange={(e) => setP({ ...p, duties: e.target.value })} />}
            </IdField>
            <div className="form-grid">
              <IdField id="dp-hours" label="Hours per day" error={errorFor(errors, 'dp-hours')}>
                {(d) => <input id="dp-hours" type="number" min={1} max={24} value={p.hoursPerDay} aria-describedby={d} onChange={(e) => setP({ ...p, hoursPerDay: e.target.value })} />}
              </IdField>
              <IdField id="dp-start" label="Start date" required error={errorFor(errors, 'dp-start')}>
                {(d) => <input id="dp-start" type="date" value={p.startDate} aria-describedby={d} onChange={(e) => setP({ ...p, startDate: e.target.value })} />}
              </IdField>
              <IdField id="dp-end" label="End date" error={errorFor(errors, 'dp-end')}>
                {(d) => <input id="dp-end" type="date" value={p.endDate} aria-describedby={d} onChange={(e) => setP({ ...p, endDate: e.target.value })} />}
              </IdField>
            </div>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Send proposal to manager</button>
              <button type="button" className="secondary" onClick={() => setProposing(undefined)}>Cancel</button>
            </div>
          </form>
        </div>
      )}

      <h2>Modified-duty proposals</h2>
      {proposals.loading && <Loading />}
      {!!proposals.error && <ErrorAlert error={proposals.error} />}
      {proposals.data && props.length === 0 && <Empty>No proposals yet.</Empty>}
      {props.length > 0 && (
        <ul className="card-list">
          {props.map((x) => (
            <li key={x.id} className="card">
              <strong>{x.jobTitle ?? x.jobCode}</strong> <StatusBadge status={x.status} />
              <div className="small prewrap">{x.duties}</div>
              <div className="small">
                {formatDay(x.startDate)} – {formatDay(x.endDate)}
                {x.hoursPerDay ? ` · ${x.hoursPerDay} h/day` : ''} · proposed by {x.proposedBy} {formatDate(x.proposedAt)}
              </div>
              {x.restrictions && x.restrictions.length > 0 && <div className="small">Restrictions: {x.restrictions.map((r) => r.label).join('; ')}</div>}
              {x.decidedAt && (
                <div className="small">
                  Manager decision by {x.decidedBy} {formatDate(x.decidedAt)}
                  {x.reasonCode ? ` – ${humanise(x.reasonCode)}` : ''}
                  {x.note ? `: ${x.note}` : ''}
                </div>
              )}
              {x.status === 'ESCALATED_TO_HR' && <div className="small"><strong>All proposals declined – escalated to HR.</strong></div>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
