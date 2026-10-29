package com.wellnessz.security.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.wellnessz.common.web.CorrelationIdFilter;
import com.wellnessz.common.web.LogSanitizer;

/**
 * Writes tamper-evident audit records. Runs in its own transaction so that denied or failed operations are
 * still audited even when the business transaction rolls back. A PostgreSQL advisory lock serialises
 * writers across all application replicas so the hash chain stays linear.
 */
@Service
public class AuditService {

    static final String GENESIS = "0".repeat(64);
    private static final long CHAIN_LOCK_KEY = 0x5754_4155_4449_5401L; // "WZAUDIT"

    private final AuditEventRepository repository;
    private final EntityManager entityManager;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public enum Outcome { SUCCESS, DENIED, FAILURE }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, Outcome outcome, String reason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
        String roles = auth == null ? "" : String.join(",", new TreeSet<>(auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList()));
        recordAs(actor, roles, action, resourceType, resourceId, outcome, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(String actor, String roles, String action, String resourceType, String resourceId,
                         Outcome outcome, String reason) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:k)").setParameter("k", CHAIN_LOCK_KEY).getSingleResult();
        // truncate: PostgreSQL rounds timestamps to microseconds, which could change the hashed millisecond on re-read
        AuditEvent event = new AuditEvent(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), trim(actor, 200), trim(roles, 500), trim(action, 100),
                trim(resourceType, 100), trim(resourceId, 200), outcome.name(),
                reason == null ? null : trim(LogSanitizer.clean(reason), 500),
                CorrelationIdFilter.current(), clientIp());
        String prev = repository.findTopByOrderByIdDesc().map(AuditEvent::getHash).orElse(GENESIS);
        event.chain(prev, sha256(prev + "|" + event.canonical()));
        repository.save(event);
    }

    /** Re-computes the whole chain; returns the id of the first broken record, or {@code -1} if intact. */
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        String prev = GENESIS;
        long lastId = 0;
        long checked = 0;
        while (true) {
            List<AuditEvent> batch = repository.findChainAfter(lastId, PageRequest.of(0, 1000));
            if (batch.isEmpty()) {
                return new ChainVerification(true, checked, -1);
            }
            for (AuditEvent e : batch) {
                String expected = sha256(prev + "|" + e.canonical());
                if (!e.getPrevHash().equals(prev) || !e.getHash().equals(expected)) {
                    return new ChainVerification(false, checked, e.getId());
                }
                prev = e.getHash();
                lastId = e.getId();
                checked++;
            }
        }
    }

    public record ChainVerification(boolean intact, long recordsChecked, long firstBrokenId) {
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            // X-Forwarded-For is honoured via server.forward-headers-strategy=framework behind the trusted ingress
            return req.getRemoteAddr();
        }
        return null;
    }
}
