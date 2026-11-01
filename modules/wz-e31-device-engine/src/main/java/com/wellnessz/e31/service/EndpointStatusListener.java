package com.wellnessz.e31.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.e31.store.MessageStore;

/**
 * {@link DeviceEndpointStatusListener} (E34 → E31, contract section 15.8): an isolated / revoked endpoint parks its in-flight
 * messages ({@code ENDPOINT_ISOLATED}) and its instances refuse new messages ({@code device_quarantined}) until released, when
 * the parked messages are retried. Depends on the store only (the kernel {@code DeviceSpis} collects the listeners).
 */
@Component("e31EndpointStatusListener")
public class EndpointStatusListener implements DeviceEndpointStatusListener {

    private final MessageStore messages;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EndpointStatusListener(MessageStore messages, JdbcTemplate jdbc, Clock clock) {
        this.messages = messages;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void endpointStatusChanged(String endpointId, java.util.Set<String> deviceInstanceIds, DeviceCodes.EndpointStatus status, String reasonCode) {
        Instant now = clock.instant();
        String s = status == null ? "ACTIVE" : status.name();
        jdbc.update("update deviceint.connector_state set endpoint_status = ?, updated_at = ? where endpoint_id = ?", s, Timestamp.from(now), endpointId);
        for (String id : deviceInstanceIds == null ? java.util.Set.<String>of() : deviceInstanceIds) {
            int n = jdbc.update("update deviceint.connector_state set endpoint_status = ?, updated_at = ? where device_instance_id = ?", s,
                    Timestamp.from(now), id);
            if (n == 0) {
                jdbc.update("""
                        insert into deviceint.connector_state (device_instance_id, tenant_code, endpoint_id, endpoint_status, updated_at)
                        values (?, 'GROUP', ?, ?, ?) on conflict (device_instance_id) do nothing""", id, endpointId, s, Timestamp.from(now));
            }
        }
        if (status == DeviceCodes.EndpointStatus.ISOLATED || status == DeviceCodes.EndpointStatus.REVOKED) {
            messages.park(endpointId, "ENDPOINT_ISOLATED");
        } else if (status == DeviceCodes.EndpointStatus.ACTIVE) {
            messages.unpark(endpointId);
        }
    }
}
