package com.wellnessz.e04.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e04.preauth.PreauthService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F04 pre-authorisations, provider invoices and unit-level cost roll-ups. */
@RestController
@RequestMapping("/api/v1")
public class PreauthInvoiceController {

    static final String GROUP_BY = "SUBSIDIARY|DEPARTMENT|COST_CENTRE";

    private final PreauthService preauth;
    private final CurrentUserProvider users;

    public PreauthInvoiceController(PreauthService preauth, CurrentUserProvider users) {
        this.preauth = preauth;
        this.users = users;
    }

    @PostMapping("/cases/{caseId}/preauthorisations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS_OR_CLAIMS)
    public PreauthService.PreauthView create(@PathVariable UUID caseId, @Valid @RequestBody PreauthService.PreauthCommand cmd) {
        return preauth.create(users.require(), caseId, cmd);
    }

    @PostMapping("/preauthorisations/{id}/submit")
    @PreAuthorize(Spel.CLINICIANS_OR_CLAIMS)
    public PreauthService.PreauthView submit(@PathVariable UUID id) {
        return preauth.submit(users.require(), id);
    }

    @PostMapping("/preauthorisations/{id}/decision")
    @PreAuthorize("hasRole('CLAIMS_COORDINATOR')")
    public PreauthService.PreauthView decide(@PathVariable UUID id, @Valid @RequestBody PreauthService.DecisionCommand cmd) {
        return preauth.decideAsCoordinator(users.require(), id, cmd);
    }

    @PostMapping("/integration/preauthorisations/{ref}/decision")
    @PreAuthorize(Spel.INTEGRATION)
    public PreauthService.PreauthView decideIntegration(@PathVariable @Pattern(regexp = "PA-\\d{4}-\\d{6}") String ref,
                                                        @Valid @RequestBody PreauthService.DecisionCommand cmd) {
        return preauth.decideByRef(users.require().username(), ref, cmd);
    }

    @GetMapping("/preauthorisations")
    @PreAuthorize(Spel.OH_OR_CLAIMS)
    public List<PreauthService.PreauthView> list(
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|SUBMITTED|APPROVED|PARTIALLY_APPROVED|DECLINED|EXPIRED|CANCELLED") String status) {
        return preauth.list(users.require(), status);
    }

    @GetMapping("/cases/{caseId}/preauthorisations")
    @PreAuthorize(Spel.OH_OR_CLAIMS)
    public List<PreauthService.PreauthView> byCase(@PathVariable UUID caseId) {
        return preauth.byCase(users.require(), caseId);
    }

    @GetMapping("/cases/{caseId}/invoices")
    @PreAuthorize("hasAnyRole(" + Spel.OH + ",'CLAIMS_COORDINATOR','FINANCE')")
    public List<PreauthService.InvoiceView> invoices(@PathVariable UUID caseId) {
        return preauth.invoicesByCase(users.require(), caseId);
    }

    @PostMapping("/cases/{caseId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLAIMS_FINANCE)
    public PreauthService.InvoiceView createInvoice(@PathVariable UUID caseId, @Valid @RequestBody PreauthService.InvoiceCommand cmd) {
        return preauth.createInvoice(users.require(), caseId, cmd);
    }

    public record InvoiceStatusRequest(@NotBlank @Pattern(regexp = "APPROVED|DISPUTED|PAID") String status, @Size(max = 1000) String note) {
    }

    @PostMapping("/invoices/{id}/status")
    @PreAuthorize(Spel.CLAIMS_FINANCE)
    public PreauthService.InvoiceView invoiceStatus(@PathVariable UUID id, @Valid @RequestBody InvoiceStatusRequest r) {
        return preauth.invoiceStatus(users.require(), id, r.status(), r.note());
    }

    @GetMapping("/treatment/costs")
    @PreAuthorize("hasAnyRole('FINANCE','CMO','CLAIMS_COORDINATOR')")
    public List<PreauthService.CostRow> costs(@RequestParam(defaultValue = "SUBSIDIARY") @Pattern(regexp = GROUP_BY) String groupBy,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return preauth.costs(users.require(), groupBy, from, to);
    }

    @GetMapping(value = "/treatment/costs/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('FINANCE','CMO','CLAIMS_COORDINATOR')")
    public ResponseEntity<byte[]> costsExport(@RequestParam(defaultValue = "SUBSIDIARY") @Pattern(regexp = GROUP_BY) String groupBy,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] csv = preauth.costsCsv(users.require(), groupBy, from, to).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("treatment-costs.csv").build().toString())
                .body(csv);
    }
}
