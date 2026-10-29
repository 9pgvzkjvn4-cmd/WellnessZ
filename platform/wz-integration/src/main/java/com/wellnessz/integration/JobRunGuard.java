package com.wellnessz.integration;

import java.net.InetAddress;
import java.time.Clock;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures a scheduled job runs once per run key (e.g. per day) across all application instances: the first instance to
 * insert {@code (job, runKey)} wins, the others skip. Used by daily jobs (restriction expiry, retention, overdue results).
 */
@Component
public class JobRunGuard {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String instance;

    public JobRunGuard(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.instance = host.length() > 200 ? host.substring(0, 200) : host;
    }

    public boolean tryStart(String job, String runKey) {
        try {
            return jdbc.update("insert into platform.scheduled_job_run (job_name, run_key, started_at, instance) values (?, ?, ?, ?)",
                    job, runKey, java.sql.Timestamp.from(clock.instant()), instance) == 1;
        } catch (DuplicateKeyException alreadyRun) {
            return false;
        }
    }
}
