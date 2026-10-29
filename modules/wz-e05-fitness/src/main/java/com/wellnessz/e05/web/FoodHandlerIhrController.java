package com.wellnessz.e05.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.food.FoodHandlerService;
import com.wellnessz.e05.ihr.IhrService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E05-F05 food-handler exclusion / return / clearance and hygiene view; E05-F07 ill-health retirement / redeployment. */
@RestController
@RequestMapping("/api/v1")
public class FoodHandlerIhrController {

    private final FoodHandlerService food;
    private final IhrService ihr;
    private final CurrentUserProvider users;

    public FoodHandlerIhrController(FoodHandlerService food, IhrService ihr, CurrentUserProvider users) {
        this.food = food;
        this.ihr = ihr;
        this.users = users;
    }

    // ------------------------------------------------------------------ E05-F05

    public record ExcludeRequest(@NotBlank @Pattern(regexp = "GI_ILLNESS|SKIN_INFECTION|TYPHOID_PARATYPHOID|HEPATITIS_A|STEC|OTHER") String reasonCategory,
                                 LocalDate excludedFrom, LocalDate expectedReturn, @Size(max = 30) Map<String, Object> checklistAnswers) {
    }

    @PostMapping("/cases/{id}/food-handler/exclude")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public FoodHandlerService.EpisodeView exclude(@PathVariable UUID id, @Valid @RequestBody ExcludeRequest r) {
        return food.exclude(users.require(), id, r.reasonCategory(), r.excludedFrom(), r.expectedReturn(), r.checklistAnswers());
    }

    @PostMapping("/cases/{id}/food-handler/return-request")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public FoodHandlerService.EpisodeView returnRequest(@PathVariable UUID id) {
        return food.requestReturn(users.require(), id);
    }

    public record ClearRequest(@NotNull @Size(max = 30) Map<String, Object> criteria, @Size(max = 2000) String note) {
    }

    @PostMapping("/cases/{id}/food-handler/clear")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO')")
    public FoodHandlerService.EpisodeView clear(@PathVariable UUID id, @Valid @RequestBody ClearRequest r) {
        return food.clear(users.require(), id, r.criteria(), r.note());
    }

    @GetMapping("/cases/{id}/food-handler")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<FoodHandlerService.EpisodeView> episodes(@PathVariable UUID id) {
        return food.forCase(users.require(), id);
    }

    @GetMapping("/fitness/food-handlers")
    @PreAuthorize("hasAnyRole('HYGIENE_MANAGER','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')")
    public List<FoodHandlerService.HygieneRow> foodHandlers(
            @RequestParam(defaultValue = "CATERING") @Pattern(regexp = "[A-Z0-9_]{1,64}") String department) {
        return food.department(users.require(), department);
    }

    // ------------------------------------------------------------------ E05-F07

    public record IhrRequest(@NotBlank @Pattern(regexp = "ILL_HEALTH_RETIREMENT|REDEPLOYMENT") String type,
                             @NotBlank @Size(max = 8000) String findings, @NotBlank @Size(max = 4000) String recommendation,
                             @Size(max = 200) String policyReference) {
    }

    @PostMapping("/cases/{id}/ihr-assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PHYSICIANS_SPEL)
    public IhrService.IhrView assess(@PathVariable UUID id, @Valid @RequestBody IhrRequest r) {
        return ihr.assess(users.require(), id, r.type(), r.findings(), r.recommendation(), r.policyReference());
    }

    public record CmoDecisionRequest(@NotNull Boolean approve, @NotBlank @Size(max = 4000) String decisionText, @Size(max = 4000) String note) {
    }

    @PostMapping("/ihr-assessments/{id}/cmo-decision")
    @PreAuthorize("hasRole('CMO')")
    public IhrService.IhrView cmoDecision(@PathVariable UUID id, @Valid @RequestBody CmoDecisionRequest r) {
        return ihr.cmoDecision(users.require(), id, r.approve(), r.decisionText(), r.note());
    }

    public record AppealRequest(@NotBlank @Size(max = 4000) String grounds, @Size(max = 200) String secondOpinionBy) {
    }

    @PostMapping("/ihr-assessments/{id}/appeal")
    @PreAuthorize("hasAnyRole('HR','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')")
    public IhrService.IhrView appeal(@PathVariable UUID id, @Valid @RequestBody AppealRequest r) {
        return ihr.appeal(users.require(), id, r.grounds(), r.secondOpinionBy());
    }

    public record AppealDecisionRequest(@NotNull Boolean upheld, @NotBlank @Size(max = 4000) String decisionText) {
    }

    @PostMapping("/ihr-assessments/{id}/appeal-decision")
    @PreAuthorize("hasRole('CMO')")
    public IhrService.IhrView appealDecision(@PathVariable UUID id, @Valid @RequestBody AppealDecisionRequest r) {
        return ihr.appealDecision(users.require(), id, r.upheld(), r.decisionText());
    }

    @GetMapping("/cases/{id}/ihr-assessments")
    @PreAuthorize("hasAnyRole('HR','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')")
    public List<?> ihrAssessments(@PathVariable UUID id) {
        return ihr.forCase(users.require(), id);
    }
}
