import { useState, type FormEvent } from 'react';
import { authorityReportUrl, CREW_CERT_STATUSES, CREW_CERT_TYPES, CREW_EXAM_OUTCOMES, CREW_EXAM_TYPES, surveillanceApi, type CrewCertificate } from '../../api/surveillance';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { formatDay, humanise, isoDay } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { crewExpiryBand, splitCodes } from '../../domain/surveillance';
import type { ScreenProps } from '../types';

type Section = 'certificates' | 'without' | 'exam' | 'import';

function CertificatesView({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const [f, setF] = useState({ status: '', expiringWithinDays: '' });
  const list = useAsync(() => surveillanceApi(client).crewCertificates(f), onSessionExpired, [f]);
  const rows: CrewCertificate[] = [...(list.data ?? [])].sort((a, b) => a.validUntil.localeCompare(b.validUntil));
  return (
    <>
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}>
              <SelectOptions values={CREW_CERT_STATUSES} empty="All" />
            </select>
          )}
        </Field>
        <Field label="Expiring within">
          {(id) => (
            <select id={id} value={f.expiringWithinDays} onChange={(e) => setF({ ...f, expiringWithinDays: e.target.value })}>
              <option value="">Any time</option>
              <option value="30">30 days</option>
              <option value="60">60 days</option>
              <option value="90">90 days</option>
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No certificates match.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} certificate{rows.length === 1 ? '' : 's'}</caption>
            <thead>
              <tr>
                <th scope="col">Crew member</th>
                <th scope="col">Type</th>
                <th scope="col">Number / authority</th>
                <th scope="col">Examined</th>
                <th scope="col">Valid until</th>
                <th scope="col">Expiry band</th>
                <th scope="col">Limitations</th>
                <th scope="col">Source</th>
                <th scope="col">Alerts sent</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => {
                const band = crewExpiryBand(c.validUntil, undefined, c.status);
                return (
                  <tr key={c.id} className={band.band === 'EXPIRED' || band.band === 'SUSPENDED' ? 'row-breached' : undefined}>
                    <th scope="row">{c.displayName} <span className="small muted">{c.employeeId}</span></th>
                    <td>{humanise(c.certificateType)}</td>
                    <td className="small">{c.certificateNumber ?? '–'}{c.issuingAuthority ? ` · ${c.issuingAuthority}` : ''}</td>
                    <td>{formatDay(c.examinedOn)}</td>
                    <td>{formatDay(c.validUntil)}</td>
                    <td><ToneBadge tone={band.tone}>{band.label}</ToneBadge></td>
                    <td className="mono small">{c.limitationCodes.length ? c.limitationCodes.join(', ') : '–'}</td>
                    <td className="small">{c.source === 'EXTERNAL_AME' ? `External AME${c.aemcName ? ` (${c.aemcName})` : ''}` : 'Internal'}</td>
                    <td className="small">{c.alertsSent?.length ? c.alertsSent.map((d) => `${d} d`).join(', ') : '–'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function WithoutValidMedical({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const list = useAsync(() => surveillanceApi(client).withoutValidMedical(), onSessionExpired);
  const rows = list.data ?? [];
  return (
    <>
      <p className="small muted">Crew who must not be rostered: expired, suspended or no medical certificate.</p>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>All crew hold a valid medical.</Empty>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption>{rows.length} crew without a valid medical</caption>
            <thead>
              <tr>
                <th scope="col">Crew member</th>
                <th scope="col">Job</th>
                <th scope="col">Certificate</th>
                <th scope="col">Valid until</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.employeeId}>
                  <th scope="row">{r.displayName} <span className="small muted">{r.employeeId}</span></th>
                  <td>{humanise(r.jobCode)}</td>
                  <td>{humanise(r.certificateType)}</td>
                  <td>{formatDay(r.validUntil)}</td>
                  <td><ToneBadge tone="red">{humanise(r.status)}</ToneBadge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

const isBool = (t: string) => ['BOOLEAN', 'YES_NO', 'CHECKBOX', 'BOOL'].includes(t.toUpperCase());

function ExamForm({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const [type, setType] = useState<string>('CLASS1');
  const tpl = useAsync(() => surveillanceApi(client).examTemplate(type), onSessionExpired, [type]);
  const [emp, setEmp] = useState<EmployeeRef>();
  const [answers, setAnswers] = useState<Record<string, string | boolean>>({});
  const [form, setForm] = useState({ examinedOn: isoDay(new Date()), outcome: 'FIT', limitationCodes: '', validUntil: '' });
  const action = useAction(onSessionExpired);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!emp) return;
    const ok = await action.run(async () => {
      const c = await surveillanceApi(client).crewExamination({
        employeeId: emp.employeeId,
        certificateType: type,
        examinedOn: form.examinedOn,
        answers,
        outcome: form.outcome,
        limitationCodes: splitCodes(form.limitationCodes),
        ...(form.validUntil ? { validUntil: form.validUntil } : {}),
      });
      return `Examination recorded for ${emp.displayName}: certificate ${humanise(c.status).toLowerCase()} until ${formatDay(c.validUntil)}. Roster status published.`;
    });
    if (ok) {
      setEmp(undefined);
      setAnswers({});
    }
  }

  return (
    <>
      <div className="inline-form">
        <Field label="Certificate type">
          {(id) => (
            <select id={id} value={type} onChange={(e) => { setType(e.target.value); setAnswers({}); }}>
              <SelectOptions values={CREW_EXAM_TYPES} />
            </select>
          )}
        </Field>
      </div>
      {!emp ? (
        <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmp} label="Crew member (name or employee ID)" />
      ) : (
        <form className="form config-form" onSubmit={submit}>
          <p>Crew member: <strong>{emp.displayName}</strong> ({emp.employeeId}) <button type="button" className="link-button" onClick={() => setEmp(undefined)}>Change</button></p>
          <Field label="Examined on" required>{(id) => <input id={id} type="date" required max={isoDay(new Date())} value={form.examinedOn} onChange={(e) => setForm({ ...form, examinedOn: e.target.value })} />}</Field>
          {tpl.loading && <Loading label="Loading CAA examination template…" />}
          {!!tpl.error && <ErrorAlert error={tpl.error} />}
          {(tpl.data ?? []).map((s) => (
            <fieldset key={s.section} className="template-section">
              <legend>{s.section}</legend>
              <div className="form-grid">
                {s.items.map((it) =>
                  isBool(it.type) ? (
                    <label key={it.code} className="inline-row">
                      <input type="checkbox" checked={answers[it.code] === true} onChange={(e) => setAnswers({ ...answers, [it.code]: e.target.checked })} /> {it.label}
                    </label>
                  ) : (
                    <Field key={it.code} label={it.label}>
                      {(id) =>
                        it.type.toUpperCase() === 'TEXTAREA' ? (
                          <textarea id={id} rows={2} maxLength={2000} value={String(answers[it.code] ?? '')} onChange={(e) => setAnswers({ ...answers, [it.code]: e.target.value })} />
                        ) : (
                          <input
                            id={id}
                            type={it.type.toUpperCase() === 'NUMBER' ? 'number' : it.type.toUpperCase() === 'DATE' ? 'date' : 'text'}
                            step="any"
                            maxLength={500}
                            value={String(answers[it.code] ?? '')}
                            onChange={(e) => setAnswers({ ...answers, [it.code]: e.target.value })}
                          />
                        )
                      }
                    </Field>
                  ),
                )}
              </div>
            </fieldset>
          ))}
          <fieldset>
            <legend>Assessment</legend>
            <div className="inline-form">
              <Field label="Outcome" required>
                {(id) => (
                  <select id={id} value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value })}>
                    <SelectOptions values={CREW_EXAM_OUTCOMES} />
                  </select>
                )}
              </Field>
              <Field label="Limitation codes" hint="Comma-separated, e.g. VDL, OML">{(id) => <input id={id} value={form.limitationCodes} onChange={(e) => setForm({ ...form, limitationCodes: e.target.value })} />}</Field>
              <Field label="Valid until" hint="Empty = calculated from the certificate rules">{(id) => <input id={id} type="date" value={form.validUntil} onChange={(e) => setForm({ ...form, validUntil: e.target.value })} />}</Field>
            </div>
          </fieldset>
          <button type="submit" disabled={action.busy}>Record examination</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </>
  );
}

async function fileToBase64(f: File): Promise<string> {
  const bytes = new Uint8Array(await f.arrayBuffer());
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

function ImportForm({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const empty = { employeeId: '', certificateType: 'CLASS1', certificateNumber: '', issuingAuthority: '', aemcName: '', ameName: '', examinedOn: '', validUntil: '', limitationCodes: '' };
  const [f, setF] = useState(empty);
  const [pdf, setPdf] = useState<File>();
  const action = useAction(onSessionExpired);
  const ready = f.employeeId.trim() && f.certificateNumber.trim() && f.issuingAuthority.trim() && f.aemcName.trim() && f.examinedOn && f.validUntil && f.validUntil >= f.examinedOn;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    const ok = await action.run(async () => {
      const c = await surveillanceApi(client).importCrewCertificate({
        employeeId: f.employeeId.trim().toUpperCase(),
        certificateType: f.certificateType,
        certificateNumber: f.certificateNumber.trim(),
        issuingAuthority: f.issuingAuthority.trim(),
        aemcName: f.aemcName.trim(),
        ...(f.ameName.trim() ? { ameName: f.ameName.trim() } : {}),
        examinedOn: f.examinedOn,
        validUntil: f.validUntil,
        limitationCodes: splitCodes(f.limitationCodes),
        ...(pdf ? { pdfBase64: await fileToBase64(pdf) } : {}),
      });
      return `External certificate imported for ${c.displayName ?? f.employeeId} (valid until ${formatDay(c.validUntil)}).`;
    });
    if (ok) {
      setF(empty);
      setPdf(undefined);
    }
  }

  const input = (k: keyof typeof empty, label: string, required = false, type = 'text') => (
    <Field label={label} required={required}>{(id) => <input id={id} type={type} required={required} maxLength={120} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} />}</Field>
  );

  return (
    <form className="form config-form" onSubmit={submit}>
      <p className="small muted">Structured import of a medical issued by an external AME / AeMC. The certificate is validated and recorded with source “External AME”.</p>
      <div className="form-grid">
        {input('employeeId', 'Employee ID', true)}
        <Field label="Certificate type" required>
          {(id) => (
            <select id={id} value={f.certificateType} onChange={(e) => setF({ ...f, certificateType: e.target.value })}>
              <SelectOptions values={CREW_CERT_TYPES} />
            </select>
          )}
        </Field>
        {input('certificateNumber', 'Certificate number', true)}
        {input('issuingAuthority', 'Issuing authority', true)}
        {input('aemcName', 'AeMC', true)}
        {input('ameName', 'AME name')}
        {input('examinedOn', 'Examined on', true, 'date')}
        {input('validUntil', 'Valid until', true, 'date')}
        {input('limitationCodes', 'Limitation codes (comma-separated)')}
        <Field label="Certificate PDF (optional)">{(id) => <input id={id} type="file" accept="application/pdf" onChange={(e) => setPdf(e.target.files?.[0])} />}</Field>
      </div>
      {f.examinedOn && f.validUntil && f.validUntil < f.examinedOn && <p className="field-error-text small" role="alert">“Valid until” must be after the examination date.</p>}
      <button type="submit" disabled={action.busy || !ready}>Import certificate</button>
      <ActionFeedback error={action.error} message={action.message} />
    </form>
  );
}

/** `#/surveillance/crew` – crew aeromedical certificates (E07-F04). CREW_LICENSING sees validity / expiry status only. */
export function CrewCertificates({ client, session, onSessionExpired }: ScreenProps) {
  const ame = can(session.roles, 'crewExam');
  const sections: [Section, string, boolean][] = [
    ['certificates', 'Certificates', true],
    ['without', 'Without valid medical', true],
    ['exam', 'AME examination', ame],
    ['import', 'External import', can(session.roles, 'crewImport')],
  ];
  const [section, setSection] = useState<Section>('certificates');
  const [report, setReport] = useState({ from: '', to: '' });
  return (
    <section>
      <PageTitle sub="Alerts go out at 90, 60 and 30 days; on expiry the roster status becomes unfit so nobody is rostered beyond expiry.">Crew medical certificates</PageTitle>
      <div className="mode-switch" role="group" aria-label="Section">
        {sections.filter(([, , show]) => show).map(([id, label]) => (
          <button key={id} type="button" className={`secondary small-button${section === id ? ' mode-active' : ''}`} aria-pressed={section === id} onClick={() => setSection(id)}>
            {label}
          </button>
        ))}
      </div>
      {section === 'certificates' && <CertificatesView client={client} onSessionExpired={onSessionExpired} />}
      {section === 'without' && <WithoutValidMedical client={client} onSessionExpired={onSessionExpired} />}
      {section === 'exam' && ame && <ExamForm client={client} onSessionExpired={onSessionExpired} />}
      {section === 'import' && <ImportForm client={client} onSessionExpired={onSessionExpired} />}
      {can(session.roles, 'crewAuthorityReport') && (
        <section className="tile" aria-labelledby="authority-report">
          <h2 id="authority-report">Authority report (CSV)</h2>
          <div className="inline-form">
            <Field label="From">{(id) => <input id={id} type="date" value={report.from} onChange={(e) => setReport({ ...report, from: e.target.value })} />}</Field>
            <Field label="To">{(id) => <input id={id} type="date" value={report.to} onChange={(e) => setReport({ ...report, to: e.target.value })} />}</Field>
            {report.from && report.to && report.to >= report.from && (
              <a className="button secondary" href={authorityReportUrl(report.from, report.to)} download={`crew-authority-${report.from}-${report.to}.csv`} rel="noopener">
                Download
              </a>
            )}
          </div>
        </section>
      )}
    </section>
  );
}
