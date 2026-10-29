package com.wellnessz.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NotificationRulesTest {

    private final NotificationTemplates templates = new NotificationTemplates();
    private final QuietHours quiet = new QuietHours(LocalTime.of(21, 0), LocalTime.of(7, 0));

    @Test
    void rendersAllowListedPlaceholdersOnly() {
        assertThat(templates.render("case.update", Map.of("firstName", "Sam", "caseRef", "OH-2026-000001", "portalLink", "https://oh.example")))
                .contains("Sam").contains("OH-2026-000001");
        assertThatThrownBy(() -> templates.render("case.update", Map.of("diagnosis", "x")))
                .isInstanceOf(NotificationTemplates.ContentRuleViolation.class);
    }

    @Test
    void blocksClinicalContentSmuggledInParameters() {
        assertThatThrownBy(() -> templates.render("case.update", Map.of("firstName", "Positive drug test")))
                .isInstanceOf(NotificationTemplates.ContentRuleViolation.class);
        assertThatThrownBy(() -> templates.render("case.update", Map.of("firstName", "F32.1")))
                .isInstanceOf(NotificationTemplates.ContentRuleViolation.class);
    }

    @Test
    void quietHoursDeferNormalButNotUrgentInRecipientZone() {
        Instant lateInSydney = Instant.parse("2026-10-29T12:30:00Z"); // 23:30 AEDT
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        Instant release = quiet.nextAllowed(lateInSydney, sydney, Priority.NORMAL);
        assertThat(release.atZone(sydney).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(quiet.nextAllowed(lateInSydney, sydney, Priority.URGENT)).isEqualTo(lateInSydney);
        assertThat(quiet.nextAllowed(lateInSydney, ZoneId.of("Europe/London"), Priority.NORMAL)).isEqualTo(lateInSydney);
    }
}
