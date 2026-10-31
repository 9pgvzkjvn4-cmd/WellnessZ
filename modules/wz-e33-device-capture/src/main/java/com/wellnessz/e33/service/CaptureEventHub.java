package com.wellnessz.e33.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live update stream of the capture dialogue (contract 14.2 {@code GET /device-capture/sessions/{id}/events}): Server-Sent
 * Events {@code state}, {@code progress} and {@code preview}. Events carry ids, states, statuses and quality-flag codes only –
 * never values; the dialogue reads the values with {@code GET /device-capture/sessions/{id}} (which is authorised and audited).
 * Events are sent after the transaction that produced them commits. Subscribers are held in memory per instance; with several
 * replicas the dialogue falls back to polling {@code GET …/{id}} every 2 s (the contract's documented fallback).
 */
@Component
public class CaptureEventHub {

    private static final Logger log = LoggerFactory.getLogger(CaptureEventHub.class);

    /** One event: SSE name ({@code state | progress | preview}) and a flat, value-free payload. */
    public record Event(String name, Map<String, Object> data) {
    }

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** Registers a subscriber and immediately sends the initial events (current state, preview if ready). */
    public SseEmitter subscribe(UUID sessionId, long timeoutMillis, List<Event> initial) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        List<SseEmitter> list = emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            emitters.computeIfPresent(sessionId, (k, v) -> v.isEmpty() ? null : v);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        for (Event e : initial) {
            send(emitter, e);
        }
        return emitter;
    }

    /** Publishes after the current transaction commits (immediately when no transaction is active). */
    public void publish(UUID sessionId, Event event) {
        publish(sessionId, event, false);
    }

    /**
     * @param last {@code true} when the session reached a final state: the streams are completed after the event
     */
    public void publish(UUID sessionId, Event event, boolean last) {
        Runnable r = () -> {
            deliver(sessionId, event);
            if (last) {
                complete(sessionId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    /** Ends every stream of a session that reached a final state. */
    public void complete(UUID sessionId) {
        List<SseEmitter> list = emitters.remove(sessionId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }

    public int subscribers(UUID sessionId) {
        return emitters.getOrDefault(sessionId, List.of()).size();
    }

    private void deliver(UUID sessionId, Event event) {
        for (SseEmitter e : emitters.getOrDefault(sessionId, List.of())) {
            send(e, event);
        }
    }

    private void send(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
        } catch (IOException | IllegalStateException ex) {
            log.debug("capture stream closed: {}", ex.getClass().getSimpleName());
            emitter.completeWithError(ex);
        }
    }
}
