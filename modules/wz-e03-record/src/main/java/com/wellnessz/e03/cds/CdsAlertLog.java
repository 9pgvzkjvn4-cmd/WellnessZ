package com.wellnessz.e03.cds;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.secrets.FieldEncryptor;

/**
 * Logs every fired alert in its own transaction so that alerts that block a request (409) are still counted in the
 * alert statistics. Override reasons are clinical free text and are encrypted.
 */
@Component
public class CdsAlertLog {

    static final String CONTEXT = "wellnessz-field";
    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final Clock clock;

    public CdsAlertLog(JdbcTemplate jdbc, FieldEncryptor encryptor, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String employeeId, String context, String username, List<CdsAlert> alerts, Map<String, String> reasons) {
        Timestamp now = Timestamp.from(clock.instant());
        for (CdsAlert a : alerts) {
            String reason = reasons.get(a.code());
            jdbc.update("""
                    insert into ohrecord.cds_alert_log (id, employee_id, context, alert_code, severity, overridden, override_reason, username, fired_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), employeeId, context, a.code(), a.severity(), reason != null,
                    reason == null ? null : encryptor.encrypt(reason, CONTEXT), username, now);
        }
    }
}
