package com.wellnessz.e06.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e06.certificate.CertificateService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F03 medical certificates: employee upload, clinic scan, partner e-certificates, validation, verification, HR validity view. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceCertificateController {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    private final CertificateService certificates;
    private final CurrentUserProvider users;

    public AbsenceCertificateController(CertificateService certificates, CurrentUserProvider users) {
        this.certificates = certificates;
        this.users = users;
    }

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files must be between 1 byte and 10 MB");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("upload_failed", "Upload could not be read");
        }
    }

    @PostMapping(value = "/me/absence/certificates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public CertificateService.SelfView upload(@RequestPart("file") MultipartFile file,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                              @RequestParam(required = false) UUID episodeId) {
        return certificates.employeeUpload(users.require(), bytes(file), fromDate, toDate, episodeId);
    }

    @GetMapping("/me/absence/certificates")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<CertificateService.SelfView> mine() {
        return certificates.mine(users.require());
    }

    @PostMapping(value = "/absence/certificates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public CertificateService.CertificateView scan(@RequestPart("file") MultipartFile file,
                                                   @RequestParam @Pattern(regexp = Patterns.EMP) String employeeId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                   @RequestParam(required = false) UUID episodeId) {
        return certificates.clinicScan(users.require(), employeeId, bytes(file), fromDate, toDate, episodeId);
    }

    public record PartnerCertificate(@NotBlank @Pattern(regexp = Patterns.EMP) String employeeRef,
                                     @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\- ]{2,60}") String partnerOrg,
                                     @NotBlank @Pattern(regexp = "[A-Za-z0-9_/\\-]{1,60}") String certificateNumber,
                                     @NotBlank @Size(max = 200) String issuerName,
                                     @NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{2,40}") String issuerRegistration,
                                     @NotNull LocalDate issueDate, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                                     @Pattern(regexp = "NOT_FIT|MAY_BE_FIT") String fitNote,
                                     @Size(max = 14_000_000) @Pattern(regexp = "[A-Za-z0-9+/=\\s]*") String pdfBase64) {
    }

    @PostMapping("/integration/absence/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INTEGRATION')")
    public CertificateService.CertificateView partner(@Valid @RequestBody PartnerCertificate r) {
        return certificates.partner(users.require().username(), new CertificateService.PartnerCommand(r.employeeRef(), r.partnerOrg(),
                r.certificateNumber(), r.issuerName(), r.issuerRegistration(), r.issueDate(), r.fromDate(), r.toDate(), r.fitNote(), r.pdfBase64()));
    }

    @GetMapping("/absence/certificates")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public List<CertificateService.CertificateView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.CERT_STATUS) String status,
                                                         @RequestParam(required = false) @Pattern(regexp = Patterns.EMP) String employeeId) {
        return certificates.list(users.require(), status, employeeId);
    }

    @GetMapping("/absence/certificates/{id}/content")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        DocumentService.Content c = certificates.content(users.require(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(c.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(c.bytes());
    }

    public record Checklist(Boolean issuerIdentified, Boolean registrationValid, Boolean datesConsistent, Boolean signaturePresent,
                            Boolean coversAbsence) {
        Map<String, Boolean> asMap() {
            Map<String, Boolean> m = new java.util.LinkedHashMap<>();
            m.put("issuerIdentified", issuerIdentified);
            m.put("registrationValid", registrationValid);
            m.put("datesConsistent", datesConsistent);
            m.put("signaturePresent", signaturePresent);
            m.put("coversAbsence", coversAbsence);
            m.values().removeIf(java.util.Objects::isNull);
            return m;
        }
    }

    public record ValidateRequest(@NotNull @Valid Checklist checklist, @NotBlank @Pattern(regexp = "VALID|INVALID|SUSPICIOUS") String outcome,
                                  @Size(max = 1000) String note) {
    }

    @PostMapping("/absence/certificates/{id}/validate")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public CertificateService.CertificateView validate(@PathVariable UUID id, @Valid @RequestBody ValidateRequest r) {
        return certificates.validate(users.require(), id, r.checklist().asMap(), r.outcome(), r.note());
    }

    public record CallRequest(@NotBlank @Size(max = 200) String calledParty, @NotBlank @Pattern(regexp = "CONFIRMED|NOT_CONFIRMED|NO_ANSWER") String outcome,
                              @Size(max = 1000) String note) {
    }

    @PostMapping("/absence/certificates/{id}/calls")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public CertificateService.CertificateView call(@PathVariable UUID id, @Valid @RequestBody CallRequest r) {
        return certificates.call(users.require(), id, r.calledParty(), r.outcome(), r.note());
    }

    public record VerifyRequest(@NotBlank @Pattern(regexp = "VERIFIED_GENUINE|VERIFIED_FRAUDULENT") String outcome, boolean escalateToHr) {
    }

    @PostMapping("/absence/certificates/{id}/verify")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public CertificateService.CertificateView verify(@PathVariable UUID id, @Valid @RequestBody VerifyRequest r) {
        return certificates.verify(users.require(), id, r.outcome(), r.escalateToHr());
    }

    @GetMapping("/workspace/absence/certificates")
    @PreAuthorize("hasAnyRole('HR','MANAGER')")
    public List<CertificateService.WorkspaceView> workspace(@RequestParam(required = false) @Pattern(regexp = Patterns.EMP) String employeeId) {
        return certificates.workspace(users.require(), employeeId);
    }
}
