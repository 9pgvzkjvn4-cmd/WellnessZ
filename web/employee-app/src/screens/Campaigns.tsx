import { useState } from 'react';
import {
  canRespondToCampaign,
  DEVICE_TYPES,
  enrolWearable,
  fetchMyCampaigns,
  fetchVaccinationCampaigns,
  respondToVaccination,
  WEARABLE_METRICS,
  withdrawWearable,
  type CampaignInvitation,
  type DeviceType,
  type ScreeningBooking,
  type VaccinationCampaign,
  type VaccinationResponse,
  type WearableEnrolment,
} from '../api/prevention';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ConfirmPanel, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { Fact } from '../components/wellbeing';
import { labelOf } from '../i18n/portal';
import { wellbeingErrorText, wellbeingStrings, type WellbeingStrings } from '../i18n/wellbeing';
import type { PortalScreenProps } from './types';

const period = (lang: string, from?: string, to?: string) => (from || to ? `${formatDay(from, lang)} – ${to ? formatDay(to, lang) : '…'}` : undefined);

export interface WearablePanelProps {
  t: WellbeingStrings;
  lang: string;
  campaign: CampaignInvitation;
  enrolment?: WearableEnrolment;
  device: DeviceType;
  consent: boolean;
  consentError: boolean;
  withdrawing: boolean;
  busy: boolean;
  onDevice: (d: DeviceType) => void;
  onConsent: (v: boolean) => void;
  onEnrol: (c: CampaignInvitation) => void;
  onWithdraw: (e: WearableEnrolment) => void;
  onConfirmWithdraw: () => void;
  onCancelWithdraw: () => void;
}

/** Explicit WEARABLE_DATA consent with the data-minimisation explanation; withdrawal deletes the data. Hook-free. */
export function WearablePanel(p: WearablePanelProps) {
  const { t, campaign } = p;
  const id = `wear-${campaign.campaignId}`;
  return (
    <div className="consent-box" aria-labelledby={`${id}-title`}>
      <h4 id={`${id}-title`}>{t.caWearable}</h4>
      <p>{t.caWearableIntro}</p>
      <p className="small">{t.caMinimisation}</p>
      <ul className="small">
        {WEARABLE_METRICS.map((m) => (
          <li key={m}>{t.metrics[m]}</li>
        ))}
      </ul>
      {p.enrolment ? (
        <>
          <p>
            <StatusBadge status="granted" label={t.caEnrolled(labelOf(t.devices, p.enrolment.deviceType ?? 'PHONE'))} />
            {p.enrolment.enrolledAt && <span className="muted small"> · {formatDate(p.enrolment.enrolledAt, p.lang)}</span>}
          </p>
          {p.withdrawing ? (
            <ConfirmPanel id={`${id}-withdraw`} title={t.caWithdrawTitle} confirmLabel={t.caWithdraw} cancelLabel={t.back} busy={p.busy} busyLabel={t.sending} danger onConfirm={p.onConfirmWithdraw} onCancel={p.onCancelWithdraw}>
              <p>{t.caWithdrawBody}</p>
            </ConfirmPanel>
          ) : (
            <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onWithdraw(p.enrolment!)}>
              {t.caWithdraw}
            </button>
          )}
        </>
      ) : (
        <>
          <div className="field">
            <label htmlFor={`${id}-device`}>{t.caDevice}</label>
            <select id={`${id}-device`} value={p.device} onChange={(e) => p.onDevice(e.target.value as DeviceType)}>
              {DEVICE_TYPES.map((d) => (
                <option key={d} value={d}>
                  {t.devices[d]}
                </option>
              ))}
            </select>
          </div>
          <label className="choice">
            <input type="checkbox" checked={p.consent} onChange={(e) => p.onConsent(e.target.checked)} aria-invalid={p.consentError} /> {t.caConsent}
          </label>
          {p.consentError && <p className="field-error">{t.caConsentNeeded}</p>}
          <button type="button" disabled={p.busy} onClick={() => p.onEnrol(campaign)}>
            {p.busy ? t.sending : t.caEnrol}
          </button>
        </>
      )}
    </div>
  );
}

export function ScreeningList({ t, lang, items }: { t: WellbeingStrings; lang: string; items: readonly ScreeningBooking[] }) {
  if (items.length === 0) return <p className="muted">{t.caScreeningsEmpty}</p>;
  return (
    <ul className="card-list">
      {items.map((s) => (
        <li key={s.id} className="card">
          <strong>{s.type ? labelOf(t.screeningTypes, s.type) : '–'}</strong>
          <dl className="case-facts">
            <Fact label={t.caWhen}>{s.startsAt ? formatDate(s.startsAt, lang) : s.date && formatDay(s.date, lang)}</Fact>
            <Fact label={t.caWhere}>{s.siteCode && <bdi>{s.siteCode}</bdi>}</Fact>
          </dl>
        </li>
      ))}
    </ul>
  );
}

export interface VaccinationCardProps {
  t: WellbeingStrings;
  lang: string;
  campaign: VaccinationCampaign;
  now?: Date;
  busy: boolean;
  onRespond: (c: VaccinationCampaign, r: VaccinationResponse) => void;
}

export function VaccinationCard(p: VaccinationCardProps) {
  const { t, campaign: c } = p;
  const open = canRespondToCampaign(c, p.now);
  return (
    <article className="card" aria-label={c.name}>
      <strong>{c.name}</strong>
      <dl className="case-facts">
        <Fact label={t.caWhen}>{period(p.lang, c.startDate, c.endDate)}</Fact>
        <Fact label={t.caWhere}>{c.siteCodes.length ? c.siteCodes.join(', ') : undefined}</Fact>
      </dl>
      {c.response === 'ACCEPT' && <p className="muted">{t.caAccepted}</p>}
      {c.response === 'DECLINE' && <p className="muted">{t.caDeclined}</p>}
      {!c.response && !open && <p className="muted">{t.caClosed}</p>}
      {open && (
        <div className="actions">
          <button type="button" disabled={p.busy} onClick={() => p.onRespond(c, 'ACCEPT')}>
            {t.caAccept}
          </button>
          <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onRespond(c, 'DECLINE')}>
            {t.caDecline}
          </button>
        </div>
      )}
    </article>
  );
}

/** `#/campaigns` – invitations, vaccination accept / decline, screening bookings, wearable enrolment (E11 §5.4, E12 §6). */
export function Campaigns({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = wellbeingStrings(lang);
  const mine = useAsync(() => fetchMyCampaigns(client), onSessionExpired);
  const vacc = useAsync(() => fetchVaccinationCampaigns(client), onSessionExpired);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [devices, setDevices] = useState<Record<string, DeviceType>>({});
  const [consents, setConsents] = useState<Record<string, boolean>>({});
  const [consentError, setConsentError] = useState<string>();
  const [withdrawing, setWithdrawing] = useState<string>();

  async function run(fn: () => Promise<void>, ok: string) {
    setBusy(true);
    setMessage(undefined);
    try {
      await fn();
      setMessage({ kind: 'success', text: ok });
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: wellbeingErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const enrol = (c: CampaignInvitation) => {
    if (!consents[c.campaignId]) {
      setConsentError(c.campaignId);
      return;
    }
    setConsentError(undefined);
    void run(async () => {
      await enrolWearable(client, c.campaignId, devices[c.campaignId] ?? 'PHONE', true);
      setConsents((m) => ({ ...m, [c.campaignId]: false }));
      mine.reload();
    }, t.caEnrolOk);
  };

  const data = mine.data;
  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.caTitle}</PageTitle>
      <p className="lead">{t.caIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>

      <h2>{t.caVaccination}</h2>
      {vacc.loading && <Loading />}
      {!!vacc.error && <Alert kind="error">{wellbeingErrorText(lang, vacc.error)}</Alert>}
      {vacc.data && vacc.data.length === 0 && <p className="muted">{t.caVaccEmpty}</p>}
      {vacc.data && vacc.data.length > 0 && (
        <ul className="card-list">
          {vacc.data.map((c) => (
            <li key={c.id}>
              <VaccinationCard
                t={t}
                lang={lang}
                campaign={c}
                busy={busy}
                onRespond={(camp, r) =>
                  void run(async () => {
                    await respondToVaccination(client, camp.id, r);
                    vacc.reload();
                  }, r === 'ACCEPT' ? t.caAcceptOk : t.caDeclineOk)
                }
              />
            </li>
          ))}
        </ul>
      )}

      {mine.loading && <Loading />}
      {!!mine.error && <Alert kind="error">{wellbeingErrorText(lang, mine.error)}</Alert>}
      {data && (
        <>
          <h2>{t.caInvitations}</h2>
          {data.invitations.length === 0 && <p className="muted">{t.caInvitationsEmpty}</p>}
          <ul className="card-list">
            {data.invitations.map((c) => {
              const enrolment = data.enrolments.find((e) => e.campaignId === c.campaignId);
              return (
                <li key={c.campaignId} className="card">
                  <div className="card-row">
                    <strong>{c.title}</strong>
                    {c.risk && <StatusBadge status="active" label={labelOf(t.risks, c.risk)} />}
                  </div>
                  {c.objective && <p>{c.objective}</p>}
                  <dl className="case-facts">
                    <Fact label={t.caWhen}>{period(lang, c.startDate, c.endDate)}</Fact>
                  </dl>
                  {(c.wearable || enrolment) && (
                    <WearablePanel
                      t={t}
                      lang={lang}
                      campaign={c}
                      enrolment={enrolment}
                      device={devices[c.campaignId] ?? 'PHONE'}
                      consent={!!consents[c.campaignId]}
                      consentError={consentError === c.campaignId}
                      withdrawing={!!enrolment && withdrawing === enrolment.id}
                      busy={busy}
                      onDevice={(d) => setDevices((m) => ({ ...m, [c.campaignId]: d }))}
                      onConsent={(v) => setConsents((m) => ({ ...m, [c.campaignId]: v }))}
                      onEnrol={enrol}
                      onWithdraw={(e) => setWithdrawing(e.id)}
                      onCancelWithdraw={() => setWithdrawing(undefined)}
                      onConfirmWithdraw={() =>
                        enrolment &&
                        void run(async () => {
                          await withdrawWearable(client, enrolment.id);
                          setWithdrawing(undefined);
                          mine.reload();
                        }, t.caWithdrawOk)
                      }
                    />
                  )}
                </li>
              );
            })}
          </ul>
          <h2>{t.caScreenings}</h2>
          <ScreeningList t={t} lang={lang} items={data.screenings} />
        </>
      )}
    </PortalSection>
  );
}
