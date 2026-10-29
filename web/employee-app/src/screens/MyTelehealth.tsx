import { useCallback, useEffect, useState } from 'react';
import {
  canChat,
  canJoinSession,
  canSendChat,
  fetchChat,
  fetchMySessions,
  giveSessionConsent,
  isOpenSession,
  joinSession,
  MAX_CHAT,
  sendChat,
  sessionEnd,
  sessionWindow,
  sortSessions,
  usableLink,
  type ChatMessage,
  type JoinLink,
  type MySession,
} from '../api/care';
import { SessionExpiredError } from '../auth/AuthClient';
import { formatDateTimeZone } from '../components/format';
import { EmptyState, PortalSection } from '../components/portal';
import { Alert, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { careErrorText, careStrings, type CareStrings } from '../i18n/care';
import { labelOf } from '../i18n/portal';
import type { PortalScreenProps } from './types';

const timeOnly = (iso: string, lang: string) => new Date(iso).toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });

export interface ConsentBoxProps {
  t: CareStrings;
  s: MySession;
  checked: boolean;
  busy: boolean;
  onCheck: (v: boolean) => void;
  onConsent: (s: MySession) => void;
}

/** Explains what is recorded and who sees the note; asks for the session's TELEHEALTH_CONSULTATION consent. Hook-free. */
export function ConsentBox({ t, s, checked, busy, onCheck, onConsent }: ConsentBoxProps) {
  const id = `th-consent-${s.id}`;
  return (
    <section className="ack-box consent-box" aria-labelledby={`${id}-title`}>
      <h4 id={`${id}-title`} className="card-title">
        {t.thConsentTitle}
      </h4>
      <p>{t.thConsentIntro}</p>
      <strong>{t.thRecordedTitle}</strong>
      <ul>
        <li>{t.thRecAttendance}</li>
        <li>{t.thRecChat}</li>
        <li>{t.thRecNote}</li>
        <li>{t.thRecNoVideo}</li>
      </ul>
      <p>
        <strong>{t.thNoteOhOnly}</strong>
      </p>
      {s.partner && <p>{t.thPartnerSees}</p>}
      <p className="muted small">{t.thWithdraw}</p>
      <label className="checkbox">
        <input id={id} type="checkbox" checked={checked} onChange={(e) => onCheck(e.target.checked)} />
        <span>{t.thConsentCheckbox}</span>
      </label>
      <div className="actions">
        <button type="button" className="large-touch" disabled={busy || !checked} onClick={() => onConsent(s)}>
          {busy ? t.sending : t.thConsentGive}
        </button>
      </div>
    </section>
  );
}

export interface ChatPanelProps {
  t: CareStrings;
  lang: string;
  s: MySession;
  messages?: readonly ChatMessage[];
  loading: boolean;
  draft: string;
  busy: boolean;
  onDraft: (v: string) => void;
  onSend: () => void;
  onRefresh: () => void;
}

/** Secure chat: message history (text only, never HTML) and a composer while the session is open. Hook-free. */
export function ChatPanel(p: ChatPanelProps) {
  const { t, lang, s } = p;
  const id = `th-chat-${s.id}`;
  const canSend = canSendChat(s);
  return (
    <section className="chat-panel" aria-labelledby={`${id}-title`}>
      <div className="card-row">
        <h4 id={`${id}-title`} className="card-title">
          {t.chTitle}
        </h4>
        <button type="button" className="secondary small-button" disabled={p.loading} onClick={p.onRefresh}>
          {t.refresh}
        </button>
      </div>
      <p className="muted small">{t.chIntro}</p>
      <p className="muted small">{t.chEmergency}</p>
      {p.loading && !p.messages && <Loading />}
      {p.messages && p.messages.length === 0 && <p className="muted">{t.chEmpty}</p>}
      {p.messages && p.messages.length > 0 && (
        <ol className="chat-log" aria-live="polite">
          {p.messages.map((m) => (
            <li key={m.id} className={m.mine ? 'chat-msg mine' : 'chat-msg'}>
              <span className="chat-meta muted small">
                <bdi>{m.mine ? t.chYou : (m.sender ?? t.chOh)}</bdi>
                {m.sentAt && <> · {timeOnly(m.sentAt, lang)}</>}
              </span>
              <span className="chat-text">{m.text}</span>
            </li>
          ))}
        </ol>
      )}
      {canSend ? (
        <form
          className="form chat-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (p.draft.trim()) p.onSend();
          }}
        >
          <label htmlFor={`${id}-text`}>{t.chMessage}</label>
          <textarea id={`${id}-text`} rows={3} maxLength={MAX_CHAT} value={p.draft} onChange={(e) => p.onDraft(e.target.value)} aria-describedby={`${id}-count`} />
          <span id={`${id}-count`} className="muted small">
            {t.chCount(p.draft.length, MAX_CHAT)}
          </span>
          <div className="actions">
            <button type="submit" className="large-touch" disabled={p.busy || !p.draft.trim()}>
              {p.busy ? t.sending : t.chSend}
            </button>
          </div>
        </form>
      ) : (
        <p className="muted small">{t.chReadOnly}</p>
      )}
    </section>
  );
}

export interface SessionCardProps {
  t: CareStrings;
  lang: string;
  s: MySession;
  now: Date;
  busy: boolean;
  link?: JoinLink;
  consentChecked: boolean;
  chatOpen: boolean;
  chat?: Omit<ChatPanelProps, 't' | 'lang' | 's'>;
  onConsentCheck: (v: boolean) => void;
  onConsent: (s: MySession) => void;
  onJoin: (s: MySession) => void;
  onToggleChat: (s: MySession) => void;
}

/** One consultation: when, how, with whom, layover note; consent, join and secure chat. Hook-free. */
export function SessionCard(p: SessionCardProps) {
  const { t, lang, s, now } = p;
  const hid = `th-${s.id}`;
  const win = sessionWindow(s, now);
  const link = usableLink(s, p.link, now);
  const open = isOpenSession(s);
  return (
    <article className="card session-card" aria-labelledby={hid}>
      <div className="card-row">
        <h3 id={hid} className="card-title">
          {labelOf(t.mode, s.mode)}
        </h3>
        <StatusBadge status={`th-${s.status}`} label={labelOf(t.sessionStatus, s.status)} />
      </div>
      <dl className="case-facts">
        <dt>{t.thWhen}</dt>
        <dd>
          <strong>{formatDateTimeZone(s.scheduledAt, lang)}</strong> · {t.thDuration(s.durationMinutes)}
        </dd>
        <dt>{t.thWith}</dt>
        <dd>
          <bdi>{s.partner ? t.thPartner(s.partner) : (s.clinician ?? t.thOhClinician)}</bdi>
        </dd>
      </dl>
      {s.crewLayover && (
        <div className="ack-box layover-note">
          <strong>{s.layoverCity ? t.thLayover(s.layoverCity) : t.thLayoverNoCity}</strong>
          <p>{t.thLayoverHint}</p>
        </div>
      )}
      {open && win !== 'ended' && !s.consentGiven && (
        <ConsentBox t={t} s={s} checked={p.consentChecked} busy={p.busy} onCheck={p.onConsentCheck} onConsent={p.onConsent} />
      )}
      {s.consentGiven && open && (
        <p className="muted small">
          <StatusBadge status="GRANTED" label={t.thConsentGiven} />
        </p>
      )}
      {open && s.mode === 'CHAT' && s.consentGiven && <p>{t.thChatMode}</p>}
      {open && s.mode !== 'CHAT' && (
        <div className="actions join-actions">
          {link ? (
            <>
              <p className="muted small">
                {t.thLinkReady}
                {link.expiresAt && <> {t.thLinkExpires(timeOnly(link.expiresAt, lang))}</>}
              </p>
              <a className="button large-touch" href={link.url} target="_blank" rel="noopener noreferrer">
                {t.thOpenRoom} <span className="visually-hidden">{t.opensNewTab}</span>
              </a>
            </>
          ) : (
            <button type="button" className="large-touch" disabled={p.busy || !canJoinSession(s, now)} onClick={() => p.onJoin(s)}>
              {t.thJoin}
            </button>
          )}
          {!s.consentGiven && win !== 'ended' && <span className="muted small">{t.thJoinNeedsConsent}</span>}
          {s.consentGiven && win === 'before' && <span className="muted small">{t.thJoinBefore(timeOnly(s.scheduledAt, lang))}</span>}
          {win === 'ended' && <span className="muted small">{t.thJoinEnded}</span>}
        </div>
      )}
      {canChat(s) && (
        <div className="actions">
          <button type="button" className="secondary large-touch" aria-expanded={p.chatOpen} aria-controls={`th-chat-${s.id}-title`} onClick={() => p.onToggleChat(s)}>
            {p.chatOpen ? t.chHide : t.chShow}
          </button>
        </div>
      )}
      {p.chatOpen && p.chat && canChat(s) && <ChatPanel t={t} lang={lang} s={s} {...p.chat} />}
    </article>
  );
}

const CHAT_POLL_MS = 15_000;
const CLOCK_MS = 30_000;

/** `#/telehealth` – my video / chat consultations: consent, join, secure chat (E04 §3.7). */
export function MyTelehealth({ client, onSessionExpired, lang }: PortalScreenProps) {
  const t = careStrings(lang);
  const sessions = useAsync(() => fetchMySessions(client), onSessionExpired);
  const [now, setNow] = useState(() => new Date());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [links, setLinks] = useState<Record<string, JoinLink>>({});
  const [chatId, setChatId] = useState<string>();
  const [chat, setChat] = useState<{ id: string; messages?: ChatMessage[]; loading: boolean }>();
  const [draft, setDraft] = useState('');

  // The join window opens by itself: re-evaluate every 30 seconds.
  useEffect(() => {
    const h = setInterval(() => setNow(new Date()), CLOCK_MS);
    return () => clearInterval(h);
  }, []);

  const loadChat = useCallback(
    async (id: string) => {
      setChat((c) => ({ id, messages: c?.id === id ? c.messages : undefined, loading: true }));
      try {
        const messages = await fetchChat(client, id);
        setChat((c) => (c && c.id !== id ? c : { id, messages, loading: false }));
      } catch (e) {
        if (e instanceof SessionExpiredError) onSessionExpired();
        setChat((c) => (c && c.id !== id ? c : { id, messages: c?.messages, loading: false }));
        setMessage({ kind: 'error', text: careErrorText(lang, e) });
      }
    },
    [client, lang, onSessionExpired],
  );

  // Poll the open chat while it is shown.
  useEffect(() => {
    if (!chatId) return;
    const h = setInterval(() => void loadChat(chatId), CHAT_POLL_MS);
    return () => clearInterval(h);
  }, [chatId, loadChat]);

  async function run(fn: () => Promise<string | void>, reload = true) {
    setBusy(true);
    setMessage(undefined);
    try {
      const msg = await fn();
      if (msg) setMessage({ kind: 'success', text: msg });
      if (reload) sessions.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: careErrorText(lang, e) });
    } finally {
      setBusy(false);
    }
  }

  const list = sortSessions(sessions.data ?? [], now);
  const upcoming = list.filter((s) => isOpenSession(s) && sessionEnd(s) >= now.getTime());
  const past = list.filter((s) => !upcoming.includes(s));

  const card = (s: MySession) => (
    <li key={s.id}>
      <SessionCard
        t={t}
        lang={lang}
        s={s}
        now={now}
        busy={busy}
        link={links[s.id]}
        consentChecked={!!checked[s.id]}
        chatOpen={chatId === s.id}
        chat={
          chat?.id === s.id
            ? {
                messages: chat.messages,
                loading: chat.loading,
                draft,
                busy,
                onDraft: setDraft,
                onRefresh: () => void loadChat(s.id),
                onSend: () =>
                  void run(async () => {
                    await sendChat(client, s.id, draft);
                    setDraft('');
                    await loadChat(s.id);
                  }, false),
              }
            : undefined
        }
        onConsentCheck={(v) => setChecked((c) => ({ ...c, [s.id]: v }))}
        onConsent={(x) =>
          void run(async () => {
            await giveSessionConsent(client, x.id);
            return t.thConsentOk;
          })
        }
        onJoin={(x) =>
          void run(async () => {
            if (!canJoinSession(x, new Date())) return;
            const link = await joinSession(client, x.id);
            if (link) setLinks((l) => ({ ...l, [x.id]: link }));
          }, false)
        }
        onToggleChat={(x) => {
          if (chatId === x.id) {
            setChatId(undefined);
            return;
          }
          setDraft('');
          setChatId(x.id);
          void loadChat(x.id);
        }}
      />
    </li>
  );

  return (
    <PortalSection lang={lang} className="telehealth">
      <PageTitle>{t.thTitle}</PageTitle>
      <p className="lead">{t.thIntro}</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      {sessions.loading && <Loading />}
      {!!sessions.error && <Alert kind="error">{careErrorText(lang, sessions.error)}</Alert>}
      {sessions.data && list.length === 0 && <EmptyState title={t.thEmpty} hint={t.thEmptyHint} />}
      {upcoming.length > 0 && (
        <>
          <h2>{t.thUpcoming}</h2>
          <ul className="card-list">{upcoming.map(card)}</ul>
        </>
      )}
      {past.length > 0 && (
        <>
          <h2>{t.thPast}</h2>
          <ul className="card-list">{past.map(card)}</ul>
        </>
      )}
    </PortalSection>
  );
}
