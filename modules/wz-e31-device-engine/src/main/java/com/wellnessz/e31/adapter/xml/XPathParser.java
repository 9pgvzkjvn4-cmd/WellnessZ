package com.wellnessz.e31.adapter.xml;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

/**
 * {@code XPATH} (vendor XML exports, e.g. GE MUSE resting ECG) and {@code POCT1A_XPATH} (CLSI POCT1-A2 OBS.R01 from data
 * managers). XPath 1.0 plus {@code string-join(path, 'separator')}. When {@code parse.records} names a node set, field
 * selectors without a leading {@code /} are evaluated relative to each record node and the first non-empty value wins – the
 * message is one panel (one record).
 */
public class XPathParser implements RecordParser {

    private static final Pattern STRING_JOIN = Pattern.compile("string-join\\((.+),\\s*'([^']*)'\\)");

    private final String type;

    public XPathParser(String type) {
        this.type = type;
    }

    @Override
    public String parseType() {
        return type;
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        Map<String, String> ns = ctx.pkg().parse().namespaces();
        Document doc = SafeXml.parse(ctx.body(), !ns.isEmpty());
        XPath xp = XPathFactory.newInstance().newXPath();
        if (!ns.isEmpty()) {
            xp.setNamespaceContext(new MapNamespaces(ns));
        }
        if ("POCT1A_XPATH".equals(type)) {
            String root = doc.getDocumentElement().getNodeName();
            if (!root.matches("[A-Z]{3}\\.R\\d{2}")) {
                throw EngineException.profile("Not a POCT1-A message (" + root + ")");
            }
            if (!"OBS.R01".equals(root)) {
                return List.of();
            }
        }
        NodeList records = null;
        String recordSel = ctx.pkg().parse().records();
        if (recordSel != null) {
            records = (NodeList) eval(xp, recordSel, doc, XPathConstants.NODESET);
        }
        NodeList recs = records;
        RecordAssembler.SelectorSource src = selector -> {
            if (selector.startsWith("/") || selector.startsWith("concat(") || selector.startsWith("string-join(") || recs == null) {
                return text(xp, selector, doc);
            }
            for (int i = 0; i < recs.getLength(); i++) {
                String v = text(xp, selector, recs.item(i));
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            return null;
        };
        return List.of(RecordAssembler.assemble(0, ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
    }

    static String text(XPath xp, String selector, Object context) {
        Matcher m = STRING_JOIN.matcher(selector.strip());
        if (m.matches()) {
            NodeList nodes = (NodeList) eval(xp, m.group(1), context, XPathConstants.NODESET);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                String t = nodes.item(i).getTextContent();
                if (t != null && !t.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(m.group(2));
                    }
                    sb.append(t.strip());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        String v = (String) eval(xp, selector, context, XPathConstants.STRING);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static Object eval(XPath xp, String expr, Object context, javax.xml.namespace.QName type) {
        try {
            return xp.evaluate(expr, context, type);
        } catch (XPathExpressionException e) {
            throw new EngineException("parse_failed", "Invalid XPath selector in the driver package", true);
        }
    }

    /** Message control id of a POCT1-A message ({@code HDR.control_id/@V}). */
    public static String controlId(byte[] xml) {
        try {
            Document doc = SafeXml.parse(xml, false);
            XPath xp = XPathFactory.newInstance().newXPath();
            return text(xp, "/*/HDR/HDR.control_id/@V", doc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * QC summary of a POCT1-A {@code QCN.R01}: control / reagent lot, lot expiry, outcome (PASS | FAIL) and time. Unknown
     * elements yield {@code null}; a result flagged abnormal ({@code A}, {@code AA}, {@code H}, {@code L}, {@code FAIL}) is FAIL.
     */
    public static Map<String, String> qcSummary(byte[] xml) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            Document doc = SafeXml.parse(xml, false);
            XPath xp = XPathFactory.newInstance().newXPath();
            out.put("lot", first(xp, doc, "//CTC/CTC.lot_number/@V", "//REG/REG.reagent_lot_number/@V", "//*[contains(local-name(),'lot_number')]/@V"));
            out.put("lotExpiry", first(xp, doc, "//CTC/CTC.expiration_date/@V", "//REG/REG.expiration_date/@V",
                    "//*[contains(local-name(),'expiration_date')]/@V"));
            out.put("performedAt", first(xp, doc, "//OBS/OBS.observation_dttm/@V", "/*/HDR/HDR.creation_dttm/@V"));
            String flag = first(xp, doc, "//OBS/OBS.qc_result_cd/@V", "//OBS/OBS.normal_flag/@V", "//OBS/OBS.interpretation_cd/@V");
            out.put("outcome", flag == null ? null : java.util.Set.of("A", "AA", "H", "L", "HH", "LL", "F", "FAIL", "FAILED")
                    .contains(flag.toUpperCase(java.util.Locale.ROOT)) ? "FAIL" : "PASS");
        } catch (RuntimeException e) {
            // unparseable – the summary stays empty and E32 ignores the event
        }
        return out;
    }

    private static String first(XPath xp, Document doc, String... selectors) {
        for (String s : selectors) {
            String v = text(xp, s, doc);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Root element name, e.g. {@code OBS.R01}; {@code null} if unparseable. */
    public static String rootName(byte[] xml) {
        try {
            Node n = SafeXml.parse(xml, false).getDocumentElement();
            return n.getNodeName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    record MapNamespaces(Map<String, String> ns) implements NamespaceContext {
        @Override
        public String getNamespaceURI(String prefix) {
            return ns.getOrDefault(prefix, javax.xml.XMLConstants.NULL_NS_URI);
        }

        @Override
        public String getPrefix(String uri) {
            return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).findFirst().orElse(null);
        }

        @Override
        public Iterator<String> getPrefixes(String uri) {
            return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).iterator();
        }
    }
}
