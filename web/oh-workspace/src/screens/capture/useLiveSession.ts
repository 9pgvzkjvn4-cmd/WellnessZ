import { useCallback, useEffect, useState } from 'react';
import { SessionExpiredError, type AuthClient } from '../../auth/AuthClient';
import { captureApi, sessionEventsUrl, type CaptureSession } from '../../api/deviceCapture';
import { isFinal } from '../../domain/capture';

export type LiveChannel = 'connecting' | 'live' | 'polling' | 'closed';

export interface LiveSession {
  session?: CaptureSession;
  error?: unknown;
  /** How updates arrive: server-sent events, 2-second polling (fallback) or closed (final state). */
  channel: LiveChannel;
  /** Last progress stage from the stream (e.g. RECEIVING) – cleared on preview / state change. */
  progress?: string;
  refresh: () => Promise<void>;
  /** Replace the session with a response the caller already holds (after an action). */
  set: (s: CaptureSession) => void;
}

interface Options {
  pollMs?: number;
  /** No event within this time after opening the stream → switch to polling (a buffering proxy never delivers). */
  sseTimeoutMs?: number;
  /** Injectable for tests; default `window.EventSource` (absent in jsdom → polling). */
  eventSource?: ((url: string) => EventSource) | null;
}

interface State {
  id?: string;
  session?: CaptureSession;
  error?: unknown;
  channel?: 'live' | 'polling';
  progress?: string;
}

/**
 * Live capture session: `GET /device-capture/sessions/{id}` plus the SSE stream `…/events` (state / progress / preview –
 * value-free; every event triggers an authorised re-read). Falls back to polling every 2 s when the stream is unavailable,
 * errors or stays silent (contract §14.2). Stops when the session reaches a final state.
 */
export function useLiveSession(client: AuthClient, sessionId: string | undefined, onSessionExpired: () => void, opts: Options = {}): LiveSession {
  const pollMs = opts.pollMs ?? 2000;
  const sseTimeoutMs = opts.sseTimeoutMs ?? 3000;
  const factory = opts.eventSource === undefined ? (typeof EventSource === 'undefined' ? null : (u: string) => new EventSource(u, { withCredentials: true })) : opts.eventSource;
  const [st, setSt] = useState<State>({});
  const cur: State = st.id === sessionId ? st : {};
  const patch = useCallback((id: string, p: Partial<State>) => setSt((prev) => (prev.id === id ? { ...prev, ...p } : { id, ...p })), []);

  const load = useCallback(
    (id: string): Promise<void> =>
      captureApi(client).session(id).then(
        (s) => patch(id, { session: s, error: undefined }),
        (e: unknown) => {
          if (e instanceof SessionExpiredError) onSessionExpired();
          patch(id, { error: e });
        },
      ),
    [client, onSessionExpired, patch],
  );

  const refresh = useCallback(async () => {
    if (sessionId) await load(sessionId);
  }, [sessionId, load]);

  const set = useCallback((s: CaptureSession) => patch(s.sessionId, { session: s, progress: undefined }), [patch]);

  useEffect(() => {
    if (sessionId) void load(sessionId);
  }, [sessionId, load]);

  const final = isFinal(cur.session?.state);
  const hasFactory = !!factory;

  // Live stream with polling fallback; re-established when the session changes, torn down on a final state.
  useEffect(() => {
    if (!sessionId || final) return;
    const id = sessionId;
    let stopped = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    let es: EventSource | undefined;
    let silence: ReturnType<typeof setTimeout> | undefined;
    const poll = () => {
      if (stopped || timer) return;
      es?.close();
      es = undefined;
      patch(id, { channel: 'polling' });
      timer = setInterval(() => void load(id), pollMs);
    };
    if (factory) {
      try {
        es = factory(sessionEventsUrl(id));
        const onEvent = (ev: MessageEvent<string>, kind: string) => {
          if (silence) clearTimeout(silence);
          silence = undefined;
          if (kind === 'progress') {
            let stage = 'RECEIVING';
            try {
              const d = JSON.parse(ev.data) as { stage?: unknown };
              if (typeof d.stage === 'string') stage = d.stage;
            } catch {
              /* value-free event; keep the default stage */
            }
            patch(id, { channel: 'live', progress: stage });
          } else {
            patch(id, { channel: 'live', progress: undefined });
            void load(id);
          }
        };
        for (const kind of ['state', 'progress', 'preview']) es.addEventListener(kind, (ev) => onEvent(ev as MessageEvent<string>, kind));
        es.onerror = () => poll();
        silence = setTimeout(poll, sseTimeoutMs);
      } catch {
        silence = setTimeout(poll, 0);
      }
    } else {
      timer = setInterval(() => void load(id), pollMs);
    }
    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
      if (silence) clearTimeout(silence);
      es?.close();
    };
    // the factory is stable per mount (derived from options); intentionally not a dependency
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, final, load, patch, pollMs, sseTimeoutMs]);

  const channel: LiveChannel = final ? 'closed' : !hasFactory ? 'polling' : (cur.channel ?? 'connecting');
  return { session: cur.session, error: cur.error, channel, progress: cur.progress, refresh, set };
}
