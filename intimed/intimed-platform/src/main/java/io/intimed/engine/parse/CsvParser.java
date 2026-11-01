package io.intimed.engine.parse;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** P2 vendor CSV export (RFC 4180 quoting). Each data row becomes one observation set; columns mapped by header name. */
public class CsvParser implements PayloadParser {
    @Override public String type() { return "CSV"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        char d = g.path("delimiter").asString(",").charAt(0);
        List<List<String>> rows = rows(payload, d);
        if (rows.size() < 2) throw new ParseException("CSV without data rows");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) idx.put(rows.get(0).get(i).trim(), i);
        List<Parsed> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() == 1 && row.get(0).isBlank()) continue;
            if (row.size() != rows.get(0).size()) throw new ParseException("CSV row " + r + " has " + row.size() + " columns, header has " + rows.get(0).size());
            Parsed p = new Parsed();
            g.path("identity").properties().forEach(e -> { Integer i = idx.get(e.getValue().asString()); if (i != null) p.id(e.getKey(), row.get(i)); });
            for (JsonNode f : g.path("fields")) {
                Integer i = idx.get(f.get("column").asString());
                if (i != null) p.val(f.get("key").asString(), row.get(i), f.path("unit").asString(null));
            }
            if (!p.values.isEmpty()) out.add(p);
        }
        if (out.isEmpty()) throw new ParseException("no mapped columns found");
        return out;
    }

    static List<List<String>> rows(String s, char d) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') q = false;
                else cell.append(c);
            } else if (c == '"') q = true;
            else if (c == d) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (q) throw new ParseException("unterminated quoted field");
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
