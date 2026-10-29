import { useState, type FormEvent } from 'react';
import { recordApi, type AiDraft, type Soap } from '../../api/record';
import { ApiError, type AuthClient } from '../../auth/AuthClient';
import { formatDate, humanise } from '../../components/format';
import { ActionFeedback, Alert } from '../../components/ui';
import { useAction } from '../../components/useAsync';

export const DEFAULT_AI_CONSENT =
  'The employee was told that an AI documentation assistant will draft this note from the consultation, that the clinician reviews and signs the note, and agreed verbally.';

export const isAiNotConfigured = (e: unknown): boolean => e instanceof ApiError && e.status === 503 && e.problem?.code === 'ai_not_configured';

/**
 * E03-F07 AI documentation assistant (optional): consent capture → draft request. Drafts are never saved
 * automatically; inserting any part marks the note AI-assisted (saved with the next "Save draft").
 */
export function AiAssistantPanel({ client, onSessionExpired, encounterId, onInsert }: { client: AuthClient; onSessionExpired: () => void; encounterId: string; onInsert: (soap: Soap) => void }) {
  const api = recordApi(client);
  const action = useAction(onSessionExpired);
  const [consented, setConsented] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [statement, setStatement] = useState(DEFAULT_AI_CONSENT);
  const [transcript, setTranscript] = useState('');
  const [draft, setDraft] = useState<AiDraft>();
  const [notConfigured, setNotConfigured] = useState(false);

  async function run(fn: () => Promise<string | void>) {
    let unavailable = false;
    await action.run(async () => {
      try {
        return await fn();
      } catch (e) {
        if (isAiNotConfigured(e)) {
          unavailable = true;
          return;
        }
        throw e;
      }
    });
    if (unavailable) setNotConfigured(true);
  }

  async function recordConsent(e: FormEvent) {
    e.preventDefault();
    if (!confirm || !statement.trim()) return;
    await run(async () => {
      await api.aiConsent(encounterId, statement.trim());
      setConsented(true);
      return 'Consent recorded.';
    });
  }

  async function requestDraft(e: FormEvent) {
    e.preventDefault();
    if (!transcript.trim()) return;
    await run(async () => {
      setDraft(await api.aiDraft(encounterId, transcript.trim()));
    });
  }

  if (notConfigured)
    return (
      <section className="tile ai-panel" aria-labelledby="ai-title">
        <h2 id="ai-title">AI documentation assistant</h2>
        <Alert kind="info">
          <strong>AI assistant not configured.</strong> No AI documentation provider is enabled in this environment. Document the encounter manually.
        </Alert>
      </section>
    );

  const soap = draft?.draft.soap;
  return (
    <section className="tile ai-panel" aria-labelledby="ai-title">
      <h2 id="ai-title">AI documentation assistant</h2>
      <p className="small muted">Optional. The draft is a suggestion: review every word; nothing is saved until you save the note, and the note is then flagged as AI-assisted.</p>
      {!consented ? (
        <form className="form" onSubmit={recordConsent}>
          <div className="field">
            <label htmlFor="ai-statement">Consent statement read to the employee</label>
            <textarea id="ai-statement" rows={3} maxLength={1000} value={statement} onChange={(e) => setStatement(e.target.value)} />
          </div>
          <label className="checkbox">
            <input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> The employee gave verbal consent to AI-assisted documentation
          </label>
          <button type="submit" className="secondary" disabled={!confirm || !statement.trim() || action.busy}>
            Record consent
          </button>
        </form>
      ) : (
        <form className="form" onSubmit={requestDraft}>
          <div className="field">
            <label htmlFor="ai-transcript">Consultation transcript or dictation</label>
            <textarea id="ai-transcript" rows={5} maxLength={20000} value={transcript} onChange={(e) => setTranscript(e.target.value)} />
          </div>
          <button type="submit" className="secondary" disabled={!transcript.trim() || action.busy}>
            {action.busy ? 'Generating draft…' : 'Request draft'}
          </button>
        </form>
      )}
      <ActionFeedback error={action.error} message={action.message} />
      {soap && (
        <div className="ai-draft" aria-live="polite">
          <h3>
            Draft <span className="badge badge-ai">AI draft – not saved</span>
          </h3>
          <p className="small muted">
            {draft?.provider ? `${draft.provider} · ` : ''}
            {formatDate(draft?.generatedAt)}
          </p>
          <dl className="details">
            {(['subjective', 'objective', 'assessment', 'plan'] as const).map((k) =>
              soap[k] ? (
                <div className="details-row" key={k}>
                  <dt>{humanise(k)}</dt>
                  <dd className="prewrap">{soap[k]}</dd>
                </div>
              ) : null,
            )}
          </dl>
          <div className="actions">
            <button type="button" onClick={() => onInsert(soap)}>
              Insert draft into SOAP note
            </button>
            <button type="button" className="secondary" onClick={() => setDraft(undefined)}>
              Discard draft
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
