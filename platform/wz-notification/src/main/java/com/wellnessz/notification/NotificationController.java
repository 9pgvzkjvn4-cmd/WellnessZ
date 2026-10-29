package com.wellnessz.notification;

import java.time.ZoneId;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.GlobalExceptionHandler;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    public record TestRequest(@NotBlank @Size(max = 320) String address, @NotNull Channel channel, Priority priority,
                              @Size(max = 64) String timeZone) {
    }

    public record Queued(String id, String status, java.time.Instant scheduledAt) {
    }

    @PostMapping("/test")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Queued test(@Valid @RequestBody TestRequest r) {
        Notification n = service.enqueue("test", r.address(), r.channel(), r.priority() == null ? Priority.NORMAL : r.priority(),
                "test.message", Map.of("firstName", "Administrator"),
                r.timeZone() == null ? ZoneId.of("UTC") : ZoneId.of(r.timeZone()));
        return new Queued(n.getId().toString(), n.getStatus().name(), n.getScheduledAt());
    }

    @ExceptionHandler(NotificationTemplates.ContentRuleViolation.class)
    public org.springframework.http.ProblemDetail contentViolation(NotificationTemplates.ContentRuleViolation e) {
        return GlobalExceptionHandler.problem(HttpStatus.UNPROCESSABLE_CONTENT, "content_rule_violation", e.getMessage());
    }
}
