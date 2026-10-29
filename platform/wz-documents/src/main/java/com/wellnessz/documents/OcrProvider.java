package com.wellnessz.documents;

import java.util.Optional;

/**
 * SPI for optical character recognition of scanned images (E03-BL-016). The default deployment has no OCR engine;
 * {@link TesseractOcrProvider} activates when {@code wellnessz.documents.ocr.tesseract-path} is configured, and a cloud
 * OCR service (Azure AI Document Intelligence, Google Document AI) can be plugged in by providing another bean.
 */
public interface OcrProvider {

    Optional<String> extractText(byte[] image, String contentType);
}
