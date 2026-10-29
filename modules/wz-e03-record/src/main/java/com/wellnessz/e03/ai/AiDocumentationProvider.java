package com.wellnessz.e03.ai;

import java.util.List;
import java.util.Map;

/**
 * SPI for AI-assisted documentation (E03-F07): a provider turns a consented consultation transcript into a draft structured
 * note. No provider ships enabled – the choice between a locally hosted ambient scribe and a vendor service is the
 * evaluation spike E03-BL-018 (privacy, accuracy, clinician acceptance, DPIA). A provider is activated by implementing this
 * interface as a Spring bean and setting {@code wellnessz.record.ai.provider} to its {@link #name()}.
 * Implementations must not retain transcripts and must process data only in approved hosting locations.
 */
public interface AiDocumentationProvider {

    String name();

    Draft draft(DraftRequest request);

    /** What the provider may use: the encounter type, the template field keys it may fill, and the transcript. */
    record DraftRequest(String encounterType, int templateVersion, List<String> fieldKeys, String transcript) {
    }

    /** Draft content; never saved automatically – the clinician reviews, edits and saves it. */
    record Draft(Map<String, String> soap, Map<String, Object> answers) {
    }
}
