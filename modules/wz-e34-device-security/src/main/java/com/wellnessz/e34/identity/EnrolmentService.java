package com.wellnessz.e34.identity;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointStatus;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.CertificateVerifier.CertificateInfo;
import com.wellnessz.e34.identity.CertificateVerifier.PkiException;
import com.wellnessz.e34.identity.EndpointRepository.Endpoint;
import com.wellnessz.e34.identity.EndpointRepository.EnrolmentToken;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.security.audit.AuditService;

/**
 * Zero-touch enrolment and certificate renewal (contract §15.2, E34-BL-001 / -014). The one-time enrolment token (single use,
 * unexpired, kind matches) is exchanged – together with a group-PKI client certificate whose thumbprint the mTLS ingress
 * forwarded, a valid chain, {@code clientAuth} usage and a proof of possession – for the per-endpoint token. Every failure is a
 * security event {@code ENROLMENT_FAILED}; attempts are throttled per source address.
 */
@Service
public class EnrolmentService {

    public record EnrolCommand(String enrolmentToken, String endpointKind, String certificatePem, String proof, String hostname, String os,
                               String osVersion, String agentVersion, String hardeningBaseline, String hardeningStatus, String attestation) {

        @Override
        public String toString() {
            return "EnrolCommand[kind=" + endpointKind + "]";
        }
    }

    /** @param endpointToken shown once */
    public record Enrolled(String endpointId, String endpointToken, Instant tokenExpiresAt, String configUrl, Instant serverTime) {

        @Override
        public String toString() {
            return "Enrolled[" + endpointId + "]";
        }
    }

    public record Renewed(String endpointId, EndpointService.CertificateView certificate) {
    }

    private final EndpointRepository repo;
    private final CertificateVerifier pki;
    private final TokenHasher hasher;
    private final EnrolmentThrottle throttle;
    private final SecurityEventService events;
    private final AuditService audit;
    private final E34Settings settings;
    private final TransactionTemplate tx;

    public EnrolmentService(EndpointRepository repo, CertificateVerifier pki, TokenHasher hasher, EnrolmentThrottle throttle,
                            SecurityEventService events, AuditService audit, E34Settings settings, PlatformTransactionManager tm) {
        this.repo = repo;
        this.pki = pki;
        this.hasher = hasher;
        this.throttle = throttle;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
        this.tx = new TransactionTemplate(tm);
    }

    public Enrolled enrol(EnrolCommand cmd, String headerThumbprint, String remoteAddress) {
        settings.requireEnabled();
        if (!throttle.tryAcquire(remoteAddress)) {
            throw fail(null, remoteAddress, HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (cmd == null || blank(cmd.enrolmentToken()) || cmd.enrolmentToken().length() > 128 || blank(cmd.endpointKind())
                || blank(cmd.certificatePem()) || blank(cmd.proof()) || tooLong(cmd)) {
            throw fail(null, remoteAddress, HttpStatus.BAD_REQUEST, "enrolment_invalid");
        }
        DeviceCodes.EndpointKind kind;
        try {
            kind = DeviceCodes.EndpointKind.valueOf(cmd.endpointKind());
        } catch (IllegalArgumentException e) {
            throw fail(null, remoteAddress, HttpStatus.BAD_REQUEST, "enrolment_invalid");
        }
        String thumbprint = normalise(headerThumbprint);
        if (thumbprint == null) {
            throw fail(null, remoteAddress, HttpStatus.UNAUTHORIZED, "mtls_required");
        }
        String tokenHash = hasher.hash(cmd.enrolmentToken());
        Instant now = settings.now();
        Outcome outcome = tx.execute(s -> {
            Optional<EnrolmentToken> t = repo.findEnrolmentTokenForUpdate(tokenHash);
            if (t.isEmpty() || t.get().usedAt() != null || t.get().revokedAt() != null) {
                return Outcome.failed(t.map(EnrolmentToken::endpointId).orElse(null), HttpStatus.UNAUTHORIZED, "enrolment_token_invalid");
            }
            EnrolmentToken token = t.get();
            if (!now.isBefore(token.expiresAt())) {
                return Outcome.failed(token.endpointId(), HttpStatus.UNAUTHORIZED, "enrolment_token_expired");
            }
            if (!token.kind().equals(kind.name())) {
                return Outcome.failed(token.endpointId(), HttpStatus.UNAUTHORIZED, "enrolment_token_invalid");
            }
            Endpoint ep = repo.findForUpdate(token.endpointId()).orElse(null);
            if (ep == null || ep.status() != EndpointStatus.PENDING_ENROLMENT) {
                return Outcome.failed(token.endpointId(), HttpStatus.CONFLICT, "already_enrolled");
            }
            CertificateInfo info;
            try {
                List<X509Certificate> chain = pki.parseChain(cmd.certificatePem());
                X509Certificate leaf = chain.get(0);
                info = CertificateVerifier.info(leaf);
                if (!TokenHasher.constantTimeEquals(info.sha256(), thumbprint)) {
                    return Outcome.failed(ep.endpointId(), HttpStatus.UNPROCESSABLE_CONTENT, "thumbprint_mismatch");
                }
                pki.validateChain(chain, now);
                CertificateVerifier.requireClientAuth(leaf);
                CertificateVerifier.verifyProof(leaf.getPublicKey(), "enrol:" + cmd.enrolmentToken() + ":" + thumbprint, cmd.proof());
            } catch (PkiException e) {
                HttpStatus status = "enrolment_invalid".equals(e.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_CONTENT;
                return Outcome.failed(ep.endpointId(), status, e.code());
            }
            String endpointToken = hasher.newToken();
            Instant expires = now.plus(settings.endpointTokenDays(), ChronoUnit.DAYS);
            repo.markEnrolmentTokenUsed(token.id(), now, remoteAddress == null ? null : remoteAddress.substring(0, Math.min(64, remoteAddress.length())));
            repo.bindEnrolment(ep.endpointId(), info, hasher.hash(endpointToken), now, expires, cap(cmd.hostname(), 200), cap(cmd.os(), 100),
                    cap(cmd.osVersion(), 100), cap(cmd.agentVersion(), 60), cap(cmd.hardeningBaseline(), 100), cap(cmd.hardeningStatus(), 20),
                    !blank(cmd.attestation()));
            return Outcome.ok(new Enrolled(ep.endpointId(), endpointToken, expires, "/api/v1/device-ingest/config", now), info);
        });
        if (outcome == null || outcome.enrolled() == null) {
            String code = outcome == null ? "enrolment_invalid" : outcome.code();
            throw fail(outcome == null ? null : outcome.endpointId(), remoteAddress,
                    outcome == null ? HttpStatus.BAD_REQUEST : outcome.status(), code);
        }
        String endpointId = outcome.enrolled().endpointId();
        audit.recordAs("device:" + endpointId, "", "DEVICE_ENROL", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS,
                "certificate " + outcome.info().serial());
        Map<String, String> attrs = new HashMap<>();
        attrs.put("remoteAddress", String.valueOf(remoteAddress));
        attrs.put("certificateSerial", outcome.info().serial());
        attrs.put("thumbprint", outcome.info().sha256().substring(0, 16));
        if (cmd.hardeningStatus() != null) {
            attrs.put("status", cap(cmd.hardeningStatus(), 20));
        }
        events.record("ENROLMENT_SUCCEEDED", DeviceCodes.Severity.INFO, endpointId, null, "device:" + endpointId, null, attrs);
        if ("DRIFT".equals(cmd.hardeningStatus()) || "FAIL".equals(cmd.hardeningStatus())) {
            events.record("HARDENING_DRIFT", DeviceCodes.Severity.MEDIUM, endpointId, null, "device:" + endpointId, "enrolment_hardening",
                    Map.of("baseline", String.valueOf(cap(cmd.hardeningBaseline(), 100)), "status", cmd.hardeningStatus()));
        }
        return outcome.enrolled();
    }

    /** {@code POST /device-ingest/certificate-renewal}: binds the new thumbprint (authenticated with the old certificate + token). */
    public Renewed renew(DeviceEndpointPrincipal principal, String certificatePem, String proof) {
        settings.requireEnabled();
        String endpointId = principal.endpointId();
        if (blank(certificatePem) || blank(proof)) {
            throw ApiException.badRequest("enrolment_invalid", "Certificate and proof are required");
        }
        CertificateInfo info;
        try {
            List<X509Certificate> chain = pki.parseChain(certificatePem);
            X509Certificate leaf = chain.get(0);
            info = CertificateVerifier.info(leaf);
            pki.validateChain(chain, settings.now());
            CertificateVerifier.requireClientAuth(leaf);
            CertificateVerifier.verifyProof(leaf.getPublicKey(), "renew:" + endpointId + ":" + info.sha256(), proof);
        } catch (PkiException e) {
            events.record("ENROLMENT_FAILED", DeviceCodes.Severity.MEDIUM, endpointId, null, principal.authenticationName(), e.code(),
                    Map.of("outcome", "RENEWAL_FAILED"));
            throw new ApiException("enrolment_invalid".equals(e.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_CONTENT, e.code(),
                    "Certificate renewal refused");
        }
        if (info.sha256().equals(principal.certificateSha256())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "thumbprint_mismatch", "The new certificate must differ from the current one");
        }
        tx.executeWithoutResult(s -> repo.bindCertificate(endpointId, info));
        audit.recordAs(principal.authenticationName(), "", "DEVICE_ENROL", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS,
                "certificate renewal " + info.serial());
        events.record("CERTIFICATE_RENEWED", DeviceCodes.Severity.INFO, endpointId, null, principal.authenticationName(), null,
                Map.of("certificateSerial", info.serial(), "previousThumbprint", principal.certificateSha256().substring(0, 16),
                        "thumbprint", info.sha256().substring(0, 16), "outcome", "SUCCEEDED"));
        return new Renewed(endpointId, new EndpointService.CertificateView(info.sha256(), info.subject(), info.issuer(), info.serial(),
                info.notBefore(), info.notAfter()));
    }

    private record Outcome(Enrolled enrolled, CertificateInfo info, String endpointId, HttpStatus status, String code) {

        static Outcome ok(Enrolled e, CertificateInfo info) {
            return new Outcome(e, info, e.endpointId(), HttpStatus.CREATED, null);
        }

        static Outcome failed(String endpointId, HttpStatus status, String code) {
            return new Outcome(null, null, endpointId, status, code);
        }
    }

    private ApiException fail(String endpointId, String remoteAddress, HttpStatus status, String code) {
        try {
            events.record("ENROLMENT_FAILED", DeviceCodes.Severity.MEDIUM, endpointId, null, endpointId == null ? "anonymous" : "device:" + endpointId,
                    code, Map.of("remoteAddress", String.valueOf(remoteAddress)));
        } catch (RuntimeException e) {
            // the refusal itself must not depend on the event store
        }
        return new ApiException(status, code, "Enrolment refused");
    }

    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.replace(":", "").strip().toLowerCase(java.util.Locale.ROOT);
        return v.matches("[0-9a-f]{64}") ? v : null;
    }

    private static boolean tooLong(EnrolCommand c) {
        return len(c.hostname()) > 200 || len(c.os()) > 100 || len(c.osVersion()) > 100 || len(c.agentVersion()) > 60
                || len(c.hardeningBaseline()) > 100 || len(c.hardeningStatus()) > 20 || len(c.attestation()) > 16_384;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cap(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
