package com.wellnessz.e01.notify;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.notification.Channel;
import com.wellnessz.security.audit.AuditService;

/** Notification matrix (E01-BL-025): read by Nursing Head, maintained by OH admin after DPO / CMO sign-off. */
@RestController
@RequestMapping("/api/v1/intake/notification-matrix")
public class NotificationMatrixController {

    private final NotificationRuleRepository rules;
    private final AuditService audit;

    public NotificationMatrixController(NotificationRuleRepository rules, AuditService audit) {
        this.rules = rules;
        this.audit = audit;
    }

    public record RuleItem(@NotNull NotificationRule.Event event, @Pattern(regexp = "[A-Z_]{1,40}") String source,
                           @Pattern(regexp = "[A-Z_]{1,40}") String caseType, @NotNull NotificationRule.Recipient recipient,
                           @NotNull Channel channel, @NotBlank @Pattern(regexp = "[a-z.]{1,100}") String templateKey, boolean active) {
    }

    public record RuleView(Long id, NotificationRule.Event event, String source, String caseType, NotificationRule.Recipient recipient,
                           Channel channel, String templateKey, boolean active) {
        static RuleView of(NotificationRule r) {
            return new RuleView(r.getId(), r.getEvent(), r.getSource(), r.getCaseType(), r.getRecipient(), r.getChannel(),
                    r.getTemplateKey(), r.isActive());
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    public List<RuleView> list() {
        return rules.findAllByOrderByEventAscIdAsc().stream().map(RuleView::of).toList();
    }

    /** Replaces the whole matrix (the UI edits it as a table). */
    @PutMapping
    @PreAuthorize("hasRole('OH_ADMIN')")
    @Transactional
    public List<RuleView> replace(@Valid @RequestBody List<@Valid RuleItem> items) {
        if (items.size() > 200) {
            throw new IllegalArgumentException("Too many rules");
        }
        rules.deleteAllInBatch();
        items.forEach(i -> rules.save(new NotificationRule(i.event(), i.source(), i.caseType(), i.recipient(), i.channel(),
                i.templateKey(), i.active())));
        audit.record("NOTIFICATION_MATRIX_UPDATE", "CONFIG", "notification_rule", AuditService.Outcome.SUCCESS, "rules=" + items.size());
        return list();
    }
}
