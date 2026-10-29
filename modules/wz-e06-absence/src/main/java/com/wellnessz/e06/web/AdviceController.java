package com.wellnessz.e06.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.advice.AdviceService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F05 management advice requests and OH advice reports (employee review before release). */
@RestController("absenceAdviceController")
@RequestMapping("/api/v1")
public class AdviceController {

    private final AdviceService advice;
    private final CurrentUserProvider users;

    public AdviceController(AdviceService advice, CurrentUserProvider users) {
        this.advice = advice;
        this.users = users;
    }

    @GetMapping("/absence/advice/questions")
    public List<AdviceService.Question> questions() {
        return advice.questions();
    }

    // ------------------------------------------------------------------ requester

    public record AdviceRequestBody(@NotBlank @Pattern(regexp = Patterns.EMP) String employeeId,
                                    @NotEmpty @Size(max = 8) List<@Pattern(regexp = Patterns.QUESTION) String> questionCodes,
                                    @Size(max = 2000) String context, UUID episodeId) {
    }

    @PostMapping("/workspace/advice-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public AdviceService.AdviceRequestView submit(@Valid @RequestBody AdviceRequestBody r) {
        return advice.submit(users.require(), r.employeeId(), r.questionCodes(), r.context(), r.episodeId());
    }

    @GetMapping("/workspace/advice-requests")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<AdviceService.AdviceRequestView> workspace() {
        return advice.workspace(users.require());
    }

    @PostMapping("/workspace/advice-requests/{id}/withdraw")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public AdviceService.AdviceRequestView withdraw(@PathVariable UUID id) {
        return advice.withdraw(users.require(), id);
    }

    // ------------------------------------------------------------------ OH

    @GetMapping("/absence/advice-requests")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<AdviceService.AdviceRequestView> ohList(@RequestParam(required = false) @Pattern(regexp = Patterns.ADVICE_STATUS) String status) {
        return advice.ohList(users.require(), status);
    }

    @PostMapping("/absence/advice-requests/{id}/accept")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public AdviceService.AdviceRequestView accept(@PathVariable UUID id) {
        return advice.accept(users.require(), id);
    }

    public record ReportBody(@NotBlank @Pattern(regexp = Patterns.FITNESS) String fitness, @Size(max = 2000) String adjustments,
                             @Size(max = 2000) String restrictions, @NotBlank @Pattern(regexp = Patterns.PROGNOSIS) String prognosis,
                             LocalDate expectedReturn, LocalDate reviewDate,
                             @Size(max = 8) Map<@Pattern(regexp = Patterns.QUESTION) String, @Size(max = 400) String> answers) {
    }

    @PutMapping("/absence/advice-requests/{id}/report")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public AdviceService.AdviceRequestView draft(@PathVariable UUID id, @Valid @RequestBody ReportBody r) {
        return advice.draft(users.require(), id, new AdviceService.ReportCommand(r.fitness(), r.adjustments(), r.restrictions(), r.prognosis(),
                r.expectedReturn(), r.reviewDate(), r.answers()));
    }

    @PostMapping("/absence/advice-requests/{id}/report/sign")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public AdviceService.AdviceRequestView sign(@PathVariable UUID id) {
        return advice.sign(users.require(), id);
    }

    // ------------------------------------------------------------------ employee

    @GetMapping("/me/advice-reports")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<AdviceService.EmployeeReport> mine() {
        return advice.mine(users.require());
    }

    public record DecisionBody(@NotBlank @Pattern(regexp = "CONSENT|DECLINE") String decision, @Size(max = 1000) String comment,
                               @Size(max = 1000) String declineReason) {
    }

    @PostMapping("/me/advice-reports/{id}/decision")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public AdviceService.EmployeeReport decide(@PathVariable UUID id, @Valid @RequestBody DecisionBody r) {
        return advice.decide(users.require(), id, r.decision(), r.comment(), r.declineReason());
    }
}
