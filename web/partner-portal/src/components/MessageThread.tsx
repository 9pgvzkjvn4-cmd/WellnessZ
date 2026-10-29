import { useState, type FormEvent } from 'react';
import type { AuthClient } from '../auth/AuthClient';
import { describeError } from '../api/errors';
import { loadMessages, MESSAGE_MAX, postMessage, type Message } from '../api/messages';
import { S } from '../strings';
import { formatDateTime, humanise } from './format';
import { TextAreaField } from './FormFields';
import { Alert, Loading } from './ui';
import { useAsync } from './useAsync';

const roleLabel = (role?: string) => (role ? (S.labels.senderRole[role] ?? humanise(role)) : undefined);

/** Hook-free message list (newest last). */
export function MessageList({ messages, ownRoles }: { messages: readonly Message[]; ownRoles: readonly string[] }) {
  if (messages.length === 0) return <p className="muted">{S.messages.empty}</p>;
  return (
    <ol className="message-list" aria-label={S.messages.log}>
      {messages.map((m) => {
        const own = !!m.senderRole && ownRoles.includes(m.senderRole);
        return (
          <li key={m.id} className={own ? 'message message-own' : 'message'}>
            <p className="message-meta small muted">
              <strong>{own ? S.messages.you : (m.sender ?? roleLabel(m.senderRole) ?? '')}</strong>
              {!own && m.sender && roleLabel(m.senderRole) ? ` · ${roleLabel(m.senderRole)}` : ''} · <time dateTime={m.sentAt}>{formatDateTime(m.sentAt)}</time>
            </p>
            <p className="message-text">{m.text}</p>
          </li>
        );
      })}
    </ol>
  );
}

/** Secure message thread at `path` (GET list / POST `{text}`), with 422 clinical-content handling. */
export function MessageThread({
  client,
  path,
  idPrefix,
  ownRoles,
  hint,
  onSessionExpired,
}: {
  client: Pick<AuthClient, 'api'>;
  path: string;
  idPrefix: string;
  ownRoles: readonly string[];
  hint?: string;
  onSessionExpired: () => void;
}) {
  const thread = useAsync(() => loadMessages(client, path), onSessionExpired);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; field?: string }>();
  const inputId = `${idPrefix}-text`;

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!text.trim()) {
      setError({ message: S.validation.required, field: S.validation.required });
      document.getElementById(inputId)?.focus();
      return;
    }
    setBusy(true);
    setError(undefined);
    const res = await postMessage(client, path, text.trim(), S.messages.failedSend);
    setBusy(false);
    if (res.ok) {
      setText('');
      thread.reload();
      return;
    }
    if (res.sessionExpired) onSessionExpired();
    setError({ message: res.message, field: res.fieldErrors.text });
    if (res.fieldErrors.text) document.getElementById(inputId)?.focus();
  }

  return (
    <div className="thread">
      {hint && <p className="muted small">{hint}</p>}
      {thread.loading && !thread.data && <Loading />}
      {!!thread.error && <Alert kind="error">{describeError(thread.error)}</Alert>}
      {thread.data && <MessageList messages={thread.data} ownRoles={ownRoles} />}
      <form className="form" onSubmit={(e) => void send(e)} noValidate>
        {error && <Alert kind="error">{error.message}</Alert>}
        <TextAreaField id={inputId} label={S.messages.label} value={text} onChange={setText} max={MESSAGE_MAX} required error={error?.field} rows={2} hint={S.confidentiality.noClinicalText} />
        <div className="actions">
          <button type="submit" disabled={busy}>
            {busy ? S.messages.sending : S.messages.send}
          </button>
          <button type="button" className="secondary" onClick={thread.reload} disabled={thread.loading}>
            {S.app.refresh}
          </button>
        </div>
      </form>
    </div>
  );
}
