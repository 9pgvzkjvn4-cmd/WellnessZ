import { useState } from 'react';
import { describeError } from '../api/errors';
import { joinSession, joinState, openInNewTab, telehealthMessagesPath, toTelehealthSessions, type TelehealthSession } from '../api/telehealth';
import { PARTNER_PROVIDER } from '../auth/roles';
import { formatDateTime, humanise, idPart } from '../components/format';
import { MessageThread } from '../components/MessageThread';
import { Alert, ConfidentialityNote, Loading, PageTitle, StatusBadge } from '../components/ui';
import { useAsync } from '../components/useAsync';
import { S } from '../strings';
import { orgLabel, type ScreenProps } from './types';

type JoinOutcome = { kind: 'opened'; url: string } | { kind: 'unsafe' } | { kind: 'error'; message: string };

function JoinNote({ s, now }: { s: TelehealthSession; now: Date }) {
  const state = joinState(s, now);
  if (state === 'early') return <p className="muted small">{S.telehealth.joinEarly}</p>;
  if (state === 'ended') return <p className="muted small">{S.telehealth.joinEnded}</p>;
  if (state === 'no-consent') return <p className="muted small">{S.telehealth.noConsent}</p>;
  return null;
}

export function SessionCard({
  s,
  now,
  busy,
  outcome,
  chatOpen,
  onJoin,
  onToggleChat,
}: {
  s: TelehealthSession;
  now: Date;
  busy: boolean;
  outcome?: JoinOutcome;
  chatOpen: boolean;
  onJoin: () => void;
  onToggleChat: () => void;
}) {
  const state = joinState(s, now);
  const chatId = `chat-${idPart(s.id)}`;
  return (
    <div className="card-row">
      <div>
        <h2 className="card-title">
          {s.ref} · {s.displayName}
        </h2>
        <p className="small">
          <time dateTime={s.scheduledAt}>{formatDateTime(s.scheduledAt)}</time> · {S.telehealth.duration(s.durationMinutes)} · {humanise(s.mode)}{' '}
          <StatusBadge status={s.status} />
          {s.crewLayover && <StatusBadge status="layover" label={S.telehealth.layover(s.layoverCity)} />}
        </p>
        <JoinNote s={s} now={now} />
        {outcome?.kind === 'opened' && <Alert kind="success">{S.telehealth.joinOpened}</Alert>}
        {outcome?.kind === 'opened' && (
          <p>
            <a href={outcome.url} target="_blank" rel="noopener noreferrer">
              {S.telehealth.joinLink}
            </a>
          </p>
        )}
        {outcome?.kind === 'unsafe' && <Alert kind="error">{S.telehealth.joinUnsafe}</Alert>}
        {outcome?.kind === 'error' && <Alert kind="error">{outcome.message}</Alert>}
      </div>
      <div className="actions">
        {state === 'open' && (
          <button type="button" onClick={onJoin} disabled={busy}>
            {busy ? S.telehealth.joining : S.telehealth.join}
          </button>
        )}
        <button type="button" className="secondary" aria-expanded={chatOpen} aria-controls={chatId} onClick={onToggleChat}>
          {chatOpen ? S.telehealth.chatClose : S.telehealth.chatOpen(s.ref)}
        </button>
      </div>
    </div>
  );
}

export function Telehealth({ client, me, onSessionExpired }: ScreenProps) {
  const data = useAsync(async () => toTelehealthSessions(await client.api<unknown>('/partner/telehealth/sessions')), onSessionExpired);
  const [busy, setBusy] = useState<string>();
  const [outcomes, setOutcomes] = useState<Record<string, JoinOutcome>>({});
  const [chat, setChat] = useState<string>();
  const now = new Date();

  async function join(s: TelehealthSession) {
    setBusy(s.id);
    const res = await joinSession(client, s.id, S.telehealth.failedJoin);
    setBusy(undefined);
    let outcome: JoinOutcome;
    if (!res.ok) {
      if (res.sessionExpired) onSessionExpired();
      outcome = { kind: 'error', message: res.message };
    } else if (!res.body.joinUrl) {
      outcome = { kind: 'unsafe' };
    } else {
      openInNewTab(res.body.joinUrl);
      outcome = { kind: 'opened', url: res.body.joinUrl };
    }
    setOutcomes((o) => ({ ...o, [s.id]: outcome }));
  }

  return (
    <section>
      <PageTitle>{S.telehealth.title}</PageTitle>
      <p className="lead">{S.telehealth.lead}</p>
      <ConfidentialityNote>{S.confidentiality.provider(orgLabel(me))}</ConfidentialityNote>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={data.reload} disabled={data.loading}>
          {S.app.refresh}
        </button>
      </div>
      {data.loading && !data.data && <Loading />}
      {!!data.error && <Alert kind="error">{describeError(data.error)}</Alert>}
      {data.data && data.data.length === 0 && <p className="muted">{S.telehealth.empty}</p>}
      {data.data && data.data.length > 0 && (
        <ul className="card-list">
          {data.data.map((s) => (
            <li key={s.id} className="card">
              <SessionCard
                s={s}
                now={now}
                busy={busy === s.id}
                outcome={outcomes[s.id]}
                chatOpen={chat === s.id}
                onJoin={() => void join(s)}
                onToggleChat={() => setChat(chat === s.id ? undefined : s.id)}
              />
              {chat === s.id && (
                <div id={`chat-${idPart(s.id)}`} className="chat-panel">
                  <h3>{S.telehealth.chat}</h3>
                  <MessageThread
                    client={client}
                    path={telehealthMessagesPath(s.id)}
                    idPrefix={`chat-${idPart(s.id)}`}
                    ownRoles={[PARTNER_PROVIDER]}
                    hint={S.telehealth.chatHint}
                    onSessionExpired={onSessionExpired}
                  />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
