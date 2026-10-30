package com.wellnessz.e10.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.e10.policy.PolicyService;
import com.wellnessz.e10.report.StatisticsService;
import com.wellnessz.e10.report.Suppression;
import com.wellnessz.e10.selection.SelectionService;
import com.wellnessz.e10.support.TestingViews.CoverageRow;
import com.wellnessz.e10.support.TestingViews.PolicyView;
import com.wellnessz.e10.support.TestingViews.PoolExclusionView;
import com.wellnessz.e10.support.TestingViews.PoolView;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.support.TestingViews.SelectionView;
import com.wellnessz.e10.support.TestingViews.VerifyView;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E10-F01 policies, pools and random selections with coverage; E10-F05 programme statistics and the annual report. */
@RestController("e10ProgrammeController")
@RequestMapping("/api/v1/testing")
public class TestingProgrammeController {

    private final PolicyService policies;
    private final SelectionService selections;
    private final StatisticsService statistics;
    private final ModuleRegistry modules;
    private final CurrentUserProvider users;

    public TestingProgrammeController(PolicyService policies, SelectionService selections, StatisticsService statistics, ModuleRegistry modules,
                                      CurrentUserProvider users) {
        this.policies = policies;
        this.selections = selections;
        this.statistics = statistics;
        this.modules = modules;
        this.users = users;
    }

    // ------------------------------------------------------------------ policies

    @GetMapping("/policies")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','CMO','SAFETY_MANAGER')")
    public List<PolicyView> policies() {
        modules.requireEnabled("E10");
        return policies.list();
    }

    @GetMapping("/policies/{roleGroup}")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','CMO','SAFETY_MANAGER')")
    public PolicyView policy(@PathVariable @Pattern(regexp = Patterns.ROLE_GROUP) String roleGroup) {
        return policies.get(roleGroup);
    }

    public record PolicyBody(@Size(min = 1, max = 20) List<@Pattern(regexp = Patterns.JOB_CODE) String> jobCodes,
                             @Size(min = 1, max = 3) List<@Pattern(regexp = Patterns.PANEL) String> panels,
                             @DecimalMin("0") @DecimalMax("100") BigDecimal randomDrugRatePct,
                             @DecimalMin("0") @DecimalMax("100") BigDecimal randomAlcoholRatePct,
                             @Min(1) @Max(24) Integer alcoholWindowHours, @Min(1) @Max(72) Integer drugWindowHours,
                             @Min(0) @Max(1440) Integer leadTimeMinutes, @Size(min = 2, max = 120) String jurisdiction, Boolean consentRequired,
                             @Pattern(regexp = Patterns.APPROVAL) String approvalStatus, @Size(max = 200) String approvedBy) {
    }

    @PutMapping("/policies/{roleGroup}")
    @PreAuthorize("hasRole('DA_PROGRAMME_MANAGER')")
    public PolicyView update(@PathVariable @Pattern(regexp = Patterns.ROLE_GROUP) String roleGroup, @Valid @RequestBody PolicyBody b) {
        return policies.update(users.require(), roleGroup, new PolicyService.PolicyUpdate(b.jobCodes(), b.panels(), b.randomDrugRatePct(),
                b.randomAlcoholRatePct(), b.alcoholWindowHours(), b.drugWindowHours(), b.leadTimeMinutes(), b.jurisdiction(), b.consentRequired(),
                b.approvalStatus(), b.approvedBy()));
    }

    // ------------------------------------------------------------------ pools

    @GetMapping("/pools")
    @PreAuthorize("hasRole('DA_PROGRAMME_MANAGER')")
    public List<PoolView> pools(@RequestParam(required = false) @Pattern(regexp = Patterns.ROLE_GROUP) String roleGroup) {
        modules.requireEnabled("E10");
        return policies.pools(users.require(), roleGroup);
    }

    public record ExclusionBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId,
                                @NotBlank @Pattern(regexp = Patterns.EXCLUSION) String reason, @NotNull LocalDate until) {
    }

    @PostMapping("/pools/{roleGroup}/exclusions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DA_PROGRAMME_MANAGER')")
    public PoolExclusionView exclude(@PathVariable @Pattern(regexp = Patterns.ROLE_GROUP) String roleGroup, @Valid @RequestBody ExclusionBody b) {
        return policies.exclude(users.require(), roleGroup, b.employeeId(), b.reason(), b.until());
    }

    // ------------------------------------------------------------------ selections

    public record SelectionBody(@NotBlank @Pattern(regexp = Patterns.ROLE_GROUP) String roleGroup, @NotBlank @Pattern(regexp = Patterns.PERIOD) String period,
                                @NotBlank @Pattern(regexp = Patterns.TEST_TYPE) String testType) {
    }

    @PostMapping("/selections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DA_PROGRAMME_MANAGER')")
    public SelectionView select(@Valid @RequestBody SelectionBody b) {
        modules.requireEnabled("E10");
        return selections.create(users.require(), b.roleGroup(), b.period(), b.testType());
    }

    @GetMapping("/selections")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','AUDITOR')")
    public List<SelectionView> selections(@RequestParam(required = false) @Pattern(regexp = Patterns.PERIOD) String period) {
        return selections.list(period);
    }

    @GetMapping("/selections/{id}/verify")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','AUDITOR')")
    public VerifyView verify(@PathVariable UUID id) {
        return selections.verify(id);
    }

    @PostMapping("/selections/{id}/notify")
    @PreAuthorize("hasRole('DA_PROGRAMME_MANAGER')")
    public List<Requirement> notifySelected(@PathVariable UUID id) {
        return selections.notify(users.require(), id);
    }

    @GetMapping("/coverage")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','CMO')")
    public List<CoverageRow> coverage(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        return selections.coverage(year == null ? thisYear() : year);
    }

    // ------------------------------------------------------------------ reporting

    @GetMapping("/statistics")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','CMO','SAFETY_MANAGER')")
    public List<Suppression.Cell> statistics(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        return statistics.statistics(year == null ? thisYear() : year);
    }

    @GetMapping("/reports/annual")
    @PreAuthorize("hasAnyRole('CMO','DA_PROGRAMME_MANAGER')")
    public ResponseEntity<byte[]> annual(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
                                         @RequestParam(defaultValue = "pdf") @Pattern(regexp = Patterns.FORMAT) String format) {
        StatisticsService.Report r = statistics.annual(year == null ? thisYear() : year, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(r.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(r.contentType())).body(r.content());
    }

    private static int thisYear() {
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }
}
