package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.util.UUID;

/** Attachment metadata (no content). */
public record AttachmentMeta(UUID id, String fileName, String contentType, long size, Instant uploadedAt) {
}
