package com.wellnessz.e32.spi;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/**
 * {@link DeviceEndpointStatusListener} (contract §13.11 / §15.8): when E34 isolates or revokes an endpoint, every live
 * instance behind it becomes {@code QUARANTINED} (alert + content-free notification to the on-duty administrator). A later
 * release ({@code ACTIVE}) does <b>not</b> return the instance to service – that needs revise → test → approve → publish.
 */
@Component("e32EndpointStatusAdapter")
public class EndpointStatusAdapter implements DeviceEndpointStatusListener {

    private final ConfigurationStore configurations;
    private final E32Support support;

    public EndpointStatusAdapter(ConfigurationStore configurations, E32Support support) {
        this.configurations = configurations;
        this.support = support;
    }

    @Override
    @Transactional
    public void endpointStatusChanged(String endpointId, Set<String> deviceInstanceIds, DeviceCodes.EndpointStatus status, String reasonCode) {
        if (!support.enabled()) {
            return;
        }
        Map<String, Configuration> affected = new LinkedHashMap<>();
        if (endpointId != null) {
            configurations.liveForEndpoint(endpointId).forEach(c -> affected.putIfAbsent(c.deviceInstanceId(), c));
        }
        if (deviceInstanceIds != null) {
            for (String id : deviceInstanceIds) {
                configurations.live(id).ifPresent(c -> affected.putIfAbsent(c.deviceInstanceId(), c));
            }
        }
        for (Configuration c : affected.values()) {
            if (status == DeviceCodes.EndpointStatus.ISOLATED || status == DeviceCodes.EndpointStatus.REVOKED) {
                if ("DECOMMISSIONED".equals(reasonCode) || Configuration.QUARANTINED.equals(c.status())) {
                    continue;
                }
                c.setStatus(Configuration.QUARANTINED);
                c.setStatusReason(reasonCode == null ? status.name() : reasonCode);
                configurations.update(c, "security");
                support.history("CONFIGURATION", c.configurationId(), c.version(), "security", "QUARANTINE", null,
                        Docs.of("endpointId", endpointId, "status", status.name(), "reason", reasonCode), null);
                support.audit("DEVICE_CONFIG_STEP", "DEVICE_CONFIGURATION", c.configurationId(), "quarantined " + status.name());
                int inserted = support.jdbc().update("""
                        insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                        values (?, ?, ?, 'QUARANTINED', 'CRITICAL', ?, ?, 'OPEN', ?) on conflict do nothing""",
                        UUID.randomUUID(), c.deviceInstanceId(), c.siteCode(), "Endpoint " + status.name().toLowerCase(java.util.Locale.ROOT),
                        "QUARANTINED:" + c.deviceInstanceId(), Timestamp.from(support.now()));
                if (inserted > 0) {
                    Map<String, String> p = support.params(endpointId == null ? c.deviceInstanceId() : endpointId, c.siteCode());
                    support.notifyOnDuty(c.siteCode(), "device.endpoint-isolated", p, true);
                }
            } else {
                support.history("CONFIGURATION", c.configurationId(), c.version(), "security", "ENDPOINT_" + status.name(), null,
                        Docs.of("endpointId", endpointId, "reason", reasonCode, "note", "re-certification required before return to service"), null);
            }
        }
    }
}
