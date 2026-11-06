package io.intimed.commercial;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * White-label theming (IM-E09-BL-006): per-tenant branding (product name, primary / accent colour, logo, support e-mail
 * and URL). The console applies the colours as CSS custom properties through the CSSOM at load (no inline style
 * attributes – the CSP forbids them) and the capture web component reads the same {@code --im-*} tokens. Logos are data
 * URLs of at most 64 KiB: PNG (signature-checked) or SVG parsed with a hardened XML parser (no DOCTYPE / entities) and
 * refused if it contains script, event handlers, foreign content or external / javascript: references.
 */
@Service
public class BrandingService {
    public static final String KIND = "branding";
    public static final int MAX_LOGO_BYTES = 64 * 1024;
    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,180}\\.[A-Za-z]{2,24}");
    static final Pattern URL = Pattern.compile("https://[A-Za-z0-9.-]{1,200}(:\\d{2,5})?(/[A-Za-z0-9._~%/?#&=+-]{0,300})?");
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} .,&'()+_-]{1,60}");
    static final Set<String> FORBIDDEN_ELEMENTS = Set.of("script", "foreignobject", "iframe", "embed", "object", "audio", "video", "handler", "listener", "set", "animate");
    public static final ObjectNode DEFAULTS = Json.obj().put("productName", "IntiMed").put("primaryColor", "#0b5fff").put("accentColor", "#0846c0")
            .put("supportEmail", "").put("supportUrl", "");
    private final DocumentStore store;
    private final AuditService audit;

    public BrandingService(DocumentStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public ObjectNode get(String tenant) {
        ObjectNode b = (ObjectNode) DEFAULTS.deepCopy();
        store.get(tenant, KIND, "current").ifPresent(b::setAll);
        b.put("custom", store.get(tenant, KIND, "current").isPresent());
        return b;
    }

    public ObjectNode update(String tenant, JsonNode in, String actor) {
        if (in == null || !in.isObject()) throw ApiException.bad("invalid-branding", "branding must be an object");
        for (String k : in.propertyNames()) {
            if (!Set.of("productName", "primaryColor", "accentColor", "logo", "supportEmail", "supportUrl").contains(k)) throw ApiException.bad("unknown-field", "unknown branding field " + k);
        }
        ObjectNode cur = get(tenant);
        cur.remove("custom");
        ObjectNode next = (ObjectNode) cur.deepCopy();
        if (in.has("productName")) next.put("productName", require(in, "productName", NAME, "product name: 1–60 letters, digits and basic punctuation"));
        if (in.has("primaryColor")) next.put("primaryColor", require(in, "primaryColor", COLOR, "primaryColor must be #RRGGBB").toLowerCase(Locale.ROOT));
        if (in.has("accentColor")) next.put("accentColor", require(in, "accentColor", COLOR, "accentColor must be #RRGGBB").toLowerCase(Locale.ROOT));
        if (in.has("supportEmail")) next.put("supportEmail", optional(in, "supportEmail", EMAIL, "invalid support e-mail"));
        if (in.has("supportUrl")) next.put("supportUrl", optional(in, "supportUrl", URL, "support URL must be https://"));
        if (in.has("logo")) {
            String logo = in.get("logo").isNull() ? "" : in.get("logo").asString("");
            if (logo.isEmpty()) next.remove("logo");
            else { validateLogo(logo); next.put("logo", logo); }
        }
        next.put("updatedAt", Instant.now().toString()).put("updatedBy", actor);
        store.put(tenant, KIND, "current", next, "ACTIVE", null);
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("productName", next.path("productName").asString());
        det.put("primaryColor", next.path("primaryColor").asString());
        det.put("accentColor", next.path("accentColor").asString());
        det.put("logoSha256", next.has("logo") ? Hashing.sha256(next.get("logo").asString()).substring(0, 16) : "none");
        audit.record(tenant, actor, "tenant.branding", "tenant/" + tenant, det);
        return get(tenant);
    }

    private static String require(JsonNode in, String f, Pattern p, String msg) {
        String v = in.path(f).asString("");
        if (!p.matcher(v).matches()) throw ApiException.bad("invalid-" + f, msg);
        return v;
    }

    private static String optional(JsonNode in, String f, Pattern p, String msg) {
        String v = in.path(f).isNull() ? "" : in.path(f).asString("");
        if (!v.isEmpty() && !p.matcher(v).matches()) throw ApiException.bad("invalid-" + f, msg);
        return v;
    }

    /** Validates a logo data URL; throws {@link ApiException} (400) when it is not an acceptable PNG / SVG. */
    public static void validateLogo(String dataUrl) {
        if (dataUrl.length() > MAX_LOGO_BYTES * 4 / 3 + 64) throw ApiException.bad("logo-too-large", "logo exceeds 64 KiB");
        String prefix;
        if (dataUrl.startsWith("data:image/png;base64,")) prefix = "png";
        else if (dataUrl.startsWith("data:image/svg+xml;base64,")) prefix = "svg";
        else throw ApiException.bad("logo-type", "logo must be a base64 data URL of image/png or image/svg+xml");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)); }
        catch (IllegalArgumentException e) { throw ApiException.bad("logo-encoding", "logo is not valid base64"); }
        if (bytes.length == 0 || bytes.length > MAX_LOGO_BYTES) throw ApiException.bad("logo-too-large", "logo exceeds 64 KiB");
        if (prefix.equals("png")) {
            byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
            if (bytes.length < 8) throw ApiException.bad("logo-type", "not a PNG image");
            for (int i = 0; i < 8; i++) if (bytes[i] != sig[i]) throw ApiException.bad("logo-type", "not a PNG image");
            return;
        }
        validateSvg(bytes);
    }

    static void validateSvg(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) throw ApiException.bad("logo-unsafe", "SVG must not contain a DOCTYPE or entities");
        if (lower.contains("javascript:") || lower.contains("vbscript:") || lower.contains("<script") || lower.contains("@import"))
            throw ApiException.bad("logo-unsafe", "SVG must not contain script");
        org.w3c.dom.Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw ApiException.bad("logo-invalid", "SVG is not well-formed XML");
        }
        Element root = doc.getDocumentElement();
        if (!"svg".equalsIgnoreCase(root.getLocalName() == null ? root.getNodeName() : root.getLocalName())) throw ApiException.bad("logo-invalid", "root element must be <svg>");
        walk(root);
    }

    private static void walk(Node n) {
        if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) throw ApiException.bad("logo-unsafe", "SVG must not contain processing instructions");
        if (n instanceof Element e) {
            String name = (e.getLocalName() == null ? e.getNodeName() : e.getLocalName()).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_ELEMENTS.contains(name)) throw ApiException.bad("logo-unsafe", "SVG must not contain <" + name + ">");
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String an = (a.getLocalName() == null ? a.getNodeName() : a.getLocalName()).toLowerCase(Locale.ROOT);
                String av = a.getNodeValue().trim().toLowerCase(Locale.ROOT);
                if (an.startsWith("on")) throw ApiException.bad("logo-unsafe", "SVG must not contain event handler attributes");
                if ((an.equals("href") || an.equals("src")) && !(av.startsWith("#") || av.startsWith("data:image/png") || av.startsWith("data:image/jpeg")))
                    throw ApiException.bad("logo-unsafe", "SVG must not reference external resources");
                if (av.contains("url(") && !av.matches(".*url\\(\\s*['\"]?#[^)]*\\).*")) throw ApiException.bad("logo-unsafe", "SVG must not reference external resources");
            }
        }
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) walk(kids.item(i));
    }

    /** CSS custom properties for the console and the capture component (colours are pattern-validated on write). */
    public String css(String tenant) {
        ObjectNode b = get(tenant);
        String p = safeColor(b.path("primaryColor").asString(), "#0b5fff"), a = safeColor(b.path("accentColor").asString(), "#0846c0");
        return ":root{--brand:" + p + ";--brand-2:" + a + ";--im-primary:" + p + ";--im-accent:" + a + "}\n";
    }

    static String safeColor(String v, String def) { return v != null && COLOR.matcher(v).matches() ? v : def; }
}
