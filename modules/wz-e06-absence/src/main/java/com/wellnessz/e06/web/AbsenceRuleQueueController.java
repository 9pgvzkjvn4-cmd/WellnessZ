package com.wellnessz.e06.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.wellnessz.e06.queue.AbsenceQueueService;
import com.wellnessz.e06.rules.AbsenceRuleService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F02 trigger rules (versioned configuration, test mode) and the early-intervention / LTA / AME queue. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceRuleQueueController {

    private final AbsenceRuleService rules;
    private final AbsenceQueueService queue;
    private final CurrentUserProvider users;

    public AbsenceRuleQueueController(AbsenceRuleService rules, AbsenceQueueService queue, CurrentUserProvider users) {
        this.rules = rules;
        this.queue = queue;
        this.users = users;
    }

    // ------------------------------------------------------------------ rules

    public record RuleRequest(@NotBlank @Pattern(regexp = Patterns.CODE) String code, @NotBlank @Size(max = 200) String name,
                              @Pattern(regexp = "[A-Z0-9_]{1,20}") String subsidiaryCode,
                              @NotBlank @Pattern(regexp = Patterns.RULE_TYPE) String ruleType,
                              @NotEmpty @Size(max = 4) Map<@Pattern(regexp = "[A-Za-z]{1,20}") String, Object> params,
                              @NotBlank @Pattern(regexp = Patterns.RULE_ACTION) String action, Boolean active) {
        AbsenceRuleService.RuleCommand command() {
            return new AbsenceRuleService.RuleCommand(code, name, subsidiaryCode, ruleType, params, action, active);
        }
    }

    @GetMapping("/absence/rules")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasAnyRole('OH_ADMIN','HR')")
    public List<AbsenceRuleService.RuleView> list() {
        return rules.list();
    }

    @PostMapping("/absence/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OH_ADMIN')")
    public AbsenceRuleService.RuleView create(@Valid @RequestBody RuleRequest r) {
        return rules.create(users.require(), r.command());
    }

    @PutMapping("/absence/rules/{id}")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public AbsenceRuleService.RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleRequest r) {
        return rules.update(users.require(), id, r.command());
    }

    @DeleteMapping("/absence/rules/{id}")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public AbsenceRuleService.RuleView deactivate(@PathVariable UUID id) {
        return rules.deactivate(users.require(), id);
    }

    public record TestRule(@Pattern(regexp = Patterns.CODE) String code, @Size(max = 200) String name,
                           @Pattern(regexp = "[A-Z0-9_]{1,20}") String subsidiaryCode,
                           @NotBlank @Pattern(regexp = Patterns.RULE_TYPE) String ruleType,
                           @NotEmpty @Size(max = 4) Map<@Pattern(regexp = "[A-Za-z]{1,20}") String, Object> params,
                           @NotBlank @Pattern(regexp = Patterns.RULE_ACTION) String action) {
    }

    public record TestRequest(@Valid TestRule rule, UUID ruleId, @Pattern(regexp = "[A-Z0-9_]{1,20}") String subsidiaryCode) {
    }

    @PostMapping("/absence/rules/test")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public AbsenceRuleService.TestResult test(@Valid @RequestBody TestRequest r) {
        AbsenceRuleService.RuleCommand unsaved = r.rule() == null ? null : new AbsenceRuleService.RuleCommand(r.rule().code(), r.rule().name(),
                r.rule().subsidiaryCode(), r.rule().ruleType(), r.rule().params(), r.rule().action(), true);
        return rules.test(users.require(), r.ruleId(), unsaved, r.subsidiaryCode());
    }

    // ------------------------------------------------------------------ queue

    @GetMapping("/absence/queue")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<AbsenceQueueService.QueueItemView> queue(@RequestParam(required = false) @Pattern(regexp = Patterns.QUEUE_STATUS) String status,
                                                         @RequestParam(required = false) @Pattern(regexp = Patterns.RULE_ACTION) String action) {
        return queue.list(users.require(), status, action);
    }

    @PostMapping("/absence/queue/{id}/accept")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public AbsenceQueueService.QueueItemView accept(@PathVariable UUID id) {
        return queue.accept(users.require(), id);
    }

    public record ContactRequest(@NotBlank @Pattern(regexp = Patterns.CHANNEL) String channel,
                                 @NotBlank @Pattern(regexp = Patterns.CONTACT_OUTCOME) String outcome, @Size(max = 2000) String note) {
    }

    @PostMapping("/absence/queue/{id}/contact")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public AbsenceQueueService.QueueItemView contact(@PathVariable UUID id, @Valid @RequestBody ContactRequest r) {
        return queue.contact(users.require(), id, r.channel(), r.outcome(), r.note());
    }

    public record CloseRequest(@NotNull @NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/absence/queue/{id}/close")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public AbsenceQueueService.QueueItemView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest r) {
        return queue.close(users.require(), id, r.reason());
    }
}
