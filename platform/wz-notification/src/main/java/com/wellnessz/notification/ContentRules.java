package com.wellnessz.notification;

import java.util.Locale;
import java.util.regex.Pattern;

/** Last-line defence: rejects rendered messages that look like they contain clinical content. */
final class ContentRules {

    private static final Pattern ICD_CODE = Pattern.compile("\\b[A-TV-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?\\b");
    private static final Pattern CLINICAL_TERMS = Pattern.compile(
            "\\b(diagnos\\w*|positive|negative|result\\w*|depress\\w*|anxiety|alcohol|drug|hiv|pregnan\\w*|cancer|psychiatr\\w*|medication)\\b");

    private ContentRules() {
    }

    static void assertNoClinicalContent(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (CLINICAL_TERMS.matcher(lower).find() || ICD_CODE.matcher(text).find()) {
            throw new NotificationTemplates.ContentRuleViolation("Message appears to contain clinical content");
        }
    }
}
