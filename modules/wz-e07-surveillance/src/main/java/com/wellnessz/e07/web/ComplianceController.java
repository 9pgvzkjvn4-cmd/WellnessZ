package com.wellnessz.e07.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e07.compliance.AuditPackService;
import com.wellnessz.e07.compliance.ComplianceService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E07-F08 compliance dashboards and audit packs. */
@RestController
@RequestMapping("/api/v1/surveillance")
public class ComplianceController {

    private final ComplianceService compliance;
    private final AuditPackService packs;
    private final CurrentUserProvider users;

    public ComplianceController(ComplianceService compliance, AuditPackService packs, CurrentUserProvider users) {
        this.compliance = compliance;
        this.packs = packs;
        this.users = users;
    }

    @GetMapping("/compliance")
    @PreAuthorize(Spel.COMPLIANCE)
    public ComplianceService.Compliance compliance(@RequestParam(required = false) @Pattern(regexp = "PROGRAMME|SUBSIDIARY|DEPARTMENT") String groupBy,
                                                   @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,40}") String programme) {
        return compliance.compliance(users.require(), groupBy, programme);
    }

    @GetMapping("/compliance/trend")
    @PreAuthorize(Spel.COMPLIANCE)
    public List<ComplianceService.TrendPoint> trend(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,40}") String programme) {
        return compliance.trend(users.require(), programme);
    }

    @GetMapping("/compliance/overdue")
    @PreAuthorize(Spel.OH_TEAM)
    public List<ComplianceService.OverdueRow> overdue(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,40}") String programme,
                                                      @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String department) {
        return compliance.overdue(users.require(), programme, department);
    }

    @GetMapping("/audit-packs/templates")
    @PreAuthorize(Spel.AUDIT_PACKS)
    public List<AuditPackService.Template> templates() {
        return packs.templates();
    }

    public record PackRequest(@NotBlank @Pattern(regexp = "IOSA|CAA|FOOD_SAFETY|LABOUR_INSPECTORATE") String template,
                              @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String programmeCode, @NotNull LocalDate from, @NotNull LocalDate to,
                              Boolean anonymise) {
    }

    @PostMapping("/audit-packs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.AUDIT_PACKS)
    public AuditPackService.PackView generate(@Valid @RequestBody PackRequest r) {
        return packs.generate(users.require(), r.template(), r.programmeCode(), r.from(), r.to(), !Boolean.FALSE.equals(r.anonymise()));
    }

    @GetMapping("/audit-packs/{id}/content")
    @PreAuthorize(Spel.AUDIT_PACKS)
    public ResponseEntity<byte[]> content(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") @Pattern(regexp = "pdf|csv") String format) {
        AuditPackService.Content c = packs.content(users.require(), id, format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + c.fileName() + "\"").body(c.bytes());
    }
}
