package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.ClockSkew;
import com.wellnessz.gateway.identity.ClientCertificate;

/**
 * Application-level hardening self-check reported in every heartbeat ({@code hardening{baseline, status, findings}},
 * contract §16.4; drift raises {@code HARDENING_DRIFT}). OS controls of the CIS image (disk encryption, firewall, EDR,
 * USB policy) are verified by the E34 image pipeline; this check covers what the application can observe itself.
 */
public class HardeningCheck {

    private static final Set<String> PRIVILEGED = Set.of("root", "system", "administrator");

    private final GatewayProperties props;
    private final ClientCertificate certificate;
    private final ClockSkew skew;
    private final Clock clock;
    private final String managementAddress;
    private final Supplier<List<String>> plaintextListeners;

    public HardeningCheck(GatewayProperties props, ClientCertificate certificate, ClockSkew skew, Clock clock,
                          String managementAddress, Supplier<List<String>> plaintextListeners) {
        this.props = props;
        this.certificate = certificate;
        this.skew = skew;
        this.clock = clock;
        this.managementAddress = managementAddress;
        this.plaintextListeners = plaintextListeners;
    }

    public Map<String, Object> run() {
        List<String> findings = new ArrayList<>();
        boolean fail = false;
        if (PRIVILEGED.contains(System.getProperty("user.name", "").toLowerCase(Locale.ROOT))) {
            findings.add("RUNS_AS_PRIVILEGED_ACCOUNT");
            fail = true;
        }
        Path keyFile = props.dataDir().resolve("keys").resolve("gateway.key");
        if (Files.exists(keyFile) && !ownerOnly(keyFile)) {
            findings.add("KEY_FILE_PERMISSIONS");
        }
        if (!certificate.present()) {
            findings.add("NO_CLIENT_CERTIFICATE");
        } else if (certificate.notAfter() != null
                && certificate.notAfter().isBefore(clock.instant().plus(Duration.ofDays(30)))) {
            findings.add("CERTIFICATE_EXPIRING");
        }
        if (props.tls().simulateIngress()) {
            findings.add("INGRESS_SIMULATION_ENABLED");
        }
        if (!props.coreUrl().startsWith("https://")) {
            findings.add("CORE_URL_NOT_TLS");
        }
        for (String l : plaintextListeners.get()) {
            findings.add("PLAINTEXT_LISTENER_EXCEPTION:" + l);
        }
        if (skew.measured() && Math.abs(skew.offsetMillis()) > 2000) {
            findings.add("CLOCK_SKEW");
        }
        if (diskFreePct() < 10) {
            findings.add("DISK_SPACE_LOW");
        }
        if (!loopback(managementAddress)) {
            findings.add("MANAGEMENT_PORT_NOT_LOCALHOST");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", props.heartbeat().baseline());
        out.put("status", fail ? "FAIL" : findings.isEmpty() ? "PASS" : "DRIFT");
        out.put("findings", findings);
        return out;
    }

    public double diskFreePct() {
        try {
            var store = Files.getFileStore(Files.createDirectories(props.dataDir()));
            long total = store.getTotalSpace();
            return total == 0 ? 100 : Math.round(store.getUsableSpace() * 1000.0 / total) / 10.0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean ownerOnly(Path file) {
        PosixFileAttributeView v = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (v == null) {
            return true; // Windows: owner-only ACL set at creation
        }
        try {
            Set<PosixFilePermission> perms = v.readAttributes().permissions();
            return perms.stream().noneMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
