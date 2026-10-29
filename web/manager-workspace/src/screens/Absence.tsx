import { useState, type FormEvent } from 'react';
import {
  ANALYTICS_GROUP_BY,
  ANALYTICS_GROUP_LABELS,
  analyticsExportUrl,
  analyticsUnitsPath,
  buildHrStage,
  CERTIFICATE_VALIDITY_LABELS,
  emptyTeamAbsenceFilter,
  filterTeamAbsence,
  formatMetric,
  HR_STAGE_FIELDS,
  HR_STAGE_LABELS,
  HR_STAGE_NOTE_MAX,
  hrStageDraftFor,
  hrStagePath,
  isValidMonth,
  milestoneLabel,
  previousMonth,
  SETTABLE_HR_STAGES,
  summariseTeamAbsence,
  suppressedText,
  toLtaRows,
  toTeamAbsence,
  toUnitAnalytics,
  toWorkspaceCertificates,
  validateAnalyticsQuery,
  validateHrStage,
  type AnalyticsGroupBy,
  type AnalyticsQuery,
  type CertificateValidity,
  type HrStage,
  type HrStageDraft,
  type LtaRow,
  type TeamAbsenceFilter,
  type TeamAbsenceRow,
  type UnitAnalytics,
  type WorkspaceCertificate,
} from '../api/absence';
import { CLINICAL_CONTENT_MESSAGE, GUIDANCE, type FieldErrors } from '../api/referrals';
import { submitAction } from '../api/submit';
import { absenceSections } from '../auth/roles';
import { FieldError, InputField, TextAreaField } from '../components/FormFields';
import { focusFirstError } from '../components/focus';
import { describeError, formatDay, idPart } from '../components/format';
import { hrStageIds } from '../components/ids';
import { NoClinicalInfoNote } from '../components/Restrictions';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

export function ValidityBadge({ validity }: { validity?: CertificateValidity }) {
  if (!validity) return <span className="muted">No certificate</span>;
  return <StatusBadge status={`cert-${validity}`} label={CERTIFICATE_VALIDITY_LABELS[validity]} />;
}

const days = (n?: number) => (n === undefined ? '–' : `${n} ${n === 1 ? 'day' : 'days'}`);

/* ---------------- team absence ---------------- */

export interface TeamAbsenceViewProps {
  rows: readonly TeamAbsenceRow[];
  filter: TeamAbsenceFilter;
  onFilter: (f: TeamAbsenceFilter) => void;
}

/** E06-F01 – team absence status (no reasons). Hook-free view. */
export function TeamAbsenceView({ rows, filter, onFilter }: TeamAbsenceViewProps) {
  const shown = filterTeamAbsence(rows, filter);
  const sum = summariseTeamAbsence(rows);
  return (
    <>
      <p className="muted small">
        {sum.total} team members · {sum.absentNow} currently absent · {sum.ohEngaged} with Occupational Health engaged
      </p>
      <form className="filters" role="search" aria-label="Filter team absence" onSubmit={(e) => e.preventDefault()}>
        <div className="field">
          <label htmlFor="ab-q">Employee</label>
          <input id="ab-q" type="search" autoComplete="off" value={filter.q} onChange={(e) => onFilter({ ...filter, q: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="ab-show">Show</label>
          <select id="ab-show" value={filter.show} onChange={(e) => onFilter({ ...filter, show: e.target.value as TeamAbsenceFilter['show'] })}>
            <option value="">All team members</option>
            <option value="OPEN">Currently absent</option>
            <option value="OH">Occupational Health engaged</option>
          </select>
        </div>
      </form>
      <p className="muted small" role="status" aria-live="polite">
        Showing {shown.length} of {rows.length} team members.
      </p>
      {rows.length === 0 && <p className="muted">No team members in your scope.</p>}
      {shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Team absence status</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Current absence</th>
                <th scope="col">Absences in last 12 months</th>
                <th scope="col">Occupational Health</th>
                <th scope="col">Fit note / certificate</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.employeeId}>
                  <th scope="row">
                    {r.displayName}
                    <span className="muted small"> {r.employeeId}</span>
                  </th>
                  <td>
                    {r.openEpisode ? (
                      <>
                        <strong>{days(r.openEpisode.days)}</strong>
                        <span className="muted small"> since {formatDay(r.openEpisode.startDate)}</span>
                      </>
                    ) : (
                      <span className="muted">At work</span>
                    )}
                  </td>
                  <td className="num">{r.episodes12m ?? '–'}</td>
                  <td>{r.ohEngaged ? <StatusBadge status="oh-engaged" label="OH engaged" /> : <span className="muted">–</span>}</td>
                  <td>
                    <ValidityBadge validity={r.certificateValidity} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/* ---------------- long-term absence (HR) ---------------- */

export interface LtaCardProps {
  row: LtaRow;
  canSetStage: boolean;
  draft: HrStageDraft;
  errors: FieldErrors;
  message?: string;
  busy: boolean;
  onDraft: (d: HrStageDraft) => void;
  onSubmit: (e: FormEvent) => void;
}

/** One long-term absence pathway: status, weeks, next milestone, released advice and the HR-stage action. Hook-free. */
export function LtaCard({ row, canSetStage, draft, errors, message, busy, onDraft, onSubmit }: LtaCardProps) {
  const ids = hrStageIds(row.caseId);
  const headingId = `lta-${idPart(row.caseId)}-heading`;
  return (
    <li className="card">
      <article aria-labelledby={headingId}>
        <div className="card-row">
          <h3 id={headingId}>
            {row.displayName}
            {row.caseRef && <span className="muted small"> {row.caseRef}</span>}
          </h3>
          <StatusBadge status={`stage-${row.hrStage}`} label={`HR stage: ${HR_STAGE_LABELS[row.hrStage]}`} />
        </div>
        <dl className="details">
          <dt>Absent since</dt>
          <dd>{formatDay(row.absenceStart)}</dd>
          <dt>Weeks absent</dt>
          <dd>{row.weeksAbsent ?? '–'}</dd>
          <dt>Next milestone</dt>
          <dd>
            {row.nextMilestone ? (
              <>
                {milestoneLabel(row.nextMilestone.type)}
                {row.nextMilestone.week !== undefined && <span className="muted"> (week {row.nextMilestone.week})</span>} – due{' '}
                {formatDay(row.nextMilestone.dueDate)}
              </>
            ) : (
              <span className="muted">None scheduled</span>
            )}
          </dd>
          <dt>OH advice released</dt>
          <dd>
            {row.adviceReleased === 0 ? (
              <span className="muted">None yet</span>
            ) : (
              <>
                {row.adviceReleased} {row.adviceReleased === 1 ? 'report' : 'reports'} – <a href="#/advice-requests">view in OH advice</a>
              </>
            )}
          </dd>
        </dl>
        {canSetStage && (
          <form className="form" noValidate onSubmit={onSubmit} aria-label={`Update HR stage for ${row.displayName}`}>
            {message && <Alert kind="error">{message}</Alert>}
            <div className={errors.stage ? 'field field-invalid' : 'field'}>
              <label htmlFor={ids.stage}>
                HR stage<span className="req" aria-hidden="true"> *</span>
              </label>
              <FieldError id={ids.stage} error={errors.stage} />
              <select
                id={ids.stage}
                value={draft.stage}
                aria-required
                aria-invalid={errors.stage ? true : undefined}
                aria-describedby={errors.stage ? `${ids.stage}-error` : undefined}
                onChange={(e) => onDraft({ ...draft, stage: e.target.value as HrStage | '' })}
              >
                <option value="">Select…</option>
                {SETTABLE_HR_STAGES.map((st) => (
                  <option key={st} value={st}>
                    {HR_STAGE_LABELS[st]}
                  </option>
                ))}
              </select>
            </div>
            <TextAreaField
              id={ids.note}
              label="Note (optional)"
              max={HR_STAGE_NOTE_MAX}
              value={draft.note}
              onChange={(v) => onDraft({ ...draft, note: v })}
              hint={`Visible to HR and Occupational Health. ${GUIDANCE}`}
              error={errors.note}
              rows={2}
            />
            <button type="submit" disabled={busy}>
              {busy ? 'Saving…' : 'Update HR stage'}
            </button>
          </form>
        )}
      </article>
    </li>
  );
}

/* ---------------- certificates (HR) ---------------- */

/** Certificate validity with the escalation flag – no certificate content. Hook-free. */
export function CertificateTable({ items }: { items: readonly WorkspaceCertificate[] }) {
  if (items.length === 0) return <p className="muted">No fit notes or certificates in your scope.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">Certificate validity</caption>
        <thead>
          <tr>
            <th scope="col">Employee</th>
            <th scope="col">Covers</th>
            <th scope="col">Validity</th>
            <th scope="col">Escalated to HR</th>
          </tr>
        </thead>
        <tbody>
          {items.map((c) => (
            <tr key={c.id}>
              <th scope="row">
                {c.displayName}
                {c.employeeId && <span className="muted small"> {c.employeeId}</span>}
              </th>
              <td>
                {formatDay(c.fromDate)} – {formatDay(c.toDate)}
              </td>
              <td>
                <ValidityBadge validity={c.validity} />
              </td>
              <td>{c.escalated ? <StatusBadge status="escalated" label="Escalated – action needed" /> : <span className="muted">No</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ---------------- analytics (HR) ---------------- */

/** Unit-level absence analytics; suppressed rows show no numbers. Hook-free. */
export function UnitAnalyticsTable({ result, groupBy }: { result: UnitAnalytics; groupBy: AnalyticsGroupBy }) {
  if (result.rows.length === 0) return <p className="muted">No data for this period.</p>;
  const k = result.minGroupSize;
  return (
    <div className="table-wrap">
      <table>
        <caption>Absence by {ANALYTICS_GROUP_LABELS[groupBy].toLowerCase()}</caption>
        <thead>
          <tr>
            <th scope="col">{ANALYTICS_GROUP_LABELS[groupBy]}</th>
            <th scope="col">Headcount</th>
            <th scope="col">Episodes</th>
            <th scope="col">Days lost</th>
            <th scope="col">Absence rate %</th>
            <th scope="col">Frequency rate</th>
            <th scope="col">Avg. duration (days)</th>
            <th scope="col">Bradford mean</th>
          </tr>
        </thead>
        <tbody>
          {result.rows.map((r) => (
            <tr key={r.key}>
              <th scope="row">{r.label}</th>
              {r.suppressed ? (
                <td colSpan={7} className="suppressed">
                  {suppressedText(k)}
                </td>
              ) : (
                <>
                  <td className="num">{formatMetric(r.headcount)}</td>
                  <td className="num">{formatMetric(r.episodes)}</td>
                  <td className="num">{formatMetric(r.daysLost)}</td>
                  <td className="num">{formatMetric(r.absenceRatePct, 1)}</td>
                  <td className="num">{formatMetric(r.frequencyRate, 2)}</td>
                  <td className="num">{formatMetric(r.avgDurationDays, 1)}</td>
                  <td className="num">{formatMetric(r.bradfordMean, 0)}</td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export interface AnalyticsFormProps {
  query: AnalyticsQuery;
  errors: FieldErrors;
  onQuery: (q: AnalyticsQuery) => void;
  onSubmit: (e: FormEvent) => void;
}

export function AnalyticsForm({ query, errors, onQuery, onSubmit }: AnalyticsFormProps) {
  return (
    <form className="filters" noValidate aria-label="Absence analytics period" onSubmit={onSubmit}>
      <div className="field">
        <label htmlFor="an-groupBy">Group by</label>
        <select id="an-groupBy" value={query.groupBy} onChange={(e) => onQuery({ ...query, groupBy: e.target.value as AnalyticsGroupBy })}>
          {ANALYTICS_GROUP_BY.map((g) => (
            <option key={g} value={g}>
              {ANALYTICS_GROUP_LABELS[g]}
            </option>
          ))}
        </select>
      </div>
      <InputField id="an-from" type="date" label="From" value={query.from} onChange={(v) => onQuery({ ...query, from: v })} error={errors.from} />
      <InputField id="an-to" type="date" label="To" value={query.to} onChange={(v) => onQuery({ ...query, to: v })} error={errors.to} />
      <button type="submit" className="secondary">
        Show
      </button>
    </form>
  );
}

export function AnalyticsExport({ month, onMonth }: { month: string; onMonth: (m: string) => void }) {
  const valid = isValidMonth(month);
  return (
    <form className="filters" aria-label="Monthly absence export" onSubmit={(e) => e.preventDefault()}>
      <div className="field">
        <label htmlFor="an-month">Month</label>
        <input id="an-month" type="month" value={month} onChange={(e) => onMonth(e.target.value)} aria-describedby="an-month-hint" />
      </div>
      {valid ? (
        <a className="button secondary" href={analyticsExportUrl(month)} download={`absence-pack-${month}.csv`}>
          Download monthly pack (CSV)
        </a>
      ) : (
        <span className="muted small">Choose a month to download its pack.</span>
      )}
      <p id="an-month-hint" className="muted small">
        Unit-level figures only; small groups are suppressed in the file as well. Downloads are logged.
      </p>
    </form>
  );
}

/* ---------------- HR sections ---------------- */

function HrAbsenceSections({ client, onSessionExpired, canSetStage }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { canSetStage: boolean }) {
  const lta = useAsync(async () => toLtaRows(await client.api<unknown>('/workspace/absence/lta')), onSessionExpired);
  const certs = useAsync(async () => toWorkspaceCertificates(await client.api<unknown>('/workspace/absence/certificates')), onSessionExpired);

  const [drafts, setDrafts] = useState<Record<string, HrStageDraft>>({});
  const [errors, setErrors] = useState<Record<string, FieldErrors>>({});
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string>();
  const [notice, setNotice] = useState<string>();

  const [query, setQuery] = useState<AnalyticsQuery>({ groupBy: 'DEPARTMENT', from: '', to: '' });
  const [applied, setApplied] = useState<AnalyticsQuery>(query);
  const [queryErrors, setQueryErrors] = useState<FieldErrors>({});
  const analytics = useAsync(async () => toUnitAnalytics(await client.api<unknown>(analyticsUnitsPath(applied))), onSessionExpired);
  const [month, setMonth] = useState(previousMonth);

  async function updateStage(row: LtaRow, e: FormEvent) {
    e.preventDefault();
    setNotice(undefined);
    const draft = drafts[row.caseId] ?? hrStageDraftFor(row);
    const ids = hrStageIds(row.caseId);
    const v = validateHrStage(draft, row.hrStage);
    setErrors((x) => ({ ...x, [row.caseId]: v }));
    setMessages((x) => ({ ...x, [row.caseId]: '' }));
    if (Object.keys(v).length) {
      focusFirstError([v.stage ? ids.stage : '', v.note ? ids.note : ''].filter(Boolean));
      return;
    }
    setBusy(row.caseId);
    const r = await submitAction(client, hrStagePath(row.caseId), buildHrStage(draft), HR_STAGE_FIELDS, { failed: 'The HR stage was not updated' });
    setBusy(undefined);
    if (r.ok) {
      setDrafts((x) => Object.fromEntries(Object.entries(x).filter(([k]) => k !== row.caseId)));
      setNotice(`HR stage for ${row.displayName} updated to "${HR_STAGE_LABELS[draft.stage as HrStage]}".`);
      lta.reload();
      return;
    }
    if (r.sessionExpired) onSessionExpired();
    // The note is the only screened free text – a clinical-content 422 without a field still belongs to it.
    const fe = r.clinical && !Object.keys(r.fieldErrors).length && draft.note.trim() ? { note: CLINICAL_CONTENT_MESSAGE } : r.fieldErrors;
    setErrors((x) => ({ ...x, [row.caseId]: fe }));
    setMessages((x) => ({ ...x, [row.caseId]: r.message }));
    focusFirstError([fe.stage ? ids.stage : '', fe.note ? ids.note : ''].filter(Boolean));
  }

  function showAnalytics(e: FormEvent) {
    e.preventDefault();
    const v = validateAnalyticsQuery(query);
    setQueryErrors(v);
    if (Object.keys(v).length) return;
    setApplied(query);
    analytics.reload();
  }

  return (
    <>
      <h2 className="section-gap" id="lta-heading">
        Long-term absence
      </h2>
      <p className="muted small">
        Employees on the long-term absence pathway (28 days or more). Occupational Health manages contacts and milestones; you
        record the HR stage.
      </p>
      {notice && <Alert kind="success">{notice}</Alert>}
      {lta.loading && <Loading />}
      {!!lta.error && <Alert kind="error">{describeError(lta.error)}</Alert>}
      {lta.data && lta.data.length === 0 && <p className="muted">No one in your scope is on the long-term absence pathway.</p>}
      {lta.data && lta.data.length > 0 && (
        <ul className="card-list" aria-labelledby="lta-heading">
          {lta.data.map((row) => (
            <LtaCard
              key={row.caseId}
              row={row}
              canSetStage={canSetStage}
              draft={drafts[row.caseId] ?? hrStageDraftFor(row)}
              errors={errors[row.caseId] ?? {}}
              message={messages[row.caseId] || undefined}
              busy={busy === row.caseId}
              onDraft={(d) => {
                setDrafts((x) => ({ ...x, [row.caseId]: d }));
                setErrors((x) => ({ ...x, [row.caseId]: {} }));
              }}
              onSubmit={(e) => void updateStage(row, e)}
            />
          ))}
        </ul>
      )}

      <h2 className="section-gap">Fit notes and certificates</h2>
      <p className="muted small">Validity only. Certificates escalated by Occupational Health need follow-up under the absence policy.</p>
      {certs.loading && <Loading />}
      {!!certs.error && <Alert kind="error">{describeError(certs.error)}</Alert>}
      {certs.data && <CertificateTable items={certs.data} />}

      <h2 className="section-gap">Absence analytics</h2>
      <article className="tile" aria-label="Absence analytics by unit">
        <p className="muted small">
          De-identified, by unit only – never by person. Units with fewer than {analytics.data?.minGroupSize ?? 5} people are suppressed.
        </p>
        <AnalyticsForm query={query} errors={queryErrors} onQuery={setQuery} onSubmit={showAnalytics} />
        {analytics.loading && <Loading />}
        {!!analytics.error && <Alert kind="error">{describeError(analytics.error)}</Alert>}
        {analytics.data && <UnitAnalyticsTable result={analytics.data} groupBy={applied.groupBy} />}
        <h3 className="section-gap">Monthly export</h3>
        <AnalyticsExport month={month} onMonth={setMonth} />
      </article>
    </>
  );
}

/* ---------------- screen ---------------- */

export function Absence({ client, session, onSessionExpired }: ScreenProps) {
  const sections = absenceSections(session.roles);
  const team = useAsync(async () => toTeamAbsence(await client.api<unknown>('/workspace/absence/team')), onSessionExpired);
  const [filter, setFilter] = useState<TeamAbsenceFilter>(emptyTeamAbsenceFilter);
  return (
    <section>
      <PageTitle>Absence</PageTitle>
      <p className="lead">Who is absent, for how long, and whether Occupational Health is involved.</p>
      <NoClinicalInfoNote>
        No medical details are shown – absence reasons, diagnoses and certificate content stay with Occupational Health. You see
        dates, counts and status only.
      </NoClinicalInfoNote>
      <div className="toolbar">
        <a className="button secondary" href="#/advice-requests">
          Request OH advice
        </a>
      </div>
      <h2>Team absence status</h2>
      {team.loading && <Loading />}
      {!!team.error && <Alert kind="error">{describeError(team.error)}</Alert>}
      {team.data && <TeamAbsenceView rows={team.data} filter={filter} onFilter={setFilter} />}
      {sections.lta && <HrAbsenceSections client={client} onSessionExpired={onSessionExpired} canSetStage={sections.hrStage} />}
    </section>
  );
}
