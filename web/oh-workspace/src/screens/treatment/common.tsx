import { useState, type FormEvent, type ReactNode } from 'react';
import type { Provider } from '../../api/treatment';
import { formatDate, humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { ActionFeedback, Empty, ErrorAlert, Field, Loading } from '../../components/ui';
import { useAction, useAsync } from '../../components/useAsync';
import { contractTone, isContracted, scoreTone, type TimelineStep } from '../../domain/treatment';
import './treatment.css';

/** Contracted / preferred / contract-status badges (shape + text, never colour alone). */
export function ProviderBadges({ p }: { p: Pick<Provider, 'preferred' | 'contract'> }) {
  return (
    <span className="badge-row">
      {p.preferred && <ToneBadge tone="green">Preferred</ToneBadge>}
      {isContracted(p) ? (
        <ToneBadge tone={contractTone(p.contract?.status)}>{p.contract?.status === 'EXPIRING' ? 'Contracted – expiring' : 'Contracted'}</ToneBadge>
      ) : (
        <ToneBadge tone={contractTone(p.contract?.status)}>{p.contract?.status === 'EXPIRED' ? 'Contract expired' : 'No contract'}</ToneBadge>
      )}
    </span>
  );
}

export function ScoreBadge({ score, label = 'Scorecard' }: { score?: number | null; label?: string }) {
  if (score === undefined || score === null) return <span className="muted small">No scorecard</span>;
  return (
    <ToneBadge tone={scoreTone(score)} title={`${label} ${score.toFixed(0)} of 100`}>
      {label} {score.toFixed(0)}/100
    </ToneBadge>
  );
}

export function StatusSteps({ steps, label }: { steps: TimelineStep[]; label: string }) {
  return (
    <ol className="status-steps" aria-label={label}>
      {steps.map((s) => (
        <li key={s.status} className={`step-${s.state}`} aria-current={s.state === 'current' ? 'step' : undefined}>
          <span>{humanise(s.status)}</span>
          {s.at && <span className="small"> · {formatDate(s.at)}</span>}
          {s.by && <span className="small muted"> · {s.by}</span>}
          <span className="visually-hidden"> ({s.state === 'done' ? 'completed' : s.state === 'current' ? 'current' : s.state === 'stopped' ? 'ended' : 'not yet'})</span>
        </li>
      ))}
    </ol>
  );
}

/** 1–5 rating as radio buttons (keyboard accessible). */
export function StarRating({ value, onChange, name, legend, disabled }: { value: number; onChange: (n: number) => void; name: string; legend: string; disabled?: boolean }) {
  return (
    <fieldset className="stars" disabled={disabled}>
      <legend className="visually-hidden">{legend}</legend>
      {[1, 2, 3, 4, 5].map((n) => (
        <label key={n} title={`${n} of 5`}>
          <input className="visually-hidden" type="radio" name={name} value={n} checked={value === n} onChange={() => onChange(n)} />
          <span aria-hidden="true">{n <= value ? '★' : '☆'}</span>
          <span className="visually-hidden">{n} of 5</span>
        </label>
      ))}
    </fieldset>
  );
}

export const Stars = ({ value }: { value?: number | null }) =>
  value ? (
    <span title={`${value} of 5`}>
      <span aria-hidden="true">{'★'.repeat(value)}{'☆'.repeat(Math.max(0, 5 - value))}</span>
      <span className="visually-hidden">{value} of 5</span>
    </span>
  ) : (
    <span className="muted small">Not rated</span>
  );

export interface ChatLine {
  id: string;
  sender: string;
  senderRole?: string;
  text: string;
  sentAt: string;
}

/**
 * Secure message thread (telehealth chat, claim messages). `hint` explains what may be written (claims messages are
 * screened for clinical content – a 422 is shown inline).
 */
export function MessageThread({
  load,
  send,
  onSessionExpired,
  me,
  label,
  hint,
  max = 2000,
  canSend = true,
  children,
}: {
  load: () => Promise<ChatLine[]>;
  send: (text: string) => Promise<unknown>;
  onSessionExpired: () => void;
  me?: string;
  label: string;
  hint?: string;
  max?: number;
  canSend?: boolean;
  children?: ReactNode;
}) {
  const list = useAsync(load, onSessionExpired);
  const action = useAction(onSessionExpired);
  const [text, setText] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    const ok = await action.run(async () => {
      await send(text.trim());
    });
    if (ok) {
      setText('');
      list.reload();
    }
  }

  const rows = list.data ?? [];
  return (
    <section aria-label={label}>
      {children}
      {list.loading && !list.data && <Loading label="Loading messages…" />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No messages yet.</Empty>}
      {rows.length > 0 && (
        <ol className="chat-log" aria-live="polite">
          {rows.map((m) => (
            <li key={m.id} className={me && m.sender === me ? 'chat-mine' : undefined}>
              <span className="small muted">
                {m.sender}
                {m.senderRole ? ` (${humanise(m.senderRole)})` : ''} · {formatDate(m.sentAt)}
              </span>
              <br />
              <span className="chat-text">{m.text}</span>
            </li>
          ))}
        </ol>
      )}
      {canSend && (
        <form className="form" onSubmit={submit}>
          <Field label="Message" hint={hint}>
            {(id) => <textarea id={id} rows={2} maxLength={max} value={text} onChange={(e) => setText(e.target.value)} />}
          </Field>
          <div className="actions">
            <button type="submit" disabled={action.busy || !text.trim()}>Send</button>
            <button type="button" className="secondary" onClick={list.reload}>Refresh</button>
          </div>
        </form>
      )}
      <ActionFeedback error={action.error} />
    </section>
  );
}
