import { useState, type FormEvent } from 'react';
import type { Complexity } from '../../api/types';
import { IntensityBadge } from '../../components/badges';
import { formatDate } from '../../components/format';
import { ActionFeedback, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { casePath, type CaseTabProps } from './caseTabProps';

export function ComplexityTab({ client, caseId, refresh, onSessionExpired }: CaseTabProps) {
  const complexity = useAsync(() => client.api<Complexity>(casePath(caseId, '/complexity')), onSessionExpired);
  const action = useAction(onSessionExpired);
  const [flags, setFlags] = useState({ comorbidity: false, psychosocialScreenPositive: false, claimOrLitigation: false, primaryIcd10: '' });
  const c = complexity.data;

  async function saveFlags(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await client.api(casePath(caseId, '/risk-flags'), {
        method: 'PUT',
        body: { comorbidity: flags.comorbidity, psychosocialScreenPositive: flags.psychosocialScreenPositive, claimOrLitigation: flags.claimOrLitigation, ...(flags.primaryIcd10.trim() ? { primaryIcd10: flags.primaryIcd10.trim().toUpperCase() } : {}) },
      });
      await client.api(casePath(caseId, '/complexity/recalculate'), { method: 'POST' });
      return 'Risk flags saved and score recalculated.';
    });
    if (ok) {
      complexity.reload();
      refresh();
    }
  }

  async function recalc() {
    if (await action.run(async () => { await client.api(casePath(caseId, '/complexity/recalculate'), { method: 'POST' }); return 'Score recalculated.'; })) {
      complexity.reload();
      refresh();
    }
  }

  return (
    <div className="grid-2">
      <div>
        <h2>Complexity / risk score</h2>
        {complexity.loading && <Loading />}
        {!!complexity.error && <ErrorAlert error={complexity.error} />}
        {c && (
          <>
            <div className="score">
              <span className="score-value">{c.score}</span>
              <span className="muted"> / 100</span> <IntensityBadge intensity={c.intensity} />
            </div>
            <div className="meter" aria-hidden="true">
              <span className={`meter-fill ${c.intensity === 'INTENSIVE' ? 'meter-high' : ''}`} style={{ width: `${Math.min(100, Math.max(0, c.score))}%` }} />
            </div>
            <p className="small muted">
              Calculated {formatDate(c.calculatedAt)}
              {c.expectedDurationDays != null && ` · expected duration ${c.expectedDurationDays} days`}
              {c.actualAbsenceDays != null && ` · actual absence ${c.actualAbsenceDays} days`}
            </p>
            <table>
              <caption>Score drivers</caption>
              <thead>
                <tr>
                  <th scope="col">Driver</th>
                  <th scope="col">Points</th>
                </tr>
              </thead>
              <tbody>
                {c.drivers.length === 0 && (
                  <tr>
                    <td colSpan={2} className="muted">No drivers.</td>
                  </tr>
                )}
                {c.drivers.map((d) => (
                  <tr key={d.code}>
                    <td>{d.label}</td>
                    <td className="num">+{d.points}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
        <button type="button" className="secondary" disabled={action.busy} onClick={() => void recalc()}>
          Recalculate
        </button>
      </div>
      <div>
        <h2>Risk flags</h2>
        <form className="form" onSubmit={saveFlags}>
          <label className="checkbox">
            <input type="checkbox" checked={flags.comorbidity} onChange={(e) => setFlags({ ...flags, comorbidity: e.target.checked })} /> Relevant comorbidity
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={flags.psychosocialScreenPositive} onChange={(e) => setFlags({ ...flags, psychosocialScreenPositive: e.target.checked })} /> Psychosocial screen
            positive
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={flags.claimOrLitigation} onChange={(e) => setFlags({ ...flags, claimOrLitigation: e.target.checked })} /> Claim or litigation
          </label>
          <Field label="Primary ICD-10 (optional)" hint="Used for the duration guideline.">
            {(id) => <input id={id} value={flags.primaryIcd10} maxLength={8} pattern="[A-Za-z][0-9]{2}(\.[0-9A-Za-z]{1,4})?" onChange={(e) => setFlags({ ...flags, primaryIcd10: e.target.value })} />}
          </Field>
          <button type="submit" disabled={action.busy}>
            Save flags &amp; recalculate
          </button>
        </form>
        <ActionFeedback error={action.error} message={action.message} />
      </div>
    </div>
  );
}
