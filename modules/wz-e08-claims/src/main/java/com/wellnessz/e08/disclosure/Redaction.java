package com.wellnessz.e08.disclosure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.common.error.ApiException;

/**
 * Text redaction for disclosure sets (E08-F03): every occurrence of each phrase is masked, case-insensitively and literally
 * (no regular-expression semantics), longest phrases first so that overlapping phrases are masked completely. The masked
 * text is re-rendered as a new REDACTED_COPY – the original document is never altered.
 */
public final class Redaction {

    public static final String MASK = "[REDACTED]";
    public static final int MAX_PHRASES = 50;
    public static final int MAX_PHRASE_LENGTH = 200;

    public record Result(String text, int replacements) {
    }

    private Redaction() {
    }

    public static List<String> validate(List<String> phrases) {
        if (phrases == null || phrases.isEmpty() || phrases.size() > MAX_PHRASES) {
            throw ApiException.badRequest("invalid_phrases", "Between 1 and " + MAX_PHRASES + " phrases");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String p : phrases) {
            String v = p == null ? "" : p.strip();
            if (v.length() < 2 || v.length() > MAX_PHRASE_LENGTH) {
                throw ApiException.badRequest("invalid_phrases", "Each phrase has 2 to " + MAX_PHRASE_LENGTH + " characters");
            }
            out.add(v);
        }
        return new ArrayList<>(out);
    }

    public static Result mask(String text, List<String> phrases) {
        if (text == null || text.isEmpty()) {
            return new Result(text == null ? "" : text, 0);
        }
        if (phrases.isEmpty()) {
            return new Result(text, 0);
        }
        List<String> ordered = new ArrayList<>(phrases);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        // one pass over the text with longest-first alternatives: a mask is never re-matched by a shorter phrase
        String alternatives = String.join("|", ordered.stream().map(Pattern::quote).toList());
        Matcher m = Pattern.compile(alternatives, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        StringBuilder out = new StringBuilder();
        int count = 0;
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(MASK));
            count++;
        }
        m.appendTail(out);
        return new Result(out.toString(), count);
    }
}
