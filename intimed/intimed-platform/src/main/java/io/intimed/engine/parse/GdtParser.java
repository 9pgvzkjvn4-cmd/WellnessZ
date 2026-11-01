package io.intimed.engine.parse;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * P2 GDT 2.1/3.0 export (German device data exchange used by spirometers/ECG software): lines {@code LLLFFFFcontent}
 * with a 3-digit length that must match. Results are 8410 (test id) → 8420 (value) → 8421 (unit) groups; a field
 * mapping {@code "8410=FEV1"} selects the value of the group whose test id is FEV1.
 */
public class GdtParser implements PayloadParser {
    @Override public String type() { return "GDT"; }

    record Line(String field, String content) { }

    static List<Line> lines(String payload) {
        List<Line> out = new ArrayList<>();
        int n = 0;
        for (String raw : payload.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            n++;
            if (raw.isBlank()) continue;
            if (raw.length() < 7 || !raw.substring(0, 7).chars().allMatch(Character::isDigit)) throw new ParseException("GDT line " + n + " malformed");
            int len = Integer.parseInt(raw.substring(0, 3));
            if (len != raw.length() + 2) throw new ParseException("GDT line " + n + " length " + len + " does not match");
            out.add(new Line(raw.substring(3, 7), raw.substring(7)));
        }
        return out;
    }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        List<Line> ls = lines(payload);
        Parsed p = new Parsed();
        for (var e : g.path("identity").properties()) {
            String f = e.getValue().asString();
            ls.stream().filter(l -> l.field().equals(f)).findFirst().ifPresent(l -> p.id(e.getKey(), l.content()));
        }
        for (JsonNode f : g.path("fields")) {
            String[] sel = f.get("test").asString().split("=");
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).field().equals(sel[0]) && ls.get(i).content().equals(sel[1])) {
                    String v = null, u = null;
                    for (int j = i + 1; j < ls.size() && !ls.get(j).field().equals(sel[0]); j++) {
                        if (ls.get(j).field().equals("8420")) v = ls.get(j).content();
                        if (ls.get(j).field().equals("8421")) u = ls.get(j).content();
                    }
                    p.val(f.get("key").asString(), v, u == null ? f.path("unit").asString(null) : u);
                    break;
                }
            }
        }
        if (!ls.isEmpty() && !"8000".equals(ls.get(0).field())) throw new ParseException("GDT must start with record type 8000");
        if (p.values.isEmpty()) throw new ParseException("no mapped GDT results");
        return Parsed.one(p);
    }
}
