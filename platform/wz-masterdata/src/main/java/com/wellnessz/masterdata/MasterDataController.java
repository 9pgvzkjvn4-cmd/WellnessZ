package com.wellnessz.masterdata;

import java.time.Clock;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/masterdata")
public class MasterDataController {

    private static final String ADMIN = "hasAnyRole('OH_ADMIN','PLATFORM_ADMIN','INTEGRATION')";
    private static final String CODE = "[A-Z0-9_\\-]{1,64}";

    private final OrgUnitRepository orgUnits;
    private final JobCodeRepository jobCodes;
    private final HazardRepository hazards;
    private final ConfigService config;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public MasterDataController(OrgUnitRepository orgUnits, JobCodeRepository jobCodes, HazardRepository hazards,
                                ConfigService config, CurrentUserProvider users, AuditService audit, Clock clock) {
        this.orgUnits = orgUnits;
        this.jobCodes = jobCodes;
        this.hazards = hazards;
        this.config = config;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public record OrgUnitRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name,
                                 @Pattern(regexp = CODE) String parentCode, @NotBlank @Size(max = 40) String unitType,
                                 Boolean active) {
    }

    public record JobCodeRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String title,
                                 boolean safetyCritical, @Size(max = 1000) String hazardCodes) {
    }

    public record HazardRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name,
                                @NotBlank @Size(max = 60) String category, boolean surveillanceRequired,
                                Integer surveillanceIntervalMonths) {
    }

    public record ConfigRequest(@NotBlank @Size(max = 100_000) String value, @Size(max = 500) String description) {
    }

    @GetMapping("/org-units")
    public List<OrgUnitView> orgUnits() {
        return orgUnits.findAll(Sort.by("path")).stream().map(OrgUnitView::of).toList();
    }

    @PostMapping("/org-units")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrgUnitView upsertOrgUnit(@Valid @RequestBody OrgUnitRequest r) {
        String parentPath = "";
        if (r.parentCode() != null) {
            parentPath = orgUnits.findById(r.parentCode()).map(OrgUnit::getPath)
                    .orElseThrow(() -> ApiException.badRequest("unknown_parent", "Parent org unit does not exist"));
        }
        String path = parentPath + "/" + r.code();
        boolean active = r.active() == null || r.active();
        OrgUnit unit = orgUnits.findById(r.code()).map(u -> {
            u.update(r.name(), r.parentCode(), path, r.unitType(), active, clock.instant());
            return u;
        }).orElseGet(() -> orgUnits.save(new OrgUnit(r.code(), r.name(), r.parentCode(), path, r.unitType(), clock.instant())));
        audit.record("MASTERDATA_UPSERT", "ORG_UNIT", r.code(), AuditService.Outcome.SUCCESS, null);
        return OrgUnitView.of(unit);
    }

    @GetMapping("/job-codes")
    public List<JobCodeView> jobCodes() {
        return jobCodes.findAll(Sort.by("code")).stream().map(JobCodeView::of).toList();
    }

    @PostMapping("/job-codes")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public JobCodeView upsertJobCode(@Valid @RequestBody JobCodeRequest r) {
        JobCode saved = jobCodes.save(new JobCode(r.code(), r.title(), r.safetyCritical(), r.hazardCodes()));
        audit.record("MASTERDATA_UPSERT", "JOB_CODE", r.code(), AuditService.Outcome.SUCCESS, null);
        return JobCodeView.of(saved);
    }

    @GetMapping("/hazards")
    public List<HazardView> hazards() {
        return hazards.findAll(Sort.by("code")).stream().map(HazardView::of).toList();
    }

    @PostMapping("/hazards")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public HazardView upsertHazard(@Valid @RequestBody HazardRequest r) {
        Hazard saved = hazards.save(new Hazard(r.code(), r.name(), r.category(), r.surveillanceRequired(), r.surveillanceIntervalMonths()));
        audit.record("MASTERDATA_UPSERT", "HAZARD", r.code(), AuditService.Outcome.SUCCESS, null);
        return HazardView.of(saved);
    }

    @GetMapping("/config/{key}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','PLATFORM_ADMIN')")
    public ConfigEntryView getConfig(@PathVariable @Pattern(regexp = "[a-z0-9._\\-]{1,200}") String key) {
        return config.get(key).map(v -> new ConfigEntryView(key, v)).orElseThrow(() -> ApiException.notFound("Config entry"));
    }

    @PutMapping("/config/{key}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','PLATFORM_ADMIN')")
    public ConfigEntryView putConfig(@PathVariable @Pattern(regexp = "[a-z0-9._\\-]{1,200}") String key,
                                     @Valid @RequestBody ConfigRequest r) {
        ConfigEntry e = config.put(key, r.value(), r.description(), users.actorOrSystem());
        return new ConfigEntryView(e.getKey(), e.getValue());
    }

    public record ConfigEntryView(String key, String value) {
    }

    public record OrgUnitView(String code, String name, String parentCode, String path, String unitType, boolean active,
                              java.time.Instant updatedAt) {
        static OrgUnitView of(OrgUnit u) {
            return new OrgUnitView(u.getCode(), u.getName(), u.getParentCode(), u.getPath(), u.getUnitType(), u.isActive(),
                    u.getUpdatedAt());
        }
    }

    public record JobCodeView(String code, String title, boolean safetyCritical, String hazardCodes) {
        static JobCodeView of(JobCode j) {
            return new JobCodeView(j.getCode(), j.getTitle(), j.isSafetyCritical(), j.getHazardCodes());
        }
    }

    public record HazardView(String code, String name, String category, boolean surveillanceRequired,
                             Integer surveillanceIntervalMonths) {
        static HazardView of(Hazard h) {
            return new HazardView(h.getCode(), h.getName(), h.getCategory(), h.isSurveillanceRequired(),
                    h.getSurveillanceIntervalMonths());
        }
    }
}
