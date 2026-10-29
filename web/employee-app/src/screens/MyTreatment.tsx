import { useState } from 'react';
import {
  canRespond,
  fetchMyTreatmentPlans,
  MAX_REASON,
  respondToFollowUp,
  type ExternalAppointment,
  type FollowUpResponse,
  type MyTreatmentPlan,
  type PlanFollowUp,
  type PlanIntervention,
} from '../api/care';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ConfirmPanel, EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { careErrorText, careStrings, type CareStrings } from '../i18n/care';
import { labelOf } from '../i18n/portal';
import type { PortalScreenProps } from './types';

function period(i: PlanIntervention, lang: string): string | undefined {
  if (!i.startDate && !i.endDate) return undefined;
  return `${formatDay(i.startDate, lang)} – ${i.endDate ? formatDay(i.endDate, lang) : '…'}`;
}

/** Interventions in plain language: type, description, how often, when, with whom. Hook-free. */
export function InterventionList({ t, lang, items }: { t: CareStrings; lang: string; items: readonly PlanIntervention[] }) {
  if (items.length === 0) return <p className="muted">{t.trInterventionsEmpty}</p>;
  return (
    <ul className="card-list">
      {items.map((i, n) => {
        const when = period(i, lang);
        return (
          <li key={n} className="card">
            <strong>{labelOf(t.interventionTypes, i.type)}</strong>
            {i.description && <p>{i.description}</p>}
            <dl className="case-facts">
              {i.frequency && (
                <>
                  <dt>{t.trHowOften}</dt>
                  <dd>{i.frequency}</dd>
                </>
              )}
              {when && (
                <>
                  <dt>{t.trPeriod}</dt>
                  <dd>{when}</dd>
                </>
              )}
              {i.provider && (
                <>
                  <dt>{t.trWithProvider}</dt>
                  <dd>
                    <bdi>{i.provider}</bdi>
                  </dd>
                </>
              )}
            </dl>
          </li>
        );
      })}
    </ul>
  );
}

export interface FollowUpCardProps {
  t: CareStrings;
  lang: string;
  plan: MyTreatmentPlan;
  f: PlanFollowUp;
  busy: boolean;
  declining: boolean;
  reason: string;
  onConfirm: (plan: MyTreatmentPlan, f: PlanFollowUp) => void;
  onDecline: (plan: MyTreatmentPlan, f: PlanFollowUp) => void;
  onReason: (v: string) => void;
  onSubmitDecline: () => void;
  onAbortDecline: () => void;
}

/** One check-in: type, due date, booked appointment, status; confirm / decline while still open. Hook-free. */
export function FollowUpCard(p: FollowUpCardProps) {
  const { t, lang, f, plan } = p;
  const hid = `fu-${f.id}`;
  const open = canRespond(f, plan);
  return (
    <article className="card" aria-labelledby={hid}>
      <div className="card-row">
        <h4 id={hid} className="card-title">
          {labelOf(t.followUpTypes, f.type)}
        </h4>
        <StatusBadge status={`fu-${f.status}`} label={labelOf(t.followUpStatus, f.status)} />
      </div>
      <dl className="case-facts">
        {f.dueDate && (
          <>
            <dt>{t.trDue}</dt>
            <dd>{formatDay(f.dueDate, lang)}</dd>
          </>
        )}
        {f.appointment && (
          <>
            <dt>{t.trBooked}</dt>
            <dd>
              {formatDate(f.appointment.startsAt, lang)}
              {f.appointment.siteCode && <> · {f.appointment.siteCode}</>}
            </dd>
          </>
        )}
      </dl>
      {f.response === 'CONFIRM' && <p className="muted small">{t.trYouConfirmed}</p>}
      {f.response === 'DECLINE' && <p className="muted small">{t.trYouDeclined}</p>}
      {open &&
        (p.declining ? (
          <ConfirmPanel
            id={`${hid}-decline`}
            title={t.trDeclineTitle}
            confirmLabel={t.trDeclineConfirm}
            cancelLabel={t.back}
            busy={p.busy}
            busyLabel={t.sending}
            onConfirm={p.onSubmitDecline}
            onCancel={p.onAbortDecline}
          >
            <label htmlFor={`${hid}-reason`}>{t.trDeclineReason}</label>
            <textarea id={`${hid}-reason`} rows={3} maxLength={MAX_REASON} value={p.reason} onChange={(e) => p.onReason(e.target.value)} aria-describedby={`${hid}-reason-hint`} />
            <p id={`${hid}-reason-hint`} className="muted small">
              {t.trDeclineReasonHint}
            </p>
          </ConfirmPanel>
        ) : (
          <>
            <p className="muted small">{t.trRespondHint}</p>
            <div className="actions">
              <button type="button" disabled={p.busy} onClick={() => p.onConfirm(plan, f)}>
                {t.trConfirm}
              </button>
              <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onDecline(plan, f)}>
                {t.trDecline}
              </button>
            </div>
          </>
        ))}
    </article>
  );
}

/** Appointments booked by partner providers after an OH referral. Hook-free. */
export function ExternalAppointmentList({ t, lang, items }: { t: CareStrings; lang: string; items: readonly ExternalAppointment[] }) {
  if (items.length === 0) return <p className="muted">{t.trExternalEmpty}</p>;
  return (
    <ul className="card-list">
      {items.map((a) => (
        <li key={a.key} className="card">
          <div className="card-row">
            <strong>
              <bdi>{a.provider ?? a.service ?? '–'}</bdi>
            </strong>
            {a.status && <StatusBadge status={`ref-${a.status}`} label={labelOf(t.referralStatus, a.status)} />}
          </div>
          <dl className="case-facts">
            <dt>{t.trWhen}</dt>
            <dd>{formatDate(a.appointmentAt, lang)}</dd>
            {a.service && a.provider && (
              <>
                <dt>{t.trService}</dt>
                <dd>{a.service}</dd>
              </>
            )}
            {a.location && (
              <>
                <dt>{t.trWhere}</dt>
                <dd>{a.location}</dd>
              </>
            )}
            {a.ref && (
              <>
                <dt>{t.trRef}</dt>
                <dd className="mono">{a.ref}</dd>
              </>
            )}
          </dl>
          {a.instructions && (
            <div className="ack-box">
              <strong>{t.trInstructions}</strong>
              <p>{a.instructions}</p>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

export interface PlanViewProps extends Omit<FollowUpCardProps, 'plan' | 'f' | 'declining'> {
  plan: MyTreatmentPlan;
  decliningId?: string;
}

/** A whole plan: goals, interventions, check-ins, external appointments. Hook-free. */
export function PlanView(p: PlanViewProps) {
  const { t, lang, plan } = p;
  const hid = `plan-${plan.id}`;
  return (
    <article className="card plan" aria-labelledby={hid}>
      <div className="card-row">
        <h2 id={hid} className="card-title">
          {labelOf(t.planStatus, plan.status)}
        </h2>
        {plan.caseRef && <span className="muted small mono">{t.trPlanFor(plan.caseRef)}</span>}
      </div>
      {plan.expectedRecoveryDate && (
        <p>
          {t.trExpectedRecovery} <strong>{formatDay(plan.expectedRecoveryDate, lang)}</strong>
        </p>
      )}
      <h3>{t.trGoals}</h3>
      {plan.goals.length === 0 ? (
        <p className="muted">{t.trGoalsEmpty}</p>
      ) : (
        <ul>
          {plan.goals.map((g, i) => (
            <li key={i}>{g}</li>
          ))}
        </ul>
      )}
      <h3>{t.trInterventions}</h3>
      <InterventionList t={t} lang={lang} items={plan.interventions} />
      <h3>{t.trFollowUps}</h3>
      {plan.followUps.length === 0 ? (
        <p className="muted">{t.trFollowUpsEmpty}</p>
      ) : (
        <ul className="card-list">
          {plan.followUps.map((f) => (
            <li key={f.id}>
              <FollowUpCard {...p} plan={plan} f={f} declining={p.decliningId === f.id} />
            </li>
          ))}
        </ul>
      )}
      <h3>{t.trExternal}</h3>
      <p className="muted small">{t.trExternalIntro}</p>
      <ExternalAppointmentList t={t} lang={lang} items={plan.externalAppointments} />
    </article>
  );
}

/** `#/treatment` – my treatment plan in plain language; confirm / decline check-ins (E04 §3.1). */
export function MyTreatment({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = careStrings(lang);
  const plans = useAsync(() => fetchMyTreatmentPlans(client), onSessionExpired);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [declining, setDeclining] = useState<{ planId: string; followUpId: string }>();
  const [reason, setReason] = useState('');

  async function respond(planId: string, followUpId: string, response: FollowUpResponse, why?: string) {
    setBusy(true);
    setMessage(undefined);
    try {
      await respondToFollowUp(client, planId, followUpId, response, why);
      setDeclining(undefined);
      setReason('');
      setMessage({ kind: 'success', text: response === 'CONFIRM' ? t.trConfirmedOk : t.trDeclinedOk });
      plans.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: careErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const list = plans.data ?? [];
  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.trTitle}</PageTitle>
      <p className="lead">{t.trIntro}</p>
      <p className="muted small">{t.trPrivacy}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {plans.loading && <Loading />}
      {!!plans.error && <Alert kind="error">{careErrorText(lang, plans.error)}</Alert>}
      {plans.data && list.length === 0 && <EmptyState title={t.trEmpty} hint={t.trEmptyHint} />}
      {list.map((plan) => (
        <PlanView
          key={plan.id}
          t={t}
          lang={lang}
          plan={plan}
          busy={busy}
          decliningId={declining?.planId === plan.id ? declining.followUpId : undefined}
          reason={reason}
          onConfirm={(pl, f) => void respond(pl.id, f.id, 'CONFIRM')}
          onDecline={(pl, f) => {
            setReason('');
            setMessage(undefined);
            setDeclining({ planId: pl.id, followUpId: f.id });
          }}
          onReason={setReason}
          onSubmitDecline={() => declining && void respond(declining.planId, declining.followUpId, 'DECLINE', reason)}
          onAbortDecline={() => setDeclining(undefined)}
        />
      ))}
    </PortalSection>
  );
}
