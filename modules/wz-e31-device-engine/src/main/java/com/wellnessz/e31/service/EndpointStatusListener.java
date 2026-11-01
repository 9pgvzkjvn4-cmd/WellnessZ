package com.wellnessz.e31.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.e31.store.MessageStore;

/**
 * {@link DeviceEndpointStatusListener} (E34 → E31, contract section 15.8): an isolated / revoked endpoint parks its in-flight
 * messages ({@code ENDPOINT_ISOLATED}) and its instances refuse new messages ({@code device_quarantined}) until released, when
 * the parked messages are retried. A revocation of single instances on a still-active shared endpoint affects only those instances.
 * {@code DeviceSpis} is resolved lazily (it collects the listeners).
 */
@Component("e31EndpointStatusListener")
public class EndpointStatusListener implements DeviceEndpointStatusListener {

    private final MessageStore messages;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectProvider<DeviceSpis> spis;

    public EndpointStatusListener(MessageStore messages, JdbcTemplate jdbc, Clock clock, ObjectProvider<DeviceSpis> spis) {
        this.messages = messages;
        this.jdbc = jdbc;
        this.clock = clock;
        this.spis = spis;
    }

    /**
     * {@code true} when E34 withdrew only the listed instances' authorisation on a shared endpoint (decommissioning one device
     * behind a gateway, contract section 13.2): the endpoint itself is still ACTIVE, so its other instances keep running.
     */
    boolean instanceOnly(String endpointId, java.util.Set<String> deviceInstanceIds, DeviceCodes.EndpointStatus status) {
        if (endpointId == null || deviceInstanceIds == null || deviceInstanceIds.isEmpty() || status == DeviceCodes.EndpointStatus.ACTIVE) {
            return false;
        }
        try {
            DeviceSpis s = spis.getIfAvailable();
            DeviceEndpointProvisioning.EndpointState state = s == null ? null : s.provisioning().state(endpointId);
            return state != null && state.status() == DeviceCodes.EndpointStatus.ACTIVE;
        } catch (RuntimeException unavailable) {
            return false; // fail safe: treat as an endpoint-wide change
        }
    }

    @Override
    public void endpointStatusChanged(String endpointId, java.util.Set<String> deviceInstanceIds, DeviceCodes.EndpointStatus status, String reasonCode) {
        Instant now = clock.instant();
        String s = status == null ? "ACTIVE" : status.name();
        boolean instanceOnly = instanceOnly(endpointId, deviceInstanceIds, status);
        if (!instanceOnly) {
            jdbc.update("update deviceint.connector_state set endpoint_status = ?, updated_at = ? where endpoint_id = ?", s, Timestamp.from(now), endpointId);
        }
        for (String id : deviceInstanceIds == null ? java.util.Set.<String>of() : deviceInstanceIds) {
            int n = jdbc.update("update deviceint.connector_state set endpoint_status = ?, updated_at = ? where device_instance_id = ?", s,
                    Timestamp.from(now), id);
            if (n == 0) {
                jdbc.update("""
                        insert into deviceint.connector_state (device_instance_id, tenant_code, endpoint_id, endpoint_status, updated_at)
                        values (?, 'GROUP', ?, ?, ?) on conflict (device_instance_id) do nothing""", id, endpointId, s, Timestamp.from(now));
            }
        }
        if (instanceOnly) {
            deviceInstanceIds.forEach(id -> messages.parkInstance(id, "ENDPOINT_ISOLATED"));
        } else if (status == DeviceCodes.EndpointStatus.ISOLATED || status == DeviceCodes.EndpointStatus.REVOKED) {
            messages.park(endpointId, "ENDPOINT_ISOLATED");
        } else if (status == DeviceCodes.EndpointStatus.ACTIVE) {
            messages.unpark(endpointId);
        }
    }
}
