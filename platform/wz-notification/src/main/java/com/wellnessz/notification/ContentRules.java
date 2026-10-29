package com.wellnessz.notification;

import com.wellnessz.common.text.ClinicalTextDetector;

/** Last-line defence: rejects rendered messages that look like they contain clinical content. */
final class ContentRules {

    private ContentRules() {
    }

    static void assertNoClinicalContent(String text) {
        if (ClinicalTextDetector.looksClinical(text) || text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(positive|negative|results?|drug|alcohol)\\b.*")) {
            throw new NotificationTemplates.ContentRuleViolation("Message appears to contain clinical content");
        }
    }
}
