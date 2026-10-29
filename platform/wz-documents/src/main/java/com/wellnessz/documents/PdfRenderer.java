package com.wellnessz.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders template documents to PDF with Apache PDFBox and extracts the text layer of uploaded PDFs. By default the
 * standard Helvetica font is used (Latin script); set {@code wellnessz.documents.font-path} to a Unicode TrueType font
 * (e.g. Noto Sans + Noto Naskh Arabic) to render Arabic or other scripts.
 */
@Component
public class PdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderer.class);
    private static final float MARGIN = 56;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final int MAX_PAGES_EXTRACTED = 50;

    private final String fontPath;

    public PdfRenderer(@Value("${wellnessz.documents.font-path:}") String fontPath) {
        this.fontPath = fontPath;
    }

    public record Rendered(byte[] pdf, String text) {
    }

    public record Section(String heading, String text) {
    }

    public Rendered render(String title, String documentNumber, int version, List<Section> sections, String footer) {
        StringBuilder plain = new StringBuilder(title).append('\n');
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular;
            PDFont bold;
            if (fontPath != null && !fontPath.isBlank() && Files.isReadable(Path.of(fontPath))) {
                regular = PDType0Font.load(doc, Path.of(fontPath).toFile());
                bold = regular;
            } else {
                regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            }
            doc.getDocumentInformation().setTitle(title);
            doc.getDocumentInformation().setCreator("WellnessZ OHCM");
            Writer w = new Writer(doc, regular, bold, footer);
            w.line("WellnessZ – Occupational Health", bold, 10, 0.35f);
            w.line(title, bold, 16, 0.05f);
            w.line(documentNumber + "  ·  version " + version, regular, 9, 0.35f);
            w.gap(10);
            for (Section s : sections) {
                if (s.heading() != null) {
                    w.paragraph(s.heading(), bold, 10);
                    plain.append(s.heading()).append('\n');
                }
                w.paragraph(s.text() == null ? "" : s.text(), regular, 11);
                plain.append(s.text()).append('\n');
                w.gap(6);
            }
            w.close();
            doc.save(out);
            return new Rendered(out.toByteArray(), plain.toString());
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    /** Text layer of an uploaded PDF (first 50 pages); empty for scanned images without text or unreadable files. */
    public String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), MAX_PAGES_EXTRACTED));
            String text = stripper.getText(doc);
            return text.length() > 200_000 ? text.substring(0, 200_000) : text;
        } catch (IOException | RuntimeException e) {
            log.info("No text layer extracted from uploaded PDF: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    /** Small layout helper: wraps paragraphs and starts new pages, with a confidentiality footer and page numbers. */
    private static final class Writer {
        private final PDDocument doc;
        private final PDFont regular;
        private final String footer;
        private final Map<PDFont, Map<Integer, Boolean>> glyphCache = new HashMap<>();
        private PDPageContentStream cs;
        private float y;
        private int pages;

        Writer(PDDocument doc, PDFont regular, PDFont bold, String footer) throws IOException {
            this.doc = doc;
            this.regular = regular;
            this.footer = footer;
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) {
                finishPage();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
            pages++;
        }

        void finishPage() throws IOException {
            cs.beginText();
            cs.setFont(regular, 8);
            cs.newLineAtOffset(MARGIN, 30);
            cs.showText(safe(regular, footer + "   –   page " + pages));
            cs.endText();
            cs.close();
        }

        void gap(float g) {
            y -= g;
        }

        void line(String text, PDFont font, float size, float after) throws IOException {
            ensure(size * 1.4f);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(safe(font, text));
            cs.endText();
            y -= size * 1.4f + size * after;
        }

        void paragraph(String text, PDFont font, float size) throws IOException {
            for (String raw : text.split("\\R")) {
                for (String l : wrap(font, size, safe(font, raw))) {
                    line(l, font, size, 0);
                }
            }
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN + 20) {
                newPage();
            }
        }

        private List<String> wrap(PDFont font, float size, String text) throws IOException {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > WIDTH && !current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            out.add(current.toString());
            return out;
        }

        /** Replaces characters the font cannot encode (e.g. Arabic with the standard font) instead of failing. */
        private String safe(PDFont font, String s) {
            Map<Integer, Boolean> cache = glyphCache.computeIfAbsent(font, f -> new HashMap<>());
            StringBuilder b = new StringBuilder(s.length());
            s.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) {
                    b.append(' ');
                    return;
                }
                boolean ok = cache.computeIfAbsent(cp, c -> {
                    try {
                        font.encode(new String(Character.toChars(c)));
                        return true;
                    } catch (IOException | IllegalArgumentException e) {
                        return false;
                    }
                });
                b.appendCodePoint(ok ? cp : '?');
            });
            return b.toString();
        }

        void close() throws IOException {
            finishPage();
        }
    }
}
