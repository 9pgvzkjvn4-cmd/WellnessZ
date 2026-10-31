package com.wellnessz.e32.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Diagnostics and masked trace (E32-HLR-09, E32-F09, contract §13.10): live trace, payload excerpt, parse / mapping outcome,
 * time offset and connector health through {@link DeviceDiagnostics} (E31), PHI-masked twice (engine + {@link TraceMasker});
 * "re-send last result" audited. No access to the clinical record from these roles.
 */
@Service("e32DiagnosticsService")
public class DiagnosticsService {

    private final E32Support support;
    private final ConfigurationStore configurations;

    public DiagnosticsService(E32Support support, ConfigurationStore configurations) {
        this.support = support;
        this.configurations = configurations;
    }

    private Configuration instance(String id) {
        return configurations.live(id).orElseThrow(() -> ApiException.notFound("Device instance"));
    }

    @Transactional
    public Map<String, Object> trace(CurrentUser user, String instanceId, int limit) {
        support.requireEnabled();
        Configuration c = instance(instanceId);
        int n = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DeviceDiagnostics.TraceEntry e : support.spis().diagnostics().trace(instanceId, n)) {
            entries.add(Docs.of("messageId", e.messageId() == null ? null : e.messageId().toString(), "receivedAt", e.receivedAt(), "stage", e.stage(),
                    "outcome", e.outcome(), "code", e.code(), "maskedExcerpt", TraceMasker.mask(e.maskedExcerpt()),
                    "mappingOutcome", TraceMasker.mask(e.mappingOutcome()), "clockOffsetMillis", e.clockOffsetMillis()));
        }
        support.audit("DEVICE_TRACE_VIEW", "DEVICE_INSTANCE", instanceId, entries.size() + " entries");
        return Docs.of("deviceInstanceId", instanceId, "modelCode", c.modelCode(), "option", c.option(), "driver",
                c.driverPackageId() + "@" + c.driverVersion(), "timeZone", Docs.str(c.common().get("timeZone")), "entries", entries);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health(String instanceId) {
        support.requireEnabled();
        Configuration c = instance(instanceId);
        DeviceDiagnostics.ConnectorHealth h = support.spis().diagnostics().health(instanceId);
        return Docs.of("deviceInstanceId", instanceId, "status", c.status(), "state", h == null ? "UNKNOWN" : h.state(),
                "lastSeenAt", h == null ? null : h.lastSeenAt(), "messages24h", h == null ? null : h.messages24h(), "errors24h", h == null ? null : h.errors24h(),
                "p95LatencyMillis", h == null ? null : h.p95LatencyMillis(), "unmatchedOpen", h == null ? null : h.unmatchedOpen(),
                "deadLetters", h == null ? null : h.deadLetters(), "endpointId", c.endpointId());
    }

    @Transactional
    public Map<String, Object> resend(CurrentUser user, String instanceId) {
        support.requireEnabled();
        instance(instanceId);
        UUID messageId = support.spis().diagnostics().resendLast(user.username(), instanceId);
        support.audit("DEVICE_RESEND", "DEVICE_INSTANCE", instanceId);
        return Docs.of("deviceInstanceId", instanceId, "messageId", messageId == null ? null : messageId.toString(), "status", "RESENT");
    }
}
