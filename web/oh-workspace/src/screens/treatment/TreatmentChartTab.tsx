import { recordApi } from '../../api/record';
import { treatmentApi } from '../../api/treatment';
import { formatDay, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Empty, ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import { describeImprovement, normaliseOutcomes, outcomeImprovement, varianceTone } from '../../domain/treatment';
import type { ChartTabProps } from '../chart/chartTabProps';
import type { ScreenProps } from '../types';
import { ReferralStatus } from './Referrals';
import { caseLink } from './util';

function CaseTreatment({ props, caseId, caseRef }: { props: ScreenProps; caseId: string; caseRef: string }) {
  const api = treatmentApi(props.client);
  const data = useAsync(
    () => Promise.all([api.plans(caseId), api.caseReferrals(caseId), api.outcomes(caseId)]).then(([plans, referrals, outcomes]) => ({ plans, referrals, outcomes: normaliseOutcomes(outcomes) })),
    props.onSessionExpired,
    [caseId],
  );
  const d = data.data;
  const plan = d?.plans.find((p) => p.status === 'ACTIVE') ?? d?.plans.find((p) => p.status === 'DRAFT');
  const openReferrals = (d?.referrals ?? []).filter((r) => !['CLOSED', 'CANCELLED', 'DECLINED'].includes(r.status));
  const improvement = d ? describeImprovement(outcomeImprovement(d.outcomes)) : [];
  const v = varianceTone(plan?.variance);

  return (
    <section className="tile" aria-labelledby={`ct-${caseId}`}>
      <h3 id={`ct-${caseId}`}><a href={caseLink(caseId, 'treatment')}>{caseRef}</a></h3>
      {data.loading && !d && <Loading />}
      {!!data.error && <ErrorAlert error={data.error} />}
      {d && (
        <>
          <h4>Active plan</h4>
          {plan ? (
            <p>
              v{plan.version} <StatusBadge status={plan.status} /> <ToneBadge tone={v.tone}>{v.label}</ToneBadge>
              <br />
              <span className="small">{plan.goals.join(' · ')}</span>
              <br />
              <span className="small muted">
                Next follow-up: {formatDay(plan.followUps.filter((f) => ['PLANNED', 'BOOKED', 'CONFIRMED'].includes(f.status)).map((f) => f.dueDate).sort()[0])}
              </span>
            </p>
          ) : (
            <p className="muted small">No active plan.</p>
          )}
          <h4>Open referrals</h4>
          {openReferrals.length === 0 ? (
            <p className="muted small">None.</p>
          ) : (
            <ul className="plain-list">
              {openReferrals.map((r) => (
                <li key={r.id}>
                  <a href={`#/referrals/${encodeURIComponent(r.id)}`}>{r.ref}</a> {r.serviceName} – {r.providerName} <ReferralStatus r={r} />
                </li>
              ))}
            </ul>
          )}
          <h4>Outcomes</h4>
          {d.outcomes.length === 0 ? (
            <p className="muted small">None recorded.</p>
          ) : (
            <p className="small">
              {d.outcomes.map((o) => `${humanise(o.milestone)}: pain ${o.painScore}`).join(' → ')}
              {improvement.length > 0 && <><br />{improvement.join(' · ')}</>}
            </p>
          )}
        </>
      )}
    </section>
  );
}

/** `#/employees/{id}/chart/treatment` – active plans, open referrals and outcomes per open case (E04 ChartContributor). */
export function TreatmentChartTab(props: ChartTabProps) {
  const chart = useAsync(() => recordApi(props.client).chart(props.employeeId), props.onSessionExpired, [props.employeeId]);
  const cases = chart.data?.openCases ?? [];
  return (
    <div>
      <h2 className="visually-hidden">Treatment</h2>
      {chart.loading && !chart.data && <Loading />}
      {!!chart.error && <ErrorAlert error={chart.error} />}
      {chart.data && cases.length === 0 && <Empty>No open cases – treatment plans, referrals and outcomes are kept on the case.</Empty>}
      {cases.map((c) => <CaseTreatment key={c.id} props={props} caseId={c.id} caseRef={c.caseRef} />)}
    </div>
  );
}
