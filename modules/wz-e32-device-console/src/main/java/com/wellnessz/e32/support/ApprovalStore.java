package com.wellnessz.e32.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The four-eyes queue ({@code devicecat.approval}): configuration submissions, decommissioning requests, environment imports,
 * driver certifications, posture exceptions and relaxing schedule changes. "No production change without an approval record"
 * (E32-F06) – every approved change references one of these rows.
 */
@Component("e32ApprovalStore")
public class ApprovalStore {

    private final E32Support support;

    public ApprovalStore(E32Support support) {
        this.support = support;
    }

    public record Approval(UUID id, String kind, String reference, Integer refVersion, String status, String requestedBy, Instant requestedAt,
                           String decidedBy, Instant decidedAt, String note, Map<String, Object> detail) {
    }

    public UUID open(String kind, String reference, Integer refVersion, String requestedBy, Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        support.jdbc().update("""
                insert into devicecat.approval (id, kind, reference, ref_version, status, requested_by, requested_at, detail)
                values (?, ?, ?, ?, 'PENDING', ?, ?, ?)""", id, kind, reference, refVersion, requestedBy, Timestamp.from(support.now()),
                detail == null ? null : support.write(detail));
        return id;
    }

    public Optional<Approval> pending(String kind, String reference) {
        return support.jdbc().query("""
                select * from devicecat.approval where kind = ? and reference = ? and status = 'PENDING' order by requested_at desc limit 1""",
                (rs, i) -> map(rs), kind, reference).stream().findFirst();
    }

    public Optional<Approval> find(UUID id) {
        return support.jdbc().query("select * from devicecat.approval where id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    public List<Approval> byStatus(String status) {
        return support.jdbc().query("select * from devicecat.approval where status = ? order by requested_at", (rs, i) -> map(rs), status);
    }

    public void decide(UUID id, String status, String decidedBy, String note) {
        support.jdbc().update("update devicecat.approval set status = ?, decided_by = ?, decided_at = ?, note = ? where id = ?",
                status, decidedBy, Timestamp.from(support.now()), note, id);
    }

    private Approval map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp req = rs.getTimestamp("requested_at");
        Timestamp dec = rs.getTimestamp("decided_at");
        Object v = rs.getObject("ref_version");
        return new Approval(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("reference"),
                v == null ? null : ((Number) v).intValue(), rs.getString("status"), rs.getString("requested_by"),
                req == null ? null : req.toInstant(), rs.getString("decided_by"), dec == null ? null : dec.toInstant(), rs.getString("note"),
                support.readMap(rs.getString("detail")));
    }
}
