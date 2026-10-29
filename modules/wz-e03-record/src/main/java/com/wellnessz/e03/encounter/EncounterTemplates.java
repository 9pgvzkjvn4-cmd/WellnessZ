package com.wellnessz.e03.encounter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned encounter templates (E03-BL-004). Definitions ship as JSON resources ({@code encounter-templates/*.json});
 * governance status (DRAFT until the CMO approves) is kept in {@code ohrecord.encounter_template}.
 */
@Component
public class EncounterTemplates {

    public static final List<String> TYPES = List.of("INJURY", "ILLNESS", "FITNESS_ASSESSMENT", "SURVEILLANCE_EXAM", "MENTAL_HEALTH",
            "TRAVEL", "CREW_AEROMEDICAL", "FOOD_HANDLER");

    public record Option(String value, String label) {
    }

    public record Field(String key, String label, String type, boolean required, List<Option> options, String codeSystem, String unit) {
    }

    public record Section(String key, String title, List<Field> fields) {
    }

    public record Definition(String type, int version, String title, boolean bodyMap, boolean bodyMapRequired, boolean requiresDiagnosis,
                             boolean fitnessStandardPrompt, List<String> protocolPrompts, List<Section> sections) {
    }

    /** API view: definition + governance status. */
    public record EncounterTemplate(String type, int version, String title, List<Section> sections, boolean bodyMap,
                                    boolean requiresDiagnosis, boolean fitnessStandardPrompt, List<String> protocolPrompts,
                                    String approvedBy, Instant approvedAt, String status) {
    }

    private final Map<String, Definition> definitions = new TreeMap<>();
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public EncounterTemplates(JsonMapper json, JdbcTemplate jdbc, AuditService audit, Clock clock) throws IOException {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:encounter-templates/*.json");
        for (Resource r : files) {
            try (InputStream in = r.getInputStream()) {
                Definition d = json.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Definition.class);
                definitions.put(key(d.type(), d.version()), d);
            }
        }
    }

    private static String key(String type, int version) {
        return type + ":" + version;
    }

    /** Latest version of a template type. */
    public Definition latest(String type) {
        return definitions.values().stream().filter(d -> d.type().equals(type)).max(Comparator.comparingInt(Definition::version))
                .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown encounter type"));
    }

    public Definition get(String type, Integer version) {
        if (version == null) {
            return latest(type);
        }
        return Optional.ofNullable(definitions.get(key(type, version)))
                .orElseThrow(() -> ApiException.badRequest("unknown_template_version", "Unknown template version"));
    }

    @Transactional(readOnly = true)
    public List<EncounterTemplate> list() {
        return definitions.values().stream().map(this::view).toList();
    }

    private EncounterTemplate view(Definition d) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, approved_by, approved_at from ohrecord.encounter_template where type = ? and version = ?", d.type(), d.version());
        String status = rows.isEmpty() ? "DRAFT" : (String) rows.get(0).get("status");
        String by = rows.isEmpty() ? null : (String) rows.get(0).get("approved_by");
        Object at = rows.isEmpty() ? null : rows.get(0).get("approved_at");
        Instant approvedAt = at instanceof Timestamp ts ? ts.toInstant() : at instanceof java.time.OffsetDateTime o ? o.toInstant() : null;
        return new EncounterTemplate(d.type(), d.version(), d.title(), d.sections(), d.bodyMap(), d.requiresDiagnosis(),
                d.fitnessStandardPrompt(), d.protocolPrompts(), by, approvedAt, status);
    }

    /** CMO approval of a template version (governance step of E03-BL-004). */
    @Transactional
    public EncounterTemplate approve(CurrentUser user, String type, Integer version) {
        Definition d = get(type, version);
        int updated = jdbc.update("""
                update ohrecord.encounter_template set status = 'APPROVED', approved_by = ?, approved_at = ?
                 where type = ? and version = ?""", user.username(), Timestamp.from(clock.instant()), d.type(), d.version());
        if (updated == 0) {
            throw ApiException.notFound("Template version");
        }
        audit.record("ENCOUNTER_TEMPLATE_APPROVE", "ENCOUNTER_TEMPLATE", key(d.type(), d.version()), AuditService.Outcome.SUCCESS, null);
        return view(d);
    }
}
