package com.wellnessz.e31.adapter.xml;

import java.io.StringWriter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * POCT1-A2 {@code ACK.R01}: {@code ACK.type_cd} AA (accepted, stored) or AE (error), echoing the message control id. Built as a
 * DOM and serialised by the JAXP transformer, so echoed values are always escaped.
 */
public final class Poct1aAck {

    private static final AtomicLong CONTROL = new AtomicLong(System.nanoTime() & 0xffffL);

    private Poct1aAck() {
    }

    public static String of(String code, String ackControlId, String note, Instant now) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = f.newDocumentBuilder().newDocument();
            Element root = doc.createElement("ACK.R01");
            doc.appendChild(root);
            Element hdr = child(doc, root, "HDR", null);
            child(doc, hdr, "HDR.control_id", String.valueOf(CONTROL.incrementAndGet()));
            child(doc, hdr, "HDR.version_id", "POCT1");
            child(doc, hdr, "HDR.creation_dttm", now.toString());
            Element ack = child(doc, root, "ACK", null);
            child(doc, ack, "ACK.type_cd", "AA".equals(code) ? "AA" : "AE");
            child(doc, ack, "ACK.ack_control_id", ackControlId == null ? "" : ackControlId);
            if (note != null && !"AA".equals(code)) {
                child(doc, child(doc, ack, "NTE", null), "NTE.text", note);
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.STANDALONE, "no");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("POCT1-A acknowledgement could not be built", e);
        }
    }

    private static Element child(Document doc, Element parent, String name, String value) {
        Element e = doc.createElement(name);
        if (value != null) {
            e.setAttribute("V", value);
        }
        parent.appendChild(e);
        return e;
    }
}
