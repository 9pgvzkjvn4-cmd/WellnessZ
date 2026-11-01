package com.wellnessz.e34;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointStatus;
import com.wellnessz.e34.identity.CertificateVerifier;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.TokenHasher;

/**
 * Local / test profiles only: the demo endpoint identities of contract §19 with <b>test-only</b> tokens and demo certificate
 * thumbprints (the dev stack has no mTLS ingress, so the E2E script and the tests send the thumbprint header themselves). The
 * token hashes depend on the environment's pepper, which is why they are written here and not in the repeatable SQL.
 *
 * <p>Demo tokens (documented non-secrets, local stack only): {@code wzdemo-<endpoint id in lower case>-not-a-secret}, e.g.
 * {@code wzdemo-ep-lhr-gw-01-not-a-secret}; demo thumbprint: {@code SHA-256("wellnessz-demo-certificate:" + endpointId)} in hex.
 * The pending endpoint {@code EP-LHR-GW-02} has the one-time enrolment token {@code wzdemo-enrol-ep-lhr-gw-02-not-a-secret}.
 * {@code EP-DXB-GW-01} is ISOLATED (its device {@code DEV-000008} is QUARANTINED in the console); the kiosk certificate of
 * {@code EP-LHR-KSK-01} expires in 20 days (certificate-expiry demo).</p>
 */
@Component
@Profile({"local", "test"})
public class E34DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(E34DemoSeeder.class);

    record DemoEndpoint(String id, EndpointKind kind, String site, String zone, List<String> instances, int certDays) {
    }

    static final List<DemoEndpoint> ENDPOINTS = List.of(
            new DemoEndpoint("EP-LHR-GW-01", EndpointKind.GATEWAY, "LHR", "GATEWAY_ZONE", List.of("DEV-000003", "DEV-000006"), 365),
            new DemoEndpoint("EP-LHR-AGT-01", EndpointKind.DEVICE_HOST_AGENT, "LHR", "GATEWAY_ZONE", List.of("DEV-000001", "DEV-000002", "DEV-000005"), 365),
            new DemoEndpoint("EP-LHR-TAB-01", EndpointKind.MOBILE_APP, "LHR", "CLINIC_LAN", List.of("DEV-000004"), 365),
            new DemoEndpoint("EP-LHR-KSK-01", EndpointKind.KIOSK, "LHR", "CLINIC_LAN", List.of(), 20),
            new DemoEndpoint("EP-DXB-GW-01", EndpointKind.GATEWAY, "DXB", "GATEWAY_ZONE", List.of("DEV-000008"), 365));

    /**
     * The Dubai gateway was isolated by the failed-authentication-burst rule; the console demo shows its vital-signs monitor
     * {@code DEV-000008} QUARANTINED for the same reason (E32 demo seed), and release waits for re-certification.
     */
    public static final String ISOLATED_ENDPOINT = "EP-DXB-GW-01";
    static final String ISOLATION_REASON = "ANOMALY_FAILED_AUTH_BURST";

    public static final String PENDING_ENDPOINT = "EP-LHR-GW-02";

    private final JdbcTemplate jdbc;
    private final EndpointRepository repo;
    private final TokenHasher hasher;
    private final TransactionTemplate tx;

    public E34DemoSeeder(JdbcTemplate jdbc, EndpointRepository repo, TokenHasher hasher, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.hasher = hasher;
        this.tx = tx;
    }

    /** The documented demo token of an endpoint (local / test only). */
    public static String demoToken(String endpointId) {
        return "wzdemo-" + endpointId.toLowerCase(Locale.ROOT) + "-not-a-secret";
    }

    public static String demoThumbprint(String endpointId) {
        return TokenHasher.sha256Hex("wellnessz-demo-certificate:" + endpointId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            int created = tx.execute(s -> {
                int n = 0;
                Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                for (DemoEndpoint d : ENDPOINTS) {
                    if (repo.exists(d.id())) {
                        continue;
                    }
                    repo.insert(d.id(), d.kind(), d.site(), d.zone(), "GROUP", EndpointStatus.PENDING_ENROLMENT, null, "demo", now.minus(40, ChronoUnit.DAYS));
                    for (String i : d.instances()) {
                        repo.addInstance(d.id(), i, "demo", now.minus(40, ChronoUnit.DAYS));
                    }
                    CertificateVerifier.CertificateInfo cert = new CertificateVerifier.CertificateInfo(demoThumbprint(d.id()),
                            "CN=" + d.id() + ",OU=Devices,O=WellnessZ Demo", "CN=WellnessZ Dev Device CA,O=WellnessZ Demo",
                            Long.toHexString(Math.abs((long) d.id().hashCode())), now.minus(30, ChronoUnit.DAYS), now.plus(d.certDays(), ChronoUnit.DAYS));
                    repo.bindEnrolment(d.id(), cert, hasher.hash(demoToken(d.id())), now.minus(30, ChronoUnit.DAYS), now.plus(60, ChronoUnit.DAYS),
                            d.id().toLowerCase(Locale.ROOT) + ".demo.local", d.kind() == EndpointKind.DEVICE_HOST_AGENT ? "Windows" : "Linux",
                            d.kind() == EndpointKind.DEVICE_HOST_AGENT ? "10 IoT LTSC" : "Debian 12", "0.6.0", "CIS-L1-2026.1", "PASS", false);
                    jdbc.update("update devicesec.endpoint set status_reason = 'DEMO', created_by = 'demo' where endpoint_id = ?", d.id());
                    if (ISOLATED_ENDPOINT.equals(d.id())) {
                        Instant isolated = now.minus(2, ChronoUnit.DAYS);
                        repo.setStatus(d.id(), EndpointStatus.ISOLATED, ISOLATION_REASON, isolated);
                        jdbc.update("insert into devicesec.isolation (id, endpoint_id, reason_code, actor, detected_at, isolated_at) values (?,?,?,?,?,?)",
                                UUID.randomUUID(), d.id(), ISOLATION_REASON, "system:anomaly", EndpointRepository.ts(isolated.minus(4, ChronoUnit.MINUTES)),
                                EndpointRepository.ts(isolated));
                    }
                    n++;
                }
                if (!repo.exists(PENDING_ENDPOINT)) {
                    repo.insert(PENDING_ENDPOINT, EndpointKind.GATEWAY, "LHR", "GATEWAY_ZONE", "GROUP", EndpointStatus.PENDING_ENROLMENT, null, "demo", now);
                    repo.insertEnrolmentToken(UUID.randomUUID(), PENDING_ENDPOINT, hasher.hash("wzdemo-enrol-ep-lhr-gw-02-not-a-secret"),
                            EndpointKind.GATEWAY, "demo", now, now.plus(72, ChronoUnit.HOURS));
                    n++;
                }
                return n;
            });
            if (created > 0) {
                log.info("E34 demo endpoints seeded: {}", created);
            }
        } catch (RuntimeException e) {
            log.warn("E34 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }
}
