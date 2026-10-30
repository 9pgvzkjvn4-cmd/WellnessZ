import { useState, type FormEvent } from 'react';
import { EXCLUSION_REASONS, PANELS, ROLE_GROUPS, TEST_TYPES, testingApi, type ExclusionReason, type Pool, type RoleGroup, type Selection, type TestingPolicy, type TestType } from '../../api/testing';
import { can } from '../../auth/roles';
import { Dialog } from '../../components/Dialog';
import { ErrorSummary, IdField, type FieldError } from '../../components/forms';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { todayIso } from '../../domain/absence';
import { serverFieldError } from '../../domain/support';
import { currentPeriod, expectedSelectionCount, policyTone, shortHash, validatePolicy, validateSelection, type SelectionForm } from '../../domain/testing';
import { errorFor } from '../../domain/validation';
import type { ScreenProps } from '../types';

export function PendingApprovalBadge({ policy }: { policy: Pick<TestingPolicy, 'approvalStatus' | 'approvedBy'> }) {
  return policy.approvalStatus === 'APPROVED' ? (
    <ToneBadge tone="green">Approved{policy.approvedBy ? ` by ${policy.approvedBy}` : ''}</ToneBadge>
  ) : (
    <ToneBadge tone={policyTone(policy)} title="E10-BL-010">Pending Legal / Safety / HR approval</ToneBadge>
  );
}

/** `#/testing/policies` – per role group: panels, random rates, windows, lead time, jurisdiction, approval (E10-F01). */
export function TestingPolicies({ client, session, onSessionExpired }: ScreenProps) {
  const api = testingApi(client);
  const list = useAsync(() => api.policies(), onSessionExpired);
  const [editing, setEditing] = useState<TestingPolicy & { jobCodesText: string }>();
  const [errors, setErrors] = useState<FieldError[]>([]);
  const action = useAction(onSessionExpired);
  const canWrite = can(session.roles, 'testingPolicyWrite');
  const rows = list.data ?? [];
  const pending = rows.filter((p) => p.approvalStatus !== 'APPROVED').length;

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    const { jobCodesText, ...rest } = editing;
    const policy: TestingPolicy = { ...rest, jobCodes: jobCodesText.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean) };
    const errs = validatePolicy(policy);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        const saved = await api.savePolicy(policy);
        return `${humanise(saved.roleGroup)} policy saved (version ${saved.version}).`;
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) {
      setEditing(undefined);
      list.reload();
    } else {
      const fe = serverFieldError(failure, { randomDrugRatePct: 'pol-drug-rate', randomAlcoholRatePct: 'pol-alcohol-rate', alcoholWindowHours: 'pol-alcohol-window', drugWindowHours: 'pol-drug-window', leadTimeMinutes: 'pol-lead', panels: 'pol-panels', jurisdiction: 'pol-jurisdiction' });
      if (fe) setErrors([fe]);
    }
  }
  const set = (patch: Partial<TestingPolicy & { jobCodesText: string }>) => editing && setEditing({ ...editing, ...patch });

  return (
    <section>
      <PageTitle sub="Testing policy per safety-sensitive role group. Random testing starts only once Legal, Safety and HR have approved the policy.">
        Testing policies {pending > 0 && <ToneBadge tone="amber">{pending} pending approval</ToneBadge>}
      </PageTitle>
      <ActionFeedback error={editing ? undefined : action.error} message={action.message} />
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No policies configured.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th scope="col">Role group</th><th scope="col">Panels</th><th scope="col" className="num">Random drug %</th><th scope="col" className="num">Random alcohol %</th><th scope="col">Windows (alcohol / drug)</th><th scope="col">Lead time</th><th scope="col">Jurisdiction</th><th scope="col">Approval</th>{canWrite && <th scope="col"><span className="visually-hidden">Actions</span></th>}</tr></thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.roleGroup}>
                  <th scope="row">{humanise(p.roleGroup)} <span className="small muted">v{p.version}{p.jobCodes.length ? ` · ${p.jobCodes.join(', ')}` : ''}</span></th>
                  <td>{p.panels.map(humanise).join(', ')}</td>
                  <td className="num">{p.randomDrugRatePct}</td>
                  <td className="num">{p.randomAlcoholRatePct}</td>
                  <td>{p.alcoholWindowHours} h / {p.drugWindowHours} h</td>
                  <td>{p.leadTimeMinutes} min</td>
                  <td>{p.jurisdiction}{p.consentRequired ? <span className="small muted"> · consent required</span> : ''}</td>
                  <td><PendingApprovalBadge policy={p} /></td>
                  {canWrite && <td><button type="button" className="secondary small-button" aria-label={`Edit ${humanise(p.roleGroup)} policy`} onClick={() => { setErrors([]); action.reset(); setEditing({ ...p, jobCodesText: p.jobCodes.join(', ') }); }}>Edit</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <Dialog title={`${humanise(editing.roleGroup)} policy`} onClose={() => setEditing(undefined)}>
          <form className="form" onSubmit={save} noValidate>
            <ErrorSummary errors={errors} />
            <p className="small muted">Saving a change creates a new version that needs approval again.</p>
            <Field label="Job codes" hint="Comma-separated HRIS job codes in this pool">{(id) => <input id={id} value={editing.jobCodesText} onChange={(e) => set({ jobCodesText: e.target.value })} />}</Field>
            <fieldset id="pol-panels" tabIndex={-1}>
              <legend>Panels</legend>
              {errorFor(errors, 'pol-panels') && <span className="field-error-text small">{errorFor(errors, 'pol-panels')}</span>}
              {PANELS.map((p) => (
                <label key={p} className="inline-row">
                  <input type="checkbox" checked={editing.panels.includes(p)} onChange={(e) => set({ panels: e.target.checked ? [...editing.panels, p] : editing.panels.filter((x) => x !== p) })} /> {humanise(p)}
                </label>
              ))}
            </fieldset>
            <div className="form-grid">
              <IdField id="pol-drug-rate" label="Random drug rate (% per year)" error={errorFor(errors, 'pol-drug-rate')}>{(d) => <input id="pol-drug-rate" type="number" min={0} max={100} step="0.1" aria-describedby={d} value={editing.randomDrugRatePct} onChange={(e) => set({ randomDrugRatePct: Number(e.target.value) })} />}</IdField>
              <IdField id="pol-alcohol-rate" label="Random alcohol rate (% per year)" error={errorFor(errors, 'pol-alcohol-rate')}>{(d) => <input id="pol-alcohol-rate" type="number" min={0} max={100} step="0.1" aria-describedby={d} value={editing.randomAlcoholRatePct} onChange={(e) => set({ randomAlcoholRatePct: Number(e.target.value) })} />}</IdField>
              <IdField id="pol-alcohol-window" label="Alcohol window (hours)" error={errorFor(errors, 'pol-alcohol-window')}>{(d) => <input id="pol-alcohol-window" type="number" min={1} aria-describedby={d} value={editing.alcoholWindowHours} onChange={(e) => set({ alcoholWindowHours: Number(e.target.value) })} />}</IdField>
              <IdField id="pol-drug-window" label="Drug window (hours)" error={errorFor(errors, 'pol-drug-window')}>{(d) => <input id="pol-drug-window" type="number" min={1} aria-describedby={d} value={editing.drugWindowHours} onChange={(e) => set({ drugWindowHours: Number(e.target.value) })} />}</IdField>
              <IdField id="pol-lead" label="Lead time (minutes)" error={errorFor(errors, 'pol-lead')}>{(d) => <input id="pol-lead" type="number" min={0} aria-describedby={d} value={editing.leadTimeMinutes} onChange={(e) => set({ leadTimeMinutes: Number(e.target.value) })} />}</IdField>
              <IdField id="pol-jurisdiction" label="Jurisdiction" error={errorFor(errors, 'pol-jurisdiction')}>{(d) => <input id="pol-jurisdiction" aria-describedby={d} value={editing.jurisdiction} onChange={(e) => set({ jurisdiction: e.target.value })} />}</IdField>
            </div>
            <label className="inline-row"><input type="checkbox" checked={editing.consentRequired} onChange={(e) => set({ consentRequired: e.target.checked })} /> Donor consent required</label>
            <div className="actions">
              <button type="submit" disabled={action.busy}>Save policy</button>
              <button type="button" className="secondary" onClick={() => setEditing(undefined)}>Cancel</button>
            </div>
          </form>
          <ActionFeedback error={errors.length ? undefined : action.error} />
        </Dialog>
      )}
    </section>
  );
}

/** `#/testing/pools` – random-testing pools from HRIS job codes, with exclusions (leave, long-term absence, not safety-sensitive). */
export function TestingPools({ client, onSessionExpired }: ScreenProps) {
  const api = testingApi(client);
  const [roleGroup, setRoleGroup] = useState('');
  const pools = useAsync(() => api.pools(roleGroup || undefined), onSessionExpired, [roleGroup]);
  return (
    <section>
      <PageTitle sub="Pool membership comes from HRIS job codes. Excluded members are not eligible for random selection until the exclusion ends.">Testing pools</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => e.preventDefault()}>
        <Field label="Role group">{(id) => <select id={id} value={roleGroup} onChange={(e) => setRoleGroup(e.target.value)}><SelectOptions values={ROLE_GROUPS} empty="All role groups" /></select>}</Field>
      </form>
      {pools.loading && !pools.data && <Loading />}
      {!!pools.error && <ErrorAlert error={pools.error} />}
      {pools.data && pools.data.length === 0 && <Empty>No pools.</Empty>}
      {(pools.data ?? []).map((p) => <PoolCard key={p.roleGroup} client={client} onSessionExpired={onSessionExpired} pool={p} onChanged={pools.reload} />)}
    </section>
  );
}

function PoolCard({ client, onSessionExpired, pool, onChanged }: { client: ScreenProps['client']; onSessionExpired: () => void; pool: Pool; onChanged: () => void }) {
  const [form, setForm] = useState<{ employeeId: string; reason: ExclusionReason | ''; until: string }>({ employeeId: '', reason: '', until: '' });
  const action = useAction(onSessionExpired);
  const today = todayIso();
  const excluded = new Set(pool.exclusions.filter((x) => x.until >= today).map((x) => x.employeeId));
  async function exclude(e: FormEvent) {
    e.preventDefault();
    if (!form.employeeId || !form.reason || !form.until) return;
    if (await action.run(async () => { await testingApi(client).addExclusion(pool.roleGroup, { employeeId: form.employeeId, reason: form.reason as ExclusionReason, until: form.until }); return `${form.employeeId} excluded until ${formatDay(form.until)}.`; })) {
      setForm({ employeeId: '', reason: '', until: '' });
      onChanged();
    }
  }
  return (
    <article className="card" aria-labelledby={`pool-${pool.roleGroup}`}>
      <h2 id={`pool-${pool.roleGroup}`}>{humanise(pool.roleGroup)} <span className="small muted">{pool.size} in pool · {excluded.size} excluded</span></h2>
      <details>
        <summary>Members ({pool.members.length})</summary>
        <table>
          <thead><tr><th scope="col">Employee</th><th scope="col">Job code</th><th scope="col">Eligible</th></tr></thead>
          <tbody>
            {pool.members.map((m) => (
              <tr key={m.employeeId}>
                <th scope="row">{m.displayName} <span className="small muted">{m.employeeId}</span></th>
                <td>{m.jobCode ?? '–'}</td>
                <td>{excluded.has(m.employeeId) ? <ToneBadge tone="muted">Excluded</ToneBadge> : <ToneBadge tone="green">Eligible</ToneBadge>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      <h3>Exclusions</h3>
      {pool.exclusions.length === 0 ? <Empty>No exclusions.</Empty> : (
        <ul className="plain-list">
          {pool.exclusions.map((x) => (
            <li key={`${x.employeeId}-${x.until}`}>
              {x.displayName ?? pool.members.find((m) => m.employeeId === x.employeeId)?.displayName ?? x.employeeId} – {humanise(x.reason)} until {formatDay(x.until)} {x.until < today && <span className="small muted">(ended)</span>}
            </li>
          ))}
        </ul>
      )}
      <form className="form inline-form" onSubmit={exclude} aria-label={`Add exclusion to ${humanise(pool.roleGroup)}`}>
        <Field label="Member" required>
          {(id) => (
            <select id={id} value={form.employeeId} onChange={(e) => setForm({ ...form, employeeId: e.target.value })}>
              <option value="">Choose…</option>
              {pool.members.filter((m) => !excluded.has(m.employeeId)).map((m) => <option key={m.employeeId} value={m.employeeId}>{m.displayName} ({m.employeeId})</option>)}
            </select>
          )}
        </Field>
        <Field label="Reason" required>{(id) => <select id={id} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value as ExclusionReason })}><SelectOptions values={EXCLUSION_REASONS} empty="Choose…" /></select>}</Field>
        <Field label="Until" required>{(id) => <input id={id} type="date" min={today} value={form.until} onChange={(e) => setForm({ ...form, until: e.target.value })} />}</Field>
        <button type="submit" className="secondary" disabled={action.busy || !form.employeeId || !form.reason || !form.until}>Exclude</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
    </article>
  );
}

/** `#/testing/selections` – reproducible random selection (seed commitment + pool hash), verification and notification. */
export function TestingSelections({ client, onSessionExpired }: ScreenProps) {
  const api = testingApi(client);
  const [period, setPeriod] = useState(currentPeriod());
  const list = useAsync(() => api.selections(period || undefined), onSessionExpired, [period]);
  const policies = useAsync(() => api.policies(), onSessionExpired);
  const pools = useAsync(() => api.pools(), onSessionExpired);
  const [form, setForm] = useState<SelectionForm>({ roleGroup: '', period: currentPeriod(), testType: '' });
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [verified, setVerified] = useState<Record<string, boolean>>({});
  const [notify, setNotify] = useState<Selection>();
  const action = useAction(onSessionExpired);
  const rows = list.data ?? [];

  const policy = (policies.data ?? []).find((p) => p.roleGroup === form.roleGroup);
  const pool = (pools.data ?? []).find((p) => p.roleGroup === form.roleGroup);
  const rate = form.testType === 'ALCOHOL' ? policy?.randomAlcoholRatePct : form.testType === 'DRUGS' ? policy?.randomDrugRatePct : undefined;
  const expected = pool && rate !== undefined ? expectedSelectionCount(pool.size, rate) : undefined;

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs = validateSelection(form, form.period === period ? rows : []);
    setErrors(errs);
    if (errs.length) return;
    let failure: unknown;
    const ok = await action.run(async () => {
      try {
        const s = await api.createSelection({ roleGroup: form.roleGroup as RoleGroup, period: form.period, testType: form.testType as TestType });
        return `Selection ${s.ref}: ${s.selectedCount} of ${s.poolSize} selected.`;
      } catch (x) {
        failure = x;
        throw x;
      }
    });
    if (ok) {
      setPeriod(form.period);
      list.reload();
    } else {
      const fe = serverFieldError(failure, { roleGroup: 'sel-role-group', period: 'sel-period', testType: 'sel-test-type' });
      if (fe) setErrors([fe]);
    }
  }
  async function verify(s: Selection) {
    await action.run(async () => {
      const r = await api.verifySelection(s.id);
      setVerified((v) => ({ ...v, [s.id]: r.reproducible }));
      return r.reproducible ? `${s.ref} is reproducible from the stored seed and pool snapshot.` : `${s.ref} could NOT be reproduced – escalate to the programme auditor.`;
    });
  }
  async function doNotify() {
    if (!notify) return;
    const s = notify;
    if (await action.run(async () => { await api.notifySelection(s.id); return `${s.selectedCount} employees notified; collectors dispatched.`; })) list.reload();
    setNotify(undefined);
  }

  return (
    <section>
      <PageTitle sub="Monthly random selection per role group and test type: n = ceil(pool × annual rate ÷ 12). The seed is committed and the pool snapshot hashed so every draw can be re-verified.">Random selections</PageTitle>
      <section className="card" aria-labelledby="sel-new">
        <h2 id="sel-new">New selection</h2>
        {policy && policy.approvalStatus !== 'APPROVED' && <p><PendingApprovalBadge policy={policy} /> <span className="small">Random testing for this group should wait for policy approval.</span></p>}
        <form className="form" onSubmit={create} noValidate>
          <ErrorSummary errors={errors} />
          <div className="form-grid">
            <IdField id="sel-role-group" label="Role group" required error={errorFor(errors, 'sel-role-group')}>
              {(d) => <select id="sel-role-group" aria-describedby={d} value={form.roleGroup} onChange={(e) => setForm({ ...form, roleGroup: e.target.value as RoleGroup })}><SelectOptions values={ROLE_GROUPS} empty="Choose…" /></select>}
            </IdField>
            <IdField id="sel-period" label="Period" required error={errorFor(errors, 'sel-period')} hint="YYYY-MM">
              {(d) => <input id="sel-period" type="month" aria-describedby={d} aria-invalid={!!errorFor(errors, 'sel-period')} min={currentPeriod()} value={form.period} onChange={(e) => setForm({ ...form, period: e.target.value })} />}
            </IdField>
            <IdField id="sel-test-type" label="Test type" required error={errorFor(errors, 'sel-test-type')}>
              {(d) => <select id="sel-test-type" aria-describedby={d} value={form.testType} onChange={(e) => setForm({ ...form, testType: e.target.value as SelectionForm['testType'] })}><SelectOptions values={TEST_TYPES} empty="Choose…" /></select>}
            </IdField>
          </div>
          {expected !== undefined && <p className="small" aria-live="polite">Expected: <strong>{expected}</strong> of {pool?.size} ({rate} % per year ÷ 12)</p>}
          <div className="actions"><button type="submit" disabled={action.busy}>Draw selection</button></div>
        </form>
      </section>
      <ActionFeedback error={errors.length ? undefined : action.error} message={action.message} />
      <form className="filters inline-form" onSubmit={(e) => e.preventDefault()}>
        <Field label="Show period">{(id) => <input id={id} type="month" value={period} onChange={(e) => setPeriod(e.target.value)} />}</Field>
      </form>
      {list.loading && !list.data && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No selections for {period || 'this period'}.</Empty>}
      {rows.map((s) => (
        <article key={s.id} className="card" aria-labelledby={`sel-${s.id}`}>
          <h3 id={`sel-${s.id}`}>{s.ref} – {humanise(s.roleGroup)} {s.period} {s.testType ? humanise(s.testType) : ''}</h3>
          <Dl
            items={[
              ['Selected', `${s.selectedCount} of ${s.poolSize}`],
              ['Pool hash', <span className="mono" title={s.poolHash}>{shortHash(s.poolHash)}</span>],
              ['Seed commitment', <span className="mono" title={s.seedCommitment}>{shortHash(s.seedCommitment)}</span>],
              ['Created', formatDate(s.createdAt)],
              ['Notified', s.notifiedAt ? formatDate(s.notifiedAt) : 'Not yet'],
              ['Reproducibility', verified[s.id] === undefined ? 'Not checked' : verified[s.id] ? <ToneBadge tone="green">Reproducible</ToneBadge> : <ToneBadge tone="red">Not reproducible</ToneBadge>],
            ]}
          />
          <details>
            <summary>Selected employees ({s.selected.length})</summary>
            <ul className="plain-list">{s.selected.map((p) => <li key={p.employeeId}>{p.displayName} <span className="small muted">{p.employeeId}</span></li>)}</ul>
          </details>
          <div className="card-actions">
            <button type="button" className="secondary small-button" disabled={action.busy} onClick={() => void verify(s)} aria-label={`Verify ${s.ref}`}>Verify reproducibility</button>
            {!s.notifiedAt && <button type="button" className="small-button" disabled={action.busy} onClick={() => setNotify(s)} aria-label={`Notify ${s.ref}`}>Notify employees</button>}
          </div>
        </article>
      ))}
      {notify && (
        <Dialog title={`Notify ${notify.ref}`} onClose={() => setNotify(undefined)}>
          <p>{notify.selectedCount} employees receive a content-free selection notice with a short lead time; collectors receive the dispatch. This cannot be undone.</p>
          <div className="actions">
            <button type="button" disabled={action.busy} onClick={() => void doNotify()}>Notify</button>
            <button type="button" className="secondary" onClick={() => setNotify(undefined)}>Cancel</button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
