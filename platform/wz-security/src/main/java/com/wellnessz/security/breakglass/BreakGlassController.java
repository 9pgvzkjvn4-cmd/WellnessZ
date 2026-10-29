package com.wellnessz.security.breakglass;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/break-glass")
public class BreakGlassController {

    private final BreakGlassService service;
    private final CurrentUserProvider users;

    public BreakGlassController(BreakGlassService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record GrantRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                               @NotBlank @Size(min = 20, max = 2000) String reason) {
    }

    public record ReviewRequest(@NotNull BreakGlassGrant.Review review, @Size(max = 2000) String note) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD')")
    public BreakGlassService.GrantView grant(@Valid @RequestBody GrantRequest r) {
        return service.grant(users.require(), r.employeeId(), r.reason());
    }

    @GetMapping
    @PreAuthorize("hasRole('DPO')")
    public List<BreakGlassService.GrantView> list(@RequestParam(required = false) @Pattern(regexp = "PENDING|APPROPRIATE|INAPPROPRIATE") String status) {
        return service.list(status);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('DPO')")
    public BreakGlassService.GrantView review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return service.review(users.require(), id, r.review(), r.note());
    }

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('DPO','CMO')")
    public BreakGlassService.MonthlyReport report(@RequestParam @Pattern(regexp = "\\d{4}-\\d{2}") String month) {
        return service.report(YearMonth.parse(month));
    }
}
