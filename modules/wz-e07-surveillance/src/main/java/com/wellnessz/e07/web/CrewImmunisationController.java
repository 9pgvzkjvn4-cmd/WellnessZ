package com.wellnessz.e07.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

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

import com.wellnessz.common.error.ApiException;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e07.crew.CrewCertificateService;
import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E07-F04 crew aeromedical certificates and E07-F05 immunisation & titre registry. */
@RestController
@RequestMapping("/api/v1")
public class CrewImmunisationController {

    private final CrewCertificateService crew;
    private final ImmunisationService immunisation;
    private final CurrentUserProvider users;

    public CrewImmunisationController(CrewCertificateService crew, ImmunisationService immunisation, CurrentUserProvider users) {
        this.crew = crew;
        this.immunisation = immunisation;
        this.users = users;
    }

    // ------------------------------------------------------------------ E07-F04

    @GetMapping("/surveillance/crew/exam-template")
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public List<CrewCertificateService.TemplateSection> template(@RequestParam @Pattern(regexp = "CLASS1|CLASS2|LAPL|CABIN_CREW") String type) {
        return crew.examTemplate(type);
    }

    @PostMapping("/surveillance/crew/examinations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('AME')")
    public CrewCertificateService.CrewCertificateView examine(@Valid @RequestBody CrewCertificateService.ExamCommand cmd) {
        return crew.examine(users.require(), cmd);
    }

    @GetMapping("/surveillance/crew/certificates")
    @PreAuthorize(Spel.CREW_STATUS)
    public List<CrewCertificateService.CrewCertificateView> certificates(
            @RequestParam(required = false) @Pattern(regexp = "VALID|EXPIRING|EXPIRED|SUSPENDED") String status,
            @RequestParam(required = false) @Min(0) @Max(3650) Integer expiringWithinDays) {
        return crew.list(users.require(), status, expiringWithinDays);
    }

    @GetMapping("/surveillance/crew/without-valid-medical")
    @PreAuthorize(Spel.CREW_STATUS)
    public List<CrewCertificateService.WithoutMedical> withoutValidMedical() {
        return crew.withoutValidMedical(users.require());
    }

    @PostMapping("/surveillance/crew/certificates/import")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('AME','INTEGRATION')")
    public CrewCertificateService.CrewCertificateView importCertificate(@Valid @RequestBody CrewCertificateService.ImportCommand cmd) {
        return crew.importExternal(users.require().username(), cmd);
    }

    @GetMapping("/surveillance/crew/authority-report")
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public ResponseEntity<byte[]> authorityReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        byte[] csv = crew.authorityReport(users.require(), from, to).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"crew-authority-report.csv\"").body(csv);
    }

    // ------------------------------------------------------------------ E07-F05

    @GetMapping("/surveillance/immunisation/requirements")
    @PreAuthorize("hasAnyRole(" + Spel.OH + ",'OH_ADMIN')")
    public List<ImmunisationService.RequirementView> requirements() {
        return immunisation.requirements();
    }

    @PutMapping("/surveillance/immunisation/requirements/{id}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    public ImmunisationService.RequirementView putRequirement(@PathVariable UUID id, @Valid @RequestBody ImmunisationService.RequirementCommand cmd) {
        return immunisation.putRequirement(users.require(), id, cmd);
    }

    @GetMapping("/surveillance/immunisation/due")
    @PreAuthorize(Spel.DUE_READ)
    public List<ImmunisationService.DueRow> due(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,40}") String vaccine,
                                                @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,20}") String subsidiary,
                                                @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String department) {
        return immunisation.due(users.require(), vaccine, subsidiary, department);
    }

    @GetMapping("/surveillance/immunisation/employees/{employeeId}")
    @PreAuthorize("hasAnyRole(" + Spel.OH + ",'HYGIENE_MANAGER')")
    public List<ImmunisationService.VaccineStatus> employee(@PathVariable @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId) {
        return immunisation.employee(users.require(), employeeId);
    }

    @PostMapping("/surveillance/immunisation/administrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ImmunisationService.AdministrationView administer(@Valid @RequestBody ImmunisationService.AdministrationCommand cmd) {
        return immunisation.administer(users.require(), cmd);
    }

    @PostMapping("/surveillance/immunisation/administrations/batch")
    @PreAuthorize(Spel.CLINICIANS)
    public ImmunisationService.BatchResult batch(@Valid @RequestBody ImmunisationService.BatchCommand cmd) {
        return immunisation.batch(users.require(), cmd);
    }

    @PostMapping("/surveillance/immunisation/titres")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ImmunisationService.TitreResult titre(@Valid @RequestBody ImmunisationService.TitreCommand cmd) {
        return immunisation.titre(users.require(), cmd);
    }

    @GetMapping("/me/immunisations")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<ImmunisationService.VaccineStatus> mine() {
        CurrentUser user = users.require();
        if (user.employeeId() == null) {
            throw ApiException.forbidden("No employee number on the session");
        }
        return immunisation.mine(user.employeeId());
    }

    @PostMapping("/me/immunisations/certificate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public DocumentService.DocumentView certificate() {
        return immunisation.certificate(users.require());
    }
}
