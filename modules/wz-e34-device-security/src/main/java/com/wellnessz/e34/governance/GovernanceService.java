package com.wellnessz.e34.governance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Governance records of E34 (contract §15.7, §15.10–15.12): secure life-cycle (threat models, SBOM, vulnerabilities with
 * remediation SLAs critical 7 d / high 30 d), penetration-test findings, the IEC 80001-1 responsibility agreement, the DPIA,
 * the compensating-control register, security test-pack runs, the access report and the go-live readiness gate.
 */
@Service
public class GovernanceService {

    static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final Set<String> FINDING_STATUS = Set.of("OPEN", "FIXED", "ACCEPTED");
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._:\\-]{1,60}");
    static final Pattern SCRIPT = Pattern.compile("[A-Za-z0-9._\\-]{1,80}");
    static final Pattern CONTROL = Pattern.compile("SC-[0-9]{2}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public record ThreatModel(UUID id, String adapterOrOption, String version, LocalDate reviewedOn, String reviewer, String documentRef) {
    }

    public record Sbom(String release, String documentId, String format, String sha256, LocalDate generatedOn) {
    }

    public record Vulnerability(String id, String component, String severity, LocalDate foundOn, LocalDate dueOn, String status, LocalDate fixedOn,
                                String note, boolean overdue) {
    }

    public record Sdlc(List<ThreatModel> threatModels, Sbom sbom, List<Vulnerability> vulnerabilities, String disclosureUrl) {
    }

    public record PentestFinding(String ref, String severity, String title, String status, String engagement, LocalDate foundOn, LocalDate closedOn) {
    }

    public record Party(String party, String role, String signatory) {
    }

    public record Agreement(String version, List<Party> parties, LocalDate signedOn, String reviewCadence, String updatedBy, Instant updatedAt) {
    }

    public record Dpia(String version, String status, String approvedBy, Instant approvedAt) {
    }

    public record CompensatingControl(UUID id, String code, String title, String description, String appliesTo, String status, String owner,
                                      LocalDate reviewOn) {
    }

    public record TestResult(String script, String control, String outcome, String evidenceSha256) {
    }

    public record TestRun(UUID id, String version, String environment, String runBy, Instant runAt, int passed, int failed, int notRun,
                          List<TestResult> results) {
    }

    public record AccessRow(String actor, String type, LocalDate day, int count) {
    }

    public record ReadinessCheck(String code, boolean passed, String detail) {
    }

    public record Readiness(boolean ready, List<ReadinessCheck> checks) {
    }

    private static final TypeReference<List<Party>> PARTIES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final AuditService audit;
    private final SecurityEventService events;
    private final E34Settings settings;
    private final String disclosureUrl;

    public GovernanceService(JdbcTemplate jdbc, JsonMapper json, AuditService audit, SecurityEventService events, E34Settings settings,
                             @Value("${wellnessz.device.security.disclosure-url:https://wellnessz.local/.well-known/security.txt}") String disclosureUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.disclosureUrl = disclosureUrl;
    }

    // ------------------------------------------------------------------ SDLC

    @Transactional(readOnly = true)
    public Sdlc sdlc() {
        settings.requireEnabled();
        List<ThreatModel> tms = jdbc.query("select id, adapter_or_option, version, reviewed_on, reviewer, document_ref from devicesec.threat_model "
                + "order by adapter_or_option, reviewed_on desc", (rs, i) -> new ThreatModel(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6)));
        Sbom sbom = jdbc.query("select release, document_id, format, sha256, generated_on from devicesec.sbom_record order by generated_on desc, "
                + "recorded_at desc limit 1", (rs, i) -> new Sbom(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getDate(5).toLocalDate())).stream().findFirst().orElse(null);
        return new Sdlc(tms, sbom, vulnerabilities(), disclosureUrl);
    }

    private List<Vulnerability> vulnerabilities() {
        LocalDate today = LocalDate.ofInstant(settings.now(), ZoneOffset.UTC);
        return jdbc.query("select id, component, severity, found_on, due_on, status, fixed_on, note from devicesec.vulnerability "
                + "order by status, due_on", (rs, i) -> {
                    LocalDate due = rs.getDate("due_on").toLocalDate();
                    String status = rs.getString("status");
                    return new Vulnerability(rs.getString("id"), rs.getString("component"), rs.getString("severity"),
                            rs.getDate("found_on").toLocalDate(), due, status, rs.getDate("fixed_on") == null ? null : rs.getDate("fixed_on").toLocalDate(),
                            rs.getString("note"), "OPEN".equals(status) && today.isAfter(due));
                });
    }

    @Transactional
    public ThreatModel addThreatModel(String actor, String adapterOrOption, String version, LocalDate reviewedOn, String documentRef) {
        settings.requireEnabled();
        if (!valid(adapterOrOption, 60) || !valid(version, 30) || reviewedOn == null) {
            throw ApiException.badRequest("validation_failed", "Adapter / option, version and review date are required");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into devicesec.threat_model (id, adapter_or_option, version, reviewed_on, reviewer, document_ref) values (?,?,?,?,?,?)",
                    id, adapterOrOption, version, reviewedOn, actor, documentRef);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("threat_model_exists", "A threat model for this version exists");
        }
        audit.record("DEVICE_THREAT_MODEL_RECORD", "DEVICE_SDLC", adapterOrOption, AuditService.Outcome.SUCCESS, version);
        return new ThreatModel(id, adapterOrOption, version, reviewedOn, actor, documentRef);
    }

    @Transactional
    public Sbom recordSbom(String actor, String release, String documentId, String format, String sha256, LocalDate generatedOn) {
        settings.requireEnabled();
        if (!valid(release, 40) || documentId == null || documentId.isBlank() || documentId.length() > 200
                || !("CycloneDX".equals(format) || "SPDX".equals(format)) || sha256 != null && !SHA.matcher(sha256).matches()) {
            throw ApiException.badRequest("validation_failed", "Release, document id and format (CycloneDX / SPDX) are required");
        }
        LocalDate on = generatedOn == null ? LocalDate.ofInstant(settings.now(), ZoneOffset.UTC) : generatedOn;
        jdbc.update("insert into devicesec.sbom_record (release, document_id, format, sha256, generated_on, recorded_by, recorded_at) values (?,?,?,?,?,?,?) "
                + "on conflict (release) do update set document_id = excluded.document_id, format = excluded.format, sha256 = excluded.sha256, "
                + "generated_on = excluded.generated_on, recorded_by = excluded.recorded_by, recorded_at = excluded.recorded_at", release, documentId,
                format, sha256, on, actor, EndpointRepository.ts(settings.now()));
        audit.record("DEVICE_SBOM_RECORD", "DEVICE_SDLC", release, AuditService.Outcome.SUCCESS, format);
        return new Sbom(release, documentId, format, sha256, on);
    }

    /** Remediation due date by severity (E34-HLR-06: critical 7 days, high 30 days; medium 90, low 180). */
    public static LocalDate dueOn(String severity, LocalDate foundOn) {
        return switch (severity) {
            case "CRITICAL" -> foundOn.plusDays(7);
            case "HIGH" -> foundOn.plusDays(30);
            case "MEDIUM" -> foundOn.plusDays(90);
            default -> foundOn.plusDays(180);
        };
    }

    @Transactional
    public Vulnerability recordVulnerability(String actor, String id, String component, String severity, LocalDate foundOn, String note) {
        settings.requireEnabled();
        if (id == null || !ID.matcher(id).matches() || !valid(component, 200) || severity == null || !SEVERITIES.contains(severity)) {
            throw ApiException.badRequest("validation_failed", "Id, component and severity are required");
        }
        LocalDate found = foundOn == null ? LocalDate.ofInstant(settings.now(), ZoneOffset.UTC) : foundOn;
        try {
            jdbc.update("insert into devicesec.vulnerability (id, component, severity, found_on, due_on, status, note, updated_by, updated_at) "
                    + "values (?,?,?,?,?,'OPEN',?,?,?)", id, component, severity, found, dueOn(severity, found), cap(note, 500), actor,
                    EndpointRepository.ts(settings.now()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("vulnerability_exists", "The vulnerability is already tracked");
        }
        audit.record("DEVICE_VULNERABILITY_RECORD", "DEVICE_SDLC", id, AuditService.Outcome.SUCCESS, severity);
        return vulnerabilities().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public Vulnerability updateVulnerability(String actor, String id, String status, String note) {
        settings.requireEnabled();
        if (status == null || !FINDING_STATUS.contains(status)) {
            throw ApiException.badRequest("validation_failed", "Status must be OPEN, FIXED or ACCEPTED");
        }
        LocalDate today = LocalDate.ofInstant(settings.now(), ZoneOffset.UTC);
        if (jdbc.update("update devicesec.vulnerability set status = ?, fixed_on = ?, note = coalesce(?, note), updated_by = ?, updated_at = ? "
                + "where id = ?", status, "OPEN".equals(status) ? null : today, cap(note, 500), actor, EndpointRepository.ts(settings.now()), id) == 0) {
            throw ApiException.notFound("Vulnerability");
        }
        audit.record("DEVICE_VULNERABILITY_UPDATE", "DEVICE_SDLC", id, AuditService.Outcome.SUCCESS, status);
        return vulnerabilities().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ penetration test

    @Transactional(readOnly = true)
    public List<PentestFinding> findings() {
        settings.requireEnabled();
        return jdbc.query("select ref, severity, title, status, engagement, found_on, closed_on from devicesec.pentest_finding order by found_on desc, ref",
                (rs, i) -> new PentestFinding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6).toLocalDate(), rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate()));
    }

    @Transactional
    public PentestFinding recordFinding(String actor, String ref, String severity, String title, String status, String engagement, LocalDate foundOn) {
        settings.requireEnabled();
        String st = status == null ? "OPEN" : status;
        if (ref == null || !ID.matcher(ref).matches() || severity == null || !(SEVERITIES.contains(severity) || "INFO".equals(severity))
                || !valid(title, 300) || !FINDING_STATUS.contains(st)) {
            throw ApiException.badRequest("validation_failed", "Reference, severity, title and status are required");
        }
        LocalDate today = LocalDate.ofInstant(settings.now(), ZoneOffset.UTC);
        LocalDate found = foundOn == null ? today : foundOn;
        jdbc.update("insert into devicesec.pentest_finding (ref, severity, title, status, engagement, found_on, closed_on, updated_by, updated_at) "
                + "values (?,?,?,?,?,?,?,?,?) on conflict (ref) do update set severity = excluded.severity, title = excluded.title, "
                + "status = excluded.status, engagement = excluded.engagement, closed_on = excluded.closed_on, updated_by = excluded.updated_by, "
                + "updated_at = excluded.updated_at", ref, severity, title, st, cap(engagement, 100), found, "OPEN".equals(st) ? null : today, actor,
                EndpointRepository.ts(settings.now()));
        audit.record("DEVICE_PENTEST_FINDING", "DEVICE_PENTEST", ref, AuditService.Outcome.SUCCESS, severity + " " + st);
        return findings().stream().filter(f -> f.ref().equals(ref)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ IEC 80001-1 agreement

    @Transactional(readOnly = true)
    public Agreement agreement() {
        settings.requireEnabled();
        return jdbc.query("select version, parties, signed_on, review_cadence, updated_by, updated_at from devicesec.responsibility_agreement where id = 1",
                (rs, i) -> new Agreement(rs.getString(1), json.readValue(rs.getString(2), PARTIES), rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(),
                        rs.getString(4), rs.getString(5), EndpointRepository.inst(rs, "updated_at"))).get(0);
    }

    @Transactional
    public Agreement updateAgreement(String actor, String version, List<Party> parties, LocalDate signedOn, String reviewCadence) {
        settings.requireEnabled();
        if (!valid(version, 30) || parties == null || parties.isEmpty() || parties.size() > 20 || !valid(reviewCadence, 30)
                || parties.stream().anyMatch(p -> p == null || !valid(p.party(), 200) || !valid(p.role(), 300))) {
            throw ApiException.badRequest("validation_failed", "Version, parties and review cadence are required");
        }
        if (signedOn != null && parties.stream().anyMatch(p -> p.signatory() == null || p.signatory().isBlank())) {
            throw ApiException.badRequest("validation_failed", "A signed agreement names a signatory for every party");
        }
        jdbc.update("update devicesec.responsibility_agreement set version = ?, parties = ?, signed_on = ?, review_cadence = ?, updated_by = ?, "
                + "updated_at = ? where id = 1", version, json.writeValueAsString(parties), signedOn, reviewCadence, actor,
                EndpointRepository.ts(settings.now()));
        audit.record("DEVICE_RESPONSIBILITY_AGREEMENT", "DEVICE_GOVERNANCE", "IEC80001-1", AuditService.Outcome.SUCCESS,
                signedOn == null ? "draft " + version : "signed " + version);
        return agreement();
    }

    // ------------------------------------------------------------------ DPIA (E34-HLR-10)

    @Transactional(readOnly = true)
    public Dpia dpia() {
        settings.requireEnabled();
        return jdbc.query("select version, status, approved_by, approved_at from devicesec.dpia where id = 1",
                (rs, i) -> new Dpia(rs.getString(1), rs.getString(2), rs.getString(3), EndpointRepository.inst(rs, "approved_at"))).get(0);
    }

    @Transactional
    public Dpia approveDpia(String actor) {
        settings.requireEnabled();
        Dpia d = dpia();
        if ("APPROVED".equals(d.status())) {
            throw ApiException.conflict("dpia_approved", "The DPIA is already approved");
        }
        jdbc.update("update devicesec.dpia set status = 'APPROVED', approved_by = ?, approved_at = ?, updated_at = ? where id = 1", actor,
                EndpointRepository.ts(settings.now()), EndpointRepository.ts(settings.now()));
        audit.record("DEVICE_DPIA_APPROVE", "DEVICE_DPIA", d.version(), AuditService.Outcome.SUCCESS, "approved");
        return dpia();
    }

    // ------------------------------------------------------------------ compensating controls (E34-HLR-07)

    @Transactional(readOnly = true)
    public List<CompensatingControl> controls() {
        settings.requireEnabled();
        return jdbc.query("select id, code, title, description, applies_to, status, owner, review_on from devicesec.compensating_control order by code",
                (rs, i) -> new CompensatingControl(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getDate(8).toLocalDate()));
    }

    @Transactional
    public CompensatingControl addControl(String actor, String code, String title, String description, String appliesTo, String owner, LocalDate reviewOn) {
        settings.requireEnabled();
        if (code == null || !code.matches("[A-Z0-9\\-]{2,40}") || !valid(title, 200) || !valid(description, 1000) || !valid(appliesTo, 200)
                || !valid(owner, 200) || reviewOn == null) {
            throw ApiException.badRequest("validation_failed", "Code, title, description, scope, owner and review date are required");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into devicesec.compensating_control (id, code, title, description, applies_to, status, owner, review_on, created_by, "
                    + "created_at) values (?,?,?,?,?,'ACTIVE',?,?,?,?)", id, code, title, description, appliesTo, owner, reviewOn, actor,
                    EndpointRepository.ts(settings.now()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("control_exists", "A control with this code exists");
        }
        audit.record("DEVICE_COMPENSATING_CONTROL", "DEVICE_GOVERNANCE", code, AuditService.Outcome.SUCCESS, "added");
        return new CompensatingControl(id, code, title, description, appliesTo, "ACTIVE", owner, reviewOn);
    }

    // ------------------------------------------------------------------ security test pack runs (E34-HLR-11)

    @Transactional
    public TestRun recordRun(String actor, String version, String environment, List<TestResult> results) {
        settings.requireEnabled();
        if (!valid(version, 30) || environment == null || !environment.matches("[A-Za-z0-9_\\-]{1,30}") || results == null || results.isEmpty()
                || results.size() > 200) {
            throw ApiException.badRequest("validation_failed", "Version, environment and results are required");
        }
        for (TestResult r : results) {
            if (r == null || r.script() == null || !SCRIPT.matcher(r.script()).matches() || r.control() == null || !CONTROL.matcher(r.control()).matches()
                    || !Set.of("PASS", "FAIL", "NOT_RUN").contains(r.outcome()) || r.evidenceSha256() != null && !SHA.matcher(r.evidenceSha256()).matches()) {
                throw ApiException.badRequest("validation_failed", "Each result needs script, control SC-nn, outcome and an optional SHA-256");
            }
        }
        UUID id = UUID.randomUUID();
        int passed = (int) results.stream().filter(r -> "PASS".equals(r.outcome())).count();
        int failed = (int) results.stream().filter(r -> "FAIL".equals(r.outcome())).count();
        int notRun = results.size() - passed - failed;
        jdbc.update("insert into devicesec.test_pack_run (id, version, environment, run_by, run_at, passed, failed, not_run) values (?,?,?,?,?,?,?,?)",
                id, version, environment, actor, EndpointRepository.ts(settings.now()), passed, failed, notRun);
        for (TestResult r : results) {
            jdbc.update("insert into devicesec.test_pack_result (run_id, script, control, outcome, evidence_sha256) values (?,?,?,?,?) "
                    + "on conflict do nothing", id, r.script(), r.control(), r.outcome(), r.evidenceSha256());
        }
        audit.record("DEVICE_TEST_PACK_RUN", "DEVICE_TEST_PACK", id.toString(), AuditService.Outcome.SUCCESS,
                "pass=" + passed + " fail=" + failed + " notRun=" + notRun);
        events.record("TEST_PACK_RUN", failed > 0 ? DeviceCodes.Severity.MEDIUM : DeviceCodes.Severity.INFO, null, null, actor, null,
                Map.of("version", version, "environment", environment, "count", String.valueOf(results.size()), "outcome", failed > 0 ? "FAIL" : "PASS"));
        return runs(1).get(0);
    }

    @Transactional(readOnly = true)
    public List<TestRun> runs(int limit) {
        settings.requireEnabled();
        List<TestRun> runs = jdbc.query("select id, version, environment, run_by, run_at, passed, failed, not_run from devicesec.test_pack_run "
                + "order by run_at desc limit ?", (rs, i) -> new TestRun(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                EndpointRepository.inst(rs, "run_at"), rs.getInt(6), rs.getInt(7), rs.getInt(8), List.of()), Math.min(Math.max(limit, 1), 100));
        List<TestRun> out = new ArrayList<>();
        for (TestRun r : runs) {
            List<TestResult> results = jdbc.query("select script, control, outcome, evidence_sha256 from devicesec.test_pack_result where run_id = ? "
                    + "order by script, control", (rs, i) -> new TestResult(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), r.id());
            out.add(new TestRun(r.id(), r.version(), r.environment(), r.runBy(), r.runAt(), r.passed(), r.failed(), r.notRun(), results));
        }
        return out;
    }

    // ------------------------------------------------------------------ access report (E34-HLR-08)

    @Transactional(readOnly = true)
    public List<AccessRow> accessReport(Instant from, Instant to) {
        settings.requireEnabled();
        Instant f = from == null ? settings.now().minusSeconds(30L * 24 * 3600) : from;
        Instant t = to == null ? settings.now() : to;
        return jdbc.query("select actor, type, cast(occurred_at at time zone 'UTC' as date) as day, count(*) as n from devicesec.security_event "
                + "where type in ('DEVICE_DATA_ACCESSED','DIAGNOSTIC_TRACE_VIEWED','CONFIG_EXPORTED','CONFIG_IMPORTED','SECRET_RESOLVED',"
                + "'SECRET_METADATA_VIEWED') and occurred_at >= ? and occurred_at < ? group by actor, type, day order by day, actor, type",
                (rs, i) -> new AccessRow(rs.getString("actor"), rs.getString("type"), rs.getDate("day").toLocalDate(), rs.getInt("n")),
                EndpointRepository.ts(f), EndpointRepository.ts(t));
    }

    // ------------------------------------------------------------------ go-live readiness (E34-HLR-12)

    @Transactional(readOnly = true)
    public Readiness goLiveReadiness() {
        settings.requireEnabled();
        List<ReadinessCheck> checks = new ArrayList<>();
        Integer openCritical = jdbc.queryForObject("select count(*) from devicesec.pentest_finding where status = 'OPEN' and severity = 'CRITICAL'",
                Integer.class);
        Integer findingsTotal = jdbc.queryForObject("select count(*) from devicesec.pentest_finding", Integer.class);
        checks.add(new ReadinessCheck("PENTEST_PERFORMED", findingsTotal != null && findingsTotal > 0,
                "penetration test findings recorded: " + findingsTotal));
        checks.add(new ReadinessCheck("PENTEST_NO_OPEN_CRITICAL", openCritical != null && openCritical == 0, "open critical findings: " + openCritical));
        Agreement a = agreement();
        checks.add(new ReadinessCheck("IEC80001_AGREEMENT_SIGNED", a.signedOn() != null, a.signedOn() == null ? "not signed" : "signed " + a.signedOn()));
        Dpia d = dpia();
        checks.add(new ReadinessCheck("DPIA_APPROVED", "APPROVED".equals(d.status()), "DPIA " + d.version() + " " + d.status()));
        List<TestRun> last = runs(1);
        boolean packOk = !last.isEmpty() && last.get(0).failed() == 0;
        checks.add(new ReadinessCheck("TEST_PACK_PASSED", packOk, last.isEmpty() ? "no run recorded"
                : "last run " + last.get(0).runAt() + ": pass=" + last.get(0).passed() + " fail=" + last.get(0).failed()));
        long overdue = vulnerabilities().stream().filter(v -> v.overdue() && ("CRITICAL".equals(v.severity()) || "HIGH".equals(v.severity()))).count();
        checks.add(new ReadinessCheck("VULNERABILITY_SLA", overdue == 0, "overdue critical / high: " + overdue));
        Integer tm = jdbc.queryForObject("select count(*) from devicesec.threat_model", Integer.class);
        checks.add(new ReadinessCheck("THREAT_MODELS", tm != null && tm > 0, "threat models: " + tm));
        Integer sbom = jdbc.queryForObject("select count(*) from devicesec.sbom_record", Integer.class);
        checks.add(new ReadinessCheck("SBOM_PUBLISHED", sbom != null && sbom > 0, "SBOM records: " + sbom));
        Integer intact = events.verify().intact() ? 1 : 0;
        checks.add(new ReadinessCheck("SECURITY_EVENT_CHAIN_INTACT", intact == 1, intact == 1 ? "intact" : "broken"));
        return new Readiness(checks.stream().allMatch(ReadinessCheck::passed), checks);
    }

    private static boolean valid(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private static String cap(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
