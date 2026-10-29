package com.wellnessz.ehsintake;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.ehsintake.model.DeadLetter;
import com.wellnessz.ehsintake.model.DeadLetterRepository;
import com.wellnessz.ehsintake.model.IncidentRecord;
import com.wellnessz.ehsintake.model.IntakeChannel;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EHS incident webhook (E01-F01 / E01-BL-002): schema validation, per-item dead-lettering with an operator alert,
 * then the common ingestion path (de-duplication, persistence, {@code ehs.incident.received.v1}).
 * Transport-level idempotency is provided by the {@code Idempotency-Key} handling in the controller.
 */
@Service
public class IncidentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(IncidentWebhookService.class);
    static final Set<String> INCIDENT_CLASSES = Set.of("INJURY_FIRST_AID", "INJURY_FIRST_AID_PLUS", "INJURY_MEDICAL_TREATMENT",
            "LOST_TIME_INJURY", "OCCUPATIONAL_ILLNESS", "EXPOSURE", "BODY_FLUID_EXPOSURE", "FUME_EVENT", "HEAT_ILLNESS", "NEAR_MISS",
            "PROPERTY_DAMAGE", "OTHER");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final IncidentIntakeService intake;
    private final DeadLetterRepository dlq;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final String defaultSource;

    public IncidentWebhookService(IncidentIntakeService intake, DeadLetterRepository dlq, AuditService audit, JsonMapper json,
                                  Clock clock, @Value("${wellnessz.ehs.source-system:EHS}") String defaultSource) {
        this.intake = intake;
        this.dlq = dlq;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.defaultSource = defaultSource;
    }

    public record WebhookResult(int accepted, int duplicates, int deadLettered, String batchId) {
    }

    @Transactional
    public WebhookResult receive(String body, String actor) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_json", "Body is not valid JSON");
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.has("incidents") && root.get("incidents").isArray()) {
            root.get("incidents").forEach(items::add);
        } else {
            items.add(root);
        }
        if (items.size() > 500) {
            throw ApiException.badRequest("batch_too_large", "At most 500 incidents per call");
        }
        List<IncidentRecord> valid = new ArrayList<>();
        int deadLettered = 0;
        for (JsonNode item : items) {
            try {
                valid.add(toRecord(item));
            } catch (IllegalArgumentException e) {
                dlq.save(new DeadLetter("ehs-webhook", e.getMessage(), item.toString(), clock.instant()));
                deadLettered++;
            }
        }
        if (deadLettered > 0) {
            // operational alert (SIEM / on-call rule on this log event + audit record)
            log.warn("ALERT ehs-webhook dead-lettered={} – review /api/v1/intake/dlq", deadLettered);
            audit.recordAs(actor, "", "EHS_WEBHOOK_DLQ", "INCIDENT_DLQ", null, AuditService.Outcome.FAILURE, "count=" + deadLettered);
        }
        if (valid.isEmpty()) {
            return new WebhookResult(0, 0, deadLettered, null);
        }
        var batch = intake.importRecords(valid, "webhook", IntakeChannel.API, actor);
        return new WebhookResult(batch.getRowsAccepted(), batch.getRowsDuplicate(), deadLettered, batch.getId().toString());
    }

    /** Re-validates a dead-lettered payload (after the upstream system or mapping was fixed). */
    @Transactional
    public WebhookResult replay(DeadLetter item, String actor) {
        IncidentRecord record = toRecord(json.readTree(item.getPayload()));
        var batch = intake.importRecords(List.of(record), "dlq-replay", IntakeChannel.API, actor);
        item.resolve(DeadLetter.Status.REPLAYED, actor, clock.instant());
        return new WebhookResult(batch.getRowsAccepted(), batch.getRowsDuplicate(), 0, batch.getId().toString());
    }

    IncidentRecord toRecord(JsonNode n) {
        String ref = req(n, "externalRef", "[A-Za-z0-9._/\\-]{1,100}");
        Instant occurredAt = instant(n, "occurredAt", true);
        String incidentClass = opt(n, "incidentClass", "[A-Z_]{2,40}");
        if (incidentClass != null && !INCIDENT_CLASSES.contains(incidentClass)) {
            throw new IllegalArgumentException("Unknown incidentClass");
        }
        String severity = opt(n, "severity", "[A-Za-z]{1,20}");
        if (severity != null) {
            severity = severity.toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException("Unknown severity");
            }
        }
        String source = opt(n, "sourceSystem", "[A-Za-z0-9_\\-]{1,100}");
        String reporter = opt(n, "reporterEmail", "[^@\\s]{1,64}@[^@\\s]{1,255}");
        String description = opt(n, "description", "(?s).{0,4000}");
        boolean injury = incidentClass != null && (incidentClass.startsWith("INJURY") || incidentClass.equals("LOST_TIME_INJURY"));
        Boolean lostTime = n.has("lostTime") && !n.get("lostTime").isNull() ? n.get("lostTime").asBoolean() : null;
        return new IncidentRecord(source == null ? defaultSource : source, ref, occurredAt, opt(n, "location", ".{0,200}"),
                opt(n, "employeeId", "[A-Za-z0-9\\-]{1,64}"), opt(n, "employeeName", ".{0,200}"),
                opt(n, "orgUnitCode", "[A-Za-z0-9_\\-]{1,64}"), incidentClass, severity, injury || Boolean.TRUE.equals(lostTime),
                description, incidentClass, opt(n, "bodyPart", "[A-Z_]{1,60}"), lostTime, opt(n, "exposureType", "[A-Z_]{1,60}"),
                opt(n, "unitCode", "[A-Z0-9]{1,20}"), opt(n, "shift", "[A-Z_]{1,40}"), reporter);
    }

    private static String req(JsonNode n, String field, String pattern) {
        String v = opt(n, field, pattern);
        if (v == null) {
            throw new IllegalArgumentException("Missing required field " + field);
        }
        return v;
    }

    private static String opt(JsonNode n, String field, String pattern) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw new IllegalArgumentException("Field " + field + " must be a string");
        }
        String s = v.asString().strip();
        if (s.isEmpty()) {
            return null;
        }
        if (!s.matches(pattern)) {
            throw new IllegalArgumentException("Field " + field + " has an invalid format");
        }
        return s;
    }

    private static Instant instant(JsonNode n, String field, boolean required) {
        String v = opt(n, field, "[0-9T:\\-.+Z]{10,40}");
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException("Missing required field " + field);
            }
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Field " + field + " is not an ISO-8601 instant");
        }
    }
}
