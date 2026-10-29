package com.wellnessz.e02.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.e02.complexity.ComplexityService;
import com.wellnessz.e02.escalation.EscalationService;
import com.wellnessz.e02.model.DurationGuideline;
import com.wellnessz.e02.model.TriageProtocol;
import com.wellnessz.e02.model.TriageProtocolRepository;
import com.wellnessz.e02.qa.TriageAuditService;
import com.wellnessz.e02.safety.SafetyService;
import com.wellnessz.e02.triage.TriageService;
import com.wellnessz.security.identity.CurrentUserProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** E02 triage, safety, escalation, complexity and QA endpoints. */
@RestController
@RequestMapping("/api/v1")
public class TriageController {

    static final String OH = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')";

    private final TriageService triage;
    private final TriageProtocolRepository protocols;
    private final SafetyService safety;
    private final EscalationService escalations;
    private final ComplexityService complexity;
    private final TriageAuditService qa;
    private final OhCaseRepository cases;
    private final CurrentUserProvider users;
    private final JsonMapper json;

    public TriageController(TriageService triage, TriageProtocolRepository protocols, SafetyService safety,
                            EscalationService escalations, ComplexityService complexity, TriageAuditService qa,
                            OhCaseRepository cases, CurrentUserProvider users, JsonMapper json) {
        this.triage = triage;
        this.protocols = protocols;
        this.safety = safety;
        this.escalations = escalations;
        this.complexity = complexity;
        this.qa = qa;
        this.cases = cases;
        this.users = users;
        this.json = json;
    }

    // ---------------------------------------------------------------- protocols & triage

    public record ProtocolSummary(String code, int version, String title, String presentation, String status, String approvedBy,
                                  Instant approvedAt) {
        static ProtocolSummary of(TriageProtocol p) {
            return new ProtocolSummary(p.getCode(), p.getVersion(), p.getTitle(), p.getPresentation(), p.getStatus(), p.getApprovedBy(),
                    p.getApprovedAt());
        }
    }

    @GetMapping("/triage/protocols")
    @PreAuthorize(OH)
    public List<ProtocolSummary> protocols() {
        return protocols.findAllByOrderByCodeAscVersionDesc().stream().map(ProtocolSummary::of).toList();
    }

    @GetMapping("/triage/protocols/{code}")
    @PreAuthorize(OH)
    public JsonNode protocol(@PathVariable @Pattern(regexp = "[A-Z_]{1,40}") String code) {
        TriageProtocol p = triage.current(code);
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(p.getDefinition());
        node.put("version", p.getVersion());
        node.put("status", p.getStatus());
        return node;
    }

    public record ApproveRequest(Integer version) {
    }

    @PostMapping("/triage/protocols/{code}/approve")
    @PreAuthorize("hasRole('CMO')")
    public ProtocolSummary approve(@PathVariable @Pattern(regexp = "[A-Z_]{1,40}") String code,
                                   @RequestBody(required = false) ApproveRequest r) {
        return ProtocolSummary.of(triage.approve(users.require(), code, r == null ? null : r.version()));
    }

    public record CallRequest(@Size(max = 200) String caller, @Size(max = 40) String phone, Instant startedAt, Instant endedAt,
                              boolean consentToRemoteAssessment, Instant callBackAt) {
    }

    public record TriageRequest(@NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String protocolCode, Integer protocolVersion,
                                @Pattern(regexp = "IN_PERSON|TELEPHONE|DIGITAL") String mode, @NotNull Map<String, Object> answers,
                                Urgency overrideUrgency, @Size(max = 1000) String overrideReason, @Valid CallRequest call) {
    }

    @PostMapping("/cases/{id}/triage")
    public TriageService.TriageResult triage(@PathVariable UUID id, @Valid @RequestBody TriageRequest r) {
        if (r.answers().size() > 50) {
            throw new IllegalArgumentException("Too many answers");
        }
        TriageService.CallLog call = r.call() == null ? null : new TriageService.CallLog(r.call().caller(), r.call().phone(),
                r.call().startedAt(), r.call().endedAt(), r.call().consentToRemoteAssessment(), r.call().callBackAt());
        return triage.triage(users.require(), id, new TriageService.TriageRequest(r.protocolCode(), r.protocolVersion(), r.mode(),
                r.answers(), r.overrideUrgency(), r.overrideReason(), call));
    }

    @GetMapping("/cases/{id}/triage")
    public List<TriageService.TriageResult> triageHistory(@PathVariable UUID id) {
        return triage.history(users.require(), id);
    }

    @GetMapping("/triage/advice/{key}")
    @PreAuthorize(OH)
    public Map<String, String> advice(@PathVariable @Pattern(regexp = "[A-Z_]{1,60}") String key,
                                      @RequestParam(defaultValue = "en") @Pattern(regexp = "en|ar|fr") String lang) {
        return Map.of("adviceKey", key, "text", triage.adviceText(key, lang));
    }

    public record AdviceRequest(@NotBlank @Pattern(regexp = "[A-Z_]{1,60}") String adviceKey) {
    }

    @PostMapping("/cases/{id}/advice")
    @ResponseStatus(HttpStatus.CREATED)
    public TriageService.AdviceView sendAdvice(@PathVariable UUID id, @Valid @RequestBody AdviceRequest r) {
        return triage.sendAdvice(users.require(), id, r.adviceKey());
    }

    @GetMapping("/me/advice")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<TriageService.AdviceView> myAdvice() {
        return triage.myAdvice(users.require(), caseId -> cases.findById(caseId).map(OhCase::getCaseRef).orElse(null));
    }

    // ---------------------------------------------------------------- safety-critical

    public record ChecklistRequest(@NotNull Map<String, Object> answers, @NotBlank @Pattern(regexp = "CLEAR|EXCLUDED|RESTRICTED") String outcome) {
    }

    @GetMapping("/cases/{id}/checklists")
    public List<SafetyService.ChecklistView> checklists(@PathVariable UUID id) {
        return safety.list(users.require(), id);
    }

    @PostMapping("/cases/{id}/checklists/{code}")
    public SafetyService.ChecklistView checklist(@PathVariable UUID id, @PathVariable @Pattern(regexp = "FOOD_HANDLER|DRIVING|HEIGHT") String code,
                                                 @Valid @RequestBody ChecklistRequest r) {
        return safety.complete(users.require(), id, code, r.answers(), r.outcome());
    }

    @GetMapping("/status/crew")
    @PreAuthorize("hasRole('CREW_SCHEDULING')")
    public List<SafetyService.StatusRow> crew(@RequestParam @Size(max = 6000) String employeeIds) {
        return safety.crewStatus(Arrays.stream(employeeIds.split(",")).map(String::strip)
                .filter(s -> s.matches("[A-Za-z0-9\\-]{1,64}")).distinct().toList());
    }

    @GetMapping("/status/food-handlers")
    @PreAuthorize("hasRole('HYGIENE_MANAGER')")
    public List<SafetyService.StatusRow> foodHandlers(@RequestParam(defaultValue = "CATERING") @Pattern(regexp = "[A-Z0-9_]{1,64}") String department) {
        return safety.foodHandlerStatus(department);
    }

    // ---------------------------------------------------------------- escalations

    public record EscalationRequest(@NotBlank @Pattern(regexp = "PHYSICIAN|CMO|EXTERNAL_SPECIALIST|EMERGENCY_SERVICES") String level,
                                    @NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String reasonCode, @Size(max = 2000) String note) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 4000) String decision) {
    }

    @PostMapping("/cases/{id}/escalations")
    @ResponseStatus(HttpStatus.CREATED)
    public EscalationService.EscalationView escalate(@PathVariable UUID id, @Valid @RequestBody EscalationRequest r) {
        return escalations.raise(users.require(), id, r.level(), r.reasonCode(), r.note());
    }

    @GetMapping("/escalations")
    @PreAuthorize(OH)
    public List<EscalationService.EscalationView> escalations(@RequestParam(required = false) UUID caseId,
                                                              @RequestParam(required = false) @Pattern(regexp = "OPEN|ACKNOWLEDGED|DECIDED") String status,
                                                              @RequestParam(defaultValue = "false") boolean mine) {
        return escalations.list(users.require(), caseId, status, mine);
    }

    @PostMapping("/escalations/{id}/acknowledge")
    public EscalationService.EscalationView acknowledge(@PathVariable UUID id) {
        return escalations.acknowledge(users.require(), id);
    }

    @PostMapping("/escalations/{id}/decision")
    public EscalationService.EscalationView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return escalations.decide(users.require(), id, r.decision());
    }

    public record ConferenceRequest(@NotNull Instant scheduledAt, @Size(max = 20) List<EscalationService.Attendee> attendees,
                                    @Size(max = 4000) String agenda) {
    }

    public record MinutesRequest(@Size(max = 10000) String minutes, @Size(max = 4000) String decisions,
                                 @NotBlank @Pattern(regexp = "CLINICAL_ONLY|ALL_ATTENDEES") String visibility) {
    }

    @PostMapping("/cases/{id}/conferences")
    @ResponseStatus(HttpStatus.CREATED)
    public EscalationService.ConferenceView conference(@PathVariable UUID id, @Valid @RequestBody ConferenceRequest r) {
        return escalations.schedule(users.require(), id, r.scheduledAt(), r.attendees(), r.agenda());
    }

    @GetMapping("/cases/{id}/conferences")
    public List<EscalationService.ConferenceView> conferences(@PathVariable UUID id) {
        return escalations.conferences(users.require(), id);
    }

    @PostMapping("/conferences/{id}/minutes")
    public EscalationService.ConferenceView minutes(@PathVariable UUID id, @Valid @RequestBody MinutesRequest r) {
        return escalations.minutes(users.require(), id, r.minutes(), r.decisions(), r.visibility());
    }

    // ---------------------------------------------------------------- complexity

    public record RiskFlagsRequest(boolean comorbidity, boolean psychosocialScreenPositive, boolean claimOrLitigation,
                                   @Pattern(regexp = "[A-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?") String primaryIcd10) {
    }

    @GetMapping("/cases/{id}/complexity")
    public ComplexityService.Score complexity(@PathVariable UUID id) {
        return complexity.current(users.require(), id);
    }

    @PostMapping("/cases/{id}/complexity/recalculate")
    public ComplexityService.Score recalculate(@PathVariable UUID id) {
        return complexity.recalculate(users.require(), id);
    }

    @GetMapping("/cases/{id}/risk-flags")
    public ComplexityService.FlagsView riskFlags(@PathVariable UUID id) {
        return complexity.flags(users.require(), id);
    }

    @PutMapping("/cases/{id}/risk-flags")
    public ComplexityService.Score updateRiskFlags(@PathVariable UUID id, @Valid @RequestBody RiskFlagsRequest r) {
        return complexity.updateFlags(users.require(), id, new ComplexityService.FlagsView(r.comorbidity(),
                r.psychosocialScreenPositive(), r.claimOrLitigation(), r.primaryIcd10()));
    }

    @GetMapping("/triage/complexity/calibration")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','CMO')")
    public List<Map<String, Object>> calibration(@RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String month) {
        return complexity.calibration(month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month));
    }

    @GetMapping("/triage/duration-guidelines")
    @PreAuthorize(OH)
    public List<GuidelineDto> guidelines(@RequestParam(required = false) @Pattern(regexp = "[A-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?") String icd) {
        return complexity.guidelines(icd).stream().map(g -> new GuidelineDto(g.getIcdCode(), g.getTitle(), g.getExpectedDays(),
                g.getSource())).toList();
    }

    public record GuidelineDto(String icdCode, String title, int expectedDays, String source) {
    }

    // ---------------------------------------------------------------- triage QA (E02-BL-016)

    public record ReviewRequest(boolean agree, @Size(max = 2000) String comment) {
    }

    @PostMapping("/triage/audit/sample")
    @PreAuthorize("hasRole('NURSING_HEAD')")
    public List<TriageAuditService.SampleView> sample(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int percent) {
        Instant end = (to == null ? LocalDate.now(ZoneOffset.UTC) : to).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = from == null ? end.minusSeconds(31L * 86400) : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        return qa.sample(users.require(), start, end, percent);
    }

    @GetMapping("/triage/audit/samples")
    public List<TriageAuditService.SampleView> samples() {
        return qa.list(users.require());
    }

    @PostMapping("/triage/audit/samples/{id}/review")
    public TriageAuditService.SampleView review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return qa.review(users.require(), id, r.agree(), r.comment());
    }

    @GetMapping("/triage/audit/report")
    public Map<String, Object> report() {
        return qa.report(users.require());
    }
}
