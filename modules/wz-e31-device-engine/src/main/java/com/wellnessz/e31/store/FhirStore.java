package com.wellnessz.e31.store;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.secrets.FieldEncryptor;

/** Canonical FHIR R4 resources as validated, field-encrypted JSON ({@code deviceint.fhir_resource}); search columns outside the JSON. */
@Component("e31FhirStore")
public class FhirStore {

    static final String CTX = "deviceint.fhir_resource.json";

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final Clock clock;

    public FhirStore(JdbcTemplate jdbc, FieldEncryptor encryptor, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    public void save(List<FhirWriter.Written> resources, ObservationStore.ObservationRow o, String status) {
        Timestamp now = Timestamp.from(clock.instant());
        for (FhirWriter.Written w : resources) {
            String enc = encryptor.encrypt(w.json(), CTX);
            boolean device = "Device".equals(w.resourceType());
            int n = jdbc.update("""
                    update deviceint.fhir_resource set json = ?, status = ?, employee_id = ?, effective_at = ?, version = version + 1, updated_at = ?
                     where resource_type = ? and resource_id = ?""", enc, status, device ? null : o.employeeId(),
                    device ? null : Timestamp.from(o.effectiveAt()), now, w.resourceType(), w.id());
            if (n == 0) {
                jdbc.update("""
                        insert into deviceint.fhir_resource (id, resource_type, resource_id, tenant_code, employee_id, observation_id, category,
                            effective_at, status, json, version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""",
                        UUID.randomUUID(), w.resourceType(), w.id(), o.tenantCode(), device ? null : o.employeeId(), device ? null : o.id(),
                        device ? null : o.category().name(), device ? null : Timestamp.from(o.effectiveAt()), status, enc, now, now);
            }
        }
    }

    public record Stored(String resourceType, String resourceId, String json, int version) {
    }

    /** Observation, DiagnosticReport and Provenance of an observation, plus its Device. */
    public List<Stored> bundle(UUID observationId, String deviceInstanceId) {
        return jdbc.query("""
                select resource_type, resource_id, json, version from deviceint.fhir_resource
                 where observation_id = ? or (resource_type = 'Device' and resource_id = ?) order by resource_type""",
                (rs, i) -> new Stored(rs.getString(1), rs.getString(2), encryptor.decrypt(rs.getString(3), CTX), rs.getInt(4)), observationId,
                deviceInstanceId == null ? "" : FhirWriter.safeId(deviceInstanceId));
    }
}
