package com.wellnessz.e34.identity;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Enrolment attempts per source address: at most 10 in 15 minutes (contract §15.2, 429 beyond). */
@Component
public class EnrolmentThrottle {

    static final int MAX_ATTEMPTS = 10;
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public EnrolmentThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Records an attempt; {@code false} when the address exceeded the budget. */
    public boolean tryAcquire(String address) {
        String key = address == null ? "unknown" : address;
        long now = clock.millis();
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW_MILLIS;
                }
            });
        }
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MILLIS) {
                q.pollFirst();
            }
            if (q.size() >= MAX_ATTEMPTS) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    /** Test / operations hook: forget the attempts of an address. */
    public void reset(String address) {
        attempts.remove(address == null ? "unknown" : address);
    }
}
