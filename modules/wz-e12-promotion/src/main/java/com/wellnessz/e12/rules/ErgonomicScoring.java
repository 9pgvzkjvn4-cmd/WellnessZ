package com.wellnessz.e12.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/**
 * Ergonomic checklist scoring (E12-F03): every item of the template is scored 0 (no risk) … 3 (high risk); the risk score is
 * the weighted sum as a percentage of the weighted maximum (0–100). RAG: below {@code amberFrom} GREEN, below {@code redFrom}
 * AMBER, otherwise RED; any heavily weighted item (weight ≥ 2) scored 3 lifts GREEN to AMBER. Pure – unit tested.
 */
public final class ErgonomicScoring {

    public static final int MAX_ITEM_SCORE = 3;

    private ErgonomicScoring() {
    }

    public record Item(String code, String label, int weight) {
    }

    public record Score(int riskScore, String rag) {
    }

    public static Score score(List<Item> items, Map<String, Integer> checklist, int amberFrom, int redFrom) {
        if (checklist == null || checklist.isEmpty()) {
            throw unprocessable("checklist_incomplete", "Every checklist item must be scored");
        }
        Set<String> known = new HashSet<>();
        items.forEach(i -> known.add(i.code()));
        for (Map.Entry<String, Integer> e : checklist.entrySet()) {
            if (!known.contains(e.getKey())) {
                throw unprocessable("unknown_checklist_item", "Checklist item '" + e.getKey() + "' is not part of this template");
            }
            if (e.getValue() == null || e.getValue() < 0 || e.getValue() > MAX_ITEM_SCORE) {
                throw unprocessable("invalid_item_score", "Checklist items are scored 0 to 3");
            }
        }
        long sum = 0;
        long max = 0;
        boolean severeItem = false;
        for (Item i : items) {
            Integer s = checklist.get(i.code());
            if (s == null) {
                throw unprocessable("checklist_incomplete", "Every checklist item must be scored");
            }
            sum += (long) s * i.weight();
            max += (long) MAX_ITEM_SCORE * i.weight();
            severeItem |= s == MAX_ITEM_SCORE && i.weight() >= 2;
        }
        int risk = max == 0 ? 0 : (int) Math.round(100.0 * sum / max);
        String rag = risk >= redFrom ? "RED" : risk >= amberFrom ? "AMBER" : "GREEN";
        if ("GREEN".equals(rag) && severeItem) {
            rag = "AMBER";
        }
        return new Score(risk, rag);
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
