package com.wellnessz.e32.catalogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wellnessz.e32.support.Docs;

/**
 * Vendor security posture policy (E32-HLR-06, E34-HLR-07, contract §13.8): unsupported OS, no patch path, direct vendor
 * remote access, end of support or a missing MDS2 → {@code BLOCKED}; SBOM and security contacts are recommended (warnings).
 * A blocked model may be connected only with an approved exception; for legacy vendor PCs the compensating controls must
 * include isolation behind a gateway in zone {@code DEVICE_VLAN} and an outbound-only Device Host agent.
 */
public final class PosturePolicy {

    public static final String CONTROL_GATEWAY_ISOLATION = "GATEWAY_ISOLATION_DEVICE_VLAN";
    public static final String CONTROL_OUTBOUND_AGENT = "OUTBOUND_ONLY_DEVICE_HOST_AGENT";
    public static final Set<String> KNOWN_CONTROLS = Set.of(CONTROL_GATEWAY_ISOLATION, CONTROL_OUTBOUND_AGENT, "NO_DIRECT_REMOTE_ACCESS",
            "VENDOR_BROKERED_REMOTE_ACCESS", "APPLICATION_ALLOW_LIST", "ENHANCED_MONITORING", "REMOVABLE_MEDIA_BLOCKED", "LOCAL_ACCOUNTS_DISABLED");

    private PosturePolicy() {
    }

    public record Finding(String rule, String outcome, String message) {
    }

    public record Evaluation(String posture, List<Finding> findings) {

        public Evaluation {
            findings = List.copyOf(findings);
        }

        public boolean blocked() {
            return "BLOCKED".equals(posture);
        }

        public boolean legacyPc() {
            return findings.stream().anyMatch(f -> "OS_SUPPORTED".equals(f.rule()) && "FAIL".equals(f.outcome()));
        }
    }

    public static Evaluation evaluate(Map<String, Object> intake) {
        List<Finding> f = new ArrayList<>();
        boolean mds2 = Docs.bool(Docs.at(intake, "mds2.received"), false);
        f.add(new Finding("MDS2_RECEIVED", mds2 ? "PASS" : "FAIL", mds2 ? "MDS2 (ANSI/NEMA HN 1-2019) on file" : "No MDS2 received"));
        Object osSupported = intake.get("osSupported");
        boolean osOk = osSupported == null || Docs.bool(osSupported, true);
        f.add(new Finding("OS_SUPPORTED", osOk ? "PASS" : "FAIL", osOk ? "Operating system supported" : "Unsupported operating system"));
        String patch = Docs.str(intake.get("patchPath"));
        boolean patchOk = patch != null && !"NONE".equals(patch);
        f.add(new Finding("PATCH_PATH", patchOk ? "PASS" : "FAIL", patchOk ? "Patch path " + patch : "No patching path"));
        String remote = Docs.str(intake.get("remoteSupport"));
        boolean remoteOk = !"DIRECT".equals(remote);
        f.add(new Finding("REMOTE_SUPPORT", remoteOk ? "PASS" : "FAIL", remoteOk ? "No direct vendor remote access" : "Direct vendor remote access"));
        String support = Docs.str(intake.get("supportStatus"));
        boolean supportOk = !"END_OF_SUPPORT".equals(support);
        f.add(new Finding("SUPPORT_STATUS", supportOk ? "PASS" : "FAIL", supportOk ? "Vendor support current or unknown" : "Model is end of support"));
        boolean sbom = Docs.bool(Docs.at(intake, "sbom.available"), false);
        f.add(new Finding("SBOM", sbom ? "PASS" : "WARN", sbom ? "SBOM available" : "No SBOM (FDA §524B / IEC 81001-5-1 recommend one)"));
        boolean contact = Docs.str(Docs.at(intake, "contacts.securityEmail")) != null || Docs.str(Docs.at(intake, "contacts.disclosureUrl")) != null;
        f.add(new Finding("SECURITY_CONTACT", contact ? "PASS" : "WARN", contact ? "Security contact / disclosure channel recorded"
                : "No security contact or vulnerability disclosure channel"));
        boolean blocked = f.stream().anyMatch(x -> "FAIL".equals(x.outcome()));
        return new Evaluation(blocked ? "BLOCKED" : "ACCEPTABLE", f);
    }

    /** Missing mandatory compensating controls for an exception request (empty = acceptable request). */
    public static List<String> missingControls(Evaluation evaluation, List<String> controls) {
        List<String> missing = new ArrayList<>();
        if (evaluation.legacyPc()) {
            if (controls.stream().noneMatch(c -> c.contains(CONTROL_GATEWAY_ISOLATION))) {
                missing.add(CONTROL_GATEWAY_ISOLATION);
            }
            if (controls.stream().noneMatch(c -> c.contains(CONTROL_OUTBOUND_AGENT))) {
                missing.add(CONTROL_OUTBOUND_AGENT);
            }
        }
        if (evaluation.findings().stream().anyMatch(x -> "REMOTE_SUPPORT".equals(x.rule()) && "FAIL".equals(x.outcome()))
                && controls.stream().noneMatch(c -> c.contains("NO_DIRECT_REMOTE_ACCESS") || c.contains("VENDOR_BROKERED_REMOTE_ACCESS"))) {
            missing.add("VENDOR_BROKERED_REMOTE_ACCESS");
        }
        return missing;
    }

    /** A model may be used / certified when acceptable or covered by an approved exception. */
    public static boolean usable(String posture) {
        return "ACCEPTABLE".equals(posture) || "EXCEPTION_APPROVED".equals(posture);
    }
}
