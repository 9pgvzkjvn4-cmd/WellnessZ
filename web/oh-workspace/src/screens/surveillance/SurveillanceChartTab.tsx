import { surveillanceApi } from '../../api/surveillance';
import { formatDate, formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Loading } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { ChartTabProps } from '../chart/chartTabProps';

const TONE: Record<string, 'red' | 'amber' | 'green' | 'muted'> = { OVERDUE: 'red', DUE: 'amber', TITRE_NEEDED: 'amber', CURRENT: 'green', SCHEDULED: 'green', COMPLETE: 'green', EXEMPT: 'muted' };

/** Chart tab `#/employees/{id}/chart/surveillance` – programmes (enrolments), immunisations & titres, exposure history and cosmic dose. */
export function SurveillanceChartTab({ client, onSessionExpired, employeeId }: ChartTabProps) {
  const api = surveillanceApi(client);
  // The due list has no employee filter in the contract: ask for `employeeId` and filter client-side as well.
  const enrolments = useAsync(
    () => api.due({ employeeId }).then((rows) => rows.filter((r) => r.employeeId === employeeId)),
    onSessionExpired,
    [employeeId],
  );
  const imms = useAsync(() => api.employeeImmunisations(employeeId), onSessionExpired, [employeeId]);
  const exposure = useAsync(() => api.exposureHistory(employeeId), onSessionExpired, [employeeId]);

  return (
    <section aria-labelledby="surv-tab-title">
      <h2 id="surv-tab-title">Surveillance</h2>

      <h3>Programmes</h3>
      {enrolments.loading && <Loading />}
      {!!enrolments.error && <ErrorAlert error={enrolments.error} />}
      {enrolments.data && enrolments.data.length === 0 && <Empty>Not enrolled in any programme.</Empty>}
      {(enrolments.data ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Programme enrolments</caption>
            <thead>
              <tr>
                <th scope="col">Programme</th>
                <th scope="col">Reason</th>
                <th scope="col">Last done</th>
                <th scope="col">Next due</th>
                <th scope="col">Status</th>
                <th scope="col">Appointment</th>
              </tr>
            </thead>
            <tbody>
              {(enrolments.data ?? []).map((e) => (
                <tr key={e.id}>
                  <th scope="row">{e.programmeTitle}</th>
                  <td>{humanise(e.reason)}</td>
                  <td>{formatDay(e.lastCompletedOn)}</td>
                  <td>{formatDay(e.nextDueOn)}</td>
                  <td><ToneBadge tone={TONE[e.status] ?? 'muted'}>{humanise(e.status)}</ToneBadge></td>
                  <td className="small">{e.appointment ? `${formatDate(e.appointment.startsAt)} · ${e.appointment.siteCode}` : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3>Immunisations</h3>
      {imms.loading && <Loading />}
      {!!imms.error && <ErrorAlert error={imms.error} />}
      {imms.data && imms.data.length === 0 && <Empty>No immunisation requirements.</Empty>}
      {(imms.data ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Immunisations</caption>
            <thead>
              <tr>
                <th scope="col">Vaccine</th>
                <th scope="col">Status</th>
                <th scope="col">Last dose</th>
                <th scope="col">Next due</th>
                <th scope="col">Titre</th>
              </tr>
            </thead>
            <tbody>
              {(imms.data ?? []).map((i) => (
                <tr key={i.vaccineCode}>
                  <th scope="row">{i.vaccineName}</th>
                  <td><ToneBadge tone={TONE[i.status] ?? 'muted'}>{humanise(i.status)}</ToneBadge></td>
                  <td>{formatDay(i.lastDoseOn)}</td>
                  <td>{formatDay(i.nextDueOn)}</td>
                  <td className="small">{i.titre ? `${i.titre.value} · ${i.titre.protective ? 'protective' : 'not protective'} · ${formatDay(i.titre.testedOn)}` : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3>Exposure history</h3>
      {exposure.loading && <Loading />}
      {!!exposure.error && <ErrorAlert error={exposure.error} />}
      {exposure.data && exposure.data.exposures.length === 0 && <Empty>No exposure group history.</Empty>}
      {(exposure.data?.exposures ?? []).length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Exposure history</caption>
            <thead>
              <tr>
                <th scope="col">Exposure group</th>
                <th scope="col">Hazard</th>
                <th scope="col">From</th>
                <th scope="col">To</th>
                <th scope="col" className="num">Latest value</th>
              </tr>
            </thead>
            <tbody>
              {(exposure.data?.exposures ?? []).map((x) => (
                <tr key={`${x.segCode}-${x.from ?? ''}`}>
                  <th scope="row" className="mono">{x.segCode}</th>
                  <td>{humanise(x.hazardCode)}</td>
                  <td>{formatDay(x.from)}</td>
                  <td>{x.to ? formatDay(x.to) : 'current'}</td>
                  <td className="num">{x.latestValue != null ? `${x.latestValue} ${x.unit ?? ''}` : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(exposure.data?.cosmicDose ?? []).length > 0 && (
        <>
          <h3>Cosmic radiation dose</h3>
          <ul className="plain-list">
            {[...(exposure.data?.cosmicDose ?? [])].sort((a, b) => b.year - a.year).map((c) => (
              <li key={c.year}>
                {c.year}: <strong>{c.doseMsv.toFixed(2)} mSv</strong> {c.aboveThreshold && <ToneBadge tone="red">Above alert threshold</ToneBadge>}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
