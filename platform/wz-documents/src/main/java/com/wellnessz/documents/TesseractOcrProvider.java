package com.wellnessz.documents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local OCR through the Tesseract command line (no data leaves the host). The binary path is fixed configuration, the
 * input is a private temporary file and the process is bounded by a timeout.
 */
@Component
@ConditionalOnProperty(name = "wellnessz.documents.ocr.tesseract-path")
public class TesseractOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrProvider.class);

    private final String binary;
    private final String languages;

    public TesseractOcrProvider(@Value("${wellnessz.documents.ocr.tesseract-path}") String binary,
                                @Value("${wellnessz.documents.ocr.languages:eng}") String languages) {
        if (!languages.matches("[a-z_+]{3,40}")) {
            throw new IllegalArgumentException("Invalid OCR language list");
        }
        this.binary = binary;
        this.languages = languages;
    }

    @Override
    public Optional<String> extractText(byte[] image, String contentType) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("wz-ocr-", contentType.endsWith("png") ? ".png" : ".jpg");
            Files.write(tmp, image);
            Process p = new ProcessBuilder(List.of(binary, tmp.toString(), "stdout", "-l", languages)).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readNBytes(1_000_000);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(new String(out, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            log.warn("OCR failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    log.debug("temporary OCR file not deleted");
                }
            }
        }
    }
}
