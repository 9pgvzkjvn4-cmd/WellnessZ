package io.intimed.engine.parse;

import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * XML payloads: P2 vendor exports (audiometry XML, MUSE ECG XML, PSG summaries) and P5 POCT1-A2 OBS messages.
 * Hardened against XXE / billion laughs: DTDs disallowed, external entities and XInclude off, secure processing on.
 */
public class XmlParser implements PayloadParser {
    private final String type;

    public XmlParser(String type) { this.type = type; }

    @Override public String type() { return type; }

    public static Document secureParse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ParseException("XML rejected: " + (e.getMessage() != null && e.getMessage().contains("DOCTYPE") ? "DOCTYPE not allowed" : "malformed"));
        }
    }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        Document doc = secureParse(payload.strip());
        XPath xp = XPathFactory.newInstance().newXPath();
        Parsed p = new Parsed();
        try {
            if (g.has("root") && !doc.getDocumentElement().getNodeName().equals(g.get("root").asString())) {
                throw new ParseException("unexpected root element " + doc.getDocumentElement().getNodeName());
            }
            for (var e : g.path("identity").properties()) p.id(e.getKey(), xp.evaluate(e.getValue().asString(), doc));
            for (var e : g.path("extras").properties()) { String v = xp.evaluate(e.getValue().asString(), doc); if (!v.isBlank()) p.extras.put(e.getKey(), v); }
            for (JsonNode f : g.path("fields")) {
                String v = xp.evaluate(f.get("xpath").asString(), doc);
                String u = f.has("unitXpath") ? xp.evaluate(f.get("unitXpath").asString(), doc) : f.path("unit").asString(null);
                p.val(f.get("key").asString(), v, u);
            }
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("XPath evaluation failed");
        }
        if (p.values.isEmpty()) throw new ParseException("no mapped values in XML");
        return Parsed.one(p);
    }
}
