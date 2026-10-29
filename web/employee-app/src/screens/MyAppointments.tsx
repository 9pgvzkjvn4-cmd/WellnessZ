import { useState } from 'react';
import {
  bookAppointment,
  cancelAppointment,
  canChange,
  canCheckIn,
  canJoinTelehealth,
  checkInAppointment,
  fetchBookableTypes,
  fetchMyAppointments,
  fetchSites,
  fetchSlots,
  isOpenAppointment,
  rescheduleAppointment,
  slotsByDay,
  sortAppointments,
  telehealthLink,
  type BookableType,
  type ClinicSite,
  type MyAppointment,
  type Slot,
} from '../api/occupational';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDate, formatDay } from '../components/format';
import { ConfirmPanel, EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { labelOf } from '../i18n/portal';
import { ohErrorText, ohStrings, type OhStrings } from '../i18n/occupational';
import { today } from '../intake/occupationalForms';
import type { PortalScreenProps } from './types';

const timeOf = (iso: string, lang: string) => new Date(iso).toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });

export interface AppointmentCardProps {
  t: OhStrings;
  lang: string;
  a: MyAppointment;
  now: Date;
  busy: boolean;
  joinUrl?: string;
  cancelling: boolean;
  cancelReason: string;
  /** Candidates can cancel but not pick new slots (self-booking slots are employee-only). */
  canReschedule: boolean;
  onCheckIn: (a: MyAppointment) => void;
  onJoin: (a: MyAppointment) => void;
  onReschedule: (a: MyAppointment) => void;
  onCancel: (a: MyAppointment) => void;
  onCancelReason: (v: string) => void;
  onConfirmCancel: () => void;
  onAbortCancel: () => void;
}

/** One appointment – type, time, clinic, status, ticket; check-in / join / change / cancel when allowed. Hook-free. */
export function AppointmentCard(p: AppointmentCardProps) {
  const { t, lang, a, now } = p;
  const hid = `appt-${a.id}`;
  return (
    <article className="card" aria-labelledby={hid}>
      <div className="card-row">
        <h3 id={hid} className="card-title">
          {a.typeName ?? labelOf({}, a.type)}
        </h3>
        <StatusBadge status={`appt-${a.status}`} label={labelOf(t.apptStatus, a.status)} />
      </div>
      <dl className="case-facts">
        <dt>{t.apptWhen}</dt>
        <dd>
          {formatDate(a.startsAt, lang)}
          {a.endsAt && <> – {timeOf(a.endsAt, lang)}</>}
        </dd>
        <dt>{t.apptWhere}</dt>
        <dd>{a.telehealth ? t.apptVideo : (a.siteCode ?? '–')}</dd>
      </dl>
      {a.ticket && (
        <p className="ack-box" role="status">
          <strong>{t.apptTicket(a.ticket)}</strong>
        </p>
      )}
      {canCheckIn(a, now) && !a.telehealth && (
        <div className="actions">
          <button type="button" disabled={p.busy} onClick={() => p.onCheckIn(a)}>
            {t.apptCheckIn}
          </button>
          <span className="muted small">{t.apptCheckInHint}</span>
        </div>
      )}
      {a.telehealth && isOpenAppointment(a) && (
        <div className="actions">
          {p.joinUrl ? (
            <a className="button" href={p.joinUrl} target="_blank" rel="noopener noreferrer">
              {t.apptJoin} <span className="visually-hidden">{t.opensNewTab}</span>
            </a>
          ) : (
            <button type="button" disabled={p.busy || !canJoinTelehealth(a, now)} onClick={() => p.onJoin(a)}>
              {t.apptJoin}
            </button>
          )}
          {!canJoinTelehealth(a, now) && <span className="muted small">{t.apptJoinHint}</span>}
        </div>
      )}
      {canChange(a, now) &&
        (p.cancelling ? (
          <ConfirmPanel
            id={`${hid}-cancel`}
            title={t.apptCancelTitle}
            confirmLabel={t.apptCancelConfirm}
            cancelLabel={t.back}
            busy={p.busy}
            busyLabel={t.sending}
            danger
            onConfirm={p.onConfirmCancel}
            onCancel={p.onAbortCancel}
          >
            <label htmlFor={`${hid}-reason`}>{t.apptCancelReason}</label>
            <textarea id={`${hid}-reason`} rows={2} maxLength={500} value={p.cancelReason} onChange={(e) => p.onCancelReason(e.target.value)} />
          </ConfirmPanel>
        ) : (
          <div className="actions">
            {p.canReschedule && (
              <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onReschedule(a)}>
                {t.apptReschedule}
              </button>
            )}
            <button type="button" className="secondary" disabled={p.busy} onClick={() => p.onCancel(a)}>
              {t.apptCancel}
            </button>
          </div>
        ))}
    </article>
  );
}

export interface SlotPickerProps {
  t: OhStrings;
  lang: string;
  slots: readonly Slot[];
  chosen?: string;
  busy: boolean;
  submitLabel: string;
  onChoose: (startsAt: string) => void;
  onSubmit: () => void;
}

/** Free times grouped by day as radio buttons (keyboard friendly). Hook-free. */
export function SlotPicker({ t, lang, slots, chosen, busy, submitLabel, onChoose, onSubmit }: SlotPickerProps) {
  if (slots.length === 0) return <p className="muted">{t.apptNoSlots}</p>;
  return (
    <fieldset className="slot-picker">
      <legend>{t.apptChooseSlot}</legend>
      {slotsByDay(slots).map(([day, list]) => (
        <div key={day} className="slot-day">
          <h4 className="sub-title">{formatDay(day, lang)}</h4>
          <div className="inline-options">
            {list.map((s) => (
              <label key={s.startsAt} className="chip">
                <input type="radio" name="slot" value={s.startsAt} checked={chosen === s.startsAt} onChange={() => onChoose(s.startsAt)} /> {timeOf(s.startsAt, lang)}
              </label>
            ))}
          </div>
        </div>
      ))}
      <div className="actions">
        <button type="button" disabled={busy || !chosen} onClick={onSubmit}>
          {busy ? t.sending : submitLabel}
        </button>
      </div>
    </fieldset>
  );
}

export interface BookingFormProps {
  t: OhStrings;
  lang: string;
  types: readonly BookableType[];
  sites: readonly ClinicSite[];
  type: string;
  site: string;
  /** Types the employee already has an open booking for (one open booking per type). */
  blockedTypes: ReadonlySet<string>;
  slots?: readonly Slot[];
  chosen?: string;
  busy: boolean;
  onType: (v: string) => void;
  onSite: (v: string) => void;
  onFind: () => void;
  onChoose: (startsAt: string) => void;
  onBook: () => void;
}

/** Self-booking: type (self-bookable only) → clinic → free times → book. Hook-free. */
export function BookingForm(p: BookingFormProps) {
  const { t } = p;
  if (p.types.length === 0) return <p className="muted">{t.apptNoTypes}</p>;
  const blocked = !!p.type && p.blockedTypes.has(p.type);
  return (
    <div className="form">
      <p className="muted small">{t.apptRules}</p>
      <div className="field">
        <label htmlFor="bk-type">{t.apptType}</label>
        <select id="bk-type" value={p.type} onChange={(e) => p.onType(e.target.value)} aria-describedby={blocked ? 'bk-type-err' : undefined}>
          <option value="">{t.apptChoose}</option>
          {p.types.map((ty) => (
            <option key={ty.code} value={ty.code}>
              {ty.name}
            </option>
          ))}
        </select>
        {blocked && (
          <p id="bk-type-err" className="field-error">
            {t.apptAlreadyBooked}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="bk-site">{t.apptSite}</label>
        <select id="bk-site" value={p.site} onChange={(e) => p.onSite(e.target.value)}>
          <option value="">{t.apptChoose}</option>
          {p.sites.map((s) => (
            <option key={s.code} value={s.code}>
              {s.name}
            </option>
          ))}
        </select>
      </div>
      <div className="actions">
        <button type="button" className="secondary" disabled={p.busy || !p.type || !p.site || blocked} onClick={p.onFind}>
          {t.apptFindSlots}
        </button>
      </div>
      {p.slots && !blocked && <SlotPicker t={t} lang={p.lang} slots={p.slots} chosen={p.chosen} busy={p.busy} submitLabel={t.apptBook} onChoose={p.onChoose} onSubmit={p.onBook} />}
    </div>
  );
}

const plusDays = (n: number, now = new Date()) => today(new Date(now.getFullYear(), now.getMonth(), now.getDate() + n));

/** `#/appointments` – my appointments, self-booking, reschedule, cancel, check-in, telehealth join (E14 §5.2). */
export function MyAppointments({ client, session, onSessionExpired, lang }: PortalScreenProps) {
  const t = ohStrings(lang);
  const isEmployee = session.roles.includes('EMPLOYEE');
  const appts = useAsync(() => fetchMyAppointments(client), onSessionExpired);
  const options = useAsync(() => (isEmployee ? Promise.all([fetchBookableTypes(client), fetchSites(client)]) : Promise.resolve([[], []] as [BookableType[], ClinicSite[]])), onSessionExpired);
  const [now] = useState(() => new Date());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [joinUrls, setJoinUrls] = useState<Record<string, string>>({});
  const [cancelling, setCancelling] = useState<string>();
  const [reason, setReason] = useState('');
  const [book, setBook] = useState<{ type: string; site: string; slots?: Slot[]; chosen?: string; reschedule?: MyAppointment }>({ type: '', site: '' });

  async function run(fn: () => Promise<string | void>, reload = true) {
    setBusy(true);
    setMessage(undefined);
    try {
      const msg = await fn();
      if (msg) setMessage({ kind: 'success', text: msg });
      if (reload) appts.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: ohErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const findSlots = (type: string, site: string, reschedule?: MyAppointment) =>
    run(async () => {
      const slots = await fetchSlots(client, type, site, plusDays(1), plusDays(60));
      setBook({ type, site, slots, chosen: undefined, reschedule });
    }, false);

  const list = sortAppointments(appts.data ?? [], now);
  const upcoming = list.filter((a) => isOpenAppointment(a) && Date.parse(a.endsAt ?? a.startsAt) >= now.getTime());
  const past = list.filter((a) => !upcoming.includes(a));
  const blockedTypes = new Set(upcoming.filter((a) => a.status === 'BOOKED').map((a) => a.type));
  const [types, sites] = options.data ?? [[], []];

  const card = (a: MyAppointment) => (
    <li key={a.id}>
      <AppointmentCard
        t={t}
        lang={lang}
        a={a}
        now={now}
        busy={busy}
        joinUrl={joinUrls[a.id]}
        cancelling={cancelling === a.id}
        cancelReason={reason}
        canReschedule={isEmployee && !!a.siteCode && !a.telehealth}
        onCheckIn={(x) => void run(async () => t.apptCheckedInOk(await checkInAppointment(client, x.id)))}
        onJoin={(x) =>
          void run(async () => {
            const url = await telehealthLink(client, x.id);
            if (url) setJoinUrls((j) => ({ ...j, [x.id]: url }));
          }, false)
        }
        onReschedule={(x) => void findSlots(x.type, x.siteCode ?? '', x)}
        onCancel={(x) => {
          setReason('');
          setCancelling(x.id);
        }}
        onCancelReason={setReason}
        onConfirmCancel={() =>
          void run(async () => {
            await cancelAppointment(client, a.id, reason);
            setCancelling(undefined);
            return t.apptCancelledOk;
          })
        }
        onAbortCancel={() => setCancelling(undefined)}
      />
    </li>
  );

  return (
    <PortalSection lang={lang}>
      <PageTitle>{t.apptTitle}</PageTitle>
      <p className="lead">{t.apptIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {appts.loading && <Loading />}
      {!!appts.error && <Alert kind="error">{ohErrorText(lang, appts.error)}</Alert>}
      {appts.data && list.length === 0 && <EmptyState title={t.apptEmpty} hint={t.apptEmptyHint} />}
      {upcoming.length > 0 && (
        <>
          <h2>{t.apptUpcoming}</h2>
          <ul className="card-list">{upcoming.map(card)}</ul>
        </>
      )}

      {book.reschedule && book.slots && (
        <section aria-labelledby="resched-title" className="card">
          <h2 id="resched-title">
            {t.apptRescheduleTitle} – {book.reschedule.typeName ?? book.reschedule.type}
          </h2>
          <SlotPicker
            t={t}
            lang={lang}
            slots={book.slots}
            chosen={book.chosen}
            busy={busy}
            submitLabel={t.apptReschedule}
            onChoose={(chosen) => setBook({ ...book, chosen })}
            onSubmit={() =>
              void run(async () => {
                if (!book.reschedule || !book.chosen) return;
                await rescheduleAppointment(client, book.reschedule.id, book.chosen);
                setBook({ type: '', site: '' });
                return t.apptRescheduledOk;
              })
            }
          />
          <button type="button" className="link-button" onClick={() => setBook({ type: '', site: '' })}>
            {t.cancel}
          </button>
        </section>
      )}

      {isEmployee && (
        <section aria-labelledby="book-title">
          <h2 id="book-title">{t.apptBookTitle}</h2>
          <p className="muted">{t.apptBookIntro}</p>
          {options.loading && <Loading />}
          {!!options.error && <Alert kind="error">{ohErrorText(lang, options.error)}</Alert>}
          {options.data && (
            <BookingForm
              t={t}
              lang={lang}
              types={types}
              sites={sites}
              type={book.reschedule ? '' : book.type}
              site={book.reschedule ? '' : book.site}
              blockedTypes={blockedTypes}
              slots={book.reschedule ? undefined : book.slots}
              chosen={book.chosen}
              busy={busy}
              onType={(type) => setBook({ type, site: book.reschedule ? '' : book.site })}
              onSite={(site) => setBook({ type: book.reschedule ? '' : book.type, site })}
              onFind={() => void findSlots(book.type, book.site)}
              onChoose={(chosen) => setBook({ ...book, chosen })}
              onBook={() =>
                void run(async () => {
                  if (!book.chosen) return;
                  const a = await bookAppointment(client, book.type, book.site, book.chosen);
                  setBook({ type: '', site: '' });
                  return t.apptBookedOk(formatDate(a?.startsAt ?? book.chosen, lang));
                })
              }
            />
          )}
        </section>
      )}

      {past.length > 0 && (
        <>
          <h2>{t.apptPast}</h2>
          <ul className="card-list">{past.map(card)}</ul>
        </>
      )}
    </PortalSection>
  );
}
