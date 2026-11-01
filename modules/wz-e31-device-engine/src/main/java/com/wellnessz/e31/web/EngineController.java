package com.wellnessz.e31.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverValidator;
import com.wellnessz.e31.service.ClinicalReadService;
import com.wellnessz.e31.service.DriverService;
import com.wellnessz.e31.service.OperationsService;
import com.wellnessz.e31.service.TenantService;
import com.wellnessz.e31.service.HistoricRetrievalService;
import com.wellnessz.e31.cloud.CloudPollService;
import com.wellnessz.e31.outbound.OutboundService;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.simulator.SimulatorService;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.terminology.Gli2012;
import com.wellnessz.e31.terminology.Terminology;
import com.wellnessz.e31.terminology.TerminologyService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** User and operations API of the engine ({@code /api/v1/device-engine/**}, contract section 12). */
@RestController("e31EngineController")
@RequestMapping("/api/v1/device-engine")
@Validated
public class EngineController {

    static final String OH_READ = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','MRO')";
    static final String UNMATCHED = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','NURSING_HEAD','AME','CMO')";
    /** Operations views (connector health, metrics, alerts) – technical and PHI-free: ids, states, counts and rates only. */
    static final String OPS = "hasAnyRole('DEVICE_ADMIN','SECURITY_OPS','PLATFORM_ADMIN','NURSING_HEAD','INTEGRATION_TESTER')";
    static final String CLINICIANS_AND_TESTERS = "hasAnyRole('INTEGRATION_TESTER','DEVICE_ADMIN','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD')";

    private final ClinicalReadService clinical;
    private final OperationsService ops;
    private final TenantService tenants;
    private final HistoricRetrievalService historic;
    private final CloudPollService cloud;
    private final OutboundService outbound;
    private final DriverService drivers;
    private final GoldenDatasets golden;
    private final SimulatorService simulators;
    private final TerminologyService terminology;
    private final CurrentUserProvider users;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public EngineController(ClinicalReadService clinical, OperationsService ops, TenantService tenants, HistoricRetrievalService historic,
                            CloudPollService cloud, OutboundService outbound, DriverService drivers, GoldenDatasets golden,
                            SimulatorService simulators, TerminologyService terminology, CurrentUserProvider users, ModuleRegistry modules,
                            JsonMapper json) {
        this.clinical = clinical;
        this.ops = ops;
        this.tenants = tenants;
        this.historic = historic;
        this.cloud = cloud;
        this.outbound = outbound;
        this.drivers = drivers;
        this.golden = golden;
        this.simulators = simulators;
        this.terminology = terminology;
        this.users = users;
        this.modules = modules;
        this.json = json;
    }

    CurrentUser user() {
        modules.requireEnabled("E31");
        return users.require();
    }

    // ------------------------------------------------------------------ observations (person level, ABAC, audited)

    @GetMapping("/observations")
    @PreAuthorize(OH_READ)
    public List<ClinicalReadService.ObservationSummary> observations(@RequestParam @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                                                     @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{2,20}") String category,
                                                                     @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return clinical.list(user(), employeeId, category, from, to);
    }

    @GetMapping("/observations/{id}")
    @PreAuthorize(OH_READ)
    public ClinicalReadService.ObservationView observation(@PathVariable UUID id, @RequestParam(required = false) String format) {
        return clinical.get(user(), id, "fhir".equalsIgnoreCase(format));
    }

    @GetMapping("/observations/{id}/archive/verify")
    @PreAuthorize(OH_READ)
    public RawPayloadStore.Verification verifyObservationArchive(@PathVariable UUID id, @RequestParam(required = false) UUID archiveId) {
        return clinical.verifyArchive(user(), id, archiveId);
    }

    @GetMapping("/observations/{id}/provenance")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','AUDITOR')")
    public ClinicalReadService.ProvenanceView provenance(@PathVariable UUID id) {
        user();
        return clinical.provenance(id);
    }

    @GetMapping("/observations/{id}/attachments/{archiveId}")
    @PreAuthorize(OH_READ)
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID archiveId) {
        ClinicalReadService.AttachmentContent a = clinical.attachment(user(), id, archiveId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.mediaType())).header("Cache-Control", "no-store").body(a.bytes());
    }

    // ------------------------------------------------------------------ unmatched queue

    @GetMapping("/unmatched")
    @PreAuthorize(UNMATCHED)
    public List<ClinicalReadService.UnmatchedView> unmatched(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_\\-]{1,40}") String siteCode,
                                                             @RequestParam(required = false) @Pattern(regexp = "OPEN|RESOLVED|DISCARDED") String status) {
        return clinical.unmatched(user(), siteCode, status);
    }

    @GetMapping("/unmatched/count")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','NURSING_HEAD','AME','CMO','DEVICE_ADMIN')")
    public Map<String, Long> unmatchedCount(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_\\-]{1,40}") String siteCode) {
        user();
        return clinical.unmatchedCount(siteCode);
    }

    public record ResolveRequest(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                 @NotBlank @Pattern(regexp = "[A-Z_]{2,30}") String reasonCode, @Size(max = 1000) String note, Boolean discard) {
    }

    @PostMapping("/unmatched/{id}/resolve")
    @PreAuthorize(UNMATCHED)
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid ResolveRequest r) {
        return clinical.resolve(user(), id, r.employeeId(), r.reasonCode(), r.note(), Boolean.TRUE.equals(r.discard()));
    }

    // ------------------------------------------------------------------ dead letters and replay (masked)

    @GetMapping("/dead-letters")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER','PLATFORM_ADMIN')")
    public List<MessageStore.DeadLetter> deadLetters(@RequestParam(required = false) @Pattern(regexp = "[A-Z]{2,20}") String stage,
                                                     @RequestParam(required = false) @Pattern(regexp = "OPEN|REPLAYED|DISCARDED") String status) {
        user();
        return ops.deadLetters(stage, status);
    }

    public record ReplayRequest(@Pattern(regexp = "\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}") String driverVersion) {
    }

    @PostMapping("/dead-letters/{id}/replay")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER')")
    public Map<String, Object> replay(@PathVariable UUID id, @RequestBody(required = false) @jakarta.validation.Valid ReplayRequest r) {
        return ops.replayDeadLetter(user().username(), id, r == null ? null : r.driverVersion());
    }

    public record DiscardRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/dead-letters/{id}/discard")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER')")
    public Map<String, Object> discard(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid DiscardRequest r) {
        return ops.discardDeadLetter(user().username(), id, r.reason());
    }

    public record RangeReplay(Instant from, Instant to, @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId,
                              @Pattern(regexp = "PARSE|NORMALISE") String stageFrom) {
    }

    @PostMapping("/replay")
    @PreAuthorize("hasRole('DEVICE_ADMIN')")
    public Map<String, Object> replayRange(@RequestBody @jakarta.validation.Valid RangeReplay r) {
        return ops.replayRange(user().username(), r.from(), r.to(), r.deviceInstanceId(), r.stageFrom());
    }

    // ------------------------------------------------------------------ operations (PHI-free)

    @GetMapping("/ops/connectors")
    @PreAuthorize(OPS)
    public List<OperationsService.ConnectorHealth> connectors(@RequestParam(required = false) String siteCode,
                                                              @RequestParam(required = false) @Pattern(regexp = "HEALTHY|DEGRADED|STALE|SILENT|ISOLATED") String state) {
        user();
        return ops.connectors(siteCode, state);
    }

    @GetMapping("/ops/metrics")
    @PreAuthorize(OPS)
    public Map<String, Object> metrics(@RequestParam(required = false, defaultValue = "PT1H") @Pattern(regexp = "PT1H|P1D") String window) {
        user();
        return ops.metrics(window);
    }

    @GetMapping("/ops/alerts")
    @PreAuthorize(OPS)
    public List<OperationsService.Alert> alerts() {
        user();
        return ops.alerts();
    }

    // ------------------------------------------------------------------ driver SDK

    @GetMapping("/drivers")
    @PreAuthorize("hasAnyRole('INTEGRATION_TESTER','DEVICE_ADMIN')")
    public List<Map<String, Object>> drivers() {
        user();
        return drivers.samples();
    }

    @GetMapping("/drivers/{packageId}/{version}")
    @PreAuthorize("hasAnyRole('INTEGRATION_TESTER','DEVICE_ADMIN')")
    public JsonNode driver(@PathVariable String packageId, @PathVariable String version) {
        user();
        return drivers.sampleSource(packageId, version);
    }

    @PostMapping(value = "/drivers/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('INTEGRATION_TESTER')")
    public DriverValidator.Report validate(@RequestBody JsonNode packageJson) {
        return drivers.validate(user().username(), json.writeValueAsString(packageJson));
    }

    @PostMapping(value = "/drivers/conformance", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('INTEGRATION_TESTER')")
    public DriverService.Conformance conformance(@RequestBody JsonNode request) {
        JsonNode pkg = request.has("packageJson") ? request.get("packageJson") : request;
        return drivers.conformance(user().username(), pkg.isString() ? pkg.asString() : json.writeValueAsString(pkg));
    }

    @PostMapping(value = "/drivers/sign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('INTEGRATION_TESTER')")
    public Map<String, Object> requestSignature(@RequestBody JsonNode request) {
        JsonNode pkg = request.has("packageJson") ? request.get("packageJson") : request;
        return drivers.requestSignature(user().username(), pkg.isString() ? pkg.asString() : json.writeValueAsString(pkg));
    }

    @PostMapping("/drivers/sign/{requestId}/approve")
    @PreAuthorize("hasRole('DEVICE_APPROVER')")
    public Map<String, Object> approveSignature(@PathVariable UUID requestId) {
        return drivers.approveSignature(user().username(), requestId);
    }

    // ------------------------------------------------------------------ golden datasets and simulators

    @GetMapping("/golden-datasets")
    @PreAuthorize("hasAnyRole('INTEGRATION_TESTER','DEVICE_ADMIN')")
    public List<Map<String, Object>> golden(@RequestParam(required = false) @Pattern(regexp = "[A-Z_]{2,20}") String family) {
        user();
        return golden.list(family).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("datasetId", d.datasetId());
            m.put("family", d.family());
            m.put("adapter", d.adapter());
            m.put("packageId", d.packageId());
            m.put("kind", d.kind());
            m.put("description", d.description());
            return m;
        }).toList();
    }

    public record SimulatorRequest(@NotBlank @Size(max = 160) String datasetId, @Min(1) @Max(500) Integer count,
                                   @Pattern(regexp = "[a-z0-9]+(\\.[a-z0-9\\-]+){1,4}") String driverPackageId,
                                   @Pattern(regexp = "\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}") String driverVersion, Boolean targetTestForm,
                                   @Min(1) @Max(200) Integer ratePerSecond,
                                   @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId) {
    }

    @PostMapping("/simulators/{adapter}/run")
    @PreAuthorize("hasAnyRole('INTEGRATION_TESTER','DEVICE_ADMIN')")
    public SimulatorService.RunResult simulate(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,20}") String adapter,
                                               @RequestBody @jakarta.validation.Valid SimulatorRequest r) {
        return simulators.run(user().username(), adapter, r.datasetId(), r.count(), r.driverPackageId(), r.driverVersion(),
                Boolean.TRUE.equals(r.targetTestForm()), null, r.deviceInstanceId());
    }

    // ------------------------------------------------------------------ terminology, gates

    @GetMapping("/terminology/maps")
    @PreAuthorize(CLINICIANS_AND_TESTERS)
    public List<Terminology.Entry> maps(@RequestParam(required = false) @Pattern(regexp = "MDC|LOINC|LOCAL") String system,
                                        @RequestParam(required = false) @Size(max = 80) String code) {
        user();
        return terminology.entries(system, code);
    }

    @GetMapping("/terminology/versions")
    @PreAuthorize(CLINICIANS_AND_TESTERS)
    public List<Map<String, Object>> versions() {
        user();
        return terminology.versions();
    }

    @GetMapping("/terminology/predicted")
    @PreAuthorize(CLINICIANS_AND_TESTERS)
    public Map<String, Object> predicted(@RequestParam(defaultValue = "GLI_2012") @Pattern(regexp = "GLI_2012") String equation,
                                         @RequestParam @Min(3) @Max(95) double age, @RequestParam @Pattern(regexp = "M|F") String sex,
                                         @RequestParam @Min(90) @Max(230) double heightCm) {
        user();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("equation", equation);
        for (String p : List.of("FEV1", "FVC", "FEV1FVC")) {
            Gli2012.Coefficients c = terminology.gli(p, sex).orElseThrow(() -> ApiException.notFound("Equation"));
            Gli2012.Prediction pr = Gli2012.predict(c, age, heightCm);
            out.put(p, Map.of("predicted", round(pr.predicted()), "lln", round(pr.lln()), "l", round(pr.l()), "s", round(pr.s())));
        }
        out.put("release", terminology.releaseVersion());
        return out;
    }

    @GetMapping("/terminology/osha-age-correction")
    @PreAuthorize(CLINICIANS_AND_TESTERS)
    public Map<String, Object> osha(@RequestParam @Pattern(regexp = "M|F") String sex, @RequestParam @Min(16) @Max(100) int age) {
        user();
        return terminology.oshaRow(sex, age).map(r -> Map.<String, Object>of("sex", sex, "age", r.age(), "corrections", r.asMap()))
                .orElseThrow(() -> ApiException.notFound("Age correction"));
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    @GetMapping("/gates")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','BIOMEDICAL')")
    public List<Map<String, Object>> gates() {
        user();
        return ops.gates();
    }

    @GetMapping("/archive/{archiveId}/verify")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','AUDITOR')")
    public RawPayloadStore.Verification verify(@PathVariable UUID archiveId) {
        user();
        return ops.verifyArchive(archiveId);
    }

    // ------------------------------------------------------------------ historic retrieval, P6 connectors, outbound messages (round 2)

    public record HistoricRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId, Instant from, Instant to) {
    }

    /** Requests the results a device / data manager holds for a time range (gateway command RETRIEVE_HISTORY). */
    @PostMapping("/historic-retrievals")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER')")
    public ResponseEntity<Map<String, Object>> historic(@RequestBody @jakarta.validation.Valid HistoricRequest r) {
        return ResponseEntity.status(201).body(historic.request(user().username(), r.deviceInstanceId(), r.from(), r.to()));
    }

    @GetMapping("/historic-retrievals")
    @PreAuthorize(OPS)
    public List<Map<String, Object>> historicList(@RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        user();
        return historic.list(deviceInstanceId, limit);
    }

    @GetMapping("/historic-retrievals/{id}")
    @PreAuthorize(OPS)
    public Map<String, Object> historicGet(@PathVariable UUID id) {
        user();
        return historic.get(id);
    }

    /** P6 vendor-cloud connector status (schedule, last run, counts, allow-list and residency state – PHI-free). */
    @GetMapping("/ops/cloud-connectors")
    @PreAuthorize(OPS)
    public List<Map<String, Object>> cloudConnectors() {
        user();
        return cloud.status();
    }

    /** Polls one P6 connector now (outside its schedule). */
    @PostMapping("/ops/cloud-connectors/{deviceInstanceId}/poll")
    @PreAuthorize("hasRole('DEVICE_ADMIN')")
    public CloudPollService.Run pollNow(@PathVariable @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId) {
        user();
        return cloud.pollNow(deviceInstanceId);
    }

    /**
     * Outbound order / worklist / start messages for a capture session or a device (status and acknowledgement only – no message
     * content); commands not yet fetched by the gateway are listed as {@code pending}.
     */
    @GetMapping("/outbound")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','COLLECTOR')")
    public Map<String, Object> outbound(@RequestParam(required = false) UUID sessionId,
                                        @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9._\\-]{1,64}") String deviceInstanceId,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        user();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", outbound.list(sessionId, deviceInstanceId, limit));
        out.put("pending", sessionId == null ? List.of() : outbound.pendingForSession(sessionId));
        return out;
    }

    // ------------------------------------------------------------------ tenants (E31-F12)

    @GetMapping("/tenants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Map<String, Object>> tenants() {
        user();
        return tenants.tenants();
    }

    /**
     * @param catalogueScope  GROUP (shared group catalogue plus own rows) or TENANT (own rows only); unchanged / GROUP when omitted
     * @param residencyRegion data residency of the tenant (P6 residency check); unchanged / UK when omitted
     */
    public record QuotaRequest(@Min(1) @Max(100000) int messagesPerMinute, @Min(1) @Max(100000) int maxConnectors,
                               @Pattern(regexp = "GROUP|TENANT") String catalogueScope, @Pattern(regexp = "[A-Z]{2,10}") String residencyRegion) {
    }

    /** Creates or updates a tenant (quota, connector limit, catalogue scope, residency). */
    @PutMapping("/tenants/{code}/quota")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Map<String, Object> quota(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,30}") String code, @RequestBody @jakarta.validation.Valid QuotaRequest r) {
        return tenants.put(user().username(), code, r.messagesPerMinute(), r.maxConnectors(), r.catalogueScope(), r.residencyRegion());
    }
}
