package com.wellnessz.e02.triage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wellnessz.casekernel.model.Urgency;

import tools.jackson.databind.JsonNode;

/**
 * Server-side traversal of a triage protocol (E02-BL-002). The client only sends answers; urgency is always computed
 * here. Red-flag questions answered "yes" are hard stops (EMERGENCY). Rule grammar: {@code >=n <=n >n <n ==n =n !=n},
 * bare {@code n}, inclusive ranges {@code a..b} / {@code a-b}, {@code ==true/false}, {@code ==VALUE}; {@code else: true}.
 */
public final class ProtocolEvaluator {

    private ProtocolEvaluator() {
    }

    public record Result(Urgency urgency, List<String> redFlags, List<String> path) {
    }

    public static final class IncompleteException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IncompleteException(String message) {
            super(message);
        }
    }

    public static Result evaluate(JsonNode protocol, Map<String, Object> answers) {
        Map<String, JsonNode> questions = new HashMap<>();
        protocol.path("questions").forEach(q -> questions.put(q.path("id").asString(), q));
        String current = protocol.path("start").asString();
        List<String> path = new ArrayList<>();
        List<String> redFlags = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (true) {
            JsonNode q = questions.get(current);
            if (q == null) {
                throw new IllegalStateException("Protocol references unknown question " + current);
            }
            if (!seen.add(current) || seen.size() > 100) {
                throw new IllegalStateException("Protocol contains a cycle");
            }
            path.add(current);
            Object answer = answers == null ? null : answers.get(current);
            if (answer == null || String.valueOf(answer).isBlank()) {
                throw new IncompleteException("Answer required for question " + current);
            }
            String type = q.path("type").asString("BOOLEAN");
            if (q.path("redFlag").asBoolean(false) && truthy(answer)) {
                redFlags.add(current);
                return new Result(Urgency.EMERGENCY, redFlags, path);
            }
            JsonNode branch = null;
            if ("BOOLEAN".equals(type)) {
                branch = truthy(answer) ? q.get("onYes") : q.get("onNo");
            } else {
                if ("NUMBER".equals(type)) {
                    double v = number(answer);
                    if (q.has("min") && v < q.get("min").asDouble() || q.has("max") && v > q.get("max").asDouble()) {
                        throw new IncompleteException("Answer out of range for question " + current);
                    }
                } else if ("SELECT".equals(type) && q.has("options")) {
                    boolean valid = false;
                    for (JsonNode o : q.get("options")) {
                        valid |= o.path("value").asString().equals(String.valueOf(answer));
                    }
                    if (!valid) {
                        throw new IncompleteException("Invalid option for question " + current);
                    }
                }
                for (JsonNode rule : q.path("rules")) {
                    if (rule.path("else").asBoolean(false) || matches(rule.path("if").asString(""), answer)) {
                        branch = rule;
                        break;
                    }
                }
            }
            if (branch == null || (!branch.has("outcome") && !branch.has("next"))) {
                branch = q.has("next") ? q : null;
            }
            if (branch == null) {
                throw new IllegalStateException("No branch for question " + current);
            }
            if (branch.has("outcome")) {
                return new Result(Urgency.valueOf(branch.get("outcome").asString()), redFlags, path);
            }
            current = branch.get("next").asString();
        }
    }

    static boolean matches(String cond, Object answer) {
        String c = cond.strip();
        if (c.isEmpty()) {
            return false;
        }
        String[] ops = {">=", "<=", "!=", "==", ">", "<", "="};
        for (String op : ops) {
            if (c.startsWith(op)) {
                String rhs = c.substring(op.length()).strip();
                return compare(op, answer, rhs);
            }
        }
        if (c.matches("-?[0-9.]+\\s*(\\.\\.|-)\\s*-?[0-9.]+")) {
            String[] parts = c.split("\\.\\.|(?<=[0-9])-");
            double v = number(answer);
            return v >= Double.parseDouble(parts[0].strip()) && v <= Double.parseDouble(parts[1].strip());
        }
        return compare("==", answer, c);
    }

    private static boolean isNumber(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        boolean digits = false;
        boolean dot = false;
        for (; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits = true;
            } else if (ch == '.' && digits && !dot && i + 1 < s.length()) {
                dot = true;
            } else {
                return false;
            }
        }
        return digits;
    }

    private static boolean compare(String op, Object answer, String rhs) {
        if (isNumber(rhs)) {
            double v = number(answer);
            double r = Double.parseDouble(rhs);
            return switch (op) {
                case ">=" -> v >= r;
                case "<=" -> v <= r;
                case ">" -> v > r;
                case "<" -> v < r;
                case "!=" -> v != r;
                default -> v == r;
            };
        }
        boolean eq = rhs.equalsIgnoreCase("true") || rhs.equalsIgnoreCase("false")
                ? truthy(answer) == Boolean.parseBoolean(rhs.toLowerCase(Locale.ROOT))
                : rhs.equals(String.valueOf(answer));
        return "!=".equals(op) != eq;
    }

    static boolean truthy(Object answer) {
        return answer instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(answer)) || "yes".equalsIgnoreCase(String.valueOf(answer));
    }

    static double number(Object answer) {
        if (answer instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(answer));
        } catch (NumberFormatException e) {
            throw new IncompleteException("Numeric answer expected");
        }
    }
}
