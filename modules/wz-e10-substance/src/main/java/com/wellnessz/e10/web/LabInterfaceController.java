package com.wellnessz.e10.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e10.collection.LabService;
import com.wellnessz.e10.support.TestingViews.LabAck;
import com.wellnessz.e10.support.TestingViews.LabOrder;
import com.wellnessz.e10.support.TestingViews.LabResult;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Laboratory interface (E10-F03, E15): only the machine client of the programme laboratory ({@code org_scope
 * /PARTNERS/TOXLAB}) – any other organisation or an unbound client is answered 404 and audited.
 */
@RestController("e10LabInterfaceController")
@RequestMapping("/api/v1/integration/testing")
public class LabInterfaceController {

    private final LabService lab;
    private final CurrentUserProvider users;

    public LabInterfaceController(LabService lab, CurrentUserProvider users) {
        this.lab = lab;
        this.users = users;
    }

    @GetMapping("/lab-orders")
    @PreAuthorize("hasRole('INTEGRATION')")
    public List<LabOrder> orders() {
        return lab.orders(users.require());
    }

    public record ResultLine(@NotBlank @Pattern(regexp = Patterns.ANALYTE) String analyte, @NotBlank @Pattern(regexp = Patterns.NUMBER) String cutoff,
                             @NotBlank @Pattern(regexp = Patterns.NUMBER) String value, @NotBlank @Pattern(regexp = Patterns.UNIT) String unit,
                             @NotBlank @Pattern(regexp = Patterns.OUTCOME) String outcome) {
    }

    public record ResultBody(@NotBlank @Pattern(regexp = Patterns.SPECIMEN_ID) String specimenId, @NotBlank @Pattern(regexp = Patterns.LAB_REF) String labRef,
                             @NotNull Instant receivedAt, @NotBlank @Pattern(regexp = Patterns.VALIDITY) String validity,
                             @Size(max = 30) List<@Valid @NotNull ResultLine> results) {
    }

    @PostMapping("/lab-results")
    @PreAuthorize("hasRole('INTEGRATION')")
    public LabAck results(@Valid @RequestBody ResultBody b) {
        List<LabResult> results = b.results() == null ? List.of()
                : b.results().stream().map(r -> new LabResult(r.analyte(), r.cutoff(), r.value(), r.unit(), r.outcome())).toList();
        return lab.results(users.require(), new LabService.ResultCommand(b.specimenId(), b.labRef(), b.receivedAt(), b.validity(), results));
    }
}
