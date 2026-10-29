package com.wellnessz.e14.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.YearMonth;
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

import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e14.catalogue.CatalogueService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E14-F01 / BL-010: appointment types, service catalogue, site resources, diary templates, internal charging. */
@RestController
@RequestMapping("/api/v1/scheduling")
public class CatalogueController {

    static final String CODE = "[A-Z0-9_]{1,40}";
    static final String SITE = "[A-Z0-9-]{1,40}";
    static final String MONTH = "\\d{4}-\\d{2}";

    private final CatalogueService catalogue;
    private final CurrentUserProvider users;

    public CatalogueController(CatalogueService catalogue, CurrentUserProvider users) {
        this.catalogue = catalogue;
        this.users = users;
    }

    @GetMapping("/appointment-types")
    @PreAuthorize("isAuthenticated()")
    public List<CatalogueService.AppointmentTypeView> types() {
        return catalogue.types();
    }

    @GetMapping("/service-catalogue")
    @PreAuthorize("isAuthenticated()")
    public List<CatalogueService.AppointmentTypeView> serviceCatalogue() {
        return catalogue.types().stream().filter(CatalogueService.AppointmentTypeView::active).toList();
    }

    public record TypeRequest(@NotBlank @Size(max = 200) String name, @NotNull @Min(5) @Max(480) Integer durationMinutes,
                              @Size(max = 10) List<@Pattern(regexp = "[A-Z_]{1,40}") String> clinicianRoles,
                              @Pattern(regexp = "CONSULT|AUDIO_BOOTH|TREATMENT|VACCINATION|TELEHEALTH") String roomType,
                              @Pattern(regexp = "AUDIOMETER|SPIROMETER|ECG|VISION_SCREENER|VACCINE_FRIDGE|OTHER") String deviceCategory,
                              Boolean telehealth, Boolean selfBookable, @Pattern(regexp = "[A-Z0-9-]{1,40}") String chargeCode,
                              @DecimalMin("0") @DecimalMax("100000") BigDecimal internalPrice, Boolean active) {
    }

    @PutMapping("/appointment-types/{code}")
    @PreAuthorize(E14Roles.CATALOGUE_ADMIN)
    public CatalogueService.AppointmentTypeView upsertType(@PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody TypeRequest r) {
        return catalogue.upsertType(users.require(), code, new CatalogueService.TypeCommand(r.name(), r.durationMinutes(), r.clinicianRoles(),
                r.roomType(), r.deviceCategory(), Boolean.TRUE.equals(r.telehealth()), Boolean.TRUE.equals(r.selfBookable()), r.chargeCode(),
                r.internalPrice(), r.active() == null || r.active()));
    }

    @GetMapping("/sites/{siteCode}/resources")
    @PreAuthorize(E14Roles.SITE_READ)
    public List<CatalogueService.ResourceView> resources(@PathVariable @Pattern(regexp = SITE) String siteCode) {
        return catalogue.resources(siteCode);
    }

    public record ResourceRequest(@NotBlank @Pattern(regexp = "CLINICIAN|ROOM|DEVICE") String type, @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String code,
                                  @NotBlank @Size(max = 200) String name, @Pattern(regexp = "[A-Za-z0-9._@-]{1,200}") String username,
                                  @Pattern(regexp = "CONSULT|AUDIO_BOOTH|TREATMENT|VACCINATION|TELEHEALTH") String roomType, UUID equipmentId,
                                  Boolean active) {
    }

    @PostMapping("/sites/{siteCode}/resources")
    @PreAuthorize(E14Roles.CATALOGUE_ADMIN)
    public CatalogueService.ResourceView upsertResource(@PathVariable @Pattern(regexp = SITE) String siteCode, @Valid @RequestBody ResourceRequest r) {
        return catalogue.upsertResource(users.require(), siteCode, new CatalogueService.ResourceCommand(r.type(), r.code(), r.name(), r.username(),
                r.roomType(), r.equipmentId(), r.active()));
    }

    @GetMapping("/sites/{siteCode}/templates")
    @PreAuthorize(E14Roles.SITE_READ)
    public List<CatalogueService.TemplateView> templates(@PathVariable @Pattern(regexp = SITE) String siteCode) {
        return catalogue.templates(siteCode);
    }

    public record TemplateRequest(@NotNull @Min(1) @Max(7) Integer weekday, @NotNull LocalTime start, @NotNull LocalTime end,
                                  @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,200}") String clinicianUsername,
                                  @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String roomCode,
                                  @Size(max = 40) List<@Pattern(regexp = CODE) String> appointmentTypes, Boolean telehealth) {
    }

    @PutMapping("/sites/{siteCode}/templates")
    @PreAuthorize(E14Roles.TEMPLATE_WRITE)
    public List<CatalogueService.TemplateView> replaceTemplates(@PathVariable @Pattern(regexp = SITE) String siteCode,
                                                                @RequestBody @Size(max = CatalogueService.MAX_TEMPLATES) List<@Valid TemplateRequest> rows) {
        return catalogue.replaceTemplates(users.require(), siteCode, rows.stream().map(r -> new CatalogueService.TemplateCommand(r.weekday(),
                r.start(), r.end(), r.clinicianUsername(), r.roomCode(), r.appointmentTypes(), Boolean.TRUE.equals(r.telehealth()))).toList());
    }

    static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (java.time.DateTimeException e) {
            throw com.wellnessz.common.error.ApiException.badRequest("invalid_month", "month must be YYYY-MM");
        }
    }

    @GetMapping("/charging/usage")
    @PreAuthorize(E14Roles.CHARGING)
    public List<CatalogueService.UsageRow> usage(@RequestParam @Pattern(regexp = MONTH) String month) {
        return catalogue.usage(month(month));
    }

    @GetMapping(value = "/charging/export", produces = "text/csv")
    @PreAuthorize(E14Roles.CHARGING)
    public ResponseEntity<String> export(@RequestParam @Pattern(regexp = MONTH) String month) {
        YearMonth m = month(month);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"oh-charging-" + m + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(CatalogueService.usageCsv(m, catalogue.usage(m)));
    }
}
