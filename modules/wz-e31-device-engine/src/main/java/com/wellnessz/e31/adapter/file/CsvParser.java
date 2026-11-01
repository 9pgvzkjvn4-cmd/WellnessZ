package com.wellnessz.e31.adapter.file;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

/**
 * {@code CSV_COLUMNS}: vendor CSV exports and database views exported as CSV by the Device Host agent (RFC 4180 quoting,
 * delimiter from {@code protocol.csvDelimiter}). The first line is the header; selectors are header names or 1-based
 * column indexes. Each data row is one record.
 */
public class CsvParser implements RecordParser {

    @Override
    public String parseType() {
        return "CSV_COLUMNS";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        String text = new String(ctx.body(), ctx.charset(StandardCharsets.UTF_8));
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        String d = ctx.pkg().protocolText("csvDelimiter");
        char delimiter = d == null || d.isEmpty() ? ',' : "\\t".equals(d) ? '\t' : d.charAt(0);
        List<List<String>> rows = rows(text, delimiter);
        if (rows.size() < 2) {
            throw EngineException.parse("CSV export without data rows");
        }
        List<String> header = rows.get(0);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).strip(), i);
        }
        List<ParsedRecord> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            if (row.size() != header.size()) {
                throw EngineException.parse("CSV row " + r + " has " + row.size() + " columns, header has " + header.size());
            }
            RecordAssembler.SelectorSource src = selector -> {
                Integer i = index.get(selector);
                if (i == null && selector.matches("\\d{1,3}")) {
                    i = Integer.parseInt(selector) - 1;
                }
                return i == null || i < 0 || i >= row.size() ? null : row.get(i);
            };
            out.add(RecordAssembler.assemble(out.size(), ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
        }
        return out;
    }

    /** RFC 4180 tokenizer (quoted fields, doubled quotes, CR/LF/CRLF line ends, embedded line breaks in quotes). */
    public static List<List<String>> rows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !cell.isEmpty()) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw EngineException.parse("CSV export has an unterminated quoted field");
        }
        if (any || !cell.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
