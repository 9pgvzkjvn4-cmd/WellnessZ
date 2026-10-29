package com.wellnessz.e04.web;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

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

import com.wellnessz.e04.medication.EPrescribingGateway;
import com.wellnessz.e04.medication.MedicationAdminService;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F05 clinic formulary, stock, medication administration, usage and e-prescribing status. */
@RestController
@RequestMapping("/api/v1")
public class MedicationController {

    private final MedicationAdminService medication;
    private final TreatmentSupport support;
    private final CurrentUserProvider users;

    public MedicationController(MedicationAdminService medication, TreatmentSupport support, CurrentUserProvider users) {
        this.medication = medication;
        this.support = support;
        this.users = users;
    }

    @GetMapping("/treatment/formulary")
    @PreAuthorize(Spel.OH_TEAM)
    public List<MedicationAdminService.FormularyView> formulary() {
        return medication.formulary();
    }

    @GetMapping("/treatment/formulary/{code}")
    @PreAuthorize(Spel.OH_TEAM)
    public MedicationAdminService.FormularyView item(@PathVariable @Pattern(regexp = Spel.CODE) String code) {
        return medication.item(code);
    }

    @PutMapping("/treatment/formulary/{code}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    public MedicationAdminService.FormularyView putItem(@PathVariable @Pattern(regexp = Spel.CODE) String code,
                                                        @Valid @RequestBody MedicationAdminService.FormularyCommand cmd) {
        return medication.putItem(users.require(), code, cmd);
    }

    @PostMapping("/treatment/stock")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('NURSING_HEAD','OH_CLINICIAN')")
    public MedicationAdminService.StockView receive(@Valid @RequestBody MedicationAdminService.StockCommand cmd) {
        return medication.receive(users.require(), cmd);
    }

    @GetMapping("/treatment/stock")
    @PreAuthorize(Spel.OH_TEAM)
    public List<MedicationAdminService.StockView> stock(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String siteCode) {
        return medication.stock(siteCode);
    }

    @PostMapping("/cases/{caseId}/medications/administer")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public MedicationAdminService.AdministrationView administer(@PathVariable UUID caseId,
                                                                @Valid @RequestBody MedicationAdminService.AdministerCommand cmd) {
        return medication.administer(users.require(), caseId, cmd);
    }

    @GetMapping("/cases/{caseId}/medications/administrations")
    @PreAuthorize(Spel.OH_TEAM)
    public List<MedicationAdminService.AdministrationView> administrations(@PathVariable UUID caseId) {
        return medication.administrations(users.require(), caseId);
    }

    @GetMapping("/treatment/medication-usage")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','CMO')")
    public List<MedicationAdminService.UsageRow> usage(@RequestParam(required = false) @Pattern(regexp = Spel.MONTH) String month) {
        return medication.usage(month == null ? YearMonth.from(support.today()) : YearMonth.parse(month));
    }

    @GetMapping("/treatment/eprescribing/status")
    @PreAuthorize(Spel.OH_TEAM)
    public EPrescribingGateway.Status eprescribing() {
        return medication.eprescribing();
    }
}
