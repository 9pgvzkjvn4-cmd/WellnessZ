package com.wellnessz.e10.collection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.e10.support.TestingViews.CustodyView;

/**
 * Electronic chain of custody: append-only events (no update or delete grant; a database trigger rejects changes) with the
 * signer, the signer's role and the time of every hand-over.
 */
@Component("e10CustodyLog")
public class CustodyLog {

    private final JdbcTemplate jdbc;

    public CustodyLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String specimenId, String event, Instant at, String by, String signer, String signerRole, Instant recordedAt) {
        jdbc.update("""
                insert into substance.custody_event (specimen_id, event, event_at, by_name, signer, signer_role, recorded_at)
                values (?, ?, ?, ?, ?, ?, ?)""", specimenId, event, Timestamp.from(at), by, signer, signerRole, Timestamp.from(recordedAt));
    }

    public List<CustodyView> events(String specimenId) {
        return jdbc.query("""
                select specimen_id, event, event_at, by_name, signer, signer_role, recorded_at from substance.custody_event
                 where specimen_id = ? order by id""",
                (rs, i) -> new CustodyView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()), specimenId);
    }
}
