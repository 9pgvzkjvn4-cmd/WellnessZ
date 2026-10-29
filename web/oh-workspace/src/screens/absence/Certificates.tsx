import { useState, type FormEvent } from 'react';
import { absenceApi, CALL_OUTCOMES, CERTIFICATE_STATUSES, VALIDATION_OUTCOMES, type Certificate, type CertificateChecklist } from '../../api/absence';
import { documentContentUrl } from '../../api/platform';
import type { EmployeeRef } from '../../api/types';
import { can } from '../../auth/roles';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { formatBytes, formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Dl, Empty, ErrorAlert, Field, Loading, PageTitle, SelectOptions, StatusBadge } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import type { ScreenProps } from '../types';

const CHECKS: [keyof CertificateChecklist, string][] = [
  ['issuerIdentified', 'Issuer identified'],
  ['registrationValid', 'Issuer registration valid'],
  ['datesConsistent', 'Dates consistent'],
  ['signaturePresent', 'Signature / stamp present'],
  ['coversAbsence', 'Covers the absence period'],
];
const EMPTY_CHECKS: CertificateChecklist = { issuerIdentified: false, registrationValid: false, datesConsistent: false, signaturePresent: false, coversAbsence: false };
const MAX_BYTES = 10 * 1024 * 1024;

function ScanCertificate({ client, onSessionExpired, onDone }: Pick<ScreenProps, 'client' | 'onSessionExpired'> & { onDone: () => void }) {
  const [emp, setEmp] = useState<EmployeeRef>();
  const [file, setFile] = useState<File>();
  const [form, setForm] = useState({ fromDate: '', toDate: '', episodeId: '' });
  const action = useAction(onSessionExpired);
  const tooBig = !!file && file.size > MAX_BYTES;
  const badType = !!file && !['application/pdf', 'image/jpeg', 'image/png'].includes(file.type);
  const datesBad = !!form.fromDate && !!form.toDate && form.toDate < form.fromDate;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!emp || !file || tooBig || badType || !form.fromDate || !form.toDate || datesBad) return;
    const fd = new FormData();
    fd.append('file', file);
    fd.append('employeeId', emp.employeeId);
    fd.append('fromDate', form.fromDate);
    fd.append('toDate', form.toDate);
    if (form.episodeId.trim()) fd.append('episodeId', form.episodeId.trim());
    const ok = await action.run(async () => {
      const c = await absenceApi(client).scanCertificate(fd);
      return `Certificate received for ${emp.displayName} (${humanise(c.status)}).`;
    });
    if (ok) {
      setFile(undefined);
      setForm({ fromDate: '', toDate: '', episodeId: '' });
      setEmp(undefined);
      onDone();
    }
  }

  return (
    <section className="tile" aria-labelledby="scan-cert">
      <h2 id="scan-cert">Scan a paper certificate</h2>
      {!emp ? (
        <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmp} label="Employee (name or employee ID)" />
      ) : (
        <form className="form compact" onSubmit={submit}>
          <p>
            Employee: <strong>{emp.displayName}</strong> ({emp.employeeId}){' '}
            <button type="button" className="link-button" onClick={() => setEmp(undefined)}>Change</button>
          </p>
          <Field label="Certificate file (PDF, JPEG or PNG, ≤ 10 MB)" required>
            {(id) => <input id={id} type="file" accept="application/pdf,image/jpeg,image/png" capture="environment" required onChange={(e) => setFile(e.target.files?.[0])} />}
          </Field>
          {file && <p className="small">{file.name} · {formatBytes(file.size)}</p>}
          {tooBig && <p className="field-error-text small" role="alert">The file is larger than 10 MB.</p>}
          {badType && <p className="field-error-text small" role="alert">Only PDF, JPEG or PNG files are accepted.</p>}
          <div className="inline-form">
            <Field label="From" required>{(id) => <input id={id} type="date" required value={form.fromDate} onChange={(e) => setForm({ ...form, fromDate: e.target.value })} />}</Field>
            <Field label="To" required>{(id) => <input id={id} type="date" required min={form.fromDate || undefined} value={form.toDate} onChange={(e) => setForm({ ...form, toDate: e.target.value })} />}</Field>
            <Field label="Episode id (optional)">{(id) => <input id={id} maxLength={60} value={form.episodeId} onChange={(e) => setForm({ ...form, episodeId: e.target.value })} />}</Field>
          </div>
          {datesBad && <p className="field-error-text small" role="alert">“To” must be on or after “From”.</p>}
          <button type="submit" disabled={action.busy || !file || tooBig || badType || !form.fromDate || !form.toDate || datesBad}>Upload certificate</button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

function CertificateDetail({ client, session, onSessionExpired, cert, onChanged }: ScreenProps & { cert: Certificate; onChanged: () => void }) {
  const api = absenceApi(client);
  const oh = can(session.roles, 'certificateVerify');
  const action = useAction(onSessionExpired);
  const [checks, setChecks] = useState<CertificateChecklist>({ ...EMPTY_CHECKS, ...(cert.checklist ?? {}) });
  const [outcome, setOutcome] = useState<string>('VALID');
  const [note, setNote] = useState('');
  const [call, setCall] = useState({ calledParty: '', outcome: 'CONFIRMED', note: '' });
  const [verify, setVerify] = useState<{ outcome: 'VERIFIED_GENUINE' | 'VERIFIED_FRAUDULENT'; escalateToHr: boolean }>({ outcome: 'VERIFIED_GENUINE', escalateToHr: false });
  const allChecked = CHECKS.every(([k]) => checks[k]);

  async function validate(e: FormEvent) {
    e.preventDefault();
    if (outcome === 'VALID' && !allChecked) return;
    const ok = await action.run(async () => {
      await api.validateCertificate(cert.id, { checklist: checks, outcome, ...(note.trim() ? { note: note.trim() } : {}) });
      return `Certificate marked ${humanise(outcome).toLowerCase()}.`;
    });
    if (ok) onChanged();
  }

  async function logCall(e: FormEvent) {
    e.preventDefault();
    if (!call.calledParty.trim()) return;
    const ok = await action.run(async () => {
      await api.logCall(cert.id, { calledParty: call.calledParty.trim(), outcome: call.outcome, ...(call.note.trim() ? { note: call.note.trim() } : {}) });
      return 'Verification call logged.';
    });
    if (ok) {
      setCall({ calledParty: '', outcome: 'CONFIRMED', note: '' });
      onChanged();
    }
  }

  async function doVerify(e: FormEvent) {
    e.preventDefault();
    const ok = await action.run(async () => {
      await api.verifyCertificate(cert.id, verify);
      return `Certificate ${verify.outcome === 'VERIFIED_GENUINE' ? 'verified as genuine' : 'recorded as fraudulent'}${verify.escalateToHr ? ' and escalated to HR (status only)' : ''}.`;
    });
    if (ok) onChanged();
  }

  const ex = cert.extracted;
  return (
    <section className="side-panel" aria-labelledby="cert-detail">
      <h2 id="cert-detail">Certificate – {cert.displayName}</h2>
      <Dl
        items={[
          ['Status', <StatusBadge status={cert.status} />],
          ['Source', humanise(cert.source)],
          ['Period', `${formatDay(cert.fromDate)} – ${formatDay(cert.toDate)}`],
          ['Issuer', cert.issuerName ? `${cert.issuerName}${cert.issuerRegistration ? ` (${cert.issuerRegistration})` : ''}` : undefined],
          ['Issued', cert.issueDate ? formatDay(cert.issueDate) : undefined],
          ['Received', formatDate(cert.receivedAt)],
          ['Verified', cert.verifiedAt ? `${formatDate(cert.verifiedAt)} · ${cert.verifiedBy ?? ''}` : undefined],
        ]}
      />
      {cert.duplicateOfId && <p><ToneBadge tone="red">Possible duplicate</ToneBadge> <span className="small">of certificate {cert.duplicateOfId}</span></p>}
      {cert.alterationFlags.length > 0 && (
        <ul className="plain-list" aria-label="Alteration flags">
          {cert.alterationFlags.map((f) => (
            <li key={f}><ToneBadge tone="amber">{humanise(f)}</ToneBadge></li>
          ))}
        </ul>
      )}
      {oh && (
        <>
          {cert.documentId && (
            <p>
              <a className="button secondary small-button" href={documentContentUrl(cert.documentId)} target="_blank" rel="noopener">View certificate</a>
            </p>
          )}
          {ex && (
            <details>
              <summary className="small">Extracted by OCR</summary>
              <Dl items={[['Issuer', ex.issuerName], ['Registration', ex.registration], ['Issue date', ex.issueDate ? formatDay(ex.issueDate) : undefined], ['From', ex.fromDate ? formatDay(ex.fromDate) : undefined], ['To', ex.toDate ? formatDay(ex.toDate) : undefined]]} />
            </details>
          )}
        </>
      )}

      <h3>Validation checklist</h3>
      <form className="form compact" onSubmit={validate}>
        <fieldset>
          <legend className="visually-hidden">Checklist</legend>
          {CHECKS.map(([k, label]) => (
            <label key={k} className="inline-row">
              <input type="checkbox" checked={checks[k]} onChange={(e) => setChecks({ ...checks, [k]: e.target.checked })} /> {label}
            </label>
          ))}
        </fieldset>
        <Field label="Outcome">
          {(id) => (
            <select id={id} value={outcome} onChange={(e) => setOutcome(e.target.value)}>
              <SelectOptions values={VALIDATION_OUTCOMES} />
            </select>
          )}
        </Field>
        {outcome === 'VALID' && !allChecked && <p className="small text-amber">All checks must be ticked to mark the certificate valid.</p>}
        <Field label="Validation note">{(id) => <textarea id={id} rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
        <button type="submit" disabled={action.busy || (outcome === 'VALID' && !allChecked)}>Save validation</button>
      </form>

      <h3>Verification calls</h3>
      {cert.calls.length === 0 ? (
        <p className="muted small">No calls logged.</p>
      ) : (
        <ul className="plain-list history-list">
          {cert.calls.map((c, i) => (
            <li key={c.id ?? i}>
              {c.at && <strong>{formatDate(c.at)} </strong>}
              {c.calledParty} · <StatusBadge status={c.outcome} /> {c.by && <span className="small muted">by {c.by}</span>}
              {c.note && <div className="small">{c.note}</div>}
            </li>
          ))}
        </ul>
      )}
      <form className="form compact" onSubmit={logCall} aria-label="Log verification call">
        <Field label="Called party" required>{(id) => <input id={id} maxLength={200} required value={call.calledParty} onChange={(e) => setCall({ ...call, calledParty: e.target.value })} />}</Field>
        <Field label="Call outcome">
          {(id) => (
            <select id={id} value={call.outcome} onChange={(e) => setCall({ ...call, outcome: e.target.value })}>
              <SelectOptions values={CALL_OUTCOMES} />
            </select>
          )}
        </Field>
        <Field label="Call note">{(id) => <input id={id} maxLength={500} value={call.note} onChange={(e) => setCall({ ...call, note: e.target.value })} />}</Field>
        <button type="submit" className="secondary" disabled={action.busy || !call.calledParty.trim()}>Log call</button>
      </form>

      {oh && (
        <>
          <h3>Verify</h3>
          <form className="form compact" onSubmit={doVerify}>
            <fieldset>
              <legend className="visually-hidden">Verification outcome</legend>
              {(['VERIFIED_GENUINE', 'VERIFIED_FRAUDULENT'] as const).map((o) => (
                <label key={o} className="inline-row">
                  <input type="radio" name="verify-outcome" checked={verify.outcome === o} onChange={() => setVerify({ ...verify, outcome: o })} /> {humanise(o)}
                </label>
              ))}
            </fieldset>
            <label className="inline-row">
              <input type="checkbox" checked={verify.escalateToHr} onChange={(e) => setVerify({ ...verify, escalateToHr: e.target.checked })} /> Escalate to HR (status only – no certificate content)
            </label>
            <button type="submit" disabled={action.busy}>Record verification</button>
          </form>
        </>
      )}
      <ActionFeedback error={action.error} message={action.message} />
    </section>
  );
}

/** `#/absence/certificates` – medical certificates: list, validation checklist, call log, verify / escalate (E06-F03). */
export function AbsenceCertificates(props: ScreenProps) {
  const { client, session, onSessionExpired } = props;
  const [status, setStatus] = useState('RECEIVED');
  const list = useAsync(() => absenceApi(client).certificates({ status }), onSessionExpired, [status]);
  const [selected, setSelected] = useState<string>();
  const rows = list.data ?? [];
  const current = rows.find((c) => c.id === selected);
  const oh = can(session.roles, 'certificateVerify');

  return (
    <section>
      <PageTitle sub={oh ? 'Validate employee uploads, clinic scans and partner certificates; verify suspicious ones by phone and escalate fraud to HR (status only).' : 'Scan paper certificates and complete the validation checklist. Certificate content is visible to OH only.'}>
        Medical certificates
      </PageTitle>
      <ScanCertificate client={client} onSessionExpired={onSessionExpired} onDone={list.reload} />
      <div className="filters inline-form">
        <Field label="Status">
          {(id) => (
            <select id={id} value={status} onChange={(e) => { setStatus(e.target.value); setSelected(undefined); }}>
              <SelectOptions values={CERTIFICATE_STATUSES} empty="All" />
            </select>
          )}
        </Field>
      </div>
      {list.loading && <Loading />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No certificates.</Empty>}
      <div className={current ? 'queue-layout' : undefined}>
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <caption>{rows.length} certificate{rows.length === 1 ? '' : 's'}</caption>
              <thead>
                <tr>
                  <th scope="col">Employee</th>
                  <th scope="col">Period</th>
                  <th scope="col">Source</th>
                  <th scope="col">Received</th>
                  <th scope="col">Flags</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.id} className={c.id === selected ? 'row-selected' : undefined}>
                    <th scope="row">
                      <button type="button" className="link-button" aria-pressed={c.id === selected} onClick={() => setSelected(c.id === selected ? undefined : c.id)}>
                        {c.displayName}
                      </button>{' '}
                      <span className="small muted">{c.employeeId}</span>
                    </th>
                    <td className="small">{formatDay(c.fromDate)} – {formatDay(c.toDate)}</td>
                    <td>{humanise(c.source)}</td>
                    <td className="small">{formatDate(c.receivedAt)}</td>
                    <td>
                      {c.duplicateOfId && <ToneBadge tone="red">Duplicate</ToneBadge>} {c.alterationFlags.length > 0 && <ToneBadge tone="amber">{c.alterationFlags.length} alteration flag{c.alterationFlags.length === 1 ? '' : 's'}</ToneBadge>}
                    </td>
                    <td><StatusBadge status={c.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {current && <CertificateDetail key={current.id} {...props} cert={current} onChanged={list.reload} />}
      </div>
    </section>
  );
}
