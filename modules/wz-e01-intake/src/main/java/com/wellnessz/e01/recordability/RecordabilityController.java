package com.wellnessz.e01.recordability;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;
import com.wellnessz.casekernel.model.CaseEnums.WorkRelated;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
public class RecordabilityController {

    private static final String OH = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')";

    private final RecordabilityService service;
    private final RecordabilityMappingRepository mappings;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public RecordabilityController(RecordabilityService service, RecordabilityMappingRepository mappings, CurrentUserProvider users,
                                   AuditService audit, Clock clock) {
        this.service = service;
        this.mappings = mappings;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public record ClassifyRequest(@NotNull Map<String, Object> answers, @NotNull WorkRelated workRelated, Recordability override,
                                  @Size(max = 40) String reasonCode, @Size(max = 2000) String rationale) {
    }

    public record MappingItem(@NotNull Recordability recordability, @NotBlank @Size(max = 100) String localCategory,
                              boolean caaReportable, @Size(max = 500) String description) {
    }

    @GetMapping("/intake/recordability/wizard")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','HSE_OFFICER')")
    public Map<String, Object> wizard() {
        return Map.of("questions", RecordabilityClassifier.QUESTIONS, "definitions", RecordabilityClassifier.DEFINITIONS,
                "reasonCodes", RecordabilityClassifier.REASON_CODES);
    }

    @PostMapping("/cases/{id}/recordability")
    public RecordabilityService.Result classify(@PathVariable UUID id, @Valid @RequestBody ClassifyRequest r) {
        if (r.answers().size() > 20) {
            throw ApiException.badRequest("too_many_answers", "Unexpected answers");
        }
        return service.classify(users.require(), id, r.answers(), r.workRelated(), r.override(), r.reasonCode(), r.rationale());
    }

    @GetMapping("/cases/{id}/recordability/history")
    public List<RecordabilityService.HistoryItem> history(@PathVariable UUID id) {
        return service.history(users.require(), id);
    }

    @GetMapping("/intake/recordability/report")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','CMO','HSE_OFFICER','OH_ADMIN')")
    public List<Map<String, Object>> report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }

    public record MappingView(Recordability recordability, String localCategory, boolean caaReportable, String description,
                              String updatedBy, java.time.Instant updatedAt) {
        static MappingView of(RecordabilityMapping m) {
            return new MappingView(m.getRecordability(), m.getLocalCategory(), m.isCaaReportable(), m.getDescription(),
                    m.getUpdatedBy(), m.getUpdatedAt());
        }
    }

    @GetMapping("/intake/recordability/mapping")
    @PreAuthorize(OH)
    public List<MappingView> mapping() {
        return mappings.findAll().stream().map(MappingView::of).toList();
    }

    @PutMapping("/intake/recordability/mapping")
    @PreAuthorize("hasRole('OH_ADMIN')")
    @Transactional
    public List<MappingView> updateMapping(@Valid @RequestBody List<@Valid MappingItem> items) {
        for (MappingItem i : items) {
            RecordabilityMapping m = mappings.findById(i.recordability()).orElseThrow(() -> ApiException.notFound("Mapping"));
            m.update(i.localCategory(), i.caaReportable(), i.description(), users.actorOrSystem(), clock.instant());
        }
        audit.record("RECORDABILITY_MAPPING_UPDATE", "CONFIG", "recordability_mapping", AuditService.Outcome.SUCCESS, null);
        return mapping();
    }
}
