import { useState, type FormEvent } from 'react';
import { auditPackUrl, AUDIT_TEMPLATES, COMPLIANCE_GROUP_BY, surveillanceApi, type AuditPack, type ComplianceRow } from '../../api/surveillance';
import { can, hasAnyRole } from '../../auth/roles';
import { formatDate, formatDay, humanise, isoDay } from '../../components/format';
import { LineTrend, ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { complianceText, complianceTone, trendDelta } from '../../domain/surveillance';
import type { ScreenProps } from '../types';

function OverdueDrill({ client, onSessionExpired, programme, department }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { programme?: string; department?: string }) {
  const list = useAsync(() => surveillanceApi(client).complianceOverdue({ programme, department }), onSessionExpired, [programme, department]);
  const rows = list.data ?? [];
  return (
    <section className="tile" aria-labelledby="overdue-title">
      <h2 id="overdue-title">Overdue – {[programme && humanise(programme), department].filter(Boolean).join(' · ') || 'all'}</h2>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>Nobody overdue.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Overdue people</caption>
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Unit</th>
                <th scope="col">Programme</th>
                <th scope="col">Due since</th>
                <th scope="col">Invitations</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <th scope="row"><a href={`#/employees/${encodeURIComponent(e.employeeId)}/chart/surveillance`}>{e.displayName}</a></th>
                  <td className="small">{[e.subsidiaryCode, e.departmentCode].filter(Boolean).join(' / ')}</td>
                  <td>{e.programmeTitle}</td>
                  <td>{formatDay(e.nextDueOn)}</td>
                  <td>{e.invitations}{e.escalatedAt && <> <ToneBadge tone="red">Escalated</ToneBadge></>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function AuditPacks({ client, session, onSessionExpired, programmes }: ScreenProps & { programmes: string[] }) {
  const api = surveillanceApi(client);
  const templates = useAsync(() => api.auditTemplates(), onSessionExpired);
  // AUDITOR / HSE_OFFICER always get anonymised packs (server-enforced); the box is locked for them.
  const forcedAnon = !hasAnyRole(session.roles, ['CMO', 'NURSING_HEAD']);
  const today = new Date();
  const yearAgo = new Date(today.getFullYear() - 1, today.getMonth(), today.getDate());
  const [f, setF] = useState({ template: '', programmeCode: '', from: isoDay(yearAgo), to: isoDay(today), anonymise: true });
  const [packs, setPacks] = useState<AuditPack[]>([]);
  const action = useAction(onSessionExpired);
  const options = templates.data?.length ? templates.data : AUDIT_TEMPLATES.map((code) => ({ code, title: humanise(code), sections: [] as string[] }));
  const chosen = options.find((t) => t.code === f.template);
  const ready = !!f.template && !!f.programmeCode.trim() && !!f.from && !!f.to && f.to >= f.from;

  async function generate(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    await action.run(async () => {
      const p = await api.generateAuditPack({ template: f.template, programmeCode: f.programmeCode.trim().toUpperCase(), from: f.from, to: f.to, anonymise: forcedAnon || f.anonymise });
      setPacks((cur) => [p, ...cur]);
      return `Audit pack generated (${p.anonymised ? 'anonymised' : 'identifiable'}).`;
    });
  }

  return (
    <section className="tile" aria-labelledby="audit-title">
      <h2 id="audit-title">Audit packs</h2>
      {!!templates.error && <ErrorAlert error={templates.error} />}
      <form className="form" onSubmit={generate}>
        <div className="inline-form">
          <Field label="Template" required>
            {(id) => (
              <select id={id} required value={f.template} onChange={(e) => setF({ ...f, template: e.target.value })}>
                <option value="">Choose…</option>
                {options.map((t) => <option key={t.code} value={t.code}>{t.title}</option>)}
              </select>
            )}
          </Field>
          <Field label="Programme" required>
            {(id) => (
              <>
                <input id={id} required list={`${id}-list`} maxLength={40} value={f.programmeCode} onChange={(e) => setF({ ...f, programmeCode: e.target.value.toUpperCase() })} />
                <datalist id={`${id}-list`}>{programmes.map((p) => <option key={p} value={p} />)}</datalist>
              </>
            )}
          </Field>
          <Field label="From" required>{(id) => <input id={id} type="date" required value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} />}</Field>
          <Field label="To" required>{(id) => <input id={id} type="date" required value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} />}</Field>
        </div>
        {chosen && chosen.sections.length > 0 && <p className="small muted">Sections: {chosen.sections.join(' · ')}</p>}
        <label className="inline-row">
          <input type="checkbox" checked={forcedAnon || f.anonymise} disabled={forcedAnon} onChange={(e) => setF({ ...f, anonymise: e.target.checked })} /> Anonymise
          {forcedAnon && <span className="small muted"> (always anonymised for your role)</span>}
        </label>
        <button type="submit" disabled={action.busy || !ready}>{action.busy ? 'Generating…' : 'Generate audit pack'}</button>
      </form>
      <ActionFeedback error={action.error} message={action.message} />
      {packs.length > 0 && (
        <ul className="plain-list" aria-label="Generated audit packs">
          {packs.map((p) => (
            <li key={p.id}>
              <strong>{humanise(p.template)}</strong> · {humanise(p.programmeCode)} · {formatDate(p.generatedAt)} · {p.anonymised ? 'anonymised' : 'identifiable'}{' '}
              {p.files.map((file) => (
                <a key={file.format} className="button secondary small-button" href={auditPackUrl(p.id, file.format)} download={`audit-pack-${p.template}-${p.programmeCode}.${file.format}`} rel="noopener">
                  Download {file.format.toUpperCase()}
                </a>
              ))}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** `#/surveillance/compliance` – compliance by programme / unit, trend, overdue drill-down (OH only), audit packs (E07-F08). */
export function ComplianceScreen(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const api = surveillanceApi(client);
  const [form, setForm] = useState({ groupBy: 'PROGRAMME', programme: '' });
  const [applied, setApplied] = useState(form);
  const data = useAsync(() => api.compliance({ groupBy: applied.groupBy, programme: applied.programme || undefined }), onSessionExpired, [applied]);
  const trend = useAsync(() => api.complianceTrend(applied.programme || undefined), onSessionExpired, [applied.programme]);
  const programmes = useAsync(() => api.programmes().then((ps) => ps.map((p) => p.code)).catch(() => [] as string[]), onSessionExpired);
  const [drill, setDrill] = useState<{ programme?: string; department?: string }>();
  const drillDown = can(session.roles, 'complianceOverdue');
  const rows: ComplianceRow[] = data.data?.rows ?? [];
  const points = trend.data ?? [];

  const drillFor = (r: ComplianceRow) =>
    applied.groupBy === 'PROGRAMME' ? { programme: r.key } : applied.groupBy === 'DEPARTMENT' ? { programme: applied.programme || undefined, department: r.key } : undefined;

  return (
    <section>
      <PageTitle sub={`Nightly snapshot${data.data?.refreshedAt ? ` – refreshed ${formatDate(data.data.refreshedAt)}` : ''}. Groups below the minimum size are suppressed.`}>Surveillance compliance</PageTitle>
      <form className="filters inline-form" onSubmit={(e) => { e.preventDefault(); setDrill(undefined); setApplied({ ...form, programme: form.programme.trim().toUpperCase() }); }}>
        <Field label="Group by">
          {(id) => (
            <select id={id} value={form.groupBy} onChange={(e) => setForm({ ...form, groupBy: e.target.value })}>
              <SelectOptions values={COMPLIANCE_GROUP_BY} />
            </select>
          )}
        </Field>
        <Field label="Programme">
          {(id) => (
            <select id={id} value={form.programme} onChange={(e) => setForm({ ...form, programme: e.target.value })}>
              <option value="">All programmes</option>
              {(programmes.data ?? []).map((p) => <option key={p} value={p}>{humanise(p)}</option>)}
            </select>
          )}
        </Field>
        <button type="submit" className="secondary">Apply</button>
      </form>
      {data.loading && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {data.data && rows.length === 0 && <Empty>No compliance data.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>Compliance by {humanise(applied.groupBy).toLowerCase()}</caption>
            <thead>
              <tr>
                <th scope="col">{humanise(applied.groupBy)}</th>
                <th scope="col" className="num">Enrolled</th>
                <th scope="col" className="num">Current</th>
                <th scope="col" className="num">Due ≤ 30 d</th>
                <th scope="col" className="num">Overdue</th>
                <th scope="col">Compliance</th>
                {drillDown && <th scope="col"><span className="visually-hidden">Drill down</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) =>
                r.suppressed ? (
                  <tr key={r.key} className="row-suppressed">
                    <th scope="row">{r.label}</th>
                    <td colSpan={drillDown ? 6 : 5} className="suppressed">Suppressed – group too small to report</td>
                  </tr>
                ) : (
                  <tr key={r.key}>
                    <th scope="row">{r.label}</th>
                    <td className="num">{r.enrolled ?? '–'}</td>
                    <td className="num">{r.current ?? '–'}</td>
                    <td className="num">{r.due ?? '–'}</td>
                    <td className="num">{r.overdue ?? '–'}</td>
                    <td><ToneBadge tone={complianceTone(r.compliancePct)}>{complianceText(r)}</ToneBadge></td>
                    {drillDown && (
                      <td>
                        {(r.overdue ?? 0) > 0 && drillFor(r) && (
                          <button type="button" className="secondary small-button" onClick={() => setDrill(drillFor(r))} aria-label={`Show overdue for ${r.label}`}>
                            Overdue ({r.overdue})
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
      {drill && drillDown && <OverdueDrill client={client} onSessionExpired={onSessionExpired} {...drill} />}
      <h2>Trend {points.length > 1 && <span className="small muted">({trendDelta(points)})</span>}</h2>
      {!!trend.error && <ErrorAlert error={trend.error} />}
      {trend.data && (
        <LineTrend labels={points.map((p) => p.date)} label={`Compliance trend${applied.programme ? ` for ${applied.programme}` : ''}`} max={100} series={[{ name: 'Compliance %', values: points.map((p) => p.compliancePct), format: (v) => `${v.toFixed(1)} %` }]} />
      )}
      {can(session.roles, 'auditPacks') && <AuditPacks {...props} programmes={programmes.data ?? []} />}
    </section>
  );
}
