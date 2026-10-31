package com.wellnessz.e32.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.support.Docs;

class PosturePolicyTest {

    static Map<String, Object> good() {
        return Docs.of("mds2", Docs.of("received", true), "sbom", Docs.of("available", true), "supportStatus", "SUPPORTED", "osSupported", true,
                "patchPath", "VENDOR", "remoteSupport", "BROKERED", "contacts", Docs.of("securityEmail", "psirt@vendor.example"));
    }

    @Test
    void acceptableWhenEveryMandatoryRulePasses() {
        PosturePolicy.Evaluation e = PosturePolicy.evaluate(good());
        assertThat(e.posture()).isEqualTo("ACCEPTABLE");
        assertThat(e.findings()).allMatch(f -> "PASS".equals(f.outcome()));
        PosturePolicy.Evaluation noSbom = PosturePolicy.evaluate(Docs.merge(good(), Docs.of("sbom", Docs.of("available", false))));
        assertThat(noSbom.posture()).isEqualTo("ACCEPTABLE");
        assertThat(noSbom.findings()).anyMatch(f -> "SBOM".equals(f.rule()) && "WARN".equals(f.outcome()));
    }

    @Test
    void unsupportedOsNoPatchPathDirectRemoteOrMissingMds2Block() {
        assertThat(PosturePolicy.evaluate(Docs.merge(good(), Docs.of("osSupported", false))).blocked()).isTrue();
        assertThat(PosturePolicy.evaluate(Docs.merge(good(), Docs.of("patchPath", "NONE"))).blocked()).isTrue();
        assertThat(PosturePolicy.evaluate(Docs.merge(good(), Docs.of("remoteSupport", "DIRECT"))).blocked()).isTrue();
        assertThat(PosturePolicy.evaluate(Docs.merge(good(), Docs.of("mds2", Docs.of("received", false)))).blocked()).isTrue();
        assertThat(PosturePolicy.evaluate(Docs.merge(good(), Docs.of("supportStatus", "END_OF_SUPPORT"))).blocked()).isTrue();
        assertThat(PosturePolicy.evaluate(Map.of()).blocked()).isTrue();
    }

    @Test
    void legacyVendorPcExceptionsNeedGatewayIsolationAndOutboundOnlyAgent() {
        PosturePolicy.Evaluation legacy = PosturePolicy.evaluate(Docs.merge(good(), Docs.of("osSupported", false, "remoteSupport", "DIRECT")));
        assertThat(PosturePolicy.missingControls(legacy, List.of("ENHANCED_MONITORING")))
                .containsExactly(PosturePolicy.CONTROL_GATEWAY_ISOLATION, PosturePolicy.CONTROL_OUTBOUND_AGENT, "VENDOR_BROKERED_REMOTE_ACCESS");
        assertThat(PosturePolicy.missingControls(legacy, List.of(PosturePolicy.CONTROL_GATEWAY_ISOLATION, PosturePolicy.CONTROL_OUTBOUND_AGENT,
                "VENDOR_BROKERED_REMOTE_ACCESS"))).isEmpty();
        assertThat(PosturePolicy.usable("EXCEPTION_APPROVED")).isTrue();
        assertThat(PosturePolicy.usable("NOT_ASSESSED")).isFalse();
    }
}
